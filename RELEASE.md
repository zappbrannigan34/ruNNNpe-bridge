# Release checklist

## Versioning

- Use tags in format: `vMAJOR.MINOR.PATCH`
- Example: `v0.1.0`
- Keep `versionCode` strictly increasing for every release.

## Required checks before release

1. Local build passes:

```bash
./gradlew assembleRelease bundleRelease
```

2. `Android Build` workflow is green.
3. README and docs are up to date.
4. Release signing secrets are configured in GitHub repository settings:

- `ANDROID_UPLOAD_KEYSTORE_BASE64`
- `ANDROID_UPLOAD_KEYSTORE_PASSWORD`
- `ANDROID_UPLOAD_KEY_ALIAS`
- `ANDROID_UPLOAD_KEY_PASSWORD`

5. Play Console app content is updated:

- Privacy policy URL
- Data Safety form
- Health apps declaration
- Foreground service declaration (and reviewer video if required)

## Publish

Option A (recommended):

- Push a tag:

```bash
git tag v0.1.0
git push origin v0.1.0
```

Option B:

- Run `Publish Release` workflow manually and provide `tag_name`.

## Expected release artifacts

- `ruNNNpe bridge-<tag>.apk`
- `ruNNNpe bridge-<tag>.aab`
