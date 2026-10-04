package io.github.hectorvent.floci.core.common;

/**
 * Public key material that could not be read. Thrown by {@link SshPublicKeys} so that each caller
 * maps it onto whatever its own API calls the failure, rather than the parser deciding: EC2
 * answers an unreadable imported key with no fingerprint at all.
 */
public class SshPublicKeyException extends RuntimeException {

    public SshPublicKeyException(String message) {
        super(message);
    }

    public SshPublicKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}
