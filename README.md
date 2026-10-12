# Ondu for Android
The official Android client for [Ondu](https://ondumusic.com).

> [!WARNING]
> **Work in progress.** The Android app is an early pre-release that hasn't been
> tested on real phones yet, only on the emulator. Expect bugs, and don't rely on
> it as your only way to listen. Version 1.0 will be the first tested release.

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

No Google Play Services needed, so it also runs on de-Googled phones.

## Installing
Ondu isn't on Google Play. Download the APK of the newest version from the
[releases page](../../releases) on your phone and open it:

1. Android asks to allow installs from the app you opened it with (your browser
   or Files). Allow it, then go back.
2. Tap **Install**. Google Play Protect may say it doesn't recognise the
   developer; choose **More details → Install anyway**.

To update, install a newer APK the same way. Your library, downloads and sign-in
are kept.

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
