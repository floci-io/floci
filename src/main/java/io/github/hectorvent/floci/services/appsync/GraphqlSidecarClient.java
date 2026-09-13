package io.github.hectorvent.floci.services.appsync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncTransportException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the stateless GraphQL sidecar (issue #2917). Every call sends the full SDL
 * plus the request; the sidecar caches and registers nothing across calls.
 *
 * <p>This client carries no AppSync-specific behavior of its own. {@link #plan} and the
 * {@code denyFields} parameter of {@link #execute} are how {@code @aws_auth}/IAM field
 * authorization gets applied without the sidecar knowing what those directives mean:
 * {@link io.github.hectorvent.floci.services.appsync.graphql.auth.SidecarFieldAuthorizationPlanner}
 * is where that AWS-specific interpretation actually lives. AWS custom-scalar semantics
 * (AWSDateTime, AWSJSON, etc.) run inside the sidecar itself, since scalar coercion happens
 * inline during graphql-java's own execution with no clean seam to apply it out here.
 */
@ApplicationScoped
public class GraphqlSidecarClient {
    private final GraphqlSidecarManager manager;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    @Inject
    public GraphqlSidecarClient(GraphqlSidecarManager manager, ObjectMapper mapper) {
        this(manager, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    GraphqlSidecarClient(GraphqlSidecarManager manager, ObjectMapper mapper, HttpClient httpClient) {
        this.manager = manager;
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    /** One structured problem in an SDL: mirrors what {@code StartSchemaCreation}'s codeErrors need. */
    public record SchemaIssue(String category, String message, int line, int column) {
    }

    /** Thrown by {@link #validateSchema} with the sidecar's structured per-problem detail, not just a flat message. */
    public static final class SchemaValidationException extends AppSyncTransportException {
        private final List<SchemaIssue> issues;

        public SchemaValidationException(String message, List<SchemaIssue> issues) {
            super(400, "BadRequestException", message);
            this.issues = issues;
        }

        public List<SchemaIssue> issues() {
            return issues;
        }
    }

    /** Validates/compiles an SDL document. Throws {@link SchemaValidationException} if the schema is invalid. */
    public void validateSchema(String sdl) {
        ObjectNode body = mapper.createObjectNode().put("sdl", sdl);
        SidecarResponse response = call("/v1/schema/validate", body, "schema validation");
        if (response.status() == 400) {
            throw new SchemaValidationException(
                    response.body().path("error").asText("Schema validation failed."),
                    issuesFrom(response.body().path("issues")));
        }
        requireSuccess(response, "schema validation");
    }

    private List<SchemaIssue> issuesFrom(JsonNode array) {
        List<SchemaIssue> issues = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode node : array) {
                issues.add(new SchemaIssue(node.path("category").asText("VALIDATION_ERROR"), node.path("message").asText(),
                        node.path("line").asInt(0), node.path("column").asInt(0)));
            }
        }
        return issues;
    }

    /** One directive application: {@code args} is whatever the directive's arguments deserialize to (usually a Map). */
    public record PlannedDirective(String name, Object args) {
    }

    /** {@code typeDirectives} is the containing object type's own applied directives, from the sidecar's query planner. */
    public record PlannedField(String typeName, String fieldName, List<PlannedDirective> directives,
                                List<PlannedDirective> typeDirectives) {
    }

    /** {@code errorType}/{@code message} are supplied by the caller; this sidecar has no opinion on them. */
    public record DenyField(String typeName, String fieldName, String errorType, String message) {
    }

    /**
     * {@code operationType} is {@code QUERY}/{@code MUTATION}/{@code SUBSCRIPTION}, or {@code
     * null} when the query didn't parse or named an operation that doesn't exist, in which case
     * {@code fields} is empty too. That's deliberate (see {@link #plan}): a bad query is {@code
     * /v1/execute}'s problem to report correctly, not planning's to reject early.
     */
    public record PlanResult(String operationType, List<PlannedField> fields) {
    }

    /**
     * Learns the query's operation type and every {@code (typeName, fieldName)} coordinate it
     * would visit, with directives on each, in one call, since both come from parsing the same
     * query once. Never throws for a malformed query (see {@link PlanResult}).
     */
    public PlanResult plan(String sdl, String query, String operationName) {
        ObjectNode body = mapper.createObjectNode();
        body.put("sdl", sdl);
        body.put("query", query);
        if (operationName != null && !operationName.isBlank()) {
            body.put("operationName", operationName);
        }
        JsonNode response = requireSuccess(call("/v1/plan", body, "query planning"), "query planning");
        String operationType = response.path("operationType").isTextual()
                ? response.path("operationType").asText() : null;
        List<PlannedField> fields = new ArrayList<>();
        for (JsonNode node : response.path("fields")) {
            fields.add(new PlannedField(
                    node.path("typeName").asText(),
                    node.path("fieldName").asText(),
                    directivesFrom(node.path("directives")),
                    directivesFrom(node.path("typeDirectives"))));
        }
        return new PlanResult(operationType, fields);
    }

    @SuppressWarnings("unchecked")
    private List<PlannedDirective> directivesFrom(JsonNode array) {
        List<PlannedDirective> directives = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode node : array) {
                directives.add(new PlannedDirective(node.path("name").asText(),
                        mapper.convertValue(node.path("args"), Map.class)));
            }
        }
        return directives;
    }

    /** Executes a query against the given SDL. {@code denyFields} coordinates come back null with the given error. */
    public Map<String, Object> execute(String sdl, String query, Map<String, Object> variables, String operationName,
                                       List<DenyField> denyFields) {
        ObjectNode body = mapper.createObjectNode();
        body.put("sdl", sdl);
        body.put("query", query);
        body.set("variables", mapper.valueToTree(variables == null ? Map.of() : variables));
        if (operationName != null && !operationName.isBlank()) {
            body.put("operationName", operationName);
        }
        if (denyFields != null && !denyFields.isEmpty()) {
            ArrayNode array = body.putArray("denyFields");
            for (DenyField deny : denyFields) {
                ObjectNode node = array.addObject();
                node.put("typeName", deny.typeName());
                node.put("fieldName", deny.fieldName());
                node.put("errorType", deny.errorType());
                node.put("message", deny.message());
            }
        }
        JsonNode response = requireSuccess(call("/v1/execute", body, "query execution"), "query execution");
        return mapper.convertValue(response, Map.class);
    }

    private record SidecarResponse(int status, JsonNode body) {
    }

    /** Raw call: returns whatever status/body the sidecar sent, without deciding what's an error. */
    private SidecarResponse call(String path, JsonNode body, String operation) {
        try {
            String baseUrl = manager.ensureReady();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode responseBody = response.body() == null || response.body().isBlank()
                    ? mapper.createObjectNode() : mapper.readTree(response.body());
            return new SidecarResponse(response.statusCode(), responseBody);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppSyncTransportException(500, "InternalFailureException",
                    "GraphQL sidecar " + operation + " was interrupted.");
        } catch (Exception e) {
            throw new AppSyncTransportException(500, "InternalFailureException",
                    "Failed to call GraphQL sidecar for " + operation + ": " + safeMessage(e));
        }
    }

    /** For callers that don't need to special-case a 400 body shape: any non-200 becomes a transport exception. */
    private JsonNode requireSuccess(SidecarResponse response, String operation) {
        if (response.status() == 400) {
            throw new AppSyncTransportException(400, "BadRequestException",
                    response.body().path("error").asText("GraphQL " + operation + " failed."));
        }
        if (response.status() != 200) {
            throw new AppSyncTransportException(500, "InternalFailureException",
                    "GraphQL sidecar " + operation + " failed with HTTP " + response.status() + ".");
        }
        return response.body();
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
