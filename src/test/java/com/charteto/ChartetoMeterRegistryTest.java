package com.charteto;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

// A custom ChartetoConfig for testing that can point to our local server
class TestChartetoConfig implements ChartetoConfig {
    private String uri;
    private String apiKey = "test-api-key";
    private boolean enabled = true;

    public TestChartetoConfig(String uri) {
        this.uri = uri;
    }

    public TestChartetoConfig(String uri, boolean enabled) {
        this.uri = uri;
        this.enabled = enabled;
    }

    @Override
    public String prefix() {
        return "charteto";
    }

    @Override
    public String get(String key) {
        return null; // Not needed for these tests
    }

    @Override
    public String uri() {
        return uri;
    }

    @Override
    public String apiKey() {
        return apiKey;
    }

    @Override
    public int batchSize() {
        return 100;
    }

    @Override
    public Duration connectTimeout() {
        return Duration.ofSeconds(2);
    }

    @Override
    public Duration readTimeout() {
        return Duration.ofSeconds(2);
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public Duration step() {
        return Duration.ofMinutes(1);
    }
}

// A simple handler for our test HTTP server to capture and inspect requests
class CapturingHttpHandler implements HttpHandler {
    private final List<String> receivedBodies = new ArrayList<>();
    private final List<String> receivedApiKeys = new ArrayList<>();
    private final List<String> receivedMethods = new ArrayList<>();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        receivedMethods.add(exchange.getRequestMethod());
        receivedApiKeys.add(exchange.getRequestHeaders().getFirst("X-API-Key"));

        String body = convertStreamToString(exchange.getRequestBody());
        receivedBodies.add(body);

        // Send a minimal successful response
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    public String getLatestBody() {
        if (receivedBodies.isEmpty()) {
            return null;
        }
        return receivedBodies.get(receivedBodies.size() - 1);
    }

    public String getLatestApiKey() {
        if (receivedApiKeys.isEmpty()) {
            return null;
        }
        return receivedApiKeys.get(receivedApiKeys.size() - 1);
    }

    public String getLatestMethod() {
        if (receivedMethods.isEmpty()) {
            return null;
        }
        return receivedMethods.get(receivedMethods.size() - 1);
    }

    public void clear() {
        receivedBodies.clear();
        receivedApiKeys.clear();
        receivedMethods.clear();
    }

    private String convertStreamToString(InputStream is) {
        Scanner s = new Scanner(is, StandardCharsets.UTF_8.name()).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }
}


public class ChartetoMeterRegistryTest {

    private HttpServer server;
    private CapturingHttpHandler handler;
    private ChartetoMeterRegistry registry;
    private TestChartetoConfig config;

    @Before
    public void setUp() throws IOException {
        // Start a lightweight HTTP server on a free port
        handler = new CapturingHttpHandler();
        server = HttpServer.create(new InetSocketAddress(0), 0); // 0 finds a free port
        server.createContext("/api/v1/metrics", handler);
        server.setExecutor(null); // Use a default single-threaded executor
        server.start();

        // Configure the registry to point to our test server
        String serverUri = "http://localhost:" + server.getAddress().getPort();
        config = new TestChartetoConfig(serverUri);

        // Let the registry create its own default HttpUrlConnectionSender
        registry = new ChartetoMeterRegistry(config, Clock.SYSTEM);
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0); // Stop the server immediately
        }
        if (registry != null) {
            registry.close();
        }
    }

    @Test
    public void publishSendsMetricsCorrectly() {
        // 1. Arrange: Create some metrics in the registry
        Counter counter = registry.counter("my.test.counter", "tagKey", "tagValue");
        counter.increment(42);

        Timer timer = registry.timer("my.test.timer", "timerTag", "timerValue");
        timer.record(Duration.ofSeconds(2));

        registry.gauge("my.test.gauge", 123.0);

        // 2. Act: Manually trigger the publish action
        registry.publish();

        // 3. Assert: Check what the HTTP server received
        assertEquals("POST", handler.getLatestMethod());
        assertEquals(config.apiKey(), handler.getLatestApiKey());

        String receivedJson = handler.getLatestBody();
        assertNotNull("Request body should not be null", receivedJson);

        assertTrue("JSON should start with batch information", receivedJson.startsWith("{\"batchId\":\""));
        assertTrue("JSON should contain a metrics array", receivedJson.contains(",\"metrics\":["));
        assertTrue("JSON should be correctly terminated", receivedJson.endsWith("]}"));

        // Verify counter metric content - type should be uppercase "COUNT"
        assertTrue("JSON should contain the counter name", receivedJson.contains("\"name\":\"my.test.counter\""));
        assertTrue("JSON should contain counter value", receivedJson.contains("42.0"));
        assertTrue("JSON should contain counter type 'COUNT'", receivedJson.contains("\"type\":\"COUNT\""));
        assertTrue("JSON should contain the counter's custom tag", receivedJson.contains("\"tagKey\":\"tagValue\""));

        // Verify timer metric content. Based on the actual output, TOTAL_TIME is serialized as "total".
        assertTrue("JSON should contain the timer name", receivedJson.contains("\"name\":\"my.test.timer\""));
        assertTrue("JSON should contain timer statistic 'count'", receivedJson.contains("\"statistic\":\"count\""));
        assertTrue("JSON should contain timer statistic 'total'", receivedJson.contains("\"statistic\":\"total\""));
        assertTrue("JSON should contain timer statistic 'max'", receivedJson.contains("\"statistic\":\"max\""));
        assertTrue("JSON should contain the timer's custom tag", receivedJson.contains("\"timerTag\":\"timerValue\""));

        // Verify gauge metric content - type should be uppercase "GAUGE"
        assertTrue("JSON should contain the gauge name", receivedJson.contains("\"name\":\"my.test.gauge\""));
        assertTrue("JSON should contain the gauge value", receivedJson.contains("123.0"));
        assertTrue("JSON should contain gauge type 'GAUGE'", receivedJson.contains("\"type\":\"GAUGE\""));
    }

    @Test
    public void meterCreationWorksAsExpected() {
        // This test ensures the basic meter factory methods work without needing to publish.
        Counter counter = registry.counter("test.counter");
        assertTrue(counter.getClass().getSimpleName().contains("CumulativeCounter"));

        Timer timer = registry.timer("test.timer");
        assertTrue(timer.getClass().getSimpleName().contains("CumulativeTimer"));

        // The registry.gauge() method registers the gauge and returns the number.
        // To verify the gauge was created, we must find it in the registry.
        registry.gauge("test.gauge", 1.0);
        Gauge foundGauge = registry.find("test.gauge").gauge();
        assertNotNull("Gauge should be found in the registry", foundGauge);
        assertEquals(1.0, foundGauge.value(), 0.0);
    }
}