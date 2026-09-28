package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
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

                LogGroup group = findGroup(logs, groupName);

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

    @Test
    @DisplayName("returns storedBytes for the log group after events are ingested")
    void returnsStoredBytesAfterIngest() {
        String groupName = "/test/" + TestFixtures.uniqueName("logs-describe-bytes");
        String streamName = "stream-1";
        try (CloudWatchLogsClient logs = TestFixtures.cloudWatchLogsClient()) {
            try {
                logs.createLogGroup(request -> request.logGroupName(groupName));
                logs.createLogStream(request -> request
                        .logGroupName(groupName)
                        .logStreamName(streamName));

                LogGroup empty = findGroup(logs, groupName);
                assertThat(empty.storedBytes()).isZero();

                logs.putLogEvents(request -> request
                        .logGroupName(groupName)
                        .logStreamName(streamName)
                        .logEvents(InputLogEvent.builder()
                                .timestamp(System.currentTimeMillis())
                                .message("hello")
                                .build()));

                LogGroup afterIngest = findGroup(logs, groupName);
                assertThat(afterIngest.storedBytes()).isPositive();
            } finally {
                deleteIfPresent(logs, groupName);
            }
        }
    }

    private static LogGroup findGroup(CloudWatchLogsClient logs, String groupName) {
        return logs.describeLogGroups(request -> request.logGroupNamePrefix(groupName))
                .logGroups()
                .stream()
                .filter(candidate -> groupName.equals(candidate.logGroupName()))
                .findFirst()
                .orElseThrow();
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