# Veil architecture

Veil is a privacy-focused Android app for Instagram. It shows Instagram's own website
(instagram.com) inside a hardened WebView and wraps it in a native shell: navigation,
settings, sharing and a cleaner, ad-free look. You log in on Instagram's real login page.
Veil never talks to Instagram except through that website.

## Modules

| Module | Kind | Contents |
|---|---|---|
| `:core` | KMP library (no UI) | settings model and storage, host allowlist, link cleaning, page-styling rules |
| `:ui` | KMP library (Compose Multiplatform) | theme, native shell (bottom bar, top bar, settings screen), WebView host (androidMain) |
| `:androidApp` | Android application | `Application`, `MainActivity`, intents (share/open links), platform wiring |

`commonMain` stays free of JVM/Android APIs so an iOS app can later reuse the shell logic
with WKWebView.

## How it works

- **WebView host** (`ui/androidMain`): isolated storage for the app, JavaScript enabled,
  third-party cookies off, file uploads through the system Photo Picker, no geolocation,
  no camera/microphone grants, safe browsing on, no remote debugging in release builds.
- **Host allowlist** (`core/web/HostPolicy`): the WebView may only load Instagram and its
  media CDNs. Every other request is blocked inside `shouldInterceptRequest`, which removes
  third-party trackers. Navigations to other sites open in the system browser after
  `LinkSanitizer` strips tracking parameters.
- **Page styling** (`core/web/PageStyle`): a stylesheet injected after each page load hides
  sponsored posts, optionally hides "Suggested for you" content, and tidies the layout so
  the site feels like part of the app. It changes appearance only.
- **Native shell**: bottom bar (Home, Reels, Messages, Search, Profile) that drives the
  WebView, a top bar with settings, and a native settings screen.

## Privacy rules

1. No analytics, crash reporting or third-party SDKs.
2. Only Instagram hosts load. Everything else is blocked or handed to the browser.
3. No backups or device transfer of app data. Optional screenshot blocking.
4. No storage, contacts, location, camera or microphone permissions.
5. Shared links are cleaned of tracking parameters.

## Development rules

- Build with `tools/gradlew-locked …`.
- Unit tests in `commonTest` for settings, host allowlist, link cleaning and styling rules.
- Testing on the redroid device. Never interact with other people's accounts while testing.
