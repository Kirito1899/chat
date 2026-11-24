# Increment 3 — Pairwise groups and encrypted media

## Цель

Личные и групповые чаты с E2EE-текстом, файлами, фото, видео, стикерами и голосовыми. Сервер хранит только ciphertext, публичные prekeys и необходимые маршрутизационные метаданные. Название группы, имя/MIME файла и файловый ключ находятся только внутри E2EE payload.

## Checkpoint 2026-11-25 — WIP перед выключением машины

Приращение 3 НЕ завершено и не принято. Ветка `increment-3-groups-media`.

Подтверждено:
- Полный host gate ранее завершался `BUILD SUCCESSFUL`: shared/server/app unit, PostgreSQL integration, lint и `assembleDebug`.
- `MessageRepositoryTest` после отдельного UUID для read receipts зелёный.
- На POCO `OK (24 tests)`: SQLCipher, native PQXDH, миграция v2→v4, adaptive UI, ExoPlayer и реальная запись `MediaRecorder`.
- Личные E2EE-чаты по-прежнему проходят двухклиентский сценарий без крэшей и plaintext в logcat.
- A создаёт группу и отправляет сообщение B; зашифрованное название и групповая доставка работают.
- Третий APK и test APK разрешены MIUI и успешно установлены.

Текущая блокирующая ошибка:
- При повторном полном сценарии B остаётся `Reply group ... PENDING`; скрипт останавливается на `send` после 30 секунд.
- Сервер продолжает отвечать `409 Conflict: POST /v1/groups/{id}/messages` при повторных попытках.
- Первая причина была исправлена: read receipt теперь получает собственный UUID в `markGroupRead`, а исходный message ID остаётся только внутри E2EE payload. Регрессионный тест это подтверждает.
- Повторный `409` остаётся. Вероятная область следующего анализа — старые outbox-записи B, созданные до исправления, и сравнение существующих строк `group_messages`/`message_queue`. Это гипотеза, не подтверждённая причина.
- Следующий шаг: безопасно сравнить `groupMessageId`, sender device, envelope UUID и ciphertext hash конфликтующей записи; не логировать plaintext/ключи. Обработать `MESSAGE_CONFLICT` так, чтобы одна повреждённая outbox-запись не блокировала остальные. Не очищать БД и не удалять данные без разрешения пользователя.

Оставшаяся приёмка:
- Групповые delivery/read receipts, PNG/MP4/WAV/TXT на трёх клиентах, удаление C, отсутствие новых сообщений после удаления и offline delivery.
- Финальный host gate и on-device gate после исправления, затем README и обычный (не WIP) commit/push.

Окружение для продолжения:
- Запуск: `bash scripts/dev-up.sh`, затем `bash scripts/group-three-clients.sh`.
- PostgreSQL/Mailpit/CA/secrets/media fixtures остаются только в игнорируемом `.local` и не коммитятся.
- Тестовые media: `.local/test-image.png`, `.local/test-video.mp4`, `.local/test-voice.wav`, `.local/test-file.txt` и файлы в `/sdcard/Android/data/dev.securemessenger.test/files` на POCO.

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
