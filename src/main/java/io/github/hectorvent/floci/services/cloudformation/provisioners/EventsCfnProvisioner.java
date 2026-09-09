package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.eventbridge.model.BatchParameters;
import io.github.hectorvent.floci.services.eventbridge.model.EventBus;
import io.github.hectorvent.floci.services.eventbridge.model.InputTransformer;
import io.github.hectorvent.floci.services.eventbridge.model.RuleState;
import io.github.hectorvent.floci.services.eventbridge.model.SqsParameters;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * CloudFormation provisioning for EventBridge: {@code AWS::Events::Rule},
 * {@code AWS::Events::EventBus} and {@code AWS::Events::EventBusPolicy}. Extracted from
 * {@code CloudFormationResourceProvisioner}.
 *
 * <p>Two of these types need the resource's stored attributes to delete, not just the physical
 * id, so they are served by the {@link #delete(StackResource, String)} override rather than the
 * id-only form: a rule remembers the custom bus it lives on, and an event bus remembers the
 * creation time that proves the stack still owns it.
 */
@ApplicationScoped
public class EventsCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(EventsCfnProvisioner.class);

    private static final String EVENT_BUS_CREATED_TIME_ATTR = "FlociEventBusCreatedTime";
    private static final String EVENT_BUS_MANAGED_TAG_KEYS_ATTR = "FlociEventBusManagedTagKeys";
    private static final String EVENT_BUS_MANAGED_POLICY_ATTR = "FlociEventBusManagedPolicy";
    private static final Set<String> EVENT_BUS_SUPPORTED_PROPERTIES =
            Set.of("Name", "Description", "Tags", "Policy");
    private static final Pattern EVENT_BUS_TAG_PATTERN =
            Pattern.compile("[\\p{L}\\p{N}\\p{Z}_.:/=+\\-@]*");

    private final EventBridgeService eventBridgeService;
    private final ObjectMapper objectMapper;

    @Inject
    public EventsCfnProvisioner(EventBridgeService eventBridgeService, ObjectMapper objectMapper) {
        this.eventBridgeService = eventBridgeService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::Events::Rule", "AWS::Events::EventBus", "AWS::Events::EventBusPolicy");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case "AWS::Events::Rule" -> provisionRule(r, props, ctx);
            case "AWS::Events::EventBus" -> provisionEventBus(r, props, ctx);
            case "AWS::Events::EventBusPolicy" -> provisionEventBusPolicy(r, props, ctx);
            default -> throw new IllegalStateException(
                    "EventsCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    /**
     * A rule and an event bus both need attributes the physical id cannot carry: the rule's custom
     * bus, and the bus's recorded creation time. Deleting either by id alone would silently target
     * the default bus or skip the ownership check.
     */
    @Override
    public void delete(StackResource resource, String region) {
        switch (resource.getResourceType()) {
            case "AWS::Events::Rule" -> deleteEventBridgeRuleSafe(resource.getPhysicalId(),
                    resource.getAttributes() != null ? resource.getAttributes().get("EventBusName") : null,
                    region);
            case "AWS::Events::EventBus" -> deleteEventBusSafe(resource, region);
            default -> delete(resource.getResourceType(), resource.getPhysicalId(), region);
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        switch (resourceType) {
            case "AWS::Events::Rule" -> deleteEventBridgeRuleSafe(physicalId, null, region);
            case "AWS::Events::EventBus" -> deleteEventBusSafe(physicalId, region);
            case "AWS::Events::EventBusPolicy" -> removeEventBusPolicySafe(physicalId, region);
            default -> {
                // no other type reaches this provisioner
            }
        }
    }

    private void provisionRule(StackResource r, JsonNode props, ProvisionContext ctx) {
        String ruleName = ctx.resolveOptional(props, "Name");
        if (ruleName == null || ruleName.isBlank()) {
            ruleName = ctx.generatePhysicalName(r.getLogicalId(), 64, false);
        }

        String busName = ctx.resolveOptional(props, "EventBusName");
        String description = ctx.resolveOptional(props, "Description");
        String roleArn = ctx.resolveOptional(props, "RoleArn");
        String scheduleExpression = ctx.resolveOptional(props, "ScheduleExpression");

        String eventPattern = null;
        if (props != null && props.has("EventPattern") && !props.get("EventPattern").isNull()) {
            JsonNode patternNode = ctx.engine().resolveNode(props.get("EventPattern"));
            eventPattern = patternNode.toString();
        }

        String stateStr = ctx.resolveOptional(props, "State");
        RuleState state = "DISABLED".equals(stateStr) ? RuleState.DISABLED : RuleState.ENABLED;

        var rule = eventBridgeService.putRule(ruleName, busName, eventPattern, scheduleExpression,
                state, description, roleArn, Map.of(), ctx.region());
        r.setPhysicalId(ruleName);
        r.getAttributes().put("Arn", rule.getArn());
        // A rule on a custom bus is keyed by that bus; remember it so the resource delete can target
        // the right bus (the physical id is only the rule name, which resolves to the default bus).
        if (busName != null && !busName.isBlank()) {
            r.getAttributes().put("EventBusName", busName);
        }

        // Provision inline targets
        if (props != null && props.has("Targets")) {
            List<Target> targets = new ArrayList<>();
            for (JsonNode targetNode : props.get("Targets")) {
                JsonNode resolved = ctx.engine().resolveNode(targetNode);
                String targetId = resolved.path("Id").asText(null);
                String targetArn = resolved.path("Arn").asText(null);
                String input = resolved.path("Input").asText(null);
                String inputPath = resolved.path("InputPath").asText(null);
                if (targetId != null && targetArn != null) {
                    Target target = new Target(targetId, targetArn, input, inputPath);
                    target.setInputTransformer(InputTransformer.fromJson(resolved.path("InputTransformer")));
                    JsonNode sqsParamsNode = resolved.path("SqsParameters");
                    if (!sqsParamsNode.isMissingNode() && sqsParamsNode.isObject()) {
                        String messageGroupId = sqsParamsNode.path("MessageGroupId").asText(null);
                        if (messageGroupId != null) {
                            SqsParameters sqsParameters = new SqsParameters();
                            sqsParameters.setMessageGroupId(messageGroupId);
                            target.setSqsParameters(sqsParameters);
                        }
                    }
                    JsonNode batchParamsNode = resolved.path("BatchParameters");
                    if (!batchParamsNode.isMissingNode() && batchParamsNode.isObject()) {
                        JsonNode arrayProperties = batchParamsNode.path("ArrayProperties");
                        BatchParameters batchParameters = new BatchParameters();
                        batchParameters.setJobDefinition(batchParamsNode.path("JobDefinition").asText(null));
                        batchParameters.setJobName(batchParamsNode.path("JobName").asText(null));
                        if (arrayProperties.isObject()) {
                            batchParameters.setArrayProperties(jsonObjectToMap(arrayProperties));
                        }
                        if (batchParamsNode.has("RetryStrategy")) {
                            batchParameters.setRetryStrategy(batchParamsNode.get("RetryStrategy"));
                        }
                        target.setBatchParameters(batchParameters);
                    }
                    targets.add(target);
                }
            }
            if (!targets.isEmpty()) {
                eventBridgeService.putTargets(ruleName, busName, targets, ctx.region());
            }
        }
    }

    /**
     * Provisions an {@code AWS::Events::EventBus} (a custom EventBridge event bus). Without this the
     * resource would fall through to the generic stub, which assigns a physical id but never registers
     * the bus with the EventBridge service — so any {@code AWS::Events::Rule} (or PutEvents) targeting
     * the bus fails "EventBus not found". Per the AWS spec, {@code Ref} returns the bus <em>name</em>
     * (not the ARN), so the physical id is the name; {@code Fn::GetAtt "Arn"} exposes the ARN.
     */
    private void provisionEventBus(StackResource r, JsonNode props, ProvisionContext ctx) {
        validateEventBusProperties(props);
        String existingBusName = r.getPhysicalId();
        String busName = ctx.resolveOptional(props, "Name");
        validateEventBusName(busName);
        if (existingBusName != null && !existingBusName.equals(busName)) {
            throw new AwsException("ValidationError",
                    "Updating EventBus Name requires resource replacement, which is not supported.", 400);
        }
        String description = ctx.resolveOptional(props, "Description");
        if (description != null && description.length() > 512) {
            throw new AwsException("ValidationError",
                    "AWS::Events::EventBus Description must not exceed 512 characters.", 400);
        }
        Map<String, String> tags = parseEventBusTags(props != null ? props.get("Tags") : null, ctx);
        JsonNode policy = resolveEventBusPolicy(props, ctx);

        EventBus bus;
        boolean createdBus = false;
        try {
            bus = eventBridgeService.createEventBus(busName, description, tags, ctx.region());
            createdBus = true;
            r.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
        } catch (AwsException e) {
            boolean stackAlreadyOwnsBus = existingBusName != null && existingBusName.equals(busName);
            if (!stackAlreadyOwnsBus || !"ResourceAlreadyExistsException".equals(e.getErrorCode())) {
                throw e;
            }
            bus = eventBridgeService.describeEventBus(busName, ctx.region());
            // A missing created-time means ownership was never tracked, not that it changed: stacks
            // provisioned before this attribute existed are restored without it. Refusing there would
            // wedge every later UpdateStack, including no-op ones. Only a recorded time that actually
            // disagrees means the bus was recreated out of band and belongs to its new owner.
            String existingCreatedTime = r.getAttributes().get(EVENT_BUS_CREATED_TIME_ATTR);
            String actualCreatedTime = eventBusCreatedTime(bus);
            if (existingCreatedTime != null && !existingCreatedTime.equals(actualCreatedTime)) {
                r.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
                throw e;
            }
            validateEventBusMutablePropertiesUnchanged(r, bus, description, tags, policy);
        }

        // Record identity before applying the policy: rollbackCreatedResources skips any resource
        // whose physicalId is still null, so a putPermission failure after the bus exists would
        // otherwise orphan it with no way for rollback to find it.
        r.setPhysicalId(busName);              // Ref → EventBus name (AWS-faithful)
        r.getAttributes().put("Arn", bus.getArn());
        r.getAttributes().put("Name", busName);
        r.getAttributes().put(EVENT_BUS_CREATED_TIME_ATTR, eventBusCreatedTime(bus));
        recordEventBusManagedTagKeys(r, tags.keySet());
        recordEventBusManagedPolicy(r, policy);

        // Apply an optional inline resource policy only during creation. Updating it is rejected by
        // validateEventBusMutablePropertiesUnchanged until stack updates can roll back live resource
        // mutations transactionally.
        if (createdBus && !policy.isNull()) {
            eventBridgeService.putPermission(busName, null, null, null, null, policy.toString(), ctx.region());
        }
    }

    private void validateEventBusProperties(JsonNode props) {
        if (props == null || props.isNull()) {
            return;
        }
        if (!props.isObject()) {
            throw new AwsException("ValidationError",
                    "AWS::Events::EventBus Properties must be an object.", 400);
        }
        List<String> unsupported = new ArrayList<>();
        props.fieldNames().forEachRemaining(name -> {
            if (!EVENT_BUS_SUPPORTED_PROPERTIES.contains(name)) {
                unsupported.add(name);
            }
        });
        if (!unsupported.isEmpty()) {
            Collections.sort(unsupported);
            throw new AwsException("ValidationError",
                    "Unsupported AWS::Events::EventBus properties: "
                            + String.join(", ", unsupported), 400);
        }
    }

    private void validateEventBusName(String busName) {
        if (busName == null || busName.isBlank()) {
            throw new AwsException("ValidationError",
                    "Name is required for AWS::Events::EventBus.", 400);
        }
        if (busName.length() > 256
                || !busName.matches("[.\\-_A-Za-z0-9]+")
                || "default".equals(busName)) {
            throw new AwsException("ValidationError",
                    "Invalid custom event bus Name: " + busName, 400);
        }
    }

    private Map<String, String> parseEventBusTags(JsonNode tagsNode, ProvisionContext ctx) {
        if (tagsNode == null || tagsNode.isNull()) {
            return Map.of();
        }
        JsonNode resolvedTags = ctx.engine().resolveNode(tagsNode);
        if (!resolvedTags.isArray()) {
            throw new AwsException("ValidationError",
                    "AWS::Events::EventBus Tags must be an array.", 400);
        }
        if (resolvedTags.size() > 50) {
            throw new AwsException("ValidationError",
                    "AWS::Events::EventBus supports at most 50 tags.", 400);
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (JsonNode entry : resolvedTags) {
            if (!entry.isObject()) {
                throw new AwsException("ValidationError",
                        "Each AWS::Events::EventBus tag must be an object.", 400);
            }
            String key = entry.path("Key").asText(null);
            String value = entry.path("Value").asText(null);
            if (key == null || key.isEmpty() || key.length() > 128) {
                throw new AwsException("ValidationError",
                        "Event bus tag Key must contain 1 to 128 characters.", 400);
            }
            if (key.regionMatches(true, 0, "aws:", 0, 4)) {
                throw new AwsException("ValidationError",
                        "Event bus tag Key must not use the reserved aws: prefix.", 400);
            }
            if (!EVENT_BUS_TAG_PATTERN.matcher(key).matches()) {
                throw new AwsException("ValidationError",
                        "Event bus tag Key contains unsupported characters.", 400);
            }
            if (value == null || value.length() > 256) {
                throw new AwsException("ValidationError",
                        "Event bus tag Value must contain at most 256 characters.", 400);
            }
            if (!EVENT_BUS_TAG_PATTERN.matcher(value).matches()) {
                throw new AwsException("ValidationError",
                        "Event bus tag Value contains unsupported characters.", 400);
            }
            if (tags.putIfAbsent(key, value) != null) {
                throw new AwsException("ValidationError",
                        "Duplicate event bus tag Key: " + key, 400);
            }
        }
        return tags;
    }

    private void validateEventBusMutablePropertiesUnchanged(
            StackResource resource, EventBus bus, String requestedDescription,
            Map<String, String> requestedTags, JsonNode requestedPolicy) {
        if (!Objects.equals(bus.getDescription(), requestedDescription)) {
            throw unsupportedEventBusMutableUpdate();
        }

        // Stacks persisted by the older EventBus provisioner have no managed-key metadata. There
        // is no reliable way to distinguish their CloudFormation tags from tags added out of band,
        // so allow this one-time adoption and start tracking the requested keys afterwards.
        if (resource.getAttributes().containsKey(EVENT_BUS_MANAGED_TAG_KEYS_ATTR)) {
            Map<String, String> currentManagedTags = new LinkedHashMap<>();
            for (String key : eventBusManagedTagKeys(resource)) {
                if (bus.getTags().containsKey(key)) {
                    currentManagedTags.put(key, bus.getTags().get(key));
                }
            }
            if (!currentManagedTags.equals(requestedTags)) {
                throw unsupportedEventBusMutableUpdate();
            }
        }

        String managedPolicy = resource.getAttributes().get(EVENT_BUS_MANAGED_POLICY_ATTR);
        JsonNode policyToCompare = managedPolicy != null
                ? parseEventBusPolicy(managedPolicy, "stored CloudFormation metadata")
                : parseEventBusPolicy(bus.getPolicy(), "the existing event bus");
        if (managedPolicy != null || !requestedPolicy.isNull()) {
            if (!policyToCompare.equals(requestedPolicy)) {
                throw unsupportedEventBusMutableUpdate();
            }
        }
    }

    private JsonNode resolveEventBusPolicy(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.has("Policy") || props.get("Policy").isNull()) {
            return JsonNodeFactory.instance.nullNode();
        }
        return ctx.engine().resolveNode(props.get("Policy"));
    }

    private JsonNode parseEventBusPolicy(String policy, String source) {
        if (policy == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(policy);
            return parsed != null ? parsed : JsonNodeFactory.instance.nullNode();
        } catch (Exception e) {
            throw new AwsException("InternalFailure",
                    "Invalid EventBus policy in " + source + ": " + e.getMessage(), 500);
        }
    }

    private void recordEventBusManagedPolicy(StackResource resource, JsonNode policy) {
        try {
            resource.getAttributes().put(EVENT_BUS_MANAGED_POLICY_ATTR,
                    objectMapper.writeValueAsString(policy));
        } catch (Exception e) {
            throw new AwsException("InternalFailure",
                    "Failed to store EventBus managed-policy metadata: " + e.getMessage(), 500);
        }
    }

    private AwsException unsupportedEventBusMutableUpdate() {
        return new AwsException("ValidationError",
                "Updating AWS::Events::EventBus Description, Tags, or Policy is not supported "
                        + "until transactional rollback is available.", 400);
    }

    private Set<String> eventBusManagedTagKeys(StackResource resource) {
        String value = resource.getAttributes().get(EVENT_BUS_MANAGED_TAG_KEYS_ATTR);
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        try {
            JsonNode keys = objectMapper.readTree(value);
            if (!keys.isArray()) {
                throw new IllegalArgumentException("managed tag keys are not an array");
            }
            Set<String> result = new HashSet<>();
            keys.forEach(key -> result.add(key.asText()));
            return result;
        } catch (Exception e) {
            throw new AwsException("InternalFailure",
                    "Invalid stored EventBus managed-tag metadata: " + e.getMessage(), 500);
        }
    }

    private void recordEventBusManagedTagKeys(StackResource resource, Set<String> keys) {
        try {
            resource.getAttributes().put(
                    EVENT_BUS_MANAGED_TAG_KEYS_ATTR,
                    objectMapper.writeValueAsString(new TreeSet<>(keys)));
        } catch (Exception e) {
            throw new AwsException("InternalFailure",
                    "Failed to store EventBus managed-tag metadata: " + e.getMessage(), 500);
        }
    }

    private String eventBusCreatedTime(EventBus bus) {
        return bus.getCreatedTime() != null ? bus.getCreatedTime().toString() : "";
    }

    private void deleteEventBridgeRuleSafe(String ruleName, String busName, String region) {
        try {
            // Remove all targets before deleting the rule (busName scopes the lookup to the rule's bus).
            var targets = eventBridgeService.listTargetsByRule(ruleName, busName, region);
            if (!targets.isEmpty()) {
                List<String> targetIds = targets.stream().map(Target::getId).toList();
                eventBridgeService.removeTargets(ruleName, busName, targetIds, region);
            }
            eventBridgeService.deleteRule(ruleName, busName, region);
        } catch (AwsException e) {
            // An already-deleted rule is the one failure that genuinely means "done". Anything else
            // is a real error worth surfacing rather than hiding behind a debug line.
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("EventBridge rule already gone, treating as deleted: {0}", ruleName);
        } catch (Exception e) {
            throw new AwsException("InternalFailure",
                    "Could not delete EventBridge rule " + ruleName + ": " + e.getMessage(), 500);
        }
    }

    private void deleteEventBusSafe(String busName, String region) {
        try {
            eventBridgeService.deleteEventBus(busName, region);
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Event bus already gone, treating as deleted: {0}", busName);
        }
    }

    private void deleteEventBusSafe(StackResource resource, String region) {
        String busName = resource.getPhysicalId();
        EventBus bus;
        try {
            bus = eventBridgeService.describeEventBus(busName, region);
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                LOG.debugv("Event bus already gone, treating as deleted: {0}", busName);
                return;
            }
            throw e;
        }

        // A missing attribute means ownership was never tracked, not that it changed: stacks
        // provisioned before this attribute existed are restored from cloudformation-stacks.json
        // without it. Refusing there would leave every such stack permanently in DELETE_FAILED, so
        // fall back to the pre-tracking behaviour of deleting what the stack recorded it created.
        String expectedCreatedTime = resource.getAttributes().get(EVENT_BUS_CREATED_TIME_ATTR);
        if (expectedCreatedTime != null && !expectedCreatedTime.equals(eventBusCreatedTime(bus))) {
            throw new AwsException("ValidationError",
                    "EventBus ownership changed; refusing to delete: " + busName, 400);
        }
        deleteEventBusSafe(busName, region);
    }


    private void provisionEventBusPolicy(StackResource r, JsonNode props, ProvisionContext ctx) {
        String busName = resolveOrDefault(props, "EventBusName", ctx, "default");
        String statementId = ctx.resolveOptional(props, "StatementId");
        if (statementId == null || statementId.isBlank()) {
            throw new AwsException("ValidationException", "EventBusPolicy StatementId is required.", 400);
        }

        if (props != null && props.has("Statement") && props.get("Statement").isObject()) {
            // Statement form: merge the full statement into the bus policy, keyed by Sid,
            // so multiple EventBusPolicy resources on the same bus coexist.
            try {
                ObjectNode statement = (ObjectNode) ctx.engine().resolveNode(props.get("Statement")).deepCopy();
                statement.put("Sid", statementId);

                EventBus bus = eventBridgeService.describeEventBus(busName, ctx.region());
                ObjectNode policy;
                String current = bus.getPolicy();
                if (current != null && !current.isBlank()) {
                    policy = (ObjectNode) objectMapper.readTree(current);
                } else {
                    policy = objectMapper.createObjectNode();
                    policy.put("Version", "2012-10-17");
                    policy.putArray("Statement");
                }
                ArrayNode statements = policy.withArray("Statement");
                for (int i = 0; i < statements.size(); i++) {
                    if (statementId.equals(statements.get(i).path("Sid").asText(null))) {
                        statements.remove(i);
                        break;
                    }
                }
                statements.add(statement);
                eventBridgeService.putPermission(busName, null, null, statementId, null,
                        objectMapper.writeValueAsString(policy), ctx.region());
            } catch (AwsException e) {
                throw e;
            } catch (Exception e) {
                throw new AwsException("ValidationException",
                        "Invalid EventBusPolicy Statement: " + e.getMessage(), 400);
            }
            r.setPhysicalId(busName + "|" + statementId);
            return;
        }

        // Individual form: Action + Principal (+ optional Condition {Type, Key, Value}).
        String action = ctx.resolveOptional(props, "Action");
        String principal = ctx.resolveOptional(props, "Principal");
        String conditionJson = null;
        if (props != null && props.has("Condition") && !props.get("Condition").isNull()) {
            JsonNode c = ctx.engine().resolveNode(props.get("Condition"));
            String type = c.path("Type").asText(null);
            String key = c.path("Key").asText(null);
            String value = c.path("Value").asText(null);
            if (type != null && key != null && value != null) {
                ObjectNode condition = objectMapper.createObjectNode();
                condition.set(type, objectMapper.createObjectNode().put(key, value));
                conditionJson = condition.toString();
            }
        }
        eventBridgeService.putPermission(busName, action, principal, statementId, conditionJson, null, ctx.region());

        r.setPhysicalId(busName + "|" + statementId);
    }

    private void removeEventBusPolicySafe(String physicalId, String region) {
        try {
            int sep = physicalId.lastIndexOf('|');
            String busName = sep >= 0 ? physicalId.substring(0, sep) : "default";
            String statementId = sep >= 0 ? physicalId.substring(sep + 1) : physicalId;
            eventBridgeService.removePermission(busName, statementId, false, region);
        } catch (Exception e) {
            LOG.debugv("Could not remove event bus policy {0}: {1}", physicalId, e.getMessage());
        }
    }
    private static String resolveOrDefault(JsonNode props, String name, ProvisionContext ctx, String defaultValue) {
        String value = ctx.resolveOptional(props, name);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    /** Copied from the monolith: the shared original serves six other callers and stays there. */
    private Map<String, Object> jsonObjectToMap(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> out.put(e.getKey(), jsonNodeToValue(e.getValue())));
        return out;
    }

    private Object jsonNodeToValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return jsonObjectToMap(node);
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(v -> values.add(jsonNodeToValue(v)));
            return values;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        return node.asText();
    }
}
