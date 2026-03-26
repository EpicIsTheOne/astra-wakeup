# Astra Wake-Up Android MVP

Yes, this is the real Android app scaffold for Astra wake-ups at **5:50 AM ET**.

## What it does now
- Schedules an exact daily alarm for 5:50 AM (America/New_York).
- Opens a full-screen wake activity at alarm time.
- Uses Android TTS (female-ish pitched voice) to read wake lines.
- Fetches dynamic lines from OpenClaw wake API (`/api/wakeup/line`).
- Punishment loop: repeats taunts + random SFX until acknowledged.
- Supports **I'm awake** and **Snooze 10 min**.
- Reschedules after reboot.

## OpenClaw connection
In app settings, set API URL to your reachable OpenClaw wake endpoint, e.g.:
`http://<server-ip>:8787/api/wakeup/line`

## Still TODO
- Bundle custom SFX pack and volume profiles.
- Build + sign release APK.

## Build prerequisites (on a machine with Android SDK)
- Java 17
- Android SDK + platform tools
- Gradle (or Android Studio)

## Build commands
```bash
cd astra-wakeup-android
./gradlew assembleDebug
```

APK path:
`app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions debug APK
This repo now includes a GitHub Actions workflow that builds the debug APK on:
- pushes to `main`
- pull requests touching Android build/app files
- manual runs via **Actions > Android Debug APK > Run workflow**

The APK is uploaded as a workflow artifact named:
`astra-wakeup-debug-apk`

Once the workflow finishes, download it from the run’s **Artifacts** section.

## Public download link plan
Once APK is built, upload to one of:
- GitHub Releases
- Cloudflare R2 (public object URL)
- S3 static URL

Then share that URL with Epic.
