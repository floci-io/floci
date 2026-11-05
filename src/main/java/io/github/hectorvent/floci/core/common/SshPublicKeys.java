package io.github.hectorvent.floci.core.common;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The OpenSSH public key wire form, {@code "ssh-rsa AAAA..."}: decoding a line into its
 * length-prefixed fields, reading or writing the RSA key they carry, and telling whether a blob
 * holds a whole RSA, ed25519, ECDSA or security-key public key of the type it declares.
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

    /** The length of an ed25519 public key, the one field after its type. */
    private static final int ED25519_KEY_LENGTH = 32;

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

        /** Whether every byte of the blob has been read. */
        private boolean atEnd() {
            return !buffer.hasRemaining();
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
        return rsaKeyFrom(reader);
    }

    /** The RSA public key whose exponent and then modulus are the reader's next two fields. */
    private static RSAPublicKey rsaKeyFrom(Reader reader) {
        BigInteger exponent = new BigInteger(reader.readField());
        BigInteger modulus = new BigInteger(reader.readField());
        try {
            return PublicKeys.rsa(modulus, exponent);
        } catch (GeneralSecurityException e) {
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

    /**
     * Whether a blob carries a whole public key of the type it declares: an {@code ssh-rsa}
     * exponent and modulus that make a usable key, a 32-byte ed25519 key, or an uncompressed point
     * of the size of the curve an ECDSA type names, followed by an application field for the two
     * security-key types, {@code sk-ssh-ed25519@openssh.com} and
     * {@code sk-ecdsa-sha2-nistp256@openssh.com}. A type field alone, or a field of the wrong size,
     * is not a key, and neither is a type other than these, such as {@code ssh-dss} or a
     * certificate. Unlike {@link #rsaKeyOf}, which reads only the fields it needs, nothing may
     * follow the key, as OpenSSH requires. The check is of the format alone: it does not ask
     * whether an RSA modulus is long enough for OpenSSH or whether an EC point lies on its curve.
     */
    public static boolean carriesAKey(byte[] blob) {
        if (blob == null) {
            return false;
        }
        try {
            Reader reader = reader(blob);
            String type = new String(reader.readField(), StandardCharsets.UTF_8);
            boolean keyFields = switch (type) {
                case "ssh-rsa" -> {
                    rsaKeyFrom(reader); // throws when the exponent and modulus make no usable key
                    yield true;
                }
                case "ssh-ed25519" -> reader.readField().length == ED25519_KEY_LENGTH;
                case "sk-ssh-ed25519@openssh.com" -> {
                    boolean key = reader.readField().length == ED25519_KEY_LENGTH;
                    reader.readField(); // the application, which only has to be there
                    yield key;
                }
                case "ecdsa-sha2-nistp256" -> carriesAPointOn(reader, "nistp256", 32);
                case "ecdsa-sha2-nistp384" -> carriesAPointOn(reader, "nistp384", 48);
                case "ecdsa-sha2-nistp521" -> carriesAPointOn(reader, "nistp521", 66);
                case "sk-ecdsa-sha2-nistp256@openssh.com" -> {
                    boolean point = carriesAPointOn(reader, "nistp256", 32);
                    reader.readField(); // the application, which only has to be there
                    yield point;
                }
                default -> false;
            };
            return keyFields && reader.atEnd();
        } catch (SshPublicKeyException | NumberFormatException ignored) {
            // A missing or empty field, such as an ssh-rsa type with no exponent: not a key.
            return false;
        }
    }

    /**
     * Whether the next fields name the curve and hold an uncompressed point of its size:
     * {@code 0x04} and then both coordinates, each {@code coordinateLength} bytes long.
     */
    private static boolean carriesAPointOn(Reader reader, String curve, int coordinateLength) {
        if (!curve.equals(new String(reader.readField(), StandardCharsets.UTF_8))) {
            return false;
        }
        byte[] point = reader.readField();
        return point.length == 1 + 2 * coordinateLength && point[0] == 4;
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
        return HexFormat.of().withDelimiter(":").formatHex(Digests.md5(bytes));
    }
}
