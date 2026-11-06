package com.floci.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.AlarmType;
import software.amazon.awssdk.services.cloudwatch.model.CloudWatchException;
import software.amazon.awssdk.services.cloudwatch.model.ComparisonOperator;
import software.amazon.awssdk.services.cloudwatch.model.CompositeAlarm;
import software.amazon.awssdk.services.cloudwatch.model.StateValue;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudWatch - Composite alarm evaluation")
class CloudWatchCompositeAlarmTest {

    private CloudWatchClient cloudWatch;
    private final List<String> composites = new ArrayList<>();
    private final List<String> metricAlarms = new ArrayList<>();

    @BeforeEach
    void setup() {
        cloudWatch = TestFixtures.cloudWatchClient();
    }

    @AfterEach
    void cleanup() {
        try {
            // Parents first: CloudWatch refuses to delete an alarm a composite alarm still references.
            for (int index = composites.size() - 1; index >= 0; index--) {
                String name = composites.get(index);
                cloudWatch.deleteAlarms(request -> request.alarmNames(name));
            }
            if (!metricAlarms.isEmpty()) {
                cloudWatch.deleteAlarms(request -> request.alarmNames(metricAlarms));
            }
        } finally {
            cloudWatch.close();
        }
    }

    @Test
    @DisplayName("Reasons and triggering alarms follow creation, child transitions and updates")
    void stateReasonsFollowCreationTransitionsAndUpdates() {
        String a = metricAlarm("sdk-composite-a");
        String b = metricAlarm("sdk-composite-b");
        String both = composite("sdk-composite-both", "ALARM(\"" + a + "\") AND ALARM(\"" + b + "\")");

        CompositeAlarm created = describe(both);
        assertThat(created.stateValue()).isEqualTo(StateValue.OK);
        assertThat(created.stateReason()).isEqualTo(created.alarmArn() + " was created and its alarm rule evaluates to OK");
        assertThat(created.stateReasonData()).startsWith("{\"triggeringAlarms\":[{\"arn\":\"")
                .contains(arn(a), arn(b));

        cloudWatch.setAlarmState(request -> request.alarmName(a).stateValue(StateValue.ALARM).stateReason("test"));
        assertThat(describe(both).stateReason()).isEqualTo(created.stateReason());
        cloudWatch.setAlarmState(request -> request.alarmName(b).stateValue(StateValue.ALARM).stateReason("test"));
        CompositeAlarm transitioned = describe(both);
        assertThat(transitioned.stateValue()).isEqualTo(StateValue.ALARM);
        assertThat(transitioned.stateReason()).matches("\\Q" + arn(b) + " transitioned to ALARM at \\E"
                + "[A-Z][a-z]+day \\d{2} [A-Z][a-z]+, \\d{4} \\d{2}:\\d{2}:\\d{2} UTC");
        assertThat(transitioned.stateReasonData()).startsWith("{\"triggeringAlarms\":[{\"arn\":\"" + arn(b) + "\"")
                .contains(arn(a));

        cloudWatch.putCompositeAlarm(request -> request.alarmName(both).alarmRule("ALARM(" + a + ") AND OK(" + b + ")")
                .actionsEnabled(false));
        CompositeAlarm updated = describe(both);
        assertThat(updated.stateValue()).isEqualTo(StateValue.OK);
        assertThat(updated.stateReason()).isEqualTo(updated.alarmArn() + " was updated and its new alarm rule evaluates to OK");
        assertThat(updated.stateReasonData()).matches("\\Q{\"triggeringAlarms\":[{\"arn\":\"" + arn(b)
                + "\",\"state\":{\"value\":\"ALARM\",\"timestamp\":\"\\E"
                + "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+0000\\Q\"}}]}\\E");
    }

    @Test
    @DisplayName("Unknown children and deleting referenced alarms fail with CloudWatch's messages")
    void refusalsMatchCloudWatch() {
        String child = metricAlarm("sdk-composite-child");
        String parent = composite("sdk-composite-parent", "OK(" + child + ")");
        String other = composite("sdk-composite-other", "TRUE");

        assertValidationError(() -> cloudWatch.deleteAlarms(request -> request.alarmNames(child)),
                "Cannot delete " + child + " as there are composite alarm(s) depending on it.");
        assertValidationError(() -> cloudWatch.deleteAlarms(request -> request.alarmNames(child, parent)),
                "Cannot delete " + child + " as there are composite alarm(s) depending on it.");
        assertValidationError(() -> cloudWatch.deleteAlarms(request -> request.alarmNames(parent, other)),
                "Cannot delete [2] Composite Alarms as only one composite alarm delete is allowed in single request");
        String missing = TestFixtures.uniqueName("sdk-composite-missing");
        assertValidationError(() -> cloudWatch.putCompositeAlarm(request -> request
                        .alarmName(TestFixtures.uniqueName("sdk-composite-orphan")).alarmRule("ALARM(" + missing + ")")),
                "Could not save the composite alarm as alarms [" + arn(missing) + "] in the alarm rule do not exist");
        assertThat(describe(parent).alarmRule()).isEqualTo("OK(" + child + ")");
        assertThat(cloudWatch.describeAlarms(request -> request.alarmNames(child)).metricAlarms()).hasSize(1);
    }

    private void assertValidationError(Runnable call, String message) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CloudWatchException.class, error -> {
            assertThat(error.statusCode()).isEqualTo(400);
            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
            assertThat(error.awsErrorDetails().errorMessage()).isEqualTo(message);
        });
    }

    /** TreatMissingData=ignore keeps the emulator's alarm evaluator from resetting the states these tests set. */
    private String metricAlarm(String prefix) {
        String name = TestFixtures.uniqueName(prefix);
        cloudWatch.putMetricAlarm(request -> request.alarmName(name).namespace("Compat/Composite")
                .metricName("Errors").statistic(Statistic.SUM).period(3600).evaluationPeriods(1).threshold(1.0)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD).treatMissingData("ignore")
                .actionsEnabled(false));
        metricAlarms.add(name);
        return name;
    }

    private String composite(String prefix, String rule) {
        String name = TestFixtures.uniqueName(prefix);
        cloudWatch.putCompositeAlarm(request -> request.alarmName(name).alarmRule(rule).actionsEnabled(false));
        composites.add(name);
        return name;
    }

    private CompositeAlarm describe(String name) {
        List<CompositeAlarm> found = cloudWatch.describeAlarms(request -> request.alarmNames(name)
                .alarmTypes(AlarmType.COMPOSITE_ALARM)).compositeAlarms();
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private String arn(String name) {
        String any = cloudWatch.describeAlarms(request -> request.alarmNames(metricAlarms.get(0)))
                .metricAlarms().get(0).alarmArn();
        return any.substring(0, any.lastIndexOf(':') + 1) + name;
    }
}
