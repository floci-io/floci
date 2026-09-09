package io.github.hectorvent.floci.services.docdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.docdb.model.DocDbCluster;
import io.github.hectorvent.floci.services.docdb.model.DocDbInstance;
import io.github.hectorvent.floci.services.rds.model.DbClusterParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbParameterGroup;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Records written before tags were stored have to keep working after an upgrade.
 *
 * <p>Floci persists these, so the first read after an upgrade deserializes JSON with no
 * {@code tags} entry — and an explicit null is what Jackson writes back for a null field. Either
 * way a caller listing tags must get an empty list, not a failure.
 */
class TaggedRecordUpgradeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void aClusterPersistedWithoutTagsReadsAsUntagged() throws Exception {
        DocDbCluster cluster = MAPPER.readValue(
                "{\"dbClusterIdentifier\":\"legacy\",\"status\":\"available\"}", DocDbCluster.class);

        assertNotNull(cluster.getTags());
        assertTrue(cluster.getTags().isEmpty());

        cluster.getTags().put("env", "added-after-upgrade");
        assertEquals(Map.of("env", "added-after-upgrade"), cluster.getTags());
    }

    @Test
    void anExplicitNullIsToleratedByEveryNewlyTaggedRecord() throws Exception {
        assertTrue(MAPPER.readValue("{\"tags\":null}", DocDbCluster.class).getTags().isEmpty());
        assertTrue(MAPPER.readValue("{\"tags\":null}", DocDbInstance.class).getTags().isEmpty());
        assertTrue(MAPPER.readValue("{\"tags\":null}", DbParameterGroup.class).getTags().isEmpty());
        assertTrue(MAPPER.readValue("{\"tags\":null}", DbClusterParameterGroup.class).getTags().isEmpty());
    }

    @Test
    void tagsSurviveARoundTripThroughStorage() throws Exception {
        DocDbInstance instance = new DocDbInstance();
        instance.setDbInstanceIdentifier("round-trip");
        instance.setTags(Map.of("env", "prod"));

        DocDbInstance restored =
                MAPPER.readValue(MAPPER.writeValueAsString(instance), DocDbInstance.class);

        assertEquals(Map.of("env", "prod"), restored.getTags());
    }
}
