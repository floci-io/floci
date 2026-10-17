package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.cloudwatchlogs.model.CloudWatchLogsException;
import software.amazon.awssdk.services.cloudwatchlogs.model.InvalidParameterException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudWatch Logs stream resource permissions")
class CloudWatchLogsStreamAuthorizationTest extends CloudWatchLogsAuthorizationFixture {

    CloudWatchLogsStreamAuthorizationTest() {
        super("logs-stream-permissions", "streams");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("exact stream grants do not allow another group's stream")
    void exactStreamPermission(boolean delete) {
        prepare(delete);
        String action = delete ? "DeleteLogStream" : "CreateLogStream";
        String arn = TestFixtures.arn("logs", "000000000000", "log-group:" + protectedGroup + ":log-stream:selected");
        policy("{\"Effect\":\"Allow\",\"Action\":\"logs:" + action + "\",\"Resource\":\"" + arn + "\"}");
        assertThatThrownBy(() -> invoke(delete, otherGroup))
                .isInstanceOfSatisfying(CloudWatchLogsException.class, this::assertDenied);
        assertPresent(otherGroup, delete);
        invoke(delete, protectedGroup);
        assertPresent(protectedGroup, !delete);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("a parent-group tag deny overrides wildcard stream permission")
    void parentGroupTagDeny(boolean delete) {
        prepare(delete);
        String action = delete ? "DeleteLogStream" : "CreateLogStream";
        policy("{\"Effect\":\"Allow\",\"Action\":\"logs:" + action + "\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Deny\",\"Action\":\"logs:" + action + "\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/protected\":\"true\"}}}");
        assertThatThrownBy(() -> invoke(delete, protectedGroup))
                .isInstanceOfSatisfying(CloudWatchLogsException.class, this::assertDenied);
        assertPresent(protectedGroup, delete);
        invoke(delete, otherGroup);
        assertPresent(otherGroup, !delete);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("stream name constraints reject aliases and preserve valid Unicode names")
    void nameConstraintsPreventAmbiguousTargets(boolean delete) {
        prepare(delete);
        String action = delete ? "DeleteLogStream" : "CreateLogStream";
        policy("{\"Effect\":\"Allow\",\"Action\":\"logs:" + action + "\",\"Resource\":\"*\"}");
        for (String invalid : new String[]{"", "bad::stream", "bad*stream", "s".repeat(513)}) {
            assertThatThrownBy(() -> invoke(delete, protectedGroup, invalid))
                    .isInstanceOfSatisfying(InvalidParameterException.class, error ->
                            assertThat(error.statusCode()).isEqualTo(400));
        }
        assertPresent(protectedGroup, delete);
        String maximum = "🚀".repeat(512);
        if (delete) {
            root.createLogStream(r -> r.logGroupName(protectedGroup).logStreamName(maximum));
        }
        invoke(delete, protectedGroup, maximum);
        assertThat(root.describeLogStreams(r -> r.logGroupName(protectedGroup)).logStreams().stream()
                .anyMatch(stream -> maximum.equals(stream.logStreamName()))).isEqualTo(!delete);
    }

    private void prepare(boolean delete) {
        if (delete) {
            root.createLogStream(r -> r.logGroupName(protectedGroup).logStreamName("selected"));
            root.createLogStream(r -> r.logGroupName(otherGroup).logStreamName("selected"));
        }
    }

    private void invoke(boolean delete, String group) {
        invoke(delete, group, "selected");
    }

    private void invoke(boolean delete, String group, String stream) {
        if (delete) {
            caller.deleteLogStream(r -> r.logGroupName(group).logStreamName(stream));
        } else {
            caller.createLogStream(r -> r.logGroupName(group).logStreamName(stream));
        }
    }

    private void assertPresent(String group, boolean expected) {
        assertThat(root.describeLogStreams(r -> r.logGroupName(group)).logStreams().stream()
                .anyMatch(stream -> "selected".equals(stream.logStreamName()))).isEqualTo(expected);
    }
}
