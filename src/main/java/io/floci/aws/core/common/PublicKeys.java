package io.floci.aws.core.common;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;

/**
 * Public keys built from their raw components, the form JWKs, COSE keys and SSH blobs carry them in,
 * and the parameters of a named EC curve.
 */
public final class PublicKeys {

    private PublicKeys() {
    }

    /** The RSA public key with this modulus and public exponent. */
    public static RSAPublicKey rsa(BigInteger modulus, BigInteger exponent) throws GeneralSecurityException {
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
    }

    /** The EC public key at the point ({@code x}, {@code y}) of the named curve, such as {@code secp256r1}. */
    public static ECPublicKey ec(String curve, BigInteger x, BigInteger y) throws GeneralSecurityException {
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(
                new ECPublicKeySpec(new ECPoint(x, y), namedCurve(curve)));
    }

    /** The domain parameters of a named EC curve, such as {@code secp256r1}. */
    public static ECParameterSpec namedCurve(String name) throws GeneralSecurityException {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec(name));
        return parameters.getParameterSpec(ECParameterSpec.class);
    }
}
