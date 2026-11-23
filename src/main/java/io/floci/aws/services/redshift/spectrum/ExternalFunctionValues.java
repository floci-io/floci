package io.floci.aws.services.redshift.spectrum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.Set;

/**
 * Converts the values of a Lambda UDF call between the text PostgreSQL holds them as and the JSON the Lambda
 * function receives and returns. Numbers travel as JSON numbers, booleans as JSON booleans, and character, date
 * and timestamp values as JSON strings; a SQL NULL is a JSON null.
 */
final class ExternalFunctionValues {
    private static final String RESULT_ERROR = "22P02";
    private static final Set<String> NON_FINITE = Set.of("NaN", "Infinity", "-Infinity");
    /** PostgreSQL's text form: seconds always, and a fraction of up to six digits only when there is one. */
    private static final DateTimeFormatter TIMESTAMP_TEXT = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss").appendFraction(ChronoField.NANO_OF_SECOND, 0, 6, true)
            .toFormatter(Locale.ROOT);

    private ExternalFunctionValues() {
    }

    /** The JSON form of an argument, given as the text PostgreSQL printed for a value of the declared type. */
    static JsonNode toJson(String declaredType, String text) {
        if (text == null) {
            return NullNode.getInstance();
        }
        String base = ExternalFunctionTypes.base(declaredType);
        try {
            return switch (base) {
                case "smallint", "integer", "bigint" -> LongNode.valueOf(Long.parseLong(text.trim()));
                case "numeric" -> numeric(text.trim());
                case "real", "double precision" -> floating(text.trim());
                case "boolean" -> BooleanNode.valueOf(booleanText(text));
                default -> TextNode.valueOf(text);
            };
        } catch (IllegalArgumentException e) {
            throw new SpectrumSqlException(RESULT_ERROR, "The argument \"" + text + "\" is not a valid " + base);
        }
    }

    private static JsonNode numeric(String text) {
        // PostgreSQL numeric can hold NaN, which JSON cannot, so it travels as its text like a float's does
        return "NaN".equalsIgnoreCase(text) ? TextNode.valueOf(text) : DecimalNode.valueOf(new BigDecimal(text));
    }

    /**
     * The text of a result value, ready to be read back as the declared type, or null for a JSON null. A value of
     * a JSON type the declared type does not take fails the call, as Redshift does for a mistyped result.
     */
    static String fromJson(String declaredType, JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        String base = ExternalFunctionTypes.base(declaredType);
        return switch (base) {
            case "smallint" -> whole(base, value, Short.MIN_VALUE, Short.MAX_VALUE);
            case "integer" -> whole(base, value, Integer.MIN_VALUE, Integer.MAX_VALUE);
            case "bigint" -> whole(base, value, Long.MIN_VALUE, Long.MAX_VALUE);
            case "numeric" -> number(base, value).decimalValue().toPlainString();
            case "real", "double precision" -> floatResult(base, value);
            case "boolean" -> Boolean.toString(bool(base, value));
            case "date" -> date(text(base, value));
            case "timestamp" -> timestamp(text(base, value));
            default -> text(base, value);
        };
    }

    private static JsonNode floating(String text) {
        double parsed = Double.parseDouble(text);
        // JSON has no NaN or infinity, so those travel as the strings PostgreSQL prints them as
        return Double.isFinite(parsed) ? DoubleNode.valueOf(parsed) : TextNode.valueOf(text);
    }

    private static boolean booleanText(String text) {
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "t", "true" -> true;
            case "f", "false" -> false;
            default -> throw new IllegalArgumentException("Not a boolean: " + text);
        };
    }

    private static String floatResult(String base, JsonNode value) {
        // the non-finite values a float argument travels as come back the same way
        if (value.isTextual() && NON_FINITE.contains(value.textValue())) {
            return value.textValue();
        }
        return Double.toString(number(base, value).doubleValue());
    }

    private static String whole(String base, JsonNode value, long min, long max) {
        // an integer type takes a JSON number with no fraction (3 and 3.0, not 1.5 or "1")
        if (!value.isNumber()) {
            throw mistyped(base, value);
        }
        BigDecimal decimal = value.decimalValue();
        if (decimal.stripTrailingZeros().scale() > 0) {
            throw mistyped(base, value);
        }
        BigInteger number = decimal.toBigInteger();
        if (number.compareTo(BigInteger.valueOf(min)) < 0 || number.compareTo(BigInteger.valueOf(max)) > 0) {
            throw new SpectrumSqlException(RESULT_ERROR, "The Lambda result " + number + " is out of range for "
                    + base);
        }
        return number.toString();
    }

    private static JsonNode number(String base, JsonNode value) {
        if (!value.isNumber()) {
            throw mistyped(base, value);
        }
        return value;
    }

    private static boolean bool(String base, JsonNode value) {
        if (!value.isBoolean()) {
            throw mistyped(base, value);
        }
        return value.booleanValue();
    }

    private static String text(String base, JsonNode value) {
        if (!value.isTextual()) {
            throw mistyped(base, value);
        }
        return value.textValue();
    }

    private static String date(String text) {
        try {
            return LocalDate.parse(text.trim()).toString();
        } catch (DateTimeParseException e) {
            throw new SpectrumSqlException(RESULT_ERROR, "The Lambda result \"" + text + "\" is not a date");
        }
    }

    private static String timestamp(String text) {
        try {
            // accepts the ISO form with a T and the PostgreSQL form with a space
            return TIMESTAMP_TEXT.format(LocalDateTime.parse(text.trim().replace(' ', 'T')));
        } catch (DateTimeParseException e) {
            throw new SpectrumSqlException(RESULT_ERROR, "The Lambda result \"" + text + "\" is not a timestamp");
        }
    }

    private static SpectrumSqlException mistyped(String base, JsonNode value) {
        return new SpectrumSqlException(RESULT_ERROR, "The Lambda result of JSON type " + value.getNodeType()
                + " does not fit the return type " + base);
    }
}
