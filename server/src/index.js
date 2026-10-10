import { createServer as createHttpServer } from 'node:http'
import { createServer as createHttpsServer } from 'node:https'
import { readFileSync, existsSync } from 'node:fs'
import { randomUUID } from 'node:crypto'

import { WebSocketServer } from './ws-server.js'
import { DeviceStore } from './store.js'
import { buildIceServers } from './turn.js'
import { FcmSender, NullFcmSender } from './fcm.js'

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

const PORT = Number(process.env.PORT ?? 8443)
const PUBLIC_HOST = process.env.PUBLIC_HOST ?? 'localhost'
const HOME_SECRET = process.env.HOME_SECRET
const TURN_SECRET = process.env.TURN_SECRET
const SERVICE_ACCOUNT = process.env.FCM_SERVICE_ACCOUNT ?? ''
const STORE_PATH = process.env.STORE_PATH ?? '/var/lib/intercom/devices.json'
const SSL_CERT = process.env.SSL_CERT ?? ''
const SSL_KEY = process.env.SSL_KEY ?? ''

/**
 * How long the TV rings phones before giving up. You chose: stop, no PSTN
 * fallback. Overridable only so the test suite doesn't have to wait 45s.
 */
const RING_TIMEOUT_MS = Number(process.env.RING_TIMEOUT_MS ?? 45_000)
/** Dead-socket detection. Mobile networks drop sockets without a FIN constantly. */
const HEARTBEAT_MS = 25_000
/**
 * No fixed number of people in a call: the apps send smaller pictures as it
 * grows. MAX_CALL_MEMBERS sets a ceiling, for a TV too slow for a big one.
 */
const MAX_MEMBERS = Number(process.env.MAX_CALL_MEMBERS) || 0

if (!HOME_SECRET || HOME_SECRET.length < 20) {
  console.error('FATAL: HOME_SECRET must be set to a long random string (see deploy/setup.sh)')
  process.exit(1)
}
if (!TURN_SECRET) {
  console.error('FATAL: TURN_SECRET must match static-auth-secret in turnserver.conf')
  process.exit(1)
}

const store = new DeviceStore(STORE_PATH)
const fcm = SERVICE_ACCOUNT && existsSync(SERVICE_ACCOUNT)
  ? new FcmSender(SERVICE_ACCOUNT)
  : new NullFcmSender()

// ---------------------------------------------------------------------------
// Live state
// ---------------------------------------------------------------------------

/** deviceId -> WebSocket, for devices connected right now. */
const sockets = new Map()

/**
 * The system has exactly one TV, so at most one call can be in flight; a phone
 * that calls while one is on joins it. Keeping that as an invariant removes a
 * whole class of race conditions (a stale accept landing after a hangup).
 */
let activeCall = null

function send (ws, type, payload = {}) {
  if (!ws || ws.readyState !== 'open') return false
  return ws.send(JSON.stringify({ type, ...payload }))
}

function sendTo (deviceId, type, payload = {}) {
  return send(sockets.get(deviceId), type, payload)
}

/** Who is online, and who is in a call right now - so a phone can offer to join it. */
function broadcastPresence () {
  const online = [...sockets.keys()]
  const roster = [...store.devices.values()].map(d => ({
    deviceId: d.deviceId,
    role: d.role,
    displayName: d.displayName,
    online: online.includes(d.deviceId)
  }))
  const call = activeCall
    ? { callId: activeCall.callId, active: activeCall.active, members: activeCall.members }
    : null
  for (const ws of sockets.values()) send(ws, 'presence', { devices: roster, call })
}

function clearCall (reason) {
  if (!activeCall) return
  clearTimeout(activeCall.timer)
  console.log(`[call] ${activeCall.callId} ended (${reason})`)
  activeCall = null
  broadcastPresence()
}

// ---------------------------------------------------------------------------
// Call flow
//
// A call is always with the TV, and several phones can be in it at once. The TV
// rings every phone and each one that answers joins; a phone calls the TV, and
// others can join it. Every member connects directly to every other member (a
// mesh), so the server only has to say who is in the call and, for each pair,
// which side makes the WebRTC offer: the one that joined later.
// ---------------------------------------------------------------------------

/** Targets that are still being rung: not answered, not declined, not timed out. */
function ringingIds (call) {
  if (!call.ringing) return []
  return call.targetIds.filter(id => !call.members.includes(id) && !call.rejectedBy.has(id))
}

/** True only when MAX_CALL_MEMBERS is set and the call has reached it. */
function isFull (call) {
  return MAX_MEMBERS > 0 && call.members.length >= MAX_MEMBERS
}

function roleOf (deviceId) {
  return store.get(deviceId)?.role
}

async function startCall (caller, { mode = 'normal', fullScreenRemote = false }) {
  if (activeCall) {
    // Pressing "call" while the family is already talking to grandpa joins in.
    if (caller.role === 'phone') return joinCall(caller)
    sendTo(caller.deviceId, 'error', { code: 'busy', message: 'A call is already in progress' })
    return
  }

  // The TV rings every phone; a phone rings the TV.
  const targets = caller.role === 'tv' ? store.byRole('phone') : store.byRole('tv')
  if (targets.length === 0) {
    sendTo(caller.deviceId, 'error', { code: 'no_targets', message: 'Nothing registered to call' })
    return
  }

  const callId = randomUUID()
  const now = Date.now()
  activeCall = {
    callId,
    callerId: caller.deviceId,
    callerRole: caller.role,
    targetIds: targets.map(t => t.deviceId),
    // Everyone in the call, in the order they joined.
    members: [caller.deviceId],
    rejectedBy: new Set(),
    // Someone on the other side has answered.
    active: false,
    // Targets that have not answered are still being rung.
    ringing: true,
    mode,
    fullScreenRemote,
    startedAt: now,
    ringDeadline: now + RING_TIMEOUT_MS,
    timer: setTimeout(() => onRingTimeout(callId), RING_TIMEOUT_MS)
  }

  console.log(`[call] ${callId} ${caller.displayName} (${caller.role}) -> ${targets.length} target(s), mode=${mode}`)
  sendTo(caller.deviceId, 'call-started', { callId, targets: activeCall.targetIds, ringTimeoutMs: RING_TIMEOUT_MS })
  broadcastPresence()

  for (const target of targets) {
    // Ring over the open socket when we have one - it is instant.
    const delivered = sendTo(target.deviceId, 'incoming-call', incomingCallPayload(activeCall))

    // And push regardless, because a phone with a live socket can still have a
    // screen off and a user who needs the ringtone. The app de-duplicates on callId.
    if (target.role === 'phone') {
      await fcm.sendCallInvite({
        token: target.fcmToken,
        callId,
        fromName: caller.displayName,
        fromDeviceId: caller.deviceId,
        mode
      })
    } else if (!delivered) {
      console.warn(`[call] ${callId}: TV ${target.deviceId} is offline, cannot ring it`)
    }
  }
}

/** A phone asking to call while a call is already on: it joins that one. */
function joinCall (device) {
  const call = activeCall
  if (call.members.includes(device.deviceId)) {
    sendTo(device.deviceId, 'error', { code: 'busy', message: 'Already in this call' })
    return
  }
  if (ringingIds(call).includes(device.deviceId)) return acceptCall(device, call.callId)
  if (isFull(call)) {
    sendTo(device.deviceId, 'error', { code: 'full', message: `This call is limited to ${MAX_MEMBERS} devices` })
    return
  }

  call.members.push(device.deviceId)
  call.rejectedBy.delete(device.deviceId)
  console.log(`[call] ${call.callId} joined by ${device.displayName}`)

  // Before the TV answers this waits with the others; after, it connects.
  sendTo(device.deviceId, 'call-started', {
    callId: call.callId,
    targets: call.targetIds,
    ringTimeoutMs: Math.max(0, call.ringDeadline - Date.now())
  })
  if (call.active) sendPeers(call)
  broadcastPresence()
}

function onRingTimeout (callId) {
  const call = activeCall
  if (!call || call.callId !== callId || !call.ringing) return

  for (const id of ringingIds(call)) {
    sendTo(id, 'call-cancelled', { callId, reason: 'timeout' })
    const device = store.get(id)
    if (device?.role === 'phone') fcm.sendCallCancelled({ token: device.fcmToken, callId, reason: 'timeout' })
  }

  if (call.active) {
    // The call goes on; the phones that never answered stop ringing.
    call.ringing = false
    console.log(`[call] ${callId} stopped ringing the phones that did not answer`)
    return
  }
  console.log(`[call] ${callId} timed out after ${RING_TIMEOUT_MS / 1000}s`)
  for (const id of call.members) sendTo(id, 'call-timeout', { callId })
  clearCall('timeout')
}

function acceptCall (device, callId) {
  const call = activeCall
  if (!call || call.callId !== callId) {
    sendTo(device.deviceId, 'call-cancelled', { callId, reason: 'gone' })
    return
  }
  if (call.members.includes(device.deviceId)) return
  if (!ringingIds(call).includes(device.deviceId)) {
    // Answering just after the ring ran out still gets a phone into a call
    // that is going on. A second TV is never let in: the call has one.
    if (device.role === 'phone' && call.active) return joinCall(device)
    const reason = call.targetIds.includes(device.deviceId) && call.active ? 'answered_elsewhere' : 'gone'
    sendTo(device.deviceId, 'call-cancelled', { callId, reason })
    return
  }
  if (isFull(call)) {
    sendTo(device.deviceId, 'call-cancelled', { callId, reason: 'full' })
    return
  }

  call.members.push(device.deviceId)
  call.active = true
  if (device.role === 'tv') {
    // A phone called the TV and a TV answered: any other TV stands down.
    for (const id of ringingIds(call)) sendTo(id, 'call-cancelled', { callId, reason: 'answered_elsewhere' })
    call.ringing = false
    clearTimeout(call.timer)
  }
  console.log(`[call] ${callId} answered by ${device.displayName} (${call.members.length} in the call)`)

  sendPeers(call)
  broadcastPresence()
}

/**
 * A target turning the call down. One phone declining does not stop the others
 * ringing; an unanswered call only ends once every target has declined.
 */
function rejectCall (device, callId) {
  const call = activeCall
  if (!call || call.callId !== callId) return
  if (!ringingIds(call).includes(device.deviceId)) return

  call.rejectedBy.add(device.deviceId)
  console.log(`[call] ${callId} declined by ${device.displayName}`)
  sendTo(call.callerId, 'call-rejected-by', { callId, deviceId: device.deviceId })

  if (!call.active && call.targetIds.every(id => call.rejectedBy.has(id))) {
    closeCall(device.deviceId, 'declined')
  }
}

/**
 * Leaving a call. Only members can leave; a target that is still ringing is
 * declining, and anyone else has no say at all - otherwise one decline, or a
 * late cancel push, ends a call someone else is in. The call carries on while
 * the TV and at least one phone are still in it.
 */
function endCall (device, callId, reason) {
  const call = activeCall
  if (!call || call.callId !== callId) return
  if (!call.members.includes(device.deviceId)) {
    rejectCall(device, callId)
    return
  }

  const remaining = call.members.filter(id => id !== device.deviceId)
  const roles = remaining.map(roleOf)
  const over = call.active
    ? !roles.includes('tv') || !roles.includes('phone')
    : device.role === 'tv' || remaining.length === 0
  if (over) {
    closeCall(device.deviceId, reason)
    return
  }

  call.members = remaining
  console.log(`[call] ${callId} left by ${device.displayName} (${reason}), ${remaining.length} still in it`)
  if (call.active) sendPeers(call)
  broadcastPresence()
}

/** Tell everyone else in the active call that it is over, then forget it. */
function closeCall (endedById, reason) {
  const call = activeCall
  const ringing = ringingIds(call)
  for (const id of new Set([...call.members, ...call.targetIds])) {
    if (id === endedById) continue
    sendTo(id, 'call-cancelled', { callId: call.callId, reason })
    // A phone still ringing may be asleep, so it hears by push too.
    const other = store.get(id)
    if (other?.role === 'phone' && ringing.includes(id)) {
      fcm.sendCallCancelled({ token: other.fcmToken, callId: call.callId, reason })
    }
  }
  clearCall(reason)
}

/**
 * Tell every member who else is in the call. Each member connects to each peer
 * listed and drops any it is connected to that is no longer listed; `offer`
 * says which side of each pair starts.
 */
function sendPeers (call) {
  for (const [index, id] of call.members.entries()) {
    const peers = []
    for (const [peerIndex, peerId] of call.members.entries()) {
      if (peerId === id) continue
      const peer = store.get(peerId)
      peers.push({
        peerId,
        peerName: peer?.displayName ?? 'Someone',
        role: peer?.role ?? 'phone',
        offer: index > peerIndex
      })
    }
    sendTo(id, 'call-peers', {
      callId: call.callId,
      mode: call.mode,
      fullScreenRemote: call.fullScreenRemote,
      peers
    })
  }
}

function incomingCallPayload (call) {
  return {
    callId: call.callId,
    from: call.callerId,
    fromName: store.get(call.callerId)?.displayName ?? 'TV',
    mode: call.mode,
    fullScreenRemote: call.fullScreenRemote
  }
}

// ---------------------------------------------------------------------------
// WebSocket plumbing
// ---------------------------------------------------------------------------

const useTls = SSL_CERT && SSL_KEY && existsSync(SSL_CERT) && existsSync(SSL_KEY)
const httpServer = useTls
  ? createHttpsServer({ cert: readFileSync(SSL_CERT), key: readFileSync(SSL_KEY) })
  : createHttpServer()

// A plain health endpoint, handy for checking the VM is alive from a browser.
httpServer.on('request', (req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ ok: true, devices: store.devices.size, online: sockets.size, call: activeCall?.callId ?? null }))
  } else {
    res.writeHead(404).end()
  }
})

const wss = new WebSocketServer({ server: httpServer, path: '/ws' })

wss.on('connection', (ws, req) => {
  ws.isAlive = true
  ws.device = null
  ws.on('pong', () => { ws.isAlive = true })

  const peer = req.socket.remoteAddress
  console.log(`[ws] connection from ${peer}`)

  // A client that never registers is a port scanner. Drop it.
  const registrationDeadline = setTimeout(() => {
    if (!ws.device) {
      console.log(`[ws] dropping unregistered connection from ${peer}`)
      ws.close(4001, 'register first')
    }
  }, 10_000)

  ws.on('message', async raw => {
    let msg
    try {
      msg = JSON.parse(raw.toString())
    } catch {
      return send(ws, 'error', { code: 'bad_json', message: 'Message was not JSON' })
    }

    // --- registration is the only message allowed before authentication ---
    if (msg.type === 'register') {
      if (msg.secret !== HOME_SECRET) {
        console.warn(`[ws] bad secret from ${peer}`)
        return ws.close(4003, 'unauthorised')
      }
      if (!msg.deviceId || !['tv', 'phone'].includes(msg.role)) {
        return send(ws, 'error', { code: 'bad_register', message: 'deviceId and role are required' })
      }

      const device = store.upsert({
        deviceId: msg.deviceId,
        role: msg.role,
        displayName: msg.displayName,
        fcmToken: msg.fcmToken
      })

      // Replace any previous socket for this device (app restart, network flap).
      const previous = sockets.get(device.deviceId)
      if (previous && previous !== ws) previous.close(4000, 'replaced by newer connection')

      ws.device = device
      sockets.set(device.deviceId, ws)
      clearTimeout(registrationDeadline)

      console.log(`[ws] registered ${device.displayName} (${device.role}/${device.deviceId.slice(0, 8)})`)
      send(ws, 'registered', {
        deviceId: device.deviceId,
        ...buildIceServers({ host: PUBLIC_HOST, turnSecret: TURN_SECRET })
      })
      broadcastPresence()

      // A device coming online mid-call needs to know about it.
      if (activeCall && ringingIds(activeCall).includes(device.deviceId)) {
        send(ws, 'incoming-call', incomingCallPayload(activeCall))
      }
      return
    }

    if (!ws.device) return send(ws, 'error', { code: 'unregistered', message: 'Send register first' })
    const device = ws.device

    switch (msg.type) {
      case 'call-request':
        await startCall(device, { mode: msg.mode, fullScreenRemote: !!msg.fullScreenRemote })
        break

      case 'call-accept':
        acceptCall(device, msg.callId)
        break

      case 'call-reject':
        rejectCall(device, msg.callId)
        break

      case 'hangup':
        endCall(device, msg.callId, msg.reason ?? 'hangup')
        break

      case 'signal': {
        // Opaque relay for SDP offers, answers and ICE candidates. The server
        // deliberately does not parse these - it only needs to know where to
        // put them. Only members of an answered call may address each other.
        if (!msg.to || !activeCall || activeCall.callId !== msg.callId || !activeCall.active) break
        const { members } = activeCall
        if (!members.includes(device.deviceId) || !members.includes(msg.to)) break
        sendTo(msg.to, 'signal', { callId: msg.callId, from: device.deviceId, payload: msg.payload })
        break
      }

      case 'refresh-ice':
        // TURN credentials expire; a long-lived TV app asks for new ones.
        send(ws, 'ice-servers', buildIceServers({ host: PUBLIC_HOST, turnSecret: TURN_SECRET }))
        break

      case 'update-token':
        store.upsert({ ...device, fcmToken: msg.fcmToken })
        break

      case 'ping':
        send(ws, 'pong', { t: Date.now() })
        break

      default:
        send(ws, 'error', { code: 'unknown_type', message: `Unknown message type: ${msg.type}` })
    }
  })

  ws.on('close', (code, reason) => {
    clearTimeout(registrationDeadline)
    const device = ws.device
    if (!device) return
    if (sockets.get(device.deviceId) === ws) sockets.delete(device.deviceId)
    console.log(`[ws] ${device.displayName} disconnected (${code} ${reason})`)

    // A member that vanishes leaves the call, rather than leaving the others
    // staring at a frozen frame. A replaced socket is not a departure.
    if (activeCall && activeCall.members.includes(device.deviceId) && !sockets.has(device.deviceId)) {
      endCall(device, activeCall.callId, 'peer_disconnected')
    }
    broadcastPresence()
  })

  ws.on('error', err => console.error(`[ws] socket error: ${err.message}`))
})

// Mobile networks drop TCP connections silently. Without this the server would
// happily "ring" a socket that died twenty minutes ago.
setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      console.log('[ws] terminating unresponsive socket')
      ws.terminate()
      continue
    }
    ws.isAlive = false
    ws.ping()
  }
}, HEARTBEAT_MS)

httpServer.listen(PORT, () => {
  console.log(`[server] listening on ${useTls ? 'wss' : 'ws'}://${PUBLIC_HOST}:${PORT}/ws`)
  if (!useTls) console.warn('[server] TLS is OFF - fine for LAN testing, not for the internet')
})

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    console.log(`[server] ${signal}, shutting down`)
    for (const ws of wss.clients) ws.close(1001, 'server restarting')
    httpServer.close(() => process.exit(0))
    setTimeout(() => process.exit(0), 3000)
  })
}
