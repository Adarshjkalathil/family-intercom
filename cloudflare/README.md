# Signalling server on Cloudflare (free, no card)

The same server as `server/`, rewritten to run on Cloudflare's free Workers
plan. Use this if you cannot get a VM (Oracle's free tier rejects many debit
cards). The apps cannot tell the difference: they only need its URL.

| Job | Where it runs | Free allowance |
|---|---|---|
| Introduce the devices (signalling) | Cloudflare Worker + Durable Object | 100,000 requests/day, 5 GB storage |
| Relay video when CGNAT blocks a direct path (TURN) | Metered Open Relay | 20 GB/month |
| Wake sleeping phones (push) | The same Worker, via Firebase | Free |

A direct call uses none of the TURN allowance. Only calls that cannot connect
directly are relayed, at very roughly 1–2 GB per hour of video.

---

## 1. Accounts (5 minutes, no card)

1. **Cloudflare:** sign up at <https://dash.cloudflare.com/sign-up> and verify
   your email.
2. **Metered:** sign up for Open Relay at <https://www.metered.ca/tools/openrelay/>,
   create an app, and note its **app name** (the `<name>` in
   `<name>.metered.live`) and its **API key**.

## 2. Deploy

From this folder:

```bash
npm install
npx wrangler login      # approve in the browser
npx wrangler deploy
```

The first deploy asks you to pick a `workers.dev` subdomain. It prints the
Worker's address, for example `https://intercom.yourname.workers.dev`.

## 3. Secrets

Never put these in a file in the repository.

```bash
# The shared family secret every app is set up with. Any long random string.
npx wrangler secret put HOME_SECRET

# Metered's API key.
npx wrangler secret put METERED_API_KEY
```

Then put your Metered **app name** into `METERED_APP_NAME` in `wrangler.jsonc`
and run `npx wrangler deploy` again.

**On Metered's free 20 GB plan the credential API is not available.** Instead,
create one credential in the dashboard (**TURN Server → Add Credential → Show ICE
Servers Array**) and give the Worker its fixed values, which take priority over
the Metered API settings:

```bash
npx wrangler secret put TURN_URLS        # the relay URLs, separated by spaces
npx wrangler secret put TURN_USERNAME
npx wrangler secret put TURN_CREDENTIAL
```

The dashboard works too: **Workers & Pages → intercom → Settings → Variables
and Secrets → Add → Secret**.

For push notifications (see `docs/02-firebase.md` for the JSON file):

```bash
npx wrangler secret put FCM_SERVICE_ACCOUNT < path/to/service-account.json
```

## 4. Check it

```bash
curl https://intercom.yourname.workers.dev/health
```

Expect `"ok":true,"configured":true` and a `turn` section like
`{"source":"metered","relay":true,"servers":5}`.

- `configured: false` means `HOME_SECRET` is missing or shorter than 20 characters.
- `relay: false` with `source: "metered"` means the Metered app name or API key
  is wrong, so calls between two CGNAT networks will fail. `npx wrangler tail`
  shows the error.
- `source: "none"` means no TURN relay is configured at all.

In each app's setup screen:

- **Server URL:** `wss://intercom.yourname.workers.dev/ws`
- **Home secret:** the `HOME_SECRET` you set

Live log of every call, who rang, who answered and why it ended:

```bash
npx wrangler tail
```

---

## How it differs from `server/`

- **Hibernation.** Between messages the Durable Object is evicted from memory
  while Cloudflare keeps the sockets open and answers their pings. So the device
  registry and the active call live in SQLite, each socket's identity lives in
  its attachment, and the ring timeout is a storage alarm.
- **No heartbeat.** The apps' own WebSocket pings keep the sockets alive, and
  Cloudflare answers them without waking the object.
- **TURN.** There is no machine to run coturn on, so credentials come from
  Metered. `turn.js` still supports your own coturn (`TURN_HOST` + `TURN_SECRET`),
  which is also what the tests use.

## Tests

```bash
npm test
```

Runs `server/test/smoke.js` and `timeout.js` against the Worker under
`wrangler dev`. No Cloudflare account is needed, and CI runs it on every push.
