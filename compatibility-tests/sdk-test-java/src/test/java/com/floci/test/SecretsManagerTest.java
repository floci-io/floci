package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
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
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;
import software.amazon.awssdk.services.secretsmanager.model.SecretVersionsListEntry;
import software.amazon.awssdk.services.secretsmanager.model.TagResourceRequest;
import software.amazon.awssdk.services.secretsmanager.model.UntagResourceRequest;
import software.amazon.awssdk.services.secretsmanager.model.UpdateSecretRequest;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

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
    void rotateSecretStub() {
        RotateSecretResponse rotateResponse = sm.rotateSecret(RotateSecretRequest.builder()
                .secretId(secretName)
                .rotationRules(RotationRulesType.builder().automaticallyAfterDays(30L).build())
                .build());

        assertThat(rotateResponse.arn()).isEqualTo(secretArn);

        DescribeSecretResponse describeResponse = sm.describeSecret(DescribeSecretRequest.builder()
                .secretId(secretName)
                .build());

        assertThat(describeResponse.rotationEnabled()).isTrue();
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
}
