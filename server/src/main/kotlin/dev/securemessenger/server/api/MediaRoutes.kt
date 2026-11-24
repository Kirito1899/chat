package dev.securemessenger.server.api

import dev.securemessenger.server.SessionPrincipal
import dev.securemessenger.server.media.MediaRepository
import dev.securemessenger.shared.MEDIA_UPLOAD_CHUNK_SIZE
import dev.securemessenger.shared.MediaUploadRequest
import dev.securemessenger.shared.ValidationException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json

fun Route.mediaRoutes(media: MediaRepository) {
    post("/v1/media/uploads") {
        val principal = call.principal<SessionPrincipal>()!!
        val request = call.receiveLimited<MediaUploadRequest>(Json)
        call.respond(
            HttpStatusCode.Created,
            media.create(principal.session.accountId, principal.session.deviceId, request),
        )
    }
    get("/v1/media/{id}/status") {
        val principal = call.principal<SessionPrincipal>()!!
        call.respond(media.status(principal.session.accountId, principal.session.deviceId, parseUuid(call.parameters["id"])))
    }
    put("/v1/media/{id}/chunks/{offset}") {
        val principal = call.principal<SessionPrincipal>()!!
        val offset = call.parameters["offset"]?.toLongOrNull() ?: throw ValidationException("INVALID_MEDIA_OFFSET")
        val channel = call.receiveChannel()
        val bytes = channel.readRemaining(MEDIA_UPLOAD_CHUNK_SIZE + 1L).readByteArray()
        if (bytes.size > MEDIA_UPLOAD_CHUNK_SIZE) {
            channel.cancel(CancellationException("Payload too large"))
            throw PayloadTooLargeException()
        }
        call.respond(media.upload(principal.session.accountId, principal.session.deviceId, parseUuid(call.parameters["id"]), offset, bytes))
    }
    get("/v1/media/{id}/content") {
        val principal = call.principal<SessionPrincipal>()!!
        val accountId = principal.session.accountId
        val deviceId = principal.session.deviceId
        val mediaId = parseUuid(call.parameters["id"])
        val status = media.status(accountId, deviceId, mediaId)
        val range = call.request.headers[HttpHeaders.Range]?.let(::parseRange)
        val start = range?.first ?: 0L
        val lastAllowed = minOf(start + MEDIA_UPLOAD_CHUNK_SIZE - 1L, status.expectedBytes - 1)
        val end = minOf(range?.second ?: lastAllowed, lastAllowed)
        if (start < 0 || start >= status.expectedBytes || end < start) {
            call.response.header(HttpHeaders.ContentRange, "bytes */${status.expectedBytes}")
            call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
            return@get
        }
        val chunk = media.read(accountId, deviceId, mediaId, start, (end - start + 1).toInt())
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/${status.expectedBytes}")
        call.respondBytes(
            chunk.bytes,
            ContentType.Application.OctetStream,
            if (start == 0L && end == status.expectedBytes - 1) HttpStatusCode.OK else HttpStatusCode.PartialContent,
        )
    }
    delete("/v1/media/{id}") {
        val principal = call.principal<SessionPrincipal>()!!
        media.delete(principal.session.accountId, principal.session.deviceId, parseUuid(call.parameters["id"]))
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun parseRange(value: String): Pair<Long, Long?> {
    val match = Regex("bytes=(\\d+)-(\\d*)").matchEntire(value) ?: throw ValidationException("INVALID_MEDIA_RANGE")
    val start = match.groupValues[1].toLongOrNull() ?: throw ValidationException("INVALID_MEDIA_RANGE")
    val end = match.groupValues[2].toLongOrNull()
    return start to end
}
