package io.floci.aws.services.redshift.proxy;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Applies the COPY options that change field content to the bytes of one S3 object before they
 * reach PostgreSQL's {@code COPY ... FROM STDIN}. The object is already fully in memory, so the
 * transform works on a byte array: there are no chunk boundaries to straddle.
 */
final class CopyRecordTransformer {

    private static final String SQLSTATE_INVALID_COPY = "22P04";
    private static final byte NEWLINE = '\n';
    private static final byte CARRIAGE_RETURN = '\r';
    private static final byte BACKSLASH = '\\';
    private static final byte DOUBLE_QUOTE = '"';
    private static final byte SINGLE_QUOTE = '\'';

    /** What a COPY column holds, as far as the date and time formats are concerned. */
    enum ColumnKind {
        DATE, TIMESTAMP, TEXT, CHAR, OTHER
    }

    private record Field(int tokenEnd, byte[] value, boolean quoted) {
    }

    private final CopyStatementParser.CopyTransforms transforms;
    private final boolean csv;
    private final byte delimiter;
    private final byte[] nullMarker;
    private final List<Integer> columnMaxBytes;
    private final List<ColumnKind> columnKinds;
    private final boolean emptyNullString;
    private final List<Integer> columnScales;

    CopyRecordTransformer(CopyStatementParser.S3CopyFrom spec, List<Integer> columnMaxBytes) {
        this(spec, columnMaxBytes, null);
    }

    CopyRecordTransformer(CopyStatementParser.S3CopyFrom spec, List<Integer> columnMaxBytes,
                          List<ColumnKind> columnKinds) {
        this(spec, columnMaxBytes, columnKinds, null);
    }

    CopyRecordTransformer(CopyStatementParser.S3CopyFrom spec, List<Integer> columnMaxBytes,
                          List<ColumnKind> columnKinds, List<Integer> columnScales) {
        this.columnScales = columnScales;
        this.columnKinds = columnKinds;
        this.emptyNullString = spec.nullAs() != null && spec.nullAs().isEmpty();
        this.transforms = spec.transforms();
        this.csv = spec.csv();
        String delimiterText = spec.delimiter() != null ? spec.delimiter() : (spec.csv() ? "," : "|");
        this.delimiter = delimiterText.getBytes(StandardCharsets.UTF_8)[0];
        String marker = spec.nullAs() != null ? spec.nullAs() : (spec.csv() ? "" : "\\N");
        this.nullMarker = marker.getBytes(StandardCharsets.UTF_8);
        this.columnMaxBytes = columnMaxBytes;
    }

    byte[] apply(byte[] input) {
        return apply(input, null);
    }

    byte[] apply(byte[] input, IntConsumer onOutputBytes) {
        byte[] data = transforms.invalidCharReplacement() != null
                ? sanitizeUtf8(input, (byte) transforms.invalidCharReplacement().charValue())
                : input;
        if (!transforms.fieldLevel() && (columnScales == null || columnScales.stream().allMatch(scale -> scale == null))) {
            if (onOutputBytes != null) {
                onOutputBytes.accept(data.length);
            }
            return data;
        }
        if (data.length == 0) {
            if (onOutputBytes != null) {
                onOutputBytes.accept(0);
            }
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 16);
        int position = 0;
        while (position < data.length) {
            position = transformRecord(data, position, out, onOutputBytes);
            if (onOutputBytes != null) {
                onOutputBytes.accept(out.size());
            }
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

    private int transformRecord(byte[] data, int start, ByteArrayOutputStream out, IntConsumer onOutputBytes) {
        if (transforms.ignoreBlankLines()) {
            if (data[start] == NEWLINE) {
                return start + 1;
            }
            if (isCrlfAt(data, start)) {
                return Math.min(start + 2, data.length);
            }
        }
        int position = start;
        int column = 0;
        while (true) {
            Field field = scanField(data, position);
            try {
                writeField(field, column, out);
            } catch (S3CopySimulator.S3TransferException error) {
                throw new S3CopySimulator.S3TransferException(error.sqlState(), error.getMessage(), error,
                        Map.of('c', Integer.toString(column + 1), 'd',
                                new String(field.value(), 0, Math.min(field.value().length, 1024), StandardCharsets.UTF_8)));
            }
            if (onOutputBytes != null) {
                onOutputBytes.accept(out.size());
            }
            position = field.tokenEnd();
            if (position >= data.length) {
                fillMissingColumns(column + 1, out);
                out.write(NEWLINE);
                return data.length;
            }
            if (data[position] == NEWLINE) {
                fillMissingColumns(column + 1, out);
                out.write(NEWLINE);
                return position + 1;
            }
            if (isCrlfAt(data, position)) {
                fillMissingColumns(column + 1, out);
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
                j = textEscapeEnd(data, j);
            } else {
                j++;
            }
        }
        int valueEnd = j;
        if (j < length && data[j] == NEWLINE && j > start && data[j - 1] == CARRIAGE_RETURN) {
            valueEnd = j - 1;
        }
        return new Field(j, Arrays.copyOfRange(data, start, valueEnd), false);
    }

    private static int textEscapeEnd(byte[] data, int position) {
        int next = position + 1;
        if (data[next] >= '0' && data[next] <= '7') {
            int end = next;
            while (end < data.length && end < next + 3 && data[end] >= '0' && data[end] <= '7') {
                end++;
            }
            return end;
        }
        if (data[next] == 'x' && next + 1 < data.length && Character.digit(data[next + 1], 16) >= 0) {
            int end = next + 2;
            if (end < data.length && Character.digit(data[end], 16) >= 0) {
                end++;
            }
            return end;
        }
        return next + 1;
    }

    /** A carriage return that ends the record: followed by a newline or by the end of the data. */
    private static boolean isCrlfAt(byte[] data, int index) {
        return index < data.length && data[index] == CARRIAGE_RETURN
                && (index + 1 == data.length || data[index + 1] == NEWLINE);
    }

    private void writeField(Field field, int column, ByteArrayOutputStream out) {
        byte[] value = field.value();
        boolean isVarchar = columnKinds == null
                || (column < columnKinds.size() && columnKinds.get(column) == ColumnKind.TEXT);
        // A NULL marker that ends in spaces is matched on the raw field, so trimming must leave it alone.
        boolean rawNullMarker = !field.quoted() && Arrays.equals(value, nullMarker);
        if (transforms.trimBlanks() && isVarchar && !rawNullMarker) {
            value = trimTrailingSpaces(value, csv);
        }
        // Trimming a CSV field down to nothing yields an empty string, which an unquoted empty field would turn into NULL.
        boolean trimmedToEmpty = csv && !field.quoted() && value.length == 0 && field.value().length > 0;
        boolean emptyField = !field.quoted() && value.length == 0;
        boolean nonCharacter = columnKinds != null && column < columnKinds.size()
                && columnKinds.get(column) != ColumnKind.TEXT
                && columnKinds.get(column) != ColumnKind.CHAR;
        boolean nulled = (transforms.blanksAsNull() && isBlank(field.value()))
                || (transforms.emptyAsNull() && value.length == 0)
                || (emptyField && nonCharacter);
        if (nulled) {
            out.writeBytes(nullMarker);
            return;
        }
        if (trimmedToEmpty && nullMarker.length == 0) {
            out.writeBytes(new byte[]{DOUBLE_QUOTE, DOUBLE_QUOTE});
            return;
        }
        // A field that already is the NULL marker must not be cut or reformatted: that would turn NULL into data.
        boolean nullMarkerField = !field.quoted() && Arrays.equals(value, nullMarker);
        boolean converted = false;
        boolean decimalConverted = false;
        if (!nullMarkerField) {
            byte[] decimal = convertDecimal(value, column);
            decimalConverted = decimal != value;
            converted = decimalConverted;
            value = decimal;
            byte[] temporal = convertTemporal(value, column);
            if (temporal == null) {
                out.writeBytes(nullMarker);
                return;
            }
            if (temporal != value) {
                converted = true;
                value = temporal;
            }
        }
        byte[] limited = nullMarkerField ? value : truncate(value, column);
        if (decimalConverted && !csv) {
            writeDecimalText(limited, out);
            return;
        }
        boolean needsEscaping = field.quoted() || (converted && containsDelimiter(limited))
                || (decimalConverted && Arrays.equals(limited, nullMarker));
        if (!needsEscaping) {
            out.writeBytes(limited);
        } else if (csv) {
            writeCsvQuoted(limited, out);
        } else {
            out.writeBytes(escapeTextField(limited));
        }
    }

    private byte[] convertDecimal(byte[] value, int column) {
        if (columnScales == null || column >= columnScales.size() || columnScales.get(column) == null || value.length == 0) {
            return value;
        }
        try {
            byte[] decoded = csv ? value : decodeTextEscapes(value);
            BigDecimal decimal = new BigDecimal(trimNumericWhitespace(new String(decoded, StandardCharsets.UTF_8)));
            if (decimal.precision() - (long) decimal.scale() > 1000) {
                throw new ArithmeticException("Numeric value exceeds the supported precision");
            }
            RoundingMode rounding = transforms.roundDecimal() ? RoundingMode.HALF_UP : RoundingMode.DOWN;
            if (decimal.scale() - (long) decimal.precision() > columnScales.get(column) + 1L) {
                decimal = BigDecimal.ZERO;
            }
            return decimal.setScale(columnScales.get(column), rounding).toPlainString().getBytes(StandardCharsets.UTF_8);
        } catch (NumberFormatException ignored) {
            // Let PostgreSQL report invalid numeric text with its normal SQLSTATE and column context.
            return value;
        } catch (ArithmeticException exception) {
            throw new S3CopySimulator.S3TransferException("22003", "COPY numeric value out of range", exception);
        }
    }

    private static String trimNumericWhitespace(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isNumericWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && isNumericWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isNumericWhitespace(char character) {
        return character == ' ' || (character >= '\t' && character <= '\r');
    }

    private void writeDecimalText(byte[] value, ByteArrayOutputStream out) {
        byte[] encoded = encodeDecimalText(value, false);
        out.writeBytes(Arrays.equals(encoded, nullMarker) ? encodeDecimalText(value, true) : encoded);
    }

    private byte[] encodeDecimalText(byte[] value, boolean escapeFirst) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length + 4);
        for (int i = 0; i < value.length; i++) {
            byte b = value[i];
            if (i == 0 && escapeFirst && b == delimiter && b >= '0' && b <= '7') {
                // The default encoding already uses octal here; hex gives the marker a different spelling.
                out.write(BACKSLASH);
                out.write('x');
                out.write(Character.forDigit((b >>> 4) & 15, 16));
                out.write(Character.forDigit(b & 15, 16));
            } else if (b == delimiter || (i == 0 && escapeFirst)) {
                out.write(BACKSLASH);
                // An escaped octal digit consumes following digits, so always emit all three octal digits.
                if ((b >= '0' && b <= '7') || (i == 0 && escapeFirst)) {
                    out.write('0' + ((b >>> 6) & 7));
                    out.write('0' + ((b >>> 3) & 7));
                    out.write('0' + (b & 7));
                } else {
                    out.write(b);
                }
            } else {
                out.write(b);
            }
        }
        return out.toByteArray();
    }

    private static byte[] trimTrailingSpaces(byte[] value, boolean csv) {
        int end = value.length;
        while (end > 0) {
            if (value[end - 1] != ' ' && value[end - 1] != '\t') {
                break;
            }
            if (!csv) {
                int backslashes = 0;
                for (int k = end - 2; k >= 0 && value[k] == BACKSLASH; k--) {
                    backslashes++;
                }
                if ((backslashes % 2) != 0) {
                    break;
                }
            }
            end--;
        }
        return end == value.length ? value : Arrays.copyOf(value, end);
    }

    private boolean containsDelimiter(byte[] value) {
        for (byte b : value) {
            if (b == delimiter) {
                return true;
            }
        }
        return false;
    }

    /**
     * FILLRECORD: a record that stops short of the last columns gets the rest of them. A character column is
     * filled with a zero-length string and any other column with NULL; EMPTYASNULL and {@code NULL AS ''} make an
     * empty string NULL, so they NULL the character columns too. CSV needs the empty string quoted.
     */
    private void fillMissingColumns(int fieldsWritten, ByteArrayOutputStream out) {
        if (!transforms.fillRecord() || columnKinds == null) {
            return;
        }
        for (int column = fieldsWritten; column < columnKinds.size(); column++) {
            out.write(delimiter);
            boolean emptyString = columnKinds.get(column) == ColumnKind.TEXT
                    && !transforms.emptyAsNull() && !emptyNullString;
            if (!emptyString) {
                out.writeBytes(nullMarker);
            } else if (csv) {
                out.write(DOUBLE_QUOTE);
                out.write(DOUBLE_QUOTE);
            }
        }
    }

    /**
     * Rewrites a DATE or TIMESTAMP field from the DATEFORMAT or TIMEFORMAT to the ISO text PostgreSQL reads.
     * Returns the field as it is when no format applies to its column, and null when ACCEPTANYDATE turns a
     * value that does not match into NULL; otherwise it is a record error counted by MAXERROR.
     */
    private byte[] convertTemporal(byte[] value, int column) {
        if (!transforms.temporal() || columnKinds == null || column >= columnKinds.size()) {
            return value;
        }
        ColumnKind kind = columnKinds.get(column);
        CopyTemporalFormat format = switch (kind) {
            case DATE -> transforms.dateFormat();
            case TIMESTAMP -> transforms.timeFormat();
            case TEXT, CHAR, OTHER -> null;
        };
        if (format == null || !format.converts()) {
            return value;
        }
        String text = new String(value, StandardCharsets.UTF_8);
        try {
            String iso = kind == ColumnKind.DATE ? format.toDate(text) : format.toTimestamp(text);
            return iso.getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            if (transforms.acceptAnyDate()) {
                return null;
            }
            throw new S3CopySimulator.S3TransferException("22007",
                    "COPY invalid " + (kind == ColumnKind.DATE ? "date" : "timestamp") + " in column "
                            + (column + 1) + ": " + e.getMessage(), e);
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
        return new S3CopySimulator.S3TransferException(SQLSTATE_INVALID_COPY, "COPY failed: " + detail, null);
    }
}
