package com.charteto;

import io.micrometer.core.instrument.step.StepRegistryConfig;

public interface ChartetoRegistryConfig extends StepRegistryConfig {

    // Default configuration
    ChartetoRegistryConfig DEFAULT = k -> null;

    /**
     * Prefix for configuration properties (e.g., management.metrics.export.charteto.uri)
     */
    //
    @Override
    default String prefix() {
        return "charteto";
    }

    // --- Custom properties ---
    default String uri() {
        return get(prefix() + ".uri");
    }

    default String apiKeyHeader() {
        return get(prefix() + ".apiKey");
    }
}
