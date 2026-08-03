package ru.abs7.videooffer.kontur;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KonturRecordingUrlParserTest {
    private final KonturRecordingUrlParser parser=new KonturRecordingUrlParser();
    @Test void extractsKey(){assertEquals("abc-123",parser.extractRecordingKey("https://rko7.ktalk.ru/recordings/abc-123"));}
    @Test void rejectsInvalid(){assertThrows(IllegalArgumentException.class,()->parser.extractRecordingKey("https://example.org/no-recording"));}
}
