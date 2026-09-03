package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.acm.model.Certificate;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.acm.model.ValidationMethod;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provisions {@code AWS::CertificateManager::Certificate}.
 *
 * <p>The certificate is ISSUED as soon as it is requested. Real ACM waits for DNS or email
 * validation and CloudFormation waits with it; the emulator has nothing to validate, so
 * {@code DomainValidationOptions} is accepted and ignored.
 */
@ApplicationScoped
public class AcmCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(AcmCfnProvisioner.class);

    private final AcmService acmService;

    public AcmCfnProvisioner(AcmService acmService) {
        this.acmService = acmService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::CertificateManager::Certificate");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String domainName = ctx.resolveOptional(props, "DomainName");
        if (domainName == null || domainName.isBlank()) {
            throw new IllegalArgumentException("AWS::CertificateManager::Certificate requires DomainName");
        }
        List<String> sans = ctx.resolveStringList(props, "SubjectAlternativeNames");
        String method = ctx.resolveOptional(props, "ValidationMethod");
        ValidationMethod validationMethod = method == null ? ValidationMethod.DNS : ValidationMethod.valueOf(method);
        KeyAlgorithm keyAlgorithm = KeyAlgorithm.fromAwsName(ctx.resolveOptional(props, "KeyAlgorithm"));
        Map<String, String> tags = ctx.resolveTags(props, "Tags");

        // DomainName, SubjectAlternativeNames and KeyAlgorithm are createOnly in the schema: a change
        // to any of them replaces the certificate, and there is no generic replacement flow, so the
        // previous one is deleted here once the new one exists. Anything else updates in place.
        Certificate existing = ctx.isUpdate() ? findExisting(ctx.priorPhysicalId(), ctx.region()) : null;
        String arn;
        if (existing != null && sameCreateOnlyProperties(existing, domainName, sans, keyAlgorithm)) {
            arn = existing.getArn();
            reconcileTags(arn, tags, ctx.region());
        } else {
            arn = acmService.requestCertificate(domainName, sans, validationMethod, null,
                    keyAlgorithm, null, null, tags, ctx.region()).getArn();
            if (existing != null) {
                delete(r.getResourceType(), existing.getArn(), ctx.region());
            }
        }
        r.setPhysicalId(arn);
        r.getAttributes().put("CertificateArn", arn);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        CfnDeletes.safeDelete("certificate", physicalId,
                () -> acmService.deleteCertificate(physicalId, region), "ResourceNotFoundException");
    }

    private Certificate findExisting(String arn, String region) {
        try {
            return acmService.describeCertificate(arn, region);
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Certificate {0} from the previous execution is gone, requesting a new one", arn);
            return null;
        }
    }

    private static boolean sameCreateOnlyProperties(Certificate existing, String domainName,
                                                    List<String> sans, KeyAlgorithm keyAlgorithm) {
        Set<String> desiredNames = new LinkedHashSet<>();
        desiredNames.add(domainName);
        desiredNames.addAll(sans);
        Set<String> storedNames = existing.getSubjectAlternativeNames() == null
                ? Set.of(existing.getDomainName())
                : new LinkedHashSet<>(existing.getSubjectAlternativeNames());
        return domainName.equals(existing.getDomainName())
                && desiredNames.equals(storedNames)
                && keyAlgorithm == existing.getKeyAlgorithm();
    }

    private void reconcileTags(String arn, Map<String, String> desired, String region) {
        List<Map<String, String>> stale = ProvisionContext
                .staleTagKeys(acmService.listTagsForCertificate(arn, region), desired).stream()
                .map(key -> Map.of("Key", key))
                .toList();
        if (!stale.isEmpty()) {
            acmService.removeTagsFromCertificate(arn, stale, region);
        }
        if (!desired.isEmpty()) {
            acmService.addTagsToCertificate(arn, desired, region);
        }
    }
}
