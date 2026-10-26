package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsPartition;
import io.github.hectorvent.floci.core.common.AwsPartitions;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Publishes SNS actions once per state transition, independently of scaling action retries. */
@ApplicationScoped
public class AlarmSnsNotifier {

    private static final Logger LOG = Logger.getLogger(AlarmSnsNotifier.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT).withZone(ZoneOffset.UTC);
    private final SnsService snsService;
    private final ObjectMapper objectMapper;

    public AlarmSnsNotifier(SnsService snsService, ObjectMapper objectMapper) {
        this.snsService = snsService;
        this.objectMapper = objectMapper;
    }

    record Notification(String topicArn, String message, String subject, String region) {}

    List<Notification> prepareTransition(MetricAlarm alarm, String oldState) {
        if (!alarm.isActionsEnabled() || alarm.getStateValue().equals(oldState)) {
            return List.of();
        }
        List<String> actions = switch (alarm.getStateValue()) {
            case "ALARM" -> alarm.getAlarmActions();
            case "OK" -> alarm.getOkActions();
            case "INSUFFICIENT_DATA" -> alarm.getInsufficientDataActions();
            default -> List.of();
        };
        if (actions == null) {
            return List.of();
        }
        List<Notification> notifications = new ArrayList<>();
        for (String action : actions) {
            if (!AwsArnUtils.isArnFor(action, "sns")) {
                continue;
            }
            try {
                AwsArnUtils.Arn topic = AwsArnUtils.parse(action);
                String subject = alarm.getStateValue() + ": \"" + alarm.getAlarmName() + "\"";
                if (subject.length() > 100) {
                    subject = subject.substring(0, 100);
                }
                notifications.add(new Notification(action, message(alarm, oldState), subject, topic.region()));
            } catch (Exception e) {
                LOG.warnv(e, "Unable to publish alarm {0} state {1} to SNS action {2}",
                        alarm.getAlarmName(), alarm.getStateValue(), action);
            }
        }
        return notifications;
    }

    void publish(List<Notification> notifications) {
        for (Notification notification : notifications) {
            try {
                snsService.publish(notification.topicArn(), null, notification.message(),
                        notification.subject(), notification.region());
            } catch (Exception error) {
                LOG.warnv(error, "Unable to publish alarm transition {0} to SNS action {1}",
                        notification.subject(), notification.topicArn());
            }
        }
    }

    private String message(MetricAlarm alarm, String oldState) throws JsonProcessingException {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(alarm.getAlarmArn());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("AlarmName", alarm.getAlarmName());
        payload.put("AlarmDescription", alarm.getAlarmDescription());
        payload.put("AWSAccountId", arn.accountId());
        payload.put("AlarmConfigurationUpdatedTimestamp", timestamp(alarm.getAlarmConfigurationUpdatedTimestamp()));
        payload.put("NewStateValue", alarm.getStateValue());
        payload.put("NewStateReason", alarm.getStateReason());
        payload.put("StateChangeTime", timestamp(alarm.getStateUpdatedTimestamp()));
        String regionName = AwsPartitions.forRegion(arn.region()).stream()
                .flatMap(partition -> partition.regions().stream())
                .filter(region -> region.id().equals(arn.region()))
                .map(AwsPartition.Region::description).findFirst().orElse(arn.region());
        payload.put("Region", regionName);
        payload.put("AlarmArn", alarm.getAlarmArn());
        payload.put("OldStateValue", oldState);
        payload.put("OKActions", alarm.getOkActions());
        payload.put("AlarmActions", alarm.getAlarmActions());
        payload.put("InsufficientDataActions", alarm.getInsufficientDataActions());
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("MetricName", alarm.getMetricName());
        trigger.put("Namespace", alarm.getNamespace());
        trigger.put("StatisticType", "Statistic");
        trigger.put("Statistic", alarm.getStatistic() != null
                ? alarm.getStatistic().toUpperCase(Locale.ROOT) : null);
        trigger.put("Unit", alarm.getUnit());
        trigger.put("Dimensions", alarm.getDimensions());
        trigger.put("Period", alarm.getPeriod());
        trigger.put("EvaluationPeriods", alarm.getEvaluationPeriods());
        if (alarm.getDatapointsToAlarm() != null) {
            trigger.put("DatapointsToAlarm", alarm.getDatapointsToAlarm());
        }
        trigger.put("ComparisonOperator", alarm.getComparisonOperator());
        trigger.put("Threshold", alarm.getThreshold());
        trigger.put("TreatMissingData", alarm.getTreatMissingData());
        trigger.put("EvaluateLowSampleCountPercentile", alarm.getEvaluateLowSampleCountPercentile() != null
                ? alarm.getEvaluateLowSampleCountPercentile() : "");
        payload.put("Trigger", trigger);
        return objectMapper.writeValueAsString(payload);
    }

    private String timestamp(long epochSeconds) {
        return TIMESTAMP_FORMAT.format(Instant.ofEpochSecond(epochSeconds));
    }
}
