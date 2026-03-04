# Руководство по сборке RUNN Bridge

## Шаг 1: Установка инструментов
- Скачать Android Studio: developer.android.com/studio
- Установить (JDK 17 встроен)
- При первом запуске — установить Android SDK 34

## Шаг 2: Создание проекта
- Android Studio → New Project → "No Activity"
- Name: RunnBridge
- Package: com.example.runnbridge
- Language: Kotlin, Min SDK: API 29

## Шаг 3: Замена файлов
Корень проекта:
- settings.gradle.kts
- build.gradle.kts
- gradle.properties

app/:
- app/build.gradle.kts — зависимости
- app/src/main/AndroidManifest.xml — разрешения
- res/values/strings.xml
- res/values/themes.xml

Java файлы:
- java/com/example/runnbridge/FtmsParser.kt
- java/com/example/runnbridge/WorkoutStateMachine.kt
- java/com/example/runnbridge/HealthConnectWriter.kt
- java/com/example/runnbridge/BleForegroundService.kt
- java/com/example/runnbridge/BootReceiver.kt
- java/com/example/runnbridge/MainActivity.kt

## Шаг 4: Sync & Build
- File → Sync Project with Gradle Files
- Дождаться "BUILD SUCCESSFUL"

## Шаг 5: Подготовка телефона
- Включить режим разработчика (7 тапов по "Номер сборки")
- Включить "Отладка по USB"
- Установить Health Connect из Play Store (Android < 14)
- Подключить телефон USB-кабелем

## Шаг 6: Запуск
- Выбрать устройство в выпадающем списке
- Нажать ▶ (Run)
- Дождаться установки на телефон

## Шаг 7: Первая настройка
- Включить дорожку (чтобы RUNN был активен)
- Открыть RUNN Bridge на телефоне
- Нажать "⚡ Найти RUNN и запустить"
- Дать все разрешения
- Дождаться "✅ Готово!" — закрыть приложение
