package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.cognito.model.CognitoAuthorizationCode;
import io.github.hectorvent.floci.services.cognito.model.CognitoAuthorizationTransaction;
import io.github.hectorvent.floci.services.cognito.model.CognitoGroup;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.IdentityProvider;
import io.github.hectorvent.floci.services.cognito.model.ResourceServer;
import io.github.hectorvent.floci.services.cognito.model.RevokedTokenInfo;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import io.github.hectorvent.floci.services.cognito.model.UserPoolDomain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CognitoFederationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer oidcServer;
    private CognitoService cognitoService;
    private CognitoFederationStateStore stateStore;
    private CognitoFederationService federationService;
    private UserPool pool;
    private final AtomicReference<Request> tokenRequest = new AtomicReference<>();
    private final AtomicReference<Request> claimsRequest = new AtomicReference<>();
    private volatile int tokenStatus;
    private volatile String tokenResponse;
    private volatile int claimsStatus;
    private volatile String claimsResponse;

    @BeforeEach
    void setUp() throws IOException {
        oidcServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        oidcServer.createContext("/token", this::handleToken);
        oidcServer.createContext("/userinfo", this::handleClaims);
        oidcServer.start();

        cognitoService = new CognitoService(
                new InMemoryStorage<String, UserPool>(),
                new InMemoryStorage<String, UserPoolClient>(),
                new InMemoryStorage<String, ResourceServer>(),
                new InMemoryStorage<String, UserPoolDomain>(),
                new InMemoryStorage<String, IdentityProvider>(),
                new InMemoryStorage<String, CognitoUser>(),
                new InMemoryStorage<String, CognitoGroup>(),
                new InMemoryStorage<String, RevokedTokenInfo>(),
                "http://localhost:4566",
                new RegionResolver("us-east-1", "000000000000"),
                null,
                mock(AcmService.class),
                null,
                null,
                null);
        stateStore = new CognitoFederationStateStore(CLOCK);
        federationService = new CognitoFederationService(
                cognitoService, stateStore, new CognitoOidcClient(MAPPER), CLOCK);
        pool = cognitoService.createUserPool(Map.of("PoolName", "FederationPool"), "us-east-1");
        tokenStatus = 200;
        tokenResponse = "{\"access_token\":\"provider-access-token\"}";
        claimsStatus = 200;
        claimsResponse = "{\"sub\":\"provider-subject\",\"iss\":\"https://issuer.example.test\","
                + "\"email\":\"alice@example.test\",\"name\":\"Alice Provider\"}";
    }

    @AfterEach
    void tearDown() {
        oidcServer.stop(0);
    }

    @Test
    void beginAuthorizationRejectsProviderWithoutAuthorizeEndpoint() {
        createProvider(Map.of(
                "client_id", "provider-client",
                "token_url", endpoint("/token"),
                "attributes_url", endpoint("/userinfo")), Map.of());

        AwsException exception = assertThrows(AwsException.class, () -> federationService.beginAuthorization(
                pool.getId(), "cognito-client", "https://application.example.test/callback",
                List.of("openid"), "nonce-value", "ExampleOidc"));

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    @Test
    void beginAuthorizationRejectsNonOidcProviderBeforeStoringState() {
        createProvider("ExampleGoogle", "Google", Map.of(
                "client_id", "provider-client",
                "authorize_url", "https://provider.example.test/authorize",
                "token_url", endpoint("/token"),
                "attributes_url", endpoint("/userinfo")), Map.of());

        AwsException exception = assertThrows(AwsException.class, () -> federationService.beginAuthorization(
                pool.getId(), "cognito-client", "https://application.example.test/callback",
                List.of("openid"), "nonce-value", "ExampleGoogle"));

        assertEquals("InvalidParameterException", exception.getErrorCode());
        assertNull(tokenRequest.get());
        assertNull(claimsRequest.get());
    }

    @Test
    void beginAuthorizationStoresRelyingPartyStateInTransaction() {
        createDefaultProvider();

        String redirect = federationService.beginAuthorization(
                pool.getId(), "cognito-client", "https://application.example.test/callback",
                List.of("openid"), "nonce-value", "ExampleOidc", "relying-party-state");

        CognitoAuthorizationTransaction transaction = stateStore.consumeTransaction(queryValue(redirect, "state")).orElseThrow();
        assertEquals("relying-party-state", transaction.relyingPartyState());
    }

    @Test
    void completeAuthorizationRejectsNonOidcProvider() {
        createProvider("ExampleGoogle", "Google", Map.of(
                "client_id", "provider-client",
                "authorize_url", "https://provider.example.test/authorize",
                "token_url", endpoint("/token"),
                "attributes_url", endpoint("/userinfo")), Map.of());
        String state = putTransaction("ExampleGoogle");

        AwsException exception = assertThrows(AwsException.class,
                () -> federationService.completeAuthorization(state, "provider-code"));

        assertEquals("InvalidParameterException", exception.getErrorCode());
        assertNull(tokenRequest.get());
        assertNull(claimsRequest.get());
    }

    @Test
    void completeAuthorizationRejectsProviderWithoutTokenEndpoint() {
        createProvider(Map.of(
                "client_id", "provider-client",
                "authorize_url", "https://provider.example.test/authorize",
                "attributes_url", endpoint("/userinfo")), Map.of());
        String state = beginAuthorization();

        AwsException exception = assertThrows(AwsException.class,
                () -> federationService.completeAuthorization(state, "provider-code"));

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    @Test
    void completeAuthorizationRejectsProviderWithoutClaimsEndpoint() {
        createProvider(Map.of(
                "client_id", "provider-client",
                "authorize_url", "https://provider.example.test/authorize",
                "token_url", endpoint("/token")), Map.of());
        String state = beginAuthorization();

        AwsException exception = assertThrows(AwsException.class,
                () -> federationService.completeAuthorization(state, "provider-code"));

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    @Test
    void completeAuthorizationConvertsInvalidTokenEndpointUriToAwsException() {
        createProvider(Map.of(
                "client_id", "provider-client",
                "authorize_url", "https://provider.example.test/authorize",
                "token_url", "http://[invalid",
                "attributes_url", endpoint("/userinfo")), Map.of());
        String state = beginAuthorization();

        AwsException exception = assertThrows(AwsException.class,
                () -> federationService.completeAuthorization(state, "provider-code"));

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    @Test
    void completeAuthorizationRejectsUnsupportedTokenEndpointScheme() {
        createProvider(Map.of(
                "client_id", "provider-client",
                "authorize_url", "https://provider.example.test/authorize",
                "token_url", "ftp://provider.example.test/token",
                "attributes_url", endpoint("/userinfo")), Map.of());
        String state = beginAuthorization();

        AwsException exception = assertThrows(AwsException.class,
                () -> federationService.completeAuthorization(state, "provider-code"));

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    @Test
    void completeAuthorizationRejectsUnsupportedClaimsEndpointScheme() {
        createProvider(Map.of(
                "client_id", "provider-client",
                "authorize_url", "https://provider.example.test/authorize",
                "token_url", endpoint("/token"),
                "attributes_url", "ftp://provider.example.test/userinfo"), Map.of());
        String state = beginAuthorization();

        AwsException exception = assertThrows(AwsException.class,
                () -> federationService.completeAuthorization(state, "provider-code"));

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    @Test
    void completeAuthorizationRejectsFailedTokenExchange() {
        createDefaultProvider();
        tokenStatus = 400;
        tokenResponse = "{\"error\":\"invalid_grant\"}";
        String state = beginAuthorization();

        AwsException exception = assertThrows(AwsException.class,
                () -> federationService.completeAuthorization(state, "provider-code"));

        assertEquals("NotAuthorizedException", exception.getErrorCode());
    }

    @Test
    void completeAuthorizationRejectsFailedClaimsRequest() {
        createDefaultProvider();
        claimsStatus = 503;
        claimsResponse = "{\"error\":\"temporarily_unavailable\"}";
        String state = beginAuthorization();

        AwsException exception = assertThrows(AwsException.class,
                () -> federationService.completeAuthorization(state, "provider-code"));

        assertEquals("NotAuthorizedException", exception.getErrorCode());
    }

    @Test
    void completeAuthorizationRejectsClaimsWithoutSubject() {
        createDefaultProvider();
        claimsResponse = "{\"email\":\"alice@example.test\"}";
        String state = beginAuthorization();

        AwsException exception = assertThrows(AwsException.class,
                () -> federationService.completeAuthorization(state, "provider-code"));

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    @Test
    void completeAuthorizationMapsClaimsAndUsesOidcRequests() {
        createDefaultProvider();
        String state = beginAuthorization();

        String authorizationCode = federationService.completeAuthorization(state, "provider code");

        CognitoAuthorizationCode storedCode = stateStore.consumeAuthorizationCode(authorizationCode).orElseThrow();
        CognitoUser user = cognitoService.adminGetUser(pool.getId(), storedCode.userId());
        assertEquals("alice@example.test", user.getAttributes().get("email"));
        assertEquals("Alice Provider", user.getAttributes().get("name"));
        assertEquals("ExampleOidc", user.getFederatedProviderName());
        assertEquals("provider-subject", user.getFederatedSubject());

        Request observedTokenRequest = tokenRequest.get();
        assertNotNull(observedTokenRequest);
        assertEquals("POST", observedTokenRequest.method());
        assertEquals("authorization_code", formValue(observedTokenRequest.body(), "grant_type"));
        assertEquals("provider-client", formValue(observedTokenRequest.body(), "client_id"));
        assertEquals("provider-secret", formValue(observedTokenRequest.body(), "client_secret"));
        assertEquals("provider code", formValue(observedTokenRequest.body(), "code"));
        assertEquals(cognitoService.getIdentityProviderCallbackEndpoint(pool.getId()),
                formValue(observedTokenRequest.body(), "redirect_uri"));

        Request observedClaimsRequest = claimsRequest.get();
        assertNotNull(observedClaimsRequest);
        assertEquals("GET", observedClaimsRequest.method());
        assertEquals("Bearer provider-access-token", observedClaimsRequest.authorization());
    }

    @Test
    void completeAuthorizationProvisionsNewFederatedUser() {
        createDefaultProvider();

        CognitoAuthorizationCode storedCode = completeAndConsumeAuthorizationCode();
        CognitoUser user = cognitoService.adminGetUser(pool.getId(), storedCode.userId());

        assertEquals(pool.getId(), user.getUserPoolId());
        assertEquals("CONFIRMED", user.getUserStatus());
        assertTrue(user.isEnabled());
        assertFalse(user.getUsername().isBlank());
        assertNotNull(user.getAttributes().get("sub"));
    }

    @Test
    void completeAuthorizationReconcilesExistingFederatedUserWithoutLosingOtherAttributes() {
        createDefaultProvider();
        CognitoAuthorizationCode firstCode = completeAndConsumeAuthorizationCode();
        CognitoUser firstUser = cognitoService.adminGetUser(pool.getId(), firstCode.userId());
        cognitoService.adminUpdateUserAttributes(pool.getId(), firstUser.getUsername(),
                Map.of("custom:note", "preserve-me", "email", "old@example.test"));
        claimsResponse = "{\"sub\":\"provider-subject\",\"iss\":\"https://issuer.example.test\","
                + "\"email\":\"new@example.test\",\"name\":\"Updated Provider\"}";

        CognitoAuthorizationCode secondCode = completeAndConsumeAuthorizationCode();
        CognitoUser reconciledUser = cognitoService.adminGetUser(pool.getId(), secondCode.userId());

        assertEquals(firstUser.getUsername(), reconciledUser.getUsername());
        assertEquals("new@example.test", reconciledUser.getAttributes().get("email"));
        assertEquals("Updated Provider", reconciledUser.getAttributes().get("name"));
        assertEquals("preserve-me", reconciledUser.getAttributes().get("custom:note"));
    }

    @Test
    void completeAuthorizationStoresAwsCompatibleIdentityFields() throws Exception {
        createDefaultProvider();

        CognitoAuthorizationCode storedCode = completeAndConsumeAuthorizationCode();
        CognitoUser user = cognitoService.adminGetUser(pool.getId(), storedCode.userId());
        JsonNode identity = MAPPER.readTree(user.getAttributes().get("identities")).get(0);
        List<String> fieldNames = new ArrayList<>();
        identity.fieldNames().forEachRemaining(fieldNames::add);

        assertEquals(List.of("userId", "providerName", "providerType", "issuer", "primary", "dateCreated"),
                fieldNames);
        assertEquals("provider-subject", identity.path("userId").asText());
        assertEquals("ExampleOidc", identity.path("providerName").asText());
        assertEquals("OIDC", identity.path("providerType").asText());
        assertEquals("https://issuer.example.test", identity.path("issuer").asText());
        assertFalse(identity.path("primary").asBoolean());
        assertTrue(identity.path("dateCreated").asLong() > 0L);
    }

    private IdentityProvider createDefaultProvider() {
        return createProvider(Map.of(
                "client_id", "provider-client",
                "client_secret", "provider-secret",
                "authorize_url", "https://provider.example.test/authorize",
                "token_url", endpoint("/token"),
                "attributes_url", endpoint("/userinfo")),
                Map.of("email", "email", "name", "name"));
    }

    private IdentityProvider createProvider(Map<String, String> details, Map<String, String> attributeMapping) {
        return createProvider("ExampleOidc", "OIDC", details, attributeMapping);
    }

    private IdentityProvider createProvider(String providerName, String providerType, Map<String, String> details,
                                            Map<String, String> attributeMapping) {
        return cognitoService.createIdentityProvider(pool.getId(), providerName, providerType, details,
                attributeMapping, List.of());
    }

    private CognitoAuthorizationCode completeAndConsumeAuthorizationCode() {
        String authorizationCode = federationService.completeAuthorization(beginAuthorization(), "provider-code");
        return stateStore.consumeAuthorizationCode(authorizationCode).orElseThrow();
    }

    private String beginAuthorization() {
        String redirect = federationService.beginAuthorization(
                pool.getId(), "cognito-client", "https://application.example.test/callback",
                List.of("openid"), "nonce-value", "ExampleOidc");
        return queryValue(redirect, "state");
    }

    private String putTransaction(String providerName) {
        return stateStore.putTransaction(new CognitoAuthorizationTransaction(
                pool.getId(), "cognito-client", "https://application.example.test/callback",
                List.of("openid"), "nonce-value", providerName, null, CLOCK.instant().plusSeconds(60)));
    }

    private String endpoint(String path) {
        return "http://127.0.0.1:" + oidcServer.getAddress().getPort() + path;
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        tokenRequest.set(new Request(exchange.getRequestMethod(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                exchange.getRequestHeaders().getFirst("Authorization")));
        writeResponse(exchange, tokenStatus, tokenResponse);
    }

    private void handleClaims(HttpExchange exchange) throws IOException {
        claimsRequest.set(new Request(exchange.getRequestMethod(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                exchange.getRequestHeaders().getFirst("Authorization")));
        writeResponse(exchange, claimsStatus, claimsResponse);
    }

    private void writeResponse(HttpExchange exchange, int status, String response) throws IOException {
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private String queryValue(String uri, String name) {
        String query = URI.create(uri).getRawQuery();
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && name.equals(URLDecoder.decode(parts[0], StandardCharsets.UTF_8))) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Query parameter " + name + " was absent from " + uri);
    }

    private String formValue(String form, String name) {
        return queryValue("https://form.example.test/?" + form, name);
    }

    private record Request(String method, String body, String authorization) {
    }
}
