package com.floci.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.CloudWatchLogsException;
import software.amazon.awssdk.services.cloudwatchlogs.model.InvalidParameterException;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("CloudWatch Logs stream resource permissions")
class CloudWatchLogsStreamAuthorizationTest {

    private IamClient iam;
    private CloudWatchLogsClient root;
    private CloudWatchLogsClient caller;
    private String user;
    private AccessKey key;
    private String protectedGroup;
    private String otherGroup;
    private boolean policyCreated;

    @BeforeEach
    void setUp() {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Uses emulator account-root credentials");
        iam = TestFixtures.iamClient("000000000000");
        root = client("000000000000", "test");
        String name = TestFixtures.uniqueName("logs-stream-permissions");
        iam.createUser(r -> r.userName(name));
        user = name;
        key = iam.createAccessKey(r -> r.userName(user)).accessKey();
        caller = client(key.accessKeyId(), key.secretAccessKey());
        boolean enforced = false;
        try {
            caller.describeLogGroups();
        } catch (CloudWatchLogsException error) {
            assertDenied(error);
            enforced = true;
        }
        Assumptions.assumeTrue(enforced, "Requires floci.services.iam.enforcement-enabled=true");
        String group = name + "-protected";
        root.createLogGroup(r -> r.logGroupName(group).tags(Map.of("protected", "true")));
        protectedGroup = group;
        String other = name + "-other";
        root.createLogGroup(r -> r.logGroupName(other).tags(Map.of("protected", "false")));
        otherGroup = other;
    }

    @AfterEach
    void cleanUp() {
        assertAll("remove Logs and IAM fixtures and close every client",
                () -> {
                    if (protectedGroup != null) {
                        root.deleteLogGroup(r -> r.logGroupName(protectedGroup));
                    }
                },
                () -> {
                    if (otherGroup != null) {
                        root.deleteLogGroup(r -> r.logGroupName(otherGroup));
                    }
                },
                () -> {
                    if (policyCreated) {
                        iam.deleteUserPolicy(r -> r.userName(user).policyName("streams"));
                    }
                },
                () -> {
                    if (key != null) {
                        iam.deleteAccessKey(r -> r.userName(user).accessKeyId(key.accessKeyId()));
                    }
                },
                () -> {
                    if (user != null) {
                        iam.deleteUser(r -> r.userName(user));
                    }
                },
                () -> {
                    if (caller != null) {
                        caller.close();
                    }
                },
                () -> {
                    if (root != null) {
                        root.close();
                    }
                },
                () -> {
                    if (iam != null) {
                        iam.close();
                    }
                });
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

    private void policy(String statements) {
        iam.putUserPolicy(r -> r.userName(user).policyName("streams")
                .policyDocument("{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}"));
        policyCreated = true;
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

    private void assertDenied(CloudWatchLogsException error) {
        assertThat(error.statusCode()).isEqualTo(400);
        assertThat(error.awsErrorDetails().errorCode()).contains("AccessDenied");
    }

    private static CloudWatchLogsClient client(String accessKey, String secret) {
        return CloudWatchLogsClient.builder().endpointOverride(TestFixtures.endpoint()).region(TestFixtures.region())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secret)))
                .build();
    }
}
