package io.floci.aws.core.common;

import java.util.BitSet;
import java.util.function.IntPredicate;

/** Patterns whose only wildcard is a star. */
public final class StarWildcard {

    private StarWildcard() {
    }

    /**
     * Anchored, case-sensitive glob. Each {@code *} matches any run of characters, including an
     * empty one, and every other character matches only itself. This is a scan, not a regular
     * expression, so {@code .} and other metacharacters stay literal. A star retries from the last
     * one only, so the work stays within the product of the two lengths however many stars the
     * pattern holds. A caller that wants a case-insensitive match passes both in one case.
     */
    public static boolean matches(String pattern, String value) {
        return scan(pattern, index -> pattern.charAt(index) == '*', value);
    }

    /**
     * As {@link #matches}, where a backslash makes the star or the backslash after it literal:
     * {@code \*} stands for a {@code *} and {@code \\} for a {@code \}, the two escapes
     * EventBridge's {@code wildcard} filter takes. A backslash before any other character stands
     * for itself.
     */
    public static boolean matchesEscaped(String pattern, String value) {
        StringBuilder literal = new StringBuilder(pattern.length());
        BitSet stars = new BitSet(pattern.length());
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()
                    && (pattern.charAt(i + 1) == '*' || pattern.charAt(i + 1) == '\\')) {
                literal.append(pattern.charAt(++i));
            } else {
                if (c == '*') {
                    stars.set(literal.length());
                }
                literal.append(c);
            }
        }
        return scan(literal, stars::get, value);
    }

    private static boolean scan(CharSequence pattern, IntPredicate isStar, String value) {
        int patternIndex = 0;
        int valueIndex = 0;
        int starPattern = -1;
        int starValue = -1;
        while (valueIndex < value.length()) {
            if (patternIndex < pattern.length() && isStar.test(patternIndex)) {
                starPattern = patternIndex++;
                starValue = valueIndex;
            } else if (patternIndex < pattern.length()
                    && pattern.charAt(patternIndex) == value.charAt(valueIndex)) {
                patternIndex++;
                valueIndex++;
            } else if (starPattern >= 0) {
                patternIndex = starPattern + 1;
                valueIndex = ++starValue;
            } else {
                return false;
            }
        }
        while (patternIndex < pattern.length() && isStar.test(patternIndex)) {
            patternIndex++;
        }
        return patternIndex == pattern.length();
    }
}
