package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.acm.model.Certificate;
import io.github.hectorvent.floci.services.acm.model.CertificateStatus;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.IdentityCertificate;
import io.github.hectorvent.floci.testing.MutableClock;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SesIdentityCertificateServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String DOMAIN = "example.com";
    private static final String CERT_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/11111111-2222-3333-4444-555555555555";
    private static final String OTHER_CERT_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/99999999-2222-3333-4444-555555555555";

    // A SAN of alice@example.com, matching the sender most tests associate.
    private static final SmimeTestCertificates.Pem ALICE_CERTIFICATE =
            SmimeTestCertificates.rsa("alice@example.com", 2048);

    private final MutableClock clock = new MutableClock();
    private SesIdentityService identityService;
    private AcmService acmService;
    private SesIdentityCertificateService service;

    @BeforeEach
    void setUp() {
        identityService = new SesIdentityService(new InMemoryStorage<>(), null, Clock.systemUTC());
        markVerified(identityService.verifyDomainIdentity(DOMAIN, REGION));
        identityService.verifyEmailIdentity("alice@example.org", REGION);
        acmService = mock(AcmService.class);
        when(acmService.getCertificate(anyString(), anyString())).thenAnswer(invocation -> {
            throw new AwsException("ResourceNotFoundException", "missing", 404);
        });
        service = new SesIdentityCertificateService(new InMemoryStorage<>(), identityService, acmService, clock);
    }

    @Test
    void associate_domainNeedsFromAddress() {
        assertBadRequest("FromAddress is required when EmailIdentity is a domain.",
                () -> service.associate(DOMAIN, null, CERT_ARN, REGION));
    }

    @Test
    void associate_fromAddressMustBeAnAddressInTheDomain() {
        assertBadRequest("FromAddress <not-an-address> is not a valid email address.",
                () -> service.associate(DOMAIN, "not-an-address", CERT_ARN, REGION));
        assertBadRequest("FromAddress <alice@other.example.net> does not belong to the domain identity <example.com>.",
                () -> service.associate(DOMAIN, "alice@other.example.net", CERT_ARN, REGION));
        assertBadRequest("FromAddress <alice@notexample.com> does not belong to the domain identity <example.com>.",
                () -> service.associate(DOMAIN, "alice@notexample.com", CERT_ARN, REGION));
    }

    @Test
    void associate_acceptsSubdomainAddress() {
        service.associate(DOMAIN, "alice@sub.example.com", CERT_ARN, REGION);

        assertEquals(List.of("alice@sub.example.com"), fromAddresses(service.list(DOMAIN, null, null, REGION)));
    }

    @Test
    void associate_emailIdentityDefaultsFromAddressToItself() {
        service.associate("alice@example.org", null, CERT_ARN, REGION);

        assertEquals(List.of("alice@example.org"),
                fromAddresses(service.list("alice@example.org", null, null, REGION)));
        assertBadRequest("FromAddress <bob@example.org> does not match the email identity <alice@example.org>.",
                () -> service.associate("alice@example.org", "bob@example.org", CERT_ARN, REGION));
    }

    @Test
    void emailIdentityFromAddressMustMatchExactly() {
        assertBadRequest("FromAddress <Alice@example.org> does not match the email identity <alice@example.org>.",
                () -> service.associate("alice@example.org", "Alice@example.org", CERT_ARN, REGION));
        assertBadRequest("FromAddress <ALICE@example.org> does not match the email identity <alice@example.org>.",
                () -> service.disassociate("alice@example.org", "ALICE@example.org", REGION));
    }

    @Test
    void associate_malformedArnReportsBothConstraints() {
        assertBadRequest("2 validation errors detected: Value at 'certificateArn' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to 20; Value at 'certificateArn' failed to "
                        + "satisfy constraint: Member must satisfy regular expression pattern: "
                        + "arn:[\\w+=/,.@-]+:[\\w+=/,.@-]+:[\\w+=/,.@-]*:[0-9]+:certificate/[\\w+=,.@-]+",
                () -> service.associate(DOMAIN, "alice@example.com", "not-an-arn", REGION));
    }

    @Test
    void associate_missingIdentityIsNotFound() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.associate("missing.example.com", "alice@missing.example.com", CERT_ARN, REGION));
        assertEquals("NotFoundException", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());
        assertEquals("Email identity <missing.example.com> does not exist.", e.getMessage());
    }

    @Test
    void associate_duplicateAddressIgnoresCaseAndKeepsFirstSpelling() {
        service.associate(DOMAIN, "Alice@Example.com", CERT_ARN, REGION);

        AwsException e = assertThrows(AwsException.class,
                () -> service.associate(DOMAIN, "ALICE@EXAMPLE.COM", OTHER_CERT_ARN, REGION));
        assertEquals("AlreadyExistsException", e.getErrorCode());
        assertEquals("A certificate is already associated with sender <Alice@Example.com> on identity <example.com>.",
                e.getMessage());
        assertEquals(List.of("Alice@Example.com"), fromAddresses(service.list(DOMAIN, null, null, REGION)));
    }

    @Test
    void list_statusFollowsTheAcmCertificate() {
        Instant notAfter = clock.instant().plus(Duration.ofDays(365));
        Certificate issued = certificate(CERT_ARN, CertificateStatus.ISSUED, notAfter);
        when(acmService.getCertificate(eq(CERT_ARN), eq(REGION))).thenReturn(issued);
        service.associate(DOMAIN, "alice@example.com", CERT_ARN, REGION);
        service.associate(DOMAIN, "bob@example.com", OTHER_CERT_ARN, REGION);

        List<SesIdentityCertificateService.Entry> entries = service.list(DOMAIN, null, null, REGION).items();

        assertEquals(new SesIdentityCertificateService.Entry("alice@example.com", "ACTIVE", CERT_ARN, notAfter),
                entries.get(0));
        assertEquals(new SesIdentityCertificateService.Entry("bob@example.com", "FAILED", OTHER_CERT_ARN, null),
                entries.get(1));
    }

    @Test
    void list_expiredOrUnissuedCertificateIsFailed() {
        Certificate certificate = certificate(CERT_ARN, CertificateStatus.ISSUED, clock.instant().plusSeconds(60));
        when(acmService.getCertificate(eq(CERT_ARN), eq(REGION))).thenReturn(certificate);
        service.associate(DOMAIN, "alice@example.com", CERT_ARN, REGION);
        assertEquals("ACTIVE", service.list(DOMAIN, null, null, REGION).items().getFirst().status());

        clock.advance(Duration.ofSeconds(60));
        assertEquals("FAILED", service.list(DOMAIN, null, null, REGION).items().getFirst().status());

        certificate.setNotAfter(clock.instant().plusSeconds(60));
        certificate.setStatus(CertificateStatus.PENDING_VALIDATION);
        assertEquals("FAILED", service.list(DOMAIN, null, null, REGION).items().getFirst().status());
    }

    @Test
    void list_certificateNeedsAnAllowedKey() {
        Certificate certificate = certificate(CERT_ARN, CertificateStatus.ISSUED, clock.instant().plusSeconds(3600));
        when(acmService.getCertificate(eq(CERT_ARN), eq(REGION))).thenReturn(certificate);
        service.associate(DOMAIN, "alice@example.com", CERT_ARN, REGION);

        // ACM records RSA-1536 as RSA_2048 and Ed25519 as RSA_2048, so the key itself has to be read.
        for (SmimeTestCertificates.Pem refused : List.of(SmimeTestCertificates.rsa("alice@example.com", 1024),
                SmimeTestCertificates.rsa("alice@example.com", 1536),
                SmimeTestCertificates.ed25519("alice@example.com"))) {
            certificate.setCertificateBody(refused.certificate());
            assertEquals("FAILED", service.list(DOMAIN, null, null, REGION).items().getFirst().status());
        }
        for (SmimeTestCertificates.Pem allowed : List.of(SmimeTestCertificates.rsa("alice@example.com", 3072),
                SmimeTestCertificates.ec("alice@example.com", "secp256r1"),
                SmimeTestCertificates.ec("alice@example.com", "secp521r1"))) {
            certificate.setCertificateBody(allowed.certificate());
            assertEquals("ACTIVE", service.list(DOMAIN, null, null, REGION).items().getFirst().status());
        }
    }

    @Test
    void list_certificateMustBeAnEmailSigningEndEntity() {
        Certificate certificate = certificate(CERT_ARN, CertificateStatus.ISSUED, clock.instant().plusSeconds(3600));
        when(acmService.getCertificate(eq(CERT_ARN), eq(REGION))).thenReturn(certificate);
        service.associate(DOMAIN, "alice@example.com", CERT_ARN, REGION);
        int signing = KeyUsage.digitalSignature;
        KeyPurposeId email = KeyPurposeId.id_kp_emailProtection;
        // The eight shapes probed against SES on 2026-10-06, with the status it reported for each.
        Map<SmimeTestCertificates.Extensions, String> probed = new LinkedHashMap<>();
        probed.put(new SmimeTestCertificates.Extensions(true, 0), "FAILED");
        probed.put(new SmimeTestCertificates.Extensions(false, signing, email), "ACTIVE");
        probed.put(new SmimeTestCertificates.Extensions(false, 0), "FAILED");
        probed.put(new SmimeTestCertificates.Extensions(false, 0, email), "FAILED");
        probed.put(new SmimeTestCertificates.Extensions(false, signing), "FAILED");
        probed.put(new SmimeTestCertificates.Extensions(true, signing, email), "FAILED");
        probed.put(new SmimeTestCertificates.Extensions(null, signing, email), "ACTIVE");
        probed.put(new SmimeTestCertificates.Extensions(false, signing | KeyUsage.keyEncipherment, email,
                KeyPurposeId.id_kp_clientAuth), "ACTIVE");

        probed.forEach((extensions, expected) -> {
            certificate.setCertificateBody(
                    SmimeTestCertificates.rsa("alice@example.com", 2048, extensions).certificate());
            assertEquals(expected, service.list(DOMAIN, null, null, REGION).items().getFirst().status(),
                    extensions.toString());
        });
    }

    @Test
    void list_certificateNotYetValidIsFailed() {
        Certificate certificate = certificate(CERT_ARN, CertificateStatus.ISSUED, clock.instant().plusSeconds(3600));
        certificate.setNotBefore(clock.instant().plusSeconds(60));
        when(acmService.getCertificate(eq(CERT_ARN), eq(REGION))).thenReturn(certificate);
        service.associate(DOMAIN, "alice@example.com", CERT_ARN, REGION);

        assertEquals("FAILED", service.list(DOMAIN, null, null, REGION).items().getFirst().status());

        clock.advance(Duration.ofSeconds(60));
        assertEquals("ACTIVE", service.list(DOMAIN, null, null, REGION).items().getFirst().status());
    }

    @Test
    void list_certificateSanMustNameTheFromAddressExactly() {
        when(acmService.getCertificate(eq(CERT_ARN), eq(REGION)))
                .thenReturn(certificate(CERT_ARN, CertificateStatus.ISSUED, clock.instant().plusSeconds(3600)));
        service.associate(DOMAIN, "Alice@example.com", CERT_ARN, REGION);
        service.associate(DOMAIN, "bob@example.com", CERT_ARN, REGION);

        assertEquals(List.of("FAILED", "FAILED"), statuses(service.list(DOMAIN, null, null, REGION)));
    }

    @Test
    void list_unverifiedIdentityIsFailed() {
        Identity pending = identityService.verifyDomainIdentity("pending.example.com", REGION);
        Certificate certificate = certificate(CERT_ARN, CertificateStatus.ISSUED, clock.instant().plusSeconds(3600));
        certificate.setCertificateBody(SmimeTestCertificates.rsa("alice@pending.example.com", 2048).certificate());
        when(acmService.getCertificate(eq(CERT_ARN), eq(REGION))).thenReturn(certificate);
        service.associate("pending.example.com", "alice@pending.example.com", CERT_ARN, REGION);

        assertEquals(List.of("FAILED"), statuses(service.list("pending.example.com", null, null, REGION)));

        markVerified(pending);
        assertEquals(List.of("ACTIVE"), statuses(service.list("pending.example.com", null, null, REGION)));
    }

    @Test
    void list_certificateFoundUnderAnotherArnIsFailed() {
        String otherRegionArn = CERT_ARN.replace("us-east-1", "us-west-2");
        when(acmService.getCertificate(eq(otherRegionArn), eq(REGION)))
                .thenReturn(certificate(CERT_ARN, CertificateStatus.ISSUED, clock.instant().plusSeconds(3600)));
        service.associate(DOMAIN, "alice@example.com", otherRegionArn, REGION);

        assertEquals("FAILED", service.list(DOMAIN, null, null, REGION).items().getFirst().status());
    }

    @Test
    void list_sortsByAddressAndPages() {
        service.associate(DOMAIN, "carol@example.com", CERT_ARN, REGION);
        service.associate(DOMAIN, "Bob@example.com", CERT_ARN, REGION);
        service.associate(DOMAIN, "alice@example.com", CERT_ARN, REGION);

        PaginatedResult<SesIdentityCertificateService.Entry> first = service.list(DOMAIN, 2, null, REGION);
        PaginatedResult<SesIdentityCertificateService.Entry> second =
                service.list(DOMAIN, 2, first.nextToken(), REGION);

        assertEquals(List.of("alice@example.com", "Bob@example.com"), fromAddresses(first));
        assertEquals(List.of("carol@example.com"), fromAddresses(second));
        assertNull(second.nextToken());
    }

    @Test
    void list_validatesPageSizeAndToken() {
        assertBadRequest("1 validation error detected: Value '0' at 'pageSize' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 1", () -> service.list(DOMAIN, 0, null, REGION));
        assertBadRequest("1 validation error detected: Value '1001' at 'pageSize' failed to satisfy constraint: "
                + "Member must have value less than or equal to 1000", () -> service.list(DOMAIN, 1001, null, REGION));
        assertBadRequest("Invalid NextToken.", () -> service.list(DOMAIN, null, "bogus", REGION));
    }

    @Test
    void list_tokenIsBoundToTheIdentity() {
        identityService.verifyDomainIdentity("example.net", REGION);
        service.associate(DOMAIN, "alice@example.com", CERT_ARN, REGION);
        service.associate(DOMAIN, "bob@example.com", CERT_ARN, REGION);
        String token = service.list(DOMAIN, 1, null, REGION).nextToken();

        assertBadRequest("Invalid NextToken.", () -> service.list("example.net", 1, token, REGION));
    }

    @Test
    void disassociate_isIdempotentAndMatchesAddressWithoutCase() {
        service.associate(DOMAIN, "alice@example.com", CERT_ARN, REGION);

        service.disassociate(DOMAIN, "ALICE@example.com", REGION);
        service.disassociate(DOMAIN, "ALICE@example.com", REGION);

        assertTrue(service.list(DOMAIN, null, null, REGION).items().isEmpty());
    }

    @Test
    void disassociate_checksTheAddressBeforeTheIdentity() {
        assertBadRequest("FromAddress <alice@example.com> does not belong to the domain identity <missing.example.com>.",
                () -> service.disassociate("missing.example.com", "alice@example.com", REGION));
        AwsException e = assertThrows(AwsException.class,
                () -> service.disassociate("missing.example.com", "alice@missing.example.com", REGION));
        assertEquals("NotFoundException", e.getErrorCode());
        assertBadRequest("FromAddress is required when EmailIdentity is a domain.",
                () -> service.disassociate(DOMAIN, null, REGION));
    }

    @Test
    void deleteIdentityGuarded_refusesWhileACertificateIsAssociated() {
        service.associate(DOMAIN, "alice@example.com", CERT_ARN, REGION);
        AtomicBoolean deleted = new AtomicBoolean();

        assertBadRequest("Cannot delete <arn:aws:ses:us-east-1:000000000000:identity/example.com> because it has "
                        + "certificates associated with it. Disassociate all certificates and try again.",
                () -> service.deleteIdentityGuarded(DOMAIN, ACCOUNT, REGION, () -> deleted.set(true)));
        assertFalse(deleted.get());

        service.disassociate(DOMAIN, "alice@example.com", REGION);
        service.deleteIdentityGuarded(DOMAIN, ACCOUNT, REGION, () -> deleted.set(true));
        assertTrue(deleted.get());
    }

    @Test
    void deleteIdentityGuarded_ignoresAnotherIdentitysAssociations() {
        identityService.verifyDomainIdentity("sub.example.com", REGION);
        service.associate("sub.example.com", "alice@sub.example.com", CERT_ARN, REGION);
        AtomicBoolean deleted = new AtomicBoolean();

        service.deleteIdentityGuarded(DOMAIN, ACCOUNT, REGION, () -> deleted.set(true));

        assertTrue(deleted.get());
    }

    @Test
    void associationsSurviveAPersistentStorageReload(@TempDir Path dir) {
        SesIdentityCertificateService first = persistentService(dir);
        first.associate(DOMAIN, "Alice@example.com", CERT_ARN, REGION);

        SesIdentityCertificateService reloaded = persistentService(dir);

        assertEquals(List.of(new SesIdentityCertificateService.Entry("Alice@example.com", "FAILED", CERT_ARN, null)),
                reloaded.list(DOMAIN, null, null, REGION).items());
        assertThrows(AwsException.class, () -> reloaded.associate(DOMAIN, "alice@example.com", CERT_ARN, REGION));
    }

    private SesIdentityCertificateService persistentService(Path dir) {
        PersistentStorage<String, IdentityCertificate> store = new PersistentStorage<>(
                dir.resolve("ses-identity-certificates.json"),
                new TypeReference<Map<String, IdentityCertificate>>() {});
        store.load();
        return new SesIdentityCertificateService(store, identityService, acmService, clock);
    }

    private void markVerified(Identity identity) {
        identity.setVerificationStatus("Success");
        identityService.save(identity, REGION);
    }

    private static Certificate certificate(String arn, CertificateStatus status, Instant notAfter) {
        Certificate certificate = new Certificate();
        certificate.setArn(arn);
        certificate.setStatus(status);
        certificate.setNotAfter(notAfter);
        certificate.setCertificateBody(ALICE_CERTIFICATE.certificate());
        return certificate;
    }

    private static List<String> statuses(PaginatedResult<SesIdentityCertificateService.Entry> page) {
        return page.items().stream().map(SesIdentityCertificateService.Entry::status).toList();
    }

    private static List<String> fromAddresses(PaginatedResult<SesIdentityCertificateService.Entry> page) {
        return page.items().stream().map(SesIdentityCertificateService.Entry::fromAddress).toList();
    }

    private static void assertBadRequest(String message, Runnable call) {
        AwsException e = assertThrows(AwsException.class, call::run);
        assertEquals("BadRequestException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        assertEquals(message, e.getMessage());
    }
}
