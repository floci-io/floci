package io.github.hectorvent.floci.services.redshift.proxy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Applies the COPY options that change field content to the bytes of one S3 object before they
 * reach PostgreSQL's {@code COPY ... FROM STDIN}. The object is already fully in memory, so the
 * transform works on a byte array: there are no chunk boundaries to straddle.
 */
final class CopyRecordTransformer {

    private static final String SQLSTATE_INTERNAL = "XX000";
    private static final byte NEWLINE = '\n';
    private static final byte CARRIAGE_RETURN = '\r';
    private static final byte BACKSLASH = '\\';
    private static final byte DOUBLE_QUOTE = '"';
    private static final byte SINGLE_QUOTE = '\'';

    private record Field(int tokenEnd, byte[] value, boolean quoted) {
    }

    private final CopyStatementParser.CopyTransforms transforms;
    private final boolean csv;
    private final byte delimiter;
    private final byte[] nullMarker;
    private final List<Integer> columnMaxBytes;

    CopyRecordTransformer(CopyStatementParser.S3CopyFrom spec, List<Integer> columnMaxBytes) {
        this.transforms = spec.transforms();
        this.csv = spec.csv();
        String delimiterText = spec.delimiter() != null ? spec.delimiter() : (spec.csv() ? "," : "|");
        this.delimiter = delimiterText.getBytes(StandardCharsets.UTF_8)[0];
        String marker = spec.nullAs() != null ? spec.nullAs() : (spec.csv() ? "" : "\\N");
        this.nullMarker = marker.getBytes(StandardCharsets.UTF_8);
        this.columnMaxBytes = columnMaxBytes;
    }

    byte[] apply(byte[] input) {
        byte[] data = transforms.invalidCharReplacement() != null
                ? sanitizeUtf8(input, (byte) transforms.invalidCharReplacement().charValue())
                : input;
        if (!transforms.fieldLevel()) {
            return data;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 16);
        int position = 0;
        while (position < data.length) {
            position = transformRecord(data, position, out);
        }
        return out.toByteArray();
    }

    static byte[] sanitizeUtf8(byte[] input, byte replacement) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length);
        int i = 0;
        while (i < input.length) {
            int length = validSequenceLength(input, i);
            if (length == 0) {
                out.write(replacement);
                i++;
            } else {
                out.write(input, i, length);
                i += length;
            }
        }
        return out.toByteArray();
    }

    private static int validSequenceLength(byte[] bytes, int index) {
        int lead = bytes[index] & 0xFF;
        if (lead < 0x80) {
            return 1;
        }
        int length;
        if (lead >= 0xC2 && lead <= 0xDF) {
            length = 2;
        } else if (lead >= 0xE0 && lead <= 0xEF) {
            length = 3;
        } else if (lead >= 0xF0 && lead <= 0xF4) {
            length = 4;
        } else {
            return 0;
        }
        if (index + length > bytes.length) {
            return 0;
        }
        for (int k = 1; k < length; k++) {
            if ((bytes[index + k] & 0xC0) != 0x80) {
                return 0;
            }
        }
        int second = bytes[index + 1] & 0xFF;
        boolean overlongOrOutOfRange = (lead == 0xE0 && second < 0xA0)
                || (lead == 0xED && second > 0x9F)
                || (lead == 0xF0 && second < 0x90)
                || (lead == 0xF4 && second > 0x8F);
        return overlongOrOutOfRange ? 0 : length;
    }

    private int transformRecord(byte[] data, int start, ByteArrayOutputStream out) {
        int position = start;
        int column = 0;
        while (true) {
            Field field = scanField(data, position);
            writeField(field, column, out);
            position = field.tokenEnd();
            if (position >= data.length) {
                out.write(NEWLINE);
                return data.length;
            }
            if (data[position] == NEWLINE) {
                out.write(NEWLINE);
                return position + 1;
            }
            if (isCrlfAt(data, position)) {
                out.write(NEWLINE);
                return Math.min(position + 2, data.length);
            }
            if (data[position] != delimiter) {
                throw malformed("unexpected character after closing quote");
            }
            out.write(delimiter);
            position++;
            column++;
        }
    }

    private Field scanField(byte[] data, int start) {
        int length = data.length;
        if (start < length && transforms.removeQuotes()
                && (data[start] == DOUBLE_QUOTE || data[start] == SINGLE_QUOTE)) {
            byte quote = data[start];
            for (int j = start + 1; j < length; j++) {
                if (!csv && data[j] == BACKSLASH) {
                    // Text mode: a backslash escapes the next byte, so an escaped quote never closes the field.
                    j++;
                    continue;
                }
                boolean closes = data[j] == quote
                        && (j + 1 == length || data[j + 1] == delimiter || data[j + 1] == NEWLINE
                                || isCrlfAt(data, j + 1));
                if (closes) {
                    return new Field(j + 1, Arrays.copyOfRange(data, start + 1, j), true);
                }
            }
            throw malformed("quoted field has no closing quote");
        }
        if (start < length && csv && data[start] == DOUBLE_QUOTE) {
            ByteArrayOutputStream value = new ByteArrayOutputStream();
            int j = start + 1;
            while (j < length) {
                if (data[j] == DOUBLE_QUOTE) {
                    if (j + 1 < length && data[j + 1] == DOUBLE_QUOTE) {
                        value.write(DOUBLE_QUOTE);
                        j += 2;
                        continue;
                    }
                    return new Field(j + 1, value.toByteArray(), true);
                }
                value.write(data[j]);
                j++;
            }
            throw malformed("quoted field has no closing quote");
        }
        int j = start;
        while (j < length && data[j] != delimiter && data[j] != NEWLINE) {
            if (!csv && data[j] == BACKSLASH && j + 1 < length) {
                j++;
            }
            j++;
        }
        int valueEnd = j;
        if (j < length && data[j] == NEWLINE && j > start && data[j - 1] == CARRIAGE_RETURN) {
            valueEnd = j - 1;
        }
        return new Field(j, Arrays.copyOfRange(data, start, valueEnd), false);
    }

    /** A carriage return that ends the record: followed by a newline or by the end of the data. */
    private static boolean isCrlfAt(byte[] data, int index) {
        return index < data.length && data[index] == CARRIAGE_RETURN
                && (index + 1 == data.length || data[index + 1] == NEWLINE);
    }

    private void writeField(Field field, int column, ByteArrayOutputStream out) {
        byte[] value = field.value();
        boolean nulled = (transforms.blanksAsNull() && isBlank(value))
                || (transforms.emptyAsNull() && value.length == 0);
        if (nulled) {
            out.writeBytes(nullMarker);
            return;
        }
        // A field that already is the NULL marker must not be cut: that would turn NULL into data.
        byte[] limited = !field.quoted() && Arrays.equals(value, nullMarker) ? value : truncate(value, column);
        if (!field.quoted()) {
            out.writeBytes(limited);
        } else if (csv) {
            writeCsvQuoted(limited, out);
        } else {
            out.writeBytes(escapeTextField(limited));
        }
    }

    private static boolean isBlank(byte[] value) {
        if (value.length == 0) {
            return false;
        }
        for (byte b : value) {
            if (b != ' ' && b != '\t') {
                return false;
            }
        }
        return true;
    }

    private byte[] truncate(byte[] value, int column) {
        if (!transforms.truncateColumns() || columnMaxBytes == null || column >= columnMaxBytes.size()) {
            return value;
        }
        Integer max = columnMaxBytes.get(column);
        if (max == null) {
            return value;
        }
        // Redshift counts CHAR(n) and VARCHAR(n) in bytes, so measure the decoded bytes and cut at a
        // character boundary. Text mode decodes its escapes first so one is never split.
        byte[] decoded = csv ? value : decodeTextEscapes(value);
        int end = byteLimitBoundary(decoded, max);
        if (end == decoded.length) {
            return value;
        }
        byte[] kept = Arrays.copyOf(decoded, end);
        return csv ? kept : encodeTextEscapes(kept);
    }

    /** Largest offset not above {@code maxBytes} that does not fall inside a multi-byte character. */
    private static int byteLimitBoundary(byte[] bytes, int maxBytes) {
        if (bytes.length <= maxBytes) {
            return bytes.length;
        }
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return end;
    }

    /** Decodes COPY text escapes: backslash b f n r t v, octal NNN, hex xHH, and backslash c for any other c. */
    private static byte[] decodeTextEscapes(byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length);
        int i = 0;
        while (i < value.length) {
            byte b = value[i];
            if (b != BACKSLASH || i + 1 >= value.length) {
                out.write(b);
                i++;
                continue;
            }
            byte next = value[i + 1];
            if (next >= '0' && next <= '7') {
                int octal = 0;
                int digits = 0;
                i++;
                while (i < value.length && digits < 3 && value[i] >= '0' && value[i] <= '7') {
                    octal = octal * 8 + (value[i] - '0');
                    i++;
                    digits++;
                }
                out.write(octal);
            } else if (next == 'x' && i + 2 < value.length && Character.digit(value[i + 2], 16) >= 0) {
                int hex = Character.digit(value[i + 2], 16);
                i += 3;
                if (i < value.length && Character.digit(value[i], 16) >= 0) {
                    hex = hex * 16 + Character.digit(value[i], 16);
                    i++;
                }
                out.write(hex);
            } else {
                out.write(switch (next) {
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'v' -> 0x0B;
                    default -> next;
                });
                i += 2;
            }
        }
        return out.toByteArray();
    }

    private byte[] encodeTextEscapes(byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length + 4);
        for (byte b : value) {
            if (b == BACKSLASH || b == delimiter) {
                out.write(BACKSLASH);
                out.write(b);
            } else if (b == NEWLINE) {
                out.write(BACKSLASH);
                out.write('n');
            } else if (b == CARRIAGE_RETURN) {
                out.write(BACKSLASH);
                out.write('r');
            } else {
                out.write(b);
            }
        }
        return out.toByteArray();
    }

    private byte[] escapeTextField(byte[] value) {
        ByteArrayOutputStream escaped = new ByteArrayOutputStream(value.length + 4);
        for (int i = 0; i < value.length; i++) {
            byte b = value[i];
            if (b == BACKSLASH && i + 1 < value.length) {
                escaped.write(b);
                escaped.write(value[++i]);
            } else if (b == delimiter) {
                escaped.write(BACKSLASH);
                escaped.write(b);
            } else if (b == NEWLINE) {
                escaped.write(BACKSLASH);
                escaped.write('n');
            } else if (b == CARRIAGE_RETURN) {
                escaped.write(BACKSLASH);
                escaped.write('r');
            } else {
                escaped.write(b);
            }
        }
        return escaped.toByteArray();
    }

    private static void writeCsvQuoted(byte[] value, ByteArrayOutputStream out) {
        out.write(DOUBLE_QUOTE);
        for (byte b : value) {
            if (b == DOUBLE_QUOTE) {
                out.write(DOUBLE_QUOTE);
            }
            out.write(b);
        }
        out.write(DOUBLE_QUOTE);
    }

    private static S3CopySimulator.S3TransferException malformed(String detail) {
        return new S3CopySimulator.S3TransferException(SQLSTATE_INTERNAL, "COPY failed: " + detail, null);
    }
}
