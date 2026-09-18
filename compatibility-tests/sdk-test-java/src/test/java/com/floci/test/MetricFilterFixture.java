package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;

final class MetricFilterFixture {
    private MetricFilterFixture() {}

    static JsonNode load() throws IOException {
        try (InputStream fixture = MetricFilterFixture.class.getResourceAsStream(
                "/cloudwatchlogs/metric-filter-publishing-aws.json")) {
            if (fixture == null) {
                throw new IOException("Missing packaged metric-filter publishing oracle");
            }
            return new ObjectMapper().readTree(fixture);
        }
    }
}
