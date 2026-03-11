# CI/CD and Publication

## Build workflow

Workflow: `.github/workflows/android-build.yml`

Triggers:

- `push` to `master` and `main`
- `pull_request`
- `workflow_dispatch`

Actions:

1. Checkout code.
2. Ensure Gradle wrapper is executable on Linux runners.
3. Setup pinned toolchain (JDK 17 + Android platform/build-tools).
4. Build debug and release outputs (`assembleDebug`, `assembleRelease`, `bundleRelease`).
5. Normalize artifact names (`.apk` and `.aab`) with signed/unsigned release APK fallback handling.
6. Upload build artifacts.
7. On push to `master`/`main`, publish rolling GitHub pre-release `pre-release` with latest artifacts.
8. Pre-release notes include artifact purpose (`which file to install`) and commit history for that push range.
9. Push pre-release build requires release signing secrets so `ruNNNpe bridge-release.apk` is installable (not unsigned).

## Publish workflow

Workflow: `.github/workflows/publish.yml`

Triggers:

- tag push `v*`

Actions:

1. Validate release tag format and match against version files.
2. Decode upload keystore from repository secrets.
3. Build release APK and AAB (`assembleRelease`, `bundleRelease`).
4. Rename outputs to `ruNNNpe bridge-<tag>.*`.
5. Publish final GitHub Release for that tag.
6. Attach APK and AAB to release assets.
7. Release notes include artifact purpose and changelog from previous tag.

## Required publish secrets

- `ANDROID_UPLOAD_KEYSTORE_BASE64`
- `ANDROID_UPLOAD_KEYSTORE_PASSWORD`
- `ANDROID_UPLOAD_KEY_ALIAS`
- `ANDROID_UPLOAD_KEY_PASSWORD`

If these secrets are absent, publish workflow fails.

## How to publish manually

1. Open Actions -> `Release Prep`.
2. Run on `master` with `bump` (`patch`/`minor`/`major`).
3. Wait for pushed tag (`vX.Y.Z`) and `Publish Release` workflow completion.
4. Check Releases page for final uploaded APK and AAB.

## Pre-release behavior

- Every push to `master`/`main` updates rolling GitHub pre-release `pre-release`.
- URL: `https://github.com/zappbrannigan34/ruNNNpe-bridge/releases/tag/pre-release`
- Final releases are still created only from `v*` tags.
- F-Droid update checks ignore this tag via `.fdroid.yml` `UpdateCheckIgnore: "(?i)pre-release"`.
- For testing, install `ruNNNpe bridge-release.apk`; `ruNNNpe bridge-debug.apk` is for debug/diagnostics.
- If pre-release publish ever reports unsigned release APK, workflow fails by design and pre-release is not updated.
