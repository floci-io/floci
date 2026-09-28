package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AppConfigDataServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void transformFeatureFlagsProducesRetrievalTimeValues() throws Exception {
        byte[] deploymentContent = """
                {
                  "flags": {
                    "enabled": {"name": "enabled"},
                    "disabled": {"name": "disabled"}
                  },
                  "values": {
                    "enabled": {
                      "enabled": true,
                      "number": 0,
                      "beta": false,
                      "empty": "",
                      "choices": [],
                      "details": {"tier": "gold"},
                      "_createdAt": "created",
                      "_updatedAt": "updated"
                    },
                    "disabled": {"enabled": false, "secret": "must-not-leak"}
                  },
                  "version": "1"
                }
                """.getBytes(StandardCharsets.UTF_8);

        byte[] retrievalContent = AppConfigDataService.transformFeatureFlags(deploymentContent, MAPPER);

        assertEquals(MAPPER.readTree("""
                {
                  "enabled": {
                    "enabled": true,
                    "number": 0,
                    "beta": false,
                    "empty": "",
                    "choices": [],
                    "details": {"tier": "gold"}
                  },
                  "disabled": {"enabled": false}
                }
                """), MAPPER.readTree(retrievalContent));
    }

    @Test
    void transformFeatureFlagsPreservesWholeMultiVariantDocument() {
        byte[] content = """
                {"values":{"basic":{"enabled":true},"variant":{"enabled":true,"_variants":[]}}}
                """.getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(content, AppConfigDataService.transformFeatureFlags(content, MAPPER));
    }

    @Test
    void transformFeatureFlagsPreservesInvalidDocuments() {
        List<String> documents = List.of(
                "{not-json",
                "[]",
                "{}",
                "{\"values\":[]}",
                "{\"values\":{\"flag\":true}}",
                "{\"values\":{\"flag\":{}}}",
                "{\"values\":{\"flag\":{\"enabled\":\"true\"}}}");

        for (String document : documents) {
            byte[] content = document.getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(content, AppConfigDataService.transformFeatureFlags(content, MAPPER));
        }
    }

    @Test
    void transformFeatureFlagsPreservesNullAndEmptyContent() {
        assertNull(AppConfigDataService.transformFeatureFlags(null, MAPPER));
        assertArrayEquals(new byte[0], AppConfigDataService.transformFeatureFlags(new byte[0], MAPPER));
    }
}
