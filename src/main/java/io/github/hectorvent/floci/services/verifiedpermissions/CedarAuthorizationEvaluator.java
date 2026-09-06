package io.github.hectorvent.floci.services.verifiedpermissions;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.AuthorizationRequest;
import com.cedarpolicy.model.AuthorizationSuccessResponse;
import com.cedarpolicy.model.Context;
import com.cedarpolicy.model.entity.Entities;
import com.cedarpolicy.model.policy.LinkValue;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.policy.TemplateLink;
import com.cedarpolicy.value.CedarList;
import com.cedarpolicy.value.CedarMap;
import com.cedarpolicy.value.DateTime;
import com.cedarpolicy.value.Decimal;
import com.cedarpolicy.value.Duration;
import com.cedarpolicy.value.EntityTypeName;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.IpAddress;
import com.cedarpolicy.value.PrimBool;
import com.cedarpolicy.value.PrimLong;
import com.cedarpolicy.value.PrimString;
import com.cedarpolicy.value.Value;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.verifiedpermissions.model.EntityIdentifier;
import io.github.hectorvent.floci.services.verifiedpermissions.model.Policy;
import io.github.hectorvent.floci.services.verifiedpermissions.model.PolicyTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class CedarAuthorizationEvaluator {
    private final ObjectMapper objectMapper;
    private final BasicAuthorizationEngine engine = new BasicAuthorizationEngine();

    @Inject
    public CedarAuthorizationEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }


    public ObjectNode appendTokenPrincipal(JsonNode entitiesDefinition, EntityIdentifier principal,
                                           JsonNode identityClaims, List<EntityIdentifier> parents) {
        ArrayNode cedarEntities;
        try {
            if (entitiesDefinition == null || entitiesDefinition.isNull()) {
                cedarEntities = objectMapper.createArrayNode();
            } else if (entitiesDefinition.has("cedarJson")) {
                JsonNode parsed = objectMapper.readTree(entitiesDefinition.path("cedarJson").asText());
                if (!parsed.isArray()) throw VerifiedPermissionsService.validation("entities.cedarJson must encode an array.");
                cedarEntities = (ArrayNode) parsed.deepCopy();
            } else if (entitiesDefinition.has("entityList") && entitiesDefinition.get("entityList").isArray()) {
                cedarEntities = toCedarEntities((ArrayNode) entitiesDefinition.get("entityList"));
            } else {
                throw VerifiedPermissionsService.validation("entities must contain cedarJson or entityList.");
            }
            ObjectNode entity = objectMapper.createObjectNode();
            entity.set("uid", cedarEntity(principal));
            ObjectNode attrs = entity.putObject("attrs");
            if (identityClaims != null && identityClaims.isObject()) {
                identityClaims.fields().forEachRemaining(e -> {
                    if (!isGroupClaim(e.getKey()) && claimCanBeCedar(e.getValue())) attrs.set(e.getKey(), e.getValue().deepCopy());
                });
            }
            ArrayNode parentArray = entity.putArray("parents");
            parents.forEach(parent -> parentArray.add(cedarEntity(parent)));
            cedarEntities.add(entity);
            ObjectNode union = objectMapper.createObjectNode();
            union.put("cedarJson", cedarEntities.toString());
            return union;
        } catch (JsonProcessingException e) {
            throw VerifiedPermissionsService.validation("entities.cedarJson is invalid JSON.");
        }
    }

    private static boolean isGroupClaim(String name) {
        return "cognito:groups".equals(name) || "groups".equals(name);
    }

    private static boolean claimCanBeCedar(JsonNode value) {
        if (value == null || value.isNull()) return false;
        if (value.isTextual() || value.isBoolean() || value.isIntegralNumber()) return true;
        if (value.isArray()) {
            for (JsonNode item : value) if (!claimCanBeCedar(item) || item.isContainerNode()) return false;
            return true;
        }
        if (value.isObject()) {
            var it = value.elements();
            while (it.hasNext()) if (!claimCanBeCedar(it.next())) return false;
            return true;
        }
        return false;
    }

    public EvaluationResult evaluate(JsonNode request, List<Policy> storedPolicies,
                                     Map<String, PolicyTemplate> templates) {
        try {
            EntityUID principal = euid(request.get("principal"), "principal");
            EntityUID action = actionEuid(request.get("action"));
            EntityUID resource = euid(request.get("resource"), "resource");
            Entities entities = entities(request.get("entities"));
            Context context = context(request.get("context"));
            PolicySet policySet = policySet(storedPolicies, templates);
            AuthorizationRequest authorizationRequest = new AuthorizationRequest(principal, action, resource, context);
            com.cedarpolicy.model.AuthorizationResponse authorizationResponse = engine.isAuthorized(authorizationRequest, policySet, entities);
            AuthorizationSuccessResponse response = authorizationResponse.success.orElseThrow(() ->
                    VerifiedPermissionsService.validation("Cedar authorization failed: " + authorizationResponse.errors.orElse(com.google.common.collect.ImmutableList.of())));
            List<String> errors = response.getErrors().stream().map(Object::toString).toList();
            return new EvaluationResult(response.isAllowed() ? "ALLOW" : "DENY",
                    response.getReason().stream().sorted().toList(), errors);
        } catch (io.github.hectorvent.floci.core.common.AwsException e) {
            throw e;
        } catch (Exception e) {
            throw VerifiedPermissionsService.validation("Authorization request isn't valid Cedar input: " + safeMessage(e));
        }
    }

    private PolicySet policySet(List<Policy> storedPolicies, Map<String, PolicyTemplate> templates) {
        Set<com.cedarpolicy.model.policy.Policy> staticPolicies = new LinkedHashSet<>();
        Set<com.cedarpolicy.model.policy.Policy> cedarTemplates = new LinkedHashSet<>();
        List<TemplateLink> links = new ArrayList<>();
        for (PolicyTemplate template : templates.values()) {
            cedarTemplates.add(new com.cedarpolicy.model.policy.Policy(template.statement(), template.policyTemplateId()));
        }
        for (Policy policy : storedPolicies) {
            if ("STATIC".equals(policy.policyType())) {
                staticPolicies.add(new com.cedarpolicy.model.policy.Policy(policy.statement(), policy.policyId()));
                continue;
            }
            List<LinkValue> values = new ArrayList<>();
            if (policy.principal() != null) values.add(new LinkValue("?principal", euid(policy.principal())));
            if (policy.resource() != null) values.add(new LinkValue("?resource", euid(policy.resource())));
            links.add(new TemplateLink(policy.policyTemplateId(), policy.policyId(), values));
        }
        return new PolicySet(staticPolicies, cedarTemplates, links);
    }

    private Entities entities(JsonNode definition) throws JsonProcessingException {
        if (definition == null || definition.isNull()) return new Entities();
        if (!definition.isObject() || definition.size() != 1) {
            throw VerifiedPermissionsService.validation("entities must contain exactly one union member.");
        }
        if (definition.has("cedarJson")) {
            if (!definition.get("cedarJson").isTextual()) throw VerifiedPermissionsService.validation("entities.cedarJson must be a string.");
            return Entities.parse(definition.get("cedarJson").asText());
        }
        if (definition.has("entityList")) {
            JsonNode entityList = definition.get("entityList");
            if (!entityList.isArray()) throw VerifiedPermissionsService.validation("entities.entityList must be an array.");
            return Entities.parse(toCedarEntities((ArrayNode) entityList).toString());
        }
        throw VerifiedPermissionsService.validation("entities must contain cedarJson or entityList.");
    }

    private ArrayNode toCedarEntities(ArrayNode input) {
        ArrayNode output = objectMapper.createArrayNode();
        Map<String, ObjectNode> lastByUid = new LinkedHashMap<>();
        for (JsonNode entity : input) {
            if (!entity.isObject()) throw VerifiedPermissionsService.validation("Each entityList item must be an object.");
            EntityIdentifier id = identifier(entity.get("identifier"), "identifier");
            ObjectNode cedar = objectMapper.createObjectNode();
            cedar.set("uid", cedarEntity(id));
            ObjectNode attrs = cedar.putObject("attrs");
            JsonNode attributes = entity.get("attributes");
            if (attributes != null && !attributes.isNull()) {
                if (!attributes.isObject()) throw VerifiedPermissionsService.validation("entity attributes must be an object.");
                attributes.fields().forEachRemaining(e -> attrs.set(e.getKey(), cedarAttribute(e.getValue())));
            }
            ArrayNode parents = cedar.putArray("parents");
            JsonNode parentInput = entity.get("parents");
            if (parentInput != null && !parentInput.isNull()) {
                if (!parentInput.isArray()) throw VerifiedPermissionsService.validation("entity parents must be an array.");
                parentInput.forEach(p -> parents.add(cedarEntity(identifier(p, "parent"))));
            }
            ObjectNode tags = cedar.putObject("tags");
            JsonNode tagInput = entity.get("tags");
            if (tagInput != null && !tagInput.isNull()) {
                if (!tagInput.isObject()) throw VerifiedPermissionsService.validation("entity tags must be an object.");
                tagInput.fields().forEachRemaining(e -> tags.set(e.getKey(), cedarAttribute(e.getValue())));
            }
            lastByUid.put(id.entityType() + "\u0000" + id.entityId(), cedar);
        }
        lastByUid.values().forEach(output::add);
        return output;
    }

    private JsonNode cedarAttribute(JsonNode union) {
        if (union == null || !union.isObject() || union.size() != 1) {
            throw VerifiedPermissionsService.validation("Attribute values must contain exactly one union member.");
        }
        Map.Entry<String, JsonNode> member = union.fields().next();
        return switch (member.getKey()) {
            case "boolean" -> objectMapper.getNodeFactory().booleanNode(member.getValue().asBoolean());
            case "long" -> objectMapper.getNodeFactory().numberNode(member.getValue().asLong());
            case "string" -> objectMapper.getNodeFactory().textNode(member.getValue().asText());
            case "entityIdentifier" -> explicitEntity(identifier(member.getValue(), "entityIdentifier"));
            case "ipaddr" -> extension("ip", member.getValue().asText());
            case "decimal" -> extension("decimal", member.getValue().asText());
            case "datetime" -> extension("datetime", member.getValue().asText());
            case "duration" -> extension("duration", member.getValue().asText());
            case "set" -> {
                if (!member.getValue().isArray()) throw VerifiedPermissionsService.validation("set must be an array.");
                ArrayNode set = objectMapper.createArrayNode();
                member.getValue().forEach(v -> set.add(cedarAttribute(v)));
                yield set;
            }
            case "record" -> {
                if (!member.getValue().isObject()) throw VerifiedPermissionsService.validation("record must be an object.");
                ObjectNode record = objectMapper.createObjectNode();
                member.getValue().fields().forEachRemaining(e -> record.set(e.getKey(), cedarAttribute(e.getValue())));
                yield record;
            }
            default -> throw VerifiedPermissionsService.validation("Unsupported attribute union member: " + member.getKey());
        };
    }

    private Context context(JsonNode definition) throws JsonProcessingException {
        if (definition == null || definition.isNull()) return new Context();
        if (!definition.isObject() || definition.size() != 1) {
            throw VerifiedPermissionsService.validation("context must contain exactly one union member.");
        }
        JsonNode raw;
        if (definition.has("cedarJson")) {
            if (!definition.get("cedarJson").isTextual()) throw VerifiedPermissionsService.validation("context.cedarJson must be a string.");
            raw = objectMapper.readTree(definition.get("cedarJson").asText());
            if (!raw.isObject()) throw VerifiedPermissionsService.validation("context.cedarJson must encode an object.");
            return new Context(valueMapFromCedarJson(raw));
        }
        if (definition.has("contextMap")) {
            raw = definition.get("contextMap");
            if (!raw.isObject()) throw VerifiedPermissionsService.validation("context.contextMap must be an object.");
            Map<String, Value> values = new LinkedHashMap<>();
            raw.fields().forEachRemaining(e -> values.put(e.getKey(), valueFromUnion(e.getValue())));
            return new Context(values);
        }
        throw VerifiedPermissionsService.validation("context must contain cedarJson or contextMap.");
    }

    private Map<String, Value> valueMapFromCedarJson(JsonNode object) {
        Map<String, Value> result = new LinkedHashMap<>();
        object.fields().forEachRemaining(e -> result.put(e.getKey(), valueFromCedarJson(e.getValue())));
        return result;
    }

    private Value valueFromCedarJson(JsonNode value) {
        if (value.isBoolean()) return new PrimBool(value.asBoolean());
        if (value.isIntegralNumber()) return new PrimLong(value.asLong());
        if (value.isTextual()) return new PrimString(value.asText());
        if (value.isArray()) {
            List<Value> values = new ArrayList<>();
            value.forEach(v -> values.add(valueFromCedarJson(v)));
            return new CedarList(values);
        }
        if (value.isObject()) {
            if (value.has("__entity")) return euid(value.get("__entity"), "__entity");
            if (value.has("__extn")) {
                JsonNode ext = value.get("__extn");
                return extensionValue(ext.path("fn").asText(), ext.path("arg").asText());
            }
            return new CedarMap(valueMapFromCedarJson(value));
        }
        throw VerifiedPermissionsService.validation("Unsupported Cedar JSON value.");
    }

    private Value valueFromUnion(JsonNode union) {
        if (union == null || !union.isObject() || union.size() != 1) {
            throw VerifiedPermissionsService.validation("Context attribute values must contain exactly one union member.");
        }
        Map.Entry<String, JsonNode> member = union.fields().next();
        return switch (member.getKey()) {
            case "boolean" -> new PrimBool(member.getValue().asBoolean());
            case "long" -> new PrimLong(member.getValue().asLong());
            case "string" -> new PrimString(member.getValue().asText());
            case "entityIdentifier" -> euid(member.getValue(), "entityIdentifier");
            case "ipaddr" -> new IpAddress(member.getValue().asText());
            case "decimal" -> new Decimal(member.getValue().asText());
            case "datetime" -> new DateTime(member.getValue().asText());
            case "duration" -> new Duration(member.getValue().asText());
            case "set" -> {
                List<Value> list = new ArrayList<>();
                if (!member.getValue().isArray()) throw VerifiedPermissionsService.validation("set must be an array.");
                member.getValue().forEach(v -> list.add(valueFromUnion(v)));
                yield new CedarList(list);
            }
            case "record" -> {
                if (!member.getValue().isObject()) throw VerifiedPermissionsService.validation("record must be an object.");
                Map<String, Value> map = new LinkedHashMap<>();
                member.getValue().fields().forEachRemaining(e -> map.put(e.getKey(), valueFromUnion(e.getValue())));
                yield new CedarMap(map);
            }
            default -> throw VerifiedPermissionsService.validation("Unsupported attribute union member: " + member.getKey());
        };
    }

    private static Value extensionValue(String function, String arg) {
        return switch (function) {
            case "ip" -> new IpAddress(arg);
            case "decimal" -> new Decimal(arg);
            case "datetime" -> new DateTime(arg);
            case "duration" -> new Duration(arg);
            default -> throw VerifiedPermissionsService.validation("Unsupported Cedar extension: " + function);
        };
    }

    private ObjectNode explicitEntity(EntityIdentifier id) {
        ObjectNode out = objectMapper.createObjectNode();
        out.set("__entity", cedarEntity(id));
        return out;
    }

    private ObjectNode cedarEntity(EntityIdentifier id) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", id.entityType());
        node.put("id", id.entityId());
        return node;
    }

    private ObjectNode extension(String fn, String arg) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode ext = root.putObject("__extn");
        ext.put("fn", fn);
        ext.put("arg", arg);
        return root;
    }

    private static EntityUID actionEuid(JsonNode node) {
        if (node == null || !node.isObject()) throw VerifiedPermissionsService.validation("action is required.");
        EntityIdentifier id = new EntityIdentifier(
                VerifiedPermissionsService.requiredText(node, "actionType"),
                VerifiedPermissionsService.requiredText(node, "actionId"));
        return euid(id);
    }

    private static EntityUID euid(JsonNode node, String field) {
        EntityIdentifier id = identifier(node, field);
        return euid(id);
    }

    private static EntityUID euid(EntityIdentifier id) {
        EntityTypeName type = EntityTypeName.parse(id.entityType())
                .orElseThrow(() -> VerifiedPermissionsService.validation("Invalid Cedar entity type: " + id.entityType()));
        return type.of(id.entityId());
    }

    private static EntityIdentifier identifier(JsonNode node, String field) {
        if (node == null || !node.isObject()) throw VerifiedPermissionsService.validation(field + " is required.");
        String type = VerifiedPermissionsService.requiredText(node, node.has("entityType") ? "entityType" : "type");
        String id = VerifiedPermissionsService.requiredText(node, node.has("entityId") ? "entityId" : "id");
        return new EntityIdentifier(type, id);
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    public record EvaluationResult(String decision, List<String> determiningPolicyIds, List<String> errors) {}
}
