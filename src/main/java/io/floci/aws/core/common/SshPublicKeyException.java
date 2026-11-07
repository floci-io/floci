package io.floci.aws.core.common;

/**
 * Public key material that could not be read. Thrown by {@link SshPublicKeys} so that each caller
 * maps it onto whatever its own API calls the failure, rather than the parser deciding: EC2's
 * ImportKeyPair answers an unreadable key with InvalidKey.Format, IAM with InvalidPublicKey.
 */
public class SshPublicKeyException extends RuntimeException {

    public SshPublicKeyException(String message) {
        super(message);
    }

    public SshPublicKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}
