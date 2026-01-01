package com.charteto;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Statistic;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.cumulative.CumulativeCounter;
import io.micrometer.core.instrument.cumulative.CumulativeDistributionSummary;
import io.micrometer.core.instrument.cumulative.CumulativeFunctionCounter;
import io.micrometer.core.instrument.cumulative.CumulativeFunctionTimer;
import io.micrometer.core.instrument.cumulative.CumulativeTimer;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.HistogramGauges;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import io.micrometer.core.instrument.internal.DefaultGauge;
import io.micrometer.core.instrument.internal.DefaultLongTaskTimer;
import io.micrometer.core.instrument.internal.DefaultMeter;
import io.micrometer.core.instrument.push.PushMeterRegistry;
import io.micrometer.core.instrument.util.MeterPartition;
import io.micrometer.core.instrument.util.NamedThreadFactory;
import io.micrometer.core.instrument.util.StringEscapeUtils;
import io.micrometer.core.ipc.http.HttpSender;
import io.micrometer.core.ipc.http.HttpUrlConnectionSender;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class ChartetoMeterRegistry extends PushMeterRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ChartetoMeterRegistry.class);

    private static final ThreadFactory DEFAULT_THREAD_FACTORY =
            new NamedThreadFactory("charteto-metrics-publisher");

    private final ChartetoConfig config;
    private final HttpSender httpClient;

    public ChartetoMeterRegistry(ChartetoConfig config, Clock clock) {
        this(config, clock, DEFAULT_THREAD_FACTORY, new HttpUrlConnectionSender(config.connectTimeout(), config.readTimeout()));
    }

    private ChartetoMeterRegistry(ChartetoConfig config, Clock clock, ThreadFactory threadFactory, HttpSender httpClient) {
        super(config, clock);
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
        String endpoint = config.uri() + "/api/v1/metrics";

        try {
            for (List<Meter> batch : MeterPartition.partition(this, config.batchSize())) {
                String batchId = UUID.randomUUID().toString();

                String body = batch.stream()
                        .flatMap(this::writeMeter)
                        .collect(Collectors.joining(
                                ",",
                                "{\"batchId\":\"" + batchId + "\",\"metrics\":[",
                                "]}"
                        ));

                logger.trace("sending metrics batch to charteto:\n{}", body);

                httpClient.post(endpoint)
                        .withHeader("X-API-Key", config.apiKey())
                        .withJsonContent(body)
                        .send()
                        .onSuccess(r -> logger.debug("sent {} meters to charteto", batch.size()))
                        .onError(r -> logger.error("failed to send metrics: {}", r.body()));
            }
        } catch (Throwable ex) {
            logger.warn("failed to send metrics to charteto", ex);
        }
    }

    private Stream<String> writeMeter(Meter meter) {
        long wallTime = clock.wallTime(); // epoch millis

        return StreamSupport.stream(meter.measure().spliterator(), false)
                .map(ms -> {
                            Meter.Id idWithStat = meter.getId()
                                    .withTag(ms.getStatistic());
                            return writeMetric(idWithStat, wallTime, ms.getValue(), ms.getStatistic());
                        }
                );
    }

    private String writeMetric(Meter.Id id,
                               long wallTime,
                               double value,
                               Statistic statistic) {

        Iterable<Tag> tags = getConventionTags(id);
        String tagsJson = tags.iterator().hasNext()
                ? StreamSupport.stream(tags.spliterator(), false)
                .map(t ->
                        "\"" + StringEscapeUtils.escapeJson(t.getKey()) + "\":\"" +
                                StringEscapeUtils.escapeJson(t.getValue()) + "\""
                )
                .collect(Collectors.joining(",", ",\"tags\":{", "}"))
                : "";

        String baseUnit = ChartetoMetricMetadata.sanitizeBaseUnit(id.getBaseUnit(), null);
        String unit = baseUnit != null
                ? ",\"unit\":\"" + baseUnit + "\""
                : "";

        return "{"
                + "\"name\":\"" + StringEscapeUtils.escapeJson(this.getConventionName(id)) + "\""
                + ",\"type\":\"" + ChartetoMetricMetadata.sanitizeType(statistic) + "\""
                + ",\"points\":[[" + wallTime + "," + value + "]]"
                + unit
                + tagsJson
                + "}";
    }

    @Override
    protected DistributionSummary newDistributionSummary(Meter.Id id,
                                                         DistributionStatisticConfig distributionStatisticConfig, double scale) {
        DistributionStatisticConfig merged = distributionStatisticConfig
                .merge(DistributionStatisticConfig.builder().expiry(config.step()).build());

        DistributionSummary summary = new CumulativeDistributionSummary(id, clock, merged, scale, false);
        HistogramGauges.registerWithCommonFormat(summary, this);

        return summary;
    }

    @Override
    protected Meter newMeter(Meter.Id id, Meter.Type type, Iterable<Measurement> measurements) {
        return new DefaultMeter(id, type, measurements);
    }

    @Override
    protected Timer newTimer(Meter.Id id, DistributionStatisticConfig distributionStatisticConfig,
                             PauseDetector pauseDetector) {
        DistributionStatisticConfig merged = distributionStatisticConfig
                .merge(DistributionStatisticConfig.builder().expiry(config.step()).build());

        Timer timer = new CumulativeTimer(id, clock, merged, pauseDetector, getBaseTimeUnit(), false);
        HistogramGauges.registerWithCommonFormat(timer, this);

        return timer;
    }

    @Override
    protected <T> Gauge newGauge(Meter.Id id, @Nullable T obj, ToDoubleFunction<T> valueFunction) {
        return new DefaultGauge<>(id, obj, valueFunction);
    }

    @Override
    protected Counter newCounter(Meter.Id id) {
        return new CumulativeCounter(id);
    }

    @Override
    protected LongTaskTimer newLongTaskTimer(Meter.Id id, DistributionStatisticConfig distributionStatisticConfig) {
        DefaultLongTaskTimer ltt = new DefaultLongTaskTimer(id, clock, getBaseTimeUnit(), distributionStatisticConfig,
                false);
        HistogramGauges.registerWithCommonFormat(ltt, this);
        return ltt;
    }

    @Override
    protected <T> FunctionTimer newFunctionTimer(Meter.Id id, T obj, ToLongFunction<T> countFunction,
                                                 ToDoubleFunction<T> totalTimeFunction, TimeUnit totalTimeFunctionUnit) {
        return new CumulativeFunctionTimer<>(id, obj, countFunction, totalTimeFunction, totalTimeFunctionUnit,
                getBaseTimeUnit());
    }

    @Override
    protected <T> FunctionCounter newFunctionCounter(Meter.Id id, T obj, ToDoubleFunction<T> countFunction) {
        return new CumulativeFunctionCounter<>(id, obj, countFunction);
    }

    @Override
    protected TimeUnit getBaseTimeUnit() {
        return TimeUnit.SECONDS;
    }

    @Override
    protected DistributionStatisticConfig defaultHistogramConfig() {
        return DistributionStatisticConfig.builder()
                .expiry(config.step())
                .build()
                .merge(DistributionStatisticConfig.DEFAULT);
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

        public ChartetoMeterRegistry.Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public ChartetoMeterRegistry.Builder threadFactory(ThreadFactory threadFactory) {
            this.threadFactory = threadFactory;
            return this;
        }

        public ChartetoMeterRegistry.Builder httpClient(HttpSender httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public ChartetoMeterRegistry build() {
            return new ChartetoMeterRegistry(this.config, this.clock, this.threadFactory, this.httpClient);
        }
    }
}
