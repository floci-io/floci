package io.floci.aws.services.paymentcryptography.crypto;

import java.util.ArrayList;
import java.util.List;

/**
 * The ISO 9564-1 PIN block formats the service makes.
 *
 * <p>Three of the four formats the API names are made: the format 0 and the format 3 are
 * eight-byte Triple DES blocks, and the format 4 is a sixteen-byte AES block. The format 1
 * the API also names is a format ISO 9564-1 defines, a block that carries no PAN, but this
 * service does not make it, so a request that asks for one is refused rather than answered
 * with a block of some other format.
 */
public enum PinBlockFormat {

    /** The eight-byte Triple DES block that fills with F. */
    ISO_FORMAT_0(8, false),

    /** The eight-byte Triple DES block that fills with random nibbles. */
    ISO_FORMAT_3(8, false),

    /** The sixteen-byte AES block that binds the PIN to the whole account number. */
    ISO_FORMAT_4(16, true);

    /** The block the format is, in bytes. */
    private final int blockBytes;

    /** Whether the format is protected by an AES key rather than a Triple DES one. */
    private final boolean aes;

    PinBlockFormat(int blockBytes, boolean aes) {
        this.blockBytes = blockBytes;
        this.aes = aes;
    }

    /** The block the format is, in bytes. */
    public int blockBytes() {
        return blockBytes;
    }

    /** The block the format is, in hexadecimal characters. */
    public int blockHexLength() {
        return blockBytes * 2;
    }

    /** Whether the format is protected by an AES key rather than a Triple DES one. */
    public boolean isAes() {
        return aes;
    }

    /** How the format is named in a message. */
    public String label() {
        return "ISO-" + suffix();
    }

    /** How the format is named in the translation attributes. */
    public String translationMember() {
        return "IsoFormat" + suffix();
    }

    private String suffix() {
        return name().substring("ISO_FORMAT_".length());
    }

    /**
     * The format a name in {@code PinBlockFormat} names.
     *
     * @param wireName the name the request carries
     * @return the format, or null when the name is one this service does not make
     */
    public static PinBlockFormat from(String wireName) {
        if (wireName == null) {
            return null;
        }
        for (PinBlockFormat format : values()) {
            if (format.name().equals(wireName)) {
                return format;
            }
        }
        return null;
    }

    /**
     * The format a name in the translation attributes names.
     *
     * @param member the name the request carries
     * @return the format, or null when the name is one this service does not make
     */
    public static PinBlockFormat fromTranslationMember(String member) {
        if (member == null) {
            return null;
        }
        for (PinBlockFormat format : values()) {
            if (format.translationMember().equals(member)) {
                return format;
            }
        }
        return null;
    }

    /** The formats this service makes, for a message. */
    public static String supported() {
        List<String> names = new ArrayList<>();
        for (PinBlockFormat format : values()) {
            names.add(format.name());
        }
        return String.join(", ", names);
    }
}
