# Astra handoff notes

## Current architectural decision
Astra Android is now **bridge-first**.

Normal production path:
- Android app → `https://techexplore.us/commandcenter`
- Command Center backend bridge → OpenClaw

Do **not** resume treating direct Android gateway pairing/device-token auth as the primary path unless there is a very specific debugging reason.
That path caused repeated failures and was intentionally abandoned in favor of the working Command Center architecture.

## Working backend reference
Server-side bridge implementation used as the model:
- `/root/.openclaw/workspace/openclaw-command-center/server/openclaw-bridge.js`
- `/root/.openclaw/workspace/openclaw-command-center/server/index.js`
- `/root/.openclaw/workspace/openclaw-command-center/server/agents.js`

Important backend behaviors:
- websocket: `/commandcenter/ws`
- status endpoint: `/commandcenter/api/status`
- direct chat endpoint: `/commandcenter/api/chat/direct`
- backend primary agent resolves to `orchestrator` when present

## Current Android defaults
- default base URL: `https://techexplore.us`
- default bridge agent/session key: `orchestrator`
- bridge status is treated as the source of truth for connect/probe

## Recent milestones completed
- bridge-first connect path added
- bridge-backed chat send added
- bridge websocket reply handling added
- Android default base URL switched from old raw gateway IP to `https://techexplore.us`
- Android default agent switched from `main` to `orchestrator`
- legacy direct-pairing UX removed from normal connect behavior

## What still needs cleanup
- hide or remove more legacy gateway auth controls from the main UI / advanced UI
- reduce dead legacy direct-gateway code where safe
- optionally add a small explicit status label like `Connected via Command Center bridge`
- if history is needed in Android, add a backend bridge endpoint for history instead of reviving direct gateway history auth

## Release model
Latest signed releases were published repeatedly during migration. Check GitHub releases for the newest live version.
Release workflow:
- `.github/workflows/astra-release.yml`

## Future editing rule
When updating Astra features that need OpenClaw:
- prefer adding / extending a backend bridge endpoint
- then call it from Android
- avoid rebuilding direct mobile gateway auth unless absolutely necessary
