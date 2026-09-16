package io.github.hectorvent.floci.services.secretsmanager;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
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
    void getSecretValueWithMatchingVersionIdAndStage() {
        service.createSecret("paired", "v1", null, null, null, null, REGION);
        SecretVersion current = service.getSecretValue("paired", null, null, REGION);

        SecretVersion fetched = service.getSecretValue("paired",
                current.getVersionId(), "AWSCURRENT", REGION);
        assertEquals("v1", fetched.getSecretString());
    }

    @Test
    void getSecretValueWithMismatchedVersionIdAndStageThrows() {
        // botocore: when both are supplied they must refer to the same version; moto and
        // LocalStack both raise InvalidRequestException with this message.
        service.createSecret("mismatched", "v1", null, null, null, null, REGION);
        SecretVersion current = service.getSecretValue("mismatched", null, null, REGION);

        AwsException thrown = assertThrows(AwsException.class, () ->
                service.getSecretValue("mismatched", current.getVersionId(), "AWSPREVIOUS", REGION));
        assertEquals("InvalidRequestException", thrown.getErrorCode());
        assertTrue(thrown.getMessage().contains(
                "You provided a VersionStage that is not associated to the provided VersionId."));
    }

    @Test
    void putSecretValueRotatesVersion() {
        service.createSecret("my-secret", "v1", null, null, null, null, REGION);
        service.putSecretValue("my-secret", "v2", null, null, REGION, null);

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
                service.putSecretValue("my-secret", "v2", null, null, REGION, null));
    }

    @Test
    void putSecretValuePendingStage() {
        service.createSecret("my-secret", "v1", null, null, null, null, REGION);
        service.putSecretValue("my-secret", "v2", null, null, REGION, List.of("AWSCURRENT"));
        service.putSecretValue("my-secret", "v3", null, null, REGION, List.of("AWSPENDING"));

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
        service.putSecretValue("my-secret", "v2", null, null, REGION, List.of("AWSCURRENT"));
        SecretVersion previous = service.getSecretValue("my-secret", null, "AWSPREVIOUS", REGION);
        assertEquals("v1", previous.getSecretString());
        current = service.getSecretValue("my-secret", null, "AWSCURRENT", REGION);
        assertEquals("v2", current.getSecretString());

        // adding new secret version v3, current and pending will be v3,
        // previous will be v2
        service.putSecretValue("my-secret", "v3", null, null, REGION, List.of("AWSCURRENT", "AWSPENDING"));
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
            service.putSecretValue("my-secret", "v2", null, null, REGION, List.of())
        );
        // more than 20
        List<String> stages =
                IntStream.range(0, 21).mapToObj(i -> "stage" + i).toList();
        Assertions.assertThrows(AwsException.class, () ->
                service.putSecretValue("my-secret", "v2", null, null, REGION, stages)
        );
    }

    @Test
    void putSecretValueInvalidStageName() {
        service.createSecret("my-secret", "v1", null, null, null, null, REGION);
        // Stage name is 0-length
        Assertions.assertThrows(AwsException.class, () ->
                service.putSecretValue("my-secret", "v2", null, null, REGION, List.of(""))
        );
        // Stage name is larger than 256 characters
        String stageName = RandomStringUtils.randomAlphanumeric(257);
        Assertions.assertThrows(AwsException.class, () ->
                service.putSecretValue("my-secret", "v2", null, null, REGION, List.of(stageName))
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
    void targetAttachmentClaimIsExclusiveAndIdempotentForItsOwner() {
        service.createSecret("my-secret", "value", null, null, null, null, REGION);

        assertTrue(service.claimTargetAttachment("my-secret", "stack/First", REGION));
        assertFalse(service.claimTargetAttachment("my-secret", "stack/First", REGION));
        AwsException duplicate = assertThrows(AwsException.class,
                () -> service.claimTargetAttachment("my-secret", "stack/Second", REGION));

        assertEquals("ResourceExistsException", duplicate.getErrorCode());
        assertTrue(duplicate.getMessage().contains("already attached"));
        assertTrue(service.canManageTargetAttachment("my-secret", "stack/First", REGION));
        assertFalse(service.canManageTargetAttachment("my-secret", "stack/Second", REGION));
    }

    @Test
    void targetAttachmentClaimCanOnlyBeReleasedByItsOwner() {
        service.createSecret("my-secret", "value", null, null, null, null, REGION);
        service.claimTargetAttachment("my-secret", "stack/First", REGION);

        service.releaseTargetAttachment("my-secret", "stack/Second", REGION);
        assertThrows(AwsException.class,
                () -> service.claimTargetAttachment("my-secret", "stack/Second", REGION));

        service.releaseTargetAttachment("my-secret", "stack/First", REGION);
        assertTrue(service.claimTargetAttachment("my-secret", "stack/Second", REGION));
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
        service.deleteSecret("deleted", null, true, REGION);

        List<Secret> secrets = service.listSecrets(REGION);
        assertEquals(1, secrets.size());
        assertEquals("active", secrets.getFirst().getName());
    }

    @Test
    void deleteSecretWithRecoveryWindow() {
        service.createSecret("my-secret", "value", null, null, null, null, REGION);
        Secret deleted = service.deleteSecret("my-secret", 7, false, REGION);

        assertNotNull(deleted.getDeletedDate());

        // The secret still exists but marked deleted: real AWS raises InvalidRequestException
        // here, distinct from the ResourceNotFoundException a genuinely absent secret gets.
        // A bare assertThrows(AwsException.class, ...) doesn't distinguish the two - regression
        // test for the bug where every one of these sites except batchGetSecretValue threw the
        // wrong (not-found) code.
        AwsException ex = assertThrows(AwsException.class, () ->
                service.getSecretValue("my-secret", null, null, REGION));
        assertEquals("InvalidRequestException", ex.getErrorCode());
    }

    @Test
    void operationsOnPendingDeletionSecretRaiseInvalidRequestNotResourceNotFound() {
        service.createSecret("my-secret", "value", null, null, null, null, REGION);
        service.deleteSecret("my-secret", 7, false, REGION);

        assertEquals("InvalidRequestException", assertThrows(AwsException.class, () ->
                service.getSecretValue("my-secret", null, null, REGION)).getErrorCode());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class, () ->
                service.putSecretValue("my-secret", "v2", null, null, REGION, null)).getErrorCode());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class, () ->
                service.updateSecret("my-secret", "new description", null, REGION)).getErrorCode());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class, () ->
                service.claimTargetAttachment("my-secret", "owner-1", REGION)).getErrorCode());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class, () ->
                service.rotateSecret("my-secret", null, "arn:aws:lambda:us-east-1:000000000000:function:rotator",
                        null, false, REGION)).getErrorCode());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class, () ->
                service.updateSecretVersionStage("my-secret", null, null, "AWSCURRENT", REGION)).getErrorCode());
    }

    /**
     * The data-loss case from #2549: CreateSecret against a name inside its recovery window used
     * to fall through the "already exists" guard (which only fired when {@code deletedDate} was
     * null), overwrite the recoverable secret at the same storage key, and clear its
     * {@code deletedDate} as a side effect. The original value became unrecoverable and
     * RestoreSecret then reported "was not deleted", so nothing surfaced the loss.
     */
    @Test
    void createSecretOnPendingDeletionNameIsRefusedAndLeavesOriginalRecoverable() {
        service.createSecret("my-secret", "ORIGINAL-VALUE", null, null, null, null, REGION);
        service.deleteSecret("my-secret", 7, false, REGION);

        assertEquals("InvalidRequestException", assertThrows(AwsException.class, () ->
                service.createSecret("my-secret", "NEW-VALUE", null, null, null, null, REGION)).getErrorCode());

        // The recovery window still means what it says: restore works and returns the original.
        service.restoreSecret("my-secret", REGION);
        assertEquals("ORIGINAL-VALUE",
                service.getSecretValue("my-secret", null, null, REGION).getSecretString());
    }

    @Test
    void tagUntagAndRescheduleOnPendingDeletionSecretRaiseInvalidRequest() {
        service.createSecret("my-secret", "value", null, null, null, null, REGION);
        service.deleteSecret("my-secret", 7, false, REGION);

        assertEquals("InvalidRequestException", assertThrows(AwsException.class, () ->
                service.tagResource("my-secret", List.of(new Secret.Tag("k", "v")), REGION)).getErrorCode());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class, () ->
                service.untagResource("my-secret", List.of("k"), REGION)).getErrorCode());
        // A second non-force DeleteSecret used to silently move DeletionDate forward.
        assertEquals("InvalidRequestException", assertThrows(AwsException.class, () ->
                service.deleteSecret("my-secret", 7, false, REGION)).getErrorCode());
    }

    /**
     * Force delete stays available as the documented "skip the recovery window" escape hatch, so
     * the guard added above must not block it. Pins the guard's placement after the force branch.
     */
    @Test
    void forceDeleteStillWorksOnAPendingDeletionSecret() {
        service.createSecret("my-secret", "value", null, null, null, null, REGION);
        service.deleteSecret("my-secret", 7, false, REGION);

        service.deleteSecret("my-secret", null, true, REGION);

        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class, () ->
                service.describeSecret("my-secret", REGION)).getErrorCode());
    }

    /**
     * DescribeSecret is how a caller reads a scheduled secret's DeletedDate, and AWS does not
     * declare InvalidRequestException for it. Pins that the guard was not over-applied.
     */
    @Test
    void describeSecretRemainsAllowedOnPendingDeletionSecret() {
        service.createSecret("my-secret", "value", null, null, null, null, REGION);
        service.deleteSecret("my-secret", 7, false, REGION);

        Secret described = service.describeSecret("my-secret", REGION);
        assertEquals("my-secret", described.getName());
        assertNotNull(described.getDeletedDate());
    }

    @Test
    void forceDeleteSecret() {
        service.createSecret("my-secret", "value", null, null, null, null, REGION);
        service.deleteSecret("my-secret", null, true, REGION);

        assertThrows(AwsException.class, () ->
                service.describeSecret("my-secret", REGION));
    }

    @Test
    void rotateSecret() throws Exception {
        // We need a mocked LambdaService for testing the orchestrator.
        // The service in the setUp method doesn't have it.
        io.github.hectorvent.floci.services.lambda.LambdaService mockLambda = org.mockito.Mockito.mock(io.github.hectorvent.floci.services.lambda.LambdaService.class);
        
        io.github.hectorvent.floci.services.lambda.model.InvokeResult successResult = new io.github.hectorvent.floci.services.lambda.model.InvokeResult();
        // Assume success response
        org.mockito.Mockito.when(mockLambda.invoke(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.any(byte[].class), org.mockito.Mockito.any()))
                .thenReturn(successResult);

        SecretsManagerService svc = new SecretsManagerService(
                new InMemoryStorage<String, Secret>(), 30,
                new io.github.hectorvent.floci.core.common.RegionResolver("us-east-1", "000000000000"),
                mockLambda, new com.fasterxml.jackson.databind.ObjectMapper());

        svc.createSecret("my-secret", "value", null, null, null, null, REGION);
        
        Secret.RotationRules rules = new Secret.RotationRules(30, null, null);
        
        Secret rotated = svc.rotateSecret("my-secret", "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                "arn:aws:lambda:us-east-1:000000000000:function:rotate",
                rules, true, REGION);

        assertTrue(rotated.isRotationEnabled());
        
        // Verify lambda invoked 4 times
        org.mockito.Mockito.verify(mockLambda, org.mockito.Mockito.timeout(5000).times(4))
                .invoke(org.mockito.Mockito.eq(REGION),
                        org.mockito.Mockito.eq("arn:aws:lambda:us-east-1:000000000000:function:rotate"), 
                        org.mockito.Mockito.any(byte[].class),
                        org.mockito.Mockito.eq(io.github.hectorvent.floci.services.lambda.model.InvocationType.RequestResponse));
    }

    @Test
    void rotateSecretNotImmediate() throws Exception {
        io.github.hectorvent.floci.services.lambda.LambdaService mockLambda = org.mockito.Mockito.mock(io.github.hectorvent.floci.services.lambda.LambdaService.class);
        
        io.github.hectorvent.floci.services.lambda.model.InvokeResult successResult = new io.github.hectorvent.floci.services.lambda.model.InvokeResult();
        org.mockito.Mockito.when(mockLambda.invoke(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.any(byte[].class), org.mockito.Mockito.any()))
                .thenReturn(successResult);

        SecretsManagerService svc = new SecretsManagerService(
                new InMemoryStorage<String, Secret>(), 30,
                new io.github.hectorvent.floci.core.common.RegionResolver("us-east-1", "000000000000"),
                mockLambda, new com.fasterxml.jackson.databind.ObjectMapper());

        svc.createSecret("my-secret", "value", null, null, null, null, REGION);
        Secret.RotationRules rules = new Secret.RotationRules(30, null, null);
        
        svc.rotateSecret("my-secret", "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                "arn:aws:lambda:us-east-1:000000000000:function:rotate",
                rules, false, REGION);

        // Verify lambda invoked 1 time (testSecret only for non-immediate rotation)
        org.mockito.Mockito.verify(mockLambda, org.mockito.Mockito.timeout(5000).times(1))
                .invoke(org.mockito.Mockito.eq(REGION),
                        org.mockito.Mockito.eq("arn:aws:lambda:us-east-1:000000000000:function:rotate"), 
                        org.mockito.Mockito.any(byte[].class),
                        org.mockito.Mockito.eq(io.github.hectorvent.floci.services.lambda.model.InvocationType.RequestResponse));
    }

    @Test
    void rotateSecretWithoutLambdaArnThrows() {
        service.createSecret("my-secret", "value", null, null, null, null, REGION);

        AwsException e = assertThrows(AwsException.class, () ->
                service.rotateSecret("my-secret", null, null,
                        new Secret.RotationRules(30, null, null), true, REGION));

        assertEquals("InvalidRequestException", e.getErrorCode());
    }

    @Test
    void rotateServiceManagedSecretNeedsNoLambdaArn() {
        io.github.hectorvent.floci.services.lambda.LambdaService mockLambda =
                org.mockito.Mockito.mock(io.github.hectorvent.floci.services.lambda.LambdaService.class);
        SecretsManagerService svc = new SecretsManagerService(
                new InMemoryStorage<String, Secret>(), 30,
                new io.github.hectorvent.floci.core.common.RegionResolver("us-east-1", "000000000000"),
                mockLambda, new com.fasterxml.jackson.databind.ObjectMapper());

        // The secret RDS manages: rotated by RDS itself, so it carries no rotation Lambda.
        svc.createSecret("rds!db-1234", "value", null, null, null, null, "rds", REGION);

        Secret rotated = svc.rotateSecret("rds!db-1234", null, null,
                new Secret.RotationRules(7, null, null), true, REGION);

        assertTrue(rotated.isRotationEnabled());
        assertEquals(7, rotated.getRotationRules().automaticallyAfterDays());
        // floci does not re-issue the credential, so it must not claim a rotation happened.
        assertNull(rotated.getLastRotatedDate());
        assertNull(rotated.getRotationLambdaArn());
        // No Lambda exists to drive the rotation lifecycle, so none may be invoked.
        org.mockito.Mockito.verifyNoInteractions(mockLambda);
    }

    @Test
    void rotateServiceManagedSecretRejectsLambdaArn() {
        service.createSecret("rds!db-1234", "value", null, null, null, null, "rds", REGION);

        AwsException e = assertThrows(AwsException.class, () ->
                service.rotateSecret("rds!db-1234", null,
                        "arn:aws:lambda:us-east-1:000000000000:function:rotate",
                        new Secret.RotationRules(7, null, null), true, REGION));

        assertEquals("InvalidRequestException", e.getErrorCode());
        assertEquals("Rotation Lambda ARN is not supported for a service-managed secret.", e.getMessage());
    }

    @Test
    void markOwnedByServiceLetsAPersistedSecretRotateAsManaged() {
        // A secret stored before floci tracked ownership deserializes without an owning service.
        String arn = service.createSecret("rds!db-1234", "value", null, null, null, null, REGION).getArn();
        assertThrows(AwsException.class, () ->
                service.rotateSecret("rds!db-1234", null, null,
                        new Secret.RotationRules(7, null, null), true, REGION));

        service.markOwnedByService(arn, "rds");

        assertEquals("rds", service.describeSecret("rds!db-1234", REGION).getOwningService());
        assertTrue(service.rotateSecret("rds!db-1234", null, null,
                new Secret.RotationRules(7, null, null), true, REGION).isRotationEnabled());
    }

    @Test
    void markOwnedByServiceKeepsAnExistingOwnerAndToleratesAMissingSecret() {
        String arn = service.createSecret("rds!db-1234", "value", null, null, null, null, "rds", REGION).getArn();

        service.markOwnedByService(arn, "redshift");
        assertEquals("rds", service.describeSecret("rds!db-1234", REGION).getOwningService());

        // A backfill runs over whatever state it finds, so neither a secret that is gone nor a
        // malformed ARN is an error.
        assertDoesNotThrow(() -> service.markOwnedByService(
                "arn:aws:secretsmanager:" + REGION + ":000000000000:secret:no-such-secret-AbCdEf", "rds"));
        assertDoesNotThrow(() -> service.markOwnedByService("not-an-arn", "rds"));
    }

    @Test
    void markOwnedByServiceAddressesTheAccountNamedInTheArn() {
        // A backfill runs at startup, outside any request, so the store resolves the default
        // account unless the ARN's account is used. Secrets of other accounts must still be found,
        // and a same-named default-account secret must be left alone.
        AccountAwareStorageBackend<Secret> accountAware =
                AccountAwareStorageBackend.inMemory("000000000000");
        SecretsManagerService svc = new SecretsManagerService(accountAware, 30);

        Secret otherAccount = new Secret();
        otherAccount.setName("rds!db-1234");
        otherAccount.setArn("arn:aws:secretsmanager:" + REGION + ":111122223333:secret:rds!db-1234-AbCdEf");
        accountAware.putForAccount("111122223333", REGION + "::rds!db-1234", otherAccount);

        Secret sameNameHere = svc.createSecret("rds!db-1234", "value", null, null, null, null, REGION);

        svc.markOwnedByService(otherAccount.getArn(), "rds");

        assertEquals("rds",
                accountAware.getForAccount("111122223333", REGION + "::rds!db-1234").orElseThrow().getOwningService());
        assertNull(sameNameHere.getOwningService());
    }

    @Test
    void ordinarySecretNamedLikeAnRdsSecretStillNeedsALambdaArn() {
        // Ownership is a property of the secret, not of its name: anyone may create a secret
        // called rds!something, and that must not grant it service-managed rotation.
        service.createSecret("rds!db-impostor", "value", null, null, null, null, REGION);

        AwsException e = assertThrows(AwsException.class, () ->
                service.rotateSecret("rds!db-impostor", null, null,
                        new Secret.RotationRules(7, null, null), true, REGION));

        assertEquals("InvalidRequestException", e.getErrorCode());
        assertTrue(e.getMessage().contains("doesn't already have a Lambda function ARN configured"));
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
        service.putSecretValue("my-secret", "v2", null, null, REGION, null);

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

        service.putSecretValue("my-secret", "v2", null, null, REGION, null);

        SecretVersion fetched = service.getSecretValue("my-secret", v1Id, null, REGION);
        assertEquals("v1", fetched.getSecretString());
    }

    @Test
    void batchGetSecretValue() {
        service.createSecret("secret1", "value1", null, null, null, null, REGION);
        service.createSecret("secret2", "value2", null, null, null, null, REGION);

        SecretsManagerService.BatchGetSecretValueResult result = service.batchGetSecretValue(
                List.of("secret1", "secret2"), REGION);

        List<SecretsManagerService.BatchSecretValue> values = result.values();
        assertEquals(2, values.size());
        assertTrue(values.stream().anyMatch(v -> "secret1".equals(v.name()) && "value1".equals(v.secretString())));
        assertTrue(values.stream().anyMatch(v -> "secret2".equals(v.name()) && "value2".equals(v.secretString())));

        assertEquals(0, result.errors().size());
    }

    @Test
    void batchGetSecretValueReportsDeletedInErrors() {
        service.createSecret("secret1", "value1", null, null, null, null, REGION);
        service.createSecret("secret2", "value2", null, null, null, null, REGION);
        service.deleteSecret("secret1", 7, false, REGION);

        SecretsManagerService.BatchGetSecretValueResult result = service.batchGetSecretValue(
                List.of("secret1", "secret2"), REGION);

        List<SecretsManagerService.BatchSecretValue> values = result.values();
        assertEquals(1, values.size());
        assertEquals("secret2", values.getFirst().name());

        List<SecretsManagerService.BatchGetSecretValueError> errors = result.errors();
        assertEquals(1, errors.size());
        assertEquals("secret1", errors.getFirst().secretId());
        assertEquals("InvalidRequestException", errors.getFirst().errorCode());
        assertEquals("You can't perform this operation on the secret because it was marked for deletion.",
                errors.getFirst().message());
    }

    @Test
    void batchGetSecretValueReportsMissingCurrentVersionInErrors() {
        service.createSecret("secret1", "value1", null, null, null, null, REGION);

        Secret secret = service.describeSecret("secret1", REGION);
        SecretVersion version = secret.getVersions().get(secret.getCurrentVersionId());
        version.setVersionStages(List.of());

        SecretsManagerService.BatchGetSecretValueResult result = service.batchGetSecretValue(
                List.of("secret1"), REGION);

        assertEquals(0, result.values().size());

        List<SecretsManagerService.BatchGetSecretValueError> errors = result.errors();
        assertEquals(1, errors.size());
        assertEquals("secret1", errors.getFirst().secretId());
        assertEquals("ResourceNotFoundException", errors.getFirst().errorCode());
    }

    @Test
    void batchGetSecretValueReportsMissingInErrors() {
        service.createSecret("secret1", "value1", null, null, null, null, REGION);

        SecretsManagerService.BatchGetSecretValueResult result = service.batchGetSecretValue(
                List.of("secret1", "non-existent"), REGION);

        List<SecretsManagerService.BatchSecretValue> values = result.values();
        assertEquals(1, values.size());
        assertEquals("secret1", values.getFirst().name());

        List<SecretsManagerService.BatchGetSecretValueError> errors = result.errors();
        assertEquals(1, errors.size());
        assertEquals("non-existent", errors.getFirst().secretId());
        assertEquals("ResourceNotFoundException", errors.getFirst().errorCode());
        assertEquals("Secrets Manager can't find the specified secret.", errors.getFirst().message());
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

    /**
     * A secret name may contain colons, and the partial-ARN fallback used to preserve them by
     * splitting the ARN tail with an explicit limit. Reading the name off the parsed resource has
     * to keep that property: strip only the leading "secret:", never split again.
     */
    @Test
    void getSecretValueByPartialArnWithColonsInNameSucceeds() {
        Secret secret = service.createSecret("team:app:db", "colon-pass", null, null, null, null, REGION);
        String partialArn = secret.getArn().substring(0, secret.getArn().length() - 7);

        SecretVersion version = service.getSecretValue(partialArn, null, null, REGION);
        assertEquals("colon-pass", version.getSecretString());
    }

    /**
     * The partial-ARN fallback matched a literal "arn:aws:secretsmanager:", so outside the
     * commercial partition a secret's own ARN, minus the random suffix AWS lets clients omit,
     * resolved to nothing. Round trip through the ARN the emulator itself minted.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "us-east-1,      arn:aws:secretsmanager:",
            "us-gov-west-1,  arn:aws-us-gov:secretsmanager:",
            "cn-north-1,     arn:aws-cn:secretsmanager:"})
    void getSecretValueByPartialArnSucceedsInAnyPartition(String region, String expectedArnPrefix) {
        Secret secret = service.createSecret("p-secret", "value", null, null, null, null, region);

        assertTrue(secret.getArn().startsWith(expectedArnPrefix), "minted ARN was " + secret.getArn());

        String partialArn = secret.getArn().substring(0, secret.getArn().length() - 7);
        assertEquals("value", service.getSecretValue(partialArn, null, null, region).getSecretString());
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
        service.putSecretValue("my-secret", "v2", null, null, REGION, null);

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
        service.putSecretValue("my-secret", "v2", null, null, REGION, List.of("AWSCURRENT", "MYSTAGE"));

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
        service.putSecretValue("my-secret", "v2", null, null, REGION, null);

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
        service.putSecretValue("my-secret", "v2", null, null, REGION, null);
        service.putSecretValue("my-secret", "v3", null, null, REGION, null);

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
        service.putSecretValue("my-secret", "v2", null, null, REGION, null);
        service.putSecretValue("my-secret", "v3", null, null, REGION, null);

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
    void rotateSecretRemovesAwsCurrentOnlyRemovesLabel() {
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

    @Test
    void rotateSecretFailsEarly() throws Exception {
        io.github.hectorvent.floci.services.lambda.LambdaService mockLambda = org.mockito.Mockito.mock(io.github.hectorvent.floci.services.lambda.LambdaService.class);

        io.github.hectorvent.floci.services.lambda.model.InvokeResult successResult = new io.github.hectorvent.floci.services.lambda.model.InvokeResult();
        io.github.hectorvent.floci.services.lambda.model.InvokeResult errorResult = new io.github.hectorvent.floci.services.lambda.model.InvokeResult();
        errorResult.setFunctionError("Unhandled");

        // Fail on second invocation (setSecret)
        org.mockito.Mockito.when(mockLambda.invoke(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.any(byte[].class), org.mockito.Mockito.any()))
                .thenReturn(successResult)
                .thenReturn(errorResult);

        SecretsManagerService svc = new SecretsManagerService(
                new InMemoryStorage<String, Secret>(), 30,
                new io.github.hectorvent.floci.core.common.RegionResolver("us-east-1", "000000000000"),
                mockLambda, new com.fasterxml.jackson.databind.ObjectMapper());

        svc.createSecret("my-secret", "value", null, null, null, null, REGION);
        Secret.RotationRules rules = new Secret.RotationRules(30, null, null);

        svc.rotateSecret("my-secret", "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                "arn:aws:lambda:us-east-1:000000000000:function:rotate",
                rules, true, REGION);

        // Verify lambda invoked 2 times only (createSecret, setSecret)
        org.mockito.Mockito.verify(mockLambda, org.mockito.Mockito.timeout(5000).times(2))
                .invoke(org.mockito.Mockito.eq(REGION),
                        org.mockito.Mockito.eq("arn:aws:lambda:us-east-1:000000000000:function:rotate"),
                        org.mockito.Mockito.any(byte[].class),
                        org.mockito.Mockito.eq(io.github.hectorvent.floci.services.lambda.model.InvocationType.RequestResponse));
    }

    @Test
    void rotateSecret_actuallyChangesTheStoredSecretValue() throws Exception {
        io.github.hectorvent.floci.services.lambda.LambdaService mockLambda = org.mockito.Mockito.mock(io.github.hectorvent.floci.services.lambda.LambdaService.class);
        String lambdaArn = "arn:aws:lambda:us-east-1:000000000000:function:rotate";

        SecretsManagerService svc = new SecretsManagerService(
                new InMemoryStorage<String, Secret>(), 30,
                new io.github.hectorvent.floci.core.common.RegionResolver("us-east-1", "000000000000"),
                mockLambda, new com.fasterxml.jackson.databind.ObjectMapper());

        svc.createSecret("my-secret", "old-value", null, null, null, null, REGION);

        org.mockito.Mockito.when(mockLambda.invoke(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.any(byte[].class), org.mockito.Mockito.any()))
                .thenAnswer(invocation -> {
                    byte[] payloadBytes = invocation.getArgument(2);
                    com.fasterxml.jackson.databind.JsonNode payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payloadBytes);
                    String step = payload.get("Step").asText();
                    String token = payload.get("ClientRequestToken").asText();

                    switch (step) {
                        case "createSecret" ->
                                svc.putSecretValue("my-secret", "new-rotated-value", null, token, REGION, List.of("AWSPENDING"));
                        case "finishSecret" ->
                                svc.updateSecretVersionStage("my-secret", token,
                                        svc.getSecretValue("my-secret", null, "AWSCURRENT", REGION).getVersionId(),
                                        "AWSCURRENT", REGION);
                        default -> { /* setSecret, testSecret: no-op for this test */ }
                    }
                    io.github.hectorvent.floci.services.lambda.model.InvokeResult ok = new io.github.hectorvent.floci.services.lambda.model.InvokeResult();
                    ok.setStatusCode(200);
                    return ok;
                });

        svc.rotateSecret("my-secret", "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                lambdaArn, null, true, REGION);

        long end = System.currentTimeMillis() + 2000;
        boolean passed = false;
        while (System.currentTimeMillis() < end) {
            try {
                SecretVersion current = svc.getSecretValue("my-secret", null, "AWSCURRENT", REGION);
                assertEquals("new-rotated-value", current.getSecretString());
                passed = true;
                break;
            } catch (Throwable t) {
                Thread.sleep(100);
            }
        }
        assertTrue(passed, "Failed to wait for rotation completion");
    }

    @Test
    void rotateSecret_previousRotationInProgressThrows() throws Exception {
        io.github.hectorvent.floci.services.lambda.LambdaService mockLambda = org.mockito.Mockito.mock(io.github.hectorvent.floci.services.lambda.LambdaService.class);
        String lambdaArn = "arn:aws:lambda:us-east-1:000000000000:function:rotate";

        SecretsManagerService svc = new SecretsManagerService(
                new InMemoryStorage<String, Secret>(), 30,
                new io.github.hectorvent.floci.core.common.RegionResolver("us-east-1", "000000000000"),
                mockLambda, new com.fasterxml.jackson.databind.ObjectMapper());

        svc.createSecret("my-secret", "old-value", null, null, null, null, REGION);

        // Put an AWSPENDING version to simulate an ongoing rotation
        svc.putSecretValue("my-secret", "pending-value", null, "a1b2c3d4-e5f6-7890-abcd-ef1234567890", REGION, List.of("AWSPENDING"));

        AwsException ex = assertThrows(AwsException.class, () ->
            svc.rotateSecret("my-secret", "b1b2c3d4-e5f6-7890-abcd-ef1234567890",
                    lambdaArn, null, true, REGION)
        );
        assertEquals("InvalidRequestException", ex.getErrorCode());
    }

    @Test
    void rotateSecret_concurrentCallsLockingWorks() throws Exception {
        io.github.hectorvent.floci.services.lambda.LambdaService mockLambda = org.mockito.Mockito.mock(io.github.hectorvent.floci.services.lambda.LambdaService.class);
        String lambdaArn = "arn:aws:lambda:us-east-1:000000000000:function:rotate";

        SecretsManagerService svc = new SecretsManagerService(
                new InMemoryStorage<String, Secret>(), 30,
                new io.github.hectorvent.floci.core.common.RegionResolver("us-east-1", "000000000000"),
                mockLambda, new com.fasterxml.jackson.databind.ObjectMapper());

        svc.createSecret("my-secret", "old-value", null, null, null, null, REGION);
        
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        
        org.mockito.Mockito.when(mockLambda.invoke(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.any(byte[].class), org.mockito.Mockito.any()))
                .thenAnswer(invocation -> {
                    // Create secret and hold the lock on executor to simulate delay
                    byte[] payloadBytes = invocation.getArgument(2);
                    com.fasterxml.jackson.databind.JsonNode payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payloadBytes);
                    String step = payload.get("Step").asText();
                    String token = payload.get("ClientRequestToken").asText();

                    if ("createSecret".equals(step)) {
                        svc.putSecretValue("my-secret", "new-rotated-value", null, token, REGION, List.of("AWSPENDING"));
                        latch.await();
                    }

                    io.github.hectorvent.floci.services.lambda.model.InvokeResult ok = new io.github.hectorvent.floci.services.lambda.model.InvokeResult();
                    ok.setStatusCode(200);
                    return ok;
                });

        // Trigger first rotation
        svc.rotateSecret("my-secret", "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                lambdaArn, null, true, REGION);
                
        // Wait until AWSPENDING is set by the background thread
        long end = System.currentTimeMillis() + 2000;
        boolean pendingFound = false;
        while (System.currentTimeMillis() < end) {
            try {
                svc.getSecretValue("my-secret", null, "AWSPENDING", REGION);
                pendingFound = true;
                break;
            } catch (Exception e) {
                Thread.sleep(100);
            }
        }
        assertTrue(pendingFound, "AWSPENDING version was not created in time");

        // Trigger second rotation while the first one is running and holds AWSPENDING
        AwsException ex = assertThrows(AwsException.class, () ->
            svc.rotateSecret("my-secret", "b1b2c3d4-e5f6-7890-abcd-ef1234567890",
                    lambdaArn, null, true, REGION)
        );
        assertEquals("InvalidRequestException", ex.getErrorCode());
        latch.countDown();
    }

    // ─── CancelRotateSecret ────────────────────────────────────────────────────

    private static final String ROTATION_LAMBDA_ARN =
            "arn:aws:lambda:us-east-1:000000000000:function:rotate";
    private static final String ROTATION_TOKEN = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";

    /**
     * Turns rotation on without leaving a background rotation racing the assertions: this
     * service has no LambdaService, and with no AWSPENDING staged the {@code rotateImmediately
     * = false} task only invokes a Lambda that isn't there, so it mutates nothing.
     */
    private void enableRotation(String name) {
        service.rotateSecret(name, ROTATION_TOKEN, ROTATION_LAMBDA_ARN,
                new Secret.RotationRules(14, null, null), false, REGION);
    }

    @Test
    void cancelRotateSecretTurnsRotationOff() {
        service.createSecret("rotating", "v1", null, null, null, null, REGION);
        enableRotation("rotating");
        assertTrue(service.describeSecret("rotating", REGION).isRotationEnabled());

        SecretsManagerService.CancelRotationResult result =
                service.cancelRotateSecret("rotating", REGION);

        assertFalse(result.secret().isRotationEnabled());
        assertFalse(service.describeSecret("rotating", REGION).isRotationEnabled());
    }

    @Test
    void cancelRotateSecretReportsNoPendingVersionWhenNoneIsStaged() {
        service.createSecret("rotating", "v1", null, null, null, null, REGION);
        enableRotation("rotating");

        assertNull(service.cancelRotateSecret("rotating", REGION).pendingVersionId());
    }

    @Test
    void cancelRotateSecretReportsTheStagedPendingVersion() {
        service.createSecret("rotating", "v1", null, null, null, null, REGION);
        service.putSecretValue("rotating", "half-rotated", null,
                ROTATION_TOKEN, REGION, List.of("AWSPENDING"));

        assertEquals(ROTATION_TOKEN,
                service.cancelRotateSecret("rotating", REGION).pendingVersionId());
    }

    @Test
    void cancelRotateSecretLeavesAwsPendingAttachedForTheCallerToCleanUp() {
        // AWS explicitly does NOT unstage the version: "You might need to remove the staging
        // label AWSPENDING from the partially created version."
        service.createSecret("rotating", "v1", null, null, null, null, REGION);
        service.putSecretValue("rotating", "half-rotated", null,
                ROTATION_TOKEN, REGION, List.of("AWSPENDING"));

        service.cancelRotateSecret("rotating", REGION);

        SecretVersion pending = service.getSecretValue("rotating", null, "AWSPENDING", REGION);
        assertEquals(ROTATION_TOKEN, pending.getVersionId());
    }

    @Test
    void cancelRotateSecretOnASecretMarkedForDeletionThrows() {
        service.createSecret("doomed", "v1", null, null, null, null, REGION);
        service.deleteSecret("doomed", 7, false, REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                service.cancelRotateSecret("doomed", REGION));
        assertEquals("InvalidRequestException", ex.getErrorCode());
    }

    // ─── DeleteSecret recovery-window validation ───────────────────────────────

    @Test
    void deleteSecretRejectsARecoveryWindowOutsideSevenToThirtyDays() {
        service.createSecret("windowed", "v1", null, null, null, null, REGION);

        for (int window : new int[] { -1, 0, 6, 31, 365 }) {
            AwsException ex = assertThrows(AwsException.class, () ->
                    service.deleteSecret("windowed", window, false, REGION));
            assertEquals("InvalidParameterException", ex.getErrorCode(),
                    "window " + window + " should be rejected");
        }
    }

    @Test
    void deleteSecretAcceptsTheBoundaryRecoveryWindows() {
        for (int window : new int[] { 7, 30 }) {
            String name = "boundary-" + window;
            service.createSecret(name, "v1", null, null, null, null, REGION);
            assertNotNull(service.deleteSecret(name, window, false, REGION).getDeletedDate());
        }
    }

    @Test
    void deleteSecretRejectsForceDeleteCombinedWithARecoveryWindow() {
        service.createSecret("conflicted", "v1", null, null, null, null, REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                service.deleteSecret("conflicted", 7, true, REGION));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }

    // ─── CreateSecret ClientRequestToken ───────────────────────────────────────

    @Test
    void createSecretUsesTheClientRequestTokenAsTheVersionId() {
        Secret secret = service.createSecret("tokened", "v1", null, null, null, null,
                null, ROTATION_TOKEN, REGION);

        assertEquals(ROTATION_TOKEN, secret.getCurrentVersionId());
        assertEquals("v1", service.getSecretValue("tokened", ROTATION_TOKEN, null, REGION)
                .getSecretString());
    }

    @Test
    void createSecretRejectsAClientRequestTokenOutsideThe32To64Range() {
        AwsException ex = assertThrows(AwsException.class, () ->
                service.createSecret("bad-token", "v1", null, null, null, null,
                        null, "too-short", REGION));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }

    // ─── Service-managed secrets stay manageable by their owning service ───────

    @Test
    void theOwningServiceCanStillUpdateItsOwnManagedSecret() {
        // RDS re-encrypts its managed master user secret in place on ModifyDBCluster by calling
        // updateSecret. The "managed by another service" guard belongs on the wire API, which
        // outside callers reach; applying it here would break RDS's own rekey path.
        service.createSecret("rds!db-1", "v1", null, null, "old-key", null, "rds", REGION);

        Secret rekeyed = service.updateSecret("rds!db-1", null, "new-key", REGION);

        assertEquals("new-key", rekeyed.getKmsKeyId());
    }

    @Test
    void theOwningServiceCanStillCancelRotationOnItsOwnManagedSecret() {
        service.createSecret("rds!db-2", "v1", null, null, null, null, "rds", REGION);

        assertFalse(service.cancelRotateSecret("rds!db-2", REGION).secret().isRotationEnabled());
    }

    @Test
    void putSecretValueRejectsBothSecretStringAndSecretBinary() {
        // AWS: "Either SecretString or SecretBinary must have a value, but not both."
        service.createSecret("both-values", "v1", null, null, null, null, REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                service.putSecretValue("both-values", "text", "YmluYXJ5", null, REGION, null));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }

    @Test
    void putSecretValueStillAcceptsTheEmptyPlaceholderARotationStages() {
        // Both null is how a rotation registers its AWSPENDING version before it has a value.
        service.createSecret("placeholder", "v1", null, null, null, null, REGION);

        SecretVersion staged = service.putSecretValue("placeholder", null, null,
                "a1b2c3d4-e5f6-7890-abcd-ef1234567890", REGION, List.of("AWSPENDING"));

        assertNull(staged.getSecretString());
        assertTrue(staged.getVersionStages().contains("AWSPENDING"));
    }

    // ─── KMS key validation ────────────────────────────────────────────────────

    /**
     * KmsService is a cross-service collaborator whose real test constructors are package-private
     * to {@code services.kms}, so it is mocked here the same way the rotation tests mock
     * LambdaService.
     */
    private SecretsManagerService serviceWithKms(io.github.hectorvent.floci.services.kms.KmsService kms) {
        return new SecretsManagerService(new InMemoryStorage<>(), 30,
                new RegionResolver(REGION, "000000000000"), null, new com.fasterxml.jackson.databind.ObjectMapper(), kms);
    }

    private static io.github.hectorvent.floci.services.kms.model.KmsKey kmsKey(boolean enabled, String state) {
        io.github.hectorvent.floci.services.kms.model.KmsKey key =
                new io.github.hectorvent.floci.services.kms.model.KmsKey();
        key.setKeyId("abcd1234-ef56-7890-abcd-ef1234567890");
        key.setEnabled(enabled);
        key.setKeyState(state);
        return key;
    }

    @Test
    void createSecretRejectsAKmsKeyThatDoesNotExist() {
        io.github.hectorvent.floci.services.kms.KmsService kms =
                org.mockito.Mockito.mock(io.github.hectorvent.floci.services.kms.KmsService.class);
        org.mockito.Mockito.when(kms.describeKey(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new AwsException("NotFoundException", "Key not found", 404));

        AwsException ex = assertThrows(AwsException.class, () -> serviceWithKms(kms)
                .createSecret("kms-secret", "v1", null, null, "missing-key", null, REGION));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }

    @Test
    void createSecretRejectsADisabledKmsKey() {
        io.github.hectorvent.floci.services.kms.KmsService kms =
                org.mockito.Mockito.mock(io.github.hectorvent.floci.services.kms.KmsService.class);
        org.mockito.Mockito.when(kms.describeKey(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(kmsKey(false, "Disabled"));

        AwsException ex = assertThrows(AwsException.class, () -> serviceWithKms(kms)
                .createSecret("kms-secret", "v1", null, null, "disabled-key", null, REGION));
        assertEquals("EncryptionFailure", ex.getErrorCode());
    }

    @Test
    void createSecretRejectsAKmsKeyPendingDeletion() {
        io.github.hectorvent.floci.services.kms.KmsService kms =
                org.mockito.Mockito.mock(io.github.hectorvent.floci.services.kms.KmsService.class);
        org.mockito.Mockito.when(kms.describeKey(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(kmsKey(true, "PendingDeletion"));

        AwsException ex = assertThrows(AwsException.class, () -> serviceWithKms(kms)
                .createSecret("kms-secret", "v1", null, null, "doomed-key", null, REGION));
        assertEquals("EncryptionFailure", ex.getErrorCode());
    }

    @Test
    void createSecretAcceptsAnEnabledKmsKey() {
        io.github.hectorvent.floci.services.kms.KmsService kms =
                org.mockito.Mockito.mock(io.github.hectorvent.floci.services.kms.KmsService.class);
        org.mockito.Mockito.when(kms.describeKey(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(kmsKey(true, "Enabled"));

        Secret secret = serviceWithKms(kms)
                .createSecret("kms-secret", "v1", null, null, "good-key", null, REGION);
        assertEquals("good-key", secret.getKmsKeyId());
    }

    @Test
    void theAwsManagedDefaultKeyIsNeverLookedUp() {
        // AWS creates aws/secretsmanager on demand, so it always resolves. Looking it up would
        // fail on a fresh emulator where nobody has created it.
        io.github.hectorvent.floci.services.kms.KmsService kms =
                org.mockito.Mockito.mock(io.github.hectorvent.floci.services.kms.KmsService.class);
        SecretsManagerService kmsAware = serviceWithKms(kms);

        kmsAware.createSecret("default-key", "v1", null, null, "alias/aws/secretsmanager", null, REGION);
        kmsAware.createSecret("bare-default", "v1", null, null, "aws/secretsmanager", null, REGION);

        org.mockito.Mockito.verify(kms, org.mockito.Mockito.never())
                .describeKey(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void updateSecretRejectsAKmsKeyThatDoesNotExist() {
        io.github.hectorvent.floci.services.kms.KmsService kms =
                org.mockito.Mockito.mock(io.github.hectorvent.floci.services.kms.KmsService.class);
        SecretsManagerService kmsAware = serviceWithKms(kms);
        kmsAware.createSecret("kms-secret", "v1", null, null, null, null, REGION);

        org.mockito.Mockito.when(kms.describeKey(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new AwsException("NotFoundException", "Key not found", 404));

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsAware.updateSecret("kms-secret", null, "missing-key", REGION));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }

    @Test
    void aSecretWithoutAKmsKeyNeedsNoLookup() {
        io.github.hectorvent.floci.services.kms.KmsService kms =
                org.mockito.Mockito.mock(io.github.hectorvent.floci.services.kms.KmsService.class);
        serviceWithKms(kms).createSecret("no-key", "v1", null, null, null, null, REGION);

        org.mockito.Mockito.verify(kms, org.mockito.Mockito.never())
                .describeKey(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    // ─── Replication ───────────────────────────────────────────────────────────

    private static final String REPLICA_REGION = "eu-west-1";

    private Secret replicate(String name, String... regions) {
        List<SecretsManagerService.ReplicaRegion> targets = new java.util.ArrayList<>();
        for (String r : regions) {
            targets.add(new SecretsManagerService.ReplicaRegion(r, null));
        }
        return service.replicateSecretToRegions(name, targets, false, REGION);
    }

    @Test
    void replicateSecretCopiesTheValueIntoTheReplicaRegion() {
        service.createSecret("multi", "v1", null, null, null, null, REGION);

        Secret primary = replicate("multi", REPLICA_REGION);

        assertEquals(1, primary.getReplicationStatus().size());
        assertEquals(REPLICA_REGION, primary.getReplicationStatus().getFirst().region());
        assertEquals("InSync", primary.getReplicationStatus().getFirst().status());
        assertEquals("v1", service.getSecretValue("multi", null, null, REPLICA_REGION).getSecretString());
    }

    @Test
    void replicaArnMatchesThePrimaryWithTheRegionSwapped() {
        // AWS: "The replica ARN is the same as the original primary secret ARN except the Region."
        service.createSecret("multi", "v1", null, null, null, null, REGION);
        Secret primary = replicate("multi", REPLICA_REGION);
        Secret replica = service.describeSecret("multi", REPLICA_REGION);

        assertEquals(primary.getArn().replace(REGION, REPLICA_REGION), replica.getArn());
    }

    @Test
    void replicaTracksLaterWritesToThePrimary() {
        service.createSecret("multi", "v1", null, null, null, null, REGION);
        replicate("multi", REPLICA_REGION);

        service.putSecretValue("multi", "v2", null, null, REGION, null);

        assertEquals("v2", service.getSecretValue("multi", null, null, REPLICA_REGION).getSecretString());
    }

    @Test
    void replicaReportsThePrimaryRegionAndThePrimaryReportsItsOwn() {
        service.createSecret("multi", "v1", null, null, null, null, REGION);
        replicate("multi", REPLICA_REGION);

        assertEquals(REGION, service.describeSecret("multi", REPLICA_REGION).getPrimaryRegion());
        assertNull(service.describeSecret("multi", REGION).getPrimaryRegion());
    }

    @Test
    void replicatingOntoAnExistingNameFailsThatRegionWithoutForce() {
        service.createSecret("multi", "v1", null, null, null, null, REGION);
        service.createSecret("multi", "squatter", null, null, null, null, REPLICA_REGION);

        Secret primary = replicate("multi", REPLICA_REGION);

        assertEquals("Failed", primary.getReplicationStatus().getFirst().status());
        // The pre-existing secret is left untouched.
        assertEquals("squatter", service.getSecretValue("multi", null, null, REPLICA_REGION).getSecretString());
    }

    @Test
    void replicatingOntoAnExistingNameOverwritesItWithForce() {
        service.createSecret("multi", "v1", null, null, null, null, REGION);
        service.createSecret("multi", "squatter", null, null, null, null, REPLICA_REGION);

        Secret primary = service.replicateSecretToRegions("multi",
                List.of(new SecretsManagerService.ReplicaRegion(REPLICA_REGION, null)), true, REGION);

        assertEquals("InSync", primary.getReplicationStatus().getFirst().status());
        assertEquals("v1", service.getSecretValue("multi", null, null, REPLICA_REGION).getSecretString());
    }

    @Test
    void writingToAReplicaIsRejected() {
        service.createSecret("multi", "v1", null, null, null, null, REGION);
        replicate("multi", REPLICA_REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                service.putSecretValue("multi", "sneaky", null, null, REPLICA_REGION, null));
        assertEquals("InvalidRequestException", ex.getErrorCode());
    }

    @Test
    void deletingAPrimaryThatStillHasReplicasIsRejected() {
        service.createSecret("multi", "v1", null, null, null, null, REGION);
        replicate("multi", REPLICA_REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                service.deleteSecret("multi", null, false, REGION));
        assertEquals("InvalidRequestException", ex.getErrorCode());
    }

    @Test
    void removingARegionDeletesTheReplicaImmediately() {
        service.createSecret("multi", "v1", null, null, null, null, REGION);
        replicate("multi", REPLICA_REGION);

        Secret primary = service.removeRegionsFromReplication("multi", List.of(REPLICA_REGION), REGION);

        assertTrue(primary.getReplicationStatus().isEmpty());
        assertThrows(AwsException.class, () -> service.describeSecret("multi", REPLICA_REGION));
        // With the last replica gone the primary can be deleted again.
        assertNotNull(service.deleteSecret("multi", null, false, REGION).getDeletedDate());
    }

    @Test
    void stopReplicationPromotesTheReplicaToAStandalonePrimary() {
        service.createSecret("multi", "v1", null, null, null, null, REGION);
        replicate("multi", REPLICA_REGION);

        Secret promoted = service.stopReplicationToReplica("multi", REPLICA_REGION);

        assertNull(promoted.getPrimaryRegion());
        // The old primary no longer lists it, and the promoted copy now accepts writes.
        assertTrue(service.describeSecret("multi", REGION).getReplicationStatus().isEmpty());
        assertEquals("now-mine", service.putSecretValue("multi", "now-mine", null, null,
                REPLICA_REGION, null).getSecretString());
        // The promotion does not disturb the original primary's value.
        assertEquals("v1", service.getSecretValue("multi", null, null, REGION).getSecretString());
    }

    @Test
    void stopReplicationOnAPrimaryIsRejected() {
        service.createSecret("multi", "v1", null, null, null, null, REGION);
        replicate("multi", REPLICA_REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                service.stopReplicationToReplica("multi", REGION));
        assertEquals("InvalidRequestException", ex.getErrorCode());
    }

    // ─── ListSecrets planned deletion ──────────────────────────────────────────

    @Test
    void listSecretsCanIncludeSecretsPendingDeletion() {
        service.createSecret("active", "v1", null, null, null, null, REGION);
        service.createSecret("doomed", "v2", null, null, null, null, REGION);
        service.deleteSecret("doomed", 7, false, REGION);

        assertEquals(1, service.listSecrets(REGION, null, false).size());
        assertEquals(2, service.listSecrets(REGION, null, true).size());
    }
}
