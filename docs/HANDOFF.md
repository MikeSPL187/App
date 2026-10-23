# Передача работы: «Тихие файлы»

Документ для продолжения работы в новой сессии. Читать вместе с [`spec/TZ.md`](../spec/TZ.md) (техзадание), [`docs/v1.0.md`](v1.0.md) (что сделано в 1.0) и макетами в [`design/mockups/`](../design/mockups).

## Где код

- Репозиторий: `mikespl187/app`. Версии 1.0–1.1 — в ветке `ccr-8bb902a5-73wrcu`, версия 1.2 — в ветке **`ccr-e313f98d-ba8rpx`** (продолжает 1.1). PR не создавался.
- Приложение с нуля: Kotlin 2.2, Jetpack Compose, Material 3, Hilt, Room, DataStore, WorkManager, Coil 3. Пакет `io.quietfiles`, minSdk 30, targetSdk 36.
- Код старого File Manager+ не используется и в репозиторий не добавлялся.

## Состояние

| Что | Статус |
|---|---|
| v1.0 целиком (F1–F7, F10, F14, F15, безопасность) | ✅ готово, см. [`v1.0.md`](v1.0.md) |
| v1.1 целиком: F8 фото, F9 видео, F11 музыка, F12 редактор, F13 очистка, F1.5 окна, F5.1 групповое переименование, F5.11 архивы с паролем, F6.6 поиск по содержимому | ✅ готово, см. [`v1.1.md`](v1.1.md) (там же отличия от ТЗ) |
| v1.2 F16 сеть (SMB, SFTP, FTP/FTPS, WebDAV), F17 «Доступ с ПК», S1–S4, S6 | ✅ готово, см. [`v1.2.md`](v1.2.md) |

Всё проверено `test`, `lintDebug` (без замечаний) и `:app:assembleRelease`; на устройстве не запускалось — эмулятора в среде нет.

## Сборка и окружение (важно)

- `local.properties` с `sdk.dir=...` (в git не входит).
- Maven Central в облачной среде отдаёт 429 — в `settings.gradle.kts` и `build-logic/settings.gradle.kts` первым стоит зеркало `https://maven-central.storage-download.googleapis.com/maven2/`. Robolectric качает образы Android с того же зеркала (системное свойство в `build-logic/.../ProjectExtensions.kt`).
- Тесты запускаются с `LC_ALL=C.UTF-8` (кириллица в именах файлов).
- Последний прогон в облачной среде убили по памяти (код 137) — запускать с `--no-parallel`, при необходимости `./gradlew --stop` перед прогоном.
- lint не анализирует тестовые исходники (его анализатор падает на тестах Compose UI) — `lint.ignoreTestSources = true` в convention-плагинах.
- Эмулятора в облачной среде нет. Визуальная проверка — через `AppSmokeTest` (Robolectric + Hilt, настоящий `MainActivity`, сохраняет PNG всех окон, включая диалоги и листы).
- Android SDK в новом контейнере может отсутствовать: `cmdline-tools` с dl.google.com, затем `sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"` в `/opt/android-sdk`.
- Полный прогон занимает 12–17 минут. **Не правьте модули, пока идёт сборка**: Gradle может подхватить половину изменений.

### Особенности тестов в Robolectric

- Coil: `ImageDecoder` в Robolectric не работает, аппаратные битмапы не рисуются на снимке — в `AppSmokeTest` загрузчик с `imageDecoderEnabled(false)` и `allowHardware(false)`.
- Тест работает на главном потоке: вместо `Thread.sleep` — `waitForImages()` (сон + `idleFor` лупера), иначе фоновая работа не доходит до экрана.
- Media3: в тесте подключены фейковые декодеры `ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()` (артефакт `media3-test-utils-robolectric`), тестовые медиафайлы — в `app/src/test/resources/media/`.
- `AlertDialog` с текстовым полем в Robolectric не становится «idle» — формы с полями делаем нижними листами.
- «Назад» из теста идёт в диспетчер Activity, а не в окно листа: лист и экран под ним закрываются одним нажатием.
- Media3: нестабильные API помечаются `@androidx.annotation.OptIn(UnstableApi::class)` — котлиновский `@OptIn` lint не засчитывает.

## Архитектура (кратко)

```
app                 навигация (4 вкладки + рейка от 600 dp), лист операций, окна (F1.5), онбординг,
                    PickerActivity, SaveActivity, блокировка, ярлыки, FileOpener → встроенные просмотрщики,
                    мини-плеер, задачи WorkManager (корзина, индекс содержимого), кэш приложения для очистки
feature/home        главная («Можно освободить N», кнопка окон)
feature/browser     обзор, библиотеки, галерея, архивы (пароли), корзина, выбор («Ещё»), групповое переименование
feature/search      поиск по имени и по содержимому
feature/settings    настройки
feature/editor      текстовый редактор (F12)
feature/viewer      просмотр фото (F8)
feature/player      видео (ViewModel с ExoPlayer и MediaSession) и музыка (MusicPlayer + PlaybackService)
feature/cleaner     «Инструменты»: очистка (F13), подробности пунктов, анализ папок
feature/network     «Сеть»: список серверов и статусы, форма подключения, доверие ключу/сертификату, «Доступ с ПК»
core/model          чистая логика: имена, сортировка, MIME, медиа (время, кодеки, субтитры, FLAC/WAV),
                    очистка (дубликаты, выбор), переименование, окна, кодировки, извлечение текста (PDF, DOCX)
core/common         диспетчеры, форматирование, DeviceAuth, VolumePaths, ViewerSessions
core/designsystem   тема, значки QfIcons, компоненты, LocalFileOpener, LocalWindows, ImmersiveMode
core/storage        локальная ФС, тома, MediaStore, FileObserver, ApkInspector, LocationStripper
core/data           Room (quietfiles.db v3: избранное, история, корзина, позиции, история очистки),
                    content_index.db (FTS4), DataStore (настройки, окна)
core/operations     очередь операций, корзина, архивы (zip4j, commons-compress, junrar), пароли архивов,
                    групповое переименование, сканер и исполнитель очистки
```

Принципы, которых держались:
- Операции с файлами — только через `OperationQueue`; запись во временный `.qfpart` и переименование в конце.
- Встроенные просмотрщики открываются через `LocalFileOpener` (предоставляет `AppRoot`); список соседних файлов передаётся через `ViewerSessions`, в маршруте — только номер сессии.
- Строки всегда в двух языках: `values` (английский) и `values-ru`.
- Чистая логика выносится в тестируемые объекты (см. `feature/editor/.../text/*`).
- Перед коммитом: `test`, `lintDebug` без предупреждений, `assembleRelease` (R8).
- Отклонения от ТЗ записываются в `docs/v1.0.md` (раздел «Отличия»), для 1.1 — завести `docs/v1.1.md`.

## Сеть (1.2) — что важно знать

- Пути серверов — `/qf-net/<id>/<путь>` (`core/model/.../network/NetworkPaths`); `RoutingStorageProvider` (привязан к `StorageProvider` в `NetworkModule`) отправляет их в `NetworkStorageProvider`.
- Секреты — только через `SecretStore` (`core/security`), шифр подменяется в тестах приложения `TestSecurityModule` (в Robolectric нет Keystore).
- Тесты протоколов поднимают настоящие серверы (MINA SSHD, Apache FtpServer, `TestDavServer`); для TLS на стороне сервера в Robolectric нужен `--add-opens java.base/java.net` (в `core/network/build.gradle.kts`).
- BouncyCastle: все артефакты одной версии (иначе провайдер падает при загрузке), в R8 оставлены только нужные алгоритмы (`core/network/consumer-rules.pro`).
- В `PcAccessServer` есть `addressOverride` для тестов: в Robolectric нет Wi-Fi.

## Следующие шаги (2.0)

1. Прогон 1.1 и 1.2 на устройствах по разделу 12 ТЗ: настоящие SMB (Windows, Samba, NAS), Nextcloud, FileZilla/WinSCP к «Доступу с ПК», release-сборка с SFTP и SMB.
2. v2.0 по ТЗ: F18 облака (OAuth + PKCE, S5), F19 две панели, F20 Premium, защищённая папка (S12), приложения и APK, root/Shizuku, USB OTG.
3. Вести `docs/v2.0.md`, обновлять `README.md`.

## Договорённости с пользователем

- Общение на русском, работа «очень качественно и грамотно», без остановок.
- Коммиты 1.2 — в ветку `ccr-e313f98d-ba8rpx`, PR не создавать без просьбы.
- В коммитах и коде не упоминать модель; подпись коммитов — по правилам среды.
