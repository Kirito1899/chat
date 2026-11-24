package dev.securemessenger.server

import dev.securemessenger.server.api.adminRoutes
import dev.securemessenger.server.api.authRoutes
import dev.securemessenger.server.api.installRateLimiting
import dev.securemessenger.server.api.PayloadTooLargeException
import dev.securemessenger.server.api.RateLimitException
import dev.securemessenger.server.api.userRoutes
import dev.securemessenger.server.api.e2eeRoutes
import dev.securemessenger.server.e2ee.EventsHub
import dev.securemessenger.server.e2ee.E2eeRepository
import dev.securemessenger.server.e2ee.JdbcE2eeRepository
import dev.securemessenger.server.e2ee.IdentityChangeException
import dev.securemessenger.server.e2ee.RecipientNotFoundException
import dev.securemessenger.server.e2ee.MessageAuthorizationException
import dev.securemessenger.server.e2ee.MessageConflictException
import dev.securemessenger.server.auth.AuthenticatedSession
import dev.securemessenger.server.auth.AuthService
import dev.securemessenger.server.config.ServerConfig
import dev.securemessenger.server.mail.SmtpMailSender
import dev.securemessenger.server.security.Argon2PasswordHasher
import dev.securemessenger.server.security.TokenService
import dev.securemessenger.server.storage.DatabaseFactory
import dev.securemessenger.server.storage.JdbcAuthRepository
import dev.securemessenger.shared.ErrorResponse
import dev.securemessenger.shared.HealthResponse
import dev.securemessenger.shared.ValidationException
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.Principal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.time.Clock

data class SessionPrincipal(val session: AuthenticatedSession) : Principal

data class ApplicationDependencies(
    val authService: AuthService,
    val adminToken: String,
    val e2eeRepository: E2eeRepository? = null,
    val eventsHub: EventsHub = EventsHub(),
)

fun Application.module() {
    val config = ServerConfig.fromEnvironment()
    val dataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.databaseUrl
            username = config.databaseUser
            password = config.databasePassword
            maximumPoolSize = 10
            minimumIdle = 1
            connectionTimeout = 10_000
        },
    )
    DatabaseFactory(dataSource).migrate()
    val repository = JdbcAuthRepository(dataSource)
    val authService = AuthService(
        repository = repository,
        passwordHasher = Argon2PasswordHasher(),
        tokens = TokenService(config.tokenPepper),
        mail = SmtpMailSender(config.smtp),
        clock = Clock.systemUTC(),
    )
    configureApp(ApplicationDependencies(authService, config.adminToken, JdbcE2eeRepository(dataSource)))
    monitor.subscribe(ApplicationStopping) { dataSource.close() }
}

fun Application.configureApp(dependencies: ApplicationDependencies) {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = false
                explicitNulls = false
                encodeDefaults = true
            },
        )
    }
    install(Authentication) {
        bearer("access") {
            realm = "secure-messenger"
            authenticate { credential ->
                dependencies.authService.authenticateAccess(credential.token)
                    ?.let { SessionPrincipal(it) }
            }
        }
    }
    install(WebSockets)
    installRateLimiting()
    install(CallLogging) {
        level = Level.INFO
    }
    install(StatusPages) {
        exception<ValidationException> { call, error ->
            call.application.environment.log.info("Request validation failed: {}", error.code)
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.code))
        }
        exception<dev.securemessenger.server.auth.AuthException> { call, error ->
            val status = when (error.code) {
                "INVALID_CREDENTIALS" -> HttpStatusCode.Unauthorized
                "EMAIL_NOT_VERIFIED" -> HttpStatusCode.Forbidden
                "ACCOUNT_EXISTS", "DEVICE_LIMIT" -> HttpStatusCode.Conflict
                else -> HttpStatusCode.BadRequest
            }
            call.respond(status, ErrorResponse(error.code))
        }
        exception<IdentityChangeException> { call, _ ->
            call.respond(HttpStatusCode.Conflict, ErrorResponse("IDENTITY_CHANGE_NOT_ALLOWED"))
        }
        exception<RecipientNotFoundException> { call, _ ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse("RECIPIENT_NOT_FOUND"))
        }
        exception<MessageAuthorizationException> { call, _ ->
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("MESSAGE_FORBIDDEN"))
        }
        exception<MessageConflictException> { call, _ ->
            call.respond(HttpStatusCode.Conflict, ErrorResponse("MESSAGE_CONFLICT"))
        }
        exception<RateLimitException> { call, _ ->
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("RATE_LIMITED"))
        }
        exception<PayloadTooLargeException> { call, _ ->
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("PAYLOAD_TOO_LARGE"))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_REQUEST"))
        }
        exception<Throwable> { call, error ->
            call.application.environment.log.error("Unhandled request failure", error)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR"))
        }
    }

    routing {
        get("/v1/health") {
            call.respond(HealthResponse("ok"))
        }
        adminRoutes(dependencies)
        authRoutes(dependencies.authService)
        authenticate("access") {
            userRoutes(dependencies.authService)
            dependencies.e2eeRepository?.let { e2eeRoutes(it, dependencies.eventsHub) }
            webSocket("/v1/events") {
                val principal = call.principal<SessionPrincipal>()!!
                dependencies.eventsHub.listen(principal.session.deviceId, this)
            }
        }
    }
}
