package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.jboss.logging.Logger;

import java.util.Iterator;
import java.util.Map;

/**
 * The replacement-cleanup lifecycle for provisioners whose replacing update simply creates the new
 * entity and leaves the displaced one to be deleted once the stack update commits: the shape
 * CloudFormation gives every replacement (DELETE_IN_PROGRESS on the old physical id after
 * UPDATE_COMPLETE_CLEANUP_IN_PROGRESS, three attempts, {@code UpdateReplacePolicy: Retain} keeps it).
 *
 * <p>{@code PipesCfnProvisioner} carries its own richer record because it also snapshots the
 * entity's configuration for in-place rollback; this is the plain version for types without one. A
 * provisioner calls {@link #record} at the end of a successful {@code provision} and delegates the
 * cleanup hooks and {@link #rollback} here. A failed stack update after a replacement is rolled
 * back by deleting the replacement and pointing the resource at the prior entity again, which the
 * replacement never touched; an in-place update owes no record and is not rolled back here.
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
     * physical id, and clears any stale record when it did not. Call after the new entity exists,
     * with the attributes the resource carried before {@code provision} overwrote them: they are
     * what {@link #rollback} puts back.
     */
    static void record(StackResource r, ProvisionContext ctx, Map<String, String> attributesBeforeProvision) {
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
        ObjectNode priorAttributes = cleanup.putObject("priorAttributes");
        attributesBeforeProvision.forEach((key, value) -> {
            if (!key.startsWith("__Floci") && value != null) {
                priorAttributes.put(key, value);
            }
        });
        r.getAttributes().put(CfnRollback.REPLACEMENT_CLEANUP_ATTR, cleanup.toString());
    }

    /**
     * Undoes a replacement when a later resource failed the stack update: the resource names the
     * prior entity again, with the attributes it had, and the replacement this update created is
     * deleted. The prior entity was displaced, never changed, so nothing is written to it.
     *
     * <p>The record is spent before the delete is attempted. It owes a delete only once the update
     * commits, and a rollback is the update never committing; a resource still carrying it would put
     * the prior entity this rollback just restored on the next cleanup's delete list. A delete that
     * fails therefore leaves the replacement orphaned and the prior standing, and propagates so the
     * stack reports the failure. Returns false when no replacement was recorded, which leaves the
     * engine's handling of in-place updates unchanged.
     */
    static boolean rollback(StackResource r, Deleter deleter) {
        JsonNode cleanup = read(r);
        if (cleanup == null) {
            return false;
        }
        String prior = cleanup.path("priorPhysicalId").asText(null);
        String replacement = r.getPhysicalId();
        if (prior == null || prior.equals(replacement)) {
            clear(r);
            return prior != null;
        }
        r.setPhysicalId(prior);
        Iterator<String> keys = r.getAttributes().keySet().iterator();
        while (keys.hasNext()) {
            if (!keys.next().startsWith("__Floci")) {
                keys.remove();
            }
        }
        cleanup.path("priorAttributes").fields()
                .forEachRemaining(e -> r.getAttributes().put(e.getKey(), e.getValue().asText()));
        clear(r);
        deleter.delete(cleanup.path("resourceType").asText(r.getResourceType()), replacement,
                cleanup.path("region").asText(null));
        return true;
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
