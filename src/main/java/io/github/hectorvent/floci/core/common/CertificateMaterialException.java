package io.github.hectorvent.floci.core.common;

/**
 * Thrown when PEM certificate or private-key material cannot be read, or when certificate
 * generation fails.
 *
 * <p>Lives here rather than beside a single service because the material it describes is read by
 * {@code config} and by several services, not just by ACM.
 */
public class CertificateMaterialException extends RuntimeException {

    public CertificateMaterialException(String message) {
        super(message);
    }

    public CertificateMaterialException(String message, Throwable cause) {
        super(message, cause);
    }
}
