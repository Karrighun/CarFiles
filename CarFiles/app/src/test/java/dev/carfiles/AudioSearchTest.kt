package dev.carfiles

import org.junit.Assert.*
import org.junit.Test

class AudioSearchTest {
    @Test fun matchesAllWordsIgnoringCaseAndOrder() {
        assertTrue(AudioSearch.matches("Исполнитель - Моя Песня.FLAC", "  ПЕСНЯ   исполнитель "))
        assertFalse(AudioSearch.matches("Другая песня.mp3", "песня исполнитель"))
    }
    @Test fun emptyQuerySupportsPlayAnything() {
        assertTrue(AudioSearch.matches("track.mp3", "   "))
    }
}
