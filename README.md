# Private Messenger

Закрытый Android-мессенджер с сервером на Kotlin. Реализованы приращения 1–3: аккаунты по приглашению, email verification/recovery, username search, одно активное устройство на аккаунт, личные и групповые сообщения со сквозным шифрованием (E2EE), зашифрованные медиа.

## Что работает

- Регистрация, подтверждение и восстановление по email, вход и ротация сессий, поиск по `@username`.
- Личные текстовые сообщения через официальный `libsignal-client` 0.86.5 из Maven Central: PQXDH и Double Ratchet, подписанные EC/Kyber prekeys и last-resort Kyber key.
- Сервер хранит только публичные ключи и недоставленные ciphertext envelopes с TTL. Одноразовые prekeys выдаются атомарно; повторная отправка с тем же message ID не создаёт дубликат.
- Android хранит identity, prekeys, Signal sessions, сообщения, outbox и черновики в SQLCipher. Ключи расшифровки защищены Android Keystore.
- Offline outbox, подтверждение доставки после durable local save, статусы `PENDING`/`SENT`/`DELIVERED`/`READ`.
- Safety number из 60 цифр и QR. Смена identity блокирует отправку; пользователь видит fingerprint нового bundle и принимает именно просмотренный bundle после проверки подписей.
- Восстановление сессии и черновиков после пересоздания Activity/перезапуска приложения, автопрокрутка к последнему сообщению, адаптивные layouts и Flex Mode.
- HTTPS с проверкой CA/hostname. Dev APK использует локальный dev CA; cleartext отключён.
- Группы до 50 участников с pairwise Signal E2EE и отдельным envelope для каждого получателя. Название группы находится только в E2EE payload; владелец управляет участниками. Для добавления участник должен хотя бы раз войти и опубликовать ключи, иначе сервер отклоняет операцию.
- Новый участник не получает прежнюю историю. После удаления участника сервер запрещает ему новые сообщения и удаляет его недоставленные group envelopes.
- Групповые статусы доставки/прочтения по каждому получателю. Открытие группы сразу отмечает входящие прочитанными; одновременные вызовы не создают повторные receipts. Каждый receipt имеет отдельный envelope UUID.
- Фото, видео, произвольные файлы, стикеры и голосовые. Файл шифруется новым AES-256-GCM ключом по 64-KiB chunks с аутентификацией порядка и длины; ключ, имя и MIME передаются только в E2EE payload.
- Возобновляемые upload/download, streaming-расшифровка для Media3, повреждённый ciphertext отклоняется. Максимум 512 MiB, TTL 7 дней, удаление владельцем.
- WSS foreground service с переподключением и обновлением access token. События содержат только `SYNC`/`DELIVERED`, без текста, отправителя или файловых ключей.
- Собственный fallback через `JobScheduler` каждые 15 минут при наличии сети, включая восстановление после выгрузки процесса. Уведомления обобщённые и не содержат plaintext; разрешение запрашивается после входа. Android `force-stop` и OEM-ограничения могут отменить фоновую работу до следующего ручного запуска.

FCM как дополнительный канал, резервные endpoints и production deployment относятся к следующим этапам и пока не заявляются как готовые. FCM не имитируется без реальных Firebase-настроек.

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

PostgreSQL integration tests используют Zonky с настоящими PostgreSQL 18.7 binaries. SMTP test использует настоящий Mailpit на `127.0.0.1:1025/8025`. Эти проверки не заменяются mocks и не пропускаются незаметно.

При запущенном dev-окружении:

- `bash scripts/smoke-test.sh` проверяет admin invite → register → Mailpit verify → login → search → recovery → reset → login.
- `bash scripts/provision-device-account.sh` создаёт отдельный тестовый аккаунт для свободного device ID.
- `bash scripts/e2ee-two-clients.sh` собирает и устанавливает два изолированных application ID на одном телефоне, проверяет PQXDH сообщения в обе стороны, offline-доставку, safety number, ciphertext в PostgreSQL, отсутствие plaintext и крэшей в logcat. Аккаунты должны соответствовать device ID установок; для другой dev-базы задайте `A_EMAIL`, `A_USER`, `B_EMAIL`, `B_USER`, `E2EE_TEST_PASSWORD`.
- `bash scripts/group-three-clients.sh` проверяет три изолированных клиента: приглашения с pairwise E2EE, отсутствие прежней истории у нового участника, PNG/MP4/WAV/TXT, group receipts, удаление участника и offline delivery. Сначала каждый клиент публикует свои ключи. `CLIENT_A_SUFFIX`, `CLIENT_B_SUFFIX`, `CLIENT_C_SUFFIX` задают application ID; `A_EMAIL`, `A_USER`, `B_EMAIL`, `B_USER`, `C_EMAIL`, `C_USER` — соответствующие тестовые аккаунты. Для новых installation ID нужны новые тестовые аккаунты или явно отозванные старые тестовые устройства: ограничение `DEVICE_LIMIT` не обходится.
- `bash scripts/sync-fallback-two-clients.sh` проверяет реальный WSS event, затем `am kill` процесса получателя, доставку через `JobScheduler`, восстановление зашифрованного хранилища и обобщённое системное уведомление. Сценарий использует локальные dev-аккаунты, заданные в начале скрипта, и проверяет отсутствие plaintext и крэшей в logcat.

Для ручного прогона реального account/draft сценария передайте `email`, `password` и `username` в `dev.securemessenger.ui.RealAccountFlowTest` через `am instrument`. Тесты `E2eeConversationTest` запускаются приёмочным скриптом, а не без параметров в общем `connectedDebugAndroidTest`.

## Production-границы

Текущая dev-конфигурация не является production deployment. Для него нужны выделенный Linux-хост, публичный CA certificate, реальный SMTP с TLS, отдельные production-секреты, резервные копии, мониторинг, нагрузочное тестирование и независимый security audit. `KTOR_HOST` по умолчанию безопасно ограничен `127.0.0.1`. Автоматические тесты снижают риск ошибок, но не доказывают отсутствие всех уязвимостей.
