package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import io.github.hectorvent.floci.services.cognito.model.ResourceServer;
import io.github.hectorvent.floci.services.cognito.model.ResourceServerScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@ApplicationScoped
public class CognitoResourceServerCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::Cognito::UserPoolResourceServer";
    private static final String POOL_ATTR = "__FlociResourceServerPoolId";
    private static final String UPDATE_ATTR = "__FlociResourceServerUpdate";
    private static final String CLEANUP_ATTR = "__FlociResourceServerCleanup";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CognitoService cognitoService;

    @Inject
    public CognitoResourceServerCfnProvisioner(CognitoService cognitoService) {
        this.cognitoService = cognitoService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource resource, JsonNode properties, ProvisionContext ctx) {
        String poolId = required(ctx.resolveOptional(properties, "UserPoolId"), "UserPoolId");
        String identifier = required(ctx.resolveOptional(properties, "Identifier"), "Identifier");
        String name = required(ctx.resolveOptional(properties, "Name"), "Name");
        validateString(poolId, "UserPoolId", 55, "[\\w-]+_[0-9a-zA-Z]+");
        validateString(identifier, "Identifier", 256, "[\\x21\\x23-\\x5B\\x5D-\\x7E]+");
        validateString(name, "Name", 256, "[\\w\\s+=,.@-]+");
        List<ResourceServerScope> scopes = scopes(properties, ctx);

        if (ctx.isUpdate() && resource.getAttributes().containsKey(UPDATE_ATTR)) {
            try {
                rollbackUpdate(resource);
                resource.getAttributes().remove(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR);
                resource.getAttributes().remove(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR);
            } catch (RuntimeException failure) {
                resource.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR, failure.getMessage());
                throw new IllegalStateException("Could not finish the previous resource server update rollback", failure);
            }
        }
        String priorPhysicalId = ctx.isUpdate() ? resource.getPhysicalId() : null;
        deletePending(resource);
        Identity target = new Identity(poolId, identifier);
        Identity prior = ctx.isUpdate() ? identity(resource, priorPhysicalId) : null;
        boolean replacement = prior != null && !prior.equals(target);
        ResourceServer existing = prior == null ? null
                : cognitoService.describeResourceServer(prior.poolId(), prior.identifier());
        if (prior == null || replacement) {
            cognitoService.createResourceServer(poolId, identifier, name, scopes);
        } else {
            resource.getAttributes().put(UPDATE_ATTR, snapshot(prior, existing, false).toString());
            cognitoService.updateResourceServer(poolId, identifier, name, scopes);
        }
        if (replacement) {
            resource.getAttributes().put(UPDATE_ATTR, snapshot(prior, existing, true).toString());
            setCleanup(resource, prior, true);
        }
        setIdentity(resource, target);
        resource.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
    }

    private static String required(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError", TYPE + " requires " + property, 400);
        }
        return value;
    }

    private static List<ResourceServerScope> scopes(JsonNode properties, ProvisionContext ctx) {
        if (properties == null || !properties.hasNonNull("Scopes")) {
            return List.of();
        }
        JsonNode resolved = ctx.engine().resolveNodeOmittingNoValue(properties.get("Scopes"));
        if (resolved == null || resolved.isNull() || resolved.isMissingNode()) {
            return List.of();
        }
        if (!resolved.isArray()) {
            throw new AwsException("ValidationError", TYPE + " Scopes must be a list", 400);
        }
        if (resolved.size() > 100) {
            throw new AwsException("ValidationError", TYPE + " allows at most 100 Scopes", 400);
        }
        List<ResourceServerScope> scopes = new ArrayList<>();
        for (JsonNode node : resolved) {
            if (!node.isObject() || !node.path("ScopeName").isTextual()
                    || !node.path("ScopeDescription").isTextual()
                    || !validScopeName(node.path("ScopeName").asText())
                    || !validScopeDescription(node.path("ScopeDescription").asText())) {
                throw new AwsException("ValidationError", TYPE + " scopes require ScopeName and ScopeDescription", 400);
            }
            ResourceServerScope scope = new ResourceServerScope();
            scope.setScopeName(node.path("ScopeName").asText());
            scope.setScopeDescription(node.path("ScopeDescription").asText());
            scopes.add(scope);
        }
        return scopes;
    }

    private static void validateString(String value, String property, int maximum, String pattern) {
        if (value.length() > maximum || !value.matches(pattern)) {
            throw new AwsException("ValidationError", TYPE + " has invalid " + property, 400);
        }
    }

    private static boolean validScopeName(String value) {
        return value.length() <= 256 && value.matches("[\\x21\\x23-\\x2E\\x30-\\x5B\\x5D-\\x7E]+");
    }

    private static boolean validScopeDescription(String value) {
        return !value.isEmpty() && value.length() <= 256;
    }

    private record Identity(String poolId, String identifier) {
    }

    private static Identity identity(StackResource resource, String identifier) {
        String poolId = resource.getAttributes().get(POOL_ATTR);
        if (poolId == null || poolId.isBlank() || identifier == null || identifier.isBlank()) {
            throw new IllegalStateException("Missing Cognito resource server identity for " + resource.getLogicalId());
        }
        return new Identity(poolId, identifier);
    }

    private static Identity identity(JsonNode node) {
        return new Identity(node.path("poolId").asText(), node.path("identifier").asText());
    }

    private static void setIdentity(StackResource resource, Identity identity) {
        resource.setPhysicalId(identity.identifier());
        resource.getAttributes().put(POOL_ATTR, identity.poolId());
    }

    private static ObjectNode address(Identity identity) {
        return MAPPER.createObjectNode().put("poolId", identity.poolId()).put("identifier", identity.identifier());
    }

    private static ObjectNode snapshot(Identity identity, ResourceServer server, boolean replacement) {
        ObjectNode snapshot = address(identity);
        snapshot.put("name", server.getName());
        snapshot.set("scopes", MAPPER.valueToTree(server.getScopes()));
        snapshot.put("replacement", replacement);
        return snapshot;
    }

    private static void setCleanup(StackResource resource, Identity identity, boolean retainable) {
        ObjectNode cleanup = address(identity);
        cleanup.put("retainable", retainable);
        cleanup.put("attempts", 0);
        resource.getAttributes().put(CLEANUP_ATTR, cleanup.toString());
    }

    @Override
    public void delete(StackResource resource, String region) {
        deletePending(resource);
        if (resource.getPhysicalId() != null) {
            delete(identity(resource, resource.getPhysicalId()));
        }
        resource.getAttributes().remove(UPDATE_ATTR);
        resource.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
    }

    private void delete(Identity identity) {
        CfnDeletes.safeDelete("Cognito resource server", identity.identifier(),
                () -> cognitoService.deleteResourceServer(identity.poolId(), identity.identifier()),
                "ResourceNotFoundException");
    }

    private void deletePending(StackResource resource) {
        ObjectNode cleanup = read(resource, CLEANUP_ATTR);
        if (cleanup != null) {
            delete(identity(cleanup));
            resource.getAttributes().remove(CLEANUP_ATTR);
        }
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        ObjectNode snapshot = read(resource, UPDATE_ATTR);
        if (snapshot == null) {
            return false;
        }
        Identity prior = identity(snapshot);
        String name = snapshot.path("name").asText();
        if (snapshot.path("replacement").asBoolean()) {
            Identity replacement = identity(resource, resource.getPhysicalId());
            setIdentity(resource, prior);
            resource.getAttributes().remove(UPDATE_ATTR);
            resource.getAttributes().remove(CLEANUP_ATTR);
            if (!Objects.equals(prior, replacement)) {
                setCleanup(resource, replacement, false);
                deletePending(resource);
            }
        } else {
            cognitoService.updateResourceServer(prior.poolId(), prior.identifier(), name,
                    scopes(snapshot.path("scopes")));
            setIdentity(resource, prior);
            resource.getAttributes().remove(UPDATE_ATTR);
        }
        return true;
    }

    private static List<ResourceServerScope> scopes(JsonNode nodes) {
        List<ResourceServerScope> scopes = new ArrayList<>();
        for (JsonNode node : nodes) {
            ResourceServerScope scope = new ResourceServerScope();
            scope.setScopeName(node.path("scopeName").asText());
            scope.setScopeDescription(node.path("scopeDescription").asText(null));
            scopes.add(scope);
        }
        return scopes;
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        ObjectNode snapshot = read(resource, UPDATE_ATTR);
        return snapshot != null && snapshot.path("replacement").asBoolean();
    }

    @Override
    public boolean retainsFailedUpdateState(StackResource resource) {
        return resource.getAttributes().containsKey(UPDATE_ATTR);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        ObjectNode cleanup = read(resource, CLEANUP_ATTR);
        return cleanup == null || retained(resource, cleanup) ? null : cleanup.path("identifier").asText();
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        if ("UPDATE_FAILED".equals(resource.getStatus()) && retainsFailedUpdateState(resource)) {
            throw new IllegalStateException("Resource server rollback is still pending; its original configuration "
                    + "cannot be discarded by another resource's update cleanup");
        }
        boolean updated = resource.getAttributes().remove(UPDATE_ATTR) != null;
        UpdateCleanupResult cleanup = completeCleanup(resource);
        return !cleanup.applicable() && updated ? new UpdateCleanupResult(true, true, null, 0, null) : cleanup;
    }

    @Override
    public UpdateCleanupResult completeDeleteCleanup(StackResource resource) {
        return completeCleanup(resource);
    }

    @Override
    public void clearDeleteCleanup(StackResource resource) {
        // The rollback snapshot remains until deleting the managed server succeeds.
    }

    private UpdateCleanupResult completeCleanup(StackResource resource) {
        ObjectNode cleanup = read(resource, CLEANUP_ATTR);
        if (cleanup == null) {
            return UpdateCleanupResult.notApplicable();
        }
        String identifier = cleanup.path("identifier").asText();
        if (retained(resource, cleanup)) {
            resource.getAttributes().remove(CLEANUP_ATTR);
            return new UpdateCleanupResult(true, true, identifier, 0, null);
        }
        try {
            deletePending(resource);
            return new UpdateCleanupResult(true, true, identifier, 0, null);
        } catch (RuntimeException failure) {
            int attempts = cleanup.path("attempts").asInt() + 1;
            cleanup.put("attempts", attempts);
            resource.getAttributes().put(CLEANUP_ATTR, cleanup.toString());
            return new UpdateCleanupResult(true, false, identifier, attempts, failure.getMessage());
        }
    }

    private static boolean retained(StackResource resource, JsonNode cleanup) {
        return cleanup.path("retainable").asBoolean() && "Retain".equals(resource.getUpdateReplacePolicy());
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(UPDATE_ATTR);
        ObjectNode cleanup = read(resource, CLEANUP_ATTR);
        // AWS stops managing a committed replacement's old server after three failed deletions.
        if (cleanup != null && cleanup.path("retainable").asBoolean() && cleanup.path("attempts").asInt() >= 3) {
            resource.getAttributes().remove(CLEANUP_ATTR);
        }
    }

    @Override
    public void mergeFailedUpdateResourceTracking(StackResource previous, StackResource attempted) {
        ObjectNode cleanup = read(attempted, CLEANUP_ATTR);
        if (cleanup != null && !cleanup.path("retainable").asBoolean()) {
            previous.getAttributes().put(CLEANUP_ATTR, cleanup.toString());
        }
    }

    private static ObjectNode read(StackResource resource, String attribute) {
        String raw = resource.getAttributes().get(attribute);
        if (raw == null) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (node instanceof ObjectNode object) {
                return object;
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid Cognito resource server lifecycle record", e);
        }
        throw new IllegalStateException("Invalid Cognito resource server lifecycle record");
    }
}
