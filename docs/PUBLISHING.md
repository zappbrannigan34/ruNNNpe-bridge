# Publishing Compliance Checklist

This document tracks publication readiness for `ruNNNpe bridge` in Google Play and F-Droid.

## Current release identity

- Application ID: `com.zappbrannigan34.runnnpebridge`
- Target SDK: `35`
- Release artifacts: APK + AAB from GitHub release workflow

## Google Play checklist

- [ ] Create Play Console app with package name `com.zappbrannigan34.runnnpebridge`.
- [ ] Upload signed AAB (`ruNNNpe bridge-<tag>.aab`).
- [ ] Enable Play App Signing and store upload key safely.
- [ ] Set privacy policy URL to public `PRIVACY_POLICY.md` location.
- [ ] Complete Data Safety form using actual app behavior.
- [ ] Complete Health apps declaration.
- [ ] Complete Foreground service declaration (with review video if requested).
- [ ] Complete additional Permissions Declaration Form if prompted by Play Console.
- [ ] Validate app content, testing track requirements, and production access gates.

Official references:

- App Bundles: https://developer.android.com/guide/app-bundle
- App signing: https://developer.android.com/studio/publish/app-signing
- Target API policy: https://support.google.com/googleplay/android-developer/answer/11926878
- Data Safety: https://support.google.com/googleplay/android-developer/answer/10787469
- User Data + privacy policy: https://support.google.com/googleplay/android-developer/answer/9888076
- Health apps declaration: https://support.google.com/googleplay/android-developer/answer/14738291
- Health policy: https://support.google.com/googleplay/android-developer/answer/16679511
- Foreground service declaration: https://support.google.com/googleplay/android-developer/answer/13392821

## F-Droid checklist

- [ ] Keep project FLOSS and maintain root `LICENSE` file.
- [ ] Keep `gradlew` and wrapper files committed for Linux build-from-source.
- [ ] Ensure versioning is tag-based and `versionCode` is monotonic.
- [ ] Prepare and submit `fdroiddata` metadata (`metadata/<applicationId>.yml`).
- [ ] Provide listing text and screenshots via fastlane metadata paths.
- [ ] Document dependency on Health Connect clearly for reviewer context.
- [ ] Declare anti-features if required by F-Droid review.
- [ ] Monitor F-Droid build logs after submission and adjust metadata/build recipe.

Official references:

- Inclusion How-To: https://f-droid.org/docs/Inclusion_How-To/
- Inclusion Policy: https://f-droid.org/docs/Inclusion_Policy/
- Metadata reference: https://f-droid.org/docs/Build_Metadata_Reference/
- Anti-Features: https://f-droid.org/docs/Anti-Features/
- Reproducible builds: https://f-droid.org/docs/Reproducible_Builds/

## Repository-side controls

- `app/build.gradle.kts`
  - target SDK and release signing env hooks
  - pinned `buildToolsVersion` for reproducible release signing
- `.github/workflows/publish.yml`
  - tag-only release publishing with pinned JDK/SDK build-tools and version/tag consistency checks
- `.github/workflows/android-build.yml`
  - validates debug and release outputs on pinned Linux/JDK/SDK toolchain
  - publishes rolling GitHub pre-release `pre-release` on every push to `master`/`main`
- `.github/workflows/release-prep.yml`
  - one-click release prep from GitHub Actions (bumps `versionName`/`versionCode`, updates `.fdroid.yml`, commits, and tags)
- `.fdroid.yml`
  - upstream F-Droid metadata baseline for submission
- `PRIVACY_POLICY.md`
  - required for Play listing and User Data compliance
- `LICENSE`
  - required for FLOSS distribution expectations

## GitHub secrets for signed release builds

- `ANDROID_UPLOAD_KEYSTORE_BASE64`
- `ANDROID_UPLOAD_KEYSTORE_PASSWORD`
- `ANDROID_UPLOAD_KEY_ALIAS`
- `ANDROID_UPLOAD_KEY_PASSWORD`

If these secrets are not set, release publishing fails.

## Automated release prep flow

1. Open GitHub Actions -> `Release Prep` workflow.
2. Run workflow on `master` and choose only one input: `bump` (`patch`, `minor`, or `major`).
3. Workflow automatically calculates the next `versionName` and `versionCode`, updates `app/build.gradle.kts` and `.fdroid.yml`, commits to `master`, and pushes the new tag.
4. Tag push automatically triggers `Publish Release` workflow to build and upload release assets.

## Pre-release policy

- Every push to `master`/`main` updates rolling GitHub pre-release tag `pre-release` with latest APK/AAB artifacts.
- Final release publication remains tag-gated (`vX.Y.Z`) via `Publish Release` workflow.
- F-Droid update checks explicitly ignore pre-release tag via `.fdroid.yml` `UpdateCheckIgnore: "(?i)pre-release"`.
- Both pre-release and final release notes include: artifact purpose (`which file to install`) and changelog history.
- Pre-release changelog start point prefers previous pre-release target commit, then stable release tag commit, then push `before` SHA, with repo-root fallback.

## Reproducible build requirements (F-Droid)

1. Build and publish only from release tags (`vX.Y.Z`), never from untagged commits.
2. Ensure tag version matches both `app/build.gradle.kts` (`versionName`) and `.fdroid.yml` (`CurrentVersion`).
3. Build with Gradle CLI (`./gradlew`), not Android Studio.
4. Pin toolchain in CI (Temurin Java 17, Android platform 35, build-tools 34.0.0).
5. Build release artifacts from a clean checkout and keep release signing keys stable.
6. For reproducibility investigations, diff upstream APK against rebuild using `diffoscope` and follow F-Droid guidance: https://f-droid.org/docs/Reproducible_Builds/
