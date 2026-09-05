package io.github.hectorvent.floci.services.acm;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.acm.model.Certificate;
import io.github.hectorvent.floci.services.acm.model.CertificateOptions;
import io.github.hectorvent.floci.services.acm.model.CertificateStatus;
import io.github.hectorvent.floci.services.acm.model.DomainValidation;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.acm.model.ResourceRecord;
import io.github.hectorvent.floci.services.acm.model.RevocationReason;
import io.github.hectorvent.floci.services.acm.model.ValidationMethod;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.Security;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The domain validation status a certificate reports must follow its issuance. Clients such as the
 * AWS SDK {@code CertificateValidated} waiter and the CDK {@code DnsValidatedCertificate} handler
 * poll {@code DomainValidationOptions[].ValidationStatus}, not {@code Status}, and never finish
 * while an issued certificate still reports PENDING_VALIDATION.
 */
class AcmIssuanceServiceTest {

    private static final String REGION = "us-east-1";
    private static final String PRIVATE_CA =
            "arn:aws:acm-pca:us-east-1:000000000000:certificate-authority/11111111-2222-3333-4444-555555555555";
    private static CertificateGenerator generator;

    @BeforeAll
    static void setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        generator = new CertificateGenerator();
    }

    @Test
    void issuedCertificateReportsSuccessfulValidationForEveryDomain(@TempDir Path dir) {
        Certificate cert = request(newService(dir, 0), null);

        assertEquals(CertificateStatus.ISSUED, cert.getStatus());
        assertEquals(2, cert.getDomainValidationOptions().size());
        for (DomainValidation validation : cert.getDomainValidationOptions()) {
            assertEquals("SUCCESS", validation.validationStatus(), validation.domainName());
            assertEquals("DNS", validation.validationMethod());
            assertNotNull(validation.resourceRecord(), "the validation CNAME stays on an issued certificate");
        }
    }

    @Test
    void privateCertificateReportsSuccessfulValidationRegardlessOfTheWait(@TempDir Path dir) {
        Certificate cert = request(newService(dir, 3600), PRIVATE_CA);

        assertEquals(CertificateStatus.ISSUED, cert.getStatus());
        assertTrue(cert.getDomainValidationOptions().stream()
                .allMatch(validation -> "SUCCESS".equals(validation.validationStatus())));
    }

    @Test
    void pendingCertificateReportsPendingValidationUntilTheWaitHasPassed(@TempDir Path dir) throws Exception {
        AcmService service = newService(dir, 1);
        Certificate requested = request(service, null);
        String arn = requested.getArn();

        assertEquals(CertificateStatus.PENDING_VALIDATION, requested.getStatus());
        assertNull(requested.getIssuedAt());
        Certificate stillPending = service.describeCertificate(arn, REGION);
        assertEquals(CertificateStatus.PENDING_VALIDATION, stillPending.getStatus(),
                "a read before the wait has passed must not issue the certificate");
        assertTrue(stillPending.getDomainValidationOptions().stream()
                .allMatch(validation -> "PENDING_VALIDATION".equals(validation.validationStatus())));

        Thread.sleep(1200);

        Certificate issued = service.describeCertificate(arn, REGION);
        assertEquals(CertificateStatus.ISSUED, issued.getStatus());
        assertNotNull(issued.getIssuedAt());
        assertTrue(issued.getDomainValidationOptions().stream()
                .allMatch(validation -> "SUCCESS".equals(validation.validationStatus())));
    }

    @Test
    void settledCertificateIsPersistedNotRecomputed(@TempDir Path dir) throws Exception {
        AcmService service = newService(dir, 1);
        String arn = request(service, null).getArn();
        Thread.sleep(1200);
        service.getCertificate(arn, REGION);

        // A restarted service with a wait that has not passed yet would leave a pending certificate
        // pending, so an ISSUED status here can only have come from the store.
        Certificate reloaded = newService(dir, 3600).describeCertificate(arn, REGION);

        assertEquals(CertificateStatus.ISSUED, reloaded.getStatus());
        assertTrue(reloaded.getDomainValidationOptions().stream()
                .allMatch(validation -> "SUCCESS".equals(validation.validationStatus())));
    }

    @Test
    void listCertificatesSettlesPendingCertificatesBeforeFilteringByStatus(@TempDir Path dir) throws Exception {
        AcmService service = newService(dir, 1);
        String arn = request(service, null).getArn();
        assertTrue(service.listCertificates(List.of(CertificateStatus.ISSUED), null, REGION, 100, null)
                .certificates().isEmpty(), "still pending");

        Thread.sleep(1200);

        List<Certificate> issued = service.listCertificates(List.of(CertificateStatus.ISSUED), null, REGION, 100, null)
                .certificates();
        assertEquals(1, issued.size());
        assertEquals(arn, issued.get(0).getArn());
        assertEquals(CertificateStatus.ISSUED, issued.get(0).getStatus());
    }

    @Test
    void issuedCertificateStoredWithPendingValidationIsRepairedOnRead(@TempDir Path dir) {
        // Earlier releases stored ISSUED certificates whose validation entries stayed
        // PENDING_VALIDATION. The first read after an upgrade repairs and stores them.
        String id = "11111111-2222-3333-4444-555555555555";
        String arn = "arn:aws:acm:us-east-1:000000000000:certificate/" + id;
        Certificate legacy = new Certificate();
        legacy.setArn(arn);
        legacy.setDomainName("legacy.example.com");
        legacy.setStatus(CertificateStatus.ISSUED);
        legacy.setCreatedAt(Instant.now());
        legacy.setDomainValidationOptions(List.of(new DomainValidation("legacy.example.com", "example.com",
                "PENDING_VALIDATION", "DNS", new ResourceRecord("_a.legacy.example.com.", "CNAME", "_b.acm-validations.aws."), null)));
        PersistentStorage<String, Certificate> store = store(dir);
        store.put(REGION + "::" + id, legacy);

        Certificate repaired = newService(dir, store, 0).describeCertificate(arn, REGION);

        assertEquals(CertificateStatus.ISSUED, repaired.getStatus());
        assertEquals("SUCCESS", repaired.getDomainValidationOptions().get(0).validationStatus());
        assertEquals("_a.legacy.example.com.", repaired.getDomainValidationOptions().get(0).resourceRecord().name());
        Certificate reloaded = store(dir).get(REGION + "::" + id).orElseThrow();
        assertEquals("SUCCESS", reloaded.getDomainValidationOptions().get(0).validationStatus(), "repair is stored");
    }

    @Test
    void certificateRevokedAfterIssuanceStoredWithPendingValidationIsRepaired(@TempDir Path dir) {
        String id = "22222222-2222-3333-4444-555555555555";
        String arn = "arn:aws:acm:us-east-1:000000000000:certificate/" + id;
        Certificate legacy = new Certificate();
        legacy.setArn(arn);
        legacy.setDomainName("revoked.example.com");
        legacy.setStatus(CertificateStatus.REVOKED);
        legacy.setCreatedAt(Instant.now().minusSeconds(60));
        legacy.setIssuedAt(Instant.now().minusSeconds(60));
        legacy.setDomainValidationOptions(List.of(new DomainValidation("revoked.example.com", "example.com",
                "PENDING_VALIDATION", "DNS", null, null)));
        PersistentStorage<String, Certificate> store = store(dir);
        store.put(REGION + "::" + id, legacy);

        Certificate repaired = newService(dir, store, 0).describeCertificate(arn, REGION);

        assertEquals(CertificateStatus.REVOKED, repaired.getStatus());
        assertEquals("SUCCESS", repaired.getDomainValidationOptions().get(0).validationStatus());
    }

    @Test
    void certificateRevokedWhileStillPendingKeepsItsPendingValidation(@TempDir Path dir) {
        // Never issued, so nothing was ever validated: leaving PENDING_VALIDATION is the honest answer.
        AcmService service = newService(dir, 3600);
        String arn = request(service, null).getArn();
        service.updateCertificateOptions(arn, new CertificateOptions(null, "ENABLED"), REGION);
        service.revokeCertificate(arn, RevocationReason.UNSPECIFIED, REGION);

        Certificate revoked = service.describeCertificate(arn, REGION);

        assertEquals(CertificateStatus.REVOKED, revoked.getStatus());
        assertNull(revoked.getIssuedAt());
        assertTrue(revoked.getDomainValidationOptions().stream()
                .allMatch(validation -> "PENDING_VALIDATION".equals(validation.validationStatus())));
    }

    private static Certificate request(AcmService service, String certificateAuthorityArn) {
        return service.requestCertificate("example.com", List.of("www.example.com"), ValidationMethod.DNS,
                null, KeyAlgorithm.RSA_2048, certificateAuthorityArn, null, Map.of(), REGION);
    }

    private static AcmService newService(Path dir, int validationWaitSeconds) {
        return newService(dir, store(dir), validationWaitSeconds);
    }

    private static AcmService newService(Path dir, StorageBackend<String, Certificate> store, int validationWaitSeconds) {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        return new AcmService(store, generator, FlociCertificateAuthority.loadOrCreate(dir.resolve("tls")),
                regionResolver, validationWaitSeconds);
    }

    private static PersistentStorage<String, Certificate> store(Path dir) {
        PersistentStorage<String, Certificate> store = new PersistentStorage<>(
                dir.resolve("acm-certificates.json"), new TypeReference<Map<String, Certificate>>() {});
        store.load();
        return store;
    }
}
