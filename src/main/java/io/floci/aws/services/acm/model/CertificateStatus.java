package io.floci.aws.services.acm.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Certificate lifecycle status values matching AWS ACM.
 *
 * @see <a href="https://docs.aws.amazon.com/acm/latest/APIReference/API_CertificateDetail.html">AWS ACM CertificateDetail</a>
 */
@RegisterForReflection
public enum CertificateStatus {
    PENDING_VALIDATION,
    ISSUED,
    INACTIVE,
    EXPIRED,
    VALIDATION_TIMED_OUT,
    REVOKED,
    FAILED
}
