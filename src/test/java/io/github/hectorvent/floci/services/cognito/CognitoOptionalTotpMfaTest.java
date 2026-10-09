package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.cognito.CognitoService.MfaSettingsUpdate;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Software-token MFA in a pool where MFA is optional, turned on per user with the MFA preference actions. */
class CognitoOptionalTotpMfaTest {
    private static final String USERNAME = "alice";
    private static final String PASSWORD = "Perm1234!";
    private static final MfaSettingsUpdate TOTP_PREFERRED = new MfaSettingsUpdate(true, true);

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
        pool = service.createUserPool(Map.of("PoolName", "OptionalTotpPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), USERNAME, Map.of("email", "alice@example.com"), null);
        service.adminSetUserPassword(pool.getId(), USERNAME, PASSWORD, true);
        client = service.createUserPoolClient(pool.getId(), "optional-totp-client", false, false,
                List.of(), List.of());
        client.setExplicitAuthFlows(List.of("ALLOW_USER_PASSWORD_AUTH", "ALLOW_USER_SRP_AUTH",
                "ALLOW_ADMIN_USER_PASSWORD_AUTH"));
        service.setUserPoolMfaConfig(pool.getId(), "OPTIONAL", true, false);
    }

    @Test
    void verifiedAuthenticatorIsNotChallengedUntilTurnedOn() {
        String secret = registerAuthenticator();
        assertNotNull(passwordLogin().get("AuthenticationResult"));

        service.setUserMFAPreference(accessToken(), MfaSettingsUpdate.NONE, TOTP_PREFERRED);
        Map<String, Object> challenged = passwordLogin();
        assertEquals("SOFTWARE_TOKEN_MFA", challenged.get("ChallengeName"));
        assertFalse(challenged.containsKey("AuthenticationResult"));

        Map<String, Object> signedIn = service.respondToAuthChallenge(client.getClientId(), "SOFTWARE_TOKEN_MFA",
                (String) challenged.get("Session"),
                Map.of("USERNAME", USERNAME, "SOFTWARE_TOKEN_MFA_CODE", CognitoTotp.code(secret, clock.instant())));
        assertNotNull(((Map<?, ?>) signedIn.get("AuthenticationResult")).get("AccessToken"));
    }

    @Test
    void turningTotpOffSignsInWithoutAChallengeAgain() {
        registerAuthenticator();
        service.adminSetUserMFAPreference(pool.getId(), USERNAME, MfaSettingsUpdate.NONE, TOTP_PREFERRED);
        assertEquals("SOFTWARE_TOKEN_MFA", passwordLogin().get("ChallengeName"));

        service.adminSetUserMFAPreference(pool.getId(), USERNAME, MfaSettingsUpdate.NONE,
                new MfaSettingsUpdate(false, null));

        assertNotNull(passwordLogin().get("AuthenticationResult"));
        CognitoUser user = service.adminGetUser(pool.getId(), USERNAME);
        assertTrue(mfaSettingList(user).isEmpty());
        assertNull(preferredMfaSetting(user));
    }

    @Test
    void turningTotpOnRequiresAVerifiedAuthenticator() {
        String accessToken = accessToken();
        AwsException noToken = assertThrows(AwsException.class,
                () -> service.setUserMFAPreference(accessToken, MfaSettingsUpdate.NONE, TOTP_PREFERRED));
        assertEquals("InvalidParameterException", noToken.getErrorCode());

        service.associateSoftwareToken(accessToken, null);
        AwsException unverified = assertThrows(AwsException.class, () -> service.adminSetUserMFAPreference(
                pool.getId(), USERNAME, MfaSettingsUpdate.NONE, TOTP_PREFERRED));
        assertEquals("InvalidParameterException", unverified.getErrorCode());
        assertNull(service.adminGetUser(pool.getId(), USERNAME).getSoftwareTokenMfaSettings());
        assertNotNull(passwordLogin().get("AuthenticationResult"));
    }

    @Test
    void adminPasswordAuthAndAdminChallengeResponseUseTheAuthenticator() {
        String secret = registerAuthenticator();
        service.adminSetUserMFAPreference(pool.getId(), USERNAME, MfaSettingsUpdate.NONE,
                new MfaSettingsUpdate(true, null));

        Map<String, Object> challenged = service.adminInitiateAuth(pool.getId(), client.getClientId(),
                "ADMIN_USER_PASSWORD_AUTH", Map.of("USERNAME", USERNAME, "PASSWORD", PASSWORD), Map.of());
        assertEquals("SOFTWARE_TOKEN_MFA", challenged.get("ChallengeName"));

        String code = CognitoTotp.code(secret, clock.instant());
        String wrongCode = code.equals("000000") ? "000001" : "000000";
        assertEquals("CodeMismatchException", assertThrows(AwsException.class,
                () -> service.adminRespondToAuthChallenge(pool.getId(), client.getClientId(), "SOFTWARE_TOKEN_MFA",
                        (String) challenged.get("Session"),
                        Map.of("USERNAME", USERNAME, "SOFTWARE_TOKEN_MFA_CODE", wrongCode), Map.of()))
                .getErrorCode());
        Map<String, Object> signedIn = service.adminRespondToAuthChallenge(pool.getId(), client.getClientId(),
                "SOFTWARE_TOKEN_MFA", (String) challenged.get("Session"),
                Map.of("USERNAME", USERNAME, "SOFTWARE_TOKEN_MFA_CODE", code), Map.of());
        assertNotNull(((Map<?, ?>) signedIn.get("AuthenticationResult")).get("AccessToken"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void srpProofContinuesToTheSoftwareTokenChallenge() throws Exception {
        registerAuthenticator();
        service.adminSetUserMFAPreference(pool.getId(), USERNAME, MfaSettingsUpdate.NONE, TOTP_PREFERRED);

        BigInteger privateA = new BigInteger("123456789abcdef123456789abcdef", 16);
        BigInteger publicA = CognitoSrpHelper.G.modPow(privateA, CognitoSrpHelper.N);
        Map<String, Object> started = service.initiateAuth(client.getClientId(), "USER_SRP_AUTH",
                Map.of("USERNAME", USERNAME, "SRP_A", publicA.toString(16)));
        Map<String, String> params = (Map<String, String>) started.get("ChallengeParameters");
        String timestamp = "Wed Apr 8 12:00:00 UTC 2026";
        byte[] sessionKey = clientSrpSessionKey(privateA, publicA,
                new BigInteger(params.get("SRP_B"), 16), params.get("SALT"));
        String signature = Base64.getEncoder().encodeToString(CognitoSrpHelper.computeSignature(sessionKey,
                pool.getId(), USERNAME, Base64.getDecoder().decode(params.get("SECRET_BLOCK")), timestamp));

        Map<String, Object> verified = service.respondToAuthChallenge(client.getClientId(),
                "PASSWORD_VERIFIER", (String) started.get("Session"), Map.of(
                        "USERNAME", USERNAME,
                        "PASSWORD_CLAIM_SECRET_BLOCK", params.get("SECRET_BLOCK"),
                        "PASSWORD_CLAIM_SIGNATURE", signature,
                        "TIMESTAMP", timestamp));
        assertEquals("SOFTWARE_TOKEN_MFA", verified.get("ChallengeName"));
        assertFalse(verified.containsKey("AuthenticationResult"));
    }

    @Test
    void preferringOneFactorDropsThePreferenceOfTheOther() {
        registerAuthenticator();
        service.adminSetUserMFAPreference(pool.getId(), USERNAME, new MfaSettingsUpdate(true, true),
                MfaSettingsUpdate.NONE);
        CognitoUser emailPreferred = service.adminGetUser(pool.getId(), USERNAME);
        assertEquals("EMAIL_OTP", preferredMfaSetting(emailPreferred));

        service.adminSetUserMFAPreference(pool.getId(), USERNAME, MfaSettingsUpdate.NONE, TOTP_PREFERRED);
        CognitoUser totpPreferred = service.adminGetUser(pool.getId(), USERNAME);
        assertEquals("SOFTWARE_TOKEN_MFA", preferredMfaSetting(totpPreferred));
        assertEquals(List.of("EMAIL_OTP", "SOFTWARE_TOKEN_MFA"), mfaSettingList(totpPreferred));
        assertTrue(totpPreferred.getEmailMfaSettings().isEnabled());
        assertFalse(totpPreferred.getEmailMfaSettings().isPreferredMfa());

        AwsException both = assertThrows(AwsException.class, () -> service.adminSetUserMFAPreference(
                pool.getId(), USERNAME, new MfaSettingsUpdate(true, true), TOTP_PREFERRED));
        assertEquals("InvalidParameterException", both.getErrorCode());
        assertEquals("SOFTWARE_TOKEN_MFA",
                preferredMfaSetting(service.adminGetUser(pool.getId(), USERNAME)));
    }

    @Test
    void preferringADisabledFactorIsRefused() {
        registerAuthenticator();
        AwsException refused = assertThrows(AwsException.class, () -> service.adminSetUserMFAPreference(
                pool.getId(), USERNAME, MfaSettingsUpdate.NONE, new MfaSettingsUpdate(false, true)));
        assertEquals("InvalidParameterException", refused.getErrorCode());
    }

    @Test
    void completingRequiredMfaSetupTurnsTheAuthenticatorOn() {
        service.setUserPoolMfaConfig(pool.getId(), "ON", true, false);
        Map<String, Object> login = passwordLogin();
        assertEquals("MFA_SETUP", login.get("ChallengeName"));
        Map<String, Object> associated = service.associateSoftwareToken(null, (String) login.get("Session"));
        String code = CognitoTotp.code((String) associated.get("SecretCode"), clock.instant());
        String verifiedSession = (String) service.verifySoftwareToken(null,
                (String) associated.get("Session"), code).get("Session");
        service.respondToAuthChallenge(client.getClientId(), "MFA_SETUP", verifiedSession,
                Map.of("USERNAME", USERNAME));

        CognitoUser user = service.adminGetUser(pool.getId(), USERNAME);
        assertEquals(List.of("SOFTWARE_TOKEN_MFA"), mfaSettingList(user));
        assertEquals("SOFTWARE_TOKEN_MFA", preferredMfaSetting(user));

        service.setUserPoolMfaConfig(pool.getId(), "OPTIONAL", true, false);
        assertEquals("SOFTWARE_TOKEN_MFA", passwordLogin().get("ChallengeName"));
    }

    @Test
    void mfaSettingsSurviveUserSerialization() throws Exception {
        registerAuthenticator();
        service.adminSetUserMFAPreference(pool.getId(), USERNAME, MfaSettingsUpdate.NONE, TOTP_PREFERRED);

        ObjectMapper mapper = new ObjectMapper();
        CognitoUser restored = mapper.readValue(
                mapper.writeValueAsBytes(service.adminGetUser(pool.getId(), USERNAME)), CognitoUser.class);
        assertEquals(List.of("SOFTWARE_TOKEN_MFA"), mfaSettingList(restored));
        assertEquals("SOFTWARE_TOKEN_MFA", preferredMfaSetting(restored));
    }

    @Test
    void requiredPoolReportsTheAuthenticatorItAsksFor() {
        service.setUserPoolMfaConfig(pool.getId(), "OFF", null, false);
        String accessToken = accessToken();
        service.setUserPoolMfaConfig(pool.getId(), "ON", true, false);
        String secret = (String) service.associateSoftwareToken(accessToken, null).get("SecretCode");
        service.verifySoftwareToken(accessToken, null, CognitoTotp.code(secret, clock.instant()));

        assertEquals("SOFTWARE_TOKEN_MFA", passwordLogin().get("ChallengeName"));
        CognitoUser user = service.adminGetUser(pool.getId(), USERNAME);
        assertEquals(List.of("SOFTWARE_TOKEN_MFA"), mfaSettingList(user));
        assertNull(preferredMfaSetting(user));

        service.setUserPoolMfaConfig(pool.getId(), "OPTIONAL", true, false);
        assertTrue(mfaSettingList(user).isEmpty());
        assertNotNull(passwordLogin().get("AuthenticationResult"));
    }

    private List<String> mfaSettingList(CognitoUser user) {
        return CognitoService.userMfaSettingList(service.describeUserPool(pool.getId()), user);
    }

    private String preferredMfaSetting(CognitoUser user) {
        return CognitoService.preferredMfaSetting(service.describeUserPool(pool.getId()), user);
    }

    private String registerAuthenticator() {
        String accessToken = accessToken();
        String secret = (String) service.associateSoftwareToken(accessToken, null).get("SecretCode");
        assertEquals("SUCCESS", service.verifySoftwareToken(accessToken, null,
                CognitoTotp.code(secret, clock.instant())).get("Status"));
        return secret;
    }

    private String accessToken() {
        return (String) ((Map<?, ?>) passwordLogin().get("AuthenticationResult")).get("AccessToken");
    }

    private Map<String, Object> passwordLogin() {
        return service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", USERNAME, "PASSWORD", PASSWORD));
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
}
