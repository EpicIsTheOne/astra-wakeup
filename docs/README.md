# GitHub Pages download shim

This Pages site redirects visitors to the newest GitHub release asset named `app-release.apk`.

Why it exists:
- GitHub's `releases/latest` endpoint ignores prereleases.
- The signed Android APK may be published as a prerelease.
- This page uses the Releases API client-side and jumps to the newest matching signed APK asset.
