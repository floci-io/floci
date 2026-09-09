package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code AWS::CloudWatch::Alarm}. */
class CloudWatchCfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";

    private CloudWatchMetricsService metrics;
    private CloudWatchCfnProvisioner provisioner;
    private ProvisionContext ctx;

    @BeforeEach
    void setUp() {
        metrics = mock(CloudWatchMetricsService.class);
        provisioner = new CloudWatchCfnProvisioner(metrics);
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(i -> i.getArgument(0));
        ctx = new ProvisionContext(engine, REGION, "000000000000", "my-stack");
    }

    private static JsonNode props(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private MetricAlarm provisionAndCapture(String json) {
        StackResource r = new StackResource();
        r.setLogicalId("Alarm");
        r.setResourceType("AWS::CloudWatch::Alarm");
        r.setAttributes(new HashMap<>());
        provisioner.provision(r, props(json), ctx);
        ArgumentCaptor<MetricAlarm> alarm = ArgumentCaptor.forClass(MetricAlarm.class);
        verify(metrics).putMetricAlarm(alarm.capture(), eq(REGION));
        return alarm.getValue();
    }

    @Test
    void refIsTheAlarmNameAndTheAlarmReachesTheService() {
        StackResource r = new StackResource();
        r.setLogicalId("Alarm");
        r.setResourceType("AWS::CloudWatch::Alarm");
        r.setAttributes(new HashMap<>());

        provisioner.provision(r, props("""
                {"AlarmName": "cpu-high", "MetricName": "CPUUtilization", "Namespace": "AWS/EC2",
                 "ComparisonOperator": "GreaterThanThreshold", "Threshold": "80.5"}
                """), ctx);

        assertEquals("cpu-high", r.getPhysicalId(), "Ref is the alarm name");
        assertTrue(r.getAttributes().containsKey("Arn"), "aws-cloudwatch-alarm.json declares Arn read-only");

        ArgumentCaptor<MetricAlarm> alarm = ArgumentCaptor.forClass(MetricAlarm.class);
        verify(metrics).putMetricAlarm(alarm.capture(), eq(REGION));
        assertEquals("CPUUtilization", alarm.getValue().getMetricName());
        assertEquals("AWS/EC2", alarm.getValue().getNamespace());
        assertEquals(80.5, alarm.getValue().getThreshold());
    }

    @Test
    void anUnnamedAlarmGetsAGeneratedStackScopedName() {
        StackResource r = new StackResource();
        r.setLogicalId("Alarm");
        r.setResourceType("AWS::CloudWatch::Alarm");
        r.setAttributes(new HashMap<>());

        provisioner.provision(r, props("{}"), ctx);

        assertTrue(r.getPhysicalId().startsWith("my-stack-Alarm-"), r.getPhysicalId());
    }

    @Test
    void periodAndEvaluationPeriodsFallBackToTheAwsDefaults() {
        MetricAlarm alarm = provisionAndCapture("""
                {"AlarmName": "a"}
                """);

        assertEquals(60, alarm.getPeriod());
        assertEquals(1, alarm.getEvaluationPeriods());
        // DatapointsToAlarm defaults to EvaluationPeriods rather than to a fixed number.
        assertEquals(1, alarm.getDatapointsToAlarm());
    }

    @Test
    void datapointsToAlarmDefaultsToEvaluationPeriods() {
        MetricAlarm alarm = provisionAndCapture("""
                {"AlarmName": "a", "EvaluationPeriods": "5"}
                """);

        assertEquals(5, alarm.getEvaluationPeriods());
        assertEquals(5, alarm.getDatapointsToAlarm());
    }

    /** ActionsEnabled is true on AWS unless explicitly disabled, so an absent property means true. */
    @Test
    void actionsAreEnabledUnlessExplicitlyDisabled() {
        assertTrue(provisionAndCapture("""
                {"AlarmName": "a"}
                """).isActionsEnabled());

        setUp();
        assertFalse(provisionAndCapture("""
                {"AlarmName": "a", "ActionsEnabled": "false"}
                """).isActionsEnabled());
    }

    @Test
    void dimensionsAndAllThreeActionListsAreCarried() {
        MetricAlarm alarm = provisionAndCapture("""
                {
                  "AlarmName": "a",
                  "Dimensions": [{"Name": "InstanceId", "Value": "i-123"}],
                  "AlarmActions": ["arn:alarm"],
                  "OKActions": ["arn:ok"],
                  "InsufficientDataActions": ["arn:insufficient"]
                }
                """);

        assertEquals(1, alarm.getDimensions().size());
        assertEquals("InstanceId", alarm.getDimensions().get(0).name());
        assertEquals("i-123", alarm.getDimensions().get(0).value());
        assertEquals(List.of("arn:alarm"), alarm.getAlarmActions());
        assertEquals(List.of("arn:ok"), alarm.getOkActions());
        assertEquals(List.of("arn:insufficient"), alarm.getInsufficientDataActions());
    }

    @Test
    void anUnparseableThresholdIsIgnoredRatherThanFailingTheResource() {
        MetricAlarm alarm = provisionAndCapture("""
                {"AlarmName": "a", "Threshold": "high"}
                """);

        assertEquals(0.0, alarm.getThreshold());
    }

    @Test
    void deleteReachesTheService() {
        provisioner.delete("AWS::CloudWatch::Alarm", "cpu-high", REGION);
        verify(metrics).deleteAlarms(List.of("cpu-high"), REGION);
    }
}
