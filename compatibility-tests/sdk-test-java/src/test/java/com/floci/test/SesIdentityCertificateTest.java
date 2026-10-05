package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.AlreadyExistsException;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.IdentityCertificate;
import software.amazon.awssdk.services.sesv2.model.IdentityCertificateStatus;
import software.amazon.awssdk.services.sesv2.model.ListEmailIdentityCertificatesResponse;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK compatibility test for the S/MIME identity certificate operations on a verified domain identity:
 * {@code associateEmailIdentityCertificate}, {@code listEmailIdentityCertificates} (including the
 * {@code CertificateExpiryTime} timestamp an ACTIVE entry carries) and
 * {@code disassociateEmailIdentityCertificate}, plus the {@code deleteEmailIdentity} guard.
 */
@DisplayName("SES v2 Identity Certificates")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesIdentityCertificateTest {

    private static final String DOMAIN = TestFixtures.uniqueName("compat-smime") + ".example.com";

    private static SesV2Client sesV2;
    private static AcmClient acm;
    private static String certificateArn;
    private static String missingCertificateArn;

    @BeforeAll
    static void setup() throws Exception {
        sesV2 = TestFixtures.sesV2Client();
        acm = TestFixtures.acmClient();
        TestFixtures.verifySesDomainIdentityViaRoute53(sesV2, DOMAIN);
        byte[][] certAndKey = generateSelfSignedCert("alice@" + DOMAIN);
        certificateArn = acm.importCertificate(b -> b
                .certificate(SdkBytes.fromByteArray(certAndKey[0]))
                .privateKey(SdkBytes.fromByteArray(certAndKey[1]))).certificateArn();
        missingCertificateArn = certificateArn.substring(0, certificateArn.indexOf("certificate/"))
                + "certificate/00000000-0000-0000-0000-000000000000";
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            try {
                sesV2.disassociateEmailIdentityCertificate(b -> b.emailIdentity(DOMAIN).fromAddress("alice@" + DOMAIN));
                sesV2.disassociateEmailIdentityCertificate(b -> b.emailIdentity(DOMAIN).fromAddress("bob@" + DOMAIN));
                sesV2.deleteEmailIdentity(b -> b.emailIdentity(DOMAIN));
            } catch (Exception ignored) {
                // Best-effort cleanup; the emulator is reset between runs.
            }
            sesV2.close();
        }
        if (acm != null) {
            try {
                acm.deleteCertificate(b -> b.certificateArn(certificateArn));
            } catch (Exception ignored) {
                // Best-effort cleanup; the emulator is reset between runs.
            }
            acm.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Associate certificates and list them with their status")
    void associateAndList() {
        sesV2.associateEmailIdentityCertificate(b -> b.emailIdentity(DOMAIN)
                .fromAddress("bob@" + DOMAIN).certificateArn(missingCertificateArn));
        sesV2.associateEmailIdentityCertificate(b -> b.emailIdentity(DOMAIN)
                .fromAddress("alice@" + DOMAIN).certificateArn(certificateArn));

        ListEmailIdentityCertificatesResponse response =
                sesV2.listEmailIdentityCertificates(b -> b.emailIdentity(DOMAIN));

        assertThat(response.certificates()).extracting(IdentityCertificate::fromAddress)
                .containsExactly("alice@" + DOMAIN, "bob@" + DOMAIN);
        IdentityCertificate active = response.certificates().get(0);
        assertThat(active.status()).isEqualTo(IdentityCertificateStatus.ACTIVE);
        assertThat(active.certificateArn()).isEqualTo(certificateArn);
        assertThat(active.certificateExpiryTime()).isAfter(Instant.now());
        IdentityCertificate failed = response.certificates().get(1);
        assertThat(failed.status()).isEqualTo(IdentityCertificateStatus.FAILED);
        assertThat(failed.certificateExpiryTime()).isNull();
        assertThat(response.nextToken()).isNull();
    }

    @Test
    @Order(2)
    @DisplayName("List pages with PageSize and NextToken")
    void listPages() {
        ListEmailIdentityCertificatesResponse first =
                sesV2.listEmailIdentityCertificates(b -> b.emailIdentity(DOMAIN).pageSize(1));
        ListEmailIdentityCertificatesResponse second = sesV2.listEmailIdentityCertificates(b -> b
                .emailIdentity(DOMAIN).pageSize(1).nextToken(first.nextToken()));

        assertThat(first.certificates()).extracting(IdentityCertificate::fromAddress)
                .containsExactly("alice@" + DOMAIN);
        assertThat(second.certificates()).extracting(IdentityCertificate::fromAddress)
                .containsExactly("bob@" + DOMAIN);
        assertThat(second.nextToken()).isNull();
    }

    @Test
    @Order(3)
    @DisplayName("Errors surface as the modelled exceptions")
    void errors() {
        assertThatThrownBy(() -> sesV2.associateEmailIdentityCertificate(b -> b.emailIdentity(DOMAIN)
                .fromAddress("ALICE@" + DOMAIN).certificateArn(certificateArn)))
                .isInstanceOf(AlreadyExistsException.class);
        assertThatThrownBy(() -> sesV2.associateEmailIdentityCertificate(b -> b.emailIdentity(DOMAIN)
                .certificateArn(certificateArn)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("FromAddress is required when EmailIdentity is a domain.");
        assertThatThrownBy(() -> sesV2.listEmailIdentityCertificates(b -> b.emailIdentity("nosuch-" + DOMAIN)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @Order(4)
    @DisplayName("DeleteEmailIdentity is refused until every certificate is disassociated")
    void deleteGuard() {
        assertThatThrownBy(() -> sesV2.deleteEmailIdentity(b -> b.emailIdentity(DOMAIN)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("because it has certificates associated with it");

        sesV2.disassociateEmailIdentityCertificate(b -> b.emailIdentity(DOMAIN).fromAddress("alice@" + DOMAIN));
        sesV2.disassociateEmailIdentityCertificate(b -> b.emailIdentity(DOMAIN).fromAddress("bob@" + DOMAIN));
        sesV2.disassociateEmailIdentityCertificate(b -> b.emailIdentity(DOMAIN).fromAddress("bob@" + DOMAIN));

        assertThat(sesV2.listEmailIdentityCertificates(b -> b.emailIdentity(DOMAIN)).certificates()).isEmpty();
        sesV2.deleteEmailIdentity(b -> b.emailIdentity(DOMAIN));
    }

    private static byte[][] generateSelfSignedCert(String email) throws Exception {
        Path keyFile = Files.createTempFile("smime-key", ".pem");
        Path certFile = Files.createTempFile("smime-cert", ".pem");
        try {
            ProcessBuilder pb = new ProcessBuilder("openssl", "req", "-x509", "-newkey", "rsa:2048",
                    "-keyout", keyFile.toString(), "-out", certFile.toString(),
                    "-days", "365", "-nodes", "-subj", "/CN=" + email,
                    "-addext", "subjectAltName=email:" + email,
                    "-addext", "basicConstraints=critical,CA:FALSE",
                    "-addext", "keyUsage=critical,digitalSignature",
                    "-addext", "extendedKeyUsage=emailProtection");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().readAllBytes();
            if (p.waitFor() != 0) {
                throw new IllegalStateException("openssl failed with exit code " + p.exitValue());
            }
            return new byte[][] { Files.readAllBytes(certFile), Files.readAllBytes(keyFile) };
        } finally {
            Files.deleteIfExists(keyFile);
            Files.deleteIfExists(certFile);
        }
    }
}
