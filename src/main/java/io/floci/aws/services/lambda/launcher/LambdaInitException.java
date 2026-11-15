package io.floci.aws.services.lambda.launcher;

/** An init failure AWS reports with its own error type, such as {@code Runtime.InvalidEntrypoint}. */
public class LambdaInitException extends RuntimeException {

    private final String errorType;

    public LambdaInitException(String errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public String errorType() {
        return errorType;
    }
}
