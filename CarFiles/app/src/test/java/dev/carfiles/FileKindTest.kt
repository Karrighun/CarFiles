package dev.carfiles
import org.junit.Assert.assertEquals
import org.junit.Test
class FileKindTest {
    @Test fun genericProviderMimeFallsBackToExtension() {
        assertEquals("audio", FileKind.classify("application/octet-stream", "TRACK.FLAC"))
        assertEquals("video", FileKind.classify(null, "movie.MKV"))
        assertEquals("other", FileKind.classify(null, "archive.zip"))
    }
    @Test fun specificMimeTakesPriority() {
        assertEquals("image", FileKind.classify("image/png", "cover.mp3"))
    }
}
