package dev.securemessenger.server.api

import dev.securemessenger.server.auth.AuthService
import dev.securemessenger.shared.EmailCodeRequest
import dev.securemessenger.shared.LoginRequest
import dev.securemessenger.shared.LogoutRequest
import dev.securemessenger.shared.PasswordResetRequest
import dev.securemessenger.shared.RecoveryRequest
import dev.securemessenger.shared.RefreshRequest
import dev.securemessenger.shared.RegisterRequest
import dev.securemessenger.shared.ResendVerificationRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json

fun Route.authRoutes(service: AuthService) {
    post("/v1/auth/register") {
        call.respond(HttpStatusCode.Accepted, service.register(call.receiveLimited<RegisterRequest>(Json)))
    }
    post("/v1/auth/verify-email") {
        service.verifyEmail(call.receiveLimited<EmailCodeRequest>(Json))
        call.respond(HttpStatusCode.NoContent)
    }
    post("/v1/auth/resend-verification") {
        service.resendVerification(call.receiveLimited<ResendVerificationRequest>(Json))
        call.respond(HttpStatusCode.Accepted)
    }
    post("/v1/auth/login") {
        call.respond(service.login(call.receiveLimited<LoginRequest>(Json)))
    }
    post("/v1/auth/refresh") {
        call.respond(service.refresh(call.receiveLimited<RefreshRequest>(Json)))
    }
    post("/v1/auth/recovery") {
        service.recover(call.receiveLimited<RecoveryRequest>(Json))
        call.respond(HttpStatusCode.Accepted)
    }
    post("/v1/auth/reset") {
        service.resetPassword(call.receiveLimited<PasswordResetRequest>(Json))
        call.respond(HttpStatusCode.NoContent)
    }
    post("/v1/auth/logout") {
        service.logout(call.receiveLimited<LogoutRequest>(Json))
        call.respond(HttpStatusCode.NoContent)
    }
}
