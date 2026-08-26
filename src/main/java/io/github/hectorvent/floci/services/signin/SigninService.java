package io.github.hectorvent.floci.services.signin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.SessionCreds;
import io.github.hectorvent.floci.services.signin.model.AuthorizationRequest;
import io.github.hectorvent.floci.services.signin.model.TokenResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Local implementation of the AWS Sign-In OAuth flow used by {@code aws login}.
 *
 * <p>Floci has no external console to authenticate against, so the authorization endpoint
 * presents a local consent page for the emulator account before issuing a one-time PKCE
 * authorization code. The token endpoint still follows the AWS flow: codes are single-use,
 * PKCE is verified,
 * refresh tokens expire, and returned credentials are registered with IAM for request signing.
 */
@ApplicationScoped
public class SigninService {

    static final String SAME_DEVICE_CLIENT = "arn:aws:signin:::devtools/same-device";
    static final String CROSS_DEVICE_CLIENT = "arn:aws:signin:::devtools/cross-device";
    static final int ACCESS_TOKEN_TTL_SECONDS = 900;
    private static final long AUTHORIZATION_CODE_TTL_SECONDS = 300;
    private static final long REFRESH_TOKEN_TTL_SECONDS = 12 * 60 * 60;
    private static final long EXPIRED_TOKEN_TOMBSTONE_TTL_SECONDS = ACCESS_TOKEN_TTL_SECONDS;
    private static final int MAX_AUTHORIZATION_CODE_LENGTH = 512;
    private static final int MAX_REDIRECT_URI_LENGTH = 2048;
    private static final int MAX_RESOURCE_LENGTH = 2048;
    private static final int MAX_REFRESH_TOKEN_LENGTH = 2048;
    private static final int MAX_STATE_LENGTH = 128;
    private static final Pattern PKCE_VALUE_PATTERN = Pattern.compile("[A-Za-z0-9._~-]{43,128}");
    private static final Pattern AWS_SIGNIN_HOST_PATTERN =
            Pattern.compile("[a-z]{2}-[a-z-]+-\\d+\\.signin\\.aws\\.amazon\\.com");

    private final IamService iamService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SecureRandom random;
    private final ConcurrentHashMap<String, AuthorizationRequest> pendingAuthorizations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AuthorizationCode> authorizationCodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RefreshGrant> refreshGrants = new ConcurrentHashMap<>();

    @Inject
    public SigninService(IamService iamService, RegionResolver regionResolver, ObjectMapper objectMapper,
                         Clock clock) {
        this.iamService = iamService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.random = new SecureRandom();
    }

    public String beginAuthorization(String clientId, String codeChallenge, String codeChallengeMethod,
                                     String redirectUri, String responseType, String scope, String state,
                                     String resource) {
        validateClient(clientId);
        if (!"SHA-256".equals(codeChallengeMethod)) {
            throw new SigninException("invalid_request", "code_challenge_method must be SHA-256");
        }
        if (!isValidPkceValue(codeChallenge)) {
            throw new SigninException("invalid_request",
                    "code_challenge must be 43-128 characters using the AWS PKCE alphabet");
        }
        if (!"code".equals(responseType) || !"openid".equals(scope)) {
            throw new SigninException("invalid_request", "response_type=code and scope=openid are required");
        }
        validateRedirectUri(clientId, redirectUri);
        if (state == null || state.isEmpty() || state.length() > MAX_STATE_LENGTH) {
            throw new SigninException("invalid_request", "state must be 1-128 characters");
        }
        validateOptionalResource(resource);
        String accountId = regionResolver.getAccountId();
        Instant now = clock.instant();
        pendingAuthorizations.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        String requestId = randomToken(24);
        pendingAuthorizations.put(requestId, new AuthorizationRequest(
                clientId, codeChallenge, redirectUri, resource, state, accountId,
                now.plusSeconds(AUTHORIZATION_CODE_TTL_SECONDS)));
        return requestId;
    }

    public AuthorizationRequest pendingAuthorization(String requestId) {
        validateRequestId(requestId);
        AuthorizationRequest request = pendingAuthorizations.get(requestId);
        if (request == null || !request.expiresAt().isAfter(clock.instant())) {
            pendingAuthorizations.remove(requestId, request);
            throw new SigninException("invalid_request", "The authorization request is invalid or expired");
        }
        return request;
    }

    public String completeAuthorization(String requestId) {
        Instant now = clock.instant();
        AuthorizationRequest request = consumeAuthorizationRequest(requestId, now);
        cleanupExpiredAuthorizationCodes(now);
        String code = randomToken(32);
        authorizationCodes.put(code, AuthorizationCode.active(
                request.clientId(), request.codeChallenge(), request.redirectUri(), request.resource(),
                request.accountId(),
                now.plusSeconds(AUTHORIZATION_CODE_TTL_SECONDS)));
        return appendQuery(request.redirectUri(), "code", code, "state", request.state());
    }

    public String denyAuthorization(String requestId) {
        AuthorizationRequest request = consumeAuthorizationRequest(requestId, clock.instant());
        return appendQuery(request.redirectUri(), "error", "access_denied", "state", request.state());
    }

    private AuthorizationRequest consumeAuthorizationRequest(String requestId, Instant now) {
        validateRequestId(requestId);
        AuthorizationRequest request = pendingAuthorizations.remove(requestId);
        if (request == null || !request.expiresAt().isAfter(now)) {
            throw new SigninException("invalid_request", "The authorization request is invalid or expired");
        }
        return request;
    }

    private static void validateRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new SigninException("invalid_request", "request_id is required");
        }
    }

    public TokenResult exchange(String clientId, String grantType, String code, String redirectUri,
                                String codeVerifier, String refreshToken, String resource) {
        if (!"authorization_code".equals(grantType) && !"refresh_token".equals(grantType)) {
            throw SigninTokenException.unsupportedGrant();
        }
        try {
            validateClient(clientId);
            validateOptionalResource(resource);
            if ("authorization_code".equals(grantType)) {
                return exchangeCode(clientId, code, redirectUri, codeVerifier, resource);
            }
            return refresh(clientId, refreshToken);
        } catch (SigninTokenException e) {
            throw e;
        } catch (SigninException e) {
            throw SigninTokenException.validation();
        }
    }

    private TokenResult exchangeCode(String clientId, String code, String redirectUri, String codeVerifier,
                                     String resource) {
        if (code == null || code.isEmpty() || code.length() > MAX_AUTHORIZATION_CODE_LENGTH) {
            throw new SigninException("invalid_request", "code must be 1-512 characters");
        }
        if (redirectUri == null || redirectUri.isEmpty() || redirectUri.length() > MAX_REDIRECT_URI_LENGTH) {
            throw new SigninException("invalid_request", "redirect_uri must be 1-2048 characters");
        }
        if (!isValidPkceValue(codeVerifier)) {
            throw new SigninException("invalid_request",
                    "code_verifier must be 43-128 characters using the AWS PKCE alphabet");
        }
        Instant now = clock.instant();
        AuthorizationCode authorization = claimAuthorizationCode(code, now);
        if (!clientId.equals(authorization.clientId())
                || !redirectUri.equals(authorization.redirectUri())
                || !matchesPkce(authorization.codeChallenge(), codeVerifier)) {
            throw new SigninException("invalid_grant", "The authorization code is invalid or expired");
        }
        return issueTokens(clientId, resource != null ? resource : authorization.resource(),
                authorization.accountId(), true, now);
    }

    private TokenResult refresh(String clientId, String refreshToken) {
        if (refreshToken == null || refreshToken.isEmpty() || refreshToken.length() > MAX_REFRESH_TOKEN_LENGTH) {
            throw new SigninException("invalid_request", "refresh_token must be 1-2048 characters");
        }
        Instant now = clock.instant();
        RefreshGrant grant = refreshGrants.get(refreshToken);
        boolean identityMatches = grant != null && clientId.equals(grant.clientId());
        cleanupExpiredRefreshGrants(now, identityMatches ? refreshToken : null);
        if (grant == null) {
            throw new SigninException("invalid_grant", "The refresh token is invalid or expired");
        }
        rejectExpiredRefreshTombstone(refreshToken, grant, now);
        if (!identityMatches) {
            throw new SigninException("invalid_grant", "The refresh token is invalid or expired");
        }
        synchronized (grant) {
            if (refreshGrants.get(refreshToken) != grant) {
                rejectExpiredRefreshTombstone(refreshToken, refreshGrants.get(refreshToken), now);
                throw new SigninException("invalid_grant", "The refresh token is invalid or expired");
            }
            if (!grant.expiresAt().isAfter(now)) {
                if (replaceWithExpiredRefreshTombstone(refreshToken, grant, now)) {
                    throw SigninTokenException.refreshTokenExpired();
                }
                throw SigninTokenException.validation();
            }
            if (grant.replayResult() != null) {
                if (grant.replayExpiresAt().isAfter(now)) {
                    return grant.replayResultWithRemainingLifetime(now);
                }
                refreshGrants.remove(refreshToken, grant);
                throw new SigninException("invalid_grant", "The refresh token is invalid or expired");
            }

            Instant accessTokenExpiresAt = now.plusSeconds(ACCESS_TOKEN_TTL_SECONDS);
            StoredRefreshGrant successor = storeRefreshGrant(
                    clientId, grant.resource(), grant.accountId(), grant.expiresAt());
            TokenResult result;
            try {
                result = issueAccessToken(clientId, grant.resource(), false, successor.token(),
                        grant.accountId(), now);
            } catch (RuntimeException e) {
                refreshGrants.remove(successor.token(), successor.grant());
                throw e;
            }
            grant.cacheReplay(result, accessTokenExpiresAt);
            return result;
        }
    }

    private TokenResult issueTokens(String clientId, String resource, String accountId, boolean includeIdToken,
                                    Instant now) {
        cleanupExpiredRefreshGrants(now, null);
        StoredRefreshGrant stored = storeRefreshGrant(
                clientId, resource, accountId, now.plusSeconds(REFRESH_TOKEN_TTL_SECONDS));
        try {
            return issueAccessToken(clientId, resource, includeIdToken, stored.token(), accountId, now);
        } catch (RuntimeException e) {
            refreshGrants.remove(stored.token(), stored.grant());
            throw e;
        }
    }

    private TokenResult issueAccessToken(String clientId, String resource, boolean includeIdToken,
                                         String refreshToken, String accountId, Instant issuedAt) {
        String accessKeyId = "ASIA" + randomAlphaNumeric(16);
        String secretAccessKey = randomAlphaNumeric(40);
        String sessionToken = randomAlphaNumeric(200);
        Instant expiration = issuedAt.plusSeconds(ACCESS_TOKEN_TTL_SECONDS);
        String principalArn = "arn:aws:iam::" + accountId + ":root";
        iamService.registerSessionForAccount(
                accountId, accessKeyId, secretAccessKey, principalArn, expiration, null);

        SessionCreds accessToken = new SessionCreds(accessKeyId, secretAccessKey, sessionToken);
        String idToken = includeIdToken ? idToken(principalArn, accountId, clientId, issuedAt) : null;
        return new TokenResult(accessToken, ACCESS_TOKEN_TTL_SECONDS, refreshToken, idToken);
    }

    private StoredRefreshGrant storeRefreshGrant(String clientId, String resource, String accountId,
                                                 Instant expiresAt) {
        while (true) {
            String token = randomToken(48);
            RefreshGrant grant = new RefreshGrant(clientId, resource, accountId, expiresAt);
            if (refreshGrants.putIfAbsent(token, grant) == null) {
                return new StoredRefreshGrant(token, grant);
            }
        }
    }

    private String idToken(String principalArn, String accountId, String clientId, Instant issuedAt) {
        try {
            long now = issuedAt.getEpochSecond();
            String header = encodeJson(Map.of("alg", "none", "typ", "JWT"));
            String payload = encodeJson(Map.of(
                    "iss", "https://signin.amazonaws.com",
                    "sub", principalArn,
                    "aud", clientId,
                    "aws_account_id", accountId,
                    "iat", now,
                    "exp", now + REFRESH_TOKEN_TTL_SECONDS));
            return header + "." + payload + ".";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to encode local Sign-In identity token", e);
        }
    }

    private String encodeJson(Map<String, ?> value) throws JsonProcessingException {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(value));
    }

    private static boolean matchesPkce(String challenge, String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            String calculated = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return MessageDigest.isEqual(calculated.getBytes(StandardCharsets.US_ASCII),
                    challenge.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the AWS Sign-In protocol", e);
        }
    }

    private static void validateClient(String clientId) {
        if (!SAME_DEVICE_CLIENT.equals(clientId) && !CROSS_DEVICE_CLIENT.equals(clientId)) {
            throw new SigninException("invalid_client", "Unsupported AWS Sign-In client");
        }
    }

    private static boolean isValidPkceValue(String value) {
        return value != null && PKCE_VALUE_PATTERN.matcher(value).matches();
    }

    private static void validateRedirectUri(String clientId, String redirectUri) {
        if (redirectUri == null || redirectUri.isEmpty() || redirectUri.length() > MAX_REDIRECT_URI_LENGTH) {
            throw new SigninException("invalid_request", "redirect_uri must be 1-2048 characters");
        }

        URI uri;
        try {
            uri = URI.create(redirectUri);
        } catch (IllegalArgumentException e) {
            throw new SigninException("invalid_request", "redirect_uri is invalid");
        }

        boolean valid = SAME_DEVICE_CLIENT.equals(clientId)
                ? isSameDeviceRedirect(uri)
                : isCrossDeviceRedirect(uri);
        if (!valid) {
            throw new SigninException("invalid_request", "redirect_uri is invalid for the AWS Sign-In client");
        }
    }

    private static void validateOptionalResource(String resource) {
        if (resource != null && (resource.isEmpty() || resource.length() > MAX_RESOURCE_LENGTH)) {
            throw new SigninException("invalid_request", "resource must be 1-2048 characters when provided");
        }
    }

    private AuthorizationCode claimAuthorizationCode(String code, Instant now) {
        while (true) {
            AuthorizationCode authorization = authorizationCodes.get(code);
            if (authorization == null) {
                throw new SigninException("invalid_grant", "The authorization code is invalid or expired");
            }
            if (authorization.expiredTombstone()) {
                if (authorization.expiredTombstoneUntil().isAfter(now)) {
                    throw SigninTokenException.authorizationCodeExpired();
                }
                if (authorizationCodes.remove(code, authorization)) {
                    throw new SigninException("invalid_grant", "The authorization code is invalid or expired");
                }
                continue;
            }
            if (!authorization.expiresAt().isAfter(now)) {
                AuthorizationCode tombstone = authorization.toExpiredTombstone();
                if (tombstone.expiredTombstoneUntil().isAfter(now)) {
                    if (authorizationCodes.replace(code, authorization, tombstone)) {
                        throw SigninTokenException.authorizationCodeExpired();
                    }
                } else if (authorizationCodes.remove(code, authorization)) {
                    throw SigninTokenException.validation();
                }
                continue;
            }
            if (authorizationCodes.remove(code, authorization)) {
                return authorization;
            }
        }
    }

    private void cleanupExpiredAuthorizationCodes(Instant now) {
        authorizationCodes.forEach((code, authorization) -> {
            if (authorization.expiredTombstone()) {
                if (!authorization.expiredTombstoneUntil().isAfter(now)) {
                    authorizationCodes.remove(code, authorization);
                }
                return;
            }
            if (!authorization.expiresAt().isAfter(now)) {
                AuthorizationCode tombstone = authorization.toExpiredTombstone();
                if (tombstone.expiredTombstoneUntil().isAfter(now)) {
                    authorizationCodes.replace(code, authorization, tombstone);
                } else {
                    authorizationCodes.remove(code, authorization);
                }
            }
        });
    }

    private void cleanupExpiredRefreshGrants(Instant now, String refreshTokenInUse) {
        refreshGrants.forEach((token, grant) -> {
            if (token.equals(refreshTokenInUse)) {
                return;
            }
            synchronized (grant) {
                if (refreshGrants.get(token) != grant || !grant.retentionExpiredAt(now)) {
                    return;
                }
                if (!grant.expiredTombstone() && !grant.expiresAt().isAfter(now)) {
                    replaceWithExpiredRefreshTombstone(token, grant, now);
                } else {
                    refreshGrants.remove(token, grant);
                }
            }
        });
    }

    private void rejectExpiredRefreshTombstone(String token, RefreshGrant grant, Instant now) {
        if (grant == null || !grant.expiredTombstone()) {
            return;
        }
        if (grant.expiredTombstoneUntil().isAfter(now)) {
            throw SigninTokenException.refreshTokenExpired();
        }
        refreshGrants.remove(token, grant);
        throw new SigninException("invalid_grant", "The refresh token is invalid or expired");
    }

    private boolean replaceWithExpiredRefreshTombstone(String token, RefreshGrant grant, Instant now) {
        Instant retainedUntil = grant.expiresAt().plusSeconds(EXPIRED_TOKEN_TOMBSTONE_TTL_SECONDS);
        if (retainedUntil.isAfter(now)) {
            return refreshGrants.replace(token, grant, RefreshGrant.expired(retainedUntil));
        }
        refreshGrants.remove(token, grant);
        return false;
    }

    static Instant refreshGrantRetentionExpiry(Instant expiresAt, Instant replayExpiresAt) {
        return replayExpiresAt == null || expiresAt.isBefore(replayExpiresAt)
                ? expiresAt : replayExpiresAt;
    }

    private static boolean isSameDeviceRedirect(URI uri) {
        return "http".equalsIgnoreCase(uri.getScheme())
                && "127.0.0.1".equals(uri.getHost())
                && uri.getPort() >= 1 && uri.getPort() <= 65535
                && "/oauth/callback".equals(uri.getRawPath())
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && uri.getUserInfo() == null;
    }

    private static boolean isCrossDeviceRedirect(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && AWS_SIGNIN_HOST_PATTERN.matcher(uri.getHost()).matches()
                && uri.getPort() == -1
                && "/v1/sessions/confirmation".equals(uri.getRawPath())
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && uri.getUserInfo() == null;
    }

    private static int remainingSeconds(Instant now, Instant expiresAt) {
        Duration remaining = Duration.between(now, expiresAt);
        long seconds = remaining.getSeconds() + (remaining.getNano() == 0 ? 0 : 1);
        return Math.toIntExact(Math.max(1, Math.min(ACCESS_TOKEN_TTL_SECONDS, seconds)));
    }

    private static String appendQuery(String redirectUri, String... values) {
        StringBuilder result = new StringBuilder(redirectUri);
        result.append(redirectUri.contains("?") ? '&' : '?');
        for (int i = 0; i < values.length; i += 2) {
            if (i > 0) {
                result.append('&');
            }
            result.append(java.net.URLEncoder.encode(values[i], StandardCharsets.UTF_8))
                    .append('=')
                    .append(java.net.URLEncoder.encode(values[i + 1], StandardCharsets.UTF_8));
        }
        return result.toString();
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String randomAlphaNumeric(int length) {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder value = new StringBuilder(length);
        while (value.length() < length) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
    }

    private record AuthorizationCode(String clientId, String codeChallenge, String redirectUri,
                                     String resource, String accountId, Instant expiresAt,
                                     Instant expiredTombstoneUntil) {

        private static AuthorizationCode active(String clientId, String codeChallenge, String redirectUri,
                                                String resource, String accountId, Instant expiresAt) {
            return new AuthorizationCode(clientId, codeChallenge, redirectUri, resource, accountId,
                    expiresAt, null);
        }

        private boolean expiredTombstone() {
            return expiredTombstoneUntil != null;
        }

        private AuthorizationCode toExpiredTombstone() {
            return new AuthorizationCode(null, null, null, null, null, null,
                    expiresAt.plusSeconds(EXPIRED_TOKEN_TOMBSTONE_TTL_SECONDS));
        }
    }

    private record StoredRefreshGrant(String token, RefreshGrant grant) {
    }

    private static final class RefreshGrant {
        private final String clientId;
        private final String resource;
        private final String accountId;
        private final Instant expiresAt;
        private final Instant expiredTombstoneUntil;
        private TokenResult replayResult;
        private Instant replayExpiresAt;

        private RefreshGrant(String clientId, String resource, String accountId, Instant expiresAt) {
            this.clientId = clientId;
            this.resource = resource;
            this.accountId = accountId;
            this.expiresAt = expiresAt;
            this.expiredTombstoneUntil = null;
        }

        private RefreshGrant(Instant expiredTombstoneUntil) {
            this.clientId = null;
            this.resource = null;
            this.accountId = null;
            this.expiresAt = null;
            this.expiredTombstoneUntil = expiredTombstoneUntil;
        }

        private static RefreshGrant expired(Instant retainedUntil) {
            return new RefreshGrant(retainedUntil);
        }

        private String clientId() {
            return clientId;
        }

        private String resource() {
            return resource;
        }

        private String accountId() {
            return accountId;
        }

        private Instant expiresAt() {
            return expiresAt;
        }

        private boolean expiredTombstone() {
            return expiredTombstoneUntil != null;
        }

        private Instant expiredTombstoneUntil() {
            return expiredTombstoneUntil;
        }

        private TokenResult replayResult() {
            return replayResult;
        }

        private Instant replayExpiresAt() {
            return replayExpiresAt;
        }

        private boolean retentionExpiredAt(Instant now) {
            if (expiredTombstone()) {
                return !expiredTombstoneUntil.isAfter(now);
            }
            return !refreshGrantRetentionExpiry(expiresAt, replayExpiresAt).isAfter(now);
        }

        private TokenResult replayResultWithRemainingLifetime(Instant now) {
            return new TokenResult(replayResult.accessToken(), remainingSeconds(now, replayExpiresAt),
                    replayResult.refreshToken(), replayResult.idToken());
        }

        private void cacheReplay(TokenResult result, Instant expiresAt) {
            this.replayResult = result;
            this.replayExpiresAt = expiresAt;
        }
    }

}
