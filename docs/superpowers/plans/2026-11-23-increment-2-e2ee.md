# Increment 2 — E2EE Direct Messages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Два Android-аккаунта обмениваются личными сообщениями через PQXDH/Double Ratchet, при этом PostgreSQL, серверные логи и push содержат только ciphertext и минимальную маршрутизацию.

**Architecture:** Официальный libsignal 0.86.5 загружается из Maven Central с явного разрешения пользователя. Android реализует `SignalProtocolStore` поверх SQLCipher; сервер хранит immutable device identity, подписанные/одноразовые prekeys и недоставленные ciphertext envelopes. Приложение использует HTTPS sync; сервер также предоставляет authenticated WSS wakeups. Read receipts передаются внутри E2EE payload.

**Tech Stack:** libsignal 0.86.5, Kotlin, Ktor/PostgreSQL, Compose, Room/SQLCipher, OkHttp HTTPS/WSS, kotlinx.serialization.

## Checkpoint после реализации

Текущая ветка: `increment-2-e2ee`. Реализация и приёмка завершены; финальные commit/push выполняются отдельно.

Проверено:
- Полный host-side gate `assembleDebug`, shared/server/app unit tests, PostgreSQL integration tests и lint завершается `BUILD SUCCESSFUL`.
- На POCO X5 Pro: `OK (21 tests)` для SQLCipher, native PQXDH, persistence и adaptive UI; реальный account/draft scenario — `OK (1 test)`.
- Два изолированных application ID на POCO обмениваются уникальными E2EE сообщениями в обе стороны, получатель получает offline-сообщение после `force-stop` и восстанавливает сессию без повторного login.
- PostgreSQL содержит ciphertext, а не plaintext; свежий logcat не содержит ни plaintext тестовых сообщений, ни крэшей приложения.
- Safety number и QR проверены в реальном UI. Identity replacement блокируется; принимается только просмотренный bundle с валидными подписями, identity/session/device binding сохраняются атомарно.
- Исправлены гонка повторного login/device UUID, формат serialized Kyber public key (1569 байт), гонка загрузки черновика и автопрокрутка LazyColumn.

Ограничения: два клиента запускаются на одном физическом POCO, а не на двух разных телефонах. Production SMTP/TLS/deployment, группы, медиа и push/fallback не входят в это приращение.

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

- [x] С разрешения пользователя использовать официальные `libsignal-client` и `libsignal-android` 0.86.5 из Maven Central вместо нерабочего Windows source build.
- [x] Проверить отсутствие `.so` и приватных ключей в git, зафиксировать единую версию dependency.
- [x] Написать instrumentation smoke test: `IdentityKeyPair.generate()` и PQXDH encrypt/decrypt между двумя `InMemorySignalProtocolStore`.
- [x] Запустить test на arm64 POCO, убедиться в PASS и commit `build: integrate official libsignal`.

### Task 2: Shared E2EE wire contracts

**Files:**
- Create: `shared/src/main/kotlin/dev/securemessenger/shared/E2eeApi.kt`
- Test: `shared/src/test/kotlin/dev/securemessenger/shared/E2eeApiTest.kt`

**Interfaces:**
- `DeviceKeysUpload(registrationId, identityKey, signedPreKey, oneTimePreKeys, kyberOneTimePreKeys, lastResortKyberPreKey)`.
- `PreKeyBundleResponse(accountId, deviceId, registrationId, protocolDeviceId=1, identityKey, signedPreKey, optionalPreKey, kyberPreKey)`.
- `OutgoingMessage(messageId, recipientDeviceId, ciphertextType, ciphertext)`; `IncomingMessage` дополнительно содержит sender account/device IDs.
- Все ключи/ciphertext кодируются Base64; размеры и число prekeys валидируются.

- [x] Failing tests на unknown JSON fields, Base64, 100/101 one-time keys, отсутствие EC one-time key и круговой round trip.
- [x] Реализовать DTO/validation до PASS и commit `feat: add E2EE wire contracts`.

### Task 3: PostgreSQL key directory and ciphertext queue

**Files:**
- Create: `server/src/main/resources/db/migration/V2__e2ee.sql`
- Create: `server/src/main/kotlin/dev/securemessenger/server/e2ee/E2eeRepository.kt`
- Create: `server/src/main/kotlin/dev/securemessenger/server/e2ee/JdbcE2eeRepository.kt`
- Create: `server/src/main/kotlin/dev/securemessenger/server/e2ee/E2eeService.kt`
- Test: `server/src/integrationTest/kotlin/dev/securemessenger/server/e2ee/PostgresE2eeRepositoryTest.kt`

**Interfaces:** immutable identity upload, atomic EC/PQ prekey consumption, last-resort PQ fallback, idempotent enqueue, recipient fetch, delivery ack and TTL deletion.

- [x] Failing real PostgreSQL tests: identity cannot be overwritten; parallel bundle fetch never returns same one-time key; no EC key returns null EC but still consumes PQ once; duplicate message ID does not duplicate queue; foreign recipient is forbidden.
- [x] Реализовать migration/JDBC transactions до PASS.
- [x] Добавить unit tests на размеры, TTL, recipient authorization и commit `feat: add server E2EE key directory`.

### Task 4: E2EE API and realtime wakeups

**Files:**
- Create: `server/src/main/kotlin/dev/securemessenger/server/api/E2eeRoutes.kt`
- Create: `server/src/main/kotlin/dev/securemessenger/server/e2ee/EventsHub.kt`
- Modify: `server/src/main/kotlin/dev/securemessenger/server/Application.kt`
- Test: `server/src/test/kotlin/dev/securemessenger/server/api/E2eeApiTest.kt`

**Interfaces:** `PUT /v1/devices/keys`, `GET /v1/prekey-bundles/by-username/{username}`, `POST /v1/messages`, `GET /v1/messages`, `POST /v1/messages/{id}/delivered`; authenticated `/v1/events` sends only `SYNC`/`DELIVERED` without payload.

- [x] Failing API tests на unauthenticated access, malformed bundle, atomic queue, foreign delivery ack и generic wakeup.
- [x] Реализовать routes/hub до PASS и commit `feat: expose E2EE messaging API`.

### Task 5: Persistent Android SignalProtocolStore

**Files:**
- Modify: `app/src/main/kotlin/dev/securemessenger/data/local/AppDatabase.kt` (version 2 + migration)
- Create: `app/src/main/kotlin/dev/securemessenger/data/e2ee/PersistentSignalStore.kt`
- Create: `app/src/main/kotlin/dev/securemessenger/data/e2ee/SignalAddressCodec.kt`
- Test: `app/src/androidTest/kotlin/dev/securemessenger/data/e2ee/PersistentSignalStoreTest.kt`

**Interfaces:** synchronous libsignal store backed by SQLCipher; separate records for local identity, trusted remote identities, sessions, EC/signed/PQ prekeys, used PQ base keys and future sender keys.

- [x] Failing on-device tests: identity/prekeys/session survive database close/reopen; wrong identity is untrusted; used PQ base key replay throws; session state does not change without `storeSession`.
- [x] Реализовать store/migration до PASS.
- [x] Проверить plaintext отсутствует в raw DB и commit `feat: persist Signal protocol state securely`.

### Task 6: Device key upload and direct-message encryption

**Files:**
- Create: `app/src/main/kotlin/dev/securemessenger/data/e2ee/E2eeManager.kt`
- Create: `app/src/main/kotlin/dev/securemessenger/data/e2ee/PreKeyFactory.kt`
- Create: `app/src/main/kotlin/dev/securemessenger/data/e2ee/ChatPayload.kt`
- Create: `app/src/main/kotlin/dev/securemessenger/data/MessageRepository.kt`
- Modify: Android API/client and local database DAOs.

**Interfaces:** `initializeDevice()`, `sendText(remoteAccountId, remoteDeviceId, body)`, `sync()`, `markDelivered()` and encrypted read receipts.

- [x] Failing two-store tests: initial PREKEY message decrypts; subsequent WHISPER round trips; out-of-order works; duplicate decrypt rejected; tampering rejected; identity replacement blocks send.
- [x] Failing repository tests: no plaintext request body, idempotency, ack after durable local save and no ack after decrypt failure.
- [x] Реализовать до PASS и commit `feat: send and receive E2EE direct messages`.

### Task 7: Compose chat, statuses and safety numbers

**Files:**
- Modify: `ChatsViewModel.kt`, `AdaptiveApp.kt`
- Create: `ui/SafetyNumberScreen.kt`, `data/e2ee/SafetyNumberService.kt`
- Test: Compose and unit tests for pending/sent/delivered/read, blocked key change and draft preservation.

- [x] Включить composer только после device key initialization; отправка хранится сначала локально.
- [x] Показывать сообщения и pending/sent/delivered/read без plaintext в push.
- [x] Safety number создаётся `NumericFingerprintGenerator(5200)` с version 2; QR содержит `ScannableFingerprint.serialized`.
- [x] При `UntrustedIdentityException` показывать блокирующий экран; явное доверие требует повторной сверки fingerprint.
- [x] Пройти UI tests и commit `feat: add E2EE chat UX and safety numbers`.

### Task 8: End-to-end acceptance and delivery

**Files:**
- Create: `scripts/e2ee-two-clients.sh` and instrumented two-client test support.

- [x] Два изолированных application ID на POCO обмениваются сообщениями в обе стороны.
- [x] Остановить клиент получателя, отправить, перезапустить и подтвердить одну доставку без дубликатов.
- [x] Проверить PostgreSQL ciphertext, отсутствие plaintext в logcat и обнаружение malicious key-directory substitution.
- [x] Выполнить `assembleDebug`, все unit/integration/instrumented tests, lint и cold-start/logcat gate.
- [x] Обновить README, проверить git status, commit и push `increment-2-e2ee`.
