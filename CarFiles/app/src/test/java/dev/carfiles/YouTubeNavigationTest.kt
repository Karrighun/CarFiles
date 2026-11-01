package dev.carfiles
import org.junit.Assert.*
import org.junit.Test
class YouTubeNavigationTest {
    @Test fun allowsOnlyHttpsYouTubeHosts() {
        assertTrue(YouTubeNavigation.isAllowed("https", "m.youtube.com"))
        assertTrue(YouTubeNavigation.isAllowed("https", "youtu.be"))
        assertFalse(YouTubeNavigation.isAllowed("http", "m.youtube.com"))
        assertFalse(YouTubeNavigation.isAllowed("https", "youtube.com.evil.example"))
        assertFalse(YouTubeNavigation.isAllowed("javascript", null))
    }
}
