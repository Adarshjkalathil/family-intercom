# Intercom server

Signalling broker + TURN relay. Runs on one Oracle Cloud Always Free VM.

**It never sees your video or audio.** Media goes phone↔TV directly over WebRTC.
This server only introduces the two peers to each other, and — when CGNAT makes a
direct path impossible — coturn forwards encrypted packets it cannot read.

## Zero dependencies

`npm install` does nothing: there are none. The WebSocket server
(`src/ws-server.js`) and the Google OAuth signing (`src/fcm.js`) are written
against node's standard library. Nothing to install on the VM, nothing to patch,
no supply chain sitting on the internet in front of a family safety device.

Node 20 or newer is required (for built-in `fetch` and `WebSocket` in the tests).

## Install

On a fresh Ubuntu 22.04/24.04 Oracle VM:

```bash
sudo PUBLIC_HOST=yourname.duckdns.org EMAIL=you@example.com ./deploy/setup.sh
```

See `../docs/01-server-setup.md` for the full walkthrough, including the Oracle
Security List rules that catch everybody out.

## Test

```bash
npm test
```

Starts the real server, drives a TV and three phones through the whole call
flow over real WebSockets, group calls included, and checks the ring-timeout path
with a shortened timer.
No network access needed.

## Protocol

JSON messages over one WebSocket at `wss://<host>/ws`. Every device registers
first; anything else before that is refused.

### Client → server

| Type | Fields | Meaning |
|---|---|---|
| `register` | `deviceId`, `role` (`tv`\|`phone`), `displayName`, `secret`, `fcmToken?` | Authenticate and come online |
| `call-request` | `mode` (`normal`\|`emergency`), `fullScreenRemote?` | Start ringing the other side; from a phone during a call, join it |
| `call-accept` | `callId` | Answer, and join the call |
| `call-reject` | `callId` | Decline without stopping the other phones |
| `hangup` | `callId`, `reason?` | Leave the call. It ends when the TV leaves, or the last phone |
| `signal` | `callId`, `to`, `payload` | Relay an SDP offer/answer or ICE candidate |
| `refresh-ice` | — | Get fresh TURN credentials (they expire in 12h) |
| `update-token` | `fcmToken` | FCM rotated the token |
| `ping` | — | Keepalive |

### Server → client

| Type | Fields | Meaning |
|---|---|---|
| `registered` | `deviceId`, `iceServers`, `expiresAt` | Authenticated; here are your ICE servers |
| `presence` | `devices[]`, `call` | Who exists and who is online; the call going on (`callId`, `active`, `members`), or null |
| `call-started` | `callId`, `targets`, `ringTimeoutMs` | Your outgoing call is ringing, or you joined one |
| `incoming-call` | `callId`, `from`, `fromName`, `mode`, `fullScreenRemote` | Ring |
| `call-peers` | `callId`, `mode`, `fullScreenRemote`, `peers[]` (`peerId`, `peerName`, `role`, `offer`) | Everyone else in the call, re-sent on every join and leave. Connect to each; **make the offer where `offer` is true** |
| `call-cancelled` | `callId`, `reason` | Stop ringing / the call is over for you |
| `call-timeout` | `callId` | Nobody answered within 45s |
| `call-rejected-by` | `callId`, `deviceId` | One phone declined; others still ringing |
| `signal` | `callId`, `from`, `payload` | Relayed SDP/ICE |
| `error` | `code`, `message` | Something was refused |

### Design notes

- **One call at a time, many people in it.** There is exactly one TV, so one
  call is enforced as an invariant rather than handled as a race. A phone that
  asks to call while one is on joins it; the TV asking again gets `error: busy`.
  No fixed number of devices; `MAX_CALL_MEMBERS` sets a ceiling if the TV
  needs one, with `error: full` beyond it.
- **A mesh, and the later joiner offers.** Every member connects directly to
  every other. For each pair, whoever joined the call later makes the WebRTC
  offer, and `call-peers` says so explicitly. This avoids glare entirely.
- **Ringing outlives the first answer.** When grandpa calls, phones that have
  not answered keep ringing until the 45 s are up, so the family can join; then
  they stop and the call goes on.
- **Ring over socket *and* push.** A phone with a live socket can still have a
  dark screen, so both fire; the app de-duplicates on `callId`.
- **The relay is opaque.** The server never parses SDP. It checks only that both
  ends are members of the current call, which stops a registered-but-
  uninvolved device injecting signalling.
