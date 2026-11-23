package dev.securemessenger.server.api

import dev.securemessenger.server.ApplicationDependencies
import dev.securemessenger.shared.ErrorResponse
import dev.securemessenger.shared.InviteResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.security.MessageDigest

fun Route.adminRoutes(dependencies: ApplicationDependencies) {
    post("/v1/admin/invites") {
        val supplied = call.request.headers["X-Admin-Token"] ?: ""
        val matches = MessageDigest.isEqual(
            supplied.toByteArray(Charsets.UTF_8),
            dependencies.adminToken.toByteArray(Charsets.UTF_8),
        )
        if (!matches) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("INVALID_CREDENTIALS"))
            return@post
        }
        call.respond(HttpStatusCode.Created, InviteResponse(dependencies.authService.createInvite()))
    }
}
