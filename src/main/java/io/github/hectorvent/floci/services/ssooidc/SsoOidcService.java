package io.github.hectorvent.floci.services.ssooidc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ssooidc.model.AuthorizationCode;
import io.github.hectorvent.floci.services.ssooidc.model.DeviceAuthorization;
import io.github.hectorvent.floci.services.ssooidc.model.RegisteredClient;
import io.github.hectorvent.floci.services.ssooidc.model.TokenSession;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class SsoOidcService implements Resettable {
    private static final long CLIENT_SECRET_LIFETIME_SECONDS = 90L * 24L * 60L * 60L;
    private static final int DEVICE_CODE_LIFETIME_SECONDS = 600;
    private static final int DEVICE_POLL_INTERVAL_SECONDS = 5;
    private static final int AUTHORIZATION_CODE_LIFETIME_SECONDS = 300;
    private static final int ACCESS_TOKEN_LIFETIME_SECONDS = 3600;
    private static final long REFRESH_TOKEN_LIFETIME_SECONDS = 30L * 24L * 60L * 60L;
    private static final Set<String> SUPPORTED_GRANT_TYPES = Set.of(
            "authorization_code",
            "urn:ietf:params:oauth:grant-type:device_code",
            "refresh_token");
    private static final Pattern APPLICATION_ARN = Pattern.compile(
            "arn:aws(?:-[a-z]{1,5}){0,3}:sso::[0-9]{12}:application/(?:sso)?ins-[a-zA-Z0-9-.]{16}/apl-[a-zA-Z0-9]{16}");

    private final StorageBackend<String, RegisteredClient> clients;
    private final StorageBackend<String, DeviceAuthorization> deviceAuthorizations;
    private final StorageBackend<String, AuthorizationCode> authorizationCodes;
    private final StorageBackend<String, TokenSession> tokenSessions;
    private final String baseUrl;

    @Inject
    public SsoOidcService(StorageFactory storageFactory, EmulatorConfig config) {
        this(storageFactory.create("ssooidc", "ssooidc-registered-clients.json",
                        new TypeReference<Map<String, RegisteredClient>>() {}),
                storageFactory.create("ssooidc", "ssooidc-device-authorizations.json",
                        new TypeReference<Map<String, DeviceAuthorization>>() {}),
                storageFactory.create("ssooidc", "ssooidc-authorization-codes.json",
                        new TypeReference<Map<String, AuthorizationCode>>() {}),
                storageFactory.create("ssooidc", "ssooidc-token-sessions.json",
                        new TypeReference<Map<String, TokenSession>>() {}),
                trimTrailingSlash(config.effectiveBaseUrl()));
    }

    SsoOidcService(StorageBackend<String, RegisteredClient> clients,
                   StorageBackend<String, DeviceAuthorization> deviceAuthorizations,
                   StorageBackend<String, AuthorizationCode> authorizationCodes,
                   StorageBackend<String, TokenSession> tokenSessions,
                   String baseUrl) {
        this.clients = clients;
        this.deviceAuthorizations = deviceAuthorizations;
        this.authorizationCodes = authorizationCodes;
        this.tokenSessions = tokenSessions;
        this.baseUrl = trimTrailingSlash(baseUrl);
    }

    public synchronized RegisteredClient registerClient(JsonNode request) {
        String clientName = requiredText(request, "clientName");
        if (clientName.isBlank()) {
            throw invalidClientMetadata("clientName must not be empty");
        }
        String clientType = requiredText(request, "clientType");
        if (!"public".equals(clientType)) {
            throw invalidClientMetadata("clientType must be public");
        }

        List<String> scopes = optionalStringList(request, "scopes", "invalid_scope");
        List<String> redirectUris = optionalStringList(request, "redirectUris", "invalid_redirect_uri");
        List<String> grantTypes = optionalStringList(request, "grantTypes", "unsupported_grant_type");
        for (String grantType : grantTypes) {
            if (!SUPPORTED_GRANT_TYPES.contains(grantType)) {
                throw new SsoOidcException("unsupported_grant_type",
                        "Unsupported grant type: " + grantType, 400);
            }
        }

        String issuerUrl = optionalText(request, "issuerUrl");
        if (issuerUrl != null && issuerUrl.isBlank()) {
            throw invalidClientMetadata("issuerUrl must not be empty");
        }
        String entitledApplicationArn = optionalText(request, "entitledApplicationArn");
        if (entitledApplicationArn != null && !APPLICATION_ARN.matcher(entitledApplicationArn).matches()) {
            throw invalidClientMetadata("entitledApplicationArn is invalid");
        }

        long issuedAt = System.currentTimeMillis() / 1000L;
        String clientId = UUID.randomUUID().toString().replace("-", "");
        String clientSecret = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        RegisteredClient client = new RegisteredClient(
                clientId,
                clientSecret,
                issuedAt,
                issuedAt + CLIENT_SECRET_LIFETIME_SECONDS,
                clientName,
                clientType,
                scopes,
                redirectUris,
                grantTypes,
                issuerUrl,
                entitledApplicationArn);
        clients.put(clientId, client);
        return client;
    }

    public synchronized DeviceAuthorization startDeviceAuthorization(JsonNode request) {
        String clientId = requiredText(request, "clientId");
        String clientSecret = requiredText(request, "clientSecret");
        String startUrl = requiredText(request, "startUrl");
        if (startUrl.isBlank()) {
            throw new SsoOidcException("invalid_request", "startUrl must not be empty", 400);
        }
        RegisteredClient client = requireClientCredentials(clientId, clientSecret);
        if (!"public".equals(client.clientType())) {
            throw new SsoOidcException("unauthorized_client", "Client is not a public client", 400);
        }
        String deviceGrant = "urn:ietf:params:oauth:grant-type:device_code";
        if (!client.grantTypes().isEmpty() && !client.grantTypes().contains(deviceGrant)) {
            throw new SsoOidcException("unauthorized_client", "Client is not registered for the device code grant", 400);
        }

        long now = System.currentTimeMillis() / 1000L;
        String deviceCode = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        String rawUserCode = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String userCode = rawUserCode.substring(0, 4) + "-" + rawUserCode.substring(4);
        DeviceAuthorization authorization = new DeviceAuthorization(
                deviceCode,
                userCode,
                clientId,
                startUrl,
                now + DEVICE_CODE_LIFETIME_SECONDS,
                DEVICE_POLL_INTERVAL_SECONDS,
                false,
                0L,
                null);
        deviceAuthorizations.put(deviceCode, authorization);
        return authorization;
    }

    public synchronized DeviceAuthorization authorizeDevice(String userCode, String principalId) {
        if (userCode == null || userCode.isBlank()) {
            throw new SsoOidcException("invalid_request", "user_code is required", 400);
        }
        DeviceAuthorization authorization = deviceAuthorizations.scan(key -> true).stream()
                .filter(item -> userCode.equals(item.userCode()))
                .findFirst()
                .orElseThrow(() -> new SsoOidcException("invalid_grant", "User code is invalid", 400));
        if (authorization.expiresAtEpochSeconds() <= System.currentTimeMillis() / 1000L) {
            throw new SsoOidcException("expired_token", "Device code has expired", 400);
        }
        DeviceAuthorization authorized = new DeviceAuthorization(
                authorization.deviceCode(), authorization.userCode(), authorization.clientId(), authorization.startUrl(),
                authorization.expiresAtEpochSeconds(), authorization.intervalSeconds(), true,
                authorization.lastPollAtEpochMillis(), principalId);
        deviceAuthorizations.put(authorization.deviceCode(), authorized);
        return authorized;
    }

    public synchronized AuthorizationCode createAuthorizationCode(
            String clientId, String redirectUri, String codeChallenge) {
        RegisteredClient client = requireClient(clientId);
        if (!client.grantTypes().isEmpty() && !client.grantTypes().contains("authorization_code")) {
            throw new SsoOidcException("unauthorized_client", "Client is not registered for authorization code", 400);
        }
        return createAuthorizationCode(clientId, redirectUri, codeChallenge, client.redirectUris(), null);
    }

    public synchronized AuthorizationCode createIamAuthorizationCode(
            String applicationArn, String redirectUri, String codeChallenge, List<String> allowedRedirectUris) {
        return createAuthorizationCode(applicationArn, redirectUri, codeChallenge, allowedRedirectUris, null);
    }

    private AuthorizationCode createAuthorizationCode(
            String clientId, String redirectUri, String codeChallenge, List<String> allowedRedirectUris,
            String principalId) {
        return createAuthorizationCodeForPrincipal(clientId, redirectUri, codeChallenge, allowedRedirectUris, principalId);
    }

    public synchronized AuthorizationCode createAuthorizationCodeForPrincipal(
            String clientId, String redirectUri, String codeChallenge, List<String> allowedRedirectUris,
            String principalId) {
        if (redirectUri == null || redirectUri.isBlank() || !allowedRedirectUris.contains(redirectUri)) {
            throw new SsoOidcException("invalid_redirect_uri", "Redirect URI is not registered", 400);
        }
        if (codeChallenge == null || codeChallenge.isBlank()) {
            throw new SsoOidcException("invalid_request", "code_challenge is required", 400);
        }
        String code = randomToken();
        AuthorizationCode authorizationCode = new AuthorizationCode(
                code, clientId, redirectUri, codeChallenge,
                System.currentTimeMillis() / 1000L + AUTHORIZATION_CODE_LIFETIME_SECONDS, principalId);
        authorizationCodes.put(code, authorizationCode);
        return authorizationCode;
    }

    public synchronized TokenSession createToken(JsonNode request) {
        String clientId = requiredText(request, "clientId");
        String clientSecret = requiredText(request, "clientSecret");
        RegisteredClient client = requireClientCredentials(clientId, clientSecret);
        String grantType = requiredText(request, "grantType");
        if (!SUPPORTED_GRANT_TYPES.contains(grantType)) {
            throw new SsoOidcException("unsupported_grant_type", "Unsupported grant type: " + grantType, 400);
        }
        if (!client.grantTypes().isEmpty() && !client.grantTypes().contains(grantType)) {
            throw new SsoOidcException("unauthorized_client", "Client is not registered for this grant type", 400);
        }

        return switch (grantType) {
            case "urn:ietf:params:oauth:grant-type:device_code" -> createTokenFromDeviceCode(request, client);
            case "authorization_code" -> createTokenFromAuthorizationCode(request, client);
            case "refresh_token" -> createTokenFromRefreshToken(request, client);
            default -> throw new SsoOidcException("unsupported_grant_type", "Unsupported grant type", 400);
        };
    }

    private TokenSession createTokenFromDeviceCode(JsonNode request, RegisteredClient client) {
        String deviceCode = requiredText(request, "deviceCode");
        DeviceAuthorization authorization = requireDeviceAuthorization(deviceCode);
        if (!client.clientId().equals(authorization.clientId())) {
            throw new SsoOidcException("invalid_grant", "Device code belongs to another client", 400);
        }
        long nowSeconds = System.currentTimeMillis() / 1000L;
        long nowMillis = System.currentTimeMillis();
        if (authorization.expiresAtEpochSeconds() <= nowSeconds) {
            deviceAuthorizations.delete(deviceCode);
            throw new SsoOidcException("expired_token", "Device code has expired", 400);
        }
        if (authorization.lastPollAtEpochMillis() > 0
                && nowMillis - authorization.lastPollAtEpochMillis() < authorization.intervalSeconds() * 1000L) {
            throw new SsoOidcException("slow_down", "Token polling is too frequent", 400);
        }
        if (!authorization.authorized()) {
            deviceAuthorizations.put(deviceCode, new DeviceAuthorization(
                    authorization.deviceCode(), authorization.userCode(), authorization.clientId(), authorization.startUrl(),
                    authorization.expiresAtEpochSeconds(), authorization.intervalSeconds(), false, nowMillis,
                    authorization.principalId()));
            throw new SsoOidcException("authorization_pending", "Device authorization is pending", 400);
        }
        deviceAuthorizations.delete(deviceCode);
        return issueToken(client, authorization.principalId());
    }

    private TokenSession createTokenFromAuthorizationCode(JsonNode request, RegisteredClient client) {
        String code = requiredText(request, "code");
        String codeVerifier = requiredText(request, "codeVerifier");
        String redirectUri = requiredText(request, "redirectUri");
        AuthorizationCode authorizationCode = authorizationCodes.get(code)
                .orElseThrow(() -> new SsoOidcException("invalid_grant", "Authorization code is invalid", 400));
        if (authorizationCode.expiresAtEpochSeconds() <= System.currentTimeMillis() / 1000L) {
            authorizationCodes.delete(code);
            throw new SsoOidcException("expired_token", "Authorization code has expired", 400);
        }
        if (!client.clientId().equals(authorizationCode.clientId())
                || !redirectUri.equals(authorizationCode.redirectUri())
                || !pkceChallenge(codeVerifier).equals(authorizationCode.codeChallenge())) {
            throw new SsoOidcException("invalid_grant", "Authorization code validation failed", 400);
        }
        authorizationCodes.delete(code);
        return issueToken(client, authorizationCode.principalId());
    }

    private TokenSession createTokenFromRefreshToken(JsonNode request, RegisteredClient client) {
        String refreshToken = requiredText(request, "refreshToken");
        TokenSession prior = tokenSessions.get("refresh:" + refreshToken)
                .orElseThrow(() -> new SsoOidcException("invalid_grant", "Refresh token is invalid", 400));
        if (!client.clientId().equals(prior.clientId())) {
            throw new SsoOidcException("invalid_grant", "Refresh token belongs to another client", 400);
        }
        if (prior.refreshTokenExpiresAtEpochSeconds() <= System.currentTimeMillis() / 1000L) {
            revokeTokenPair(prior);
            throw new SsoOidcException("expired_token", "Refresh token has expired", 400);
        }
        rotateRefreshToken(prior);
        return issueToken(client, prior.principalId());
    }

    private TokenSession issueToken(RegisteredClient client, String principalId) {
        return issueToken(client.clientId(), client.scopes(), true, principalId);
    }

    public synchronized TokenSession createIamToken(JsonNode request, String applicationArn, List<String> grantedScopes) {
        String grantType = requiredText(request, "grantType");
        return switch (grantType) {
            case "authorization_code" -> createIamTokenFromAuthorizationCode(request, applicationArn, grantedScopes);
            case "refresh_token" -> createIamTokenFromRefreshToken(request, applicationArn, grantedScopes);
            case "urn:ietf:params:oauth:grant-type:jwt-bearer" -> {
                requiredText(request, "assertion");
                yield issueToken(applicationArn, grantedScopes, true, null);
            }
            case "urn:ietf:params:oauth:grant-type:token-exchange" ->
                    createIamTokenFromExchange(request, applicationArn, grantedScopes);
            default -> throw new SsoOidcException("unsupported_grant_type", "Unsupported grant type: " + grantType, 400);
        };
    }

    private TokenSession createIamTokenFromAuthorizationCode(
            JsonNode request, String applicationArn, List<String> grantedScopes) {
        String code = requiredText(request, "code");
        String codeVerifier = requiredText(request, "codeVerifier");
        String redirectUri = requiredText(request, "redirectUri");
        AuthorizationCode authorizationCode = authorizationCodes.get(code)
                .orElseThrow(() -> new SsoOidcException("invalid_grant", "Authorization code is invalid", 400));
        if (authorizationCode.expiresAtEpochSeconds() <= System.currentTimeMillis() / 1000L) {
            authorizationCodes.delete(code);
            throw new SsoOidcException("expired_token", "Authorization code has expired", 400);
        }
        if (!applicationArn.equals(authorizationCode.clientId())
                || !redirectUri.equals(authorizationCode.redirectUri())
                || !pkceChallenge(codeVerifier).equals(authorizationCode.codeChallenge())) {
            throw new SsoOidcException("invalid_grant", "Authorization code validation failed", 400);
        }
        authorizationCodes.delete(code);
        return issueToken(applicationArn, grantedScopes, true, authorizationCode.principalId());
    }

    private TokenSession createIamTokenFromRefreshToken(
            JsonNode request, String applicationArn, List<String> grantedScopes) {
        String refreshToken = requiredText(request, "refreshToken");
        TokenSession prior = requireRefreshToken(applicationArn, refreshToken);
        if (!prior.scopes().containsAll(grantedScopes)) {
            throw new SsoOidcException("invalid_scope", "Requested scopes exceed the refresh token scopes", 400);
        }
        rotateRefreshToken(prior);
        return issueToken(applicationArn, grantedScopes, true, prior.principalId());
    }

    private TokenSession createIamTokenFromExchange(
            JsonNode request, String applicationArn, List<String> grantedScopes) {
        String subjectToken = requiredText(request, "subjectToken");
        String subjectTokenType = requiredText(request, "subjectTokenType");
        if (!"urn:ietf:params:oauth:token-type:access_token".equals(subjectTokenType)) {
            throw new SsoOidcException("invalid_request", "subjectTokenType must be access_token", 400);
        }
        String requestedTokenType = optionalText(request, "requestedTokenType");
        if (requestedTokenType != null
                && !Set.of("urn:ietf:params:oauth:token-type:access_token",
                        "urn:ietf:params:oauth:token-type:refresh_token").contains(requestedTokenType)) {
            throw new SsoOidcException("invalid_request", "requestedTokenType is invalid", 400);
        }
        TokenSession subject = requireAccessToken(subjectToken);
        if (applicationArn.equals(subject.clientId())) {
            throw new SsoOidcException("invalid_grant", "Subject token must be issued to a different application", 400);
        }
        return issueToken(applicationArn, grantedScopes,
                !"urn:ietf:params:oauth:token-type:access_token".equals(requestedTokenType), subject.principalId());
    }

    public TokenSession requireRefreshToken(String applicationArn, String refreshToken) {
        TokenSession prior = tokenSessions.get("refresh:" + refreshToken)
                .orElseThrow(() -> new SsoOidcException("invalid_grant", "Refresh token is invalid", 400));
        if (!applicationArn.equals(prior.clientId())) {
            throw new SsoOidcException("invalid_grant", "Refresh token belongs to another application", 400);
        }
        if (prior.refreshTokenExpiresAtEpochSeconds() <= System.currentTimeMillis() / 1000L) {
            tokenSessions.delete("refresh:" + refreshToken);
            throw new SsoOidcException("expired_token", "Refresh token has expired", 400);
        }
        return prior;
    }

    public TokenSession requireAccessToken(String accessToken) {
        TokenSession session = tokenSessions.get("access:" + accessToken)
                .orElseThrow(() -> new SsoOidcException("invalid_grant", "Access token is invalid", 400));
        if (session.accessTokenExpiresAtEpochSeconds() <= System.currentTimeMillis() / 1000L) {
            revokeTokenPair(session);
            throw new SsoOidcException("expired_token", "Access token has expired", 400);
        }
        return session;
    }

    public synchronized void revokeAccessTokenSession(String accessToken) {
        TokenSession session = requireAccessToken(accessToken);
        revokeTokenPair(session);
    }

    private void rotateRefreshToken(TokenSession session) {
        if (session.refreshToken() != null) {
            tokenSessions.delete("refresh:" + session.refreshToken());
        }
    }

    private void revokeTokenPair(TokenSession session) {
        tokenSessions.delete("access:" + session.accessToken());
        rotateRefreshToken(session);
    }

    public TokenSession issueIamToken(String applicationArn, List<String> scopes, boolean issueRefreshToken) {
        return issueToken(applicationArn, scopes, issueRefreshToken, null);
    }

    private TokenSession issueToken(String clientId, List<String> scopes, boolean issueRefreshToken,
                                    String principalId) {
        long now = System.currentTimeMillis() / 1000L;
        String accessToken = randomToken();
        String refreshToken = issueRefreshToken ? randomToken() : null;
        TokenSession session = new TokenSession(
                accessToken, refreshToken, clientId, scopes,
                now + ACCESS_TOKEN_LIFETIME_SECONDS,
                issueRefreshToken ? now + REFRESH_TOKEN_LIFETIME_SECONDS : 0L,
                principalId);
        tokenSessions.put("access:" + accessToken, session);
        if (refreshToken != null) {
            tokenSessions.put("refresh:" + refreshToken, session);
        }
        return session;
    }

    public RegisteredClient requireClient(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new SsoOidcException("invalid_client", "clientId is required", 400);
        }
        return clients.get(clientId)
                .orElseThrow(() -> new SsoOidcException("invalid_client", "Client not found", 401));
    }

    public RegisteredClient requireClientCredentials(String clientId, String clientSecret) {
        RegisteredClient client = requireClient(clientId);
        long now = System.currentTimeMillis() / 1000L;
        if (clientSecret == null || !client.clientSecret().equals(clientSecret)
                || client.clientSecretExpiresAt() <= now) {
            throw new SsoOidcException("invalid_client", "Client credentials are invalid or expired", 401);
        }
        return client;
    }

    public DeviceAuthorization requireDeviceAuthorization(String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) {
            throw new SsoOidcException("invalid_request", "deviceCode is required", 400);
        }
        return deviceAuthorizations.get(deviceCode)
                .orElseThrow(() -> new SsoOidcException("invalid_grant", "Device code is invalid", 400));
    }

    public String verificationUri() {
        return baseUrl + "/device";
    }

    public String verificationUriComplete(DeviceAuthorization authorization) {
        return verificationUri() + "?user_code=" + authorization.userCode();
    }

    public String authorizationEndpoint() {
        return baseUrl + "/authorize";
    }

    public String tokenEndpoint() {
        return baseUrl + "/token";
    }

    @Override
    public void clear() {
        clients.clear();
        deviceAuthorizations.clear();
        authorizationCodes.clear();
        tokenSessions.clear();
    }

    private static String requiredText(JsonNode request, String field) {
        String value = optionalText(request, field);
        if (value == null) {
            throw new SsoOidcException("invalid_request", field + " is required", 400);
        }
        return value;
    }

    static String optionalText(JsonNode request, String field) {
        if (request == null || !request.has(field) || request.get(field).isNull()) {
            return null;
        }
        JsonNode value = request.get(field);
        if (!value.isTextual()) {
            throw new SsoOidcException("invalid_request", field + " must be a string", 400);
        }
        return value.textValue();
    }

    private static List<String> optionalStringList(JsonNode request, String field, String semanticError) {
        if (request == null || !request.has(field) || request.get(field).isNull()) {
            return List.of();
        }
        JsonNode node = request.get(field);
        if (!node.isArray()) {
            throw new SsoOidcException("invalid_request", field + " must be an array", 400);
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                String description = field + " must contain non-empty strings";
                throw new SsoOidcException(semanticError, description, 400);
            }
            values.add(item.textValue());
        }
        return new ArrayList<>(values);
    }

    private static SsoOidcException invalidClientMetadata(String description) {
        return new SsoOidcException("invalid_client_metadata", description, 400);
    }

    private static String randomToken() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    private static String pkceChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:4566";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
