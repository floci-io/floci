package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.batch.BatchService;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Set;

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

    private void provisionComputeEnvironment(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.resolveOptional(props, "ComputeEnvironmentName");
        if (name == null || name.isBlank()) {
            name = ctx.generatePhysicalName(r.getLogicalId(), NAME_MAX_LENGTH, false);
        }

        ObjectNode req = JsonNodeFactory.instance.objectNode();
        req.put("computeEnvironmentName", name);
        putResolvedText(req, "type", props, "Type", ctx);
        putResolvedText(req, "state", props, "State", ctx);
        putResolvedText(req, "serviceRole", props, "ServiceRole", ctx);
        putResolvedObject(req, "computeResources", props, "ComputeResources", ctx);
        putTags(req, props, ctx);

        ObjectNode response = batchService.createComputeEnvironment(req, ctx.region());
        String arn = response.path("computeEnvironmentArn").asText();
        r.setPhysicalId(arn);
        r.getAttributes().put("Arn", arn);
        r.getAttributes().put("ComputeEnvironmentArn", arn);
        r.getAttributes().put("ComputeEnvironmentName", name);
    }

    private void provisionJobQueue(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.resolveOptional(props, "JobQueueName");
        if (name == null || name.isBlank()) {
            name = ctx.generatePhysicalName(r.getLogicalId(), NAME_MAX_LENGTH, false);
        }

        ObjectNode req = JsonNodeFactory.instance.objectNode();
        req.put("jobQueueName", name);
        String priority = ctx.resolveOptional(props, "Priority");
        req.put("priority", priority != null ? Integer.parseInt(priority) : 1);
        putResolvedText(req, "state", props, "State", ctx);
        putResolvedText(req, "jobQueueType", props, "JobQueueType", ctx);
        req.set("computeEnvironmentOrder", computeEnvironmentOrder(props, ctx));
        putTags(req, props, ctx);

        ObjectNode response = batchService.createJobQueue(req, ctx.region());
        String arn = response.path("jobQueueArn").asText();
        r.setPhysicalId(arn);
        r.getAttributes().put("Arn", arn);
        r.getAttributes().put("JobQueueArn", arn);
        r.getAttributes().put("JobQueueName", name);
    }

    private void provisionJobDefinition(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.resolveOptional(props, "JobDefinitionName");
        if (name == null || name.isBlank()) {
            name = ctx.generatePhysicalName(r.getLogicalId(), NAME_MAX_LENGTH, false);
        }

        ObjectNode req = JsonNodeFactory.instance.objectNode();
        req.put("jobDefinitionName", name);
        String type = ctx.resolveOptional(props, "Type");
        req.put("type", type != null && !type.isBlank() ? type : "container");
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
        r.getAttributes().put("Revision", response.path("revision").asText());
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
