/**
 * Tests the "nobody answered" path on its own, with the ring timeout shortened
 * so the suite doesn't take a minute. Also the call size limit, the other
 * setting a test has to change.
 *
 * This is the path that matters most and is exercised least: grandpa presses
 * the button, nobody picks up, and the system has to tell him so rather than
 * leaving him in front of a silent screen.
 *
 *   node test/timeout.js
 */
import { spawn } from 'node:child_process'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = fileURLToPath(new URL('..', import.meta.url))
const PORT = 18444
const SECRET = 'test-secret-that-is-definitely-long-enough'
const RING_MS = 1200
const MAX_MEMBERS = 3
// Set by cloudflare/test/run.mjs to run this same suite against the Worker.
const EXTERNAL_URL = process.env.EXTERNAL_WS_URL
const URL_BASE = EXTERNAL_URL ?? `ws://127.0.0.1:${PORT}/ws`

let passed = 0
let failed = 0
const check = (label, ok, detail = '') => {
  if (ok) { passed++; console.log(`  \x1b[32mPASS\x1b[0m ${label}`) }
  else { failed++; console.log(`  \x1b[31mFAIL\x1b[0m ${label} ${detail}`) }
}
const sleep = ms => new Promise(r => setTimeout(r, ms))

class Client {
  constructor () {
    this.received = []
    this.ws = new WebSocket(URL_BASE)
    this.ready = new Promise(resolve => { this.ws.onopen = resolve })
    this.ws.onmessage = ev => this.received.push(JSON.parse(ev.data))
  }

  send (obj) { this.ws.send(JSON.stringify(obj)) }

  async expect (type, timeoutMs = 4000) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      const found = this.received.find(m => m.type === type)
      if (found) return found
      await sleep(25)
    }
    return null
  }

  close () { try { this.ws.close() } catch { /* gone */ } }
}

const storeDir = mkdtempSync(join(tmpdir(), 'intercom-timeout-'))
const server = EXTERNAL_URL ? null : spawn(process.execPath, [join(ROOT, 'src/index.js')], {
  env: {
    ...process.env,
    PORT: String(PORT),
    PUBLIC_HOST: '127.0.0.1',
    HOME_SECRET: SECRET,
    TURN_SECRET: 'turn-test-secret',
    STORE_PATH: join(storeDir, 'devices.json'),
    RING_TIMEOUT_MS: String(RING_MS),
    MAX_CALL_MEMBERS: String(MAX_MEMBERS),
    SSL_CERT: '', SSL_KEY: '', FCM_SERVICE_ACCOUNT: ''
  },
  stdio: ['ignore', 'pipe', 'pipe']
})
const log = []
server?.stdout.on('data', d => log.push(d.toString()))
server?.stderr.on('data', d => log.push(d.toString()))

if (server) await sleep(600)

console.log('\nRing timeout\n')

const tv = new Client()
const phone = new Client()
const phoneB = new Client()
await Promise.all([tv.ready, phone.ready, phoneB.ready])

tv.send({ type: 'register', deviceId: 'tv-1', role: 'tv', displayName: 'TV', secret: SECRET })
phone.send({ type: 'register', deviceId: 'phone-a', role: 'phone', displayName: 'Alex', secret: SECRET, fcmToken: 't' })
phoneB.send({ type: 'register', deviceId: 'phone-b', role: 'phone', displayName: 'Sam', secret: SECRET, fcmToken: 'u' })
await tv.expect('registered')
await phone.expect('registered')
await phoneB.expect('registered')

tv.received.length = 0
phone.received.length = 0
const t0 = Date.now()
tv.send({ type: 'call-request', mode: 'normal' })

const started = await tv.expect('call-started')
check('call starts', started !== null)
check('the TV is told how long it will ring', started?.ringTimeoutMs === RING_MS)

const ring = await phone.expect('incoming-call')
check('the phone rings', ring !== null)

// Nobody answers.
const timedOut = await tv.expect('call-timeout')
const elapsed = Date.now() - t0
check('the TV is told nobody answered', timedOut !== null)
check('it gives up at roughly the configured time',
  elapsed >= RING_MS && elapsed < RING_MS + 1500, `(${elapsed}ms)`)

const stoodDown = await phone.expect('call-cancelled')
check('the phone stops ringing', stoodDown?.reason === 'timeout')

// After a timeout the system must be usable again, not wedged.
tv.received.length = 0
tv.send({ type: 'call-request', mode: 'normal' })
const again = await tv.expect('call-started')
check('a new call can be placed after a timeout', again !== null)
check('the new call has a fresh id', again?.callId !== started?.callId)

// A late accept for the dead call must not resurrect it.
phone.received.length = 0
phone.send({ type: 'call-accept', callId: started?.callId })
const stale = await phone.expect('call-cancelled', 1500)
check('a late accept for the timed-out call is refused', stale?.reason === 'gone')

tv.send({ type: 'hangup', callId: again?.callId })
await sleep(200)

// Once someone has answered, running out of ring time only silences the phones
// that never did. The call itself goes on.
console.log('\nRing timeout during a call\n')
tv.received.length = 0
phone.received.length = 0
phoneB.received.length = 0
tv.send({ type: 'call-request', mode: 'normal' })
const third = await tv.expect('call-started')
phone.send({ type: 'call-accept', callId: third?.callId })
await tv.expect('call-peers')

const silenced = await phoneB.expect('call-cancelled')
check('a phone that never answered stops ringing', silenced?.reason === 'timeout')
await sleep(300)
check('the call itself does not time out',
  !tv.received.some(m => m.type === 'call-timeout' || m.type === 'call-cancelled') &&
  !phone.received.some(m => m.type === 'call-cancelled'))

// And the phone that stopped ringing can still join.
phoneB.send({ type: 'call-request', mode: 'normal' })
const joined = await phoneB.expect('call-peers')
check('a phone that missed the ring can still join', joined?.callId === third?.callId)

// The TV and two phones fill a call limited to three.
console.log('\nA limit, when one is set\n')
const phoneC = new Client()
await phoneC.ready
phoneC.send({ type: 'register', deviceId: 'phone-c', role: 'phone', displayName: 'Jo', secret: SECRET, fcmToken: 'v' })
await phoneC.expect('registered')
tv.received.length = 0
phoneC.send({ type: 'call-request', mode: 'normal' })
const full = await phoneC.expect('error')
check('a phone beyond MAX_CALL_MEMBERS is turned away', full?.code === 'full')
await sleep(300)
check('the call goes on without it', !tv.received.some(m => m.type === 'call-peers' || m.type === 'call-cancelled'))

tv.close(); phone.close(); phoneB.close(); phoneC.close()
await sleep(200)

console.log(`\n${passed} passed, ${failed} failed\n`)
if (failed) console.error(log.join(''))
server?.kill('SIGTERM')
setTimeout(() => { server?.kill('SIGKILL'); process.exit(failed ? 1 : 0) }, 300)
