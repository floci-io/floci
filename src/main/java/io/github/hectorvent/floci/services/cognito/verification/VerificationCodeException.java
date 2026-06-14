package io.github.hectorvent.floci.services.cognito.verification;

/**
 * Thrown by {@link VerificationCodeService} for code-validation failures.
 * The caller in {@code CognitoService} translates each {@link Kind} into the
 * corresponding AWS Cognito exception name ({@code CodeMismatchException},
 * {@code ExpiredCodeException}, {@code LimitExceededException}).
 */
public final class VerificationCodeException extends RuntimeException {

    public enum Kind { MISMATCH, EXPIRED, RATE_LIMIT, NOT_FOUND }

    private final Kind kind;

    public VerificationCodeException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind getKind() { return kind; }
}
