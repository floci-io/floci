package io.github.hectorvent.floci.services.paymentcryptography;

import java.util.List;

/**
 * The key algorithms this service makes, with each one's key length.
 *
 * <p>The length is what tells the two families apart: a TDES key is 16 or 24 bytes and an
 * AES key is 16, 24 or 32 bytes. The PIN block formats need the family, because a format 0
 * or 3 block is a TDES block and a format 4 block is an AES one.
 *
 * <p>A real enum rather than a list of names, so the service, the check value and the
 * operations all read the same definition.
 */
enum PaymentCryptographyKeyAlgorithm {

    TDES_2KEY(16, false, false, false),
    TDES_3KEY(24, false, false, false),
    AES_128(16, true, false, false),
    AES_192(24, true, false, false),
    AES_256(32, true, false, false),
    /**
     * An EC key agreement key on NIST P-256, the curve the cardholder-device flow uses.
     * The length is the size of the curve, not of a key block.
     */
    ECC_NIST_P256(32, false, true, false),
    /** An EC key agreement key on NIST P-384. */
    ECC_NIST_P384(48, false, true, false),
    /** An EC key agreement key on NIST P-521. */
    ECC_NIST_P521(66, false, true, false),
    /**
     * An RSA key pair with a 2048-bit modulus.
     *
     * <p>A TR-34 exchange needs one: the authority that certifies the signing key is an RSA
     * certificate.
     */
    RSA_2048(256, false, false, true),
    /** An RSA key pair with a 3072-bit modulus. */
    RSA_3072(384, false, false, true),
    /** An RSA key pair with a 4096-bit modulus. */
    RSA_4096(512, false, false, true);

    /** How many bytes of material the key holds. */
    final int bytes;

    /** Whether the key is an AES key rather than a TDES one. */
    final boolean aes;

    /** Whether the key is an EC key, which has a key pair rather than key material. */
    final boolean ecc;

    /** Whether the key is an RSA key, which has a key pair and no symmetric material. */
    final boolean rsa;

    PaymentCryptographyKeyAlgorithm(int bytes, boolean aes, boolean ecc, boolean rsa) {
        this.bytes = bytes;
        this.aes = aes;
        this.ecc = ecc;
        this.rsa = rsa;
    }

    /** Whether the key holds material a symmetric cipher can use. */
    boolean symmetric() {
        return !ecc && !rsa;
    }

    /**
     * The kind of check value a key of this algorithm carries.
     *
     * <p>An AES key's check value is a CMAC, and a TDES key's is the ANSI X9.24 one. An
     * asymmetric key has no material a check value could be computed from, so it reports the
     * fixed placeholder and the same kind as a TDES key.
     *
     * <p>The service and the request that asks for a key agree on this, so a request is
     * never refused for a kind the service would have answered with.
     */
    String checkValueAlgorithm() {
        return aes ? "CMAC" : "ANSI_X9_24";
    }

    /** The generated key length in bytes. */
    int bytes() {
        return bytes;
    }

    /** Whether the key is an AES key rather than a TDES one. */
    boolean isAes() {
        return aes;
    }

    /** Whether the key is an EC key, which has a key pair rather than key material. */
    boolean isEcc() {
        return ecc;
    }

    /** Whether the key is an RSA key, which has a key pair and no symmetric material. */
    boolean isRsa() {
        return rsa;
    }

    /** The algorithm of that name, or null when the service does not make it. */
    static PaymentCryptographyKeyAlgorithm from(String name) {
        if (name == null) {
            return null;
        }
        for (PaymentCryptographyKeyAlgorithm algorithm : values()) {
            if (algorithm.name().equals(name)) {
                return algorithm;
            }
        }
        return null;
    }

    /** The algorithms of that family, for a message that names the family. */
    static String supportedOf(boolean aes) {
        StringBuilder sb = new StringBuilder();
        for (PaymentCryptographyKeyAlgorithm algorithm : values()) {
            if (algorithm.aes != aes || !algorithm.symmetric()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(algorithm.name());
        }
        return sb.toString();
    }

    /** Every algorithm the service makes, for a message that names them all. */
    static String supportedAll() {
        StringBuilder sb = new StringBuilder();
        for (PaymentCryptographyKeyAlgorithm algorithm : values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(algorithm.name());
        }
        return sb.toString();
    }

    /** The usages a key pair is made of, and the symmetric ones are the rest. */
    private static final List<String> ASYMMETRIC_USAGES = List.of(
            "TR31_D1_ASYMMETRIC_KEY_FOR_DATA_ENCRYPTION",
            "TR31_K2_TR34_ASYMMETRIC_KEY",
            "TR31_K3_ASYMMETRIC_KEY_FOR_KEY_AGREEMENT",
            "TR31_S0_ASYMMETRIC_KEY_FOR_DIGITAL_SIGNATURE");

    /**
     * The algorithms a key of a usage may be made of.
     *
     * <p>The service refuses a key whose algorithm its usage does not allow, because the usage
     * is what binds the key to its purpose, and a MAC key made of AES is a key whose purpose
     * its own algorithm does not serve.
     *
     * @param usage the TR-31 usage name of the key
     * @return the algorithms the usage allows
     */
    static List<PaymentCryptographyKeyAlgorithm> allowedOf(String usage) {
        if (ASYMMETRIC_USAGES.contains(usage)) {
            // The asymmetric usages are the ones a key pair is made of: the data
            // encryption key, the key agreement key, the TR-34 key and the signing key.
            return List.of(RSA_2048, RSA_3072, RSA_4096, ECC_NIST_P256, ECC_NIST_P384,
                    ECC_NIST_P521);
        }
        if ("TR31_M1_ISO_9797_1_MAC_KEY".equals(usage)
                || "TR31_M3_ISO_9797_3_MAC_KEY".equals(usage)
                || "TR31_M0_ISO_16609_MAC_KEY".equals(usage)) {
            // The ISO 9797 MACs are defined over Triple DES.
            return List.of(TDES_2KEY, TDES_3KEY);
        }
        // Every other usage takes every symmetric algorithm.
        return List.of(TDES_2KEY, TDES_3KEY, AES_128, AES_192, AES_256);
    }

    /**
     * The reason an algorithm is refused for a usage, in words a caller can act on.
     *
     * <p>An algorithm is refused for a usage the usage does not allow, whether it is symmetric
     * or not: a key pair has no material a symmetric usage would bind it to, and a symmetric
     * key has no pair a key pair's usage would bind it to. A key that claims a purpose its own
     * material cannot serve is not a key the service makes.
     *
     * @param usage the TR-31 usage name of the key
     * @param algorithm the algorithm of the key
     * @return the message, or null when the combination is allowed
     */
    static String refusal(String usage, PaymentCryptographyKeyAlgorithm algorithm) {
        if (algorithm == null) {
            return null;
        }
        if (allowedOf(usage).contains(algorithm)) {
            return null;
        }
        return "a key of the usage " + usage + " may not be made of " + algorithm.name()
                + ": the usage allows " + allowedOf(usage);
    }
}
