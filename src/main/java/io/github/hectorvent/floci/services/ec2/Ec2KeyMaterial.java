package io.github.hectorvent.floci.services.ec2;

import org.bouncycastle.openssl.jcajce.JcaPEMWriter;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
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
     * Fingerprint for an imported public key: the MD5 digest of its DER encoding, which is
     * what AWS reports for ImportKeyPair.
     *
     * <p>Only ssh-rsa is decoded back to a DER SubjectPublicKeyInfo. For any other key type
     * (ssh-ed25519 and friends) the digest is taken over the wire-format blob instead: still
     * stable and distinct per key, which is what callers actually depend on, but not
     * byte-identical to what AWS would report. Returns null for material that does not parse,
     * so the caller can decide rather than getting a fingerprint of garbage.
     */
    public static String fingerprintOf(String openSshPublicKey) {
        byte[] blob = decodeOpenSshBlob(openSshPublicKey);
        if (blob == null) {
            return null;
        }
        try {
            byte[] der = blob;
            SshReader reader = new SshReader(blob);
            String type = new String(reader.readBytes(), StandardCharsets.UTF_8);
            if ("ssh-rsa".equals(type)) {
                BigInteger exponent = new BigInteger(reader.readBytes());
                BigInteger modulus = new BigInteger(reader.readBytes());
                der = KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(modulus, exponent))
                        .getEncoded();
            }
            return colonHex(MessageDigest.getInstance("MD5").digest(der));
        } catch (Exception e) {  // malformed material is the caller's problem, not a crash
            return null;
        }
    }

    static String openSshPublicKey(RSAPublicKey key) {
        byte[] type = "ssh-rsa".getBytes(StandardCharsets.US_ASCII);
        byte[] exponent = key.getPublicExponent().toByteArray();
        byte[] modulus = key.getModulus().toByteArray();
        ByteBuffer blob = ByteBuffer.allocate(
                12 + type.length + exponent.length + modulus.length);
        for (byte[] field : new byte[][]{type, exponent, modulus}) {
            blob.putInt(field.length).put(field);
        }
        return "ssh-rsa " + Base64.getEncoder().encodeToString(blob.array());
    }

    private static byte[] decodeOpenSshBlob(String openSshPublicKey) {
        if (openSshPublicKey == null) {
            return null;
        }
        // "ssh-rsa AAAAB3Nza... comment" -- the middle field is the blob.
        String[] fields = openSshPublicKey.trim().split("\\s+");
        if (fields.length < 2) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(fields[1]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String colonHex(byte[] digest) {
        return HexFormat.of().withDelimiter(":").formatHex(digest);
    }

    /** Reads the length-prefixed fields of an OpenSSH public key blob. */
    private static final class SshReader {
        private final ByteBuffer buffer;

        SshReader(byte[] blob) {
            this.buffer = ByteBuffer.wrap(blob);
        }

        byte[] readBytes() {
            byte[] field = new byte[buffer.getInt()];
            buffer.get(field);
            return field;
        }
    }
}
