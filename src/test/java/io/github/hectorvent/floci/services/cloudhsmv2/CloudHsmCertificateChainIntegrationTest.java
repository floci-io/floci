package io.github.hectorvent.floci.services.cloudhsmv2;

import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.util.Arrays;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudHsmCertificateChainIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createClusterIssuesTwoCertificatesForTheSameHardwareKey() throws Exception {
        JsonPath response = given()
                .header("X-Amz-Target", "BaldrApiService.CreateCluster")
                .contentType("application/x-amz-json-1.1")
                .body("""
                        {"HsmType":"hsm1.medium","SubnetIds":["subnet-abcdef01"]}
                        """)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().jsonPath();

        X509Certificate manufacturer = Pem.parseCertificate(
                response.getString("Cluster.Certificates.ManufacturerHardwareCertificate"));
        X509Certificate aws = Pem.parseCertificate(
                response.getString("Cluster.Certificates.AwsHardwareCertificate"));
        X509Certificate hsm = Pem.parseCertificate(
                response.getString("Cluster.Certificates.HsmCertificate"));

        assertEquals(manufacturer.getSubjectX500Principal(), aws.getSubjectX500Principal());
        assertArrayEquals(manufacturer.getPublicKey().getEncoded(), aws.getPublicKey().getEncoded());
        assertArrayEquals(subjectKeyIdentifier(manufacturer), subjectKeyIdentifier(aws));
        assertTrue(manufacturer.getBasicConstraints() >= 0);
        assertTrue(aws.getBasicConstraints() >= 0);
        assertTrue(manufacturer.getKeyUsage()[5]);
        assertTrue(aws.getKeyUsage()[5]);

        assertNotEquals(manufacturer.getSubjectX500Principal(), manufacturer.getIssuerX500Principal());
        assertNotEquals(aws.getSubjectX500Principal(), aws.getIssuerX500Principal());
        assertNotEquals(manufacturer.getIssuerX500Principal(), aws.getIssuerX500Principal());
        assertFalse(Arrays.equals(authorityKeyIdentifier(manufacturer), authorityKeyIdentifier(aws)));
        assertFalse(Arrays.equals(subjectKeyIdentifier(manufacturer), authorityKeyIdentifier(manufacturer)));
        assertFalse(Arrays.equals(subjectKeyIdentifier(aws), authorityKeyIdentifier(aws)));

        assertEquals(-1, hsm.getBasicConstraints());
        assertFalse(Arrays.equals(hsm.getPublicKey().getEncoded(), aws.getPublicKey().getEncoded()));
        assertIssuedBy(hsm, aws);
        assertIssuedBy(hsm, manufacturer);
    }

    private static void assertIssuedBy(X509Certificate certificate, X509Certificate issuer) throws Exception {
        assertEquals(issuer.getSubjectX500Principal(), certificate.getIssuerX500Principal());
        assertNotNull(certificate.getExtensionValue(Extension.subjectKeyIdentifier.getId()));
        assertArrayEquals(subjectKeyIdentifier(issuer), authorityKeyIdentifier(certificate));
        certificate.verify(issuer.getPublicKey());
    }

    private static byte[] subjectKeyIdentifier(X509Certificate certificate) {
        assertNotNull(certificate.getExtensionValue(Extension.subjectKeyIdentifier.getId()));
        return SubjectKeyIdentifier.getInstance(ASN1OctetString.getInstance(
                certificate.getExtensionValue(Extension.subjectKeyIdentifier.getId())).getOctets()).getKeyIdentifier();
    }

    private static byte[] authorityKeyIdentifier(X509Certificate certificate) {
        assertNotNull(certificate.getExtensionValue(Extension.authorityKeyIdentifier.getId()));
        return AuthorityKeyIdentifier.getInstance(ASN1OctetString.getInstance(
                certificate.getExtensionValue(Extension.authorityKeyIdentifier.getId())).getOctets()).getKeyIdentifier();
    }
}
