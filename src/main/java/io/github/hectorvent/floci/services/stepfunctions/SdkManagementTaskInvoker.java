package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.services.ecs.EcsJsonHandler;
import io.github.hectorvent.floci.services.lambda.LambdaController;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerJsonHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import static io.github.hectorvent.floci.services.stepfunctions.AslExecutor.FailStateException;

/** AWS SDK management tasks delegated to the same handlers as the public APIs. */
@ApplicationScoped
public class SdkManagementTaskInvoker {

    private static final Set<String> ECS_ACTIONS = Set.of("updateService", "listServices", "describeServices");
    private static final Set<String> LAMBDA_ACTIONS = Set.of("listEventSourceMappings", "updateEventSourceMapping");
    private static final Set<String> DICTIONARIES = Set.of("options", "Options", "dockerLabels", "DockerLabels",
            "VersionIdsToStages", "Endpoints");
    private static final Set<String> TIMESTAMPS = Set.of("CreatedAt", "UpdatedAt", "StartedAt", "FinishedAt",
            "CreatedDate", "LastChangedDate", "LastAccessedDate", "LastRotatedDate", "NextRotationDate", "DeletedDate",
            "LastModified", "StartingPositionTimestamp", "StabilityStatusAt");

    private final EcsJsonHandler ecs;
    private final LambdaController lambda;
    private final SecretsManagerJsonHandler secrets;
    private final ServiceRegistry registry;
    private final ObjectMapper mapper;

    @Inject
    public SdkManagementTaskInvoker(EcsJsonHandler ecs, LambdaController lambda, SecretsManagerJsonHandler secrets,
                                     ServiceRegistry registry, ObjectMapper mapper) {
        this.ecs = ecs;
        this.lambda = lambda;
        this.secrets = secrets;
        this.registry = registry;
        this.mapper = mapper;
    }

    static boolean supports(StatesIntegration integration) {
        return integration.sdk() && integration.suffix().isEmpty() && switch (integration.service()) {
            case "ecs" -> ECS_ACTIONS.contains(integration.api());
            case "lambda" -> LAMBDA_ACTIONS.contains(integration.api());
            case "secretsmanager" -> "describeSecret".equals(integration.api());
            default -> false;
        };
    }

    JsonNode invoke(StatesIntegration integration, JsonNode input, String region) throws Exception {
        String prefix = switch (integration.service()) {
            case "ecs" -> "Ecs";
            case "lambda" -> "Lambda";
            default -> "SecretsManager";
        };
        try {
            if (!registry.isServiceEnabled(integration.service())) {
                throw new AwsException("ServiceNotAvailableException", "Service is disabled: " + integration.service(), 400);
            }
            if (input == null || !input.isObject()) {
                throw new AwsException("InvalidParameterValueException", "The task input must be a JSON object.", 400);
            }
            return RequestScopes.callAsChecked(null, region, () -> {
                try (Response response = dispatch(integration, input, region)) {
                    JsonNode body = response.getEntity() instanceof JsonNode json ? json.deepCopy()
                            : response.getEntity() instanceof String json ? mapper.readTree(json)
                            : mapper.valueToTree(response.getEntity());
                    if (response.getStatus() >= 400) {
                        String code = body.path("__type").asText("ServiceException");
                        code = code.substring(code.lastIndexOf('#') + 1);
                        throw new AwsException(code, body.path("message").asText(
                                body.path("Message").asText("Service request failed")), response.getStatus());
                    }
                    JsonNode result = "ecs".equals(integration.service()) ? recaseEcs(body, true) : body;
                    renderTimestamps(result);
                    return result;
                }
            });
        } catch (AwsException error) {
            throw new FailStateException(AslExecutor.sdkExceptionName(prefix, error.getErrorCode()), error.getMessage());
        }
    }

    private Response dispatch(StatesIntegration integration, JsonNode input, String region) {
        return switch (integration.service()) {
            case "ecs" -> ecs.handle(upperFirst(integration.api()), recaseEcs(input, false), region);
            case "secretsmanager" -> secrets.handle("DescribeSecret", input, region);
            case "lambda" -> switch (integration.api()) {
                case "listEventSourceMappings" -> {
                    if (input.has("Marker") || input.has("MaxItems")) {
                        throw new AwsException("InvalidParameterValueException",
                                "Pagination for Lambda mapping SDK tasks is not supported by Floci.", 400);
                    }
                    yield lambda.listEventSourceMappings(input.path("FunctionName").asText(null),
                            input.path("EventSourceArn").asText(null));
                }
                case "updateEventSourceMapping" -> {
                    String uuid = input.path("UUID").asText(null);
                    if (uuid == null || uuid.isBlank()) {
                        throw new AwsException("InvalidParameterValueException", "UUID is required.", 400);
                    }
                    ObjectNode request = input.deepCopy();
                    request.remove("UUID");
                    yield lambda.updateEventSourceMapping(uuid, request.toString());
                }
                default -> throw new IllegalStateException("Unsupported Lambda management action");
            };
            default -> throw new IllegalStateException("Unsupported SDK management service");
        };
    }

    private JsonNode recaseEcs(JsonNode node, boolean upper) {
        if (node instanceof ObjectNode object) {
            ObjectNode result = mapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                String wireKey = key.isEmpty() ? key : Character.toLowerCase(key.charAt(0)) + key.substring(1);
                result.set(upper ? upperFirst(key) : wireKey, DICTIONARIES.contains(wireKey)
                        ? field.getValue().deepCopy() : recaseEcs(field.getValue(), upper));
            }
            return result;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode result = mapper.createArrayNode();
            array.forEach(item -> result.add(recaseEcs(item, upper)));
            return result;
        }
        return node.deepCopy();
    }

    private void renderTimestamps(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (TIMESTAMPS.contains(field.getKey()) && field.getValue().isNumber()) {
                    object.put(field.getKey(), AslExecutor.sdkTimestamp(field.getValue().asDouble()));
                } else if (!DICTIONARIES.contains(field.getKey())) {
                    renderTimestamps(field.getValue());
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(this::renderTimestamps);
        }
    }

    private static String upperFirst(String key) {
        return key.isEmpty() ? key : Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }
}
