package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testutil.IamServiceTestHelper;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EksTokenValidatorTest {

    private static final String CLUSTER_NAME = "demo";
    private static final String ACCESS_KEY_ID = "AKIDEKSTEST";
    private static final String SECRET_ACCESS_KEY = "eks-secret-key";

    @Test
    void validateAcceptsSignedGetCallerIdentityRequest() throws Exception {
        EksTokenValidator validator = validator();
        String token = token(CLUSTER_NAME, Instant.now(), 60);

        assertTrue(validator.validate(token, CLUSTER_NAME));
    }

    @Test
    void validateRejectsThePublicLocalCredentialPair() throws Exception {
        EksTokenValidator validator = validator();
        String token = SigV4TokenTestHelper.createEksToken(CLUSTER_NAME, "test", "test", Instant.now(), 60);

        assertFalse(validator.validate(token, CLUSTER_NAME));
    }

    @Test
    void validateRejectsThePublicSeededDeployerCredential() throws Exception {
        String accessKeyId = "floci";
        String secretAccessKey = "floci";
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey(accessKeyId, secretAccessKey);
        EksTokenValidator validator = new EksTokenValidator(iamService);
        String token = SigV4TokenTestHelper.createEksToken(
                CLUSTER_NAME, accessKeyId, secretAccessKey, Instant.now(), 60);

        assertFalse(validator.validate(token, CLUSTER_NAME));
    }

    @Test
    void validateRejectsTamperedSignature() throws Exception {
        EksTokenValidator validator = validator();
        String token = replaceDecodedToken(token(CLUSTER_NAME, Instant.now(), 60),
                "Action=GetCallerIdentity", "Action=GetFederationToken");

        assertFalse(validator.validate(token, CLUSTER_NAME));
    }

    @Test
    void validateRejectsExpiredToken() throws Exception {
        EksTokenValidator validator = validator();
        String token = token(CLUSTER_NAME, Instant.now().minusSeconds(61), 60);

        assertFalse(validator.validate(token, CLUSTER_NAME));
    }

    @Test
    void validateRejectsTokenWithAStretchedExpiry() throws Exception {
        EksTokenValidator validator = validator();
        String token = token(CLUSTER_NAME, Instant.now(), 61);

        assertFalse(validator.validate(token, CLUSTER_NAME));
    }

    @Test
    void validateRejectsTokenSignedTooFarInTheFuture() throws Exception {
        Instant now = Instant.parse("2026-09-01T22:00:00Z");
        EksTokenValidator validator = validator(Clock.fixed(now, ZoneOffset.UTC));
        String token = token(CLUSTER_NAME, now.plusSeconds(301), 60);

        assertFalse(validator.validate(token, CLUSTER_NAME));
    }

    @Test
    void validateRejectsTokenBoundToAnotherCluster() throws Exception {
        EksTokenValidator validator = validator();
        String token = token("other-cluster", Instant.now(), 60);

        assertFalse(validator.validate(token, CLUSTER_NAME));
    }

    @Test
    void validateRejectsUnknownCredentials() throws Exception {
        EksTokenValidator validator = validator();
        String token = SigV4TokenTestHelper.createEksToken(
                CLUSTER_NAME, "AKIDUNKNOWN", "unknown-secret", Instant.now(), 60);

        assertFalse(validator.validate(token, CLUSTER_NAME));
    }

    @Test
    void validateRejectsInactiveAccessKeys() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey(ACCESS_KEY_ID, SECRET_ACCESS_KEY);
        iamService.findAccessKey(ACCESS_KEY_ID).orElseThrow().setStatus("Inactive");
        EksTokenValidator validator = new EksTokenValidator(iamService);
        String token = token(CLUSTER_NAME, Instant.now(), 60);

        assertFalse(validator.validate(token, CLUSTER_NAME));
    }

    @Test
    void validateRejectsExpiredTemporaryCredentials() throws Exception {
        String accessKeyId = "ASIAEXPIREDTOKEN";
        String secretAccessKey = "expired-session-secret";
        IamService iamService = IamServiceTestHelper.iamServiceWithSessionCredential(
                accessKeyId, secretAccessKey, Instant.now().minusSeconds(1));
        EksTokenValidator validator = new EksTokenValidator(iamService);
        String token = SigV4TokenTestHelper.createEksToken(
                CLUSTER_NAME, accessKeyId, secretAccessKey, Instant.now(), 60);

        assertFalse(validator.validate(token, CLUSTER_NAME));
    }

    @Test
    void validateAcceptsTemporaryCredentialsWithTheirSessionToken() throws Exception {
        String accessKeyId = "ASIAVALIDSESSION";
        String secretAccessKey = "valid-session-secret";
        String sessionToken = "valid-session-token";
        IamService iamService = IamServiceTestHelper.iamServiceWithSessionCredential(
                accessKeyId, secretAccessKey, sessionToken, Instant.now().plusSeconds(60));
        EksTokenValidator validator = new EksTokenValidator(iamService);
        String token = SigV4TokenTestHelper.createEksToken(
                CLUSTER_NAME, accessKeyId, secretAccessKey, Instant.now(), 60, sessionToken);

        assertTrue(validator.validate(token, CLUSTER_NAME));
    }

    @Test
    void validateRejectsLegacyTemporaryCredentialsWithoutAPersistedSessionToken() throws Exception {
        String accessKeyId = "ASIALEGACYMISSING";
        String secretAccessKey = "legacy-missing-secret";
        IamService iamService = IamServiceTestHelper.iamServiceWithSessionCredential(
                accessKeyId, secretAccessKey, null, Instant.now().plusSeconds(60));
        EksTokenValidator validator = new EksTokenValidator(iamService);
        String token = SigV4TokenTestHelper.createEksToken(
                CLUSTER_NAME, accessKeyId, secretAccessKey, Instant.now(), 60, "legacy-session-token");

        assertFalse(validator.validate(token, CLUSTER_NAME));
    }

    @Test
    void validateRejectsTemporaryCredentialsWithoutTheirSessionToken() throws Exception {
        String accessKeyId = "ASIAMISSINGTOKEN";
        String secretAccessKey = "missing-session-secret";
        IamService iamService = IamServiceTestHelper.iamServiceWithSessionCredential(
                accessKeyId, secretAccessKey, "session-token", Instant.now().plusSeconds(60));
        EksTokenValidator validator = new EksTokenValidator(iamService);
        String token = SigV4TokenTestHelper.createEksToken(
                CLUSTER_NAME, accessKeyId, secretAccessKey, Instant.now(), 60);

        assertFalse(validator.validate(token, CLUSTER_NAME));
    }

    @Test
    void validateRejectsTemporaryCredentialsWithAnotherSessionToken() throws Exception {
        String accessKeyId = "ASIAMISMATCHTOKEN";
        String secretAccessKey = "mismatch-session-secret";
        IamService iamService = IamServiceTestHelper.iamServiceWithSessionCredential(
                accessKeyId, secretAccessKey, "expected-session-token", Instant.now().plusSeconds(60));
        EksTokenValidator validator = new EksTokenValidator(iamService);
        String token = SigV4TokenTestHelper.createEksToken(
                CLUSTER_NAME, accessKeyId, secretAccessKey, Instant.now(), 60, "another-session-token");

        assertFalse(validator.validate(token, CLUSTER_NAME));
    }

    @Test
    void validateRejectsMalformedToken() {
        assertFalse(validator().validate("k8s-aws-v1.not-a-presigned-sts-request", CLUSTER_NAME));
    }

    private EksTokenValidator validator() {
        return validator(Clock.systemUTC());
    }

    private EksTokenValidator validator(Clock clock) {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey(ACCESS_KEY_ID, SECRET_ACCESS_KEY);
        return new EksTokenValidator(iamService, clock);
    }

    private String token(String clusterName, Instant timestamp, int expirySeconds) throws Exception {
        return SigV4TokenTestHelper.createEksToken(
                clusterName, ACCESS_KEY_ID, SECRET_ACCESS_KEY, timestamp, expirySeconds);
    }

    private static String replaceDecodedToken(String token, String target, String replacement) {
        String prefix = "k8s-aws-v1.";
        String encodedRequest = token.substring(prefix.length());
        String request = new String(Base64.getUrlDecoder().decode(encodedRequest), StandardCharsets.UTF_8);
        String tamperedRequest = request.replace(target, replacement);
        return prefix + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tamperedRequest.getBytes(StandardCharsets.UTF_8));
    }
}
