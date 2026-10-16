package com.iapetus.jarvis;
import org.junit.Test;
import static org.junit.Assert.*;

public class WakeWordsTest {
    @Test public void wakeRequiresCompletePhrase() {
        assertTrue(WakeWords.detected("Hey Jarvis"));
        assertTrue(WakeWords.detected("hey jarvis please look"));
        assertFalse(WakeWords.detected("jarvis"));
        assertFalse(WakeWords.detected("hey service"));
        assertFalse(WakeWords.detected("they jarvis"));
    }
}
