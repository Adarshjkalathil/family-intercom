/**
 * End-to-end smoke test for the signalling server.
 *
 * Starts the real server as a child process, connects one TV and three phones
 * over real WebSockets, and walks the whole call flow, group calls included.
 * Run with:
 *
 *   node test/smoke.js
 *
 * No network access and no npm install required.
 */
import { spawn } from 'node:child_process'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = fileURLToPath(new URL('..', import.meta.url))
const PORT = 18443
const SECRET = 'test-secret-that-is-definitely-long-enough'
// Set by cloudflare/test/run.mjs to run this same suite against the Worker.
const EXTERNAL_URL = process.env.EXTERNAL_WS_URL
const URL_BASE = EXTERNAL_URL ?? `ws://127.0.0.1:${PORT}/ws`

let passed = 0
let failed = 0

function check (label, condition, detail = '') {
  if (condition) {
    passed++
    console.log(`  \x1b[32mPASS\x1b[0m ${label}`)
  } else {
    failed++
    console.log(`  \x1b[31mFAIL\x1b[0m ${label} ${detail}`)
  }
}

const sleep = ms => new Promise(r => setTimeout(r, ms))

/** A test client that records everything the server sends it. */
class Client {
  constructor (name) {
    this.name = name
    this.received = []
    this.ws = new WebSocket(URL_BASE)
    this.ready = new Promise((resolve, reject) => {
      this.ws.onopen = resolve
      this.ws.onerror = e => reject(new Error(`${name}: ${e.message ?? 'socket error'}`))
    })
    this.ws.onmessage = ev => {
      const msg = JSON.parse(ev.data)
      this.received.push(msg)
    }
  }

  send (obj) { this.ws.send(JSON.stringify(obj)) }

  /**
   * Wait for a message of `type` satisfying `where`, or return null after the
   * timeout. The predicate matters for messages the server sends repeatedly:
   * presence and the peer list are re-sent on every change, so the first one
   * to arrive is not necessarily the one describing the final state.
   */
  async expect (type, timeoutMs = 2500, where = () => true) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      const found = this.received.find(m => m.type === type && where(m))
      if (found) return found
      await sleep(25)
    }
    return null
  }

  got (type) { return this.received.some(m => m.type === type) }

  close () { try { this.ws.close() } catch { /* already gone */ } }
}

/** Forget what every client has received, so each section starts clean. */
const reset = (...clients) => { for (const c of clients) c.received.length = 0 }

/** The ids in a call-peers message, sorted, for comparing. */
const peerIds = msg => (msg?.peers ?? []).map(p => p.peerId).sort().join(',')
const peer = (msg, id) => msg?.peers?.find(p => p.peerId === id)

// ---------------------------------------------------------------------------

const storeDir = mkdtempSync(join(tmpdir(), 'intercom-test-'))
const server = EXTERNAL_URL ? null : spawn(process.execPath, [join(ROOT, 'src/index.js')], {
  env: {
    ...process.env,
    PORT: String(PORT),
    PUBLIC_HOST: '127.0.0.1',
    HOME_SECRET: SECRET,
    TURN_SECRET: 'turn-test-secret',
    STORE_PATH: join(storeDir, 'devices.json'),
    SSL_CERT: '',
    SSL_KEY: '',
    FCM_SERVICE_ACCOUNT: ''
  },
  stdio: ['ignore', 'pipe', 'pipe']
})

const serverLog = []
server?.stdout.on('data', d => serverLog.push(d.toString()))
server?.stderr.on('data', d => serverLog.push(d.toString()))

function shutdown (code) {
  server?.kill('SIGTERM')
  setTimeout(() => { server?.kill('SIGKILL'); process.exit(code) }, 300)
}

process.on('unhandledRejection', err => {
  console.error('\nUnhandled rejection:', err)
  console.error(serverLog.join(''))
  shutdown(1)
})

if (server) await sleep(600)
if (server && server.exitCode !== null) {
  console.error('Server exited immediately:\n' + serverLog.join(''))
  process.exit(1)
}

console.log('\nSignalling server smoke test\n')

// --- registration ----------------------------------------------------------
console.log('Registration')

const tv = new Client('tv')
const phoneA = new Client('phoneA')
const phoneB = new Client('phoneB')
const phoneC = new Client('phoneC')
await Promise.all([tv.ready, phoneA.ready, phoneB.ready, phoneC.ready])

const rogue = new Client('rogue')
await rogue.ready
rogue.send({ type: 'register', deviceId: 'rogue-1', role: 'phone', secret: 'wrong-secret' })
await sleep(300)
check('a bad home secret is rejected', rogue.ws.readyState === WebSocket.CLOSED || rogue.ws.readyState === WebSocket.CLOSING)

tv.send({ type: 'register', deviceId: 'tv-1', role: 'tv', displayName: 'Living Room TV', secret: SECRET })
phoneA.send({ type: 'register', deviceId: 'phone-a', role: 'phone', displayName: 'Alex', secret: SECRET, fcmToken: 'token-a' })
phoneB.send({ type: 'register', deviceId: 'phone-b', role: 'phone', displayName: 'Sam', secret: SECRET, fcmToken: 'token-b' })
phoneC.send({ type: 'register', deviceId: 'phone-c', role: 'phone', displayName: 'Jo', secret: SECRET, fcmToken: 'token-c' })

const reg = await tv.expect('registered')
check('TV registers', reg !== null)
check('registration returns ICE servers', Array.isArray(reg?.iceServers) && reg.iceServers.length >= 2)
check('a STUN URL is present', JSON.stringify(reg?.iceServers).includes('stun:'))
check('a TURN URL is present', JSON.stringify(reg?.iceServers).includes('turn:'))
check('TURN credentials are time-limited', /^\d{10}:/.test(reg?.iceServers?.[1]?.username ?? ''))

const presence = await phoneA.expect('presence', 2500, m => m.devices?.length === 4)
check('presence eventually lists all four devices', presence?.devices?.length === 4)
check('presence marks devices online', presence?.devices?.every(d => d.online) === true)
check('presence carries roles', presence?.devices?.filter(d => d.role === 'phone').length === 3)
check('presence says no call is on', presence?.call === null)

tv.send({ type: 'refresh-ice' })
const refreshed = await tv.expect('ice-servers')
check('refresh-ice hands out fresh TURN credentials', refreshed?.iceServers?.some(s => s.username) === true)

// --- unauthenticated messages ----------------------------------------------
console.log('\nAuthorisation')
const stranger = new Client('stranger')
await stranger.ready
stranger.send({ type: 'call-request' })
const strangerErr = await stranger.expect('error')
check('call-request before register is refused', strangerErr?.code === 'unregistered')
stranger.close()

// --- the grandpa button ----------------------------------------------------
console.log('\nGrandpa presses the button')

reset(tv, phoneA, phoneB, phoneC)
tv.send({ type: 'call-request', mode: 'normal' })

const started = await tv.expect('call-started')
check('TV gets call-started', started !== null)
check('ring timeout is reported to the TV', started?.ringTimeoutMs === 45000)

const ringA = await phoneA.expect('incoming-call')
const ringB = await phoneB.expect('incoming-call')
const ringC = await phoneC.expect('incoming-call')
check('every phone rings', ringA !== null && ringB !== null && ringC !== null)
check('all phones get the same callId', ringA?.callId === ringB?.callId && ringB?.callId === ringC?.callId)
check('caller name reaches the phones', ringA?.fromName === 'Living Room TV')

const callId = started?.callId

// --- one phone declines -----------------------------------------------------
console.log('\nOne phone declines')

// The phone app sends a reject; older builds also sent a hangup. Neither may
// stop the other phones ringing.
phoneB.send({ type: 'call-reject', callId })
const rejectedBy = await tv.expect('call-rejected-by')
check('the TV hears who declined', rejectedBy?.deviceId === 'phone-b')
phoneB.send({ type: 'hangup', callId, reason: 'declined' })
await sleep(300)
check('a decline does not stop the other phones ringing', !phoneA.got('call-cancelled') && !phoneC.got('call-cancelled'))
check('a decline does not cancel the call at the TV', !tv.got('call-cancelled'))

// --- a phone answers ----------------------------------------------------------
console.log('\nA phone answers')

phoneA.send({ type: 'call-accept', callId })
const tvPeers = await tv.expect('call-peers', 2500, m => m.peers?.length === 1)
check('TV learns who answered', peerIds(tvPeers) === 'phone-a')
check('TV is told the peer name', peer(tvPeers, 'phone-a')?.peerName === 'Alex')
check('the TV waits for an offer from whoever joined later', peer(tvPeers, 'phone-a')?.offer === false)

const aPeers = await phoneA.expect('call-peers')
check('the answering phone connects to the TV', peerIds(aPeers) === 'tv-1')
check('the answering phone makes the offer', peer(aPeers, 'tv-1')?.offer === true)
check('the peer list carries roles', peer(aPeers, 'tv-1')?.role === 'tv')

await sleep(300)
check('a phone still ringing keeps ringing after someone answers', !phoneC.got('call-cancelled'))

const inCall = await phoneB.expect('presence', 2500, m => m.call?.active === true)
check('presence tells the other phones a call is on, and who is in it',
  inCall?.call?.callId === callId && inCall?.call?.members?.includes('phone-a'))

// --- signalling relay -------------------------------------------------------
console.log('\nWebRTC signalling relay')

reset(tv, phoneA)
phoneA.send({ type: 'signal', callId, to: 'tv-1', payload: { kind: 'offer', sdp: 'v=0 fake offer' } })
const offer = await tv.expect('signal')
check('offer reaches the TV', offer?.payload?.kind === 'offer')
check('relayed message says who sent it', offer?.from === 'phone-a')

tv.send({ type: 'signal', callId, to: 'phone-a', payload: { kind: 'answer', sdp: 'v=0 fake answer' } })
const answer = await phoneA.expect('signal')
check('answer reaches the phone', answer?.payload?.kind === 'answer')

// A phone that is not in the call must not be able to inject signalling.
reset(tv)
phoneB.send({ type: 'signal', callId, to: 'tv-1', payload: { kind: 'offer', sdp: 'hijack' } })
await sleep(300)
check('a device outside the call cannot inject signalling',
  !tv.received.some(m => m.type === 'signal' && m.payload?.sdp === 'hijack'))

// A phone that is not in the call cannot end it either - e.g. one reacting
// late to a push.
phoneB.send({ type: 'hangup', callId, reason: 'declined' })
await sleep(300)
check('a device outside the call cannot end it', !tv.got('call-cancelled'))

// --- a second phone joins ----------------------------------------------------
console.log('\nGroup call: another phone joins')

reset(tv, phoneA, phoneB, phoneC)
// Phone B declined earlier; pressing call now joins the call that is on.
phoneB.send({ type: 'call-request', mode: 'normal' })
const bStarted = await phoneB.expect('call-started')
check('a phone that calls during a call joins it', bStarted?.callId === callId)

const bPeers = await phoneB.expect('call-peers', 2500, m => m.peers?.length === 2)
check('the joiner is told everyone in the call', peerIds(bPeers) === 'phone-a,tv-1')
check('the joiner makes every offer', bPeers?.peers?.every(p => p.offer === true) === true)

const tvPeers2 = await tv.expect('call-peers', 2500, m => m.peers?.length === 2)
check('the TV learns of the new member', peerIds(tvPeers2) === 'phone-a,phone-b')
check('the TV keeps waiting for offers', tvPeers2?.peers?.every(p => p.offer === false) === true)
const aPeers2 = await phoneA.expect('call-peers', 2500, m => m.peers?.length === 2)
check('so does the phone already in the call', peerIds(aPeers2) === 'phone-b,tv-1')
check('the earlier phone waits for the newer one to offer', peer(aPeers2, 'phone-b')?.offer === false)

phoneB.send({ type: 'signal', callId, to: 'phone-a', payload: { kind: 'offer', sdp: 'v=0 b to a' } })
const bToA = await phoneA.expect('signal')
check('phones in the call can signal each other', bToA?.from === 'phone-b' && bToA?.payload?.sdp === 'v=0 b to a')

// The third phone has been ringing all along, and answering adds it too.
phoneC.send({ type: 'call-accept', callId })
const cPeers = await phoneC.expect('call-peers', 2500, m => m.peers?.length === 3)
check('a phone still ringing can answer and join', peerIds(cPeers) === 'phone-a,phone-b,tv-1')
const tvPeers3 = await tv.expect('call-peers', 2500, m => m.peers?.length === 3)
check('the TV now has three people in the call', tvPeers3 !== null)

// --- one phone leaves --------------------------------------------------------
console.log('\nGroup call: one phone leaves')

reset(tv, phoneA, phoneB, phoneC)
phoneB.send({ type: 'hangup', callId, reason: 'hangup' })
const tvAfterLeave = await tv.expect('call-peers', 2500, m => m.peers?.length === 2)
check('the others are told who is left', peerIds(tvAfterLeave) === 'phone-a,phone-c')
await sleep(200)
check('one phone leaving does not end the call', !tv.got('call-cancelled') && !phoneA.got('call-cancelled'))

// --- large payloads ---------------------------------------------------------
console.log('\nFraming')
const bigSdp = 'v=0\r\n' + 'a=candidate:'.repeat(9000)
reset(phoneA)
tv.send({ type: 'signal', callId, to: 'phone-a', payload: { kind: 'offer', sdp: bigSdp } })
const big = await phoneA.expect('signal')
check('a >64KB SDP survives the relay intact', big?.payload?.sdp === bigSdp,
  `(got ${big?.payload?.sdp?.length ?? 0} of ${bigSdp.length} bytes)`)

// --- hangup ------------------------------------------------------------------
console.log('\nGrandpa hangs up')
reset(phoneA, phoneC)
tv.send({ type: 'hangup', callId, reason: 'hangup' })
const byeA = await phoneA.expect('call-cancelled')
const byeC = await phoneC.expect('call-cancelled')
check('the TV hanging up ends the call for everyone', byeA?.reason === 'hangup' && byeC?.reason === 'hangup')
const idle = await phoneB.expect('presence', 2500, m => m.call === null)
check('presence says the call is over', idle !== null)

// --- one call at a time -------------------------------------------------------
console.log('\nConcurrency')
reset(tv, phoneA)
tv.send({ type: 'call-request', mode: 'normal' })
const second = await tv.expect('call-started')
check('a new call can start after the last one ended', second !== null)

tv.send({ type: 'call-request', mode: 'normal' })
const busy = await tv.expect('error')
check('the TV cannot start a second call', busy?.code === 'busy')

// A ringing phone that presses its own call button answers rather than
// starting a second call.
phoneA.send({ type: 'call-request', mode: 'normal' })
const answeredByCalling = await phoneA.expect('call-peers')
check('a ringing phone that calls answers instead', answeredByCalling?.callId === second?.callId)

tv.send({ type: 'hangup', callId: second?.callId, reason: 'cleanup' })
await sleep(200)

// --- phone-initiated call (the "show me on the TV" button) --------------------
console.log('\nPhone calls the TV')
reset(tv, phoneA)
phoneA.send({ type: 'call-request', mode: 'emergency', fullScreenRemote: true })

const tvRing = await tv.expect('incoming-call')
check('the TV rings', tvRing !== null)
check('emergency mode is carried through', tvRing?.mode === 'emergency')
check('full-screen flag is carried through', tvRing?.fullScreenRemote === true)

tv.send({ type: 'call-accept', callId: tvRing?.callId })
const phoneAccepted = await phoneA.expect('call-peers')
check('phone learns the TV answered', peerIds(phoneAccepted) === 'tv-1')
check('the TV, joining last, makes the offer', peer(phoneAccepted, 'tv-1')?.offer === false)
check('the call keeps its mode', phoneAccepted?.mode === 'emergency' && phoneAccepted?.fullScreenRemote === true)

reset(tv)
phoneA.send({ type: 'hangup', callId: tvRing?.callId })
const phoneLeft = await tv.expect('call-cancelled')
check('the last phone leaving ends the call', phoneLeft !== null)

// --- joining before the TV answers --------------------------------------------
console.log('\nA second phone joins before the TV answers')
reset(tv, phoneA, phoneB)
phoneA.send({ type: 'call-request', mode: 'normal' })
const tvRing2 = await tv.expect('incoming-call')
phoneB.send({ type: 'call-request', mode: 'normal' })
const waiting = await phoneB.expect('call-started')
check('the second caller waits on the same call', waiting?.callId === tvRing2?.callId)
await sleep(200)
check('nothing connects before the TV answers', !phoneA.got('call-peers') && !phoneB.got('call-peers'))

tv.send({ type: 'call-accept', callId: tvRing2?.callId })
const tvBoth = await tv.expect('call-peers', 2500, m => m.peers?.length === 2)
check('once the TV answers it connects to both', peerIds(tvBoth) === 'phone-a,phone-b')
check('and offers to both, having joined last', tvBoth?.peers?.every(p => p.offer === true) === true)
const bWaitPeers = await phoneB.expect('call-peers', 2500, m => m.peers?.length === 2)
check('the two phones connect to each other too', peer(bWaitPeers, 'phone-a')?.offer === true)

tv.send({ type: 'hangup', callId: tvRing2?.callId })
await sleep(200)

// --- the only target declines -------------------------------------------------
console.log('\nThe TV declines')
reset(tv, phoneA)
phoneA.send({ type: 'call-request', mode: 'normal' })
const tvRing3 = await tv.expect('incoming-call')
tv.send({ type: 'call-reject', callId: tvRing3?.callId })
const declined = await phoneA.expect('call-cancelled')
check('once every target declines, the caller is told', declined?.reason === 'declined')

// --- no fixed number of people ------------------------------------------------
console.log('\nA big family')
const extra = ['Dee', 'Eli', 'Fin'].map(name => new Client(name))
await Promise.all(extra.map(c => c.ready))
extra.forEach((c, i) => c.send({
  type: 'register', deviceId: `phone-${'def'[i]}`, role: 'phone', displayName: c.name, secret: SECRET, fcmToken: `token-${'def'[i]}`
}))
await Promise.all(extra.map(c => c.expect('registered')))
const family = [phoneA, phoneB, phoneC, ...extra]
reset(tv, ...family)
tv.send({ type: 'call-request', mode: 'normal' })
const bigCall = await tv.expect('call-started')
for (const phone of family) phone.send({ type: 'call-accept', callId: bigCall?.callId })
const everyone = await tv.expect('call-peers', 4000, m => m.peers?.length === family.length)
check('seven devices share one call when no limit is set', everyone !== null)
check('nobody is turned away',
  !family.some(p => p.received.some(m => m.type === 'error' || m.reason === 'full')))
tv.send({ type: 'hangup', callId: bigCall?.callId })
for (const c of extra) c.close()
await sleep(300)

// --- disconnect mid-call ------------------------------------------------------
console.log('\nPeople disappear mid-call')
reset(tv, phoneA, phoneB)
tv.send({ type: 'call-request', mode: 'normal' })
const dropCall = await tv.expect('call-started')
phoneA.send({ type: 'call-accept', callId: dropCall?.callId })
phoneB.send({ type: 'call-accept', callId: dropCall?.callId })
await tv.expect('call-peers', 2500, m => m.peers?.length === 2)

reset(tv)
phoneB.close()
const afterDrop = await tv.expect('call-peers', 3000, m => m.peers?.length === 1)
check('a phone vanishing leaves the call', peerIds(afterDrop) === 'phone-a')
check('the call carries on without it', !tv.got('call-cancelled'))

phoneA.close()
const dropped = await tv.expect('call-cancelled', 3000)
check('the TV is told when the last phone vanishes', dropped?.reason === 'peer_disconnected')

// --- reconnect replaces the old socket ----------------------------------------
console.log('\nReconnection')
const phoneAgain = new Client('phoneA-again')
await phoneAgain.ready
phoneAgain.send({ type: 'register', deviceId: 'phone-a', role: 'phone', displayName: 'Alex', secret: SECRET, fcmToken: 'token-a2' })
const reReg = await phoneAgain.expect('registered')
check('a device can reconnect with the same id', reReg?.deviceId === 'phone-a')

// ---------------------------------------------------------------------------
tv.close(); phoneC.close(); phoneAgain.close(); rogue.close()
await sleep(200)

console.log(`\n${passed} passed, ${failed} failed\n`)
if (failed > 0) console.error('Server log:\n' + serverLog.join(''))
shutdown(failed === 0 ? 0 : 1)
