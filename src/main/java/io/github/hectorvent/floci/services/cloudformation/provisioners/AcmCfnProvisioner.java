package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.acm.model.Certificate;
import io.github.hectorvent.floci.services.acm.model.CertificateOptions;
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
 *
 * <p>{@code CertificateExport} and {@code CertificateTransparencyLoggingPreference} become the
 * certificate's options. The first is createOnly in the registry schema, so a change replaces the
 * certificate; the second updates in place through {@code UpdateCertificateOptions}, as
 * CloudFormation does.
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
        // the schema: a change to any of them replaces the certificate, and there is no generic
        // replacement flow, so the previous one is deleted here once the new one exists. Anything
        // else updates in place.
        Certificate existing = ctx.isUpdate() ? findExisting(ctx.priorPhysicalId(), ctx.region()) : null;
        String arn;
        if (existing != null && sameCreateOnlyProperties(existing, domainName, sans, keyAlgorithm, export)) {
            arn = existing.getArn();
            reconcileTags(arn, tags, ctx.region());
            reconcileTransparencyLogging(existing, transparencyLogging, ctx.region());
        } else {
            arn = acmService.requestCertificate(domainName, sans, validationMethod, null,
                    keyAlgorithm, null, options, tags, ctx.region()).getArn();
            if (existing != null) {
                deletePriorOrUnwind(r, existing.getArn(), arn, ctx.region());
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

    /**
     * Removes the certificate a replacement supersedes. If that fails (ResourceInUseException, for
     * one), the update fails and CloudFormationService restores the previous StackResource without
     * ever learning the new ARN, so the certificate this attempt created is removed here and the
     * rollback walker is told the prior one is intact.
     */
    private void deletePriorOrUnwind(StackResource r, String priorArn, String newArn, String region) {
        try {
            delete(r.getResourceType(), priorArn, region);
        } catch (AwsException failure) {
            LOG.warnv("Could not delete certificate {0} replaced by {1}, removing the replacement: {2}",
                    priorArn, newArn, failure.getMessage());
            acmService.deleteCertificate(newArn, region);
            r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR, "true");
            throw failure;
        }
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
