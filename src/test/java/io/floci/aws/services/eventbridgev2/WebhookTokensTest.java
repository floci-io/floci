package io.floci.aws.services.eventbridgev2;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookTokensTest {
    private final SecureRandom random = new SecureRandom();

    @Test
    void hostIdIsAnAFollowedBy37LowercaseBase32Characters() {
        assertTrue(WebhookTokens.newHostId(random).matches("a[a-z2-7]{37}"));
    }

    @Test
    void tokenIsK1AndFortyEightBytesOfUnpaddedBase64Url() {
        String token = WebhookTokens.newToken(WebhookTokens.newHostId(random), random);
        assertTrue(token.matches("k1[A-Za-z0-9_-]{64}"), token);
        assertEquals(48, Base64.getUrlDecoder().decode(token.substring(2)).length);
    }
}
