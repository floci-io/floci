package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import io.github.hectorvent.floci.services.cognito.model.ResourceServer;
import io.github.hectorvent.floci.services.cognito.model.ResourceServerScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@ApplicationScoped
public class CognitoResourceServerCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::Cognito::UserPoolResourceServer";
    private static final String POOL_ATTR = "__FlociResourceServerPoolId";
    private static final String UPDATE_ATTR = "__FlociResourceServerUpdate";
    private static final String LEGACY_CLEANUP_ATTR = "__FlociResourceServerCleanup";
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

        if (ctx.isUpdate() && retainsFailedUpdateState(resource)) {
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
        cleanupBeforeProvision(resource);
        boolean priorIsStub = ctx.isUpdate()
                && CfnResourceDispatcher.isStub(priorPhysicalId, resource.getAttributes());
        Identity prior = ctx.isUpdate() && !priorIsStub ? identity(resource, priorPhysicalId) : null;
        boolean replacement = prior != null
                && (!prior.poolId().equals(poolId) || !prior.identifier().equals(identifier));
        ResourceServer existing = prior == null ? null
                : cognitoService.describeResourceServer(prior.poolId(), prior.identifier());
        Identity target = prior;
        if (prior == null || replacement) {
            cognitoService.createResourceServer(poolId, identifier, name, scopes);
            target = new Identity(poolId, identifier);
        } else {
            resource.getAttributes().put(UPDATE_ATTR, snapshot(prior, existing, false).toString());
            cognitoService.updateResourceServer(poolId, identifier, name, scopes);
        }
        Map<String, String> attributesBefore = new HashMap<>(resource.getAttributes());
        setIdentity(resource, target);
        if (priorIsStub) {
            resource.getAttributes().remove("Arn");
        }
        ProvisionContext cleanupContext = new ProvisionContext(ctx.engine(), ctx.region(), ctx.accountId(),
                ctx.stackName(), priorIsStub ? priorPhysicalId : prior == null ? null : encode(prior), ctx.progress());
        withProjection(resource, projected -> {
            ReplacementCleanup.record(projected, cleanupContext, attributesBefore);
            return null;
        });
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

    private static String encode(Identity identity) {
        return MAPPER.createArrayNode().add(identity.poolId()).add(identity.identifier()).toString();
    }

    private static Identity decode(String physicalId) {
        try {
            JsonNode address = MAPPER.readTree(physicalId);
            if (address != null && address.isArray() && address.size() == 2
                    && address.get(0).isTextual() && !address.get(0).asText().isBlank()
                    && address.get(1).isTextual() && !address.get(1).asText().isBlank()) {
                return new Identity(address.get(0).asText(), address.get(1).asText());
            }
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid Cognito resource server cleanup address", invalid);
        }
        throw new IllegalStateException("Invalid Cognito resource server cleanup address");
    }

    /*
     * Ref is only Identifier, whereas a resource server's delete address also includes its pool.
     * Only this detached view gives ReplacementCleanup the full address; no encoded id is assigned
     * to the stack resource or passed to the template engine.
     */
    private static StackResource project(StackResource resource) {
        StackResource projected = new StackResource();
        projected.setLogicalId(resource.getLogicalId());
        projected.setResourceType(resource.getResourceType());
        projected.setStatus(resource.getStatus());
        projected.setDeletionPolicy(resource.getDeletionPolicy());
        projected.setUpdateReplacePolicy(resource.getUpdateReplacePolicy());
        projected.setAttributes(new HashMap<>(resource.getAttributes()));
        if (resource.getPhysicalId() != null) {
            projected.setPhysicalId(CfnResourceDispatcher.isStub(resource.getPhysicalId(), resource.getAttributes())
                    ? resource.getPhysicalId() : encode(identity(resource, resource.getPhysicalId())));
        }
        importLegacy(projected);
        validateCleanup(projected);
        return projected;
    }

    private static <T> T withProjection(StackResource resource, Function<StackResource, T> operation) {
        StackResource projected = project(resource);
        try {
            return operation.apply(projected);
        } finally {
            // Rollback restores the prior identity before deleting the replacement, even if that delete fails.
            boolean restoredStub = CfnResourceDispatcher.isStub(projected.getPhysicalId(), projected.getAttributes());
            Identity restored = projected.getPhysicalId() == null || restoredStub
                    ? null : decode(projected.getPhysicalId());
            resource.getAttributes().clear();
            resource.getAttributes().putAll(projected.getAttributes());
            if (restoredStub) {
                resource.setPhysicalId(projected.getPhysicalId());
                resource.getAttributes().remove(POOL_ATTR);
            } else if (restored != null) {
                setIdentity(resource, restored);
            }
        }
    }

    private static void importLegacy(StackResource projected) {
        ObjectNode legacy = read(projected, LEGACY_CLEANUP_ATTR);
        ObjectNode snapshot = read(projected, UPDATE_ATTR);
        boolean legacyReplacement = snapshot != null && snapshot.path("replacement").asBoolean();
        if (legacy == null && !legacyReplacement) {
            return;
        }
        ObjectNode cleanup = read(projected, CfnRollback.REPLACEMENT_CLEANUP_ATTR);
        if (cleanup == null) {
            cleanup = MAPPER.createObjectNode();
            cleanup.putArray("displaced");
        }
        if (legacy != null) {
            Identity owed = identity(legacy);
            requireIdentity(owed);
            String encoded = encode(owed);
            JsonNode existing = null;
            for (JsonNode entry : cleanup.path("displaced")) {
                if (encoded.equals(entry.path("physicalId").asText(null))) {
                    existing = entry;
                    break;
                }
            }
            if (existing == null) {
                ObjectNode entry = cleanup.withArray("displaced").addObject();
                entry.put("physicalId", encoded);
                entry.put("resourceType", TYPE);
                entry.put("retainable", legacy.path("retainable").asBoolean());
                entry.put("cleanupAttempts", legacy.path("attempts").asInt(0));
            }
            projected.getAttributes().remove(LEGACY_CLEANUP_ATTR);
        }
        if (legacyReplacement) {
            Identity prior = identity(snapshot);
            requireIdentity(prior);
            if (!cleanup.hasNonNull("priorPhysicalId")) {
                cleanup.put("priorPhysicalId", encode(prior));
                cleanup.putObject("priorAttributes");
            }
            projected.getAttributes().remove(UPDATE_ATTR);
        }
        projected.getAttributes().put(CfnRollback.REPLACEMENT_CLEANUP_ATTR, cleanup.toString());
    }

    private static void requireIdentity(Identity identity) {
        if (identity.poolId().isBlank() || identity.identifier().isBlank()) {
            throw new IllegalStateException("Invalid Cognito resource server lifecycle identity");
        }
    }

    private static void validateCleanup(StackResource projected) {
        ObjectNode cleanup = read(projected, CfnRollback.REPLACEMENT_CLEANUP_ATTR);
        if (cleanup == null) {
            return;
        }
        if (cleanup.has("displaced") && !cleanup.path("displaced").isArray()) {
            throw new IllegalStateException("Invalid Cognito resource server cleanup entries");
        }
        for (JsonNode entry : cleanup.path("displaced")) {
            if (!entry.isObject() || !entry.path("physicalId").isTextual()
                    || entry.path("cleanupAttempts").asInt(0) < 0) {
                throw new IllegalStateException("Invalid Cognito resource server cleanup entry");
            }
            decode(entry.path("physicalId").asText());
        }
        if (cleanup.hasNonNull("priorPhysicalId")) {
            if (!cleanup.path("priorAttributes").isObject()) {
                throw new IllegalStateException("Invalid Cognito resource server rollback attributes");
            }
            Map<String, String> priorAttributes = new HashMap<>();
            cleanup.path("priorAttributes").fields()
                    .forEachRemaining(entry -> priorAttributes.put(entry.getKey(), entry.getValue().asText()));
            String priorPhysicalId = cleanup.path("priorPhysicalId").asText();
            if (!CfnResourceDispatcher.isStub(priorPhysicalId, priorAttributes)) {
                decode(priorPhysicalId);
            }
        }
    }

    private void deleteAddress(String resourceType, String physicalId, String region) {
        delete(decode(physicalId));
    }

    private void delete(Identity identity) {
        CfnDeletes.safeDelete("Cognito resource server", identity.identifier(),
                () -> cognitoService.deleteResourceServer(identity.poolId(), identity.identifier()),
                "ResourceNotFoundException");
    }

    private void cleanupBeforeProvision(StackResource resource) {
        RuntimeException[] failure = new RuntimeException[1];
        withProjection(resource, projected -> {
            UpdateCleanupResult result = ReplacementCleanup.complete(projected, (type, physicalId, region) -> {
                try {
                    deleteAddress(type, physicalId, region);
                } catch (RuntimeException deleteFailure) {
                    failure[0] = deleteFailure;
                    throw deleteFailure;
                }
            });
            ReplacementCleanup.clear(projected);
            if (failure[0] != null) {
                throw failure[0];
            }
            return result;
        });
    }

    @Override
    public void delete(StackResource resource, String region) {
        withProjection(resource, projected -> {
            if (projected.getPhysicalId() != null
                    && !CfnResourceDispatcher.isStub(projected.getPhysicalId(), projected.getAttributes())) {
                deleteAddress(projected.getResourceType(), projected.getPhysicalId(), region);
            }
            projected.getAttributes().remove(UPDATE_ATTR);
            projected.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
            ReplacementCleanup.clear(projected);
            return null;
        });
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return withProjection(resource, projected -> {
            if (ReplacementCleanup.rollback(projected, this::deleteAddress)) {
                return true;
            }
            ObjectNode snapshot = read(projected, UPDATE_ATTR);
            if (snapshot == null) {
                return false;
            }
            Identity prior = identity(snapshot);
            requireIdentity(prior);
            cognitoService.updateResourceServer(prior.poolId(), prior.identifier(), snapshot.path("name").asText(),
                    scopes(snapshot.path("scopes")));
            projected.setPhysicalId(encode(prior));
            projected.getAttributes().remove(UPDATE_ATTR);
            return true;
        });
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
        return withProjection(resource, ReplacementCleanup::hasReplacement);
    }

    private static boolean hasPendingRollback(StackResource projected) {
        ObjectNode cleanup = read(projected, CfnRollback.REPLACEMENT_CLEANUP_ATTR);
        return projected.getAttributes().containsKey(UPDATE_ATTR)
                || (cleanup != null && cleanup.hasNonNull("priorPhysicalId"));
    }

    @Override
    public boolean retainsFailedUpdateState(StackResource resource) {
        return withProjection(resource, CognitoResourceServerCfnProvisioner::hasPendingRollback);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return withProjection(resource, projected -> {
            String physicalId = ReplacementCleanup.cleanupPhysicalId(projected);
            return physicalId == null ? null : decode(physicalId).identifier();
        });
    }

    private static UpdateCleanupResult publicResult(UpdateCleanupResult result) {
        String physicalId = result.previousPhysicalId();
        return new UpdateCleanupResult(result.applicable(), result.complete(),
                physicalId == null ? null : decode(physicalId).identifier(),
                result.attempts(), result.failureReason());
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return withProjection(resource, projected -> {
            if ("UPDATE_FAILED".equals(projected.getStatus()) && hasPendingRollback(projected)) {
                throw new IllegalStateException("Resource server rollback is still pending; its original configuration "
                        + "cannot be discarded by another resource's update cleanup");
            }
            boolean updated = projected.getAttributes().remove(UPDATE_ATTR) != null;
            ObjectNode committed = read(projected, CfnRollback.REPLACEMENT_CLEANUP_ATTR);
            if (committed != null) {
                // Committing the update spends its rollback identity, independently of delete retry debt.
                committed.remove("priorPhysicalId");
                committed.remove("priorAttributes");
                projected.getAttributes().put(CfnRollback.REPLACEMENT_CLEANUP_ATTR, committed.toString());
            }
            UpdateCleanupResult cleanup = ReplacementCleanup.complete(projected, this::deleteAddress);
            return !cleanup.applicable() && updated
                    ? new UpdateCleanupResult(true, true, null, 0, null) : publicResult(cleanup);
        });
    }

    @Override
    public UpdateCleanupResult completeDeleteCleanup(StackResource resource) {
        return withProjection(resource, projected -> publicResult(ReplacementCleanup.complete(projected,
                this::deleteAddress)));
    }

    @Override
    public void clearDeleteCleanup(StackResource resource) {
        withProjection(resource, projected -> {
            ObjectNode cleanup = read(projected, CfnRollback.REPLACEMENT_CLEANUP_ATTR);
            JsonNode prior = cleanup == null ? null : cleanup.get("priorPhysicalId");
            JsonNode attributes = cleanup == null ? null : cleanup.get("priorAttributes");
            ReplacementCleanup.clear(projected);
            if (prior != null) {
                ObjectNode remaining = read(projected, CfnRollback.REPLACEMENT_CLEANUP_ATTR);
                if (remaining == null) {
                    remaining = MAPPER.createObjectNode();
                    remaining.putArray("displaced");
                }
                remaining.set("priorPhysicalId", prior);
                remaining.set("priorAttributes", attributes);
                projected.getAttributes().put(CfnRollback.REPLACEMENT_CLEANUP_ATTR, remaining.toString());
            }
            return null;
        });
    }

    @Override
    public void clearUpdate(StackResource resource) {
        withProjection(resource, projected -> {
            projected.getAttributes().remove(UPDATE_ATTR);
            ReplacementCleanup.clear(projected);
            return null;
        });
    }

    @Override
    public void mergeFailedUpdateResourceTracking(StackResource previous, StackResource attempted) {
        // The attempted resource is authoritative about debts it already consumed before failing.
        // The dispatcher's additive merge cannot remove those entries from the restored metadata.
        StackResource projectedAttempt = project(attempted);
        ObjectNode cleanup = read(projectedAttempt, CfnRollback.REPLACEMENT_CLEANUP_ATTR);
        ObjectNode carried = cleanup == null ? null : cleanup.deepCopy();
        if (carried != null) {
            ArrayNode debts = MAPPER.createArrayNode();
            String current = previous.getPhysicalId() == null
                    || CfnResourceDispatcher.isStub(previous.getPhysicalId(), previous.getAttributes())
                    ? null : encode(identity(previous, previous.getPhysicalId()));
            for (JsonNode entry : carried.path("displaced")) {
                if (!entry.path("physicalId").asText().equals(current)) {
                    debts.add(entry);
                }
            }
            carried.set("displaced", debts);
            carried.remove("priorPhysicalId");
            carried.remove("priorAttributes");
        }
        previous.getAttributes().remove(LEGACY_CLEANUP_ATTR);
        if (carried == null || carried.path("displaced").isEmpty()) {
            previous.getAttributes().remove(CfnRollback.REPLACEMENT_CLEANUP_ATTR);
        } else {
            previous.getAttributes().put(CfnRollback.REPLACEMENT_CLEANUP_ATTR, carried.toString());
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
