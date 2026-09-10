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

    /** Throwaway keys, kept fixed so their fingerprints can be pinned to known good values. */
    private static final String RSA_PUBLIC_KEY = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCp7mGC9"
            + "NkQI+loxf1G9bM6HnCs9iR1nnzZA/f/o7hx/Wv1oDhx03k6H83I+Q49eE1XO56WBPxnr8/2G6UmS9D0R"
            + "FKe9L+HJrfiZF7oLQ09JwEK91VLNSkD0Bq2zhnfWJe/ULkaPQ7FgHEghRi8aI5PsATH6VCaJDKWxl+2b"
            + "zM7MWlbKRAo8uuu2evnGrgnu+RmuXJQCRYz6lG+JESVzm6MnHXYxme+UD+7c/tTYwzoswfXh8VN8QVzX"
            + "mjfHi2Ve3PJ+YuF2X2gKpRMNMf7cLWMCTOhZI2AgXX+NLDlCG0dEUm/DXdSKRTDhm3mIJmF67eGYuff+"
            + "zHusBZ9cSBkW9i9";

    private static final String ED25519_PUBLIC_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAII0fBPBUZHaEOBc2mySfmI5btu4mkvFfNRujmF7RH2fj";

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

    @Test
    void importedRsaFingerprintMatchesTheSchemeAwsDocuments() {
        // Pinned against a value derived outside this code, from a throwaway key:
        //   openssl rsa -in rsa.pem -pubout -outform DER | openssl md5 -c
        // which is the command AWS gives for verifying an imported RSA key pair. A test that
        // compared the service against fingerprintOf, or that only checked the digest shape,
        // would pass under either scheme and so could not tell them apart.
        String expected = "4d:1a:39:2e:6a:18:60:9a:c5:2a:cb:cc:6c:de:22:b5";

        assertEquals(expected, Ec2KeyMaterial.fingerprintOf(RSA_PUBLIC_KEY));
        assertEquals(expected, Ec2KeyMaterial.fingerprintOf(RSA_PUBLIC_KEY + " someone@example.com"));

        // The other candidate scheme for the same key: MD5 over the SSH wire blob, which is
        // what "ssh-keygen -l -E md5" reports. AWS does not use it for imported RSA keys, and
        // it is a different value, which is what makes the assertion above load bearing.
        assertNotEquals("e7:d6:55:60:68:18:8f:b7:4f:2a:72:20:0b:2f:f2:d9",
                Ec2KeyMaterial.fingerprintOf(RSA_PUBLIC_KEY));
    }

    @Test
    void importedEd25519FingerprintIsTheBase64Sha256AwsReports() {
        // EC2 fingerprints ed25519 keys with SHA-256, not MD5, whichever way the key arrived.
        // Derived independently with "ssh-keygen -l -f ed25519.pub", which prints
        //   SHA256:UOyzahv0Ty520U89wfCvKdTlp2TbtpmnlpJHPW3MbMk
        // AWS reports the same digest without the prefix and with base64 padding kept.
        String fingerprint = Ec2KeyMaterial.fingerprintOf(ED25519_PUBLIC_KEY);

        assertEquals("UOyzahv0Ty520U89wfCvKdTlp2TbtpmnlpJHPW3MbMk=", fingerprint);
        assertEquals(fingerprint,
                Ec2KeyMaterial.fingerprintOf(ED25519_PUBLIC_KEY + " someone@example.com"));
    }

    @Test
    void ed25519MaterialThatIsNotA32ByteKeyYieldsNoFingerprint() {
        assertEquals(null, Ec2KeyMaterial.fingerprintOf(
                "ssh-ed25519 " + Base64.getEncoder().encodeToString(blobOf("ssh-ed25519", new byte[31]))));
    }

    @Test
    void aFieldLongerThanTheBlobIsRejectedRatherThanAllocated() {
        // ImportKeyPair passes the caller's material to this parser, so the declared field
        // length is attacker controlled. A blob that announces a two-gigabyte field is a few
        // bytes on the wire; allocating first turns that into an OutOfMemoryError, which is an
        // Error and so escapes the catch inside fingerprintOf and takes the request thread
        // with it. The length has to be rejected before the array exists.
        ByteBuffer overstated = ByteBuffer.allocate(15);
        byte[] type = "ssh-rsa".getBytes(StandardCharsets.UTF_8);
        overstated.putInt(type.length).put(type).putInt(Integer.MAX_VALUE);

        assertEquals(null, Ec2KeyMaterial.fingerprintOf(
                "ssh-rsa " + Base64.getEncoder().encodeToString(overstated.array())));
    }

    @Test
    void negativeAndTruncatedFieldLengthsYieldNoFingerprint() {
        ByteBuffer negative = ByteBuffer.allocate(4).putInt(-1);
        assertEquals(null, Ec2KeyMaterial.fingerprintOf(
                "ssh-rsa " + Base64.getEncoder().encodeToString(negative.array())));

        // Fewer than four bytes left: not even a length prefix.
        assertEquals(null, Ec2KeyMaterial.fingerprintOf(
                "ssh-rsa " + Base64.getEncoder().encodeToString(new byte[]{0, 0, 1})));
    }

    private static byte[] blobOf(String type, byte[] key) {
        byte[] typeBytes = type.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(8 + typeBytes.length + key.length)
                .putInt(typeBytes.length).put(typeBytes)
                .putInt(key.length).put(key)
                .array();
    }

    private static byte[] read(ByteBuffer blob) {
        byte[] field = new byte[blob.getInt()];
        blob.get(field);
        return field;
    }
}
