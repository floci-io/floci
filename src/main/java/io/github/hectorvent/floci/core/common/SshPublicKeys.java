package io.github.hectorvent.floci.core.common;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The OpenSSH public key wire form, {@code "ssh-rsa AAAA..."}: decoding a line into its
 * length-prefixed fields, and reading or writing the RSA key they carry.
 *
 * <p>Extracted from {@code Ec2KeyMaterial} rather than left there because it is key-format
 * handling rather than anything EC2 owns. The digest helper is here for the same reason, and
 * deliberately says only what it computes: EC2 documents its {@code ssh-rsa} fingerprint as the
 * MD5 of the DER, which is not the same value as the MD5 of the blob that OpenSSH reports, so a
 * function named for one of them would invite the other's caller to reuse it wrongly.
 */
public final class SshPublicKeys {

    /**
     * Absolute ceiling on a single field, well clear of anything a real key carries: the largest
     * is the modulus of a 16384-bit RSA key, 2049 bytes with its sign byte.
     */
    private static final int MAX_FIELD_LENGTH = 64 * 1024;

    private static final int PEM_LINE_LENGTH = 64;

    private SshPublicKeys() {}

    /**
     * The base64 blob from an {@code "ssh-rsa AAAA... comment"} line, or {@code null} when the
     * value is not an OpenSSH public key line at all.
     */
    public static byte[] decodeBlob(String openSshPublicKey) {
        if (openSshPublicKey == null) {
            return null;
        }
        // "ssh-rsa AAAAB3Nza... comment": the middle field is the blob.
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

    /**
     * A reader over an OpenSSH blob's length-prefixed fields, which reads them on demand.
     *
     * <p>On demand rather than all at once, because the number of fields is as caller-controlled
     * as their sizes: a blob of four-byte zero-length fields declares as many as it has room for,
     * and eagerly materialising them would allocate once per field however few the caller needs.
     * A reader that stops where its caller stops is bounded by what that caller asks for.
     */
    public static Reader reader(byte[] blob) {
        if (blob == null) {
            throw new SshPublicKeyException("no OpenSSH key blob");
        }
        return new Reader(blob);
    }

    /** Reads the length-prefixed fields of an OpenSSH public key blob, one at a time. */
    public static final class Reader {

        private final ByteBuffer buffer;

        private Reader(byte[] blob) {
            this.buffer = ByteBuffer.wrap(blob);
        }

        /**
         * The next field.
         *
         * @throws SshPublicKeyException when the blob is truncated or declares an impossible length
         */
        public byte[] readField() {
            if (buffer.remaining() < Integer.BYTES) {
                throw new SshPublicKeyException("truncated OpenSSH key blob");
            }
            int length = buffer.getInt();
            // The blob comes straight from a caller's request, so the declared length is
            // attacker-controlled and has to be checked before it is allocated. Reading it first
            // meant an eleven-byte blob could declare a two-gigabyte field and the array was built
            // before anything noticed the bytes were not there; the resulting OutOfMemoryError is
            // an Error, so a catch around the caller did not contain it either. Bounding by what
            // is left in the buffer ties the allocation to the size of the request.
            if (length < 0 || length > MAX_FIELD_LENGTH || length > buffer.remaining()) {
                throw new SshPublicKeyException("invalid OpenSSH key field length: " + length);
            }
            byte[] field = new byte[length];
            buffer.get(field);
            return field;
        }
    }

    /**
     * The RSA public key an {@code ssh-rsa} blob carries, whose fields after the type are the
     * exponent and then the modulus. Reads those three and stops: anything after them is not part
     * of the key, and refusing a blob over it would reject material the parse never needed.
     */
    public static RSAPublicKey rsaKeyOf(byte[] blob) {
        Reader reader = reader(blob);
        // The declared type is checked rather than skipped. Discarding it parses the exponent and
        // modulus of whatever follows, so a line labelled ssh-ed25519 carrying an RSA blob would
        // be read as an RSA key and stored under a type that was never supported.
        String type = new String(reader.readField(), StandardCharsets.UTF_8);
        if (!"ssh-rsa".equals(type)) {
            throw new SshPublicKeyException("not an ssh-rsa blob: " + type);
        }
        BigInteger exponent = new BigInteger(reader.readField());
        BigInteger modulus = new BigInteger(reader.readField());
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(modulus, exponent));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new SshPublicKeyException("not a usable RSA public key: " + e.getMessage(), e);
        }
    }

    /** The {@code "ssh-rsa AAAA..."} line for a key, without a trailing comment. */
    public static String toOpenSsh(RSAPublicKey key) {
        return "ssh-rsa " + Base64.getEncoder().encodeToString(openSshBlob(key));
    }

    /** The OpenSSH blob for a key: the type, then the exponent, then the modulus. */
    public static byte[] openSshBlob(RSAPublicKey key) {
        byte[] type = "ssh-rsa".getBytes(StandardCharsets.US_ASCII);
        byte[] exponent = key.getPublicExponent().toByteArray();
        byte[] modulus = key.getModulus().toByteArray();
        ByteBuffer blob = ByteBuffer.allocate(
                3 * Integer.BYTES + type.length + exponent.length + modulus.length);
        for (byte[] field : new byte[][] {type, exponent, modulus}) {
            blob.putInt(field.length).put(field);
        }
        return blob.array();
    }

    /** The key type an OpenSSH blob declares, such as {@code ssh-rsa}. */
    public static String keyType(byte[] blob) {
        return new String(reader(blob).readField(), StandardCharsets.UTF_8);
    }

    /** The key as a PEM SubjectPublicKeyInfo, the {@code BEGIN PUBLIC KEY} form. */
    public static String toPem(RSAPublicKey key) {
        String body = Base64.getEncoder().encodeToString(key.getEncoded());
        StringBuilder pem = new StringBuilder("-----BEGIN PUBLIC KEY-----\n");
        for (int i = 0; i < body.length(); i += PEM_LINE_LENGTH) {
            pem.append(body, i, Math.min(i + PEM_LINE_LENGTH, body.length())).append('\n');
        }
        return pem.append("-----END PUBLIC KEY-----\n").toString();
    }

    /**
     * Whether a value is a PEM public key rather than an OpenSSH line. Both markers are required:
     * treating the closing one as optional accepts a truncated body, which then decodes because
     * the base64 that survived is still valid base64.
     */
    public static boolean looksLikePem(String value) {
        return value != null
                && value.contains("-----BEGIN PUBLIC KEY-----")
                && value.contains("-----END PUBLIC KEY-----");
    }

    /** Whether a value opens as PEM, whether or not it is complete. */
    public static boolean opensAsPem(String value) {
        return value != null && value.contains("-----BEGIN PUBLIC KEY-----");
    }

    /** The RSA public key a PEM SubjectPublicKeyInfo carries. */
    public static RSAPublicKey fromPem(String pem) {
        if (!looksLikePem(pem)) {
            throw new SshPublicKeyException("not a PEM public key");
        }
        String body = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (IllegalArgumentException | GeneralSecurityException | ClassCastException e) {
            throw new SshPublicKeyException("not a usable RSA public key: " + e.getMessage(), e);
        }
    }

    /**
     * The OpenSSH fingerprint: the MD5 of the blob itself, colon-delimited lowercase hex.
     *
     * <p>This is what IAM reports as an SSH public key's {@code Fingerprint}, confirmed by
     * reproducing the worked example in the IAM API Reference. It is deliberately not the digest
     * EC2 reports for the same key, which is taken over the DER: both are sixteen bytes of colon
     * hex, so substituting one for the other is wrong and looks right.
     */
    public static String openSshFingerprint(byte[] blob) {
        return md5ColonHex(blob);
    }

    /** MD5 of the given bytes as colon-delimited lowercase hex. */
    public static String md5ColonHex(byte[] bytes) {
        try {
            return HexFormat.of().withDelimiter(":")
                    .formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new SshPublicKeyException("MD5 is unavailable", e);
        }
    }
}
