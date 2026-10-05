package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.acm.model.Certificate;
import io.github.hectorvent.floci.services.acm.model.CertificateStatus;
import io.github.hectorvent.floci.services.ses.model.IdentityCertificate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * S/MIME certificate associations of email identities (Associate/Disassociate/ListEmailIdentityCertificates).
 * Floci stores them and never signs mail. Messages and precedence follow real SES (probed 2026-10-04):
 * the request members are checked before the identity is looked up, and the certificate itself is
 * not checked when it is associated. The status is Floci's own rule, since SES never reported
 * ACTIVE in the probe: an association is ACTIVE while its ARN names an issued, unexpired
 * certificate in Floci's ACM, and FAILED otherwise.
 */
@ApplicationScoped
public class SesIdentityCertificateService {

    private static final Logger LOG = Logger.getLogger(SesIdentityCertificateService.class);

    static final String STATUS_ACTIVE = "ACTIVE";
    static final String STATUS_FAILED = "FAILED";

    private static final Pattern CERTIFICATE_ARN =
            Pattern.compile("arn:[\\w+=/,.@-]+:[\\w+=/,.@-]+:[\\w+=/,.@-]*:[0-9]+:certificate/[\\w+=,.@-]+");

    private final StorageBackend<String, IdentityCertificate> store;
    private final SesIdentityService identityService;
    private final AcmService acmService;
    private final Clock clock;
    // Keeps an association and the identity delete-guard from interleaving, so a delete never
    // leaves an association behind on a gone identity.
    private final Object mutationLock = new Object();

    @Inject
    public SesIdentityCertificateService(StorageFactory storageFactory, SesIdentityService identityService,
                                         AcmService acmService, Clock clock) {
        this(storageFactory.create("ses", "ses-identity-certificates.json",
                new TypeReference<Map<String, IdentityCertificate>>() {}), identityService, acmService, clock);
    }

    SesIdentityCertificateService(StorageBackend<String, IdentityCertificate> store,
                                  SesIdentityService identityService, AcmService acmService, Clock clock) {
        this.store = store;
        this.identityService = identityService;
        this.acmService = acmService;
        this.clock = clock;
    }

    /** A view of an association with its current status, for ListEmailIdentityCertificates. */
    public record Entry(String fromAddress, String status, String certificateArn, Instant expiryTime) {}

    public void associate(String emailIdentity, String fromAddress, String certificateArn, String region) {
        List<String> violations = new ArrayList<>();
        requireIdentityMember(emailIdentity, violations);
        if (certificateArn == null) {
            violations.add(violation("certificateArn", "Member must not be null"));
        } else {
            if (certificateArn.length() < 20) {
                violations.add(violation("certificateArn", "Member must have length greater than or equal to 20"));
            }
            if (certificateArn.length() > 2048) {
                violations.add(violation("certificateArn", "Member must have length less than or equal to 2048"));
            }
            if (!CERTIFICATE_ARN.matcher(certificateArn).matches()) {
                violations.add(violation("certificateArn",
                        "Member must satisfy regular expression pattern: " + CERTIFICATE_ARN.pattern()));
            }
        }
        throwViolations(violations);
        String sender = resolveFromAddress(emailIdentity, fromAddress);
        synchronized (mutationLock) {
            requireIdentity(emailIdentity, region);
            String key = key(region, emailIdentity, sender);
            IdentityCertificate existing = store.get(key).orElse(null);
            if (existing != null) {
                throw new AwsException("AlreadyExistsException",
                        "A certificate is already associated with sender <" + existing.fromAddress()
                                + "> on identity <" + emailIdentity + ">.", 400);
            }
            store.put(key, new IdentityCertificate(emailIdentity, sender, certificateArn, Instant.now(clock)));
        }
        LOG.infov("Associated certificate {0} with sender {1} on identity {2} in region {3}",
                certificateArn, sender, emailIdentity, region);
    }

    /** Idempotent: an identity with no association for the address is a silent success, as on SES. */
    public void disassociate(String emailIdentity, String fromAddress, String region) {
        List<String> violations = new ArrayList<>();
        requireIdentityMember(emailIdentity, violations);
        throwViolations(violations);
        String sender = resolveFromAddress(emailIdentity, fromAddress);
        synchronized (mutationLock) {
            requireIdentity(emailIdentity, region);
            store.delete(key(region, emailIdentity, sender));
        }
        LOG.infov("Disassociated certificate from sender {0} on identity {1} in region {2}",
                sender, emailIdentity, region);
    }

    public PaginatedResult<Entry> list(String emailIdentity, Integer pageSize, String nextToken, String region) {
        List<String> violations = new ArrayList<>();
        requireIdentityMember(emailIdentity, violations);
        throwViolations(violations);
        SesListPaging paging = SesListPaging.V2_LIST_EMAIL_IDENTITY_CERTIFICATES;
        paging.checkRequest(pageSize, nextToken);
        requireIdentity(emailIdentity, region);
        List<IdentityCertificate> associations = store.scan(k -> k.startsWith(identityPrefix(region, emailIdentity)));
        PaginatedResult<IdentityCertificate> page = paging.page(region, emailIdentity, associations,
                c -> c.fromAddress().toLowerCase(Locale.ROOT), pageSize, nextToken);
        List<Entry> entries = page.items().stream().map(c -> entry(c, region)).toList();
        return new PaginatedResult<>(entries, page.nextToken());
    }

    /**
     * Runs an identity deletion under the association lock. SES refuses to delete an identity that
     * still has a certificate associated, whatever its status, and does not cascade.
     */
    public void deleteIdentityGuarded(String emailIdentity, String accountId, String region, Runnable deleteAction) {
        synchronized (mutationLock) {
            if (store.keys().stream().anyMatch(k -> k.startsWith(identityPrefix(region, emailIdentity)))) {
                String identityArn = AwsArnUtils.Arn.of("ses", region, accountId, "identity/" + emailIdentity)
                        .toString();
                throw new AwsException("BadRequestException",
                        "Cannot delete <" + identityArn + "> because it has certificates associated with it. "
                                + "Disassociate all certificates and try again.", 400);
            }
            deleteAction.run();
        }
    }

    private Entry entry(IdentityCertificate association, String region) {
        Certificate certificate = usableCertificate(association.certificateArn(), region);
        if (certificate == null) {
            return new Entry(association.fromAddress(), STATUS_FAILED, association.certificateArn(), null);
        }
        return new Entry(association.fromAddress(), STATUS_ACTIVE, association.certificateArn(),
                certificate.getNotAfter());
    }

    private Certificate usableCertificate(String certificateArn, String region) {
        Certificate certificate;
        try {
            certificate = acmService.getCertificate(certificateArn, region);
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Certificate {0} is not in ACM, so its association is FAILED", certificateArn);
            return null;
        }
        // ACM looks the certificate up by its id alone, so an ARN naming another region or account
        // could still find one here.
        if (!certificateArn.equals(certificate.getArn()) || certificate.getStatus() != CertificateStatus.ISSUED) {
            return null;
        }
        Instant notAfter = certificate.getNotAfter();
        if (notAfter != null && !notAfter.isAfter(Instant.now(clock))) {
            return null;
        }
        return certificate;
    }

    private void requireIdentity(String emailIdentity, String region) {
        if (identityService.find(emailIdentity, region).isEmpty()) {
            throw new AwsException("NotFoundException", "Email identity <" + emailIdentity + "> does not exist.", 404);
        }
    }

    /**
     * The sender an association is keyed by. A domain identity needs an address in the domain or one
     * of its subdomains; an email identity defaults to itself. These checks come before the identity
     * is looked up, so whether the identity is a domain is read from its shape.
     */
    private static String resolveFromAddress(String emailIdentity, String fromAddress) {
        boolean domain = !emailIdentity.contains("@");
        if (fromAddress == null) {
            if (domain) {
                throw badRequest("FromAddress is required when EmailIdentity is a domain.");
            }
            return emailIdentity;
        }
        int at = fromAddress.lastIndexOf('@');
        if (at <= 0 || at == fromAddress.length() - 1 || fromAddress.indexOf('@') != at
                || fromAddress.chars().anyMatch(Character::isWhitespace)) {
            throw badRequest("FromAddress <" + fromAddress + "> is not a valid email address.");
        }
        if (domain) {
            String addressDomain = fromAddress.substring(at + 1).toLowerCase(Locale.ROOT);
            String identityDomain = emailIdentity.toLowerCase(Locale.ROOT);
            if (!addressDomain.equals(identityDomain) && !addressDomain.endsWith("." + identityDomain)) {
                throw badRequest("FromAddress <" + fromAddress + "> does not belong to the domain identity <"
                        + emailIdentity + ">.");
            }
        } else if (!fromAddress.equals(emailIdentity)) {
            // The model requires an exact match; SES's wording for a mismatch was not probed.
            throw badRequest("FromAddress <" + fromAddress + "> does not match the email identity <"
                    + emailIdentity + ">.");
        }
        return fromAddress;
    }

    private static void requireIdentityMember(String emailIdentity, List<String> violations) {
        if (emailIdentity == null) {
            violations.add(violation("emailIdentity", "Member must not be null"));
        } else if (emailIdentity.isEmpty()) {
            violations.add(violation("emailIdentity", "Member must have length greater than or equal to 1"));
        }
    }

    private static String violation(String member, String constraint) {
        return "Value at '" + member + "' failed to satisfy constraint: " + constraint;
    }

    private static void throwViolations(List<String> violations) {
        if (violations.isEmpty()) {
            return;
        }
        String count = violations.size() == 1 ? "1 validation error detected: "
                : violations.size() + " validation errors detected: ";
        throw badRequest(count + String.join("; ", violations));
    }

    private static AwsException badRequest(String message) {
        return new AwsException("BadRequestException", message, 400);
    }

    private static String identityPrefix(String region, String emailIdentity) {
        return "certificate::" + region + "::" + emailIdentity.length() + "::" + emailIdentity + "::";
    }

    private static String key(String region, String emailIdentity, String fromAddress) {
        return identityPrefix(region, emailIdentity) + fromAddress.toLowerCase(Locale.ROOT);
    }
}
