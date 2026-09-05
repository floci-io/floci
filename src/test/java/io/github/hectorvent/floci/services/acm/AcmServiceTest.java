package io.github.hectorvent.floci.services.acm;

import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.acm.model.Certificate;
import io.github.hectorvent.floci.services.acm.model.CertificateOptions;
import io.github.hectorvent.floci.services.acm.model.CertificateStatus;
import io.github.hectorvent.floci.services.acm.model.CertificateType;
import io.github.hectorvent.floci.services.acm.model.DomainValidation;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.acm.model.ValidationMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    private AcmService newService(int validationWaitSeconds) {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        return new AcmService(new InMemoryStorage<>(), generator, ca, regionResolver, validationWaitSeconds);
    }

    @ParameterizedTest
    @EnumSource(KeyAlgorithm.class)
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
        X509Certificate leaf = generator.parseCertificate(cert.getCertificateBody());
        assertEquals(ca.caPem(), cert.getCertificateChain(), "CertificateChain is the CA PEM");
        assertEquals(ca.certificate().getSubjectX500Principal(), leaf.getIssuerX500Principal());
        assertTrue(ca.isIssuedByUs(leaf), "signed by the CA key");
        assertEquals(-1, leaf.getBasicConstraints(), "a leaf, not a CA");
        assertEquals(SERVER_AND_CLIENT_AUTH, leaf.getExtendedKeyUsage());

        X509Certificate anchor = generator.parseCertificate(cert.getCertificateChain());
        PKIXParameters params = new PKIXParameters(Set.of(new TrustAnchor(anchor, null)));
        params.setRevocationEnabled(false);
        CertPathValidator.getInstance("PKIX").validate(
                CertificateFactory.getInstance("X.509").generateCertPath(List.of(leaf)), params);

        assertTrue(CertificateGenerator.isPair(generator.parsePrivateKey(cert.getPrivateKey()), leaf.getPublicKey()),
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
