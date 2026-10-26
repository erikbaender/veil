# Veil architecture

Veil is a privacy-focused Android app for Instagram. It shows Instagram's own website
(instagram.com) inside a hardened WebView and wraps it in a native shell: navigation,
settings, sharing and a cleaner, ad-free look. You log in on Instagram's real login page.
Veil never talks to Instagram except through that website.

## Modules

| Module | Kind | Contents |
|---|---|---|
| `:core` | KMP library (no UI) | settings model and storage, host allowlist, link cleaning, page-styling rules, release parsing for updates |
| `:ui` | KMP library (Compose Multiplatform) | theme, native shell (bottom bar, top bar, settings screen), WebView host (androidMain) |
| `:androidApp` | Android application | `Application`, `MainActivity`, intents (share/open links), self-updater, platform wiring |

`commonMain` stays free of JVM/Android APIs so an iOS app can later reuse the shell logic
with WKWebView.

## How it works

- **WebView host** (`ui/androidMain`): isolated storage for the app, JavaScript enabled,
  third-party cookies off, file uploads through the system Photo Picker, no geolocation,
  no camera/microphone grants, no remote debugging in release builds. Safe Browsing is off:
  only Instagram hosts load, so its lookups would only tell Google what you browse.
- **Host allowlist** (`core/web/HostPolicy`): the WebView may only load Instagram and its
  media CDNs. Every other request is blocked inside `shouldInterceptRequest`, which removes
  third-party trackers. Navigations to other sites open in the system browser after
  `LinkSanitizer` strips tracking parameters.
- **Page styling** (`core/web/PageStyle`): a stylesheet injected after each page load hides
  sponsored posts, optionally hides "Suggested for you" content, and tidies the layout so
  the site feels like part of the app. It changes appearance only.
- **Updater** (`core/update`, `androidApp/AppUpdater`): daily check of the latest GitHub
  release, download with checksum, size and signing-key checks, install through a
  `PackageInstaller` session that Android asks the user to confirm.
- **Unread messages** (`core/web/UnreadMessages`, `ui/site/UnreadCheck`,
  `androidApp/MessageAlerts`): the number on the site's own Messages link is read from the
  open pages for the tab's badge. If the user turns notifications on, a WorkManager job
  (15 minutes, Android's minimum) loads the start page in a hidden WebView with media
  blocked and reads the same number. When it is higher than the user already knew, it opens
  the inbox list (never a chat), reads the unread rows' names and latest lines and notifies;
  the lock-screen version shows only the count.
  It skips while the app is in front, and stops at sign-in or security-check pages until
  the user opens the app.
- **Native shell**: bottom bar (Home, Reels, Messages, Search, Profile) that drives the
  WebView, a top bar with settings, and a native settings screen.

## Privacy rules

1. No analytics, crash reporting or third-party SDKs.
2. Only Instagram hosts load. Everything else is blocked or handed to the browser. The one
   other contact is the update check: GitHub's API for this repository's latest release
   (no token, no device details) and, when the user accepts, the release's APK.
   With message notifications on, Instagram's start page also loads in the background
   about every 15 minutes (the same login, no media), and the inbox list when there is news.
3. No backups or device transfer of app data. Optional screenshot blocking.
4. No storage, contacts, location, camera or microphone permissions.
5. Shared links are cleaned of tracking parameters.

## Development rules

- Build with `./gradlew …`, or `tools/gradlew-locked …` to keep parallel builds from running at once.
- Unit tests in `commonTest` for settings, host allowlist, link cleaning, styling rules and releases.
- Test on a device or emulator with your own test account. Never interact with other people's
  accounts while testing.
