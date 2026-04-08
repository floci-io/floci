package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.acm.model.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ACM Certificate Manager")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AcmTest {

    private static AcmClient acm;
    private static final List<String> arnsToCleanup = new ArrayList<>();

    // Lifecycle test state
    private static String requestedCertArn;

    // Import/Export test state
    private static String importedCertArn;
    private static byte[] importedCertPem;
    private static byte[] importedKeyPem;
    private static String exportTestCertArn;

    // Tagging test state
    private static String tagTestCertArn;

    @BeforeAll
    static void setup() {
        acm = TestFixtures.acmClient();
    }

    @AfterAll
    static void cleanup() {
        if (acm != null) {
            for (String arn : arnsToCleanup) {
                try {
                    acm.deleteCertificate(b -> b.certificateArn(arn));
                } catch (Exception ignored) {}
            }
            acm.close();
        }
    }

    // ============================================
    // Helper: generate a self-signed certificate using openssl
    // ============================================
    private static byte[][] generateSelfSignedCert() throws Exception {
        Path keyFile = Files.createTempFile("test-key", ".pem");
        Path certFile = Files.createTempFile("test-cert", ".pem");
        try {
            ProcessBuilder pb = new ProcessBuilder("openssl", "req", "-x509", "-newkey", "rsa:2048",
                    "-keyout", keyFile.toString(), "-out", certFile.toString(),
                    "-days", "365", "-nodes", "-subj", "/CN=test.example.com");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().readAllBytes(); // consume output
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("openssl failed with exit code " + exitCode);
            }

            byte[] certPem = Files.readAllBytes(certFile);
            byte[] keyPem = Files.readAllBytes(keyFile);
            return new byte[][] { certPem, keyPem };
        } finally {
            Files.deleteIfExists(keyFile);
            Files.deleteIfExists(certFile);
        }
    }

    // ============================================
    // US1: Lifecycle tests
    // ============================================

    @Test
    @Order(1)
    @DisplayName("Request a certificate")
    void testRequestCertificate() {
        String domain = TestFixtures.uniqueName("java-test") + ".example.com";

        RequestCertificateResponse response = acm.requestCertificate(b -> b
                .domainName(domain));

        requestedCertArn = response.certificateArn();
        arnsToCleanup.add(requestedCertArn);

        assertThat(requestedCertArn).isNotNull();
        assertThat(requestedCertArn).matches("arn:aws:acm:.*:.*:certificate/.*");
    }

    @Test
    @Order(2)
    @DisplayName("Describe a certificate")
    void testDescribeCertificate() {
        Assumptions.assumeTrue(requestedCertArn != null, "RequestCertificate must succeed first");

        DescribeCertificateResponse response = acm.describeCertificate(b -> b
                .certificateArn(requestedCertArn));

        CertificateDetail detail = response.certificate();
        assertThat(detail.domainName()).contains("example.com");
        assertThat(detail.statusAsString()).isNotNull();
    }

    @Test
    @Order(3)
    @DisplayName("Get a certificate")
    void testGetCertificate() {
        Assumptions.assumeTrue(requestedCertArn != null, "RequestCertificate must succeed first");

        GetCertificateResponse response = acm.getCertificate(b -> b
                .certificateArn(requestedCertArn));

        assertThat(response.certificate()).isNotNull();
        assertThat(response.certificate()).contains("BEGIN CERTIFICATE");
    }

    @Test
    @Order(4)
    @DisplayName("List certificates contains the requested cert")
    void testListCertificates() {
        Assumptions.assumeTrue(requestedCertArn != null, "RequestCertificate must succeed first");

        ListCertificatesResponse response = acm.listCertificates();

        assertThat(response.certificateSummaryList())
                .anyMatch(s -> s.certificateArn().equals(requestedCertArn));
    }

    @Test
    @Order(5)
    @DisplayName("Delete a certificate")
    void testDeleteCertificate() {
        Assumptions.assumeTrue(requestedCertArn != null, "RequestCertificate must succeed first");

        acm.deleteCertificate(b -> b.certificateArn(requestedCertArn));
        arnsToCleanup.remove(requestedCertArn);

        assertThatThrownBy(() -> acm.describeCertificate(b -> b
                .certificateArn(requestedCertArn)))
                .isInstanceOf(AcmException.class);
    }

    // ============================================
    // US2: Import/Export tests
    // ============================================

    @Test
    @Order(10)
    @DisplayName("Import a self-signed certificate")
    void testImportCertificate() throws Exception {
        byte[][] certAndKey = generateSelfSignedCert();
        importedCertPem = certAndKey[0];
        importedKeyPem = certAndKey[1];

        ImportCertificateResponse response = acm.importCertificate(b -> b
                .certificate(SdkBytes.fromByteArray(importedCertPem))
                .privateKey(SdkBytes.fromByteArray(importedKeyPem)));

        importedCertArn = response.certificateArn();
        arnsToCleanup.add(importedCertArn);

        assertThat(importedCertArn).isNotNull();
        assertThat(importedCertArn).matches("arn:aws:acm:.*:.*:certificate/.*");
    }

    @Test
    @Order(11)
    @DisplayName("Get an imported certificate")
    void testGetImportedCertificate() {
        Assumptions.assumeTrue(importedCertArn != null, "ImportCertificate must succeed first");

        GetCertificateResponse response = acm.getCertificate(b -> b
                .certificateArn(importedCertArn));

        assertThat(response.certificate()).isNotNull();
        assertThat(response.certificate()).contains("BEGIN CERTIFICATE");
    }

    @Test
    @Order(12)
    @DisplayName("Export an imported certificate with passphrase")
    void testExportCertificate() {
        Assumptions.assumeTrue(importedCertArn != null, "ImportCertificate must succeed first");

        SdkBytes passphrase = SdkBytes.fromUtf8String("test-passphrase-123");

        ExportCertificateResponse response = acm.exportCertificate(b -> b
                .certificateArn(importedCertArn)
                .passphrase(passphrase));

        assertThat(response.certificate()).isNotNull();
        assertThat(response.certificate()).contains("BEGIN CERTIFICATE");
        assertThat(response.privateKey()).isNotNull();
        assertThat(response.privateKey()).contains("-----BEGIN");
    }

    @Test
    @Order(13)
    @DisplayName("Export a requested (non-imported) certificate fails")
    void testExportRequestedCertificateFails() {
        // Request a new cert (not imported, so no private key to export)
        String domain = TestFixtures.uniqueName("export-fail") + ".example.com";
        RequestCertificateResponse reqResp = acm.requestCertificate(b -> b.domainName(domain));
        exportTestCertArn = reqResp.certificateArn();
        arnsToCleanup.add(exportTestCertArn);

        SdkBytes passphrase = SdkBytes.fromUtf8String("test-passphrase");

        assertThatThrownBy(() -> acm.exportCertificate(b -> b
                .certificateArn(exportTestCertArn)
                .passphrase(passphrase)))
                .isInstanceOf(AcmException.class);
    }

    // ============================================
    // US3: Tagging tests
    // ============================================

    @Test
    @Order(20)
    @DisplayName("Add and list tags on a certificate")
    void testAddTags() {
        String domain = TestFixtures.uniqueName("tag-test") + ".example.com";
        RequestCertificateResponse reqResp = acm.requestCertificate(b -> b.domainName(domain));
        tagTestCertArn = reqResp.certificateArn();
        arnsToCleanup.add(tagTestCertArn);

        acm.addTagsToCertificate(b -> b
                .certificateArn(tagTestCertArn)
                .tags(
                        software.amazon.awssdk.services.acm.model.Tag.builder().key("Environment").value("test").build(),
                        software.amazon.awssdk.services.acm.model.Tag.builder().key("Project").value("floci").build()
                ));

        ListTagsForCertificateResponse tagsResp = acm.listTagsForCertificate(b -> b
                .certificateArn(tagTestCertArn));

        assertThat(tagsResp.tags()).hasSize(2);
        assertThat(tagsResp.tags())
                .anyMatch(t -> t.key().equals("Environment") && t.value().equals("test"));
        assertThat(tagsResp.tags())
                .anyMatch(t -> t.key().equals("Project") && t.value().equals("floci"));
    }

    @Test
    @Order(21)
    @DisplayName("Remove a tag from a certificate")
    void testRemoveTags() {
        Assumptions.assumeTrue(tagTestCertArn != null, "AddTags must succeed first");

        acm.removeTagsFromCertificate(b -> b
                .certificateArn(tagTestCertArn)
                .tags(software.amazon.awssdk.services.acm.model.Tag.builder().key("Project").value("floci").build()));

        ListTagsForCertificateResponse tagsResp = acm.listTagsForCertificate(b -> b
                .certificateArn(tagTestCertArn));

        assertThat(tagsResp.tags()).hasSize(1);
        assertThat(tagsResp.tags())
                .anyMatch(t -> t.key().equals("Environment") && t.value().equals("test"));
        assertThat(tagsResp.tags())
                .noneMatch(t -> t.key().equals("Project"));
    }

    // ============================================
    // US4: Account configuration tests
    // ============================================

    @Test
    @Order(30)
    @DisplayName("Put and get account configuration")
    void testPutAndGetAccountConfiguration() {
        acm.putAccountConfiguration(b -> b
                .expiryEvents(e -> e.daysBeforeExpiry(45))
                .idempotencyToken(TestFixtures.uniqueName()));

        var response = acm.getAccountConfiguration(b -> b.build());

        assertThat(response.expiryEvents()).isNotNull();
        assertThat(response.expiryEvents().daysBeforeExpiry()).isEqualTo(45);
    }

    // ============================================
    // US5: Error handling tests
    // ============================================

    @Test
    @Order(40)
    @DisplayName("Describe a non-existent certificate throws exception")
    void testDescribeNonExistentCertificate() {
        String fakeArn = "arn:aws:acm:us-east-1:000000000000:certificate/00000000-0000-0000-0000-000000000000";

        assertThatThrownBy(() -> acm.describeCertificate(b -> b
                .certificateArn(fakeArn)))
                .isInstanceOf(AcmException.class);
    }

    @Test
    @Order(41)
    @DisplayName("Request certificate with SANs")
    void testRequestWithSANs() {
        String domain = TestFixtures.uniqueName("san-test") + ".example.com";
        String san1 = "www." + domain;
        String san2 = "api." + domain;

        RequestCertificateResponse response = acm.requestCertificate(b -> b
                .domainName(domain)
                .subjectAlternativeNames(san1, san2));

        String arn = response.certificateArn();
        arnsToCleanup.add(arn);
        assertThat(arn).isNotNull();

        DescribeCertificateResponse descResp = acm.describeCertificate(b -> b
                .certificateArn(arn));

        assertThat(descResp.certificate().subjectAlternativeNames())
                .contains(san1, san2);
    }

    @Test
    @Order(42)
    @DisplayName("Import invalid PEM throws exception")
    void testImportInvalidPEM() {
        byte[] garbage = "this is not a valid certificate".getBytes();

        assertThatThrownBy(() -> acm.importCertificate(b -> b
                .certificate(SdkBytes.fromByteArray(garbage))
                .privateKey(SdkBytes.fromByteArray(garbage))))
                .isInstanceOf(AcmException.class);
    }
}
