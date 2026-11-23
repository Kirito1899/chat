package dev.securemessenger.server.api

import dev.securemessenger.server.auth.AuthService
import dev.securemessenger.shared.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.userRoutes(service: AuthService) {
    get("/v1/users/by-username/{username}") {
        val username = call.parameters["username"] ?: ""
        val user = service.findByUsername(username)
        if (user == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("USER_NOT_FOUND"))
        } else {
            call.respond(user)
        }
    }
}
