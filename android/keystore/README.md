# Signing key

Android only installs an update over an app signed with the same key. So that
every build of yours signs the same way, make one key, once, and keep it.

## Make a key

From the `android/` folder (`keytool` comes with Java):

```bash
keytool -genkeypair -v -keystore keystore/release.jks -alias intercom \
  -keyalg RSA -keysize 2048 -validity 10000
```

Then create `keystore/release.properties` next to it:

```properties
storeFile=release.jks
storePassword=the password you chose
keyAlias=intercom
keyPassword=the password you chose
```

Both files are in `.gitignore`. **Never commit them to a public repository**:
anyone holding the key can sign an app that installs as an update over yours.

Without these files the build still works, signed with a debug key. That is fine
for trying the apps, but a later build signed with a different key will not
install over it; uninstall first.

## Building with GitHub Actions

Actions cannot see files that are not committed, so give it the key as secrets
instead (**Settings → Secrets and variables → Actions**):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the output of `base64 -w0 keystore/release.jks` |
| `KEYSTORE_PASSWORD` | the password, used for both the store and the key |
| `GOOGLE_SERVICES_JSON` | optional: the whole of `phone/google-services.json`, for push |

Anyone signed in to GitHub can download the build artifacts of a **public**
repository, and those APKs carry your Firebase settings. If you add these
secrets, keep your copy of the repository private.
