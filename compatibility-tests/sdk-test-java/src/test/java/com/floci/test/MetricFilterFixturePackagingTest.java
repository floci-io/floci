package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Offline: deliberately has no SDK clients or SDK lifecycle hooks. */
class MetricFilterFixturePackagingTest {
    @Test
    void canonicalPublishingFixtureLoadsFromTheModuleClasspath() throws Exception {
        JsonNode fixture = MetricFilterFixture.load();
        assertThat(fixture).as("The module must load its packaged publishing oracle").isNotNull();
        assertThat(fixture.get("verification").asText()).isEqualTo("Verified on Live AWS");
        assertThat(fixture.get("metricDefaults").get("cases").get(0).get("expected").get("Sum").asDouble())
                .isEqualTo(13);
        assertThat(fixture.get("extraction").get("cases").size()).isEqualTo(4);
        assertThat(fixture.get("systemDimensions").get("cases").size()).isEqualTo(3);
    }
}
