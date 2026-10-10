const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging'
const TOKEN_URL = 'https://oauth2.googleapis.com/token'

const encoder = new TextEncoder()
const b64url = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes)))
  .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
const b64urlJson = value => b64url(encoder.encode(JSON.stringify(value)))

/** PKCS#8 PEM ("-----BEGIN PRIVATE KEY-----") to the raw DER bytes WebCrypto wants. */
function pemToDer (pem) {
  const body = pem.replace(/-----(BEGIN|END) PRIVATE KEY-----/g, '').replace(/\s+/g, '')
  const binary = atob(body)
  const der = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) der[i] = binary.charCodeAt(i)
  return der.buffer
}

/**
 * FCM v1 sender for Workers - server/src/fcm.js with node:crypto swapped for
 * WebCrypto, which is all a Worker has.
 *
 * Firebase push is the one mechanism that reliably wakes a sleeping, locked
 * Android phone from anywhere. Sending from here with a service account keeps
 * the project off Firebase's paid plan: Cloud Functions would require it, plain
 * FCM does not.
 */
export class FcmSender {
  /** The service account JSON comes from the FCM_SERVICE_ACCOUNT secret. */
  static fromEnv (env) {
    if (!env.FCM_SERVICE_ACCOUNT) return new NullFcmSender()
    try {
      return new FcmSender(JSON.parse(env.FCM_SERVICE_ACCOUNT))
    } catch (err) {
      console.error(`[fcm] FCM_SERVICE_ACCOUNT is not usable, push disabled: ${err.message}`)
      return new NullFcmSender()
    }
  }

  constructor (sa) {
    if (!sa.project_id || !sa.client_email || !sa.private_key) {
      throw new Error('service account JSON is missing project_id, client_email or private_key')
    }
    this.clientEmail = sa.client_email
    this.privateKey = sa.private_key
    this.endpoint = `https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`
    this._key = null
    this._token = null
    this._tokenExpiry = 0
  }

  async _signingKey () {
    this._key ??= await crypto.subtle.importKey(
      'pkcs8',
      pemToDer(this.privateKey),
      { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
      false,
      ['sign']
    )
    return this._key
  }

  /**
   * Exchange a signed JWT assertion for an access token. Cached in memory; the
   * Durable Object may hibernate and lose it, which only costs one re-sign.
   */
  async _accessToken () {
    if (this._token && Date.now() < this._tokenExpiry) return this._token

    const now = Math.floor(Date.now() / 1000)
    const unsigned = `${b64urlJson({ alg: 'RS256', typ: 'JWT' })}.${b64urlJson({
      iss: this.clientEmail,
      scope: SCOPE,
      aud: TOKEN_URL,
      iat: now,
      exp: now + 3600
    })}`
    const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', await this._signingKey(), encoder.encode(unsigned))

    const res = await fetch(TOKEN_URL, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
        assertion: `${unsigned}.${b64url(signature)}`
      })
    })
    const body = await res.json().catch(() => ({}))
    if (!res.ok) throw new Error(`token exchange failed (${res.status}): ${body.error_description ?? body.error ?? 'unknown'}`)

    this._token = body.access_token
    // Renew a minute early so a call is never delayed by a token refresh.
    this._tokenExpiry = Date.now() + ((body.expires_in ?? 3600) - 60) * 1000
    return this._token
  }

  async _post (message) {
    const res = await fetch(this.endpoint, {
      method: 'POST',
      headers: { authorization: `Bearer ${await this._accessToken()}`, 'content-type': 'application/json' },
      body: JSON.stringify({ message })
    })
    const body = await res.json().catch(() => ({}))
    if (!res.ok) throw new Error(body?.error?.message ?? `HTTP ${res.status}`)
    return body
  }

  /**
   * A data-only, high-priority wake message. Data-only is deliberate: it makes
   * the app's own code run, so it can raise a full-screen incoming-call screen.
   * Returns true if FCM accepted it; false usually means a stale token.
   */
  async sendCallInvite ({ token, callId, fromName, fromDeviceId, mode }) {
    if (!token) return false
    try {
      await this._post({
        token,
        data: {
          type: 'incoming_call',
          callId: String(callId),
          fromName: String(fromName ?? 'TV'),
          fromDeviceId: String(fromDeviceId ?? ''),
          mode: String(mode ?? 'normal'),
          sentAt: String(Date.now())
        },
        // A ring that arrives after the call timed out is worse than none.
        android: { priority: 'HIGH', ttl: '45s' }
      })
      console.log(`[fcm] delivered call ${callId} -> ${token.slice(0, 12)}…`)
      return true
    } catch (err) {
      console.error(`[fcm] failed for ${token.slice(0, 12)}…: ${err.message}`)
      return false
    }
  }

  /** Tell phones that someone else picked up, or that the caller gave up. */
  async sendCallCancelled ({ token, callId, reason }) {
    if (!token) return false
    try {
      await this._post({
        token,
        data: { type: 'call_cancelled', callId: String(callId), reason: String(reason) },
        android: { priority: 'HIGH', ttl: '30s' }
      })
      return true
    } catch (err) {
      console.error(`[fcm] cancel failed: ${err.message}`)
      return false
    }
  }
}

/** Used when no service account is configured: phones ring only while their app is open. */
export class NullFcmSender {
  async sendCallInvite ({ callId }) {
    console.warn(`[fcm] DISABLED - call ${callId} will only ring phones that are already connected`)
    return false
  }

  async sendCallCancelled () { return false }
}
