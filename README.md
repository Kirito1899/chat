# Private Messenger

Закрытый Android-мессенджер с сервером на Kotlin. Реализованы приращения 1–4: аккаунты по приглашению, email verification/recovery, username search, одно активное устройство на аккаунт, личные и групповые сообщения со сквозным шифрованием (E2EE), зашифрованные медиа.

## Что работает

- Регистрация, подтверждение и восстановление по email, вход и ротация сессий, поиск по `@username`.
- Личные текстовые сообщения через официальный `libsignal-client` 0.86.5 из Maven Central: PQXDH и Double Ratchet, подписанные EC/Kyber prekeys и last-resort Kyber key. При остатке менее 20 EC prekeys загружаются новые EC/Kyber keys с проверкой собственной identity и подписей; identity не меняется.
- Сервер хранит только публичные ключи и недоставленные ciphertext envelopes с TTL. Одноразовые prekeys выдаются атомарно; повторная отправка с тем же message ID не создаёт дубликат.
- Android хранит identity, prekeys, Signal sessions, сообщения, outbox и черновики в SQLCipher. Ключи расшифровки защищены Android Keystore.
- Offline outbox, подтверждение доставки после durable local save, статусы `PENDING`/`SENT`/`DELIVERED`/`READ`.
- Safety number из 60 цифр и QR. Смена identity блокирует отправку; пользователь видит fingerprint нового bundle и принимает именно просмотренный bundle после проверки подписей.
- Восстановление сессии, черновиков и выбранного чата/группы после пересоздания Activity и process death. Навигация хранится в SQLCipher, автопрокрутка к последнему сообщению, адаптивные layouts и Flex Mode.
- HTTPS с проверкой CA/hostname. Dev APK использует локальный dev CA; cleartext отключён.
- Светлая и тёмная темы, безопасные system-bar insets, аватары, раздельные входящие/исходящие bubbles, время и иконки статусов, карточки auth и групп.
- Группы до 50 участников с pairwise Signal E2EE и отдельным envelope для каждого получателя. Название группы находится только в E2EE payload; владелец управляет участниками. Для добавления участник должен хотя бы раз войти и опубликовать ключи, иначе сервер отклоняет операцию.
- Новый участник не получает прежнюю историю. После удаления участника сервер запрещает ему новые сообщения и удаляет его недоставленные group envelopes.
- Групповые статусы доставки/прочтения по каждому получателю. Открытие группы сразу отмечает входящие прочитанными; одновременные вызовы не создают повторные receipts. Каждый receipt имеет отдельный envelope UUID.
- Фото, видео, произвольные файлы, стикеры и голосовые. Файл шифруется новым AES-256-GCM ключом по 64-KiB chunks с аутентификацией порядка и длины; ключ, имя и MIME передаются только в E2EE payload.
- Возобновляемые upload/download, streaming-расшифровка для Media3, повреждённый ciphertext отклоняется. Максимум 512 MiB, TTL 7 дней, удаление владельцем. Получатель и caption pending upload хранятся в SQLCipher; `JobScheduler` возобновляет их после выгрузки процесса, а атомарная `dispatched`/`messageId` отметка предотвращает повторную отправку.
- WSS foreground service с переподключением и обновлением access token. События содержат только `SYNC`/`DELIVERED`, без текста, отправителя или файловых ключей.
- Собственный fallback через `JobScheduler` каждые 15 минут при наличии сети, включая восстановление после выгрузки процесса. Уведомления обобщённые и не содержат plaintext; разрешение запрашивается после входа. Android `force-stop` и OEM-ограничения могут отменить фоновую работу до следующего ручного запуска.
- Опциональный `API_FALLBACK_URL` для REST и WSS. Переключение выполняется по фактически отказавшему endpoint даже при параллельных запросах; latency успешного переключения измеряется. Timeout, HTTP-ошибка и TLS-ошибка не приводят к слепому повтору запроса на другом endpoint.

FCM как дополнительный канал и production deployment относятся к следующим этапам и пока не заявляются как готовые. FCM не имитируется без реальных Firebase-настроек.

## Требования

- JDK 21; JVM bytecode проекта — 17.
- Android SDK 37.1. Путь указывается в неотслеживаемом `local.properties`.
- Docker Desktop не требуется для dev: PostgreSQL 18.7 и Mailpit запускаются как настоящие локальные процессы. `docker-compose.yml` остаётся вариантом для отдельного Linux-хоста.
- adb и телефон с разрешённой USB-отладкой.

## Dev-запуск

В Git Bash:

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
bash scripts/dev-up.sh
```

Скрипт создаёт локальные секреты в `.local/`, dev TLS CA/server certificate, запускает PostgreSQL 18.7 и Mailpit, настраивает `adb reverse tcp:8443 tcp:8443` и запускает Ktor на `https://127.0.0.1:8443`.

- Mailpit UI: `http://127.0.0.1:8025`.
- Dev CA публичен и копируется только в debug source set. Приватные ключи, БД, APK-копии и `.local/` не коммитятся. `--ssl-no-revoke` в Windows curl нужен только потому, что у offline dev CA нет CRL; CA и hostname всё равно проверяются.
- Mailpit принимает настоящий SMTP, но не доказывает внешнюю доставку. Production требует отдельный SMTP/API-аккаунт с TLS.

Для создания одноразового приглашения в другом Git Bash:

```bash
source .local/dev-env.sh
curl --ssl-no-revoke --cacert .local/tls/ca.crt \
  -H "X-Admin-Token: $ADMIN_TOKEN" \
  -X POST https://127.0.0.1:8443/v1/admin/invites
```

Возвращённый invite code вводится при регистрации. Verification/recovery code читается в Mailpit и вводится в приложении.

## Сборка и проверки

Полный host-side gate:

```bash
./gradlew assembleDebug :shared:test :server:test :server:integrationTest \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

PostgreSQL integration tests используют Zonky с настоящими PostgreSQL 18.7 binaries. SMTP test использует настоящий Mailpit на `127.0.0.1:1025/8025`. Эти проверки не заменяются mocks и не пропускаются незаметно. `HundredUserLoadTest` создаёт 100 аккаунтов/устройств, группу из 50 участников и 148 ciphertext envelopes, проверяя очереди каждого получателя и измеряя длительность.

При запущенном dev-окружении:

- `bash scripts/smoke-test.sh` проверяет admin invite → register → Mailpit verify → login → search → recovery → reset → login.
- `bash scripts/provision-device-account.sh` создаёт отдельный тестовый аккаунт для свободного device ID.
- `bash scripts/e2ee-two-clients.sh` собирает и устанавливает два изолированных application ID на одном телефоне, проверяет PQXDH сообщения в обе стороны, offline-доставку, safety number, ciphertext в PostgreSQL, отсутствие plaintext и крэшей в logcat. Аккаунты должны соответствовать device ID установок; для другой dev-базы задайте `A_EMAIL`, `A_USER`, `B_EMAIL`, `B_USER`, `E2EE_TEST_PASSWORD`.
- `bash scripts/group-three-clients.sh` проверяет три изолированных клиента: приглашения с pairwise E2EE, отсутствие прежней истории у нового участника, PNG/MP4/WAV/TXT, group receipts, удаление участника и offline delivery. Сначала каждый клиент публикует свои ключи. `CLIENT_A_SUFFIX`, `CLIENT_B_SUFFIX`, `CLIENT_C_SUFFIX` задают application ID; `A_EMAIL`, `A_USER`, `B_EMAIL`, `B_USER`, `C_EMAIL`, `C_USER` — соответствующие тестовые аккаунты. Для новых installation ID нужны новые тестовые аккаунты или явно отозванные старые тестовые устройства: ограничение `DEVICE_LIMIT` не обходится.
- `bash scripts/sync-fallback-two-clients.sh` проверяет реальный WSS event, затем `am kill` процесса получателя, доставку через `JobScheduler`, восстановление зашифрованного хранилища и обобщённое системное уведомление. Сценарий использует локальные dev-аккаунты, заданные в начале скрипта, и проверяет отсутствие plaintext и крэшей в logcat. Для проверки failover задайте `API_BASE_URL=https://127.0.0.1:9443/ API_FALLBACK_URL=https://127.0.0.1:8443/`: первый локальный endpoint отклоняет соединение, второй обслуживает все запросы.
- `bash scripts/media-resume-two-clients.sh` создаёт pending upload, выгружает процесс отправителя и дважды запускает `JobScheduler`. Проверяет доставку получателю, сохранение `dispatched` и отсутствие дубликата после повторного resume.
- `bash scripts/state-restoration.sh` проверяет черновик и выбранный чат после `recreate`, `HOME` и настоящего `am kill`, затем запускает Activity повторно и проверяет отсутствие plaintext в logcat.

Для ручного прогона реального account/draft сценария передайте `email`, `password` и `username` в `dev.securemessenger.ui.RealAccountFlowTest` через `am instrument`. Тесты `E2eeConversationTest` запускаются приёмочным скриптом, а не без параметров в общем `connectedDebugAndroidTest`.

## Сканирование зависимостей

`bash scripts/security-scan.sh` собирает debug APK и серверный `installDist`, затем запускает официальный OWASP Dependency-Check 12.1.9 против APK и всех server jars. HTML/JSON-отчёты сохраняются в `build/security/`; CVSS High/Critical (7+) завершает проверку ошибкой. Сканер устанавливается локально в `.local/security-tools/` и проверяется по SHA-256 в скрипте. При наличии `NVD_API_KEY` его можно передать через переменную окружения, не записывая в проект. Используется 12.1.9, поскольку 13.0.0 имеет [подтверждённый upstream-баг обновления NVD без API key](https://github.com/dependency-check/DependencyCheck/issues/8715). Ktor обновлён до 3.6.2, Netty — до 4.2.21.Final, Kotlin Reflect — до 2.4.20. Узкие suppression-правила для подтверждённых ложных сопоставлений находятся в `config/dependency-check-suppressions.xml`; реальные Netty-библиотеки сканируются отдельно. Последний прогон не оставил неподавленных CVE, но OSS Index без отдельных credentials отключён. Сканирование зависимостей не заменяет аудит кода и не доказывает отсутствие уязвимостей.

## Release APK и server distribution

```bash
API_BASE_URL=https://chat.example.org/ bash scripts/build-release.sh
```

Скрипт собирает minified release APK и `server/build/install/server`, затем проверяет подпись через `apksigner.jar`. При первом запуске создаётся RSA-3072 keystore в `.local/release/securemessenger-release.p12`; параметры доступа сохраняются только в `.local/release-signing.sh`. Секреты не коммитятся. Сделайте зашифрованную резервную копию обоих путей: потеря ключа не позволит выпускать обновления, а его утечка позволит подписывать чужие APK от имени проекта. `API_FALLBACK_URL` необязателен, но тоже должен использовать HTTPS.

Для локальной проверки подписи и запуска без production endpoint используется `ALLOW_LOCAL_RELEASE=true CLIENT_SUFFIX=.release bash scripts/build-release.sh`. Отдельный application ID не заменяет debug-данные. Такая сборка не является production configuration: release доверяет системным CA, а не локальному dev CA, и должен использовать публичный HTTPS endpoint.

Готовый APK находится в `app/build/outputs/apk/release/app-release.apk`, серверный дистрибутив — в `server/build/install/server/`. Для production APK задайте настоящий `API_BASE_URL` вместо `chat.example.org`. Публикация исходного кода и лицензия AGPL находятся в репозитории проекта; не удаляйте `LICENSE` и уведомления о лицензиях зависимостей при распространении.

Файлы для установки на always-on Linux-хост, systemd sandbox, TLS renewal и зашифрованные резервные копии находятся в `deploy/`; инструкции — в `deploy/README.md`. Пакет сервера собирается командой `bash scripts/package-server.sh`.

## Production-границы

Текущая dev-конфигурация не является production deployment. Для него нужны выделенный Linux-хост, публичный CA certificate, реальный SMTP с TLS, отдельные production-секреты, резервные копии, мониторинг, нагрузочное тестирование и независимый security audit. `KTOR_HOST` по умолчанию безопасно ограничен `127.0.0.1`. Автоматические тесты снижают риск ошибок, но не доказывают отсутствие всех уязвимостей.
