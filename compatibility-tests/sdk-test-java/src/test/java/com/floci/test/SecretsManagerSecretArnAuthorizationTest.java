package com.floci.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("Secrets Manager secret resource permissions")
class SecretsManagerSecretArnAuthorizationTest {

    private IamClient iam;
    private SecretsManagerClient root;
    private SecretsManagerClient caller;
    private String user;
    private AccessKey key;
    private String prefix;
    private boolean policyCreated;
    private final List<String> secrets = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Uses emulator account-root credentials");
        iam = TestFixtures.iamClient("000000000000");
        root = client("000000000000", "test");
        String name = TestFixtures.uniqueName("secret-arn-permissions");
        iam.createUser(r -> r.userName(name));
        user = name;
        key = iam.createAccessKey(r -> r.userName(user)).accessKey();
        caller = client(key.accessKeyId(), key.secretAccessKey());
        boolean enforced = false;
        try {
            caller.listSecrets();
        } catch (SecretsManagerException error) {
            assertDenied(error);
            enforced = true;
        }
        Assumptions.assumeTrue(enforced, "Requires floci.services.iam.enforcement-enabled=true");
        prefix = name;
    }

    @AfterEach
    void cleanUp() {
        assertAll("remove Secrets Manager and IAM fixtures and close every client",
                () -> {
                    for (String secret : secrets) {
                        root.deleteSecret(r -> r.secretId(secret).forceDeleteWithoutRecovery(true));
                    }
                },
                () -> {
                    if (policyCreated) {
                        iam.deleteUserPolicy(r -> r.userName(user).policyName("secrets"));
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

    @Test
    @DisplayName("a name-prefix grant allows CreateSecret only under that prefix")
    void createSecretUnderNamePrefix() {
        policy("{\"Effect\":\"Allow\",\"Action\":\"secretsmanager:CreateSecret\",\"Resource\":\""
                + TestFixtures.arn("secretsmanager", "000000000000", "secret:" + prefix + "/*") + "\"}");

        String created = caller.createSecret(r -> r.name(prefix + "/core").secretString("x")).arn();
        secrets.add(created);

        assertThat(created).startsWith(TestFixtures.arn("secretsmanager", "000000000000",
                "secret:" + prefix + "/core-"));
        assertThatThrownBy(() -> caller.createSecret(r -> r.name("other-" + prefix).secretString("x")))
                .isInstanceOfSatisfying(SecretsManagerException.class, this::assertDenied);
    }

    @Test
    @DisplayName("a secret named by its name is authorized against its full ARN")
    void secretNamedByItsName() {
        String arn = root.createSecret(r -> r.name(prefix).secretString("x")).arn();
        secrets.add(arn);
        policy("{\"Effect\":\"Allow\",\"Action\":\"secretsmanager:GetSecretValue\",\"Resource\":\""
                + TestFixtures.arn("secretsmanager", "000000000000", "secret:" + prefix + "-??????") + "\"},"
                + "{\"Effect\":\"Allow\",\"Action\":\"secretsmanager:DescribeSecret\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Deny\",\"Action\":\"secretsmanager:DescribeSecret\",\"Resource\":\"" + arn + "\"}");

        assertThat(caller.getSecretValue(r -> r.secretId(prefix)).secretString()).isEqualTo("x");
        assertThatThrownBy(() -> caller.describeSecret(r -> r.secretId(prefix)))
                .isInstanceOfSatisfying(SecretsManagerException.class, this::assertDenied);
    }

    @Test
    @DisplayName("a secret named by a partial ARN is authorized against its full ARN")
    void secretNamedByAPartialArn() {
        String arn = root.createSecret(r -> r.name(prefix).secretString("x")).arn();
        secrets.add(arn);
        String partialArn = arn.substring(0, arn.length() - "-AbCdEf".length());
        policy("{\"Effect\":\"Allow\",\"Action\":\"secretsmanager:GetSecretValue\",\"Resource\":\""
                + TestFixtures.arn("secretsmanager", "000000000000", "secret:" + prefix + "-??????") + "\"},"
                + "{\"Effect\":\"Allow\",\"Action\":\"secretsmanager:DescribeSecret\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Deny\",\"Action\":\"secretsmanager:DescribeSecret\",\"Resource\":\"" + arn + "\"}");

        assertThat(caller.getSecretValue(r -> r.secretId(partialArn)).secretString()).isEqualTo("x");
        assertThatThrownBy(() -> caller.describeSecret(r -> r.secretId(partialArn)))
                .isInstanceOfSatisfying(SecretsManagerException.class, this::assertDenied);
    }

    private void policy(String statements) {
        iam.putUserPolicy(r -> r.userName(user).policyName("secrets")
                .policyDocument("{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}"));
        policyCreated = true;
    }

    private void assertDenied(SecretsManagerException error) {
        assertThat(error.statusCode()).isEqualTo(400);
        assertThat(error.awsErrorDetails().errorCode()).contains("AccessDenied");
    }

    private static SecretsManagerClient client(String accessKey, String secret) {
        return SecretsManagerClient.builder().endpointOverride(TestFixtures.endpoint()).region(TestFixtures.region())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secret)))
                .build();
    }
}
