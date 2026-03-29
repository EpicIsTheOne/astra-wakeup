# Next Session Handoff Prompt

Use this prompt at the start of the next Astra update session:

---

You are continuing work on the Astra Android app in:
`/root/.openclaw/workspaces/orchestrator/tmp/Astra-rebrand`

Read first:
1. `astra-android/README.md`
2. `HANDOFF_ASTRA.md`
3. if needed for backend behavior reference:
   - `/root/.openclaw/workspace/openclaw-command-center/server/openclaw-bridge.js`
   - `/root/.openclaw/workspace/openclaw-command-center/server/index.js`
   - `/root/.openclaw/workspace/openclaw-command-center/server/agents.js`

Critical architectural rule:
- Astra Android is now **bridge-first**.
- Do not restore direct Android ↔ OpenClaw pairing/device-token auth as the normal path.
- Prefer extending the Command Center backend bridge and consuming it from Android.

Current intended runtime model:
- Android app → `https://techexplore.us/commandcenter`
- backend bridge → OpenClaw
- default Android agent/session target: `orchestrator`

What is already done:
- app auto-defaults base URL to `https://techexplore.us`
- app prefers bridge-backed connect/probe
- app sends chat through `/commandcenter/api/chat/direct`
- app listens on `/commandcenter/ws` for `agent:responding` / `agent:error`
- old pairing-driven connect behavior was removed from the normal flow

Preferred next work order:
1. remove or hide remaining legacy direct-gateway UI/auth controls
2. verify all app surfaces using `WakeChatClient` / `OpenClawChatClient` work correctly in bridge mode
3. add any missing backend endpoints needed by Android features, instead of reviving direct gateway auth
4. keep docs in sync whenever the bridge contract changes
5. build locally before shipping
6. if credible, publish the next signed release

Definition of success:
- Astra works through the backend bridge for normal user flows
- no Android pairing ritual is required
- docs and handoff notes remain accurate

---
