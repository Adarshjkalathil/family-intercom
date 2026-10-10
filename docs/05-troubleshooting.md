# 5. Troubleshooting

Read this before driving to his house.

The most useful single command, run on the VM, is the live signalling log. It
names every call, who rang, who answered, and why it ended:

```bash
sudo journalctl -u intercom -f
```

---

## The build fails in GitHub Actions

### `Could not resolve io.github.webrtc-sdk:android:…`

The WebRTC version could not be verified when this was written. Open
`android/gradle/libs.versions.toml` and try the next value down:

```toml
webrtc = "125.6422.04"   # then "119.6045.03", then "114.5735.02"
```

Current versions are listed at
<https://github.com/webrtc-sdk/android/releases>. This is a one-line change; no
code depends on the version.

### `Could not resolve com.herohan:UVCAndroid:…`

Same idea. Try `1.0.5`, or switch to the jitpack coordinate, which is already an
allowed repository:

```toml
uvc = "1.0.7"
```
```toml
uvc-camera = { module = "com.github.shiyinghan:UVCAndroid", version.ref = "uvc" }
```

### `google-services.json is missing`

It should not fail — the plugin is applied only when the file exists. If it does
fail, check the file is at `android/phone/google-services.json` exactly.

---

## The TV cannot see the webcam

Watch it try:

```bash
adb logcat -s UvcCapturer:*
```

**`No USB devices are visible to the app at all`**
The TV is not doing USB host duty. Try the other USB port, and a powered hub. If
neither works, the port is storage-only and no software can change that — go to
the old-phone fallback below.

**`The device opened but is not a camera`**
The USB stack works but the kernel has no UVC driver. Confirm with:

```bash
adb shell ls -l /dev/video*
```

No such file means no UVC support compiled in. Nothing to be done without a
custom kernel.

**`USB permission was refused`**
A dialog appeared on the TV and was dismissed. Make the call again and accept it,
ticking "use by default for this device".

**`Camera opened but preview would not start`**
The webcam advertised a resolution it cannot deliver. The log line just above
lists what it claims to support; if 1280x720 is absent the app already picks the
closest, so this usually means a flaky cable or insufficient power — try a
powered hub.

**`No USB camera responded within 12s`**
Usually power. A 1080p webcam can draw more than a TV port supplies.

### The fallback

If the port genuinely cannot do cameras, mount an old Android phone near the TV
and run the **phone** app on it with autostart enabled. It becomes the camera,
mic and speaker; the TV becomes optional. This is worth doing without regret —
the audio will be better, because a phone's mic and speaker are designed to
cancel each other's echo and a webcam-plus-TV-speakers pairing is the worst case
for any echo canceller.

---

## The phone does not ring when locked

Check the server first:

```bash
sudo journalctl -u intercom -f
```

**`[fcm] DISABLED`** — the service account is not installed. See
[Firebase §2.3](02-firebase.md).

**`[fcm] failed … UNREGISTERED` or `INVALID_ARGUMENT`** — the token is stale.
Open the phone app once to re-register.

**`[fcm] delivered call …`** — Google accepted it, so the problem is on the
phone:

1. Battery optimisation. **Settings → Apps → Family Intercom → Battery →
   Unrestricted**, plus Autostart on Chinese ROMs. See
   <https://dontkillmyapp.com>.
2. Notification permission denied. Grant it and check the **Calls** channel is
   not muted — it must be at high importance or the full-screen intent is
   ignored.
3. Android 14+ restricts full-screen intents for apps that are not the default
   dialer. Check **Settings → Apps → Family Intercom → Notifications → Allow
   full-screen notifications**.
4. The app was force-stopped. An app killed with "Force stop" receives nothing
   until it is opened again — there is no way around this.

---

## The call rings but there is no picture or sound

This is nearly always ICE failing to find a path.

```bash
sudo tail -f /var/log/turnserver.log
```

- **No relay allocation appears** — the phone cannot reach coturn. Re-check the
  Oracle Security List (UDP 3478 *and* 49160–49360) and the VM's own iptables:
  `sudo iptables -L INPUT -n --line-numbers`.
- **Allocation succeeds but there is still no media** — usually the relay port
  range is closed while 3478 is open. Both are needed.
- Set `verbose=1` in `/etc/turnserver.conf` and restart coturn for detail.

On the device, `adb logcat -s WebRtcEngine:*` prints the selected candidate pair.
`typ relay` on both sides means it is going through the VM, which is expected on
CGNAT and works fine — it just uses your free egress allowance.

---

## Echo — he hears himself

Expected with a webcam mic and TV speakers: they are separate hardware with
independent clocks, which is the hardest case for any echo canceller. The app
already disables the TV's hardware canceller and uses WebRTC's own AEC3, which at
least sees both signals.

What actually helps, in order:

1. **Turn the TV volume down.** Most of the improvement is here.
2. **Move the webcam away from the speakers** — on top of the TV rather than
   beside a speaker grille.
3. **A USB speakerphone** (Anker PowerConf, Jabra Speak; ₹3–6k) does echo
   cancellation in hardware and solves it properly. This is the real fix, and if
   calls are unpleasant it is worth the money.

---

## The button does nothing

In order of likelihood:

1. **The accessibility service is off.** Open the app on the TV — if the red
   banner is showing, that is your answer. It switches itself off after some
   system updates.
2. **The service is on but the key is unbound.** Settings → Learn buttons.
3. **The app is not running.** The status line should say *Connected and ready*.
   Check with `adb shell dumpsys activity services com.kalathil.intercom.tv`.
4. **The key is one the app refuses to bind** (power, home, back, D-pad, volume).
   Pick a different key.

Watch a press land:

```bash
adb logcat -s ButtonService:*
```

No line at all means the service is not receiving keys — back to (1).

---

## The TV screen does not wake for a call

The app takes a wake lock and posts a full-screen intent. If the screen stays
dark, the direct activity start was blocked. Grant the exemption by hand:

```bash
adb shell appops set com.kalathil.intercom.tv SYSTEM_ALERT_WINDOW allow
```

Also confirm the **Calls** notification channel is at high importance; a demoted
channel makes Android ignore the full-screen intent silently.

---

## Stuck on "Connecting…" on one network, fine on another

Some Indian internet providers block or throttle `*.workers.dev` addresses,
on and off. When that happens, other sites load normally (even other Cloudflare
ones), but connections to your Worker's address time out. Seen on 8 Oct 2026: on
home Wi-Fi both apps sat on *Connecting…* and Chrome could not open
`/health`; the moment the phone switched to mobile data, both connected in under
a second.

To check, from any computer on the same network:

```bash
curl -m 20 https://<your-worker>.workers.dev/health
```

A timeout here, while `curl https://www.cloudflare.com` works, is the provider,
not the app. The apps give up on a stalled attempt after 30 seconds and keep
retrying, so they recover by themselves when the block lifts.

If it happens on **grandpa's** connection, his button is dead while it lasts.
The lasting fix is to serve the Worker from an address that is not on
`workers.dev`: a domain of your own added to Cloudflare (a cheap one costs a
few hundred rupees a year), attached under **Workers → your worker → Settings →
Domains & Routes**. Then change the server address on every device in Settings.

---

## Everything worked, then stopped after a power cut

The app restarts itself on boot, but only if it was configured. Check:

```bash
adb logcat -s BootReceiver:* TvApp:*
```

If the TV's own Wi-Fi came back later than the app did, the signalling client
retries with backoff and should recover within a minute. If the status line still
says *Offline — retrying*, the problem is the VM or DNS, not the TV:

```bash
curl https://yourname.duckdns.org/health
```

---

## The certificate expired

Certbot renews automatically and the deploy hook restarts both services. If it
lapsed:

```bash
sudo certbot renew --force-renewal
sudo systemctl restart coturn intercom
```

A DuckDNS record pointing at a changed IP will break renewal — Oracle public IPs
are stable unless you release them, but worth checking.
