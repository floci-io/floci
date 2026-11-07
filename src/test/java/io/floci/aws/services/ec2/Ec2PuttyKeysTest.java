package io.floci.aws.services.ec2;

import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.Pem;
import io.floci.aws.core.common.SshPublicKeys;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.interfaces.RSAPrivateCrtKey;
import java.util.HexFormat;

import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.PUTTY_TEST_KEY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ec2PuttyKeysTest {

    /**
     * The unencrypted version 2 file PuTTY's own test suite (test/cryptsuite.py, testPPKLoadSave)
     * checks its writer against, byte for byte, for {@link Ec2KeyPairFixtures#PUTTY_TEST_KEY}.
     */
    private static final String PUTTY_TEST_FILE = """
            PuTTY-User-Key-File-2: ssh-ed25519
            Encryption: none
            Comment: ed25519-key-20200105
            Public-Lines: 2
            AAAAC3NzaC1lZDI1NTE5AAAAIHJCszOHaI9X/yGLtjn22f0hO6VPMQDVtctkym6F
            JH1W
            Private-Lines: 1
            AAAAIGvvIpl8jyqn8Xufkw6v3FnEGtXF3KWw55AP3/AGEBpY
            Private-MAC: 2a629acfcfbe28488a1ba9b6948c36406bc28422
            """;

    @Test
    void anEd25519KeyIsWrittenExactlyAsPuttyWritesIt() {
        Ec2KeyMaterial.Generated pem = Ec2KeyMaterial.ed25519(new Ed25519PrivateKeyParameters(PUTTY_TEST_KEY, 0));

        Ec2KeyMaterial.Generated ppk = Ec2PuttyKeys.of(pem, "ed25519-key-20200105");

        assertEquals(PUTTY_TEST_FILE, ppk.privateKeyPem());
        assertEquals(pem.openSshPublicKey(), ppk.openSshPublicKey());
        assertEquals(pem.fingerprint(), ppk.fingerprint());
    }

    @Test
    void anEd25519FileHoldsTheKeyOfThePem() throws Exception {
        Ec2KeyMaterial.Generated pem = Ec2KeyMaterial.generate("ed25519", "pem", "name");

        PuttyKeyFile file = PuttyKeyFile.parse(Ec2PuttyKeys.of(pem, "name").privateKeyPem());

        assertEquals("ssh-ed25519", file.algorithm());
        assertTrue(file.macIsValid());
        assertArrayEquals(Ec2KeyPairFixtures.openSshEd25519Key(pem.privateKeyPem()).getEncoded(),
                file.ed25519Key().getEncoded());
        assertArrayEquals(SshPublicKeys.decodeBlob(pem.openSshPublicKey()), file.publicBlob());
    }

    /**
     * PuTTY keeps an RSA key's larger prime first, and Java's generator puts either first, so both
     * orders are checked: the file holds the PEM key with the larger prime first, and is
     * fingerprinted by the SHA-1 of that key's DER, which is what PuTTYgen exports from it.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anRsaFileHoldsTheKeyOfThePemWithTheLargerPrimeFirst(boolean largerPrimeFirstInThePem) throws Exception {
        Ec2KeyMaterial.Generated pem = rsaPemWithLargerPrimeFirst(largerPrimeFirstInThePem);
        RSAPrivateCrtKey generated = (RSAPrivateCrtKey) Pem.parsePrivateKey(pem.privateKeyPem());

        Ec2KeyMaterial.Generated ppk = Ec2PuttyKeys.of(pem, "rsa-name");
        PuttyKeyFile file = PuttyKeyFile.parse(ppk.privateKeyPem());

        assertEquals("ssh-rsa", file.algorithm());
        assertEquals("none", file.encryption());
        assertEquals("rsa-name", file.comment());
        assertTrue(file.macIsValid());
        RSAPrivateCrtKey written = file.rsaKey();
        assertEquals(generated.getModulus(), written.getModulus());
        assertEquals(generated.getPrivateExponent(), written.getPrivateExponent());
        assertEquals(generated.getPrimeP().max(generated.getPrimeQ()), written.getPrimeP());
        assertArrayEquals(SshPublicKeys.decodeBlob(pem.openSshPublicKey()), file.publicBlob());
        assertEquals(pem.openSshPublicKey(), ppk.openSshPublicKey());
        assertEquals(colonHex(Digests.sha1(written.getEncoded())), ppk.fingerprint());
        if (largerPrimeFirstInThePem) {
            // The same key with the same DER, so the fingerprint CreateKeyPair gives the PEM.
            assertEquals(pem.fingerprint(), ppk.fingerprint());
        }
    }

    @Test
    void aGeneratedRsaKeyFileHoldsA2048BitKeyFingerprintedAsWritten() throws Exception {
        Ec2KeyMaterial.Generated ppk = Ec2KeyMaterial.generate("rsa", "ppk", "name");
        RSAPrivateCrtKey key = PuttyKeyFile.parse(ppk.privateKeyPem()).rsaKey();

        assertEquals(2048, key.getModulus().bitLength());
        assertEquals(colonHex(Digests.sha1(key.getEncoded())), ppk.fingerprint());
    }

    /**
     * PuTTY ends a line at a line feed or a carriage return, so a name holding either cannot be the
     * comment; a tab or any other control character is left out with them.
     */
    @ParameterizedTest
    @ValueSource(strings = {"two\nlines", "two\rlines", "a\ttab", "a\177delete"})
    void aNameThatCannotBeOneLineOfTheFileIsLeftOut(String name) throws Exception {
        Ec2KeyMaterial.Generated pem = Ec2KeyMaterial.generate("ed25519", "pem", "ignored");

        PuttyKeyFile file = PuttyKeyFile.parse(Ec2PuttyKeys.of(pem, name).privateKeyPem());

        assertEquals("", file.comment());
        assertTrue(file.macIsValid());
    }

    /**
     * A key from generateRsa with its primes in the given order, which Java's generator picks at
     * random. A generator that never puts the smaller prime first leaves that order unreachable,
     * so the case is skipped rather than failed.
     */
    private static Ec2KeyMaterial.Generated rsaPemWithLargerPrimeFirst(boolean largerPrimeFirst) {
        for (int attempt = 0; attempt < 64; attempt++) {
            Ec2KeyMaterial.Generated pem = Ec2KeyMaterial.generateRsa();
            RSAPrivateCrtKey key = (RSAPrivateCrtKey) Pem.parsePrivateKey(pem.privateKeyPem());
            if ((key.getPrimeP().compareTo(key.getPrimeQ()) > 0) == largerPrimeFirst) {
                return pem;
            }
        }
        return Assumptions.abort("64 generated keys in a row had their primes in the other order");
    }

    private static String colonHex(byte[] digest) {
        return HexFormat.of().withDelimiter(":").formatHex(digest);
    }
}
