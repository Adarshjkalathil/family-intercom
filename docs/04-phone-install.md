# 4. Phone app

Repeat for each family member. Five minutes each.

---

## 4.1 Install

Send them `family-intercom-phone.apk` (WhatsApp, Drive, email). They open it and
allow installing from that source when prompted.

All the APKs are signed with the same key, so later versions install straight
over the top without uninstalling.

## 4.2 Configure

1. Open **Family Intercom**.
2. Grant **camera**, **microphone** and **notifications** when asked. Declining
   notifications means the phone will not ring.
3. Tap **Set up** (or the ⚙ gear at the top right), and fill in:
   - **Server address** — the same address as the TV
   - **Home secret** — the same secret as the TV
   - **Your name** — what appears on his screen. Use the name he calls you.
4. **Save.** The home screen should read **Connected**, with the TV listed
   underneath as **Online** once the TV app is running.

> Anyone with the home secret can register a device. Send it over something
> private, and if a phone is ever lost, regenerate it: edit `HOME_SECRET` in
> `/etc/intercom/intercom.env` on the VM, restart the service, and re-enter the
> new secret everywhere.

## 4.3 Exempt it from battery optimisation

This is the difference between a reliable intercom and one that misses calls.
Android aggressively suspends apps it thinks are idle, and the offenders —
Xiaomi, Realme, OnePlus, Oppo, Vivo, Samsung — go further than stock Android.

**Settings → Apps → Family Intercom → Battery → Unrestricted.**

On Chinese ROMs also find **Autostart** (or "Auto-launch") and enable it, and
lock the app in the recents screen if the ROM offers that.

<https://dontkillmyapp.com> has exact steps per manufacturer. It is worth two
minutes now rather than discovering the problem during an emergency.

## 4.4 The three buttons

| Button | What it does |
|---|---|
| **Call the TV** | Rings the TV. He accepts with the green button. If someone in the family is already talking to him, this reads **Join the call** and adds you to it. |
| **Put me on the TV** | The TV answers by itself and shows you full-screen. For when you want to just appear and say hello. |
| **Check in** | The TV answers by itself, camera on, so you can see he is all right. |

The last two open his camera or screen without him doing anything. The TV plays a
chime and draws a red border around the whole screen for as long as the camera is
live, and he can switch the camera off with a held key.

**Tell him these exist before you install it.** Not because the software requires
it, but because finding out later that people can look in on him is the kind of
thing that makes someone unplug the whole device — and he would be right to.

## 4.5 Test properly

Do all four:

1. App open, phone unlocked — he calls, you answer.
2. App closed, phone locked — he calls. It should ring full-screen. *This is the
   one that matters.*
3. **Wi-Fi off, mobile data only** — proves the relay path works from outside.
4. Two phones at once — both ring, and answering on both puts everyone on one
   call, with each person's picture and name on the TV.

If (2) fails, see
[troubleshooting](05-troubleshooting.md#the-phone-does-not-ring-when-locked).
