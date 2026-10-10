import { readFileSync } from 'node:fs'
import { createSign } from 'node:crypto'

const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging'
const TOKEN_URL = 'https://oauth2.googleapis.com/token'

const b64url = buf => Buffer.from(buf).toString('base64url')

/**
 * FCM v1 sender, with the Google OAuth dance done by hand.
 *
 * This is the only reason the project touches Firebase at all: it is the one
 * mechanism that reliably wakes a sleeping, locked Android phone from anywhere.
 * Sending from our own VM with a service account keeps us off the Blaze plan
 * (Cloud Functions would require it; plain FCM is free and unmetered), and
 * signing the assertion with node's crypto keeps the server dependency-free.
 */
export class FcmSender {
  constructor (serviceAccountPath) {
    const sa = JSON.parse(readFileSync(serviceAccountPath, 'utf8'))
    if (!sa.project_id || !sa.client_email || !sa.private_key) {
      throw new Error('Service account JSON is missing project_id, client_email or private_key')
    }
    this.projectId = sa.project_id
    this.clientEmail = sa.client_email
    this.privateKey = sa.private_key
    this.endpoint = `https://fcm.googleapis.com/v1/projects/${this.projectId}/messages:send`
    this._token = null
    this._tokenExpiry = 0
    console.log(`[fcm] ready for project ${this.projectId}`)
  }

  /** Exchange a signed JWT assertion for an access token, cached until shortly before expiry. */
  async _accessToken () {
    if (this._token && Date.now() < this._tokenExpiry) return this._token

    const now = Math.floor(Date.now() / 1000)
    const header = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))
    const claims = b64url(JSON.stringify({
      iss: this.clientEmail,
      scope: SCOPE,
      aud: TOKEN_URL,
      iat: now,
      exp: now + 3600
    }))

    const signer = createSign('RSA-SHA256')
    signer.update(`${header}.${claims}`)
    const signature = b64url(signer.sign(this.privateKey))
    const assertion = `${header}.${claims}.${signature}`

    const res = await fetch(TOKEN_URL, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
        assertion
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
    const token = await this._accessToken()
    const res = await fetch(this.endpoint, {
      method: 'POST',
      headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
      body: JSON.stringify({ message })
    })
    const body = await res.json().catch(() => ({}))
    if (!res.ok) throw new Error(body?.error?.message ?? `HTTP ${res.status}`)
    return body
  }

  /**
   * Send a data-only, high-priority wake message.
   *
   * Data-only (no `notification` block) is deliberate: it guarantees our own
   * code runs in onMessageReceived so we can raise a full-screen incoming-call
   * intent, rather than letting the system draw a notification we don't control.
   *
   * Returns true if FCM accepted it. False usually means a stale token.
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
        android: {
          priority: 'HIGH',
          // A ring that arrives after the call has already timed out is worse
          // than no ring at all, so let it expire with the call.
          ttl: '45s'
        }
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

/** Used when no service account is configured, so the server still runs for LAN testing. */
export class NullFcmSender {
  async sendCallInvite ({ callId }) {
    console.warn(`[fcm] DISABLED - call ${callId} will only ring phones that are already connected`)
    return false
  }

  async sendCallCancelled () { return false }
}
