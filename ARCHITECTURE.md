# Architecture - Voice Control for Spotify

## Overview

Android-приложение для голосового управления музыкой (Spotify, Яндекс Музыка и другие плееры).
Использует офлайн-распознавание речи Vosk с русской языковой моделью.
Работает как foreground service с постоянным прослушиванием микрофона.

## Tech Stack

| Компонент | Технология |
|---|---|
| Язык | Kotlin |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 36 |
| Распознавание речи | Vosk 0.3.47 (офлайн) |
| Языковая модель | vosk-model-small-ru-0.22 (~45 МБ) |
| JNA | 5.13.0 (AAR, нативные библиотеки) |
| UI | ConstraintLayout + Material3 |
| ABI | arm64-v8a, armeabi-v7a |

## Project Structure

```
app/src/main/
  java/com/voicecontrol/spotify/
    MainActivity.kt          # UI, permissions, broadcast receiver, error dialogs
    VoiceCommandService.kt   # Foreground service, Vosk, state machine, ducking, vibration
    ModelManager.kt           # Download & extraction Vosk model
  res/
    layout/activity_main.xml  # Main screen layout
    anim/pulse.xml            # Mic pulse animation
    drawable/                 # ic_mic, ic_stop, ic_help, bg_command,
                              # ic_launcher_foreground (mic + sound waves),
                              # ic_launcher_background (green-yellow gradient),
                              # ic_launcher_monochrome (for Android 13+)
    mipmap-anydpi/            # Adaptive icon definitions
    values/colors.xml         # Spotify green, dark theme, wake yellow
    values/strings.xml        # All UI strings + help dialog + error messages
    values/themes.xml         # Material3 theme + splash theme
  AndroidManifest.xml         # Permissions, service declaration
app/proguard-rules.pro        # Vosk/JNA ProGuard rules for release builds
```

## Architecture Pattern

**Foreground Service + BroadcastReceiver**

```
MainActivity <──broadcast──> VoiceCommandService
     │                              │
     ├── Help dialog                ├── AudioRecord (VOICE_RECOGNITION, 16kHz, mono)
     ├── Retry dialog (errors)      ├── Vosk Recognizer (offline)
     └── Permission handling        ├── AudioManager (media keys, volume, AudioFocus)
                                    ├── ToneGenerator (wake word beep)
                                    ├── Vibrator (wake word vibration)
                                    └── ModelManager (download/extract model)
```

## Threading Model

| Поток | Что делает |
|---|---|
| Main (UI) | Activity lifecycle, broadcast receive, UI updates, command dispatch |
| Recognition Thread | AudioRecord.read() loop + Vosk.acceptWaveForm() |
| Model Load Thread | Model(path) initialization (тяжёлая операция) |
| Download Thread | HTTP download + ZIP extraction |

Все callback'и из фоновых потоков переключаются на main thread через `Handler(Looper.getMainLooper())`.

## State Machine

```
                    ┌──────────────────────┐
                    │       NORMAL         │
                    │  (обычное слушание)  │
                    └─────────┬────────────┘
                              │
                     слово "телефон"
                     + AudioFocus duck
                     + setStreamVolume(5%)
                     + beep + vibration
                              │
                              ▼
                    ┌──────────────────────┐
                    │  WAITING_FOR_COMMAND  │
                    │  (жёлтый mic, 5 сек) │
                    └─────────┬────────────┘
                              │
                 ┌────────────┼────────────┐
                 │            │            │
           команда      "телефон"      таймаут 5с
           найдена      повторно
                 │            │            │
           выполнить     игнор        restore vol
           + restore vol              + abandon
           + abandon                  AudioFocus
           AudioFocus                     │
                 │                        │
                 └────────────┼───────────┘
                              │
                              ▼
                    ┌──────────────────────┐
                    │       NORMAL         │
                    └──────────────────────┘
```

В состоянии NORMAL команды выполняются напрямую (без кодового слова).

## Voice Commands

| Действие | Ключевые слова | MediaKey / Действие |
|---|---|---|
| Следующий трек | следующий, следующую, дальше, вперёд, вперед, next, skip, пропусти | `KEYCODE_MEDIA_NEXT` |
| Предыдущий трек | предыдущий, предыдущую, назад, прошлый, прошлую, previous, back | `KEYCODE_MEDIA_PREVIOUS` x2 (150ms apart) |
| Пауза | пауза, стоп, остановить, остановись, pause, stop | `KEYCODE_MEDIA_PLAY_PAUSE` |
| Воспроизведение | играть, играй, продолжить, воспроизвести, продолжай, продолжи, давай, play, resume | `KEYCODE_MEDIA_PLAY_PAUSE` |
| Громче | громче, увеличь, прибавь, louder, volume up | `adjustStreamVolume(RAISE)` x2 |
| Тише | тише, убавь, потише, quieter, volume down | `adjustStreamVolume(LOWER)` x2 |
| Кодовое слово | телефон | AudioFocus duck + setStreamVolume(5%) + beep + vibration + 5s window |

Матчинг: `String.containsAny()` — если хотя бы одно ключевое слово содержится в распознанном тексте.

Предыдущий трек отправляет `MEDIA_PREVIOUS` дважды с задержкой 150ms, потому что первое нажатие перематывает на начало текущего трека, второе — переключает на предыдущий.

## Audio Pipeline

```
Микрофон
  → AudioRecord (VOICE_RECOGNITION, 16000Hz, MONO, PCM_16BIT)
  → ShortArray buffer (minBufferSize / 2)
  → Vosk Recognizer.acceptWaveForm(buffer, read)
  → JSON result: {"text": "следующий"}
  → processCommand() on main thread
```

**AudioRecord параметры:**
- Source: `VOICE_RECOGNITION` (оптимизирован для речи в шуме, без агрессивной обработки)
- Sample rate: 16000 Hz (требование Vosk)
- Internal buffer: `minBufferSize * 4`

## Audio Ducking (Wake Word)

При распознавании кодового слова "телефон":

1. **AudioFocus** — `AudioFocusRequest.Builder(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)`:
   - Usage: `USAGE_ASSISTANT`
   - ContentType: `CONTENT_TYPE_SPEECH`
   - `setWillPauseWhenDucked(false)`
   - Система просит Spotify приглушиться (ducking ~20-30%)
2. **setStreamVolume** — дополнительно снижает `STREAM_MUSIC` до 5% от максимума (работает на переднем плане; на Realme в фоне блокируется — остаётся только ducking)
3. **Beep** — `ToneGenerator(STREAM_MUSIC, 100)`, `TONE_PROP_BEEP`, 200ms
4. **Vibration** — `VibrationEffect.createOneShot(150ms, DEFAULT_AMPLITUDE)`
5. Через 5 секунд или после команды → restore volume + `abandonAudioFocusRequest()`

**Двойная стратегия громкости:** AudioFocus ducking работает всегда (системный механизм), а setStreamVolume даёт точный контроль на переднем плане. Realme/ColorOS блокирует setStreamVolume для фоновых приложений с ошибкой "do not setStreamVolume in playing".

## Model Management

```
Первый запуск:
  1. ModelManager.isModelReady() → false
  2. downloadAndExtract() в фоновом потоке
  3. HTTP GET https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip
  4. Прогресс: "Скачиваю: 45% (20 МБ)"
  5. extractZip() → filesDir/vosk-model-small-ru-0.22/
  6. Удаление ZIP
  7. Model(path) загрузка в память

Повторный запуск:
  1. ModelManager.isModelReady() → true (директория существует)
  2. Model(path) загрузка в память

Ошибка загрузки:
  1. broadcast с hasError=true
  2. MainActivity показывает диалог с кнопками "Повторить" / "Закрыть"
  3. "Повторить" — перезапускает сервис
  4. "Закрыть" — останавливает сервис
```

Модель хранится в `context.filesDir` (приватное хранилище, без дополнительных разрешений).

## Communication: Service ↔ Activity

**Service → Activity:** `sendBroadcast()` с intent:
- `ACTION_STATUS_UPDATE` = "com.voicecontrol.spotify.STATUS_UPDATE"
- `EXTRA_STATUS_TEXT: String` — текст статуса ("Слушаю...", "Жду команду...", и т.д.)
- `EXTRA_LAST_COMMAND: String` — последняя распознанная фраза
- `EXTRA_IS_LISTENING: Boolean` — идёт ли запись
- `EXTRA_IS_WAITING: Boolean` — режим ожидания команды (жёлтый mic)
- `EXTRA_HAS_ERROR: Boolean` — произошла ошибка (красный mic + retry dialog)

**Activity → Service:** `startForegroundService()` / `stopService()`

Receiver регистрируется в `onResume()`, снимается в `onPause()`.
На API 33+ используется `RECEIVER_NOT_EXPORTED`.

## Permissions

| Permission | Зачем |
|---|---|
| `RECORD_AUDIO` | Доступ к микрофону (runtime) |
| `INTERNET` | Скачивание языковой модели |
| `FOREGROUND_SERVICE` | Работа в фоне |
| `FOREGROUND_SERVICE_MICROPHONE` | Тип foreground service (API 34+) |
| `MODIFY_AUDIO_SETTINGS` | Управление громкостью и AudioFocus |
| `POST_NOTIFICATIONS` | Уведомление foreground service (API 33+) |
| `VIBRATE` | Вибрация при wake word |

## UI

**Тема:** Material3 DayNight NoActionBar, тёмная палитра (bg #121212).
Status bar и navigation bar тоже тёмные. Splash theme использует тёмный фон вместо белого.

**Иконка приложения:** Адаптивная (adaptive icon) — микрофон со звуковыми волнами (белый foreground) на зелёно-жёлтом градиенте (background). Monochrome версия для Android 13+ themed icons.

**Цвета индикатора микрофона:**
- Серый (`#B3B3B3`) — сервис остановлен
- Зелёный (`#1DB954`, Spotify green) — слушает
- Жёлтый (`#FFD600`) — режим ожидания команды (wake word)
- Красный (`#E53935`) — ошибка / кнопка FAB в режиме "стоп"

**Анимация:** pulse.xml — scale 1.0→1.15 + alpha 1.0→0.6, бесконечный цикл reverse, 900ms.

**Help dialog:** Кнопка "?" в правом верхнем углу показывает AlertDialog со всеми голосовыми командами, включая кодовое слово "телефон" и подсказку.

**Error dialog:** При ошибке загрузки модели — AlertDialog с описанием ошибки и кнопками "Повторить" / "Закрыть".

## Notification

Foreground service notification:
- Channel: "VoiceControlChannel", importance LOW, silent
- Title: "Голосовое управление"
- Content: динамический статус ("Слушаю...", "Следующий трек", и т.д.)
- Icon: `ic_mic`
- Ongoing: true
- Click: открывает MainActivity

## ProGuard (Release)

Правила для корректной работы release-сборки:
- `keep class org.vosk.**` — Vosk использует JNI, нельзя обфусцировать
- `keep class com.sun.jna.**` — JNA использует рефлексию для нативных вызовов
- `keepclasseswithmembernames native` — сохранение имён нативных методов
- `keepattributes SourceFile,LineNumberTable` — для читаемых crash reports

## Known Limitations

1. **Музыка мешает распознаванию** — при высокой громкости нужно говорить близко к микрофону. AEC/NoiseSuppressor ухудшали ситуацию. Рекомендуется Bluetooth гарнитура или кодовое слово "телефон" для ducking.
2. **Realme/ColorOS блокирует setStreamVolume в фоне** — решено двойной стратегией: AudioFocus API (всегда) + setStreamVolume (на переднем плане).
3. **Модель small** — компромисс между размером (45 МБ) и точностью. Большая модель (~1.5 ГБ) лучше, но непрактична.
4. **Нет определения текущего трека** — потребует NotificationListenerService или Spotify SDK + TextToSpeech для озвучивания.
5. **Нет Bluetooth SCO** — запись всегда с встроенного микрофона. Добавление Bluetooth SCO микрофона улучшит распознавание при музыке.
