# Increment 3 — Pairwise groups and encrypted media

## Цель

Личные и групповые чаты с E2EE-текстом, файлами, фото, видео, стикерами и голосовыми. Сервер хранит только ciphertext, публичные prekeys и необходимые маршрутизационные метаданные. Название группы, имя/MIME файла и файловый ключ находятся только внутри E2EE payload.

## Приёмка 2026-11-25

Ветка `increment-3-groups-media`.

Подтверждено:
- Полный host gate завершился `BUILD SUCCESSFUL`: shared/server/app unit, PostgreSQL integration, lint, `assembleDebug` и `assembleDebugAndroidTest`.
- На POCO X5 Pro `OK (25 tests)`: SQLCipher, native PQXDH, миграция v2→v4, adaptive UI, Media3 и реальная запись `MediaRecorder`.
- `group-three-clients.sh` полностью прошёл на трёх изолированных application ID: создание группы, сообщения A↔B, добавление C без прежней истории, PNG/MP4/WAV/TXT, group read receipts, удаление C, запрет новых сообщений/отправки после удаления и offline delivery.
- Для всех медиа SHA-256 расшифрованных байтов совпал с исходником. MP4/WAV дополнительно достигли `STATE_READY` в Media3. Тестовые fixtures копируются через Base64 с проверкой SHA-256, поскольку потоковый ADB stdin повреждает бинарники.
- PostgreSQL и media storage не содержат искомый plaintext; logcat не содержит plaintext и крэшей.
- Перед добавлением участник должен опубликовать ключи хотя бы одним входом. Иначе сервер атомарно отклоняет операцию с `RECIPIENT_NOT_READY`, а Android показывает понятную ошибку без создания недоступной группы.
- Найден и исправлен реальный дефект: быстрое открытие уже синхронизированной группы не вызывало read. Теперь `selectGroup` сразу вызывает `markGroupRead`, receipts сериализованы через `syncMutex`; JVM-тест подтверждает один receipt при двух одновременных вызовах.
- Для instrumentation Hilt EntryPoint находится только в debug source set. UI-проверки ждут реальные данные Room и прокручивают LazyColumn, а не предполагают, что элемент уже находится в viewport.

Дополнительно:
- `e2ee-two-clients.sh` повторно прошёл: PQXDH в обе стороны, safety number, offline delivery, ciphertext в PostgreSQL, отсутствие plaintext и крэшей в logcat.
- Приращение 3 принято по заданным критериям. Следующий шаг — обычный (не WIP) commit/push.

Окружение:
- POCO X5 Pro подключён по ADB; default и три suffix APK с test APK установлены. Flip 4 физически не подключён; Flex Mode проверен instrumentation-тестом.
- Dev server: `bash scripts/dev-up.sh`. Групповой сценарий: `bash scripts/group-three-clients.sh` с соответствующими тестовыми аккаунтами и suffix application ID.
- PostgreSQL/Mailpit/CA/secrets/media fixtures остаются только в игнорируемом `.local` и не коммитятся. Старые устройства и серверные данные не удалялись.

## Протокол групп

- До 50 активных участников, один активный device на аккаунт. Создатель — owner; только owner добавляет/удаляет участников.
- `groups` и `group_members` хранят ID/участников/роли, но не название. Название передаётся как `GROUP_INFO` через pairwise Signal-сессии.
- Каждое групповое сообщение имеет общий `groupMessageId` и отдельный envelope UUID/ciphertext для каждого активного recipient device. Сервер проверяет членство sender/recipient и idempotency.
- Delivery использует recipient-scoped ack существующей очереди. Read receipts — отдельные E2EE payloads отправителю исходного сообщения.
- Удаление участника запрещает новые fanout-записи и удаляет его недоставленные group envelopes. Уже сохранённая локальная история не стирается. Новый участник получает только новый `GROUP_INFO`, но не прежнюю историю.
- Неизвестные/удалённые recipients не превращаются в бесконечный retry: клиент обновляет membership и помечает их как skipped.

## Формат медиа

- Каждый файл шифруется новым случайным 256-bit AES-GCM ключом. Ключ существует только в E2EE payload.
- Plaintext разбивается на 64-KiB chunks. Заголовок `SMF1` содержит размер исходного файла, размер/число chunks и случайный 96-bit base nonce.
- Каждый chunk использует отдельный nonce (base nonce + index) и AAD из заголовка/index. Подмена, truncation и reordering отклоняются. SHA-256 всего ciphertext дополнительно сверяется при скачивании.
- Сервер видит ciphertext size/hash и TTL, но не ключ, имя, MIME или plaintext. Превью и transcoding выполняются только на клиенте.
- Upload создаётся перед сообщением. Download доступен owner и устройствам, привязанным к отправленному media ID. Новые групповые сообщения добавляют recipient device к media ACL.
- Upload chunks до 1 MiB принимаются строго последовательно и идемпотентно. Resume начинается с подтверждённого серверного offset. Максимальный размер — 512 MiB, TTL — 7 дней; owner может удалить объект раньше.
- Ciphertext может кэшироваться в app-private no-backup storage. Plaintext не пишется в постоянное хранилище; playback использует streaming decryption, экспорт выполняется только явным действием через SAF.

## Основные проверки

1. PostgreSQL: membership authorization, atomic/idempotent fanout, removal purge, media ACL, sequential/resumable upload, TTL/delete.
2. JVM/Android: pairwise group round trip, per-recipient ciphertext, receipts isolation, media random access, tamper/truncation/reordering rejection, resume offset.
3. UI: группы/участники, выбор файла/фото, видео и голосовые, upload/download progress, адаптивность без потери состояния.
4. На POCO три изолированных application ID: owner + два участника; групповые тексты/медиа в обе стороны, offline delivery, group receipts, удаление второго участника и блокировка новых сообщений.
5. PostgreSQL и файловое хранилище не содержат plaintext/ключей; свежий logcat не содержит секретов и крэшей.

## Gate

`assembleDebug`, unit/integration tests, lint, `adb install`, реальные клиент-серверные сценарии и проверка logcat. Затем commit/push. UI/UX polish выполняется после приращения 4; финальный security gate и release — после polish.
