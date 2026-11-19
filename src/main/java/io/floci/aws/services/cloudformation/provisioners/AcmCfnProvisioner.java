package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.acm.AcmService;
import io.floci.aws.services.acm.model.Certificate;
import io.floci.aws.services.acm.model.CertificateOptions;
import io.floci.aws.services.acm.model.KeyAlgorithm;
import io.floci.aws.services.acm.model.ValidationMethod;
import io.floci.aws.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.HashMap;
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
 *
 * <p>{@code CertificateExport} and {@code CertificateTransparencyLoggingPreference} become the
 * certificate's options. The first is createOnly in the registry schema, so a change replaces the
 * certificate; the second updates in place through {@code UpdateCertificateOptions}, as
 * CloudFormation does.
 *
 * <p>A replacement creates the new certificate and leaves the displaced one to
 * {@link ReplacementCleanup}: it is deleted once the stack update commits, after the resources that
 * referenced it (a CloudFront distribution, an API Gateway domain) moved to the new one, because ACM
 * refuses to delete a certificate that is still in use. A failed update deletes the new certificate
 * and points the resource back at the old one.
 */
@ApplicationScoped
public class AcmCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(AcmCfnProvisioner.class);
    private static final Set<String> OPTION_VALUES = Set.of("ENABLED", "DISABLED");

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
        if (ctx.isUpdate()) {
            // The rollback undoes this update; only ContinueUpdateRollback retries the delete the
            // last rollback could not make.
            ReplacementCleanup.endRollbackRetry(r);
        }
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String domainName = ctx.resolveOptional(props, "DomainName");
        if (domainName == null || domainName.isBlank()) {
            throw new AwsException("ValidationError", "AWS::CertificateManager::Certificate requires DomainName", 400);
        }
        List<String> sans = ctx.resolveStringList(props, "SubjectAlternativeNames");
        String method = ctx.resolveOptional(props, "ValidationMethod");
        ValidationMethod validationMethod = method == null ? ValidationMethod.DNS : ValidationMethod.valueOf(method);
        KeyAlgorithm keyAlgorithm = KeyAlgorithm.fromAwsName(ctx.resolveOptional(props, "KeyAlgorithm"));
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        String export = optionValue(ctx, props, "CertificateExport", "DISABLED");
        String transparencyLogging = optionValue(ctx, props, "CertificateTransparencyLoggingPreference", "ENABLED");
        CertificateOptions options = new CertificateOptions(transparencyLogging, export);

        // DomainName, SubjectAlternativeNames, KeyAlgorithm and CertificateExport are createOnly in
        // the schema: a change to any of them replaces the certificate, and the previous one is left
        // to ReplacementCleanup. Anything else updates in place.
        Certificate existing = ctx.isUpdate() ? findExisting(ctx.priorPhysicalId(), ctx.region()) : null;
        String arn;
        if (existing != null && sameCreateOnlyProperties(existing, domainName, sans, keyAlgorithm, export)) {
            arn = existing.getArn();
            reconcileTags(arn, tags, ctx.region());
            reconcileTransparencyLogging(existing, transparencyLogging, ctx.region());
        } else {
            arn = acmService.requestCertificate(domainName, sans, validationMethod, null,
                    keyAlgorithm, null, options, tags, ctx.region()).getArn();
        }
        r.setPhysicalId(arn);
        r.getAttributes().put("CertificateArn", arn);
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        CfnDeletes.safeDelete("certificate", physicalId,
                () -> acmService.deleteCertificate(physicalId, region), "ResourceNotFoundException");
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return ReplacementCleanup.rollback(resource, this::delete);
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
                                                    List<String> sans, KeyAlgorithm keyAlgorithm,
                                                    String export) {
        Set<String> desiredNames = new LinkedHashSet<>();
        desiredNames.add(domainName);
        desiredNames.addAll(sans);
        Set<String> storedNames = existing.getSubjectAlternativeNames() == null
                ? Set.of(existing.getDomainName())
                : new LinkedHashSet<>(existing.getSubjectAlternativeNames());
        return domainName.equals(existing.getDomainName())
                && desiredNames.equals(storedNames)
                && keyAlgorithm == existing.getKeyAlgorithm()
                && export.equals(storedOptions(existing).export());
    }

    /**
     * An ENABLED or DISABLED option, defaulted the way CloudFormation documents when absent. Blank
     * counts as absent: the engine resolves {@code AWS::NoValue} to an empty string.
     */
    private static String optionValue(ProvisionContext ctx, JsonNode props, String name, String defaultValue) {
        String value = ctx.resolveOptional(props, name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        if (!OPTION_VALUES.contains(value)) {
            throw new AwsException("ValidationError", "AWS::CertificateManager::Certificate " + name
                    + " must be ENABLED or DISABLED, not " + value, 400);
        }
        return value;
    }

    /** An imported certificate carries no options; the service reads that as the defaults, so this does too. */
    private static CertificateOptions storedOptions(Certificate existing) {
        return existing.getCertOptions() != null ? existing.getCertOptions() : CertificateOptions.defaultOptions();
    }

    /**
     * Runs after the tag reconcile, which carries the validation: this call can only fail when the
     * certificate is gone, so a rejected tag never leaves the option changed on a rolled-back update.
     * Export is never sent here: ACM refuses to change it, so a changed value replaces the certificate.
     */
    private void reconcileTransparencyLogging(Certificate existing, String desired, String region) {
        if (!desired.equals(storedOptions(existing).certificateTransparencyLoggingPreference())) {
            acmService.updateCertificateOptions(existing.getArn(), new CertificateOptions(desired, null), region);
        }
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
