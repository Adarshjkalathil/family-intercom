import { createHash, randomBytes } from 'node:crypto'
import { EventEmitter } from 'node:events'

/**
 * A small RFC 6455 WebSocket server.
 *
 * Written by hand rather than pulling in `ws` so the whole server has zero npm
 * dependencies: nothing to install on the VM, nothing to keep patched, and no
 * supply chain for a box that sits on the internet for years with a family
 * safety device behind it. It implements the subset this project uses: text
 * frames, fragmentation, ping/pong and a clean close handshake.
 */

// RFC 6455 section 1.3. Note the grouping: ...-95CA-C5AB0DC85B11, which is
// easy to mistranscribe. Verified against the RFC's worked example in test/.
const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'
const MAX_MESSAGE_BYTES = 1 << 20 // 1 MiB. SDP is a few KB; anything larger is an attack.

const OP = { CONT: 0x0, TEXT: 0x1, BINARY: 0x2, CLOSE: 0x8, PING: 0x9, PONG: 0xa }

export class WebSocketConnection extends EventEmitter {
  constructor (socket, request) {
    super()
    this.socket = socket
    this.request = request
    this.readyState = 'open'
    this.isAlive = true

    this._buffer = Buffer.alloc(0)
    this._fragments = []
    this._fragmentOpcode = null

    socket.on('data', chunk => this._onData(chunk))
    socket.on('close', () => this._finish(1006, 'socket closed'))
    socket.on('error', err => {
      this.emit('error', err)
      this._finish(1006, err.message)
    })
    // A half-open TCP connection would otherwise hang around forever.
    socket.setTimeout(0)
    socket.setNoDelay(true)
  }

  // -- receiving -----------------------------------------------------------

  _onData (chunk) {
    this._buffer = this._buffer.length ? Buffer.concat([this._buffer, chunk]) : chunk

    // A single TCP read can contain several frames, or half of one.
    while (this._buffer.length >= 2) {
      const frame = this._tryReadFrame()
      if (!frame) break
      this._handleFrame(frame)
      if (this.readyState === 'closed') return
    }
  }

  _tryReadFrame () {
    const buf = this._buffer
    const first = buf[0]
    const second = buf[1]

    const fin = (first & 0x80) !== 0
    const opcode = first & 0x0f
    const masked = (second & 0x80) !== 0
    let length = second & 0x7f
    let offset = 2

    if (length === 126) {
      if (buf.length < offset + 2) return null
      length = buf.readUInt16BE(offset)
      offset += 2
    } else if (length === 127) {
      if (buf.length < offset + 8) return null
      const big = buf.readBigUInt64BE(offset)
      if (big > BigInt(MAX_MESSAGE_BYTES)) {
        this.close(1009, 'message too large')
        return null
      }
      length = Number(big)
      offset += 8
    }

    // RFC 6455: every client-to-server frame must be masked.
    if (!masked) {
      this.close(1002, 'client frame was not masked')
      return null
    }
    if (buf.length < offset + 4 + length) return null

    const maskKey = buf.subarray(offset, offset + 4)
    offset += 4
    const payload = Buffer.allocUnsafe(length)
    for (let i = 0; i < length; i++) payload[i] = buf[offset + i] ^ maskKey[i & 3]

    this._buffer = buf.subarray(offset + length)
    return { fin, opcode, payload }
  }

  _handleFrame ({ fin, opcode, payload }) {
    switch (opcode) {
      case OP.PING:
        this._send(OP.PONG, payload)
        break

      case OP.PONG:
        this.isAlive = true
        this.emit('pong')
        break

      case OP.CLOSE: {
        const code = payload.length >= 2 ? payload.readUInt16BE(0) : 1005
        const reason = payload.length > 2 ? payload.subarray(2).toString('utf8') : ''
        if (this.readyState === 'open') {
          this.readyState = 'closing'
          this._send(OP.CLOSE, payload) // echo it back to complete the handshake
        }
        this.socket.end()
        this._finish(code, reason)
        break
      }

      case OP.TEXT:
      case OP.BINARY:
        if (!fin) {
          this._fragmentOpcode = opcode
          this._fragments = [payload]
          return
        }
        this._deliver(opcode, payload)
        break

      case OP.CONT: {
        if (this._fragmentOpcode === null) {
          this.close(1002, 'continuation without start')
          return
        }
        this._fragments.push(payload)
        const total = this._fragments.reduce((n, f) => n + f.length, 0)
        if (total > MAX_MESSAGE_BYTES) {
          this.close(1009, 'message too large')
          return
        }
        if (!fin) return
        const complete = Buffer.concat(this._fragments)
        const op = this._fragmentOpcode
        this._fragments = []
        this._fragmentOpcode = null
        this._deliver(op, complete)
        break
      }

      default:
        this.close(1002, `unsupported opcode ${opcode}`)
    }
  }

  _deliver (opcode, payload) {
    this.isAlive = true
    this.emit('message', opcode === OP.TEXT ? payload.toString('utf8') : payload)
  }

  // -- sending -------------------------------------------------------------

  _send (opcode, payload) {
    if (this.socket.destroyed) return false
    const len = payload.length
    let header

    if (len < 126) {
      header = Buffer.allocUnsafe(2)
      header[1] = len
    } else if (len < 65536) {
      header = Buffer.allocUnsafe(4)
      header[1] = 126
      header.writeUInt16BE(len, 2)
    } else {
      header = Buffer.allocUnsafe(10)
      header[1] = 127
      header.writeBigUInt64BE(BigInt(len), 2)
    }
    header[0] = 0x80 | opcode // FIN set; we never fragment outbound

    try {
      this.socket.write(Buffer.concat([header, payload]))
      return true
    } catch (err) {
      this.emit('error', err)
      return false
    }
  }

  send (text) {
    if (this.readyState !== 'open') return false
    return this._send(OP.TEXT, Buffer.from(String(text), 'utf8'))
  }

  ping () {
    if (this.readyState !== 'open') return false
    return this._send(OP.PING, randomBytes(4))
  }

  close (code = 1000, reason = '') {
    if (this.readyState !== 'open') return
    this.readyState = 'closing'
    const reasonBuf = Buffer.from(reason, 'utf8')
    const payload = Buffer.allocUnsafe(2 + reasonBuf.length)
    payload.writeUInt16BE(code, 0)
    reasonBuf.copy(payload, 2)
    this._send(OP.CLOSE, payload)
    this.socket.end()
    // If the peer never replies to our close, stop waiting.
    setTimeout(() => this.terminate(), 2000).unref?.()
  }

  terminate () {
    if (this.readyState === 'closed') return
    this.socket.destroy()
    this._finish(1006, 'terminated')
  }

  _finish (code, reason) {
    if (this.readyState === 'closed') return
    this.readyState = 'closed'
    this.emit('close', code, reason)
  }
}

/**
 * Attaches to an existing http/https server and upgrades requests on `path`.
 * Emits 'connection' with (WebSocketConnection, IncomingMessage).
 */
export class WebSocketServer extends EventEmitter {
  constructor ({ server, path = '/ws' }) {
    super()
    this.path = path
    this.clients = new Set()

    server.on('upgrade', (req, socket, head) => {
      const url = (req.url ?? '').split('?')[0]
      if (url !== this.path) return this._reject(socket, 404, 'Not Found')

      const key = req.headers['sec-websocket-key']
      const version = req.headers['sec-websocket-version']
      const upgrade = String(req.headers.upgrade ?? '').toLowerCase()

      if (upgrade !== 'websocket' || !key || version !== '13') {
        return this._reject(socket, 400, 'Bad Request')
      }

      const accept = createHash('sha1').update(key + GUID).digest('base64')
      socket.write(
        'HTTP/1.1 101 Switching Protocols\r\n' +
        'Upgrade: websocket\r\n' +
        'Connection: Upgrade\r\n' +
        `Sec-WebSocket-Accept: ${accept}\r\n\r\n`
      )

      const conn = new WebSocketConnection(socket, req)
      if (head?.length) conn._onData(head)

      this.clients.add(conn)
      conn.on('close', () => this.clients.delete(conn))
      this.emit('connection', conn, req)
    })
  }

  _reject (socket, code, text) {
    socket.write(`HTTP/1.1 ${code} ${text}\r\nConnection: close\r\n\r\n`)
    socket.destroy()
  }
}
