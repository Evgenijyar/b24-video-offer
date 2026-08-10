package ru.abs7.videooffer.kontur;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KonturRecordingUrlParserTest {
    private final KonturRecordingUrlParser parser = new KonturRecordingUrlParser();

    @Test
    void extractsKey() {
        assertEquals("abc-123", parser.extractRecordingKey("https://rko7.ktalk.ru/recordings/abc-123"));
    }

    @Test
    void detectsKonturRecordingUrl() {
        assertTrue(parser.isKonturRecordingUrl("https://rko7.ktalk.ru/recordings/abc-123"));
        assertFalse(parser.isKonturRecordingUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertFalse(parser.isKonturRecordingUrl("https://ktalk.ru.example.org/recordings/abc-123"));
    }

    @Test
    void rejectsInvalid() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.extractRecordingKey("https://example.org/no-recording"));
    }
}
