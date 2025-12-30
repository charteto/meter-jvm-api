package com.charteto;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Statistic;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.step.StepMeterRegistry;
import io.micrometer.core.instrument.util.MeterPartition;
import io.micrometer.core.instrument.util.NamedThreadFactory;
import io.micrometer.core.instrument.util.StringEscapeUtils;
import io.micrometer.core.ipc.http.HttpSender;
import io.micrometer.core.ipc.http.HttpUrlConnectionSender;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class ChartetoMeterRegistry extends StepMeterRegistry {

    private static final ThreadFactory DEFAULT_THREAD_FACTORY = new NamedThreadFactory("charteto-metrics-publisher");

    private final Logger logger;

    private final ChartetoConfig config;

    private final HttpSender httpClient;

    public ChartetoMeterRegistry(ChartetoConfig config, Clock clock) {
        this(config, clock, DEFAULT_THREAD_FACTORY, new HttpUrlConnectionSender(config.connectTimeout(), config.readTimeout()));
    }

    private ChartetoMeterRegistry(ChartetoConfig config, Clock clock, ThreadFactory threadFactory, HttpSender httpClient) {
        super(config, clock);
        this.logger = LoggerFactory.getLogger(ChartetoMeterRegistry.class);
        this.config().namingConvention(new ChartetoNamingConvention());
        this.config = config;
        this.httpClient = httpClient;
        this.start(threadFactory);
    }

    @Override
    public void start(ThreadFactory threadFactory) {
        if (this.config.enabled() && this.config.apiKey() == null) {
            this.logger.info("An api key must be configured in order for metric information to be sent to Charteto.");
        }
        super.start(threadFactory);
    }

    @Override
    protected void publish() {
        String chartetoEndpoint = this.config.uri() + "/api/v1/metrics";

        try {
            for (List<Meter> meters : MeterPartition.partition(this, this.config.batchSize())) {
                String body = meters.stream()
                        .flatMap(meter -> meter.match(this::writeMeter,
                                this::writeMeter,
                                this::writeTimer,
                                this::writeSummary,
                                this::writeMeter,
                                this::writeMeter,
                                this::writeMeter,
                                this::writeTimer,
                                this::writeMeter))
                        .collect(Collectors.joining(",", "{\"metrics\":[", "]}"));
                this.logger.trace("sending metrics batch to charteto:{}{}", System.lineSeparator(), body);
                this.httpClient.post(chartetoEndpoint).withJsonContent(body)
                        .withHeader("X-API-Key",this.config.apiKey())
                        .send().onSuccess((response) -> {
                    this.logger.debug("successfully sent {} metrics to charteto", meters.size());
                }).onError((response) -> {
                    this.logger.error("failed to send metrics to charteto: {}", response.body());
                });
            }
        } catch (Throwable ex) {
            this.logger.warn("failed to send metrics to charteto", ex);
        }
    }

    private Stream<String> writeTimer(FunctionTimer timer) {
        long wallTime = this.clock.wallTime();
        Meter.Id id = timer.getId();
        return Stream.of(this.writeMetric(id, "count", wallTime, timer.count(), Statistic.COUNT, "occurrence"),
                this.writeMetric(id, "avg", wallTime, timer.mean(this.getBaseTimeUnit()), Statistic.VALUE, null),
                this.writeMetric(id, "sum", wallTime, timer.totalTime(this.getBaseTimeUnit()), Statistic.TOTAL_TIME, null));
    }

    private Stream<String> writeTimer(Timer timer) {
        long wallTime = this.clock.wallTime();
        Stream.Builder<String> metrics = Stream.builder();
        Meter.Id id = timer.getId();
        metrics.add(this.writeMetric(id, "sum", wallTime, timer.totalTime(this.getBaseTimeUnit()), Statistic.TOTAL_TIME, null));
        metrics.add(this.writeMetric(id, "count", wallTime, (double) timer.count(), Statistic.COUNT, "occurrence"));
        metrics.add(this.writeMetric(id, "avg", wallTime, timer.mean(this.getBaseTimeUnit()), Statistic.VALUE, null));
        metrics.add(this.writeMetric(id, "max", wallTime, timer.max(this.getBaseTimeUnit()), Statistic.MAX, null));
        return metrics.build();
    }

    private Stream<String> writeSummary(DistributionSummary summary) {
        long wallTime = this.clock.wallTime();
        Stream.Builder<String> metrics = Stream.builder();
        Meter.Id id = summary.getId();
        metrics.add(this.writeMetric(id, "sum", wallTime, summary.totalAmount(), Statistic.TOTAL, null));
        metrics.add(this.writeMetric(id, "count", wallTime, (double) summary.count(), Statistic.COUNT, "occurrence"));
        metrics.add(this.writeMetric(id, "avg", wallTime, summary.mean(), Statistic.VALUE, null));
        metrics.add(this.writeMetric(id, "max", wallTime, summary.max(), Statistic.MAX, null));
        return metrics.build();
    }

    private Stream<String> writeMeter(Meter m) {
        long wallTime = this.clock.wallTime();
        return StreamSupport.stream(m.measure().spliterator(), false).map((ms) -> {
            Meter.Id id = m.getId().withTag(ms.getStatistic());
            return this.writeMetric(id, null, wallTime, ms.getValue(), ms.getStatistic(), null);
        });
    }

    String writeMetric(Meter.Id id, @Nullable String suffix, long wallTime, double value, Statistic statistic, @Nullable String overrideBaseUnit) {
        Meter.Id fullId = id;
        if (suffix != null) {
            fullId = this.idWithSuffix(id, suffix);
        }

        Iterable<Tag> tags = this.getConventionTags(fullId);
        String host = this.config.hostTag() == null ? "" : StreamSupport.stream(tags.spliterator(), false)
                .filter((t) -> this.config.hostTag().equals(t.getKey())).findAny().map((t) -> ",\"host\":\"" + StringEscapeUtils.escapeJson(t.getValue()) + "\"").orElse("");
        String type = ",\"type\":\"" + ChartetoMetricMetadata.sanitizeType(statistic) + "\"";
        String baseUnit = ChartetoMetricMetadata.sanitizeBaseUnit(id.getBaseUnit(), overrideBaseUnit);
        String unit = baseUnit != null ? ",\"unit\":\"" + baseUnit + "\"" : "";
        String tagsArray = tags.iterator().hasNext() ? StreamSupport.stream(tags.spliterator(), false)
                .map((t) -> "\"" + StringEscapeUtils.escapeJson(t.getKey()) + ":" + StringEscapeUtils.escapeJson(t.getValue()) + "\"").collect(Collectors.joining(",", ",\"tags\":[", "]")) : "";
        return "{\"name\":\"" + StringEscapeUtils.escapeJson(this.getConventionName(fullId)) + "\",\"points\":[[" + wallTime + ", " + value + "]]" + host + type + unit + tagsArray + "}";
    }

    protected final TimeUnit getBaseTimeUnit() {
        return TimeUnit.SECONDS;
    }

    private Meter.Id idWithSuffix(Meter.Id id, String suffix) {
        return id.withName(id.getName() + "." + suffix);
    }

    public static class Builder {
        private final ChartetoConfig config;
        private Clock clock;
        private ThreadFactory threadFactory;
        private HttpSender httpClient;

        Builder(ChartetoConfig config) {
            this.clock = Clock.SYSTEM;
            this.threadFactory = ChartetoMeterRegistry.DEFAULT_THREAD_FACTORY;
            this.config = config;
            this.httpClient = new HttpUrlConnectionSender(config.connectTimeout(), config.readTimeout());
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public Builder threadFactory(ThreadFactory threadFactory) {
            this.threadFactory = threadFactory;
            return this;
        }

        public Builder httpClient(HttpSender httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public ChartetoMeterRegistry build() {
            return new ChartetoMeterRegistry(this.config, this.clock, this.threadFactory, this.httpClient);
        }
    }
}
