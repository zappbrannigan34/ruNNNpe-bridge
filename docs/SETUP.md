# ruNNNpe bridge - Setup

## Требования

- Android Studio (Hedgehog+)
- JDK 17
- Android SDK 34
- Android device with BLE (Android 10+)
- Health Connect installed/available

## Локальная сборка

```bat
gradlew.bat assembleDebug
```

APK после сборки:

- `app/build/outputs/apk/debug/app-debug.apk`

## Запуск на устройстве

1. Включить Bluetooth.
2. Установить debug APK.
3. Запустить приложение.
4. Выдать все разрешения (BLE, notifications, Health Connect).
5. Нажать `Find RUNN & Start`.

## Первая конфигурация

- `Save Profile Fallback` - ручные параметры профиля (если нет данных в HC).
- HR датчик выбирается автоматически в фоне при старте тренировки.
- После первичной настройки сервис продолжает работу в фоне.

## Xiaomi (рекомендуется)

- Приложение -> Батарея -> Без ограничений.
- Автозапуск -> Включить.
- Разрешить уведомления и закрепить в recent apps.
