package io.floci.aws.services.route53;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A DNS name as Route 53 spells it (Route 53 Developer Guide, "DNS domain name format"): letters lowercase however
 * they were given, as letters or as escape codes, and every byte other than a to z, 0 to 9, hyphen, underscore and
 * the dot between labels as a three-digit octal escape code. A record's identity and the record names IAM reads as
 * condition values both take this spelling, so a name given in escape codes names the record its letters name.
 */
public final class Route53RecordName {

    private Route53RecordName() {}

    /**
     * The name as {@code route53:ChangeResourceRecordSetsNormalizedRecordNames} carries it (Route 53 Developer Guide,
     * "Normalizing the Route 53 condition key values"): without the final dot, and with {@code *} as {@code \052}.
     */
    public static String conditionName(String name) {
        return format(name, true);
    }

    /**
     * The name a record is stored, matched and listed under: with the final dot and every byte outside
     * {@code a-z}, {@code 0-9}, {@code -}, {@code _} and unescaped {@code .} written as a three-digit octal escape
     * (including {@code *} as {@code \052}), as Route 53's API returns it.
     */
    public static String recordName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return format(name, true) + ".";
    }

    /**
     * Decoded DNS name with trailing dot and lowercased ASCII letters, used for ordering and
     * {@code startRecordName} comparisons so octal-escaped bytes (such as {@code \052} for {@code *})
     * sort by their underlying byte value rather than the leading backslash.
     */
    public static String sortKey(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return format(name, false) + ".";
    }

    private static String format(String name, boolean escape) {
        String source = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length);
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xFF;
            boolean escaped = value == '\\' && isEscapeCode(bytes, index + 1);
            if (escaped) {
                value = (bytes[index + 1] - '0') * 64 + (bytes[index + 2] - '0') * 8 + (bytes[index + 3] - '0');
                index += 3;
            }
            if (value >= 'A' && value <= 'Z') {
                value += 'a' - 'A';
            }
            if (!escape || value >= 'a' && value <= 'z' || value >= '0' && value <= '9'
                    || value == '-' || value == '_' || value == '.' && !escaped) {
                out.append((char) value);
            } else {
                out.append(String.format(Locale.ROOT, "\\%03o", value));
            }
        }
        return out.toString();
    }

    /** Whether the three bytes from {@code start} are the octal digits of a byte, {@code 000} to {@code 377}. */
    private static boolean isEscapeCode(byte[] bytes, int start) {
        return start + 2 < bytes.length && bytes[start] >= '0' && bytes[start] <= '3'
                && bytes[start + 1] >= '0' && bytes[start + 1] <= '7'
                && bytes[start + 2] >= '0' && bytes[start + 2] <= '7';
    }
}
