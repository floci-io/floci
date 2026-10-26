package io.github.hectorvent.floci.services.lambdaweb;

import java.util.Map;

/** An internal error. AWS answers it with the type in the header and the body {@code {}}. */
public final class LambdaWebInternalServerException extends LambdaWebErrorBodyException {

    LambdaWebInternalServerException() {
        super("InternalServerException", null, 500);
    }

    @Override
    Map<String, String> body() {
        return Map.of();
    }
}
