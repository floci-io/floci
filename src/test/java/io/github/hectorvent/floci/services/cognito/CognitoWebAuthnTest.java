package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import io.github.hectorvent.floci.services.cognito.model.WebAuthnConfiguration;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.util.ArrayList;
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

class CognitoWebAuthnTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USERNAME = "alice";
    private static final String PASSWORD = "Perm1234!";
    private static final String RP_ID = "localhost";
    private static final String ORIGIN = "http://localhost:3000";

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
        pool = createPool(List.of("PASSWORD", "WEB_AUTHN"));
        service.setUserPoolMfaConfig(pool.getId(), "OFF", null, false, webAuthnConfiguration(RP_ID, null, null));
        client = createClient(pool);
        service.adminCreateUser(pool.getId(), USERNAME, Map.of("email", "alice@example.com"), null);
        service.adminSetUserPassword(pool.getId(), USERNAME, PASSWORD, true);
    }

    @Test
    void registeredPasskeySignsInThroughUserAuth() throws Exception {
        String accessToken = accessToken();
        JsonNode options = creationOptions(accessToken);
        assertEquals(RP_ID, options.path("rp").path("id").asText());
        assertEquals("required", options.path("authenticatorSelection").path("residentKey").asText());
        assertEquals("preferred", options.path("authenticatorSelection").path("userVerification").asText());
        assertEquals(List.of(-7, -257), List.of(options.path("pubKeyCredParams").get(0).path("alg").asInt(),
                options.path("pubKeyCredParams").get(1).path("alg").asInt()));
        String sub = service.adminGetUser(pool.getId(), USERNAME).getAttributes().get("sub");
        assertEquals(WebAuthnTestAuthenticator.base64Url(sub.getBytes()), options.path("user").path("id").asText());

        WebAuthnTestAuthenticator authenticator = WebAuthnTestAuthenticator.es256(ORIGIN);
        service.completeWebAuthnRegistration(accessToken, authenticator.register(options, "none"));

        Map<String, Object> listed = service.listWebAuthnCredentials(accessToken, null, null);
        List<?> credentials = (List<?>) listed.get("Credentials");
        assertEquals(1, credentials.size());
        Map<?, ?> description = (Map<?, ?>) credentials.get(0);
        assertEquals(authenticator.credentialId(), description.get("CredentialId"));
        assertEquals(RP_ID, description.get("RelyingPartyId"));
        assertEquals("platform", description.get("AuthenticatorAttachment"));
        assertEquals(List.of("internal", "hybrid"), description.get("AuthenticatorTransports"));
        assertNotNull(description.get("FriendlyCredentialName"));
        assertFalse(listed.containsKey("NextToken"));

        Map<String, Object> challenge = userAuth("WEB_AUTHN");
        assertEquals("WEB_AUTHN", challenge.get("ChallengeName"));
        JsonNode requestOptions = JSON.readTree(challengeParameters(challenge).get("CREDENTIAL_REQUEST_OPTIONS"));
        assertEquals(RP_ID, requestOptions.path("rpId").asText());
        assertEquals(authenticator.credentialId(), requestOptions.path("allowCredentials").get(0).path("id").asText());
        assertEquals("preferred", requestOptions.path("userVerification").asText());

        String session = (String) challenge.get("Session");
        Map<String, String> answer = Map.of("USERNAME", USERNAME,
                "CREDENTIAL", authenticator.authenticate(requestOptions.toString()));
        Map<String, Object> signedIn = service.respondToAuthChallenge(client.getClientId(), "WEB_AUTHN", session, answer);
        assertNotNull(((Map<?, ?>) signedIn.get("AuthenticationResult")).get("AccessToken"));
        assertEquals(1, service.adminGetUser(pool.getId(), USERNAME).getWebAuthnCredentials().get(0).getSignCount());
        assertEquals("NotAuthorizedException", assertThrows(AwsException.class,
                () -> service.respondToAuthChallenge(client.getClientId(), "WEB_AUTHN", session, answer))
                .getErrorCode());
    }

    @Test
    void packedSelfAttestationWithRs256IsAccepted() throws Exception {
        String accessToken = accessToken();
        WebAuthnTestAuthenticator authenticator = WebAuthnTestAuthenticator.rs256(ORIGIN);
        service.completeWebAuthnRegistration(accessToken, authenticator.register(creationOptions(accessToken),
                "packed"));

        Map<String, Object> challenge = userAuth("WEB_AUTHN");
        String credential = authenticator.authenticate(challengeParameters(challenge).get("CREDENTIAL_REQUEST_OPTIONS"));
        assertNotNull(service.respondToAuthChallenge(client.getClientId(), "WEB_AUTHN",
                (String) challenge.get("Session"), Map.of("USERNAME", USERNAME, "CREDENTIAL", credential))
                .get("AuthenticationResult"));
    }

    @Test
    void packedAttestationSignatureIsChecked() throws Exception {
        String accessToken = accessToken();
        JsonNode options = creationOptions(accessToken);
        WebAuthnTestAuthenticator forged = WebAuthnTestAuthenticator.es256(ORIGIN).corruptAttestation();
        assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                () -> service.completeWebAuthnRegistration(accessToken, forged.register(options, "packed")))
                .getErrorCode());
        assertTrue(service.adminGetUser(pool.getId(), USERNAME).getWebAuthnCredentials().isEmpty());
    }

    @Test
    void registrationRejectsCredentialsCognitoDoesNotAccept() throws Exception {
        String accessToken = accessToken();
        assertRegistrationFails("WebAuthnCredentialNotSupportedException", accessToken,
                WebAuthnTestAuthenticator.es384(ORIGIN));
        assertRegistrationFails("WebAuthnOriginNotAllowedException", accessToken,
                WebAuthnTestAuthenticator.es256("https://evil.example"));
        assertRegistrationFails("WebAuthnRelyingPartyMismatchException", accessToken,
                WebAuthnTestAuthenticator.es256(ORIGIN).relyingPartyId("example.com"));

        AwsException noChallenge = assertThrows(AwsException.class, () -> service.completeWebAuthnRegistration(
                accessToken, WebAuthnTestAuthenticator.es256(ORIGIN).register(JSON.readTree(
                        "{\"challenge\":\"AAAA\",\"rp\":{\"id\":\"localhost\"}}"), "none")));
        assertEquals("WebAuthnChallengeNotFoundException", noChallenge.getErrorCode());
        assertTrue(service.adminGetUser(pool.getId(), USERNAME).getWebAuthnCredentials().isEmpty());
    }

    @Test
    void httpsSubdomainOriginsAreAllowedAndHttpOnlyForLocalhost() throws Exception {
        service.setUserPoolMfaConfig(pool.getId(), "OFF", null, false,
                webAuthnConfiguration("example.com", null, null));
        String accessToken = accessToken();
        assertRegistrationFails("WebAuthnOriginNotAllowedException", accessToken,
                WebAuthnTestAuthenticator.es256("http://app.example.com"));
        service.completeWebAuthnRegistration(accessToken,
                WebAuthnTestAuthenticator.es256("https://app.example.com").register(creationOptions(accessToken),
                        "none"));
        assertEquals(1, service.adminGetUser(pool.getId(), USERNAME).getWebAuthnCredentials().size());
    }

    @Test
    void registrationChallengeExpiresAndBelongsToTheStartingClient() throws Exception {
        String accessToken = accessToken();
        JsonNode options = creationOptions(accessToken);
        UserPoolClient other = createClient(pool);
        String otherToken = (String) ((Map<?, ?>) service.initiateAuth(other.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", USERNAME, "PASSWORD", PASSWORD)).get("AuthenticationResult")).get("AccessToken");
        WebAuthnTestAuthenticator authenticator = WebAuthnTestAuthenticator.es256(ORIGIN);
        assertEquals("WebAuthnClientMismatchException", assertThrows(AwsException.class,
                () -> service.completeWebAuthnRegistration(otherToken, authenticator.register(options, "none")))
                .getErrorCode());
        service.completeWebAuthnRegistration(accessToken, authenticator.register(options, "none"));
        assertEquals(1, service.adminGetUser(pool.getId(), USERNAME).getWebAuthnCredentials().size());

        JsonNode expiring = creationOptions(accessToken);
        clock.advance(Duration.ofMinutes(6));
        String laterToken = accessToken();
        assertEquals("WebAuthnChallengeNotFoundException", assertThrows(AwsException.class,
                () -> service.completeWebAuthnRegistration(laterToken,
                        WebAuthnTestAuthenticator.es256(ORIGIN).register(expiring, "none"))).getErrorCode());
    }

    @Test
    void requiredUserVerificationRejectsAnAuthenticatorThatSkipsIt() throws Exception {
        service.setUserPoolMfaConfig(pool.getId(), "OFF", null, false,
                webAuthnConfiguration(RP_ID, "required", null));
        String accessToken = accessToken();
        assertRegistrationFails("WebAuthnCredentialNotSupportedException", accessToken,
                WebAuthnTestAuthenticator.es256(ORIGIN).userVerified(false));

        WebAuthnTestAuthenticator authenticator = WebAuthnTestAuthenticator.es256(ORIGIN);
        service.completeWebAuthnRegistration(accessToken, authenticator.register(creationOptions(accessToken), "none"));
        authenticator.userVerified(false);
        Map<String, Object> challenge = userAuth("WEB_AUTHN");
        String credential = authenticator.authenticate(challengeParameters(challenge).get("CREDENTIAL_REQUEST_OPTIONS"));
        assertEquals("NotAuthorizedException", assertThrows(AwsException.class,
                () -> service.respondToAuthChallenge(client.getClientId(), "WEB_AUTHN",
                        (String) challenge.get("Session"), Map.of("USERNAME", USERNAME, "CREDENTIAL", credential)))
                .getErrorCode());
    }

    @Test
    void signInRejectsABadSignatureAndAnUnknownPasskey() throws Exception {
        String accessToken = accessToken();
        WebAuthnTestAuthenticator registered = WebAuthnTestAuthenticator.es256(ORIGIN);
        service.completeWebAuthnRegistration(accessToken, registered.register(creationOptions(accessToken), "none"));

        Map<String, Object> challenge = userAuth("WEB_AUTHN");
        ObjectNode tampered = (ObjectNode) JSON.readTree(
                registered.authenticate(challengeParameters(challenge).get("CREDENTIAL_REQUEST_OPTIONS")));
        ObjectNode response = (ObjectNode) tampered.path("response");
        byte[] signature = Base64.getUrlDecoder().decode(response.path("signature").asText());
        signature[signature.length - 1] ^= 0x01;
        response.put("signature", WebAuthnTestAuthenticator.base64Url(signature));
        assertEquals("NotAuthorizedException", assertThrows(AwsException.class,
                () -> service.respondToAuthChallenge(client.getClientId(), "WEB_AUTHN",
                        (String) challenge.get("Session"),
                        Map.of("USERNAME", USERNAME, "CREDENTIAL", tampered.toString()))).getErrorCode());

        Map<String, Object> retry = userAuth("WEB_AUTHN");
        String unknown = WebAuthnTestAuthenticator.es256(ORIGIN)
                .authenticate(challengeParameters(retry).get("CREDENTIAL_REQUEST_OPTIONS"));
        assertEquals("NotAuthorizedException", assertThrows(AwsException.class,
                () -> service.respondToAuthChallenge(client.getClientId(), "WEB_AUTHN",
                        (String) retry.get("Session"), Map.of("USERNAME", USERNAME, "CREDENTIAL", unknown)))
                .getErrorCode());
    }

    @Test
    void selectChallengeOffersPasskeysOnceRegisteredAndDeletingRemovesThem() throws Exception {
        String accessToken = accessToken();
        assertEquals(List.of("PASSWORD", "PASSWORD_SRP"), userAuth(null).get("AvailableChallenges"));

        WebAuthnTestAuthenticator authenticator = WebAuthnTestAuthenticator.es256(ORIGIN);
        service.completeWebAuthnRegistration(accessToken, authenticator.register(creationOptions(accessToken), "none"));
        Map<String, Object> select = userAuth(null);
        assertEquals("SELECT_CHALLENGE", select.get("ChallengeName"));
        assertEquals(List.of("PASSWORD", "PASSWORD_SRP", "WEB_AUTHN"), select.get("AvailableChallenges"));
        assertEquals(List.of("PASSWORD", "WEB_AUTHN"),
                service.getUserAuthFactors(accessToken).get("ConfiguredUserAuthFactors"));

        Map<String, Object> chosen = service.respondToAuthChallenge(client.getClientId(), "SELECT_CHALLENGE",
                (String) select.get("Session"), Map.of("USERNAME", USERNAME, "ANSWER", "WEB_AUTHN"));
        assertEquals("WEB_AUTHN", chosen.get("ChallengeName"));
        assertFalse(chosen.containsKey("AvailableChallenges"));
        String credential = authenticator.authenticate(challengeParameters(chosen).get("CREDENTIAL_REQUEST_OPTIONS"));
        assertNotNull(service.respondToAuthChallenge(client.getClientId(), "WEB_AUTHN", (String) chosen.get("Session"),
                Map.of("USERNAME", USERNAME, "CREDENTIAL", credential)).get("AuthenticationResult"));

        service.deleteWebAuthnCredential(accessToken, authenticator.credentialId());
        assertEquals(List.of("PASSWORD", "PASSWORD_SRP"), userAuth(null).get("AvailableChallenges"));
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.deleteWebAuthnCredential(accessToken, authenticator.credentialId())).getErrorCode());
    }

    @Test
    void poolsWithoutPasskeysOrARelyingPartyAreRefused() {
        UserPool passwordOnly = createPool(List.of("PASSWORD"));
        UserPoolClient passwordClient = createClient(passwordOnly);
        service.adminCreateUser(passwordOnly.getId(), USERNAME, Map.of(), null);
        service.adminSetUserPassword(passwordOnly.getId(), USERNAME, PASSWORD, true);
        String passwordToken = accessToken(passwordClient);
        assertEquals("WebAuthnNotEnabledException", assertThrows(AwsException.class,
                () -> service.startWebAuthnRegistration(passwordToken)).getErrorCode());

        UserPool unconfigured = createPool(List.of("PASSWORD", "WEB_AUTHN"));
        UserPoolClient unconfiguredClient = createClient(unconfigured);
        service.adminCreateUser(unconfigured.getId(), USERNAME, Map.of(), null);
        service.adminSetUserPassword(unconfigured.getId(), USERNAME, PASSWORD, true);
        String unconfiguredToken = accessToken(unconfiguredClient);
        assertEquals("WebAuthnConfigurationMissingException", assertThrows(AwsException.class,
                () -> service.startWebAuthnRegistration(unconfiguredToken)).getErrorCode());

        service.createUserPoolDomain("passkeys-prefix", unconfigured.getId(), null, null);
        Map<?, ?> options = (Map<?, ?>) service.startWebAuthnRegistration(unconfiguredToken)
                .get("CredentialCreationOptions");
        assertEquals("passkeys-prefix.auth.us-east-1.amazoncognito.com", ((Map<?, ?>) options.get("rp")).get("id"));
    }

    @Test
    void requiredMfaOffersPasskeysOnlyToUsersWhoTurnedPasskeyMfaOn() throws Exception {
        String accessToken = accessToken();
        WebAuthnTestAuthenticator authenticator = WebAuthnTestAuthenticator.es256(ORIGIN);
        service.completeWebAuthnRegistration(accessToken, authenticator.register(creationOptions(accessToken), "none"));

        service.setUserPoolMfaConfig(pool.getId(), "ON", true, false, null);
        assertEquals(List.of("PASSWORD", "PASSWORD_SRP"), userAuth(null).get("AvailableChallenges"));
        assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                () -> service.setUserMFAPreference(accessToken, null, null, true)).getErrorCode(),
                "passkey MFA needs a pool whose passkeys can satisfy MFA");
        assertFalse(service.adminGetUser(pool.getId(), USERNAME).isWebAuthnMfaEnabled());

        service.setUserPoolMfaConfig(pool.getId(), "ON", true, false,
                webAuthnConfiguration(RP_ID, "preferred", "MULTI_FACTOR_WITH_USER_VERIFICATION"));
        assertEquals(List.of("PASSWORD", "PASSWORD_SRP"), userAuth(null).get("AvailableChallenges"),
                "the pool's FactorConfiguration alone does not let a passkey skip MFA");
        assertEquals("SELECT_CHALLENGE", userAuth("WEB_AUTHN").get("ChallengeName"));

        service.setUserMFAPreference(accessToken, null, null, true);
        assertTrue(service.adminGetUser(pool.getId(), USERNAME).isWebAuthnMfaEnabled());
        Map<String, Object> unverified = userAuth("WEB_AUTHN");
        String requestOptions = challengeParameters(unverified).get("CREDENTIAL_REQUEST_OPTIONS");
        assertEquals("required", JSON.readTree(requestOptions).path("userVerification").asText(),
                "a passkey standing in for required MFA must verify the user");
        String withoutVerification = authenticator.userVerified(false).authenticate(requestOptions);
        assertEquals("NotAuthorizedException", assertThrows(AwsException.class,
                () -> service.respondToAuthChallenge(client.getClientId(), "WEB_AUTHN",
                        (String) unverified.get("Session"),
                        Map.of("USERNAME", USERNAME, "CREDENTIAL", withoutVerification))).getErrorCode());

        authenticator.userVerified(true);
        Map<String, Object> challenge = userAuth("WEB_AUTHN");
        assertEquals("WEB_AUTHN", challenge.get("ChallengeName"));
        String credential = authenticator.authenticate(challengeParameters(challenge).get("CREDENTIAL_REQUEST_OPTIONS"));
        Map<String, Object> signedIn = service.respondToAuthChallenge(client.getClientId(), "WEB_AUTHN",
                (String) challenge.get("Session"), Map.of("USERNAME", USERNAME, "CREDENTIAL", credential));
        assertNotNull(signedIn.get("AuthenticationResult"), "a verified passkey satisfies required MFA");
        assertNull(signedIn.get("ChallengeName"));

        service.adminSetUserMFAPreference(pool.getId(), USERNAME, null, null, false);
        assertEquals(List.of("PASSWORD", "PASSWORD_SRP"), userAuth(null).get("AvailableChallenges"));
    }

    @Test
    void passkeyMfaTurnedOffMidSignInLeavesMfaToBeDone() throws Exception {
        String accessToken = accessToken();
        WebAuthnTestAuthenticator authenticator = WebAuthnTestAuthenticator.es256(ORIGIN);
        service.completeWebAuthnRegistration(accessToken, authenticator.register(creationOptions(accessToken), "none"));
        service.setUserPoolMfaConfig(pool.getId(), "ON", true, false,
                webAuthnConfiguration(RP_ID, "preferred", "MULTI_FACTOR_WITH_USER_VERIFICATION"));
        service.setUserMFAPreference(accessToken, null, null, true);

        Map<String, Object> challenge = userAuth("WEB_AUTHN");
        assertEquals("WEB_AUTHN", challenge.get("ChallengeName"));
        String credential = authenticator.authenticate(challengeParameters(challenge).get("CREDENTIAL_REQUEST_OPTIONS"));
        service.adminSetUserMFAPreference(pool.getId(), USERNAME, null, null, false);

        Map<String, Object> answered = service.respondToAuthChallenge(client.getClientId(), "WEB_AUTHN",
                (String) challenge.get("Session"), Map.of("USERNAME", USERNAME, "CREDENTIAL", credential));
        assertNull(answered.get("AuthenticationResult"), "the passkey no longer stands in for MFA");
        assertEquals("MFA_SETUP", answered.get("ChallengeName"));
    }

    @Test
    void optionalMfaAsksForUserVerificationOnlyFromUsersWithPasskeyMfa() throws Exception {
        String accessToken = accessToken();
        WebAuthnTestAuthenticator authenticator = WebAuthnTestAuthenticator.es256(ORIGIN).userVerified(false);
        service.completeWebAuthnRegistration(accessToken, authenticator.register(creationOptions(accessToken), "none"));
        service.setUserPoolMfaConfig(pool.getId(), "OPTIONAL", true, false,
                webAuthnConfiguration(RP_ID, "preferred", "MULTI_FACTOR_WITH_USER_VERIFICATION"));

        Map<String, Object> challenge = userAuth("WEB_AUTHN");
        String requestOptions = challengeParameters(challenge).get("CREDENTIAL_REQUEST_OPTIONS");
        assertEquals("preferred", JSON.readTree(requestOptions).path("userVerification").asText());
        assertNotNull(service.respondToAuthChallenge(client.getClientId(), "WEB_AUTHN",
                (String) challenge.get("Session"),
                Map.of("USERNAME", USERNAME, "CREDENTIAL", authenticator.authenticate(requestOptions)))
                .get("AuthenticationResult"));

        service.setUserMFAPreference(accessToken, null, null, true);
        Map<String, Object> mfaChallenge = userAuth("WEB_AUTHN");
        String mfaOptions = challengeParameters(mfaChallenge).get("CREDENTIAL_REQUEST_OPTIONS");
        assertEquals("required", JSON.readTree(mfaOptions).path("userVerification").asText());
        String withoutVerification = authenticator.authenticate(mfaOptions);
        assertEquals("NotAuthorizedException", assertThrows(AwsException.class,
                () -> service.respondToAuthChallenge(client.getClientId(), "WEB_AUTHN",
                        (String) mfaChallenge.get("Session"),
                        Map.of("USERNAME", USERNAME, "CREDENTIAL", withoutVerification))).getErrorCode());
    }

    @Test
    void userCanRegisterAtMostTwentyPasskeys() throws Exception {
        String accessToken = accessToken();
        for (int i = 0; i < CognitoWebAuthn.MAX_CREDENTIALS_PER_USER; i++) {
            service.completeWebAuthnRegistration(accessToken,
                    WebAuthnTestAuthenticator.es256(ORIGIN).register(creationOptions(accessToken), "none"));
        }
        assertEquals("LimitExceededException", assertThrows(AwsException.class,
                () -> service.startWebAuthnRegistration(accessToken)).getErrorCode());

        Map<String, Object> firstPage = service.listWebAuthnCredentials(accessToken, 15, null);
        assertEquals(15, ((List<?>) firstPage.get("Credentials")).size());
        Map<String, Object> secondPage = service.listWebAuthnCredentials(accessToken, 15,
                (String) firstPage.get("NextToken"));
        assertEquals(5, ((List<?>) secondPage.get("Credentials")).size());
        assertFalse(secondPage.containsKey("NextToken"));
    }

    @Test
    void pagesListPasskeysOldestFirstAndSkipNoneWhenOneIsDeleted() throws Exception {
        String accessToken = accessToken();
        List<String> registered = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            WebAuthnTestAuthenticator authenticator = WebAuthnTestAuthenticator.es256(ORIGIN);
            service.completeWebAuthnRegistration(accessToken, authenticator.register(creationOptions(accessToken),
                    "none"));
            registered.add(authenticator.credentialId());
            clock.advance(Duration.ofMillis(1));
        }

        Map<String, Object> firstPage = service.listWebAuthnCredentials(accessToken, 1, null);
        assertEquals(List.of(registered.get(0)), credentialIds(firstPage));
        service.deleteWebAuthnCredential(accessToken, registered.get(0));
        Map<String, Object> secondPage = service.listWebAuthnCredentials(accessToken, 1,
                (String) firstPage.get("NextToken"));
        assertEquals(List.of(registered.get(1)), credentialIds(secondPage),
                "deleting a listed passkey does not move the next page past one");
        Map<String, Object> lastPage = service.listWebAuthnCredentials(accessToken, 1,
                (String) secondPage.get("NextToken"));
        assertEquals(List.of(registered.get(2)), credentialIds(lastPage));
        assertFalse(lastPage.containsKey("NextToken"));

        assertEquals(List.of(registered.get(1), registered.get(2)),
                credentialIds(service.listWebAuthnCredentials(accessToken, 0, null)));
        for (String token : List.of("not-a-token!", Base64.getUrlEncoder().encodeToString("ListUsers:x".getBytes()))) {
            assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                    () -> service.listWebAuthnCredentials(accessToken, null, token)).getErrorCode());
        }
        AwsException negative = assertThrows(AwsException.class,
                () -> service.listWebAuthnCredentials(accessToken, -1, null));
        assertTrue(negative.getMessage().endsWith("Member must have value greater than or equal to 0"),
                negative.getMessage());
    }

    @Test
    void passkeysSurviveUserSerialization() throws Exception {
        String accessToken = accessToken();
        WebAuthnTestAuthenticator authenticator = WebAuthnTestAuthenticator.es256(ORIGIN);
        service.completeWebAuthnRegistration(accessToken, authenticator.register(creationOptions(accessToken), "none"));

        CognitoUser restored = JSON.readValue(
                JSON.writeValueAsBytes(service.adminGetUser(pool.getId(), USERNAME)), CognitoUser.class);
        assertEquals(authenticator.credentialId(), restored.getWebAuthnCredentials().get(0).getCredentialId());
        assertEquals(service.adminGetUser(pool.getId(), USERNAME).getWebAuthnCredentials().get(0)
                .getAttestedCredentialData(), restored.getWebAuthnCredentials().get(0).getAttestedCredentialData());

        service.setUserPoolMfaConfig(pool.getId(), "OPTIONAL", true, false,
                webAuthnConfiguration(RP_ID, null, "MULTI_FACTOR_WITH_USER_VERIFICATION"));
        service.setUserMFAPreference(accessToken, null, null, true);
        assertTrue(JSON.readValue(JSON.writeValueAsBytes(service.adminGetUser(pool.getId(), USERNAME)),
                CognitoUser.class).isWebAuthnMfaEnabled());
    }

    private void assertRegistrationFails(String errorCode, String accessToken, WebAuthnTestAuthenticator authenticator)
            throws Exception {
        JsonNode options = creationOptions(accessToken);
        Executable complete = () -> service.completeWebAuthnRegistration(accessToken,
                authenticator.register(options, "none"));
        assertEquals(errorCode, assertThrows(AwsException.class, complete).getErrorCode());
    }

    private static List<String> credentialIds(Map<String, Object> page) {
        return ((List<?>) page.get("Credentials")).stream()
                .map(description -> (String) ((Map<?, ?>) description).get("CredentialId"))
                .toList();
    }

    private JsonNode creationOptions(String accessToken) {
        return JSON.valueToTree(service.startWebAuthnRegistration(accessToken).get("CredentialCreationOptions"));
    }

    private Map<String, Object> userAuth(String preferredChallenge) {
        Map<String, String> params = preferredChallenge == null
                ? Map.of("USERNAME", USERNAME)
                : Map.of("USERNAME", USERNAME, "PREFERRED_CHALLENGE", preferredChallenge);
        return service.initiateAuth(client.getClientId(), "USER_AUTH", params);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> challengeParameters(Map<String, Object> challenge) {
        return (Map<String, String>) challenge.get("ChallengeParameters");
    }

    private String accessToken() {
        return accessToken(client);
    }

    private String accessToken(UserPoolClient appClient) {
        return (String) ((Map<?, ?>) service.initiateAuth(appClient.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", USERNAME, "PASSWORD", PASSWORD)).get("AuthenticationResult")).get("AccessToken");
    }

    private UserPool createPool(List<String> allowedFirstAuthFactors) {
        return service.createUserPool(Map.of("PoolName", "PasskeyPool",
                "Policies", Map.of("SignInPolicy", Map.of("AllowedFirstAuthFactors", allowedFirstAuthFactors))),
                "us-east-1");
    }

    private UserPoolClient createClient(UserPool userPool) {
        UserPoolClient appClient = service.createUserPoolClient(userPool.getId(), "passkey-client", false, false,
                List.of(), List.of());
        appClient.setExplicitAuthFlows(List.of("ALLOW_USER_PASSWORD_AUTH", "ALLOW_USER_AUTH"));
        return appClient;
    }

    private static WebAuthnConfiguration webAuthnConfiguration(String relyingPartyId, String userVerification,
                                                               String factorConfiguration) {
        WebAuthnConfiguration configuration = new WebAuthnConfiguration();
        configuration.setRelyingPartyId(relyingPartyId);
        configuration.setUserVerification(userVerification);
        configuration.setFactorConfiguration(factorConfiguration);
        return configuration;
    }
}
