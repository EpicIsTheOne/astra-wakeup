# OpenClaw Android Phase 2 notes

## What is now wired

- Generates and persists a per-install Ed25519 device identity in SharedPreferences.
- Builds OpenClaw `connect` payloads using the current protocol v3 shape:
  - `client.id = openclaw-android`
  - `client.mode = ui`
  - `auth.token`, `auth.bootstrapToken`, and `auth.deviceToken` separated correctly
  - challenge-bound `device` signature payload including `nonce`
- Persists `hello-ok.auth.deviceToken` and basic server/method/event metadata from the hello payload.
- Main settings screen now stores optional shared gateway token + bootstrap token for direct OpenClaw auth flows.
- Simplified main UI into a normal-looking connect flow: external OpenClaw URL is prefilled, primary action is now `Connect this phone`, and advanced token/debug controls are hidden behind an advanced toggle.
- Added a lightweight in-app gateway debug panel showing ws URL, auth summary, device identity, and last error.
- Added direct test buttons for gateway probe, clear device token, and clear all gateway auth.
- API status output now classifies common auth failures (`pairing required`, token mismatch, invalid bootstrap token, bad device signature) into user-facing guidance.
- Uses `chat.send` params with `message` instead of the old `text` field.
- Treats `chat` events as OpenClaw chat-state events (`delta|final|aborted|error`) and extracts renderable text from `payload.message.content[]` and `payload.message.text`.

## Important limitation

This implementation assumes the Android runtime provides JCA `Ed25519` support (`KeyPairGenerator`, `KeyFactory`, and `Signature`). On modern Android this is typically available, but this repo could not be compiled in the current environment because no local Java toolchain is installed, so runtime verification is still pending.

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

## Still missing / blocked

- No explicit pairing/bootstrap UX in the app yet.
- Added a basic stale-device-token recovery path in app code: if connect/chat/history errors look like `device token mismatch`, the cached device token is cleared and the request is retried once.
- Still no fully tested gateway-close/reconnect state machine yet for all auth failures (`pairing required`, close code `1008`, etc.).
- No Java toolchain in this workspace, so `./gradlew :app:compileDebugKotlin` could not be run here.
