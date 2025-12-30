package com.charteto;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Statistic;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

class ChartetoMetricMetadata {

    private static final Set<String> UNIT_WHITELIST = Collections.unmodifiableSet(new HashSet(Arrays.asList("bit", "byte", "kibibyte", "mebibyte", "gibibyte", "tebibyte", "pebibyte", "exbibyte", "microsecond", "millisecond", "second", "minute", "hour", "day", "week", "nanosecond", "fraction", "percent", "percent_nano", "apdex", "connection", "request", "packet", "segment", "response", "message", "payload", "timeout", "datagram", "route", "session", "process", "core", "thread", "host", "node", "fault", "service", "instance", "cpu", "file", "inode", "sector", "block", "buffer", "error", "read", "write", "occurrence", "event", "time", "unit", "operation", "item", "task", "worker", "resource", "email", "sample", "stage", "monitor", "location", "check", "attempt", "device", "update", "method", "job", "container", "table", "index", "lock", "transaction", "query", "row", "key", "command", "offset", "record", "object", "cursor", "assertion", "scan", "document", "shard", "flush", "merge", "refresh", "fetch", "column", "commit", "wait", "ticket", "question", "hit", "miss", "eviction", "get", "set", "dollar", "cent", "page", "split", "hertz", "kilohertz", "megahertz", "gigahertz", "entry")));
    private static final Map<String, String> PLURALIZED_UNIT_MAPPING;
    private final Meter.Id id;
    private final String type;
    private final boolean descriptionsEnabled;
    private final @Nullable String overrideBaseUnit;

    ChartetoMetricMetadata(Meter.Id id, Statistic statistic, boolean descriptionsEnabled, @Nullable String overrideBaseUnit) {
        this.id = id;
        this.descriptionsEnabled = descriptionsEnabled;
        this.overrideBaseUnit = overrideBaseUnit;
        this.type = sanitizeType(statistic);
    }

    static @Nullable String sanitizeBaseUnit(@Nullable String baseUnit, @Nullable String overrideBaseUnit) {
        String sanitizeBaseUnit = overrideBaseUnit != null ? overrideBaseUnit : baseUnit;
        if (sanitizeBaseUnit != null) {
            return UNIT_WHITELIST.contains(sanitizeBaseUnit) ? sanitizeBaseUnit : PLURALIZED_UNIT_MAPPING.get(sanitizeBaseUnit);
        } else {
            return null;
        }
    }

    static String sanitizeType(Statistic statistic) {
        switch (statistic) {
            case COUNT:
            case TOTAL:
            case TOTAL_TIME:
                return "COUNT";
            default:
                return "GAUGE";
        }
    }

    static {
        Map<String, String> pluralizedUnitMapping = new HashMap<>();
        UNIT_WHITELIST.forEach((unit) -> {
            pluralizedUnitMapping.put(unit + "s", unit);
        });
        pluralizedUnitMapping.put("indices", "index");
        pluralizedUnitMapping.put("indexes", "index");
        PLURALIZED_UNIT_MAPPING = Collections.unmodifiableMap(pluralizedUnitMapping);
    }
}
