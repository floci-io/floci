package io.floci.aws.services.paymentcryptography.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;

/**
 * RSA-OAEP, with the parameters built explicitly.
 *
 * <p><strong>MGF1 follows the OAEP digest.</strong> The parameters are still
 * built by hand rather than by asking for {@code OAEPWithSHA-256AndMGF1Padding},
 * for two reasons. There is no {@code OAEPWithSHA-512AndMGF1Padding} constant,
 * so the SHA-512 wrapping specification has to be constructed - and a spec
 * built by copying a SHA-1 example leaves MGF1 on SHA-1 and fails with an
 * opaque OAEP decoding error on a hex string that looks perfectly valid. And
 * every OAEP operation in this project routes through here, so the two digests
 * cannot drift apart again.
 */
public final class RsaOaep {

    private RsaOaep() {
    }

    /** The OAEP digest for a wrapping specification, or null when unsupported. */
    public static String digestFor(String wrappingSpec) {
        if ("RSA_OAEP_SHA_256".equals(wrappingSpec) || wrappingSpec == null) {
            return "SHA-256";
        }
        if ("RSA_OAEP_SHA_512".equals(wrappingSpec)) {
            return "SHA-512";
        }
        return null;
    }

    /**
     * The OAEP parameters Node's {@code oaepHash} produces: the requested digest
     * for both the OAEP digest and MGF1.
     */
    static OAEPParameterSpec params(String digest) {
        MGF1ParameterSpec mgf1 = "SHA-512".equals(digest)
                ? MGF1ParameterSpec.SHA512 : MGF1ParameterSpec.SHA256;
        return new OAEPParameterSpec(digest, "MGF1", mgf1, PSource.PSpecified.DEFAULT);
    }

    /** Decrypt an OAEP-wrapped block. */
    public static byte[] decrypt(PrivateKey key, byte[] ciphertext, String digest) {
        return run(Cipher.DECRYPT_MODE, key, null, ciphertext, digest);
    }

    /** Encrypt to an OAEP-wrapped block. Used by tests and by the demo. */
    public static byte[] encrypt(PublicKey key, byte[] plaintext, String digest) {
        return run(Cipher.ENCRYPT_MODE, null, key, plaintext, digest);
    }

    private static byte[] run(int mode, PrivateKey priv, PublicKey pub,
                              byte[] input, String digest) {
        try {
            // "ECB" here is part of the JCA transformation name, not a mode:
            // RSA is not a block cipher, so there is no chaining and no ECB
            // weakness. The JCA has no other spelling for RSA-OAEP. A scanner
            // that flags ECB ciphers will flag this line; the flag is a false
            // positive, and the actual block ciphers this project uses in ECB
            // mode (TDES/AES-ECB) do so because the wire protocol and the
            // TR-31 KCV and MAC constructions require it.
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");            if (mode == Cipher.DECRYPT_MODE) {
                cipher.init(mode, priv, params(digest));
            } else {
                cipher.init(mode, pub, params(digest));
            }
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("RSA-OAEP failed: " + e.getMessage(), e);
        }
    }

    /** Decrypt with a key given as the generic {@link Key} type. */
    public static byte[] decrypt(Key key, byte[] ciphertext, String digest) {
        return decrypt((PrivateKey) key, ciphertext, digest);
    }
}
