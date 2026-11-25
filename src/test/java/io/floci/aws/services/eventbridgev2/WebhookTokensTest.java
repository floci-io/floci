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
    void aTokenIsValidOnlyForTheHostItWasBuiltFor() {
        String host = WebhookTokens.newHostId(random);
        String token = WebhookTokens.newToken(host, random);

        assertTrue(WebhookTokens.isValid(host, token));
        assertFalse(WebhookTokens.isValid(WebhookTokens.newHostId(random), token));
    }

    @Test
    void aTokenThatWasChangedOrIsMalformedIsNotValid() {
        String host = WebhookTokens.newHostId(random);
        String token = WebhookTokens.newToken(host, random);
        String altered = token.substring(0, 10) + (token.charAt(10) == 'A' ? 'B' : 'A') + token.substring(11);

        assertFalse(WebhookTokens.isValid(host, altered));
        assertFalse(WebhookTokens.isValid(host, token.substring(0, token.length() - 1)));
        assertFalse(WebhookTokens.isValid(host, "k2" + token.substring(2)));
        assertFalse(WebhookTokens.isValid(host, "k1" + "!".repeat(64)));
        assertFalse(WebhookTokens.isValid(host, "k1" + "A".repeat(64)));
        assertFalse(WebhookTokens.isValid(host, ""));
    }
}
