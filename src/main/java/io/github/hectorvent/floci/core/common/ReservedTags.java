package io.github.hectorvent.floci.core.common;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
public final class ReservedTags {

    public static final String RESERVED_PREFIX = "floci:";
    public static final String OVERRIDE_ID_KEY = RESERVED_PREFIX + "override-id";
    public static final String DEPRECATED_CUSTOM_ID_KEY = "_custom_id_";

    private ReservedTags() {
    }

    public static String extractOverrideId(Map<String, String> tags) {
        if (tags == null) {
            return null;
        }
        if (tags.containsKey(OVERRIDE_ID_KEY)) {
            return tags.get(OVERRIDE_ID_KEY);
        }
        if (tags.containsKey(DEPRECATED_CUSTOM_ID_KEY)) {
            return tags.get(DEPRECATED_CUSTOM_ID_KEY);
        }
        return null;
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
                        "ValidationException",
                        "Reserved tag keys with prefix " + RESERVED_PREFIX + " can only be supplied during resource creation.",
                        400
                );
            }
        }
    }

    private static boolean isReserved(String key) {
        return key != null && (key.startsWith(RESERVED_PREFIX) || key.equals(DEPRECATED_CUSTOM_ID_KEY));
    }
}
