package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.batch.BatchService;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * CloudFormation provisioning for Batch: {@code AWS::Batch::ComputeEnvironment},
 * {@code AWS::Batch::JobQueue} and {@code AWS::Batch::JobDefinition}. Extracted from
 * {@code CloudFormationResourceProvisioner}.
 *
 * <p>The template property names are PascalCase and {@link BatchService} speaks the wire
 * shape's camelCase, so every arm builds a request node rather than passing properties
 * through.
 */
@ApplicationScoped
public class BatchCfnProvisioner implements CfnResourceProvisioner {

    private static final int NAME_MAX_LENGTH = 128;
    private static final int PRIORITY_MIN = 0;
    private static final int PRIORITY_MAX = 1000;

    // Held rather than injected: AGENTS.md has a provisioner inject only the service it wraps, and
    // the snapshot below needs nothing the configured mapper adds.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BatchService batchService;

    @Inject
    public BatchCfnProvisioner(BatchService batchService) {
        this.batchService = batchService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::Batch::ComputeEnvironment",
                "AWS::Batch::JobQueue",
                "AWS::Batch::JobDefinition");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(CfnRollback.BATCH_UPDATE_SNAPSHOT_ATTR);
        // Taken before the arms run: they overwrite the recorded name, and the prior-entity check
        // reads it, so a decision made afterwards would compare the new name against itself.
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        switch (r.getResourceType()) {
            case "AWS::Batch::ComputeEnvironment" -> provisionComputeEnvironment(r, props, ctx, attributesBefore);
            case "AWS::Batch::JobQueue" -> provisionJobQueue(r, props, ctx, attributesBefore);
            case "AWS::Batch::JobDefinition" -> provisionJobDefinition(r, props, ctx, attributesBefore);
            default -> throw new IllegalStateException(
                    "BatchCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    /**
     * Whether this update replaced the physical entity, so the displaced one is still owed a
     * delete once the update commits. Without these four hooks a renamed compute environment or
     * job queue stayed live in Batch forever: the engine was never told a replacement happened.
     */
    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(CfnRollback.BATCH_UPDATE_SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * Undoes a Batch update when a later resource fails the same stack update. A replacement is
     * undone through the cleanup record, which points the resource back at the prior entity and
     * deletes the one this update created. Otherwise the update was in place, and the snapshot
     * taken before the mutating call is replayed through the same update call that changed it.
     *
     * <p>The snapshot is spent only once the restore succeeded: a restore that throws leaves it in
     * place for the next attempt rather than reporting a rollback that never happened. A resource
     * this update never touched carries no snapshot and answers false, so the engine keeps
     * reporting honestly instead of claiming a rollback it did not perform.
     *
     * <p><strong>Known limitation, compute environments only.</strong> The restore is exact for
     * every value the environment already carried, and cannot remove one the failed update
     * introduced. A {@code serviceRole}, or a {@code computeResources} key, that was absent before
     * the update is absent from the snapshot too, so the restore call simply omits it, and
     * UpdateComputeEnvironment leaves an omitted field alone and merges {@code computeResources}
     * rather than replacing it. The value therefore survives a rollback the stack reports as
     * successful.
     *
     * <p>This is not a gap that can be closed here. UpdateComputeEnvironment is the only way to
     * put the environment back, and its request shape has no removal: every member other than
     * {@code computeEnvironment} is an optional set-value, and the partial-merge semantics are
     * AWS's own. Recorded rather than worked around, in the way
     * {@code CognitoCfnProvisioner} records its own in-place rollback limitation, and pinned by
     * {@code BatchCfnProvisionerTest.aRollbackCannotRemoveAValueTheFailedUpdateAdded}.
     *
     * <p>Job queues are not affected: {@code updateJobQueue} assigns
     * {@code computeEnvironmentOrder} wholesale rather than merging it, and {@code state} and
     * {@code priority} are always present, so that restore is exact.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        String raw = resource.getAttributes().get(CfnRollback.BATCH_UPDATE_SNAPSHOT_ATTR);
        if (raw == null || raw.isBlank()) {
            return false;
        }
        JsonNode snapshot;
        try {
            snapshot = MAPPER.readTree(raw);
        } catch (JsonProcessingException unreadableSnapshot) {
            throw new IllegalStateException("Could not read the Batch update snapshot for "
                    + resource.getLogicalId(), unreadableSnapshot);
        }
        restoreSnapshot(resource, snapshot);
        resource.getAttributes().remove(CfnRollback.BATCH_UPDATE_SNAPSHOT_ATTR);
        return true;
    }

    private void restoreSnapshot(StackResource resource, JsonNode snapshot) {
        String priorId = snapshot.path("physicalId").asText(null);
        switch (snapshot.path("type").asText("")) {
            case "AWS::Batch::ComputeEnvironment" -> {
                ObjectNode update = JsonNodeFactory.instance.objectNode();
                update.put("computeEnvironment", priorId);
                copySnapshotted(update, snapshot, "state", "serviceRole", "computeResources");
                batchService.updateComputeEnvironment(update);
            }
            case "AWS::Batch::JobQueue" -> {
                ObjectNode update = JsonNodeFactory.instance.objectNode();
                update.put("jobQueue", priorId);
                copySnapshotted(update, snapshot, "state", "priority", "computeEnvironmentOrder");
                batchService.updateJobQueue(update);
            }
            case "AWS::Batch::JobDefinition" -> {
                // A revision bump leaves the prior revision ACTIVE, as on AWS, so rolling back is
                // deregistering the revision this update registered and naming the prior one again.
                String registered = resource.getPhysicalId();
                if (registered != null && !registered.equals(priorId)) {
                    deregisterJobDefinition(registered);
                }
                resource.setPhysicalId(priorId);
                resource.getAttributes().put("Arn", priorId);
                resource.getAttributes().put("JobDefinitionArn", priorId);
            }
            default -> throw new IllegalStateException(
                    "Unreadable Batch update snapshot on " + resource.getLogicalId());
        }
    }

    private static void copySnapshotted(ObjectNode into, JsonNode snapshot, String... fields) {
        for (String field : fields) {
            if (snapshot.has(field) && !snapshot.get(field).isNull()) {
                into.set(field, snapshot.get(field));
            }
        }
    }

    /**
     * Records what the entity looks like now, before the update call about to change it. The
     * describe is the only source: the template holds the desired state, not the current one.
     *
     * <p>Best effort by design. An entity the describe cannot find leaves no snapshot, and
     * {@code rollbackUpdate} then answers false rather than restoring a guess, which is the honest
     * answer: the engine reports the resource as not rolled back instead of claiming a restore
     * that never had anything to restore from. Failing the update here would be worse, since it
     * would break a working update over missing rollback insurance.
     */
    private void snapshotBeforeUpdate(StackResource r, String type, String physicalId,
                                      String requestKey, Function<ObjectNode, ObjectNode> describe,
                                      String... fields) {
        ObjectNode req = JsonNodeFactory.instance.objectNode();
        req.putArray(requestKey).add(physicalId);
        ObjectNode described = describe.apply(req);
        if (described == null) {
            return;
        }
        JsonNode found = described.path(requestKey);
        if (found.isEmpty()) {
            return;
        }
        JsonNode current = found.get(0);
        ObjectNode snapshot = JsonNodeFactory.instance.objectNode();
        snapshot.put("type", type);
        snapshot.put("physicalId", physicalId);
        copySnapshotted(snapshot, current, fields);
        r.getAttributes().put(CfnRollback.BATCH_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
    }

    /**
     * Batch refuses to delete a running entity, exactly as AWS does: DeleteJobQueue rejects an
     * {@code ENABLED} queue and DeleteComputeEnvironment rejects one that is not {@code DISABLED}
     * or is still referenced by a queue. So each delete disables first, which is what the real
     * resource handlers do. CloudFormation already tears down in reverse dependency order, so a
     * queue is gone before the environment it points at.
     *
     * <p>Deliberately no {@code CfnDeletes.safeDelete} here, and it is the API that rules it out
     * rather than a gap in {@link BatchService}. Batch declares exactly two errors on
     * DeleteComputeEnvironment, DeleteJobQueue and DeregisterJobDefinition, {@code ClientException}
     * (400) and {@code ServerException} (500); there is no not-found code in the service model to
     * name, so there is none to tolerate. {@code safeDelete} takes the specific already-gone code
     * on purpose, and passing {@code ClientException} would be the catch-all AGENTS.md rules out:
     * a real refusal such as "Cannot delete compute environment still associated with a job queue"
     * would be swallowed into a green stack delete instead of failing it. Giving Batch a
     * Floci-only not-found code to satisfy the pattern would put a code on the wire that AWS never
     * sends.
     *
     * <p>Already-gone is handled by looking first instead, which is also what keeps the disable
     * step from throwing on a resource someone removed out of band. {@code EcsCfnProvisioner} does
     * tolerate a blanket {@code ClientException} for its task-definition arm, so the divergence
     * here is deliberate rather than an oversight.
     */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        switch (resourceType) {
            case "AWS::Batch::ComputeEnvironment" -> deleteComputeEnvironment(physicalId);
            case "AWS::Batch::JobQueue" -> deleteJobQueue(physicalId);
            case "AWS::Batch::JobDefinition" -> deregisterJobDefinition(physicalId);
            default -> {
                // no other type reaches this provisioner
            }
        }
    }

    private void deleteComputeEnvironment(String physicalId) {
        if (!exists("computeEnvironments", physicalId,
                req -> batchService.describeComputeEnvironments(req))) {
            return;
        }
        ObjectNode disable = JsonNodeFactory.instance.objectNode();
        disable.put("computeEnvironment", physicalId);
        disable.put("state", "DISABLED");
        batchService.updateComputeEnvironment(disable);

        ObjectNode req = JsonNodeFactory.instance.objectNode();
        req.put("computeEnvironment", physicalId);
        batchService.deleteComputeEnvironment(req);
    }

    private void deleteJobQueue(String physicalId) {
        if (!exists("jobQueues", physicalId, req -> batchService.describeJobQueues(req))) {
            return;
        }
        ObjectNode disable = JsonNodeFactory.instance.objectNode();
        disable.put("jobQueue", physicalId);
        disable.put("state", "DISABLED");
        batchService.updateJobQueue(disable);

        ObjectNode req = JsonNodeFactory.instance.objectNode();
        req.put("jobQueue", physicalId);
        batchService.deleteJobQueue(req);
    }

    private void deregisterJobDefinition(String physicalId) {
        // Unlike the other two, deregister throws when the definition is gone, so the existence
        // check is what makes a repeated stack delete idempotent.
        if (!exists("jobDefinitions", physicalId, req -> batchService.describeJobDefinitions(req))) {
            return;
        }
        ObjectNode req = JsonNodeFactory.instance.objectNode();
        req.put("jobDefinition", physicalId);
        batchService.deregisterJobDefinition(req);
    }

    private boolean exists(String requestKey, String physicalId,
                           Function<ObjectNode, ObjectNode> describe) {
        ObjectNode req = JsonNodeFactory.instance.objectNode();
        req.putArray(requestKey).add(physicalId);
        return !describe.apply(req).path(requestKey).isEmpty();
    }

    private void provisionComputeEnvironment(StackResource r, JsonNode props, ProvisionContext ctx,
                                             Map<String, String> attributesBefore) {
        String name = stableName(r, ctx, props, "ComputeEnvironmentName");
        require("AWS::Batch::ComputeEnvironment", "Type", ctx.resolveOptional(props, "Type"));

        String arn;
        if (reusesPriorEntity(r, ctx, name, "ComputeEnvironmentName")) {
            // UpdateComputeEnvironment is the schema's update handler and takes only these three;
            // ComputeEnvironmentName, Type and Tags are createOnly, so a change to those is a
            // replacement the engine drives, not something to push through here.
            snapshotBeforeUpdate(r, "AWS::Batch::ComputeEnvironment", ctx.priorPhysicalId(),
                    "computeEnvironments", req -> batchService.describeComputeEnvironments(req),
                    "state", "serviceRole", "computeResources");
            ObjectNode update = JsonNodeFactory.instance.objectNode();
            update.put("computeEnvironment", ctx.priorPhysicalId());
            putResolvedText(update, "state", props, "State", ctx);
            putResolvedText(update, "serviceRole", props, "ServiceRole", ctx);
            putResolvedObject(update, "computeResources", props, "ComputeResources", ctx);
            batchService.updateComputeEnvironment(update);
            arn = ctx.priorPhysicalId();
        } else {
            ObjectNode req = JsonNodeFactory.instance.objectNode();
            req.put("computeEnvironmentName", name);
            putResolvedText(req, "type", props, "Type", ctx);
            putResolvedText(req, "state", props, "State", ctx);
            putResolvedText(req, "serviceRole", props, "ServiceRole", ctx);
            putResolvedObject(req, "computeResources", props, "ComputeResources", ctx);
            putTags(req, props, ctx);
            arn = batchService.createComputeEnvironment(req, ctx.region())
                    .path("computeEnvironmentArn").asText();
        }
        r.setPhysicalId(arn);
        r.getAttributes().put("Arn", arn);
        r.getAttributes().put("ComputeEnvironmentArn", arn);
        r.getAttributes().put("ComputeEnvironmentName", name);
        // An in-place update kept the prior ARN, so this records nothing; a rename minted a new
        // one, and the environment it displaced is owed a delete once the update commits.
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    private void provisionJobQueue(StackResource r, JsonNode props, ProvisionContext ctx,
                                   Map<String, String> attributesBefore) {
        String name = stableName(r, ctx, props, "JobQueueName");
        String priority = ctx.resolveOptional(props, "Priority");
        require("AWS::Batch::JobQueue", "Priority", priority);
        int priorityValue = requireInt("AWS::Batch::JobQueue", "Priority", priority);

        String arn;
        if (reusesPriorEntity(r, ctx, name, "JobQueueName")) {
            // UpdateJobQueue is the schema's update handler; JobQueueName and JobQueueType are
            // createOnly, so only these three are pushed through.
            snapshotBeforeUpdate(r, "AWS::Batch::JobQueue", ctx.priorPhysicalId(),
                    "jobQueues", req -> batchService.describeJobQueues(req),
                    "state", "priority", "computeEnvironmentOrder");
            ObjectNode update = JsonNodeFactory.instance.objectNode();
            update.put("jobQueue", ctx.priorPhysicalId());
            putResolvedText(update, "state", props, "State", ctx);
            update.put("priority", priorityValue);
            update.set("computeEnvironmentOrder", computeEnvironmentOrder(props, ctx));
            batchService.updateJobQueue(update);
            arn = ctx.priorPhysicalId();
        } else {
            ObjectNode req = JsonNodeFactory.instance.objectNode();
            req.put("jobQueueName", name);
            req.put("priority", priorityValue);
            putResolvedText(req, "state", props, "State", ctx);
            putResolvedText(req, "jobQueueType", props, "JobQueueType", ctx);
            req.set("computeEnvironmentOrder", computeEnvironmentOrder(props, ctx));
            putTags(req, props, ctx);
            arn = batchService.createJobQueue(req, ctx.region()).path("jobQueueArn").asText();
        }
        r.setPhysicalId(arn);
        r.getAttributes().put("Arn", arn);
        r.getAttributes().put("JobQueueArn", arn);
        r.getAttributes().put("JobQueueName", name);
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    private void provisionJobDefinition(StackResource r, JsonNode props, ProvisionContext ctx,
                                        Map<String, String> attributesBefore) {
        // No update branch: RegisterJobDefinition on an existing name records a new revision,
        // which is how AWS updates a job definition. Only the name has to stay steady.
        String name = stableName(r, ctx, props, "JobDefinitionName");
        // Decided before the register call overwrites the recorded name below.
        boolean sameDefinition = reusesPriorEntity(r, ctx, name, "JobDefinitionName");
        String type = ctx.resolveOptional(props, "Type");
        require("AWS::Batch::JobDefinition", "Type", type);

        ObjectNode req = JsonNodeFactory.instance.objectNode();
        req.put("jobDefinitionName", name);
        req.put("type", type);
        putResolvedArray(req, "platformCapabilities", props, "PlatformCapabilities", ctx);
        if (props != null && props.has("ContainerProperties")) {
            req.set("containerProperties",
                    containerProperties(ctx.engine().resolveNode(props.get("ContainerProperties"))));
        }
        putStringMapFromObject(req, "parameters", props, "Parameters", ctx);
        if (props != null && props.has("RetryStrategy")) {
            req.set("retryStrategy", retryStrategy(ctx.engine().resolveNode(props.get("RetryStrategy"))));
        }
        if (props != null && props.has("Timeout")) {
            ObjectNode timeout = JsonNodeFactory.instance.objectNode();
            JsonNode resolved = ctx.engine().resolveNode(props.get("Timeout"));
            if (resolved.has("AttemptDurationSeconds")) {
                timeout.set("attemptDurationSeconds", resolved.get("AttemptDurationSeconds"));
            }
            req.set("timeout", timeout);
        }
        putTags(req, props, ctx);

        String priorArn = ctx.isUpdate() ? ctx.priorPhysicalId() : null;
        ObjectNode response = batchService.registerJobDefinition(req, ctx.region());
        String arn = response.path("jobDefinitionArn").asText();
        r.setPhysicalId(arn);
        r.getAttributes().put("Arn", arn);
        r.getAttributes().put("JobDefinitionArn", arn);
        r.getAttributes().put("JobDefinitionName", name);

        if (sameDefinition) {
            // A revision bump is not a replacement. The registry schema's primaryIdentifier for
            // this type is JobDefinitionName, not the ARN, and the prior revision stays ACTIVE on
            // AWS: registering revision 2 does not retire revision 1, which a job may still name.
            // Recording it as displaced would have the cleanup deregister a live revision. Only a
            // changed name replaces the entity, which is the branch below.
            //
            // Rolling one back is still possible, and is the snapshot's job: deregister the
            // revision this update registered and name the prior one again.
            ObjectNode snapshot = JsonNodeFactory.instance.objectNode();
            snapshot.put("type", "AWS::Batch::JobDefinition");
            snapshot.put("physicalId", priorArn);
            r.getAttributes().put(CfnRollback.BATCH_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
            return;
        }
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /** The schema's required properties fail the resource with the repo's ValidationError wording. */
    private static void require(String type, String property, String value) {
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError", type + " requires " + property, 400);
        }
    }

    /**
     * A template value the schema types as a bounded integer. Template properties arrive as text,
     * so a non-numeric one reached {@code Integer.parseInt} and left as an uncaught
     * NumberFormatException, which the stack reported as a 500 rather than the failed validation
     * it is. The bounds are the registry schema's own: AWS::Batch::JobQueue Priority is
     * {@code integer, minimum 0, maximum 1000}.
     */
    private static int requireInt(String type, String property, String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException notAnInteger) {
            throw new AwsException("ValidationError",
                    type + " " + property + " must be an integer, but was '" + value + "'", 400);
        }
        if (parsed < PRIORITY_MIN || parsed > PRIORITY_MAX) {
            throw new AwsException("ValidationError", type + " " + property + " must be between "
                    + PRIORITY_MIN + " and " + PRIORITY_MAX + ", but was " + parsed, 400);
        }
        return parsed;
    }

    /**
     * The name to use this time round: the template's, else the one this resource already has,
     * and only failing both a freshly generated one.
     *
     * <p>{@code ctx.stablePhysicalName} does not fit these types. It falls back to the prior
     * physical id, and for Batch that id is an ARN rather than the name, so it would feed an ARN
     * back in as a name. The prior name comes from the attribute recorded at create time instead,
     * the way SqsCfnProvisioner reads QueueName beside the queue URL. Without this, an unnamed
     * resource got a fresh random name on every UpdateStack, creating a second entity and
     * orphaning the first.
     */
    private String stableName(StackResource r, ProvisionContext ctx, JsonNode props, String nameKey) {
        // For all three types the template property and the recorded attribute share a name.
        String explicit = ctx.resolveOptional(props, nameKey);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        String prior = priorName(r, ctx, nameKey);
        if (prior != null && !prior.isBlank()) {
            return prior;
        }
        return ctx.generatePhysicalName(r.getLogicalId(), NAME_MAX_LENGTH, false);
    }

    /**
     * Whether {@code name} is the entity this resource already had, so it must be updated rather
     * than created. {@code ctx.reusesPriorEntity} compares against the physical id, which is the
     * ARN here, so the comparison is made against the recorded name instead. A replacing update
     * arrives with a prior id too but has derived a different name, and must still create.
     */
    private boolean reusesPriorEntity(StackResource r, ProvisionContext ctx, String name, String attribute) {
        return ctx.isUpdate() && name.equals(priorName(r, ctx, attribute));
    }

    /**
     * The recorded name, falling back to the one embedded in the prior ARN for a resource created
     * before that attribute was stored. Batch ARNs end in {@code <kind>/<name>}, and the job
     * definition's also carries {@code :<revision>}.
     */
    private String priorName(StackResource r, ProvisionContext ctx, String attribute) {
        String recorded = r.getAttributes() != null ? r.getAttributes().get(attribute) : null;
        if (recorded != null && !recorded.isBlank()) {
            return recorded;
        }
        String priorId = ctx.priorPhysicalId();
        if (priorId == null || !priorId.startsWith("arn:")) {
            return null;
        }
        int slash = priorId.lastIndexOf('/');
        if (slash < 0 || slash == priorId.length() - 1) {
            return null;
        }
        String tail = priorId.substring(slash + 1);
        int colon = tail.lastIndexOf(':');
        return colon > 0 ? tail.substring(0, colon) : tail;
    }

    private ArrayNode computeEnvironmentOrder(JsonNode props, ProvisionContext ctx) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        if (props == null || !props.has("ComputeEnvironmentOrder")) {
            return out;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get("ComputeEnvironmentOrder"));
        if (!resolved.isArray()) {
            return out;
        }
        for (JsonNode item : resolved) {
            ObjectNode order = out.addObject();
            order.put("order", item.path("Order").asInt());
            order.put("computeEnvironment", item.path("ComputeEnvironment").asText(null));
        }
        return out;
    }

    private ObjectNode containerProperties(JsonNode resolved) {
        ObjectNode container = JsonNodeFactory.instance.objectNode();
        if (resolved == null || !resolved.isObject()) {
            return container;
        }
        copyIfPresent(container, "image", resolved, "Image");
        copyIfPresent(container, "command", resolved, "Command");
        copyIfPresent(container, "jobRoleArn", resolved, "JobRoleArn");
        copyIfPresent(container, "executionRoleArn", resolved, "ExecutionRoleArn");
        copyIfPresent(container, "logConfiguration", resolved, "LogConfiguration");
        copyIfPresent(container, "networkConfiguration", resolved, "NetworkConfiguration");
        copyIfPresent(container, "ephemeralStorage", resolved, "EphemeralStorage");
        if (resolved.has("ResourceRequirements") && resolved.get("ResourceRequirements").isArray()) {
            ArrayNode resources = container.putArray("resourceRequirements");
            for (JsonNode item : resolved.get("ResourceRequirements")) {
                ObjectNode requirement = resources.addObject();
                requirement.put("type", item.path("Type").asText(null));
                requirement.put("value", item.path("Value").asText(null));
            }
        }
        if (resolved.has("Environment") && resolved.get("Environment").isArray()) {
            ArrayNode env = container.putArray("environment");
            for (JsonNode item : resolved.get("Environment")) {
                ObjectNode entry = env.addObject();
                entry.put("name", item.path("Name").asText(null));
                entry.put("value", item.path("Value").asText(null));
            }
        }
        return container;
    }

    private ObjectNode retryStrategy(JsonNode resolved) {
        ObjectNode retry = JsonNodeFactory.instance.objectNode();
        if (resolved == null || !resolved.isObject()) {
            return retry;
        }
        if (resolved.has("Attempts")) {
            retry.set("attempts", resolved.get("Attempts"));
        }
        if (resolved.has("EvaluateOnExit")) {
            retry.set("evaluateOnExit", resolved.get("EvaluateOnExit"));
        }
        return retry;
    }

    private void putTags(ObjectNode req, JsonNode props, ProvisionContext ctx) {
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        if (!tags.isEmpty()) {
            ObjectNode tagNode = req.putObject("tags");
            tags.forEach(tagNode::put);
        }
    }

    private void putResolvedText(ObjectNode req, String target, JsonNode props, String source,
                                 ProvisionContext ctx) {
        String value = ctx.resolveOptional(props, source);
        if (value != null) {
            req.put(target, value);
        }
    }

    private void putResolvedObject(ObjectNode req, String target, JsonNode props, String source,
                                   ProvisionContext ctx) {
        if (props == null || !props.has(source) || props.get(source).isNull()) {
            return;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get(source));
        if (resolved != null && resolved.isObject()) {
            req.set(target, resolved);
        }
    }

    private void putResolvedArray(ObjectNode req, String target, JsonNode props, String source,
                                  ProvisionContext ctx) {
        if (props == null || !props.has(source) || props.get(source).isNull()) {
            return;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get(source));
        if (resolved != null && resolved.isArray()) {
            req.set(target, resolved);
        }
    }

    private void putStringMapFromObject(ObjectNode req, String target, JsonNode props, String source,
                                        ProvisionContext ctx) {
        if (props == null || !props.has(source) || props.get(source).isNull()) {
            return;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get(source));
        if (!resolved.isObject()) {
            return;
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        resolved.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        req.set(target, out);
    }

    private void copyIfPresent(ObjectNode target, String targetName, JsonNode source, String sourceName) {
        if (source.has(sourceName) && !source.get(sourceName).isNull()) {
            target.set(targetName, source.get(sourceName));
        }
    }
}
