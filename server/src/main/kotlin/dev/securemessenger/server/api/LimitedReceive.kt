package dev.securemessenger.server.api

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json

class PayloadTooLargeException : RuntimeException("Payload too large")

suspend inline fun <reified T> ApplicationCall.receiveLimited(json: Json): T {
    val channel = receiveChannel()
    val bytes = channel.readRemaining(MAX_JSON_BYTES + 1L).readByteArray()
    if (bytes.size > MAX_JSON_BYTES) {
        channel.cancel(CancellationException("Payload too large"))
        throw PayloadTooLargeException()
    }
    try {
        return json.decodeFromString<T>(bytes.decodeToString())
    } catch (error: Exception) {
        throw BadRequestException("Invalid JSON body", error)
    }
}

const val MAX_JSON_BYTES: Int = 32 * 1024
