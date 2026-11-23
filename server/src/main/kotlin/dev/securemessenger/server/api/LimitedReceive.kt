package dev.securemessenger.server.api

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json

class PayloadTooLargeException : RuntimeException("Payload too large")

suspend inline fun <reified T> ApplicationCall.receiveLimited(json: Json, maxBytes: Int = MAX_JSON_BYTES): T {
    val channel = receiveChannel()
    val bytes = channel.readRemaining(maxBytes + 1L).readByteArray()
    if (bytes.size > maxBytes) {
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
