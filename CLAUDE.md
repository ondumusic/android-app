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
- Room for the synced library; SharedPreferences for settings (read synchronously at
  startup, like UserDefaults); the session token encrypted with an Android Keystore key
- Media3 ExoPlayer + `MediaSessionService` for playback, notification and remote controls
- WorkManager for downloads
- OkHttp + kotlinx.serialization; okhttp-sse for the transcode prepare stream
- Coil for artwork

No Google Play Services dependency — the app must run on de-Googled devices.

## Architecture

Services are plain classes built once in `AppContainer` (the Android counterpart of the
iOS app's environment objects) and read in Compose through `LocalContainer` / `LocalSession`.
They expose state as `StateFlow`s; screens collect them.

- `api/` — `ApiClient` (OkHttp + kotlinx.serialization), wire types (`SyncDtos`, `ApiBodies`),
  typed errors, `ErrorMessages` (user-facing wording as `UiText`).
- `auth/` — `AuthService` (optimistic restore, background validation, re-auth; never logs
  out on its own), `TokenStore` (Keystore-encrypted token), `ServerAddress` (login URL rules).
- `data/` — Room `LibraryDatabase`, DAOs, and `MusicLibrary`: every browse query (Room flows,
  joined in SQL), sync upserts that never touch local-only columns, optimistic playlist writes.
- `service/` — `SyncService` (cursor paging; `Hooks` for outbox/downloads/artwork), outbox
  (`service/outbox/`), `PlaylistController`, `NetworkMonitor`, `SmartShuffle`, `FractionalIndex`.
- `player/` — `PlayerService` (queue, shuffle/repeat, gapless-vs-crossfade decision, loudness,
  play counting, persistence) over a `PlaybackEngine`; `ExoPlaybackEngine` (two ExoPlayers,
  Media3 disk cache); `SessionPlayer` + `PlaybackService` expose it to the system media
  session. The player is process-wide; a re-auth hands it a new token rather than replacing it.
- `ui/` — `MainScreen` (five tabs with their own back stacks via Navigation 3, mini player,
  full-player overlay), `ui/components` (shared rows, artwork, action sheets — the Android form
  of iOS context menus), one package per area. Strings live in per-area `res/values/strings_*.xml`.

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
./gradlew :app:lintDebug              # Android Lint; app/build/reports/lint-results-debug.html
./gradlew :app:installDebug           # install on running emulator/device

~/Library/Android/sdk/emulator/emulator -avd Phone &   # Pixel 9, Android 17
```

Judge a build by its exit code.

### Release builds

`docs/RELEASING.md` covers the signing key, the release commit (a dated `CHANGELOG.md` section
plus a raised `versionCode`/`versionName`), tagging, checking, archiving and distribution.
Pushing a version tag (`0.2.0`) runs `.github/workflows/release.yml`, which builds, signs and publishes the
GitHub release; `.github/workflows/ci.yml` runs the unit tests and lint on every push and pull request.
Release builds are shrunk with R8, so check one on a device before it's shared. Locally,
signing reads `keystore.properties` in the repository root, which git ignores.
