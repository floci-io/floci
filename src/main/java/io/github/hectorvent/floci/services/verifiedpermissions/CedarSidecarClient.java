package io.github.hectorvent.floci.services.verifiedpermissions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.verifiedpermissions.model.Policy;
import io.github.hectorvent.floci.services.verifiedpermissions.model.PolicyTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** HTTP client for the stateless Cedar 4 sidecar. */
@ApplicationScoped
public class CedarSidecarClient {
    private final CedarSidecarManager manager;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    @Inject
    public CedarSidecarClient(CedarSidecarManager manager, ObjectMapper mapper) {
        this(manager, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    CedarSidecarClient(CedarSidecarManager manager, ObjectMapper mapper, HttpClient httpClient) {
        this.manager = manager;
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    public void validateEntityType(String entityType) {
        ObjectNode body = mapper.createObjectNode().put("entityType", entityType);
        post("/v1/entity-type/validate", body, "entity type validation");
    }

    public void validateSchema(String schema) {
        ObjectNode body = mapper.createObjectNode().put("schema", schema);
        post("/v1/schema/validate", body, "schema validation");
    }

    public ParsedPolicy parsePolicy(String statement, boolean template) {
        ObjectNode body = mapper.createObjectNode();
        body.put("statement", statement);
        body.put("template", template);
        JsonNode response = post("/v1/policy/parse", body, "policy parsing");
        return new ParsedPolicy(response.path("effect").asText(), response.path("ast").deepCopy());
    }

    public void validatePolicy(String schema, String statement, boolean template) {
        ObjectNode body = mapper.createObjectNode();
        body.put("schema", schema);
        body.put("statement", statement);
        body.put("template", template);
        post("/v1/policy/validate", body, "policy validation");
    }

    public EvaluationResult authorize(JsonNode request, List<Policy> policies,
                                      Map<String, PolicyTemplate> templates) {
        ObjectNode body = mapper.createObjectNode();
        body.set("request", request.deepCopy());
        ArrayNode policyArray = body.putArray("policies");
        policies.forEach(policy -> policyArray.add(mapper.valueToTree(policy)));
        ObjectNode templateObject = body.putObject("templates");
        templates.forEach((id, template) -> templateObject.set(id, mapper.valueToTree(template)));
        JsonNode response = post("/v1/authorize", body, "authorization");
        return new EvaluationResult(
                response.path("decision").asText(),
                stringList(response.path("determiningPolicyIds")),
                stringList(response.path("errors")));
    }

    private List<String> stringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }

    private JsonNode post(String path, JsonNode body, String operation) {
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
            if (response.statusCode() == 400) {
                throw VerifiedPermissionsService.validation(responseBody.path("error").asText("Cedar " + operation + " failed."));
            }
            if (response.statusCode() != 200) {
                throw new AwsException("InternalServerException",
                        "Cedar sidecar " + operation + " failed with HTTP " + response.statusCode() + ".", 500);
            }
            return responseBody;
        } catch (AwsException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AwsException("InternalServerException", "Cedar sidecar " + operation + " was interrupted.", 500);
        } catch (Exception e) {
            throw new AwsException("InternalServerException",
                    "Failed to call Cedar sidecar for " + operation + ": " + safeMessage(e), 500);
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    public record ParsedPolicy(String effect, JsonNode ast) {}
    public record EvaluationResult(String decision, List<String> determiningPolicyIds, List<String> errors) {}
}
