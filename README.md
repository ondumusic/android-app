# Ondu for Android
The official Android client for [Ondu](https://ondumusic.com).

> [!WARNING]
> **Work in progress.** The Android app is an early pre-release that hasn't been
> tested on real phones yet, only on the emulator. Use at your own risk.

## Disclaimer
This repo contains almost entirely LLM generated code and documentation. It is a
port of the [iPhone and Mac app](https://github.com/ondumusic/apple-app), which
was developed over months of iterations and manual testing on my own devices with
my real music library. This repo however is a fresh port of that, so things have 
not had real humans test this on real devices yet.

## Requirements
- Android 12 or later
- An Ondu server you host yourself, reachable over https, see the
  [setup guide](https://ondumusic.com/setup.html)

It does not use Google Play Services, so it also runs on de-Googled phones.

## Installing
Ondu isn't on Google Play right now. Since it's still early we don't have
official [releases](../../releases) yet, but you can find an APK in the
`Release` GitHub Action if you are willing to try.

## Privacy
The app only talks to the server you sign in to. There is no analytics, tracking
or crash reporting. See the [privacy policy](https://ondumusic.com/privacy.html).

## Building
You'll need Android Studio (or a JDK 17+ to run Gradle; the build downloads the
JDK 21 it compiles with).

```sh
./gradlew :app:assembleDebug   # or open the project in Android Studio and Run
```

Debug builds install as a separate app, **Ondu Dev**, next to the release. Release
builds and signing are described in [`docs/RELEASING.md`](docs/RELEASING.md).

## Contributing
See [CONTRIBUTING.md](CONTRIBUTING.md) for details.

## License
Ondu is distributed under the terms of the MIT license.

See [LICENSE](LICENSE) for details.
