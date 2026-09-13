package io.github.hectorvent.floci.services.appsync.graphql;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SchemaRegistry} is now just an {@code apiId -> raw SDL} cache: schema compilation
 * happens in the GraphQL sidecar, not here (issue #2917).
 */
class SchemaRegistryTest {

    private static final String SDL = "type Query { hello: String }";

    private SchemaRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SchemaRegistry();
    }

    @Test
    void getSdlReturnsWhatWasRegistered() {
        registry.register("api-1", SDL);

        assertEquals(SDL, registry.getSdl("api-1").orElseThrow());
    }

    @Test
    void unknownApiIdIsAbsent() {
        assertTrue(registry.getSdl("nope").isEmpty());
    }

    @Test
    void removeClearsTheEntry() {
        registry.register("api-1", SDL);
        registry.remove("api-1");

        assertTrue(registry.getSdl("api-1").isEmpty());
    }

    @Test
    void reregisterReplacesTheStoredSdl() {
        registry.register("api-1", SDL);
        registry.register("api-1", "type Query { bye: String }");

        assertEquals("type Query { bye: String }", registry.getSdl("api-1").orElseThrow());
    }
}
