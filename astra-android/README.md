# Astra Android

Yes, this is the real Android app for Astras, chat/control, reminders, context features, and direct OpenClaw connection on Android.

## Current status
As of the latest token-first UI pass, the Android app can now:
- connect this phone directly to OpenClaw
- open chat successfully through the Gateway
- configure alarm and wake behavior from the same app
- publish fresh debug APKs automatically through GitHub Actions to the rolling `astra-latest` release

The current supported connection path is intentionally **token-first**.

## What it does now
- Schedules an exact daily alarm for 5:50 AM (America/New_York).
- Opens a full-screen wake activity at alarm time.
- Uses Android TTS (female-ish pitched voice) to read wake lines.
- Fetches dynamic lines from OpenClaw wake API (`/api/wakeup/line`).
- Punishment loop: repeats taunts + random SFX until acknowledged.
- Supports **I'm awake** and **Snooze 10 min**.
- Reschedules after reboot.
- Connects directly to the OpenClaw Gateway for chat/control.
- Persists per-install device identity and paired-device auth state.

## Supported OpenClaw connect flow (current)
Main UI now supports this path first:
- OpenClaw URL
- shared gateway token
- tap **Connect this phone**
- if OpenClaw asks for approval, approve the pending Android device
- then reconnect and use chat/wake controls normally

### Current working values on the active server
- OpenClaw URL: `http://72.60.29.204:18789`
- Gateway auth mode: `token`
- Gateway bind: `lan`
- Public device-pair URL source: `plugins.entries.device-pair.config.publicUrl`

## UX direction
The home screen is now intentionally organized as:
1. **Connect this phone**
2. **Wake controls**
3. **Chat**

Notes:
- pairing/bootstrap UX is currently de-emphasized and removed from the main screen because the shared-token path is more reliable right now
- advanced/debug gateway controls are hidden behind **Advanced gateway options**
- wake + chat are visually gated behind connection state

## Build prerequisites (on a machine with Android SDK)
- Java 17
- Android SDK + platform tools
- Gradle (or Android Studio)

## Build commands
```bash
cd astra-android
./gradlew assembleDebug
```

APK path:
`app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions rolling debug APK
This repo now builds and publishes a rolling debug APK on pushes to `main`.

### Workflow
- **Build Astra Android**

### Rolling release
- tag: `astra-latest`
- asset: `app-debug.apk`

Direct download pattern:
`https://github.com/EpicIsTheOne/Astra/releases/download/astra-latest/app-debug.apk`

## Release policy (current)
- Rolling release stays on **debug APK** for quick internal testing.
- Signed release builds are now available through the manual workflow **Release Astra Android (signed)**.
- Use the signed release APK for installs that should support normal in-place updates.

## Still TODO
- Finish smoothing auth edge-cases so approval/token/device-token behavior feels fully seamless.
- Polish chat UI to match the improved main screen.
- Polish spacing/iconography/status visuals on the main screen even further.
- Bundle custom SFX pack and volume profiles.
- Reintroduce a better pairing-code path later if/when it is truly reliable.

## Signed release workflow
Once repository signing secrets are configured, you can run the manual GitHub Actions workflow:

- **Release Astra Android (signed)**

This builds a signed `release` APK and publishes it as a GitHub Release asset.
See `RELEASE_SIGNING.md` for the required secrets and setup.
