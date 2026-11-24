package dev.securemessenger.server.api

import dev.securemessenger.server.SessionPrincipal
import dev.securemessenger.server.e2ee.E2eeRepository
import dev.securemessenger.server.e2ee.EventsHub
import dev.securemessenger.server.groups.GroupNotFoundException
import dev.securemessenger.server.groups.GroupRepository
import dev.securemessenger.shared.AddGroupMembersRequest
import dev.securemessenger.shared.CreateGroupRequest
import dev.securemessenger.shared.DeliveryResponse
import dev.securemessenger.shared.ErrorResponse
import dev.securemessenger.shared.GroupListResponse
import dev.securemessenger.shared.SendGroupMessageRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import java.util.UUID

fun Route.groupRoutes(groups: GroupRepository, messages: E2eeRepository, events: EventsHub) {
    post("/v1/groups") {
        val principal = call.principal<SessionPrincipal>()!!
        val request = call.receiveLimited<CreateGroupRequest>(Json)
        call.respond(HttpStatusCode.Created, groups.create(principal.session.accountId, request.memberUsernames))
    }
    get("/v1/groups") {
        val principal = call.principal<SessionPrincipal>()!!
        call.respond(GroupListResponse(groups.list(principal.session.accountId)))
    }
    get("/v1/groups/{id}") {
        val principal = call.principal<SessionPrincipal>()!!
        val group = groups.getForMember(principal.session.accountId, parseUuid(call.parameters["id"]))
            ?: throw GroupNotFoundException()
        call.respond(group)
    }
    post("/v1/groups/{id}/members") {
        val principal = call.principal<SessionPrincipal>()!!
        val request = call.receiveLimited<AddGroupMembersRequest>(Json)
        call.respond(groups.addMembers(principal.session.accountId, parseUuid(call.parameters["id"]), request.memberUsernames))
    }
    delete("/v1/groups/{id}/members/{accountId}") {
        val principal = call.principal<SessionPrincipal>()!!
        call.respond(
            groups.removeMember(
                principal.session.accountId,
                parseUuid(call.parameters["id"]),
                parseUuid(call.parameters["accountId"]),
            ),
        )
    }
    post("/v1/groups/{id}/messages") {
        val principal = call.principal<SessionPrincipal>()!!
        val request = call.receiveLimited<SendGroupMessageRequest>(Json, 64 * 1024)
        request.validate()
        messages.enqueueGroup(
            principal.session.accountId,
            principal.session.deviceId,
            parseUuid(call.parameters["id"]),
            UUID.fromString(request.groupMessageId),
            request.envelope,
        )
        events.notify(UUID.fromString(request.envelope.recipientDeviceId), "SYNC")
        call.respond(HttpStatusCode.Accepted, DeliveryResponse(request.envelope.messageId, false))
    }
}
