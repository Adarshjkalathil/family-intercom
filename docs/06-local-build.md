# 6. Building with GitHub Actions

No Android SDK on your machine, no Gradle caches, nothing to install. The SDK
alone is 10–15 GB; CI already has it.

---

## First, spend one minute saving yourself an hour

The Android code has never been compiled — it was written with no access to
Google's Maven repositories — so two dependency versions in
`android/gradle/libs.versions.toml` are educated guesses. If either is wrong the
build dies at resolution, and without a local toolchain each guess costs a
five-minute CI run.

Settle it before you push. This needs only Python and a few HTTPS requests — no
disk, no SDK:

```bash
python3 tools/check-deps.py
```

It asks Maven Central, Google's repo and JitPack what versions actually exist,
and if a pin is wrong it prints the exact line to change:

```
  com.herohan:UVCAndroid
    currently: uvc = "1.0.7"
    change to: uvc = "1.0.5"
    recent: 1.0.5, 1.0.4, 1.0.3
```

Edit that one line in `android/gradle/libs.versions.toml`, then push.

While you are there, `python3 tools/preflight.py` statically checks every
resource and manifest reference. Both run in CI too, but running them locally
costs nothing and saves a round trip.

---

## Push it

The repo is about 130 KB, so this costs no meaningful disk.

```bash
git init
git add .
git commit -m "Family intercom"
git branch -M main
git remote add origin git@github.com:<you>/family-intercom.git
git push -u origin main
```

Make your copy **private** once you add anything of your own to it: a signing
key, your Firebase settings, or notes with your home secret. See
[android/keystore/README.md](../android/keystore/README.md).

If git is awkward, GitHub's web UI accepts a dragged folder at
**Add file → Upload files**.

## Get the APKs

1. **Actions** tab → the run starts by itself.
2. When it finishes, scroll to **Artifacts** → download **apks**.
3. Inside: `family-intercom-tv.apk` and `family-intercom-phone.apk`.

With your own signing key added as secrets
([android/keystore/README.md](../android/keystore/README.md)), every build signs
the same way and installs straight over the last. Without one they use a
throwaway debug key, so uninstall before installing a newer build.

## When it fails

It probably will, the first time. The job is arranged to tell you as much as
possible per run: the dependency check and pre-flight checks report but do not
stop the build, and Gradle runs with `--continue` so one module's failure still
surfaces the other's errors.

Read the log top to bottom:

| Where it failed | What to do |
|---|---|
| `Check dependency versions exist` | It prints the line to change. Do that first — everything below it is probably noise. |
| `Pre-flight` | A missing resource or an unregistered service, with file and line. |
| `Could not resolve …` in Gradle | The dependency check missed it. Same fix, in `libs.versions.toml`. |
| Kotlin compile errors in `UvcCameraCapturer.kt` | Most likely place for real work — see below. |
| Everything else | Download the **build-reports** artifact; the full Gradle report is in it. |

### The file most likely to need work

`tv/src/main/java/…/UvcCameraCapturer.kt` was written against the UVCAndroid
library's documentation rather than against a compiler. The approach is sound —
frames go to a SurfaceTexture from WebRTC's `SurfaceTextureHelper`, staying on
the GPU instead of being copied through the CPU — but `ICameraHelper`'s method
names and the `StateCallback` signatures may not match the version that
resolves. Expect to correct names there, not logic.

### One thing not to do

Do not change `core/Protocol.kt` without changing `server/src/index.js` to
match. They are two halves of one contract, pinned from both sides by
`ProtocolTest.kt` and `server/test/smoke.js`. "Fixing" a field name to clear a
compile error is how you end up with calls that ring but never connect, which is
miserable to debug from another city.

## Getting it onto the TV

Downloading two APKs costs a few megabytes. To install without `adb`:

- Put them in Google Drive, open Drive on the TV, install from there, or
- Use **Send Files to TV** (on the Play Store for both Android TV and phones),
  which needs no cable and no PC.

`adb` is still the better route if you have it, because
`adb logcat -s UvcCapturer:*` is how you find out whether the webcam works —
see [docs/03-tv-install.md](03-tv-install.md).
