package io.github.hectorvent.floci.services.appsync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.DenyField;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.PlanResult;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncTransportException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The wire contract Floci owns towards the GraphQL sidecar: request shapes for each endpoint,
 * including the {@code scalars} mapping the sidecar's generic coercion kinds require, and how the
 * sidecar's 200 / 400 / other answers become AppSync transport errors. graphql-java behavior lives
 * in the sidecar's own repository.
 */
class GraphqlSidecarClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<JsonNode> lastBody = new AtomicReference<>();
    private HttpServer server;
    private GraphqlSidecarClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        GraphqlSidecarManager manager = mock(GraphqlSidecarManager.class);
        when(manager.ensureReady()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
        client = new GraphqlSidecarClient(manager, mapper);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void validateSchemaPostsTheSdlAndScalarMapping() {
        answer("/v1/schema/validate", 200, "{\"valid\":true}");

        client.validateSchema("type Query { hello: String }", Map.of("AWSDateTime", "date-time"));

        assertThat(lastPath.get(), equalTo("/v1/schema/validate"));
        assertThat(lastBody.get().get("sdl").asText(), equalTo("type Query { hello: String }"));
        assertThat(lastBody.get().get("scalars").get("AWSDateTime").asText(), equalTo("date-time"));
    }

    @Test
    void planPostsTheQueryAndScalarMappingAndReadsFieldsAndDirectives() {
        answer("/v1/plan", 200, "{\"operationType\":\"QUERY\",\"fields\":["
                + "{\"typeName\":\"Query\",\"fieldName\":\"hello\","
                + "\"directives\":[{\"name\":\"aws_iam\",\"args\":{}}],\"typeDirectives\":[]}]}");

        PlanResult result = client.plan("type Query { hello: String }", Map.of("AWSJSON", "json-string"),
                "{ hello }", null);

        assertThat(lastBody.get().get("scalars").get("AWSJSON").asText(), equalTo("json-string"));
        assertThat(lastBody.get().has("operationName"), is(false));
        assertThat(result.operationType(), equalTo("QUERY"));
        assertThat(result.fields().get(0).typeName(), equalTo("Query"));
        assertThat(result.fields().get(0).fieldName(), equalTo("hello"));
        assertThat(result.fields().get(0).directives().get(0).name(), equalTo("aws_iam"));
    }

    @Test
    void executePostsSdlScalarsVariablesAndDenyFields() {
        answer("/v1/execute", 200, "{\"data\":{\"hello\":null},\"errors\":[]}");

        Map<String, Object> result = client.execute("type Query { hello: String }", Map.of("AWSJSON", "json-string"),
                "{ hello }", Map.of("x", 1), "GetHello",
                List.of(new DenyField("Query", "secret", "Unauthorized", "nope")));

        assertThat(lastBody.get().get("scalars").get("AWSJSON").asText(), equalTo("json-string"));
        assertThat(lastBody.get().get("variables").get("x").asInt(), equalTo(1));
        assertThat(lastBody.get().get("operationName").asText(), equalTo("GetHello"));
        assertThat(lastBody.get().get("denyFields").get(0).get("typeName").asText(), equalTo("Query"));
        assertThat(lastBody.get().get("denyFields").get(0).get("errorType").asText(), equalTo("Unauthorized"));
        assertThat(result.get("data"), is(Collections.singletonMap("hello", null)));
    }

    @Test
    void schemaValidationBadRequestBecomesSchemaValidationExceptionWithIssues() {
        answer("/v1/schema/validate", 400, "{\"error\":\"bad schema\",\"issues\":["
                + "{\"category\":\"PARSER_ERROR\",\"message\":\"unexpected token\",\"line\":1,\"column\":5}]}");

        GraphqlSidecarClient.SchemaValidationException error = assertThrows(
                GraphqlSidecarClient.SchemaValidationException.class,
                () -> client.validateSchema("type Query {", Map.of()));

        assertThat(error.getMessage(), equalTo("bad schema"));
        assertThat(error.issues().get(0).category(), equalTo("PARSER_ERROR"));
        assertThat(error.issues().get(0).line(), equalTo(1));
    }

    @Test
    void otherSidecarFailuresBecomeInternalFailureException() {
        answer("/v1/execute", 500, "{\"error\":\"boom\"}");

        AppSyncTransportException error = assertThrows(AppSyncTransportException.class,
                () -> client.execute("type Query { hello: String }", Map.of(), "{ hello }", Map.of(), null, List.of()));

        assertThat(error.getErrorType(), equalTo("InternalFailureException"));
        assertThat(error.getHttpStatus(), equalTo(500));
    }

    @Test
    void unreachableSidecarBecomesInternalFailureException() {
        GraphqlSidecarManager down = mock(GraphqlSidecarManager.class);
        when(down.ensureReady()).thenThrow(new IllegalStateException("speaks sidecar contract 2"));
        GraphqlSidecarClient offline = new GraphqlSidecarClient(down, mapper);

        AppSyncTransportException error = assertThrows(AppSyncTransportException.class,
                () -> offline.validateSchema("type Query { hello: String }", Map.of()));

        assertThat(error.getErrorType(), equalTo("InternalFailureException"));
        assertThat(error.getMessage(), equalTo("Failed to call GraphQL sidecar for schema validation: speaks sidecar contract 2"));
    }

    private void answer(String path, int status, String body) {
        server.createContext(path, exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastBody.set(mapper.readTree(exchange.getRequestBody()));
            respond(exchange, status, body);
        });
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
