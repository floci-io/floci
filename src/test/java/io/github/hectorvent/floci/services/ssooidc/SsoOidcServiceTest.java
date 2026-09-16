package io.github.hectorvent.floci.services.ssooidc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ssooidc.model.RegisteredClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SsoOidcServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private SsoOidcService service;

    @BeforeEach
    void setUp() {
        service = new SsoOidcService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), "http://localhost:4566/");
    }

    @Test
    void registerClientPersistsAwsOidcMetadata() {
        ObjectNode request = mapper.createObjectNode();
        request.put("clientName", "Floci CLI");
        request.put("clientType", "public");
        request.putArray("scopes").add("sso:account:access");
        request.putArray("redirectUris").add("http://127.0.0.1:8400/callback");
        request.putArray("grantTypes")
                .add("authorization_code")
                .add("refresh_token");

        RegisteredClient client = service.registerClient(request);

        assertNotNull(client.clientId());
        assertEquals(32, client.clientId().length());
        assertNotNull(client.clientSecret());
        assertEquals(64, client.clientSecret().length());
        assertTrue(client.clientSecretExpiresAt() > client.clientIdIssuedAt());
        assertEquals(client, service.requireClient(client.clientId()));
        assertEquals("http://localhost:4566/authorize", service.authorizationEndpoint());
        assertEquals("http://localhost:4566/token", service.tokenEndpoint());
    }

    @Test
    void startDeviceAuthorizationValidatesCredentialsAndPersistsChallenge() {
        ObjectNode register = mapper.createObjectNode();
        register.put("clientName", "Device Client");
        register.put("clientType", "public");
        register.putArray("grantTypes").add("urn:ietf:params:oauth:grant-type:device_code");
        RegisteredClient client = service.registerClient(register);

        ObjectNode request = mapper.createObjectNode();
        request.put("clientId", client.clientId());
        request.put("clientSecret", client.clientSecret());
        request.put("startUrl", "https://example.awsapps.com/start");
        var authorization = service.startDeviceAuthorization(request);

        assertEquals(client.clientId(), authorization.clientId());
        assertEquals(64, authorization.deviceCode().length());
        assertTrue(authorization.userCode().matches("[0-9A-F]{4}-[0-9A-F]{4}"));
        assertEquals(authorization, service.requireDeviceAuthorization(authorization.deviceCode()));
        assertEquals("http://localhost:4566/device", service.verificationUri());
        assertTrue(service.verificationUriComplete(authorization).endsWith("user_code=" + authorization.userCode()));

        ObjectNode wrongSecret = request.deepCopy().put("clientSecret", "wrong");
        assertOidcError("invalid_client", () -> service.startDeviceAuthorization(wrongSecret));
    }

    @Test
    void createTokenSupportsDeviceAndRefreshGrants() {
        ObjectNode register = mapper.createObjectNode();
        register.put("clientName", "Token Client");
        register.put("clientType", "public");
        register.putArray("grantTypes")
                .add("urn:ietf:params:oauth:grant-type:device_code")
                .add("refresh_token");
        RegisteredClient client = service.registerClient(register);
        ObjectNode start = mapper.createObjectNode();
        start.put("clientId", client.clientId());
        start.put("clientSecret", client.clientSecret());
        start.put("startUrl", "https://example.awsapps.com/start");
        var authorization = service.startDeviceAuthorization(start);

        ObjectNode token = mapper.createObjectNode();
        token.put("clientId", client.clientId());
        token.put("clientSecret", client.clientSecret());
        token.put("grantType", "urn:ietf:params:oauth:grant-type:device_code");
        token.put("deviceCode", authorization.deviceCode());
        assertOidcError("authorization_pending", () -> service.createToken(token));
        assertOidcError("slow_down", () -> service.createToken(token));

        var approvedAuthorization = service.startDeviceAuthorization(start);
        service.authorizeDevice(approvedAuthorization.userCode(), "11111111-1111-1111-1111-111111111111");
        token.put("deviceCode", approvedAuthorization.deviceCode());
        var session = service.createToken(token);
        assertEquals("Token Client", client.clientName());
        assertEquals(64, session.accessToken().length());
        assertEquals(64, session.refreshToken().length());

        ObjectNode refresh = mapper.createObjectNode();
        refresh.put("clientId", client.clientId());
        refresh.put("clientSecret", client.clientSecret());
        refresh.put("grantType", "refresh_token");
        refresh.put("refreshToken", session.refreshToken());
        assertEquals(client.clientId(), service.createToken(refresh).clientId());
    }

    @Test
    void createTokenSupportsAuthorizationCodePkce() {
        ObjectNode register = mapper.createObjectNode();
        register.put("clientName", "PKCE Client");
        register.put("clientType", "public");
        register.putArray("grantTypes").add("authorization_code");
        register.putArray("redirectUris").add("http://127.0.0.1:8400/callback");
        RegisteredClient client = service.registerClient(register);
        String verifier = "01234567890123456789012345678901234567890123456789";
        String challenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                sha256(verifier));
        var code = service.createAuthorizationCode(client.clientId(),
                "http://127.0.0.1:8400/callback", challenge);

        ObjectNode token = mapper.createObjectNode();
        token.put("clientId", client.clientId());
        token.put("clientSecret", client.clientSecret());
        token.put("grantType", "authorization_code");
        token.put("code", code.code());
        token.put("codeVerifier", verifier);
        token.put("redirectUri", "http://127.0.0.1:8400/callback");
        assertEquals(client.clientId(), service.createToken(token).clientId());
        assertOidcError("invalid_grant", () -> service.createToken(token));
    }

    @Test
    void createIamTokenSupportsRefreshJwtBearerAndTokenExchangeBranches() {
        String sourceApplication = "arn:aws:sso::123456789012:application/ssoins-7223b02a5d9f7c8e/apl-1111111111111111";
        String targetApplication = "arn:aws:sso::123456789012:application/ssoins-7223b02a5d9f7c8e/apl-2222222222222222";
        var source = service.issueIamToken(sourceApplication, java.util.List.of("api:read"), true);

        ObjectNode refresh = mapper.createObjectNode();
        refresh.put("grantType", "refresh_token");
        refresh.put("refreshToken", source.refreshToken());
        assertOidcError("invalid_scope",
                () -> service.createIamToken(refresh, sourceApplication, java.util.List.of("api:write")));
        assertEquals(sourceApplication,
                service.createIamToken(refresh, sourceApplication, java.util.List.of("api:read")).clientId());

        ObjectNode jwt = mapper.createObjectNode();
        jwt.put("grantType", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        jwt.put("assertion", "header.payload.signature");
        assertEquals(targetApplication,
                service.createIamToken(jwt, targetApplication, java.util.List.of("openid")).clientId());

        ObjectNode exchange = mapper.createObjectNode();
        exchange.put("grantType", "urn:ietf:params:oauth:grant-type:token-exchange");
        exchange.put("subjectToken", source.accessToken());
        exchange.put("subjectTokenType", "urn:ietf:params:oauth:token-type:access_token");
        exchange.put("requestedTokenType", "urn:ietf:params:oauth:token-type:access_token");
        var exchanged = service.createIamToken(exchange, targetApplication, java.util.List.of("openid"));
        assertEquals(targetApplication, exchanged.clientId());
        assertNull(exchanged.refreshToken());

        assertOidcError("invalid_grant",
                () -> service.createIamToken(exchange, sourceApplication, java.util.List.of("openid")));
    }
    private static byte[] sha256(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void registerClientValidatesPublicClientAndGrantType() {
        ObjectNode confidential = mapper.createObjectNode();
        confidential.put("clientName", "Bad Client");
        confidential.put("clientType", "confidential");
        assertOidcError("invalid_client_metadata", () -> service.registerClient(confidential));

        ObjectNode unsupportedGrant = mapper.createObjectNode();
        unsupportedGrant.put("clientName", "Bad Grant");
        unsupportedGrant.put("clientType", "public");
        unsupportedGrant.putArray("grantTypes").add("client_credentials");
        assertOidcError("unsupported_grant_type", () -> service.registerClient(unsupportedGrant));
    }

    @Test
    void registerClientValidatesRequestShapes() {
        ObjectNode missingName = mapper.createObjectNode().put("clientType", "public");
        assertOidcError("invalid_request", () -> service.registerClient(missingName));

        ObjectNode badScopes = mapper.createObjectNode();
        badScopes.put("clientName", "Bad Scope");
        badScopes.put("clientType", "public");
        badScopes.put("scopes", "not-an-array");
        assertOidcError("invalid_request", () -> service.registerClient(badScopes));

        ObjectNode badArn = mapper.createObjectNode();
        badArn.put("clientName", "Bad ARN");
        badArn.put("clientType", "public");
        badArn.put("entitledApplicationArn", "not-an-arn");
        assertOidcError("invalid_client_metadata", () -> service.registerClient(badArn));
    }

    private static void assertOidcError(String code, Runnable action) {
        SsoOidcException error = assertThrows(SsoOidcException.class, action::run);
        assertEquals(code, error.error());
    }
}
