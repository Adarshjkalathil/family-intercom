import { DurableObject } from 'cloudflare:workers'
import { FcmSender, NullFcmSender } from './fcm.js'
import { buildIceServers, turnError } from './turn.js'

/**
 * The signalling server, as a Cloudflare Worker.
 *
 * Speaks exactly the protocol of server/src/index.js - the apps cannot tell the
 * two apart - but runs on Cloudflare's free plan, so there is no VM to rent,
 * patch or keep alive, and no card needed to sign up.
 *
 * The whole household is one Durable Object holding every device's socket
 * through the WebSocket Hibernation API. Between messages the object is evicted
 * from memory and costs nothing, while Cloudflare keeps the sockets open and
 * answers the apps' ping frames itself. The price of that is that nothing may
 * live only in memory: device records and the active call are in the object's
 * SQLite database, each socket's identity is in its attachment, and timers are
 * a storage alarm instead of setTimeout.
 */

const DEFAULT_RING_TIMEOUT_MS = 45_000
/** A client that never registers is a port scanner. Drop it. */
const REGISTER_DEADLINE_MS = 10_000
/** WebSocket readyState for an open connection. */
const OPEN = 1

export default {
  async fetch (request, env) {
    const { pathname } = new URL(request.url)
    const known = pathname === '/ws' || pathname === '/health' ||
      pathname === '/devices' || pathname.startsWith('/devices/')
    if (!known) {
      return new Response('Not found', { status: 404 })
    }
    // One TV, one household, one object - so every request goes to the same one.
    return env.INTERCOM.get(env.INTERCOM.idFromName('home')).fetch(request)
  }
}

export class Intercom extends DurableObject {
  constructor (ctx, env) {
    super(ctx, env)
    this.sql = ctx.storage.sql
    this.sql.exec(`CREATE TABLE IF NOT EXISTS devices (
      device_id TEXT PRIMARY KEY,
      role TEXT NOT NULL,
      display_name TEXT NOT NULL,
      fcm_token TEXT,
      last_seen INTEGER NOT NULL
    )`)
    this.sql.exec('CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)')
    this.ringTimeoutMs = Number(env.RING_TIMEOUT_MS || DEFAULT_RING_TIMEOUT_MS)
    // No fixed number of people in a call: the apps send smaller pictures as
    // it grows. MAX_CALL_MEMBERS sets a ceiling, for a TV too slow for a big one.
    this.maxMembers = Number(env.MAX_CALL_MEMBERS) || 0
    this.fcm = FcmSender.fromEnv(env)
  }

  // -------------------------------------------------------------------------
  // HTTP: health check and WebSocket upgrade
  // -------------------------------------------------------------------------

  async fetch (request) {
    const { pathname } = new URL(request.url)
    if (pathname === '/devices' || pathname.startsWith('/devices/')) {
      return this.devicesEndpoint(request, pathname)
    }
    if (pathname === '/health') {
      // Whether a relay is actually being handed out, so a bad Metered key shows
      // up here rather than as calls that fail only on CGNAT. Counts only -
      // never credentials. Metered fetches are cached, so this costs at most one
      // API call an hour.
      const { iceServers } = await buildIceServers(this.env)
      return Response.json({
        ok: true,
        configured: this.homeSecretValid(),
        devices: this.sql.exec('SELECT COUNT(*) AS n FROM devices').one().n,
        online: this.onlineIds().size,
        call: this.getCall()?.callId ?? null,
        // Whether FCM_SERVICE_ACCOUNT is set and parses - never the account itself.
        push: this.fcm instanceof NullFcmSender ? 'disabled' : 'ready',
        turn: {
          source: this.env.TURN_URLS && this.env.TURN_USERNAME && this.env.TURN_CREDENTIAL
            ? 'fixed'
            : this.env.METERED_API_KEY && this.env.METERED_APP_NAME
              ? 'metered'
              : this.env.TURN_SECRET && this.env.TURN_HOST ? 'coturn' : 'none',
          relay: iceServers.some(server => server.username && server.credential),
          servers: iceServers.length,
          error: turnError()
        }
      })
    }

    if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') {
      return new Response('Expected a WebSocket upgrade', { status: 426 })
    }

    const [client, server] = Object.values(new WebSocketPair())
    this.ctx.acceptWebSocket(server)
    const peer = request.headers.get('CF-Connecting-IP') ?? 'local'
    server.serializeAttachment({ deviceId: null, peer, connectedAt: Date.now() })
    console.log(`[ws] connection from ${peer}`)
    await this.scheduleAlarm()
    return new Response(null, { status: 101, webSocket: client })
  }

  // -------------------------------------------------------------------------
  // Messages
  // -------------------------------------------------------------------------

  /**
   * Household admin, for whoever set the system up: list registered devices and
   * remove ones that no longer exist (an uninstalled app, a replaced phone). A
   * leftover device matters: a call to the TV only ends early once every
   * registered TV has declined, and a leftover never answers.
   *
   *   GET    /devices              Authorization: Bearer <HOME_SECRET>
   *   DELETE /devices/<deviceId>   Authorization: Bearer <HOME_SECRET>
   *
   * The secret only ever travels in a header, never in the URL, and push tokens
   * are never returned.
   */
  async devicesEndpoint (request, pathname) {
    if (!this.homeSecretValid()) {
      return Response.json({ error: 'server not configured' }, { status: 503 })
    }
    const auth = request.headers.get('Authorization') ?? ''
    const given = auth.startsWith('Bearer ') ? auth.slice('Bearer '.length) : ''
    if (!safeEqual(given, this.env.HOME_SECRET)) {
      return Response.json({ error: 'unauthorised' }, { status: 401 })
    }

    if (pathname === '/devices' && request.method === 'GET') {
      const online = this.onlineIds()
      return Response.json({
        devices: this.allDevices().map(d => ({
          deviceId: d.deviceId,
          role: d.role,
          displayName: d.displayName,
          online: online.has(d.deviceId),
          lastSeen: new Date(d.lastSeen).toISOString(),
          hasPushToken: !!d.fcmToken
        }))
      })
    }

    if (pathname.startsWith('/devices/') && request.method === 'DELETE') {
      const deviceId = decodeURIComponent(pathname.slice('/devices/'.length))
      if (!this.getDevice(deviceId)) {
        return Response.json({ error: 'no such device' }, { status: 404 })
      }
      const call = this.getCall()
      if (this.onlineIds().has(deviceId) || call?.members.includes(deviceId) || call?.targetIds.includes(deviceId)) {
        return Response.json({ error: 'device is online or part of the active call' }, { status: 409 })
      }
      this.sql.exec('DELETE FROM devices WHERE device_id = ?', deviceId)
      console.log(`[store] removed device ${deviceId.slice(0, 8)}`)
      this.broadcastPresence()
      return Response.json({ removed: deviceId })
    }

    return Response.json({ error: 'method not allowed' }, { status: 405 })
  }

  async webSocketMessage (ws, raw) {
    let msg
    try {
      msg = JSON.parse(typeof raw === 'string' ? raw : new TextDecoder().decode(raw))
    } catch {
      return this.send(ws, 'error', { code: 'bad_json', message: 'Message was not JSON' })
    }
    if (!msg || typeof msg !== 'object') {
      return this.send(ws, 'error', { code: 'bad_json', message: 'Message was not a JSON object' })
    }

    // Registration is the only message allowed before authentication.
    if (msg.type === 'register') return this.register(ws, msg)

    const deviceId = this.attachment(ws).deviceId
    const device = deviceId ? this.getDevice(deviceId) : null
    if (!device) return this.send(ws, 'error', { code: 'unregistered', message: 'Send register first' })

    switch (msg.type) {
      case 'call-request':
        return this.startCall(device, { mode: msg.mode ?? 'normal', fullScreenRemote: !!msg.fullScreenRemote })

      case 'call-accept':
        return this.acceptCall(device, msg.callId)

      case 'call-reject':
        return this.rejectCall(device, msg.callId)

      case 'hangup':
        return this.endCall(device, msg.callId, msg.reason ?? 'hangup')

      case 'signal':
        return this.relaySignal(device, msg)

      case 'refresh-ice':
        // TURN credentials expire; a long-lived TV app asks for new ones.
        return this.send(ws, 'ice-servers', await buildIceServers(this.env))

      case 'update-token':
        this.upsertDevice({ ...device, fcmToken: msg.fcmToken })
        return

      case 'ping':
        return this.send(ws, 'pong', { t: Date.now() })

      default:
        return this.send(ws, 'error', { code: 'unknown_type', message: `Unknown message type: ${msg.type}` })
    }
  }

  async register (ws, msg) {
    if (!this.homeSecretValid()) {
      console.error('FATAL: HOME_SECRET is not set to a long random string - run: npx wrangler secret put HOME_SECRET')
      return ws.close(1011, 'server not configured')
    }
    if (!safeEqual(String(msg.secret ?? ''), this.env.HOME_SECRET)) {
      console.warn(`[ws] bad secret from ${this.attachment(ws).peer}`)
      return ws.close(4003, 'unauthorised')
    }
    if (!msg.deviceId || !['tv', 'phone'].includes(msg.role)) {
      return this.send(ws, 'error', { code: 'bad_register', message: 'deviceId and role are required' })
    }

    const device = this.upsertDevice({
      deviceId: String(msg.deviceId),
      role: msg.role,
      displayName: msg.displayName,
      fcmToken: msg.fcmToken
    })

    // Replace any previous socket for this device (app restart, network flap).
    // It is marked first, so its closing does not tear down a call the new
    // socket is now carrying.
    for (const other of this.ctx.getWebSockets()) {
      if (other === ws || this.attachment(other).deviceId !== device.deviceId) continue
      this.setAttachment(other, { replaced: true })
      try { other.close(4000, 'replaced by newer connection') } catch { /* already closing */ }
    }

    this.setAttachment(ws, { deviceId: device.deviceId, registeredAt: Date.now() })
    console.log(`[ws] registered ${device.displayName} (${device.role}/${device.deviceId.slice(0, 8)})`)
    this.send(ws, 'registered', { deviceId: device.deviceId, ...(await buildIceServers(this.env)) })
    this.broadcastPresence()

    // A device coming online mid-call needs to know about it.
    const call = this.getCall()
    if (call && this.ringingIds(call).includes(device.deviceId)) {
      this.send(ws, 'incoming-call', this.incomingCallPayload(call))
    }
    await this.scheduleAlarm()
  }

  // -------------------------------------------------------------------------
  // Call flow - the same rules as server/src/index.js
  //
  // A call is always with the TV, and several phones can be in it at once. The
  // TV rings every phone and each one that answers joins; a phone calls the TV,
  // and others can join it. Every member connects directly to every other
  // member (a mesh), so the server only has to say who is in the call and, for
  // each pair, which side makes the WebRTC offer: the one that joined later.
  // -------------------------------------------------------------------------

  async startCall (caller, { mode, fullScreenRemote }) {
    let existing = this.getCall()
    // A deploy or eviction drops sockets without close events, which would
    // otherwise leave a dead call in storage and the system busy forever.
    if (existing && this.isStale(existing)) {
      console.log(`[call] ${existing.callId} ended (stale: its members are no longer connected)`)
      this.saveCall(null)
      existing = null
    }
    if (existing) {
      // Pressing "call" while the family is already talking to grandpa joins in.
      if (caller.role === 'phone') return this.joinCall(caller, existing)
      return this.sendTo(caller.deviceId, 'error', { code: 'busy', message: 'A call is already in progress' })
    }

    // The TV rings every phone; a phone rings the TV.
    const targets = this.devicesByRole(caller.role === 'tv' ? 'phone' : 'tv')
    if (targets.length === 0) {
      return this.sendTo(caller.deviceId, 'error', { code: 'no_targets', message: 'Nothing registered to call' })
    }

    const now = Date.now()
    const call = {
      callId: crypto.randomUUID(),
      callerId: caller.deviceId,
      callerRole: caller.role,
      targetIds: targets.map(t => t.deviceId),
      // Everyone in the call, in the order they joined.
      members: [caller.deviceId],
      rejectedBy: [],
      // Someone on the other side has answered.
      active: false,
      // Targets that have not answered are still being rung.
      ringing: true,
      mode,
      fullScreenRemote,
      startedAt: now,
      ringDeadline: now + this.ringTimeoutMs
    }
    // Saved before anything is awaited, so a second request cannot slip in.
    this.saveCall(call)
    await this.scheduleAlarm()

    console.log(`[call] ${call.callId} ${caller.displayName} (${caller.role}) -> ${targets.length} target(s), mode=${mode}`)
    this.sendTo(caller.deviceId, 'call-started', { callId: call.callId, targets: call.targetIds, ringTimeoutMs: this.ringTimeoutMs })
    this.broadcastPresence()

    const pushes = []
    for (const target of targets) {
      // Ring over the open socket when there is one - it is instant.
      const delivered = this.sendTo(target.deviceId, 'incoming-call', this.incomingCallPayload(call))

      // And push regardless: a phone with a live socket can still have its
      // screen off. The app de-duplicates on callId.
      if (target.role === 'phone') {
        pushes.push(this.fcm.sendCallInvite({
          token: target.fcmToken,
          callId: call.callId,
          fromName: caller.displayName,
          fromDeviceId: caller.deviceId,
          mode
        }))
      } else if (!delivered) {
        console.warn(`[call] ${call.callId}: TV ${target.deviceId} is offline, cannot ring it`)
      }
    }
    await Promise.allSettled(pushes)
  }

  /** A phone asking to call while a call is already on: it joins that one. */
  async joinCall (device, call) {
    if (call.members.includes(device.deviceId)) {
      return this.sendTo(device.deviceId, 'error', { code: 'busy', message: 'Already in this call' })
    }
    if (this.ringingIds(call).includes(device.deviceId)) return this.acceptCall(device, call.callId)
    if (this.isFull(call)) {
      return this.sendTo(device.deviceId, 'error', { code: 'full', message: `This call is limited to ${this.maxMembers} devices` })
    }

    call.members.push(device.deviceId)
    call.rejectedBy = call.rejectedBy.filter(id => id !== device.deviceId)
    this.saveCall(call)
    console.log(`[call] ${call.callId} joined by ${device.displayName}`)

    // Before the TV answers this waits with the others; after, it connects.
    this.sendTo(device.deviceId, 'call-started', {
      callId: call.callId,
      targets: call.targetIds,
      ringTimeoutMs: Math.max(0, call.ringDeadline - Date.now())
    })
    if (call.active) this.sendPeers(call)
    this.broadcastPresence()
  }

  async onRingTimeout (call) {
    const pushes = []
    const stopRinging = () => {
      for (const id of this.ringingIds(call)) {
        this.sendTo(id, 'call-cancelled', { callId: call.callId, reason: 'timeout' })
        const device = this.getDevice(id)
        if (device?.role === 'phone') {
          pushes.push(this.fcm.sendCallCancelled({ token: device.fcmToken, callId: call.callId, reason: 'timeout' }))
        }
      }
    }

    if (call.active) {
      // The call goes on; the phones that never answered stop ringing.
      stopRinging()
      call.ringing = false
      this.saveCall(call)
      console.log(`[call] ${call.callId} stopped ringing the phones that did not answer`)
    } else {
      stopRinging()
      this.saveCall(null)
      console.log(`[call] ${call.callId} timed out after ${this.ringTimeoutMs / 1000}s`)
      for (const id of call.members) this.sendTo(id, 'call-timeout', { callId: call.callId })
      this.broadcastPresence()
    }
    await Promise.allSettled(pushes)
  }

  async acceptCall (device, callId) {
    const call = this.getCall()
    if (!call || call.callId !== callId) {
      return this.sendTo(device.deviceId, 'call-cancelled', { callId, reason: 'gone' })
    }
    if (call.members.includes(device.deviceId)) return
    if (!this.ringingIds(call).includes(device.deviceId)) {
      // Answering just after the ring ran out still gets a phone into a call
      // that is going on. A second TV is never let in: the call has one.
      if (device.role === 'phone' && call.active) return this.joinCall(device, call)
      const reason = call.targetIds.includes(device.deviceId) && call.active ? 'answered_elsewhere' : 'gone'
      return this.sendTo(device.deviceId, 'call-cancelled', { callId, reason })
    }
    if (this.isFull(call)) {
      return this.sendTo(device.deviceId, 'call-cancelled', { callId, reason: 'full' })
    }

    call.members.push(device.deviceId)
    call.active = true
    if (device.role === 'tv') {
      // A phone called the TV and a TV answered: any other TV stands down.
      for (const id of this.ringingIds(call)) {
        this.sendTo(id, 'call-cancelled', { callId, reason: 'answered_elsewhere' })
      }
      call.ringing = false
    }
    this.saveCall(call)
    await this.scheduleAlarm()
    console.log(`[call] ${callId} answered by ${device.displayName} (${call.members.length} in the call)`)

    this.sendPeers(call)
    this.broadcastPresence()
  }

  /**
   * A target turning the call down. One phone declining does not stop the
   * others ringing; an unanswered call only ends once every target has declined.
   */
  async rejectCall (device, callId) {
    const call = this.getCall()
    if (!call || call.callId !== callId) return
    if (!this.ringingIds(call).includes(device.deviceId)) return

    call.rejectedBy.push(device.deviceId)
    this.saveCall(call)
    console.log(`[call] ${callId} declined by ${device.displayName}`)
    this.sendTo(call.callerId, 'call-rejected-by', { callId, deviceId: device.deviceId })

    if (!call.active && call.targetIds.every(id => call.rejectedBy.includes(id))) {
      await this.closeCall(call, device.deviceId, 'declined')
    }
  }

  /**
   * Leaving a call. Only members can leave; a target that is still ringing is
   * declining, and anyone else has no say at all. The call carries on while
   * the TV and at least one phone are still in it.
   */
  async endCall (device, callId, reason) {
    const call = this.getCall()
    if (!call || call.callId !== callId) return
    if (!call.members.includes(device.deviceId)) return this.rejectCall(device, callId)

    const remaining = call.members.filter(id => id !== device.deviceId)
    const roles = remaining.map(id => this.getDevice(id)?.role)
    const over = call.active
      ? !roles.includes('tv') || !roles.includes('phone')
      : device.role === 'tv' || remaining.length === 0
    if (over) return this.closeCall(call, device.deviceId, reason)

    call.members = remaining
    this.saveCall(call)
    console.log(`[call] ${callId} left by ${device.displayName} (${reason}), ${remaining.length} still in it`)
    if (call.active) this.sendPeers(call)
    this.broadcastPresence()
  }

  /** Tell everyone else in the call that it is over, then forget it. */
  async closeCall (call, endedById, reason) {
    const ringing = this.ringingIds(call)
    this.saveCall(null)
    console.log(`[call] ${call.callId} ended (${reason})`)

    const pushes = []
    for (const id of new Set([...call.members, ...call.targetIds])) {
      if (id === endedById) continue
      this.sendTo(id, 'call-cancelled', { callId: call.callId, reason })
      // A phone still ringing may be asleep, so it hears by push too.
      const other = this.getDevice(id)
      if (other?.role === 'phone' && ringing.includes(id)) {
        pushes.push(this.fcm.sendCallCancelled({ token: other.fcmToken, callId: call.callId, reason }))
      }
    }
    this.broadcastPresence()
    await this.scheduleAlarm()
    await Promise.allSettled(pushes)
  }

  relaySignal (device, msg) {
    // Opaque relay for SDP offers, answers and ICE candidates. Only members of
    // an answered call may address each other.
    const call = this.getCall()
    if (!msg.to || !call || call.callId !== msg.callId || !call.active) return
    if (!call.members.includes(device.deviceId) || !call.members.includes(msg.to)) return
    this.sendTo(msg.to, 'signal', { callId: msg.callId, from: device.deviceId, payload: msg.payload })
  }

  /**
   * Tell every member who else is in the call. Each member connects to each
   * peer listed and drops any it is connected to that is no longer listed;
   * `offer` says which side of each pair starts.
   */
  sendPeers (call) {
    for (const [index, id] of call.members.entries()) {
      const peers = call.members
        .map((peerId, peerIndex) => ({ peerId, peerIndex }))
        .filter(p => p.peerId !== id)
        .map(({ peerId, peerIndex }) => {
          const peer = this.getDevice(peerId)
          return {
            peerId,
            peerName: peer?.displayName ?? 'Someone',
            role: peer?.role ?? 'phone',
            offer: index > peerIndex
          }
        })
      this.sendTo(id, 'call-peers', {
        callId: call.callId,
        mode: call.mode,
        fullScreenRemote: call.fullScreenRemote,
        peers
      })
    }
  }

  /** Targets that are still being rung: not answered, not declined, not timed out. */
  ringingIds (call) {
    if (!call.ringing) return []
    return call.targetIds.filter(id => !call.members.includes(id) && !call.rejectedBy.includes(id))
  }

  /** True only when MAX_CALL_MEMBERS is set and the call has reached it. */
  isFull (call) {
    return this.maxMembers > 0 && call.members.length >= this.maxMembers
  }

  /** A call whose members have all gone without saying so. */
  isStale (call) {
    const connected = call.members.filter(id => this.socketFor(id))
    if (!call.active) return connected.length === 0
    const roles = connected.map(id => this.getDevice(id)?.role)
    return !roles.includes('tv') || !roles.includes('phone')
  }

  incomingCallPayload (call) {
    return {
      callId: call.callId,
      from: call.callerId,
      fromName: this.getDevice(call.callerId)?.displayName ?? 'TV',
      mode: call.mode,
      fullScreenRemote: call.fullScreenRemote
    }
  }

  // -------------------------------------------------------------------------
  // Sockets closing
  // -------------------------------------------------------------------------

  async webSocketClose (ws, code, reason) {
    await this.socketGone(ws, code, reason)
  }

  async webSocketError (ws, error) {
    await this.socketGone(ws, 1006, error?.message ?? 'socket error')
  }

  async socketGone (ws, code, reason) {
    const a = this.attachment(ws)
    // Complete the close handshake; harmless if it is already closed.
    try { ws.close(1000, 'closed') } catch { /* already closed */ }
    if (a.gone) return
    this.setAttachment(ws, { gone: true })

    if (!a.deviceId || a.replaced) return this.scheduleAlarm()

    const device = this.getDevice(a.deviceId) ?? { deviceId: a.deviceId, displayName: a.deviceId }
    console.log(`[ws] ${device.displayName} disconnected (${code} ${reason})`)

    // A member that vanishes leaves the call, rather than leaving the others
    // staring at a frozen frame.
    const call = this.getCall()
    if (call && call.members.includes(a.deviceId) && !this.socketFor(a.deviceId)) {
      await this.endCall(device, call.callId, 'peer_disconnected')
    }
    this.broadcastPresence()
    await this.scheduleAlarm()
  }

  // -------------------------------------------------------------------------
  // Timers: one storage alarm covers the ring timeout and registration deadlines
  // -------------------------------------------------------------------------

  async scheduleAlarm () {
    const due = []
    const call = this.getCall()
    if (call && call.ringing) due.push(call.ringDeadline)
    for (const ws of this.openSockets()) {
      const a = this.attachment(ws)
      if (!a.deviceId) due.push(a.connectedAt + REGISTER_DEADLINE_MS)
    }
    if (due.length === 0) {
      await this.ctx.storage.deleteAlarm()
    } else {
      await this.ctx.storage.setAlarm(Math.min(...due))
    }
  }

  async alarm () {
    const now = Date.now()
    for (const ws of this.openSockets()) {
      const a = this.attachment(ws)
      if (!a.deviceId && now >= a.connectedAt + REGISTER_DEADLINE_MS) {
        console.log(`[ws] dropping unregistered connection from ${a.peer}`)
        try { ws.close(4001, 'register first') } catch { /* already closing */ }
      }
    }
    const call = this.getCall()
    if (call && call.ringing && now >= call.ringDeadline) {
      await this.onRingTimeout(call)
    }
    await this.scheduleAlarm()
  }

  // -------------------------------------------------------------------------
  // Socket helpers
  // -------------------------------------------------------------------------

  attachment (ws) {
    try { return ws.deserializeAttachment() ?? {} } catch { return {} }
  }

  setAttachment (ws, patch) {
    try { ws.serializeAttachment({ ...this.attachment(ws), ...patch }) } catch { /* socket closed */ }
  }

  openSockets () {
    return this.ctx.getWebSockets().filter(ws => ws.readyState === OPEN)
  }

  /** The newest open socket registered as this device, if any. */
  socketFor (deviceId) {
    let best = null
    let bestAt = -1
    for (const ws of this.openSockets()) {
      const a = this.attachment(ws)
      if (a.deviceId === deviceId && !a.replaced && (a.registeredAt ?? 0) > bestAt) {
        best = ws
        bestAt = a.registeredAt ?? 0
      }
    }
    return best
  }

  onlineIds () {
    const ids = new Set()
    for (const ws of this.openSockets()) {
      const a = this.attachment(ws)
      if (a.deviceId && !a.replaced) ids.add(a.deviceId)
    }
    return ids
  }

  send (ws, type, payload = {}) {
    if (!ws) return false
    try {
      ws.send(JSON.stringify({ type, ...payload }))
      return true
    } catch {
      return false
    }
  }

  sendTo (deviceId, type, payload = {}) {
    return this.send(this.socketFor(deviceId), type, payload)
  }

  /** Who is online, and who is in a call right now - so a phone can offer to join it. */
  broadcastPresence () {
    const online = this.onlineIds()
    const devices = this.allDevices().map(d => ({
      deviceId: d.deviceId,
      role: d.role,
      displayName: d.displayName,
      online: online.has(d.deviceId)
    }))
    const active = this.getCall()
    const call = active ? { callId: active.callId, active: active.active, members: active.members } : null
    for (const ws of this.openSockets()) {
      const a = this.attachment(ws)
      if (a.deviceId && !a.replaced) this.send(ws, 'presence', { devices, call })
    }
  }

  homeSecretValid () {
    return typeof this.env.HOME_SECRET === 'string' && this.env.HOME_SECRET.length >= 20
  }

  // -------------------------------------------------------------------------
  // Storage: the device registry and the active call
  // -------------------------------------------------------------------------

  getDevice (deviceId) {
    const row = this.sql.exec('SELECT * FROM devices WHERE device_id = ?', deviceId).toArray()[0]
    return row ? toDevice(row) : null
  }

  devicesByRole (role) {
    return this.sql.exec('SELECT * FROM devices WHERE role = ?', role).toArray().map(toDevice)
  }

  allDevices () {
    return this.sql.exec('SELECT * FROM devices').toArray().map(toDevice)
  }

  upsertDevice ({ deviceId, role, displayName, fcmToken }) {
    const existing = this.getDevice(deviceId)
    const record = {
      deviceId,
      role,
      displayName: String(displayName ?? existing?.displayName ?? role),
      // A phone that reconnects without a token (e.g. a Play Services hiccup)
      // must not wipe the token already stored.
      fcmToken: fcmToken ?? existing?.fcmToken ?? null,
      lastSeen: Date.now()
    }
    this.sql.exec(
      `INSERT INTO devices (device_id, role, display_name, fcm_token, last_seen) VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(device_id) DO UPDATE SET
         role = excluded.role,
         display_name = excluded.display_name,
         fcm_token = excluded.fcm_token,
         last_seen = excluded.last_seen`,
      record.deviceId, record.role, record.displayName, record.fcmToken, record.lastSeen
    )
    return record
  }

  getCall () {
    const row = this.sql.exec("SELECT value FROM kv WHERE key = 'call'").toArray()[0]
    return row ? JSON.parse(row.value) : null
  }

  saveCall (call) {
    if (call) {
      this.sql.exec(
        "INSERT INTO kv (key, value) VALUES ('call', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        JSON.stringify(call)
      )
    } else {
      this.sql.exec("DELETE FROM kv WHERE key = 'call'")
    }
  }
}

function toDevice (row) {
  return {
    deviceId: row.device_id,
    role: row.role,
    displayName: row.display_name,
    fcmToken: row.fcm_token,
    lastSeen: row.last_seen
  }
}

/** Constant-time comparison, so the home secret cannot be guessed by timing. */
function safeEqual (a, b) {
  const x = new TextEncoder().encode(a)
  const y = new TextEncoder().encode(b)
  if (x.byteLength !== y.byteLength) return false
  return crypto.subtle.timingSafeEqual(x, y)
}
