package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.jboss.logging.Logger;

/**
 * The replacement-cleanup lifecycle for provisioners whose replacing update simply creates the new
 * entity and leaves the displaced one to be deleted once the stack update commits: the shape
 * CloudFormation gives every replacement (DELETE_IN_PROGRESS on the old physical id after
 * UPDATE_COMPLETE_CLEANUP_IN_PROGRESS, three attempts, {@code UpdateReplacePolicy: Retain} keeps it).
 *
 * <p>{@code PipesCfnProvisioner} carries its own richer record because it also snapshots for
 * rollback; this is the plain version for types without one. A provisioner calls {@link #record}
 * at the end of a successful {@code provision} and delegates the four interface hooks here.
 */
final class ReplacementCleanup {

    private static final Logger LOG = Logger.getLogger(ReplacementCleanup.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ATTEMPTS = 3;

    /** Deletes the displaced entity; the caller's own idempotent delete arm. */
    @FunctionalInterface
    interface Deleter {
        void delete(String resourceType, String physicalId, String region);
    }

    private ReplacementCleanup() {
    }

    /**
     * Records the entity this update displaced when the provision left the resource with a new
     * physical id, and clears any stale record when it did not. Call after the new entity exists.
     */
    static void record(StackResource r, ProvisionContext ctx) {
        String prior = ctx.priorPhysicalId();
        if (!ctx.isUpdate() || prior.equals(r.getPhysicalId())) {
            r.getAttributes().remove(CfnRollback.REPLACEMENT_CLEANUP_ATTR);
            return;
        }
        ObjectNode cleanup = MAPPER.createObjectNode();
        cleanup.put("priorPhysicalId", prior);
        cleanup.put("resourceType", r.getResourceType());
        cleanup.put("region", ctx.region());
        cleanup.put("cleanupAttempts", 0);
        r.getAttributes().put(CfnRollback.REPLACEMENT_CLEANUP_ATTR, cleanup.toString());
    }

    static boolean hasReplacement(StackResource r) {
        JsonNode cleanup = read(r);
        return cleanup != null && cleanup.path("priorPhysicalId").asText(null) != null;
    }

    /** The displaced physical id, or null when nothing is owed or the policy retains it. */
    static String cleanupPhysicalId(StackResource r) {
        if ("Retain".equals(r.getUpdateReplacePolicy())) {
            return null;
        }
        JsonNode cleanup = read(r);
        return cleanup == null ? null : cleanup.path("priorPhysicalId").asText(null);
    }

    static void clear(StackResource r) {
        r.getAttributes().remove(CfnRollback.REPLACEMENT_CLEANUP_ATTR);
    }

    /**
     * One attempt at deleting the displaced entity. The attempt count and the last failure are
     * written back onto the resource so the caller's retries continue where this one stopped.
     */
    static UpdateCleanupResult complete(StackResource r, Deleter deleter) {
        JsonNode cleanup = read(r);
        if (cleanup == null) {
            return UpdateCleanupResult.notApplicable();
        }
        String prior = cleanup.path("priorPhysicalId").asText(null);
        if (prior == null || prior.equals(r.getPhysicalId()) || "Retain".equals(r.getUpdateReplacePolicy())) {
            return new UpdateCleanupResult(true, true, prior, 0, null);
        }
        int attempts = cleanup.path("cleanupAttempts").asInt(0);
        String failureReason = cleanup.path("cleanupFailureReason").asText(null);
        if (attempts >= MAX_ATTEMPTS) {
            return new UpdateCleanupResult(true, false, prior, attempts, failureReason);
        }
        try {
            deleter.delete(cleanup.path("resourceType").asText(r.getResourceType()), prior,
                    cleanup.path("region").asText(null));
            return new UpdateCleanupResult(true, true, prior, attempts, null);
        } catch (RuntimeException deleteFailure) {
            attempts++;
            ((ObjectNode) cleanup).put("cleanupAttempts", attempts);
            ((ObjectNode) cleanup).put("cleanupFailureReason", deleteFailure.getMessage());
            r.getAttributes().put(CfnRollback.REPLACEMENT_CLEANUP_ATTR, cleanup.toString());
            return new UpdateCleanupResult(true, false, prior, attempts, deleteFailure.getMessage());
        }
    }

    private static JsonNode read(StackResource r) {
        String raw = r.getAttributes().get(CfnRollback.REPLACEMENT_CLEANUP_ATTR);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            LOG.warnv("Unreadable replacement cleanup record on {0}, dropping it: {1}", r.getLogicalId(), e.getMessage());
            r.getAttributes().remove(CfnRollback.REPLACEMENT_CLEANUP_ATTR);
            return null;
        }
    }
}
