package dev.securemessenger.server.api

import dev.securemessenger.server.SessionPrincipal
import dev.securemessenger.server.e2ee.E2eeRepository
import dev.securemessenger.server.e2ee.EventsHub
import dev.securemessenger.shared.DeliveredMessagesResponse
import dev.securemessenger.shared.DeliveryResponse
import dev.securemessenger.shared.DeviceKeysUpload
import dev.securemessenger.shared.ErrorResponse
import dev.securemessenger.shared.MessageListResponse
import dev.securemessenger.shared.SendMessageRequest
import dev.securemessenger.shared.UsernameNormalizer
import dev.securemessenger.shared.ValidationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.Json
import java.util.UUID

fun Route.e2eeRoutes(repository: E2eeRepository, events: EventsHub) {
    put("/v1/devices/keys") {
        val principal = call.principal<SessionPrincipal>()!!
        repository.storeDeviceKeys(
            principal.session.accountId,
            principal.session.deviceId,
            call.receiveLimited<DeviceKeysUpload>(Json, 512 * 1024),
        )
        call.respond(HttpStatusCode.NoContent)
    }
    get("/v1/prekey-bundles/by-username/{username}") {
        val username = UsernameNormalizer.normalize(call.parameters["username"] ?: "")
        val bundle = repository.fetchBundleByUsername(username)
        if (bundle == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("USER_NOT_FOUND"))
        else call.respond(bundle)
    }
    post("/v1/messages") {
        val principal = call.principal<SessionPrincipal>()!!
        val request = call.receiveLimited<SendMessageRequest>(Json, 64 * 1024)
        repository.enqueue(principal.session.accountId, principal.session.deviceId, request)
        events.notify(UUID.fromString(request.recipientDeviceId), "SYNC")
        call.respond(HttpStatusCode.Accepted, DeliveryResponse(request.messageId, false))
    }
    get("/v1/messages") {
        val principal = call.principal<SessionPrincipal>()!!
        call.respond(MessageListResponse(repository.fetchUndelivered(principal.session.deviceId)))
    }
    get("/v1/messages/delivered") {
        val principal = call.principal<SessionPrincipal>()!!
        call.respond(DeliveredMessagesResponse(repository.deliveredForSender(principal.session.deviceId)))
    }
    post("/v1/messages/{id}/delivered") {
        val principal = call.principal<SessionPrincipal>()!!
        val messageId = parseUuid(call.parameters["id"])
        repository.markDelivered(messageId, principal.session.deviceId)
        repository.deliveredSenderDevice(messageId, principal.session.deviceId)?.let {
            events.notify(it, "DELIVERED")
        }
        call.respond(DeliveryResponse(messageId.toString(), true))
    }
}

private fun parseUuid(raw: String?): UUID =
    raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ValidationException("INVALID_UUID")
