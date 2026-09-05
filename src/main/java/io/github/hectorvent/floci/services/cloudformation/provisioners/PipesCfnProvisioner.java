package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.pipes.PipesService;
import io.github.hectorvent.floci.services.pipes.model.DesiredState;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Provisions {@code AWS::Pipes::Pipe}. */
@ApplicationScoped
public class PipesCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(PipesCfnProvisioner.class);

    private static final int PIPE_NAME_MAX_LENGTH = 64;

    private final PipesService pipesService;
    private final ObjectMapper objectMapper;

    @Inject
    public PipesCfnProvisioner(PipesService pipesService, ObjectMapper objectMapper) {
        this.pipesService = pipesService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::Pipes::Pipe");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight. The one an earlier update left behind goes
        // before this run decides whether it mutates the pipe at all, so a rollback never puts back
        // a target the pipe stopped carrying two updates ago. The rename cleanup goes with it: only
        // a rename this run performs owes a pipe deleted after the update commits.
        r.getAttributes().remove(CfnRollback.PIPE_UPDATE_SNAPSHOT_ATTR);
        r.getAttributes().remove(CfnRollback.PIPE_RENAME_CLEANUP_ATTR);
        String priorPhysicalId = ctx.priorPhysicalId();
        String name = ctx.stablePhysicalName(ctx.resolveOptional(props, "Name"),
                r.getLogicalId(), PIPE_NAME_MAX_LENGTH, false);

        String source = ctx.resolveOptional(props, "Source");
        String target = ctx.resolveOptional(props, "Target");
        String roleArn = ctx.resolveOptional(props, "RoleArn");
        String description = ctx.resolveOptional(props, "Description");
        String enrichment = ctx.resolveOptional(props, "Enrichment");

        String stateStr = ctx.resolveOptional(props, "DesiredState");
        DesiredState desiredState = "STOPPED".equals(stateStr) ? DesiredState.STOPPED : DesiredState.RUNNING;

        JsonNode sourceParameters = resolvedObject(props, "SourceParameters", ctx);
        JsonNode targetParameters = resolvedObject(props, "TargetParameters", ctx);
        JsonNode enrichmentParameters = resolvedObject(props, "EnrichmentParameters", ctx);

        Map<String, String> tags = parseCfnTags(props != null ? props.get("Tags") : null, ctx);

        // provision is also the update path. createPipe throws ConflictException on an existing
        // name, and stablePhysicalName keeps that name steady across updates, so a second
        // UpdateStack must reconcile the pipe rather than recreate it. A replacing update derives a
        // different name and still creates, which is why this asks reusesPriorEntity rather than
        // isUpdate. Null here is a first create, a rename, or a pipe deleted out of band since the
        // prior deploy, and all three create.
        Pipe existingPipe = ctx.reusesPriorEntity(name) ? pipeOnFile(name, ctx.region()) : null;

        Pipe pipe = existingPipe != null
                ? updateExistingPipe(r, existingPipe, name, source, target, roleArn, description,
                        desiredState, enrichment, sourceParameters, targetParameters,
                        enrichmentParameters, tags, ctx)
                : createPipeAndRecordRenamedPrior(r, name, source, target, roleArn, description,
                        desiredState, enrichment, sourceParameters, targetParameters,
                        enrichmentParameters, tags, priorPhysicalId, ctx);

        // Ref returns the pipe name; Fn::GetAtt Arn returns the pipe ARN.
        r.setPhysicalId(name);
        r.getAttributes().put("Arn", pipe.getArn());
    }

    /**
     * Reconciles the pipe already on file under this name, tags included.
     *
     * <p>updatePipe and the tag calls each write the pipe as they go, so a failure among them would
     * otherwise leave the pipe carrying an update the stack is about to disown. This path therefore
     * restores eagerly: the pipe goes back to the snapshot taken a line earlier before the failure
     * leaves the provisioner, and the failure itself is what reaches the caller.
     */
    private Pipe updateExistingPipe(StackResource r, Pipe existingPipe, String name, String source,
                                    String target, String roleArn, String description,
                                    DesiredState desiredState, String enrichment,
                                    JsonNode sourceParameters, JsonNode targetParameters,
                                    JsonNode enrichmentParameters, Map<String, String> tags,
                                    ProvisionContext ctx) {
        // A Source that is absent, null or resolves blank is a missing required property, not a
        // changed one. The create path reports it as such, so this path reports it identically
        // instead of blaming a replacement the template never asked for.
        if (source == null || source.isBlank()) {
            throw new AwsException("ValidationException", "Source is required", 400);
        }
        // Source is a createOnly property. With the name reused there is no replacement to move to,
        // which is the update CloudFormation refuses for a custom-named resource, so it is refused
        // here rather than silently kept on the old source. The 13 create-only paths nested under
        // SourceParameters (the StartingPosition, TopicName, QueueName, VirtualHost,
        // ConsumerGroupID and AdditionalBootstrapServers entries) are reconciled in place, where
        // AWS replaces the pipe. That gap is left to a later change.
        if (!source.equals(existingPipe.getSource())) {
            throw new AwsException("ValidationError",
                    "Updating Source requires resource replacement, which is not supported.", 400);
        }
        snapshotPipeBeforeUpdate(r, existingPipe, ctx.region());
        try {
            Pipe pipe = pipesService.updatePipe(name, target, roleArn, description, desiredState,
                    enrichment, sourceParameters, targetParameters, enrichmentParameters,
                    ctx.region());
            reconcileTags(pipe, tags, ctx.region());
            return pipe;
        } catch (RuntimeException updateFailure) {
            restorePipeAfterFailedUpdate(r, updateFailure);
            throw updateFailure;
        }
    }

    /**
     * Puts the pipe back from the snapshot this update took, while the failure that interrupted the
     * update is on its way out of the provisioner. The snapshot is spent here, so the rollback hook
     * finds nothing left to act on for a resource already restored.
     *
     * <p>A restore that fails itself leaves the pipe in a configuration nobody recorded. Its reason
     * goes on the resource under {@link CfnRollback#UPDATE_ROLLBACK_FAILURE_ATTR}, which is what
     * makes the stack report UPDATE_ROLLBACK_FAILED naming this resource instead of a clean
     * rollback, and it is attached to the update failure as suppressed so neither is lost. The
     * restore repeats the calls the update just made, so it can come back carrying the very
     * exception that interrupted the update; suppressing a throwable under itself is rejected by
     * the JDK, and that one is already the failure being reported.
     */
    private void restorePipeAfterFailedUpdate(StackResource r, RuntimeException updateFailure) {
        String rawSnapshot = r.getAttributes().get(CfnRollback.PIPE_UPDATE_SNAPSHOT_ATTR);
        try {
            restoreSnapshottedPipe(r, objectMapper.readTree(rawSnapshot));
        } catch (RuntimeException | JsonProcessingException restoreFailure) {
            if (restoreFailure != updateFailure) {
                updateFailure.addSuppressed(restoreFailure);
            }
            String reason = restoreFailure.getMessage() != null
                    ? restoreFailure.getMessage()
                    : restoreFailure.getClass().getSimpleName();
            LOG.errorv("Could not restore pipe {0} after a failed update: {1}",
                    r.getPhysicalId(), reason);
            r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR, reason);
        }
    }

    /**
     * Records what the pipe carries before updatePipe and the tag calls change it, so it can be put
     * back when the stack update fails: by {@link #restorePipeAfterFailedUpdate} when this pipe's
     * own update is what failed, and by {@link #rollbackUpdate} when a later resource is. Only the
     * properties
     * updatePipe accepts, plus the tags: Name, Source and Arn cannot change on this path. The
     * region travels with them because the rollback hook is handed the stack resource alone, and
     * name plus region is how PipesService addresses a pipe.
     */
    private void snapshotPipeBeforeUpdate(StackResource r, Pipe existingPipe, String region) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("region", region);
        snapshot.put("target", existingPipe.getTarget());
        snapshot.put("roleArn", existingPipe.getRoleArn());
        snapshot.put("description", existingPipe.getDescription());
        snapshot.put("desiredState", existingPipe.getDesiredState() == null
                ? null : existingPipe.getDesiredState().name());
        snapshot.put("enrichment", existingPipe.getEnrichment());
        snapshot.set("sourceParameters", existingPipe.getSourceParameters());
        snapshot.set("targetParameters", existingPipe.getTargetParameters());
        snapshot.set("enrichmentParameters", existingPipe.getEnrichmentParameters());
        snapshot.set("tags", objectMapper.valueToTree(
                existingPipe.getTags() == null ? Map.of() : existingPipe.getTags()));
        r.getAttributes().put(CfnRollback.PIPE_UPDATE_SNAPSHOT_ATTR, snapshot.toString());    }

    /**
     * Puts the pipe back to what it carried before this update, under either shape the update takes:
     * a reconciliation in place, undone from the snapshot, or a rename, undone by pointing the
     * resource at the prior pipe again. A run performs one or the other, so the two never meet on
     * the same resource.
     *
     * <p>This is the hook for an update a <em>later</em> resource failed: this pipe's own update
     * committed, so its snapshot is still unspent and holds the configuration to put back. When the
     * pipe's own update is what failed, {@link #restorePipeAfterFailedUpdate} has already restored
     * it inside {@code provision} and spent the snapshot there, and the service reaches this hook
     * for that resource neither by the restored marker nor by the rollback-failure one.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        String rawSnapshot = resource.getAttributes().get(CfnRollback.PIPE_UPDATE_SNAPSHOT_ATTR);
        if (rawSnapshot != null) {
            try {
                restoreSnapshottedPipe(resource, objectMapper.readTree(rawSnapshot));
            } catch (JsonProcessingException unreadableSnapshot) {
                throw new IllegalStateException("Could not read the pipe update snapshot for "
                        + resource.getLogicalId(), unreadableSnapshot);
            }
            return true;
        }
        JsonNode cleanup = renameCleanup(resource);
        if (cleanup == null) {
            return false;
        }
        restoreRenamedPipe(resource, cleanup);
        return true;
    }

    /**
     * Undoes a rename: the resource names the prior pipe again and the replacement this update
     * created is deleted, so the failed update leaves nothing behind. The prior pipe is only read,
     * never written: the rename displaced it without changing it.
     *
     * <p>The rename cleanup is spent here. It owes a delete only once the stack update commits, and
     * a rollback is the update never committing, so leaving it on the resource would put the pipe
     * this rollback just restored on the next cleanup's delete list.
     *
     * <p>The resource names the prior pipe and the cleanup is spent before the delete is attempted,
     * because that delete can fail and its failure leaves the provisioner. A resource still naming
     * the replacement, with the cleanup still on it, is one the next update deletes the prior pipe
     * for, which is the pipe this rollback exists to keep. The delete failing therefore leaves the
     * replacement orphaned and the prior pipe standing, and the stack reports the failure.
     */
    private void restoreRenamedPipe(StackResource resource, JsonNode cleanup) {
        String priorPipeName = cleanup.path("priorPipeName").asText(null);
        String region = cleanup.path("region").asText(null);
        Pipe priorPipe = pipeOnFile(priorPipeName, region);
        if (priorPipe == null) {
            throw new IllegalStateException("Cannot roll back resource " + resource.getLogicalId()
                    + ": the pipe " + priorPipeName + " it was renamed from is no longer on file.");
        }
        String replacementPipeName = resource.getPhysicalId();
        resource.setPhysicalId(priorPipeName);
        resource.getAttributes().put("Arn", priorPipe.getArn());
        resource.getAttributes().remove(CfnRollback.PIPE_RENAME_CLEANUP_ATTR);
        deleteReplacementThisUpdateCreated(resource, replacementPipeName, cleanup, region);
    }

    /**
     * Deletes the pipe the failed update created under the new name, and only that one. A pipe
     * standing under that name whose creation time is not the one recorded when this update created
     * it belongs to someone else, so the rollback refuses and leaves both pipes where they are
     * rather than guess.
     *
     * <p>{@code UpdateReplacePolicy: Retain} does not reach this delete. It governs the resource a
     * committed replacement displaces, which here is the prior pipe, and this path keeps that pipe
     * either way by making it the resource's physical id again. The replacement is a resource this
     * update created, which a rollback removes like any other.
     *
     * <p>The replacement is named by the caller: the resource already points at the prior pipe by
     * the time this runs, so its physical id no longer holds the name to delete.
     */
    private void deleteReplacementThisUpdateCreated(StackResource resource,
                                                    String replacementPipeName, JsonNode cleanup,
                                                    String region) {
        Pipe replacement = pipeOnFile(replacementPipeName, region);
        if (replacement == null) {
            // Already deleted out of band: there is nothing left to remove and nothing to identify.
            return;
        }
        long createdAtEpochMilli = cleanup.path("replacementCreatedAtEpochMilli").asLong(-1);
        if (replacement.getCreationTime() == null
                || replacement.getCreationTime().toEpochMilli() != createdAtEpochMilli) {
            throw new IllegalStateException("Refusing to delete pipe " + replacementPipeName
                    + " while rolling back resource " + resource.getLogicalId()
                    + ": it was not created by this stack update, so both pipes are left as they are.");
        }
        pipesService.deletePipe(replacementPipeName, region);
    }

    /**
     * Puts the pipe back to the configuration the snapshot holds and spends the snapshot. Tags go
     * through the same reconciliation the update uses.
     *
     * <p>The snapshot holds the whole configuration, so a property the pipe did not carry is
     * cleared: restorePipe writes every property as the snapshot has it, and what the failed update
     * added does not survive the rollback.
     */
    private void restoreSnapshottedPipe(StackResource resource, JsonNode snapshot) {
        String region = snapshot.get("region").asText();
        String desiredState = snapshotText(snapshot, "desiredState");
        Pipe restored = pipesService.restorePipe(resource.getPhysicalId(),
                snapshotText(snapshot, "target"),
                snapshotText(snapshot, "roleArn"),
                snapshotText(snapshot, "description"),
                desiredState == null ? null : DesiredState.valueOf(desiredState),
                snapshotText(snapshot, "enrichment"),
                snapshotValue(snapshot, "sourceParameters"),
                snapshotValue(snapshot, "targetParameters"),
                snapshotValue(snapshot, "enrichmentParameters"),
                region);
        Map<String, String> tags = new HashMap<>();
        snapshot.path("tags").fields().forEachRemaining(
                tag -> tags.put(tag.getKey(), tag.getValue().asText()));
        reconcileTags(restored, tags, region);
        resource.getAttributes().remove(CfnRollback.PIPE_UPDATE_SNAPSHOT_ATTR);
    }

    /** A snapshotted property, or null when the pipe carried none. */
    private static JsonNode snapshotValue(JsonNode snapshot, String property) {
        JsonNode value = snapshot.get(property);
        return value == null || value.isNull() ? null : value;
    }

    private static String snapshotText(JsonNode snapshot, String property) {
        JsonNode value = snapshotValue(snapshot, property);
        return value == null ? null : value.asText();
    }

    /**
     * Creates the pipe, and on a rename records the pipe the prior deploy left under the old name
     * so {@link #completeUpdate} deletes it once the stack update has committed. Nothing is deleted
     * here: the prior pipe outlives this call, which is what lets a rollback of a later resource
     * failure still find it.
     *
     * <p>A createPipe that throws therefore leaves the original intact, and the resource is marked
     * as already restored: rollback does not restore what was never deleted.
     */
    private Pipe createPipeAndRecordRenamedPrior(StackResource r, String name, String source,
                                                 String target, String roleArn, String description,
                                                 DesiredState desiredState, String enrichment,
                                                 JsonNode sourceParameters, JsonNode targetParameters,
                                                 JsonNode enrichmentParameters,
                                                 Map<String, String> tags, String priorPhysicalId,
                                                 ProvisionContext ctx) {
        Pipe preservedPriorPipe = priorPhysicalId != null && !priorPhysicalId.equals(name)
                ? pipeOnFile(priorPhysicalId, ctx.region())
                : null;
        Pipe pipe;
        try {
            pipe = pipesService.createPipe(name, source, target, roleArn, description, desiredState,
                    enrichment, sourceParameters, targetParameters, enrichmentParameters, tags,
                    ctx.region());
        } catch (RuntimeException failure) {
            if (preservedPriorPipe != null) {
                // The prior pipe is untouched: the rename records its deletion for the committed
                // update cleanup, which this failure means the stack never reaches.
                r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR, "true");
            }
            throw failure;
        }
        if (preservedPriorPipe != null) {
            recordRenamedPriorPipe(r, priorPhysicalId, pipe, ctx.region());
        }
        return pipe;
    }

    /**
     * Records the pipe this rename displaced, with the region that addresses it, the attempt count
     * {@link #completeUpdate} carries across its retries, and the moment the replacement was
     * created, which is how {@link #rollbackUpdate} tells the pipe this run created from one that
     * already stood under the same name.
     */
    private void recordRenamedPriorPipe(StackResource r, String priorPhysicalId, Pipe replacement,
                                        String region) {
        ObjectNode cleanup = objectMapper.createObjectNode();
        cleanup.put("priorPipeName", priorPhysicalId);
        cleanup.put("region", region);
        cleanup.put("cleanupAttempts", 0);
        cleanup.put("replacementCreatedAtEpochMilli", replacement.getCreationTime() == null
                ? -1 : replacement.getCreationTime().toEpochMilli());
        r.getAttributes().put(CfnRollback.PIPE_RENAME_CLEANUP_ATTR, cleanup.toString());
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        if ("Retain".equals(resource.getUpdateReplacePolicy())) {
            return null;
        }
        JsonNode cleanup = renameCleanup(resource);
        return cleanup == null ? null : cleanup.path("priorPipeName").asText(null);
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        JsonNode cleanup = renameCleanup(resource);
        return cleanup != null && cleanup.path("priorPipeName").asText(null) != null;
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(CfnRollback.PIPE_RENAME_CLEANUP_ATTR);
    }

    /**
     * Deletes the pipe the rename displaced, one attempt per call, now that the stack update has
     * committed. The attempt count and the last failure are written back onto the resource, so the
     * caller's retries pick up where this one left off.
     */
    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        JsonNode cleanup = renameCleanup(resource);
        if (cleanup == null) {
            return UpdateCleanupResult.notApplicable();
        }
        String priorPipeName = cleanup.path("priorPipeName").asText(null);
        String region = cleanup.path("region").asText(null);
        if (priorPipeName == null || priorPipeName.equals(resource.getPhysicalId())) {
            return new UpdateCleanupResult(true, true, priorPipeName, 0, null);
        }
        if ("Retain".equals(resource.getUpdateReplacePolicy())) {
            return new UpdateCleanupResult(true, true, priorPipeName, 0, null);
        }

        int attempts = cleanup.path("cleanupAttempts").asInt(0);
        String failureReason = cleanup.path("cleanupFailureReason").asText(null);
        if (attempts >= 3) {
            return new UpdateCleanupResult(true, false, priorPipeName, attempts, failureReason);
        }
        try {
            if (pipeOnFile(priorPipeName, region) != null) {
                pipesService.deletePipe(priorPipeName, region);
            }
            return new UpdateCleanupResult(true, true, priorPipeName, attempts, null);
        } catch (RuntimeException deleteFailure) {
            attempts++;
            ((ObjectNode) cleanup).put("cleanupAttempts", attempts);
            ((ObjectNode) cleanup).put("cleanupFailureReason", deleteFailure.getMessage());
            resource.getAttributes().put(CfnRollback.PIPE_RENAME_CLEANUP_ATTR, cleanup.toString());
            return new UpdateCleanupResult(
                    true, false, priorPipeName, attempts, deleteFailure.getMessage());
        }
    }

    /** The rename cleanup recorded on this resource, or null when no rename displaced a pipe. */
    private JsonNode renameCleanup(StackResource resource) {
        String rawCleanup = resource.getAttributes().get(CfnRollback.PIPE_RENAME_CLEANUP_ATTR);
        if (rawCleanup == null) {
            return null;
        }
        try {
            return objectMapper.readTree(rawCleanup);
        } catch (JsonProcessingException unreadableCleanup) {
            throw new IllegalStateException("Could not read the pipe rename cleanup for "
                    + resource.getLogicalId(), unreadableCleanup);
        }
    }

    /**
     * The pipe on file under this name, or null when it is not there. Only the not-found error
     * yields null: any other failure reaches the user as itself, instead of sending the caller into
     * the create arm to report ConflictException over an unrelated fault.
     */
    private Pipe pipeOnFile(String name, String region) {
        try {
            return pipesService.describePipe(name, region);
        } catch (AwsException lookupFailure) {
            if (!"NotFoundException".equals(lookupFailure.getErrorCode())
                    && lookupFailure.getHttpStatus() != 404) {
                throw lookupFailure;
            }
            // Expected when the pipe was deleted out of band since the prior deploy, and on the
            // rename arm when the prior pipe is already gone; both fall back to a plain create.
            LOG.debugv(lookupFailure, "No pipe {0} found on file", name);
            return null;
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        pipesService.deletePipe(physicalId, region);
    }

    private JsonNode resolvedObject(JsonNode props, String name, ProvisionContext ctx) {
        if (props == null || !props.has(name) || props.get(name).isNull()) {
            return null;
        }
        return ctx.engine().resolveNode(props.get(name));
    }

    /**
     * UpdatePipe carries no Tags (only CreatePipe does), so on the update path the template's tags
     * are driven to their desired state through TagResource and UntagResource, keyed by the pipe's
     * ARN. A key the template dropped is untagged rather than left over.
     */
    private void reconcileTags(Pipe pipe, Map<String, String> desired, String region) {
        List<String> stale = ProvisionContext.staleTagKeys(
                pipesService.listTags(region, pipe.getArn()), desired);
        if (!stale.isEmpty()) {
            pipesService.untagResource(region, pipe.getArn(), stale);
        }
        if (!desired.isEmpty()) {
            pipesService.tagResource(region, pipe.getArn(), desired);
        }
    }

    /** See {@code KmsCfnProvisioner#parseCfnTags} for why this is copied rather than shared. */
    private Map<String, String> parseCfnTags(JsonNode tagsNode, ProvisionContext ctx) {
        Map<String, String> out = new HashMap<>();
        if (tagsNode == null || tagsNode.isNull() || !tagsNode.isArray()) {
            return out;
        }
        for (JsonNode entry : tagsNode) {
            JsonNode resolved = ctx.engine().resolveNode(entry);
            String key = resolved.path("Key").asText(null);
            String value = resolved.path("Value").asText("");
            if (key != null) {
                out.put(key, value);
            }
        }
        return out;
    }
}
