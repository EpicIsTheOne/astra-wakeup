# Astra for Windows

Desktop client scaffold for Astra using the same backend model as Astra Android:

- **Bridge-backed chat** via Command Center (`/commandcenter/api/chat/direct` + `/commandcenter/ws`)
- **Shared memory** via `/api/astra/memory`
- **Shared reminders + task board** via `/api/astra/organizer`
- **Shared cron/calendar view** via `/api/astra/calendar`
- **Analytics** via `/api/astra/analytics`
- **Updater check** via GitHub releases
- **Mini panel window** as the Windows counterpart to the Android overlay/panel concept

## What is intentionally missing
- **Wake-up feature** (on purpose)
- Android-only alarm/full-screen notification flows
- Android-specific usage-access interventions

## Run locally
```bash
cd astra-windows
npm install
npm start
```

## Build Windows packages
```bash
cd astra-windows
npm run dist:win
```

Artifacts are configured to come out under:
- `astra-windows/dist/`

Expected primary Windows artifact naming:
- `Astra-Windows-<version>-x64.exe`

## Default backend URL
The app defaults to:

- `https://techexplore.us`

You can change this in-app.

## Updater behavior
The desktop app now reads its installed version from Electron at runtime and checks GitHub releases for a **Windows-specific** installer asset.
It does not silently self-update yet; it surfaces the correct release page / download artifact for the user.

## Shared organizer backend
This desktop client expects these backend endpoints to exist:

- `GET /api/astra/organizer`
- `PUT /api/astra/organizer`
- `POST /api/astra/organizer/reminders`
- `DELETE /api/astra/organizer/reminders/:id`
- `POST /api/astra/organizer/tasks`
- `DELETE /api/astra/organizer/tasks/:id`

Those endpoints were added in this repo under `astra-mvp/src/server.js` so reminders/task data can be shared between mobile and desktop instead of being trapped in one device.
