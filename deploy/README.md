# Linux deployment для закрытого круга

Расчётная конфигурация: один небольшой always-on Linux-хост, Ktor + PostgreSQL на том же хосте, HTTPS напрямую в Ktor на `8443`, systemd sandbox. Для хоста с 1 ГБ RAM JVM ограничена `-Xmx256m`, а одновременные Argon2-вычисления — двумя. Публичный SSH/PostgreSQL port forwarding не требуется. Откройте только нужные порты приложения и HTTP-01 challenge (80/tcp для первоначального certbot).

## Требования на хосте

- systemd, JDK 21+, PostgreSQL и `psql`, certbot, GnuPG, OpenSSL, tar.
- Домен, который указывает на публичный IP хоста.
- SMTP с TLS для verification/recovery. Mailpit используется только в dev и не подходит для внешней доставки.
- Root для установки, TLS и резервных копий. Сам Ktor работает от непривилегированного пользователя `messenger`.

## Установка

На машине с исходниками:

```bash
bash scripts/package-server.sh
```

Пакет `build/secure-messenger-server.tar.gz` содержит `bin/`, `lib/` и `deploy/`, но не содержит production secrets. Передайте его на хост через SSH и распакуйте в `/opt/secure-messenger`:

```bash
tar -xzf /tmp/secure-messenger-server.tar.gz -C /opt
```

На хосте создайте `/etc/secure-messenger/smtp.env` в редакторе с `PUBLIC_DOMAIN`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_FROM`, а при необходимости `SMTP_USERNAME` и `SMTP_PASSWORD`. Не используйте значения `CHANGE_ME` и не отправляйте файл в Git.

`/opt/secure-messenger/deploy/create-env.sh` создаст `server.env` с новыми случайными `DATABASE_PASSWORD`, `POSTGRES_PASSWORD`, `ADMIN_TOKEN`, `TOKEN_PEPPER_BASE64` и паролем TLS. Секреты генерируются на хосте и не выводятся в терминал. Парсер конфигурации не выполняет содержимое файла как shell-код. Существующий `server.env` не перезаписывается без явного `REPLACE_PRODUCTION_CONFIG=true`.

Файл должен быть доступен root и группе `messenger`, но не остальным пользователям:

```bash
chown root:messenger /etc/secure-messenger/server.env
chmod 0640 /etc/secure-messenger/server.env
```

Для Let’s Encrypt задайте `LETSENCRYPT_EMAIL` и только после ознакомления с [Subscriber Agreement](https://letsencrypt.org/repository/) установите `LETSENCRYPT_AGREE_TOS=true`. HTTP-01 требует доступность хоста по домену на `80/tcp`.

```bash
/opt/secure-messenger/deploy/create-env.sh
/opt/secure-messenger/deploy/prepare-tls.sh
/opt/secure-messenger/deploy/install.sh
curl https://PUBLIC_DOMAIN:8443/v1/health
```

`prepare-tls.sh` создаёт PKCS#12 и deploy hook для продления. `install.sh` создаёт пользователя и БД, устанавливает systemd unit и запускает сервер. Логи:

```bash
journalctl -u secure-messenger -n 100 --no-pager
```

После проверки health соберите APK с публичным endpoint:

```bash
API_BASE_URL=https://PUBLIC_DOMAIN:8443/ bash scripts/build-release.sh
```

При необходимости используйте второй реальный HTTPS endpoint через `API_FALLBACK_URL`, а не domain fronting.

## Проверка production

`/opt/secure-messenger/deploy/smoke-test.sh` создаёт случайный тестовый аккаунт, проверяет доставку настоящего письма по SMTP, читает код по IMAP, выполняет login и восстановление пароля. Коды и пароли не выводятся.

`bash scripts/production-release-e2ee.sh` собирает два release APK с публичным `API_BASE_URL`, устанавливает их на Android, проверяет UI входа, обмен E2EE-сообщениями, offline-доставку и 60-значный safety number. Первое сообщение получателя открывается через настоящее системное уведомление; проверяются отсутствие в нём текста/отправителя, preview и unread badge, а также сохранение непрочитанных после повторного force-stop. Скрипт использует `scripts/android-ui.py`, поэтому debug-компоненты не требуются. Каждый прогон получает новые изолированные application ID, чтобы не сталкиваться с `DEVICE_LIMIT` и не очищать данные приложения. Повторное использование installation ID с новым аккаунтом не поддерживается: сервер справедливо отклоняет его, пока прежнее устройство привязано к другому аккаунту. Повторные UI-fixtures создаются и блокируются административно только в БД `secure_messenger`, без обхода публичного rate limit или сброса лимитера. Публичная регистрация отдельно проверяется `smoke-test.sh`. По завершении тестовые аккаунты блокируются случайным паролем, а не остаются с тестовым доступом. Для Yandex используется plus-alias вашего почтового адреса; для другого провайдера подготовьте адреса в `create-test-account.sh` отдельно.

## Резервные копии

Установите отдельный секрет резервных копий в окружении и не сохраняйте его в репозиторий:

```bash
export BACKUP_PASSWORD='длинный-уникальный-секрет'
/opt/secure-messenger/deploy/backup.sh
```

Скрипт временно останавливает сервер, делает `pg_dump`, копирует медиа и конфигурацию, шифрует архив GPG (AES-256 с iterated S2K и integrity check), затем проверяет чтение архива и перезапускает сервер. Храните `.tar.gpg` и `BACKUP_PASSWORD` раздельно. Автоматического удаления старых копий нет: настройте retention после проверки восстановления.

Для реальной проверки восстановления передайте созданный архив в `verify-backup.sh` на Linux-хосте с тем же `BACKUP_PASSWORD`. Скрипт расшифровывает SQL во временный файл, создаёт отдельную временную БД, восстанавливает дамп через `psql`, сверяет число строк во всех public-таблицах с рабочей БД и удаляет только временную БД. Рабочая БД не перезаписывается и сервер не останавливается; сверка может сообщить об изменении данных, если во время проверки появились новые записи. При ошибке проверьте указанный временный database name перед ручной очисткой.

## Границы

Эти файлы не являются подтверждением установки на конкретном хосте. Перед production запуском проверьте DNS, firewall, JDK/PostgreSQL, TLS, реальную доставку SMTP, восстановление backup и реальные клиент-серверные вызовы из РФ. `KTOR_HOST=127.0.0.1` остаётся в dev; production example использует `0.0.0.0` только с публичным TLS.
