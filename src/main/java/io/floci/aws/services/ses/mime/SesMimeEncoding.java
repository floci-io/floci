package io.floci.aws.services.ses.mime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Header and body encodings of the MIME message {@code TestRenderTemplate} returns. SES writes it
 * the way JavaMail does. The rules here were observed on us-west-2 (2026-11-17), thresholds
 * included, except two that follow JavaMail unobserved: a bare CR inside a quoted-printable body,
 * and the escaping of {@code _} and {@code ?} in a Q word.
 *
 * <p>A body is {@code 7bit} when every octet is printable US-ASCII, tab, CR or LF. It is
 * {@code quoted-printable} when those octets outnumber the others (a control character counts
 * with the non-ASCII ones) or when an all-ASCII line runs past 998 octets, and {@code base64}
 * otherwise, a tie included. The Subject is left alone when all ASCII and written as encoded
 * words otherwise, then folded at whitespace either way.
 */
public final class SesMimeEncoding {

    private static final String CRLF = "\r\n";
    private static final int MAX_LINE_OCTETS = 998;
    private static final int HEADER_LINE_LIMIT = 76;
    private static final int QUOTED_PRINTABLE_LINE_LIMIT = 75;
    private static final int BASE64_LINE_LENGTH = 76;
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private SesMimeEncoding() {
    }

    /** A Content-Transfer-Encoding together with the encoder it names. */
    public enum TransferEncoding {
        SEVEN_BIT("7bit") {
            @Override
            public String encode(byte[] octets) {
                return new String(octets, StandardCharsets.UTF_8);
            }
        },
        QUOTED_PRINTABLE("quoted-printable") {
            @Override
            public String encode(byte[] octets) {
                return quotedPrintable(octets);
            }
        },
        BASE64("base64") {
            @Override
            public String encode(byte[] octets) {
                return base64Lines(octets);
            }
        };

        private final String headerValue;

        TransferEncoding(String headerValue) {
            this.headerValue = headerValue;
        }

        public String headerValue() {
            return headerValue;
        }

        public abstract String encode(byte[] octets);

        public static TransferEncoding of(byte[] octets) {
            int ascii = 0;
            int nonAscii = 0;
            int lineLength = 0;
            boolean longLine = false;
            for (byte octet : octets) {
                int value = octet & 0xFF;
                if (value == '\r' || value == '\n') {
                    lineLength = 0;
                } else {
                    lineLength++;
                    longLine |= lineLength > MAX_LINE_OCTETS;
                }
                if (value >= 0x7F || (value < 0x20 && value != '\r' && value != '\n' && value != '\t')) {
                    nonAscii++;
                } else {
                    ascii++;
                }
            }
            if (nonAscii == 0) {
                return longLine ? QUOTED_PRINTABLE : SEVEN_BIT;
            }
            return ascii > nonAscii ? QUOTED_PRINTABLE : BASE64;
        }
    }

    /**
     * Quoted-printable with soft line breaks: a space is escaped only at the end of a line, a bare
     * CR or LF becomes CRLF, and a line holds 75 characters before its {@code =} soft break.
     */
    static String quotedPrintable(byte[] octets) {
        QuotedPrintableWriter writer = new QuotedPrintableWriter();
        for (byte octet : octets) {
            writer.write(octet & 0xFF);
        }
        return writer.finish();
    }

    /**
     * Base64 in 76-character lines. Every full line ends in CRLF, the last one included, which
     * {@link Base64#getMimeEncoder()} leaves out: a body that fills its last line is followed by a
     * blank line in the message.
     */
    static String base64Lines(byte[] octets) {
        String encoded = Base64.getEncoder().encodeToString(octets);
        StringBuilder out = new StringBuilder(encoded.length() + encoded.length() / BASE64_LINE_LENGTH * 2);
        for (int start = 0; start < encoded.length(); start += BASE64_LINE_LENGTH) {
            int end = Math.min(start + BASE64_LINE_LENGTH, encoded.length());
            out.append(encoded, start, end);
            if (end - start == BASE64_LINE_LENGTH) {
                out.append(CRLF);
            }
        }
        return out.toString();
    }

    /** The value of a Subject header, folded for a line that starts with {@code "Subject: "}. */
    public static String encodeSubject(String subject) {
        String value = SesEncodedWord.needsEncoding(subject) ? SesEncodedWord.encodeText(subject) : subject;
        return fold("Subject: ".length(), value);
    }

    /**
     * Folds a header value at whitespace so that no line passes 76 characters where a break is
     * possible. Encoded words are separated by one space, so short neighbours share a line.
     */
    private static String fold(int used, String value) {
        int end = value.length();
        while (end > 0 && isBlank(value.charAt(end - 1))) {
            end--;
        }
        if (used + end <= HEADER_LINE_LIMIT) {
            return value.substring(0, end);
        }
        StringBuilder out = new StringBuilder(end + 4);
        int start = 0;
        int column = used;
        char previous = 0;
        while (column + end - start > HEADER_LINE_LIMIT) {
            int lastSpace = -1;
            for (int i = start; i < end; i++) {
                if (lastSpace != -1 && column + i - start > HEADER_LINE_LIMIT) {
                    break;
                }
                char c = value.charAt(i);
                if (isBlank(c) && !isBlank(previous)) {
                    lastSpace = i;
                }
                previous = c;
            }
            if (lastSpace == -1) {
                break;
            }
            previous = value.charAt(lastSpace);
            out.append(value, start, lastSpace).append(CRLF).append(previous);
            start = lastSpace + 1;
            column = 1;
        }
        return out.append(value, start, end).toString();
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t';
    }

    private static final class QuotedPrintableWriter {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int column;
        private boolean pendingSpace;
        private boolean afterCarriageReturn;

        void write(int value) {
            if (pendingSpace) {
                emit(' ', value == '\r' || value == '\n');
                pendingSpace = false;
            }
            if (value == '\r') {
                afterCarriageReturn = true;
                lineBreak();
                return;
            }
            if (value == '\n') {
                if (!afterCarriageReturn) {
                    lineBreak();
                }
            } else if (value == ' ') {
                pendingSpace = true;
            } else {
                emit(value, value < 0x20 || value >= 0x7F || value == '=');
            }
            afterCarriageReturn = false;
        }

        String finish() {
            if (pendingSpace) {
                emit(' ', true);
                pendingSpace = false;
            }
            return out.toString(StandardCharsets.US_ASCII);
        }

        private void emit(int value, boolean escape) {
            int width = escape ? 3 : 1;
            column += width;
            if (column > QUOTED_PRINTABLE_LINE_LIMIT) {
                out.write('=');
                out.write('\r');
                out.write('\n');
                column = width;
            }
            if (escape) {
                out.write('=');
                out.writeBytes(HEX.toHexDigits((byte) value).getBytes(StandardCharsets.US_ASCII));
            } else {
                out.write(value);
            }
        }

        private void lineBreak() {
            out.write('\r');
            out.write('\n');
            column = 0;
        }
    }
}
