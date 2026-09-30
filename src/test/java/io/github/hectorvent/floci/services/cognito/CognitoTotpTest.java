package io.github.hectorvent.floci.services.cognito;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitoTotpTest {
    @Test
    void usesRfc6238Sha1CounterAndSixDigits() {
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        assertEquals("287082", CognitoTotp.code(secret, Instant.ofEpochSecond(59)));
        assertTrue(CognitoTotp.validCode(secret, "287082", Instant.ofEpochSecond(59)));
        assertTrue(CognitoTotp.validCode(secret, "287082", Instant.ofEpochSecond(89)));
        assertFalse(CognitoTotp.validCode(secret, "287082", Instant.ofEpochSecond(90)));
        assertFalse(CognitoTotp.validCode(secret, "28782", Instant.ofEpochSecond(59)));
    }

    @Test
    void newSecretIsBase32Encoded() {
        String first = CognitoTotp.newSecret();
        String second = CognitoTotp.newSecret();
        assertTrue(first.matches("[A-Z2-7]{32}"));
        assertFalse(first.equals(second));
        assertTrue(CognitoTotp.validCode(first, CognitoTotp.code(first, Instant.EPOCH), Instant.EPOCH));
    }
}
