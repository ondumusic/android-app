# Ondu for Android

Kotlin + Jetpack Compose port of the iOS app at `../MusicBox` (SwiftUI). Same self-hosted
Elixir/Phoenix server, same sync-based architecture. The iOS repo is the reference for
behaviour: when porting a feature, read the Swift file first and keep the semantics,
including the edge cases its comments explain. Its `CLAUDE.md` describes the server API,
sync flow, outbox and playlists.

The UI is native Material 3, not a copy of the iOS look. Structure (tabs, screens, flows)
matches iOS; visuals, navigation idioms and controls follow Android conventions.

## Stack

- Compose + Material 3, Navigation 3, Material 3 adaptive (window size classes)
- Room for the synced library, DataStore for settings, Keystore-backed storage for credentials
- Media3 ExoPlayer + `MediaSessionService` for playback, notification and remote controls
- WorkManager for downloads
- OkHttp + kotlinx.serialization; okhttp-sse for the transcode prepare stream
- Coil for artwork

No Google Play Services dependency — the app must run on de-Googled devices.

## Platform targets

- `minSdk` 31 (Android 12), `targetSdk`/`compileSdk` 37. Newer-OS features are gated on
  `Build.VERSION.SDK_INT`, not a reason to raise `minSdk`.
- Phone first. Layouts must still tolerate any window size (Android 16+ ignores
  orientation and resizability locks on large screens).

## Build variants

|            | debug                      | release              |
| ---------- | -------------------------- | -------------------- |
| App ID     | `com.ondumusic.player.dev` | `com.ondumusic.player` |
| Name       | Ondu Dev                   | Ondu                 |
| Cleartext  | allowed                    | refused              |

Debug has its own ID so a build mid-migration can't damage the library you listen to.

## Code conventions

- Follow the iOS repo's comment rules: comment WHY, never WHAT; no references to past
  tasks or PRs; no backwards-compat shims or speculative validation.
- No `println`/`Log` in production paths except behind `BuildConfig.DEBUG`.
- Never force logout on auth errors — show re-auth. Never delete user data without consent.
- User-facing strings go in `res/values/strings.xml`; counts use `<plurals>`.
- Maintain tests with code changes. Port the matching iOS tests (`../MusicBox/OnduTests`)
  when porting logic; unit tests live in `app/src/test`.

## Commits

Small and focused, one coherent change each. Subject `area: what changed`, imperative,
terse, no trailing period. Body only for a non-obvious reason, a sentence or two.
`.githooks/commit-msg` rejects generic subjects; it runs via `git config core.hooksPath
.githooks` (set that in a fresh clone).

## Build & test

Gradle runs on the JDK bundled with Android Studio; compilation and tests use a JDK 21
toolchain that Gradle downloads itself (Robolectric breaks on 25). The SDK lives at
`~/Library/Android/sdk`.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug          # build
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:installDebug           # install on running emulator/device

~/Library/Android/sdk/emulator/emulator -avd Phone &   # Pixel 9, Android 17
```

Judge a build by its exit code.
