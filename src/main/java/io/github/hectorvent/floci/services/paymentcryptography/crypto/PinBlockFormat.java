package io.github.hectorvent.floci.services.paymentcryptography.crypto;

import java.util.ArrayList;
import java.util.List;

/**
 * The ISO 9564-1 PIN block formats this emulator implements.
 *
 * <p>Each format fixes three things at once: the length of the encrypted
 * block, the cipher that protects it, and the shape of the PAN field it binds
 * the PIN to. Getting all three from one place is the point - a format and a
 * cipher that disagree cannot produce a block, and the block length is the
 * cheapest way to catch that at the edge of the service.
 *
 * <ul>
 *   <li>Formats 0 and 3 are 8-byte TDES blocks. The clear PIN field is XORed
 *       with the 12-digit account field and the result is enciphered once.
 *       Format 3 differs from format 0 only in its fill nibbles, which are
 *       random values in the range A to F rather than a constant F.
 *   <li>Format 4 is a 16-byte AES block, the only format that can be
 *       protected by an AES key. It is built from two 16-byte fields, the PIN
 *       field and the PAN field, and enciphered twice.
 * </ul>
 */
public enum PinBlockFormat {

    /**
     * Format 0: the ANSI X9.8 block, the same as VISA-1 and ECI-1.
     */
    ISO_FORMAT_0(8, false),

    /**
     * Format 3: format 0 with random fill nibbles instead of {@code F}, so the
     * same PIN never enciphers to the same ciphertext twice.
     */
    ISO_FORMAT_3(8, false),

    /**
     * Format 4: the AES block, and the only one that an AES PEK can protect.
     */
    ISO_FORMAT_4(16, true);

    private final int blockBytes;
    private final boolean aes;

    PinBlockFormat(int blockBytes, boolean aes) {
        this.blockBytes = blockBytes;
        this.aes = aes;
    }

    /** The length of the encrypted block, in bytes. */
    public int blockBytes() {
        return blockBytes;
    }

    /** The length of the encrypted block, in hex characters. */
    public int blockHexLength() {
        return blockBytes * 2;
    }

    /** True when the format is protected by AES rather than TDES. */
    public boolean isAes() {
        return aes;
    }

    /** The short name the service uses for the format, as in {@code ISO-4}. */
    public String label() {
        return "ISO-" + suffix();
    }

    /**
     * The name of the translation-attribute member for this format, as in
     * {@code IsoFormat4}.
     */
    public String translationMember() {
        return "IsoFormat" + suffix();
    }

    /** The part of the wire name that carries the format number. */
    private String suffix() {
        return name().substring("ISO_FORMAT_".length());
    }

    /** The format for a wire name such as {@code ISO_FORMAT_4}, or null. */
    public static PinBlockFormat from(String wireName) {
        if (wireName == null) {
            return null;
        }
        for (PinBlockFormat f : values()) {
            if (f.name().equals(wireName)) {
                return f;
            }
        }
        return null;
    }

    /** The format for a translation member name such as {@code IsoFormat4}, or null. */
    public static PinBlockFormat fromTranslationMember(String member) {
        if (member == null) {
            return null;
        }
        for (PinBlockFormat f : values()) {
            if (f.translationMember().equals(member)) {
                return f;
            }
        }
        return null;
    }

    /** The supported formats as a comma-separated list, for error messages. */
    public static String supported() {
        List<String> names = new ArrayList<String>();
        for (PinBlockFormat f : values()) {
            names.add(f.name());
        }
        return String.join(", ", names);
    }
}
