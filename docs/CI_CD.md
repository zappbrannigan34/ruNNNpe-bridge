# CI/CD and Publication

## Build workflow

Workflow: `.github/workflows/android-build.yml`

Triggers:

- `push` to `master` and `main`
- `pull_request`
- `workflow_dispatch`

Actions:

1. Checkout code.
2. Setup JDK 17.
3. Build debug and release outputs (`assembleDebug`, `assembleRelease`, `bundleRelease`).
4. Normalize artifact names (`.apk` and `.aab`) with signed/unsigned release APK fallback handling.
5. Upload build artifacts.

## Publish workflow

Workflow: `.github/workflows/publish.yml`

Triggers:

- tag push `v*`
- `workflow_dispatch` with `tag_name` input

Actions:

1. Resolve release tag and semantic version name.
2. Decode upload keystore from repository secrets (if configured).
3. Build release APK and AAB (`assembleRelease`, `bundleRelease`).
4. Rename outputs to `ruNNNpe bridge-<tag>.*`.
5. Create/update GitHub Release.
6. Attach APK and AAB to release assets.

## Required publish secrets

- `ANDROID_UPLOAD_KEYSTORE_BASE64`
- `ANDROID_UPLOAD_KEYSTORE_PASSWORD`
- `ANDROID_UPLOAD_KEY_ALIAS`
- `ANDROID_UPLOAD_KEY_PASSWORD`

If these secrets are absent, `assembleRelease` still completes and produces `app-release-unsigned.apk` for validation only. Do not upload unsigned artifacts to Play production.

## How to publish manually

1. Open Actions -> `Publish Release`.
2. Run workflow.
3. Set `tag_name` like `v0.1.0`.
4. Check Release page for uploaded APK and AAB.
