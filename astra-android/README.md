# Astra Android

Astra Android is now **bridge-first**.

It no longer relies on the old direct Android ↔ OpenClaw Gateway pairing / device-token flow for normal operation.
The supported path is now:

- **Astra Android app** → **Command Center backend bridge** (`https://techexplore.us/commandcenter`)
- **Command Center backend bridge** → **OpenClaw**

## Current status
As of the current repo state, the Android app can now:
- auto-default its base URL to `https://techexplore.us`
- connect through the Command Center bridge without manual pairing on Android
- send chat through `/commandcenter/api/chat/direct`
- receive replies through `/commandcenter/ws`
- target the backend primary agent (`orchestrator`) by default
- keep wake / reminder / app flows using the same bridge-backed chat path
- build signed release APKs through GitHub Actions
- check for newer signed builds from inside the app

## Supported connection path
This is the intended path now:
1. Open Astra Android
2. Let the app use the default base URL (`https://techexplore.us`) unless you intentionally host the bridge elsewhere
3. Tap **Connect this phone**
4. Astra checks the Command Center bridge
5. Chat / wake-related app features use the bridge-backed path

## Important architecture note
### Old path: deprecated
Deprecated for normal use:
- direct Android gateway auth
- Android pairing approvals
- bootstrap token / shared-token / device-token juggling on-device
- Android device-signature handshake as the primary UX path

Those codepaths may still exist in the repo as legacy scaffolding, but they are no longer the intended production connection model.

### New path: bridge-first
Primary connection model:
- backend bridge handles OpenClaw-facing connection/auth concerns
- Android behaves as a client of the backend
- this mirrors the working Command Center architecture on the server

## Backend reference implementation
Working bridge reference on this server:
- `/root/.openclaw/workspace/openclaw-command-center/server/openclaw-bridge.js`
- `/root/.openclaw/workspace/openclaw-command-center/server/index.js`

Key behavior:
- bridge websocket: `/commandcenter/ws`
- direct chat send: `/commandcenter/api/chat/direct`
- bridge status: `/commandcenter/api/status`
- backend default agent: `orchestrator`

## Defaults
- Default base URL: `https://techexplore.us`
- Default bridge agent/session target: `orchestrator`

## Build commands
```bash
cd astra-android
./gradlew assembleDebug
```

APK path:
`app/build/outputs/apk/debug/app-debug.apk`

## Signed releases
Manual signed release workflow:
- **Release Astra Android (signed)**

Signed release asset:
- `app-release.apk`

## Current priorities
1. Keep bridge-backed chat/wake/reminder flows working
2. Continue removing or hiding legacy direct-gateway UI/auth paths
3. Add clearer in-app status text that explicitly says **Connected via Command Center bridge**
4. Only touch old Android pairing code if there is a deliberate migration/debug reason

## Rule for future work
If a future change tries to reintroduce Android-first gateway pairing as the normal path, that is probably the wrong move.
Prefer extending the backend bridge instead.
