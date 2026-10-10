package io.github.hectorvent.floci.services.applicationautoscaling;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.applicationautoscaling.model.PredefinedMetricSpecification;
import io.github.hectorvent.floci.services.applicationautoscaling.model.StepAdjustment;
import io.github.hectorvent.floci.services.applicationautoscaling.model.StepScalingConfiguration;
import io.github.hectorvent.floci.services.applicationautoscaling.model.TargetTrackingConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the two scaling-policy configuration blocks out of JSON.
 *
 * <p>Shared by {@link ApplicationAutoScalingJsonHandler} and the CloudFormation provisioner,
 * because {@code AWS::ApplicationAutoScaling::ScalingPolicy} spells
 * {@code TargetTrackingScalingPolicyConfiguration} and {@code StepScalingPolicyConfiguration}
 * with the same member names {@code PutScalingPolicy} does. One parser means a template and an
 * API call cannot disagree about what a policy means.
 */
public final class ScalingConfigurationParser {

    private ScalingConfigurationParser() {
    }

    public static TargetTrackingConfiguration parseTargetTracking(JsonNode node, ObjectMapper mapper) {
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
            config.setCustomizedMetricSpecification(mapper.convertValue(customized, new TypeReference<>() {}));
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

    public static StepScalingConfiguration parseStepScaling(JsonNode node) {
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

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
