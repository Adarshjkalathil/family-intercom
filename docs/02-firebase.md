# 2. Firebase (push only)

Without this, the phone app only rings while it is open on screen. With it, a
locked phone in a pocket rings from anywhere.

**This does not require the Blaze plan or a card.** Cloud Functions would, and we
use none — the server sends the pushes itself using a service account. Plain FCM
is free.

Time: about 15 minutes.

---

## 2.1 Create the project

1. <https://console.firebase.google.com> → **Create a project**.
2. Name it anything (`family-intercom`). Google Analytics: **off** — nothing here
   needs it.

## 2.2 Register the phone app

1. In the project, click the **Android** icon (or **Add app → Android**).
2. Package name: **`com.kalathil.intercom.phone`** — it must match exactly, or
   pushes are silently rejected.
3. Nickname and SHA-1: skip both.
4. **Download `google-services.json`.** Skip the remaining "add the SDK" steps;
   the project already has them.

Put that file at:

```
android/phone/google-services.json
```

Commit and push. CI picks it up automatically — the build log says
`google-services.json found: push notifications enabled`. Unlike the service
account below, this file only identifies your Firebase project to the app, so it
is fine in a private repository.

Push support is compiled into the app, so **install the newly built phone APK**
over the old one (the `apks` artifact from that CI run), then open the app once so
it registers its push token with the server.

> The TV app needs none of this. It holds a socket open permanently, so it never
> needs waking.

## 2.3 Give the server the service account

This is how the server is allowed to send pushes.

1. Firebase console → gear icon → **Project settings** → **Service accounts**.
2. **Generate new private key** → confirm. A JSON file downloads.

> That JSON is a credential that can send pushes to your users. Never commit it
> or send it to anyone. Once the server has it, delete the downloaded copy.

### If your server is on Cloudflare

From the `cloudflare/` folder:

```bash
npx wrangler secret put FCM_SERVICE_ACCOUNT < ~/Downloads/family-intercom-firebase-adminsdk-XXXX.json
```

Then check:

```bash
curl https://<your-worker>.workers.dev/health
```

You want `"push":"ready"`. `"push":"disabled"` means the secret is missing or is
not the complete JSON file.

### If your server is your own VM

```bash
scp ~/Downloads/family-intercom-firebase-adminsdk-*.json \
    ubuntu@<vm-ip>:/tmp/fcm.json

ssh ubuntu@<vm-ip>
sudo mv /tmp/fcm.json /etc/intercom/fcm-service-account.json
sudo chown root:intercom /etc/intercom/fcm-service-account.json
sudo chmod 640 /etc/intercom/fcm-service-account.json
sudo systemctl restart intercom
sudo journalctl -u intercom -n 20 | grep fcm
```

You want `[fcm] ready for project <your-project-id>`. If you see
`[fcm] DISABLED`, the file is missing or unreadable by the `intercom` user.

## 2.4 Verify

With the new phone app installed and configured:

1. Force-close the phone app and lock the screen.
2. Press the button on the TV.
3. The phone should ring within a couple of seconds, full-screen, over the lock
   screen.

If it does not, watch the server log while you try — `npx wrangler tail` on
Cloudflare, or `sudo journalctl -u intercom -f` on a VM.

`[fcm] delivered call …` means Google accepted it and the problem is on the
phone — usually battery optimisation. See
[troubleshooting](05-troubleshooting.md#the-phone-does-not-ring-when-locked).

---

Next: **[TV app](03-tv-install.md)**.
