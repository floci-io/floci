package io.github.hectorvent.floci.services.stepfunctions;

import com.dashjoin.jsonata.JException;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * The picture string of {@code $formatNumber}, checked against the rules XPath F&amp;O 4.7.3 gives
 * it, which is what AWS applies and the bundled JSONata library does not: it formats through
 * {@link java.text.DecimalFormat} and answers {@code "x1"} for {@code $formatNumber(1, "x")} where
 * AWS fails the state with D3086.
 *
 * <p>A picture may break more than one of the fourteen rules, and the code reported is the last
 * one, which is the one AWS reports: a picture with no digit at all breaks two at once, the
 * mantissa holding no digit (D3085) and the active part holding a passive character (D3086), and
 * D3086 is the answer.
 *
 * <p>The symbols the rules are written in terms of are the built-in ones, each replaceable through
 * the third argument of {@code $formatNumber}.
 */
final class FormatNumberPicture {

    private final char decimalSeparator;
    private final char exponentSeparator;
    private final char groupingSeparator;
    private final char digit;
    private final char patternSeparator;
    private final char percent;
    private final char perMille;
    private final char zeroDigit;

    private FormatNumberPicture(Map<?, ?> options) {
        decimalSeparator = symbol(options, "decimal-separator", '.');
        exponentSeparator = symbol(options, "exponent-separator", 'e');
        groupingSeparator = symbol(options, "grouping-separator", ',');
        digit = symbol(options, "digit", '#');
        patternSeparator = symbol(options, "pattern-separator", ';');
        percent = symbol(options, "percent", '%');
        perMille = symbol(options, "per-mille", '‰');
        zeroDigit = symbol(options, "zero-digit", '0');
    }

    private static char symbol(Map<?, ?> options, String name, char builtIn) {
        Object override = options == null ? null : options.get(name);
        return override instanceof String text && text.length() == 1 ? text.charAt(0) : builtIn;
    }

    /**
     * Fails with the code of the rule the picture breaks, or returns when it breaks none. The
     * picture is split into sub-pictures first, one for positive numbers and an optional second
     * one for negative numbers.
     */
    static void validate(String picture, Map<?, ?> options) {
        FormatNumberPicture symbols = new FormatNumberPicture(options);
        String[] subPictures =
                picture.split(Pattern.quote(String.valueOf(symbols.patternSeparator)), -1);
        if (subPictures.length > 2) {
            throw new JException("D3080", -1);
        }
        for (String subPicture : subPictures) {
            String error = symbols.errorIn(symbols.split(subPicture));
            if (error != null) {
                throw new JException(error, -1);
            }
        }
    }

    /**
     * The parts a sub-picture is read in. The prefix and the suffix are the passive characters at
     * either edge, so the active part is what sits between them; the mantissa is the active part
     * up to the exponent separator, and the integer and fractional parts are the mantissa either
     * side of the decimal separator.
     */
    private record Parts(String subPicture, String activePart, String mantissaPart,
                         String integerPart, String fractionalPart, String exponentPart) { }

    private Parts split(String subPicture) {
        int activeStart = 0;
        while (activeStart < subPicture.length() && !isActiveOutsideExponent(subPicture.charAt(activeStart))) {
            activeStart++;
        }
        int activeEnd = subPicture.length();
        while (activeEnd > 0 && !isActiveOutsideExponent(subPicture.charAt(activeEnd - 1))) {
            activeEnd--;
        }
        // A sub-picture with no active character at all is all active part and has no edges.
        if (activeEnd == 0) {
            activeStart = 0;
            activeEnd = subPicture.length();
        }
        String activePart = subPicture.substring(activeStart, activeEnd);
        int exponentAt = subPicture.indexOf(exponentSeparator, activeStart) - activeStart;
        boolean hasExponent = exponentAt >= 0 && exponentAt <= activePart.length();
        String mantissaPart = hasExponent ? upTo(activePart, exponentAt) : activePart;
        String exponentPart = hasExponent ? from(activePart, exponentAt + 1) : null;
        int decimalAt = mantissaPart.indexOf(decimalSeparator);
        String integerPart = decimalAt < 0 ? mantissaPart : mantissaPart.substring(0, decimalAt);
        String fractionalPart =
                decimalAt < 0 ? subPicture.substring(activeEnd) : mantissaPart.substring(decimalAt + 1);
        return new Parts(subPicture, activePart, mantissaPart, integerPart, fractionalPart, exponentPart);
    }

    /** The exponent separator is located in the sub-picture, so it can point past the active part. */
    private static String upTo(String text, int end) {
        return text.substring(0, Math.min(end, text.length()));
    }

    private static String from(String text, int start) {
        return text.substring(Math.min(start, text.length()));
    }

    /**
     * The code of the last rule the sub-picture breaks, or null when it breaks none. The rules are
     * read in the order the codes number them, and a later one replaces what an earlier one found.
     */
    private String errorIn(Parts parts) {
        String error = separatorCountError(parts.subPicture());
        error = later(error, digitError(parts));
        error = later(error, groupingSeparatorError(parts));
        error = later(error, optionalDigitError(parts));
        error = later(error, exponentError(parts));
        return error;
    }

    private static String later(String error, String found) {
        return found != null ? found : error;
    }

    /** D3081 to D3084: a symbol that may appear once appears twice, or two that exclude each other. */
    private String separatorCountError(String subPicture) {
        String error = null;
        if (subPicture.indexOf(decimalSeparator) != subPicture.lastIndexOf(decimalSeparator)) {
            error = "D3081";
        }
        if (subPicture.indexOf(percent) != subPicture.lastIndexOf(percent)) {
            error = "D3082";
        }
        if (subPicture.indexOf(perMille) != subPicture.lastIndexOf(perMille)) {
            error = "D3083";
        }
        if (subPicture.indexOf(percent) >= 0 && subPicture.indexOf(perMille) >= 0) {
            error = "D3084";
        }
        return error;
    }

    /** D3085 and D3086: the mantissa holds no digit, and the active part holds a passive character. */
    private String digitError(Parts parts) {
        String error = null;
        if (!holdsDigit(parts.mantissaPart())) {
            error = "D3085";
        }
        if (!parts.activePart().chars().allMatch(character -> isActive((char) character))) {
            error = "D3086";
        }
        return error;
    }

    /** D3087 to D3089: a grouping separator next to the decimal separator, at the end, or doubled. */
    private String groupingSeparatorError(Parts parts) {
        String subPicture = parts.subPicture();
        int decimalAt = subPicture.indexOf(decimalSeparator);
        String error = null;
        if (decimalAt >= 0 && (characterAt(subPicture, decimalAt - 1) == groupingSeparator
                || characterAt(subPicture, decimalAt + 1) == groupingSeparator)) {
            error = "D3087";
        } else if (decimalAt < 0 && !parts.integerPart().isEmpty()
                && parts.integerPart().charAt(parts.integerPart().length() - 1) == groupingSeparator) {
            error = "D3088";
        }
        if (subPicture.indexOf("" + groupingSeparator + groupingSeparator) >= 0) {
            error = "D3089";
        }
        return error;
    }

    /**
     * D3090 and D3091: an optional digit with a mandatory one on the side the number grows from,
     * which is to its left in the integer part and to its right in the fractional part.
     */
    private String optionalDigitError(Parts parts) {
        String error = null;
        int firstOptional = parts.integerPart().indexOf(digit);
        if (firstOptional >= 0 && holdsDecimalDigit(parts.integerPart().substring(0, firstOptional))) {
            error = "D3090";
        }
        int lastOptional = parts.fractionalPart().lastIndexOf(digit);
        if (lastOptional >= 0 && holdsDecimalDigit(parts.fractionalPart().substring(lastOptional))) {
            error = "D3091";
        }
        return error;
    }

    /** D3092 and D3093: an exponent alongside a percent, and an exponent that is not all digits. */
    private String exponentError(Parts parts) {
        if (parts.exponentPart() == null) {
            return null;
        }
        boolean scaled = parts.subPicture().indexOf(percent) >= 0
                || parts.subPicture().indexOf(perMille) >= 0;
        String error = null;
        if (!parts.exponentPart().isEmpty() && scaled) {
            error = "D3092";
        }
        if (parts.exponentPart().isEmpty()
                || !parts.exponentPart().chars().allMatch(character -> isDecimalDigit((char) character))) {
            error = "D3093";
        }
        return error;
    }

    private static char characterAt(String text, int index) {
        return index >= 0 && index < text.length() ? text.charAt(index) : '\0';
    }

    private boolean holdsDigit(String text) {
        return text.chars().anyMatch(character -> isDecimalDigit((char) character) || character == digit);
    }

    private boolean holdsDecimalDigit(String text) {
        return text.chars().anyMatch(character -> isDecimalDigit((char) character));
    }

    private boolean isDecimalDigit(char character) {
        return character >= zeroDigit && character < zeroDigit + 10;
    }

    private boolean isActive(char character) {
        return isDecimalDigit(character) || character == decimalSeparator
                || character == exponentSeparator || character == groupingSeparator
                || character == digit || character == patternSeparator;
    }

    private boolean isActiveOutsideExponent(char character) {
        return isActive(character) && character != exponentSeparator;
    }
}
