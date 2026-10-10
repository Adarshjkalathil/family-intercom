import { createHmac } from 'node:crypto'

/**
 * Ephemeral TURN credentials, matching coturn's `use-auth-secret` scheme.
 *
 * coturn accepts a username of the form "<unix-expiry>:<label>" and a password
 * of base64(HMAC-SHA1(static-auth-secret, username)). No user database, and
 * credentials that leak are useless within hours.
 */
export function makeTurnCredentials ({ secret, label = 'intercom', ttlSeconds = 12 * 3600 }) {
  const expiry = Math.floor(Date.now() / 1000) + ttlSeconds
  const username = `${expiry}:${label}`
  const credential = createHmac('sha1', secret).update(username).digest('base64')
  return { username, credential, expiresAt: expiry * 1000 }
}

/**
 * Build the ICE server list handed to both peers.
 *
 * Order matters to WebRTC only as a hint, but listing STUN first makes the
 * intent clear: try to find a direct path, and fall back to the relay. On Jio
 * AirFiber (CGNAT, frequently symmetric NAT) the relay will sometimes be the
 * only thing that works, which is exactly why it exists.
 */
export function buildIceServers ({ host, turnSecret, turnPort = 3478, turnsPort = 5349 }) {
  const { username, credential, expiresAt } = makeTurnCredentials({ secret: turnSecret })
  return {
    expiresAt,
    iceServers: [
      { urls: [`stun:${host}:${turnPort}`] },
      {
        urls: [
          `turn:${host}:${turnPort}?transport=udp`,
          `turn:${host}:${turnPort}?transport=tcp`,
          // TLS on 5349 is the last resort: it survives networks that block
          // UDP outright, at the cost of latency.
          `turns:${host}:${turnsPort}?transport=tcp`
        ],
        username,
        credential
      }
    ]
  }
}
