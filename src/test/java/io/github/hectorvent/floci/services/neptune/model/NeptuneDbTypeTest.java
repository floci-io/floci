package io.github.hectorvent.floci.services.neptune.model;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NeptuneDbTypeTest {

    @Test
    void defaultsToGremlinWhenUnset() {
        assertEquals(NeptuneDbType.GREMLIN, NeptuneDbType.fromConfig(null));
        assertEquals(NeptuneDbType.GREMLIN, NeptuneDbType.fromConfig(""));
        assertEquals(NeptuneDbType.GREMLIN, NeptuneDbType.fromConfig("  "));
    }

    @Test
    void parsesGremlinAliases() {
        assertEquals(NeptuneDbType.GREMLIN, NeptuneDbType.fromConfig("gremlin"));
        assertEquals(NeptuneDbType.GREMLIN, NeptuneDbType.fromConfig("TinkerPop"));
        assertEquals(NeptuneDbType.GREMLIN, NeptuneDbType.fromConfig(" GREMLIN "));
    }

    @Test
    void parsesNeo4jAliases() {
        assertEquals(NeptuneDbType.NEO4J, NeptuneDbType.fromConfig("neo4j"));
        assertEquals(NeptuneDbType.NEO4J, NeptuneDbType.fromConfig("openCypher"));
        assertEquals(NeptuneDbType.NEO4J, NeptuneDbType.fromConfig("cypher"));
        assertEquals(NeptuneDbType.NEO4J, NeptuneDbType.fromConfig("bolt"));
    }

    @Test
    void backendPortsMatchProtocols() {
        assertEquals(8182, NeptuneDbType.GREMLIN.backendPort());
        assertEquals(7687, NeptuneDbType.NEO4J.backendPort());
    }

    @Test
    void rejectsUnknownDbType() {
        AwsException ex = assertThrows(AwsException.class,
                () -> NeptuneDbType.fromConfig("sparql"));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
    }
}
