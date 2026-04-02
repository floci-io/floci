package io.github.hectorvent.floci.services.s3.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A single S3 notification filter rule (prefix or suffix match on the object key).
 *
 * @param name  "prefix" or "suffix"
 * @param value the value to match against
 */
@RegisterForReflection
public record FilterRule(String name, String value) {

    public boolean matches(String key) {
        if (key == null || name == null || value == null) return false;
        return switch (name.toLowerCase()) {
            case "prefix" -> key.startsWith(value);
            case "suffix" -> key.endsWith(value);
            default -> false;
        };
    }
}
