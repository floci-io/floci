package io.github.hectorvent.floci.services.paymentcryptography.crypto;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The TR-34 builder, against the TR-34 verifier.
 *
 * <p>The two are the mirror of each other: what the builder produces, the verifier recovers.
 * A block that one makes and the other cannot read is a block the service would reject.
 */
class PaymentCryptographyTr34RoundTripTest {

    @Test
    @DisplayName("a built block recovers under the receiver's key")
    void buildsWhatTheVerifierRecovers() throws Exception {
        // The key receiving device: its key pair and its certificate.
        KeyPair krd = X509.generateRsaKeyPair(2048);
        X509.Cert krdCert = X509.mintSelfSigned(krd, "krd",
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(86400));
        // The key distribution host: the service's signing key.
        KeyPair kdh = X509.generateRsaKeyPair(2048);
        X509.Cert kdhCert = X509.mintSelfSigned(kdh, "kdh",
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(86400));

        byte[] clear = Hex.decode("404142434445464748494A4B4C4D4E4F");
        String header = "B0010P0TB00E0000";
        byte[] nonce = Hex.decode("0102030405060708");

        byte[] block = Tr34.build(clear, header, nonce, kdh.getPrivate(), krd.getPublic());
        assertNotNull(block, "the block was not built");

        Tr34.Recovered recovered = Tr34.recoverAndVerify(Hex.encodeUpper(block),
                krd.getPrivate(), kdhCert.pem, null, Hex.encodeUpper(nonce));

        assertEquals(Hex.encodeUpper(clear), recovered.keyHex);
        assertEquals(header, recovered.header);
    }

    @Test
    @DisplayName("a built block carries the key's TR-31 header")
    void carriesTheHeader() throws Exception {
        KeyPair krd = X509.generateRsaKeyPair(2048);
        KeyPair kdh = X509.generateRsaKeyPair(2048);
        X509.Cert kdhCert = X509.mintSelfSigned(kdh, "kdh",
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(86400));
        byte[] block = Tr34.build(Hex.decode("404142434445464748494A4B4C4D4E4F"),
                "B0010P0TB00E0000", null, kdh.getPrivate(), krd.getPublic());
        Tr34.Recovered recovered = Tr34.recoverAndVerify(Hex.encodeUpper(block),
                krd.getPrivate(), kdhCert.pem, null, null);
        assertEquals("B0010P0TB00E0000", recovered.header);
    }

    @Test
    @DisplayName("a tampered block does not verify")
    void refusesATamperedBlock() throws Exception {
        KeyPair krd = X509.generateRsaKeyPair(2048);
        KeyPair kdh = X509.generateRsaKeyPair(2048);
        X509.Cert kdhCert = X509.mintSelfSigned(kdh, "kdh",
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(86400));
        byte[] block = Tr34.build(Hex.decode("404142434445464748494A4B4C4D4E4F"),
                "B0010P0TB00E0000", null, kdh.getPrivate(), krd.getPublic());
        block[block.length - 1] ^= 0x01;
        try {
            Tr34.recoverAndVerify(Hex.encodeUpper(block), krd.getPrivate(), kdhCert.pem, null, null);
            Assertions.fail("a tampered block was accepted");
        } catch (RuntimeException expected) {
            // The signature or the digest catches it.
        }
    }
}
