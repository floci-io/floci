package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Writes items as the Ion text of an ION export. AWS prints them with the standard text writer of
 * ion-java, so this follows its quoting, escapes and decimal forms.
 */
final class DynamoDbIonText {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    /** Ion reads these as symbol ids, so they are quoted like the keywords. */
    private static final Pattern SYMBOL_ID = Pattern.compile("\\$[0-9]+");
    private static final Set<String> KEYWORDS = Set.of("null", "true", "false", "nan");
    private static final List<String> RECORD_PARTS = List.of("Keys", "Metadata", "OldImage", "NewImage");

    private DynamoDbIonText() {}

    /** One line of a full export, without its newline. */
    static String exportLine(JsonNode item) {
        StringBuilder out = new StringBuilder("$ion_1_0 {Item:");
        appendStruct(out, item);
        return out.append('}').toString();
    }

    /** One line of an incremental export, without its newline. AWS puts Keys before Metadata here. */
    static String recordLine(JsonNode record) {
        StringBuilder out = new StringBuilder("$ion_1_0 {Record:{");
        String separator = "";
        for (String part : RECORD_PARTS) {
            if (record.has(part)) {
                out.append(separator).append(part).append(':');
                separator = ",";
                appendStruct(out, record.get(part));
            }
        }
        return out.append("}}").toString();
    }

    private static void appendStruct(StringBuilder out, JsonNode attributes) {
        out.append('{');
        String separator = "";
        for (Map.Entry<String, JsonNode> attribute : attributes.properties()) {
            out.append(separator);
            separator = ",";
            appendFieldName(out, attribute.getKey());
            out.append(':');
            appendValue(out, attribute.getValue());
        }
        out.append('}');
    }

    private static void appendValue(StringBuilder out, JsonNode value) {
        Map.Entry<String, JsonNode> typed = value.properties().iterator().next();
        JsonNode content = typed.getValue();
        switch (typed.getKey()) {
            case "S" -> appendString(out, content);
            case "N" -> appendDecimal(out, content);
            case "B" -> appendBlob(out, content);
            case "BOOL" -> out.append(content.asBoolean());
            case "NULL" -> out.append("null");
            case "SS" -> appendList(out.append("$dynamodb_SS::"), content, DynamoDbIonText::appendString);
            case "NS" -> appendList(out.append("$dynamodb_NS::"), content, DynamoDbIonText::appendDecimal);
            case "BS" -> appendList(out.append("$dynamodb_BS::"), content, DynamoDbIonText::appendBlob);
            case "L" -> appendList(out, content, DynamoDbIonText::appendValue);
            case "M" -> appendStruct(out, content);
            default -> throw new IllegalArgumentException("Unknown attribute type: " + typed.getKey());
        }
    }

    private static void appendList(StringBuilder out, JsonNode members, BiConsumer<StringBuilder, JsonNode> appendMember) {
        out.append('[');
        String separator = "";
        for (JsonNode member : members) {
            out.append(separator);
            separator = ",";
            appendMember.accept(out, member);
        }
        out.append(']');
    }

    private static void appendFieldName(StringBuilder out, String name) {
        if (IDENTIFIER.matcher(name).matches() && !KEYWORDS.contains(name) && !SYMBOL_ID.matcher(name).matches()) {
            out.append(name);
        } else {
            out.append('\'');
            appendEscaped(out, name, '\'');
            out.append('\'');
        }
    }

    private static void appendString(StringBuilder out, JsonNode text) {
        out.append('"');
        appendEscaped(out, text.asText(), '"');
        out.append('"');
    }

    private static void appendEscaped(StringBuilder out, String text, char quote) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case 0 -> out.append("\\0");
                case 7 -> out.append("\\a");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case 0x0B -> out.append("\\v");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c == quote) {
                        out.append('\\').append(c);
                    } else if (c < 0x20 || (0x7F <= c && c <= 0x9F)) {
                        out.append("\\x").append(HexFormat.of().toHexDigits((byte) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
    }

    private static void appendDecimal(StringBuilder out, JsonNode number) {
        BigDecimal value = new BigDecimal(number.asText()).stripTrailingZeros();
        if (value.signum() < 0) {
            out.append('-');
        }
        String digits = value.unscaledValue().abs().toString();
        int scale = value.scale();
        if (scale < 0) {
            out.append(digits).append('d').append(-scale);
        } else if (scale == 0) {
            out.append(digits).append('.');
        } else if (scale < digits.length()) {
            int whole = digits.length() - scale;
            out.append(digits, 0, whole).append('.').append(digits, whole, digits.length());
        } else if (scale <= digits.length() + 5) {
            out.append("0.").append("0".repeat(scale - digits.length())).append(digits);
        } else {
            out.append(digits).append("d-").append(scale);
        }
    }

    private static void appendBlob(StringBuilder out, JsonNode base64) {
        out.append("{{").append(base64.asText()).append("}}");
    }
}
