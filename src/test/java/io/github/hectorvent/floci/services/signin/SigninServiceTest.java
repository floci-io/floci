package io.github.hectorvent.floci.services.signin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.signin.model.TokenResult;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SigninServiceTest {

    private static final String CLIENT_ID = SigninService.SAME_DEVICE_CLIENT;
    private static final String REDIRECT_URI = "http://127.0.0.1:4567/oauth/callback";
    private static final String ACCOUNT_A = "000000000000";
    private static final String ACCOUNT_B = "111111111111";
    private static final String VERIFIER = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";

    private IamService iam;
    private AtomicReference<String> accountId;
    private MutableClock clock;
    private SigninService service;

    @BeforeEach
    void setUp() {
        iam = mock(IamService.class);
        RegionResolver region = mock(RegionResolver.class);
        accountId = new AtomicReference<>(ACCOUNT_A);
        when(region.getAccountId()).thenAnswer(ignored -> accountId.get());
        clock = new MutableClock();
        service = new SigninService(iam, region, new ObjectMapper(), clock);
    }

    @Test
    void issuesSingleUsePkceCodeAndRegistersAwsSessionCredentials() throws Exception {
        String code = authorizeCode(VERIFIER);

        TokenResult tokens = exchangeCode(code, VERIFIER);

        assertTrue(tokens.accessToken().accessKeyId().startsWith("ASIA"));
        assertEquals(900, tokens.expiresIn());
        verify(iam).registerSessionForAccount(eq(ACCOUNT_A), eq(tokens.accessToken().accessKeyId()),
                eq(tokens.accessToken().secretAccessKey()),
                eq("arn:aws:iam::" + ACCOUNT_A + ":root"), any(), isNull());
        assertThrows(SigninException.class, () -> exchangeCode(code, VERIFIER));
    }

    @Test
    void requiresAwsSha256ChallengeMethod() throws Exception {
        SigninException error = assertThrows(SigninException.class,
                () -> service.authorize(CLIENT_ID, challenge(VERIFIER), "S256", REDIRECT_URI,
                        "code", "openid", "state", null));

        assertEquals("invalid_request", error.error());
        service.authorize(CLIENT_ID, challenge(VERIFIER), "SHA-256", REDIRECT_URI,
                "code", "openid", "state", null);
    }

    @Test
    void validatesAuthorizationRequestBoundariesAndClientRedirects() throws Exception {
        authorize("A".repeat(43), CLIENT_ID, REDIRECT_URI, "s".repeat(128));
        authorize("A".repeat(128), CLIENT_ID, REDIRECT_URI, "state");
        authorize(challenge(VERIFIER), SigninService.CROSS_DEVICE_CLIENT,
                "https://us-east-1.signin.aws.amazon.com/v1/sessions/confirmation", "state");

        assertInvalidAuthorization("A".repeat(42), CLIENT_ID, REDIRECT_URI, "state");
        assertInvalidAuthorization("A".repeat(129), CLIENT_ID, REDIRECT_URI, "state");
        assertInvalidAuthorization("A".repeat(42) + "!", CLIENT_ID, REDIRECT_URI, "state");
        assertInvalidAuthorization(challenge(VERIFIER), CLIENT_ID, REDIRECT_URI, "s".repeat(129));
        assertInvalidAuthorization(challenge(VERIFIER), CLIENT_ID,
                "http://localhost:4567/oauth/callback", "state");
        assertInvalidAuthorization(challenge(VERIFIER), CLIENT_ID,
                "https://127.0.0.1:4567/oauth/callback", "state");
        assertInvalidAuthorization(challenge(VERIFIER), CLIENT_ID,
                "http://127.0.0.1:4567/wrong", "state");
        assertInvalidAuthorization(challenge(VERIFIER), SigninService.CROSS_DEVICE_CLIENT,
                "https://example.com/v1/sessions/confirmation", "state");
        assertInvalidAuthorization(challenge(VERIFIER), SigninService.CROSS_DEVICE_CLIENT,
                REDIRECT_URI, "state");
        assertInvalidAuthorization(challenge(VERIFIER), CLIENT_ID, "x".repeat(2049), "state");
    }

    @Test
    void validatesPkceVerifierBoundaries() throws Exception {
        String minimumVerifier = "A".repeat(43);
        String maximumVerifier = "A".repeat(128);
        exchangeCode(authorizeCode(minimumVerifier), minimumVerifier);
        exchangeCode(authorizeCode(maximumVerifier), maximumVerifier);

        assertInvalidVerifier("A".repeat(42));
        assertInvalidVerifier("A".repeat(129));
        assertInvalidVerifier("A".repeat(42) + "!");
    }

    @Test
    void validatesModeledTokenAndResourceBoundsBeforeConsumingState() throws Exception {
        String code = authorizeCode(VERIFIER);

        assertInvalidRequest(() -> service.exchange(CLIENT_ID, "authorization_code", "x".repeat(513),
                REDIRECT_URI, VERIFIER, null, null));
        assertInvalidRequest(() -> service.exchange(CLIENT_ID, "authorization_code", code,
                "x".repeat(2049), VERIFIER, null, null));
        assertInvalidRequest(() -> service.exchange(CLIENT_ID, "authorization_code", code,
                REDIRECT_URI, VERIFIER, null, ""));
        assertInvalidRequest(() -> service.exchange(CLIENT_ID, "authorization_code", code,
                REDIRECT_URI, VERIFIER, null, "r".repeat(2049)));
        assertInvalidRequest(() -> service.exchange(CLIENT_ID, "refresh_token", null,
                null, null, "r".repeat(2049), null));
        assertInvalidRequest(() -> service.authorize(CLIENT_ID, challengeUnchecked(VERIFIER), "SHA-256",
                REDIRECT_URI, "code", "openid", "state", ""));
        assertInvalidRequest(() -> service.authorize(CLIENT_ID, challengeUnchecked(VERIFIER), "SHA-256",
                REDIRECT_URI, "code", "openid", "state", "r".repeat(2049)));

        TokenResult result = service.exchange(CLIENT_ID, "authorization_code", code,
                REDIRECT_URI, VERIFIER, null, "r".repeat(2048));
        assertEquals(900, result.expiresIn());
    }

    @Test
    void authorizationCodeExpiresAtFiveMinuteBoundary() throws Exception {
        String code = authorizeCode(VERIFIER);
        clock.advance(Duration.ofMinutes(5));

        SigninException error = assertThrows(SigninException.class,
                () -> exchangeCode(code, VERIFIER));

        assertEquals("invalid_grant", error.error());
    }

    @Test
    void rejectsPkceMismatchWithoutRegisteringCredentials() throws Exception {
        String code = authorizeCode(VERIFIER);

        SigninException error = assertThrows(SigninException.class,
                () -> exchangeCode(code, VERIFIER + "wrong"));

        assertEquals("invalid_grant", error.error());
    }

    @Test
    void refreshRotationIsIdempotentUnderConcurrency() throws Exception {
        TokenResult initial = issueInitialTokens();
        int callers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<TokenResult>> futures = java.util.stream.IntStream.range(0, callers)
                    .mapToObj(ignored -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return refresh(initial.refreshToken());
                    }))
                    .toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            TokenResult first = futures.getFirst().get(5, TimeUnit.SECONDS);
            assertNotEquals(initial.refreshToken(), first.refreshToken());
            for (Future<TokenResult> future : futures) {
                TokenResult result = future.get(5, TimeUnit.SECONDS);
                assertEquals(first.accessToken(), result.accessToken());
                assertEquals(first.refreshToken(), result.refreshToken());
                assertEquals(900, result.expiresIn());
            }
            verify(iam, times(2)).registerSessionForAccount(eq(ACCOUNT_A), anyString(), anyString(),
                    anyString(), any(), isNull());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void predecessorReplayReportsRemainingLifetimeAndExpires() throws Exception {
        TokenResult initial = issueInitialTokens();
        TokenResult first = refresh(initial.refreshToken());

        clock.advance(Duration.ofMinutes(10));
        TokenResult replay = refresh(initial.refreshToken());
        assertEquals(first.accessToken(), replay.accessToken());
        assertEquals(first.refreshToken(), replay.refreshToken());
        assertEquals(300, replay.expiresIn());

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertInvalidRefresh(initial.refreshToken());
        TokenResult successor = refresh(first.refreshToken());
        assertNotEquals(first.refreshToken(), successor.refreshToken());
    }

    @Test
    void rotatedRefreshTokenKeepsOriginalAbsoluteExpiry() throws Exception {
        TokenResult initial = issueInitialTokens();
        clock.advance(Duration.ofHours(11).plusMinutes(59));

        TokenResult rotated = refresh(initial.refreshToken());
        clock.advance(Duration.ofMinutes(2));

        assertInvalidRefresh(rotated.refreshToken());
    }

    @Test
    void expiryCleanupWaitsForInFlightRotation() throws Exception {
        TokenResult initial = issueInitialTokens();
        clock.advance(Duration.ofHours(12).minusMillis(2));
        CountDownLatch issuanceStarted = new CountDownLatch(1);
        CountDownLatch allowIssuance = new CountDownLatch(1);
        doAnswer(ignored -> {
            issuanceStarted.countDown();
            assertTrue(allowIssuance.await(5, TimeUnit.SECONDS));
            return null;
        }).when(iam).registerSessionForAccount(anyString(), anyString(), anyString(),
                anyString(), any(), isNull());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<TokenResult> rotation =
                    executor.submit(() -> refresh(initial.refreshToken()));
            assertTrue(issuanceStarted.await(5, TimeUnit.SECONDS));
            Future<?> cleanup = executor.submit(() -> assertInvalidRefresh("unknown-refresh-token"));

            allowIssuance.countDown();
            TokenResult rotated = rotation.get(5, TimeUnit.SECONDS);
            cleanup.get(5, TimeUnit.SECONDS);

            TokenResult replay = refresh(initial.refreshToken());
            assertEquals(rotated.accessToken(), replay.accessToken());
            assertEquals(rotated.refreshToken(), replay.refreshToken());
        } finally {
            allowIssuance.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void authorizationCodesAndRefreshGrantsIssueOnlyForCapturedAccount() throws Exception {
        String code = authorizeCode(VERIFIER);
        accountId.set(ACCOUNT_B);
        TokenResult initial = exchangeCode(code, VERIFIER);

        TokenResult rotated = refresh(initial.refreshToken());
        assertNotEquals(initial.refreshToken(), rotated.refreshToken());
        verify(iam, times(2)).registerSessionForAccount(eq(ACCOUNT_A), anyString(), anyString(),
                eq("arn:aws:iam::" + ACCOUNT_A + ":root"), any(), isNull());
        verify(iam, never()).registerSessionForAccount(eq(ACCOUNT_B), anyString(), anyString(),
                anyString(), any(), isNull());
    }

    private TokenResult issueInitialTokens() throws Exception {
        return exchangeCode(authorizeCode(VERIFIER), VERIFIER);
    }

    private String authorizeCode(String verifier) throws Exception {
        String redirect = service.authorize(CLIENT_ID, challenge(verifier), "SHA-256", REDIRECT_URI,
                "code", "openid", "state", null);
        return query(URI.create(redirect).getRawQuery()).get("code");
    }

    private String authorize(String codeChallenge, String clientId, String redirectUri, String state) {
        return service.authorize(clientId, codeChallenge, "SHA-256", redirectUri,
                "code", "openid", state, null);
    }

    private TokenResult exchangeCode(String code, String verifier) {
        return service.exchange(CLIENT_ID, "authorization_code", code,
                REDIRECT_URI, verifier, null, null);
    }

    private TokenResult refresh(String refreshToken) {
        return service.exchange(CLIENT_ID, "refresh_token", null,
                null, null, refreshToken, null);
    }

    private void assertInvalidAuthorization(String challenge, String clientId, String redirectUri, String state) {
        SigninException error = assertThrows(SigninException.class,
                () -> authorize(challenge, clientId, redirectUri, state));
        assertEquals("invalid_request", error.error());
    }

    private void assertInvalidVerifier(String verifier) throws Exception {
        String code = authorizeCode(verifier);
        SigninException error = assertThrows(SigninException.class,
                () -> exchangeCode(code, verifier));
        assertEquals("invalid_request", error.error());
    }

    private void assertInvalidRefresh(String refreshToken) {
        SigninException error = assertThrows(SigninException.class,
                () -> refresh(refreshToken));
        assertEquals("invalid_grant", error.error());
    }

    private void assertInvalidRequest(org.junit.jupiter.api.function.Executable request) {
        SigninException error = assertThrows(SigninException.class, request);
        assertEquals("invalid_request", error.error());
    }

    private static String challengeUnchecked(String verifier) {
        try {
            return challenge(verifier);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String challenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static Map<String, String> query(String query) {
        return java.util.Arrays.stream(query.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(
                        parts -> URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8)));
    }
}
