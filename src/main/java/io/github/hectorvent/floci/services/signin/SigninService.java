package io.github.hectorvent.floci.services.signin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    private final IamService iamService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final SecureRandom random;
    private final ConcurrentHashMap<String, AuthorizationCode> authorizationCodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RefreshGrant> refreshGrants = new ConcurrentHashMap<>();

    @Inject
    public SigninService(IamService iamService, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.iamService = iamService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.random = new SecureRandom();
    }

    public String authorize(String clientId, String codeChallenge, String codeChallengeMethod,
                            String redirectUri, String responseType, String scope, String state,
                            String resource) {
        validateClient(clientId);
        if (!"S256".equals(codeChallengeMethod) || isBlank(codeChallenge)
                || codeChallenge.length() < 43 || codeChallenge.length() > 128) {
            throw new SigninException("invalid_request", "code_challenge must use SHA-256 and be 43-128 characters");
        }
        if (!"code".equals(responseType) || !"openid".equals(scope)) {
            throw new SigninException("invalid_request", "response_type=code and scope=openid are required");
        }
        if (isBlank(redirectUri) || isBlank(state)) {
            throw new SigninException("invalid_request", "redirect_uri and state are required");
        }
        String code = randomToken(32);
        authorizationCodes.put(code, new AuthorizationCode(
                clientId, codeChallenge, redirectUri, resource,
                Instant.now().plusSeconds(AUTHORIZATION_CODE_TTL_SECONDS)));
        return appendQuery(redirectUri, "code", code, "state", state);
    }

    public TokenResult exchange(String clientId, String grantType, String code, String redirectUri,
                                String codeVerifier, String refreshToken, String resource) {
        validateClient(clientId);
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
        if (isBlank(code) || isBlank(redirectUri) || isBlank(codeVerifier)) {
            throw new SigninException("invalid_request", "code, redirect_uri, and code_verifier are required");
        }
        AuthorizationCode authorization = authorizationCodes.remove(code);
        if (authorization == null || authorization.expiresAt().isBefore(Instant.now())
                || !clientId.equals(authorization.clientId())
                || !redirectUri.equals(authorization.redirectUri())
                || !matchesPkce(authorization.codeChallenge(), codeVerifier)) {
            throw new SigninException("invalid_grant", "The authorization code is invalid or expired");
        }
        return issueTokens(clientId, resource != null ? resource : authorization.resource(), true);
    }

    private TokenResult refresh(String clientId, String refreshToken) {
        if (isBlank(refreshToken)) {
            throw new SigninException("invalid_request", "refresh_token is required");
        }
        RefreshGrant grant = refreshGrants.get(refreshToken);
        if (grant == null || grant.expiresAt().isBefore(Instant.now()) || !clientId.equals(grant.clientId())) {
            throw new SigninException("invalid_grant", "The refresh token is invalid or expired");
        }
        synchronized (grant) {
            Instant now = Instant.now();
            if (grant.accessToken() != null && grant.accessTokenExpiresAt().isAfter(now)) {
                return grant.accessToken();
            }
            TokenResult result = issueAccessToken(clientId, grant.resource(), false, refreshToken);
            grant.cacheAccessToken(result, now.plusSeconds(ACCESS_TOKEN_TTL_SECONDS));
            return result;
        }
    }

    private TokenResult issueTokens(String clientId, String resource, boolean includeIdToken) {
        String refreshToken = randomToken(48);
        TokenResult result = issueAccessToken(clientId, resource, includeIdToken, refreshToken);
        refreshGrants.put(refreshToken, new RefreshGrant(
                clientId, resource, Instant.now().plusSeconds(REFRESH_TOKEN_TTL_SECONDS)));
        return result;
    }

    private TokenResult issueAccessToken(String clientId, String resource, boolean includeIdToken,
                                         String refreshToken) {
        String accessKeyId = "ASIA" + randomAlphaNumeric(16);
        String secretAccessKey = randomAlphaNumeric(40);
        String sessionToken = randomAlphaNumeric(200);
        Instant expiration = Instant.now().plusSeconds(ACCESS_TOKEN_TTL_SECONDS);
        String accountId = regionResolver.getAccountId();
        String principalArn = "arn:aws:iam::" + accountId + ":root";
        iamService.registerSession(accessKeyId, secretAccessKey, principalArn, expiration, null, accountId);

        Map<String, String> accessToken = Map.of(
                "accessKeyId", accessKeyId,
                "secretAccessKey", secretAccessKey,
                "sessionToken", sessionToken);
        String idToken = includeIdToken ? idToken(principalArn, accountId, clientId) : null;
        return new TokenResult(accessToken, ACCESS_TOKEN_TTL_SECONDS, refreshToken, idToken);
    }

    private String idToken(String principalArn, String accountId, String clientId) {
        try {
            long now = Instant.now().getEpochSecond();
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record AuthorizationCode(String clientId, String codeChallenge, String redirectUri,
                                     String resource, Instant expiresAt) {
    }

    private static final class RefreshGrant {
        private final String clientId;
        private final String resource;
        private final Instant expiresAt;
        private TokenResult accessToken;
        private Instant accessTokenExpiresAt;

        private RefreshGrant(String clientId, String resource, Instant expiresAt) {
            this.clientId = clientId;
            this.resource = resource;
            this.expiresAt = expiresAt;
        }

        private String clientId() {
            return clientId;
        }

        private String resource() {
            return resource;
        }

        private Instant expiresAt() {
            return expiresAt;
        }

        private TokenResult accessToken() {
            return accessToken;
        }

        private Instant accessTokenExpiresAt() {
            return accessTokenExpiresAt;
        }

        private void cacheAccessToken(TokenResult token, Instant tokenExpiresAt) {
            this.accessToken = token;
            this.accessTokenExpiresAt = tokenExpiresAt;
        }
    }

    public record TokenResult(Map<String, String> accessToken, int expiresIn,
                              String refreshToken, String idToken) {
    }

    public static final class SigninException extends RuntimeException {
        private final String error;

        public SigninException(String error, String message) {
            super(message);
            this.error = error;
        }

        public String error() {
            return error;
        }
    }
}
