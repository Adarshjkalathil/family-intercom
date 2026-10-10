/**
 * Runs the server's own call-flow suites (server/test/smoke.js and timeout.js)
 * against the Worker under `wrangler dev`, so both implementations are held to
 * one protocol by one set of tests.
 *
 *   npm test
 *
 * Each suite gets a fresh wrangler instance and empty storage. No Cloudflare
 * account is needed: wrangler dev runs the Worker locally.
 */
import { spawn } from 'node:child_process'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = fileURLToPath(new URL('..', import.meta.url))
const SERVER_TESTS = fileURLToPath(new URL('../../server/test/', import.meta.url))
const isWindows = process.platform === 'win32'
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

const BASE_VARS = {
  // Must match SECRET in server/test/*.js.
  HOME_SECRET: 'test-secret-that-is-definitely-long-enough',
  // Selects the coturn credential mode, which the suites check the shape of.
  TURN_SECRET: 'turn-test-secret',
  TURN_HOST: '127.0.0.1'
}

const WORKER_TESTS = fileURLToPath(new URL('./', import.meta.url))

const SUITES = [
  { dir: SERVER_TESTS, file: 'smoke.js', port: 18787, vars: {} },
  // Must match RING_MS and MAX_MEMBERS in server/test/timeout.js.
  { dir: SERVER_TESTS, file: 'timeout.js', port: 18788, vars: { RING_TIMEOUT_MS: '1200', MAX_CALL_MEMBERS: '3' } },
  // Worker-only: the authenticated /devices admin endpoint.
  { dir: WORKER_TESTS, file: 'devices.mjs', port: 18789, vars: {} }
]

function startWrangler (port, vars) {
  const args = [
    'wrangler', 'dev',
    '--ip', '127.0.0.1',
    '--port', String(port),
    '--persist-to', mkdtempSync(join(tmpdir(), 'intercom-wrangler-')),
    '--show-interactive-dev-session=false'
  ]
  for (const [key, value] of Object.entries({ ...BASE_VARS, ...vars })) args.push('--var', `${key}:${value}`)

  const child = spawn(isWindows ? 'npx.cmd' : 'npx', args, {
    cwd: HERE,
    env: { ...process.env, WRANGLER_SEND_METRICS: 'false' },
    stdio: ['ignore', 'pipe', 'pipe'],
    // A process group on POSIX, so stopping it also stops workerd.
    detached: !isWindows,
    shell: isWindows
  })
  const log = []
  child.stdout.on('data', d => log.push(d.toString()))
  child.stderr.on('data', d => log.push(d.toString()))
  return { child, log }
}

async function waitForHealth (port, child, timeoutMs = 120_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (child.exitCode !== null) return false
    try {
      const res = await fetch(`http://127.0.0.1:${port}/health`)
      if (res.ok) return true
    } catch { /* not listening yet */ }
    await sleep(500)
  }
  return false
}

function stopWrangler ({ child }) {
  try {
    if (isWindows) spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' })
    else process.kill(-child.pid, 'SIGTERM')
  } catch { /* already gone */ }
}

function runSuite (dir, file, port) {
  return new Promise(resolve => {
    const child = spawn(process.execPath, [join(dir, file)], {
      env: { ...process.env, EXTERNAL_WS_URL: `ws://127.0.0.1:${port}/ws` },
      stdio: 'inherit'
    })
    child.on('exit', code => resolve(code ?? 1))
  })
}

let failures = 0
for (const suite of SUITES) {
  console.log(`\n=== ${suite.file} against the Worker (wrangler dev :${suite.port})`)
  const wrangler = startWrangler(suite.port, suite.vars)

  if (!(await waitForHealth(suite.port, wrangler.child))) {
    console.error('wrangler dev never became healthy:\n' + wrangler.log.join(''))
    stopWrangler(wrangler)
    failures++
    continue
  }

  const code = await runSuite(suite.dir, suite.file, suite.port)
  if (code !== 0) {
    failures++
    console.error('--- worker log ---\n' + wrangler.log.join(''))
  }
  stopWrangler(wrangler)
  await sleep(1500)
}

console.log(failures ? `\n${failures} suite(s) failed against the Worker\n` : '\nAll suites passed against the Worker\n')
process.exit(failures ? 1 : 0)
