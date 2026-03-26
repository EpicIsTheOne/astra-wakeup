# OpenClaw Android implementation notes

## What is now wired

- Generates and persists a per-install Ed25519 device identity in SharedPreferences.
- Builds OpenClaw `connect` payloads using the current protocol v3 shape:
  - `client.id = openclaw-android`
  - `client.mode = ui`
  - `auth.token`, `auth.bootstrapToken`, and `auth.deviceToken` separated correctly
  - challenge-bound `device` signature payload including `nonce`
- Persists `hello-ok.auth.deviceToken` and basic server/method/event metadata from the hello payload.
- Uses `chat.send` params with `message` instead of the old `text` field.
- Treats `chat` events as OpenClaw chat-state events (`delta|final|aborted|error`) and extracts renderable text from `payload.message.content[]` and `payload.message.text`.
- Added direct Gateway debug tools in the Android app:
  - probe gateway auth
  - clear device token
  - clear all gateway auth
- Added stale-device-token retry behavior for mismatch-style failures.
- Added auth debug persistence for the last `hello.auth` blob returned by OpenClaw.

## Current product/UI direction

The Android app is now intentionally **token-first**.

### Main screen priorities
1. **Connect this phone**
2. **Wake controls**
3. **Chat**

### Main-screen behavior
- external OpenClaw URL is prefilled
- shared gateway token is the primary supported auth path
- pairing/bootstrap flow is no longer emphasized in the main UI for now
- advanced/debug gateway controls are hidden behind **Advanced gateway options**
- wake + chat stay visually locked until the phone is connected

## Current known-good server assumptions

For the active server used during development:
- public URL: `http://72.60.29.204:18789`
- Gateway WebSocket route: `ws://72.60.29.204:18789`
- `gateway.bind = lan`
- `gateway.auth.mode = token`
- `plugins.entries.device-pair.config.publicUrl = http://72.60.29.204:18789`

## Auth/signature behavior implemented

Current Android connect/signing logic is trying to support three auth sources:
- shared gateway token
- bootstrap token
- paired device token

Recent fixes applied:
- bootstrap-token pairing flow now signs the connect challenge instead of omitting the token source
- config loading prefers device token over bootstrap after pairing state exists
- shared-token mode now prefers **gateway token first** when building the challenge signature, instead of incorrectly preferring device token
- on successful hello auth persistence, bootstrap/pairing state is cleared when a real device token is issued

## Important limitation

This implementation assumes the Android runtime provides JCA `Ed25519` support (`KeyPairGenerator`, `KeyFactory`, and `Signature`). On modern Android this is typically available, but this repo could not be compiled locally in the workspace because no local Java toolchain was installed here; CI/GitHub Actions was used as the build verifier instead.

If older devices fail here, the fallback path to add is:
1. bundle a provider/library with Ed25519 support, or
2. move key generation/signing behind a pluggable crypto provider interface with a software implementation.

## OpenClaw protocol details inferred from local install

From local OpenClaw type declarations and bundled client code:

- `connect` request `device` fields are required together when present:
  - `id`
  - `publicKey`
  - `signature`
  - `signedAt`
  - `nonce`
- device auth signature payload is the OpenClaw `v3` format:
  - `v3|deviceId|clientId|clientMode|role|scopesCsv|signedAtMs|token|nonce|platform|deviceFamily`
- `chat.send` params use `message`, not `text`
- `chat` event payloads expose `state` and `message`
- `hello-ok.auth.deviceToken` is the issued paired-device token that should be reused as `auth.deviceToken` on later connects

## CI/release notes

- Rolling Android distribution currently uses the debug APK only.
- GitHub Actions publishes `app-debug.apk` to the rolling `astra-latest` release.
- Signed release workflow was intentionally removed/disabled for now.

## Still rough / future work

- Approval behavior may still show up even on the shared-token path in some real-world cases; auth expectations should be tightened and simplified.
- Pairing/bootstrap UX should stay secondary until it is genuinely reliable.
- Chat UI still needs its own polish pass to match the main screen improvements.
- The main screen could still benefit from a lighter visual polish pass (icons, spacing rhythm, tighter state presentation).
- No fully audited reconnect state machine yet for every auth failure / close-code edge case.
