package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SaslPrepTest {

    @ParameterizedTest
    @ValueSource(strings = {"pencil", "", "with space", "tab\there", "ctl\u0007"})
    void anAsciiPasswordIsTakenAsItIs(String password) {
        assertSame(password, SaslPrep.prepare(password));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // C.1.2 spaces become U+0020
            "pass word|pass word",
            "pass　word|pass word",
            // B.1 characters are mapped to nothing
            "pa­ss‍word|password",
            "I­X|IX",
            // NFKC
            "ﬁx|fix",
            "Ⅸ|IX",
            "ª|a",
            "Café|Café",
            "été|été",
    })
    void aNonAsciiPasswordIsMappedAndNormalized(String password, String prepared) {
        assertEquals(prepared, SaslPrep.prepare(password));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // a prohibited character after the mapping: a control, private use, a non-character
            "é\u0007",
            "é",
            "é﷐",
            // left-to-right and right-to-left characters together
            "אa",
            // right-to-left, but not first and last
            "א1",
            // nothing left after the mapping
            "­",
    })
    void anInvalidPreparationFallsBackToThePasswordAsGiven(String password) {
        assertSame(password, SaslPrep.prepare(password));
    }

    @Test
    void aRightToLeftPasswordThatStartsAndEndsRightToLeftIsPrepared() {
        assertEquals("א1ב", SaslPrep.prepare("א1ב"));
    }

    @Test
    void theSaltedPasswordIsDerivedFromThePreparedPassword() throws Exception {
        byte[] salt = {1, 2, 3, 4, 5, 6, 7, 8};
        PBEKeySpec spec = new PBEKeySpec("pass word".toCharArray(), salt, 4096, 256);
        byte[] expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();

        assertArrayEquals(expected, BackendAuthenticator.saltedPassword("pass word", salt, 4096));
    }
}
