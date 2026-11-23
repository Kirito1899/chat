package dev.securemessenger.server.mail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertTrue

class MailpitSmtpTest {
    private val http = HttpClient.newHttpClient()

    @Test
    fun `smtp message is delivered to real mailpit mailbox`() {
        val recipient = "smtp-test@example.com"
        val code = "12345678"
        request("DELETE", "http://127.0.0.1:8025/api/v1/messages")

        SmtpMailSender(
            SmtpConfig(
                host = "127.0.0.1",
                port = 1025,
                username = null,
                password = null,
                from = "messenger@localhost",
                startTls = false,
                ssl = false,
            ),
        ).sendCode(recipient, MailPurpose.EMAIL_VERIFICATION, code)

        val messages = request("GET", "http://127.0.0.1:8025/api/v1/messages")
        assertTrue(recipient in messages)
        val id = Json.parseToJsonElement(messages).jsonObject["messages"]!!.jsonArray.single().jsonObject["ID"]!!.jsonPrimitive.content
        val message = request("GET", "http://127.0.0.1:8025/api/v1/message/$id")
        val text = Json.parseToJsonElement(message).jsonObject["Text"]!!.jsonPrimitive.content
        assertTrue(code in text)
    }

    private fun request(method: String, url: String): String {
        val request = HttpRequest.newBuilder(URI(url))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "Mailpit returned ${response.statusCode()}" }
        return response.body()
    }
}
