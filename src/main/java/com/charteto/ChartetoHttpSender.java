package com.charteto;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class ChartetoHttpSender {

    private final ChartetoRegistryConfig config;

    public ChartetoHttpSender(ChartetoRegistryConfig config) {
        this.config = config;
    }

    public void send(String payload) throws Exception {
        URL url = new URL(config.uri());
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        // Setting Headers
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");

        // **YOUR CUSTOM HEADER HERE**
        connection.setRequestProperty("X-API-Key", config.apiKeyHeader());

        connection.setDoOutput(true);

        byte[] output = payload.getBytes(StandardCharsets.UTF_8);

        try (OutputStream os = connection.getOutputStream()) {
            os.write(output);
            os.flush();
        }

        // Check response code
        int responseCode = connection.getResponseCode();
        if (responseCode >= 400) {
            throw new RuntimeException("Charteto metrics endpoint error: " + responseCode);
        }
    }

}
