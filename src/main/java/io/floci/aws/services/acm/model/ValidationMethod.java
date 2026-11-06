package io.floci.aws.services.acm.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Domain validation method for certificate issuance.
 *
 * @see <a href="https://docs.aws.amazon.com/acm/latest/APIReference/API_RequestCertificate.html">AWS ACM RequestCertificate</a>
 */
@RegisterForReflection
public enum ValidationMethod {
    DNS,
    EMAIL
}
