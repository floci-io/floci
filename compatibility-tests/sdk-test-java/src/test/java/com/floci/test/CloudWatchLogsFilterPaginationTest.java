package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CloudWatch Logs FilterLogEvents pagination")
class CloudWatchLogsFilterPaginationTest {

    @Test
    @DisplayName("the SDK paginator walks every match once and terminates")
    void paginatorWalksEveryMatchOnceAndTerminates() {
        String groupName = "/test/" + TestFixtures.uniqueName("logs-filter-pagination");
        String streamName = "stream-1";

        try (CloudWatchLogsClient logs = TestFixtures.cloudWatchLogsClient()) {
            try {
                logs.createLogGroup(request -> request.logGroupName(groupName));
                logs.createLogStream(request -> request
                        .logGroupName(groupName)
                        .logStreamName(streamName));

                long base = System.currentTimeMillis();
                List<InputLogEvent> events = new ArrayList<>();
                for (int i = 0; i < 5; i++) {
                    int index = i;
                    events.add(InputLogEvent.builder()
                            .timestamp(base + index)
                            .message("msg-" + index)
                            .build());
                }
                logs.putLogEvents(request -> request
                        .logGroupName(groupName)
                        .logStreamName(streamName)
                        .logEvents(events));

                // The paginator is the point of the test. This SDK's FilterLogEventsIterable
                // keeps requesting while nextToken is non-empty and, unlike GetLogEventsIterable,
                // has no guard against being handed the same token twice. A response that
                // advertises a next page on the final page therefore never terminates here.
                List<String> messages = new ArrayList<>();
                List<FilterLogEventsResponse> pages = new ArrayList<>();
                logs.filterLogEventsPaginator(request -> request
                                .logGroupName(groupName)
                                .limit(2))
                        .stream()
                        .forEach(page -> {
                            pages.add(page);
                            page.events().stream().map(FilteredLogEvent::message).forEach(messages::add);
                        });

                assertThat(messages)
                        .as("every match is seen exactly once, oldest first")
                        .containsExactly("msg-0", "msg-1", "msg-2", "msg-3", "msg-4");
                assertThat(pages).hasSize(3);
                assertThat(pages.get(pages.size() - 1).nextToken())
                        .as("an absent token is how FilterLogEvents signals the end")
                        .isNull();
            } finally {
                deleteIfPresent(logs, groupName);
            }
        }
    }

    @Test
    @DisplayName("a token from a previous page resumes rather than restarting")
    void tokenResumesRatherThanRestarting() {
        String groupName = "/test/" + TestFixtures.uniqueName("logs-filter-pagination-resume");
        String streamName = "stream-1";

        try (CloudWatchLogsClient logs = TestFixtures.cloudWatchLogsClient()) {
            try {
                logs.createLogGroup(request -> request.logGroupName(groupName));
                logs.createLogStream(request -> request
                        .logGroupName(groupName)
                        .logStreamName(streamName));

                long base = System.currentTimeMillis();
                logs.putLogEvents(request -> request
                        .logGroupName(groupName)
                        .logStreamName(streamName)
                        .logEvents(
                                InputLogEvent.builder().timestamp(base).message("first").build(),
                                InputLogEvent.builder().timestamp(base + 1).message("second").build(),
                                InputLogEvent.builder().timestamp(base + 2).message("third").build()));

                FilterLogEventsResponse firstPage = logs.filterLogEvents(request -> request
                        .logGroupName(groupName)
                        .limit(2));
                assertThat(firstPage.events()).extracting(FilteredLogEvent::message)
                        .containsExactly("first", "second");
                assertThat(firstPage.nextToken()).isNotNull();

                FilterLogEventsResponse secondPage = logs.filterLogEvents(request -> request
                        .logGroupName(groupName)
                        .limit(2)
                        .nextToken(firstPage.nextToken()));
                assertThat(secondPage.events()).extracting(FilteredLogEvent::message)
                        .containsExactly("third");
                assertThat(secondPage.nextToken()).isNull();
            } finally {
                deleteIfPresent(logs, groupName);
            }
        }
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
