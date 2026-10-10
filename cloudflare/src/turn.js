/**
 * ICE servers handed to both peers at registration and on refresh-ice.
 *
 * Four sources, first configured wins:
 *
 *  0. Fixed credentials (TURN_URLS + TURN_USERNAME + TURN_CREDENTIAL): one
 *     credential created by hand in a TURN provider's dashboard. Metered's free
 *     20 GB plan only allows this; its credential API needs a paid plan. The
 *     credential only ever reaches devices that know the home secret.
 *  1. Metered Open Relay (METERED_APP_NAME + METERED_API_KEY): a free managed
 *     TURN relay, 20 GB/month, no card. The production choice on Cloudflare,
 *     where there is no machine to run coturn on.
 *  2. Your own coturn (TURN_HOST + TURN_SECRET): the same time-limited
 *     HMAC credentials as server/src/turn.js. Also what the tests use.
 *  3. Public STUN only. Calls still connect when either side has a reachable
 *     address, but two CGNAT networks (Jio on both ends) can fail without a relay.
 */

const PUBLIC_STUN = { urls: ['stun:stun.cloudflare.com:3478', 'stun:stun.l.google.com:19302'] }
/** Re-fetch Metered credentials hourly; the apps refresh every 4 hours. */
const METERED_CACHE_MS = 60 * 60 * 1000

let meteredCache = null
/** Why the last Metered fetch failed, for /health. Never includes the URL or key. */
let lastMeteredError = null

export function turnError () {
  return lastMeteredError
}

export async function buildIceServers (env) {
  if (env.TURN_URLS && env.TURN_USERNAME && env.TURN_CREDENTIAL) return fixedCredentials(env)
  if (env.METERED_API_KEY && env.METERED_APP_NAME) return metered(env)
  if (env.TURN_SECRET && env.TURN_HOST) return coturn(env)
  console.warn('[turn] no TURN relay configured - calls between two CGNAT networks may fail')
  return { expiresAt: Date.now() + 12 * 3600 * 1000, iceServers: [PUBLIC_STUN] }
}

/** TURN_URLS is a space- or comma-separated list, e.g. "turn:x:80 turns:x:443?transport=tcp". */
function fixedCredentials (env) {
  const urls = String(env.TURN_URLS).split(/[\s,]+/).filter(Boolean)
  return {
    expiresAt: Date.now() + 12 * 3600 * 1000,
    iceServers: [
      PUBLIC_STUN,
      { urls, username: String(env.TURN_USERNAME), credential: String(env.TURN_CREDENTIAL) }
    ]
  }
}

async function metered (env) {
  const now = Date.now()
  if (meteredCache && now < meteredCache.fetchedAt + METERED_CACHE_MS) return meteredCache.value

  // Accept either "myapp" or "myapp.metered.live".
  const app = String(env.METERED_APP_NAME).trim().replace(/\.metered\.live$/, '')
  const url = new URL(`https://${app}.metered.live/api/v1/turn/credentials`)
  url.searchParams.set('apiKey', env.METERED_API_KEY)
  if (env.METERED_REGION) url.searchParams.set('region', env.METERED_REGION)

  try {
    const res = await fetch(url)
    if (!res.ok) {
      // Metered explains a bad key or app in the body; keep it short and key-free.
      const detail = (await res.text().catch(() => '')).replace(/\s+/g, ' ').slice(0, 120)
      throw new Error(`HTTP ${res.status}${detail ? `: ${detail}` : ''}`)
    }
    const list = await res.json()
    if (!Array.isArray(list) || list.length === 0) throw new Error('unexpected response shape')

    const iceServers = list
      .filter(server => server && server.urls)
      .map(server => ({
        urls: Array.isArray(server.urls) ? server.urls : [server.urls],
        ...(server.username ? { username: server.username } : {}),
        ...(server.credential ? { credential: server.credential } : {})
      }))
    const value = { expiresAt: now + METERED_CACHE_MS, iceServers }
    meteredCache = { fetchedAt: now, value }
    lastMeteredError = null
    // Success is logged too, so `wrangler tail` can prove the relay key works
    // rather than only ever reporting when it does not.
    console.log(`[turn] Metered relay credentials fetched (${iceServers.length} ICE servers)`)
    return value
  } catch (err) {
    // Never log the URL: it carries the API key.
    lastMeteredError = String(err.message).replaceAll(String(env.METERED_API_KEY), '<key>')
    console.error(`[turn] could not fetch Metered credentials: ${lastMeteredError}`)
    return meteredCache?.value ?? { expiresAt: now + 60_000, iceServers: [PUBLIC_STUN] }
  }
}

/** coturn's use-auth-secret scheme: username "<expiry>:<label>", password base64(HMAC-SHA1). */
async function coturn (env, ttlSeconds = 12 * 3600) {
  const expiry = Math.floor(Date.now() / 1000) + ttlSeconds
  const username = `${expiry}:intercom`
  const encoder = new TextEncoder()
  const key = await crypto.subtle.importKey(
    'raw', encoder.encode(env.TURN_SECRET), { name: 'HMAC', hash: 'SHA-1' }, false, ['sign']
  )
  const mac = await crypto.subtle.sign('HMAC', key, encoder.encode(username))
  const credential = btoa(String.fromCharCode(...new Uint8Array(mac)))
  const host = env.TURN_HOST

  return {
    expiresAt: expiry * 1000,
    iceServers: [
      { urls: [`stun:${host}:3478`] },
      {
        urls: [
          `turn:${host}:3478?transport=udp`,
          `turn:${host}:3478?transport=tcp`,
          `turns:${host}:5349?transport=tcp`
        ],
        username,
        credential
      }
    ]
  }
}
