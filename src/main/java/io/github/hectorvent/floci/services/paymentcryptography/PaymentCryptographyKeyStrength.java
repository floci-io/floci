package io.github.hectorvent.floci.services.paymentcryptography;

import java.security.interfaces.ECKey;

/**
 * The minimum key strengths the PCI standards set, and the rule they make of them.
 *
 * <p>A key that protects another one has to be at least as strong as the key it
 * protects, because a weaker protector buys nothing: the protected key is then
 * only as safe as the key that wraps it. The service enforces this on export
 * and on import, and the reference does the same.
 *
 * <p>The strengths are the ones the PCI HSM standards count, not the length of
 * the material: a two-key Triple DES key is 128 bits of material but 80 bits of
 * strength, and a three-key one is 192 bits of material and 112 bits of
 * strength. A length would call the two-key key the stronger one, which is the
 * opposite of the truth.
 */
final class PaymentCryptographyKeyStrength {

    private PaymentCryptographyKeyStrength() {
    }

    /**
     * The strength of a key of an algorithm, in bits, as the PCI standards
     * count it. A key of an algorithm this service does not have a strength
     * for has none, and cannot protect anything.
     */
    static int bitsOf(String algorithm) {
        if (algorithm == null) {
            return 0;
        }
        return switch (algorithm) {
            case "TDES_2KEY" -> 80;
            case "TDES_3KEY" -> 112;
            case "AES_128" -> 128;
            case "AES_192" -> 192;
            case "AES_256" -> 256;
            default -> 0;
        };
    }

    /**
     * The strength of an RSA key of a modulus size, in bits.
     *
     * <p>An RSA key is as strong as factoring its modulus is hard. The standards count a
     * 2048-bit modulus as 112 bits, a 3072-bit one as 128 bits and a 4096-bit one as 152
     * bits.
     *
     * @param modulusBits the size of the modulus, in bits
     * @return the strength, in bits, or zero for a size this service has none for
     */
    static int rsaBitsOf(int modulusBits) {
        if (modulusBits >= 4096) {
            return 152;
        }
        if (modulusBits >= 3072) {
            return 128;
        }
        if (modulusBits >= 2048) {
            return 112;
        }
        return 0;
    }

    /**
     * The strength of an EC key on a curve, in bits.
     *
     * <p>A curve is as strong as the discrete logarithm on it is hard. The standards count
     * P-256 as 128 bits, P-384 as 192 bits and P-521 as 256 bits.
     *
     * @param algorithm the curve, as the key algorithm names it
     * @return the strength, in bits, or zero for a curve this service has none for
     */
    static int eccBitsOf(String algorithm) {
        if (algorithm == null) {
            return 0;
        }
        return switch (algorithm) {
            case "ECC_NIST_P256" -> 128;
            case "ECC_NIST_P384" -> 192;
            case "ECC_NIST_P521" -> 256;
            default -> 0;
        };
    }

    /**
     * The strength of an EC public key, from the curve it is on.
     *
     * <p>The curve is read from the key itself, because a certificate may name a curve the
     * service does not have a strength for, and a key agreement is only as strong as the
     * weaker of the two curves it was agreed on.
     *
     * @param key the public key, or null when there is none
     * @return the strength, in bits, or zero for a curve this service has none for
     */
    static int eccBitsOf(ECKey key) {
        if (key == null) {
            return 0;
        }
        int fieldBits = key.getParams().getCurve().getField().getFieldSize();
        return switch (fieldBits) {
            case 256 -> 128;
            case 384 -> 192;
            case 521 -> 256;
            default -> 0;
        };
    }

    /**
     * Whether a key of one algorithm may protect a key of another.
     *
     * <p>The protecting key has to be at least as strong as the key it
     * protects. A key of the same strength is allowed: the rule is that the
     * protector is not weaker, not that it is stronger.
     *
     * @param protecting the algorithm of the key that protects the other
     * @param protectedKey the algorithm of the key that is protected
     * @return whether the combination is one the standards allow
     */
    static boolean mayProtect(String protecting, String protectedKey) {
        int protector = bitsOf(protecting);
        int protectedBits = bitsOf(protectedKey);
        if (protector == 0 || protectedBits == 0) {
            return false;
        }
        return protector >= protectedBits;
    }

    /**
     * The reason a combination is refused, in words a caller can act on.
     *
     * @param protecting the algorithm of the key that protects the other
     * @param protectedKey the algorithm of the key that is protected
     * @return the message, or null when the combination is allowed
     */
    static String refusal(String protecting, String protectedKey) {
        return refusal(bitsOf(protecting), bitsOf(protectedKey), protecting, protectedKey);
    }

    /**
     * The reason a combination is refused, in words a caller can act on.
     *
     * <p>This is the form for a protector whose strength comes from the size of its modulus or
     * its curve rather than from a name.
     *
     * @param protectorBits the strength of the protecting key, in bits
     * @param protectedBits the strength of the protected key, in bits
     * @param protectingName what to call the protecting key
     * @param protectedName what to call the protected key
     * @return the message, or null when the combination is allowed
     */
    static String refusal(int protectorBits, int protectedBits, String protectingName,
                          String protectedName) {
        if (protectorBits == 0 || protectedBits == 0) {
            return "a " + (protectedName == null ? "the key" : protectedName)
                    + " key may not be protected by a "
                    + (protectingName == null ? "the wrapping key" : protectingName)
                    + " key: the wrapping key has to be at least as strong as the key it protects";
        }
        if (protectorBits >= protectedBits) {
            return null;
        }
        String name = protectedName == null ? "the key" : protectedName;
        String protector = protectingName == null ? "the wrapping key" : protectingName;
        return "a " + name + " key may not be protected by a " + protector
                + " key: the wrapping key has to be at least as strong as the key it protects";
    }
}
