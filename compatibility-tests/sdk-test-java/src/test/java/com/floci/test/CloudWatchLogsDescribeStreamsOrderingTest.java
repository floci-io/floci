package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogStreamsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.InvalidParameterException;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogStream;
import software.amazon.awssdk.services.cloudwatchlogs.model.OrderBy;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudWatch Logs DescribeLogStreams ordering and pagination")
class CloudWatchLogsDescribeStreamsOrderingTest {

    @Test
    @DisplayName("orderBy LastEventTime descending with limit 1 returns the most recently active stream")
    void lastEventTimeDescendingWithLimitReturnsMostRecentStream() {
        String groupName = "/test/" + TestFixtures.uniqueName("logs-describe-streams-order");

        try (CloudWatchLogsClient logs = TestFixtures.cloudWatchLogsClient()) {
            try {
                logs.createLogGroup(request -> request.logGroupName(groupName));
                // Alphabetical order deliberately disagrees with event recency, so a
                // name-sorted result (the pre-fix behavior) fails the assertion.
                long base = System.currentTimeMillis();
                createStreamWithEvent(logs, groupName, "a-oldest", base - 2000);
                createStreamWithEvent(logs, groupName, "b-newest", base);
                createStreamWithEvent(logs, groupName, "c-middle", base - 1000);

                DescribeLogStreamsResponse response = logs.describeLogStreams(request -> request
                        .logGroupName(groupName)
                        .orderBy(OrderBy.LAST_EVENT_TIME)
                        .descending(true)
                        .limit(1));

                assertThat(response.logStreams())
                        .as("limit is honored on the wire")
                        .hasSize(1);
                assertThat(response.logStreams().get(0).logStreamName())
                        .as("descending LastEventTime puts the most recently active stream first")
                        .isEqualTo("b-newest");
                assertThat(response.nextToken())
                        .as("more streams remain, so a next page is advertised")
                        .isNotNull();
            } finally {
                deleteIfPresent(logs, groupName);
            }
        }
    }

    @Test
    @DisplayName("the SDK paginator walks every stream once and terminates")
    void paginatorWalksEveryStreamOnceAndTerminates() {
        String groupName = "/test/" + TestFixtures.uniqueName("logs-describe-streams-paging");

        try (CloudWatchLogsClient logs = TestFixtures.cloudWatchLogsClient()) {
            try {
                logs.createLogGroup(request -> request.logGroupName(groupName));
                for (int i = 0; i < 5; i++) {
                    int index = i;
                    logs.createLogStream(request -> request
                            .logGroupName(groupName)
                            .logStreamName("stream-" + index));
                }

                List<String> names = new ArrayList<>();
                logs.describeLogStreamsPaginator(request -> request
                                .logGroupName(groupName)
                                .limit(2))
                        .stream()
                        .forEach(page -> page.logStreams().stream()
                                .map(LogStream::logStreamName)
                                .forEach(names::add));

                assertThat(names)
                        .as("every stream is seen exactly once, name-ascending by default")
                        .containsExactly("stream-0", "stream-1", "stream-2", "stream-3", "stream-4");
            } finally {
                deleteIfPresent(logs, groupName);
            }
        }
    }

    @Test
    @DisplayName("orderBy LastEventTime combined with a name prefix is rejected like real AWS")
    void lastEventTimeWithPrefixIsRejected() {
        String groupName = "/test/" + TestFixtures.uniqueName("logs-describe-streams-reject");

        try (CloudWatchLogsClient logs = TestFixtures.cloudWatchLogsClient()) {
            try {
                logs.createLogGroup(request -> request.logGroupName(groupName));

                assertThatThrownBy(() -> logs.describeLogStreams(request -> request
                        .logGroupName(groupName)
                        .logStreamNamePrefix("stream")
                        .orderBy(OrderBy.LAST_EVENT_TIME)))
                        .as("real AWS rejects LastEventTime ordering with a logStreamNamePrefix")
                        .isInstanceOf(InvalidParameterException.class);
            } finally {
                deleteIfPresent(logs, groupName);
            }
        }
    }

    private static void createStreamWithEvent(CloudWatchLogsClient logs, String groupName,
                                              String streamName, long timestamp) {
        logs.createLogStream(request -> request
                .logGroupName(groupName)
                .logStreamName(streamName));
        logs.putLogEvents(request -> request
                .logGroupName(groupName)
                .logStreamName(streamName)
                .logEvents(InputLogEvent.builder()
                        .timestamp(timestamp)
                        .message("event for " + streamName)
                        .build()));
    }

    private static void deleteIfPresent(CloudWatchLogsClient logs, String groupName) {
        boolean present = logs.describeLogGroups(request -> request.logGroupNamePrefix(groupName))
                .logGroups()
                .stream()
                .anyMatch(group -> groupName.equals(group.logGroupName()));
        if (present) {
            logs.deleteLogGroup(request -> request.logGroupName(groupName));
        }
    }
}
