package io.github.hectorvent.floci.services.secretsmanager;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class SecretsManagerServiceTest {

    private static final String REGION = "us-east-1";

    private SecretsManagerService service;

    @BeforeEach
    void setUp() {
        service = new SecretsManagerService(new InMemoryStorage<>(), 30);
    }

    @Test
    void createSecret() {
        Secret secret = service.createSecret("my-secret", "super-secret-value",
                null, "A test secret", null, null, REGION);

        assertNotNull(secret.getArn());
        assertEquals("my-secret", secret.getName());
        assertEquals("A test secret", secret.getDescription());
        assertNotNull(secret.getCurrentVersionId());
    }

    @Test
    void createSecretDuplicateThrows() {
        service.createSecret("my-secret", "value1", null, null, null, null, REGION);
        assertThrows(AwsException.class, () ->
                service.createSecret("my-secret", "value2", null, null, null, null, REGION));
    }

    @Test
    void getSecretValue() {
        service.createSecret("db-password", "s3cr3t", null, null, null, null, REGION);
        SecretVersion version = service.getSecretValue("db-password", null, null, REGION);

        assertEquals("s3cr3t", version.getSecretString());
        assertNotNull(version.getVersionId());
        assertTrue(version.getVersionStages().contains("AWSCURRENT"));
    }

    @Test
    void getSecretValueNotFoundThrows() {
        assertThrows(AwsException.class, () ->
                service.getSecretValue("missing", null, null, REGION));
    }

    @Test
    void putSecretValueRotatesVersion() {
        service.createSecret("my-secret", "v1", null, null, null, null, REGION);
        service.putSecretValue("my-secret", "v2", null, REGION, null);

        SecretVersion current = service.getSecretValue("my-secret", null, "AWSCURRENT", REGION);
        assertEquals("v2", current.getSecretString());

        SecretVersion previous = service.getSecretValue("my-secret", null, "AWSPREVIOUS", REGION);
        assertEquals("v1", previous.getSecretString());
    }

    @Test
    void putSecretValueOnDeletedSecretThrows() {
        service.createSecret("my-secret", "v1", null, null, null, null, REGION);
        service.deleteSecret("my-secret", null, true, REGION);
        assertThrows(AwsException.class, () ->
                service.putSecretValue("my-secret", "v2", null, REGION, null));
    }

    @Test
    void putSecretValuePendingStage() {
        service.createSecret("my-secret", "v1", null, null, null, null, REGION);
        service.putSecretValue("my-secret", "v2", null, REGION, List.of("AWSCURRENT"));
        service.putSecretValue("my-secret", "v3", null, REGION, List.of("AWSPENDING"));

        SecretVersion previous = service.getSecretValue("my-secret", null, "AWSPREVIOUS", REGION);
        assertEquals("v1", previous.getSecretString());

        SecretVersion current = service.getSecretValue("my-secret", null, "AWSCURRENT", REGION);
        assertEquals("v2", current.getSecretString());

        SecretVersion pending = service.getSecretValue("my-secret", null, "AWSPENDING", REGION);
        assertEquals("v3", pending.getSecretString());
    }

    @Test
    void putSecretValueMultiStage() {
        // create secret, single secret version exists
        service.createSecret("my-secret", "v1", null, null, null, null, REGION);
        SecretVersion current = service.getSecretValue("my-secret", null, "AWSCURRENT", REGION);
        assertEquals("v1", current.getSecretString());

        // adding new secret version, previous will be v1, current will be v2
        service.putSecretValue("my-secret", "v2", null, REGION, List.of("AWSCURRENT"));
        SecretVersion previous = service.getSecretValue("my-secret", null, "AWSPREVIOUS", REGION);
        assertEquals("v1", previous.getSecretString());
        current = service.getSecretValue("my-secret", null, "AWSCURRENT", REGION);
        assertEquals("v2", current.getSecretString());

        // adding new secret version v3, current and pending will be v3,
        // previous will be v2
        service.putSecretValue("my-secret", "v3", null, REGION, List.of("AWSCURRENT", "AWSPENDING"));
        previous = service.getSecretValue("my-secret", null, "AWSPREVIOUS", REGION);
        assertEquals("v2", previous.getSecretString());
        current = service.getSecretValue("my-secret", null, "AWSCURRENT", REGION);
        assertEquals("v3", current.getSecretString());
        SecretVersion pending = service.getSecretValue("my-secret", null, "AWSPENDING", REGION);
        assertEquals("v3", pending.getSecretString());
    }

    @Test
    void putSecretValueInvalidNumberOfStages() {
        service.createSecret("my-secret", "v1", null, null, null, null, REGION);

        // no stages
        Assertions.assertThrows(AwsException.class, () ->
            service.putSecretValue("my-secret", "v2", null, REGION, List.of())
        );
        // more than 20
        List<String> stages =
                IntStream.range(0, 21).mapToObj(i -> "stage" + i).toList();
        Assertions.assertThrows(AwsException.class, () ->
                service.putSecretValue("my-secret", "v2", null, REGION, stages)
        );
    }

    @Test
    void putSecretValueInvalidStageName() {
        service.createSecret("my-secret", "v1", null, null, null, null, REGION);
        // Stage name is 0-length
        Assertions.assertThrows(AwsException.class, () ->
                service.putSecretValue("my-secret", "v2", null, REGION, List.of(""))
        );
        // Stage name is larger than 256 characters
        String stageName = RandomStringUtils.randomAlphanumeric(257);
        Assertions.assertThrows(AwsException.class, () ->
                service.putSecretValue("my-secret", "v2", null, REGION, List.of(stageName))
        );

    }

    @Test
    void describeSecret() {
        service.createSecret("my-secret", "value", null, "desc", null, null, REGION);
        Secret described = service.describeSecret("my-secret", REGION);

        assertEquals("my-secret", described.getName());
        assertEquals("desc", described.getDescription());
    }

    @Test
    void updateSecret() {
        service.createSecret("my-secret", "value", null, "old desc", null, null, REGION);
        service.updateSecret("my-secret", "new desc", null, REGION);

        Secret updated = service.describeSecret("my-secret", REGION);
        assertEquals("new desc", updated.getDescription());
    }

    @Test
    void listSecrets() {
        service.createSecret("secret-1", "v1", null, null, null, null, REGION);
        service.createSecret("secret-2", "v2", null, null, null, null, REGION);
        service.createSecret("other-region", "v3", null, null, null, null, "eu-west-1");

        List<Secret> secrets = service.listSecrets(REGION);
        assertEquals(2, secrets.size());
    }

    @Test
    void listSecretsExcludesDeleted() {
        service.createSecret("active", "v1", null, null, null, null, REGION);
        service.createSecret("deleted", "v2", null, null, null, null, REGION);
        service.deleteSecret("deleted", 0, true, REGION);

        List<Secret> secrets = service.listSecrets(REGION);
        assertEquals(1, secrets.size());
        assertEquals("active", secrets.getFirst().getName());
    }

    @Test
    void deleteSecretWithRecoveryWindow() {
        service.createSecret("my-secret", "value", null, null, null, null, REGION);
        Secret deleted = service.deleteSecret("my-secret", 7, false, REGION);

        assertNotNull(deleted.getDeletedDate());

        // The secret still exists but marked deleted
        assertThrows(AwsException.class, () ->
                service.getSecretValue("my-secret", null, null, REGION));
    }

    @Test
    void forceDeleteSecret() {
        service.createSecret("my-secret", "value", null, null, null, null, REGION);
        service.deleteSecret("my-secret", null, true, REGION);

        assertThrows(AwsException.class, () ->
                service.describeSecret("my-secret", REGION));
    }

    @Test
    void rotateSecret() {
        service.createSecret("my-secret", "value", null, null, null, null, REGION);
        Secret rotated = service.rotateSecret("my-secret",
                "arn:aws:lambda:us-east-1:000000000000:function:rotate",
                Map.of("AutomaticallyAfterDays", 30), true, REGION);

        assertTrue(rotated.isRotationEnabled());
    }

    @Test
    void tagAndUntagResource() {
        service.createSecret("my-secret", "value", null, null, null,
                List.of(new Secret.Tag("env", "prod")), REGION);

        service.tagResource("my-secret", List.of(new Secret.Tag("team", "platform")), REGION);

        Secret secret = service.describeSecret("my-secret", REGION);
        List<String> keys = secret.getTags().stream().map(Secret.Tag::key).toList();
        assertTrue(keys.containsAll(List.of("env", "team")));

        service.untagResource("my-secret", List.of("env"), REGION);
        secret = service.describeSecret("my-secret", REGION);
        assertEquals(1, secret.getTags().size());
        assertEquals("team", secret.getTags().getFirst().key());
    }

    @Test
    void tagResourceUpserts() {
        service.createSecret("my-secret", "value", null, null, null,
                List.of(new Secret.Tag("env", "dev")), REGION);

        service.tagResource("my-secret", List.of(new Secret.Tag("env", "prod")), REGION);

        Secret secret = service.describeSecret("my-secret", REGION);
        assertEquals(1, secret.getTags().size());
        assertEquals("prod", secret.getTags().getFirst().value());
    }

    @Test
    void listSecretVersionIds() {
        service.createSecret("my-secret", "v1", null, null, null, null, REGION);
        service.putSecretValue("my-secret", "v2", null, REGION, null);

        Map<String, List<String>> versions = service.listSecretVersionIds("my-secret", REGION);
        assertEquals(2, versions.size());

        long currentCount = versions.values().stream()
                .filter(stages -> stages.contains("AWSCURRENT")).count();
        assertEquals(1, currentCount);
    }

    @Test
    void getSecretValueByVersionId() {
        service.createSecret("my-secret", "v1", null, null, null, null, REGION);
        SecretVersion v1 = service.getSecretValue("my-secret", null, "AWSCURRENT", REGION);
        String v1Id = v1.getVersionId();

        service.putSecretValue("my-secret", "v2", null, REGION, null);

        SecretVersion fetched = service.getSecretValue("my-secret", v1Id, null, REGION);
        assertEquals("v1", fetched.getSecretString());
    }

    @Test
    void batchGetSecretValue() {
        service.createSecret("secret1", "value1", null, null, null, null, REGION);
        service.createSecret("secret2", "value2", null, null, null, null, REGION);

        List<SecretsManagerService.BatchSecretValue> values = service.batchGetSecretValue(
                List.of("secret1", "secret2"), REGION);

        assertEquals(2, values.size());
        assertTrue(values.stream().anyMatch(v -> "secret1".equals(v.name()) && "value1".equals(v.secretString())));
        assertTrue(values.stream().anyMatch(v -> "secret2".equals(v.name()) && "value2".equals(v.secretString())));
    }

    @Test
    void batchGetSecretValueSkipsDeleted() {
        service.createSecret("secret1", "value1", null, null, null, null, REGION);
        service.createSecret("secret2", "value2", null, null, null, null, REGION);
        service.deleteSecret("secret1", 7, false, REGION);

        List<SecretsManagerService.BatchSecretValue> values = service.batchGetSecretValue(
                List.of("secret1", "secret2"), REGION);

        assertEquals(1, values.size());
        assertEquals("secret2", values.getFirst().name());
    }

    @Test
    void batchGetSecretValueThrowsIfNotFound() {
        service.createSecret("secret1", "value1", null, null, null, null, REGION);
        assertThrows(AwsException.class, () ->
                service.batchGetSecretValue(List.of("secret1", "non-existent"), REGION));
    }

    @Test
    void getSecretValueByPartialArnSucceeds() {
        Secret secret = service.createSecret("my-secret", "value", null, null, null, null, REGION);
        // Full ARN: arn:aws:secretsmanager:us-east-1:000000000000:secret:my-secret-XXXXXX
        // Partial:  arn:aws:secretsmanager:us-east-1:000000000000:secret:my-secret
        String partialArn = secret.getArn().substring(0, secret.getArn().length() - 7);

        SecretVersion version = service.getSecretValue(partialArn, null, null, REGION);
        assertEquals("value", version.getSecretString());
    }

    @Test
    void getSecretValueByPartialArnWithSlashesInNameSucceeds() {
        Secret secret = service.createSecret("my-app/dev/database", "db-pass", null, null, null, null, REGION);
        String partialArn = secret.getArn().substring(0, secret.getArn().length() - 7);

        SecretVersion version = service.getSecretValue(partialArn, null, null, REGION);
        assertEquals("db-pass", version.getSecretString());
    }

    @Test
    void getSecretValueByFullArnStillWorks() {
        Secret secret = service.createSecret("my-secret", "value", null, null, null, null, REGION);

        SecretVersion version = service.getSecretValue(secret.getArn(), null, null, REGION);
        assertEquals("value", version.getSecretString());
    }

    @Test
    void getSecretValueByNonExistentPartialArnThrows() {
        String nonExistent = "arn:aws:secretsmanager:us-east-1:000000000000:secret:does-not-exist";
        assertThrows(AwsException.class, () ->
                service.getSecretValue(nonExistent, null, null, REGION));
    }

    @Test
    void kmsKeyIdIsPreserved() {
        String kmsKeyId = "arn:aws:kms:us-east-1:000000000000:key/my-key";
        // Signature: name, secretString, secretBinary, description, kmsKeyId, tags, region
        Secret secret = service.createSecret("kms-secret", "value", null,
                "desc", kmsKeyId, null, REGION);

        assertEquals(kmsKeyId, secret.getKmsKeyId());

        Secret described = service.describeSecret("kms-secret", REGION);
        assertEquals(kmsKeyId, described.getKmsKeyId());

        service.updateSecret("kms-secret", "new desc", "arn:aws:kms:us-east-1:000000000000:key/other-key", REGION);
        Secret updated = service.describeSecret("kms-secret", REGION);
        assertEquals("arn:aws:kms:us-east-1:000000000000:key/other-key", updated.getKmsKeyId());
    }
}
