package dev.carfiles

object FileKind {
    fun classify(mime: String?, name: String): String {
        val type = mime.orEmpty().lowercase()
        if (type.startsWith("audio/")) return "audio"
        if (type.startsWith("video/")) return "video"
        if (type.startsWith("image/")) return "image"
        return when (name.substringAfterLast('.', "").lowercase()) {
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus" -> "audio"
            "mp4", "mkv", "webm", "mov", "avi" -> "video"
            "jpg", "jpeg", "png", "webp", "gif" -> "image"
            else -> "other"
        }
    }
}
