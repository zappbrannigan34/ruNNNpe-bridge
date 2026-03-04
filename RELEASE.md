# Release checklist

## Versioning

- Use tags in format: `vMAJOR.MINOR.PATCH`
- Example: `v0.1.0`

## Required checks before release

1. Local build passes:

```bat
gradlew.bat assembleDebug
```

2. `Android Build` workflow is green.
3. README and docs are up to date.

## Publish

Option A (recommended):

- Push a tag:

```bash
git tag v0.1.0
git push origin v0.1.0
```

Option B:

- Run `Publish APK` workflow manually and provide `tag_name`.

## Expected release artifact

- `ruNNNpe bridge-<tag>.apk`
