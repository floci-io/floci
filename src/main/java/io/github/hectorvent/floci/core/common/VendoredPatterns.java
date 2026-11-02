package io.github.hectorvent.floci.core.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles a pattern a vendored AWS model declares, in the dialect the model writes it in rather
 * than in Java's.
 *
 * <p>One thing differs between the two. A model writes a code point above U+FFFF as a backslash-u
 * escape of five or six hex digits, the U+10000 to U+10FFFF range on STS's {@code arnType} among
 * them, where Java reads exactly four digits and takes the rest as literals. Compiled as written,
 * that range becomes the single character U+1000, a literal zero, a hyphen, the character U+10FF
 * and two literal Fs, so the character class around it gains a range from {@code 0} to U+10FF: it
 * admits characters the model excludes, U+0081 among them, and refuses every supplementary
 * character the model admits. Each wide escape is read here as the code point it spells, which is
 * what the model means by it, and written in Java's own {@code x} form.
 *
 * <p>Five or six hex digits naming no code point are left as the model wrote them, as is an escape
 * of four, which is Java's own: nothing vendored here carries either case, and compiling what the
 * model wrote answers what it has always answered.
 */
public final class VendoredPatterns {

    /** A backslash-u escape wider than the four digits Java reads, the longest run first. */
    private static final Pattern WIDE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{5,6})");

    /** The narrowest run this reads: four digits are Java's own escape. */
    private static final int NARROWEST_WIDE_ESCAPE = 5;

    private VendoredPatterns() {
    }

    /** {@code pattern} compiled as the model means it. */
    public static Pattern compile(String pattern) {
        return Pattern.compile(rewrite(pattern));
    }

    /**
     * {@code pattern} with every wide escape written in Java's form. The digits are read longest
     * first, so six of them name the top of Unicode rather than five of them and a stray F, and a
     * run naming no code point at any width is handed back as it was written.
     */
    static String rewrite(String pattern) {
        Matcher escape = WIDE_ESCAPE.matcher(pattern);
        StringBuilder rewritten = new StringBuilder();
        int from = 0;
        while (escape.find()) {
            rewritten.append(pattern, from, escape.start()).append(asCodePoint(escape.group()));
            from = escape.end();
        }
        return from == 0 ? pattern : rewritten.append(pattern, from, pattern.length()).toString();
    }

    /** The escape as a code point, or as the model wrote it where no run of its digits names one. */
    private static String asCodePoint(String escape) {
        String digits = escape.substring(2);
        for (int width = digits.length(); width >= NARROWEST_WIDE_ESCAPE; width--) {
            int codePoint = Integer.parseInt(digits.substring(0, width), 16);
            if (codePoint >= Character.MIN_SUPPLEMENTARY_CODE_POINT && codePoint <= Character.MAX_CODE_POINT) {
                return "\\x{" + digits.substring(0, width) + "}" + digits.substring(width);
            }
        }
        return escape;
    }
}
