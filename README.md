# ruNNNpe bridge

[![Android Build](https://github.com/zappbrannigan34/ruNNNpe-bridge/actions/workflows/android-build.yml/badge.svg)](https://github.com/zappbrannigan34/ruNNNpe-bridge/actions/workflows/android-build.yml)
[![Release](https://img.shields.io/github/v/tag/zappbrannigan34/ruNNNpe-bridge?label=release)](https://github.com/zappbrannigan34/ruNNNpe-bridge/releases/latest)
[![Platform](https://img.shields.io/badge/platform-Android-3DDC84)](https://developer.android.com/)
[![Min SDK](https://img.shields.io/badge/minSDK-29-blue)](https://developer.android.com/about/versions/android-10)
[![Health Connect](https://img.shields.io/badge/Health%20Connect-enabled-0A66C2)](https://developer.android.com/health-and-fitness/guides/health-connect)

Android bridge between NPE RUNN and Google Health Connect.

## Table of contents

- [Features](#features)
- [Screenshots](#screenshots)
- [Download](#download)
- [Quick start](#quick-start)
- [Project docs](#project-docs)

## Features

- Background BLE monitoring with foreground service.
- Automatic workout start and finish detection.
- Automatic HR sensor discovery and reconnect.
- Live metrics in app and notification.
- Health Connect write: session, segment, speed, distance, steps, HR, calories, elevation.

## Screenshots

### ruNNNpe bridge app screen

![ruNNNpe bridge app screen](docs/images/runnbridge-app-screen.png)

### NPE RUNN device

![NPE RUNN device](docs/images/npe-runn-device.webp)

## Download

- Latest APK: [Releases](https://github.com/zappbrannigan34/ruNNNpe-bridge/releases/latest)
- Release asset naming: `ruNNNpe bridge-<tag>.apk`

## Quick start

```bat
gradlew.bat assembleDebug
```

After install:

1. Grant BLE, notifications, and Health Connect permissions.
2. Tap `Find RUNN & Start`.
3. Keep app unrestricted in battery settings for stable background work.

## Project docs

- Setup: `docs/SETUP.md`
- Architecture: `docs/ARCHITECTURE.md`
- Dependencies: `docs/DEPENDENCIES.md`
- Troubleshooting: `docs/TROUBLESHOOTING.md`
- CI/CD: `docs/CI_CD.md`
- Release checklist: `RELEASE.md`
