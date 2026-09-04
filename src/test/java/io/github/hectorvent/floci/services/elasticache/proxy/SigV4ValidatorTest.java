package io.github.hectorvent.floci.services.elasticache.proxy;

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

class SigV4ValidatorTest {

    private static final String ECS_ACCESS_KEY_ID = "ASIAECS" + "C".repeat(13);
    private static final String ECS_SECRET_ACCESS_KEY = "ecs-cache-secret";
    private static final String ECS_SESSION_TOKEN = "ecs-cache-session+token/==";
    private static final String ECS_TASK_ARN =
            "arn:aws:ecs:us-east-1:000000000000:task/default/cache-validator";
    private static final String ECS_CREDENTIAL_PATH = "/v2/credentials/" + "C".repeat(48);

    @Test
    void validateAcceptsTokenForMatchingReplicationGroup() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDCACHE", "secret-cache");

        SigV4Validator validator = new SigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createElastiCacheToken(
                "cache-cluster-01",
                "default",
                "AKIDCACHE",
                "secret-cache",
                Instant.now().minusSeconds(60),
                900
        );

        assertTrue(validator.validate(token, "cache-cluster-01", "default"));
        assertTrue(validator.validate(token, "CACHE-CLUSTER-01", "default"));
    }

    @Test
    void validateRejectsTokenForDifferentReplicationGroup() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDCACHE", "secret-cache");

        SigV4Validator validator = new SigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createElastiCacheToken(
                "cache-cluster-01",
                "default",
                "AKIDCACHE",
                "secret-cache",
                Instant.now().minusSeconds(60),
                900
        );

        assertFalse(validator.validate(token, "other-cluster", "default"));
    }

    @Test
    void validateRejectsTamperedSignature() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDCACHE", "secret-cache");

        SigV4Validator validator = new SigV4Validator(iamService);
        String validToken = SigV4TokenTestHelper.createElastiCacheToken(
                "cache-cluster-01",
                "default",
                "AKIDCACHE",
                "secret-cache",
                Instant.now().minusSeconds(60),
                900
        );
        String tamperedToken = validToken.replace("User=default", "User=other");

        assertFalse(validator.validate(tamperedToken, "cache-cluster-01", "default"));
    }

    @Test
    void validateAcceptsTokenWhenExpectedGroupIsNull() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDCACHE", "secret-cache");

        SigV4Validator validator = new SigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createElastiCacheToken(
                "cache-cluster-01",
                "default",
                "AKIDCACHE",
                "secret-cache",
                Instant.now().minusSeconds(60),
                900
        );

        assertTrue(validator.validate(token, null, "default"));
    }

    @Test
    void validateRejectsExpiredToken() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDCACHE", "secret-cache");

        SigV4Validator validator = new SigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createElastiCacheToken(
                "cache-cluster-01",
                "default",
                "AKIDCACHE",
                "secret-cache",
                Instant.now().minusSeconds(1200),
                900
        );

        assertFalse(validator.validate(token, "cache-cluster-01", "default"));
    }

    @Test
    void validateRejectsTokenWithUnknownAccessKey() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDCACHE", "secret-cache");

        SigV4Validator validator = new SigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createElastiCacheToken(
                "cache-cluster-01",
                "default",
                "AKIDUNKNOWN",
                "wrong-secret",
                Instant.now().minusSeconds(60),
                900
        );

        assertFalse(validator.validate(token, "cache-cluster-01", "default"));
    }

    /**
     * A bare 12-digit access key ID that isn't registered in IAM must be rejected like any
     * other unknown key, not resolved to the well-known "test" secret. That fallback would let
     * a client forge an IAM-auth token for any account number, signed with the public "test"
     * secret, and authenticate as any matching cache user — a bypass of ElastiCache IAM
     * authentication, which is only consulted when a caller has explicitly opted into it.
     */
    @Test
    void validateRejectsUnregisteredNumericAccessKeyId() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDCACHE", "secret-cache");

        SigV4Validator validator = new SigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createElastiCacheToken(
                "cache-cluster-01",
                "default",
                "123456789012",
                "test",
                Instant.now().minusSeconds(60),
                900
        );

        assertFalse(validator.validate(token, "cache-cluster-01", "default"));
    }

    @Test
    void validateRejectsTokenForWrongUser() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDCACHE", "secret-cache");

        SigV4Validator validator = new SigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createElastiCacheToken(
                "cache-cluster-01",
                "default",
                "AKIDCACHE",
                "secret-cache",
                Instant.now().minusSeconds(60),
                900
        );

        assertFalse(validator.validate(token, "cache-cluster-01", "attacker"),
                "Token signed for 'default' must be rejected when client authenticates as 'attacker'");
    }

    @Test
    void validateAcceptsTokenWhenExpectedUsernameIsNull() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDCACHE", "secret-cache");

        SigV4Validator validator = new SigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createElastiCacheToken(
                "cache-cluster-01",
                "default",
                "AKIDCACHE",
                "secret-cache",
                Instant.now().minusSeconds(60),
                900
        );

        assertTrue(validator.validate(token, "cache-cluster-01", null),
                "Null expectedUsername should skip the user identity check");
    }

    @Test
    void validateAcceptsTokenWithUrlEncodedUser() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDCACHE", "secret-cache");

        SigV4Validator validator = new SigV4Validator(iamService);
        // Username with characters that require URL encoding exercises the
        // encoding path independently of the validator's decode logic
        String token = SigV4TokenTestHelper.createElastiCacheToken(
                "cache-cluster-01",
                "user+name@domain.com",
                "AKIDCACHE",
                "secret-cache",
                Instant.now().minusSeconds(60),
                900
        );

        assertTrue(validator.validate(token, "cache-cluster-01", "user+name@domain.com"));
    }

    @Test
    void validateRejectsTokenMissingActionParameter() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDCACHE", "secret-cache");

        SigV4Validator validator = new SigV4Validator(iamService);
        String validToken = SigV4TokenTestHelper.createElastiCacheToken(
                "cache-cluster-01",
                "default",
                "AKIDCACHE",
                "secret-cache",
                Instant.now().minusSeconds(60),
                900
        );
        String withoutAction = validToken.replaceFirst("Action=connect&", "");

        assertFalse(validator.validate(withoutAction, "cache-cluster-01", "default"));
    }

    @Test
    void validateRejectsTokenMissingSignatureParameter() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDCACHE", "secret-cache");

        SigV4Validator validator = new SigV4Validator(iamService);
        String validToken = SigV4TokenTestHelper.createElastiCacheToken(
                "cache-cluster-01",
                "default",
                "AKIDCACHE",
                "secret-cache",
                Instant.now().minusSeconds(60),
                900
        );
        String withoutSignature = validToken.replaceFirst("&X-Amz-Signature=[0-9a-f]+", "");

        assertFalse(validator.validate(withoutSignature, "cache-cluster-01", "default"));
    }

    @Test
    void validateRejectsTokenSelfSignedWithUnregisteredAccessKeyAsSecret() throws Exception {
        // Only "AKIDCACHE" is registered; the attacker picks an arbitrary, unregistered
        // access key and signs using that same access key as the secret. If the validator
        // ever falls back to accessKeyId as the signing secret for unknown keys, this forged
        // token would be accepted for any cluster/user the attacker chooses.
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDCACHE", "secret-cache");

        SigV4Validator validator = new SigV4Validator(iamService);
        String forgedAccessKeyId = "AKIDFORGEDBYATTACKER";
        String token = SigV4TokenTestHelper.createElastiCacheToken(
                "cache-cluster-01",
                "default",
                forgedAccessKeyId,
                forgedAccessKeyId,
                Instant.now().minusSeconds(60),
                900
        );

        assertFalse(validator.validate(token, "cache-cluster-01", "default"),
                "A token self-signed with secret == accessKeyId for an unregistered access key "
                        + "must never validate; unregistered keys must fail closed");
    }

    @Test
    void validateRejectsTokenMissingUserParameterWhenUsernameExpected() throws Exception {
        // A validly-signed token that simply never includes User as a query param (the
        // attacker controls exactly what they sign) must not bypass the expectedUsername
        // identity check just because User is absent.
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDCACHE", "secret-cache");

        SigV4Validator validator = new SigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createElastiCacheTokenWithoutUser(
                "cache-cluster-01",
                "AKIDCACHE",
                "secret-cache",
                Instant.now().minusSeconds(60),
                900
        );

        assertFalse(validator.validate(token, "cache-cluster-01", "default"),
                "Omitting User from the token must not bypass the expectedUsername identity check");
    }

    @Test
    void validateAcceptsWellKnownLocalDevCredentialEvenWhenNotRegisteredInIam() throws Exception {
        // AwsBasicCredentials.create("test", "test") is the default local-dev credential used
        // pervasively by SDK clients against this emulator (RDS, S3, etc. compat tests). It must
        // keep working even though it is never registered in IamService -- this is the same
        // "test"/"test" convenience already honored by S3Service/PreSignedUrlFilter, carved out
        // explicitly rather than via the removed generic unregistered-key fallback.
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDCACHE", "secret-cache");

        SigV4Validator validator = new SigV4Validator(iamService);
        String token = SigV4TokenTestHelper.createElastiCacheToken(
                "cache-cluster-01",
                "default",
                "test",
                "test",
                Instant.now().minusSeconds(60),
                900
        );

        assertTrue(validator.validate(token, "cache-cluster-01", "default"),
                "The well-known \"test\"/\"test\" local-dev credential pair must still validate");
    }

    @Test
    void validateAcceptsTokenSignedWithActiveEcsTaskRoleAndIssuedSessionToken() throws Exception {
        IamService iamService = ecsIamService(Instant.now().plusSeconds(3600));
        SigV4Validator validator = new SigV4Validator(iamService);

        String token = createEcsElastiCacheToken(ECS_SESSION_TOKEN);

        assertTrue(validator.validate(token, "cache-cluster-01", "default"));
    }

    @Test
    void bindsOtherwiseValidEcsTokensToTheServerSelectedService() throws Exception {
        SigV4Validator validator = new SigV4Validator(ecsIamService(Instant.now().plusSeconds(3600)));
        for (String service : java.util.List.of("sts", "s3", "rds-db", "memorydb")) {
            assertFalse(validator.validate(createEcsElastiCacheToken(ECS_SESSION_TOKEN, service),
                    "cache-cluster-01", "default"), service);
        }
        assertTrue(validator.validate(createEcsElastiCacheToken(ECS_SESSION_TOKEN, "memorydb"),
                "cache-cluster-01", "default", "memorydb"));
        assertFalse(validator.validate(createEcsElastiCacheToken(ECS_SESSION_TOKEN),
                "cache-cluster-01", "default", "memorydb"));
    }

    @Test
    void validateRejectsEcsTaskRoleTokenWithoutSessionToken() throws Exception {
        IamService iamService = ecsIamService(Instant.now().plusSeconds(3600));
        SigV4Validator validator = new SigV4Validator(iamService);

        String token = createEcsElastiCacheToken(null);

        assertFalse(validator.validate(token, "cache-cluster-01", "default"));
    }

    @Test
    void validateRejectsEcsTaskRoleTokenWithWrongSessionToken() throws Exception {
        IamService iamService = ecsIamService(Instant.now().plusSeconds(3600));
        SigV4Validator validator = new SigV4Validator(iamService);

        String token = createEcsElastiCacheToken("forged-session-token");

        assertFalse(validator.validate(token, "cache-cluster-01", "default"));
    }

    @Test
    void validateRejectsExpiredEcsTaskRoleCredentialEvenWithIssuedSessionToken() throws Exception {
        IamService iamService = ecsIamService(Instant.now().plusSeconds(3600));
        iamService.resolveEcsTaskRoleSession(ECS_ACCESS_KEY_ID).orElseThrow().setExpiration(Instant.EPOCH);
        SigV4Validator validator = new SigV4Validator(iamService);

        String token = createEcsElastiCacheToken(ECS_SESSION_TOKEN);

        assertFalse(validator.validate(token, "cache-cluster-01", "default"));
    }

    @Test
    void validateRejectsRevokedEcsTaskRoleCredentialEvenWithIssuedSessionToken() throws Exception {
        IamService iamService = ecsIamService(Instant.now().plusSeconds(3600));
        iamService.revokeEcsTaskRoleSession(ECS_TASK_ARN, ECS_ACCESS_KEY_ID);
        SigV4Validator validator = new SigV4Validator(iamService);

        String token = createEcsElastiCacheToken(ECS_SESSION_TOKEN);

        assertFalse(validator.validate(token, "cache-cluster-01", "default"));
    }

    @Test
    void sanitizeForLogStripsControlCharacters() throws Exception {
        // A forged accessKeyId containing CR/LF must not be able to inject fake log lines into
        // the debug logs this validator writes.
        Method sanitizeForLog = SigV4Validator.class.getDeclaredMethod("sanitizeForLog", String.class);
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
            IamRole role = iamService.createRole("cache-validator", "/", "{}", null, 0, null);
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

    private static String createEcsElastiCacheToken(String sessionToken) throws Exception {
        return createEcsElastiCacheToken(sessionToken, "elasticache");
    }

    private static String createEcsElastiCacheToken(String sessionToken, String service) throws Exception {
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
        params.put("User", "default");
        if (sessionToken != null) {
            params.put("X-Amz-Security-Token", sessionToken);
        }
        return (String) signer.invoke(
                null,
                "cache-cluster-01",
                null,
                ECS_ACCESS_KEY_ID,
                ECS_SECRET_ACCESS_KEY,
                "us-east-1",
                service,
                Instant.now().minusSeconds(60),
                900,
                params,
                Map.of("host", "cache-cluster-01"));
    }
}
