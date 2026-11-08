package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.StsException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("STS session and federation tokens require a long-term caller")
class StsSessionTokenCallerTest {
    @ParameterizedTest
    @ValueSource(strings = {"session", "federation", "role"})
    void rejectsTemporaryCallersWhileTheirIdentityRemainsUsable(String kind) {
        List<Runnable> cleanup = new ArrayList<>();
        try (IamClient iam = TestFixtures.iamClient()) {
            try {
                String name = TestFixtures.uniqueName("token-caller");
                iam.createUser(r -> r.userName(name));
                cleanup.add(() -> iam.deleteUser(r -> r.userName(name)));
                AccessKey key = iam.createAccessKey(r -> r.userName(name)).accessKey();
                cleanup.add(() -> iam.deleteAccessKey(r -> r.userName(name).accessKeyId(key.accessKeyId())));
                String policy = "{\"Version\":\"2012-10-17\",\"Statement\":["
                        + "{\"Effect\":\"Allow\",\"Action\":\"sts:*\",\"Resource\":\"*\"}]}";
                iam.putUserPolicy(r -> r.userName(name).policyName("sts").policyDocument(policy));
                cleanup.add(() -> iam.deleteUserPolicy(r -> r.userName(name).policyName("sts")));
                try (StsClient caller = client(AwsBasicCredentials.create(key.accessKeyId(), key.secretAccessKey()))) {
                    Credentials issued;
                    if ("role".equals(kind)) {
                        String arn = iam.createRole(r -> r.roleName(name).assumeRolePolicyDocument(
                                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                                + "\"Principal\":{\"AWS\":\"*\"},\"Action\":\"sts:AssumeRole\"}]}"))
                                .role().arn();
                        cleanup.add(() -> iam.deleteRole(r -> r.roleName(name)));
                        iam.putRolePolicy(r -> r.roleName(name).policyName("sts").policyDocument(policy));
                        cleanup.add(() -> iam.deleteRolePolicy(r -> r.roleName(name).policyName("sts")));
                        issued = caller.assumeRole(r -> r.roleArn(arn).roleSessionName("caller")).credentials();
                    } else if ("federation".equals(kind)) {
                        issued = caller.getFederationToken(r -> r.name(name).policy(policy)).credentials();
                    } else {
                        issued = caller.getSessionToken(r -> r.durationSeconds(900)).credentials();
                    }
                    assertThat(issued.accessKeyId()).startsWith("ASIA");
                    try (StsClient temporary = client(AwsSessionCredentials.create(
                            issued.accessKeyId(), issued.secretAccessKey(), issued.sessionToken()))) {
                        String identity = temporary.getCallerIdentity().arn();
                        assertThat(identity).isNotBlank();
                        assertThatThrownBy(() -> temporary.getSessionToken(r -> r.durationSeconds(900)))
                                .isInstanceOfSatisfying(StsException.class, error -> {
                                    assertThat(error.statusCode()).isEqualTo(403);
                                    assertThat(error.awsErrorDetails().errorCode()).isEqualTo("AccessDenied");
                                });
                        assertThatThrownBy(() -> temporary.getFederationToken(r -> r.name("another-caller").policy(policy)))
                                .isInstanceOfSatisfying(StsException.class, error -> {
                                    assertThat(error.statusCode()).isEqualTo(403);
                                    assertThat(error.awsErrorDetails().errorCode()).isEqualTo("AccessDenied");
                                    assertThat(error.awsErrorDetails().errorMessage())
                                            .isEqualTo("Cannot call GetFederationToken with session credentials");
                                });
                        assertThat(temporary.getCallerIdentity().arn()).isEqualTo(identity);
                    }
                }
            } finally {
                RuntimeException failure = null;
                for (int i = cleanup.size() - 1; i >= 0; i--) {
                    try {
                        cleanup.get(i).run();
                    } catch (RuntimeException problem) {
                        if (failure == null) {
                            failure = new IllegalStateException("STS fixture cleanup failed");
                        }
                        failure.addSuppressed(problem);
                    }
                }
                if (failure != null) {
                    throw failure;
                }
            }
        }
    }

    private static StsClient client(AwsCredentials credentials) {
        return StsClient.builder().endpointOverride(TestFixtures.endpoint()).region(TestFixtures.region())
                .credentialsProvider(StaticCredentialsProvider.create(credentials)).build();
    }
}
