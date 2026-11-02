package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.services.acm.AcmCertificateReferenceProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/** ACM discovers this consumer lazily, after the load-balancer service has initialized. */
@ApplicationScoped
public class ElbClassicAcmCertificateReferences implements AcmCertificateReferenceProvider {
    private final ElbClassicService service;

    @Inject
    public ElbClassicAcmCertificateReferences(ElbClassicService service) {
        this.service = service;
    }

    @Override
    public List<CertificateReference> acmCertificateReferences() {
        return service.acmCertificateReferences();
    }

    /** A Classic listener serves no TLS of its own, so a reimported certificate has nothing to refresh. */
    @Override
    public void certificateReimported(String certificateArn, String region) {
    }
}
