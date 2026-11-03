package io.github.hectorvent.floci.services.paymentcryptography;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;

/**
 * The key pairs the service makes.
 *
 * <p>An RSA key pair is made for the key exchange and for the TR-34 block, and an EC one
 * for the key agreement the exchange does. The certificates over them are the exchange's
 * own business.
 */
final class PaymentCryptographyKeyPairs {

    private PaymentCryptographyKeyPairs() {
    }

    /** An RSA key pair of the requested modulus size. */
    static KeyPair rsa(int bits, SecureRandom random) {
        if (bits != 2048 && bits != 3072 && bits != 4096) {
            throw new IllegalArgumentException(
                    "unsupported key size " + bits + " (2048, 3072, 4096)");
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits, random);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot generate an RSA key pair", e);
        }
    }

    /** An EC key pair on the curve the algorithm names. */
    static KeyPair ec(PaymentCryptographyKeyAlgorithm algorithm, SecureRandom random) {
        String curve = curveOf(algorithm);
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec(curve), random);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot generate an EC key pair", e);
        }
    }

    /**
     * The curve an algorithm names.
     *
     * <p>The curve is the NIST one the cardholder-device flow uses.
     */
    private static String curveOf(PaymentCryptographyKeyAlgorithm algorithm) {
        return switch (algorithm) {
            case ECC_NIST_P256 -> "secp256r1";
            case ECC_NIST_P384 -> "secp384r1";
            case ECC_NIST_P521 -> "secp521r1";
            default -> throw new IllegalArgumentException(
                    algorithm.name() + " is not an elliptic curve algorithm");
        };
    }
}
