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

    /**
     * API Gateway accepted a custom id through this key before {@link #OVERRIDE_ID_KEY} existed. It is
     * API Gateway specific and deprecated: still honored on create, but no longer persisted in the
     * resource tags. Other services never supported it and must not start reserving it.
     */
    public static final String DEPRECATED_API_GATEWAY_CUSTOM_ID_KEY = "_custom_id_";

    private static final String INVALID_PARAMETER_EXCEPTION = "InvalidParameterException";
    private static final String VALIDATION_EXCEPTION = "ValidationException";
    private static final String TAG_EXCEPTION = "TagException";
    private static final String BAD_REQUEST_EXCEPTION = "BadRequestException";
    private static final String CONTROL_CHARACTER_ERROR_MESSAGE = "Override %s must not contain control characters.";

    private ReservedTags() {
    }

    public static String extractOverrideKeyId(Map<String, String> tags) {
        return getOverride(tags, OVERRIDE_ID_KEY, ReservedTags::validateOverrideId, "Resource ID", TAG_EXCEPTION);
    }

    public static String extractOverrideUserPoolId(Map<String, String> tags) {
        return getOverride(tags, OVERRIDE_ID_KEY, ReservedTags::validateOverrideId, "Resource ID", INVALID_PARAMETER_EXCEPTION);
    }

    /**
     * API Gateway v1 and v2 override id. {@link #OVERRIDE_ID_KEY} wins when both keys are present, so a
     * caller migrating off {@link #DEPRECATED_API_GATEWAY_CUSTOM_ID_KEY} can set both during a rollout.
     * Uses {@code BadRequestException}, which is what CreateRestApi and CreateApi declare.
     */
    public static String extractOverrideApiId(Map<String, String> tags) {
        String override = getOverride(tags, OVERRIDE_ID_KEY, ReservedTags::validateOverrideId,
                "Resource ID", BAD_REQUEST_EXCEPTION);
        if (override != null) {
            return override;
        }
        return getOverride(tags, DEPRECATED_API_GATEWAY_CUSTOM_ID_KEY, ReservedTags::validateOverrideId,
                "Resource ID", BAD_REQUEST_EXCEPTION);
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

    /**
     * Like {@link #stripReservedTags(Map)} but also drops API Gateway's deprecated custom-id key, so an
     * id override never survives into the tags the API returns.
     */
    public static Map<String, String> stripApiGatewayReservedTags(Map<String, String> tags) {
        Map<String, String> stripped = stripReservedTags(tags);
        stripped.remove(DEPRECATED_API_GATEWAY_CUSTOM_ID_KEY);
        return stripped;
    }

    /**
     * API Gateway update-path guard. An id override only makes sense at create time, so both the
     * reserved keys and the deprecated custom-id key are rejected here, with API Gateway's declared
     * error code rather than the {@code ValidationException} the shared guard uses.
     */
    public static void rejectApiGatewayReservedTagsOnUpdate(Map<String, String> tags) {
        if (tags == null) {
            return;
        }
        for (String key : tags.keySet()) {
            if (isReserved(key) || DEPRECATED_API_GATEWAY_CUSTOM_ID_KEY.equals(key)) {
                throw new AwsException(
                        BAD_REQUEST_EXCEPTION,
                        "Reserved tag key " + key + " can only be supplied during resource creation.",
                        400
                );
            }
        }
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
