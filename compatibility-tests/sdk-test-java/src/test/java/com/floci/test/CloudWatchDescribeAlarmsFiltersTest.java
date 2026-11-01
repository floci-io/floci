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
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsRequest;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricAlarm;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricAlarmRequest;
import software.amazon.awssdk.services.cloudwatch.model.StateValue;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudWatch - DescribeAlarms filters")
class CloudWatchDescribeAlarmsFiltersTest {

    private CloudWatchClient cloudWatch;
    private String prefix;
    private final List<String> created = new ArrayList<>();

    @BeforeEach
    void setup() {
        cloudWatch = TestFixtures.cloudWatchClient();
        prefix = TestFixtures.uniqueName("sdk-describe-filters");
    }

    @AfterEach
    void cleanup() {
        try {
            for (int index = created.size() - 1; index >= 0; index--) {
                String name = created.get(index);
                cloudWatch.deleteAlarms(request -> request.alarmNames(name));
            }
        } finally {
            cloudWatch.close();
        }
    }

    @Test
    @DisplayName("StateValue and ActionPrefix narrow metric and composite alarms")
    void stateValueAndActionPrefixNarrowBothAlarmKinds() {
        String topic = "arn:aws:sns:us-east-1:000000000000:" + prefix;
        String alarm = putMetricAlarm("alarm", request -> request.alarmActions(topic + "-page"));
        String ok = putMetricAlarm("ok", request -> request.okActions(topic + "-info"));
        String unknown = putMetricAlarm("unknown", request -> request.insufficientDataActions(topic + "-info"));
        setState(alarm, StateValue.ALARM);
        setState(ok, StateValue.OK);
        String compositeAlarm = putCompositeAlarm("composite-alarm", "TRUE", topic + "-page");
        String compositeOk = putCompositeAlarm("composite-ok", "FALSE", null);

        DescribeAlarmsResponse inAlarm = cloudWatch.describeAlarms(request -> request.alarmNamePrefix(prefix)
                .stateValue(StateValue.ALARM).alarmTypes(AlarmType.METRIC_ALARM, AlarmType.COMPOSITE_ALARM));
        assertThat(inAlarm.metricAlarms()).extracting(MetricAlarm::alarmName).containsExactly(alarm);
        assertThat(inAlarm.compositeAlarms()).extracting(CompositeAlarm::alarmName).containsExactly(compositeAlarm);

        DescribeAlarmsResponse okAlarms = cloudWatch.describeAlarms(request -> request.alarmNamePrefix(prefix)
                .stateValue(StateValue.OK).alarmTypes(AlarmType.METRIC_ALARM, AlarmType.COMPOSITE_ALARM));
        assertThat(okAlarms.metricAlarms()).extracting(MetricAlarm::alarmName).containsExactly(ok);
        assertThat(okAlarms.compositeAlarms()).extracting(CompositeAlarm::alarmName).containsExactly(compositeOk);

        DescribeAlarmsResponse pageAction = cloudWatch.describeAlarms(request -> request.actionPrefix(topic + "-page")
                .alarmTypes(AlarmType.METRIC_ALARM, AlarmType.COMPOSITE_ALARM));
        assertThat(pageAction.metricAlarms()).extracting(MetricAlarm::alarmName).containsExactly(alarm);
        assertThat(pageAction.compositeAlarms()).extracting(CompositeAlarm::alarmName).containsExactly(compositeAlarm);

        assertThat(cloudWatch.describeAlarms(request -> request.actionPrefix(topic + "-info")).metricAlarms())
                .extracting(MetricAlarm::alarmName).containsExactlyInAnyOrder(ok, unknown);
        assertThat(cloudWatch.describeAlarms(request -> request.actionPrefix(topic)
                .stateValue(StateValue.INSUFFICIENT_DATA)).metricAlarms())
                .extracting(MetricAlarm::alarmName).containsExactly(unknown);
    }

    @Test
    @DisplayName("ChildrenOfAlarmName and ParentsOfAlarmName return the related alarms' names and ARNs")
    void childrenAndParentsReturnTheRelatedAlarms() {
        String cpu = putMetricAlarm("cpu", request -> { });
        String disk = putMetricAlarm("disk", request -> { });
        setState(cpu, StateValue.ALARM);
        String diskArn = cloudWatch.describeAlarms(request -> request.alarmNames(disk)).metricAlarms().get(0)
                .alarmArn();
        String host = putCompositeAlarm("host", "ALARM(" + cpu + ") OR ALARM(\"" + diskArn + "\")", null);
        String fleet = putCompositeAlarm("fleet", "ALARM(" + host + ") AND NOT OK(" + cpu + ")", null);

        DescribeAlarmsResponse hostChildren = cloudWatch.describeAlarms(request -> request.childrenOfAlarmName(host));
        assertThat(hostChildren.metricAlarms()).extracting(MetricAlarm::alarmName).containsExactlyInAnyOrder(cpu, disk);
        assertThat(hostChildren.compositeAlarms()).isEmpty();
        MetricAlarm cpuChild = hostChildren.metricAlarms().stream()
                .filter(child -> child.alarmName().equals(cpu)).findFirst().orElseThrow();
        assertThat(cpuChild.stateValue()).isEqualTo(StateValue.ALARM);
        assertThat(cpuChild.stateUpdatedTimestamp()).isNotNull();
        assertThat(cpuChild.alarmArn()).endsWith(":alarm:" + cpu);
        assertThat(cpuChild.stateReason()).isNull();
        assertThat(cpuChild.metricName()).isNull();
        assertThat(cpuChild.hasAlarmActions()).isFalse();

        DescribeAlarmsResponse fleetChildren = cloudWatch.describeAlarms(request -> request
                .childrenOfAlarmName(fleet).maxRecords(10));
        assertThat(fleetChildren.metricAlarms()).extracting(MetricAlarm::alarmName).containsExactly(cpu);
        assertThat(fleetChildren.compositeAlarms()).extracting(CompositeAlarm::alarmName).containsExactly(host);
        assertThat(fleetChildren.compositeAlarms().get(0).alarmRule()).isNull();

        DescribeAlarmsResponse cpuParents = cloudWatch.describeAlarms(request -> request.parentsOfAlarmName(cpu));
        assertThat(cpuParents.metricAlarms()).isEmpty();
        assertThat(cpuParents.compositeAlarms()).extracting(CompositeAlarm::alarmName)
                .containsExactlyInAnyOrder(host, fleet);
        CompositeAlarm hostParent = cpuParents.compositeAlarms().stream()
                .filter(parent -> parent.alarmName().equals(host)).findFirst().orElseThrow();
        assertThat(hostParent.alarmArn()).endsWith(":alarm:" + host);
        assertThat(hostParent.stateValue()).isNull();
        assertThat(hostParent.alarmRule()).isNull();

        assertThat(cloudWatch.describeAlarms(request -> request.parentsOfAlarmName(disk)).compositeAlarms())
                .extracting(CompositeAlarm::alarmName).containsExactly(host);
        assertThat(cloudWatch.describeAlarms(request -> request.parentsOfAlarmName(fleet)).compositeAlarms()).isEmpty();
    }

    @Test
    @DisplayName("ChildrenOfAlarmName and ParentsOfAlarmName refuse any other filter")
    void childrenAndParentsRefuseOtherFilters() {
        assertRefused("ChildrenOfAlarmName", request -> request.childrenOfAlarmName(prefix).stateValue(StateValue.OK));
        assertRefused("ChildrenOfAlarmName", request -> request.childrenOfAlarmName(prefix)
                .alarmTypes(AlarmType.COMPOSITE_ALARM));
        assertRefused("ChildrenOfAlarmName", request -> request.childrenOfAlarmName(prefix).parentsOfAlarmName(prefix));
        assertRefused("ParentsOfAlarmName", request -> request.parentsOfAlarmName(prefix).alarmNames(prefix));
        assertRefused("ParentsOfAlarmName", request -> request.parentsOfAlarmName(prefix).actionPrefix("arn:"));
    }

    @Test
    @DisplayName("A StateValue outside OK, ALARM and INSUFFICIENT_DATA is refused")
    void unknownStateValueIsRefused() {
        assertThatThrownBy(() -> cloudWatch.describeAlarms(request -> request.stateValue("BOGUS")))
                .isInstanceOf(CloudWatchException.class)
                .satisfies(error -> {
                    CloudWatchException exception = (CloudWatchException) error;
                    assertThat(exception.statusCode()).isEqualTo(400);
                    assertThat(exception.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
                    assertThat(exception.awsErrorDetails().errorMessage()).isEqualTo("1 validation error detected: "
                            + "Value 'BOGUS' at 'stateValue' failed to satisfy constraint: Member must satisfy enum "
                            + "value set: [INSUFFICIENT_DATA, ALARM, OK]");
                });
    }

    private void assertRefused(String named, Consumer<DescribeAlarmsRequest.Builder> request) {
        assertThatThrownBy(() -> cloudWatch.describeAlarms(request))
                .isInstanceOf(CloudWatchException.class)
                .satisfies(error -> {
                    CloudWatchException exception = (CloudWatchException) error;
                    assertThat(exception.statusCode()).isEqualTo(400);
                    assertThat(exception.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
                    assertThat(exception.awsErrorDetails().errorMessage()).isEqualTo("If you specify " + named
                            + ", you cannot specify any other parameters in the request except for MaxRecords and "
                            + "NextToken.");
                });
    }

    private String putMetricAlarm(String suffix, Consumer<PutMetricAlarmRequest.Builder> actions) {
        String name = prefix + "-" + suffix;
        cloudWatch.putMetricAlarm(request -> {
            request.alarmName(name).namespace("SdkDescribeFilters").metricName(name).statistic(Statistic.AVERAGE)
                    .period(60).evaluationPeriods(1).threshold(1.0)
                    .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                    .treatMissingData("ignore").actionsEnabled(false);
            actions.accept(request);
        });
        created.add(name);
        return name;
    }

    private String putCompositeAlarm(String suffix, String rule, String alarmAction) {
        String name = prefix + "-" + suffix;
        cloudWatch.putCompositeAlarm(request -> {
            request.alarmName(name).alarmRule(rule).actionsEnabled(false);
            if (alarmAction != null) {
                request.alarmActions(alarmAction);
            }
        });
        created.add(name);
        return name;
    }

    private void setState(String name, StateValue state) {
        cloudWatch.setAlarmState(request -> request.alarmName(name).stateValue(state).stateReason("sdk test"));
    }
}
