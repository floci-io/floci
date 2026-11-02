package io.github.hectorvent.floci.services.paymentcryptography;

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
        if (mayProtect(protecting, protectedKey)) {
            return null;
        }
        String name = protectedKey == null ? "the key" : protectedKey;
        String protector = protecting == null ? "the wrapping key" : protecting;
        return "a " + name + " key may not be protected by a " + protector
                + " key: the wrapping key has to be at least as strong as the key it protects";
    }
}
