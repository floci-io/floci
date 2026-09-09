package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
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
        switch (r.getResourceType()) {
            case "AWS::Batch::ComputeEnvironment" -> provisionComputeEnvironment(r, props, ctx);
            case "AWS::Batch::JobQueue" -> provisionJobQueue(r, props, ctx);
            case "AWS::Batch::JobDefinition" -> provisionJobDefinition(r, props, ctx);
            default -> throw new IllegalStateException(
                    "BatchCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    /**
     * Batch refuses to delete a running entity, exactly as AWS does: DeleteJobQueue rejects an
     * {@code ENABLED} queue and DeleteComputeEnvironment rejects one that is not {@code DISABLED}
     * or is still referenced by a queue. So each delete disables first, which is what the real
     * resource handlers do. CloudFormation already tears down in reverse dependency order, so a
     * queue is gone before the environment it points at.
     *
     * <p>Deliberately no {@code CfnDeletes.safeDelete} here. Every {@link BatchService} failure is
     * {@code ClientException}, so tolerating that code would be a catch-all: a real refusal such
     * as "still associated with a job queue" would be swallowed into a green stack delete instead
     * of failing it. Already-gone is handled by looking first, which is also what keeps the
     * disable step from throwing on a resource someone removed out of band.
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

    private void provisionComputeEnvironment(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = stableName(r, ctx, props, "ComputeEnvironmentName");
        require("AWS::Batch::ComputeEnvironment", "Type", ctx.resolveOptional(props, "Type"));

        String arn;
        if (reusesPriorEntity(r, ctx, name, "ComputeEnvironmentName")) {
            // UpdateComputeEnvironment is the schema's update handler and takes only these three;
            // ComputeEnvironmentName, Type and Tags are createOnly, so a change to those is a
            // replacement the engine drives, not something to push through here.
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
    }

    private void provisionJobQueue(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = stableName(r, ctx, props, "JobQueueName");
        String priority = ctx.resolveOptional(props, "Priority");
        require("AWS::Batch::JobQueue", "Priority", priority);

        String arn;
        if (reusesPriorEntity(r, ctx, name, "JobQueueName")) {
            // UpdateJobQueue is the schema's update handler; JobQueueName and JobQueueType are
            // createOnly, so only these three are pushed through.
            ObjectNode update = JsonNodeFactory.instance.objectNode();
            update.put("jobQueue", ctx.priorPhysicalId());
            putResolvedText(update, "state", props, "State", ctx);
            update.put("priority", Integer.parseInt(priority));
            update.set("computeEnvironmentOrder", computeEnvironmentOrder(props, ctx));
            batchService.updateJobQueue(update);
            arn = ctx.priorPhysicalId();
        } else {
            ObjectNode req = JsonNodeFactory.instance.objectNode();
            req.put("jobQueueName", name);
            req.put("priority", Integer.parseInt(priority));
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
    }

    private void provisionJobDefinition(StackResource r, JsonNode props, ProvisionContext ctx) {
        // No update branch: RegisterJobDefinition on an existing name records a new revision,
        // which is how AWS updates a job definition. Only the name has to stay steady.
        String name = stableName(r, ctx, props, "JobDefinitionName");
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

        ObjectNode response = batchService.registerJobDefinition(req, ctx.region());
        String arn = response.path("jobDefinitionArn").asText();
        r.setPhysicalId(arn);
        r.getAttributes().put("Arn", arn);
        r.getAttributes().put("JobDefinitionArn", arn);
        r.getAttributes().put("JobDefinitionName", name);
    }

    /** The schema's required properties fail the resource with the repo's ValidationError wording. */
    private static void require(String type, String property, String value) {
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError", type + " requires " + property, 400);
        }
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
