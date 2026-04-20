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
        service.createSecret("my-secret", "v1", null, null, null, null, REGION);
        service.putSecretValue("my-secret", "v2", null, REGION, List.of("AWSCURRENT"));
        service.putSecretValue("my-secret", "v3", null, REGION, List.of("AWSCURRENT", "AWSPENDING"));

        SecretVersion previous = service.getSecretValue("my-secret", null, "AWSPREVIOUS", REGION);
        assertEquals("v2", previous.getSecretString());

        SecretVersion current = service.getSecretValue("my-secret", null, "AWSCURRENT", REGION);
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

    @Test
    void updateSecretVersionStageInvalidSecretIdThrows() {
        String validStage = "AWSCURRENT";
        assertThrows(AwsException.class, () ->
                service.updateSecretVersionStage(null, null, null, validStage, REGION));
        assertThrows(AwsException.class, () ->
                service.updateSecretVersionStage("", null, null, validStage, REGION));
        String longId = RandomStringUtils.randomAlphanumeric(2049);
        assertThrows(AwsException.class, () ->
                service.updateSecretVersionStage(longId, null, null, validStage, REGION));
    }

    @Test
    void updateSecretVersionStageInvalidVersionStageThrows() {
        assertThrows(AwsException.class, () ->
                service.updateSecretVersionStage("my-secret", null, null, null, REGION));
        assertThrows(AwsException.class, () ->
                service.updateSecretVersionStage("my-secret", null, null, "", REGION));
        String longStage = RandomStringUtils.randomAlphanumeric(257);
        assertThrows(AwsException.class, () ->
                service.updateSecretVersionStage("my-secret", null, null, longStage, REGION));
    }

    @Test
    void updateSecretVersionStageInvalidMoveToVersionIdThrows() {
        String shortId = RandomStringUtils.randomAlphanumeric(31);
        assertThrows(AwsException.class, () ->
                service.updateSecretVersionStage("my-secret", shortId, null, "AWSCURRENT", REGION));
        String longId = RandomStringUtils.randomAlphanumeric(65);
        assertThrows(AwsException.class, () ->
                service.updateSecretVersionStage("my-secret", longId, null, "AWSCURRENT", REGION));
    }

    @Test
    void updateSecretVersionStageInvalidRemoveFromVersionIdThrows() {
        String shortId = RandomStringUtils.randomAlphanumeric(31);
        assertThrows(AwsException.class, () ->
                service.updateSecretVersionStage("my-secret", null, shortId, "AWSCURRENT", REGION));
        String longId = RandomStringUtils.randomAlphanumeric(65);
        assertThrows(AwsException.class, () ->
                service.updateSecretVersionStage("my-secret", null, longId, "AWSCURRENT", REGION));
    }

    @Test
    void updateSecretVersionStageSecretNotFoundThrows() {
        assertThrows(AwsException.class, () ->
                service.updateSecretVersionStage("non-existent", null, null, "AWSCURRENT", REGION));
    }

    @Test
    void updateSecretVersionStageDeletedSecretThrows() {
        service.createSecret("my-secret", "v1", null, null, null, null, REGION);
        service.deleteSecret("my-secret", 7, false, REGION);

        assertThrows(AwsException.class, () ->
                service.updateSecretVersionStage("my-secret", null, null, "AWSCURRENT", REGION));
    }

    @Test
    void updateSecretVersionStageRemoveFromRequiredWhenStageAttached() {
        service.createSecret("my-secret", "v1", null, null, null, null, REGION);
        String v1Id = service.getSecretValue("my-secret", null, "AWSCURRENT", REGION).getVersionId();

        assertThrows(AwsException.class, () ->
                service.updateSecretVersionStage("my-secret", v1Id, null, "AWSCURRENT", REGION));
    }

    @Test
    void updateSecretVersionStageRemoveFromMustMatchCurrentVersion() {
        service.createSecret("my-secret", "v1", null, null, null, null, REGION);
        service.putSecretValue("my-secret", "v2", null, REGION, null);

        String v1Id = service.getSecretValue("my-secret", null, "AWSPREVIOUS", REGION).getVersionId();
        String v2Id = service.getSecretValue("my-secret", null, "AWSCURRENT", REGION).getVersionId();

        assertThrows(AwsException.class, () ->
                service.updateSecretVersionStage("my-secret", v1Id, v1Id, "AWSCURRENT", REGION));
    }

    @Test
    void updateSecretVersionStageMoveToNonExistentVersionThrows() {
        service.createSecret("my-secret", "v1", null, null, null, null, REGION);
        String fakeVersionId = RandomStringUtils.randomAlphanumeric(36);

        assertThrows(AwsException.class, () ->
                service.updateSecretVersionStage("my-secret", fakeVersionId, null, "NEWLABEL", REGION));
    }

    @Test
    void updateSecretVersionStageMovesCustomLabel() {
        service.createSecret("my-secret", "v1", null, null, null, null, REGION);
        service.putSecretValue("my-secret", "v2", null, REGION, List.of("AWSCURRENT", "MYSTAGE"));

        String v1Id = service.getSecretValue("my-secret", null, "AWSPREVIOUS", REGION).getVersionId();
        String v2Id = service.getSecretValue("my-secret", null, "AWSCURRENT", REGION).getVersionId();

        service.updateSecretVersionStage("my-secret", v1Id, v2Id, "MYSTAGE", REGION);

        SecretVersion v1After = service.getSecretValue("my-secret", v1Id, null, REGION);
        SecretVersion v2After = service.getSecretValue("my-secret", v2Id, null, REGION);

        assertTrue(v1After.getVersionStages().contains("MYSTAGE"));
        assertFalse(v2After.getVersionStages().contains("MYSTAGE"));
    }

    @Test
    void updateSecretVersionStageMoveAwsCurrentAddsAwsPrevious() {
        service.createSecret("my-secret", "v1", null, null, null, null, REGION);
        service.putSecretValue("my-secret", "v2", null, REGION, null);

        String v1Id = service.getSecretValue("my-secret", null, "AWSPREVIOUS", REGION).getVersionId();
        String v2Id = service.getSecretValue("my-secret", null, "AWSCURRENT", REGION).getVersionId();

        service.updateSecretVersionStage("my-secret", v1Id, v2Id, "AWSCURRENT", REGION);

        SecretVersion v1After = service.getSecretValue("my-secret", v1Id, null, REGION);
        SecretVersion v2After = service.getSecretValue("my-secret", v2Id, null, REGION);

        assertTrue(v1After.getVersionStages().contains("AWSCURRENT"));
        assertFalse(v1After.getVersionStages().contains("AWSPREVIOUS"));
        assertFalse(v2After.getVersionStages().contains("AWSCURRENT"));
        assertTrue(v2After.getVersionStages().contains("AWSPREVIOUS"));
    }

    @Test
    void updateSecretVersionStageAddsLabelWhenNotAttached() {
        service.createSecret("my-secret", "v1", null, null, null, null, REGION);
        String v1Id = service.getSecretValue("my-secret", null, "AWSCURRENT", REGION).getVersionId();

        service.updateSecretVersionStage("my-secret", v1Id, null, "NEWLABEL", REGION);

        SecretVersion v1After = service.getSecretValue("my-secret", v1Id, null, REGION);
        assertTrue(v1After.getVersionStages().contains("NEWLABEL"));
        assertTrue(v1After.getVersionStages().contains("AWSCURRENT"));
    }

    @Test
    void updateSecretVersionStageMoveAwsCurrentCleansUpPreviousFromMultiStageVersion() {
        service.createSecret("my-secret", "v1", null, null, null, null, REGION);
        service.putSecretValue("my-secret", "v2", null, REGION, null);
        service.putSecretValue("my-secret", "v3", null, REGION, null);

        String v1Id = service.getSecretValue("my-secret", null, "AWSCURRENT", REGION).getVersionId();
        String v3Id = service.getSecretValue("my-secret", null, "AWSCURRENT", REGION).getVersionId();
        String v2Id = service.getSecretValue("my-secret", null, "AWSPREVIOUS", REGION).getVersionId();

        service.updateSecretVersionStage("my-secret", v2Id, null, "CUSTOMLABEL", REGION);

        Secret described = service.describeSecret("my-secret", REGION);
        String v1Id2 = described.getVersions().keySet().stream()
                .filter(id -> !id.equals(v2Id) && !id.equals(v3Id))
                .findFirst().orElseThrow();

        service.updateSecretVersionStage("my-secret", v1Id2, v3Id, "AWSCURRENT", REGION);

        SecretVersion v2After = service.getSecretValue("my-secret", v2Id, null, REGION);
        assertFalse(v2After.getVersionStages().contains("AWSPREVIOUS"));
        assertTrue(v2After.getVersionStages().contains("CUSTOMLABEL"));
    }

    @Test
    void updateSecretVersionStageMoveAwsCurrentRemovesPreviousOnlyVersion() {
        service.createSecret("my-secret", "v1", null, null, null, null, REGION);
        service.putSecretValue("my-secret", "v2", null, REGION, null);
        service.putSecretValue("my-secret", "v3", null, REGION, null);

        String v3Id = service.getSecretValue("my-secret", null, "AWSCURRENT", REGION).getVersionId();
        String v2Id = service.getSecretValue("my-secret", null, "AWSPREVIOUS", REGION).getVersionId();

        service.updateSecretVersionStage("my-secret", v2Id, v3Id, "AWSCURRENT", REGION);

        SecretVersion v2After = service.getSecretValue("my-secret", v2Id, null, REGION);
        assertTrue(v2After.getVersionStages().contains("AWSCURRENT"));
        assertFalse(v2After.getVersionStages().contains("AWSPREVIOUS"));

        SecretVersion v3After = service.getSecretValue("my-secret", v3Id, null, REGION);
        assertTrue(v3After.getVersionStages().contains("AWSPREVIOUS"));
    }

    @Test
    void updateSecretVersionStageRemovesLabelOnly() {
        service.createSecret("my-secret", "v1", null, null, null, null, REGION);
        String v1Id = service.getSecretValue("my-secret", null, "AWSCURRENT", REGION).getVersionId();

        service.updateSecretVersionStage("my-secret", v1Id, null, "CUSTOMLABEL", REGION);
        assertTrue(service.getSecretValue("my-secret", v1Id, null, REGION)
                .getVersionStages().contains("CUSTOMLABEL"));

        service.updateSecretVersionStage("my-secret", null, v1Id, "CUSTOMLABEL", REGION);

        SecretVersion v1After = service.getSecretValue("my-secret", v1Id, null, REGION);
        assertFalse(v1After.getVersionStages().contains("CUSTOMLABEL"));
        assertTrue(v1After.getVersionStages().contains("AWSCURRENT"));
    }
}
