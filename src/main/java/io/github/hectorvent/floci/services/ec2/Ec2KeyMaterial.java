package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.PublicKeys;
import io.github.hectorvent.floci.core.common.SshPublicKeyException;
import io.github.hectorvent.floci.core.common.SshPublicKeys;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil;
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

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

    /** The shortest RSA key ImportKeyPair takes, the shortest of the lengths AWS lists as supported. */
    private static final int MIN_IMPORTED_RSA_BITS = 1024;

    /** The longest RSA key ImportKeyPair takes, which is also the longest OpenSSH reads. */
    private static final int MAX_IMPORTED_RSA_BITS = 16384;

    private static final String RFC_4716_BEGIN = "---- BEGIN SSH2 PUBLIC KEY ----";
    private static final String RFC_4716_END = "---- END SSH2 PUBLIC KEY ----";

    /** The base64 line length of ssh-keygen's private key files; readers take any. */
    private static final int OPENSSH_LINE_LENGTH = 70;

    private Ec2KeyMaterial() {}

    /**
     * @param privateKeyPem    the private key as KeyMaterial returns it: for an RSA key as PEM, the PKCS#1
     *                         "BEGIN RSA PRIVATE KEY" form AWS returns, for an ED25519 key as an OpenSSH
     *                         private key, and for KeyFormat ppk as a PuTTY key file
     * @param openSshPublicKey the matching "ssh-rsa AAAA..." or "ssh-ed25519 AAAA..." line, for authorized_keys
     * @param fingerprint      for an RSA key the SHA-1 of the DER private key, colon-separated hex; for an
     *                         ED25519 key the base64 SHA-256 of the public key blob
     */
    public record Generated(String privateKeyPem, String openSshPublicKey, String fingerprint) {}

    /**
     * A public key ImportKeyPair accepted.
     *
     * @param keyType          {@code rsa} or {@code ed25519}, as DescribeKeyPairs reports it
     * @param fingerprint      the fingerprint AWS reports for an imported key of that type
     * @param openSshPublicKey the key as one {@code "ssh-rsa AAAA..."} line without a comment,
     *                         whatever format it was imported in, for authorized_keys
     */
    public record Imported(String keyType, String fingerprint, String openSshPublicKey) {}

    public static Generated generateRsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_KEY_SIZE);
            KeyPair pair = generator.generateKeyPair();

            StringWriter out = new StringWriter();
            try (JcaPEMWriter pem = new JcaPEMWriter(out)) {
                // Writes the traditional PKCS#1 "RSA PRIVATE KEY" block rather than Java's
                // native PKCS#8 "PRIVATE KEY", matching what AWS actually returns. OpenSSH
                // reads both, but tooling that pattern-matches the header only reads the former.
                pem.writeObject(pair.getPrivate());
            }

            // "The SHA-1 digest of the DER encoded private key" for a key pair AWS created.
            // (Imported keys are fingerprinted another way, by key type -- see readImportedKey.)
            String fingerprint = colonHex(Digests.sha1(pair.getPrivate().getEncoded()));

            return new Generated(
                    out.toString(),
                    openSshPublicKey((RSAPublicKey) pair.getPublic()),
                    fingerprint);
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("Could not generate an EC2 key pair", e);
        }
    }

    /**
     * Reads the public key given to ImportKeyPair, accepting the key types and formats AWS
     * documents: an RSA or ED25519 key as an OpenSSH line, and an RSA key also as an RFC 4716
     * file or as base64 DER, bare or in PEM armour. An RSA key must be 1024 to 16384 bits. EC2
     * does not accept DSA keys, and ECDSA and security-key types are not among those it supports.
     *
     * <p>An OpenSSH line holds one key, whose blob has to be exactly the key of the type the line
     * names, as OpenSSH requires: no field missing, nothing after the key, no other type. Material
     * with a second line is refused rather than read as its first key, since an instance launched
     * with the key pair would trust every line of it.
     *
     * <p>The fingerprint is the one AWS reports for an imported key, which is not the scheme
     * CreateKeyPair uses:
     *
     * <ul>
     *   <li><b>rsa</b>: "the MD5 public key fingerprint", taken over the DER SubjectPublicKeyInfo
     *       rather than over the SSH wire blob. AWS documents the check as
     *       {@code openssl rsa -in key -pubout -outform DER | openssl md5 -c}, and
     *       {@link java.security.Key#getEncoded()} produces exactly that DER. Colon-separated
     *       hex, as AWS reports it.</li>
     *   <li><b>ed25519</b>: the base64-encoded SHA-256 digest of the wire blob, "which is the
     *       default for OpenSSH". This is the {@code ssh-keygen -l} value without its
     *       {@code SHA256:} prefix, and padded: AWS keeps the trailing {@code =} that ssh-keygen
     *       drops.</li>
     * </ul>
     *
     * @return the key, or null when AWS would refuse the material
     * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/create-key-pairs.html#how-to-generate-your-own-key-and-import-it-to-aws">Requirements for key pairs</a>
     * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/verify-keys.html">Verify the fingerprint of your key pair</a>
     */
    public static Imported readImportedKey(String material) {
        if (material == null) {
            return null;
        }
        List<String> lines = material.trim().lines().map(String::strip).toList();
        if (lines.isEmpty()) {
            return null;
        }
        String first = lines.getFirst();
        try {
            if (first.equals(RFC_4716_BEGIN)) {
                byte[] blob = rfc4716Blob(lines);
                return importedRsa(rsaKeyOfWholeBlob(blob), "ssh-rsa " + base64(blob));
            }
            if (first.startsWith("-----BEGIN ")) {
                RSAPublicKey key = rsaKeyOfDer(pemBody(lines));
                return importedRsa(key, SshPublicKeys.toOpenSsh(key));
            }
            String[] fields = first.split("\\s+", 3);
            if (fields.length > 1) {
                return lines.size() == 1 ? openSshLine(fields[0], Base64.getDecoder().decode(fields[1])) : null;
            }
            RSAPublicKey key = rsaKeyOfDer(Base64.getDecoder().decode(String.join("", lines)));
            return importedRsa(key, SshPublicKeys.toOpenSsh(key));
        } catch (SshPublicKeyException | IllegalArgumentException | IllegalStateException
                 | GeneralSecurityException | IOException expected) {
            // Material AWS refuses, which the caller answers with InvalidKey.Format. Bouncy Castle
            // reports some malformed DER with an IllegalStateException rather than an IOException.
            return null;
        }
    }

    static String openSshPublicKey(RSAPublicKey key) {
        return SshPublicKeys.toOpenSsh(key);
    }

    /** The key of an OpenSSH line whose type field is {@code type} and whose blob is {@code blob}. */
    private static Imported openSshLine(String type, byte[] blob) throws GeneralSecurityException {
        return switch (type) {
            case "ssh-rsa" -> importedRsa(rsaKeyOfWholeBlob(blob), "ssh-rsa " + base64(blob));
            case "ssh-ed25519" -> importedEd25519(blob);
            default -> null;
        };
    }

    /**
     * The RSA key of an {@code ssh-rsa} blob that holds its type, exponent and modulus and nothing
     * else. {@link SshPublicKeys#rsaKeyOf} stops once it has the key; an import must not.
     */
    private static RSAPublicKey rsaKeyOfWholeBlob(byte[] blob) throws GeneralSecurityException {
        SshPublicKeys.Reader reader = SshPublicKeys.reader(blob);
        byte[] type = reader.readField();
        byte[] exponent = reader.readField();
        byte[] modulus = reader.readField();
        BigInteger e = new BigInteger(exponent);
        BigInteger n = new BigInteger(modulus);
        if (!"ssh-rsa".equals(new String(type, StandardCharsets.UTF_8)) || !fillsTheBlob(blob, type, exponent, modulus)
                || e.signum() <= 0 || n.signum() <= 0) {
            throw new SshPublicKeyException("not exactly one ssh-rsa public key");
        }
        return PublicKeys.rsa(n, e);
    }

    private static Imported importedEd25519(byte[] blob) {
        SshPublicKeys.Reader reader = SshPublicKeys.reader(blob);
        byte[] type = reader.readField();
        byte[] key = reader.readField();
        if (!"ssh-ed25519".equals(new String(type, StandardCharsets.UTF_8)) || key.length != ED25519_KEY_LENGTH
                || !fillsTheBlob(blob, type, key)) {
            return null;
        }
        return new Imported("ed25519", ed25519Fingerprint(blob), "ssh-ed25519 " + base64(blob));
    }

    private static Imported importedRsa(RSAPublicKey key, String openSshPublicKey) {
        int bits = key.getModulus().bitLength();
        if (bits < MIN_IMPORTED_RSA_BITS || bits > MAX_IMPORTED_RSA_BITS) {
            return null;
        }
        return new Imported("rsa", SshPublicKeys.md5ColonHex(key.getEncoded()), openSshPublicKey);
    }

    /** Whether the length-prefixed fields read so far are the whole blob, with nothing after them. */
    private static boolean fillsTheBlob(byte[] blob, byte[]... fields) {
        int length = 0;
        for (byte[] field : fields) {
            length += Integer.BYTES + field.length;
        }
        return length == blob.length;
    }

    /**
     * The key blob of an RFC 4716 file: the base64 body between the begin and end markers, after
     * its header lines, each a {@code Tag: value} that a trailing backslash continues onto the next.
     */
    private static byte[] rfc4716Blob(List<String> lines) {
        if (lines.size() < 3 || !RFC_4716_END.equals(lines.getLast())) {
            throw new SshPublicKeyException("not an RFC 4716 public key");
        }
        StringBuilder body = new StringBuilder();
        boolean continued = false;
        for (String line : lines.subList(1, lines.size() - 1)) {
            if (body.isEmpty() && (continued || line.contains(":"))) {
                continued = line.endsWith("\\");
                continue;
            }
            body.append(line);
        }
        return Base64.getDecoder().decode(body.toString());
    }

    /**
     * The DER in PEM armour labelled as a public key: {@code PUBLIC KEY}, a SubjectPublicKeyInfo,
     * or {@code RSA PUBLIC KEY}, the bare PKCS#1 key that {@code ssh-keygen -e -m PEM} writes. Any
     * other label, such as a private key pasted by mistake, is not a public key.
     */
    private static byte[] pemBody(List<String> lines) {
        String label = lines.getFirst().substring("-----BEGIN ".length());
        if (!("PUBLIC KEY-----".equals(label) || "RSA PUBLIC KEY-----".equals(label))
                || lines.size() < 3 || !lines.getLast().equals("-----END " + label)) {
            throw new SshPublicKeyException("not a PEM public key");
        }
        return Base64.getDecoder().decode(String.join("", lines.subList(1, lines.size() - 1)));
    }

    /**
     * The RSA key a DER SubjectPublicKeyInfo or PKCS#1 RSAPublicKey encodes. Another algorithm,
     * such as an EC key, is refused, as are bytes after the structure.
     */
    private static RSAPublicKey rsaKeyOfDer(byte[] der) throws IOException, GeneralSecurityException {
        if (!(ASN1Primitive.fromByteArray(der) instanceof ASN1Sequence structure) || structure.size() == 0) {
            throw new SshPublicKeyException("not a DER public key");
        }
        // A SubjectPublicKeyInfo opens with its algorithm, itself a sequence; PKCS#1 with the modulus.
        ASN1Primitive rsaKey = structure;
        if (structure.getObjectAt(0) instanceof ASN1Sequence) {
            SubjectPublicKeyInfo info = SubjectPublicKeyInfo.getInstance(structure);
            // The key is a whole number of bytes, so a bit string declaring unused bits holds none.
            if (!PKCSObjectIdentifiers.rsaEncryption.equals(info.getAlgorithm().getAlgorithm())
                    || info.getPublicKeyData().getPadBits() != 0) {
                throw new SshPublicKeyException("not an RSA public key");
            }
            rsaKey = info.parsePublicKey();
        }
        // PKCS#1 RSAPublicKey: SEQUENCE { modulus INTEGER, publicExponent INTEGER }. An empty bit string
        // parses to nothing at all. The integers are read signed, as an ssh-rsa blob's are, so a
        // modulus missing its sign byte is refused rather than read as another, positive key.
        if (!(rsaKey instanceof ASN1Sequence fields) || fields.size() != 2) {
            throw new SshPublicKeyException("not an RSA public key");
        }
        BigInteger modulus = ASN1Integer.getInstance(fields.getObjectAt(0)).getValue();
        BigInteger exponent = ASN1Integer.getInstance(fields.getObjectAt(1)).getValue();
        if (modulus.signum() <= 0 || exponent.signum() <= 0) {
            throw new SshPublicKeyException("not an RSA public key");
        }
        return PublicKeys.rsa(modulus, exponent);
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    static String colonHex(byte[] digest) {
        return HexFormat.of().withDelimiter(":").formatHex(digest);
    }

    /**
     * The fingerprint EC2 gives an ED25519 key, created or imported: the base64-encoded SHA-256 of the
     * public key blob, the value {@code ssh-keygen -l} prints after {@code SHA256:}, with the {@code =}
     * padding AWS keeps and ssh-keygen drops.
     */
    private static String ed25519Fingerprint(byte[] blob) {
        return base64(Digests.sha256(blob));
    }

    /** A new ED25519 key pair for CreateKeyPair's {@code KeyType=ed25519}. */
    static Generated generateEd25519() {
        return ed25519(new Ed25519PrivateKeyParameters(new SecureRandom()));
    }

    /**
     * The material CreateKeyPair returns for an ED25519 private key: the key in OpenSSH's own private
     * key format, the {@code BEGIN OPENSSH PRIVATE KEY} block AWS returns for one, since PKCS#1, the
     * RSA form, has no ED25519 encoding.
     */
    static Generated ed25519(Ed25519PrivateKeyParameters privateKey) {
        byte[] blob;
        byte[] encoded;
        try {
            blob = OpenSSHPublicKeyUtil.encodePublicKey(privateKey.generatePublicKey());
            encoded = OpenSSHPrivateKeyUtil.encodePrivateKey(privateKey);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not encode an ED25519 key pair", e);
        }
        String body = base64(encoded);
        StringBuilder pem = new StringBuilder("-----BEGIN OPENSSH PRIVATE KEY-----\n");
        for (int i = 0; i < body.length(); i += OPENSSH_LINE_LENGTH) {
            pem.append(body, i, Math.min(i + OPENSSH_LINE_LENGTH, body.length())).append('\n');
        }
        pem.append("-----END OPENSSH PRIVATE KEY-----\n");
        return new Generated(pem.toString(), "ssh-ed25519 " + base64(blob), ed25519Fingerprint(blob));
    }

    /**
     * The key pair CreateKeyPair returns for a KeyType, {@code rsa} or {@code ed25519}, and a
     * KeyFormat, {@code pem} or {@code ppk}: a new key of that type, its private half in that format.
     * The request has already been refused for any other value.
     *
     * @param keyName the key pair's name, which a PuTTY key file carries as its comment
     */
    public static Generated generate(String keyType, String keyFormat, String keyName) {
        Generated pem = switch (keyType) {
            case "rsa" -> generateRsa();
            case "ed25519" -> generateEd25519();
            default -> throw new IllegalArgumentException("Unsupported key type: " + keyType);
        };
        return switch (keyFormat) {
            case "pem" -> pem;
            case "ppk" -> Ec2PuttyKeys.of(pem, keyName);
            default -> throw new IllegalArgumentException("Unsupported key format: " + keyFormat);
        };
    }
}
