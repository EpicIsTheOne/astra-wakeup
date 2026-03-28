# Astra

Android assistant app experiments for Astra: wake alarms, chat, reminders, context, updater flow, and more.

## Quick links
- **Repo:** https://github.com/EpicIsTheOne/Astra
- **Latest signed APK page:** https://epicistheone.github.io/Astra/
- **Android install guide:** https://epicistheone.github.io/Astra/install-android.html
- **All releases:** https://github.com/EpicIsTheOne/Astra/releases
- **Latest signed test APK:** https://github.com/EpicIsTheOne/Astra/releases/download/v0.2.1-signed-test/app-release.apk

## Repo layout
- `astra-android/` — current Android app project for Astra
- `astra-mvp/` — older MVP bits
- `docs/` — GitHub Pages download/install page
- `.github/workflows/` — debug + signed release automation
- `HANDOFF_ASTRA.md` — next-session operator handoff notes

## Current Android release model
Astra now has two distinct Android release paths:

### 1. Rolling debug build
- workflow: **Build Astra Android**
- tag: `astra-latest`
- asset: `app-debug.apk`
- purpose: quick internal debug builds

### 2. Signed release build
- workflow: **Release Astra Android (signed)**
- asset: `app-release.apk`
- purpose: installs that should support normal update-over-install behavior

## Signed APK behavior
The GitHub Pages site redirects to the newest GitHub release asset named `app-release.apk`, including prereleases.
That gives Astra one stable human-friendly download URL even when GitHub’s normal “latest release” behavior ignores prereleases.

## In-app updater
The Android app now includes an updater panel that can:
- check GitHub Releases for the newest signed `app-release.apk`
- show **installed vs latest** version
- preview release notes
- auto-check on launch
- auto-download newer signed builds
- skip one specific version
- hand the downloaded APK to Android’s installer

Stock Android still usually requires the final install confirmation tap.
Silent unattended install is not expected unless the device is privileged/rooted/device-owner.

## Overlay status
The floating Astra overlay has been actively reworked and now follows a compact overlay-first model instead of a mini chat window.

Current overlay behavior:
- latest-Astra-reply-first surface instead of a large visible conversation log
- no title/subtitle/close button chrome
- swipe-down dismissal from the drag handle
- live speech recognition text routed into the existing input field
- long replies collapse by default and can be expanded on tap
- transparent background outside the panel instead of a dim fullscreen blanket

Important note:
A bad regression briefly caused the overlay to behave like a fullscreen touch-blocking layer. That was hotfixed in `v0.2.11` by removing the fullscreen blanket behavior and making the outer overlay background transparent again.

## Install Astra on Android
- **Install page:** https://epicistheone.github.io/Astra/install-android.html
- **Latest signed APK page:** https://epicistheone.github.io/Astra/

## Operations note
If you need the important paths, workflow names, signing secrets, Pages URLs, or test-release procedure, read:
- `HANDOFF_ASTRA.md`
