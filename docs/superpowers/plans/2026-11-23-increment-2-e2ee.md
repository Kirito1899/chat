# Increment 2 — E2EE Direct Messages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Два Android-аккаунта обмениваются личными сообщениями через PQXDH/Double Ratchet, при этом PostgreSQL, серверные логи и push содержат только ciphertext и минимальную маршрутизацию.

**Architecture:** Официальный libsignal v0.86.5 публикуется в локальный Maven из исходников при недоступности Signal Maven. Android реализует `SignalProtocolStore` поверх SQLCipher; сервер хранит immutable device identity, подписанные/одноразовые prekeys и недоставленные ciphertext envelopes. Приложение использует HTTPS sync и authenticated WSS wakeups, а read receipts передаются внутри E2EE payload.

**Tech Stack:** libsignal 0.86.5/Rust 1.98.1, Kotlin, Ktor/PostgreSQL, Compose, Room/SQLCipher, OkHttp HTTPS/WSS, kotlinx.serialization.

## Checkpoint перед перерывом

Текущая ветка: `increment-2-e2ee`. Приращение 2 НЕ завершено; критерии приёмки ещё не выполнены.

Выполнено:
- Wire DTO `shared/E2eeApi.kt` и shared tests зелёные.
- PostgreSQL migration V2, immutable device identity, atomic EC/PQ prekey consumption, last-resort PQ, idempotent ciphertext queue и delivery ack.
- E2EE routes, authenticated WSS wakeups и EventsHub.
- Real PostgreSQL E2EE tests и полный server unit/integration suite были зелёными после routes.
- Пользователь явно разрешил официальный libsignal 0.86.5 из Maven Central вместо 0.108.0. Версия 0.86.5 успешно разрешается Gradle.
- Android WIP: Room/SQLCipher migration 2, PersistentSignalStore, PreKeyFactory, E2eeManager, MessageRepository/outbox, E2EE methods в MessengerApi/AuthRepository и DI.
- PersistentSignalStore основной код компилировался; nullable-ошибка только в instrumented test исправлена, но повторный on-device запуск ещё не выполнен.

Текущая незавершённая точка:
- `AdaptiveApp.kt` частично обновлён: вызовы `MessageHistory(messages)` и `Composer(..., onSend)` уже изменены, но тела composables всё ещё имеют старые сигнатуры. Нужно заменить секции MessageHistory/Composer, добавить список MessageEntity и активную отправку.
- После этого обязательно запустить `:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`.
- Safety numbers/QR, blocking key-change UI, два реальных клиента, offline/MITM tests и полная приёмка ещё не реализованы.
- README/LICENSE и финальная документация приращения 2 не обновлены.

Окружение:
- Signal Maven и Windows source build 0.108.0 недоступны/неуспешны; WSL зависает. Не возвращаться к source build без новой Linux-среды.
- Dev PostgreSQL data фактически оказалась в `devtools/.local/postgres-data` из-за workingDir Gradle; при продолжении задать DevPostgres абсолютный root path и обновить dev-up.
- Локальные Rust/NDK/GCC и исходники находятся только в игнорируемом `.local`.
- Приращение 1 полностью проверено и отправлено в origin. Приращение 2 пока не запускать на устройстве и не считать рабочим.

## Global Constraints

- Приватные identity/prekey/session keys никогда не отправляются на сервер и не пишутся в logcat.
- Локальный Signal store и plaintext сообщений находятся только в SQLCipher.
- Первый контакт — TOFU; любая смена существующего identity отклоняется libsignal и блокирует отправку до явного пользовательского решения.
- Один active device на аккаунт; адрес протокола включает account ID, device UUID и numeric protocol device ID 1.
- Сервер не знает plaintext, сообщение имеет случайный UUID, TTL и idempotency.
- Каждый behavior task начинается с failing test. Production-замена libsignal или самодельный Double Ratchet запрещены.

---

### Task 1: Reproducible official libsignal dependency

**Files:**
- Create: `scripts/build-libsignal.sh`
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`
- Create: `LICENSE` (AGPLv3) and dependency notices.

- [ ] Установить локальные Rust/NDK и выполнить официальный `publishToMavenLocal` для `:client` и `:android` версии 0.86.5.
- [ ] Проверить отсутствие `.so` в git, добавить mavenLocal перед недоступным Signal Maven.
- [ ] Написать instrumentation smoke test: `IdentityKeyPair.generate()` и PQXDH encrypt/decrypt между двумя `InMemorySignalProtocolStore`.
- [ ] Запустить test на arm64 POCO, убедиться в PASS и commit `build: integrate official libsignal`.

### Task 2: Shared E2EE wire contracts

**Files:**
- Create: `shared/src/main/kotlin/dev/securemessenger/shared/E2eeApi.kt`
- Test: `shared/src/test/kotlin/dev/securemessenger/shared/E2eeApiTest.kt`

**Interfaces:**
- `DeviceKeysUpload(registrationId, identityKey, signedPreKey, oneTimePreKeys, kyberOneTimePreKeys, lastResortKyberPreKey)`.
- `PreKeyBundleResponse(accountId, deviceId, registrationId, protocolDeviceId=1, identityKey, signedPreKey, optionalPreKey, kyberPreKey)`.
- `OutgoingMessage(messageId, recipientDeviceId, ciphertextType, ciphertext)`; `IncomingMessage` дополнительно содержит sender account/device IDs.
- Все ключи/ciphertext кодируются Base64; размеры и число prekeys валидируются.

- [ ] Failing tests на unknown JSON fields, Base64, 100/101 one-time keys, отсутствие EC one-time key и круговой round trip.
- [ ] Реализовать DTO/validation до PASS и commit `feat: add E2EE wire contracts`.

### Task 3: PostgreSQL key directory and ciphertext queue

**Files:**
- Create: `server/src/main/resources/db/migration/V2__e2ee.sql`
- Create: `server/src/main/kotlin/dev/securemessenger/server/e2ee/E2eeRepository.kt`
- Create: `server/src/main/kotlin/dev/securemessenger/server/e2ee/JdbcE2eeRepository.kt`
- Create: `server/src/main/kotlin/dev/securemessenger/server/e2ee/E2eeService.kt`
- Test: `server/src/integrationTest/kotlin/dev/securemessenger/server/e2ee/PostgresE2eeRepositoryTest.kt`

**Interfaces:** immutable identity upload, atomic EC/PQ prekey consumption, last-resort PQ fallback, idempotent enqueue, recipient fetch, delivery ack and TTL deletion.

- [ ] Failing real PostgreSQL tests: identity cannot be overwritten; parallel bundle fetch never returns same one-time key; no EC key returns null EC but still consumes PQ once; duplicate message ID does not duplicate queue; foreign recipient is forbidden.
- [ ] Реализовать migration/JDBC transactions до PASS.
- [ ] Добавить unit tests на размеры, TTL, recipient authorization и commit `feat: add server E2EE key directory`.

### Task 4: E2EE API and realtime wakeups

**Files:**
- Create: `server/src/main/kotlin/dev/securemessenger/server/api/E2eeRoutes.kt`
- Create: `server/src/main/kotlin/dev/securemessenger/server/e2ee/EventsHub.kt`
- Modify: `server/src/main/kotlin/dev/securemessenger/server/Application.kt`
- Test: `server/src/test/kotlin/dev/securemessenger/server/api/E2eeApiTest.kt`

**Interfaces:** `PUT /v1/devices/keys`, `GET /v1/prekey-bundles/by-username/{username}`, `POST /v1/messages`, `GET /v1/messages`, `POST /v1/messages/{id}/delivered`; authenticated `/v1/events` sends only `SYNC`/`DELIVERED` without payload.

- [ ] Failing API tests на unauthenticated access, malformed bundle, atomic queue, foreign delivery ack и generic wakeup.
- [ ] Реализовать routes/hub до PASS и commit `feat: expose E2EE messaging API`.

### Task 5: Persistent Android SignalProtocolStore

**Files:**
- Modify: `app/src/main/kotlin/dev/securemessenger/data/local/AppDatabase.kt` (version 2 + migration)
- Create: `app/src/main/kotlin/dev/securemessenger/data/e2ee/PersistentSignalStore.kt`
- Create: `app/src/main/kotlin/dev/securemessenger/data/e2ee/SignalAddressCodec.kt`
- Test: `app/src/androidTest/kotlin/dev/securemessenger/data/e2ee/PersistentSignalStoreTest.kt`

**Interfaces:** synchronous libsignal store backed by SQLCipher; separate records for local identity, trusted remote identities, sessions, EC/signed/PQ prekeys, used PQ base keys and future sender keys.

- [ ] Failing on-device tests: identity/prekeys/session survive database close/reopen; wrong identity is untrusted; used PQ base key replay throws; session state does not change without `storeSession`.
- [ ] Реализовать store/migration до PASS.
- [ ] Проверить plaintext отсутствует в raw DB и commit `feat: persist Signal protocol state securely`.

### Task 6: Device key upload and direct-message encryption

**Files:**
- Create: `app/src/main/kotlin/dev/securemessenger/data/e2ee/E2eeManager.kt`
- Create: `app/src/main/kotlin/dev/securemessenger/data/e2ee/PreKeyFactory.kt`
- Create: `app/src/main/kotlin/dev/securemessenger/data/e2ee/ChatPayload.kt`
- Create: `app/src/main/kotlin/dev/securemessenger/data/MessageRepository.kt`
- Modify: Android API/client and local database DAOs.

**Interfaces:** `initializeDevice()`, `sendText(remoteAccountId, remoteDeviceId, body)`, `sync()`, `markDelivered()` and encrypted read receipts.

- [ ] Failing two-store tests: initial PREKEY message decrypts; subsequent WHISPER round trips; out-of-order works; duplicate decrypt rejected; tampering rejected; identity replacement blocks send.
- [ ] Failing repository tests: no plaintext request body, idempotency, ack after durable local save and no ack after decrypt failure.
- [ ] Реализовать до PASS и commit `feat: send and receive E2EE direct messages`.

### Task 7: Compose chat, statuses and safety numbers

**Files:**
- Modify: `ChatsViewModel.kt`, `AdaptiveApp.kt`
- Create: `ui/SafetyNumberScreen.kt`, `data/e2ee/SafetyNumberService.kt`
- Test: Compose and unit tests for pending/sent/delivered/read, blocked key change and draft preservation.

- [ ] Включить composer только после device key initialization; отправка хранится сначала локально.
- [ ] Показывать сообщения и pending/sent/delivered/read без plaintext в push.
- [ ] Safety number создаётся `NumericFingerprintGenerator(5200)` с version 2; QR содержит `ScannableFingerprint.serialized`.
- [ ] При `UntrustedIdentityException` показывать блокирующий экран; явное доверие требует повторной сверки fingerprint.
- [ ] Пройти UI tests и commit `feat: add E2EE chat UX and safety numbers`.

### Task 8: End-to-end acceptance and delivery

**Files:**
- Create: `scripts/e2ee-smoke-test.sh` and instrumented two-client test support.

- [ ] Два реальных клиента (POCO + emulator/Flip) обмениваются сообщениями в обе стороны.
- [ ] Остановить клиент получателя, отправить, перезапустить и подтвердить одну доставку без дубликатов.
- [ ] Проверить PostgreSQL ciphertext, отсутствие plaintext в logcat и обнаружение malicious key-directory substitution.
- [ ] Выполнить `assembleDebug`, все unit/integration/instrumented tests, lint и cold-start/logcat gate.
- [ ] Обновить README, проверить git status, commit и push `increment-2-e2ee`.
