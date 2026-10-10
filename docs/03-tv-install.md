# 3. TV app

Time: about 30 minutes, plus however long the webcam argument takes.

---

## 3.1 Enable developer mode and ADB

On the TV:

1. **Settings → System → About**.
2. Scroll to **Build** and click it **seven times**. It says you are now a
   developer.
3. Back to **Settings → System → Developer options**.
4. Turn on **USB debugging** and, if present, **Network debugging** / **ADB over
   network**.
5. Note the TV's IP: **Settings → Network & Internet → (your Wi-Fi) → IP address**.

On your computer, with `adb` installed (part of Android platform-tools):

```bash
adb connect <tv-ip>:5555
```

The TV shows an **Allow debugging?** dialog. Tick *always allow* and accept.

```bash
adb devices          # should list the TV as "device", not "unauthorized"
```

## 3.2 Install

```bash
adb install -r family-intercom-tv.apk
```

If it fails with `INSTALL_FAILED_USER_RESTRICTED`, the set blocks sideloading —
enable **Install unknown apps** in Developer options, or for that source in
Settings → Apps.

## 3.3 Configure

Open **Family Intercom** from the TV's home screen (it appears in the apps row).
The front screen only shows whether the TV is connected; the connection details
are one step in, so nothing sensitive sits on screen.

1. Press **Settings**.
2. **Server address** — your Worker's address, e.g.
   `wss://intercom.<your-subdomain>.workers.dev/ws` (or
   `wss://yourname.duckdns.org/ws` on your own VM)
3. **Home secret** — the `HOME_SECRET` you set on the server
4. **This TV's name** — what shows on everyone's phone when he calls. "Achacha's
   TV" beats "TV".
5. **Save and connect**, then **Back**.

The front screen's status should turn green: **Connected and ready**. If it says
**Wrong home secret**, the server refused the secret — check it in Settings.

Typing a 64-character secret with a remote is miserable. Paste it instead:

```bash
adb shell input text "3f9a8b7c..."
```

## 3.4 Enable the button service — do not skip this

The front screen shows a **red banner** until this is done, because without it
the call button does nothing while everything else looks perfectly healthy.

**Settings → Accessibility → Family Intercom call button → On.**

Android warns that the service can observe your actions. It reads key presses
and nothing else — it requests no screen-content events at all, which you can
check in `tv/src/main/res/xml/accessibility_service_config.xml`.

Why it is needed: on Android TV, key events reach only the foreground app. While
he is watching YouTube, YouTube gets the button press. This service is the only
supported way for the intercom to see the key from the background.

If the accessibility screen is hard to reach with the remote:

```bash
adb shell settings put secure enabled_accessibility_services \
  com.kalathil.intercom.tv/com.kalathil.intercom.tv.ButtonAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

## 3.5 Plug in the webcam

Plug the USB webcam into the TV. If the TV has only one USB port, use a powered
hub — a webcam and a macro pad together can exceed what the port supplies, and
under-powered USB on cheap TVs fails in confusing, intermittent ways.

Watch what happens:

```bash
adb logcat -s UvcCapturer:* IntercomService:*
```

Then press **Test call** on the front screen. You want:

```
UvcCapturer: selecting HD Webcam (vendor 0x..., product 0x...)
UvcCapturer: supported sizes: 640x480, 1280x720, 1920x1080
UvcCapturer: camera open, preview size 1280x720
```

Anything else is diagnostic. See
[troubleshooting](05-troubleshooting.md#the-tv-cannot-see-the-webcam).

## 3.6 Teach it the macro pad

A macro pad is just a USB keyboard, and the packaging never says which keycode
each key sends — so the app learns it instead of guessing.

1. Plug in the macro pad.
2. **Settings** → **Learn buttons**.
3. Press the key you want for each of, in order: **CALL**, **ANSWER**,
   **HANG UP**, **CAMERA ON/OFF**.

If you have a 1-key pad, bind CALL to it and leave the other three on the
remote's coloured buttons (the defaults: red = call, green = answer, blue = hang
up, yellow = camera). If the pad has software to program its keys, F13–F24 are
good choices — no other app uses them.

The app refuses to bind power, home, back, the D-pad or volume, since swallowing
those would make the TV unusable.

### Making it obvious

Whatever you use, label it. A big printed sticker saying who it calls, in
Malayalam, next to a single button, is worth more than any amount of on-screen
design. He should never have to remember which of four keys is which — consider
covering the ones you are not using.

## 3.7 Test the whole thing

1. Press the call button while the TV is showing something else entirely.
2. Every registered phone should ring.
3. Answer on one, then another — both join the same call, side by side on
   the TV.
4. Confirm two-way audio and video.
5. Press hang up.

Then the case that matters: **switch the TV off** (standby, not the wall socket),
and call it from your phone. The panel should wake and ring.

---

Next: **[Phone app](04-phone-install.md)**.
