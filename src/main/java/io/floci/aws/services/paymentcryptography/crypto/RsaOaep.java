package io.floci.aws.services.paymentcryptography.crypto;

import org.bouncycastle.crypto.AsymmetricBlockCipher;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.encodings.OAEPEncoding;
import org.bouncycastle.crypto.engines.RSABlindedEngine;
import org.bouncycastle.crypto.engines.RSAEngine;
import org.bouncycastle.crypto.params.RSAKeyParameters;
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters;

import java.security.Key;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

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

    /** Decrypt an OAEP-wrapped block. */
    public static byte[] decrypt(PrivateKey key, byte[] ciphertext, String digest) {
        return run(false, key, null, ciphertext, digest);
    }

    /** Encrypt to an OAEP-wrapped block. Used by tests and by the demo. */
    public static byte[] encrypt(PublicKey key, byte[] plaintext, String digest) {
        return run(true, null, key, plaintext, digest);
    }

    /**
     * The OAEP encoding, with the requested digest for the OAEP digest and for MGF1.
     *
     * <p>The label is empty, which is what the reference implementation's
     * {@code PSource.PSpecified.DEFAULT} means.
     */
    private static OAEPEncoding encoding(String digest, boolean encrypt) {
        // The private operation is the blinded one: it decrypts what a caller brought, so its
        // timing must not say how far the exponentiation has come. The public operation
        // exponentiates with a public exponent, and blinding that protects nothing.
        AsymmetricBlockCipher engine = encrypt ? new RSAEngine() : new RSABlindedEngine();
        Digest hash = "SHA-512".equals(digest) ? new SHA512Digest() : new SHA256Digest();
        return new OAEPEncoding(engine, hash, hash, null);
    }

    private static byte[] run(boolean encrypt, PrivateKey priv, PublicKey pub,
                              byte[] input, String digest) {
        try {
            OAEPEncoding oaep = encoding(digest, encrypt);
            if (encrypt) {
                RSAPublicKey rsa = (RSAPublicKey) pub;
                oaep.init(true, new RSAKeyParameters(false, rsa.getModulus(), rsa.getPublicExponent()));
            } else {
                oaep.init(false, privateParameters((RSAPrivateKey) priv));
            }
            return oaep.processBlock(input, 0, input.length);
        } catch (InvalidCipherTextException e) {
            throw new IllegalArgumentException("RSA-OAEP failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("RSA-OAEP failed: " + e.getMessage(), e);
        }
    }

    /**
     * The private key's parameters, with the CRT values when the key carries them.
     *
     * <p>The blinded engine blinds the exponentiation it is given, and it does so with the
     * CRT values, so a key that has them is passed as they are.
     *
     * @param key the private key
     * @return the parameters the engine works with
     */
    private static RSAKeyParameters privateParameters(RSAPrivateKey key) {
        if (key instanceof RSAPrivateCrtKey crt) {
            return new RSAPrivateCrtKeyParameters(crt.getModulus(), crt.getPublicExponent(),
                    crt.getPrivateExponent(), crt.getPrimeP(), crt.getPrimeQ(),
                    crt.getPrimeExponentP(), crt.getPrimeExponentQ(), crt.getCrtCoefficient());
        }
        return new RSAKeyParameters(true, key.getModulus(), key.getPrivateExponent());
    }

    /** Decrypt with a key given as the generic {@link Key} type. */
    public static byte[] decrypt(Key key, byte[] ciphertext, String digest) {
        return decrypt((PrivateKey) key, ciphertext, digest);
    }
}
