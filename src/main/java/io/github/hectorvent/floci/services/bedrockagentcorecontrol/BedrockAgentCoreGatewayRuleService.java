package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class BedrockAgentCoreGatewayRuleService {

    private final StorageBackend<String, ObjectNode> storage;
    private final BedrockAgentCoreGatewayService gatewayService;

    @Inject
    public BedrockAgentCoreGatewayRuleService(StorageFactory storageFactory,
                                              BedrockAgentCoreGatewayService gatewayService) {
        this(storageFactory.create("bedrockagentcore", "bedrock-agentcore-gateway-rules.json",
                new TypeReference<Map<String, ObjectNode>>() {}), gatewayService);
    }

    BedrockAgentCoreGatewayRuleService(StorageBackend<String, ObjectNode> storage,
                                       BedrockAgentCoreGatewayService gatewayService) {
        this.storage = storage;
        this.gatewayService = gatewayService;
    }

    public ObjectNode create(String gatewayId, ObjectNode request, String region) {
        var gateway = gatewayService.get(gatewayId, region);
        String clientToken = request.hasNonNull("clientToken") ? request.get("clientToken").asText() : null;
        if (clientToken != null) {
            if (clientToken.length() < 33 || clientToken.length() > 256
                    || !clientToken.matches("[a-zA-Z0-9](-*[a-zA-Z0-9]){0,256}")) {
                throw new AwsException("ValidationException",
                        "clientToken does not satisfy length or pattern constraints", 400);
            }
            ObjectNode existing = storage.scan(k -> k.startsWith(prefix(region, gatewayId))).stream()
                    .filter(rule -> clientToken.equals(rule.path("clientToken").asText(null)))
                    .findFirst()
                    .orElse(null);
            if (existing != null) {
                return existing.deepCopy();
            }
        }
        JsonNode actions = request.get("actions");
        if (actions == null || !actions.isArray() || actions.isEmpty() || actions.size() > 2) {
            throw new AwsException("ValidationException", "actions must contain between 1 and 2 items", 400);
        }
        validateActions(actions);
        if (!request.hasNonNull("priority") || !request.get("priority").canConvertToInt()) {
            throw new AwsException("ValidationException", "priority is required", 400);
        }
        int priority = request.get("priority").asInt();
        if (priority < 1 || priority > 1_000_000) {
            throw new AwsException("ValidationException", "priority must be between 1 and 1000000", 400);
        }
        JsonNode conditions = request.get("conditions");
        if (conditions != null && (!conditions.isArray() || conditions.size() > 2)) {
            throw new AwsException("ValidationException", "conditions must contain at most 2 items", 400);
        }
        if (conditions != null) {
            validateConditions(conditions);
        }
        String ruleId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        ObjectNode rule = request.deepCopy();
        rule.put("ruleId", ruleId);
        rule.put("gatewayArn", gatewayService.gatewayArn(gateway, region));
        rule.put("status", "ACTIVE");
        rule.put("createdAt", now.toString());
        rule.put("updatedAt", now.toString());
        storage.put(key(region, gatewayId, ruleId), rule);
        return rule.deepCopy();
    }

    public ObjectNode get(String gatewayId, String ruleId, String region) {
        gatewayService.get(gatewayId, region);
        if (ruleId == null || !ruleId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new AwsException("ValidationException", "ruleId does not satisfy the required UUID pattern", 400);
        }
        return storage.get(key(region, gatewayId, ruleId))
                .map(ObjectNode::deepCopy)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Gateway rule not found: " + ruleId, 404));
    }

    public PaginatedResult<ObjectNode> list(String gatewayId, Integer maxResults,
                                             String nextToken, String region) {
        gatewayService.get(gatewayId, region);
        List<ObjectNode> rules = storage.scan(k -> k.startsWith(prefix(region, gatewayId))).stream()
                .map(ObjectNode::deepCopy)
                .toList();
        return Pagination.paginate(rules, node -> node.path("ruleId").asText(),
                maxResults, nextToken, 100, 100, "ValidationException");
    }

    public ObjectNode update(String gatewayId, String ruleId, ObjectNode request, String region) {
        ObjectNode rule = get(gatewayId, ruleId, region);
        if (request.has("actions")) {
            JsonNode actions = request.get("actions");
            if (actions == null || !actions.isArray() || actions.isEmpty() || actions.size() > 2) {
                throw new AwsException("ValidationException", "actions must contain between 1 and 2 items", 400);
            }
            validateActions(actions);
            rule.set("actions", actions.deepCopy());
        }
        if (request.has("conditions")) {
            JsonNode conditions = request.get("conditions");
            if (conditions == null || !conditions.isArray() || conditions.size() > 2) {
                throw new AwsException("ValidationException", "conditions must contain at most 2 items", 400);
            }
            validateConditions(conditions);
            rule.set("conditions", conditions.deepCopy());
        }
        if (request.hasNonNull("description")) {
            String description = request.get("description").asText();
            if (description.length() < 1 || description.length() > 256) {
                throw new AwsException("ValidationException", "description must be between 1 and 256 characters", 400);
            }
            rule.put("description", description);
        }
        if (request.has("priority")) {
            if (!request.hasNonNull("priority") || !request.get("priority").canConvertToInt()) {
                throw new AwsException("ValidationException", "priority must be an integer", 400);
            }
            int priority = request.get("priority").asInt();
            if (priority < 1 || priority > 1_000_000) {
                throw new AwsException("ValidationException", "priority must be between 1 and 1000000", 400);
            }
            rule.put("priority", priority);
        }
        rule.put("status", "ACTIVE");
        rule.put("updatedAt", Instant.now().toString());
        storage.put(key(region, gatewayId, ruleId), rule);
        return rule.deepCopy();
    }

    public ObjectNode delete(String gatewayId, String ruleId, String region) {
        ObjectNode rule = get(gatewayId, ruleId, region);
        storage.delete(key(region, gatewayId, ruleId));
        ObjectNode response = rule.objectNode();
        response.put("ruleId", ruleId);
        response.put("status", "DELETING");
        return response;
    }

    private static void validateActions(JsonNode actions) {
        for (JsonNode action : actions) {
            if (!action.isObject() || action.size() != 1) {
                throw new AwsException("ValidationException", "each action must contain exactly one union member", 400);
            }
            if (action.has("routeToTarget")) {
                validateRouteToTarget(action.get("routeToTarget"));
            } else if (action.has("configurationBundle")) {
                validateConfigurationBundleAction(action.get("configurationBundle"));
            } else {
                throw new AwsException("ValidationException", "action union member is invalid", 400);
            }
        }
    }

    private static void validateRouteToTarget(JsonNode route) {
        if (route == null || !route.isObject() || route.size() != 1) {
            throw new AwsException("ValidationException", "routeToTarget must contain exactly one union member", 400);
        }
        if (route.has("staticRoute")) {
            String targetName = requiredText(route.get("staticRoute"), "targetName", "staticRoute.targetName");
            if (!targetName.matches("([0-9a-zA-Z][-]?){1,100}")) {
                throw new AwsException("ValidationException", "staticRoute.targetName is invalid", 400);
            }
            return;
        }
        if (route.has("weightedRoute")) {
            validateTargetTrafficSplit(route.path("weightedRoute").get("trafficSplit"));
            return;
        }
        throw new AwsException("ValidationException", "routeToTarget union member is invalid", 400);
    }

    private static void validateTargetTrafficSplit(JsonNode split) {
        if (split == null || !split.isArray() || split.size() != 2) {
            throw new AwsException("ValidationException", "weightedRoute.trafficSplit must contain exactly 2 items", 400);
        }
        for (JsonNode entry : split) {
            String name = requiredText(entry, "name", "trafficSplit.name");
            String targetName = requiredText(entry, "targetName", "trafficSplit.targetName");
            if (!name.matches("[a-zA-Z0-9]([a-zA-Z0-9-]{0,62}[a-zA-Z0-9])?")) {
                throw new AwsException("ValidationException", "trafficSplit.name is invalid", 400);
            }
            if (!targetName.matches("([0-9a-zA-Z][-]?){1,100}")) {
                throw new AwsException("ValidationException", "trafficSplit.targetName is invalid", 400);
            }
            int weight = requiredInt(entry, "weight", "trafficSplit.weight");
            if (weight < 1 || weight > 99) {
                throw new AwsException("ValidationException", "trafficSplit.weight must be between 1 and 99", 400);
            }
        }
    }

    private static void validateConfigurationBundleAction(JsonNode action) {
        if (action == null || !action.isObject() || action.size() != 1) {
            throw new AwsException("ValidationException", "configurationBundle must contain exactly one union member", 400);
        }
        if (action.has("staticOverride")) {
            validateBundleReference(action.get("staticOverride"), "staticOverride");
            return;
        }
        if (action.has("weightedOverride")) {
            JsonNode split = action.path("weightedOverride").get("trafficSplit");
            if (split == null || !split.isArray() || split.size() != 2) {
                throw new AwsException("ValidationException", "weightedOverride.trafficSplit must contain exactly 2 items", 400);
            }
            int total = 0;
            for (JsonNode entry : split) {
                String name = requiredText(entry, "name", "trafficSplit.name");
                if (!name.matches("[a-zA-Z0-9]([a-zA-Z0-9-]{0,62}[a-zA-Z0-9])?")) {
                    throw new AwsException("ValidationException", "trafficSplit.name is invalid", 400);
                }
                int weight = requiredInt(entry, "weight", "trafficSplit.weight");
                if (weight < 1 || weight > 99) {
                    throw new AwsException("ValidationException", "trafficSplit.weight must be between 1 and 99", 400);
                }
                validateBundleReference(entry.get("configurationBundle"), "trafficSplit.configurationBundle");
                total += weight;
            }
            if (total != 100) {
                throw new AwsException("ValidationException", "trafficSplit weights must sum to 100", 400);
            }
            return;
        }
        throw new AwsException("ValidationException", "configurationBundle union member is invalid", 400);
    }

    private static void validateBundleReference(JsonNode reference, String field) {
        if (reference == null || !reference.isObject()) {
            throw new AwsException("ValidationException", field + " is required", 400);
        }
        String arn = requiredText(reference, "bundleArn", field + ".bundleArn");
        String version = requiredText(reference, "bundleVersion", field + ".bundleVersion");
        if (!arn.matches("arn:aws[a-zA-Z-]*:bedrock-agentcore:[a-z0-9-]+:[0-9]{12}:configuration-bundle/[a-zA-Z][a-zA-Z0-9-_]{0,99}-[a-zA-Z0-9]{10}")) {
            throw new AwsException("ValidationException", field + ".bundleArn is invalid", 400);
        }
        if (!version.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw new AwsException("ValidationException", field + ".bundleVersion is invalid", 400);
        }
    }

    private static void validateConditions(JsonNode conditions) {
        for (JsonNode condition : conditions) {
            if (!condition.isObject() || condition.size() != 1) {
                throw new AwsException("ValidationException", "each condition must contain exactly one union member", 400);
            }
            if (condition.has("matchPaths")) {
                JsonNode anyOf = condition.path("matchPaths").get("anyOf");
                if (anyOf == null || !anyOf.isArray() || anyOf.isEmpty() || anyOf.size() > 10) {
                    throw new AwsException("ValidationException", "matchPaths.anyOf must contain between 1 and 10 items", 400);
                }
                for (JsonNode path : anyOf) {
                    if (!path.isTextual() || path.asText().length() > 512
                            || !path.asText().matches("/[\\w\\-.]+/\\*")) {
                        throw new AwsException("ValidationException", "matchPaths.anyOf contains an invalid path", 400);
                    }
                }
            } else if (condition.has("matchPrincipals")) {
                validatePrincipals(condition.get("matchPrincipals"));
            } else {
                throw new AwsException("ValidationException", "condition union member is invalid", 400);
            }
        }
    }

    private static void validatePrincipals(JsonNode matchPrincipals) {
        JsonNode anyOf = matchPrincipals == null ? null : matchPrincipals.get("anyOf");
        if (anyOf == null || !anyOf.isArray() || anyOf.isEmpty() || anyOf.size() > 100) {
            throw new AwsException("ValidationException", "matchPrincipals.anyOf must contain between 1 and 100 items", 400);
        }
        for (JsonNode entry : anyOf) {
            if (!entry.isObject() || entry.size() != 1 || !entry.has("iamPrincipal")) {
                throw new AwsException("ValidationException", "principal entry must contain iamPrincipal", 400);
            }
            JsonNode principal = entry.get("iamPrincipal");
            String arn = requiredText(principal, "arn", "iamPrincipal.arn");
            if (arn.length() > 2048 || !arn.matches("(arn:aws[a-zA-Z-]*:iam::(\\d{12}|\\*):(user|role)/[\\w+=,.@*?/-]+|arn:aws[a-zA-Z-]*:sts::(\\d{12}|\\*):assumed-role/[\\w+=,.@*?/-]+)")) {
                throw new AwsException("ValidationException", "iamPrincipal.arn is invalid", 400);
            }
            if (principal.hasNonNull("operator")) {
                String operator = principal.get("operator").asText();
                if (!"StringEquals".equals(operator) && !"StringLike".equals(operator)) {
                    throw new AwsException("ValidationException", "iamPrincipal.operator is invalid", 400);
                }
            }
        }
    }

    private static String requiredText(JsonNode node, String field, String displayName) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.asText().isEmpty()) {
            throw new AwsException("ValidationException", displayName + " is required", 400);
        }
        return value.asText();
    }

    private static int requiredInt(JsonNode node, String field, String displayName) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new AwsException("ValidationException", displayName + " is required", 400);
        }
        return value.asInt();
    }

    private static String key(String region, String gatewayId, String ruleId) {
        return prefix(region, gatewayId) + ruleId;
    }

    private static String prefix(String region, String gatewayId) {
        return "gateway-rule:" + region + ":" + gatewayId + ":";
    }
}
