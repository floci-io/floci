package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CognitoTotpMfaTest {
    private static final String USERNAME = "alice";
    private static final String PASSWORD = "Perm1234!";

    private CognitoService service;
    private MutableClock clock;
    private UserPool pool;
    private UserPoolClient client;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        service = new CognitoService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                "http://localhost:4566", "cloudfront.net",
                new RegionResolver("us-east-1", "000000000000"), null, mock(AcmService.class),
                null, null, null, clock);
        pool = service.createUserPool(Map.of("PoolName", "TotpTestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), USERNAME, Map.of("email", "alice@example.com"), null);
        service.adminSetUserPassword(pool.getId(), USERNAME, PASSWORD, true);
        client = service.createUserPoolClient(pool.getId(), "totp-client", false, false, List.of(), List.of());
        client.setExplicitAuthFlows(List.of("ALLOW_USER_PASSWORD_AUTH", "ALLOW_USER_SRP_AUTH"));
        service.setUserPoolMfaConfig(pool.getId(), "ON", true, false);
    }

    @Test
    void setupAndLaterSignInRequireSeparateTotpChallenges() {
        Map<String, Object> login = passwordLogin();
        assertEquals("MFA_SETUP", login.get("ChallengeName"));
        assertFalse(login.containsKey("AuthenticationResult"));
        assertTrue(String.valueOf(login.get("ChallengeParameters")).contains("SOFTWARE_TOKEN_MFA"));

        String loginSession = (String) login.get("Session");
        Map<String, Object> association = service.associateSoftwareToken(null, loginSession);
        String secret = (String) association.get("SecretCode");
        String associationSession = (String) association.get("Session");
        assertNotNull(secret);
        assertFalse(loginSession.equals(associationSession));

        AwsException wrong = assertThrows(AwsException.class, () -> service.verifySoftwareToken(
                null, associationSession, "not-a-code"));
        assertEquals("InvalidParameterException", wrong.getErrorCode());

        String code = CognitoTotp.code(secret, clock.instant());
        Map<String, Object> verified = service.verifySoftwareToken(null, associationSession, code);
        assertEquals("SUCCESS", verified.get("Status"));
        String verifiedSession = (String) verified.get("Session");
        assertFalse(associationSession.equals(verifiedSession));

        assertEquals("NotAuthorizedException", assertThrows(AwsException.class,
                () -> service.respondToAuthChallenge(client.getClientId(), "MFA_SETUP", loginSession,
                        Map.of("USERNAME", USERNAME))).getErrorCode());
        Map<String, Object> authenticated = service.respondToAuthChallenge(client.getClientId(),
                "MFA_SETUP", verifiedSession, Map.of("USERNAME", USERNAME));
        assertNotNull(((Map<?, ?>) authenticated.get("AuthenticationResult")).get("AccessToken"));
        assertEquals("NotAuthorizedException", assertThrows(AwsException.class,
                () -> service.respondToAuthChallenge(client.getClientId(), "MFA_SETUP", verifiedSession,
                        Map.of("USERNAME", USERNAME))).getErrorCode());

        Map<String, Object> later = passwordLogin();
        assertEquals("SOFTWARE_TOKEN_MFA", later.get("ChallengeName"));
        assertFalse(later.containsKey("AuthenticationResult"));
        String laterSession = (String) later.get("Session");
        String wrongCode = code.equals("000000") ? "000001" : "000000";
        AwsException mismatch = assertThrows(AwsException.class, () -> service.respondToAuthChallenge(
                client.getClientId(), "SOFTWARE_TOKEN_MFA", laterSession,
                Map.of("USERNAME", USERNAME, "SOFTWARE_TOKEN_MFA_CODE", wrongCode)));
        assertEquals("CodeMismatchException", mismatch.getErrorCode());
        Map<String, Object> completed = service.respondToAuthChallenge(client.getClientId(),
                "SOFTWARE_TOKEN_MFA", laterSession,
                Map.of("USERNAME", USERNAME, "SOFTWARE_TOKEN_MFA_CODE", code));
        assertNotNull(((Map<?, ?>) completed.get("AuthenticationResult")).get("AccessToken"));
    }

    @Test
    void disabledUserCannotCompleteMfaSetupChallenge() {
        String loginSession = (String) passwordLogin().get("Session");
        Map<String, Object> association = service.associateSoftwareToken(null, loginSession);
        String code = CognitoTotp.code((String) association.get("SecretCode"), clock.instant());
        String verifiedSession = (String) service.verifySoftwareToken(null,
                (String) association.get("Session"), code).get("Session");

        service.adminDisableUser(pool.getId(), USERNAME);

        AwsException disabled = assertThrows(AwsException.class,
                () -> service.respondToAuthChallenge(client.getClientId(), "MFA_SETUP", verifiedSession,
                        Map.of("USERNAME", USERNAME)));
        assertEquals("NotAuthorizedException", disabled.getErrorCode());
        assertEquals("User is disabled.", disabled.getMessage());
    }

    @Test
    void disabledUserCannotCompleteSoftwareTokenChallenge() {
        String loginSession = (String) passwordLogin().get("Session");
        Map<String, Object> association = service.associateSoftwareToken(null, loginSession);
        String code = CognitoTotp.code((String) association.get("SecretCode"), clock.instant());
        String verifiedSession = (String) service.verifySoftwareToken(null,
                (String) association.get("Session"), code).get("Session");
        service.respondToAuthChallenge(client.getClientId(), "MFA_SETUP", verifiedSession,
                Map.of("USERNAME", USERNAME));
        String challengeSession = (String) passwordLogin().get("Session");

        service.adminDisableUser(pool.getId(), USERNAME);

        AwsException disabled = assertThrows(AwsException.class,
                () -> service.respondToAuthChallenge(client.getClientId(), "SOFTWARE_TOKEN_MFA", challengeSession,
                        Map.of("USERNAME", USERNAME, "SOFTWARE_TOKEN_MFA_CODE", code)));
        assertEquals("NotAuthorizedException", disabled.getErrorCode());
        assertEquals("User is disabled.", disabled.getMessage());
    }

    @Test
    void setupSessionExpiresAndCannotBeUsedByAnotherClient() {
        String session = (String) passwordLogin().get("Session");
        UserPoolClient other = service.createUserPoolClient(pool.getId(), "other", false, false, List.of(), List.of());
        assertEquals("NotAuthorizedException", assertThrows(AwsException.class,
                () -> service.respondToAuthChallenge(other.getClientId(), "MFA_SETUP", session,
                        Map.of("USERNAME", USERNAME))).getErrorCode());

        clock.advance(Duration.ofMinutes(client.getAuthSessionValidity()).plusSeconds(1));
        AwsException expired = assertThrows(AwsException.class,
                () -> service.associateSoftwareToken(null, session));
        assertEquals("NotAuthorizedException", expired.getErrorCode());
        assertTrue(expired.getMessage().contains("expired"));
    }

    @Test
    void associationRejectsPoolsWithoutSoftwareTokenMfa() {
        service.setUserPoolMfaConfig(pool.getId(), "OFF", null, false);
        String accessToken = (String) ((Map<?, ?>) passwordLogin().get("AuthenticationResult")).get("AccessToken");

        AwsException unsupported = assertThrows(AwsException.class,
                () -> service.associateSoftwareToken(accessToken, null));
        assertEquals("SoftwareTokenMFANotFoundException", unsupported.getErrorCode());
    }

    @Test
    void invalidSetupCodeUsesCodeMismatchError() {
        String loginSession = (String) passwordLogin().get("Session");
        Map<String, Object> associated = service.associateSoftwareToken(null, loginSession);
        String code = CognitoTotp.code((String) associated.get("SecretCode"), clock.instant());
        String wrongCode = code.equals("000000") ? "000001" : "000000";

        AwsException mismatch = assertThrows(AwsException.class,
                () -> service.verifySoftwareToken(null, (String) associated.get("Session"), wrongCode));
        assertEquals("CodeMismatchException", mismatch.getErrorCode());
    }

    @Test
    void repeatedWrongSetupCodesInvalidatePendingSoftwareToken() throws Exception {
        String loginSession = (String) passwordLogin().get("Session");
        Map<String, Object> associated = service.associateSoftwareToken(null, loginSession);
        String session = (String) associated.get("Session");
        String code = CognitoTotp.code((String) associated.get("SecretCode"), clock.instant());
        String wrongCode = code.equals("000000") ? "000001" : "000000";

        for (int attempt = 0; attempt < CognitoTotp.MAX_FAILED_ATTEMPTS; attempt++) {
            AwsException mismatch = assertThrows(AwsException.class,
                    () -> service.verifySoftwareToken(null, session, wrongCode));
            assertEquals("CodeMismatchException", mismatch.getErrorCode());
        }
        CognitoUser pending = service.adminGetUser(pool.getId(), USERNAME);
        ObjectMapper mapper = new ObjectMapper();
        CognitoUser reloaded = mapper.readValue(mapper.writeValueAsBytes(pending), CognitoUser.class);
        assertEquals(0, reloaded.getPendingSoftwareTokenMfaAttemptsRemaining());
        assertEquals("CodeMismatchException", assertThrows(AwsException.class,
                () -> service.verifySoftwareToken(null, session, code)).getErrorCode());

        Map<String, Object> replacement = service.associateSoftwareToken(null,
                (String) passwordLogin().get("Session"));
        String replacementCode = CognitoTotp.code((String) replacement.get("SecretCode"), clock.instant());
        assertEquals("SUCCESS", service.verifySoftwareToken(null,
                (String) replacement.get("Session"), replacementCode).get("Status"));
    }

    @Test
    void accessTokenCannotRetryExhaustedSoftwareTokenAssociation() {
        service.setUserPoolMfaConfig(pool.getId(), "OFF", null, false);
        String accessToken = (String) ((Map<?, ?>) passwordLogin().get("AuthenticationResult")).get("AccessToken");
        service.setUserPoolMfaConfig(pool.getId(), "ON", true, false);
        Map<String, Object> associated = service.associateSoftwareToken(accessToken, null);
        String code = CognitoTotp.code((String) associated.get("SecretCode"), clock.instant());
        String wrongCode = code.equals("000000") ? "000001" : "000000";

        for (int attempt = 0; attempt < CognitoTotp.MAX_FAILED_ATTEMPTS; attempt++) {
            assertEquals("CodeMismatchException", assertThrows(AwsException.class,
                    () -> service.verifySoftwareToken(accessToken, null, wrongCode)).getErrorCode());
        }
        assertEquals("CodeMismatchException", assertThrows(AwsException.class,
                () -> service.verifySoftwareToken(accessToken, null, code)).getErrorCode());
    }

    @Test
    void repeatedWrongSignInCodesInvalidateChallengeSession() {
        Map<String, Object> associated = service.associateSoftwareToken(null,
                (String) passwordLogin().get("Session"));
        String code = CognitoTotp.code((String) associated.get("SecretCode"), clock.instant());
        String verifiedSession = (String) service.verifySoftwareToken(null,
                (String) associated.get("Session"), code).get("Session");
        service.respondToAuthChallenge(client.getClientId(), "MFA_SETUP", verifiedSession,
                Map.of("USERNAME", USERNAME));

        String session = (String) passwordLogin().get("Session");
        String wrongCode = code.equals("000000") ? "000001" : "000000";
        for (int attempt = 0; attempt < CognitoTotp.MAX_FAILED_ATTEMPTS; attempt++) {
            assertEquals("CodeMismatchException", assertThrows(AwsException.class,
                    () -> service.respondToAuthChallenge(client.getClientId(), "SOFTWARE_TOKEN_MFA", session,
                            Map.of("USERNAME", USERNAME, "SOFTWARE_TOKEN_MFA_CODE", wrongCode)))
                    .getErrorCode());
        }
        assertEquals("NotAuthorizedException", assertThrows(AwsException.class,
                () -> service.respondToAuthChallenge(client.getClientId(), "SOFTWARE_TOKEN_MFA", session,
                        Map.of("USERNAME", USERNAME, "SOFTWARE_TOKEN_MFA_CODE", code))).getErrorCode());
        assertNotNull(service.respondToAuthChallenge(client.getClientId(), "SOFTWARE_TOKEN_MFA",
                (String) passwordLogin().get("Session"),
                Map.of("USERNAME", USERNAME, "SOFTWARE_TOKEN_MFA_CODE", code)).get("AuthenticationResult"));
    }

    @Test
    void accessTokenCanAssociateSoftwareTokenWithoutAnAuthSession() {
        service.setUserPoolMfaConfig(pool.getId(), "OFF", null, false);
        String accessToken = (String) ((Map<?, ?>) passwordLogin().get("AuthenticationResult")).get("AccessToken");
        service.setUserPoolMfaConfig(pool.getId(), "ON", true, false);

        Map<String, Object> association = service.associateSoftwareToken(accessToken, null);
        assertFalse(association.containsKey("Session"));
        String code = CognitoTotp.code((String) association.get("SecretCode"), clock.instant());
        Map<String, Object> verified = service.verifySoftwareToken(accessToken, null, code);
        assertEquals("SUCCESS", verified.get("Status"));
        assertFalse(verified.containsKey("Session"));
        assertEquals("SOFTWARE_TOKEN_MFA", passwordLogin().get("ChallengeName"));
    }

    @Test
    void laterAssociationInvalidatesEarlierSecretAndSession() {
        String firstLogin = (String) passwordLogin().get("Session");
        String secondLogin = (String) passwordLogin().get("Session");
        Map<String, Object> first = service.associateSoftwareToken(null, firstLogin);
        Map<String, Object> second = service.associateSoftwareToken(null, secondLogin);
        assertEquals("NotAuthorizedException", assertThrows(AwsException.class,
                () -> service.associateSoftwareToken(null, firstLogin)).getErrorCode());

        String firstCode = CognitoTotp.code((String) first.get("SecretCode"), clock.instant());
        assertEquals("NotAuthorizedException", assertThrows(AwsException.class,
                () -> service.verifySoftwareToken(null, (String) first.get("Session"), firstCode)).getErrorCode());

        String secondCode = CognitoTotp.code((String) second.get("SecretCode"), clock.instant());
        Map<String, Object> verified = service.verifySoftwareToken(null, (String) second.get("Session"), secondCode);
        assertNotNull(service.respondToAuthChallenge(client.getClientId(), "MFA_SETUP",
                (String) verified.get("Session"), Map.of("USERNAME", USERNAME)).get("AuthenticationResult"));
    }

    @Test
    void registeredSecretSurvivesUserSerialization() throws Exception {
        String loginSession = (String) passwordLogin().get("Session");
        Map<String, Object> associated = service.associateSoftwareToken(null, loginSession);
        String secret = (String) associated.get("SecretCode");
        String code = CognitoTotp.code(secret, clock.instant());
        service.verifySoftwareToken(null, (String) associated.get("Session"), code);

        CognitoUser persisted = service.adminGetUser(pool.getId(), USERNAME);
        ObjectMapper mapper = new ObjectMapper();
        CognitoUser restored = mapper.readValue(mapper.writeValueAsBytes(persisted), CognitoUser.class);
        assertEquals(secret, restored.getSoftwareTokenMfaSecret());
        assertNull(restored.getPendingSoftwareTokenMfaSecret());
        assertTrue(CognitoTotp.validCode(restored.getSoftwareTokenMfaSecret(), code, clock.instant()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulSrpProofContinuesToMfaSetupWithoutTokens() throws Exception {
        BigInteger privateA = new BigInteger("123456789abcdef123456789abcdef", 16);
        BigInteger publicA = CognitoSrpHelper.G.modPow(privateA, CognitoSrpHelper.N);
        Map<String, Object> started = service.initiateAuth(client.getClientId(), "USER_SRP_AUTH",
                Map.of("USERNAME", USERNAME, "SRP_A", publicA.toString(16)));
        assertEquals("PASSWORD_VERIFIER", started.get("ChallengeName"));
        Map<String, String> params = (Map<String, String>) started.get("ChallengeParameters");
        String timestamp = "Wed Apr 8 12:00:00 UTC 2026";
        byte[] sessionKey = clientSrpSessionKey(privateA, publicA,
                new BigInteger(params.get("SRP_B"), 16), params.get("SALT"));
        byte[] secretBlock = Base64.getDecoder().decode(params.get("SECRET_BLOCK"));
        String signature = Base64.getEncoder().encodeToString(CognitoSrpHelper.computeSignature(
                sessionKey, pool.getId(), USERNAME, secretBlock, timestamp));
        UserPoolClient other = service.createUserPoolClient(pool.getId(), "other-srp-client", false, false,
                List.of(), List.of());
        assertEquals("NotAuthorizedException", assertThrows(AwsException.class,
                () -> service.respondToAuthChallenge(other.getClientId(), "PASSWORD_VERIFIER",
                        (String) started.get("Session"), Map.of(
                                "USERNAME", USERNAME,
                                "PASSWORD_CLAIM_SECRET_BLOCK", params.get("SECRET_BLOCK"),
                                "PASSWORD_CLAIM_SIGNATURE", signature,
                                "TIMESTAMP", timestamp))).getErrorCode());
        Map<String, Object> verified = service.respondToAuthChallenge(client.getClientId(),
                "PASSWORD_VERIFIER", (String) started.get("Session"), Map.of(
                        "USERNAME", USERNAME,
                        "PASSWORD_CLAIM_SECRET_BLOCK", params.get("SECRET_BLOCK"),
                        "PASSWORD_CLAIM_SIGNATURE", signature,
                        "TIMESTAMP", timestamp));
        assertEquals("MFA_SETUP", verified.get("ChallengeName"));
        assertFalse(verified.containsKey("AuthenticationResult"));
    }

    private byte[] clientSrpSessionKey(BigInteger privateA, BigInteger publicA,
                                        BigInteger publicB, String saltHex) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        hash.update((CognitoSrpHelper.extractPoolName(pool.getId()) + USERNAME + ":" + PASSWORD)
                .getBytes(StandardCharsets.UTF_8));
        byte[] inner = hash.digest();
        hash.update(new BigInteger(saltHex, 16).toByteArray());
        BigInteger x = new BigInteger(1, hash.digest(inner));
        hash.update(publicA.toByteArray());
        BigInteger u = new BigInteger(1, hash.digest(publicB.toByteArray()));
        BigInteger base = publicB.subtract(CognitoSrpHelper.K.multiply(
                CognitoSrpHelper.G.modPow(x, CognitoSrpHelper.N))).mod(CognitoSrpHelper.N);
        BigInteger shared = base.modPow(privateA.add(u.multiply(x)), CognitoSrpHelper.N);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(u.toByteArray(), "HmacSHA256"));
        byte[] prk = mac.doFinal(shared.toByteArray());
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update("Caldera Derived Key".getBytes(StandardCharsets.UTF_8));
        mac.update((byte) 1);
        return Arrays.copyOf(mac.doFinal(), 16);
    }

    private Map<String, Object> passwordLogin() {
        return service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", USERNAME, "PASSWORD", PASSWORD));
    }
}
