# Private Messenger

Закрытый Android-мессенджер. Текущий код реализует приращение 1: аккаунты по приглашению, email verification/recovery, сессии, username search, TLS и адаптивный UI. Отправка сообщений намеренно отключена до E2EE-приращения.

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

Скрипт создаёт локальные секреты в `.local/`, dev TLS CA/server certificate, запускает настоящие PostgreSQL 18.7 и Mailpit без Docker, настраивает `adb reverse tcp:8443 tcp:8443` и запускает Ktor на `https://127.0.0.1:8443`.

- Mailpit UI: `http://127.0.0.1:8025`.
- Dev CA публичен и копируется только в debug source set. Приватные ключи и `.local/` не коммитятся. `--ssl-no-revoke` в Windows curl нужен только потому, что у offline dev CA нет CRL; CA и hostname всё равно проверяются.
- Cleartext отключён и в debug, и в release.
- Mailpit принимает настоящий SMTP, но не доказывает внешнюю доставку. Production требует отдельный SMTP/API-аккаунт с TLS.

Для создания одноразового приглашения в другом Git Bash:

```bash
source .local/dev-env.sh
curl --ssl-no-revoke --cacert .local/tls/ca.crt \
  -H "X-Admin-Token: $ADMIN_TOKEN" \
  -X POST https://127.0.0.1:8443/v1/admin/invites
```

Возвращённый invite code вводится при регистрации. Verification/recovery code читается в Mailpit и вводится в приложении.

## Проверки

При запущенном dev-окружении `bash scripts/smoke-test.sh` выполняет реальные admin invite → register → Mailpit verify → login → search → recovery → reset → login. Для отдельного тестового аккаунта телефона без уже занятого device ID используйте `bash scripts/provision-device-account.sh`.

```bash
./gradlew :shared:test :server:test :app:testDebugUnitTest assembleDebug
./gradlew :server:integrationTest
./gradlew :app:connectedDebugAndroidTest
```

PostgreSQL integration test использует Zonky с настоящими PostgreSQL 18.7 binaries; SMTP test использует настоящий Mailpit на `127.0.0.1:1025/8025`. Они не включаются в обычный unit suite и не должны незаметно пропускаться.

## Production-границы

Текущая dev-конфигурация не является production deployment. Для него нужны выделенный Linux-хост, публичный CA certificate, реальный SMTP с TLS, отдельные production-секреты, резервные копии, rate limiting review и security audit. `KTOR_HOST` по умолчанию безопасно ограничен `127.0.0.1`.
