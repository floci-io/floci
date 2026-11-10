package io.floci.aws.services.lambdaweb;

import io.floci.aws.core.common.AwsException;

import java.util.Map;

/** An error AWS answers with a body other than {@code {"message": ...}}. */
public abstract class LambdaWebErrorBodyException extends AwsException {

    LambdaWebErrorBodyException(String errorCode, String message, int httpStatus) {
        super(errorCode, message, httpStatus);
    }

    abstract Map<String, String> body();
}
