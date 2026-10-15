package io.github.hectorvent.floci.core.common;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads a file vendored on the classpath, such as the IAM data generated from AWS's Service
 * Reference. A missing or unreadable resource is a build defect, not a runtime condition, so it
 * fails with {@link IllegalStateException} rather than leaving the caller to degrade to an empty
 * catalog.
 */
public final class VendoredResources {

    /** Reads the resource's content into what the caller keeps. */
    @FunctionalInterface
    public interface Parser<T> {
        T parse(InputStream in) throws IOException;
    }

    private VendoredResources() {
    }

    /** The resource {@code name} as {@code parser} reads it; {@code description} names it in a failure. */
    public static <T> T read(String name, String description, Parser<T> parser) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException(description + " resource not found: " + name);
            }
            return parser.parse(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read the " + description + " " + name, e);
        }
    }
}
