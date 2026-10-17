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
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
        createProvider("ExampleFacebook", "Facebook", Map.of(
                "client_id", "provider-client",
                "authorize_url", "https://provider.example.test/authorize",
                "token_url", endpoint("/token"),
                "attributes_url", endpoint("/userinfo")), Map.of());

        AwsException exception = assertThrows(AwsException.class, () -> federationService.beginAuthorization(
                pool.getId(), "cognito-client", "https://application.example.test/callback",
                List.of("openid"), "nonce-value", "ExampleFacebook"));

        assertEquals("InvalidParameterException", exception.getErrorCode());
        assertNull(tokenRequest.get());
        assertNull(claimsRequest.get());
    }

    @Test
    void beginAuthorizationStoresRelyingPartyStateInTransaction() {
        createDefaultProvider();

        String redirect = federationService.beginAuthorization(
                pool.getId(), "cognito-client", "https://application.example.test/callback",
                List.of("openid"), List.of("openid"), "nonce-value", "ExampleOidc", "relying-party-state", null);

        CognitoAuthorizationTransaction transaction = stateStore.consumeTransaction(queryValue(redirect, "state")).orElseThrow();
        assertEquals("relying-party-state", transaction.relyingPartyState());
    }

    @Test
    void completeAuthorizationCarriesTheNonceAndCodeChallengeIntoTheCode() {
        createDefaultProvider();
        String challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
        String redirect = federationService.beginAuthorization(
                pool.getId(), "cognito-client", "https://application.example.test/callback",
                List.of("openid"), List.of("openid"), "nonce-value", "ExampleOidc", null, challenge);

        String authorizationCode = federationService.completeAuthorization(queryValue(redirect, "state"), "provider-code");

        CognitoAuthorizationCode storedCode = stateStore.consumeAuthorizationCode(authorizationCode).orElseThrow();
        assertEquals("nonce-value", storedCode.nonce());
        assertEquals(challenge, storedCode.codeChallenge());
        assertFalse(redirect.contains(challenge), "the challenge stays with Cognito: " + redirect);
    }

    @Test
    void completeAuthorizationRejectsNonOidcProvider() {
        createProvider("ExampleFacebook", "Facebook", Map.of(
                "client_id", "provider-client",
                "authorize_url", "https://provider.example.test/authorize",
                "token_url", endpoint("/token"),
                "attributes_url", endpoint("/userinfo")), Map.of());
        String state = putTransaction("ExampleFacebook");

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
    void googleAuthorizationUsesDefaultsWithoutChangingStoredDetails() {
        Map<String, String> details = Map.of("client_id", "google-client", "client_secret", "google-secret",
                "authorize_scopes", "openid email profile");
        createProvider("Google", "Google", details, Map.of("email", "email"));

        String redirect = federationService.beginAuthorization(pool.getId(), "cognito-client",
                "https://application.example.test/callback", List.of("openid"), "google-nonce", "Google");

        assertTrue(redirect.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"), redirect);
        assertEquals("google-client", queryValue(redirect, "client_id"));
        assertEquals("openid email profile", queryValue(redirect, "scope"));
        assertEquals("google-nonce", queryValue(redirect, "nonce"));
        assertFalse(redirect.contains("google-secret"));
        assertEquals(details, cognitoService.describeIdentityProvider(pool.getId(), "Google").getProviderDetails());
    }

    @Test
    void googleEndpointOverridesComeOnlyFromServerConfiguration() {
        Map<String, String> supplied = Map.of("client_id", "google-client",
                "authorize_url", "https://untrusted.example/authorize", "token_url", "https://untrusted.example/token",
                "attributes_url", "https://untrusted.example/userinfo", "oidc_issuer", "https://untrusted.example");
        IdentityProvider provider = createProvider("Google", "Google", supplied, Map.of());

        IdentityProvider effective = new CognitoOidcClient(MAPPER).googleProvider(provider);

        assertEquals("https://accounts.google.com/o/oauth2/v2/auth", effective.getProviderDetails().get("authorize_url"));
        assertEquals("https://oauth2.googleapis.com/token", effective.getProviderDetails().get("token_url"));
        assertEquals("https://openidconnect.googleapis.com/v1/userinfo", effective.getProviderDetails().get("attributes_url"));
        assertEquals("https://accounts.google.com", effective.getProviderDetails().get("oidc_issuer"));
        assertEquals(supplied, provider.getProviderDetails());
    }

    @Test
    void googleSignInDiscoversConfiguredIssuerAndMapsUserInfoClaims() throws Exception {
        CognitoFederationService googleFederation = googleFederation(discovery(), 200);
        Map<String, String> details = Map.of("client_id", "google-client", "client_secret", "google-secret",
                "authorize_scopes", "openid email profile");
        createProvider("Google", "Google", details, Map.of("email", "email", "email_verified", "email_verified",
                "name", "name", "given_name", "given_name", "family_name", "family_name", "picture", "picture"));
        claimsResponse = """
                {"sub":"google-subject","email":"google@example.test","email_verified":true,
                 "name":"Google User","given_name":"Google","family_name":"User","picture":"https://picture.example/user"}
                """;

        String redirect = googleFederation.beginAuthorization(pool.getId(), "cognito-client",
                "https://application.example.test/callback", List.of("openid"), "nonce-value", "Google");
        assertTrue(redirect.startsWith(endpoint("/authorize") + "?"), redirect);
        assertEquals("openid email profile", queryValue(redirect, "scope"));
        String code = googleFederation.completeAuthorization(queryValue(redirect, "state"), "provider-code");
        CognitoAuthorizationCode storedCode = stateStore.consumeAuthorizationCode(code).orElseThrow();
        CognitoUser user = cognitoService.adminGetUser(pool.getId(), storedCode.userId());

        assertEquals("Google_google-subject", user.getUsername());
        assertEquals("google@example.test", user.getAttributes().get("email"));
        assertEquals("true", user.getAttributes().get("email_verified"));
        assertEquals("Google User", user.getAttributes().get("name"));
        assertEquals("Google", user.getAttributes().get("given_name"));
        assertEquals("User", user.getAttributes().get("family_name"));
        assertEquals("https://picture.example/user", user.getAttributes().get("picture"));
        assertEquals("google-client", formValue(tokenRequest.get().body(), "client_id"));
        assertEquals("google-secret", formValue(tokenRequest.get().body(), "client_secret"));
        assertEquals("Bearer provider-access-token", claimsRequest.get().authorization());
        assertEquals(details, cognitoService.describeIdentityProvider(pool.getId(), "Google").getProviderDetails());
    }

    @Test
    void googleSignInRejectsUntrustedOrIncompleteDiscoveryBeforeExchangingCredentials() throws Exception {
        createProvider("Google", "Google", Map.of("client_id", "google-client"), Map.of());
        for (String body : List.of(discovery().replace(endpoint(""), "https://other-issuer.example"),
                "{\"issuer\":\"" + endpoint("") + "\"}",
                discovery().replace(endpoint("/token"), "file:///tmp/token"), "[]")) {
            CognitoFederationService googleFederation = googleFederation(body, 200);
            assertThrows(AwsException.class, () -> googleFederation.beginAuthorization(pool.getId(), "cognito-client",
                    "https://application.example.test/callback", List.of("openid"), null, "Google"));
            oidcServer.removeContext("/.well-known/openid-configuration");
        }
        assertNull(tokenRequest.get());
        assertNull(claimsRequest.get());
    }

    @Test
    void googleSignInReportsDiscoveryFailureWithoutLeakingTheResponse() throws Exception {
        createProvider("Google", "Google", Map.of("client_id", "google-client"), Map.of());
        CognitoFederationService googleFederation = googleFederation("sensitive response", 503);

        AwsException failure = assertThrows(AwsException.class, () -> googleFederation.beginAuthorization(
                pool.getId(), "cognito-client", "https://application.example.test/callback", List.of("openid"), null, "Google"));

        assertEquals("NotAuthorizedException", failure.getErrorCode());
        assertFalse(failure.getMessage().contains("sensitive response"));
        assertNull(tokenRequest.get());
        assertNull(claimsRequest.get());
    }

    @Test
    void googleProvisioningUsesSubjectAndReconcilesTheSameUser() throws Exception {
        IdentityProvider provider = createProvider("Google", "Google", Map.of("client_id", "google-client"), Map.of());
        CognitoUser first = cognitoService.provisionFederatedUser(pool.getId(), provider, "google-subject", null,
                Map.of("email", "first@example.test"));
        CognitoUser second = cognitoService.provisionFederatedUser(pool.getId(), provider, "google-subject", null,
                Map.of("email", "updated@example.test"));

        assertEquals("Google_google-subject", first.getUsername());
        assertEquals(first.getUsername(), second.getUsername());
        assertEquals("updated@example.test", second.getAttributes().get("email"));
        JsonNode identity = MAPPER.readTree(second.getAttributes().get("identities")).get(0);
        assertEquals("Google", identity.path("providerName").asText());
        assertEquals("Google", identity.path("providerType").asText());
        assertEquals("google-subject", identity.path("userId").asText());
    }

    @Test
    void googleProvisioningDoesNotReplaceAnUnlinkedUserWithTheSameUsername() {
        IdentityProvider provider = createProvider("Google", "Google", Map.of("client_id", "google-client"), Map.of());
        cognitoService.adminCreateUser(pool.getId(), "Google_google-subject", Map.of("email", "local@example.test"),
                "Password123!");

        AwsException failure = assertThrows(AwsException.class, () -> cognitoService.provisionFederatedUser(
                pool.getId(), provider, "google-subject", null, Map.of("email", "external@example.test")));

        assertEquals("UsernameExistsException", failure.getErrorCode());
        assertEquals("local@example.test", cognitoService.adminGetUser(pool.getId(), "Google_google-subject")
                .getAttributes().get("email"));
    }

    @Test
    void googleSignInFollowsDiscoveryRedirect() throws Exception {
        createProvider("Google", "Google", Map.of("client_id", "google-client"), Map.of());
        oidcServer.createContext("/.well-known/openid-configuration", exchange -> {
            exchange.getResponseHeaders().set("Location", endpoint("/redirected-discovery"));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        oidcServer.createContext("/redirected-discovery", exchange -> writeResponse(exchange, 200, discovery()));
        CognitoFederationService googleFederation = new CognitoFederationService(cognitoService, stateStore,
                new CognitoOidcClient(MAPPER, endpoint("")), CLOCK);

        String redirect = googleFederation.beginAuthorization(pool.getId(), "cognito-client",
                "https://application.example.test/callback", List.of("openid"), null, "Google");

        assertTrue(redirect.startsWith(endpoint("/authorize") + "?"), redirect);
    }

    @Test
    void googleCallbackSucceedsWhenDiscoveryOutageOccursAfterAuthorization() throws Exception {
        createProvider("Google", "Google", Map.of("client_id", "google-client"), Map.of());
        CognitoFederationService googleFederation = googleFederation(discovery(), 200);

        String redirect = googleFederation.beginAuthorization(pool.getId(), "cognito-client",
                "https://application.example.test/callback", List.of("openid"), "nonce-value", "Google");
        assertTrue(redirect.startsWith(endpoint("/authorize") + "?"), redirect);

        // Simulate a discovery outage after the authorization transaction has begun.
        oidcServer.removeContext("/.well-known/openid-configuration");

        String code = googleFederation.completeAuthorization(queryValue(redirect, "state"), "provider-code");
        CognitoAuthorizationCode storedCode = stateStore.consumeAuthorizationCode(code).orElseThrow();
        CognitoUser user = cognitoService.adminGetUser(pool.getId(), storedCode.userId());

        assertEquals("Google_provider-subject", user.getUsername());
        assertNotNull(tokenRequest.get());
        assertNotNull(claimsRequest.get());
    }

    @Test
    void googleProvisioningReconcilesUserLinkedViaAdminLinkProviderForUser() throws Exception {
        IdentityProvider provider = createProvider("Google", "Google", Map.of("client_id", "google-client"), Map.of());
        cognitoService.adminCreateUser(pool.getId(), "alice", Map.of("email", "alice@example.test"), "Password123!");
        cognitoService.adminLinkProviderForUser(pool.getId(), "alice", "Google", "google-subject");

        CognitoUser reconciled = cognitoService.provisionFederatedUser(pool.getId(), provider, "google-subject", null,
                Map.of("email", "updated@example.test"));

        assertEquals("alice", reconciled.getUsername());
        assertEquals("updated@example.test", reconciled.getAttributes().get("email"));
        JsonNode identity = MAPPER.readTree(reconciled.getAttributes().get("identities")).get(0);
        assertEquals("Google", identity.path("providerName").asText());
        assertEquals("google-subject", identity.path("userId").asText());

        // Verify no duplicate user was created.
        assertTrue(cognitoService.listUsers(pool.getId(), null).stream()
                .noneMatch(u -> "Google_google-subject".equals(u.getUsername())));
    }

    @Test
    void googleProvisioningReconcilesUserLinkedWithGeneratedUsername() throws Exception {
        IdentityProvider provider = createProvider("Google", "Google", Map.of("client_id", "google-client"), Map.of());
        cognitoService.adminCreateUser(pool.getId(), "Google_google-subject",
                Map.of("email", "pre-created@example.test"), "Password123!");
        cognitoService.adminLinkProviderForUser(pool.getId(), "Google_google-subject", "Google", "google-subject");

        CognitoUser reconciled = cognitoService.provisionFederatedUser(pool.getId(), provider, "google-subject", null,
                Map.of("email", "reconciled@example.test"));

        assertEquals("Google_google-subject", reconciled.getUsername());
        assertEquals("reconciled@example.test", reconciled.getAttributes().get("email"));
        assertEquals("reconciled@example.test", cognitoService.adminGetUser(pool.getId(), "Google_google-subject")
                .getAttributes().get("email"));
    }

    @Test
    void googleProvisioningRacesSafelyWithAdminCreateUser() throws Exception {
        IdentityProvider provider = createProvider("Google", "Google", Map.of("client_id", "google-client"), Map.of());
        String targetUsername = "Google_concurrent-subject";

        for (int i = 0; i < 10; i++) {
            UserPool racePool = cognitoService.createUserPool(Map.of("PoolName", "race-pool-" + i), "us-east-1");
            cognitoService.createIdentityProvider(racePool.getId(), provider.getProviderName(),
                    provider.getProviderType(), provider.getProviderDetails(), provider.getAttributeMapping(), List.of());

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger usernameExistsExceptions = new AtomicInteger();

            Future<?> federatedTask = executor.submit(() -> {
                try {
                    start.await();
                    cognitoService.provisionFederatedUser(racePool.getId(), provider, "concurrent-subject", null,
                            Map.of("email", "federated@example.test"));
                    successes.incrementAndGet();
                } catch (AwsException e) {
                    if ("UsernameExistsException".equals(e.getErrorCode())) {
                        usernameExistsExceptions.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            Future<?> adminTask = executor.submit(() -> {
                try {
                    start.await();
                    cognitoService.adminCreateUser(racePool.getId(), targetUsername,
                            Map.of("email", "admin@example.test"), "Password123!");
                    successes.incrementAndGet();
                } catch (AwsException e) {
                    if ("UsernameExistsException".equals(e.getErrorCode())) {
                        usernameExistsExceptions.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            start.countDown();
            federatedTask.get(5, TimeUnit.SECONDS);
            adminTask.get(5, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(1, successes.get(), "Exactly one creation should succeed");
            assertEquals(1, usernameExistsExceptions.get(), "Exactly one operation should receive UsernameExistsException");

            CognitoUser user = cognitoService.adminGetUser(racePool.getId(), targetUsername);
            assertNotNull(user);
            String email = user.getAttributes().get("email");
            assertTrue("federated@example.test".equals(email) || "admin@example.test".equals(email));
        }
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
                List.of("openid"), "nonce-value", providerName, null, null, CLOCK.instant().plusSeconds(60)));
    }

    private String endpoint(String path) {
        return "http://127.0.0.1:" + oidcServer.getAddress().getPort() + path;
    }

    private String discovery() {
        return """
                {"issuer":"%s","authorization_endpoint":"%s","token_endpoint":"%s","userinfo_endpoint":"%s"}
                """.formatted(endpoint(""), endpoint("/authorize"), endpoint("/token"), endpoint("/userinfo"));
    }

    private CognitoFederationService googleFederation(String discovery, int status) {
        oidcServer.createContext("/.well-known/openid-configuration", exchange -> writeResponse(exchange, status, discovery));
        return new CognitoFederationService(cognitoService, stateStore,
                new CognitoOidcClient(MAPPER, endpoint("")), CLOCK);
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
