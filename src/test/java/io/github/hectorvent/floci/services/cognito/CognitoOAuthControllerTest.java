package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cognito.model.CognitoAuthorizationCode;
import io.github.hectorvent.floci.services.cognito.model.CognitoAuthorizationTransaction;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CognitoOAuthControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC);
    private static final String POOL_ID = "us-east-1_pool";
    private static final String CLIENT_ID = "cognito-client";
    private static final String CALLBACK_URI = "https://application.example.test/callback";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CognitoService cognitoService = mock(CognitoService.class);
    private final CognitoFederationService federationService = mock(CognitoFederationService.class);
    private CognitoFederationStateStore stateStore;
    private CognitoOAuthController controller;
    private UserPoolClient client;

    @BeforeEach
    void setUp() {
        stateStore = new CognitoFederationStateStore(CLOCK);
        controller = new CognitoOAuthController(cognitoService, objectMapper, federationService, stateStore);
        client = client();
        when(cognitoService.findClientById(CLIENT_ID)).thenReturn(client);
    }

    @Test
    void authorizeRedirectsToConfiguredIdentityProvider() {
        when(federationService.beginAuthorization(POOL_ID, CLIENT_ID, CALLBACK_URI, List.of("openid"), "nonce", "ExampleOidc"))
                .thenReturn("https://provider.example.test/authorize?state=provider-state");

        Response response = controller.authorize(requestContext(null), CLIENT_ID, CALLBACK_URI, "code", "openid", "nonce", "ExampleOidc");

        assertEquals(302, response.getStatus());
        assertEquals("https://provider.example.test/authorize?state=provider-state", response.getHeaderString("Location"));
    }

    @Test
    void authorizeReturnsInvalidClientForUnknownClient() {
        when(cognitoService.findClientById("missing-client"))
                .thenThrow(new AwsException("ResourceNotFoundException", "Client not found", 400));

        Response response = controller.authorize(requestContext(null), "missing-client", CALLBACK_URI, "code", "openid", null, "ExampleOidc");

        assertOAuthError(response, "invalid_client");
    }

    @Test
    void authorizeReturnsInvalidRequestForUnregisteredRedirectUri() {
        Response response = controller.authorize(requestContext(null), CLIENT_ID, "https://other.example.test/callback", "code", "openid", null, "ExampleOidc");

        assertOAuthError(response, "invalid_request");
    }

    @Test
    void authorizeReturnsUnsupportedResponseTypeForTokenResponse() {
        Response response = controller.authorize(requestContext(null), CLIENT_ID, CALLBACK_URI, "token", "openid", null, "ExampleOidc");

        assertOAuthError(response, "unsupported_response_type");
    }

    @Test
    void idpResponseReturnsInvalidRequestForUnknownState() {
        Response response = controller.idpResponse(requestContext(null), "unknown-state", "provider-code", null, null);

        assertOAuthError(response, "invalid_request");
    }

    @Test
    void idpResponseRedirectsProviderErrorToApplication() {
        String state = putTransaction();

        Response response = controller.idpResponse(requestContext(null), state, null, "access_denied", "The provider refused access");

        assertEquals(302, response.getStatus());
        assertEquals(CALLBACK_URI + "?error=access_denied&error_description=The+provider+refused+access", response.getHeaderString("Location"));
    }

    @Test
    void idpResponseRedirectsAuthorizationCodeToApplication() {
        String state = putTransaction();
        when(federationService.completeAuthorization(any(CognitoAuthorizationTransaction.class), eq("provider-code")))
                .thenReturn("authorization-code");

        Response response = controller.idpResponse(requestContext(null), state, "provider-code", null, null);

        assertEquals(302, response.getStatus());
        assertEquals(CALLBACK_URI + "?code=authorization-code", response.getHeaderString("Location"));
    }

    @Test
    void tokenRedeemsAuthorizationCodeOnce() throws Exception {
        String code = stateStore.putAuthorizationCode(new CognitoAuthorizationCode(
                POOL_ID, CLIENT_ID, "federated-user", CALLBACK_URI, List.of("openid"), CLOCK.instant().plusSeconds(60)));
        when(cognitoService.describeUserPool(POOL_ID)).thenReturn(pool());
        when(cognitoService.adminGetUser(POOL_ID, "federated-user")).thenReturn(user());
        when(cognitoService.generateAuthResult(any(CognitoUser.class), any(UserPool.class), eq(client), eq(null)))
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
                POOL_ID, "other-client", "federated-user", CALLBACK_URI, List.of("openid"), CLOCK.instant().plusSeconds(60)));

        Response response = controller.token(null, requestContext(null), form("grant_type", "authorization_code",
                "client_id", CLIENT_ID, "code", code, "redirect_uri", CALLBACK_URI));

        assertOAuthError(response, "invalid_grant");
    }

    @Test
    void tokenRejectsMismatchedBasicAndFormClientAuthentication() {
        Response response = controller.token(basicAuthorization(CLIENT_ID, "header-secret"), requestContext(null),
                form("grant_type", "authorization_code", "client_id", CLIENT_ID, "client_secret", "form-secret",
                        "code", "authorization-code", "redirect_uri", CALLBACK_URI));

        assertOAuthError(response, "invalid_request");
    }

    private String putTransaction() {
        return stateStore.putTransaction(new CognitoAuthorizationTransaction(POOL_ID, CLIENT_ID, CALLBACK_URI,
                List.of("openid"), "nonce", "ExampleOidc", CLOCK.instant().plusSeconds(60)));
    }

    private static UserPoolClient client() {
        UserPoolClient result = new UserPoolClient();
        result.setClientId(CLIENT_ID);
        result.setUserPoolId(POOL_ID);
        result.setAllowedOAuthFlowsUserPoolClient(true);
        result.setAllowedOAuthFlows(List.of("code"));
        result.setCallbackURLs(List.of(CALLBACK_URI));
        return result;
    }

    private static UserPool pool() {
        UserPool result = new UserPool();
        result.setId(POOL_ID);
        return result;
    }

    private static CognitoUser user() {
        CognitoUser result = new CognitoUser();
        result.setUsername("federated-user");
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
