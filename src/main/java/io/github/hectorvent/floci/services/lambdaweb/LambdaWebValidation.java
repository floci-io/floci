package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.core.common.SmithyValidationErrors;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Collects the input-shape errors of one request into the single {@code ValidationException} AWS
 * reports, which counts the errors and joins them with "; ".
 */
final class LambdaWebValidation {

    private final List<String> errors = new ArrayList<>();

    /** A member read by {@link LambdaWebJson}, so an absent JSON member arrives as null. */
    LambdaWebValidation required(String path, Object value) {
        if (value == null) {
            errors.add(SmithyValidationErrors.notNull(path));
        }
        return this;
    }

    LambdaWebValidation length(String path, String value, int min, int max) {
        if (value == null) {
            return this;
        }
        if (value.length() < min) {
            errors.add(valueAt(value, path) + "Member must have length greater than or equal to " + min);
        } else if (value.length() > max) {
            errors.add(valueAt(value, path) + "Member must have length less than or equal to " + max);
        }
        return this;
    }

    LambdaWebValidation listSize(String path, int size, int min, int max) {
        if (size < min) {
            errors.add("Value at '" + path + "' failed to satisfy constraint: "
                    + "Member must have length greater than or equal to " + min);
        } else if (size > max) {
            errors.add("Value at '" + path + "' failed to satisfy constraint: "
                    + "Member must have length less than or equal to " + max);
        }
        return this;
    }

    /** AWS reports one error for the whole list, printing it, when any member breaks a constraint. */
    LambdaWebValidation listMembers(String path, List<String> values, int min, int max, Pattern pattern) {
        boolean bad = values.stream().anyMatch(v -> v.length() < min || v.length() > max || !pattern.matcher(v).matches());
        if (bad) {
            errors.add(valueAt(values, path) + "Member must satisfy constraint: [" + lengthConstraints(min, max)
                    + ", Member must satisfy regular expression pattern: " + pattern.pattern() + "]");
        }
        return this;
    }

    LambdaWebValidation pattern(String path, String value, Pattern pattern) {
        if (value != null && !pattern.matcher(value).matches()) {
            errors.add(valueAt(value, path) + "Member must satisfy regular expression pattern: " + pattern.pattern());
        }
        return this;
    }

    LambdaWebValidation range(String path, Integer value, int min, int max) {
        if (value == null) {
            return this;
        }
        if (value < min) {
            errors.add(valueAt(value, path) + "Member must have value greater than or equal to " + min);
        } else if (value > max) {
            errors.add(valueAt(value, path) + "Member must have value less than or equal to " + max);
        }
        return this;
    }

    LambdaWebValidation enumeration(String path, String value, List<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            errors.add(valueAt(value, path) + "Member must satisfy enum value set: " + allowed);
        }
        return this;
    }

    /** AWS lists every key constraint whichever one failed. */
    LambdaWebValidation mapKeys(String path, Map<String, ?> map, int min, int max, Pattern pattern) {
        boolean bad = map.keySet().stream()
                .anyMatch(k -> k.length() < min || k.length() > max || !pattern.matcher(k).matches());
        if (bad) {
            errors.add("Value at '" + path + "' failed to satisfy constraint: Map keys must satisfy constraint: ["
                    + lengthConstraints(min, max) + ", Member must satisfy regular expression pattern: "
                    + pattern.pattern() + "]");
        }
        return this;
    }

    /** As {@link #mapKeys}, for values. AWS prints the whole map as the offending value. */
    LambdaWebValidation mapValues(String path, Map<String, String> map, int min, int max, Pattern pattern) {
        boolean bad = map.values().stream()
                .anyMatch(v -> v == null || v.length() < min || v.length() > max || !pattern.matcher(v).matches());
        if (bad) {
            errors.add(valueAt(map, path) + "Map value must satisfy constraint: ["
                    + lengthConstraints(min, max) + ", Member must satisfy regular expression pattern: "
                    + pattern.pattern() + "]");
        }
        return this;
    }

    /** A sensitive map: AWS prints no value and the keys have a minimum length and a pattern only. */
    LambdaWebValidation sensitiveMapKeys(String path, Map<String, ?> map, int min, Pattern pattern) {
        boolean bad = map.keySet().stream().anyMatch(k -> k.length() < min || !pattern.matcher(k).matches());
        if (bad) {
            errors.add("Value at '" + path + "' failed to satisfy constraint: Map keys must satisfy constraint: ["
                    + "Member must have length greater than or equal to " + min
                    + ", Member must satisfy regular expression pattern: " + pattern.pattern() + "]");
        }
        return this;
    }

    LambdaWebValidation sensitiveMapValues(String path, Map<String, String> map, int min) {
        boolean bad = map.values().stream().anyMatch(v -> v.length() < min);
        if (bad) {
            errors.add("Value at '" + path + "' failed to satisfy constraint: Map value must satisfy constraint: ["
                    + "Member must have length greater than or equal to " + min + "]");
        }
        return this;
    }

    private static String lengthConstraints(int min, int max) {
        return "Member must have length less than or equal to " + max
                + ", Member must have length greater than or equal to " + min;
    }

    private static String valueAt(Object value, String path) {
        return "Value '" + value + "' at '" + path + "' failed to satisfy constraint: ";
    }

    void throwIfAny() {
        if (errors.isEmpty()) {
            return;
        }
        throw SmithyValidationErrors.validationException(errors);
    }
}
