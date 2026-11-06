package io.floci.aws.services.s3.model;

import io.floci.aws.core.common.AwsException;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public enum ObjectAttributeName {
    E_TAG("ETag"),
    CHECKSUM("Checksum"),
    OBJECT_PARTS("ObjectParts"),
    STORAGE_CLASS("StorageClass"),
    OBJECT_SIZE("ObjectSize");

    private final String wireValue;

    ObjectAttributeName(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Set<ObjectAttributeName> parseHeader(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new AwsException("InvalidRequest",
                    "Missing required header for this request: x-amz-object-attributes", 400);
        }

        Set<ObjectAttributeName> attributes = new LinkedHashSet<>();
        for (String token : headerValue.split(",")) {
            String normalized = token.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            attributes.add(fromWireValue(normalized));
        }

        if (attributes.isEmpty()) {
            throw new AwsException("InvalidRequest",
                    "Missing required header for this request: x-amz-object-attributes", 400);
        }
        return attributes;
    }

    public static ObjectAttributeName fromWireValue(String value) {
        for (ObjectAttributeName attribute : values()) {
            if (attribute.wireValue.equalsIgnoreCase(value)) {
                return attribute;
            }
        }
        throw new AwsException("InvalidArgument",
                "Unsupported object attribute: " + value, 400);
    }

    public static String normalizeStorageClass(String value) {
        if (value == null || value.isBlank()) {
            return "STANDARD";
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "STANDARD", "STANDARD_IA", "ONEZONE_IA", "INTELLIGENT_TIERING",
                    "GLACIER", "DEEP_ARCHIVE", "REDUCED_REDUNDANCY",
                    "GLACIER_IR", "OUTPOSTS", "SNOW", "EXPRESS_ONEZONE" -> normalized;
            default -> throw new AwsException("InvalidStorageClass",
                    "The storage class you specified is not valid", 400);
        };
    }
}
