# Veil

<img src="docs/veil-icon.svg" width="96" alt="Veil icon: a lens seen through a sheer veil whose hem dips into a V">

A privacy-focused Android app for Instagram. Veil shows Instagram's own website inside a
hardened WebView and wraps it in a native shell, with ads and third-party trackers blocked.

## What you get

- **Native shell:** an Instagram-style bottom bar for Home, Reels, Messages, Search and
  Profile (your own profile, found automatically). Long-press Profile for Veil's settings.
- **No ads:** feed posts labelled as ads ("Ad"/"Sponsored") are hidden. Suggested posts and suggested accounts
  can be hidden too (Settings → Feed).
- **Only Instagram:** the WebView may only load Instagram and its media CDNs. Everything
  else (trackers, third-party scripts) is blocked. Links to other sites open in your
  browser with tracking parameters removed.
- **Private by default:** isolated WebView storage, no third-party cookies, no backups or
  device transfer of app data, no WebView metrics, no geolocation/camera/microphone access,
  uploads through the system Photo Picker (no storage permission), optional screenshot
  blocking, one-tap sign out that wipes cookies, storage and cache.
- **Links:** Instagram links from other apps (or shared text containing one) open in Veil.
- **Save Reels:** a bookmark under Share in the Reels viewer, which the website lacks. It uses
  Instagram's own Save button on the reel's page, opened in a hidden view with your login.

## Build

Requirements: JDK 17+ and the Android SDK (compileSdk 37).

```bash
./gradlew :androidApp:assembleDebug     # debug build, app id app.veil.client.debug
./gradlew :androidApp:assembleRelease   # minified release build (release key if configured, see below)
./gradlew :core:testAndroidHostTest     # unit tests
```

APKs end up in `androidApp/build/outputs/apk/`. Install with `adb install <apk>`, or get the APK from
the GitHub releases.

To make Instagram links open in Veil by default, enable it under
*Settings → Apps → Veil → Open by default*.

Release builds are signed with the key described in `~/.config/veil/signing.properties` (or the
file named by `VEIL_SIGNING`): `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Without it
they fall back to the debug key, and such builds can't update an installed release.

### Versions

Versions are dates: `year.month.release` (e.g. `2026.10.0`, then `2026.10.1`, `2026.11.0`). Veil
runs on a website that changes under it, so how recent a build is matters more than anything a
semantic version could say. The Android version code is the same date as a number (`20261000`).
Set it in `androidApp/build.gradle.kts`; tag releases `v<version>`.

## Notes and limitations

- Veil is the Instagram website in a native frame, so features and layout are whatever the
  website offers. Hiding of ads and suggested posts relies on their visible labels
  (English and German).
- Background notifications aren't supported; the app only talks to Instagram while open.
- Requires Android 12 (API 31) or newer and an up-to-date Android System WebView.

## Project layout

See [ARCHITECTURE.md](ARCHITECTURE.md). `core` holds platform-independent logic (settings,
host allowlist, link cleaning, page styling); `ui` holds the Compose shell and the WebView
host; `androidApp` is the Android application.

## License

Veil is free software under the [GNU General Public License v3.0](LICENSE): you may use, study,
change and share it, and anything built from it must be shared under the same terms.
Security issues: see [SECURITY.md](SECURITY.md).

Veil is not affiliated with or endorsed by Instagram or Meta.
