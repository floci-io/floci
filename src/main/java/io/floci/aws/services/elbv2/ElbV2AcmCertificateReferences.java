package io.floci.aws.services.elbv2;

import io.floci.aws.services.acm.AcmCertificateReferenceProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/** ACM discovers this consumer lazily, after the load-balancer service has initialized. */
@ApplicationScoped
public class ElbV2AcmCertificateReferences implements AcmCertificateReferenceProvider {
    private final ElbV2Service service;

    @Inject
    public ElbV2AcmCertificateReferences(ElbV2Service service) {
        this.service = service;
    }

    @Override
    public List<CertificateReference> acmCertificateReferences() {
        return service.acmCertificateReferences();
    }

    @Override
    public void certificateReimported(String certificateArn, String region) {
        service.certificateReimported(certificateArn, region);
    }
}
