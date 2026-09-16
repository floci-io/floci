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
        byte[] blob = decodeOpenSshBlob(openSshPublicKey);
        if (blob == null) {
            return null;
        }
        try {
            byte[] der = blob;
            SshReader reader = new SshReader(blob);
            String type = new String(reader.readBytes(), StandardCharsets.UTF_8);
            if ("ssh-ed25519".equals(type)) {
                if (reader.readBytes().length != ED25519_KEY_LENGTH) {
                    return null;
                }
                return Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(blob));
            }
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

        /**
         * Absolute ceiling on a single field, well clear of anything a real key carries: the
         * largest is the modulus of a 16384-bit RSA key, 2049 bytes with its sign byte.
         */
        private static final int MAX_FIELD_LENGTH = 64 * 1024;

        private final ByteBuffer buffer;

        SshReader(byte[] blob) {
            this.buffer = ByteBuffer.wrap(blob);
        }

        byte[] readBytes() {
            if (buffer.remaining() < Integer.BYTES) {
                throw new IllegalArgumentException("truncated OpenSSH key blob");
            }
            int length = buffer.getInt();
            // ImportKeyPair hands the caller's PublicKeyMaterial straight to this parser, so
            // the declared length is attacker-controlled and has to be checked before it is
            // allocated. Reading it first meant an eleven-byte blob could declare a
            // two-gigabyte field and the array was built before anything noticed the bytes
            // were not there; the resulting OutOfMemoryError is an Error, so the catch around
            // fingerprintOf did not contain it either. Bounding by what is left in the buffer
            // ties the allocation to the size of the request that carried it.
            if (length < 0 || length > MAX_FIELD_LENGTH || length > buffer.remaining()) {
                throw new IllegalArgumentException("invalid OpenSSH key field length: " + length);
            }
            byte[] field = new byte[length];
            buffer.get(field);
            return field;
        }
    }
}
