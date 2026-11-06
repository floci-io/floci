package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.InvalidParameterException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FilterLogEvents reads filterPattern with the filter pattern syntax. The events and the matches are
 * the ones measured on AWS (eu-west-1, 2026-10-16) on one stream holding these five events.
 */
@DisplayName("CloudWatch Logs FilterLogEvents filter patterns")
class CloudWatchLogsFilterPatternTest {

    private static final List<String> EVENTS = List.of(
            "{\"time\":\"t\",\"level\":\"INFO\",\"msg\":\"mail not sent\",\"template\":\"sign-in-code\","
                    + "\"substitutions\":{\"code\":\"694692\"}}",
            "{\"level\":\"ERROR\",\"msg\":\"boom\",\"template\":\"other\"}",
            "ERROR something failed in sign-in-code path",
            "WARN slow response",
            "INFO all good");

    @Test
    @DisplayName("each pattern matches the events AWS matches")
    void eachPatternMatchesTheEventsAwsMatches() {
        String groupName = "/test/" + TestFixtures.uniqueName("logs-filter-pattern");
        String streamName = "stream-1";

        try (CloudWatchLogsClient logs = TestFixtures.cloudWatchLogsClient()) {
            logs.createLogGroup(request -> request.logGroupName(groupName));
            try {
                logs.createLogStream(request -> request.logGroupName(groupName).logStreamName(streamName));
                long base = System.currentTimeMillis();
                List<InputLogEvent> events = new ArrayList<>();
                for (int i = 0; i < EVENTS.size(); i++) {
                    events.add(InputLogEvent.builder().timestamp(base + i).message(EVENTS.get(i)).build());
                }
                logs.putLogEvents(request -> request
                        .logGroupName(groupName)
                        .logStreamName(streamName)
                        .logEvents(events));

                assertThat(matches(logs, groupName, "{ $.template = \"sign-in-code\" }")).containsExactly(1);
                assertThat(matches(logs, groupName, "{ $.level = \"INFO\" }")).containsExactly(1);
                assertThat(matches(logs, groupName, "{ $.substitutions.code = 694692 }")).containsExactly(1);
                assertThat(matches(logs, groupName, "{ $.substitutions.code = \"694692\" }")).containsExactly(1);
                assertThat(matches(logs, groupName, "sign-in-code")).isEmpty();
                assertThat(matches(logs, groupName, "\"sign-in-code\"")).containsExactly(1, 3);
                assertThat(matches(logs, groupName, "ERROR")).containsExactly(2, 3);
                assertThat(matches(logs, groupName, "error")).isEmpty();
                assertThat(matches(logs, groupName, "?ERROR ?WARN")).containsExactly(2, 3, 4);
                assertThat(matches(logs, groupName, "ERROR -sign")).containsExactly(2);
                assertThat(matches(logs, groupName, "\"mail not sent\"")).containsExactly(1);
                assertThat(matches(logs, groupName, "mail")).containsExactly(1);
                assertThat(matches(logs, groupName, "{ $.missing = \"x\" }")).isEmpty();
            } finally {
                logs.deleteLogGroup(request -> request.logGroupName(groupName));
            }
        }
    }

    @Test
    @DisplayName("an invalid pattern is refused with InvalidParameterException")
    void anInvalidPatternIsRefused() {
        String groupName = "/test/" + TestFixtures.uniqueName("logs-filter-pattern-invalid");

        try (CloudWatchLogsClient logs = TestFixtures.cloudWatchLogsClient()) {
            logs.createLogGroup(request -> request.logGroupName(groupName));
            try {
                assertThatThrownBy(() -> matches(logs, groupName, "{ $.x = }"))
                        .isInstanceOf(InvalidParameterException.class);
                assertThatThrownBy(() -> matches(logs, groupName, "[a, b"))
                        .isInstanceOf(InvalidParameterException.class);
            } finally {
                logs.deleteLogGroup(request -> request.logGroupName(groupName));
            }
        }
    }

    @Test
    @DisplayName("a request naming no log group is refused with InvalidParameterException")
    void aRequestNamingNoLogGroupIsRefused() {
        // Not measured on AWS: the API reference requires logGroupName or logGroupIdentifier and models
        // InvalidParameterException for a parameter specified incorrectly.
        try (CloudWatchLogsClient logs = TestFixtures.cloudWatchLogsClient()) {
            assertThatThrownBy(() -> logs.filterLogEvents(request -> request.filterPattern("ERROR")))
                    .isInstanceOf(InvalidParameterException.class);
        }
    }

    /** The 1-based positions in {@link #EVENTS} of the events the pattern matches, oldest first. */
    private static List<Integer> matches(CloudWatchLogsClient logs, String groupName, String pattern) {
        List<Integer> positions = new ArrayList<>();
        logs.filterLogEventsPaginator(request -> request.logGroupName(groupName).filterPattern(pattern))
                .events()
                .stream()
                .map(FilteredLogEvent::message)
                .forEach(message -> positions.add(EVENTS.indexOf(message) + 1));
        return positions;
    }
}
