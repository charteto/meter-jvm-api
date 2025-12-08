package com.charteto;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.step.StepMeterRegistry;
import io.micrometer.core.instrument.util.NamedThreadFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class ChartetoMeterRegistry extends StepMeterRegistry {

    private static final Logger logger = Logger.getLogger(ChartetoMeterRegistry.class.getName());

    private final ChartetoRegistryConfig config;
    private final ChartetoHttpSender sender;

    public ChartetoMeterRegistry(ChartetoRegistryConfig config, Clock clock) {
        super(config, clock);
        this.config = config;
        this.sender = new ChartetoHttpSender(config);
        // Start the publishing thread
        start(new NamedThreadFactory("charteto-metrics-publisher"));
    }

    @Override
    protected void publish() {
        // Gather all meters from the registry
        String jsonPayload = formatMetricsToJson(getMeters());
        // Send the payload using the sender
        try {
            sender.send(jsonPayload);
        } catch (Throwable e) {
            logger.log(Level.WARNING, "Failed to push metrics to custom endpoint", e);
        }
    }

    /**
     * Format metrics to JSON objects
     */
    private String formatMetricsToJson(List<Meter> meters) {
        List<Object> metricData = meters.stream()
                .flatMap(meter -> meter.match(
                        // Helper function to safely convert Iterable<Measurement> to Stream<Object>
                        (counter) -> measurementsToStream(counter, counter.measure()),
                        (gauge) -> measurementsToStream(gauge, gauge.measure()),
                        (timer) -> measurementsToStream(timer, timer.measure()),
                        (summary) -> measurementsToStream(summary, summary.measure()),
                        (longTaskTimer) -> measurementsToStream(longTaskTimer, longTaskTimer.measure()),
                        (timeGauge) -> measurementsToStream(timeGauge, timeGauge.measure()),
                        (functionCounter) -> measurementsToStream(functionCounter, functionCounter.measure()),
                        (functionTimer) -> measurementsToStream(functionTimer, functionTimer.measure()),
                        (other) -> measurementsToStream(other, other.measure())
                )).collect(Collectors.toList());

        try {
            return new ObjectMapper().writeValueAsString(metricData);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to push metrics to custom endpoint", e);
            return "[]";
        }
    }

    /**
     * Utility method to convert the Iterable<Measurement> to a Stream of metric objects.
     */
    private Stream<Object> measurementsToStream(Meter meter, Iterable<Measurement> measurements) {
        return StreamSupport.stream(measurements.spliterator(), false)
                .map(m -> createMetricObject(meter.getId(), m.getValue()));
    }

    /**
     * Simple map to represent the JSON object for one metric
     */
    private Object createMetricObject(Meter.Id id, double value) {
        return Map.of(
                "name", id.getName(),
                "tags", id.getTags().stream().collect(Collectors.toMap(Tag::getKey, Tag::getValue)),
                "value", value,
                "timestamp", System.currentTimeMillis()
        );
    }

    @Override
    protected final TimeUnit getBaseTimeUnit() {
        return TimeUnit.SECONDS;
    }
}
