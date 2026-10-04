package io.github.hectorvent.floci.services.iam;

import java.util.List;

/**
 * Reports which IAM server certificates a service currently references.
 *
 * <p>Implemented by services that accept a server certificate on a resource (ELB listeners,
 * CloudFront distributions); consumed by {@code IamQueryHandler} via
 * {@code Instance<ServerCertificateReferenceProvider>} so {@code DeleteServerCertificate} can
 * refuse a certificate that is in use without IAM depending on those services.</p>
 */
public interface ServerCertificateReferenceProvider {

    /**
     * One use of a server certificate.
     *
     * @param certificate the certificate as the referrer stores it: its ARN or its ServerCertificateId
     * @param referrer a description of what holds the reference, for the DeleteConflict message
     */
    record Reference(String certificate, String referrer) {}

    /**
     * @return every server certificate reference this service currently holds, or an empty list
     */
    List<Reference> serverCertificateReferences();
}
