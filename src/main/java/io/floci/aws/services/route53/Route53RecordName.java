package io.floci.aws.services.route53;

import java.util.Locale;

/** Shared DNS spelling for record identity and IAM condition keys. */
public final class Route53RecordName {

    private Route53RecordName() {}

    public static String conditionName(String name) {
        return normalize(name, true);
    }

    public static String recordName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return normalize(name, false) + ".";
    }

    // Route 53 Developer Guide: specifying-conditions-route53.html and DomainNameFormat.html.
    private static String normalize(String name, boolean condition) {
        String source = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < source.length(); index++) {
            char value = source.charAt(index);
            boolean escaped = value == '\\' && index + 3 < source.length()
                    && source.substring(index + 1, index + 4).chars().allMatch(c -> c >= '0' && c <= '7');
            if (escaped) {
                value = (char) Integer.parseInt(source.substring(index + 1, index + 4), 8);
                index += 3;
            }
            if (value >= 'A' && value <= 'Z') {
                value = (char) (value + ('a' - 'A'));
            }
            if (value >= 'a' && value <= 'z' || value >= '0' && value <= '9' || value == '-'
                    || value == '_' || value == '*' && !condition || value == '.' && !escaped) {
                normalized.append(value);
            } else {
                normalized.append(String.format(Locale.ROOT, "\\%03o", (int) value));
            }
        }
        return normalized.toString();
    }
}
