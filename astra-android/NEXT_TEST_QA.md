# Astra Android direct OpenClaw QA checklist

## Preflight
- Install app build
- Open main screen
- Confirm `gateway debug` shows:
  - ws URL
  - auth summary
  - deviceId

## Test 1: Probe with shared token
1. Enter API URL
2. Enter shared gateway token
3. Save settings
4. Tap **Probe gateway auth**
5. Expected:
   - `gateway probe ok ✅`
   - server version and connId shown
   - `gateway debug` shows shared token present

## Test 2: Bootstrap token onboarding
1. Clear all gateway auth
2. Enter API URL
3. Enter bootstrap token
4. Save settings
5. Tap **Probe gateway auth**
6. Expected:
   - probe succeeds
   - `gateway debug` eventually shows device token present
7. Optional:
   - clear bootstrap token field only, save again
   - probe again
   - should still succeed via cached device token

## Test 3: Stale device token recovery
1. Start from a working paired setup
2. Tamper with cached `gateway_device_token` if you have adb/debug access
3. Send chat or run Check server connection
4. Expected:
   - app clears stale device token automatically and retries once
   - if gateway still rejects, UI should classify the failure

## Test 4: Pairing required path
1. Clear all gateway auth
2. Leave token fields empty
3. Tap **Probe gateway auth**
4. Expected:
   - failure is classified as pairing/token guidance instead of generic network mush

## Test 5: Main status check
1. Tap **Check server connection**
2. Expected details include:
   - health status
   - line status
   - chat/auth classification when chat fails

## Good signs
- `gateway debug` updates after save/probe/reset
- deviceId stays stable across app restarts
- successful bootstrap probe results in later device-token auth

## Bad signs
- probe always fails with signature/device identity errors
- deviceId changes unexpectedly between launches
- chat still uses old contextless path
- status says only `fail` with no auth guidance
