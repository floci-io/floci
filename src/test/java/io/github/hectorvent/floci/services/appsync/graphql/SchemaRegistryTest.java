package io.github.hectorvent.floci.services.appsync.graphql;

import graphql.GraphQL;
import io.github.hectorvent.floci.services.appsync.graphql.scalars.AppSyncScalarRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaRegistryTest {

    private static final String SDL = "type Query { hello: String }";

    private SchemaRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SchemaRegistry(new AppSyncSchemaParser(new AppSyncScalarRegistry()));
    }

    @Test
    void getGraphQLReturnsSameInstanceAcrossCalls() {
        registry.register("api-1", SDL);

        GraphQL first = registry.getGraphQL("api-1").orElseThrow();
        GraphQL second = registry.getGraphQL("api-1").orElseThrow();

        assertSame(first, second);
    }

    @Test
    void removeClearsCachedGraphQL() {
        registry.register("api-1", SDL);
        assertTrue(registry.getGraphQL("api-1").isPresent());

        registry.remove("api-1");

        assertTrue(registry.getGraphQL("api-1").isEmpty());
        assertTrue(registry.getSchema("api-1").isEmpty());
    }

    @Test
    void reregisterReplacesCachedGraphQL() {
        registry.register("api-1", SDL);
        GraphQL original = registry.getGraphQL("api-1").orElseThrow();

        registry.register("api-1", "type Query { bye: String }");
        GraphQL replaced = registry.getGraphQL("api-1").orElseThrow();

        assertNotSame(original, replaced);
        assertSame(replaced, registry.getGraphQL("api-1").orElseThrow());
    }
}
