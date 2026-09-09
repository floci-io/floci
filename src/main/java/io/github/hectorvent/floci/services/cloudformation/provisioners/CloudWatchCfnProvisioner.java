package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Provisions {@code AWS::CloudWatch::Alarm}. */
@ApplicationScoped
public class CloudWatchCfnProvisioner implements CfnResourceProvisioner {

    private static final int ALARM_NAME_MAX_LENGTH = 255;
    private static final int DEFAULT_PERIOD_SECONDS = 60;
    private static final int DEFAULT_EVALUATION_PERIODS = 1;

    private final CloudWatchMetricsService cloudWatchMetricsService;

    public CloudWatchCfnProvisioner(CloudWatchMetricsService cloudWatchMetricsService) {
        this.cloudWatchMetricsService = cloudWatchMetricsService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::CloudWatch::Alarm");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.stablePhysicalName(ctx.resolveOptional(props, "AlarmName"),
                r.getLogicalId(), ALARM_NAME_MAX_LENGTH, false);

        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName(name);
        alarm.setAlarmDescription(ctx.resolveOptional(props, "AlarmDescription"));
        alarm.setMetricName(ctx.resolveOptional(props, "MetricName"));
        alarm.setNamespace(ctx.resolveOptional(props, "Namespace"));
        alarm.setStatistic(ctx.resolveOptional(props, "Statistic"));
        alarm.setUnit(ctx.resolveOptional(props, "Unit"));
        alarm.setComparisonOperator(ctx.resolveOptional(props, "ComparisonOperator"));
        alarm.setPeriod(parseIntProp(props, "Period", ctx, DEFAULT_PERIOD_SECONDS));
        alarm.setEvaluationPeriods(parseIntProp(props, "EvaluationPeriods", ctx, DEFAULT_EVALUATION_PERIODS));
        alarm.setDatapointsToAlarm(
                parseIntProp(props, "DatapointsToAlarm", ctx, alarm.getEvaluationPeriods()));
        String threshold = ctx.resolveOptional(props, "Threshold");
        if (threshold != null && !threshold.isBlank()) {
            try {
                alarm.setThreshold(Double.parseDouble(threshold.trim()));
            } catch (NumberFormatException ignored) {
                // leave default
            }
        }
        String treatMissing = ctx.resolveOptional(props, "TreatMissingData");
        if (treatMissing != null && !treatMissing.isBlank()) {
            alarm.setTreatMissingData(treatMissing);
        }
        String actionsEnabled = ctx.resolveOptional(props, "ActionsEnabled");
        alarm.setActionsEnabled(actionsEnabled == null || Boolean.parseBoolean(actionsEnabled));

        if (props != null && props.has("Dimensions") && props.get("Dimensions").isArray()) {
            List<Dimension> dimensions = new ArrayList<>();
            for (JsonNode dim : props.get("Dimensions")) {
                dimensions.add(new Dimension(ctx.engine().resolve(dim.path("Name")),
                        ctx.engine().resolve(dim.path("Value"))));
            }
            alarm.setDimensions(dimensions);
        }
        addAlarmActions(props, "AlarmActions", ctx, alarm.getAlarmActions());
        addAlarmActions(props, "OKActions", ctx, alarm.getOkActions());
        addAlarmActions(props, "InsufficientDataActions", ctx, alarm.getInsufficientDataActions());

        cloudWatchMetricsService.putMetricAlarm(alarm, ctx.region());
        // Ref returns the alarm name; Fn::GetAtt Arn returns the alarm ARN.
        r.setPhysicalId(name);
        r.getAttributes().put("Arn", alarm.getAlarmArn());
    }

    private void addAlarmActions(JsonNode props, String field, ProvisionContext ctx, List<String> target) {
        if (props != null && props.has(field) && props.get(field).isArray()) {
            for (JsonNode action : props.get(field)) {
                String resolved = ctx.engine().resolve(action);
                if (resolved != null && !resolved.isBlank()) {
                    target.add(resolved);
                }
            }
        }
    }

    /** Copied from the monolith, which still has callers for it. */
    private int parseIntProp(JsonNode props, String name, ProvisionContext ctx, int fallback) {
        String value = ctx.resolveOptional(props, name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        cloudWatchMetricsService.deleteAlarms(List.of(physicalId), region);
    }
}
