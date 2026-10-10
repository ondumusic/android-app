# Releasing Ondu for Android

How to make a build that other people install: the signing key, version numbers, building,
checking, archiving and handing it out. Ondu is distributed as an APK (sideloaded), not
through Google Play.

Commands assume the repository root and Android Studio's bundled JDK:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$HOME/Library/Android/sdk/build-tools/37.0.0:$HOME/Library/Android/sdk/platform-tools:$PATH"
```

## 1. The signing key (once)

Android only installs an update over an existing app if both are signed with the **same
key**. There is no recovery: lose the key and every user has to uninstall Ondu — losing
their downloads and settings — before they can install a build signed with a new one. Treat
it like the master password to the app.

### Create it

Keep it outside the repository, for example in `~/Keys/`:

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
(PKCS12) protects the key with that same password, so `storePassword` and `keyPassword`
below are identical. The 100-year validity is deliberate: a key can't be renewed without
the same problem as losing it.

### Back it up

Before anything is shared, store **both the `.jks` file and its password** in at least two
places that don't fail together, for example a password manager (as an attachment) plus an
encrypted offline copy. Check the backup opens:

```bash
keytool -list -v -keystore /path/to/backup/ondu-release.jks -alias ondu
```

Note the `SHA-256` fingerprint it prints. It identifies genuine Ondu builds (see Verify).

### Point the build at it

Create `keystore.properties` in the repository root. Git ignores it, so it never gets
committed:

```properties
storeFile=/Users/you/Keys/ondu-release.jks
storePassword=…
keyAlias=ondu
keyPassword=…
```

Without this file, `assembleRelease` still builds, but the APK is unsigned and can't be
installed.

## 2. Version numbers (every shared build)

Both are in `app/build.gradle.kts`, under `defaultConfig`:

```kotlin
versionCode = 1
versionName = "1.0"
```

- **`versionCode`**: a whole number Android uses to tell builds apart. It must go **up by
  at least one for every build you hand out**. Android refuses to install an update whose
  code isn't higher than the installed one. It is never shown to users, and it never goes
  back down.
- **`versionName`**: what people see, in system settings. Use `major.minor.patch`:
  - patch (`0.1.0` → `0.1.1`) for fixes,
  - minor (`0.1.1` → `0.2.0`) for new features,
  - major for the first public release (`1.0.0`) and anything that breaks expectations.

  Stay below `1.0.0` while it's in testing.

Commit the bump on its own, then tag that commit so a build can always be traced back to its
source:

```bash
git commit -am "build: version 0.2.0 (3)"
git tag -a v0.2.0 -m "Ondu 0.2.0 (3)"
```

## 3. Build

```bash
./gradlew :app:testDebugUnitTest      # tests pass first
./gradlew :app:assembleRelease
```

Output:

- `app/build/outputs/apk/release/app-release.apk` is the signed APK to share.
- `app/build/outputs/mapping/release/mapping.txt` is the R8 map. Keep it (see Archive).

The release build has the ID `com.ondumusic.player` and the name "Ondu". It installs
alongside the debug build ("Ondu Dev", `com.ondumusic.player.dev`) without touching it.

## 4. Verify before sharing

Release builds are shrunk and obfuscated by R8. Code that works in debug can break there,
typically JSON parsing, the database or playback. So try every build on a device or emulator
before it goes out:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

The certificate's SHA-256 must match the key's fingerprint from step 1. Then, in the app:

- sign in, let the first sync finish, check artwork appears
- play a song, skip, open the full player and the queue
- download a song and play it in airplane mode
- add a song to a playlist and remove it again
- open Settings

`adb install -r` over an earlier release build is also the check that the key and version
code are right. If it fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (wrong key) or
`INSTALL_FAILED_VERSION_DOWNGRADE` (code not raised), don't ship.

## 5. Archive

For each shared build, keep these together, for example in `~/Ondu Releases/0.2.0 (3)/`:

- `ondu-0.2.0.apk`: the exact file you shared.
- `mapping.txt`: without it, crash stack traces from that build are unreadable. Decode
  one with `retrace mapping.txt stacktrace.txt` (from the Android SDK's
  `cmdline-tools/latest/bin`).
- A note of the git tag and the SHA-256 of the APK (`shasum -a 256 ondu-0.2.0.apk`).

## 6. Distribute

### To a tester

Send the APK file (renamed to `ondu-0.2.0.apk`) directly: AirDrop to a Mac and on, a
messenger, a cloud drive link. On the phone:

1. Open the file. Android asks to allow installs from that app (Files, Chrome, the
   messenger). Allow it, then go back.
2. Tap **Install**. Google Play Protect may warn that it doesn't recognise the developer.
   Choose **More details → Install anyway**, since sideloaded apps aren't registered with
   Google.
3. Updates: open the newer APK the same way. It installs over the old one and keeps the
   library, downloads and sign-in, as long as it's signed with the same key and has a
   higher `versionCode`.

Testers need Android 12 or later.

### Later: updates without sending files

Nothing tells a sideloaded app's users that a new version exists. The easy route is a
**GitHub release** per version, with the APK attached. Testers install
[Obtainium](https://obtainium.imranr.dev), an open-source updater that needs no Google
account, and add the repository's URL. Obtainium checks for new releases and installs them.
A private repository works too, with a token in Obtainium.

## Checklist

- [ ] Tests pass
- [ ] `versionCode` raised, `versionName` set, committed and tagged
- [ ] `assembleRelease` with `keystore.properties` present
- [ ] `apksigner verify` shows the release key's fingerprint
- [ ] Installed over the previous release and smoke-tested (step 4)
- [ ] APK, `mapping.txt` and checksum archived
- [ ] Shared
