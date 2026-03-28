# Signed Release Setup

Astra can now build a signed `release` APK through GitHub Actions.

## Required repository secrets
Add these repository secrets before running the signed release workflow:

- `ASTRA_KEYSTORE_B64` — base64 of your `.jks` / `.keystore` file
- `ASTRA_KEY_ALIAS`
- `ASTRA_KEY_PASSWORD`
- `ASTRA_STORE_PASSWORD`

## Create a release keystore
Example:

```bash
keytool -genkeypair   -v   -keystore astra-release.keystore   -alias astra   -keyalg RSA   -keysize 4096   -validity 3650
```

Back up the keystore and passwords before using it in CI.
If you lose this key, installed apps signed with it cannot be updated normally.

## Encode keystore for GitHub secret
GNU coreutils example:

```bash
base64 -w 0 astra-release.keystore
```

macOS / BSD example:

```bash
base64 < astra-release.keystore | tr -d '
'
```

Save the resulting single-line output as `ASTRA_KEYSTORE_B64`.

## Workflow
Run **Release Astra Android (signed)** from GitHub Actions and provide:

- `release_tag` — for example `v0.2.0`
- `release_name` — release title shown on GitHub
- `prerelease` — whether this should be marked as prerelease

The workflow will:
1. decode the keystore from secrets
2. build `:app:assembleRelease`
3. upload `app-release.apk` as an artifact
4. publish a GitHub Release with the signed APK attached

## Local project properties
The Android app reads these project properties for signing:

- `ASTRA_KEYSTORE_PATH`
- `ASTRA_KEY_ALIAS`
- `ASTRA_KEY_PASSWORD`
- `ASTRA_STORE_PASSWORD`

You can provide them via Gradle properties or environment-backed property injection for local signed builds.

## First install migration note
If your phone currently has a debug build or a build signed with a different key, you will likely need one final uninstall/reinstall before future signed updates can install over the top.
