package io.github.hectorvent.floci.services.redshift.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationModelTest {

    @Test
    void legacyJsonWithCheckpointSequenceNumberLoadsWithEmptyShardSequenceNumbers() throws Exception {
        // A default ObjectMapper, as the storage backends use, so an unknown property fails the load.
        Integration restored = new ObjectMapper().readValue("""
                {"integrationArn":"arn:aws:redshift:us-east-1:111111111111:integration:one",
                 "checkpointSequenceNumber":"000000000000000000042","backfillCompleted":true}
                """, Integration.class);

        assertEquals("arn:aws:redshift:us-east-1:111111111111:integration:one", restored.getIntegrationArn());
        assertTrue(restored.isBackfillCompleted());
        assertTrue(restored.getShardSequenceNumbers().isEmpty());
    }

    @Test
    void shardSequenceNumbersStayNonNullAndAreCopiedOnSet() throws Exception {
        Integration integration = new Integration();
        assertTrue(integration.getShardSequenceNumbers().isEmpty());

        integration.setShardSequenceNumbers(null);
        assertTrue(integration.getShardSequenceNumbers().isEmpty());

        Map<String, String> progress = new HashMap<>(Map.of("shard-1", "s1"));
        integration.setShardSequenceNumbers(progress);
        progress.put("shard-2", "s2");
        assertEquals(Map.of("shard-1", "s1"), integration.getShardSequenceNumbers());

        Integration nulled = new ObjectMapper().readValue("{\"shardSequenceNumbers\":null}", Integration.class);
        assertTrue(nulled.getShardSequenceNumbers().isEmpty());
    }
}
