package io.github.hectorvent.floci.services.appsync.graphql;

import graphql.schema.DataFetcher;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import io.github.hectorvent.floci.services.appsync.graphql.scalars.AppSyncScalarRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static graphql.Scalars.GraphQLString;
import static graphql.schema.FieldCoordinates.coordinates;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryExecutorTest {

    private QueryExecutor executor;
    private GraphQLSchema helloSchema;
    private GraphQLSchema subscriptionSchema;
    private GraphQLSchema multiOpSchema;

    @BeforeEach
    void setUp() {
        AppSyncErrorFormatter formatter = new AppSyncErrorFormatter();
        executor = new QueryExecutor(formatter);
        AppSyncSchemaParser parser = new AppSyncSchemaParser(new AppSyncScalarRegistry());
        helloSchema = parser.parse("type Query { hello: String }");
        subscriptionSchema = parser.parse("""
                type Query { hello: String }
                type Subscription { onHello: String }
                """);
        multiOpSchema = parser.parse("""
                type Query {
                  hello: String
                  bye: String
                }
                """);
    }

    @Test
    void nullableFieldReturnsNullWithoutDataFetcher() {
        Map<String, Object> response = executor.execute(helloSchema, "{ hello }", null, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertTrue(data.containsKey("hello"));
        assertNull(data.get("hello"));
        assertTrue(!response.containsKey("errors") || ((List<?>) response.get("errors")).isEmpty());
    }

    @Test
    void usesAsyncStrategiesAndSupportsSimpleQuery() {
        Map<String, Object> response = executor.execute(helloSchema, "query Q { hello }", Map.of(), "Q");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNull(data.get("hello"));
    }

    @Test
    void httpSubscriptionReturnsOperationNotSupported() {
        Map<String, Object> response = executor.execute(
                subscriptionSchema,
                "subscription { onHello }",
                null,
                null);

        assertTrue(response.containsKey("errors"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) response.get("errors");
        assertEquals(1, errors.size());
        assertEquals("OperationNotSupported", errors.get(0).get("errorType"));
        Object data = response.get("data");
        if (data != null) {
            assertTrue(!(data instanceof org.reactivestreams.Publisher),
                    "Must not return Publisher JSON for HTTP subscriptions");
        }
    }

    @Test
    void missingOperationNameWithMultipleOpsThrowsBadRequest() {
        AppSyncTransportException ex = assertThrows(AppSyncTransportException.class, () ->
                executor.execute(multiOpSchema, """
                        query A { hello }
                        query B { bye }
                        """, null, null));

        assertEquals(400, ex.getHttpStatus());
        assertEquals("BadRequestException", ex.getErrorType());
        assertEquals("Missing operation name.", ex.getMessage());
    }

    @Test
    void operationNameSelectsAmongMultipleOps() {
        Map<String, Object> response = executor.execute(multiOpSchema, """
                query A { hello }
                query B { bye }
                """, null, "B");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertTrue(data.containsKey("bye"));
        assertNull(data.get("bye"));
    }

    @Test
    void syntaxErrorFormattedAsSyntaxError() {
        Map<String, Object> response = executor.execute(helloSchema, "{ hello", null, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) response.get("errors");
        assertEquals("SyntaxError", errors.get(0).get("errorType"));
    }

    @Test
    void executePassesGraphQlContextKeys() {
        DataFetcher<Object> fetcher = env -> {
            assertEquals("IAM Authorization", env.getGraphQlContext().get("authType"));
            Object identity = env.getGraphQlContext().get("identity");
            assertTrue(identity instanceof Map<?, ?>);
            assertTrue(((Map<?, ?>) identity).get("sourceIp") instanceof List<?>);
            return env.getGraphQlContext().get("authType");
        };
        GraphQLObjectType query = GraphQLObjectType.newObject()
                .name("Query")
                .field(f -> f.name("hello").type(GraphQLString))
                .build();
        GraphQLSchema schema = GraphQLSchema.newSchema()
                .query(query)
                .codeRegistry(graphql.schema.GraphQLCodeRegistry.newCodeRegistry()
                        .dataFetcher(coordinates("Query", "hello"), fetcher)
                        .build())
                .build();
        Map<Object, Object> ctx = Map.of(
                "identity", Map.of("sourceIp", List.of("127.0.0.1")),
                "authType", "IAM Authorization",
                "deniedFields", List.of());

        Map<String, Object> response = executor.execute(
                SchemaRegistry.buildGraphQL(schema), "{ hello }", null, null, ctx);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertEquals("IAM Authorization", data.get("hello"));
    }
}
