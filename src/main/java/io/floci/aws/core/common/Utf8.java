package io.floci.aws.core.common;

/** Helpers for UTF-8 byte-budgeted strings. */
public final class Utf8 {

    private Utf8() {
    }

    /** The longest prefix of {@code text} that is at most {@code maxBytes} in UTF-8, cut on a code point boundary. */
    public static String truncate(String text, int maxBytes) {
        int bytes = 0;
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            int size = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            if (bytes + size > maxBytes) {
                break;
            }
            bytes += size;
            i += Character.charCount(codePoint);
        }
        return text.substring(0, i);
    }
}
