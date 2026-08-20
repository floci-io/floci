package io.github.hectorvent.floci.services.signin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.iam.IamService;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SigninServiceTest {

    private static final String CLIENT_ID = SigninService.SAME_DEVICE_CLIENT;
    private static final String REDIRECT_URI = "http://127.0.0.1:4567/oauth/callback";

    @Test
    void issuesSingleUsePkceCodeAndRegistersAwsSessionCredentials() throws Exception {
        IamService iam = mock(IamService.class);
        RegionResolver region = mock(RegionResolver.class);
        when(region.getAccountId()).thenReturn("000000000000");
        SigninService service = new SigninService(iam, region, new ObjectMapper());
        String verifier = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";

        String redirect = service.authorize(CLIENT_ID, challenge(verifier), "S256", REDIRECT_URI,
                "code", "openid", "state", null);
        String code = query(URI.create(redirect).getRawQuery()).get("code");

        SigninService.TokenResult tokens = service.exchange(CLIENT_ID, "authorization_code", code,
                REDIRECT_URI, verifier, null, null);

        assertTrue(tokens.accessToken().get("accessKeyId").startsWith("ASIA"));
        assertEquals(900, tokens.expiresIn());
        verify(iam).registerSession(eq(tokens.accessToken().get("accessKeyId")),
                eq(tokens.accessToken().get("secretAccessKey")),
                eq("arn:aws:iam::000000000000:root"), any(), isNull(), eq("000000000000"));
        assertThrows(SigninService.SigninException.class, () -> service.exchange(CLIENT_ID,
                "authorization_code", code, REDIRECT_URI, verifier, null, null));
    }

    @Test
    void rejectsPkceMismatchWithoutRegisteringCredentials() throws Exception {
        IamService iam = mock(IamService.class);
        RegionResolver region = mock(RegionResolver.class);
        when(region.getAccountId()).thenReturn("000000000000");
        SigninService service = new SigninService(iam, region, new ObjectMapper());
        String verifier = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
        String redirect = service.authorize(CLIENT_ID, challenge(verifier), "S256", REDIRECT_URI,
                "code", "openid", "state", null);
        String code = query(URI.create(redirect).getRawQuery()).get("code");

        SigninService.SigninException error = assertThrows(SigninService.SigninException.class,
                () -> service.exchange(CLIENT_ID, "authorization_code", code, REDIRECT_URI,
                        verifier + "wrong", null, null));

        assertEquals("invalid_grant", error.error());
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
