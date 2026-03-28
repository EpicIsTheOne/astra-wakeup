# Astra Android

Yes, this is the real Android app for Astra: wake alarms, chat/control, reminders, context features, and direct OpenClaw connection on Android.

## Current status
As of the current session, the Android app can now:
- connect this phone directly to OpenClaw
- open chat successfully through the Gateway
- configure alarm and wake behavior from the same app
- publish fresh debug APKs automatically through GitHub Actions to the rolling `astra-latest` release
- build signed release APKs through a manual GitHub Actions workflow
- check for newer signed builds from inside the app and hand them off to Android installer flow

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
- Includes an in-app updater for signed releases.

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
4. **Updater**
5. **Interventions**

Notes:
- pairing/bootstrap UX is currently de-emphasized and removed from the main screen because the shared-token path is more reliable right now
- advanced/debug gateway controls are hidden behind **Advanced gateway options**
- wake + chat are visually gated behind connection state
- updater is aimed at rapid signed-build testing

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
This repo builds and publishes a rolling debug APK on pushes to `main`.

### Workflow
- **Build Astra Android**

### Rolling release
- tag: `astra-latest`
- asset: `app-debug.apk`

Direct download pattern:
`https://github.com/EpicIsTheOne/Astra/releases/download/astra-latest/app-debug.apk`

## Signed release workflow
The repo also supports a manual signed-release workflow.

### Workflow
- **Release Astra Android (signed)**

### Signed release artifact
- asset: `app-release.apk`

### Signed release behavior
- `versionName` is derived from the workflow tag input, e.g. `v0.2.1` → `0.2.1`
- `versionCode` is derived from the GitHub Actions run number
- this is intentional so Android update-over-install behaves correctly for testing

See `RELEASE_SIGNING.md` for the secrets/setup details.

## In-app updater
The updater card on the main screen can:
- auto-check on launch
- auto-download newer signed releases
- show installed vs latest version
- preview release notes from GitHub release body text
- skip one specific version
- launch Android installer for the downloaded APK

Important reality check:
- on stock Android, the final install still usually needs a user confirmation tap
- fully silent installs are not expected without elevated device privileges

## Floating overlay status
The floating Astra overlay was recently reworked away from the old bulky transcript-heavy panel.

Current overlay direction:
- compact bottom overlay instead of mini full-screen chat sheet
- latest Astra reply is the primary visible response surface
- no visible assistant title/subtitle or close button
- dismissal is handled by swiping down on the drag handle
- live speech partials/final text feed into the input field
- long replies are line-capped by default and expand on tap
- outside-panel background is transparent rather than dimmed
- empty reply state is hidden until Astra actually has something to say

Recent release note:
- `v0.2.11` specifically hotfixed a serious overlay regression where the panel could act like a fullscreen touch-blocking blanket and become difficult to dismiss

## Still TODO
- Validate the latest overlay hotfix carefully on-device, especially dismissal reliability and outside-touch behavior.
- Decide whether the latest-reply surface should stay tappable-to-expand or gain a more explicit affordance.
- Finish smoothing auth edge-cases so approval/token/device-token behavior feels fully seamless.
- Polish chat UI to match the improved main screen.
- Polish spacing/iconography/status visuals on the main screen even further.
- Bundle custom SFX pack and volume profiles.
- Reintroduce a better pairing-code path later if/when it is truly reliable.
- Consider replacing `softprops/action-gh-release@v2` later if the lingering Node 20 warning ever becomes operationally annoying.
