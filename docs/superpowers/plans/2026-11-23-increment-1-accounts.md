# Increment 1 — Secure Accounts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Построить запускаемые Android-клиент и Ktor/PostgreSQL-сервер с invite-only регистрацией, настоящей email verification/recovery, сессиями и поиском username, без пользовательских plaintext-сообщений.

**Architecture:** Модуль `shared` содержит строгие DTO и валидацию. `server` реализует транзакционные JDBC-репозитории, Argon2id, одноразовые HMAC-коды, SMTP и Ktor API. `app` использует Compose/ViewModel/Room/SQLCipher и OkHttp; dev-подключение идёт через USB `adb reverse`, production cleartext запрещён.

**Tech Stack:** JDK 21 toolchain/JVM target 17, Gradle 9.4.1, AGP 9.2.1, Kotlin 2.4.20, Compose BOM 2026.11.01, Ktor 3.6.1, PostgreSQL, Flyway, HikariCP, Spring Security Crypto Argon2id, Jakarta Mail, Mailpit dev-only, Room 2.8.5, Hilt 2.60.1, KSP 2.3.14.

## Global Constraints

- Код и комментарии не раскрывают секреты; `local.properties`, `.local/`, `.runtime/`, keystore и `.env` игнорируются.
- Пароль: 12–1024 символа; username: 5–32 символа из `a-z`, `0-9`, `_`, начинается с буквы.
- Refresh/access tokens — случайные минимум 256 бит, на сервере только SHA-256 hash; короткие email codes — HMAC-SHA256 с серверным pepper.
- В production HTTP cleartext запрещён. Dev Mailpit не выдаётся за внешнюю доставку почты.
- Каждый behavior task начинается с failing test. Каждое завершённое приращение проходит build/test/adb/logcat gate.

---

### Task 1: Gradle/Android/JVM skeleton

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `shared/build.gradle.kts`, `server/build.gradle.kts`, `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`, `server/src/main/resources/application.conf`
- Create: `local.properties` (not committed), Gradle wrapper files

**Interfaces:** Gradle собирает `:shared`, `:server`, `:app`; Android applicationId `dev.private.messenger`, compileSdk 36.1, minSdk 26.

- [ ] Записать version catalog и module plugins с зафиксированными версиями.
- [ ] Создать пустой `SharedContractTest`, проверить его запуск через `:shared:test`.
- [ ] Скопировать/сгенерировать Gradle wrapper 9.4.1 и выполнить `./gradlew projects`.
- [ ] Commit: `build: initialize Android and server project`.

### Task 2: Shared validation and API contracts

**Files:**
- Create: `shared/src/commonMain/kotlin/dev/private/messenger/shared/Validation.kt`
- Create: `shared/src/commonMain/kotlin/dev/private/messenger/shared/Api.kt`
- Test: `shared/src/commonTest/kotlin/dev/private/messenger/shared/ValidationTest.kt`

**Interfaces:**
- `UsernameNormalizer.normalize(raw: String): String`
- `PasswordPolicy.validate(password: String): PasswordError?`
- DTO: `RegisterRequest`, `LoginRequest`, `TokenResponse`, `EmailCodeRequest`, `RecoveryRequest`, `PasswordResetRequest`, `UserResponse`.

- [ ] Написать failing tests для Unicode/confusable username, границ длины, паролей 11/12/1024/1025 символов и нормализации email whitespace.
- [ ] Запустить `:shared:test` и убедиться в FAIL.
- [ ] Реализовать allowlist username и length-only password policy без небезопасного substring/filter подхода.
- [ ] Повторить tests до PASS.
- [ ] Commit: `feat: add shared account contracts`.

### Task 3: Persistence, password hashing and token services

**Files:**
- Create: `server/src/main/resources/db/migration/V1__accounts.sql`
- Create: `server/src/main/kotlin/dev/private/messenger/server/config/ServerConfig.kt`
- Create: `server/src/main/kotlin/dev/private/messenger/server/security/PasswordHasher.kt`
- Create: `server/src/main/kotlin/dev/private/messenger/server/security/TokenService.kt`
- Create: `server/src/main/kotlin/dev/private/messenger/server/storage/Database.kt`
- Create: `server/src/main/kotlin/dev/private/messenger/server/storage/AuthRepository.kt`
- Test: `server/src/test/kotlin/dev/private/messenger/server/security/SecurityServicesTest.kt`
- Test: `server/src/integrationTest/kotlin/dev/private/messenger/server/storage/PostgresAuthRepositoryTest.kt`

**Interfaces:**
- `PasswordHasher.hash(password: String): String`, `verify(password, encoded): Boolean`.
- `TokenService.newToken(): GeneratedToken(raw, hash)`; `hashCode(code): String` использует HMAC pepper.
- `AuthRepository` выполняет account/invite/session/device/challenge операции в транзакциях.

- [ ] Написать failing tests: wrong password rejected; Argon2id hash не содержит password; token hashes одинаковы для одинакового token; code HMAC меняется при смене pepper; случайные tokens не повторяются.
- [ ] Запустить tests, увидеть FAIL, реализовать минимальные services.
- [ ] Написать PostgreSQL integration tests на одноразовый invite, unique username/email, single active device, refresh rotation и истечение challenge.
- [ ] Запустить PostgreSQL tests до FAIL, затем migration + JDBC repository до PASS.
- [ ] Commit: `feat: add secure account persistence`.

### Task 4: SMTP and account workflows

**Files:**
- Create: `server/src/main/kotlin/dev/private/messenger/server/mail/MailSender.kt`
- Create: `server/src/main/kotlin/dev/private/messenger/server/mail/SmtpMailSender.kt`
- Create: `server/src/main/kotlin/dev/private/messenger/server/auth/AuthService.kt`
- Test: `server/src/test/kotlin/dev/private/messenger/server/auth/AuthServiceTest.kt`
- Test: `server/src/integrationTest/kotlin/dev/private/messenger/server/mail/MailpitSmtpTest.kt`

**Interfaces:**
- `MailSender.sendCode(to: String, purpose: MailPurpose, code: String)`.
- `AuthService.register`, `verifyEmail`, `login`, `refresh`, `recover`, `resetPassword`, `logout`.
- Все recovery requests возвращают одинаковый accepted result; verification/recovery codes одноразовые и ограничены пятью попытками.

- [ ] Написать failing service tests с recording `MailSender`: invite consumed once, unverified login denied, wrong code increments attempts, recovery не раскрывает unknown email, reset revokes sessions, refresh reuse revokes chain.
- [ ] Запустить tests до FAIL, реализовать workflows до PASS.
- [ ] Интеграционный test отправляет настоящее SMTP-сообщение в Mailpit и читает его через Mailpit API; PASS только при запущенном dev SMTP.
- [ ] Commit: `feat: implement email account workflows`.

### Task 5: Ktor HTTPS API and username search

**Files:**
- Create: `server/src/main/kotlin/dev/private/messenger/server/Application.kt`
- Create: `server/src/main/kotlin/dev/private/messenger/server/api/AuthRoutes.kt`
- Create: `server/src/main/kotlin/dev/private/messenger/server/api/UserRoutes.kt`
- Create: `server/src/main/kotlin/dev/private/messenger/server/api/AdminRoutes.kt`
- Test: `server/src/test/kotlin/dev/private/messenger/server/api/ApiTest.kt`

**Interfaces:** `/v1/admin/invites`, `/v1/auth/register`, `/verify-email`, `/resend-verification`, `/login`, `/refresh`, `/recovery`, `/reset`, `/logout`, `/v1/users/by-username/{username}`, `/v1/health`, authenticated `/v1/events` WebSocket.

- [ ] Написать failing `testApplication` tests на malformed JSON, validation, generic recovery, bearer auth и username search.
- [ ] Запустить до FAIL, подключить routes/serialization/authentication до PASS.
- [ ] Настроить TLS connectors через environment/HOCON; никаких secrets в resources.
- [ ] Commit: `feat: expose secure account API`.

### Task 6: Android secure data and network layer

**Files:**
- Create: `app/src/main/kotlin/dev/private/messenger/data/local/AppDatabase.kt`
- Create: `app/src/main/kotlin/dev/private/messenger/data/local/DraftStore.kt`
- Create: `app/src/main/kotlin/dev/private/messenger/data/local/KeyStoreManager.kt`
- Create: `app/src/main/kotlin/dev/private/messenger/data/local/SessionStore.kt`
- Create: `app/src/main/kotlin/dev/private/messenger/data/remote/MessengerApi.kt`
- Create: `app/src/main/kotlin/dev/private/messenger/data/AuthRepository.kt`
- Test: `app/src/test/kotlin/dev/private/messenger/data/AuthRepositoryTest.kt`
- Test: `app/src/androidTest/kotlin/dev/private/messenger/data/local/SecureStorageTest.kt`

**Interfaces:** `MessengerApi` выполняет typed requests; `AuthRepository` сохраняет rotating tokens и device ID; `DraftStore` сохраняет drafts по chat ID; Keystore AES/GCM wrapping защищает SQLCipher passphrase.

- [ ] Написать failing MockWebServer tests на login/refresh/recovery, serialization и отсутствие passwords/tokens в request logs.
- [ ] Запустить до FAIL, реализовать API/repository до PASS.
- [ ] Написать device tests на сохранение/восстановление draft, device ID, logout wipe и Keystore unwrap.
- [ ] Commit: `feat: add Android secure account data`.

### Task 7: Adaptive Compose UI and end-to-end flows

**Files:**
- Create: `app/src/main/kotlin/dev/private/messenger/MainActivity.kt`
- Create: `app/src/main/kotlin/dev/private/messenger/ui/AuthViewModel.kt`
- Create: `app/src/main/kotlin/dev/private/messenger/ui/AuthScreens.kt`
- Create: `app/src/main/kotlin/dev/private/messenger/ui/ChatsScreen.kt`
- Create: `app/src/main/kotlin/dev/private/messenger/ui/AdaptiveApp.kt`
- Create: `app/src/main/kotlin/dev/private/messenger/ui/FoldingState.kt`
- Test: `app/src/test/kotlin/dev/private/messenger/ui/AuthViewModelTest.kt`
- Test: `app/src/androidTest/kotlin/dev/private/messenger/ui/AdaptiveAppTest.kt`

**Interfaces:** Auth state machine показывает register/login/verify/recovery/reset; authenticated shell показывает chats и username search. UI state переживает rotation/posture, drafts переживают process recreation.

- [ ] Написать failing ViewModel tests для loading/success/error и generic recovery; Compose tests для compact/wide и horizontal hinge.
- [ ] Запустить до FAIL, реализовать минимальные screens/state/layout до PASS.
- [ ] Подключить реальный server через HTTPS/WSS; dev credentials только одноразовые тестовые.
- [ ] Commit: `feat: add adaptive account UI`.

### Task 8: Acceptance and delivery

**Files:**
- Create: `scripts/dev-up.ps1`, `scripts/dev-up.sh`, `scripts/generate-dev-tls.sh`
- Create: `docker-compose.yml` для PostgreSQL и Mailpit
- Create: `README.md` с точными dev/production границами и командами

- [ ] Поднять PostgreSQL/Mailpit и server; проверить `/v1/health` через TLS.
- [ ] Создать invite через admin API, пройти register → Mailpit code → verify → login → username search → recovery → reset → login с новым паролем через реальные вызовы.
- [ ] Выполнить `./gradlew clean assembleDebug :shared:test :server:test :app:testDebugUnitTest`.
- [ ] Выполнить integration tests и Android instrumented tests при доступных Docker/device.
- [ ] Выполнить `adb install -r app/build/outputs/apk/debug/app-debug.apk`, запустить app и проверить logcat на `FATAL EXCEPTION`/AndroidRuntime.
- [ ] Проверить `git status`, удалить только созданные проверками ненужные artifacts и сделать final increment commit.

Expected acceptance: build и unit tests PASS, реальный SMTP/PostgreSQL flow PASS, APK запускается без крэша. Если device или Docker недоступны, соответствующий gate явно остаётся невыполненным; заглушка не считается приёмкой.
