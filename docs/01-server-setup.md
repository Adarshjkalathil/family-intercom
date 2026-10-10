# 1. Server setup

One Oracle Cloud Always Free VM runs everything the apps need that is not on a
device: signalling, the TURN relay, and the push sender.

Time: about 30 minutes, most of it waiting for Oracle.

---

## Why this exists at all

You asked for no server, and the media genuinely has none — video and audio go
phone↔TV directly. But two things cannot be done peer-to-peer:

- **Finding each other.** Both ends are behind CGNAT (Jio AirFiber gives you a
  shared address, so port forwarding does nothing), which means neither device
  can be dialled directly. Something with a stable address has to introduce them.
- **Waking a sleeping phone.** Android will not let an app hold a socket open
  indefinitely, so a push is the only reliable ring.

The VM does those two jobs and nothing else. It cannot decrypt your calls.

---

## 1.1 Create the VM

1. Sign up at <https://cloud.oracle.com> and pick the **Mumbai** or **Hyderabad**
   region (lowest latency to Jio). Region cannot be changed later.
2. **Compute → Instances → Create instance.**
3. Shape: **VM.Standard.E2.1.Micro** (AMD, Always Free).

   > Choose AMD, not the Ampere ARM shape. Oracle reclaims *idle* Always Free ARM
   > instances, and an intercom that sits quiet for weeks is the definition of
   > idle. The AMD micro shapes are not reclaimed.

4. Image: **Canonical Ubuntu 24.04** (22.04 also fine).
5. Add your SSH public key. Download it if Oracle generates one.
6. Create, and note the **public IP address**.

## 1.2 Point a name at it

TLS needs a hostname. DuckDNS is free:

1. Sign in at <https://www.duckdns.org> with any of the listed providers.
2. Create a subdomain, e.g. `yourname`.
3. Set its IP to the VM's public IP and save.

Check it resolves:

```bash
getent hosts yourname.duckdns.org
```

## 1.3 Open the cloud firewall

**This is the step everybody misses.** Oracle has a firewall *outside* the VM,
and until you open it the machine is invisible from the internet.

**Networking → Virtual Cloud Networks → your VCN → Security Lists → Default.**
Add these **ingress** rules, source `0.0.0.0/0`:

| Protocol | Port(s) | What for |
|---|---|---|
| TCP | 80 | Let's Encrypt challenge |
| TCP | 443 | Signalling (WSS) |
| TCP | 3478 | TURN over TCP |
| UDP | 3478 | TURN over UDP — the fast path |
| TCP | 5349 | TURN over TLS |
| UDP | 5349 | TURN over TLS |
| UDP | 49160–49360 | Relayed media |

## 1.4 Run the installer

```bash
ssh ubuntu@<your-vm-ip>
git clone <your-private-repo-url> intercom
cd intercom/server
sudo PUBLIC_HOST=yourname.duckdns.org EMAIL=you@example.com ./deploy/setup.sh
```

It installs Node and coturn, gets a Let's Encrypt certificate, generates your
secrets, configures both services, and starts them. It is safe to re-run; it will
not regenerate secrets that already exist.

At the end it prints:

```
  Server URL   wss://yourname.duckdns.org/ws
  Home secret  3f9a...  (64 hex characters)
```

**Write both down.** Every app needs them, and the secret is the only thing
stopping a stranger registering a device against your intercom.

## 1.5 Check it works

```bash
curl https://yourname.duckdns.org/health
```

Expect `{"ok":true,"devices":0,"online":0,"call":null}`.

To test the relay directly, mint a credential on the VM:

```bash
cd /opt/intercom/server
sudo -u intercom node --input-type=module -e '
  import("./src/turn.js").then(({buildIceServers}) =>
    console.log(JSON.stringify(buildIceServers({
      host: process.env.PUBLIC_HOST,
      turnSecret: process.env.TURN_SECRET
    }), null, 2)))
' --env-file=/etc/intercom/intercom.env
```

Paste the TURN URL, username and credential into the Trickle ICE tester at
<https://webrtc.github.io/samples/src/content/peerconnection/trickle-ice/>. A
line of type `relay` means the relay works.

The honest end-to-end test is still a real call with Wi-Fi off on the phone — if
that connects, the whole path is proven.

## 1.6 Useful commands

```bash
sudo systemctl status intercom coturn
sudo journalctl -u intercom -f          # live call log
sudo journalctl -u intercom -n 200      # recent history
sudo tail -f /var/log/turnserver.log    # relay activity
sudo systemctl restart intercom
```

The signalling log is genuinely readable — it names every call, who rang, who
answered, and why it ended. When something goes wrong, start here.

---

Next: **[Firebase](02-firebase.md)**, so a locked phone rings.
