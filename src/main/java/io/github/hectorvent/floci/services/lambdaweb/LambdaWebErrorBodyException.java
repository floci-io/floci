package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.core.common.AwsException;

import java.util.Map;

/** An error AWS answers with a body other than {@code {"message": ...}}. */
public abstract class LambdaWebErrorBodyException extends AwsException {

    LambdaWebErrorBodyException(String errorCode, String message, int httpStatus) {
        super(errorCode, message, httpStatus);
    }

    abstract Map<String, String> body();
}
