package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The replacement lifecycle for provisioners whose replacing update simply creates the new entity
 * and leaves the displaced one to be dealt with later: deleted once the stack update commits, the
 * shape CloudFormation gives every replacement (DELETE_IN_PROGRESS on the old physical id after
 * UPDATE_COMPLETE_CLEANUP_IN_PROGRESS, three attempts, {@code UpdateReplacePolicy: Retain} keeps it),
 * or put back when a later resource fails the update and it rolls back.
 *
 * <p>The record on the resource lists every entity owed a delete, not only the one this update
 * displaced: a replacement whose rollback could not delete it stays listed, so the next committed
 * update or the stack delete removes it instead of it being forgotten. The prior entity's id and
 * attributes are kept beside the list only while this update's replacement can still be rolled back.
 *
 * <p>{@code PipesCfnProvisioner} carries its own richer record because it also snapshots the
 * entity's configuration for in-place rollback; this is the plain version for types without one.
 * A provisioner calls {@link #record} at the end of a successful {@code provision} and delegates
 * the cleanup hooks and {@link #rollback} here.
 */
final class ReplacementCleanup {

    private static final Logger LOG = Logger.getLogger(ReplacementCleanup.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ATTEMPTS = 3;

    /** Deletes a displaced entity; the caller's own idempotent delete arm. */
    @FunctionalInterface
    interface Deleter {
        void delete(String resourceType, String physicalId, String region);
    }

    private ReplacementCleanup() {
    }

    /**
     * Records the entity this update displaced when the provision left the resource with a new
     * physical id. Call after the new entity exists, with the attributes the resource carried
     * before {@code provision} overwrote them: they are what {@link #rollback} puts back. A
     * provision that replaced nothing drops the rollback fields but keeps any entity an earlier
     * failed rollback left owed a delete.
     */
    static void record(StackResource r, ProvisionContext ctx, Map<String, String> attributesBeforeProvision) {
        ObjectNode cleanup = readOrEmpty(r);
        cleanup.remove("priorPhysicalId");
        cleanup.remove("priorAttributes");
        cleanup.put("region", ctx.region());
        String prior = ctx.priorPhysicalId();
        if (ctx.isUpdate() && !prior.equals(r.getPhysicalId())) {
            cleanup.put("priorPhysicalId", prior);
            ObjectNode priorAttributes = cleanup.putObject("priorAttributes");
            attributesBeforeProvision.forEach((key, value) -> {
                if (!key.startsWith("__Floci") && value != null) {
                    priorAttributes.put(key, value);
                }
            });
            addDisplaced(cleanup, prior, r.getResourceType(), ctx.region(), true);
        }
        write(r, cleanup);
    }

    /**
     * Undoes this update's replacement when a later resource failed the stack update: the resource
     * names the prior entity again, with the attributes it had, and the replacement is deleted. The
     * prior entity was displaced, never changed, so nothing is written to it.
     *
     * <p>The prior leaves the delete list before the replacement's delete is attempted: it is
     * standing again, and a resource still owing its delete would put it on the next cleanup's list.
     * A replacement whose delete fails is listed in its place, never retained (a failed update
     * created it), so the next committed update or the stack delete removes it, and the failure
     * propagates so the stack reports it. Returns false when this update replaced nothing, which
     * leaves the engine's handling of in-place updates unchanged.
     */
    static boolean rollback(StackResource r, Deleter deleter) {
        ObjectNode cleanup = read(r);
        if (cleanup == null || cleanup.path("priorPhysicalId").asText(null) == null) {
            return false;
        }
        String prior = cleanup.path("priorPhysicalId").asText();
        String replacement = r.getPhysicalId();
        r.setPhysicalId(prior);
        Iterator<String> keys = r.getAttributes().keySet().iterator();
        while (keys.hasNext()) {
            if (!keys.next().startsWith("__Floci")) {
                keys.remove();
            }
        }
        cleanup.path("priorAttributes").fields()
                .forEachRemaining(e -> r.getAttributes().put(e.getKey(), e.getValue().asText()));
        cleanup.remove("priorPhysicalId");
        cleanup.remove("priorAttributes");
        removeDisplaced(cleanup, prior);
        write(r, cleanup);
        if (prior.equals(replacement)) {
            return true;
        }
        try {
            deleter.delete(r.getResourceType(), replacement, region(cleanup));
        } catch (RuntimeException deleteFailure) {
            addDisplaced(cleanup, replacement, r.getResourceType(), region(cleanup), false);
            write(r, cleanup);
            throw deleteFailure;
        }
        return true;
    }

    static boolean hasReplacement(StackResource r) {
        ObjectNode cleanup = read(r);
        return cleanup != null && cleanup.path("displaced").size() > 0;
    }

    /**
     * The entity the cleanup is working on, or null when none is owed or {@code UpdateReplacePolicy:
     * Retain} keeps it. The engine announces one physical id per resource, so a resource owing more
     * than one names the one with the fewest attempts, the same entity {@link #complete} reports,
     * so the progress and failure events of one cleanup name the same thing.
     */
    static String cleanupPhysicalId(StackResource r) {
        ObjectNode cleanup = read(r);
        if (cleanup == null) {
            return null;
        }
        JsonNode next = nextOwed(cleanup.path("displaced"), r.getPhysicalId(), "Retain".equals(r.getUpdateReplacePolicy()));
        return next == null ? null : next.path("physicalId").asText(null);
    }

    /**
     * The owed entry with the fewest attempts, ties by list order: the one the engine's retries are
     * still for. Skips the resource's own entity and, under Retain, the entities a committed
     * replacement displaced.
     */
    private static JsonNode nextOwed(JsonNode displaced, String currentPhysicalId, boolean retain) {
        JsonNode next = null;
        for (JsonNode entry : displaced) {
            String physicalId = entry.path("physicalId").asText(null);
            if (physicalId == null || physicalId.equals(currentPhysicalId)
                    || (retain && entry.path("retainable").asBoolean(false))) {
                continue;
            }
            if (next == null || entry.path("cleanupAttempts").asInt(0) < next.path("cleanupAttempts").asInt(0)) {
                next = entry;
            }
        }
        return next;
    }

    /**
     * Drops what the cleanup is done with: the record when nothing is owed any more, and otherwise
     * only the entries that used up their attempts. The engine calls this once the resource's
     * cleanup either completed or gave up, and giving up on one entity must not forget another
     * that still has attempts left; that one stays owed for the next committed update or the
     * stack delete.
     */
    static void clear(StackResource r) {
        ObjectNode cleanup = read(r);
        if (cleanup == null) {
            return;
        }
        ArrayNode displaced = cleanup.withArray("displaced");
        ArrayNode kept = MAPPER.createArrayNode();
        for (JsonNode entry : displaced) {
            if (entry.path("cleanupAttempts").asInt(0) < MAX_ATTEMPTS) {
                kept.add(entry);
            }
        }
        cleanup.set("displaced", kept);
        cleanup.remove("priorPhysicalId");
        cleanup.remove("priorAttributes");
        write(r, cleanup);
    }

    private static void drop(StackResource r) {
        r.getAttributes().remove(CfnRollback.REPLACEMENT_CLEANUP_ATTR);
    }

    /**
     * One delete attempt for every entity still owed one. An entity whose delete succeeds leaves
     * the list; one whose delete throws keeps its attempt count and last failure, so the caller's
     * retries continue where this one stopped and no entity is left untried because another kept
     * failing. The result reports the lowest attempt count among what remains: the engine retries
     * while that is below three, so it stops only once every remaining entity has used up its own
     * attempts. {@code UpdateReplacePolicy: Retain} keeps the entity a committed replacement
     * displaced, not a replacement a failed update created.
     */
    static UpdateCleanupResult complete(StackResource r, Deleter deleter) {
        ObjectNode cleanup = read(r);
        if (cleanup == null) {
            return UpdateCleanupResult.notApplicable();
        }
        boolean retain = "Retain".equals(r.getUpdateReplacePolicy());
        ArrayNode displaced = cleanup.withArray("displaced");
        ArrayNode remaining = MAPPER.createArrayNode();
        for (JsonNode node : displaced) {
            ObjectNode entry = (ObjectNode) node;
            String physicalId = entry.path("physicalId").asText(null);
            if (physicalId == null || physicalId.equals(r.getPhysicalId())
                    || (retain && entry.path("retainable").asBoolean(false))) {
                continue;
            }
            int entryAttempts = entry.path("cleanupAttempts").asInt(0);
            if (entryAttempts < MAX_ATTEMPTS) {
                try {
                    deleter.delete(entry.path("resourceType").asText(r.getResourceType()), physicalId,
                            entry.path("region").asText(null));
                    continue;
                } catch (RuntimeException deleteFailure) {
                    entryAttempts++;
                    entry.put("cleanupAttempts", entryAttempts);
                    entry.put("cleanupFailureReason", deleteFailure.getMessage());
                }
            }
            remaining.add(entry);
        }
        cleanup.set("displaced", remaining);
        write(r, cleanup);
        if (remaining.isEmpty()) {
            return new UpdateCleanupResult(true, true, firstDisplaced(displaced), 0, null);
        }
        // The result names the entity with the fewest attempts, the one the engine's retries are
        // still for and the one cleanupPhysicalId announced, so both events point at the same thing.
        JsonNode next = nextOwed(remaining, r.getPhysicalId(), retain);
        return new UpdateCleanupResult(true, false, next.path("physicalId").asText(null),
                next.path("cleanupAttempts").asInt(0), next.path("cleanupFailureReason").asText(null));
    }

    private static String firstDisplaced(ArrayNode displaced) {
        return displaced.isEmpty() ? null : displaced.get(0).path("physicalId").asText(null);
    }

    private static void addDisplaced(ObjectNode cleanup, String physicalId, String resourceType, String region,
                                     boolean retainable) {
        ArrayNode displaced = cleanup.withArray("displaced");
        for (JsonNode entry : displaced) {
            if (physicalId.equals(entry.path("physicalId").asText(null))) {
                return;
            }
        }
        ObjectNode entry = displaced.addObject();
        entry.put("physicalId", physicalId);
        entry.put("resourceType", resourceType);
        entry.put("region", region);
        entry.put("retainable", retainable);
        entry.put("cleanupAttempts", 0);
    }

    private static void removeDisplaced(ObjectNode cleanup, String physicalId) {
        ArrayNode displaced = cleanup.withArray("displaced");
        List<JsonNode> kept = new ArrayList<>();
        for (JsonNode entry : displaced) {
            if (!physicalId.equals(entry.path("physicalId").asText(null))) {
                kept.add(entry);
            }
        }
        displaced.removeAll();
        kept.forEach(displaced::add);
    }

    private static String region(ObjectNode cleanup) {
        return cleanup.path("region").asText(null);
    }

    private static void write(StackResource r, ObjectNode cleanup) {
        if (cleanup.path("displaced").size() == 0 && cleanup.path("priorPhysicalId").asText(null) == null) {
            drop(r);
            return;
        }
        r.getAttributes().put(CfnRollback.REPLACEMENT_CLEANUP_ATTR, cleanup.toString());
    }

    private static ObjectNode readOrEmpty(StackResource r) {
        ObjectNode cleanup = read(r);
        return cleanup == null ? MAPPER.createObjectNode() : cleanup;
    }

    private static ObjectNode read(StackResource r) {
        String raw = r.getAttributes().get(CfnRollback.REPLACEMENT_CLEANUP_ATTR);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            return node.isObject() ? (ObjectNode) node : null;
        } catch (JsonProcessingException e) {
            LOG.warnv("Unreadable replacement cleanup record on {0}, dropping it: {1}", r.getLogicalId(), e.getMessage());
            drop(r);
            return null;
        }
    }
}
