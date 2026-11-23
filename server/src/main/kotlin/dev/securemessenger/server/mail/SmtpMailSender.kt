package dev.securemessenger.server.mail

import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage

data class SmtpConfig(
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val from: String,
    val startTls: Boolean,
    val ssl: Boolean,
) {
    init {
        require(port in 1..65535)
        require(username == null || password != null)
        require(!(ssl && startTls))
        if (host !in setOf("localhost", "127.0.0.1", "::1")) {
            require(startTls || ssl) { "Remote SMTP requires TLS" }
        }
    }
}

class SmtpMailSender(private val config: SmtpConfig) : MailSender {
    override fun sendCode(to: String, purpose: MailPurpose, code: String) {
        val properties = System.getProperties().clone() as java.util.Properties
        properties["mail.smtp.host"] = config.host
        properties["mail.smtp.port"] = config.port.toString()
        properties["mail.smtp.auth"] = (config.username != null).toString()
        properties["mail.smtp.starttls.enable"] = config.startTls.toString()
        properties["mail.smtp.starttls.required"] = config.startTls.toString()
        properties["mail.smtp.ssl.enable"] = config.ssl.toString()
        properties["mail.smtp.ssl.checkserveridentity"] = "true"
        properties["mail.smtp.connectiontimeout"] = "10000"
        properties["mail.smtp.timeout"] = "10000"
        properties["mail.smtp.writetimeout"] = "10000"

        val session = Session.getInstance(
            properties,
            config.username?.let { username ->
                object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication =
                        PasswordAuthentication(username, config.password)
                }
            },
        )
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(config.from))
            setRecipient(Message.RecipientType.TO, InternetAddress(to))
            setSubject(
                when (purpose) {
                    MailPurpose.EMAIL_VERIFICATION -> "Подтверждение аккаунта"
                    MailPurpose.PASSWORD_RECOVERY -> "Восстановление доступа"
                },
                "UTF-8",
            )
            setText(
                "Ваш одноразовый код: $code\n\nКод действует 10 минут. Никому его не передавайте.",
                "UTF-8",
            )
        }
        jakarta.mail.Transport.send(message)
    }
}
