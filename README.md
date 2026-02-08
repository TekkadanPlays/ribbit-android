# Ribbit (Android)

A Nostr client for Android built with Jetpack Compose and Material Design 3. Browse the relay feed, view threads and topic replies (kind-1 and kind-1111), manage relays, and receive notifications for replies, likes, reposts, and zaps.

## Prerequisites

- **JDK 11** or later
- **Android SDK** (API 35+)
- Android Studio or command-line build tools

## Build

Release APK:

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

## Install

```bash
adb install app/build/outputs/apk/release/app-release.apk
```

## Obtanium

You can install or update Ribbit via [Obtanium](https://obtanium.com/) using the app's update manifest:

- **Raw URL:** `https://raw.githubusercontent.com/TekkadanPlays/ribbit-android/main/obtanium.json`

Add this URL in Obtanium to get release updates and download the APK from GitHub Releases.

## License

MIT — see [LICENSE](LICENSE).
