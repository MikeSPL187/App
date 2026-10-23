# Передача работы: «Тихие файлы»

Документ для продолжения работы в новой сессии. Читать вместе с [`spec/TZ.md`](../spec/TZ.md) (техзадание), [`docs/v1.0.md`](v1.0.md) (что сделано в 1.0) и макетами в [`design/mockups/`](../design/mockups).

## Где код

- Репозиторий: `mikespl187/app`, ветка **`ccr-8bb902a5-73wrcu`**. Всё запушено туда, PR не создавался.
- Приложение с нуля: Kotlin 2.2, Jetpack Compose, Material 3, Hilt, Room, DataStore, WorkManager, Coil 3. Пакет `io.quietfiles`, minSdk 30, targetSdk 36.
- Код старого File Manager+ не используется и в репозиторий не добавлялся.

## Состояние

| Что | Статус |
|---|---|
| v1.0 целиком (F1–F7, F10, F14, F15, безопасность) | ✅ готово, проверено тестами, CI на GitHub зелёный |
| v1.1 F12 текстовый редактор (`feature/editor`) | ✅ готово, закоммичено, тесты зелёные |
| v1.1 F8 просмотр фото (`feature/viewer`) | ⚠️ **написан и закоммичен как черновик, последние правки не проверены** (см. ниже) |
| v1.1 F9 видеоплеер, F11 музыкальный плеер | ⏳ не начато |
| v1.1 F13 очистка и анализ | ⏳ не начато |
| v1.1 прочее: F1.5 вкладки, F5.1 групповое переименование, F5.11 архивы с паролем, F6.6 поиск по содержимому | ⏳ не начато |

### Что нужно проверить первым делом (F8)

Последний коммит «WIP: photo viewer» не прогонялся тестами после трёх правок:
1. общий помощник `core/common/.../VolumePaths.kt` (`displayPath`, `volumeShortName`) — путь «Память/DCIM/Camera» вместо абсолютного; редактор и просмотр фото переведены на него, строки `editor_volume_*` удалены;
2. фон у миниатюр в ленте просмотра фото;
3. `Thread.sleep(1_500)` перед снимком `14_photo_viewer` в `AppSmokeTest` — проверить, успевает ли Coil показать фото в Robolectric; если нет, убрать задержку.

Команда проверки:
```bash
export ANDROID_HOME=/opt/android-sdk   # или путь к своему SDK
./gradlew --no-parallel test lintDebug :app:assembleRelease
```
Затем посмотреть снимки в `app/build/screenshots/` (особенно `14_photo_viewer.png`, `15_photo_details.png`).

## Сборка и окружение (важно)

- `local.properties` с `sdk.dir=...` (в git не входит).
- Maven Central в облачной среде отдаёт 429 — в `settings.gradle.kts` и `build-logic/settings.gradle.kts` первым стоит зеркало `https://maven-central.storage-download.googleapis.com/maven2/`. Robolectric качает образы Android с того же зеркала (системное свойство в `build-logic/.../ProjectExtensions.kt`).
- Тесты запускаются с `LC_ALL=C.UTF-8` (кириллица в именах файлов).
- Последний прогон в облачной среде убили по памяти (код 137) — запускать с `--no-parallel`, при необходимости `./gradlew --stop` перед прогоном.
- lint не анализирует тестовые исходники (его анализатор падает на тестах Compose UI) — `lint.ignoreTestSources = true` в convention-плагинах.
- Эмулятора в облачной среде нет. Визуальная проверка — через `AppSmokeTest` (Robolectric + Hilt, настоящий `MainActivity`, сохраняет PNG всех окон, включая диалоги и листы).

## Архитектура (кратко)

```
app                 навигация (3 вкладки + рейка от 600 dp), лист операций, онбординг, инструменты,
                    PickerActivity (GET_CONTENT/PICK), SaveActivity (SEND), блокировка (BiometricPrompt),
                    подписанные ярлыки папок, FileOpener → встроенные просмотрщики
feature/home        главная
feature/browser     обзор, библиотеки, галерея фото/видео, архивы, корзина, выбор, экран выбора файла/папки
feature/search      поиск
feature/settings    настройки
feature/editor      текстовый редактор (F12)
feature/viewer      просмотр фото (F8); сюда же логично класть видео и музыку или завести feature/player
core/model          чистая логика без Android (имена, сортировка, MIME, настройки)
core/common         диспетчеры, форматирование, DeviceAuth, VolumePaths
core/designsystem   тема из design/tokens.json, значки QfIcons (stroke-пути из макетов), компоненты, LocalFileOpener
core/storage        локальная ФС, тома, MediaStore + запасной обход ФС, FileObserver, ApkInspector, LocationStripper
core/data           Room, DataStore (SettingsRepository)
core/operations     очередь операций в foreground-сервисе, корзина, архивы, журнал восстановления, импорт
```

Принципы, которых держались:
- Операции с файлами — только через `OperationQueue`; запись во временный `.qfpart` и переименование в конце.
- Встроенные просмотрщики открываются через `LocalFileOpener` (предоставляет `AppRoot`); список соседних файлов передаётся через `ViewerSessions`, в маршруте — только номер сессии.
- Строки всегда в двух языках: `values` (английский) и `values-ru`.
- Чистая логика выносится в тестируемые объекты (см. `feature/editor/.../text/*`).
- Перед коммитом: `test`, `lintDebug` без предупреждений, `assembleRelease` (R8).
- Отклонения от ТЗ записываются в `docs/v1.0.md` (раздел «Отличия»), для 1.1 — завести `docs/v1.1.md`.

## Следующие шаги (v1.1)

1. Проверить и довести F8 (см. выше), обновить коммит.
2. F9 видеоплеер и F11 музыка на Media3 (ExoPlayer + `MediaSessionService`), макеты `VideoPlayer`, `VideoSettings`, `MusicPlayer`, `MusicQueue`: жесты, скорость, субтитры SRT, дорожки, PiP, позиции последних 200 файлов в Room, мини-плеер над навигацией, таймер сна, обложки.
3. F13 очистка (макет `Cleaner`): дубликаты (размер → хэш краёв 64 КБ → SHA-256), установленные APK, кэш, корзина, большие файлы, анализ папок.
4. F5.1 групповое переименование, F5.11 архивы с паролем (zip AES — понадобится zip4j или аналог; 7z — commons-compress), F6.6 поиск по содержимому (FTS в Room, индексация при зарядке), F1.5 вкладки.
5. Обновить `docs/` и `README.md`, итоговый отчёт пользователю.

## Договорённости с пользователем

- Общение на русском, работа «очень качественно и грамотно», без остановок.
- Коммиты — в ветку `ccr-8bb902a5-73wrcu`, PR не создавать без просьбы.
- В коммитах и коде не упоминать модель; подпись коммитов — по правилам среды.
