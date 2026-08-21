package io.github.hectorvent.floci.services.signin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.SessionCreds;
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
 * represents the local emulator account and immediately issues a one-time PKCE authorization
 * code. The token endpoint still follows the AWS flow: codes are single-use, PKCE is verified,
 * refresh tokens expire, and returned credentials are registered with IAM for request signing.
 */
@ApplicationScoped
public class SigninService {

    static final String SAME_DEVICE_CLIENT = "arn:aws:signin:::devtools/same-device";
    static final String CROSS_DEVICE_CLIENT = "arn:aws:signin:::devtools/cross-device";
    static final int ACCESS_TOKEN_TTL_SECONDS = 900;
    private static final long AUTHORIZATION_CODE_TTL_SECONDS = 300;
    private static final long REFRESH_TOKEN_TTL_SECONDS = 12 * 60 * 60;
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

    public String authorize(String clientId, String codeChallenge, String codeChallengeMethod,
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
        authorizationCodes.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        String code = randomToken(32);
        authorizationCodes.put(code, new AuthorizationCode(
                clientId, codeChallenge, redirectUri, resource, accountId,
                now.plusSeconds(AUTHORIZATION_CODE_TTL_SECONDS)));
        return appendQuery(redirectUri, "code", code, "state", state);
    }

    public TokenResult exchange(String clientId, String grantType, String code, String redirectUri,
                                String codeVerifier, String refreshToken, String resource) {
        validateClient(clientId);
        validateOptionalResource(resource);
        if ("authorization_code".equals(grantType)) {
            return exchangeCode(clientId, code, redirectUri, codeVerifier, resource);
        }
        if ("refresh_token".equals(grantType)) {
            return refresh(clientId, refreshToken);
        }
        throw new SigninException("invalid_request", "grant_type must be authorization_code or refresh_token");
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
        AuthorizationCode authorization = authorizationCodes.remove(code);
        if (authorization == null || !authorization.expiresAt().isAfter(now)
                || !clientId.equals(authorization.clientId())
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
        cleanupExpiredRefreshGrants(now);
        RefreshGrant grant = refreshGrants.get(refreshToken);
        if (grant == null || !clientId.equals(grant.clientId())) {
            throw new SigninException("invalid_grant", "The refresh token is invalid or expired");
        }
        synchronized (grant) {
            if (refreshGrants.get(refreshToken) != grant) {
                throw new SigninException("invalid_grant", "The refresh token is invalid or expired");
            }
            if (grant.replayResult() != null) {
                if (grant.replayExpiresAt().isAfter(now)) {
                    return grant.replayResultWithRemainingLifetime(now);
                }
                refreshGrants.remove(refreshToken, grant);
                throw new SigninException("invalid_grant", "The refresh token is invalid or expired");
            }
            if (!grant.expiresAt().isAfter(now)) {
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
        cleanupExpiredRefreshGrants(now);
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

    private void cleanupExpiredRefreshGrants(Instant now) {
        refreshGrants.forEach((token, grant) -> {
            synchronized (grant) {
                if (grant.retentionExpiredAt(now)) {
                    refreshGrants.remove(token, grant);
                }
            }
        });
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
                                     String resource, String accountId, Instant expiresAt) {
    }

    private record StoredRefreshGrant(String token, RefreshGrant grant) {
    }

    private static final class RefreshGrant {
        private final String clientId;
        private final String resource;
        private final String accountId;
        private final Instant expiresAt;
        private TokenResult replayResult;
        private Instant replayExpiresAt;

        private RefreshGrant(String clientId, String resource, String accountId, Instant expiresAt) {
            this.clientId = clientId;
            this.resource = resource;
            this.accountId = accountId;
            this.expiresAt = expiresAt;
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

        private TokenResult replayResult() {
            return replayResult;
        }

        private Instant replayExpiresAt() {
            return replayExpiresAt;
        }

        private boolean retentionExpiredAt(Instant now) {
            Instant replayExpiry = replayExpiresAt;
            Instant retentionExpiry = replayExpiry == null ? expiresAt : replayExpiry;
            return !retentionExpiry.isAfter(now);
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
