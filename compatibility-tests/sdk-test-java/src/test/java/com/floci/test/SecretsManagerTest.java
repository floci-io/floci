package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.DeleteFunctionRequest;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.Runtime;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.BatchGetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.BatchGetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretResponse;
import software.amazon.awssdk.services.secretsmanager.model.DeleteSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.DescribeSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.DescribeSecretResponse;
import software.amazon.awssdk.services.secretsmanager.model.GetRandomPasswordRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetRandomPasswordResponse;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ListSecretVersionIdsRequest;
import software.amazon.awssdk.services.secretsmanager.model.ListSecretVersionIdsResponse;
import software.amazon.awssdk.services.secretsmanager.model.ListSecretsRequest;
import software.amazon.awssdk.services.secretsmanager.model.ListSecretsResponse;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.RotateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.RotateSecretResponse;
import software.amazon.awssdk.services.secretsmanager.model.RotationRulesType;
import software.amazon.awssdk.services.secretsmanager.model.SecretVersionsListEntry;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;
import software.amazon.awssdk.services.secretsmanager.model.TagResourceRequest;
import software.amazon.awssdk.services.secretsmanager.model.UntagResourceRequest;
import software.amazon.awssdk.services.secretsmanager.model.UpdateSecretRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Secrets Manager")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecretsManagerTest {

    private static SecretsManagerClient sm;
    private static String secretName;
    private static String secretArn;
    private static String originalVersionId;
    private static final String SECRET_VALUE = "my-super-secret-value";
    private static final String UPDATED_VALUE = "my-updated-secret-value";

    @BeforeAll
    static void setup() {
        sm = TestFixtures.secretsManagerClient();
        secretName = "sdk-test-secret-" + System.currentTimeMillis();
    }

    @AfterAll
    static void cleanup() {
        if (sm != null) {
            try {
                sm.deleteSecret(DeleteSecretRequest.builder()
                        .secretId(secretName)
                        .forceDeleteWithoutRecovery(true)
                        .build());
            } catch (Exception ignored) {}
            sm.close();
        }
    }

    @Test
    @Order(1)
    void createSecret() {
        CreateSecretResponse response = sm.createSecret(CreateSecretRequest.builder()
                .name(secretName)
                .secretString(SECRET_VALUE)
                .description("Test secret")
                .tags(software.amazon.awssdk.services.secretsmanager.model.Tag.builder().key("env").value("test").build())
                .build());

        secretArn = response.arn();
        originalVersionId = response.versionId();

        assertThat(response.arn()).isNotNull().contains(secretName);
        assertThat(response.versionId()).isNotNull();
        assertThat(response.name()).isEqualTo(secretName);
    }

    @Test
    @Order(2)
    void getSecretValueByName() {
        GetSecretValueResponse response = sm.getSecretValue(GetSecretValueRequest.builder()
                .secretId(secretName)
                .build());

        assertThat(response.secretString()).isEqualTo(SECRET_VALUE);
        assertThat(response.name()).isEqualTo(secretName);
    }

    @Test
    @Order(3)
    void getSecretValueByArn() {
        Assumptions.assumeTrue(secretArn != null, "CreateSecret must succeed first");

        GetSecretValueResponse response = sm.getSecretValue(GetSecretValueRequest.builder()
                .secretId(secretArn)
                .build());

        assertThat(response.secretString()).isEqualTo(SECRET_VALUE);
    }

    @Test
    @Order(4)
    void putSecretValue() {
        PutSecretValueResponse response = sm.putSecretValue(PutSecretValueRequest.builder()
                .secretId(secretName)
                .secretString(UPDATED_VALUE)
                .build());

        assertThat(response.versionId()).isNotNull().isNotEqualTo(originalVersionId);
    }

    @Test
    @Order(5)
    void getSecretValueAfterPut() {
        GetSecretValueResponse response = sm.getSecretValue(GetSecretValueRequest.builder()
                .secretId(secretName)
                .build());

        assertThat(response.secretString()).isEqualTo(UPDATED_VALUE);
    }

    @Test
    @Order(6)
    void describeSecret() {
        DescribeSecretResponse response = sm.describeSecret(DescribeSecretRequest.builder()
                .secretId(secretName)
                .build());

        assertThat(response.tags()).isNotEmpty();
        assertThat(response.versionIdsToStages()).hasSize(2);
        assertThat(response.rotationEnabled()).isFalse();
    }

    @Test
    @Order(7)
    void updateSecretDescription() {
        sm.updateSecret(UpdateSecretRequest.builder()
                .secretId(secretName)
                .description("Updated description")
                .build());

        DescribeSecretResponse response = sm.describeSecret(DescribeSecretRequest.builder()
                .secretId(secretName)
                .build());

        assertThat(response.description()).isEqualTo("Updated description");
    }

    @Test
    @Order(8)
    void listSecrets() {
        ListSecretsResponse response = sm.listSecrets(ListSecretsRequest.builder().build());

        assertThat(response.secretList())
                .anyMatch(s -> secretName.equals(s.name()));
    }

    @Test
    @Order(9)
    void tagResource() {
        sm.tagResource(TagResourceRequest.builder()
                .secretId(secretName)
                .tags(software.amazon.awssdk.services.secretsmanager.model.Tag.builder().key("team").value("backend").build())
                .build());

        DescribeSecretResponse response = sm.describeSecret(DescribeSecretRequest.builder()
                .secretId(secretName)
                .build());

        assertThat(response.tags())
                .anyMatch(t -> "team".equals(t.key()) && "backend".equals(t.value()));
    }

    @Test
    @Order(10)
    void untagResource() {
        sm.untagResource(UntagResourceRequest.builder()
                .secretId(secretName)
                .tagKeys("team")
                .build());

        DescribeSecretResponse response = sm.describeSecret(DescribeSecretRequest.builder()
                .secretId(secretName)
                .build());

        assertThat(response.tags())
                .noneMatch(t -> "team".equals(t.key()));
    }

    @Test
    @Order(11)
    void listSecretVersionIds() {
        ListSecretVersionIdsResponse response = sm.listSecretVersionIds(
                ListSecretVersionIdsRequest.builder()
                        .secretId(secretName)
                        .build());

        Map<String, List<String>> versionMap = response.versions().stream()
                .collect(Collectors.toMap(
                        SecretVersionsListEntry::versionId,
                        SecretVersionsListEntry::versionStages));

        assertThat(versionMap).hasSize(2);
        assertThat(versionMap.values().stream().flatMap(List::stream).toList())
                .contains("AWSCURRENT", "AWSPREVIOUS");
    }

    @Test
    @Order(12)
    void rotateSecretStub() throws Exception {
        String lambdaName = "RotationLambda-" + System.currentTimeMillis();
        try (LambdaClient lambda = TestFixtures.lambdaClient()) {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
                zos.putNextEntry(new java.util.zip.ZipEntry("index.js"));
                zos.write("exports.handler = async (event) => { return 'ok'; };".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zos.closeEntry();
            }

            lambda.createFunction(CreateFunctionRequest.builder()
                    .functionName(lambdaName)
                    .runtime(Runtime.fromValue("nodejs20.x"))
                    .role("arn:aws:iam::000000000000:role/dummy-role")
                    .handler("index.handler")
                    .code(FunctionCode.builder().zipFile(SdkBytes.fromByteArray(baos.toByteArray())).build())
                    .build());

            RotateSecretResponse rotateResponse = sm.rotateSecret(RotateSecretRequest.builder()
                    .secretId(secretName)
                    .rotationLambdaARN("arn:aws:lambda:us-east-1:000000000000:function:" + lambdaName)
                    .rotationRules(RotationRulesType.builder().automaticallyAfterDays(30L).build())
                    .build());

            assertThat(rotateResponse.arn()).isEqualTo(secretArn);

            DescribeSecretResponse describeResponse = sm.describeSecret(DescribeSecretRequest.builder()
                    .secretId(secretName)
                    .build());

            assertThat(describeResponse.rotationEnabled()).isTrue();

            lambda.deleteFunction(DeleteFunctionRequest.builder()
                    .functionName(lambdaName)
                    .build());
        }
    }

    @Test
    @Order(13)
    void kmsKeyIdPreservation() {
        String kmsKeyId = "arn:aws:kms:us-east-1:000000000000:key/my-key";
        String kmsSecretName = "sdk-test-kms-secret-" + System.currentTimeMillis();

        try {
            sm.createSecret(CreateSecretRequest.builder()
                    .name(kmsSecretName)
                    .secretString("kms-value")
                    .kmsKeyId(kmsKeyId)
                    .build());

            DescribeSecretResponse response = sm.describeSecret(DescribeSecretRequest.builder()
                    .secretId(kmsSecretName)
                    .build());

            assertThat(response.kmsKeyId()).isEqualTo(kmsKeyId);
        } finally {
            try {
                sm.deleteSecret(DeleteSecretRequest.builder()
                        .secretId(kmsSecretName)
                        .forceDeleteWithoutRecovery(true)
                        .build());
            } catch (Exception ignored) {}
        }
    }

    @Test
    @Order(14)
    void createSecretDuplicateThrows400() {
        String dupName = "sdk-test-dup-secret-" + System.currentTimeMillis();

        try {
            sm.createSecret(CreateSecretRequest.builder()
                    .name(dupName)
                    .secretString("value1")
                    .build());

            assertThatThrownBy(() -> sm.createSecret(CreateSecretRequest.builder()
                    .name(dupName)
                    .secretString("value2")
                    .build()))
                    .isInstanceOf(SecretsManagerException.class)
                    .extracting(e -> ((SecretsManagerException) e).statusCode())
                    .isEqualTo(400);
        } finally {
            try {
                sm.deleteSecret(DeleteSecretRequest.builder()
                        .secretId(dupName)
                        .forceDeleteWithoutRecovery(true)
                        .build());
            } catch (Exception ignored) {}
        }
    }

    @Test
    @Order(15)
    void getRandomPassword() {
        GetRandomPasswordResponse response = sm.getRandomPassword(GetRandomPasswordRequest.builder()
                .passwordLength(32L)
                .excludePunctuation(true)
                .build());

        assertThat(response.randomPassword()).isNotNull().hasSize(32);
    }

    @Test
    @Order(16)
    void getSecretValueNonExistentThrows400() {
        assertThatThrownBy(() -> sm.getSecretValue(GetSecretValueRequest.builder()
                .secretId("non-existent-secret-" + System.currentTimeMillis())
                .build()))
                .isInstanceOf(SecretsManagerException.class)
                .extracting(e -> ((SecretsManagerException) e).statusCode())
                .isEqualTo(400);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Issue #340 — GetSecretValue must resolve partial ARNs (no random suffix)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(17)
    @DisplayName("#340 getSecretValue resolves partial ARN (without random suffix)")
    void getSecretValueByPartialArn() {
        Assumptions.assumeTrue(secretArn != null, "CreateSecret must succeed first");

        // Full ARN: arn:aws:secretsmanager:...:secret:<name>-XXXXXX  (7 chars: hyphen + 6)
        // Partial:  arn:aws:secretsmanager:...:secret:<name>
        String partialArn = secretArn.substring(0, secretArn.length() - 7);

        GetSecretValueResponse response = sm.getSecretValue(GetSecretValueRequest.builder()
                .secretId(partialArn)
                .build());

        assertThat(response.secretString()).isEqualTo(UPDATED_VALUE);
        assertThat(response.name()).isEqualTo(secretName);
    }

    @Test
    @Order(18)
    @DisplayName("#340 getSecretValue resolves partial ARN for secret with slashes in name")
    void getSecretValueByPartialArnWithSlashesInName() {
        String slashName = "compat-340/dev/database-" + System.currentTimeMillis();

        try {
            CreateSecretResponse created = sm.createSecret(CreateSecretRequest.builder()
                    .name(slashName)
                    .secretString("db-pass")
                    .build());

            String partialArn = created.arn().substring(0, created.arn().length() - 7);

            GetSecretValueResponse response = sm.getSecretValue(GetSecretValueRequest.builder()
                    .secretId(partialArn)
                    .build());

            assertThat(response.secretString()).isEqualTo("db-pass");
            assertThat(response.name()).isEqualTo(slashName);
        } finally {
            try {
                sm.deleteSecret(DeleteSecretRequest.builder()
                        .secretId(slashName)
                        .forceDeleteWithoutRecovery(true)
                        .build());
            } catch (Exception ignored) {}
        }
    }

    @Test
    @Order(19)
    void batchGetSecretValue() {
        String s1 = "batch-secret-1-" + System.currentTimeMillis();
        String s2 = "batch-secret-2-" + System.currentTimeMillis();

        try {
            sm.createSecret(CreateSecretRequest.builder().name(s1).secretString("v1").build());
            sm.createSecret(CreateSecretRequest.builder().name(s2).secretString("v2").build());

            BatchGetSecretValueResponse response = sm.batchGetSecretValue(BatchGetSecretValueRequest.builder()
                    .secretIdList(s1, s2)
                    .build());

            assertThat(response.secretValues()).hasSize(2);
            assertThat(response.secretValues().stream().map(v -> v.name()).collect(Collectors.toList()))
                    .containsExactlyInAnyOrder(s1, s2);
        } finally {
            try {
                sm.deleteSecret(DeleteSecretRequest.builder().secretId(s1).forceDeleteWithoutRecovery(true).build());
                sm.deleteSecret(DeleteSecretRequest.builder().secretId(s2).forceDeleteWithoutRecovery(true).build());
            } catch (Exception ignored) {}
        }
    }

    @Test
    @Order(20)
    @DisplayName("batchGetSecretValue returns partial Errors list for missing secrets without throwing")
    void batchGetSecretValuePartialErrors() {
        String exists = "batch-exists-" + UUID.randomUUID();
        String missing = "batch-missing-" + UUID.randomUUID();

        try {
            sm.createSecret(CreateSecretRequest.builder()
                    .name(exists)
                    .secretString("v1")
                    .build());

            BatchGetSecretValueResponse response = sm.batchGetSecretValue(
                    BatchGetSecretValueRequest.builder()
                            .secretIdList(exists, missing)
                            .build()
            );

            assertThat(response.secretValues()).hasSize(1);
            assertThat(response.secretValues().get(0).name()).isEqualTo(exists);

            assertThat(response.errors()).hasSize(1);
            assertThat(response.errors().get(0).secretId()).isEqualTo(missing);
            assertThat(response.errors().get(0).errorCode()).isEqualTo("ResourceNotFoundException");
            assertThat(response.errors().get(0).message())
                    .isEqualTo("Secrets Manager can't find the specified secret.");
        } finally {
            try {
                sm.deleteSecret(DeleteSecretRequest.builder()
                        .secretId(exists)
                        .forceDeleteWithoutRecovery(true)
                        .build());
            } catch (Exception ignored) {
            }
        }
    }
}
