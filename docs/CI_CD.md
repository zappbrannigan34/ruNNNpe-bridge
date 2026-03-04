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
3. Build debug APK (`assembleDebug`).
4. Rename APK to `ruNNNpe-bridge-debug.apk`.
5. Upload APK artifact.

## Publish workflow

Workflow: `.github/workflows/publish.yml`

Triggers:

- tag push `v*`
- `workflow_dispatch` with `tag_name` input

Actions:

1. Build debug APK.
2. Rename APK to `ruNNNpe-bridge-<tag>.apk`.
3. Create/update GitHub Release.
4. Attach APK to release assets.

## How to publish manually

1. Open Actions -> `Publish APK`.
2. Run workflow.
3. Set `tag_name` like `v0.1.0`.
4. Check Release page for uploaded APK.
