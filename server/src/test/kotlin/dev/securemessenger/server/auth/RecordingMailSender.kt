package dev.securemessenger.server.auth

import dev.securemessenger.server.mail.MailPurpose
import dev.securemessenger.server.mail.MailSender

class RecordingMailSender : MailSender {
    data class SentMail(val to: String, val purpose: MailPurpose, val code: String)

    val messages = mutableListOf<SentMail>()

    override fun sendCode(to: String, purpose: MailPurpose, code: String) {
        messages += SentMail(to, purpose, code)
    }
}
