package io.github.hectorvent.floci.services.applicationautoscaling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.services.applicationautoscaling.model.Alarm;
import io.github.hectorvent.floci.services.applicationautoscaling.model.PredefinedMetricSpecification;
import io.github.hectorvent.floci.services.applicationautoscaling.model.ScalableTarget;
import io.github.hectorvent.floci.services.applicationautoscaling.model.ScalingPolicy;
import io.github.hectorvent.floci.services.applicationautoscaling.model.StepAdjustment;
import io.github.hectorvent.floci.services.applicationautoscaling.model.StepScalingConfiguration;
import io.github.hectorvent.floci.services.applicationautoscaling.model.SuspendedState;
import io.github.hectorvent.floci.services.applicationautoscaling.model.TargetTrackingConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * JSON 1.1 handler for the Application Auto Scaling API.
 *
 * <p>Requests arrive with the {@code AnyScaleFrontendService.} target prefix and are signed
 * with the {@code application-autoscaling} credential scope.</p>
 *
 * @see <a href="https://docs.aws.amazon.com/autoscaling/application/APIReference/Welcome.html">Application Auto Scaling API Reference</a>
 */
@ApplicationScoped
public class ApplicationAutoScalingJsonHandler {

    private static final Logger LOG = Logger.getLogger(ApplicationAutoScalingJsonHandler.class);

    private final ApplicationAutoScalingService service;
    private final ObjectMapper objectMapper;

    @Inject
    public ApplicationAutoScalingJsonHandler(ApplicationAutoScalingService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "RegisterScalableTarget" -> handleRegisterScalableTarget(request, region);
            case "DescribeScalableTargets" -> handleDescribeScalableTargets(request, region);
            case "DeregisterScalableTarget" -> handleDeregisterScalableTarget(request, region);
            case "PutScalingPolicy" -> handlePutScalingPolicy(request, region);
            case "DescribeScalingPolicies" -> handleDescribeScalingPolicies(request, region);
            case "DeleteScalingPolicy" -> handleDeleteScalingPolicy(request, region);
            case "ListTagsForResource" -> handleListTagsForResource(request, region);
            case "TagResource" -> handleTagResource(request, region);
            case "UntagResource" -> handleUntagResource(request, region);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation",
                            "Operation " + action + " is not supported."))
                    .build();
        };
    }

    // ---------------------------------------------------------------- scalable targets

    private Response handleRegisterScalableTarget(JsonNode request, String region) {
        ScalableTarget target = service.registerScalableTarget(
                text(request, "ServiceNamespace"),
                text(request, "ResourceId"),
                text(request, "ScalableDimension"),
                integer(request, "MinCapacity"),
                integer(request, "MaxCapacity"),
                text(request, "RoleARN"),
                parseSuspendedState(request.path("SuspendedState")),
                parseTags(request.path("Tags")),
                region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ScalableTargetARN", target.getScalableTargetArn());
        return Response.ok(response).build();
    }

    private Response handleDescribeScalableTargets(JsonNode request, String region) {
        List<ScalableTarget> found = service.describeScalableTargets(
                text(request, "ServiceNamespace"),
                stringList(request.path("ResourceIds")),
                text(request, "ScalableDimension"),
                region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("ScalableTargets");
        found.forEach(t -> array.add(scalableTargetJson(t)));
        return Response.ok(response).build();
    }

    private Response handleDeregisterScalableTarget(JsonNode request, String region) {
        service.deregisterScalableTarget(
                text(request, "ServiceNamespace"),
                text(request, "ResourceId"),
                text(request, "ScalableDimension"),
                region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // ---------------------------------------------------------------- scaling policies

    private Response handlePutScalingPolicy(JsonNode request, String region) {
        ScalingPolicy policy = service.putScalingPolicy(
                text(request, "PolicyName"),
                text(request, "PolicyType"),
                text(request, "ServiceNamespace"),
                text(request, "ResourceId"),
                text(request, "ScalableDimension"),
                parseTargetTracking(request.path("TargetTrackingScalingPolicyConfiguration")),
                parseStepScaling(request.path("StepScalingPolicyConfiguration")),
                region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("PolicyARN", policy.getPolicyArn());
        ArrayNode alarms = response.putArray("Alarms");
        policy.getAlarms().forEach(a -> alarms.add(alarmJson(a)));
        return Response.ok(response).build();
    }

    private Response handleDescribeScalingPolicies(JsonNode request, String region) {
        List<ScalingPolicy> found = service.describeScalingPolicies(
                text(request, "ServiceNamespace"),
                text(request, "ResourceId"),
                text(request, "ScalableDimension"),
                stringList(request.path("PolicyNames")),
                region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("ScalingPolicies");
        found.forEach(p -> array.add(scalingPolicyJson(p)));
        return Response.ok(response).build();
    }

    private Response handleDeleteScalingPolicy(JsonNode request, String region) {
        service.deleteScalingPolicy(
                text(request, "PolicyName"),
                text(request, "ServiceNamespace"),
                text(request, "ResourceId"),
                text(request, "ScalableDimension"),
                region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // ---------------------------------------------------------------- tagging

    private Response handleListTagsForResource(JsonNode request, String region) {
        Map<String, String> tags = service.listTagsForResource(text(request, "ResourceARN"), region);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode node = response.putObject("Tags");
        tags.forEach(node::put);
        return Response.ok(response).build();
    }

    private Response handleTagResource(JsonNode request, String region) {
        service.tagResource(text(request, "ResourceARN"), parseTags(request.path("Tags")), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request, String region) {
        service.untagResource(text(request, "ResourceARN"), stringList(request.path("TagKeys")), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // ---------------------------------------------------------------- serialization

    private ObjectNode scalableTargetJson(ScalableTarget t) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ServiceNamespace", t.getServiceNamespace());
        node.put("ResourceId", t.getResourceId());
        node.put("ScalableDimension", t.getScalableDimension());
        node.put("MinCapacity", t.getMinCapacity());
        node.put("MaxCapacity", t.getMaxCapacity());
        node.put("RoleARN", t.getRoleArn());
        node.put("ScalableTargetARN", t.getScalableTargetArn());
        node.put("CreationTime", t.getCreationTime());

        SuspendedState state = t.getSuspendedState();
        ObjectNode suspended = node.putObject("SuspendedState");
        suspended.put("DynamicScalingInSuspended", state.isDynamicScalingInSuspended());
        suspended.put("DynamicScalingOutSuspended", state.isDynamicScalingOutSuspended());
        suspended.put("ScheduledScalingSuspended", state.isScheduledScalingSuspended());
        return node;
    }

    private ObjectNode scalingPolicyJson(ScalingPolicy p) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("PolicyARN", p.getPolicyArn());
        node.put("PolicyName", p.getPolicyName());
        node.put("PolicyType", p.getPolicyType());
        node.put("ServiceNamespace", p.getServiceNamespace());
        node.put("ResourceId", p.getResourceId());
        node.put("ScalableDimension", p.getScalableDimension());
        node.put("CreationTime", p.getCreationTime());

        ArrayNode alarms = node.putArray("Alarms");
        p.getAlarms().forEach(a -> alarms.add(alarmJson(a)));

        if (p.getTargetTrackingConfiguration() != null) {
            node.set("TargetTrackingScalingPolicyConfiguration",
                    targetTrackingJson(p.getTargetTrackingConfiguration()));
        }
        if (p.getStepScalingConfiguration() != null) {
            node.set("StepScalingPolicyConfiguration", stepScalingJson(p.getStepScalingConfiguration()));
        }
        return node;
    }

    /**
     * Emits only the fields that were supplied. Defaulting absent optionals here would make
     * Terraform see a value it never configured and report perpetual drift.
     */
    private ObjectNode targetTrackingJson(TargetTrackingConfiguration config) {
        ObjectNode node = objectMapper.createObjectNode();
        if (config.getTargetValue() != null) {
            node.put("TargetValue", config.getTargetValue());
        }
        if (config.getPredefinedMetricSpecification() != null) {
            PredefinedMetricSpecification spec = config.getPredefinedMetricSpecification();
            ObjectNode specNode = node.putObject("PredefinedMetricSpecification");
            specNode.put("PredefinedMetricType", spec.getPredefinedMetricType());
            if (spec.getResourceLabel() != null) {
                specNode.put("ResourceLabel", spec.getResourceLabel());
            }
        }
        if (config.getCustomizedMetricSpecification() != null) {
            node.set("CustomizedMetricSpecification",
                    objectMapper.valueToTree(config.getCustomizedMetricSpecification()));
        }
        if (config.getDisableScaleIn() != null) {
            node.put("DisableScaleIn", config.getDisableScaleIn());
        }
        if (config.getScaleInCooldown() != null) {
            node.put("ScaleInCooldown", config.getScaleInCooldown());
        }
        if (config.getScaleOutCooldown() != null) {
            node.put("ScaleOutCooldown", config.getScaleOutCooldown());
        }
        return node;
    }

    private ObjectNode stepScalingJson(StepScalingConfiguration config) {
        ObjectNode node = objectMapper.createObjectNode();
        if (config.getAdjustmentType() != null) {
            node.put("AdjustmentType", config.getAdjustmentType());
        }
        if (config.getCooldown() != null) {
            node.put("Cooldown", config.getCooldown());
        }
        if (config.getMetricAggregationType() != null) {
            node.put("MetricAggregationType", config.getMetricAggregationType());
        }
        if (config.getMinAdjustmentMagnitude() != null) {
            node.put("MinAdjustmentMagnitude", config.getMinAdjustmentMagnitude());
        }
        if (!config.getStepAdjustments().isEmpty()) {
            ArrayNode steps = node.putArray("StepAdjustments");
            for (StepAdjustment step : config.getStepAdjustments()) {
                ObjectNode stepNode = steps.addObject();
                if (step.getMetricIntervalLowerBound() != null) {
                    stepNode.put("MetricIntervalLowerBound", step.getMetricIntervalLowerBound());
                }
                if (step.getMetricIntervalUpperBound() != null) {
                    stepNode.put("MetricIntervalUpperBound", step.getMetricIntervalUpperBound());
                }
                if (step.getScalingAdjustment() != null) {
                    stepNode.put("ScalingAdjustment", step.getScalingAdjustment());
                }
            }
        }
        return node;
    }

    private ObjectNode alarmJson(Alarm alarm) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("AlarmName", alarm.getAlarmName());
        node.put("AlarmARN", alarm.getAlarmArn());
        return node;
    }

    // ---------------------------------------------------------------- parsing

    private SuspendedState parseSuspendedState(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        SuspendedState state = new SuspendedState();
        state.setDynamicScalingInSuspended(node.path("DynamicScalingInSuspended").asBoolean(false));
        state.setDynamicScalingOutSuspended(node.path("DynamicScalingOutSuspended").asBoolean(false));
        state.setScheduledScalingSuspended(node.path("ScheduledScalingSuspended").asBoolean(false));
        return state;
    }

    private TargetTrackingConfiguration parseTargetTracking(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        TargetTrackingConfiguration config = new TargetTrackingConfiguration();
        if (node.hasNonNull("TargetValue")) {
            config.setTargetValue(node.get("TargetValue").asDouble());
        }
        JsonNode predefined = node.path("PredefinedMetricSpecification");
        if (predefined.isObject()) {
            PredefinedMetricSpecification spec = new PredefinedMetricSpecification();
            spec.setPredefinedMetricType(text(predefined, "PredefinedMetricType"));
            spec.setResourceLabel(text(predefined, "ResourceLabel"));
            config.setPredefinedMetricSpecification(spec);
        }
        JsonNode customized = node.path("CustomizedMetricSpecification");
        if (customized.isObject()) {
            config.setCustomizedMetricSpecification(
                    objectMapper.convertValue(customized, new com.fasterxml.jackson.core.type.TypeReference<>() {}));
        }
        if (node.hasNonNull("DisableScaleIn")) {
            config.setDisableScaleIn(node.get("DisableScaleIn").asBoolean());
        }
        if (node.hasNonNull("ScaleInCooldown")) {
            config.setScaleInCooldown(node.get("ScaleInCooldown").asInt());
        }
        if (node.hasNonNull("ScaleOutCooldown")) {
            config.setScaleOutCooldown(node.get("ScaleOutCooldown").asInt());
        }
        return config;
    }

    private StepScalingConfiguration parseStepScaling(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        StepScalingConfiguration config = new StepScalingConfiguration();
        config.setAdjustmentType(text(node, "AdjustmentType"));
        config.setMetricAggregationType(text(node, "MetricAggregationType"));
        if (node.hasNonNull("Cooldown")) {
            config.setCooldown(node.get("Cooldown").asInt());
        }
        if (node.hasNonNull("MinAdjustmentMagnitude")) {
            config.setMinAdjustmentMagnitude(node.get("MinAdjustmentMagnitude").asInt());
        }
        List<StepAdjustment> steps = new ArrayList<>();
        for (JsonNode stepNode : node.path("StepAdjustments")) {
            StepAdjustment step = new StepAdjustment();
            if (stepNode.hasNonNull("MetricIntervalLowerBound")) {
                step.setMetricIntervalLowerBound(stepNode.get("MetricIntervalLowerBound").asDouble());
            }
            if (stepNode.hasNonNull("MetricIntervalUpperBound")) {
                step.setMetricIntervalUpperBound(stepNode.get("MetricIntervalUpperBound").asDouble());
            }
            if (stepNode.hasNonNull("ScalingAdjustment")) {
                step.setScalingAdjustment(stepNode.get("ScalingAdjustment").asInt());
            }
            steps.add(step);
        }
        config.setStepAdjustments(steps);
        return config;
    }

    private Map<String, String> parseTags(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, String> tags = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            tags.put(entry.getKey(), entry.getValue().asText());
        }
        return tags;
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return values;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asInt();
    }
}
