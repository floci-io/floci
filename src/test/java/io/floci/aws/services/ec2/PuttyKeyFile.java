package io.floci.aws.services.ec2;

import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.SshPublicKeys;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A PuTTY private key file, version 2, read the way PuTTY reads one, for the key pair tests: written
 * apart from Ec2PuttyKeys, so a test checks the format rather than the writer's own idea of it. The
 * layout and the MAC follow PuTTY's sshpubk.c: a version line, an encryption line and a comment, the
 * public blob and the private fields as counted lines of base64, and an HMAC-SHA1, keyed with the
 * SHA-1 of "putty-private-key-file-mac-key" when unencrypted, over the algorithm, the encryption, the
 * comment and both blobs, each as an SSH string. Its fields and the RSA public key are read with
 * SshPublicKeys, which SshPublicKeysTest checks on its own.
 */
record PuttyKeyFile(String algorithm, String encryption, String comment, byte[] publicBlob, byte[] privateBlob,
                    String mac) {

    static PuttyKeyFile parse(String text) {
        assertTrue(text.startsWith("PuTTY-User-Key-File-2: "),
                "not a PuTTY version 2 key file: " + text.lines().findFirst().orElse(""));
        assertTrue(text.endsWith("\n"), "a PuTTY key file ends its last line");
        List<String> lines = text.lines().toList();
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, byte[]> blobs = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int colon = line.indexOf(": ");
            assertTrue(colon > 0, "line " + (i + 1) + " is not a header line");
            String name = line.substring(0, colon);
            headers.put(name, line.substring(colon + 2));
            if (name.endsWith("-Lines")) {
                List<String> body = lines.subList(i + 1, i + 1 + Integer.parseInt(headers.get(name)));
                body.subList(0, body.size() - 1).forEach(row ->
                        assertEquals(64, row.length(), "PuTTY writes 64 base64 characters to a line"));
                blobs.put(name, Base64.getDecoder().decode(String.join("", body)));
                i += body.size();
            }
        }
        assertEquals(List.of("PuTTY-User-Key-File-2", "Encryption", "Comment", "Public-Lines", "Private-Lines",
                "Private-MAC"), new ArrayList<>(headers.keySet()));
        return new PuttyKeyFile(headers.get("PuTTY-User-Key-File-2"), headers.get("Encryption"),
                headers.get("Comment"), blobs.get("Public-Lines"), blobs.get("Private-Lines"),
                headers.get("Private-MAC"));
    }

    boolean macIsValid() throws GeneralSecurityException {
        Mac hmac = Mac.getInstance("HmacSHA1");
        hmac.init(new SecretKeySpec(Digests.sha1(utf8("putty-private-key-file-mac-key")), "HmacSHA1"));
        byte[] expected = hmac.doFinal(strings(utf8(algorithm), utf8(encryption), utf8(comment), publicBlob, privateBlob));
        return HexFormat.of().formatHex(expected).equals(mac);
    }

    /**
     * The RSA key the file holds, rebuilt from its public exponent and modulus and its private exponent,
     * primes and coefficient, after checking they make one key, with the larger prime first.
     */
    RSAPrivateCrtKey rsaKey() throws GeneralSecurityException {
        assertEquals("ssh-rsa", algorithm);
        RSAPublicKey publicKey = SshPublicKeys.rsaKeyOf(publicBlob);
        List<BigInteger> fields = privateFields(4).stream().map(BigInteger::new).toList();
        BigInteger e = publicKey.getPublicExponent();
        BigInteger n = publicKey.getModulus();
        BigInteger d = fields.get(0);
        BigInteger p = fields.get(1);
        BigInteger q = fields.get(2);
        BigInteger iqmp = fields.get(3);
        assertEquals(n, p.multiply(q));
        assertTrue(p.compareTo(q) > 0, "PuTTY keeps the larger prime first");
        assertEquals(BigInteger.ONE, iqmp.multiply(q).mod(p), "the coefficient is the inverse of q modulo p");
        assertEquals(BigInteger.ONE, e.multiply(d).mod(p.subtract(BigInteger.ONE)));
        assertEquals(BigInteger.ONE, e.multiply(d).mod(q.subtract(BigInteger.ONE)));
        return (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new RSAPrivateCrtKeySpec(n, e, d, p, q,
                d.mod(p.subtract(BigInteger.ONE)), d.mod(q.subtract(BigInteger.ONE)), iqmp));
    }

    /** The ED25519 key the file holds, after checking its public blob is that key's public half. */
    Ed25519PrivateKeyParameters ed25519Key() throws IOException {
        assertEquals("ssh-ed25519", algorithm);
        Ed25519PrivateKeyParameters key = new Ed25519PrivateKeyParameters(privateFields(1).getFirst(), 0);
        assertTrue(Arrays.equals(publicBlob, OpenSSHPublicKeyUtil.encodePublicKey(key.generatePublicKey())),
                "the public blob is the public half of the private key");
        return key;
    }

    /** The private blob's fields, which have to be exactly {@code count} with nothing after them. */
    private List<byte[]> privateFields(int count) {
        SshPublicKeys.Reader reader = SshPublicKeys.reader(privateBlob);
        List<byte[]> fields = new ArrayList<>();
        int length = 0;
        for (int i = 0; i < count; i++) {
            fields.add(reader.readField());
            length += Integer.BYTES + fields.getLast().length;
        }
        assertEquals(privateBlob.length, length, "nothing follows the private fields of an unencrypted file");
        return fields;
    }

    private static byte[] strings(byte[]... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] field : fields) {
            out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(field.length).array());
            out.writeBytes(field);
        }
        return out.toByteArray();
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
