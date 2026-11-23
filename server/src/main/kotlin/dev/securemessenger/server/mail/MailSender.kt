package dev.securemessenger.server.mail

enum class MailPurpose {
    EMAIL_VERIFICATION,
    PASSWORD_RECOVERY,
}

interface MailSender {
    fun sendCode(to: String, purpose: MailPurpose, code: String)
}
