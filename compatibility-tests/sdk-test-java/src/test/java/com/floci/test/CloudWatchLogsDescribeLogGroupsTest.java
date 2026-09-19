package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogGroup;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CloudWatch Logs DescribeLogGroups")
class CloudWatchLogsDescribeLogGroupsTest {

    @Test
    @DisplayName("returns the AWS LogGroup ARN fields with their distinct formats")
    void returnsAwsLogGroupArnFields() {
        String groupName = "/test/" + TestFixtures.uniqueName("logs-describe-arn");
        try (CloudWatchLogsClient logs = TestFixtures.cloudWatchLogsClient()) {
            try {
                logs.createLogGroup(request -> request.logGroupName(groupName));

                LogGroup group = logs.describeLogGroups(request -> request
                                .logGroupNamePrefix(groupName))
                        .logGroups()
                        .stream()
                        .filter(candidate -> groupName.equals(candidate.logGroupName()))
                        .findFirst()
                        .orElseThrow();

                assertThat(group.arn())
                        .isEqualTo("arn:aws:logs:us-east-1:000000000000:log-group:" + groupName + ":*");
                assertThat(group.logGroupArn())
                        .isEqualTo("arn:aws:logs:us-east-1:000000000000:log-group:" + groupName);
                assertThat(group.logGroupArn()).doesNotEndWith(":*");
            } finally {
                deleteIfPresent(logs, groupName);
            }
        }
    }

    private static void deleteIfPresent(CloudWatchLogsClient logs, String groupName) {
        logs.describeLogGroups(request -> request.logGroupNamePrefix(groupName))
                .logGroups()
                .stream()
                .filter(group -> groupName.equals(group.logGroupName()))
                .findFirst()
                .ifPresent(group -> logs.deleteLogGroup(request -> request.logGroupName(groupName)));
    }
}