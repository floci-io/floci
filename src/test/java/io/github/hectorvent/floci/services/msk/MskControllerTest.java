package io.github.hectorvent.floci.services.msk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.services.msk.model.ConfigurationRevision;
import io.github.hectorvent.floci.services.msk.model.MskConfiguration;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

// Covers the response views the controller assembles by hand, without standing up Quarkus.
// The configuration views are built into a HashMap, which - unlike Map.of - accepts a null
// value and keeps the key, and nothing in this application sets a NON_NULL serialization
// inclusion. So a null latestRevision does not vanish from the response: it goes out as an
// explicit "latestRevision": null, and AWS marks Configuration's LatestRevision required.
class MskControllerTest {

    private MskService mskService;
    private MskController controller;

    // A configuration exactly as the pre-revision-history schema persisted it - the one case
    // that used to reach the views with no revision history at all.
    private static final String LEGACY_JSON = """
            {
              "arn": "arn:aws:kafka:us-east-1:000000000000:configuration/legacy/id",
              "name": "legacy",
              "description": "desc",
              "kafkaVersions": ["3.6.0"],
              "state": "ACTIVE",
              "creationTime": 1700000000,
              "latestRevision": {"revision": 1, "creationTime": 1700000000, "description": "desc"},
              "serverProperties": "auto.create.topics.enable=true"
            }
            """;

    @BeforeEach
    void setUp() {
        mskService = Mockito.mock(MskService.class);
        controller = new MskController(mskService);
    }

    private static MskConfiguration legacyConfiguration() throws Exception {
        return new ObjectMapper().registerModule(new JavaTimeModule())
                .readValue(LEGACY_JSON, MskConfiguration.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entityOf(Response response) {
        return (Map<String, Object>) response.getEntity();
    }

    @Test
    void describeConfigurationReturnsLatestRevisionForPreRevisionHistoryEntry() throws Exception {
        MskConfiguration legacy = legacyConfiguration();
        when(mskService.describeConfiguration(legacy.getArn())).thenReturn(legacy);

        Map<String, Object> view = entityOf(controller.describeConfiguration(legacy.getArn()));

        // Present as a key at all is not enough - a HashMap holds the key with a null value,
        // and that is what would be serialized.
        assertNotNull(view.get("latestRevision"));
        assertEquals(1L, ((ConfigurationRevision) view.get("latestRevision")).getRevision());
    }

    @Test
    void listConfigurationsReturnsLatestRevisionForPreRevisionHistoryEntry() throws Exception {
        MskConfiguration legacy = legacyConfiguration();
        when(mskService.listConfigurations(null, null))
                .thenReturn(new PaginatedResult<>(List.of(legacy), null));

        Map<String, Object> response = entityOf(controller.listConfigurations(null, null));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> configurations = (List<Map<String, Object>>) response.get("configurations");
        assertEquals(1, configurations.size());
        assertNotNull(configurations.get(0).get("latestRevision"));
        assertEquals(1L, ((ConfigurationRevision) configurations.get(0).get("latestRevision")).getRevision());
    }
}
