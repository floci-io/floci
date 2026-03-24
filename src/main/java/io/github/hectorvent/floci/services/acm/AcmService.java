package io.github.hectorvent.floci.services.acm;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.acm.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import java.util.LinkedHashSet;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * ACM (AWS Certificate Manager) service implementation for the local emulator.
 *
 * <p>Provides X.509 certificate management operations compatible with the AWS ACM API,
 * including certificate request, import, export, and lifecycle management.</p>
 *
 * @see <a href="https://docs.aws.amazon.com/acm/latest/APIReference/Welcome.html">AWS ACM API Reference</a>
 */
@ApplicationScoped
public class AcmService {

    private static final Logger LOG = Logger.getLogger(AcmService.class);
    private static final int MAX_TAGS = 50;
    private static final int MAX_TAG_KEY_LENGTH = 128;
    private static final int MAX_TAG_VALUE_LENGTH = 256;
    private static final int MAX_SANS = 100;
    private static final int MAX_DOMAIN_LENGTH = 253;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final StorageBackend<String, Certificate> store;
    private final CertificateGenerator certificateGenerator;
    private final RegionResolver regionResolver;
    private final int validationWaitSeconds;
    private final AtomicInteger accountDaysBeforeExpiry = new AtomicInteger(45);
    private final AtomicBoolean securityWarningLogged = new AtomicBoolean(false);

    /**
     * Idempotency token cache using lazy expiration (1-hour TTL).
     *
     * <p>Design decision: Using ConcurrentHashMap with timestamp-based entries
     * instead of Caffeine or scheduled cleanup. This matches moto's approach
     * (see moto/acm/models.py:478-505) where expired entries are removed on
     * lookup. No background thread needed - acceptable for emulator workloads.</p>
     *
     * @see <a href="https://github.com/getmoto/moto/blob/main/moto/acm/models.py">moto ACM</a>
     */
    private final ConcurrentHashMap<String, IdempotencyTokenEntry> idempotencyTokenIndex = new ConcurrentHashMap<>();

    @Inject
    public AcmService(StorageFactory factory, CertificateGenerator certificateGenerator,
                      EmulatorConfig config, RegionResolver regionResolver) {
        this(factory.create("acm", "acm-certificates.json",
                new TypeReference<Map<String, Certificate>>() {}),
            certificateGenerator,
            regionResolver,
            config.services().acm().validationWaitSeconds());
    }

    AcmService(StorageBackend<String, Certificate> store, CertificateGenerator certificateGenerator,
               RegionResolver regionResolver, int validationWaitSeconds) {
        this.store = store;
        this.certificateGenerator = certificateGenerator;
        this.regionResolver = regionResolver;
        this.validationWaitSeconds = validationWaitSeconds;

        // Validate Root CA resource availability
        validateRootCaResource();
    }

    /**
     * Log security warning once on first certificate creation/import.
     * Uses AtomicBoolean to ensure thread-safe single logging.
     */
    private void logSecurityWarningOnce() {
        if (securityWarningLogged.compareAndSet(false, true)) {
            LOG.warn("SECURITY WARNING: ACM emulator stores private keys in plaintext. " +
                     "This is acceptable for local development but NOT for production use.");
        }
    }

    private void validateRootCaResource() {
        try (InputStream is = getClass().getResourceAsStream("/certs/amazon-root-ca.pem")) {
            if (is == null) {
                LOG.warn("Amazon Root CA certificate not found at /certs/amazon-root-ca.pem - " +
                         "certificate chains will be empty");
            } else {
                LOG.info("Amazon Root CA certificate loaded successfully");
            }
        } catch (IOException e) {
            LOG.warnv("Failed to validate Root CA resource: {0}", e.getMessage());
        }
    }

    // ============ RequestCertificate ============

    public Certificate requestCertificate(String domainName, List<String> sans, ValidationMethod validationMethod,
                                          String idempotencyToken, KeyAlgorithm keyAlgorithm,
                                          String certAuthorityArn, CertificateOptions options,
                                          Map<String, String> tags, String region) {
        logSecurityWarningOnce();
        validateDomainName(domainName);
        validateSans(sans);
        if (tags != null) {
            validateTags(tags);
        }

        KeyAlgorithm alg = keyAlgorithm != null ? keyAlgorithm : KeyAlgorithm.RSA_2048;

        // Check idempotency with parameter validation
        if (idempotencyToken != null && !idempotencyToken.isEmpty()) {
            int requestHash = computeRequestHash(domainName, sans, alg);
            Optional<Certificate> existing = findByIdempotencyToken(idempotencyToken, region, requestHash);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        String certId = UUID.randomUUID().toString();
        String arn = buildCertificateArn(region, certId);

        // Determine certificate type and initial status
        CertificateType type;
        CertificateStatus status;
        if (certAuthorityArn != null && !certAuthorityArn.isEmpty()) {
            type = CertificateType.PRIVATE;
            status = CertificateStatus.ISSUED;
        } else {
            type = CertificateType.AMAZON_ISSUED;
            status = validationWaitSeconds > 0 ? CertificateStatus.PENDING_VALIDATION : CertificateStatus.ISSUED;
        }

        // Generate real X.509 certificate
        CertificateGenerator.GeneratedCertificate generated = certificateGenerator.generateCertificate(
            domainName, sans, alg
        );

        Instant now = Instant.now();

        Certificate cert = new Certificate();
        cert.setArn(arn);
        cert.setDomainName(domainName);

        // Use LinkedHashSet for O(1) deduplication while preserving insertion order
        LinkedHashSet<String> allSans = new LinkedHashSet<>();
        allSans.add(domainName);
        if (sans != null) {
            allSans.addAll(sans);
        }
        cert.setSubjectAlternativeNames(new ArrayList<>(allSans));

        cert.setStatus(status);
        cert.setType(type);
        cert.setValidationMethod(validationMethod != null ? validationMethod : ValidationMethod.DNS);
        cert.setCreatedAt(now);
        cert.setIssuedAt(status == CertificateStatus.ISSUED ? now : null);
        cert.setNotBefore(generated.notBefore());
        cert.setNotAfter(generated.notAfter());
        cert.setSerial(generated.serial());
        cert.setSubject(generated.subject());
        cert.setIssuer(generated.issuer());
        cert.setKeyAlgorithm(alg);
        cert.setSignatureAlgorithm(generated.signatureAlgorithm());
        cert.setCertificateBody(generated.certificatePem());
        cert.setPrivateKey(generated.privateKeyPem());
        cert.setCertificateChain(getAwsRootCa());
        cert.setCertOptions(options != null ? options : CertificateOptions.defaultOptions());
        cert.setCertAuthorityArn(certAuthorityArn);
        cert.setIdempotencyToken(idempotencyToken);
        cert.setTags(tags != null ? new HashMap<>(tags) : new HashMap<>());

        // Generate domain validation options with correct status based on type
        List<DomainValidation> validations = new ArrayList<>();
        for (String san : allSans) {
            validations.add(generateDomainValidation(san, validationMethod, type));
        }
        cert.setDomainValidationOptions(validations);

        String storageKey = regionKey(region, certId);
        store.put(storageKey, cert);

        // Index idempotency token for fast lookups with TTL
        if (idempotencyToken != null && !idempotencyToken.isEmpty()) {
            int requestHash = computeRequestHash(domainName, sans, alg);
            idempotencyTokenIndex.put(region + "::" + idempotencyToken,
                IdempotencyTokenEntry.create(arn, requestHash));
        }

        LOG.infov("Created certificate: {0} in region {1}", arn, region);
        return cert;
    }

    // ============ DescribeCertificate ============

    public Certificate describeCertificate(String certificateArn, String region) {
        Certificate cert = getCertificateByArn(certificateArn, region);

        // Check for expiration
        if (cert.isExpired() && cert.getStatus() != CertificateStatus.EXPIRED) {
            cert.setStatus(CertificateStatus.EXPIRED);
            store.put(regionKey(region, cert.extractCertificateId()), cert);
        }

        return cert;
    }

    // ============ GetCertificate ============

    public Certificate getCertificate(String certificateArn, String region) {
        return getCertificateByArn(certificateArn, region);
    }

    // ============ ListCertificates ============

    /**
     * Lists certificates with cursor-based pagination.
     *
     * @param statuses Filter by certificate status (null or empty for all)
     * @param keyTypes Filter by key algorithm (null or empty for all)
     * @param region AWS region
     * @param maxItems Maximum items per page (default 100)
     * @param nextToken Cursor for next page (null for first page)
     * @return ListResult containing certificates and optional nextToken
     */
    public ListResult listCertificates(List<CertificateStatus> statuses, List<KeyAlgorithm> keyTypes,
                                       String region, int maxItems, String nextToken) {
        int limit = maxItems > 0 ? Math.min(maxItems, 1000) : 100;
        String lastArn = decodeToken(nextToken);

        List<Certificate> allCerts = store.scan(k -> true).stream()
            .filter(c -> c.getArn().contains(":acm:" + region + ":"))
            .filter(c -> statuses == null || statuses.isEmpty() || statuses.contains(c.getStatus()))
            .filter(c -> keyTypes == null || keyTypes.isEmpty() || keyTypes.contains(c.getKeyAlgorithm()))
            .sorted(Comparator.comparing(Certificate::getArn))
            .collect(Collectors.toList());

        // Find starting position based on cursor
        int startIndex = 0;
        if (lastArn != null) {
            for (int i = 0; i < allCerts.size(); i++) {
                if (allCerts.get(i).getArn().compareTo(lastArn) > 0) {
                    startIndex = i;
                    break;
                }
                if (i == allCerts.size() - 1) {
                    startIndex = allCerts.size(); // All items before cursor
                }
            }
        }

        // Get page
        List<Certificate> page = allCerts.stream()
            .skip(startIndex)
            .limit(limit)
            .collect(Collectors.toList());

        // Determine if there are more items
        String newNextToken = null;
        if (startIndex + limit < allCerts.size() && !page.isEmpty()) {
            newNextToken = encodeToken(page.get(page.size() - 1).getArn());
        }

        return new ListResult(page, newNextToken);
    }

    /**
     * Encodes a pagination cursor as Base64 JSON.
     */
    private String encodeToken(String lastArn) {
        if (lastArn == null) return null;
        String json = "{\"lastArn\":\"" + lastArn + "\"}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a pagination cursor from Base64 JSON.
     */
    private String decodeToken(String token) {
        if (token == null || token.isEmpty()) return null;
        try {
            String json = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
            // Simple JSON parsing without Jackson dependency in this method
            int start = json.indexOf("\"lastArn\":\"") + 11;
            int end = json.indexOf("\"", start);
            return json.substring(start, end);
        } catch (Exception e) {
            throw new AwsException("InvalidNextTokenException", "Invalid pagination token", 400);
        }
    }

    // ============ DeleteCertificate ============

    public void deleteCertificate(String certificateArn, String region) {
        Certificate cert = getCertificateByArn(certificateArn, region);

        if (cert.getInUseBy() != null && !cert.getInUseBy().isEmpty()) {
            throw new AwsException("ResourceInUseException",
                "Certificate " + certificateArn + " is in use by: " + String.join(", ", cert.getInUseBy()), 409);
        }

        String storageKey = regionKey(region, cert.extractCertificateId());
        store.delete(storageKey);
        LOG.infov("Deleted certificate: {0}", certificateArn);
    }

    // ============ ImportCertificate ============

    public Certificate importCertificate(String certificatePem, String privateKeyPem, String chainPem,
                                          String existingArn, Map<String, String> tags, String region) {
        logSecurityWarningOnce();
        // Parse and validate certificate
        X509Certificate x509Cert;
        try {
            x509Cert = certificateGenerator.parseCertificate(certificatePem);
            certificateGenerator.validateCertificate(x509Cert);
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Invalid certificate: " + e.getMessage(), 400);
        }

        // Parse and validate private key
        try {
            certificateGenerator.parsePrivateKey(privateKeyPem);
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Invalid private key: " + e.getMessage(), 400);
        }

        if (tags != null) {
            validateTags(tags);
        }

        String certId;
        String arn;

        if (existingArn != null && !existingArn.isEmpty()) {
            // Re-import
            Certificate existing = getCertificateByArn(existingArn, region);
            certId = existing.extractCertificateId();
            arn = existingArn;
        } else {
            certId = UUID.randomUUID().toString();
            arn = buildCertificateArn(region, certId);
        }

        Instant now = Instant.now();
        KeyAlgorithm keyAlg = certificateGenerator.detectKeyAlgorithm(x509Cert.getPublicKey());

        Certificate cert = new Certificate();
        cert.setArn(arn);
        cert.setDomainName(extractCommonName(x509Cert));
        cert.setStatus(CertificateStatus.ISSUED);
        cert.setType(CertificateType.IMPORTED);
        cert.setCreatedAt(existingArn == null ? now : null);
        cert.setImportedAt(now);
        cert.setIssuedAt(now);
        cert.setNotBefore(x509Cert.getNotBefore().toInstant());
        cert.setNotAfter(x509Cert.getNotAfter().toInstant());
        cert.setSerial(x509Cert.getSerialNumber().toString());
        cert.setSubject(x509Cert.getSubjectX500Principal().getName());
        cert.setIssuer(x509Cert.getIssuerX500Principal().getName());
        cert.setKeyAlgorithm(keyAlg);
        cert.setSignatureAlgorithm(x509Cert.getSigAlgName());
        cert.setCertificateBody(certificatePem);
        cert.setPrivateKey(privateKeyPem);
        cert.setCertificateChain(chainPem);
        cert.setTags(tags != null ? new HashMap<>(tags) : new HashMap<>());

        String storageKey = regionKey(region, certId);
        store.put(storageKey, cert);

        LOG.infov("Imported certificate: {0}", arn);
        return cert;
    }

    // ============ ExportCertificate ============

    public Certificate exportCertificate(String certificateArn, String passphraseBase64, String region) {
        Certificate cert = getCertificateByArn(certificateArn, region);

        if (!cert.canExport()) {
            throw new AwsException("ValidationException",
                "Certificate is not PRIVATE type and Export is not ENABLED", 400);
        }

        String passphrase;
        try {
            passphrase = new String(Base64.getDecoder().decode(passphraseBase64));
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Invalid passphrase encoding", 400);
        }

        if (passphrase.length() < 4) {
            throw new AwsException("ValidationException", "Passphrase must be at least 4 characters", 400);
        }

        // Encrypt the private key
        String encryptedKey = certificateGenerator.encryptPrivateKey(cert.getPrivateKey(), passphrase);

        // Return certificate with encrypted private key
        Certificate exportCert = new Certificate();
        exportCert.setCertificateBody(cert.getCertificateBody());
        exportCert.setCertificateChain(cert.getCertificateChain());
        exportCert.setPrivateKey(encryptedKey);
        return exportCert;
    }

    // ============ Tagging Operations ============

    public void addTagsToCertificate(String certificateArn, Map<String, String> tags, String region) {
        Certificate cert = getCertificateByArn(certificateArn, region);
        validateTags(tags);

        Map<String, String> currentTags = cert.getTags() != null ? new HashMap<>(cert.getTags()) : new HashMap<>();
        currentTags.putAll(tags);

        if (currentTags.size() > MAX_TAGS) {
            throw new AwsException("TooManyTagsException",
                "Certificate cannot have more than " + MAX_TAGS + " tags", 400);
        }

        cert.setTags(currentTags);
        store.put(regionKey(region, cert.extractCertificateId()), cert);
    }

    public Map<String, String> listTagsForCertificate(String certificateArn, String region) {
        Certificate cert = getCertificateByArn(certificateArn, region);
        return cert.getTags() != null ? new HashMap<>(cert.getTags()) : new HashMap<>();
    }

    public void removeTagsFromCertificate(String certificateArn, List<Map<String, String>> tagSpecs, String region) {
        Certificate cert = getCertificateByArn(certificateArn, region);
        Map<String, String> currentTags = cert.getTags() != null ? new HashMap<>(cert.getTags()) : new HashMap<>();

        for (Map<String, String> spec : tagSpecs) {
            String key = spec.get("Key");
            String value = spec.get("Value");
            if (key != null) {
                if (value == null) {
                    // Remove by key only
                    currentTags.remove(key);
                } else {
                    // Remove only if value matches
                    if (value.equals(currentTags.get(key))) {
                        currentTags.remove(key);
                    }
                }
            }
        }

        cert.setTags(currentTags);
        store.put(regionKey(region, cert.extractCertificateId()), cert);
    }

    // ============ Account Configuration ============

    public int getAccountDaysBeforeExpiry() {
        return accountDaysBeforeExpiry.get();
    }

    public void putAccountConfiguration(int daysBeforeExpiry, String idempotencyToken) {
        if (daysBeforeExpiry < 1 || daysBeforeExpiry > 90) {
            throw new AwsException("ValidationException",
                "DaysBeforeExpiry must be between 1 and 90", 400);
        }
        this.accountDaysBeforeExpiry.set(daysBeforeExpiry);
    }

    // ============ Helper Methods ============

    private Certificate getCertificateByArn(String arn, String region) {
        String certId = extractCertificateIdFromArn(arn);
        String storageKey = regionKey(region, certId);

        return store.get(storageKey).orElseThrow(() ->
            new AwsException("ResourceNotFoundException",
                "The certificate " + arn + " does not exist.", 404));
    }

    /**
     * Finds a certificate by idempotency token with lazy expiration.
     *
     * <p>Implementation follows moto's pattern (moto/acm/models.py:478-505):
     * expired entries are removed on lookup rather than using a background
     * cleanup thread.</p>
     *
     * @param token The idempotency token
     * @param region AWS region
     * @param requestHash Hash of current request parameters for validation
     * @return Optional containing the certificate if token is valid and not expired
     * @throws AwsException if token exists but parameters don't match (IdempotencyTokenException)
     */
    private Optional<Certificate> findByIdempotencyToken(String token, String region, int requestHash) {
        String indexKey = region + "::" + token;
        IdempotencyTokenEntry entry = idempotencyTokenIndex.get(indexKey);

        if (entry == null) {
            return Optional.empty();
        }

        // Lazy expiration: remove expired entries on lookup
        if (entry.isExpired()) {
            idempotencyTokenIndex.remove(indexKey);
            return Optional.empty();
        }

        // Validate request parameters match
        if (entry.requestHash() != requestHash) {
            throw new AwsException("IdempotencyException",
                "An idempotency token was used with a request that does not match a previous request " +
                "that used that token.", 400);
        }

        String certId = extractCertificateIdFromArn(entry.arn());
        return store.get(regionKey(region, certId));
    }

    /**
     * Computes a hash of request parameters for idempotency validation.
     * Parameters include: domainName, SANs (order-independent), keyAlgorithm.
     */
    private int computeRequestHash(String domainName, List<String> sans, KeyAlgorithm keyAlgorithm) {
        return Objects.hash(
            domainName,
            sans != null ? new HashSet<>(sans) : null,
            keyAlgorithm
        );
    }

    private void validateDomainName(String domainName) {
        if (domainName == null || domainName.isEmpty()) {
            throw new AwsException("ValidationException", "Domain name cannot be empty", 400);
        }
        if (domainName.length() > MAX_DOMAIN_LENGTH) {
            throw new AwsException("ValidationException",
                "Domain name cannot exceed " + MAX_DOMAIN_LENGTH + " characters", 400);
        }
    }

    private void validateSans(List<String> sans) {
        if (sans != null && sans.size() > MAX_SANS) {
            throw new AwsException("ValidationException",
                "Cannot have more than " + MAX_SANS + " subject alternative names", 400);
        }
    }

    private void validateTags(Map<String, String> tags) {
        if (tags == null) return;

        // Check total number of tags
        if (tags.size() > MAX_TAGS) {
            throw new AwsException("ValidationException",
                "Cannot have more than " + MAX_TAGS + " tags", 400);
        }

        for (Map.Entry<String, String> entry : tags.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (key == null || key.isEmpty() || key.length() > MAX_TAG_KEY_LENGTH) {
                throw new AwsException("ValidationException",
                    "Tag key must be 1-" + MAX_TAG_KEY_LENGTH + " characters", 400);
            }
            if (key.toLowerCase().startsWith("aws:")) {
                throw new AwsException("ValidationException",
                    "Tag key cannot start with 'aws:'", 400);
            }
            if (value != null && value.length() > MAX_TAG_VALUE_LENGTH) {
                throw new AwsException("ValidationException",
                    "Tag value cannot exceed " + MAX_TAG_VALUE_LENGTH + " characters", 400);
            }
        }
    }

    /**
     * Generates domain validation options with status based on certificate type.
     * Private certificates have SUCCESS status immediately; public certificates
     * start with PENDING_VALIDATION until DNS/email validation completes.
     */
    private DomainValidation generateDomainValidation(String domain, ValidationMethod method, CertificateType type) {
        String validationToken = generateValidationToken(domain);
        ResourceRecord resourceRecord = new ResourceRecord(
            "_" + validationToken.substring(0, 32) + "." + domain + ".",
            "CNAME",
            "_" + validationToken.substring(32) + ".acm-validations.aws."
        );

        // Private certificates don't need validation; public certificates do
        String validationStatus = (type == CertificateType.PRIVATE) ? "SUCCESS" : "PENDING_VALIDATION";

        return new DomainValidation(
            domain,
            domain,
            validationStatus,
            method != null ? method.name() : "DNS",
            resourceRecord,
            null
        );
    }

    private String generateValidationToken(String domain) {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        return HexFormat.of().formatHex(randomBytes);
    }

    private String buildCertificateArn(String region, String certId) {
        String accountId = regionResolver.getAccountId();
        return "arn:aws:acm:" + region + ":" + accountId + ":certificate/" + certId;
    }

    private String extractCertificateIdFromArn(String arn) {
        int lastSlash = arn.lastIndexOf('/');
        return lastSlash >= 0 ? arn.substring(lastSlash + 1) : arn;
    }

    private String regionKey(String region, String certId) {
        return region + "::" + certId;
    }

    private String extractCommonName(X509Certificate cert) {
        String dn = cert.getSubjectX500Principal().getName();
        return Arrays.stream(dn.split(","))
            .map(String::trim)
            .filter(s -> s.startsWith("CN="))
            .findFirst()
            .map(s -> s.substring(3))
            .orElse(dn);
    }

    private String getAwsRootCa() {
        try (InputStream is = getClass().getResourceAsStream("/certs/amazon-root-ca.pem")) {
            if (is == null) {
                LOG.warn("Could not load Amazon Root CA from resources, using empty chain");
                return "";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Failed to load Amazon Root CA: " + e.getMessage());
            return "";
        }
    }
}
