package io.github.hectorvent.floci.services.rds.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A record persisted before apply methods were stored must reload with an empty map, not null. */
class ParameterGroupApplyMethodsPersistenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parameterGroupWithoutApplyMethodsReloadsEmpty() throws Exception {
        DbParameterGroup g = MAPPER.readValue(
                "{\"dbParameterGroupName\":\"g\",\"parameters\":{\"a\":\"1\"}}", DbParameterGroup.class);
        assertEquals(Map.of(), g.getApplyMethods());
        assertEquals(Map.of(), MAPPER.readValue("{\"applyMethods\":null}", DbParameterGroup.class).getApplyMethods());
    }

    @Test
    void parameterGroupApplyMethodsRoundTrip() throws Exception {
        DbParameterGroup g = new DbParameterGroup("g", "postgres15", "d");
        g.getApplyMethods().put("work_mem", "pending-reboot");
        DbParameterGroup back = MAPPER.readValue(MAPPER.writeValueAsString(g), DbParameterGroup.class);
        assertEquals(Map.of("work_mem", "pending-reboot"), back.getApplyMethods());
    }

    @Test
    void clusterParameterGroupWithoutApplyMethodsReloadsEmpty() throws Exception {
        DbClusterParameterGroup g = MAPPER.readValue(
                "{\"dbClusterParameterGroupName\":\"g\",\"parameters\":{\"a\":\"1\"}}", DbClusterParameterGroup.class);
        assertEquals(Map.of(), g.getApplyMethods());
        assertEquals(Map.of(),
                MAPPER.readValue("{\"applyMethods\":null}", DbClusterParameterGroup.class).getApplyMethods());
    }

    @Test
    void clusterParameterGroupApplyMethodsRoundTrip() throws Exception {
        DbClusterParameterGroup g = new DbClusterParameterGroup();
        g.getApplyMethods().put("rds.force_ssl", "pending-reboot");
        DbClusterParameterGroup back =
                MAPPER.readValue(MAPPER.writeValueAsString(g), DbClusterParameterGroup.class);
        assertEquals(Map.of("rds.force_ssl", "pending-reboot"), back.getApplyMethods());
    }
}
