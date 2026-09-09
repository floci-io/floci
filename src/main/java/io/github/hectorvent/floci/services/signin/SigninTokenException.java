package io.github.hectorvent.floci.services.signin;

/** AWS Sign-In token error with explicit modeled or OAuth response semantics. */
public final class SigninTokenException extends SigninException {

    static final String INVALID_REQUEST_MESSAGE =
            "The request is missing a required parameter, includes an invalid parameter value, "
                    + "or is otherwise malformed.";
    static final String UNSUPPORTED_GRANT_MESSAGE =
            "The authorization grant type is not supported by the authorization server.";

    private final String responseError;
    private final boolean modeled;

    private SigninTokenException(String awsErrorCode, String responseError, String message,
                                 int httpStatus, boolean modeled) {
        super(awsErrorCode, message, httpStatus);
        this.responseError = responseError;
        this.modeled = modeled;
    }

    public static SigninTokenException validation() {
        return new SigninTokenException(
                "ValidationException", "INVALID_REQUEST", INVALID_REQUEST_MESSAGE, 400, true);
    }

    public static SigninTokenException authorizationCodeExpired() {
        return new SigninTokenException(
                "AccessDeniedException", "AUTHCODE_EXPIRED",
                "The authorization code is invalid or expired", 401, true);
    }

    public static SigninTokenException refreshTokenExpired() {
        return new SigninTokenException(
                "AccessDeniedException", "TOKEN_EXPIRED",
                "The refresh token is invalid or expired", 401, true);
    }

    public static SigninTokenException unsupportedGrant() {
        return new SigninTokenException(
                "unsupported_grant_type", "unsupported_grant_type",
                UNSUPPORTED_GRANT_MESSAGE, 400, false);
    }

    public String responseError() {
        return responseError;
    }

    public boolean modeled() {
        return modeled;
    }
}
