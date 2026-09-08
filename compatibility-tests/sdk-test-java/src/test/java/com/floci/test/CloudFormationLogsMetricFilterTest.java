package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.MetricFilter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives an {@code AWS::Logs::MetricFilter} through the CloudFormation SDK and reads it back
 * through the Logs SDK: the physical id is {@code LogGroupName|FilterName}, a pattern change updates
 * in place, a name change replaces the filter, and the stack delete removes it.
 */
@DisplayName("CloudFormation AWS::Logs::MetricFilter")
class CloudFormationLogsMetricFilterTest {

    private static CloudFormationClient cloudFormation;
    private static CloudWatchLogsClient logs;
    private static String stackName;
    private static String group;

    @BeforeAll
    static void setup() {
        cloudFormation = TestFixtures.cloudFormationClient();
        logs = TestFixtures.cloudWatchLogsClient();
        stackName = TestFixtures.uniqueName("compat-cfn-metric-filter");
        group = "/test/" + TestFixtures.uniqueName("cfn-metric-filter");
        logs.createLogGroup(r -> r.logGroupName(group));
    }

    @AfterAll
    static void cleanup() {
        if (cloudFormation != null) {
            try {
                cloudFormation.deleteStack(r -> r.stackName(stackName));
                waitForDeleted(stackName, 60);
            } catch (Exception e) {
                System.err.println("CloudFormation metric filter cleanup skipped: " + e.getMessage());
            }
            cloudFormation.close();
        }
        if (logs != null) {
            try {
                logs.deleteLogGroup(r -> r.logGroupName(group));
            } catch (Exception e) {
                System.err.println("metric filter log group cleanup skipped: " + e.getMessage());
            }
            logs.close();
        }
    }

    @Test
    void filterFollowsTheTemplateThroughCreateUpdateReplacementAndDelete() throws InterruptedException {
        cloudFormation.createStack(r -> r
                .stackName(stackName)
                .templateBody(template())
                .parameters(parameters("errors", "ERROR")));
        assertThat(waitForTerminal(stackName, 60)).isEqualTo("CREATE_COMPLETE");
        assertThat(output("FilterRef")).as("Ref is LogGroupName|FilterName").isEqualTo(group + "|errors");
        List<MetricFilter> created = logs.describeMetricFilters(r -> r.logGroupName(group)).metricFilters();
        assertThat(created).hasSize(1);
        assertThat(created.get(0).filterName()).isEqualTo("errors");
        assertThat(created.get(0).filterPattern()).isEqualTo("ERROR");
        assertThat(created.get(0).metricTransformations().get(0).metricName()).isEqualTo("ErrorCount");
        assertThat(created.get(0).metricTransformations().get(0).defaultValue()).isEqualTo(0.0);

        cloudFormation.updateStack(r -> r
                .stackName(stackName)
                .templateBody(template())
                .parameters(parameters("errors", "FATAL")));
        assertThat(waitForTerminal(stackName, 60)).isEqualTo("UPDATE_COMPLETE");
        assertThat(output("FilterRef")).as("a pattern change updates in place").isEqualTo(group + "|errors");
        assertThat(logs.describeMetricFilters(r -> r.logGroupName(group)).metricFilters())
                .extracting(MetricFilter::filterPattern).containsExactly("FATAL");

        cloudFormation.updateStack(r -> r
                .stackName(stackName)
                .templateBody(template())
                .parameters(parameters("fatal", "FATAL")));
        assertThat(waitForTerminal(stackName, 60)).isEqualTo("UPDATE_COMPLETE");
        assertThat(output("FilterRef")).as("a name change replaces the filter").isEqualTo(group + "|fatal");
        assertThat(logs.describeMetricFilters(r -> r.logGroupName(group)).metricFilters())
                .extracting(MetricFilter::filterName).containsExactly("fatal");

        cloudFormation.deleteStack(r -> r.stackName(stackName));
        waitForDeleted(stackName, 60);
        assertThat(logs.describeMetricFilters(r -> r.logGroupName(group)).metricFilters()).isEmpty();
    }

    private static String template() {
        return """
                {
                  "Parameters": {
                    "FilterName": {"Type": "String"},
                    "Pattern": {"Type": "String"}
                  },
                  "Resources": {
                    "Filter": {
                      "Type": "AWS::Logs::MetricFilter",
                      "Properties": {
                        "LogGroupName": "%s",
                        "FilterName": {"Ref": "FilterName"},
                        "FilterPattern": {"Ref": "Pattern"},
                        "MetricTransformations": [{
                          "MetricName": "ErrorCount",
                          "MetricNamespace": "CompatStack",
                          "MetricValue": "1",
                          "DefaultValue": 0,
                          "Unit": "Count"
                        }]
                      }
                    }
                  },
                  "Outputs": {
                    "FilterRef": {"Value": {"Ref": "Filter"}}
                  }
                }
                """.formatted(group);
    }

    private static List<Parameter> parameters(String filterName, String pattern) {
        return List.of(
                Parameter.builder().parameterKey("FilterName").parameterValue(filterName).build(),
                Parameter.builder().parameterKey("Pattern").parameterValue(pattern).build());
    }

    private static String output(String key) {
        Stack stack = cloudFormation.describeStacks(
                DescribeStacksRequest.builder().stackName(stackName).build()).stacks().get(0);
        return stack.outputs().stream()
                .filter(o -> key.equals(o.outputKey()))
                .map(Output::outputValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("stack " + stackName + " has no output " + key));
    }

    private static String waitForTerminal(String name, int maxSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            List<Stack> stacks = cloudFormation.describeStacks(
                    DescribeStacksRequest.builder().stackName(name).build()).stacks();
            if (!stacks.isEmpty()) {
                String status = stacks.get(0).stackStatusAsString();
                if (!status.endsWith("_IN_PROGRESS")) {
                    return status;
                }
            }
            Thread.sleep(500);
        }
        throw new AssertionError(
                "Stack " + name + " did not reach a terminal state within " + maxSeconds + "s");
    }

    private static void waitForDeleted(String name, int maxSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<Stack> stacks = cloudFormation.describeStacks(
                        DescribeStacksRequest.builder().stackName(name).build()).stacks();
                if (stacks.isEmpty()
                        || "DELETE_COMPLETE".equals(stacks.get(0).stackStatusAsString())) {
                    return;
                }
            } catch (CloudFormationException e) {
                if (e.getMessage() != null && e.getMessage().contains("does not exist")) {
                    return;
                }
                throw e;
            }
            Thread.sleep(500);
        }
        throw new AssertionError(
                "Stack " + name + " was not deleted within " + maxSeconds + "s");
    }
}
