package io.github.hectorvent.floci.services.ec2;

import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CreateKeyPair previously returned a fixed 63-character string literal as KeyMaterial. It
 * looked like a PEM but did not parse ("ssh-keygen -y -f key.pem: invalid format"), so no
 * caller could ever use it -- and it was byte-identical for every key pair.
 *
 * <p>These tests deliberately parse and use the material rather than matching it against a
 * pattern: a placeholder passes any shape check, which is how the original went unnoticed.
 */
class Ec2KeyMaterialTest {

    @Test
    void generatedPrivateKeyParsesAsA2048BitRsaKey() throws Exception {
        Ec2KeyMaterial.Generated generated = Ec2KeyMaterial.generateRsa();

        Object parsed = new PEMParser(new StringReader(generated.privateKeyPem())).readObject();
        assertTrue(parsed instanceof PEMKeyPair, "expected a PEM key pair, got: " + parsed);
        RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) new JcaPEMKeyConverter()
                .getKeyPair((PEMKeyPair) parsed).getPrivate();

        assertEquals(2048, privateKey.getModulus().bitLength());
    }

    @Test
    void generatedPrivateKeyUsesThePkcs1HeaderAwsReturns() {
        // AWS returns the traditional "BEGIN RSA PRIVATE KEY" block. Java's own encoding is
        // PKCS#8 ("BEGIN PRIVATE KEY"); OpenSSH reads both, but tooling that matches on the
        // header only reads the former.
        String pem = Ec2KeyMaterial.generateRsa().privateKeyPem();
        assertTrue(pem.startsWith("-----BEGIN RSA PRIVATE KEY-----"), pem.lines().findFirst().orElse(""));
        assertTrue(pem.contains("-----END RSA PRIVATE KEY-----"));
    }

    @Test
    void publicKeyMatchesThePrivateKeyItWasGeneratedWith() throws Exception {
        // The whole point of the pair: what gets written to authorized_keys has to be the
        // other half of what the caller receives, or SSH still fails.
        Ec2KeyMaterial.Generated generated = Ec2KeyMaterial.generateRsa();

        PEMKeyPair parsed = (PEMKeyPair) new PEMParser(
                new StringReader(generated.privateKeyPem())).readObject();
        RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) new JcaPEMKeyConverter()
                .getKeyPair(parsed).getPrivate();

        String[] fields = generated.openSshPublicKey().split(" ");
        assertEquals("ssh-rsa", fields[0]);
        ByteBuffer blob = ByteBuffer.wrap(Base64.getDecoder().decode(fields[1]));

        assertEquals("ssh-rsa", new String(read(blob), StandardCharsets.UTF_8));
        assertEquals(privateKey.getPublicExponent(), new BigInteger(read(blob)));
        assertEquals(privateKey.getModulus(), new BigInteger(read(blob)));
    }

    @Test
    void everyKeyPairIsDistinct() {
        // The literal made all key pairs identical, so a second key silently authenticated
        // as the first.
        Ec2KeyMaterial.Generated first = Ec2KeyMaterial.generateRsa();
        Ec2KeyMaterial.Generated second = Ec2KeyMaterial.generateRsa();

        assertNotEquals(first.privateKeyPem(), second.privateKeyPem());
        assertNotEquals(first.openSshPublicKey(), second.openSshPublicKey());
        assertNotEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void fingerprintIsTheColonSeparatedSha1OfTheDerPrivateKey() {
        // "The SHA-1 digest of the DER encoded private key" -- 20 bytes, so 20 hex pairs.
        String fingerprint = Ec2KeyMaterial.generateRsa().fingerprint();

        assertTrue(fingerprint.matches("([0-9a-f]{2}:){19}[0-9a-f]{2}"), fingerprint);
    }

    @Test
    void importedKeyFingerprintIsPerKeyRatherThanAConstant() {
        String first = Ec2KeyMaterial.fingerprintOf(Ec2KeyMaterial.generateRsa().openSshPublicKey());
        String second = Ec2KeyMaterial.fingerprintOf(Ec2KeyMaterial.generateRsa().openSshPublicKey());

        assertNotNull(first);
        // MD5 of the DER public key: 16 bytes.
        assertTrue(first.matches("([0-9a-f]{2}:){15}[0-9a-f]{2}"), first);
        assertNotEquals(first, second);
    }

    @Test
    void importedKeyFingerprintIsStableAndIgnoresTheTrailingComment() {
        // ssh-keygen writes "ssh-rsa AAAA... user@host"; the comment is not part of the key,
        // so re-importing the same key under a different comment must not change its identity.
        String publicKey = Ec2KeyMaterial.generateRsa().openSshPublicKey();

        assertEquals(Ec2KeyMaterial.fingerprintOf(publicKey),
                Ec2KeyMaterial.fingerprintOf(publicKey + " someone@example.com"));
    }

    @Test
    void unparseableImportedMaterialYieldsNoFingerprintRatherThanADigestOfGarbage() {
        assertEquals(null, Ec2KeyMaterial.fingerprintOf("not-a-key"));
        assertEquals(null, Ec2KeyMaterial.fingerprintOf(""));
        assertEquals(null, Ec2KeyMaterial.fingerprintOf(null));
    }

    @Test
    void openSshEncodingRoundTripsAnArbitraryRsaPublicKey() throws Exception {
        // Guards the length-prefixed framing: a wrong length here produces a line that looks
        // right and that sshd silently ignores.
        Ec2KeyMaterial.Generated generated = Ec2KeyMaterial.generateRsa();
        PEMKeyPair parsed = (PEMKeyPair) new PEMParser(
                new StringReader(generated.privateKeyPem())).readObject();
        RSAPublicKey publicKey = (RSAPublicKey) new JcaPEMKeyConverter()
                .getKeyPair(parsed).getPublic();

        assertEquals(generated.openSshPublicKey(), Ec2KeyMaterial.openSshPublicKey(publicKey));
    }

    private static byte[] read(ByteBuffer blob) {
        byte[] field = new byte[blob.getInt()];
        blob.get(field);
        return field;
    }
}
