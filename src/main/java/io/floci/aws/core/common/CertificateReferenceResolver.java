package io.floci.aws.core.common;

import io.floci.aws.services.acm.AcmService;
import io.floci.aws.services.acm.model.Certificate;
import io.floci.aws.services.acm.model.CertificateStatus;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.ServerCertificate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Whether a certificate another service names exists, and the lock that keeps the answer true for
 * the length of a write.
 *
 * <p>A load balancer listener or a CloudFront distribution serves a certificate it does not own:
 * IAM holds server certificates and ACM holds the rest, and the referring service carries only the
 * ARN. Both of those lookups and the lock belong to neither referrer, which is why they live here
 * rather than in one service's package.
 *
 * <p>A service that is not present answers for its own certificates: an absent {@link IamService}
 * or {@link AcmService} leaves every ARN in its namespace existing rather than missing. That keeps
 * a unit test that wires one referrer and no certificate service on the behaviour it had before a
 * check existed, which is the same judgement the referrers make about an absent resolver.
 */
@ApplicationScoped
public class CertificateReferenceResolver {

    private static final Logger LOG = Logger.getLogger(CertificateReferenceResolver.class);

    private final IamService iamService;
    private final AcmService acmService;

    @Inject
    public CertificateReferenceResolver(IamService iamService, AcmService acmService) {
        this.iamService = iamService;
        this.acmService = acmService;
    }

    /**
     * Whether the ARN names a certificate that exists. Anything that is not an ARN of a service
     * that serves certificates does not name one, so it does not exist either.
     */
    public boolean exists(String region, String certificateArn) {
        if (certificateArn == null || certificateArn.isBlank()
                || !AwsArnUtils.isArn(certificateArn)) {
            return false;
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(certificateArn);
        return switch (arn.service()) {
            case "iam" -> iamService == null || iamServerCertificate(arn, certificateArn).isPresent();
            case "acm" -> acmService == null || acmCertificateExists(region, certificateArn);
            default -> false;
        };
    }

    /** Detached TLS material for a reference already recorded by a certificate consumer. */
    public record CertificateMaterial(String arn, String certificateBody, String privateKey, String certificateChain) {}

    /**
     * Reads a published reference in its owner's account. IAM retains former ARNs after a rename,
     * so an existing reference keeps its certificate while new references still pass through
     * {@link #exists}. The returned strings are snapshots; background socket callbacks must not
     * repeat an account-sensitive lookup after the request context has ended.
     */
    public CertificateMaterial publishedMaterial(String ownerArn, String region, String certificateArn) {
        AwsArnUtils.Arn owner = AwsArnUtils.parse(ownerArn);
        AwsArnUtils.Arn certificate = AwsArnUtils.parse(certificateArn);
        if (owner.accountId().isBlank() || !owner.accountId().equals(certificate.accountId())
                || !owner.partition().equals(certificate.partition())) {
            throw missingCertificate();
        }
        return RequestScopes.callAs(owner.accountId(), region, () -> {
            if ("iam".equals(certificate.service()) && iamService != null) {
                return iamService.supplyWithServerCertificatesHeld(() -> {
                    ServerCertificate stored = iamServerCertificate(certificate, certificateArn)
                            .orElseGet(() -> iamService.listServerCertificates(null).stream()
                                    .filter(value -> value.getFormerArns().contains(certificateArn))
                                    .findFirst().orElseThrow(CertificateReferenceResolver::missingCertificate));
                    return new CertificateMaterial(certificateArn, stored.getCertificateBody(),
                            stored.getPrivateKey(), stored.getCertificateChain());
                });
            }
            if ("acm".equals(certificate.service()) && acmService != null && region.equals(certificate.region())) {
                return acmService.supplyWithCertificatesHeld(() -> {
                    try {
                        Certificate stored = acmService.getCertificate(certificateArn, region);
                        if (!certificateArn.equals(stored.getArn()) || stored.getStatus() != CertificateStatus.ISSUED) {
                            throw missingCertificate();
                        }
                        return new CertificateMaterial(certificateArn, stored.getCertificateBody(),
                                stored.getPrivateKey(), stored.getCertificateChain());
                    } catch (AwsException failure) {
                        if ("ResourceNotFoundException".equals(failure.getErrorCode())) {
                            throw missingCertificate();
                        }
                        throw failure;
                    }
                });
            }
            throw missingCertificate();
        });
    }

    private static AwsException missingCertificate() {
        return new AwsException("CertificateNotFound", "The specified certificate does not exist.", 400);
    }

    /** Runs reference publication under IAM then ACM guards for the complete effective list. */
    public void runWhileHeld(Collection<String> certificateArns, Runnable action) {
        supplyWhileHeld(certificateArns, () -> {
            action.run();
            return null;
        });
    }

    public <T> T supplyWhileHeld(Collection<String> certificateArns, Supplier<T> action) {
        Supplier<T> acmGuard = () -> acmService != null && namesCertificateService(certificateArns, "acm")
                ? acmService.supplyWithCertificatesHeld(action) : action.get();
        return iamService != null && namesIamCertificate(certificateArns)
                ? iamService.supplyWithServerCertificatesHeld(acmGuard) : acmGuard.get();
    }

    /** ELB usability is narrower than existence, which other consumers also query. */
    public boolean isUsableAcmCertificate(String region, String certificateArn) {
        if (certificateArn == null || !AwsArnUtils.isArn(certificateArn)
                || !"acm".equals(AwsArnUtils.parse(certificateArn).service()) || acmService == null) {
            return true;
        }
        try {
            return acmService.supplyWithCertificatesHeld(() -> {
                Certificate certificate = acmService.getCertificate(certificateArn, region);
                return certificateArn.equals(certificate.getArn()) && certificate.getStatus() == CertificateStatus.ISSUED;
            });
        } catch (AwsException failure) {
            if ("ResourceNotFoundException".equals(failure.getErrorCode())) {
                return false;
            }
            throw failure;
        }
    }

    private boolean namesCertificateService(Collection<String> arns, String service) {
        return arns != null && arns.stream().anyMatch(arn -> arn != null && AwsArnUtils.isArn(arn)
                && service.equals(AwsArnUtils.parse(arn).service()));
    }

    /** Whether any of these ARNs names a certificate IAM serves, rather than ACM. */
    public boolean namesIamCertificate(Collection<String> certificateArns) {
        if (certificateArns == null) {
            return false;
        }
        for (String certificateArn : certificateArns) {
            if (certificateArn != null && !certificateArn.isBlank()
                    && AwsArnUtils.isArn(certificateArn)
                    && "iam".equals(AwsArnUtils.parse(certificateArn).service())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The certificate the ARN names, by the name it carries. Only the certificate's current ARN
     * counts: a rename keeps the former ones for the reference check that
     * {@code DeleteServerCertificate} makes, and they do not name a certificate a new listener can
     * be given.
     */
    private Optional<ServerCertificate> iamServerCertificate(AwsArnUtils.Arn arn,
                                                             String certificateArn) {
        if (!arn.resource().startsWith("server-certificate/")) {
            return Optional.empty();
        }
        String name = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
        return iamService.findServerCertificate(name)
                .filter(certificate -> certificateArn.equals(certificate.getArn()));
    }

    private boolean acmCertificateExists(String region, String certificateArn) {
        try {
            return certificateArn.equals(
                    acmService.describeCertificate(certificateArn, region).getArn());
        } catch (AwsException e) {
            LOG.debugv("ACM certificate {0} not usable in {1}: {2}", certificateArn, region,
                    e.getMessage());
            return false;
        }
    }
}
