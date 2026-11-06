package io.floci.aws.services.paymentcryptography;

import io.floci.aws.services.paymentcryptography.crypto.Ciphers;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The cryptography of the key record and of the data plane, against values another
 * implementation computed.
 *
 * <p>The CMAC vectors are the ones RFC 4493 uses, and the key wrap is the one RFC 3394
 * publishes. The CBC vector was computed with the same padding the service uses, so a
 * difference in it is a difference in the encryption rather than in the padding.
 */
class PaymentCryptographyCryptoTest {

    /** The key of the RFC 4493 examples. */
    private static final String RFC4493_KEY = "2b7e151628aed2a6abf7158809cf4f3c";

    private static final String M1 = "6bc1bee22e409f96e93d7e117393172a";
    private static final String M2 = "ae2d8a571e03ac9c9eb76fac45af8e51";
    private static final String M3 = "30c81c46a35ce411e5fbc1191a0a52ef";
    private static final String M4 = "f69f2445df4f9b17ad2b417be66c3710";

    @Test
    void theCmacIsTheOneOfTheStandard() {
        assertEquals("bb1d6929e95937287fa37d129b756746", mac(""));
        assertEquals("070a16b46b4d4144f79bdd9dd04a287c", mac(M1));
        assertEquals("ce0cbf1738f4df6428b1d93bf12081c9", mac(M1 + M2));
        assertEquals("dfa66747de9ae63030ca32611497c827", mac(M1 + M2 + M3.substring(0, 16)));
        assertEquals("c47c4d9d64588f67fb9de6fe745d7fbf", mac(M1 + M2 + M3));
        assertEquals("51f0bebf7e3b9d92fc49741779363cfe", mac(M1 + M2 + M3 + M4));
    }

    @Test
    void theCmacOfAPartialBlockUsesTheSecondSubkey() {
        // The last block is padded here, so the second subkey is the one that is used.
        assertEquals("41ccdefead58e63835d7582e9f2e5b62", mac(M1 + M2 + M3 + M4.substring(0, 26)));
        assertEquals("71c5a229ba6db8c471075ac5b9c64ffe", mac(M1.substring(0, 8)));
    }

    @Test
    void theCheckValueIsTheLeftmostThreeBytesOfTheCmacOfZeroes() {
        // The check value is the CMAC of a whole block of zeroes, which is not the CMAC of
        // the empty message: a whole block is exclusive-ored with the first subkey.
        byte[] mac = PaymentCryptographyCrypto.cmac(
                PaymentCryptographyCrypto.fromHex(RFC4493_KEY), new byte[16]);
        assertEquals("7ad386c3760fb3498361a1cb5563bd70", PaymentCryptographyCrypto.toHex(mac));
        assertEquals("7AD386", PaymentCryptographyCrypto.checkValue(RFC4493_KEY));
    }

    @Test
    void theKeyWrapIsTheOneOfTheStandard() {
        byte[] kek = PaymentCryptographyCrypto.fromHex("000102030405060708090a0b0c0d0e0f");
        byte[] keyData = PaymentCryptographyCrypto.fromHex("00112233445566778899aabbccddeeff");

        byte[] wrapped = PaymentCryptographyCrypto.keyWrap(kek, keyData);

        assertEquals("1fa68b0a8112b447aef34bd8fb5a7b829d3e862371d2cfe5",
                PaymentCryptographyCrypto.toHex(wrapped));
        assertArrayEquals(keyData, PaymentCryptographyCrypto.keyUnwrap(kek, wrapped));
    }

    @Test
    void aKeyWrapUnderTheWrongKeyDoesNotOpen() {
        byte[] kek = PaymentCryptographyCrypto.fromHex("000102030405060708090a0b0c0d0e0f");
        byte[] other = PaymentCryptographyCrypto.fromHex("0f0e0d0c0b0a09080706050403020100");
        byte[] keyData = PaymentCryptographyCrypto.fromHex("00112233445566778899aabbccddeeff");

        byte[] wrapped = PaymentCryptographyCrypto.keyWrap(kek, keyData);

        assertNull(PaymentCryptographyCrypto.keyUnwrap(other, wrapped));
    }

    @Test
    void theEncryptionIsTheOneAnotherImplementationComputed() {
        byte[] key = PaymentCryptographyCrypto.fromHex(
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        // The cipher pads no data, so the plaintext is a whole number of blocks.
        byte[] plaintext = "The quick brown fox jumps over!!".getBytes(StandardCharsets.UTF_8);

        byte[] vector = PaymentCryptographyCrypto.fromHex("101112131415161718191a1b1c1d1e1f");
        byte[] blob = PaymentCryptographyCrypto.encryptCbc(key, plaintext, vector);

        // The ciphertext is the encrypted bytes alone: the vector travels beside it, in the
        // request's attributes. The value is the one BouncyCastle computes for the same key,
        // vector and plaintext.
        assertEquals("b746e4f1bce2352d20440b8a2e0eb8fdf67504d7488d1792588f220540ed23b4",
                PaymentCryptographyCrypto.toHex(blob));
        assertArrayEquals(plaintext, PaymentCryptographyCrypto.decryptCbc(key, blob, vector));
    }

    @Test
    void aCiphertextOfAnotherKeyDoesNotOpen() {
        byte[] key = PaymentCryptographyCrypto.fromHex(
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        byte[] other = PaymentCryptographyCrypto.fromHex(
                "1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100");
        // The vector is fixed, so the answer is fixed.
        byte[] vector = PaymentCryptographyCrypto.fromHex("101112131415161718191a1b1c1d1e1f");
        byte[] plaintext = "a whole block ok".getBytes(StandardCharsets.UTF_8);
        byte[] blob = PaymentCryptographyCrypto.encryptCbc(key, plaintext, vector);

        // The right key opens it.
        assertArrayEquals(plaintext, PaymentCryptographyCrypto.decryptCbc(key, blob, vector));
        // The other key does not: the cipher pads no data, so it reads as the bytes the
        // ciphertext holds and nothing says they are the plaintext.
        assertFalse(Arrays.equals(plaintext,
                PaymentCryptographyCrypto.decryptCbc(other, blob, vector)));
    }

    @Test
    void theTripleDesCipherIsTheOneAnotherImplementationComputed() {
        byte[] key = PaymentCryptographyCrypto.fromHex(
                "000102030405060708090a0b0c0d0e0f0001020304050607");
        byte[] vector = PaymentCryptographyCrypto.fromHex("0102030405060708");
        byte[] plaintext = PaymentCryptographyCrypto.fromHex("00112233445566778899aabbccddeeff");

        byte[] blob = PaymentCryptographyCrypto.tdesCbcEncrypt(key, plaintext, vector);

        // The value is the one another implementation computes for the same key, vector and
        // plaintext.
        assertEquals("2ccf87b33cad11b72eb3ead4b7385053", PaymentCryptographyCrypto.toHex(blob));
        assertArrayEquals(plaintext, PaymentCryptographyCrypto.tdesCbcDecrypt(key, blob, vector));
    }

    @Test
    void theIso9797MacsAreTheOnesFipsPub113Publishes() {
        // The ISO 9797-1 worked example, with equal key halves: both algorithms collapse to
        // the single-DES CBC-MAC, so one published value pins both chains and the padding.
        byte[] key = PaymentCryptographyCrypto.fromHex("0123456789ABCDEF0123456789ABCDEF");
        byte[] message = "7654321 Now is the time for ".getBytes(StandardCharsets.US_ASCII);

        assertEquals("f1d30f6849312ca4",
                PaymentCryptographyCrypto.toHex(Ciphers.iso9797Alg1(key, message)));
        assertEquals("f1d30f6849312ca4",
                PaymentCryptographyCrypto.toHex(Ciphers.iso9797Alg3(key, message)));
    }

    @Test
    void theIso9797MacOfAMessageThatFillsItsBlocksIsTheOneFipsPub113Publishes() {
        // The published example pads its text with zeroes to a whole block, and that padded
        // text is the example's own fourth block. A text that already fills its blocks is
        // padded with nothing, so the two give one MAC.
        byte[] key = PaymentCryptographyCrypto.fromHex("0123456789ABCDEF0123456789ABCDEF");
        byte[] padded = PaymentCryptographyCrypto.fromHex(
                "37363534333231204e6f77206973207468652074696d6520666f722000000000");

        assertEquals("f1d30f6849312ca4",
                PaymentCryptographyCrypto.toHex(Ciphers.iso9797Alg1(key, padded)));
        assertEquals("f1d30f6849312ca4",
                PaymentCryptographyCrypto.toHex(Ciphers.iso9797Alg3(key, padded)));
    }

    @Test
    void theIso9797MacsAreTheOnesAnotherImplementationComputed() {
        // The two-key regression locks, which are the distinct-halves path the published
        // example does not reach.
        byte[] key = PaymentCryptographyCrypto.fromHex("0123456789ABCDEFFEDCBA9876543210");
        byte[] message = "7654321 Now is the time for ".getBytes(StandardCharsets.US_ASCII);

        assertEquals("e5e7a413c3e3f4b5",
                PaymentCryptographyCrypto.toHex(Ciphers.iso9797Alg1(key, message)));
        assertEquals("ae4b45b1b527642f",
                PaymentCryptographyCrypto.toHex(Ciphers.iso9797Alg3(key, message)));
        // Over an empty message the algorithm 3 MAC is the Triple DES of a zero block, whose
        // leftmost three bytes are the independently documented X9.24 check value of the key.
        assertEquals("08d7b4fb629d0885",
                PaymentCryptographyCrypto.toHex(Ciphers.iso9797Alg3(key, new byte[0])));
    }

    @Test
    void aTwoKeyTripleDesKeyIsUsedUnderTheThreeKeysItHolds() {
        byte[] two = PaymentCryptographyCrypto.fromHex("000102030405060708090a0b0c0d0e0f");
        byte[] three = PaymentCryptographyCrypto.fromHex(
                "000102030405060708090a0b0c0d0e0f0001020304050607");
        byte[] vector = PaymentCryptographyCrypto.fromHex("0102030405060708");
        byte[] plaintext = PaymentCryptographyCrypto.fromHex("00112233445566778899aabbccddeeff");

        assertArrayEquals(PaymentCryptographyCrypto.tdesCbcEncrypt(three, plaintext, vector),
                PaymentCryptographyCrypto.tdesCbcEncrypt(two, plaintext, vector));
    }

    @Test
    void theCipherPadsNoData() {
        byte[] key = PaymentCryptographyCrypto.fromHex(
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        byte[] vector = PaymentCryptographyCrypto.fromHex("101112131415161718191a1b1c1d1e1f");

        // A ciphertext is as long as the plaintext it holds, and the vector is not in it.
        byte[] plaintext = "a whole block ok".getBytes(StandardCharsets.UTF_8);
        byte[] blob = PaymentCryptographyCrypto.encryptCbc(key, plaintext, vector);
        assertEquals(plaintext.length, blob.length);
        assertArrayEquals(plaintext, PaymentCryptographyCrypto.decryptCbc(key, blob, vector));

        // An empty plaintext is an empty ciphertext: there is no padding to add.
        assertEquals(0, PaymentCryptographyCrypto.encryptCbc(key, new byte[0], vector).length);
    }

    @Test
    void theMaterialIsThirtyTwoBytesAndTheCheckValueIsSixCharacters() {
        String material = PaymentCryptographyCrypto.randomAes256Hex(new SecureRandom());

        assertEquals(64, material.length());
        assertEquals(6, PaymentCryptographyCrypto.checkValue(material).length());
        assertNotNull(PaymentCryptographyCrypto.checkValue(material));
    }

    @Test
    void theTripleDesCheckValueIsTheLeftmostThreeBytesOfTheEncryptedZeroes() {
        // The check value of a Triple DES key is the ANSI X9.24 one: the leftmost three
        // bytes of encrypting eight zero bytes with the key. The two-key form is widened to
        // K1||K2||K1 first, which is what the three-key cipher takes.
        assertEquals("08D7B4", PaymentCryptographyCrypto.checkValue(
                "0123456789ABCDEFFEDCBA9876543210", PaymentCryptographyKeyAlgorithm.TDES_2KEY));
        assertEquals("3FD539", PaymentCryptographyCrypto.checkValue(
                "0123456789ABCDEFFEDCBA987654321089ABCDEF01234567",
                PaymentCryptographyKeyAlgorithm.TDES_3KEY));
    }

    private static String mac(String messageHex) {
        return PaymentCryptographyCrypto.toHex(PaymentCryptographyCrypto.cmac(
                PaymentCryptographyCrypto.fromHex(RFC4493_KEY),
                PaymentCryptographyCrypto.fromHex(messageHex)));
    }

    /** A random that answers the same bytes every time, so a vector is a vector. */
    private static final class FixedRandom extends SecureRandom {
        private final byte[] bytes;
        private int used;

        FixedRandom(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public void nextBytes(byte[] target) {
            System.arraycopy(bytes, 0, target, 0, Math.min(bytes.length, target.length));
            used += target.length;
        }
    }
}
