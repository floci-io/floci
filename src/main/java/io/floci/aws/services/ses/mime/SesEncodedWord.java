package io.floci.aws.services.ses.mime;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A value as JavaMail's {@code MimeUtility} writes it in UTF-8 encoded words: B when at least
 * half its characters are non-ASCII and Q otherwise, halved recursively at the middle until each
 * encoded-word fits JavaMail's limit, and the words joined by a space. AWS echoes a display name
 * in this form in the "User name is missing" message of a v1 raw send, and writes the Subject of a
 * rendered template this way. The two differ only in what a Q word escapes.
 */
public final class SesEncodedWord {

    private static final String CHARSET = "UTF-8";
    private static final int AVAILABLE = 68 - CHARSET.length();
    private static final String WORD_SPECIALS = "=_?\"#$%&'(),.:;<>@[\\]^`{|}~";
    private static final String TEXT_SPECIALS = "=_?";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private SesEncodedWord() {
    }

    public static boolean needsEncoding(String text) {
        return text.chars().anyMatch(SesEncodedWord::isNonAscii);
    }

    /** A display name: a Q word escapes everything that is special in an RFC 822 word. */
    public static String encode(String text) {
        return encode(text, WORD_SPECIALS);
    }

    /** Unstructured header text such as a Subject: a Q word escapes only {@code =}, {@code _} and {@code ?}. */
    public static String encodeText(String text) {
        return encode(text, TEXT_SPECIALS);
    }

    private static String encode(String text, String specials) {
        long nonAscii = text.chars().filter(SesEncodedWord::isNonAscii).count();
        StringBuilder out = new StringBuilder();
        encode(text, nonAscii >= text.length() - nonAscii, true, specials, out);
        return out.toString();
    }

    private static void encode(String text, boolean base64, boolean first, String specials, StringBuilder out) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int length = base64 ? (bytes.length + 2) / 3 * 4 : qLength(bytes, specials);
        if (length > AVAILABLE && text.length() > 1) {
            int split = text.length() / 2;
            if (Character.isHighSurrogate(text.charAt(split - 1))) {
                split--;
            }
            if (split > 0) {
                encode(text.substring(0, split), base64, first, specials, out);
            }
            encode(text.substring(split), base64, false, specials, out);
            return;
        }
        if (!first) {
            out.append(' ');
        }
        out.append("=?").append(CHARSET).append(base64 ? "?B?" : "?Q?");
        if (base64) {
            out.append(Base64.getEncoder().encodeToString(bytes));
        } else {
            appendQ(bytes, specials, out);
        }
        out.append("?=");
    }

    private static int qLength(byte[] bytes, String specials) {
        int length = 0;
        for (byte b : bytes) {
            length += isQEscaped(b & 0xff, specials) ? 3 : 1;
        }
        return length;
    }

    private static void appendQ(byte[] bytes, String specials, StringBuilder out) {
        for (byte b : bytes) {
            int c = b & 0xff;
            if (c == ' ') {
                out.append('_');
            } else if (isQEscaped(c, specials)) {
                out.append('=').append(HEX[c >> 4]).append(HEX[c & 0x0f]);
            } else {
                out.append((char) c);
            }
        }
    }

    private static boolean isQEscaped(int c, String specials) {
        return c < ' ' || c >= 0x7f || specials.indexOf(c) >= 0;
    }

    private static boolean isNonAscii(int c) {
        return c >= 0x7f || (c < ' ' && c != '\r' && c != '\n' && c != '\t');
    }
}
