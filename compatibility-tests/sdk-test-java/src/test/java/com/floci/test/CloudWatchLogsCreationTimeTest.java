package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogGroup;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CloudWatch Logs creationTime")
class CloudWatchLogsCreationTimeTest {

    @Test
    @DisplayName("DescribeLogGroups and DescribeLogStreams return creationTime")
    void describeReturnsCreationTime() {
        String groupName = "/test/" + TestFixtures.uniqueName("logs-creation-time");
        String streamName = "s1";
        long before = System.currentTimeMillis();
        try (CloudWatchLogsClient logs = TestFixtures.cloudWatchLogsClient()) {
            try {
                logs.createLogGroup(request -> request.logGroupName(groupName));
                logs.createLogStream(request -> request.logGroupName(groupName).logStreamName(streamName));

                LogGroup group = logs.describeLogGroups(request -> request.logGroupNamePrefix(groupName))
                        .logGroups()
                        .stream()
                        .filter(candidate -> groupName.equals(candidate.logGroupName()))
                        .findFirst()
                        .orElseThrow();
                LogStream stream = logs.describeLogStreams(request -> request.logGroupName(groupName))
                        .logStreams()
                        .stream()
                        .filter(candidate -> streamName.equals(candidate.logStreamName()))
                        .findFirst()
                        .orElseThrow();

                assertThat(group.creationTime()).isNotNull().isGreaterThanOrEqualTo(before);
                assertThat(stream.creationTime()).isNotNull().isGreaterThanOrEqualTo(before);
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
