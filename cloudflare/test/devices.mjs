/**
 * Worker-only suite: the authenticated /devices admin endpoint, which lists
 * registered devices and removes leftovers. The Node server has no such
 * endpoint, so this lives here rather than in server/test.
 *
 * Run by test/run.mjs against `wrangler dev`.
 */
const WS_URL = process.env.EXTERNAL_WS_URL
if (!WS_URL) {
  console.error('EXTERNAL_WS_URL is required (run through test/run.mjs)')
  process.exit(2)
}
const BASE = WS_URL.replace(/^ws/, 'http').replace(/\/ws$/, '')
// Must match HOME_SECRET in test/run.mjs.
const SECRET = 'test-secret-that-is-definitely-long-enough'

let passed = 0
let failed = 0
const check = (label, ok, detail = '') => {
  if (ok) { passed++; console.log(`  \x1b[32mPASS\x1b[0m ${label}`) }
  else { failed++; console.log(`  \x1b[31mFAIL\x1b[0m ${label} ${detail}`) }
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

function connect (register) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS_URL)
    const timer = setTimeout(() => reject(new Error(`${register.deviceId}: no registration reply`)), 5000)
    ws.onopen = () => ws.send(JSON.stringify({ type: 'register', secret: SECRET, ...register }))
    ws.onmessage = ev => {
      if (JSON.parse(ev.data).type === 'registered') {
        clearTimeout(timer)
        resolve(ws)
      }
    }
    ws.onerror = () => {}
  })
}

const api = (path, { method = 'GET', secret = SECRET } = {}) =>
  fetch(BASE + path, { method, headers: secret === null ? {} : { Authorization: `Bearer ${secret}` } })

console.log('\nDevice admin endpoint\n')

const tv = await connect({ deviceId: 'tv-1', role: 'tv', displayName: 'Living Room TV' })
const oldPhone = await connect({ deviceId: 'phone-gone', role: 'phone', displayName: 'Old Phone', fcmToken: 'token-secret-x' })
oldPhone.close()
await sleep(500)

check('a request with no secret is refused', (await api('/devices', { secret: null })).status === 401)
check('a wrong secret is refused', (await api('/devices', { secret: 'wrong-secret-of-a-reasonable-length' })).status === 401)

const listRes = await api('/devices')
const list = listRes.ok ? (await listRes.json()).devices : []
const byId = Object.fromEntries(list.map(d => [d.deviceId, d]))
check('the right secret lists every device', listRes.status === 200 && list.length === 2, `(HTTP ${listRes.status}, ${list.length} devices)`)
check('a connected device is marked online', byId['tv-1']?.online === true)
check('a disconnected device is marked offline', byId['phone-gone']?.online === false)
check('push tokens are never returned', !JSON.stringify(list).includes('token-secret-x') && byId['phone-gone']?.hasPushToken === true)

check('an online device cannot be removed', (await api('/devices/tv-1', { method: 'DELETE' })).status === 409)
check('an unknown device is reported as not found', (await api('/devices/no-such-device', { method: 'DELETE' })).status === 404)
check('removing a device needs the secret', (await api('/devices/phone-gone', { method: 'DELETE', secret: null })).status === 401)
check('an offline device can be removed', (await api('/devices/phone-gone', { method: 'DELETE' })).status === 200)

const after = (await (await api('/devices')).json()).devices
check('a removed device is gone from the list', after.length === 1 && after[0].deviceId === 'tv-1')
check('other methods are refused', (await api('/devices', { method: 'POST' })).status === 405)

tv.close()
await sleep(200)
console.log(`\n${passed} passed, ${failed} failed\n`)
process.exit(failed ? 1 : 0)
