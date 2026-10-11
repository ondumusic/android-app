# Releasing Ondu for Android

How to make a build that other people install: the signing key, the release commit, building
on GitHub, checking, archiving and handing it out. Ondu is distributed as an APK
(sideloaded), not through Google Play.

The short version, once the setup in step 1 is done:

1. One commit that adds the version's `CHANGELOG.md` section and raises the version.
2. `git tag -a 0.2.0 -m "Ondu 0.2.0" && git push && git push --tags`.
3. GitHub builds, signs and publishes the release. Download the APK, check it, share it.

Local commands assume the repository root and Android Studio's bundled JDK:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$HOME/Library/Android/sdk/build-tools/37.0.0:$HOME/Library/Android/sdk/platform-tools:$PATH"
```

## 1. Setup (once)

### The signing key

Android only installs an update over an existing app if both are signed with the **same
key**. There is no recovery: lose the key and every user has to uninstall Ondu, losing their
downloads and settings, before they can install a build signed with a new one. Treat it like
the master password to the app.

Create it outside the repository, for example in `~/Keys/`:

```bash
mkdir -p ~/Keys
keytool -genkeypair -v \
  -keystore ~/Keys/ondu-release.jks \
  -alias ondu \
  -keyalg RSA -keysize 4096 \
  -validity 36500 \
  -dname "CN=Ondu"
```

`keytool` asks for a password, twice. Make it strong. The keystore format it creates
(PKCS12) protects the key with that same password, so the store and key passwords below are
identical. The 100-year validity is deliberate: a key can't be renewed without the same
problem as losing it.

### Back it up

Before anything is shared, store **both the `.jks` file and its password** in at least two
places that don't fail together, for example a password manager (as an attachment) plus an
encrypted offline copy. GitHub's copy (below) doesn't count: secrets can't be read back.
Check the backup opens:

```bash
keytool -list -v -keystore /path/to/backup/ondu-release.jks -alias ondu
```

Note the `SHA-256` fingerprint it prints. It identifies genuine Ondu builds (see step 4).

### GitHub secrets

The release workflow signs with the key from four repository secrets (on GitHub: Settings →
Secrets and variables → Actions → New repository secret):

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | the `.jks` file as text: `base64 -i ~/Keys/ondu-release.jks \| pbcopy`, then paste |
| `RELEASE_STORE_PASSWORD` | the password |
| `RELEASE_KEY_PASSWORD` | the same password |
| `RELEASE_KEY_ALIAS` | `ondu` |

### Local signing (optional)

Only needed to build a signed release on your own machine (step 7). Create
`keystore.properties` in the repository root. Git ignores it, so it never gets committed:

```properties
storeFile=/Users/you/Keys/ondu-release.jks
storePassword=…
keyAlias=ondu
keyPassword=…
```

Without it, a local `assembleRelease` still builds, but the APK is unsigned and can't be
installed.

## 2. The release commit

Each release is one commit that does three things.

**Add the changelog section.** In `CHANGELOG.md`, the new version goes at the top, dated:

```markdown
## [0.2.0] - 2026-11-02

- What changed, written for the people installing it.
```

This section becomes the GitHub release's notes. Keep an `## [Unreleased]` section at the
top while working, if that helps, and rename it in the release commit.

**Raise the version.** Both numbers are in `app/build.gradle.kts`, under `defaultConfig`:

```kotlin
versionCode = 2
versionName = "0.2.0"
```

- **`versionCode`**: a whole number Android uses to tell builds apart. It goes **up by one
  for every release** and never back down. Android refuses to install an update whose code
  isn't higher than the installed one. Users never see it.
- **`versionName`**: what people see, in system settings, as `major.minor.patch`:
  - patch (`0.1.0` → `0.1.1`) for fixes only,
  - minor (`0.1.1` → `0.2.0`) for new features,
  - major for the first public release (`1.0.0`) and anything that breaks expectations.

  Stay below `1.0.0` while it's in testing.

**Commit and tag:**

```bash
git commit -am "release: 0.2.0"
git tag -a 0.2.0 -m "Ondu 0.2.0"
git push && git push --tags
```

## 3. GitHub builds the release

Pushing a version tag (`0.2.0`, no `v`, as for the server) runs `.github/workflows/release.yml`. It:

1. checks the tag matches `versionName`, and that `CHANGELOG.md` has a section for it;
   either missing stops the release before anything is built,
2. runs the unit tests,
3. builds the release APK, signed with the key from the secrets,
4. checks it isn't signed with a debug key,
5. publishes a GitHub release named after the tag, with that changelog section as notes and
   `ondu-0.2.0.apk` plus its SHA-256 checksum attached,
6. keeps `mapping.txt` with the workflow run (see step 5).

Follow it under the repository's **Actions** tab. When it's done, the APK is on the
**Releases** page. A private repository works the same way: you download the APK from the
release page and share the file.

If the workflow fails before publishing, fix the problem, then move the tag to the fixed
commit and push it again:

```bash
git tag -d 0.2.0 && git push origin :refs/tags/0.2.0
git tag -a 0.2.0 -m "Ondu 0.2.0" && git push --tags
```

Once a release is published and shared, never move its tag. Make a new version instead.

## 4. Verify before sharing

Release builds are shrunk and obfuscated by R8. Code that works in debug can break there,
typically JSON parsing, the database or playback. So try every release on a device or
emulator before it goes out:

```bash
apksigner verify --print-certs ondu-0.2.0.apk
adb install -r ondu-0.2.0.apk
```

The certificate's SHA-256 must match the key's fingerprint from step 1. Then, in the app:

- sign in, let the first sync finish, check artwork appears
- play a song, skip, open the full player and the queue
- download a song and play it in airplane mode
- add a song to a playlist and remove it again
- open Settings

`adb install -r` over the previous release is also the check that the key and version code
are right. If it fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (wrong key) or
`INSTALL_FAILED_VERSION_DOWNGRADE` (code not raised), don't share it.

The release build has the ID `com.ondumusic.player` and the name "Ondu". It installs
alongside the debug build ("Ondu Dev", `com.ondumusic.player.dev`) without touching it.

## 5. Archive

The release page keeps the APK and its checksum. One file needs saving by hand:
**`mapping.txt`**. Without it, crash stack traces from that build can't be read. The
workflow keeps it with the run for 90 days only: download the `mapping-0.2.0` artifact from
the run's summary page and keep it, for example in `~/Ondu Releases/0.2.0/`.

To decode a crash trace from that build:

```bash
~/Library/Android/sdk/cmdline-tools/latest/bin/retrace mapping.txt stacktrace.txt
```

## 6. Distribute

Send the APK file directly: AirDrop to a Mac and on, a messenger, a cloud drive link. On
the phone:

1. Open the file. Android asks to allow installs from that app (Files, Chrome, the
   messenger). Allow it, then go back.
2. Tap **Install**. Google Play Protect may warn that it doesn't recognise the developer.
   Choose **More details → Install anyway**, since sideloaded apps aren't registered with
   Google.
3. Updates: open the newer APK the same way. It installs over the old one and keeps the
   library, downloads and sign-in, as long as it's signed with the same key and has a
   higher `versionCode`.

Testers need Android 12 or later. Nothing tells them a new version exists, so let them know
when you send one.

## 7. Building locally instead

If GitHub isn't available, the same build runs on your machine with `keystore.properties`
in place (step 1):

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease
```

The APK is `app/build/outputs/apk/release/app-release.apk` (rename it to
`ondu-0.2.0.apk`), and the map is `app/build/outputs/mapping/release/mapping.txt`. Verify,
archive and distribute as above.

## Continuous integration

Separately from releases, `.github/workflows/ci.yml` runs the unit tests, Android Lint and a debug build
on every push to `main` and on every pull request. A red run there means fix before
tagging; the release workflow runs the tests again anyway and won't publish if they fail.

## Checklist

- [ ] CI is green on the commit to release
- [ ] `CHANGELOG.md` has a dated section for the version
- [ ] `versionCode` raised, `versionName` set, committed, tagged, pushed
- [ ] The release workflow published the release
- [ ] `apksigner verify` shows the release key's fingerprint
- [ ] Installed over the previous release and smoke-tested (step 4)
- [ ] `mapping.txt` downloaded and archived
- [ ] Shared
