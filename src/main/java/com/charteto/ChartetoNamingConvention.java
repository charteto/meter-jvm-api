package com.charteto;

import io.micrometer.common.lang.Nullable;
import io.micrometer.common.util.StringUtils;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.NamingConvention;
import io.micrometer.core.instrument.util.StringEscapeUtils;

public class ChartetoNamingConvention implements NamingConvention {

    private static final int MAX_NAME_LENGTH = 200;

    private final NamingConvention delegate;

    public ChartetoNamingConvention() {
        this(NamingConvention.dot);
    }

    public ChartetoNamingConvention(NamingConvention delegate) {
        this.delegate = delegate;
    }

    /**
     * Charteto's publish API will automatically strip Unicode without replacement. It will
     * also replace all non-alphanumeric characters with '_'.
     */
    @Override
    public String name(String name, Meter.Type type, @Nullable String baseUnit) {
        // forward slashes, even URL encoded, blow up the POST metadata API
        String sanitized = StringEscapeUtils.escapeJson(delegate.name(name, type, baseUnit).replace('/', '_'));

        // Metrics that don't start with a letter get dropped on the floor by the Datadog
        // publish API,
        // so we will prepend them with 'm.'.
        if (!Character.isLetter(sanitized.charAt(0))) {
            sanitized = "m." + sanitized;
        }
        return StringUtils.truncate(sanitized, MAX_NAME_LENGTH).replaceAll("\\.+$", "");
    }

    /**
     * Some set of non-alphanumeric characters will be replaced with '_', but not all
     * (e.g. '/' is OK, but '{' is replaced). Tag keys that begin with a number show up as
     * an empty string, so we prepend them with 'm.'.
     */
    @Override
    public String tagKey(String key) {
        String sanitized = StringEscapeUtils.escapeJson(delegate.tagKey(key));
        if (Character.isDigit(sanitized.charAt(0))) {
            sanitized = "m." + sanitized;
        }
        return sanitized;
    }

    /**
     * Some set of non-alphanumeric characters will be replaced by Datadog automatically
     * with '_', but not all (e.g. '/' is OK, but '{' is replaced). It is permissible for
     * a tag value to begin with a digit.
     */
    @Override
    public String tagValue(String value) {
        return StringEscapeUtils.escapeJson(delegate.tagValue(value));
    }
}
