package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.services.acm.AcmCertificateReferenceProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/** ACM discovers this consumer lazily, after the CloudFront service has initialized. */
@ApplicationScoped
public class CloudFrontAcmCertificateReferences implements AcmCertificateReferenceProvider {
    private final CloudFrontService service;

    @Inject
    public CloudFrontAcmCertificateReferences(CloudFrontService service) {
        this.service = service;
    }

    @Override
    public List<CertificateReference> acmCertificateReferences() {
        return service.acmCertificateReferences();
    }

    /** The emulated edge serves no TLS from the viewer certificate, so there is nothing to refresh. */
    @Override
    public void certificateReimported(String certificateArn, String region) {
    }
}
