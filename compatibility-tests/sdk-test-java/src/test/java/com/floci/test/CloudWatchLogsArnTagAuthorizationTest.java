package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.CloudWatchLogsException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudWatch Logs ARN tag resource permissions")
class CloudWatchLogsArnTagAuthorizationTest extends CloudWatchLogsAuthorizationFixture {

    CloudWatchLogsArnTagAuthorizationTest() {
        super("logs-arn-tag-permissions", "arn-tags");
    }

    @ParameterizedTest
    @ValueSource(strings = {"TagResource", "UntagResource", "ListTagsForResource"})
    void exactBareArnGrant(String action) {
        policy(statement("Allow", action, arn(protectedGroup)));
        assertThatThrownBy(() -> invoke(action, otherGroup))
                .isInstanceOfSatisfying(CloudWatchLogsException.class, this::assertDenied);
        assertTags(otherGroup, Map.of("protected", "false"));
        Map<String, String> response = invoke(action, protectedGroup);
        assertResult(action, protectedGroup, "true", response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TagResource", "UntagResource", "ListTagsForResource"})
    void exactBareArnDenyOverridesWildcard(String action) {
        policy(statement("Allow", action, "*") + "," + statement("Deny", action, arn(protectedGroup)));
        assertThatThrownBy(() -> invoke(action, protectedGroup))
                .isInstanceOfSatisfying(CloudWatchLogsException.class, this::assertDenied);
        assertTags(protectedGroup, Map.of("protected", "true"));
        Map<String, String> response = invoke(action, otherGroup);
        assertResult(action, otherGroup, "false", response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TagResource", "UntagResource", "ListTagsForResource"})
    void rootCannotResolveNoncanonicalMarkersIntoLocalNames(String action) {
        String foreign = TestFixtures.arn("logs", "999999999999", "log-group:" + protectedGroup);
        for (String input : new String[]{"ARN:" + foreign.substring(4), " " + foreign,
                "x:log-group:" + protectedGroup}) {
            assertThatThrownBy(() -> invokeResource(root, action, input))
                    .isInstanceOfSatisfying(CloudWatchLogsException.class, error -> {
                        assertThat(error.statusCode()).isEqualTo(400);
                        assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ResourceNotFoundException");
                    });
            assertTags(protectedGroup, Map.of("protected", "true"));
        }
        Map<String, String> response = invokeResource(root, action, arn(protectedGroup));
        assertResult(action, protectedGroup, "true", response);
        assertTags(otherGroup, Map.of("protected", "false"));
    }

    private void assertResult(String action, String group, String original, Map<String, String> response) {
        Map<String, String> expected = switch (action) {
            case "TagResource" -> Map.of("protected", original, "team", "red");
            case "UntagResource" -> Map.of();
            case "ListTagsForResource" -> Map.of("protected", original);
            default -> throw new IllegalArgumentException(action);
        };
        if (action.equals("ListTagsForResource")) {
            assertThat(response).isEqualTo(expected);
        }
        assertTags(group, expected);
    }

    private Map<String, String> invoke(String action, String group) {
        return invokeResource(caller, action, arn(group));
    }

    private Map<String, String> invokeResource(CloudWatchLogsClient client, String action, String resource) {
        switch (action) {
            case "TagResource" -> client.tagResource(r -> r.resourceArn(resource).tags(Map.of("team", "red")));
            case "UntagResource" -> client.untagResource(r -> r.resourceArn(resource).tagKeys("protected"));
            case "ListTagsForResource" -> {
                return client.listTagsForResource(r -> r.resourceArn(resource)).tags();
            }
            default -> throw new IllegalArgumentException(action);
        }
        return Map.of();
    }

    private String arn(String group) {
        return TestFixtures.arn("logs", "000000000000", "log-group:" + group);
    }

    private void assertTags(String group, Map<String, String> expected) {
        assertThat(root.listTagsLogGroup(r -> r.logGroupName(group)).tags()).isEqualTo(expected);
    }

    private String statement(String effect, String action, String resource) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"logs:" + action
                + "\",\"Resource\":\"" + resource + "\"}";
    }
}
