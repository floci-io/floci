package io.floci.aws.services.acm;

import java.util.List;

/** Live references and material refreshes for consumers of ACM certificates. */
public interface AcmCertificateReferenceProvider {

    record CertificateReference(String certificateArn, String resourceArn) {}

    /** Reads recorded references without certificate lookups or consumer locks. */
    List<CertificateReference> acmCertificateReferences();

    /** Called after an import commits and releases the ACM write guard. */
    void certificateReimported(String certificateArn, String region);
}
