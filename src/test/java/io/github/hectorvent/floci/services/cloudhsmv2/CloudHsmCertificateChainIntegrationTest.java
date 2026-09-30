package io.github.hectorvent.floci.services.cloudhsmv2;

import io.github.hectorvent.floci.services.acm.CertificateGenerator;
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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class CloudHsmCertificateChainIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createClusterIssuesVerifiableHardwareCertificateChain() throws Exception {
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

        CertificateGenerator generator = new CertificateGenerator();
        X509Certificate manufacturer = generator.parseCertificate(
                response.getString("Cluster.Certificates.ManufacturerHardwareCertificate"));
        X509Certificate aws = generator.parseCertificate(
                response.getString("Cluster.Certificates.AwsHardwareCertificate"));
        X509Certificate hsm = generator.parseCertificate(
                response.getString("Cluster.Certificates.HsmCertificate"));

        assertIssuedBy(manufacturer, manufacturer);
        assertIssuedBy(aws, manufacturer);
        assertIssuedBy(hsm, aws);
    }

    private static void assertIssuedBy(X509Certificate certificate, X509Certificate issuer) throws Exception {
        assertEquals(issuer.getSubjectX500Principal(), certificate.getIssuerX500Principal());
        assertNotNull(certificate.getExtensionValue(Extension.subjectKeyIdentifier.getId()));
        assertNotNull(certificate.getExtensionValue(Extension.authorityKeyIdentifier.getId()));
        byte[] issuerKeyId = SubjectKeyIdentifier.getInstance(ASN1OctetString.getInstance(
                issuer.getExtensionValue(Extension.subjectKeyIdentifier.getId())).getOctets()).getKeyIdentifier();
        byte[] authorityKeyId = AuthorityKeyIdentifier.getInstance(ASN1OctetString.getInstance(
                certificate.getExtensionValue(Extension.authorityKeyIdentifier.getId())).getOctets()).getKeyIdentifier();
        assertArrayEquals(issuerKeyId, authorityKeyId);
        certificate.verify(issuer.getPublicKey());
    }
}
