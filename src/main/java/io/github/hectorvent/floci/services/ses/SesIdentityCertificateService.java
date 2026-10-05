package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.CertificateMaterialException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.acm.model.Certificate;
import io.github.hectorvent.floci.services.acm.model.CertificateStatus;
import io.github.hectorvent.floci.services.ses.model.IdentityCertificate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * S/MIME certificate associations of email identities (Associate/Disassociate/ListEmailIdentityCertificates).
 * Floci stores them and never signs mail. Messages and precedence follow real SES (probed 2026-10-04):
 * the request members are checked before the identity is looked up, and the certificate itself is
 * not checked when it is associated. The status applies the SES Developer Guide's certificate
 * requirements, plus the ones a probe found SES also enforces, when the list is read: an association
 * is ACTIVE while its identity is verified and its ARN names an issued, currently valid end-entity
 * certificate in Floci's ACM, with an allowed key, the email-signing key usages and an RFC822Name
 * SAN equal to the From address, and FAILED otherwise.
 */
@ApplicationScoped
public class SesIdentityCertificateService {

    private static final Logger LOG = Logger.getLogger(SesIdentityCertificateService.class);

    static final String STATUS_ACTIVE = "ACTIVE";
    static final String STATUS_FAILED = "FAILED";

    private static final int RFC822_NAME = 1;
    private static final int DIGITAL_SIGNATURE = 0;
    private static final String EMAIL_PROTECTION = "1.3.6.1.5.5.7.3.4";
    private static final Set<Integer> RSA_SIGNING_KEY_SIZES = Set.of(2048, 3072, 4096);
    private static final List<String> EC_SIGNING_CURVES = List.of("secp256r1", "secp384r1", "secp521r1");

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
        boolean identityVerified = identityService.isIdentityVerified(emailIdentity, region);
        List<Entry> entries = page.items().stream().map(c -> entry(c, identityVerified, region)).toList();
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

    private Entry entry(IdentityCertificate association, boolean identityVerified, String region) {
        Certificate certificate = identityVerified
                ? usableCertificate(association.certificateArn(), association.fromAddress(), region)
                : null;
        if (certificate == null) {
            return new Entry(association.fromAddress(), STATUS_FAILED, association.certificateArn(), null);
        }
        return new Entry(association.fromAddress(), STATUS_ACTIVE, association.certificateArn(),
                certificate.getNotAfter());
    }

    private Certificate usableCertificate(String certificateArn, String fromAddress, String region) {
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
        Instant now = Instant.now(clock);
        Instant notBefore = certificate.getNotBefore();
        Instant notAfter = certificate.getNotAfter();
        if ((notBefore != null && notBefore.isAfter(now)) || (notAfter != null && !notAfter.isAfter(now))) {
            return null;
        }
        X509Certificate x509 = parse(certificate);
        if (x509 == null || !isSigningKey(x509.getPublicKey()) || !isEmailSigningCertificate(x509)
                || !emailSubjectAlternativeNames(x509).contains(fromAddress)) {
            return null;
        }
        return certificate;
    }

    private static X509Certificate parse(Certificate certificate) {
        if (certificate.getCertificateBody() == null) {
            return null;
        }
        try {
            return Pem.parseCertificate(certificate.getCertificateBody());
        } catch (CertificateMaterialException e) {
            LOG.debugv(e, "Certificate {0} could not be parsed, so its association is FAILED", certificate.getArn());
            return null;
        }
    }

    /**
     * Read from the key itself: ACM's stored KeyAlgorithm folds every RSA size and EC curve it does
     * not name into RSA_2048 or EC_prime256v1.
     */
    private static boolean isSigningKey(PublicKey key) {
        if (key instanceof RSAPublicKey rsa) {
            return RSA_SIGNING_KEY_SIZES.contains(rsa.getModulus().bitLength());
        }
        if (key instanceof ECPublicKey ec) {
            ECParameterSpec params = ec.getParams();
            for (String curve : EC_SIGNING_CURVES) {
                ECParameterSpec named = namedCurve(curve);
                if (named != null && named.getCurve().equals(params.getCurve())
                        && named.getGenerator().equals(params.getGenerator())
                        && named.getOrder().equals(params.getOrder())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * What SES requires beyond the Developer Guide's list (probed 2026-10-06): an end-entity
     * certificate (a CA certificate fails even with both usages) whose key usage includes
     * digitalSignature and whose extended key usage includes emailProtection. Other usages may sit
     * alongside them.
     */
    private static boolean isEmailSigningCertificate(X509Certificate x509) {
        if (x509.getBasicConstraints() != -1) {
            return false;
        }
        boolean[] keyUsage = x509.getKeyUsage();
        if (keyUsage == null || !keyUsage[DIGITAL_SIGNATURE]) {
            return false;
        }
        try {
            List<String> extendedKeyUsage = x509.getExtendedKeyUsage();
            return extendedKeyUsage != null && extendedKeyUsage.contains(EMAIL_PROTECTION);
        } catch (CertificateParsingException e) {
            LOG.debugv(e, "The extended key usage of {0} could not be read",
                    x509.getSubjectX500Principal().getName());
            return false;
        }
    }

    private static ECParameterSpec namedCurve(String name) {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec(name));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            LOG.warnv(e, "EC curve {0} is unavailable, so no key on it counts as a signing key", name);
            return null;
        }
    }

    private static List<String> emailSubjectAlternativeNames(X509Certificate x509) {
        try {
            Collection<List<?>> names = x509.getSubjectAlternativeNames();
            if (names == null) {
                return List.of();
            }
            return names.stream()
                    .filter(name -> Integer.valueOf(RFC822_NAME).equals(name.get(0)))
                    .map(name -> (String) name.get(1))
                    .toList();
        } catch (CertificateParsingException e) {
            LOG.debugv(e, "The SAN of {0} could not be read, so it names no sender",
                    x509.getSubjectX500Principal().getName());
            return List.of();
        }
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
