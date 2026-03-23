package io.github.hectorvent.floci.services.acm;

/**
 * Exception thrown when certificate generation or parsing fails.
 */
public class CertificateGenerationException extends RuntimeException {

    public CertificateGenerationException(String message) {
        super(message);
    }

    public CertificateGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
