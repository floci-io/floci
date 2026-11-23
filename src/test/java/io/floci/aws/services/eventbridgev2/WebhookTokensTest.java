package io.floci.aws.services.eventbridgev2;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void tokenIsValidOnlyForItsHostId() {
        String hostId = WebhookTokens.newHostId(random);
        String token = WebhookTokens.newToken(hostId, random);
        assertTrue(WebhookTokens.isValidToken(hostId, token));
        assertFalse(WebhookTokens.isValidToken(WebhookTokens.newHostId(random), token));
    }

    @Test
    void aTamperedOrMalformedTokenIsInvalid() {
        String hostId = WebhookTokens.newHostId(random);
        String token = WebhookTokens.newToken(hostId, random);
        char first = token.charAt(2);
        String tampered = token.substring(0, 2) + (first == 'A' ? 'B' : 'A') + token.substring(3);
        assertFalse(WebhookTokens.isValidToken(hostId, tampered));
        assertFalse(WebhookTokens.isValidToken(hostId, "k2" + token.substring(2)));
        assertFalse(WebhookTokens.isValidToken(hostId, token.substring(0, 20)));
        assertFalse(WebhookTokens.isValidToken(hostId, "k1!!!"));
        assertFalse(WebhookTokens.isValidToken(hostId, null));
    }
}
