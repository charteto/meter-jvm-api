package com.charteto;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.NamingConvention;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChartetoNamingConventionTest {

    private final ChartetoNamingConvention naming =
            new ChartetoNamingConvention(NamingConvention.dot);

    @Test
    public void replacesSlashesWithUnderscore() {
        String result = naming.name("http/server/requests", Meter.Type.COUNTER, null);
        assertFalse(result.contains("/"));
        assertTrue(result.contains("_"));
    }

    @Test
    public void removesNonAsciiCharacters() {
        String result = naming.name("métric名", Meter.Type.GAUGE, null);
        assertEquals("mtric", result);
        assertTrue(result.chars().allMatch(c -> c < 128));
    }

    @Test
    public void replacesNonAlphanumericCharacters() {
        String result = naming.name("metric{name}", Meter.Type.GAUGE, null);
        assertEquals("metric_name_", result);
    }

    @Test
    public void prefixesNameIfItDoesNotStartWithLetter() {
        String result = naming.name("1metric", Meter.Type.GAUGE, null);
        assertTrue(result.startsWith("m."));
    }

    @Test
    public void escapesJsonCharacters() {
        String result = naming.name("metric\"name", Meter.Type.GAUGE, null);
        assertFalse(result.contains("\""));
        assertTrue(result.contains("\\\"") || !result.contains("\""));
    }

    @Test
    public void truncatesToMaxLength() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longName.append("a");
        }

        String result = naming.name(longName.toString(), Meter.Type.GAUGE, null);
        assertTrue(result.length() <= 200);
    }

    @Test
    public void removesTrailingDotsAfterTruncation() {
        String result = naming.name("metric....", Meter.Type.GAUGE, null);
        assertFalse(result.endsWith("."));
    }

    @Test
    public void tagKeyIsEscapedForJson() {
        String result = naming.tagKey("key\"name");
        assertEquals("key\\\"name", result);
    }

    @Test
    public void tagKeyStartingWithDigitIsPrefixed() {
        String result = naming.tagKey("1tag");
        assertTrue(result.startsWith("m."));
    }

    @Test
    public void tagKeyStartingWithLetterIsNotPrefixed() {
        String result = naming.tagKey("tag");
        assertFalse(result.startsWith("m."));
    }

    @Test
    public void tagValueIsEscapedForJson() {
        String result = naming.tagValue("value\"with\"quotes");
        assertEquals("value\\\"with\\\"quotes", result);
    }

    @Test
    public void tagValueMayStartWithDigit() {
        String result = naming.tagValue("123value");
        assertEquals("123value", result);
    }
}
