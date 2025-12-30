package com.charteto;

import io.micrometer.core.instrument.config.validate.Validated;
import io.micrometer.core.instrument.config.validate.ValidationException;
import org.junit.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChartetoConfigTest {

    private ChartetoConfig from(Map<String, String> props) {
        return key -> props.get(key);
    }

    @Test
    public void prefixIsCorrect() {
        ChartetoConfig config = ChartetoConfig.DEFAULT;
        assertEquals("charteto", config.prefix());
    }

    @Test
    public void defaultUriIsUsed() {
        Map<String, String> props = new HashMap<>();
        props.put("charteto.apiKey", "test-key");

        ChartetoConfig config = from(props);
        assertEquals("https://api.charteto.com", config.uri());
    }

    @Test
    public void defaultStepIs10Seconds() {
        Map<String, String> props = new HashMap<>();
        props.put("charteto.apiKey", "test-key");

        ChartetoConfig config = from(props);
        assertEquals(Duration.ofSeconds(10), config.step());
    }

    @Test
    public void defaultHostTagIsInstance() {
        Map<String, String> props = new HashMap<>();
        props.put("charteto.apiKey", "test-key");

        ChartetoConfig config = from(props);
        assertEquals("instance", config.hostTag());
    }

    @Test(expected = IllegalStateException.class)
    public void apiKeyIsRequired() {
        ChartetoConfig config = from(new HashMap<>());
        config.apiKey();
    }

    @Test
    public void customUriIsParsed() {
        Map<String, String> props = new HashMap<>();
        props.put("charteto.apiKey", "test-key");
        props.put("charteto.uri", "https://example.com");

        ChartetoConfig config = from(props);
        assertEquals("https://example.com", config.uri());
    }

    @Test
    public void customStepIsParsed() {
        Map<String, String> props = new HashMap<>();
        props.put("charteto.apiKey", "test-key");
        props.put("charteto.step", "30s");

        ChartetoConfig config = from(props);
        assertEquals(Duration.ofSeconds(30), config.step());
    }

    @Test
    public void validationSucceedsWhenRequiredFieldsPresent() {
        Map<String, String> props = new HashMap<>();
        props.put("charteto.apiKey", "test-key");
        props.put("charteto.uri", "https://example.com");

        ChartetoConfig config = from(props);
        Validated<?> validated = config.validate();

        assertTrue(validated.isValid());
    }

    @Test
    public void validationFailsWhenApiKeyMissing() {
        Map<String, String> props = new HashMap<>();
        props.put("charteto.uri", "https://example.com");

        ChartetoConfig config = from(props);
        Validated<?> validated = config.validate();

        assertFalse(validated.isValid());
    }

    @Test
    public void invalidStepThrowsException() {
        Map<String, String> props = new HashMap<>();
        props.put("charteto.apiKey", "test-key");
        props.put("charteto.step", "not-a-duration");

        ChartetoConfig config = from(props);

        try {
            config.step();
            fail("Expected IllegalArgumentException");
        } catch (ValidationException e) {
            System.out.println(e.getMessage());
            assertTrue(e.getMessage().contains("charteto.step"));
            assertTrue(e.getMessage().contains("valid duration value"));
        }
    }

    @Test
    public void invalidUriThrowsValidationExceptionWithMessage() {
        Map<String, String> props = new HashMap<>();
        props.put("charteto.apiKey", "test-key");
        props.put("charteto.uri", "not-a-url");

        ChartetoConfig config = from(props);

        try {
            config.uri();
            fail("Expected ValidationException");
        } catch (ValidationException e) {
            assertTrue(e.getMessage().contains("charteto.uri"));
            assertTrue(e.getMessage().contains("valid URL"));
        }
    }
}
