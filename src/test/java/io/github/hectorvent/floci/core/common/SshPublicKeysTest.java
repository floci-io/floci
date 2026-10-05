package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshPublicKeysTest {

    private static final RSAPublicKey KEY = rsaKeyQuietly();

    private static RSAPublicKey rsaKeyQuietly() {
        try {
            return rsaKey();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA is unavailable", e);
        }
    }

    private static RSAPublicKey rsaKey() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return (RSAPublicKey) generator.generateKeyPair().getPublic();
    }

    @Test
    void aKeyRoundTripsThroughTheOpenSshWireForm() throws Exception {
        RSAPublicKey key = rsaKey();
        String line = SshPublicKeys.toOpenSsh(key);
        assertTrue(line.startsWith("ssh-rsa "));

        RSAPublicKey parsed = SshPublicKeys.rsaKeyOf(SshPublicKeys.decodeBlob(line));
        assertEquals(key.getModulus(), parsed.getModulus());
        assertEquals(key.getPublicExponent(), parsed.getPublicExponent());
    }

    @Test
    void theTypeIsTheFirstFieldOfTheBlob() throws Exception {
        byte[] blob = SshPublicKeys.decodeBlob(SshPublicKeys.toOpenSsh(rsaKey()));
        assertEquals("ssh-rsa", new String(SshPublicKeys.reader(blob).readField()));
    }

    /** An authorized_keys line usually carries a comment, which is not part of the blob. */
    @Test
    void aTrailingCommentDoesNotDisturbTheBlob() throws Exception {
        RSAPublicKey key = rsaKey();
        String line = SshPublicKeys.toOpenSsh(key);
        RSAPublicKey parsed = SshPublicKeys.rsaKeyOf(
                SshPublicKeys.decodeBlob(line + " someone@example.com"));
        assertEquals(key.getModulus(), parsed.getModulus());
    }

    @Test
    void whatIsNotAnOpenSshLineDecodesToNothing() {
        assertNull(SshPublicKeys.decodeBlob(null));
        assertNull(SshPublicKeys.decodeBlob(""));
        assertNull(SshPublicKeys.decodeBlob("not-a-key"));
        assertNull(SshPublicKeys.decodeBlob("ssh-rsa !!!not-base64!!!"));
    }

    @Test
    void malformedMaterialIsRejectedRatherThanGuessedAt() {
        assertThrows(SshPublicKeyException.class, () -> SshPublicKeys.reader(null));
        assertThrows(SshPublicKeyException.class, () -> SshPublicKeys.reader(new byte[0]).readField());
        // A type, but no exponent and no modulus after it.
        ByteBuffer typeOnly = ByteBuffer.allocate(Integer.BYTES + 7)
                .putInt(7).put("ssh-rsa".getBytes());
        assertThrows(SshPublicKeyException.class, () -> SshPublicKeys.rsaKeyOf(typeOnly.array()));
    }

    /**
     * The blob arrives in a caller's request, so a declared field length is attacker-controlled.
     * Bounding it by what the buffer actually holds is what stops a short blob claiming a huge
     * field and provoking an OutOfMemoryError, which is an Error and would escape a catch on
     * Exception. This is the property the extraction had to carry over intact.
     */
    @Test
    void aFieldLengthLargerThanTheBlobIsRefusedBeforeAnythingIsAllocated() {
        byte[] lying = ByteBuffer.allocate(8).putInt(Integer.MAX_VALUE).putInt(0).array();
        assertThrows(SshPublicKeyException.class, () -> SshPublicKeys.reader(lying).readField());

        byte[] negative = ByteBuffer.allocate(8).putInt(-1).putInt(0).array();
        assertThrows(SshPublicKeyException.class, () -> SshPublicKeys.reader(negative).readField());
    }

    /** Sixteen bytes of colon-delimited lowercase hex, which is what an MD5 fingerprint is. */
    @Test
    void theDigestHelperFormatsColonHex() {
        String digest = SshPublicKeys.md5ColonHex(new byte[] {1, 2, 3});
        assertTrue(digest.matches("([0-9a-f]{2}:){15}[0-9a-f]{2}"), digest);
    }

    /**
     * Reading is on demand, which is observable: a field after the one asked for may be malformed
     * without disturbing the read. That is also what bounds the work, because the field count is
     * as caller-controlled as the field sizes. A blob of four-byte zero-length fields declares one
     * per four bytes it occupies, so materialising them all would cost an allocation per field
     * however few the caller wants.
     */
    @Test
    void aMalformedLaterFieldDoesNotDisturbAnEarlierRead() {
        byte[] blob = blobWithTrailingJunk();
        assertEquals("ssh-rsa", new String(SshPublicKeys.reader(blob).readField()),
                "the first field must be readable even though a later one is not");
    }


    /**
     * EC2 fingerprints a key by reading only the fields the fingerprint needs. Material after the
     * modulus is not part of the key, so rejecting a blob over it would turn a usable imported key
     * into one with no fingerprint at all.
     */
    @Test
    void fieldsAfterTheKeyDoNotInvalidateIt() {
        RSAPublicKey withJunk = SshPublicKeys.rsaKeyOf(blobWithTrailingJunk());
        assertEquals(2048, withJunk.getModulus().bitLength());
    }

    /** A valid ssh-rsa blob with an unreadable extra field welded onto the end. */
    private static byte[] blobWithTrailingJunk() {
        byte[] good = SshPublicKeys.decodeBlob(SshPublicKeys.toOpenSsh(KEY));
        ByteBuffer out = ByteBuffer.allocate(good.length + 8);
        // A trailing field claiming far more bytes than follow it.
        out.put(good).putInt(Integer.MAX_VALUE).putInt(0);
        return out.array();
    }

    /**
     * The worked {@code GetSSHPublicKey} example from the IAM API Reference returns this key with
     * {@code Encoding=PEM} and reports the fingerprint below. Reproducing it is the only way to
     * know, rather than assume, that IAM hashes the OpenSSH blob: the digest over the DER is a
     * value of exactly the same shape, so nothing in a response would reveal the mistake.
     */
    @Test
    void theOpenSshFingerprintReproducesTheDocumentedAwsExample() {
        String awsExamplePem = """
                -----BEGIN PUBLIC KEY-----
                MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAsu+WpO9hhmqGTctHI1BE
                SJ/pq4GtAt9JJpIsDnjeB+mLbwnVJLFaaYzzoZuPOVhUc7yHMWjBLmfSEgJKfAH3
                n8m8R9D3UFoRC0rtKR2jJwAwFO3Tp9wgnqzvPtLMnG7uBEuD/nHStanrd6bbBv83
                kDSy5jiuc4yEWtTAEtyp8C8BxFTxHuCQ/sX4IbjtJ8M1IKZ3hjcJO5u6ooWCxZzQ
                hXXlPDniK/RZnO+YOaJR5umaAv23HAB7qx5H3A6WpyUyzXy0eTo9eAmUrET+JDXZ
                vqHufiDzO/MOCfb+KV1OJos2AxNtRuIFA1cTq7NF+upTIoV+gK1YJhCvjSuRkIJ/
                cwIDAQAB
                -----END PUBLIC KEY-----
                """;
        RSAPublicKey key = SshPublicKeys.fromPem(awsExamplePem);
        assertEquals("7a:1d:ea:9e:b0:80:ac:f8:ec:d8:dc:e6:a7:2c:fc:51",
                SshPublicKeys.openSshFingerprint(SshPublicKeys.openSshBlob(key)));

        // And the DER digest is a different value, which is why the two are separate functions.
        assertNotEquals(SshPublicKeys.openSshFingerprint(SshPublicKeys.openSshBlob(key)),
                SshPublicKeys.md5ColonHex(key.getEncoded()),
                "if these ever agree, this test is no longer protecting anything");
    }

    @Test
    void aKeyRoundTripsBetweenPemAndTheOpenSshForm() {
        String pem = SshPublicKeys.toPem(KEY);
        assertTrue(SshPublicKeys.looksLikePem(pem));
        assertEquals(KEY.getModulus(), SshPublicKeys.fromPem(pem).getModulus());

        RSAPublicKey viaOpenSsh = SshPublicKeys.rsaKeyOf(
                SshPublicKeys.decodeBlob(SshPublicKeys.toOpenSsh(KEY)));
        assertEquals(KEY.getModulus(), SshPublicKeys.fromPem(SshPublicKeys.toPem(viaOpenSsh)).getModulus());
    }

    @Test
    void whatIsNotPemIsRefused() {
        assertFalse(SshPublicKeys.looksLikePem(null));
        assertFalse(SshPublicKeys.looksLikePem("ssh-rsa AAAA"));
        assertThrows(SshPublicKeyException.class, () -> SshPublicKeys.fromPem("not pem"));
        assertThrows(SshPublicKeyException.class, () -> SshPublicKeys.fromPem(
                "-----BEGIN PUBLIC KEY-----\nnot base64\n-----END PUBLIC KEY-----\n"));
    }

    @Test
    void theTypeIsReadFromTheBlob() {
        assertEquals("ssh-rsa", SshPublicKeys.keyType(
                SshPublicKeys.decodeBlob(SshPublicKeys.toOpenSsh(KEY))));
    }
}
