package com.charteto;

import java.time.Duration;

public class ChartetoProperties {
    /**
     * Whether to enable publishing to Charteto.
     */
    private boolean enabled = true;

    /**
     * The API key header for Charteto.
     */
    private String apiKey;
    /**
     * The step size (i.e., frequency) for publishing metrics to Charteto.
     */
    private Duration step = Duration.ofSeconds(10L);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Duration getStep() {
        return step;
    }

    public void setStep(Duration step) {
        this.step = step;
    }
}
