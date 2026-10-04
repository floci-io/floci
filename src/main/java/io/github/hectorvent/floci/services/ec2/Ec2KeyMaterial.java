package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.SshPublicKeys;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Real key material for EC2 key pairs.
 *
 * <p>CreateKeyPair is the only time AWS ever discloses a private key, and callers are
 * expected to write the response straight to a file and use it: the Packer amazon-ebs
 * builder, for one, creates a temporary key pair, saves the returned material, and SSHes
 * in with it. A placeholder string cannot serve that, so the key is generated here.
 *
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_CreateKeyPair.html">CreateKeyPair</a>
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_ImportKeyPair.html">ImportKeyPair</a>
 */
public final class Ec2KeyMaterial {

    /** AWS generates 2048-bit RSA keys for KeyType=rsa, which is the default. */
    private static final int RSA_KEY_SIZE = 2048;

    /** An ed25519 public key is a fixed 32-byte value, so any other length is not one. */
    private static final int ED25519_KEY_LENGTH = 32;

    private Ec2KeyMaterial() {}

    /**
     * @param privateKeyPem   PKCS#1 PEM, the "BEGIN RSA PRIVATE KEY" form AWS returns
     * @param openSshPublicKey the matching "ssh-rsa AAAA..." line, for authorized_keys
     * @param fingerprint     SHA-1 of the DER private key, colon-separated hex
     */
    public record Generated(String privateKeyPem, String openSshPublicKey, String fingerprint) {}

    public static Generated generateRsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_KEY_SIZE);
            java.security.KeyPair pair = generator.generateKeyPair();

            StringWriter out = new StringWriter();
            try (JcaPEMWriter pem = new JcaPEMWriter(out)) {
                // Writes the traditional PKCS#1 "RSA PRIVATE KEY" block rather than Java's
                // native PKCS#8 "PRIVATE KEY", matching what AWS actually returns. OpenSSH
                // reads both, but tooling that pattern-matches the header only reads the former.
                pem.writeObject(pair.getPrivate());
            }

            // "The SHA-1 digest of the DER encoded private key" for a key pair AWS created.
            // (Imported keys use the MD5 of the public key instead -- see fingerprintOf.)
            String fingerprint = colonHex(
                    MessageDigest.getInstance("SHA-1").digest(pair.getPrivate().getEncoded()));

            return new Generated(
                    out.toString(),
                    openSshPublicKey((RSAPublicKey) pair.getPublic()),
                    fingerprint);
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("Could not generate an EC2 key pair", e);
        }
    }

    /**
     * Fingerprint for an imported public key, in the scheme AWS uses for that key type.
     *
     * <p>ImportKeyPair does not fingerprint every key the same way, and it does not agree
     * with CreateKeyPair either:
     *
     * <ul>
     *   <li><b>ssh-rsa</b>: "the MD5 public key fingerprint", taken over the DER
     *       SubjectPublicKeyInfo rather than over the SSH wire blob. AWS documents the check
     *       as {@code openssl rsa -in key -pubout -outform DER | openssl md5 -c}, and
     *       {@link java.security.Key#getEncoded()} produces exactly that DER, so the blob is
     *       decoded back to a public key first. Colon-separated hex, as AWS reports it.</li>
     *   <li><b>ssh-ed25519</b>: the base64-encoded SHA-256 digest of the wire blob, "which is
     *       the default for OpenSSH". This is the {@code ssh-keygen -l} value without its
     *       {@code SHA256:} prefix, and padded: AWS keeps the trailing {@code =} that
     *       ssh-keygen drops.</li>
     * </ul>
     *
     * <p>Any other key type keeps the MD5 of the wire blob: stable and distinct per key,
     * which is what callers depend on, but not a value AWS would report. AWS accepts only
     * RSA and ed25519 material for import, so nothing else has a documented answer to match.
     *
     * <p>Returns null for material that does not parse, so the caller can decide rather than
     * getting a fingerprint of garbage.
     *
     * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/verify-keys.html">Verify the fingerprint of your key pair</a>
     */
    public static String fingerprintOf(String openSshPublicKey) {
        byte[] blob = SshPublicKeys.decodeBlob(openSshPublicKey);
        if (blob == null) {
            return null;
        }
        try {
            SshPublicKeys.Reader reader = SshPublicKeys.reader(blob);
            String type = new String(reader.readField(), StandardCharsets.UTF_8);
            if ("ssh-ed25519".equals(type)) {
                if (reader.readField().length != ED25519_KEY_LENGTH) {
                    return null;
                }
                return Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(blob));
            }
            // EC2 documents its ssh-rsa fingerprint as the MD5 of the DER, which is not the
            // OpenSSH fingerprint of the same key. SshPublicKeys keeps the two apart on purpose.
            byte[] der = "ssh-rsa".equals(type) ? SshPublicKeys.rsaKeyOf(blob).getEncoded() : blob;
            return SshPublicKeys.md5ColonHex(der);
        } catch (Exception e) {  // malformed material is the caller's problem, not a crash
            return null;
        }
    }

    static String openSshPublicKey(RSAPublicKey key) {
        return SshPublicKeys.toOpenSsh(key);
    }

    private static String colonHex(byte[] digest) {
        return HexFormat.of().withDelimiter(":").formatHex(digest);
    }
}
