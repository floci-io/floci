package io.github.hectorvent.floci.services.apigateway;

/**
 * Thrown when a VTL (Velocity Template Language) template render exceeds one of the configured
 * sandbox limits: loop iteration count, wall-clock execution time, or rendered output size.
 *
 * <p>This is a plain unchecked exception, matching Velocity's own {@code VelocityException}
 * hierarchy (which also extends {@link RuntimeException}), so it propagates through both the
 * API Gateway and AppSync VTL engines exactly like any other template rendering failure already
 * does today, without requiring any new catch or error-mapping logic at their call sites.
 */
public class VtlLimitExceededException extends RuntimeException {

    public VtlLimitExceededException(String message) {
        super(message);
    }
}
