package dev.securemessenger.server.media

class MediaNotFoundException : RuntimeException("Media not found")
class MediaAccessException : RuntimeException("Media access denied")
class MediaNotReadyException : RuntimeException("Media upload is incomplete")
class MediaConflictException(message: String) : RuntimeException(message)
