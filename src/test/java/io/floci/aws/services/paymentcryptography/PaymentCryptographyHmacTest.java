package io.floci.aws.services.paymentcryptography;

import io.floci.aws.services.paymentcryptography.crypto.Ciphers;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.SHA224Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.digests.SHA384Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The keyed hash of a message, as RFC 4231 gives it.
 *
 * <p>The reference makes the keyed hash of the four hashes it names, and the values here are
 * the ones the standard's own test cases give, so a hash that agrees with them agrees with
 * every other implementation of the standard.
 */
class PaymentCryptographyHmacTest {

    /** The key the standard's first test case uses: twenty bytes of the same byte. */
    private static final String KEY = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b";

    /** The message the standard's first test case uses. */
    private static final String MESSAGE = "4869205468657265";

    @Test
    void theHashOfSha224IsTheOneTheStandardGives() {
        assertEquals("896fb1128abbdf196832107cd49df33f47b4b1169912ba4f53684b22",
                mac(new SHA224Digest()));
    }

    @Test
    void theHashOfSha256IsTheOneTheStandardGives() {
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
                mac(new SHA256Digest()));
    }

    @Test
    void theHashOfSha384IsTheOneTheStandardGives() {
        assertEquals("afd03944d84895626b0825f4ab46907f15f9dadbe4101ec682aa034c7cebc59c"
                        + "faea9ea9076ede7f4af152e8b2fa9cb6",
                mac(new SHA384Digest()));
    }

    @Test
    void theHashOfSha512IsTheOneTheStandardGives() {
        assertEquals("87aa7cdea5ef619d4ff0b4241a1d6cb02379f4e2ce4ec2787ad0b30545e17cde"
                        + "daa833b7d6b8a702038b274eaea3f4e4be9d914eeb61f1702e696c203a126854",
                mac(new SHA512Digest()));
    }

    @Test
    void theHashOfTheMessageIsAsLongAsTheHash() {
        assertEquals(32, Ciphers.hmac(PaymentCryptographyCrypto.fromHex(KEY),
                PaymentCryptographyCrypto.fromHex(MESSAGE), new SHA256Digest()).length);
        assertEquals(64, Ciphers.hmac(PaymentCryptographyCrypto.fromHex(KEY),
                PaymentCryptographyCrypto.fromHex(MESSAGE), new SHA512Digest()).length);
    }

    /** The keyed hash of the message under the key, in the hash. */
    private static String mac(Digest digest) {
        return PaymentCryptographyCrypto.toHex(Ciphers.hmac(
                PaymentCryptographyCrypto.fromHex(KEY),
                PaymentCryptographyCrypto.fromHex(MESSAGE), digest));
    }
}
