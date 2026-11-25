package io.floci.aws.services.verifiedpermissions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.verifiedpermissions.model.EntityIdentifier;
import io.floci.aws.services.verifiedpermissions.model.Policy;
import io.floci.aws.services.verifiedpermissions.model.PolicyTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AVP request adapter for the Cedar sidecar.
 *
 * Cedar parsing and authorization run outside the Floci process. This class only performs
 * AWS-request normalization that does not require the Cedar runtime and delegates evaluation
 * to {@link CedarSidecarClient}.
 */
@ApplicationScoped
public class CedarAuthorizationEvaluator {
    private final ObjectMapper objectMapper;
    private final CedarSidecarClient cedarClient;

    @Inject
    public CedarAuthorizationEvaluator(ObjectMapper objectMapper, CedarSidecarClient cedarClient) {
        this.objectMapper = objectMapper;
        this.cedarClient = cedarClient;
    }

    public ObjectNode appendTokenPrincipal(JsonNode entitiesDefinition, EntityIdentifier principal,
                                           JsonNode identityClaims, List<EntityIdentifier> parents) {
        ArrayNode cedarEntities;
        try {
            if (entitiesDefinition == null || entitiesDefinition.isNull()) {
                cedarEntities = objectMapper.createArrayNode();
            } else if (entitiesDefinition.has("cedarJson")) {
                JsonNode parsed = objectMapper.readTree(entitiesDefinition.path("cedarJson").asText());
                if (!parsed.isArray()) {
                    throw VerifiedPermissionsService.validation("entities.cedarJson must encode an array.");
                }
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
                identityClaims.fields().forEachRemaining(entry -> {
                    if (!isGroupClaim(entry.getKey()) && claimCanBeCedar(entry.getValue())) {
                        attrs.set(entry.getKey(), entry.getValue().deepCopy());
                    }
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

    public EvaluationResult evaluate(JsonNode request, String schema, List<Policy> storedPolicies,
                                     Map<String, PolicyTemplate> templates) {
        JsonNode withActions = withSchemaActions(request, schema);
        CedarSidecarClient.EvaluationResult result = cedarClient.authorize(withActions, storedPolicies, templates);
        return new EvaluationResult(result.decision(), result.determiningPolicyIds(), result.errors());
    }

    /**
     * AVP builds the action entities, and their action-group parents, from the store's schema. The sidecar
     * only sees the request, so without this `action in NS::Action::"Group"` never matches a member action.
     * Entities the caller sent for the same action win.
     */
    JsonNode withSchemaActions(JsonNode request, String schema) {
        ArrayNode actions = schemaActionEntities(schema);
        if (actions.isEmpty()) {
            return request;
        }
        ArrayNode cedarEntities = requestCedarEntities(request.get("entities"));
        Set<String> present = new HashSet<>();
        cedarEntities.forEach(entity -> present.add(uidKey(entity.path("uid"))));
        actions.forEach(action -> {
            if (!present.contains(uidKey(action.path("uid")))) {
                cedarEntities.add(action);
            }
        });
        ObjectNode out = request.deepCopy();
        out.putObject("entities").put("cedarJson", cedarEntities.toString());
        return out;
    }

    private ArrayNode requestCedarEntities(JsonNode entitiesDefinition) {
        if (entitiesDefinition == null || entitiesDefinition.isNull()) {
            return objectMapper.createArrayNode();
        }
        if (entitiesDefinition.has("cedarJson")) {
            try {
                JsonNode parsed = objectMapper.readTree(entitiesDefinition.path("cedarJson").asText());
                if (!parsed.isArray()) {
                    throw VerifiedPermissionsService.validation("entities.cedarJson must encode an array.");
                }
                return (ArrayNode) parsed.deepCopy();
            } catch (JsonProcessingException e) {
                throw VerifiedPermissionsService.validation("entities.cedarJson is invalid JSON.");
            }
        }
        if (entitiesDefinition.has("entityList") && entitiesDefinition.get("entityList").isArray()) {
            return toCedarEntities((ArrayNode) entitiesDefinition.get("entityList"));
        }
        throw VerifiedPermissionsService.validation("entities must contain cedarJson or entityList.");
    }

    private ArrayNode schemaActionEntities(String schema) {
        ArrayNode out = objectMapper.createArrayNode();
        if (schema == null || schema.isBlank()) {
            return out;
        }
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(schema);
        } catch (JsonProcessingException e) {
            return out;
        }
        if (parsed == null || !parsed.isObject()) {
            return out;
        }
        parsed.fields().forEachRemaining(namespace -> {
            JsonNode actions = namespace.getValue().path("actions");
            if (!actions.isObject()) {
                return;
            }
            String actionType = namespace.getKey().isEmpty() ? "Action" : namespace.getKey() + "::Action";
            actions.fields().forEachRemaining(action -> {
                ObjectNode entity = objectMapper.createObjectNode();
                entity.set("uid", cedarEntity(new EntityIdentifier(actionType, action.getKey())));
                entity.putObject("attrs");
                ArrayNode parents = entity.putArray("parents");
                JsonNode memberOf = action.getValue().path("memberOf");
                if (memberOf.isArray()) {
                    memberOf.forEach(parent -> {
                        String parentType = parent.hasNonNull("type")
                                ? qualifiedActionType(namespace.getKey(), parent.get("type").asText())
                                : actionType;
                        parents.add(cedarEntity(new EntityIdentifier(parentType, parent.path("id").asText())));
                    });
                }
                out.add(entity);
            });
        });
        return out;
    }

    /** A memberOf `type` names an action group in another namespace, e.g. "Other::Action"; a bare "Action" is this one. */
    private static String qualifiedActionType(String namespace, String type) {
        if (type.contains("::") || namespace.isEmpty()) {
            return type;
        }
        return namespace + "::" + type;
    }

    private static String uidKey(JsonNode uid) {
        JsonNode inner = uid.has("__entity") ? uid.get("__entity") : uid;
        return inner.path("type").asText() + "\u0000" + inner.path("id").asText();
    }

    private static boolean isGroupClaim(String name) {
        return "cognito:groups".equals(name) || "groups".equals(name);
    }

    private static boolean claimCanBeCedar(JsonNode value) {
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isTextual() || value.isBoolean() || value.isIntegralNumber()) {
            return true;
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                if (!claimCanBeCedar(item) || item.isContainerNode()) {
                    return false;
                }
            }
            return true;
        }
        if (value.isObject()) {
            Iterator<JsonNode> iterator = value.elements();
            while (iterator.hasNext()) {
                if (!claimCanBeCedar(iterator.next())) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private ArrayNode toCedarEntities(ArrayNode input) {
        ArrayNode output = objectMapper.createArrayNode();
        Map<String, ObjectNode> lastByUid = new LinkedHashMap<>();
        for (JsonNode entity : input) {
            if (!entity.isObject()) {
                throw VerifiedPermissionsService.validation("Each entityList item must be an object.");
            }
            EntityIdentifier id = identifier(entity.get("identifier"), "identifier");
            ObjectNode cedar = objectMapper.createObjectNode();
            cedar.set("uid", cedarEntity(id));
            ObjectNode attrs = cedar.putObject("attrs");
            JsonNode attributes = entity.get("attributes");
            if (attributes != null && !attributes.isNull()) {
                if (!attributes.isObject()) {
                    throw VerifiedPermissionsService.validation("entity attributes must be an object.");
                }
                attributes.fields().forEachRemaining(entry -> attrs.set(entry.getKey(), cedarAttribute(entry.getValue())));
            }
            ArrayNode parents = cedar.putArray("parents");
            JsonNode parentInput = entity.get("parents");
            if (parentInput != null && !parentInput.isNull()) {
                if (!parentInput.isArray()) {
                    throw VerifiedPermissionsService.validation("entity parents must be an array.");
                }
                parentInput.forEach(parent -> parents.add(cedarEntity(identifier(parent, "parent"))));
            }
            ObjectNode tags = cedar.putObject("tags");
            JsonNode tagInput = entity.get("tags");
            if (tagInput != null && !tagInput.isNull()) {
                if (!tagInput.isObject()) {
                    throw VerifiedPermissionsService.validation("entity tags must be an object.");
                }
                tagInput.fields().forEachRemaining(entry -> tags.set(entry.getKey(), cedarAttribute(entry.getValue())));
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
                if (!member.getValue().isArray()) {
                    throw VerifiedPermissionsService.validation("set must be an array.");
                }
                ArrayNode set = objectMapper.createArrayNode();
                member.getValue().forEach(value -> set.add(cedarAttribute(value)));
                yield set;
            }
            case "record" -> {
                if (!member.getValue().isObject()) {
                    throw VerifiedPermissionsService.validation("record must be an object.");
                }
                ObjectNode record = objectMapper.createObjectNode();
                member.getValue().fields().forEachRemaining(entry -> record.set(entry.getKey(), cedarAttribute(entry.getValue())));
                yield record;
            }
            default -> throw VerifiedPermissionsService.validation("Unsupported attribute union member: " + member.getKey());
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

    private static EntityIdentifier identifier(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            throw VerifiedPermissionsService.validation(field + " is required.");
        }
        String type = VerifiedPermissionsService.requiredText(node, node.has("entityType") ? "entityType" : "type");
        String id = VerifiedPermissionsService.requiredText(node, node.has("entityId") ? "entityId" : "id");
        return new EntityIdentifier(type, id);
    }

    public record EvaluationResult(String decision, List<String> determiningPolicyIds, List<String> errors) {}
}
