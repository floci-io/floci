package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Stack lifecycle shared by the Cognito types whose {@code Ref} is a bare name within a user pool,
 * while Cognito addresses them by pool and name: {@code AWS::Cognito::UserPoolResourceServer} and
 * {@code AWS::Cognito::UserPoolIdentityProvider}.
 *
 * <p>The pool lives in an attribute next to the bare physical id. Every lifecycle record works on a
 * detached projection whose physical id is the encoded {@code [poolId, name]} pair, so
 * {@link ReplacementCleanup} can delete a displaced entity from its own pool; no encoded id is ever
 * assigned to the stack resource or passed to the template engine. An in-place update keeps a
 * snapshot, written by the provisioner and put back by its {@link SnapshotRestorer}, so a failed
 * update rolls back.
 */
final class CognitoPoolScopedLifecycle {

    static final ObjectMapper MAPPER = new ObjectMapper();

    /** A pool-scoped entity's address. */
    record Identity(String poolId, String name) {
    }

    /** What an update found on the resource before provisioning it. */
    record Prior(String physicalId, boolean stub, Identity identity) {
    }

    @FunctionalInterface
    interface EntityDeleter {
        void delete(Identity identity);
    }

    @FunctionalInterface
    interface SnapshotRestorer {
        void restore(Identity identity, ObjectNode snapshot);
    }

    private final String label;
    private final String poolAttr;
    private final String updateAttr;
    private final String snapshotNameKey;
    private final EntityDeleter deleter;
    private final SnapshotRestorer restorer;

    /**
     * @param label the entity's name in messages, lower case (for example {@code "resource server"})
     * @param poolAttr the attribute holding the owning pool id
     * @param updateAttr the attribute holding an in-place update's snapshot
     * @param snapshotNameKey the snapshot field naming the entity next to {@code poolId}
     */
    CognitoPoolScopedLifecycle(String label, String poolAttr, String updateAttr, String snapshotNameKey,
                               EntityDeleter deleter, SnapshotRestorer restorer) {
        this.label = label;
        this.poolAttr = poolAttr;
        this.updateAttr = updateAttr;
        this.snapshotNameKey = snapshotNameKey;
        this.deleter = deleter;
        this.restorer = restorer;
    }

    /**
     * Opens an update: ends the previous rollback's retry window and finishes a rollback the last
     * attempt left pending, so its snapshot or cleanup identity is not overwritten.
     */
    void beginUpdate(StackResource resource, ProvisionContext ctx) {
        if (!ctx.isUpdate()) {
            return;
        }
        // Rolling this update back undoes it alone; only ContinueUpdateRollback retries the
        // delete the last rollback could not make.
        ReplacementCleanup.endRollbackRetry(resource);
        if (retainsFailedUpdateState(resource)) {
            try {
                rollbackUpdate(resource);
                resource.getAttributes().remove(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR);
                resource.getAttributes().remove(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR);
            } catch (RuntimeException failure) {
                resource.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR, failure.getMessage());
                throw new IllegalStateException("Could not finish the previous " + label + " update rollback",
                        failure);
            }
        }
    }

    /** The resource's address before this provision: none on a create or a dispatcher stub. */
    Prior prior(StackResource resource, ProvisionContext ctx) {
        String priorPhysicalId = ctx.isUpdate() ? resource.getPhysicalId() : null;
        boolean priorIsStub = ctx.isUpdate()
                && CfnResourceDispatcher.isStub(priorPhysicalId, resource.getAttributes());
        Identity identity = ctx.isUpdate() && !priorIsStub ? identity(resource, priorPhysicalId) : null;
        return new Prior(priorPhysicalId, priorIsStub, identity);
    }

    /** A snapshot holding the address, for the provisioner to add its in-place configuration to. */
    ObjectNode snapshot(Identity identity) {
        return MAPPER.createObjectNode().put("poolId", identity.poolId()).put(snapshotNameKey, identity.name());
    }

    void saveSnapshot(StackResource resource, ObjectNode snapshot) {
        resource.getAttributes().put(updateAttr, snapshot.toString());
    }

    /** Assigns {@code target} to the resource and records what an update displaced or must roll back. */
    void commit(StackResource resource, ProvisionContext ctx, Prior prior, Identity target) {
        Map<String, String> attributesBefore = new HashMap<>(resource.getAttributes());
        setIdentity(resource, target);
        if (prior.stub()) {
            resource.getAttributes().remove("Arn");
        }
        String cleanupPrior = prior.stub() ? prior.physicalId()
                : prior.identity() == null ? null : encode(prior.identity());
        ProvisionContext cleanupContext = new ProvisionContext(ctx.engine(), ctx.region(), ctx.accountId(),
                ctx.stackName(), cleanupPrior, ctx.progress(), ctx.priorProperties());
        withProjection(resource, projected -> {
            ReplacementCleanup.record(projected, cleanupContext, attributesBefore);
            return null;
        });
        resource.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
    }

    private Identity identity(StackResource resource, String name) {
        String poolId = resource.getAttributes().get(poolAttr);
        if (poolId == null || poolId.isBlank() || name == null || name.isBlank()) {
            throw new IllegalStateException("Missing Cognito " + label + " identity for " + resource.getLogicalId());
        }
        return new Identity(poolId, name);
    }

    private void setIdentity(StackResource resource, Identity identity) {
        resource.setPhysicalId(identity.name());
        resource.getAttributes().put(poolAttr, identity.poolId());
    }

    private static String encode(Identity identity) {
        return MAPPER.createArrayNode().add(identity.poolId()).add(identity.name()).toString();
    }

    private Identity decode(String physicalId) {
        try {
            JsonNode address = MAPPER.readTree(physicalId);
            if (address != null && address.isArray() && address.size() == 2
                    && address.get(0).isTextual() && !address.get(0).asText().isBlank()
                    && address.get(1).isTextual() && !address.get(1).asText().isBlank()) {
                return new Identity(address.get(0).asText(), address.get(1).asText());
            }
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid Cognito " + label + " cleanup address", invalid);
        }
        throw new IllegalStateException("Invalid Cognito " + label + " cleanup address");
    }

    /*
     * Ref is only the name, whereas the entity's delete address also includes its pool.
     * Only this detached view gives ReplacementCleanup the full address.
     */
    private StackResource project(StackResource resource) {
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
        validateCleanup(projected);
        return projected;
    }

    private <T> T withProjection(StackResource resource, Function<StackResource, T> operation) {
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
                resource.getAttributes().remove(poolAttr);
            } else if (restored != null) {
                setIdentity(resource, restored);
            }
        }
    }

    private void requireIdentity(Identity identity) {
        if (identity.poolId().isBlank() || identity.name().isBlank()) {
            throw new IllegalStateException("Invalid Cognito " + label + " lifecycle identity");
        }
    }

    private void validateCleanup(StackResource projected) {
        ObjectNode cleanup = read(projected, CfnRollback.REPLACEMENT_CLEANUP_ATTR);
        if (cleanup == null) {
            return;
        }
        if (cleanup.has("displaced") && !cleanup.path("displaced").isArray()) {
            throw new IllegalStateException("Invalid Cognito " + label + " cleanup entries");
        }
        for (JsonNode entry : cleanup.path("displaced")) {
            if (!entry.isObject() || !entry.path("physicalId").isTextual()
                    || entry.path("cleanupAttempts").asInt(0) < 0) {
                throw new IllegalStateException("Invalid Cognito " + label + " cleanup entry");
            }
            decode(entry.path("physicalId").asText());
        }
        if (cleanup.hasNonNull("priorPhysicalId")) {
            if (!cleanup.path("priorAttributes").isObject()) {
                throw new IllegalStateException("Invalid Cognito " + label + " rollback attributes");
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
        Identity identity = decode(physicalId);
        CfnDeletes.safeDelete("Cognito " + label, identity.name(), () -> deleter.delete(identity),
                "ResourceNotFoundException");
    }

    void delete(StackResource resource, String region) {
        withProjection(resource, projected -> {
            if (projected.getPhysicalId() != null
                    && !CfnResourceDispatcher.isStub(projected.getPhysicalId(), projected.getAttributes())) {
                deleteAddress(projected.getResourceType(), projected.getPhysicalId(), region);
            }
            projected.getAttributes().remove(updateAttr);
            projected.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
            ReplacementCleanup.clear(projected);
            return null;
        });
    }

    boolean rollbackUpdate(StackResource resource) {
        return withProjection(resource, projected -> {
            if (ReplacementCleanup.rollback(projected, this::deleteAddress)) {
                return true;
            }
            ObjectNode snapshot = read(projected, updateAttr);
            if (snapshot == null) {
                return false;
            }
            Identity prior = new Identity(snapshot.path("poolId").asText(), snapshot.path(snapshotNameKey).asText());
            requireIdentity(prior);
            restorer.restore(prior, snapshot);
            projected.setPhysicalId(encode(prior));
            projected.getAttributes().remove(updateAttr);
            return true;
        });
    }

    boolean hasReplacementUpdate(StackResource resource) {
        return withProjection(resource, ReplacementCleanup::hasReplacement);
    }

    private boolean hasPendingRollback(StackResource projected) {
        ObjectNode cleanup = read(projected, CfnRollback.REPLACEMENT_CLEANUP_ATTR);
        return projected.getAttributes().containsKey(updateAttr)
                || (cleanup != null && cleanup.hasNonNull("priorPhysicalId"));
    }

    boolean retainsFailedUpdateState(StackResource resource) {
        return withProjection(resource, this::hasPendingRollback);
    }

    String updateCleanupPhysicalId(StackResource resource) {
        return withProjection(resource, projected -> {
            String physicalId = ReplacementCleanup.cleanupPhysicalId(projected);
            return physicalId == null ? null : decode(physicalId).name();
        });
    }

    private UpdateCleanupResult publicResult(UpdateCleanupResult result) {
        String physicalId = result.previousPhysicalId();
        return new UpdateCleanupResult(result.applicable(), result.complete(),
                physicalId == null ? null : decode(physicalId).name(),
                result.attempts(), result.failureReason());
    }

    UpdateCleanupResult completeUpdate(StackResource resource) {
        return withProjection(resource, projected -> {
            if ("UPDATE_FAILED".equals(projected.getStatus()) && hasPendingRollback(projected)) {
                throw new IllegalStateException(Character.toUpperCase(label.charAt(0)) + label.substring(1)
                        + " rollback is still pending; its original configuration cannot be discarded by "
                        + "another resource's update cleanup");
            }
            boolean updated = projected.getAttributes().remove(updateAttr) != null;
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

    UpdateCleanupResult completeDeleteCleanup(StackResource resource) {
        return withProjection(resource, projected -> publicResult(ReplacementCleanup.complete(projected,
                this::deleteAddress)));
    }

    void clearDeleteCleanup(StackResource resource) {
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

    void clearUpdate(StackResource resource) {
        withProjection(resource, projected -> {
            projected.getAttributes().remove(updateAttr);
            ReplacementCleanup.clear(projected);
            return null;
        });
    }

    private ObjectNode read(StackResource resource, String attribute) {
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
            throw new IllegalStateException("Invalid Cognito " + label + " lifecycle record", e);
        }
        throw new IllegalStateException("Invalid Cognito " + label + " lifecycle record");
    }
}
