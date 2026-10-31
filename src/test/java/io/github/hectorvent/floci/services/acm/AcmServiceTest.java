package io.github.hectorvent.floci.services.acm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.CertificateReferenceResolver;
import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.acm.model.Certificate;
import io.github.hectorvent.floci.services.acm.model.CertificateOptions;
import io.github.hectorvent.floci.services.acm.model.CertificateStatus;
import io.github.hectorvent.floci.services.acm.model.CertificateType;
import io.github.hectorvent.floci.services.acm.model.DomainValidation;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.acm.model.RevocationReason;
import io.github.hectorvent.floci.services.acm.model.ValidationMethod;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The issuance path of {@link AcmService} against a real local CA and no Quarkus: every
 * certificate the service issues chains to that CA, whatever the key algorithm, type or
 * validation state, and its metadata is read from the certificate rather than from the request.
 */
class AcmServiceTest {

    private static final String REGION = "us-east-1";
    private static final String PCA_ARN =
            "arn:aws:acm-pca:us-east-1:000000000000:certificate-authority/11111111-2222-3333-4444-555555555555";
    private static final List<String> SERVER_AND_CLIENT_AUTH = List.of("1.3.6.1.5.5.7.3.1", "1.3.6.1.5.5.7.3.2");

    @TempDir
    Path tempDir;

    private FlociCertificateAuthority ca;
    private CertificateGenerator generator;
    private AcmService service;

    @BeforeEach
    void setUp() {
        ca = FlociCertificateAuthority.loadOrCreate(tempDir.resolve("tls"));
        generator = new CertificateGenerator();
        service = newService(0);
    }

    @ParameterizedTest
    @EnumSource(value = KeyAlgorithm.class, names = {"RSA_2048", "EC_prime256v1"})
    void importRefusesAMismatchedKeyWithoutAllocatingACertificate(KeyAlgorithm algorithm) {
        CertificateGenerator.GeneratedCertificate first = generator.generateSelfSignedCertificate(
                "import.example.test", List.of(), algorithm);
        CertificateGenerator.GeneratedCertificate other = generator.generateSelfSignedCertificate(
                "other.example.test", List.of(), algorithm);
        AwsException failure = assertThrows(AwsException.class, () -> service.importCertificate(
                first.certificatePem(), other.privateKeyPem(), null, null, null, REGION));
        assertEquals("ValidationException", failure.getErrorCode());
        assertTrue(service.getResources().isEmpty());
        Certificate retry = service.importCertificate(first.certificatePem(), first.privateKeyPem(),
                null, null, null, REGION);
        assertEquals(first.certificatePem(), service.getCertificate(retry.getArn(), REGION).getCertificateBody());
        assertEquals(first.privateKeyPem(), service.getCertificate(retry.getArn(), REGION).getPrivateKey());
    }

    @ParameterizedTest
    @EnumSource(value = KeyAlgorithm.class, names = {"RSA_2048", "EC_prime256v1"})
    void reimportRefusesAMismatchedKeyWithoutReplacingTheExistingMaterial(KeyAlgorithm algorithm) {
        CertificateGenerator.GeneratedCertificate original = generator.generateSelfSignedCertificate(
                "existing.example.test", List.of(), algorithm);
        Certificate existing = service.importCertificate(original.certificatePem(), original.privateKeyPem(),
                null, null, null, REGION);
        CertificateGenerator.GeneratedCertificate replacement = generator.generateSelfSignedCertificate(
                "replacement.example.test", List.of(), algorithm);
        AwsException failure = assertThrows(AwsException.class, () -> service.importCertificate(
                replacement.certificatePem(), original.privateKeyPem(), null, existing.getArn(), null, REGION));
        assertEquals("ValidationException", failure.getErrorCode());
        Certificate unchanged = service.getCertificate(existing.getArn(), REGION);
        assertEquals(original.certificatePem(), unchanged.getCertificateBody());
        assertEquals(original.privateKeyPem(), unchanged.getPrivateKey());
        assertEquals(1, service.getResources().size());
        Certificate retry = service.importCertificate(replacement.certificatePem(), replacement.privateKeyPem(),
                null, existing.getArn(), null, REGION);
        assertEquals(existing.getArn(), retry.getArn());
        assertEquals(replacement.certificatePem(), service.getCertificate(existing.getArn(), REGION).getCertificateBody());
        assertEquals(replacement.privateKeyPem(), service.getCertificate(existing.getArn(), REGION).getPrivateKey());
    }

    @Test
    void pendingAcmExistsButCannotSupplyAnElbCertificate() {
        AcmService pending = newService(3600);
        Certificate certificate = pending.requestCertificate("pending.example.test", List.of(), ValidationMethod.DNS,
                null, KeyAlgorithm.RSA_2048, null, null, Map.of(), REGION);
        CertificateReferenceResolver resolver = new CertificateReferenceResolver(null, pending);
        assertTrue(resolver.exists(REGION, certificate.getArn()));
        assertEquals(false, resolver.isUsableAcmCertificate(REGION, certificate.getArn()));
        String owner = "arn:aws:elasticloadbalancing:us-east-1:000000000000:listener/app/test/one/two";
        AwsException failure = assertThrows(AwsException.class,
                () -> resolver.publishedMaterial(owner, REGION, certificate.getArn()));
        assertEquals("CertificateNotFound", failure.getErrorCode());
        assertEquals(CertificateStatus.PENDING_VALIDATION, pending.getCertificate(certificate.getArn(), REGION).getStatus());
    }

    @Test
    void attachedExportableCertificateCannotBeRevokedUntilDetached() {
        Certificate certificate = service.requestCertificate("revoke.example.test", List.of(), ValidationMethod.DNS,
                null, KeyAlgorithm.RSA_2048, null, new CertificateOptions(null, "ENABLED"), Map.of(), REGION);
        String owner = "arn:aws:cognito-idp:us-east-1:000000000000:userpool/revoke";
        service.addInUseBy(certificate.getArn(), owner, REGION);
        AwsException failure = assertThrows(AwsException.class,
                () -> service.revokeCertificate(certificate.getArn(), RevocationReason.KEY_COMPROMISE, REGION));
        assertAll(
                () -> assertEquals("ResourceInUseException", failure.getErrorCode()),
                () -> assertEquals(400, failure.getHttpStatus()),
                () -> assertEquals(CertificateStatus.ISSUED, service.getCertificate(certificate.getArn(), REGION).getStatus()),
                () -> assertEquals(List.of(owner), service.certificateInUseBy(certificate)));
        service.removeInUseBy(certificate.getArn(), owner, REGION);
        assertEquals(CertificateStatus.REVOKED,
                service.revokeCertificate(certificate.getArn(), RevocationReason.KEY_COMPROMISE, REGION).getStatus());
    }

    @Test
    void listPageScansLiveConsumersOnceAndRetainsPersistedUses() {
        Certificate first = service.requestCertificate("first.example.test", List.of(), ValidationMethod.DNS,
                null, KeyAlgorithm.RSA_2048, null, null, Map.of(), REGION);
        Certificate second = service.requestCertificate("second.example.test", List.of(), ValidationMethod.DNS,
                null, KeyAlgorithm.RSA_2048, null, null, Map.of(), REGION);
        service.requestCertificate("unused.example.test", List.of(), ValidationMethod.DNS,
                null, KeyAlgorithm.RSA_2048, null, null, Map.of(), REGION);
        service.addInUseBy(first.getArn(), "persisted-owner", REGION);
        AtomicInteger scans = new AtomicInteger();
        AcmCertificateReferenceProvider provider = mock();
        when(provider.acmCertificateReferences()).thenAnswer(call -> {
            scans.incrementAndGet();
            return List.of(new AcmCertificateReferenceProvider.CertificateReference(second.getArn(), "live-owner"));
        });
        Instance<AcmCertificateReferenceProvider> providers = mock();
        when(providers.iterator()).thenAnswer(call -> List.of(provider).iterator());
        service.referenceProviders = providers;
        ObjectMapper mapper = new ObjectMapper();
        AcmJsonHandler handler = new AcmJsonHandler(service, mapper);
        JsonNode response = (JsonNode) handler.handle("ListCertificates", mapper.createObjectNode(), REGION).getEntity();
        Map<String, Boolean> uses = new HashMap<>();
        response.path("CertificateSummaryList").forEach(row -> uses.put(row.path("CertificateArn").asText(),
                row.path("InUse").asBoolean()));
        assertAll(
                () -> assertEquals(1, scans.get()),
                () -> assertEquals(3, uses.size()),
                () -> assertEquals(true, uses.get(first.getArn())),
                () -> assertEquals(true, uses.get(second.getArn())),
                () -> assertEquals(1L, uses.values().stream().filter(value -> !value).count()));
        scans.set(0);
        JsonNode page = (JsonNode) handler.handle("ListCertificates", mapper.createObjectNode().put("MaxItems", 1),
                REGION).getEntity();
        assertEquals(1, page.path("CertificateSummaryList").size());
        assertTrue(page.has("NextToken"));
        assertEquals(1, scans.get());
    }

    @Test
    void capturedCertificateSummarySurvivesDeletion() {
        Certificate certificate = service.requestCertificate("list.example.test", List.of(), ValidationMethod.DNS,
                null, KeyAlgorithm.RSA_2048, null, null, Map.of(), REGION);
        service.deleteCertificate(certificate.getArn(), REGION);
        assertEquals(List.of(), service.certificateInUseBy(certificate));
        Map<String, List<String>> snapshot = service.certificateInUseBy(List.of(certificate));
        assertEquals(List.of(), snapshot.get(certificate.getArn()));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("other", List.of()));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.get(certificate.getArn()).add("other"));
    }

    @Test
    void anOlderImportNotificationReadsTheNewestCommittedMaterial() throws Exception {
        CertificateGenerator.GeneratedCertificate original = ca.issueServerCertificate(
                "rotation.example.test", List.of(), KeyAlgorithm.RSA_2048, null);
        Certificate certificate = service.importCertificate(original.certificatePem(), original.privateKeyPem(),
                ca.caPem(), null, Map.of(), REGION);
        CertificateGenerator.GeneratedCertificate first = ca.issueServerCertificate(
                "rotation.example.test", List.of(), KeyAlgorithm.RSA_2048, null);
        CertificateGenerator.GeneratedCertificate second = ca.issueServerCertificate(
                "rotation.example.test", List.of(), KeyAlgorithm.RSA_2048, null);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean pause = new AtomicBoolean(true);
        List<String> material = new CopyOnWriteArrayList<>();
        AcmCertificateReferenceProvider consumer = new AcmCertificateReferenceProvider() {
            @Override
            public List<CertificateReference> acmCertificateReferences() { return List.of(); }

            @Override
            public void certificateReimported(String arn, String region) {
                if (pause.getAndSet(false)) {
                    entered.countDown();
                    try {
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                }
                material.add(service.supplyWithCertificatesHeld(() -> service.getCertificate(arn, region).getCertificateBody()));
            }
        };
        Instance<AcmCertificateReferenceProvider> providers = mock();
        when(providers.iterator()).thenAnswer(call -> List.of(consumer).iterator());
        service.referenceProviders = providers;
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Certificate> older = worker.submit(() -> service.importCertificate(first.certificatePem(),
                    first.privateKeyPem(), ca.caPem(), certificate.getArn(), Map.of(), REGION));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.importCertificate(second.certificatePem(), second.privateKeyPem(), ca.caPem(),
                    certificate.getArn(), Map.of(), REGION);
            release.countDown();
            older.get(10, TimeUnit.SECONDS);
            assertEquals(List.of(second.certificatePem(), second.certificatePem()), material);
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void aDeleteWaitsForReferencePublicationThenObservesTheConsumer() throws Exception {
        Certificate certificate = service.requestCertificate("delete.example.test", List.of(), ValidationMethod.DNS,
                null, KeyAlgorithm.RSA_2048, null, null, Map.of(), REGION);
        String consumer = "arn:aws:cognito-idp:us-east-1:000000000000:userpool/guarded";
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch deleting = new CountDownLatch(1);
        AtomicLong holder = new AtomicLong();
        AtomicLong waiter = new AtomicLong();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> publication = workers.submit(() -> service.supplyWithCertificatesHeld(() -> {
                holder.set(Thread.currentThread().threadId());
                held.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                service.addInUseBy(certificate.getArn(), consumer, REGION);
                return null;
            }));
            assertTrue(held.await(5, TimeUnit.SECONDS));
            Future<AwsException> deletion = workers.submit(() -> {
                waiter.set(Thread.currentThread().threadId());
                deleting.countDown();
                return assertThrows(AwsException.class, () -> service.deleteCertificate(certificate.getArn(), REGION));
            });
            assertTrue(deleting.await(5, TimeUnit.SECONDS));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(waiter.get());
                assertEquals(Thread.State.BLOCKED, info.getThreadState());
                assertEquals(holder.get(), info.getLockOwnerId());
            });
            release.countDown();
            publication.get(10, TimeUnit.SECONDS);
            assertEquals("ResourceInUseException", deletion.get(10, TimeUnit.SECONDS).getErrorCode());
            service.removeInUseBy(certificate.getArn(), consumer, REGION);
            service.deleteCertificate(certificate.getArn(), REGION);
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private AcmService newService(int validationWaitSeconds) {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        return new AcmService(new InMemoryStorage<>(), generator, ca, regionResolver, validationWaitSeconds);
    }

    @ParameterizedTest
    @EnumSource(value = KeyAlgorithm.class, names = {"RSA_2048", "EC_prime256v1", "EC_secp384r1"})
    void issuesALeafOfTheRequestedAlgorithmSignedByTheLocalCa(KeyAlgorithm algorithm) throws Exception {
        Certificate cert = service.requestCertificate("api.example.test", List.of("*.example.test"),
                ValidationMethod.DNS, null, algorithm, null, null, Map.of(), REGION);

        X509Certificate leaf = assertChainsToTheCa(cert);
        assertEquals(CertificateType.AMAZON_ISSUED, cert.getType());
        assertEquals(CertificateStatus.ISSUED, cert.getStatus());
        assertEquals(algorithm, cert.getKeyAlgorithm());
        assertEquals(algorithm.getAlgorithm(), leaf.getPublicKey().getAlgorithm());
        if (leaf.getPublicKey() instanceof RSAPublicKey rsa) {
            assertEquals(algorithm.getKeySize(), rsa.getModulus().bitLength());
        } else {
            assertEquals(algorithm.getKeySize(),
                    ((ECPublicKey) leaf.getPublicKey()).getParams().getCurve().getField().getFieldSize());
        }
        assertEquals(List.of("api.example.test", "*.example.test"), cert.getSubjectAlternativeNames());
        assertEquals(List.of("api.example.test", "*.example.test"),
                leaf.getSubjectAlternativeNames().stream().map(san -> san.get(1)).toList());
    }

    @Test
    void privateCertificateChainsToTheSameCaAndIsIssuedAtOnce() throws Exception {
        Certificate cert = newService(30).requestCertificate("internal.example.test", null, ValidationMethod.DNS,
                null, null, PCA_ARN, null, null, REGION);

        assertChainsToTheCa(cert);
        assertEquals(CertificateType.PRIVATE, cert.getType());
        assertEquals(CertificateStatus.ISSUED, cert.getStatus(), "a private certificate waits for no validation");
        assertEquals(KeyAlgorithm.RSA_2048, cert.getKeyAlgorithm(), "the default algorithm");
        assertEquals(List.of("SUCCESS"),
                cert.getDomainValidationOptions().stream().map(DomainValidation::validationStatus).toList());
    }

    @Test
    void pendingValidationCertificateAlreadyHoldsItsLeafAndChain() throws Exception {
        Certificate cert = newService(30).requestCertificate("pending.example.test", List.of(),
                ValidationMethod.EMAIL, null, KeyAlgorithm.RSA_2048, null, null, null, REGION);

        assertEquals(CertificateStatus.PENDING_VALIDATION, cert.getStatus());
        assertNull(cert.getIssuedAt());
        assertChainsToTheCa(cert);
    }

    @Test
    void emailValidationReportsTheApprovalMailboxesAndNoDnsRecord() {
        // Issue #3252: the CNAME under _<token>.<domain> belongs to DNS validation only; an EMAIL
        // certificate reports instead where the approval mail went.
        Certificate cert = newService(30).requestCertificate("probe.example.test", List.of("*.probe.example.test"),
                ValidationMethod.EMAIL, null, KeyAlgorithm.RSA_2048, null, null, null, REGION);

        assertEquals(CertificateStatus.PENDING_VALIDATION, cert.getStatus());
        assertNull(cert.getIssuedAt());
        assertEquals(2, cert.getDomainValidationOptions().size());
        for (DomainValidation validation : cert.getDomainValidationOptions()) {
            assertEquals("EMAIL", validation.validationMethod());
            assertEquals("PENDING_VALIDATION", validation.validationStatus());
            assertNull(validation.resourceRecord(), "no DNS record for " + validation.domainName());
            assertEquals("probe.example.test", validation.validationDomain(),
                    "the wildcard is validated through its base domain");
            assertEquals(List.of("admin@probe.example.test", "administrator@probe.example.test",
                    "hostmaster@probe.example.test", "postmaster@probe.example.test", "webmaster@probe.example.test"),
                    validation.validationEmails());
        }
    }

    @Test
    void emailValidationFollowsTheConfiguredWaitLikeDnsValidation() {
        Certificate cert = service.requestCertificate("issued.example.test", List.of(),
                ValidationMethod.EMAIL, null, KeyAlgorithm.RSA_2048, null, null, null, REGION);

        assertEquals(CertificateStatus.ISSUED, cert.getStatus());
        DomainValidation validation = cert.getDomainValidationOptions().get(0);
        assertEquals("SUCCESS", validation.validationStatus());
        assertNull(validation.resourceRecord());
        assertEquals(5, validation.validationEmails().size());
    }

    @Test
    void dnsValidationCarriesNoValidationEmails() {
        Certificate cert = service.requestCertificate("dns.example.test", List.of(), ValidationMethod.DNS,
                null, KeyAlgorithm.RSA_2048, null, null, null, REGION);

        DomainValidation validation = cert.getDomainValidationOptions().get(0);
        assertEquals("CNAME", validation.resourceRecord().type());
        assertNull(validation.validationEmails());
    }

    @Test
    void domainValidationOptionsChooseTheValidationDomain() {
        Certificate cert = service.requestCertificate("site.sub.example.test", List.of("api.sub.example.test"),
                ValidationMethod.EMAIL, null, KeyAlgorithm.RSA_2048, null, null, null,
                Map.of("site.sub.example.test", "example.test"), REGION);

        DomainValidation site = cert.getDomainValidationOptions().get(0);
        assertEquals("site.sub.example.test", site.domainName());
        assertEquals("example.test", site.validationDomain());
        assertTrue(site.validationEmails().contains("admin@example.test"));
        DomainValidation api = cert.getDomainValidationOptions().get(1);
        assertEquals("api.sub.example.test", api.validationDomain(), "no option given: the domain itself");
    }

    @Test
    void domainValidationOptionsMustNameACertificateDomainAndASuperdomain() {
        AwsException unrelated = assertThrows(AwsException.class, () -> service.requestCertificate(
                "site.example.test", List.of(), ValidationMethod.EMAIL, null, KeyAlgorithm.RSA_2048, null, null, null,
                Map.of("other.example.test", "example.test"), REGION));
        assertEquals("InvalidDomainValidationOptionsException", unrelated.getErrorCode());

        AwsException notASuperdomain = assertThrows(AwsException.class, () -> service.requestCertificate(
                "site.example.test", List.of(), ValidationMethod.EMAIL, null, KeyAlgorithm.RSA_2048, null, null, null,
                Map.of("site.example.test", "example.net"), REGION));
        assertEquals("InvalidDomainValidationOptionsException", notASuperdomain.getErrorCode());
    }

    @Test
    void anIdempotentReplayReturnsTheSameCertificateInsteadOfIssuingAnother() {
        Certificate first = service.requestCertificate("same.example.test", List.of(), ValidationMethod.DNS,
                "token-1", KeyAlgorithm.RSA_2048, null, null, null, REGION);

        Certificate replay = service.requestCertificate("same.example.test", List.of(), ValidationMethod.DNS,
                "token-1", KeyAlgorithm.RSA_2048, null, null, null, REGION);

        assertEquals(first.getArn(), replay.getArn());
        assertEquals(first.getCertificateBody(), replay.getCertificateBody(), "no second leaf is minted");
        assertEquals(1, service.listCertificates(null, null, REGION, 100, null).certificates().size());
        AwsException refused = assertThrows(AwsException.class, () -> service.requestCertificate(
                "same.example.test", List.of(), ValidationMethod.DNS, "token-1", KeyAlgorithm.EC_prime256v1,
                null, null, null, REGION));
        assertEquals("IdempotencyException", refused.getErrorCode());
    }

    @Test
    void anImportKeepsTheUploadedChainAndAnImportWithoutOneHasNone() {
        FlociCertificateAuthority otherCa = FlociCertificateAuthority.loadOrCreate(tempDir.resolve("other-ca"));
        CertificateGenerator.GeneratedCertificate leaf = otherCa.issueServerCertificate(
                "imported.example.test", List.of(), KeyAlgorithm.RSA_2048, null);

        Certificate withChain = service.importCertificate(leaf.certificatePem(), leaf.privateKeyPem(),
                otherCa.caPem(), null, null, REGION);
        Certificate withoutChain = service.importCertificate(leaf.certificatePem(), leaf.privateKeyPem(),
                null, null, null, REGION);

        assertEquals(CertificateType.IMPORTED, withChain.getType());
        assertEquals(otherCa.caPem(), withChain.getCertificateChain(), "an import keeps the chain it was given");
        assertEquals(otherCa.certificate().getSubjectX500Principal().getName(), withChain.getIssuer());
        assertEquals(leaf.certificatePem(), withChain.getCertificateBody());
        assertNull(withoutChain.getCertificateChain());
    }

    @Test
    void exportReturnsTheStoredChainForPrivateAndForExportEnabledPublicCertificates() {
        String passphrase = Base64.getEncoder().encodeToString("abcd1234".getBytes(StandardCharsets.US_ASCII));
        Certificate privateCert = service.requestCertificate("export-private.example.test", null,
                ValidationMethod.DNS, null, null, PCA_ARN, null, null, REGION);
        Certificate publicCert = service.requestCertificate("export-public.example.test", null,
                ValidationMethod.DNS, null, null, null, new CertificateOptions(null, "ENABLED"), null, REGION);
        Certificate ecCert = service.requestCertificate("export-ec.example.test", null, ValidationMethod.DNS, null,
                KeyAlgorithm.EC_prime256v1, PCA_ARN, null, null, REGION);

        Certificate exportedPrivate = service.exportCertificate(privateCert.getArn(), passphrase, REGION);
        Certificate exportedPublic = service.exportCertificate(publicCert.getArn(), passphrase, REGION);
        Certificate exportedEc = service.exportCertificate(ecCert.getArn(), passphrase, REGION);

        assertEquals(privateCert.getCertificateBody(), exportedPrivate.getCertificateBody());
        assertEquals(ca.caPem(), exportedPrivate.getCertificateChain());
        assertEquals(ca.caPem(), exportedPublic.getCertificateChain());
        assertEquals(ca.caPem(), exportedEc.getCertificateChain());
        assertTrue(exportedPrivate.getPrivateKey().startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----"));
        assertTrue(exportedEc.getPrivateKey().startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----"),
                "an EC private key is exportable too");
    }

    @ParameterizedTest
    @EnumSource(value = KeyAlgorithm.class, names = {"RSA_2048", "EC_prime256v1"})
    void renewReissuesThePrivateCertificateUnderTheSameArn(KeyAlgorithm algorithm) throws Exception {
        Certificate requested = service.requestCertificate("renew.example.test", List.of("www.renew.example.test"),
                ValidationMethod.DNS, null, algorithm, PCA_ARN, new CertificateOptions(null, "ENABLED"),
                Map.of("team", "edge"), null, REGION);
        String arn = requested.getArn();
        String originalBody = requested.getCertificateBody();
        String originalKey = requested.getPrivateKey();
        String originalSerial = requested.getSerial();

        Certificate renewed = service.renewCertificate(arn, REGION);

        Certificate stored = service.getCertificate(arn, REGION);
        assertEquals(arn, renewed.getArn());
        assertTrue(!originalBody.equals(stored.getCertificateBody()), "the renewed body is new material");
        assertTrue(!originalSerial.equals(stored.getSerial()), "the renewed leaf has its own serial");
        assertTrue(!originalKey.equals(stored.getPrivateKey()), "renewal mints a fresh key pair");
        X509Certificate leaf = assertChainsToTheCa(stored);
        assertEquals(algorithm, generator.detectKeyAlgorithm(leaf.getPublicKey()));
        assertEquals(List.of("renew.example.test", "www.renew.example.test"), stored.getSubjectAlternativeNames());
        assertEquals(List.of("renew.example.test", "www.renew.example.test"),
                leaf.getSubjectAlternativeNames().stream().map(san -> san.get(1)).toList());
        assertEquals(Map.of("team", "edge"), stored.getTags());
        assertEquals("ENABLED", stored.getCertOptions().export());
        assertEquals(CertificateStatus.ISSUED, stored.getStatus());

        String passphrase = Base64.getEncoder().encodeToString("abcd1234".getBytes(StandardCharsets.US_ASCII));
        Certificate exported = service.exportCertificate(arn, passphrase, REGION);
        assertEquals(stored.getCertificateBody(), exported.getCertificateBody());
        assertEquals(ca.caPem(), exported.getCertificateChain());
    }

    @Test
    void renewRefusesAPublicCertificateWithoutTouchingItsMaterial() {
        Certificate requested = service.requestCertificate("renew-public.example.test", null, ValidationMethod.DNS,
                null, null, null, null, null, REGION);
        String originalBody = requested.getCertificateBody();

        AwsException failure = assertThrows(AwsException.class,
                () -> service.renewCertificate(requested.getArn(), REGION));

        assertEquals("InvalidArnException", failure.getErrorCode());
        assertEquals(originalBody, service.getCertificate(requested.getArn(), REGION).getCertificateBody());
    }

    @Test
    void concurrentRequestsEachGetTheirOwnCertificateChainedToTheCa() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Certificate>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                String domain = "burst-" + i + ".example.test";
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.requestCertificate(domain, List.of(), ValidationMethod.DNS, null,
                            KeyAlgorithm.RSA_2048, null, null, null, REGION);
                }));
            }
            start.countDown();

            Set<String> arns = new HashSet<>();
            Set<String> serials = new HashSet<>();
            for (Future<Certificate> future : futures) {
                Certificate cert = future.get(60, TimeUnit.SECONDS);
                assertChainsToTheCa(cert);
                arns.add(cert.getArn());
                serials.add(cert.getSerial());
            }
            assertEquals(threads, arns.size(), "every request gets its own ARN");
            assertEquals(threads, serials.size(), "every leaf gets its own serial");
            assertEquals(threads, service.listCertificates(null, null, REGION, 100, null).certificates().size());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The certificate is a server leaf issued by the CA, the chain is exactly the CA's PEM, the pair
     * builds a PKIX path with that chain as the only trust anchor, the stored private key belongs to
     * the leaf, and every metadata field equals what the certificate itself says.
     */
    private X509Certificate assertChainsToTheCa(Certificate cert) throws Exception {
        X509Certificate leaf = Pem.parseCertificate(cert.getCertificateBody());
        assertEquals(ca.caPem(), cert.getCertificateChain(), "CertificateChain is the CA PEM");
        assertEquals(ca.certificate().getSubjectX500Principal(), leaf.getIssuerX500Principal());
        assertTrue(ca.isIssuedByUs(leaf), "signed by the CA key");
        assertEquals(-1, leaf.getBasicConstraints(), "a leaf, not a CA");
        assertEquals(SERVER_AND_CLIENT_AUTH, leaf.getExtendedKeyUsage());

        X509Certificate anchor = Pem.parseCertificate(cert.getCertificateChain());
        PKIXParameters params = new PKIXParameters(Set.of(new TrustAnchor(anchor, null)));
        params.setRevocationEnabled(false);
        CertPathValidator.getInstance("PKIX").validate(
                CertificateFactory.getInstance("X.509").generateCertPath(List.of(leaf)), params);

        assertTrue(Pem.isPair(Pem.parsePrivateKey(cert.getPrivateKey()), leaf.getPublicKey()),
                "the stored private key matches the certificate");
        assertEquals(leaf.getSubjectX500Principal().getName(), cert.getSubject());
        assertEquals(ca.certificate().getSubjectX500Principal().getName(), cert.getIssuer());
        assertEquals(leaf.getSerialNumber(), new BigInteger(cert.getSerial().replace(":", ""), 16));
        assertEquals(leaf.getNotBefore().toInstant(), cert.getNotBefore());
        assertEquals(leaf.getNotAfter().toInstant(), cert.getNotAfter());
        assertEquals("SHA512WITHRSA", cert.getSignatureAlgorithm(), "the RSA CA's signature, whatever the leaf key");
        return leaf;
    }
}
