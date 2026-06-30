package io.github.hectorvent.floci.core.common;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.apache.commons.lang3.function.TriFunction;

import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
public final class ReservedTags {

    public static final String RESERVED_PREFIX = "floci:";
    public static final String OVERRIDE_ID_KEY = RESERVED_PREFIX + "override-id";
    public static final String OVERRIDE_COGNITO_CLIENT_ID_KEY = RESERVED_PREFIX + "override-cognito-client-id";
    public static final String OVERRIDE_COGNITO_CLIENT_SECRET_KEY = RESERVED_PREFIX + "override-cognito-client-secret";
    private static final String INVALID_PARAMETER_EXCEPTION = "InvalidParameterException";
    private static final String VALIDATION_EXCEPTION = "ValidationException";
    private static final String TAG_EXCEPTION = "TagException";
    private static final String CONTROL_CHARACTER_ERROR_MESSAGE = "Override %s must not contain control characters.";

    private ReservedTags() {
    }

    public static String extractOverrideKeyId(Map<String, String> tags) {
        return getOverride(tags, OVERRIDE_ID_KEY, ReservedTags::validateOverrideId, "Resource ID", TAG_EXCEPTION);
    }

    public static String extractOverrideUserPoolId(Map<String, String> tags) {
        return getOverride(tags, OVERRIDE_ID_KEY, ReservedTags::validateOverrideId, "Resource ID", INVALID_PARAMETER_EXCEPTION);
    }

    public static String extractOverrideCognitoClientId(Map<String, String> tags) {
        return getOverride(tags, OVERRIDE_COGNITO_CLIENT_ID_KEY, ReservedTags::validateOverrideId, "Cognito Client ID", INVALID_PARAMETER_EXCEPTION);
    }

    public static String extractOverrideCognitoClientSecret(Map<String, String> tags) {
        return getOverride(tags, OVERRIDE_COGNITO_CLIENT_SECRET_KEY, ReservedTags::validateClientSecret, "Cognito Client Secret", INVALID_PARAMETER_EXCEPTION);
    }

    public static Map<String, String> stripReservedTags(Map<String, String> tags) {
        Map<String, String> stripped = new HashMap<>();
        if (tags == null) {
            return stripped;
        }
        tags.forEach((key, value) -> {
            if (!isReserved(key)) {
                stripped.put(key, value);
            }
        });
        return stripped;
    }

    public static void rejectReservedTagsOnUpdate(Map<String, String> tags) {
        if (tags == null) {
            return;
        }
        for (String key : tags.keySet()) {
            if (isReserved(key)) {
                throw new AwsException(
                        VALIDATION_EXCEPTION,
                        "Reserved tag keys with prefix " + RESERVED_PREFIX + " can only be supplied during resource creation.",
                        400
                );
            }
        }
    }

    public static void rejectUnknownReservedTags(Map<String, String> tags, String errorCode) {
        if (tags == null) {
            return;
        }
        for (String key : tags.keySet()) {
            if (isReserved(key) && !key.equals(OVERRIDE_ID_KEY) && !key.equals(OVERRIDE_COGNITO_CLIENT_ID_KEY) && !key.equals(OVERRIDE_COGNITO_CLIENT_SECRET_KEY)) {
                    throw new AwsException(
                            errorCode,
                            "%s is an unknown Reserved Tag.".formatted(key),
                            400
                    );
                }
        }
    }

    private static String getOverride(Map<String, String> tags, String override, TriFunction<String, String, String, String> validator, String name, String errorCode) {
        if (tags == null) {
            return null;
        }
        if (tags.containsKey(override)) {
            String ov = tags.get(override);
            return validator.apply(ov, name, errorCode);
        }
        return null;
    }

    private static String validateOverrideId(String overrideId, String name, String errorCode) {
        String normalized = checkNullAndWhitespace(overrideId, name, errorCode);
        if (normalized.indexOf('/') >= 0 || normalized.indexOf('?') >= 0 || normalized.indexOf('#') >= 0) {
            throw new AwsException(errorCode, "Override %s contains unsupported characters.".formatted(name), 400);
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new AwsException(errorCode, CONTROL_CHARACTER_ERROR_MESSAGE.formatted(name), 400);
        }
        return normalized;
    }

    private static String validateClientSecret(String overrideSecret, String name, String errorCode) {
        String normalized = checkNullAndWhitespace(overrideSecret, name, errorCode);
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new AwsException(errorCode, CONTROL_CHARACTER_ERROR_MESSAGE.formatted(name), 400);
        }
        return normalized;
    }

    private static String checkNullAndWhitespace(String overrideSecret, String name, String errorCode) {
        if (overrideSecret == null || overrideSecret.trim().isEmpty()) {
            throw new AwsException(errorCode, "Override %s must not be blank.".formatted(name), 400);
        }
        String normalized = overrideSecret.trim();
        if (normalized.chars().anyMatch(Character::isWhitespace)) {
            throw new AwsException(errorCode, "Override %s must not contain whitespace.".formatted(name), 400);
        }
        return normalized;
    }


    private static boolean isReserved(String key) {
        return key != null && key.startsWith(RESERVED_PREFIX);
    }
}
