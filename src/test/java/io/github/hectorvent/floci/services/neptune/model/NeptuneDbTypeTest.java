package io.github.hectorvent.floci.services.neptune.model;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NeptuneDbTypeTest {

    @Test
    void defaultsToGremlinWhenUnset() {
        assertEquals(Optional.of(NeptuneDbType.GREMLIN), NeptuneDbType.fromConfig(null));
        assertEquals(Optional.of(NeptuneDbType.GREMLIN), NeptuneDbType.fromConfig(""));
        assertEquals(Optional.of(NeptuneDbType.GREMLIN), NeptuneDbType.fromConfig("  "));
    }

    @Test
    void parsesGremlinAliases() {
        assertEquals(Optional.of(NeptuneDbType.GREMLIN), NeptuneDbType.fromConfig("gremlin"));
        assertEquals(Optional.of(NeptuneDbType.GREMLIN), NeptuneDbType.fromConfig("TinkerPop"));
        assertEquals(Optional.of(NeptuneDbType.GREMLIN), NeptuneDbType.fromConfig(" GREMLIN "));
    }

    @Test
    void parsesNeo4jAliases() {
        assertEquals(Optional.of(NeptuneDbType.NEO4J), NeptuneDbType.fromConfig("neo4j"));
        assertEquals(Optional.of(NeptuneDbType.NEO4J), NeptuneDbType.fromConfig("openCypher"));
        assertEquals(Optional.of(NeptuneDbType.NEO4J), NeptuneDbType.fromConfig("cypher"));
        assertEquals(Optional.of(NeptuneDbType.NEO4J), NeptuneDbType.fromConfig("bolt"));
    }

    @Test
    void backendPortsMatchProtocols() {
        assertEquals(8182, NeptuneDbType.GREMLIN.backendPort());
        assertEquals(7687, NeptuneDbType.NEO4J.backendPort());
    }

    @Test
    void rejectsUnknownDbType() {
        assertEquals(Optional.empty(), NeptuneDbType.fromConfig("sparql"));
    }
}
