package com.charteto;

import io.micrometer.core.instrument.config.MeterRegistryConfigValidator;
import io.micrometer.core.instrument.config.validate.PropertyValidator;
import io.micrometer.core.instrument.config.validate.Validated;
import io.micrometer.core.instrument.push.PushRegistryConfig;
import org.jspecify.annotations.Nullable;

import java.time.Duration;

public interface ChartetoConfig extends PushRegistryConfig {

    // Default configuration
    ChartetoConfig DEFAULT = k -> null;

    /**
     * Prefix for configuration properties (e.g., management.metrics.export.charteto.uri)
     */
    default String prefix() {
        return "charteto";
    }

    default String apiKey() {
        return PropertyValidator.getString(this, "apiKey").required().get();
    }

    default String uri() {
        return PropertyValidator.getUrlString(this, "uri").orElse("https://api.charteto.com");
    }

    default Duration step() {
        return PropertyValidator.getDuration(this, "step").orElse(Duration.ofSeconds(10L));
    }

    default @Nullable String hostTag() {
        return PropertyValidator.getString(this, "hostTag").orElse("instance");
    }

    default Validated<?> validate() {
        return MeterRegistryConfigValidator.checkAll(this, (c) -> PushRegistryConfig.validate(c), MeterRegistryConfigValidator.checkRequired("apiKey", ChartetoConfig::apiKey), MeterRegistryConfigValidator.checkRequired("uri", ChartetoConfig::uri));
    }


}
