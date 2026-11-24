package io.floci.aws.services.elbv2;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import io.floci.aws.core.common.AwsException;

import java.util.List;

/** Shared admission and linear-time matching for path-pattern regex conditions. */
public final class PathPatternRegexValues {

    private PathPatternRegexValues() {
    }

    public static void validate(List<String> regexValues, boolean hasGlobValues) {
        if (hasGlobValues) {
            throw new AwsException("ValidationError",
                    "A path-pattern condition can specify either Values or RegexValues, not both.", 400);
        }
        for (String regex : regexValues) {
            compile(regex);
        }
    }

    static Compiled compile(String regex) {
        if (regex.isEmpty() || regex.length() > 128) {
            throw new AwsException("ValidationError",
                    "Each path-pattern regex value must be between 1 and 128 characters.", 400);
        }
        try {
            if (hasUnicodeClass(regex)) {
                throw new PatternSyntaxException("Unicode character classes are unsupported", regex);
            }
            return new Compiled(Pattern.compile(regex));
        } catch (PatternSyntaxException e) {
            throw new AwsException("ValidationError", "Invalid path-pattern regex value '" + regex + "'.", 400);
        }
    }

    private static boolean hasUnicodeClass(String regex) {
        boolean quoted = false;
        for (int i = 0; i + 1 < regex.length(); i++) {
            if (regex.charAt(i) != '\\') {
                continue;
            }
            char escaped = regex.charAt(i + 1);
            if (quoted) {
                if (escaped == 'E') {
                    quoted = false;
                    i++;
                }
            } else {
                i++;
                if (escaped == 'Q') {
                    quoted = true;
                } else if (escaped == 'p' || escaped == 'P') {
                    return true;
                }
            }
        }
        return false;
    }

    record Compiled(Pattern pattern) {
        boolean matches(String path) {
            return pattern.matcher(path).matches();
        }
    }
}
