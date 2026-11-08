package io.floci.aws.services.acm.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Certificate type indicating how the certificate was provisioned.
 *
 * @see <a href="https://docs.aws.amazon.com/acm/latest/APIReference/API_CertificateDetail.html">AWS ACM CertificateDetail</a>
 */
@RegisterForReflection
public enum CertificateType {
    AMAZON_ISSUED,
    IMPORTED,
    PRIVATE
}
