package io.github.hectorvent.floci.services.ssooidc;

public class SsoOidcException extends RuntimeException {
    private final String error;
    private final int status;

    public SsoOidcException(String error, String description, int status) {
        super(description);
        this.error = error;
        this.status = status;
    }

    public String error() {
        return error;
    }

    public int status() {
        return status;
    }
}
