package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cognito.model.CognitoAuthorizationCode;
import io.github.hectorvent.floci.services.cognito.model.CognitoAuthorizationTransaction;
import io.github.hectorvent.floci.services.cognito.model.CognitoManagedLoginSession;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CognitoOAuthControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC);
    private static final String POOL_ID = "us-east-1_pool";
    private static final String CLIENT_ID = "cognito-client";
    private static final String CALLBACK_URI = "https://application.example.test/callback";
    private static final String RFC_7636_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String RFC_7636_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CognitoService cognitoService = mock(CognitoService.class);
    private final CognitoFederationService federationService = mock(CognitoFederationService.class);
    private CognitoFederationStateStore stateStore;
    private CognitoOAuthController controller;
    private UserPoolClient client;

    @BeforeEach
    void setUp() {
        stateStore = new CognitoFederationStateStore(CLOCK);
        CognitoManagedLoginService managedLoginService = new CognitoManagedLoginService(cognitoService, stateStore, CLOCK);
        controller = new CognitoOAuthController(cognitoService, objectMapper, federationService, stateStore,
                managedLoginService);
        client = client();
        when(cognitoService.findClientById(CLIENT_ID)).thenReturn(client);
    }

    @Test
    void authorizeRedirectsToConfiguredIdentityProvider() {
        when(federationService.beginAuthorization(POOL_ID, CLIENT_ID, CALLBACK_URI, List.of("openid"), "nonce", "ExampleOidc",
                " relying-party-state ", RFC_7636_CHALLENGE))
                .thenReturn("https://provider.example.test/authorize?state=provider-state");

        Response response = controller.authorize(requestContext(null), CLIENT_ID, CALLBACK_URI, "code", "openid", "nonce",
                "ExampleOidc", " relying-party-state ", RFC_7636_CHALLENGE, "S256", null);

        assertEquals(302, response.getStatus());
        assertEquals("https://provider.example.test/authorize?state=provider-state", response.getHeaderString("Location"));
    }

    @Test
    void authorizeReturnsInvalidClientForUnknownClient() {
        when(cognitoService.findClientById("missing-client"))
                .thenThrow(new AwsException("ResourceNotFoundException", "Client not found", 400));

        Response response = controller.authorize(requestContext(null), "missing-client", CALLBACK_URI, "code", "openid", null,
                "ExampleOidc", null, null, null, null);

        assertOAuthError(response, "invalid_client");
    }

    @Test
    void authorizeReturnsInvalidRequestForUnregisteredRedirectUri() {
        Response response = controller.authorize(requestContext(null), CLIENT_ID, "https://other.example.test/callback", "code", "openid",
                null, "ExampleOidc", null, null, null, null);

        assertOAuthError(response, "invalid_request");
    }

    @Test
    void authorizeReturnsUnsupportedResponseTypeForTokenResponse() {
        Response response = controller.authorize(requestContext(null), CLIENT_ID, CALLBACK_URI, "token", "openid", null, "ExampleOidc",
                null, null, null, null);

        assertOAuthError(response, "unsupported_response_type");
    }

    @Test
    void authorizeWithoutIdentityProviderRedirectsToTheLoginEndpointWithTheRequest() {
        Response response = controller.authorize(requestContext(null), CLIENT_ID, CALLBACK_URI, "code", "openid email",
                "nonce", null, "state & more", RFC_7636_CHALLENGE, "S256", null);

        assertEquals(302, response.getStatus());
        assertEquals("/cognito-idp/login?response_type=code&client_id=" + CLIENT_ID
                        + "&redirect_uri=https%3A%2F%2Fapplication.example.test%2Fcallback&scope=openid+email"
                        + "&state=state+%26+more&nonce=nonce&code_challenge=" + RFC_7636_CHALLENGE
                        + "&code_challenge_method=S256",
                response.getHeaderString("Location"));
    }

    @Test
    void authorizeWithCognitoProviderOnACustomDomainRedirectsToItsLoginPath() {
        Response response = controller.authorize(requestContext(POOL_ID), CLIENT_ID, CALLBACK_URI, "code", null, null,
                "COGNITO", null, null, null, null);

        assertEquals(302, response.getStatus());
        assertEquals("/login?response_type=code&client_id=" + CLIENT_ID
                + "&redirect_uri=https%3A%2F%2Fapplication.example.test%2Fcallback", response.getHeaderString("Location"));
    }

    @Test
    void authorizeRejectsManagedLoginForAClientWithoutCognito() {
        client.setSupportedIdentityProviders(List.of("ExampleOidc"));

        Response response = controller.authorize(requestContext(null), CLIENT_ID, CALLBACK_URI, "code", "openid", null,
                null, null, null, null, null);

        assertOAuthError(response, "invalid_request");
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM, plain",
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM, null",
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM, s256",
            "null, S256",
            "too-short, S256",
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-c=, S256",
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cMx, S256"})
    void authorizeRejectsUnusablePkceParametersForEitherProvider(String challenge, String method) {
        for (String provider : new String[] {null, "ExampleOidc"}) {
            Response response = controller.authorize(requestContext(null), CLIENT_ID, CALLBACK_URI, "code", "openid", null,
                    provider, null, challenge, method, null);

            assertOAuthError(response, "invalid_request");
            String description = ((JsonNode) response.getEntity()).path("error_description").asText();
            assertTrue(description.startsWith("code_challenge"), description);
        }
    }

    @Test
    void authorizeWithASessionOfThePoolRedirectsWithACodeCarryingTheNonceAndChallenge() {
        when(cognitoService.adminGetUser(POOL_ID, "session-user")).thenReturn(user("session-user"));
        String sessionId = stateStore.putSession(new CognitoManagedLoginSession(POOL_ID, "session-user",
                CLOCK.instant().plusSeconds(60)));

        Response response = controller.authorize(requestContext(null), CLIENT_ID, CALLBACK_URI, "code", "openid", "nonce",
                null, "relying-party-state", RFC_7636_CHALLENGE, "S256", sessionId);

        assertEquals(302, response.getStatus());
        String location = response.getHeaderString("Location");
        assertTrue(location.startsWith(CALLBACK_URI + "?code="), location);
        assertTrue(location.endsWith("&state=relying-party-state"), location);
        String code = location.substring((CALLBACK_URI + "?code=").length(), location.indexOf("&state="));
        CognitoAuthorizationCode storedCode = stateStore.consumeAuthorizationCode(code).orElseThrow();
        assertEquals("session-user", storedCode.userId());
        assertEquals("nonce", storedCode.nonce());
        assertEquals(RFC_7636_CHALLENGE, storedCode.codeChallenge());
    }

    @Test
    void authorizeIgnoresASessionOfAnotherPool() {
        String sessionId = stateStore.putSession(new CognitoManagedLoginSession("us-east-1_other", "session-user",
                CLOCK.instant().plusSeconds(60)));

        Response response = controller.authorize(requestContext(null), CLIENT_ID, CALLBACK_URI, "code", null, null,
                null, null, null, null, sessionId);

        assertEquals(302, response.getStatus());
        assertTrue(response.getHeaderString("Location").startsWith("/cognito-idp/login?"));
        assertTrue(stateStore.findSession(sessionId).isPresent(), "another pool's session is left alone");
    }

    @Test
    void authorizeEndsTheSessionOfADisabledUser() {
        CognitoUser disabled = user("session-user");
        disabled.setEnabled(false);
        when(cognitoService.adminGetUser(POOL_ID, "session-user")).thenReturn(disabled);
        String sessionId = stateStore.putSession(new CognitoManagedLoginSession(POOL_ID, "session-user",
                CLOCK.instant().plusSeconds(60)));

        Response response = controller.authorize(requestContext(null), CLIENT_ID, CALLBACK_URI, "code", null, null,
                null, null, null, null, sessionId);

        assertTrue(response.getHeaderString("Location").startsWith("/cognito-idp/login?"));
        assertTrue(stateStore.findSession(sessionId).isEmpty());
    }

    @Test
    void idpResponseReturnsInvalidRequestForUnknownState() {
        Response response = controller.idpResponse(requestContext(null), "unknown-state", "provider-code", null, null);

        assertOAuthError(response, "invalid_request");
    }

    @Test
    void idpResponseRedirectsProviderErrorToApplication() {
        String state = putTransaction("relying-party-state");

        Response response = controller.idpResponse(requestContext(null), state, null, "access_denied", "The provider refused access");

        assertEquals(302, response.getStatus());
        assertEquals(CALLBACK_URI + "?error=access_denied&error_description=The+provider+refused+access&state=relying-party-state",
                response.getHeaderString("Location"));
    }

    @Test
    void idpResponseRedirectsAuthorizationCodeToApplication() {
        String state = putTransaction("relying-party-state");
        when(federationService.completeAuthorization(any(CognitoAuthorizationTransaction.class), eq("provider-code")))
                .thenReturn("authorization-code");

        Response response = controller.idpResponse(requestContext(null), state, "provider-code", null, null);

        assertEquals(302, response.getStatus());
        assertEquals(CALLBACK_URI + "?code=authorization-code&state=relying-party-state", response.getHeaderString("Location"));
    }

    @Test
    void tokenRedeemsAuthorizationCodeOnce() throws Exception {
        String code = stateStore.putAuthorizationCode(new CognitoAuthorizationCode(
                POOL_ID, CLIENT_ID, "federated-user", CALLBACK_URI, List.of("openid"), null, null,
                CLOCK.instant().plusSeconds(60)));
        when(cognitoService.describeUserPool(POOL_ID)).thenReturn(pool());
        when(cognitoService.adminGetUser(POOL_ID, "federated-user")).thenReturn(user());
        when(cognitoService.generateAuthResultForHostedAuth(any(CognitoUser.class), any(UserPool.class), eq(client), eq(null), any()))
                .thenReturn(Map.of("AccessToken", "access-token", "IdToken", "id-token", "RefreshToken", "refresh-token",
                        "ExpiresIn", 3600, "TokenType", "Bearer"));

        MultivaluedHashMap<String, String> form = form("grant_type", "authorization_code", "client_id", CLIENT_ID,
                "code", code, "redirect_uri", CALLBACK_URI);
        Response first = controller.token(null, requestContext(null), form);
        Response replay = controller.token(null, requestContext(null), form);

        assertEquals(200, first.getStatus());
        JsonNode body = (JsonNode) first.getEntity();
        assertEquals("access-token", body.path("access_token").asText());
        assertEquals("id-token", body.path("id_token").asText());
        assertEquals("refresh-token", body.path("refresh_token").asText());
        assertEquals(3600, body.path("expires_in").asInt());
        assertOAuthError(replay, "invalid_grant");
    }

    @Test
    void tokenRejectsAuthorizationCodeForAnotherClient() {
        String code = stateStore.putAuthorizationCode(new CognitoAuthorizationCode(
                POOL_ID, "other-client", "federated-user", CALLBACK_URI, List.of("openid"), null, null,
                CLOCK.instant().plusSeconds(60)));

        Response response = controller.token(null, requestContext(null), form("grant_type", "authorization_code",
                "client_id", CLIENT_ID, "code", code, "redirect_uri", CALLBACK_URI));

        assertOAuthError(response, "invalid_grant");
    }

    @Test
    void tokenDoesNotConsumeAuthorizationCodeWhenRedirectUriIsInvalid() {
        String code = putAuthorizationCode();

        Response invalid = controller.token(null, requestContext(null), form("grant_type", "authorization_code", "client_id", CLIENT_ID,
                "code", code, "redirect_uri", "https://application.example.test/other"));
        Response valid = controller.token(null, requestContext(null), validAuthorizationCodeForm(code));

        assertOAuthError(invalid, "invalid_grant");
        assertEquals(200, valid.getStatus());
    }

    @Test
    void tokenDoesNotConsumeAuthorizationCodeWhenClientDoesNotMatch() {
        UserPoolClient otherClient = client("other-client");
        when(cognitoService.findClientById("other-client")).thenReturn(otherClient);
        String code = putAuthorizationCode();

        Response invalid = controller.token(null, requestContext(null), form("grant_type", "authorization_code", "client_id", "other-client",
                "code", code, "redirect_uri", CALLBACK_URI));
        Response valid = controller.token(null, requestContext(null), validAuthorizationCodeForm(code));

        assertOAuthError(invalid, "invalid_grant");
        assertEquals(200, valid.getStatus());
    }

    @Test
    void tokenDoesNotConsumeAuthorizationCodeWhenClientSecretIsInvalid() {
        client.setClientSecret("correct-secret");
        String code = putAuthorizationCode();

        Response invalid = controller.token(null, requestContext(null), form("grant_type", "authorization_code", "client_id", CLIENT_ID,
                "client_secret", "wrong-secret", "code", code, "redirect_uri", CALLBACK_URI));
        Response valid = controller.token(null, requestContext(null), form("grant_type", "authorization_code", "client_id", CLIENT_ID,
                "client_secret", "correct-secret", "code", code, "redirect_uri", CALLBACK_URI));

        assertOAuthError(invalid, "invalid_client");
        assertEquals(200, valid.getStatus());
    }

    @Test
    void tokenDoesNotConsumeAuthorizationCodeWhenDomainDoesNotMatch() {
        String code = putAuthorizationCode();

        Response invalid = controller.token(null, requestContext("other-pool"), validAuthorizationCodeForm(code));
        Response valid = controller.token(null, requestContext(null), validAuthorizationCodeForm(code));

        assertOAuthError(invalid, "invalid_grant");
        assertEquals(200, valid.getStatus());
    }

    @Test
    void tokenRejectsMismatchedBasicAndFormClientAuthentication() {
        Response response = controller.token(basicAuthorization(CLIENT_ID, "header-secret"), requestContext(null),
                form("grant_type", "authorization_code", "client_id", CLIENT_ID, "client_secret", "form-secret",
                        "code", "authorization-code", "redirect_uri", CALLBACK_URI));

        assertOAuthError(response, "invalid_request");
    }

    /** RFC 7636 appendix B: the S256 challenge of a known verifier. */
    @Test
    void pkceAcceptsTheRfc7636ExampleVerifierAndNothingElse() {
        assertNull(CognitoOAuthController.pkceError(RFC_7636_CHALLENGE, RFC_7636_VERIFIER));
        assertNotNull(CognitoOAuthController.pkceError(RFC_7636_CHALLENGE, RFC_7636_VERIFIER.replace('d', 'e')));
        assertNotNull(CognitoOAuthController.pkceError(RFC_7636_CHALLENGE, null));
        assertNotNull(CognitoOAuthController.pkceError(RFC_7636_CHALLENGE, RFC_7636_CHALLENGE));
        assertNotNull(CognitoOAuthController.pkceError(RFC_7636_CHALLENGE, "short"));
        assertNotNull(CognitoOAuthController.pkceError(RFC_7636_CHALLENGE, RFC_7636_VERIFIER + "!"));
        assertNotNull(CognitoOAuthController.pkceError(null, RFC_7636_VERIFIER));
        assertNull(CognitoOAuthController.pkceError(null, null));
    }

    /** The client has no secret: a public client proves itself with the verifier alone. */
    @Test
    void tokenRedeemsAPkceCodeForAPublicClientWithTheMatchingVerifier() {
        String code = putAuthorizationCode(null, RFC_7636_CHALLENGE);

        Response response = controller.token(null, requestContext(null),
                withVerifier(validAuthorizationCodeForm(code), RFC_7636_VERIFIER));

        assertEquals(200, response.getStatus());
        assertEquals("access-token", ((JsonNode) response.getEntity()).path("access_token").asText());
    }

    @Test
    void tokenRejectsAWrongVerifierAndSpendsTheCode() {
        String code = putAuthorizationCode(null, RFC_7636_CHALLENGE);

        Response wrong = controller.token(null, requestContext(null),
                withVerifier(validAuthorizationCodeForm(code), RFC_7636_VERIFIER.replace('d', 'e')));
        Response retry = controller.token(null, requestContext(null),
                withVerifier(validAuthorizationCodeForm(code), RFC_7636_VERIFIER));

        assertOAuthError(wrong, "invalid_grant");
        assertOAuthError(retry, "invalid_grant");
    }

    @Test
    void tokenRejectsAPkceCodeWithoutAVerifier() {
        String code = putAuthorizationCode(null, RFC_7636_CHALLENGE);

        Response response = controller.token(null, requestContext(null), validAuthorizationCodeForm(code));

        assertOAuthError(response, "invalid_grant");
        assertTrue(stateStore.findAuthorizationCode(code).isEmpty());
    }

    @Test
    void tokenRejectsAVerifierForACodeIssuedWithoutAChallenge() {
        String code = putAuthorizationCode(null, null);

        Response response = controller.token(null, requestContext(null),
                withVerifier(validAuthorizationCodeForm(code), RFC_7636_VERIFIER));

        assertOAuthError(response, "invalid_grant");
    }

    @Test
    void tokenPutsTheNonceInTheIdTokenOnly() {
        String code = putAuthorizationCode("request-nonce", null);
        ArgumentCaptor<CognitoService.ClaimsOverride> override = ArgumentCaptor.forClass(CognitoService.ClaimsOverride.class);
        when(cognitoService.generateAuthResultForHostedAuth(any(CognitoUser.class), any(UserPool.class), eq(client), override.capture(), any()))
                .thenReturn(Map.of("AccessToken", "access-token", "IdToken", "id-token", "RefreshToken", "refresh-token",
                        "ExpiresIn", 3600, "TokenType", "Bearer"));

        Response response = controller.token(null, requestContext(null), validAuthorizationCodeForm(code));

        assertEquals(200, response.getStatus());
        assertEquals(Map.of("nonce", "request-nonce"), override.getValue().idClaimsToAddOrOverride());
        assertNull(override.getValue().accessClaimsToAddOrOverride());
        assertNull(override.getValue().groupsToOverride());
    }

    /**
     * Redemption must mint through the hosted-auth path, which is what fires PreTokenGeneration;
     * the plain {@code generateAuthResult} does not, and using it here silently drops the trigger.
     * The code's requested scopes travel with it, so a V2 lambda sees what was asked for.
     */
    @Test
    void tokenMintsThroughTheHostedAuthPathSoPreTokenGenerationFires() {
        String code = putAuthorizationCode(null, null);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> scopes = ArgumentCaptor.forClass(List.class);

        Response response = controller.token(null, requestContext(null), validAuthorizationCodeForm(code));

        assertEquals(200, response.getStatus());
        verify(cognitoService).generateAuthResultForHostedAuth(any(CognitoUser.class), any(UserPool.class), eq(client),
                any(), scopes.capture());
        assertEquals(List.of("openid"), scopes.getValue(), "the code's requested scopes reach the trigger");
        verify(cognitoService, never()).generateAuthResult(any(CognitoUser.class), any(UserPool.class), any(), any());
    }

    private String putTransaction(String relyingPartyState) {
        return stateStore.putTransaction(new CognitoAuthorizationTransaction(POOL_ID, CLIENT_ID, CALLBACK_URI,
                List.of("openid"), "nonce", "ExampleOidc", relyingPartyState, null, CLOCK.instant().plusSeconds(60)));
    }

    private String putAuthorizationCode() {
        return putAuthorizationCode(null, null);
    }

    private String putAuthorizationCode(String nonce, String codeChallenge) {
        String code = stateStore.putAuthorizationCode(new CognitoAuthorizationCode(
                POOL_ID, CLIENT_ID, "federated-user", CALLBACK_URI, List.of("openid"), nonce, codeChallenge,
                CLOCK.instant().plusSeconds(60)));
        when(cognitoService.describeUserPool(POOL_ID)).thenReturn(pool());
        when(cognitoService.adminGetUser(POOL_ID, "federated-user")).thenReturn(user());
        when(cognitoService.generateAuthResultForHostedAuth(any(CognitoUser.class), any(UserPool.class), eq(client), eq(null), any()))
                .thenReturn(Map.of("AccessToken", "access-token", "IdToken", "id-token", "RefreshToken", "refresh-token",
                        "ExpiresIn", 3600, "TokenType", "Bearer"));
        return code;
    }

    private static MultivaluedHashMap<String, String> withVerifier(MultivaluedHashMap<String, String> form,
                                                                   String codeVerifier) {
        form.add("code_verifier", codeVerifier);
        return form;
    }

    private static MultivaluedHashMap<String, String> validAuthorizationCodeForm(String code) {
        return form("grant_type", "authorization_code", "client_id", CLIENT_ID, "code", code, "redirect_uri", CALLBACK_URI);
    }

    private static UserPoolClient client() {
        return client(CLIENT_ID);
    }

    private static UserPoolClient client(String clientId) {
        UserPoolClient result = new UserPoolClient();
        result.setClientId(clientId);
        result.setUserPoolId(POOL_ID);
        result.setAllowedOAuthFlowsUserPoolClient(true);
        result.setAllowedOAuthFlows(List.of("code"));
        result.setCallbackURLs(List.of(CALLBACK_URI));
        result.setSupportedIdentityProviders(List.of("COGNITO"));
        return result;
    }

    private static UserPool pool() {
        UserPool result = new UserPool();
        result.setId(POOL_ID);
        return result;
    }

    private static CognitoUser user() {
        return user("federated-user");
    }

    private static CognitoUser user(String username) {
        CognitoUser result = new CognitoUser();
        result.setUsername(username);
        result.setUserPoolId(POOL_ID);
        return result;
    }

    private static ContainerRequestContext requestContext(String poolId) {
        ContainerRequestContext result = mock(ContainerRequestContext.class);
        when(result.getProperty(CognitoCustomDomainFilter.POOL_PROPERTY)).thenReturn(poolId);
        return result;
    }

    private static MultivaluedHashMap<String, String> form(String... values) {
        MultivaluedHashMap<String, String> result = new MultivaluedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.add(values[index], values[index + 1]);
        }
        return result;
    }

    private static String basicAuthorization(String clientId, String clientSecret) {
        String credentials = clientId + ":" + clientSecret;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private void assertOAuthError(Response response, String error) {
        assertEquals(400, response.getStatus());
        assertNotNull(response.getEntity());
        JsonNode body = (JsonNode) response.getEntity();
        assertEquals(error, body.path("error").asText());
        assertTrue(body.hasNonNull("error_description"));
    }
}
