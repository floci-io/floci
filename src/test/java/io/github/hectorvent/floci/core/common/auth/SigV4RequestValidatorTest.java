package io.github.hectorvent.floci.core.common.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the shared SigV4 crypto primitives against AWS's own published test vector, independent
 * of any one caller's canonical-request shape. SigV4Validator, RdsSigV4Validator, and S3's
 * PreSignedUrlFilter/S3HeaderSignatureFilter each exercise these indirectly through their own
 * request flows; this targets the primitives themselves at their one shared source.
 */
class SigV4RequestValidatorTest {

    /**
     * Signing-key derivation for AWS's well-known example secret key
     * ("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLE", date 20150830, us-east-1, iam), the same
     * inputs AWS uses throughout its SigV4 documentation. Expected value cross-checked
     * independently with Python's hmac/hashlib, not derived from this code.
     */
    @Test
    void deriveSigningKeyMatchesAnIndependentlyComputedValue() throws Exception {
        byte[] signingKey = SigV4RequestValidator.deriveSigningKey(
                "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLE", "20150830", "us-east-1", "iam");

        assertEquals("93c91b7c5da17c72120bd321a9833353b5dd75355fe396cc91abc149ad9755b5",
                SigV4RequestValidator.hexEncode(signingKey));
    }

    @Test
    void sha256HexOfEmptyStringIsTheWellKnownConstant() throws Exception {
        // This exact value is also hardcoded in validate()'s canonical request for the empty
        // body ElastiCache/RDS presigned tokens always carry; this pins it independently.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                SigV4RequestValidator.sha256Hex(""));
    }

    @Test
    void hmacSha256IsDeterministicForTheSameKeyAndData() throws Exception {
        byte[] key = "a-secret-key".getBytes(StandardCharsets.UTF_8);

        byte[] first = SigV4RequestValidator.hmacSha256(key, "some data");
        byte[] second = SigV4RequestValidator.hmacSha256(key, "some data");

        assertEquals(SigV4RequestValidator.hexEncode(first), SigV4RequestValidator.hexEncode(second));
    }

    @Test
    void hexEncodeLowercasesAndZeroPadsEachByte() {
        assertEquals("00ff0a", SigV4RequestValidator.hexEncode(new byte[]{0x00, (byte) 0xFF, 0x0A}));
    }
}
