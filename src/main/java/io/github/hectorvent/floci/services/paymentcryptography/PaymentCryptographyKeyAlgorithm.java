package io.github.hectorvent.floci.services.paymentcryptography;

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

    TDES_2KEY(16, false),
    TDES_3KEY(24, false),
    AES_128(16, true),
    AES_192(24, true),
    AES_256(32, true);

    /** How many bytes of material the key holds. */
    final int bytes;

    /** Whether the key is an AES key rather than a TDES one. */
    final boolean aes;

    PaymentCryptographyKeyAlgorithm(int bytes, boolean aes) {
        this.bytes = bytes;
        this.aes = aes;
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
            if (algorithm.aes != aes) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(algorithm.name());
        }
        return sb.toString();
    }
}
