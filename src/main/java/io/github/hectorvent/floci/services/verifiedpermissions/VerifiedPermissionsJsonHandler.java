package io.github.hectorvent.floci.services.verifiedpermissions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.services.verifiedpermissions.model.IdentitySource;
import io.github.hectorvent.floci.services.verifiedpermissions.model.Policy;
import io.github.hectorvent.floci.services.verifiedpermissions.model.PolicyStore;
import io.github.hectorvent.floci.services.verifiedpermissions.model.PolicyStoreAlias;
import io.github.hectorvent.floci.services.verifiedpermissions.model.PolicyTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class VerifiedPermissionsJsonHandler {
    private final VerifiedPermissionsService service;
    private final ObjectMapper objectMapper;
    private final CedarAuthorizationEvaluator authorizationEvaluator;
    private final VerifiedPermissionsTokenService tokenService;

    @Inject
    public VerifiedPermissionsJsonHandler(VerifiedPermissionsService service, ObjectMapper objectMapper,
                                          CedarAuthorizationEvaluator authorizationEvaluator,
                                          VerifiedPermissionsTokenService tokenService) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.authorizationEvaluator = authorizationEvaluator;
        this.tokenService = tokenService;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreatePolicyStore" -> ok(policyStoreCreate(service.createPolicyStore(request, region)));
            case "GetPolicyStore" -> ok(policyStoreDetail(service.getPolicyStore(
                    VerifiedPermissionsService.requiredText(request, "policyStoreId"), region), request.path("tags").asBoolean(false)));
            case "UpdatePolicyStore" -> ok(policyStoreUpdate(service.updatePolicyStore(request, region)));
            case "DeletePolicyStore" -> {
                service.deletePolicyStore(VerifiedPermissionsService.requiredText(request, "policyStoreId"), region);
                yield empty();
            }
            case "ListPolicyStores" -> ok(policyStoreList(service.listPolicyStores(request, region)));
            case "PutSchema" -> ok(schemaPut(service.putSchema(request, region)));
            case "GetSchema" -> ok(schemaGet(service.getSchema(
                    VerifiedPermissionsService.requiredText(request, "policyStoreId"), region)));
            case "CreatePolicyTemplate" -> ok(policyTemplateDetail(service.createPolicyTemplate(request, region)));
            case "GetPolicyTemplate" -> ok(policyTemplateDetail(service.getPolicyTemplate(
                    VerifiedPermissionsService.requiredText(request, "policyStoreId"),
                    VerifiedPermissionsService.requiredText(request, "policyTemplateId"), region)));
            case "UpdatePolicyTemplate" -> ok(policyTemplateUpdate(service.updatePolicyTemplate(request, region)));
            case "DeletePolicyTemplate" -> {
                service.deletePolicyTemplate(VerifiedPermissionsService.requiredText(request, "policyStoreId"),
                        VerifiedPermissionsService.requiredText(request, "policyTemplateId"), region);
                yield empty();
            }
            case "ListPolicyTemplates" -> ok(policyTemplateList(service.listPolicyTemplates(request, region)));
            case "CreatePolicy" -> ok(policyCreateOrUpdate(service.createPolicy(request, region), region));
            case "GetPolicy" -> ok(policyDetail(service.getPolicy(
                    VerifiedPermissionsService.requiredText(request, "policyStoreId"),
                    VerifiedPermissionsService.requiredText(request, "policyId"), region), region));
            case "UpdatePolicy" -> ok(policyCreateOrUpdate(service.updatePolicy(request, region), region));
            case "DeletePolicy" -> {
                service.deletePolicy(VerifiedPermissionsService.requiredText(request, "policyStoreId"),
                        VerifiedPermissionsService.requiredText(request, "policyId"), region);
                yield empty();
            }
            case "ListPolicies" -> ok(policyList(service.listPolicies(request, region), region));
            case "CreateIdentitySource" -> ok(identitySourceWrite(service.createIdentitySource(request, region)));
            case "GetIdentitySource" -> ok(identitySourceDetail(service.getIdentitySource(
                    VerifiedPermissionsService.requiredText(request, "policyStoreId"),
                    VerifiedPermissionsService.requiredText(request, "identitySourceId"), region)));
            case "UpdateIdentitySource" -> ok(identitySourceWrite(service.updateIdentitySource(request, region)));
            case "DeleteIdentitySource" -> {
                service.deleteIdentitySource(VerifiedPermissionsService.requiredText(request, "policyStoreId"),
                        VerifiedPermissionsService.requiredText(request, "identitySourceId"), region);
                yield empty();
            }
            case "ListIdentitySources" -> ok(identitySourceList(service.listIdentitySources(request, region)));
            case "BatchGetPolicy" -> ok(batchGetPolicy(request, region));
            case "IsAuthorized" -> ok(isAuthorized(request, region));
            case "BatchIsAuthorized" -> ok(batchIsAuthorized(request, region));
            case "IsAuthorizedWithToken" -> ok(isAuthorizedWithToken(request, region));
            case "BatchIsAuthorizedWithToken" -> ok(batchIsAuthorizedWithToken(request, region));
            case "CreatePolicyStoreAlias" -> ok(aliasCreate(service.createAlias(request, region)));
            case "GetPolicyStoreAlias" -> ok(aliasDetail(service.getAlias(
                    VerifiedPermissionsService.requiredText(request, "aliasName"), region)));
            case "ListPolicyStoreAliases" -> ok(aliasList(service.listAliases(request, region)));
            case "DeletePolicyStoreAlias" -> {
                service.deleteAlias(request, region);
                yield empty();
            }
            case "TagResource" -> {
                service.tagResource(VerifiedPermissionsService.requiredText(request, "resourceArn"),
                        stringMap(request.path("tags")), region);
                yield empty();
            }
            case "UntagResource" -> {
                service.untagResource(VerifiedPermissionsService.requiredText(request, "resourceArn"),
                        stringList(request.path("tagKeys")), region);
                yield empty();
            }
            case "ListTagsForResource" -> ok(tags(service.listTags(
                    VerifiedPermissionsService.requiredText(request, "resourceArn"), region)));
            default -> Response.status(400).entity(error("UnknownOperationException",
                    "Unknown operation: " + action)).build();
        };
    }

    private ObjectNode policyStoreCreate(PolicyStore store) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("arn", store.arn());
        time(out, "createdDate", store.createdDate());
        time(out, "lastUpdatedDate", store.lastUpdatedDate());
        out.put("policyStoreId", store.policyStoreId());
        return out;
    }

    private ObjectNode policyStoreUpdate(PolicyStore store) {
        return policyStoreCreate(store);
    }

    private ObjectNode policyStoreDetail(PolicyStore store, boolean includeTags) {
        ObjectNode out = policyStoreCreate(store);
        out.putObject("validationSettings").put("mode", store.validationMode());
        out.put("cedarVersion", "CEDAR_4");
        out.put("deletionProtection", store.deletionProtection());
        if (store.description() != null) {
            out.put("description", store.description());
        }
        ObjectNode encryptionState = out.putObject("encryptionState");
        if (store.encryptionKeyArn() == null) {
            encryptionState.putObject("default");
        } else {
            ObjectNode kms = encryptionState.putObject("kmsEncryptionState");
            kms.put("key", store.encryptionKeyArn());
            ObjectNode context = kms.putObject("encryptionContext");
            store.encryptionContext().forEach(context::put);
        }
        if (includeTags && !store.tags().isEmpty()) {
            ObjectNode tags = out.putObject("tags");
            store.tags().forEach(tags::put);
        }
        return out;
    }

    private ObjectNode policyStoreList(PaginatedResult<PolicyStore> result) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("policyStores");
        for (PolicyStore store : result.items()) {
            ObjectNode item = list.addObject();
            item.put("arn", store.arn());
            time(item, "createdDate", store.createdDate());
            time(item, "lastUpdatedDate", store.lastUpdatedDate());
            item.put("policyStoreId", store.policyStoreId());
            if (store.description() != null) {
                item.put("description", store.description());
            }
        }
        if (result.nextToken() != null) {
            out.put("nextToken", result.nextToken());
        }
        return out;
    }

    private ObjectNode schemaPut(PolicyStore store) {
        ObjectNode out = objectMapper.createObjectNode();
        time(out, "createdDate", store.schemaCreatedDate());
        time(out, "lastUpdatedDate", store.schemaLastUpdatedDate());
        out.put("policyStoreId", store.policyStoreId());
        ArrayNode namespaces = out.putArray("namespaces");
        schemaNamespaces(store.schema()).forEach(namespaces::add);
        return out;
    }

    private ObjectNode schemaGet(PolicyStore store) {
        ObjectNode out = schemaPut(store);
        out.put("schema", store.schema());
        return out;
    }

    private List<String> schemaNamespaces(String schema) {
        if (schema == null) {
            return List.of();
        }
        try {
            JsonNode parsed = objectMapper.readTree(schema);
            if (!parsed.isObject()) {
                return List.of();
            }
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            parsed.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            return names;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Stored Verified Permissions schema is not valid JSON", e);
        }
    }



    private ObjectNode identitySourceWrite(IdentitySource source) {
        ObjectNode out = objectMapper.createObjectNode();
        time(out, "createdDate", source.createdDate());
        out.put("identitySourceId", source.identitySourceId());
        time(out, "lastUpdatedDate", source.lastUpdatedDate());
        out.put("policyStoreId", source.policyStoreId());
        return out;
    }

    private ObjectNode identitySourceDetail(IdentitySource source) {
        ObjectNode out = identitySourceWrite(source);
        out.put("principalEntityType", source.principalEntityType());
        out.set("configuration", source.configuration().deepCopy());
        if (source.configuration().has("cognitoUserPoolConfiguration")) {
            JsonNode cognito = source.configuration().get("cognitoUserPoolConfiguration");
            ObjectNode details = out.putObject("details");
            ArrayNode clients = details.putArray("clientIds");
            if (cognito.path("clientIds").isArray()) {
                cognito.path("clientIds").forEach(clients::add);
            }
            details.put("discoveryUrl", cognito.path("issuer").asText() + "/.well-known/openid-configuration");
            details.put("openIdIssuer", "COGNITO");
            details.put("userPoolArn", cognito.path("userPoolArn").asText());
        }
        return out;
    }

    private ObjectNode identitySourceList(PaginatedResult<IdentitySource> result) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("identitySources");
        for (IdentitySource source : result.items()) list.add(identitySourceDetail(source));
        if (result.nextToken() != null) {
            out.put("nextToken", result.nextToken());
        }
        return out;
    }

    private ObjectNode isAuthorizedWithToken(JsonNode request, String region) {
        VerifiedPermissionsTokenService.PreparedTokenRequest prepared = tokenService.prepare(request, region);
        ObjectNode out = isAuthorized(prepared.request(), region);
        addEntity(out, "principal", prepared.principal());
        return out;
    }

    private ObjectNode batchIsAuthorizedWithToken(JsonNode request, String region) {
        String storeIdentifier = VerifiedPermissionsService.requiredText(request, "policyStoreId");
        service.getPolicyStore(storeIdentifier, region);
        JsonNode requests = request.get("requests");
        if (requests == null || !requests.isArray() || requests.isEmpty() || requests.size() > 30) {
            throw VerifiedPermissionsService.validation("requests must contain between 1 and 30 items.");
        }
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode results = out.putArray("results");
        io.github.hectorvent.floci.services.verifiedpermissions.model.EntityIdentifier principal = null;
        for (JsonNode item : requests) {
            ObjectNode single = objectMapper.createObjectNode();
            single.put("policyStoreId", storeIdentifier);
            if (request.has("identityToken")) {
                single.set("identityToken", request.get("identityToken"));
            }
            if (request.has("accessToken")) {
                single.set("accessToken", request.get("accessToken"));
            }
            if (request.has("entities")) {
                single.set("entities", request.get("entities"));
            }
            if (item.has("action")) {
                single.set("action", item.get("action"));
            }
            if (item.has("resource")) {
                single.set("resource", item.get("resource"));
            }
            if (item.has("context")) {
                single.set("context", item.get("context"));
            }
            VerifiedPermissionsTokenService.PreparedTokenRequest prepared = tokenService.prepare(single, region);
            if (principal == null) {
                principal = prepared.principal();
            }
            CedarAuthorizationEvaluator.EvaluationResult evaluation = authorizationEvaluator.evaluate(prepared.request(),
                    service.policiesForStore(storeIdentifier, region), service.templatesForStore(storeIdentifier, region));
            ObjectNode result = authorizationResult(evaluation);
            result.set("request", item.deepCopy());
            results.add(result);
        }
        addEntity(out, "principal", principal);
        return out;
    }

    private ObjectNode isAuthorized(JsonNode request, String region) {
        String storeIdentifier = VerifiedPermissionsService.requiredText(request, "policyStoreId");
        service.getPolicyStore(storeIdentifier, region);
        CedarAuthorizationEvaluator.EvaluationResult result = authorizationEvaluator.evaluate(request,
                service.policiesForStore(storeIdentifier, region), service.templatesForStore(storeIdentifier, region));
        return authorizationResult(result);
    }

    private ObjectNode batchIsAuthorized(JsonNode request, String region) {
        String storeIdentifier = VerifiedPermissionsService.requiredText(request, "policyStoreId");
        service.getPolicyStore(storeIdentifier, region);
        JsonNode requests = request.get("requests");
        if (requests == null || !requests.isArray() || requests.isEmpty() || requests.size() > 30) {
            throw VerifiedPermissionsService.validation("requests must contain between 1 and 30 items.");
        }
        validateBatchSharedDimension(requests);
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode results = out.putArray("results");
        for (JsonNode batchRequest : requests) {
            ObjectNode evaluationRequest = batchRequest.deepCopy();
            if (request.has("entities")) {
                evaluationRequest.set("entities", request.get("entities"));
            }
            CedarAuthorizationEvaluator.EvaluationResult result = authorizationEvaluator.evaluate(evaluationRequest,
                    service.policiesForStore(storeIdentifier, region), service.templatesForStore(storeIdentifier, region));
            ObjectNode item = authorizationResult(result);
            item.set("request", batchRequest.deepCopy());
            results.add(item);
        }
        return out;
    }

    private void validateBatchSharedDimension(JsonNode requests) {
        String principal = null;
        String resource = null;
        boolean principalsSame = true;
        boolean resourcesSame = true;
        for (JsonNode item : requests) {
            String nextPrincipal = item.has("principal") ? item.get("principal").toString() : "<absent>";
            String nextResource = item.has("resource") ? item.get("resource").toString() : "<absent>";
            if (principal == null) {
                principal = nextPrincipal;
            } else {
                principalsSame &= principal.equals(nextPrincipal);
            }
            if (resource == null) {
                resource = nextResource;
            } else {
                resourcesSame &= resource.equals(nextResource);
            }
        }
        if (!principalsSame && !resourcesSame) {
            throw VerifiedPermissionsService.validation("BatchIsAuthorized requires either the same principal or the same resource in every request.");
        }
    }

    private ObjectNode authorizationResult(CedarAuthorizationEvaluator.EvaluationResult result) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("decision", result.decision());
        ArrayNode determining = out.putArray("determiningPolicies");
        result.determiningPolicyIds().forEach(id -> determining.addObject().put("policyId", id));
        ArrayNode errors = out.putArray("errors");
        result.errors().forEach(message -> errors.addObject().put("errorDescription", message));
        return out;
    }

    private ObjectNode policyTemplateDetail(PolicyTemplate template) {
        ObjectNode out = objectMapper.createObjectNode();
        time(out, "createdDate", template.createdDate());
        time(out, "lastUpdatedDate", template.lastUpdatedDate());
        out.put("policyStoreId", template.policyStoreId());
        out.put("policyTemplateId", template.policyTemplateId());
        out.put("statement", template.statement());
        if (template.description() != null) {
            out.put("description", template.description());
        }
        if (template.name() != null) {
            out.put("name", template.name());
        }
        return out;
    }

    private ObjectNode policyTemplateUpdate(PolicyTemplate template) {
        ObjectNode out = objectMapper.createObjectNode();
        time(out, "createdDate", template.createdDate());
        time(out, "lastUpdatedDate", template.lastUpdatedDate());
        out.put("policyStoreId", template.policyStoreId());
        out.put("policyTemplateId", template.policyTemplateId());
        return out;
    }

    private ObjectNode policyTemplateList(PaginatedResult<PolicyTemplate> result) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("policyTemplates");
        for (PolicyTemplate template : result.items()) {
            ObjectNode item = list.addObject();
            time(item, "createdDate", template.createdDate());
            time(item, "lastUpdatedDate", template.lastUpdatedDate());
            item.put("policyStoreId", template.policyStoreId());
            item.put("policyTemplateId", template.policyTemplateId());
            if (template.description() != null) {
                item.put("description", template.description());
            }
            if (template.name() != null) {
                item.put("name", template.name());
            }
        }
        if (result.nextToken() != null) {
            out.put("nextToken", result.nextToken());
        }
        return out;
    }

    private ObjectNode policyCreateOrUpdate(Policy policy, String region) {
        ObjectNode out = objectMapper.createObjectNode();
        time(out, "createdDate", policy.createdDate());
        time(out, "lastUpdatedDate", policy.lastUpdatedDate());
        out.put("policyId", policy.policyId());
        out.put("policyStoreId", policy.policyStoreId());
        out.put("policyType", policy.policyType());
        out.put("effect", policy.effect());
        VerifiedPermissionsService.PolicyMetadata metadata = service.policyMetadata(policy, region);
        ArrayNode actions = out.putArray("actions");
        metadata.actions().forEach(action -> {
            ObjectNode node = actions.addObject();
            node.put("actionType", action.entityType());
            node.put("actionId", action.entityId());
        });
        addEntity(out, "principal", metadata.principal());
        addEntity(out, "resource", metadata.resource());
        return out;
    }

    private ObjectNode policyDetail(Policy policy, String region) {
        ObjectNode out = policyCreateOrUpdate(policy, region);
        if (policy.name() != null) {
            out.put("name", policy.name());
        }
        out.set("definition", policyDefinition(policy));
        return out;
    }

    private ObjectNode policyDefinition(Policy policy) {
        ObjectNode definition = objectMapper.createObjectNode();
        if ("STATIC".equals(policy.policyType())) {
            ObjectNode body = definition.putObject("static");
            body.put("statement", policy.statement());
            if (policy.description() != null) {
                body.put("description", policy.description());
            }
        } else {
            ObjectNode body = definition.putObject("templateLinked");
            body.put("policyTemplateId", policy.policyTemplateId());
            addEntity(body, "principal", policy.principal());
            addEntity(body, "resource", policy.resource());
        }
        return definition;
    }

    private ObjectNode policyList(PaginatedResult<Policy> result, String region) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("policies");
        for (Policy policy : result.items()) {
            ObjectNode item = policyDetail(policy, region);
            list.add(item);
        }
        if (result.nextToken() != null) {
            out.put("nextToken", result.nextToken());
        }
        return out;
    }

    private ObjectNode batchGetPolicy(JsonNode request, String region) {
        JsonNode requests = request.get("requests");
        if (requests == null || !requests.isArray() || requests.isEmpty() || requests.size() > 100) {
            throw VerifiedPermissionsService.validation("requests must contain between 1 and 100 items.");
        }
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode results = out.putArray("results");
        ArrayNode errors = out.putArray("errors");
        for (JsonNode item : requests) {
            String store = VerifiedPermissionsService.requiredText(item, "policyStoreId");
            String policy = VerifiedPermissionsService.requiredText(item, "policyId");
            try {
                results.add(policyDetail(service.getPolicy(store, policy, region), region));
            } catch (io.github.hectorvent.floci.core.common.AwsException e) {
                ObjectNode error = errors.addObject();
                error.put("policyStoreId", store);
                error.put("policyId", policy);
                boolean alias = store.startsWith("policy-store-alias/");
                if (alias) {
                    error.put("code", "POLICY_STORE_ALIAS_NOT_FOUND");
                } else if ("ResourceNotFoundException".equals(e.getErrorCode()) && !storeExists(store, region)) {
                    error.put("code", "POLICY_STORE_NOT_FOUND");
                } else {
                    error.put("code", "POLICY_NOT_FOUND");
                }
                error.put("message", e.getMessage());
            }
        }
        return out;
    }

    private boolean storeExists(String id, String region) {
        try { service.getPolicyStore(id, region); return true; }
        catch (io.github.hectorvent.floci.core.common.AwsException e) { return false; }
    }

    private static void addEntity(ObjectNode out, String field, io.github.hectorvent.floci.services.verifiedpermissions.model.EntityIdentifier entity) {
        if (entity == null) {
            return;
        }
        ObjectNode node = out.putObject(field);
        node.put("entityType", entity.entityType());
        node.put("entityId", entity.entityId());
    }

    private ObjectNode aliasCreate(PolicyStoreAlias alias) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("aliasArn", alias.aliasArn());
        out.put("aliasName", alias.aliasName());
        time(out, "createdAt", alias.createdAt());
        out.put("policyStoreId", alias.policyStoreId());
        return out;
    }

    private ObjectNode aliasDetail(PolicyStoreAlias alias) {
        ObjectNode out = aliasCreate(alias);
        out.put("state", alias.state());
        return out;
    }

    private ObjectNode aliasList(PaginatedResult<PolicyStoreAlias> result) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("policyStoreAliases");
        result.items().forEach(a -> list.add(aliasDetail(a)));
        if (result.nextToken() != null) {
            out.put("nextToken", result.nextToken());
        }
        return out;
    }

    private ObjectNode tags(Map<String, String> values) {
        ObjectNode out = objectMapper.createObjectNode();
        ObjectNode tags = out.putObject("tags");
        values.forEach(tags::put);
        return out;
    }

    private Map<String, String> stringMap(JsonNode node) {
        if (!node.isObject()) {
            throw VerifiedPermissionsService.validation("tags is required.");
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            if (!e.getValue().isTextual()) {
                throw VerifiedPermissionsService.validation("Tag values must be strings.");
            }
            out.put(e.getKey(), e.getValue().asText());
        });
        return out;
    }

    private List<String> stringList(JsonNode node) {
        if (!node.isArray()) {
            throw VerifiedPermissionsService.validation("tagKeys is required.");
        }
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        node.forEach(v -> {
            if (!v.isTextual()) {
                throw VerifiedPermissionsService.validation("tagKeys must contain strings.");
            }
            out.add(v.asText());
        });
        return out;
    }

    private static void time(ObjectNode node, String field, Instant value) {
        if (value != null) {
            node.put(field, value.toString());
        }
    }

    private Response ok(JsonNode body) { return Response.ok(body).build(); }
    private Response empty() { return Response.ok(objectMapper.createObjectNode()).build(); }
    private ObjectNode error(String type, String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("__type", type);
        node.put("message", message);
        return node;
    }
}
