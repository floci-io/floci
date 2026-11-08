package io.floci.aws.services.signin;

import io.floci.aws.core.common.AwsException;

/** AWS Sign-In error with the service's OAuth wire error code. */
public class SigninException extends AwsException {

    public SigninException(String error, String message) {
        super(error, message, 400);
    }

    protected SigninException(String error, String message, int httpStatus) {
        super(error, message, httpStatus);
    }

    public String error() {
        return getErrorCode();
    }
}
