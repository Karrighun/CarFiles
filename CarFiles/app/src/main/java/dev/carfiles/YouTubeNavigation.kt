package dev.carfiles

object YouTubeNavigation {
    fun isAllowed(scheme: String?, host: String?): Boolean = scheme == "https" && host?.lowercase() in
        setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")
}
