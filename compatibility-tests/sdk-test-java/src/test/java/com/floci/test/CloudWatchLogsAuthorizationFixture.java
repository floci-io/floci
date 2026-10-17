package com.floci.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.CloudWatchLogsException;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/** Shared emulator-only IAM lifecycle for Logs authorization SDK tests. */
abstract class CloudWatchLogsAuthorizationFixture {

    private IamClient iam;
    CloudWatchLogsClient root;
    CloudWatchLogsClient caller;
    private String user;
    private AccessKey key;
    String protectedGroup;
    String otherGroup;
    private boolean policyCreated;

    private final String prefix;
    private final String policyName;
    private final List<Executable> groups = new ArrayList<>();

    CloudWatchLogsAuthorizationFixture(String prefix, String policyName) {
        this.prefix = prefix;
        this.policyName = policyName;
    }

    @BeforeEach
    void setUp() {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Uses emulator account-root credentials");
        iam = TestFixtures.iamClient("000000000000");
        root = client("000000000000", "test");
        String name = TestFixtures.uniqueName(prefix);
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
        protectedGroup = createGroup(name + "-protected", Map.of("protected", "true"));
        otherGroup = createGroup(name + "-other", Map.of("protected", "false"));
    }

    @AfterEach
    void cleanUp() {
        List<Executable> tasks = new ArrayList<>(groups);
        tasks.addAll(List.of(
                () -> {
                    if (policyCreated) {
                        iam.deleteUserPolicy(r -> r.userName(user).policyName(policyName));
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
                }));
        assertAll("remove Logs and IAM fixtures and close every client", tasks.stream());
    }

    final String createGroup(String name, Map<String, String> tags) {
        root.createLogGroup(r -> r.logGroupName(name).tags(tags));
        groups.add(() -> deleteGroup(name));
        return name;
    }

    void deleteGroup(String name) {
        root.deleteLogGroup(r -> r.logGroupName(name));
    }

    final void policy(String statements) {
        iam.putUserPolicy(r -> r.userName(user).policyName(policyName)
                .policyDocument("{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}"));
        policyCreated = true;
    }

    final void assertDenied(CloudWatchLogsException error) {
        assertThat(error.statusCode()).isEqualTo(400);
        assertThat(error.awsErrorDetails().errorCode()).contains("AccessDenied");
    }

    private static CloudWatchLogsClient client(String accessKey, String secret) {
        return CloudWatchLogsClient.builder().endpointOverride(TestFixtures.endpoint()).region(TestFixtures.region())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secret)))
                .build();
    }
}
