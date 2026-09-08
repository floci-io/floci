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
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.GetDashboardResponse;
import software.amazon.awssdk.services.cloudwatch.model.CloudWatchException;
import software.amazon.awssdk.services.cloudwatch.model.Tag;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives an {@code AWS::CloudWatch::Dashboard} through the CloudFormation SDK and reads it back
 * through the CloudWatch SDK: {@code Ref} is the name, a body or tag change updates in place, a
 * name change replaces the dashboard and removes the displaced one, and the stack delete removes it.
 */
@DisplayName("CloudFormation AWS::CloudWatch::Dashboard")
class CloudFormationCloudWatchDashboardTest {

    private static CloudFormationClient cloudFormation;
    private static CloudWatchClient cloudWatch;
    private static String stackName;
    private static String dashboardName;

    @BeforeAll
    static void setup() {
        cloudFormation = TestFixtures.cloudFormationClient();
        cloudWatch = TestFixtures.cloudWatchClient();
        stackName = TestFixtures.uniqueName("compat-cfn-dashboard");
        dashboardName = TestFixtures.uniqueName("compat-dashboard");
    }

    @AfterAll
    static void cleanup() {
        if (cloudFormation != null) {
            try {
                cloudFormation.deleteStack(r -> r.stackName(stackName));
                waitForDeleted(stackName, 60);
            } catch (Exception e) {
                System.err.println("CloudFormation dashboard cleanup skipped: " + e.getMessage());
            }
            cloudFormation.close();
        }
        if (cloudWatch != null) {
            cloudWatch.close();
        }
    }

    @Test
    void dashboardFollowsTheTemplateThroughCreateUpdateReplacementAndDelete() throws InterruptedException {
        cloudFormation.createStack(r -> r
                .stackName(stackName)
                .templateBody(template())
                .parameters(parameters(dashboardName, "Requests", "platform")));
        assertThat(waitForTerminal(stackName, 60)).isEqualTo("CREATE_COMPLETE");

        assertThat(output("DashboardRef")).as("Ref is the dashboard name").isEqualTo(dashboardName);
        GetDashboardResponse created = get(dashboardName);
        assertThat(created.dashboardBody())
                .isEqualTo("{\"widgets\":[{\"type\":\"text\",\"properties\":{\"markdown\":\"Requests\"}}]}");
        assertThat(created.dashboardArn()).endsWith(":dashboard/" + dashboardName);
        assertThat(tags(created.dashboardArn())).containsExactlyEntriesOf(Map.of("team", "platform"));

        cloudFormation.updateStack(r -> r
                .stackName(stackName)
                .templateBody(template())
                .parameters(parameters(dashboardName, "Errors", "data")));
        assertThat(waitForTerminal(stackName, 60)).isEqualTo("UPDATE_COMPLETE");
        assertThat(output("DashboardRef")).as("a body or tag change updates in place").isEqualTo(dashboardName);
        assertThat(get(dashboardName).dashboardBody()).contains("\"Errors\"");
        assertThat(tags(created.dashboardArn())).containsExactlyEntriesOf(Map.of("team", "data"));

        String renamed = dashboardName + "-v2";
        cloudFormation.updateStack(r -> r
                .stackName(stackName)
                .templateBody(template())
                .parameters(parameters(renamed, "Errors", "data")));
        assertThat(waitForTerminal(stackName, 60)).isEqualTo("UPDATE_COMPLETE");
        assertThat(output("DashboardRef")).as("a name change replaces the dashboard").isEqualTo(renamed);
        assertThat(get(renamed).dashboardBody()).contains("\"Errors\"");
        assertDashboardMissing(dashboardName, "the displaced dashboard is deleted once the update commits");

        cloudFormation.deleteStack(r -> r.stackName(stackName));
        waitForDeleted(stackName, 60);
        assertDashboardMissing(renamed, "the stack delete removes the dashboard");
    }

    /**
     * GetDashboard answers ResourceNotFound with HTTP 404 for a dashboard that does not exist. The
     * code is asserted rather than the exception class: the SDK resolves the class from the JSON
     * error type, and the code is what both the Query and JSON wire forms carry.
     */
    private static void assertDashboardMissing(String name, String because) {
        assertThatThrownBy(() -> get(name))
                .as(because)
                .isInstanceOfSatisfying(CloudWatchException.class, e -> {
                    assertThat(e.statusCode()).isEqualTo(404);
                    assertThat(e.awsErrorDetails().errorCode()).isEqualTo("ResourceNotFound");
                });
    }

    private static GetDashboardResponse get(String name) {
        return cloudWatch.getDashboard(r -> r.dashboardName(name));
    }

    private static Map<String, String> tags(String arn) {
        return cloudWatch.listTagsForResource(r -> r.resourceARN(arn)).tags().stream()
                .collect(Collectors.toMap(Tag::key, Tag::value));
    }

    private static String template() {
        return """
                {
                  "Parameters": {
                    "Name": {"Type": "String"},
                    "Title": {"Type": "String"},
                    "Team": {"Type": "String"}
                  },
                  "Resources": {
                    "Dashboard": {
                      "Type": "AWS::CloudWatch::Dashboard",
                      "Properties": {
                        "DashboardName": {"Ref": "Name"},
                        "DashboardBody": {"Fn::Join": ["", [
                          "{\\"widgets\\":[{\\"type\\":\\"text\\",\\"properties\\":{\\"markdown\\":\\"",
                          {"Ref": "Title"},
                          "\\"}}]}"
                        ]]},
                        "Tags": [{"Key": "team", "Value": {"Ref": "Team"}}]
                      }
                    }
                  },
                  "Outputs": {
                    "DashboardRef": {"Value": {"Ref": "Dashboard"}}
                  }
                }
                """;
    }

    private static List<Parameter> parameters(String name, String title, String team) {
        return List.of(
                Parameter.builder().parameterKey("Name").parameterValue(name).build(),
                Parameter.builder().parameterKey("Title").parameterValue(title).build(),
                Parameter.builder().parameterKey("Team").parameterValue(team).build());
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
