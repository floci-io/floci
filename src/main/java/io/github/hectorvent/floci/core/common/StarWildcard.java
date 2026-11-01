package io.github.hectorvent.floci.core.common;

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
        int patternIndex = 0;
        int valueIndex = 0;
        int starPattern = -1;
        int starValue = -1;
        while (valueIndex < value.length()) {
            if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
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
        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
            patternIndex++;
        }
        return patternIndex == pattern.length();
    }
}
