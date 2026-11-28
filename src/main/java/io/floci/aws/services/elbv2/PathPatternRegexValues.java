package io.floci.aws.services.elbv2;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.elbv2.model.RuleCondition;

import java.util.List;

/** Shared admission and linear-time matching for path-pattern regex conditions. */
public final class PathPatternRegexValues {

    private static final int MAX_CONDITION_EVALUATIONS = 3;
    private static final int MAX_RULE_EVALUATIONS = 5;

    private PathPatternRegexValues() {
    }

    /** Validates regex-bearing rules at both public admission paths before mutation. */
    public static void validateConditions(List<RuleCondition> conditions) {
        boolean hasRegex = conditions.stream().anyMatch(c -> !c.getPathPatternRegexValues().isEmpty());
        if (!hasRegex) {
            return;
        }
        int evaluations = 0;
        for (RuleCondition condition : conditions) {
            List<String> regexValues = condition.getPathPatternRegexValues();
            if (!regexValues.isEmpty() && !"path-pattern".equals(condition.getField())) {
                throw new AwsException("ValidationError",
                        "PathPatternConfig.RegexValues requires the path-pattern field.", 400);
            }
            int conditionEvaluations = matchEvaluations(condition);
            if (conditionEvaluations > MAX_CONDITION_EVALUATIONS) {
                throw new AwsException("ValidationError",
                        "A condition can have at most three match evaluations.", 400);
            }
            evaluations += conditionEvaluations;
            if (evaluations > MAX_RULE_EVALUATIONS) {
                throw new AwsException("ValidationError",
                        "A rule can only have '5' condition values and regex values", 400);
            }
        }
        for (RuleCondition condition : conditions) {
            if (!condition.getPathPatternRegexValues().isEmpty()) {
                validate(condition.getPathPatternRegexValues(),
                        !condition.getValues().isEmpty() || !condition.getPathPatternValues().isEmpty());
            }
        }
    }

    private static int matchEvaluations(RuleCondition condition) {
        return switch (condition.getField()) {
            case "path-pattern" -> condition.getPathPatternRegexValues().isEmpty()
                    ? valueCount(condition.getPathPatternValues(), condition.getValues())
                    : condition.getPathPatternRegexValues().size();
            case "host-header" -> valueCount(condition.getHostHeaderValues(), condition.getValues());
            case "http-header" -> condition.getHttpHeaderValues().size();
            case "http-request-method" -> condition.getHttpMethodValues().size();
            case "source-ip" -> condition.getSourceIpValues().size();
            case "query-string" -> condition.getQueryStringValues().size();
            case null, default -> condition.getValues().size();
        };
    }

    private static int valueCount(List<String> configured, List<String> legacy) {
        return configured.isEmpty() ? legacy.size() : configured.size();
    }

    public static void validate(List<String> regexValues, boolean hasGlobValues) {
        if (hasGlobValues) {
            throw new AwsException("ValidationError",
                    "You must provide exactly one of the following: ['Values' 'RegexValues']"
                            + " for config of type 'PathPatternConfig'", 400);
        }
        if (regexValues.size() > MAX_CONDITION_EVALUATIONS) {
            throw new AwsException("ValidationError",
                    "A condition can have at most three match evaluations.", 400);
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
            throw new AwsException("ValidationError",
                    "One or more provided condition regex values are not valid", 400);
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
