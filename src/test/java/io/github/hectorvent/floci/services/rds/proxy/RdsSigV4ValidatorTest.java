package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.testutil.IamServiceTestHelper;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdsSigV4ValidatorTest {

    private static final String ECS_ACCESS_KEY_ID = "ASIAECS" + "R".repeat(13);
    private static final String ECS_SECRET_ACCESS_KEY = "ecs-rds-secret";
    private static final String ECS_SESSION_TOKEN = "ecs-rds-session+token/==";
    private static final String ECS_TASK_ARN =
            "arn:aws:ecs:us-east-1:000000000000:task/default/rds-validator";
    private static final String ECS_CREDENTIAL_PATH = "/v2/credentials/" + "R".repeat(48);

    @Test
    void validateAcceptsTokenSignedByStandardSigV4() throws Exception {
        String accessKeyId = "AKIAORACLETEST";
        String secretAccessKey = "oracle-secret-key-value";
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey(accessKeyId, secretAccessKey);

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);

        String token = SigV4TokenTestHelper.createRdsToken(
                "db.oracle-test.local",
                5432,
                "testuser",
                accessKeyId,
                secretAccessKey,
                Instant.now().minusSeconds(60),
                900
        );

        assertTrue(validator.validate(token, "testuser"),
                "Validator must accept a well-formed SigV4 RDS authentication token");
    }

    @Test
    void validateAcceptsTokenSignedWithHostAndPort() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                "AKIDRDS",
                "secret-rds",
                Instant.now().minusSeconds(60),
                900
        );

        assertTrue(validator.validate(token, "admin"));
    }

    @Test
    void validateRejectsTokenWhenSignedForHostWithoutPort() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String validToken = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                "AKIDRDS",
                "secret-rds",
                Instant.now().minusSeconds(60),
                900
        );
        String brokenToken = validToken.replace("db.example.local:5432/?", "db.example.local/?");

        assertFalse(validator.validate(brokenToken, "admin"));
    }

    @Test
    void validateRejectsExpiredToken() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                "AKIDRDS",
                "secret-rds",
                Instant.now().minusSeconds(1200),
                900
        );

        assertFalse(validator.validate(token, "admin"));
    }

    @Test
    void validateRejectsTamperedSignature() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String validToken = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                "AKIDRDS",
                "secret-rds",
                Instant.now().minusSeconds(60),
                900
        );
        String tamperedToken = validToken.replace("DBUser=admin", "DBUser=attacker");

        assertFalse(validator.validate(tamperedToken, "admin"));
    }

    @Test
    void validateRejectsTokenWithUnknownAccessKey() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                "AKIDUNKNOWN",
                "wrong-secret",
                Instant.now().minusSeconds(60),
                900
        );

        assertFalse(validator.validate(token, "admin"));
    }

    /**
     * A bare 12-digit access key ID that isn't registered in IAM must be rejected like any
     * other unknown key, not resolved to the well-known "test" secret. That fallback would let
     * a client forge an IAM-auth token for any account number, signed with the public "test"
     * secret, and authenticate as any matching database user — a bypass of RDS IAM
     * authentication, which is only consulted when a caller has explicitly opted into it.
     */
    @Test
    void validateRejectsUnregisteredNumericAccessKeyId() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                "123456789012",
                "test",
                Instant.now().minusSeconds(60),
                900
        );

        assertFalse(validator.validate(token, "admin"));
    }

    @Test
    void validateRejectsTokenMissingDbUser() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String validToken = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                "AKIDRDS",
                "secret-rds",
                Instant.now().minusSeconds(60),
                900
        );
        String withoutDbUser = validToken.replaceFirst("DBUser=admin&", "");

        assertFalse(validator.validate(withoutDbUser, "admin"));
    }

    @Test
    void validateRejectsTokenForWrongUser() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                "AKIDRDS",
                "secret-rds",
                Instant.now().minusSeconds(60),
                900
        );

        assertFalse(validator.validate(token, "attacker"),
                "Token signed for 'admin' must be rejected when client connects as 'attacker'");
    }

    @Test
    void validateAcceptsTokenWhenClientUsernameIsNull() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                "AKIDRDS",
                "secret-rds",
                Instant.now().minusSeconds(60),
                900
        );

        assertTrue(validator.validate(token, null),
                "Null clientUsername should skip the identity check (backwards compat)");
    }

    @Test
    void validateAcceptsTokenWithUrlEncodedDbUser() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        // Username with characters that require URL encoding exercises the
        // encoding path independently of the validator's decode logic
        String token = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "db+admin@example.com",
                "AKIDRDS",
                "secret-rds",
                Instant.now().minusSeconds(60),
                900
        );

        assertTrue(validator.validate(token, "db+admin@example.com"));
    }

    @Test
    void validateRejectsTokenWithWrongRegion() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                "AKIDRDS",
                "secret-rds",
                Instant.now().minusSeconds(60),
                900
        );
        // Tampering with the region in the credential scope invalidates the signature
        String tamperedToken = token.replace("us-east-1", "eu-west-1");

        assertFalse(validator.validate(tamperedToken, "admin"));
    }

    @Test
    void validateRejectsTokenMissingSignatureParameter() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String validToken = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                "AKIDRDS",
                "secret-rds",
                Instant.now().minusSeconds(60),
                900
        );
        String withoutSignature = validToken.replaceFirst("&X-Amz-Signature=[0-9a-f]+", "");

        assertFalse(validator.validate(withoutSignature, "admin"));
    }

    @Test
    void validateAcceptsTokenSignedWithStsSessionCredentials() throws Exception {
        String accessKeyId = "ASIAIOSFODNN7EXAMPLE";
        String secretAccessKey = "sts-generated-secret-key";
        IamService iamService = IamServiceTestHelper.iamServiceWithSessionCredential(accessKeyId, secretAccessKey);

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                accessKeyId,
                secretAccessKey,
                Instant.now().minusSeconds(60),
                900
        );

        assertTrue(validator.validate(token, "admin"),
                "Validator must accept RDS IAM tokens signed with STS session credentials (ASIA… keys)");
    }

    @Test
    void validateAcceptsTokenSignedWithActiveEcsTaskRoleAndIssuedSessionToken() throws Exception {
        IamService iamService = ecsIamService(Instant.now().plusSeconds(3600));
        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);

        String token = createEcsRdsToken(ECS_SESSION_TOKEN);

        assertTrue(validator.validate(token, "admin"));
    }

    @Test
    void rejectsOtherwiseValidEcsTokensSignedForAnotherService() throws Exception {
        RdsSigV4Validator validator = new RdsSigV4Validator(ecsIamService(Instant.now().plusSeconds(3600)));
        for (String service : java.util.List.of("sts", "s3", "elasticache", "memorydb")) {
            assertFalse(validator.validate(createEcsRdsToken(ECS_SESSION_TOKEN, service), "admin"), service);
        }
    }

    @Test
    void validateRejectsEcsTaskRoleTokenWithoutSessionToken() throws Exception {
        IamService iamService = ecsIamService(Instant.now().plusSeconds(3600));
        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);

        String token = createEcsRdsToken(null);

        assertFalse(validator.validate(token, "admin"));
    }

    @Test
    void validateRejectsEcsTaskRoleTokenWithWrongSessionToken() throws Exception {
        IamService iamService = ecsIamService(Instant.now().plusSeconds(3600));
        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);

        String token = createEcsRdsToken("forged-session-token");

        assertFalse(validator.validate(token, "admin"));
    }

    @Test
    void validateRejectsExpiredEcsTaskRoleCredentialEvenWithIssuedSessionToken() throws Exception {
        IamService iamService = ecsIamService(Instant.now().plusSeconds(3600));
        iamService.resolveEcsTaskRoleSession(ECS_ACCESS_KEY_ID).orElseThrow().setExpiration(Instant.EPOCH);
        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);

        String token = createEcsRdsToken(ECS_SESSION_TOKEN);

        assertFalse(validator.validate(token, "admin"));
    }

    @Test
    void validateRejectsRevokedEcsTaskRoleCredentialEvenWithIssuedSessionToken() throws Exception {
        IamService iamService = ecsIamService(Instant.now().plusSeconds(3600));
        iamService.revokeEcsTaskRoleSession(ECS_TASK_ARN, ECS_ACCESS_KEY_ID);
        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);

        String token = createEcsRdsToken(ECS_SESSION_TOKEN);

        assertFalse(validator.validate(token, "admin"));
    }

    @Test
    void validateRejectsStsTokenWithWrongSecret() throws Exception {
        String accessKeyId = "ASIAIOSFODNN7EXAMPLE";
        IamService iamService = IamServiceTestHelper.iamServiceWithSessionCredential(accessKeyId, "correct-secret");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                accessKeyId,
                "wrong-secret",
                Instant.now().minusSeconds(60),
                900
        );

        assertFalse(validator.validate(token, "admin"),
                "Validator must reject STS token signed with wrong secret");
    }

    @Test
    void validateRejectsTokenSelfSignedWithUnregisteredAccessKeyAsSecret() throws Exception {
        // Only "AKIDRDS" is registered; the attacker picks an arbitrary, unregistered
        // access key and signs using that same access key as the secret. If the validator
        // ever falls back to accessKeyId as the signing secret for unknown keys, this forged
        // token would be accepted for any DB user the attacker chooses.
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String forgedAccessKeyId = "AKIDFORGEDBYATTACKER";
        String token = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                forgedAccessKeyId,
                forgedAccessKeyId,
                Instant.now().minusSeconds(60),
                900
        );

        assertFalse(validator.validate(token, "admin"),
                "A token self-signed with secret == accessKeyId for an unregistered access key "
                        + "must never validate; unregistered keys must fail closed");
    }

    @Test
    void validateAcceptsWellKnownLocalDevCredentialEvenWhenNotRegisteredInIam() throws Exception {
        // AwsBasicCredentials.create("test", "test") is the default local-dev credential used
        // pervasively by SDK clients against this emulator (RDS compat tests generate real IAM
        // tokens with it). It must keep working even though it is never registered in
        // IamService -- the same "test"/"test" convenience already honored by
        // S3Service/PreSignedUrlFilter, carved out explicitly rather than via the removed
        // generic unregistered-key fallback.
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");

        RdsSigV4Validator validator = new RdsSigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createRdsToken(
                "db.example.local",
                5432,
                "admin",
                "test",
                "test",
                Instant.now().minusSeconds(60),
                900
        );

        assertTrue(validator.validate(token, "admin"),
                "The well-known \"test\"/\"test\" local-dev credential pair must still validate");
    }

    @Test
    void sanitizeForLogStripsControlCharacters() throws Exception {
        // A forged accessKeyId containing CR/LF must not be able to inject fake log lines into
        // the debug logs this validator writes.
        Method sanitizeForLog = RdsSigV4Validator.class.getDeclaredMethod("sanitizeForLog", String.class);
        sanitizeForLog.setAccessible(true);

        String malicious = "AKID\r\nINJECTEDLINE\r\n";
        String sanitized = (String) sanitizeForLog.invoke(null, malicious);

        assertEquals("AKIDINJECTEDLINE", sanitized);
    }

    private static IamService ecsIamService(Instant expiration) {
        try {
            Constructor<IamService> constructor = IamService.class.getDeclaredConstructor(
                    StorageBackend.class,
                    StorageBackend.class,
                    StorageBackend.class,
                    StorageBackend.class,
                    StorageBackend.class,
                    StorageBackend.class,
                    StorageBackend.class,
                    RegionResolver.class
            );
            constructor.setAccessible(true);

            StorageBackend<String, IamRole> roles = new InMemoryStorage<>();
            IamService iamService = constructor.newInstance(
                    new InMemoryStorage<>(),
                    new InMemoryStorage<>(),
                    roles,
                    new InMemoryStorage<>(),
                    new InMemoryStorage<>(),
                    new InMemoryStorage<>(),
                    new InMemoryStorage<>(),
                    new RegionResolver("us-east-1", "000000000000")
            );
            IamRole role = iamService.createRole("rds-validator", "/", "{}", null, 0, null);
            iamService.registerEcsTaskRoleSession(
                    ECS_TASK_ARN,
                    "000000000000",
                    ECS_ACCESS_KEY_ID,
                    ECS_SECRET_ACCESS_KEY,
                    ECS_SESSION_TOKEN,
                    role.getArn(),
                    expiration,
                    ECS_CREDENTIAL_PATH);
            return iamService;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to construct ECS IAM validator fixture", e);
        }
    }

    private static String createEcsRdsToken(String sessionToken) throws Exception {
        return createEcsRdsToken(sessionToken, "rds-db");
    }

    private static String createEcsRdsToken(String sessionToken, String service) throws Exception {
        Method signer = SigV4TokenTestHelper.class.getDeclaredMethod(
                "signToken",
                String.class,
                Integer.class,
                String.class,
                String.class,
                String.class,
                String.class,
                Instant.class,
                int.class,
                Map.class,
                Map.class);
        signer.setAccessible(true);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("Action", "connect");
        params.put("DBUser", "admin");
        if (sessionToken != null) {
            params.put("X-Amz-Security-Token", sessionToken);
        }
        return (String) signer.invoke(
                null,
                "db.example.local",
                Integer.valueOf(5432),
                ECS_ACCESS_KEY_ID,
                ECS_SECRET_ACCESS_KEY,
                "us-east-1",
                service,
                Instant.now().minusSeconds(60),
                900,
                params,
                Map.of("host", "db.example.local:5432"));
    }
}
