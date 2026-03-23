package io.github.hectorvent.floci.services.acm.model;

public enum CertificateStatus {
    PENDING_VALIDATION,
    ISSUED,
    INACTIVE,
    EXPIRED,
    VALIDATION_TIMED_OUT,
    REVOKED,
    FAILED
}
