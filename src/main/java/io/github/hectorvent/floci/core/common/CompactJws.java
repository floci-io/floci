package io.github.hectorvent.floci.core.common;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;

/**
 * Signs a compact JWS, the {@code header.payload.signature} form a JWT travels in. The header and
 * the claim set arrive already serialized, so what belongs to one service's token stays with that
 * service and only the assembly is shared.
 *
 * <p>Each part is base64url without padding, which is what JWS requires and what {@link Base64Url}
 * produces. An ECDSA signature has to be the raw {@code R || S} pair rather than the DER sequence
 * {@code SHA384withECDSA} returns, so an ECDSA caller names the JDK's {@code ...inP1363Format}
 * variant, which produces that pair directly and leaves nothing to re-encode here.
 */
public final class CompactJws {

    private CompactJws() {
    }

    /**
     * The signed token for a serialized {@code header} and {@code claims}, where {@code algorithm}
     * is the JCA signature name for the {@code alg} the header declares.
     */
    public static String sign(byte[] header, byte[] claims, PrivateKey key, String algorithm) {
        String signingInput = Base64Url.encode(header) + "." + Base64Url.encode(claims);
        return signingInput + "." + Base64Url.encode(signature(signingInput, key, algorithm));
    }

    private static byte[] signature(String signingInput, PrivateKey key, String algorithm) {
        try {
            Signature signature = Signature.getInstance(algorithm);
            signature.initSign(key);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Signing a compact JWS with " + algorithm + " failed", e);
        }
    }
}
