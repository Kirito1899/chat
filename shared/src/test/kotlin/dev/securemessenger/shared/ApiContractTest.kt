package dev.securemessenger.shared

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiContractTest {
    @Test
    fun `login request round trips without losing device id`() {
        val request = LoginRequest("user@example.com", "correct-horse-password", "device-123")
        val decoded = Json.decodeFromString<LoginRequest>(Json.encodeToString(request))
        assertEquals(request, decoded)
    }
}
