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
  - application ID and version env overrides
- `.github/workflows/publish.yml`
  - builds release APK + AAB and publishes both assets
- `.github/workflows/android-build.yml`
  - validates debug and release outputs on CI
- `fdroid.yml`
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

If these secrets are not set, release builds fall back to debug signing and should not be uploaded to Play production.
