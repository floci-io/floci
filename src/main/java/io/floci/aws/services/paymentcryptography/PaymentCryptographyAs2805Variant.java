package io.floci.aws.services.paymentcryptography;

/**
 * The AS2805 key variants, and the key usage each one carries.
 *
 * <p>AS2805 - Australian Standard 2805 - protects keys by variant rather than
 * by a TR-31 key block. Each variant is a single purpose, so a key brought in
 * under one can be used for nothing else. The service maps them onto its own
 * key usages:
 *
 * <pre>
 *   TERMINAL_MAJOR_KEY_VARIANT_00      TR31_K0_KEY_ENCRYPTION_KEY
 *   PIN_ENCRYPTION_KEY_VARIANT_28      TR31_P0_PIN_ENCRYPTION_KEY
 *   MESSAGE_AUTHENTICATION_KEY_VARIANT_24  TR31_M0_ISO_16609_MAC_KEY
 *   DATA_ENCRYPTION_KEY_VARIANT_22     TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY
 * </pre>
 *
 * <p>AS2805 binds the purpose into the key material as well: the variant is
 * exclusive-ored into the key before it is encrypted, so a key that was
 * wrapped for one purpose does not open under another.
 */
public enum PaymentCryptographyAs2805Variant {

    /** The key encryption key. AS2805 calls it the terminal major key. */
    TERMINAL_MAJOR_KEY_VARIANT_00("TR31_K0_KEY_ENCRYPTION_KEY", 0x00),
    /** The data encryption key. */
    DATA_ENCRYPTION_KEY_VARIANT_22("TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY", 0x16),
    /** The message authentication key. */
    MESSAGE_AUTHENTICATION_KEY_VARIANT_24("TR31_M0_ISO_16609_MAC_KEY", 0x18),
    /** The PIN encryption key. */
    PIN_ENCRYPTION_KEY_VARIANT_28("TR31_P0_PIN_ENCRYPTION_KEY", 0x1C);

    private final String usage;
    private final int variantByte;

    PaymentCryptographyAs2805Variant(String usage, int variantByte) {
        this.usage = usage;
        this.variantByte = variantByte;
    }

    /** The key usage the service gives a key imported under this variant. */
    public String usage() {
        return usage;
    }

    /**
     * The byte exclusive-ored into the key before it is encrypted.
     *
     * <p>This is the purpose binding: the same key material under a different
     * variant encrypts to a different cryptogram, and unwrapping it under the
     * wrong variant gives a key that is wrong in its first byte.
     */
    public int variantByte() {
        return variantByte;
    }

    public static PaymentCryptographyAs2805Variant from(String name) {
        if (name == null) {
            return null;
        }
        for (PaymentCryptographyAs2805Variant v : values()) {
            if (v.name().equals(name)) {
                return v;
            }
        }
        return null;
    }
}
