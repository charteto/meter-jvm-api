package com.charteto;

import io.micrometer.core.instrument.Statistic;
import org.junit.Test;

import static org.junit.Assert.*;

public class ChartetoMetricMetadataTest {

    @Test
    public void countStatisticsAreMappedToCount() {
        assertEquals("COUNT", ChartetoMetricMetadata.sanitizeType(Statistic.COUNT));
        assertEquals("COUNT", ChartetoMetricMetadata.sanitizeType(Statistic.TOTAL));
        assertEquals("COUNT", ChartetoMetricMetadata.sanitizeType(Statistic.TOTAL_TIME));
    }

    @Test
    public void nonCountStatisticsAreMappedToGauge() {
        assertEquals("GAUGE", ChartetoMetricMetadata.sanitizeType(Statistic.VALUE));
        assertEquals("GAUGE", ChartetoMetricMetadata.sanitizeType(Statistic.MAX));
        assertEquals("GAUGE", ChartetoMetricMetadata.sanitizeType(Statistic.ACTIVE_TASKS));
    }

    @Test
    public void whitelistedUnitIsReturnedAsIs() {
        String result = ChartetoMetricMetadata.sanitizeBaseUnit("second", null);
        assertEquals("second", result);
    }

    @Test
    public void pluralizedUnitIsSingularized() {
        String result = ChartetoMetricMetadata.sanitizeBaseUnit("seconds", null);
        assertEquals("second", result);
    }

    @Test
    public void specialPluralFormsAreHandled() {
        assertEquals("index", ChartetoMetricMetadata.sanitizeBaseUnit("indices", null));
        assertEquals("index", ChartetoMetricMetadata.sanitizeBaseUnit("indexes", null));
    }

    @Test
    public void nonWhitelistedUnitReturnsNull() {
        String result = ChartetoMetricMetadata.sanitizeBaseUnit("lightyears", null);
        assertNull(result);
    }

    @Test
    public void nullBaseUnitReturnsNull() {
        String result = ChartetoMetricMetadata.sanitizeBaseUnit(null, null);
        assertNull(result);
    }

    @Test
    public void overrideBaseUnitTakesPrecedence() {
        String result = ChartetoMetricMetadata.sanitizeBaseUnit(
                "seconds",
                "minute"
        );

        assertEquals("minute", result);
    }

    @Test
    public void overrideBaseUnitIsAlsoSanitized() {
        String result = ChartetoMetricMetadata.sanitizeBaseUnit(
                "seconds",
                "minutes"
        );

        assertEquals("minute", result);
    }

    @Test
    public void invalidOverrideBaseUnitResultsInNull() {
        String result = ChartetoMetricMetadata.sanitizeBaseUnit(
                "seconds",
                "lightyears"
        );

        assertNull(result);
    }
}
