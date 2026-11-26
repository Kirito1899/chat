import email
import email.header
import email.policy
import email.utils
import imaplib
import os
import re
import sys
import time

host = os.environ.get("IMAP_HOST") or os.environ["SMTP_HOST"].replace("smtp.", "imap.", 1)
port = int(os.environ.get("IMAP_PORT", "993"))
expected_subject = os.environ["EXPECTED_SUBJECT"]
after = float(os.environ.get("AFTER_EPOCH", "0")) - 60
username = os.environ["SMTP_USERNAME"]
password = os.environ["SMTP_PASSWORD"]

for _ in range(12):
    mailbox = None
    try:
        mailbox = imaplib.IMAP4_SSL(host, port)
        mailbox.login(username, password)
        mailbox.select("INBOX", readonly=True)
        status, data = mailbox.search(None, "ALL")
        if status != "OK":
            raise RuntimeError("IMAP search failed")
        ids = data[0].split()
        for message_id in reversed(ids[-50:]):
            status, data = mailbox.fetch(message_id, "(BODY.PEEK[])")
            if status != "OK" or not data or not isinstance(data[0], tuple):
                continue
            message = email.message_from_bytes(data[0][1], policy=email.policy.default)
            subject = str(email.header.make_header(email.header.decode_header(message.get("Subject", ""))))
            if subject != expected_subject:
                continue
            if message.get("Date"):
                timestamp = email.utils.parsedate_to_datetime(message["Date"]).timestamp()
                if timestamp < after:
                    continue
            if message.is_multipart():
                parts = [part.get_payload(decode=True) or b"" for part in message.walk() if part.get_content_type() == "text/plain"]
                body = b"\n".join(parts).decode("utf-8", errors="replace")
            else:
                body = (message.get_payload(decode=True) or b"").decode("utf-8", errors="replace")
            match = re.search(r"Ваш одноразовый код:\s*(\d{8})", body)
            if match:
                print(match.group(1))
                mailbox.logout()
                sys.exit(0)
        mailbox.logout()
    except Exception:
        if mailbox is not None:
            try:
                mailbox.logout()
            except Exception:
                pass
    time.sleep(5)

print("No verification email received with the expected subject", file=sys.stderr)
sys.exit(1)
