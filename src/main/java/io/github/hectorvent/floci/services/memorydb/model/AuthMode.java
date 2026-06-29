package io.github.hectorvent.floci.services.memorydb.model;

/**
 * Authentication type of a MemoryDB {@link User}. Mirrors the {@code Type} field of the
 * real API's {@code AuthenticationMode}/{@code Authentication} structures, whose wire
 * values are lowercase (e.g. {@code password}).
 *
 * <p>A cluster does not carry an auth mode of its own — it references an ACL, and the
 * effective authentication is determined by the auth modes of the users in that ACL.
 */
public enum AuthMode {
    PASSWORD("password"),
    IAM("iam"),
    NO_PASSWORD("no-password");

    private final String wireValue;

    AuthMode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    /**
     * Parse the wire value from an {@code AuthenticationMode.Type} request field.
     * Per the API, the valid input/output types are {@code password}, {@code iam} and
     * {@code no-password}.
     */
    public static AuthMode fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (AuthMode mode : values()) {
            if (mode.wireValue.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown authentication type: " + value);
    }
}
