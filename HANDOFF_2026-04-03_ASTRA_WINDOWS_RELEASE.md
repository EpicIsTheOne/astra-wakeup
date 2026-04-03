# Astra Windows release handoff — 2026-04-03

## What was added

### Desktop app
A new Windows desktop client now exists at:
- `astra-windows/`

It currently includes:
- bridge-backed chat via Command Center
- shared memory
- shared reminders + task board
- shared cron/calendar view
- analytics view
- mini panel window
- GitHub-release-backed updater UI for Windows assets

### Shared backend organizer
Server endpoints were added in:
- `astra-mvp/src/server.js`

Endpoints:
- `GET /api/astra/organizer`
- `PUT /api/astra/organizer`
- `POST /api/astra/organizer/reminders`
- `DELETE /api/astra/organizer/reminders/:id`
- `POST /api/astra/organizer/tasks`
- `DELETE /api/astra/organizer/tasks/:id`

### Android organizer sync
Android reminders/task board no longer live only in local SharedPreferences.
They now use a local-cache + backend-sync model.

Main files:
- `astra-android/app/src/main/java/com/astra/wakeup/ui/ApiOrganizerClient.kt`
- `astra-android/app/src/main/java/com/astra/wakeup/ui/ReminderRepository.kt`
- `astra-android/app/src/main/java/com/astra/wakeup/ui/RemindersActivity.kt`

## Windows packaging
Configured in:
- `astra-windows/package.json`

Build command:
```bash
cd astra-windows
npm run dist:win
```

Configured outputs:
- NSIS installer
- portable EXE

Expected artifact naming:
- `Astra-Windows-<version>-x64.exe`

## GitHub workflows

### Build workflow
- `.github/workflows/astra-windows-build.yml`

Purpose:
- build Windows artifacts on push/PR/manual trigger

### Release workflow
- `.github/workflows/astra-windows-release.yml`

Purpose:
- publish a tagged Windows GitHub release
- sets package version from the release tag
- uploads `.exe` and `.blockmap`
- supports both manual dispatch and automatic tag-push release flow

Automatic trigger:
- push tags matching `v*`

## Updater behavior
The Windows app now:
- asks Electron for the real installed version at runtime
- checks GitHub releases for a Windows-specific installer asset
- compares installed version vs latest release tag
- offers direct download/open-release actions in the UI

This is UI-level updater behavior right now, not silent self-update.
It points users to the correct Windows artifact.

## Validation completed

### Android
Ran:
```bash
cd astra-android
./gradlew :app:compileDebugKotlin
```
Result:
- BUILD SUCCESSFUL

### Windows desktop code validation
Ran syntax checks for:
- `astra-windows/main.js`
- `astra-windows/preload.js`
- `astra-windows/renderer/app.js`

Also generated package lock for `astra-windows/`.

## Important limitation
This environment is Linux, so I did **not** pretend I locally produced a final Windows installer here.
The correct final packaging validation path is the Windows GitHub Actions workflow (`windows-latest`) or a real Windows machine.

## Recommended next actions
1. normal development changes should rely on **Build Astra Windows** for CI artifact checks
2. intentional releases should use a pushed version tag, e.g. `v0.3.1`
3. verify release assets exist:
   - Windows installer `.exe`
   - `.blockmap`
4. download and smoke-test on a real Windows box:
   - first launch
   - connect to `https://techexplore.us`
   - chat send/receive
   - reminders/task sync with Android
   - release-page / download-button behavior
5. only after that, promote a non-prerelease Windows release if desired

## Suggested first release
- Tag: `v0.3.0`
- Name: `Astra Windows v0.3.0 (preview)`
- Prerelease: `true`

## Suggested commit grouping
1. `feat(windows): add Astra Windows desktop client scaffold`
2. `feat(shared-organizer): add backend organizer endpoints and Android sync`
3. `feat(windows): polish desktop UI and add updater-aware release flow`

## Things intentionally not included
- wake-up feature on Windows
- Android-only alarm/session behavior
- silent auto-update installer logic
- Windows-native intervention layer
