package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.acm.model.Certificate;
import io.github.hectorvent.floci.services.acm.model.CertificateStatus;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The ACM answers the user pool domain unit tests share: a custom domain needs an issued certificate
 * in us-east-1, so a mocked {@link AcmService} has to describe one before a domain can be created.
 */
final class CognitoCertificateStubs {

    private static final String CERTIFICATE_REGION = "us-east-1";

    private CognitoCertificateStubs() {
    }

    /** Every certificate asked for in us-east-1 exists and is issued, unless a test says otherwise. */
    static void everyCertificateIsIssued(AcmService acmService) {
        when(acmService.describeCertificate(anyString(), eq(CERTIFICATE_REGION)))
                .thenAnswer(invocation -> issuedCertificate(invocation.getArgument(0)));
    }

    static Certificate issuedCertificate(String arn) {
        Certificate certificate = new Certificate();
        certificate.setArn(arn);
        certificate.setStatus(CertificateStatus.ISSUED);
        return certificate;
    }
}
