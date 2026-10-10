import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'

/**
 * Tiny JSON-file device registry. There are at most ~5 devices in this system,
 * so a database would be ceremony. Writes are synchronous and rare (only on
 * registration or FCM token refresh).
 */
export class DeviceStore {
  constructor (path) {
    this.path = path
    this.devices = new Map()
    this.#load()
  }

  #load () {
    if (!existsSync(this.path)) return
    try {
      const raw = JSON.parse(readFileSync(this.path, 'utf8'))
      for (const d of raw.devices ?? []) this.devices.set(d.deviceId, d)
      console.log(`[store] loaded ${this.devices.size} device(s)`)
    } catch (err) {
      console.error(`[store] could not read ${this.path}, starting empty:`, err.message)
    }
  }

  #persist () {
    mkdirSync(dirname(this.path), { recursive: true })
    const tmp = `${this.path}.tmp`
    writeFileSync(tmp, JSON.stringify({ devices: [...this.devices.values()] }, null, 2))
    // Rename is atomic on POSIX, so a crash mid-write cannot corrupt the registry.
    writeFileSync(this.path, readFileSync(tmp))
  }

  /** Insert or update a device. Returns the stored record. */
  upsert ({ deviceId, role, displayName, fcmToken }) {
    const existing = this.devices.get(deviceId) ?? {}
    const record = {
      ...existing,
      deviceId,
      role,
      displayName: displayName ?? existing.displayName ?? role,
      // A phone that reconnects without a token (e.g. Play Services hiccup)
      // must not wipe the token we already have.
      fcmToken: fcmToken ?? existing.fcmToken ?? null,
      lastSeen: Date.now()
    }
    this.devices.set(deviceId, record)
    this.#persist()
    return record
  }

  get (deviceId) {
    return this.devices.get(deviceId)
  }

  /** All registered devices with the given role, whether online or not. */
  byRole (role) {
    return [...this.devices.values()].filter(d => d.role === role)
  }

  remove (deviceId) {
    if (this.devices.delete(deviceId)) this.#persist()
  }
}
