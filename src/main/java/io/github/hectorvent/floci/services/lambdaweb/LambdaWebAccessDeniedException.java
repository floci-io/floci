package io.github.hectorvent.floci.services.lambdaweb;

import java.util.Map;

/** An access denial. AWS writes its message under {@code Message}, not {@code message}. */
public final class LambdaWebAccessDeniedException extends LambdaWebErrorBodyException {

    LambdaWebAccessDeniedException(String message) {
        super("AccessDeniedException", message, 403);
    }

    @Override
    Map<String, String> body() {
        return Map.of("Message", getMessage());
    }
}
