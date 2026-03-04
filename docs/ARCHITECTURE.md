# RUNN Bridge - Архитектура

## Обзор
Автоматический мост между NPE RUNN и Google Health Connect.

## Компоненты

### FtmsParser.kt
Парсит FTMS Treadmill Data (0x2ACD) и RSC (0x2A53) BLE характеристики.
- TreadmillSnapshot: скорость, средняя скорость, дистанция, наклон, время, пульс
- RscSnapshot: скорость, каденс, дистанция

### WorkoutStateMachine.kt
State machine: IDLE → RUNNING → COOLDOWN
- Автоопределение начала тренировки (speed ≥ 0.3 m/s)
- Автоопределение конца (2 мин покоя)
- Минимальная длительность: 1 мин
- Накопление данных: скорости, дистанция, шаги, наклон

### HealthConnectWriter.kt
Запись в Health Connect:
- ExerciseSessionRecord (бег на дорожке)
- SpeedRecord (образцы скорости)
- DistanceRecord (дистанция)
- StepsRecord (шаги)

### BleForegroundService.kt
Foreground service для BLE:
- Периодический скан (5 сек каждые 30 сек)
- GATT подключение к RUNN
- Подписка на FTMS/RSC уведомления
- WakeLock для фоновой работы
- START_STICKY - перезапуск после kill

### BootReceiver.kt
Автозапуск после:
- BOOT_COMPLETED
- MY_PACKAGE_REPLACED

### MainActivity.kt
UI для настройки:
- Проверка Health Connect
- Запрос BLE разрешений
- Запрос Health Connect разрешений
- Скан и подключение к RUNN
- Сохранение MAC адреса

## Поток данных
1. NPE RUNN транслирует BLE notifications (FTMS 0x2ACD или RSC 0x2A53)
2. BleForegroundService получает raw bytes через GATT callback
3. FtmsParser декодирует: скорость, дистанция, наклон, пульс, каденс
4. WorkoutStateMachine определяет начало/конец тренировки
5. HealthConnectWriter записывает ExerciseSession + метрики
6. Данные доступны в Google Fit, Strava и других приложениях

## State Machine
- IDLE — ожидание движения
- RUNNING — накопление данных
- COOLDOWN — 2 мин таймаут до завершения

## Статистика проекта
- 6 Kotlin файлов
- ~800 строк кода
- Min SDK: API 29+
- Зависимости: только системные AndroidX
