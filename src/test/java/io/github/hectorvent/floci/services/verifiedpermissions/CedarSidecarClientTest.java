package io.github.hectorvent.floci.services.verifiedpermissions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.verifiedpermissions.model.Policy;
import io.github.hectorvent.floci.services.verifiedpermissions.model.PolicyTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The wire contract Floci owns towards the Cedar sidecar: request shapes for each endpoint and
 * how the sidecar's 200 / 400 / other answers become AWS errors. Cedar semantics live in the
 * sidecar's own repository.
 */
class CedarSidecarClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<JsonNode> lastBody = new AtomicReference<>();
    private HttpServer server;
    private CedarSidecarClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        CedarSidecarManager manager = mock(CedarSidecarManager.class);
        when(manager.ensureReady()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
        client = new CedarSidecarClient(manager, mapper);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void validateSchemaPostsTheSchema() {
        answer("/v1/schema/validate", 200, "{\"valid\":true}");

        client.validateSchema("{\"Demo\":{}}");

        assertThat(lastPath.get(), equalTo("/v1/schema/validate"));
        assertThat(lastBody.get().get("schema").asText(), equalTo("{\"Demo\":{}}"));
    }

    @Test
    void validateEntityTypePostsTheType() {
        answer("/v1/entity-type/validate", 200, "{\"valid\":true}");

        client.validateEntityType("Demo::User");

        assertThat(lastBody.get().get("entityType").asText(), equalTo("Demo::User"));
    }

    @Test
    void parsePolicyReturnsEffectAndAst() {
        answer("/v1/policy/parse", 200, "{\"effect\":\"Forbid\",\"ast\":{\"effect\":\"forbid\"}}");

        CedarSidecarClient.ParsedPolicy parsed = client.parsePolicy("forbid(principal, action, resource);", true);

        assertThat(lastBody.get().get("statement").asText(), equalTo("forbid(principal, action, resource);"));
        assertThat(lastBody.get().get("template").asBoolean(), is(true));
        assertThat(parsed.effect(), equalTo("Forbid"));
        assertThat(parsed.ast().get("effect").asText(), equalTo("forbid"));
    }

    @Test
    void validatePolicyPostsSchemaStatementAndTemplateFlag() {
        answer("/v1/policy/validate", 200, "{\"valid\":true}");

        client.validatePolicy("{\"Demo\":{}}", "permit(principal, action, resource);", false);

        assertThat(lastBody.get().get("schema").asText(), equalTo("{\"Demo\":{}}"));
        assertThat(lastBody.get().get("statement").asText(), equalTo("permit(principal, action, resource);"));
        assertThat(lastBody.get().get("template").asBoolean(), is(false));
    }

    @Test
    void authorizePostsRequestPoliciesAndTemplatesAndReadsTheDecision() throws Exception {
        answer("/v1/authorize", 200,
                "{\"decision\":\"DENY\",\"determiningPolicyIds\":[\"deny\"],\"errors\":[\"e1\"]}");
        JsonNode request = mapper.readTree("{\"principal\":{\"entityType\":\"Demo::User\",\"entityId\":\"a\"}}");
        Policy policy = new Policy("store", "deny", null, "STATIC", "forbid(principal, action, resource);",
                null, null, null, null, "Forbid", null, null);
        PolicyTemplate template = new PolicyTemplate("store", "t1", null,
                "permit(principal == ?principal, action, resource == ?resource);", null, null, null);

        CedarSidecarClient.EvaluationResult result = client.authorize(request, List.of(policy), Map.of("t1", template));

        assertThat(lastBody.get().get("request").get("principal").get("entityId").asText(), equalTo("a"));
        assertThat(lastBody.get().get("policies").get(0).get("policyId").asText(), equalTo("deny"));
        assertThat(lastBody.get().get("templates").get("t1").get("policyTemplateId").asText(), equalTo("t1"));
        assertThat(result.decision(), equalTo("DENY"));
        assertThat(result.determiningPolicyIds(), contains("deny"));
        assertThat(result.errors(), contains("e1"));
    }

    @Test
    void sidecarBadRequestBecomesValidationExceptionWithTheSidecarMessage() {
        answer("/v1/policy/parse", 400, "{\"error\":\"unexpected token\"}");

        AwsException error = assertThrows(AwsException.class, () -> client.parsePolicy("permit(", false));

        assertThat(error.getErrorCode(), equalTo("ValidationException"));
        assertThat(error.getMessage(), equalTo("unexpected token"));
    }

    @Test
    void otherSidecarFailuresBecomeInternalServerException() {
        answer("/v1/authorize", 500, "{\"error\":\"boom\"}");

        AwsException error = assertThrows(AwsException.class,
                () -> client.authorize(mapper.createObjectNode(), List.of(), Map.of()));

        assertThat(error.getErrorCode(), equalTo("InternalServerException"));
        assertThat(error.getHttpStatus(), equalTo(500));
    }

    @Test
    void unreachableSidecarBecomesInternalServerException() {
        CedarSidecarManager down = mock(CedarSidecarManager.class);
        when(down.ensureReady()).thenThrow(new IllegalStateException("speaks sidecar contract 2"));
        CedarSidecarClient offline = new CedarSidecarClient(down, mapper);

        AwsException error = assertThrows(AwsException.class, () -> offline.validateSchema("{}"));

        assertThat(error.getErrorCode(), equalTo("InternalServerException"));
        assertThat(error.getMessage(), equalTo("Failed to call Cedar sidecar for schema validation: speaks sidecar contract 2"));
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
