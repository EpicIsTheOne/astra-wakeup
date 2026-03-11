# Signed Release Setup (Phase 5)

To build signed release APK via GitHub Actions, add repository secrets:

- `ASTRA_KEYSTORE_B64` (base64 of `.jks` file)
- `ASTRA_KEY_ALIAS`
- `ASTRA_KEY_PASSWORD`
- `ASTRA_STORE_PASSWORD`

Then run workflow: **Release Astra Android APK (signed)**.

## Local gradle properties
The app build reads these project properties for signing:
- `ASTRA_KEYSTORE_PATH`
- `ASTRA_KEY_ALIAS`
- `ASTRA_KEY_PASSWORD`
- `ASTRA_STORE_PASSWORD`
