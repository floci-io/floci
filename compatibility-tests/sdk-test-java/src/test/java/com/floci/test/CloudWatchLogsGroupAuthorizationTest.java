package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.cloudwatchlogs.model.CloudWatchLogsException;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogGroup;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudWatch Logs group resource permissions")
class CloudWatchLogsGroupAuthorizationTest extends CloudWatchLogsAuthorizationFixture {

    CloudWatchLogsGroupAuthorizationTest() {
        super("logs-group-permissions", "groups");
    }

    @Override
    void deleteGroup(String name) {
        if (groupExists(name)) {
            super.deleteGroup(name);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateLogGroup", "DeleteLogGroup", "PutRetentionPolicy", "DeleteRetentionPolicy"})
    void exactGroupGrant(String action) {
        prepare(action);
        policy(statement("Allow", action, groupArn(protectedGroup)));
        assertThatThrownBy(() -> invoke(action, otherGroup))
                .isInstanceOfSatisfying(CloudWatchLogsException.class, this::assertDenied);
        assertState(action, otherGroup, false);
        invoke(action, protectedGroup);
        assertState(action, protectedGroup, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateLogGroup", "DeleteLogGroup", "PutRetentionPolicy", "DeleteRetentionPolicy"})
    void bareGroupGrant(String action) {
        prepare(action);
        policy(statement("Allow", action, TestFixtures.arn("logs", "000000000000", "log-group:" + protectedGroup)));
        assertThatThrownBy(() -> invoke(action, otherGroup))
                .isInstanceOfSatisfying(CloudWatchLogsException.class, this::assertDenied);
        assertState(action, otherGroup, false);
        invoke(action, protectedGroup);
        assertState(action, protectedGroup, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateLogGroup", "DeleteLogGroup", "PutRetentionPolicy", "DeleteRetentionPolicy"})
    void exactGroupDenyOverridesWildcard(String action) {
        prepare(action);
        policy(statement("Allow", action, "*") + "," + statement("Deny", action, groupArn(protectedGroup)));
        assertThatThrownBy(() -> invoke(action, protectedGroup))
                .isInstanceOfSatisfying(CloudWatchLogsException.class, this::assertDenied);
        assertState(action, protectedGroup, false);
        invoke(action, otherGroup);
        assertState(action, otherGroup, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DeleteLogGroup", "PutRetentionPolicy", "DeleteRetentionPolicy"})
    void groupTagDeny(String action) {
        prepare(action);
        policy(statement("Allow", action, "*") + ","
                + "{\"Effect\":\"Deny\",\"Action\":\"logs:" + action + "\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/protected\":\"true\"}}}");
        assertThatThrownBy(() -> invoke(action, protectedGroup))
                .isInstanceOfSatisfying(CloudWatchLogsException.class, this::assertDenied);
        assertState(action, protectedGroup, false);
        invoke(action, otherGroup);
        assertState(action, otherGroup, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TagResource", "TagLogGroup"})
    void taggedCreateRequiresEitherPermission(String tagAction) {
        prepare("CreateLogGroup");
        String primary = statement("Allow", "CreateLogGroup", groupArn(protectedGroup));
        policy(primary);
        assertThatThrownBy(() -> caller.createLogGroup(r -> r.logGroupName(protectedGroup)
                .tags(Map.of("team", "blue"))))
                .isInstanceOfSatisfying(CloudWatchLogsException.class, this::assertDenied);
        assertState("CreateLogGroup", protectedGroup, false);
        String resource = tagAction.equals("TagResource")
                ? TestFixtures.arn("logs", "000000000000", "log-group:" + protectedGroup)
                : groupArn(protectedGroup);
        String unused = tagAction.equals("TagResource") ? "TagLogGroup" : "TagResource";
        policy(primary + "," + statement("Deny", unused, "*") + ","
                + "{\"Effect\":\"Allow\",\"Action\":\"logs:" + tagAction + "\",\"Resource\":\"" + resource
                + "\",\"Condition\":{\"StringEquals\":{\"aws:RequestTag/team\":\"blue\"},"
                + "\"ForAllValues:StringEquals\":{\"aws:TagKeys\":[\"team\"]}}}");
        assertThatThrownBy(() -> caller.createLogGroup(r -> r.logGroupName(protectedGroup)
                .tags(Map.of("team", "red"))))
                .isInstanceOfSatisfying(CloudWatchLogsException.class, this::assertDenied);
        assertState("CreateLogGroup", protectedGroup, false);
        caller.createLogGroup(r -> r.logGroupName(protectedGroup).tags(Map.of("team", "blue")));
        assertState("CreateLogGroup", protectedGroup, true);
        assertThat(root.listTagsLogGroup(r -> r.logGroupName(protectedGroup)).tags()).containsEntry("team", "blue");
    }

    private void prepare(String action) {
        if (action.equals("CreateLogGroup")) {
            root.deleteLogGroup(r -> r.logGroupName(protectedGroup));
            root.deleteLogGroup(r -> r.logGroupName(otherGroup));
        } else if (action.equals("PutRetentionPolicy") || action.equals("DeleteRetentionPolicy")) {
            root.putRetentionPolicy(r -> r.logGroupName(protectedGroup).retentionInDays(7));
            root.putRetentionPolicy(r -> r.logGroupName(otherGroup).retentionInDays(7));
        }
    }

    private String statement(String effect, String action, String resource) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"logs:" + action
                + "\",\"Resource\":\"" + resource + "\"}";
    }

    private String groupArn(String group) {
        return TestFixtures.arn("logs", "000000000000", "log-group:" + group + ":*");
    }

    private void invoke(String action, String group) {
        switch (action) {
            case "CreateLogGroup" -> caller.createLogGroup(r -> r.logGroupName(group));
            case "DeleteLogGroup" -> caller.deleteLogGroup(r -> r.logGroupName(group));
            case "PutRetentionPolicy" -> caller.putRetentionPolicy(r -> r.logGroupName(group).retentionInDays(14));
            case "DeleteRetentionPolicy" -> caller.deleteRetentionPolicy(r -> r.logGroupName(group));
            default -> throw new IllegalArgumentException(action);
        }
    }

    private void assertState(String action, String group, boolean changed) {
        LogGroup found = root.describeLogGroupsPaginator(r -> r.logGroupNamePrefix(group)).logGroups().stream()
                .filter(g -> group.equals(g.logGroupName())).findFirst().orElse(null);
        boolean exists = action.equals("CreateLogGroup") ? changed : !action.equals("DeleteLogGroup") || !changed;
        if (!exists) {
            assertThat(found).isNull();
            return;
        }
        assertThat(found).isNotNull();
        if (action.equals("PutRetentionPolicy")) {
            assertThat(found.retentionInDays()).isEqualTo(changed ? 14 : 7);
        } else if (action.equals("DeleteRetentionPolicy")) {
            assertThat(found.retentionInDays()).isEqualTo(changed ? null : 7);
        }
    }

    private boolean groupExists(String group) {
        return root.describeLogGroupsPaginator(r -> r.logGroupNamePrefix(group)).logGroups().stream()
                .anyMatch(g -> group.equals(g.logGroupName()));
    }
}
