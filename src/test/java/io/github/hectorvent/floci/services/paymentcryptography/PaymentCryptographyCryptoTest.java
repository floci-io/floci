package io.github.hectorvent.floci.services.paymentcryptography;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        byte[] plaintext = PaymentCryptographyCrypto.fromHex(
                "54686520717569636b2062726f776e20666f78206a756d7073206f76657220746865206c617a7920646f67");

        byte[] blob = PaymentCryptographyCrypto.encryptCbc(key, plaintext, new FixedRandom(
                PaymentCryptographyCrypto.fromHex("101112131415161718191a1b1c1d1e1f")));

        assertEquals("101112131415161718191a1b1c1d1e1fb746e4f1bce2352d20440b8a2e0eb8fd6"
                        + "8c1fcd0d00fcac779ecaadacde7e51a41da205eac66749510362db1c9f3e6d5",
                PaymentCryptographyCrypto.toHex(blob));
        assertArrayEquals(plaintext, PaymentCryptographyCrypto.decryptCbc(key, blob));
    }

    @Test
    void aCiphertextOfAnotherKeyDoesNotOpen() {
        byte[] key = PaymentCryptographyCrypto.fromHex(
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        byte[] other = PaymentCryptographyCrypto.fromHex(
                "1f1e1d1c1b1a19181716151413121110f0e0d0c0b0a09080706050403020100");
        // The vector is fixed, so the answer is fixed: with a random one the padding of the
        // wrong key's plaintext can happen to be valid, and the test would fail now and then
        // for no reason.
        byte[] blob = PaymentCryptographyCrypto.encryptCbc(key, "hello".getBytes(),
                new FixedRandom(PaymentCryptographyCrypto.fromHex("101112131415161718191a1b1c1d1e1f")));

        // The right key opens it, so the refusal below is about the key and not the blob.
        assertArrayEquals("hello".getBytes(), PaymentCryptographyCrypto.decryptCbc(key, blob));
        assertThrows(IllegalArgumentException.class,
                () -> PaymentCryptographyCrypto.decryptCbc(other, blob));
    }

    @Test
    void theEncryptionOfAnEmptyPlaintextIsTheVectorAndThePadding() {
        byte[] key = PaymentCryptographyCrypto.fromHex(
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        byte[] blob = PaymentCryptographyCrypto.encryptCbc(key, new byte[0], new FixedRandom(
                PaymentCryptographyCrypto.fromHex("101112131415161718191a1b1c1d1e1f")));

        assertEquals(32, blob.length);
        assertArrayEquals(new byte[0], PaymentCryptographyCrypto.decryptCbc(key, blob));
    }

    @Test
    void theMaterialIsThirtyTwoBytesAndTheCheckValueIsSixCharacters() {
        String material = PaymentCryptographyCrypto.randomAes256Hex(new SecureRandom());

        assertEquals(64, material.length());
        assertEquals(6, PaymentCryptographyCrypto.checkValue(material).length());
        assertNotNull(PaymentCryptographyCrypto.checkValue(material));
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
