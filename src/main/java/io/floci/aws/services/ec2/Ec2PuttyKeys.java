package io.floci.aws.services.ec2;

import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.Pem;
import io.floci.aws.core.common.SshPublicKeys;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil;
import org.bouncycastle.util.io.pem.PemReader;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The key pair CreateKeyPair returns for {@code KeyFormat=ppk}: the key it creates for PEM, written
 * as a PuTTY private key file instead.
 *
 * <p>The file is version 2 of PuTTY's format, unencrypted, with the key pair's name as its comment,
 * which is what AWS returns for an RSA and an ED25519 key alike; every PuTTY release since 0.52 reads
 * version 2, and since 0.68 for an ED25519 key. It is laid out as PuTTY's sshpubk.c writes it: a line
 * naming the key's algorithm, {@code Encryption: none}, the comment, the public key blob and the
 * private fields as counted lines of base64, 64 characters to a line, and a {@code Private-MAC}, the
 * HMAC-SHA1 of the algorithm, the encryption, the comment and both blobs, each as an SSH string, keyed
 * with the SHA-1 of {@code putty-private-key-file-mac-key} followed by the passphrase, empty here.
 *
 * <p>The public key and the fingerprint are the PEM key's, but for the order of an RSA key's primes:
 * PuTTY keeps the larger first, which Java's key generator does not ensure, so the key is written in
 * that order and fingerprinted, by the SHA-1 of its DER private key, as written. That is the key
 * PuTTYgen exports from the file.
 *
 * @see <a href="https://the.earth.li/~sgtatham/putty/0.83/htmldoc/AppendixC.html">PuTTY's key file format</a>
 */
final class Ec2PuttyKeys {

    private static final int LINE_LENGTH = 64;

    private Ec2PuttyKeys() {}

    /**
     * {@code pem} as a PuTTY key file whose comment is {@code keyName}, or empty for a name holding a
     * control character, such as a line break, since the comment is a line of the file.
     */
    static Ec2KeyMaterial.Generated of(Ec2KeyMaterial.Generated pem, String keyName) {
        byte[] publicBlob = SshPublicKeys.decodeBlob(pem.openSshPublicKey());
        String algorithm = SshPublicKeys.keyType(publicBlob);
        String comment = keyName == null || keyName.chars().anyMatch(Character::isISOControl) ? "" : keyName;
        return switch (algorithm) {
            case "ssh-rsa" -> {
                RSAPrivateCrtKey key = largerPrimeFirst((RSAPrivateCrtKey) Pem.parsePrivateKey(pem.privateKeyPem()));
                // PuTTY's ssh-rsa private fields: the private exponent, the primes and the inverse of the
                // second prime modulo the first.
                byte[] privateBlob = strings(key.getPrivateExponent().toByteArray(), key.getPrimeP().toByteArray(),
                        key.getPrimeQ().toByteArray(), key.getCrtCoefficient().toByteArray());
                yield new Ec2KeyMaterial.Generated(file(algorithm, comment, publicBlob, privateBlob),
                        pem.openSshPublicKey(), Ec2KeyMaterial.colonHex(Digests.sha1(key.getEncoded())));
            }
            // PuTTY's ssh-ed25519 private field: the 32-byte private key, without OpenSSH's copy of the public key.
            case "ssh-ed25519" -> new Ec2KeyMaterial.Generated(
                    file(algorithm, comment, publicBlob, strings(ed25519PrivateKey(pem.privateKeyPem()))),
                    pem.openSshPublicKey(), pem.fingerprint());
            default -> throw new IllegalArgumentException("No PuTTY key file for a " + algorithm + " key");
        };
    }

    private static String file(String algorithm, String comment, byte[] publicBlob, byte[] privateBlob) {
        StringBuilder file = new StringBuilder()
                .append("PuTTY-User-Key-File-2: ").append(algorithm).append('\n')
                .append("Encryption: none\n")
                .append("Comment: ").append(comment).append('\n');
        appendLines(file, "Public-Lines", publicBlob);
        appendLines(file, "Private-Lines", privateBlob);
        byte[] mac = mac(strings(ascii(algorithm), ascii("none"), comment.getBytes(StandardCharsets.UTF_8),
                publicBlob, privateBlob));
        return file.append("Private-MAC: ").append(HexFormat.of().formatHex(mac)).append('\n').toString();
    }

    private static void appendLines(StringBuilder file, String header, byte[] blob) {
        String base64 = Base64.getEncoder().encodeToString(blob);
        file.append(header).append(": ").append((base64.length() + LINE_LENGTH - 1) / LINE_LENGTH).append('\n');
        for (int i = 0; i < base64.length(); i += LINE_LENGTH) {
            file.append(base64, i, Math.min(i + LINE_LENGTH, base64.length())).append('\n');
        }
    }

    private static byte[] mac(byte[] data) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA1");
            hmac.init(new SecretKeySpec(Digests.sha1(ascii("putty-private-key-file-mac-key")), "HmacSHA1"));
            return hmac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA1 is unavailable", e);
        }
    }

    /** The key with its larger prime first, the order PuTTY stores, and its CRT values for that order. */
    private static RSAPrivateCrtKey largerPrimeFirst(RSAPrivateCrtKey key) {
        if (key.getPrimeP().compareTo(key.getPrimeQ()) > 0) {
            return key;
        }
        BigInteger p = key.getPrimeQ();
        BigInteger q = key.getPrimeP();
        BigInteger d = key.getPrivateExponent();
        try {
            return (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new RSAPrivateCrtKeySpec(
                    key.getModulus(), key.getPublicExponent(), d, p, q, d.mod(p.subtract(BigInteger.ONE)),
                    d.mod(q.subtract(BigInteger.ONE)), q.modInverse(p)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not reorder the RSA key's primes", e);
        }
    }

    private static byte[] ed25519PrivateKey(String openSshPem) {
        try (PemReader reader = new PemReader(new StringReader(openSshPem))) {
            return ((Ed25519PrivateKeyParameters) OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(
                    reader.readPemObject().getContent())).getEncoded();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the ED25519 key", e);
        }
    }

    /** SSH strings: each field as a four-byte length and then its bytes. */
    private static byte[] strings(byte[]... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] field : fields) {
            out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(field.length).array());
            out.writeBytes(field);
        }
        return out.toByteArray();
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
