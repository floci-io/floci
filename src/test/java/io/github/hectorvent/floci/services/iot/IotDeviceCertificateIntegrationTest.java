package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.iot.model.IotCertificate;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Device certificates are real X.509 leaves issued by the local CA, with the AWS certificate id
 * (lowercase hex SHA-256 of the DER) and, for {@code CreateKeysAndCertificate}, a key pair that
 * matches the certificate.
 */
@QuarkusTest
class IotDeviceCertificateIntegrationTest {

    @jakarta.inject.Inject
    IotService iotService;

    @org.junit.jupiter.api.BeforeAll
    static void bouncyCastle() {
        if (java.security.Security.getProvider(org.bouncycastle.jce.provider.BouncyCastleProvider.PROVIDER_NAME) == null) {
            java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        }
    }

    @Test
    void createKeysAndCertificateIssuesARealClientCertificate() throws Exception {
        JsonPath body = given()
            .contentType("application/json")
            .queryParam("setAsActive", true)
            .body("{}")
        .when()
            .post("/keys-and-certificate")
        .then()
            .statusCode(200)
            .extract().jsonPath();

        X509Certificate cert = parse(body.getString("certificatePem"));
        X509Certificate ca = parse(given().when().get("/_floci/ca.pem").then().statusCode(200).extract().asString());

        cert.verify(ca.getPublicKey());
        assertEquals(ca.getSubjectX500Principal(), cert.getIssuerX500Principal());
        assertEquals("CN=AWS IoT Certificate", cert.getSubjectX500Principal().getName());
        assertEquals(List.of("1.3.6.1.5.5.7.3.2"), cert.getExtendedKeyUsage(), "clientAuth");
        assertEquals(-1, cert.getBasicConstraints());
        assertEquals("RSA", cert.getPublicKey().getAlgorithm());
        assertEquals(2048, ((java.security.interfaces.RSAPublicKey) cert.getPublicKey()).getModulus().bitLength());
        assertEquals(expectedNotAfter(ca), cert.getNotAfter().toInstant(),
                "AWS IoT-issued certificates all expire at the end of 2049, never past the CA");
        assertTrue(!cert.getNotBefore().toInstant().isAfter(java.time.Instant.now()), "valid from issue time");

        String expectedId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        assertEquals(expectedId, body.getString("certificateId"), "AWS certificateId is sha256(DER), lowercase hex");
        assertTrue(body.getString("certificateArn").endsWith(":cert/" + expectedId));

        String privateKeyPem = body.getString("keyPair.PrivateKey");
        assertTrue(privateKeyPem.startsWith("-----BEGIN RSA PRIVATE KEY-----"), "AWS returns a PKCS#1 RSA key");
        PrivateKey privateKey = new CertificateGenerator().parsePrivateKey(privateKeyPem);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update("floci".getBytes(StandardCharsets.UTF_8));
        byte[] sig = signer.sign();
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(cert.getPublicKey());
        verifier.update("floci".getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(sig), "private key must match the certificate's public key");

        String publicKeyPem = body.getString("keyPair.PublicKey");
        assertTrue(publicKeyPem.startsWith("-----BEGIN PUBLIC KEY-----"));
        assertEquals(Base64.getEncoder().encodeToString(cert.getPublicKey().getEncoded()),
                publicKeyPem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
                        .replaceAll("\\s", ""));
    }

    @Test
    void thePrivateKeyIsReturnedOnceAndNeverStored() {
        JsonPath created = create();

        IotCertificate stored = iotService.describeCertificate(created.getString("certificateId"), "us-east-1");
        assertNull(stored.getPrivateKey(), "AWS keeps no copy of a device's private key");
        assertEquals(created.getString("certificatePem"), stored.getCertificatePem());
        assertEquals(created.getString("keyPair.PublicKey"), stored.getPublicKey());
    }

    @Test
    void twoCertificatesNeverShareAnIdOrAKey() throws Exception {
        JsonPath first = create();
        JsonPath second = create();

        assertTrue(!first.getString("certificateId").equals(second.getString("certificateId")));
        assertTrue(!first.getString("keyPair.PrivateKey").equals(second.getString("keyPair.PrivateKey")));
        assertTrue(!parse(first.getString("certificatePem")).getSerialNumber()
                .equals(parse(second.getString("certificatePem")).getSerialNumber()));
    }

    @Test
    void createCertificateFromCsrSignsTheCsrKeyAndReturnsNoPrivateKey() throws Exception {
        KeyPair deviceKey = rsaKeyPair();
        String csrPem = csrPem("CN=thing-0001,O=Example", deviceKey);

        JsonPath body = given()
            .contentType("application/json")
            .queryParam("setAsActive", true)
            .body(Map.of("certificateSigningRequest", csrPem))
        .when()
            .post("/certificates")
        .then()
            .statusCode(200)
            .extract().jsonPath();

        X509Certificate cert = parse(body.getString("certificatePem"));
        X509Certificate ca = parse(given().when().get("/_floci/ca.pem").then().statusCode(200).extract().asString());

        cert.verify(ca.getPublicKey());
        assertEquals(deviceKey.getPublic(), cert.getPublicKey(), "the CSR's key is the certificate's key");
        assertEquals(new javax.security.auth.x500.X500Principal(new X500Name("CN=thing-0001,O=Example").getEncoded()),
                cert.getSubjectX500Principal(), "the CSR's subject is the certificate's subject");
        assertEquals(List.of("1.3.6.1.5.5.7.3.2"), cert.getExtendedKeyUsage(), "clientAuth");
        assertEquals(-1, cert.getBasicConstraints());
        assertEquals(expectedNotAfter(ca), cert.getNotAfter().toInstant());
        String expectedId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        assertEquals(expectedId, body.getString("certificateId"));
        assertNull(body.get("keyPair"), "AWS never returns a private key for a CSR: the device holds it");
    }

    @Test
    void reusingACsrYieldsADistinctCertificate() throws Exception {
        String csrPem = csrPem("CN=reused", rsaKeyPair());

        JsonPath first = fromCsr(csrPem);
        JsonPath second = fromCsr(csrPem);

        assertTrue(!first.getString("certificateId").equals(second.getString("certificateId")),
                "AWS: reusing the same CSR results in a distinct certificate");
        assertEquals(parse(first.getString("certificatePem")).getPublicKey(),
                parse(second.getString("certificatePem")).getPublicKey());
    }

    @Test
    void createCertificateFromCsrAcceptsAnEcKey() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        KeyPair deviceKey = kpg.generateKeyPair();
        PKCS10CertificationRequest csr = new JcaPKCS10CertificationRequestBuilder(
                new X500Name("CN=ec-device"), deviceKey.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withECDSA").build(deviceKey.getPrivate()));

        JsonPath body = fromCsr(toPem(csr));

        X509Certificate cert = parse(body.getString("certificatePem"));
        assertEquals(deviceKey.getPublic(), cert.getPublicKey());
        assertEquals("EC", cert.getPublicKey().getAlgorithm());
    }

    @Test
    void createCertificateFromCsrAcceptsP384AndRefusesSecp256k1() throws Exception {
        JsonPath accepted = fromCsr(ecCsrPem("secp384r1", "CN=p384"));
        assertEquals("EC", parse(accepted.getString("certificatePem")).getPublicKey().getAlgorithm());

        given()
            .contentType("application/json")
            .body(Map.of("certificateSigningRequest", ecCsrPem("secp256k1", "CN=k1")))
        .when()
            .post("/certificates")
        .then()
            .statusCode(400)
            .body("__type", containsString("InvalidRequestException"))
            .body("message", containsString("P-256"));
    }

    @Test
    void createCertificateFromCsrRejectsGarbage() {
        given()
            .contentType("application/json")
            .body(Map.of("certificateSigningRequest",
                    "-----BEGIN CERTIFICATE REQUEST-----\nbm90IGEgY3Ny\n-----END CERTIFICATE REQUEST-----"))
        .when()
            .post("/certificates")
        .then()
            .statusCode(400)
            .body("__type", containsString("InvalidRequestException"))
            .body("message", containsString("certificateSigningRequest"));
    }

    @Test
    void createCertificateFromCsrRejectsAWeakKey() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(1024);

        given()
            .contentType("application/json")
            .body(Map.of("certificateSigningRequest", csrPem("CN=weak", kpg.generateKeyPair())))
        .when()
            .post("/certificates")
        .then()
            .statusCode(400)
            .body("__type", containsString("InvalidRequestException"))
            .body("message", containsString("2048"));
    }

    @Test
    void createCertificateFromCsrRejectsACertificateInPlaceOfACsr() throws Exception {
        String certificatePem = create().getString("certificatePem");

        given()
            .contentType("application/json")
            .body(Map.of("certificateSigningRequest", certificatePem))
        .when()
            .post("/certificates")
        .then()
            .statusCode(400)
            .body("__type", containsString("InvalidRequestException"));
    }

    @Test
    void createCertificateFromCsrRejectsATamperedSignature() throws Exception {
        KeyPair deviceKey = rsaKeyPair();
        KeyPair otherKey = rsaKeyPair();
        // The request names deviceKey's public key but is signed with otherKey: proof of possession fails.
        PKCS10CertificationRequest csr = new JcaPKCS10CertificationRequestBuilder(
                new X500Name("CN=forged"), deviceKey.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(otherKey.getPrivate()));

        given()
            .contentType("application/json")
            .body(Map.of("certificateSigningRequest", toPem(csr)))
        .when()
            .post("/certificates")
        .then()
            .statusCode(400)
            .body("message", containsString("signature"));
    }

    @Test
    void describeCertificateReturnsTheSamePem() throws Exception {
        JsonPath created = given()
            .contentType("application/json")
            .queryParam("setAsActive", false)
            .body("{}")
        .when()
            .post("/keys-and-certificate")
        .then()
            .statusCode(200)
            .extract().jsonPath();

        String body = given()
        .when()
            .get("/certificates/" + created.getString("certificateId"))
        .then()
            .statusCode(200)
            .body("certificateDescription.certificatePem", equalTo(created.getString("certificatePem")))
            .body("certificateDescription.certificateId", equalTo(created.getString("certificateId")))
            .body("certificateDescription.status", equalTo("INACTIVE"))
            .body("certificateDescription.certificateMode", equalTo("DEFAULT"))
            .extract().asString();

        // Read with Jackson: RestAssured's JsonPath narrows large epoch doubles to float precision.
        com.fasterxml.jackson.databind.JsonNode described = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body)
                .path("certificateDescription");
        X509Certificate ca = parse(given().when().get("/_floci/ca.pem").then().statusCode(200).extract().asString());
        assertEquals(expectedNotAfter(ca).getEpochSecond(), described.path("validity").path("notAfter").asLong(),
                "validity.notAfter is 2049-12-31T23:59:59Z as epoch seconds (or the CA's expiry, if earlier)");
        long notBefore = described.path("validity").path("notBefore").asLong();
        long creation = described.path("creationDate").asLong();
        assertTrue(Math.abs(notBefore - creation) <= 2, "the certificate is valid from its creation");
    }

    private static JsonPath create() {
        return given()
            .contentType("application/json")
            .queryParam("setAsActive", true)
            .body("{}")
        .when()
            .post("/keys-and-certificate")
        .then()
            .statusCode(200)
            .extract().jsonPath();
    }

    private static JsonPath fromCsr(String csrPem) {
        return given()
            .contentType("application/json")
            .queryParam("setAsActive", false)
            .body(Map.of("certificateSigningRequest", csrPem))
        .when()
            .post("/certificates")
        .then()
            .statusCode(200)
            .extract().jsonPath();
    }

    /** Built with the BouncyCastle provider so curves the JDK dropped (secp256k1) can be requested. */
    private static String ecCsrPem(String curve, String subject) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "BC");
        kpg.initialize(new java.security.spec.ECGenParameterSpec(curve));
        KeyPair keyPair = kpg.generateKeyPair();
        PKCS10CertificationRequest csr = new JcaPKCS10CertificationRequestBuilder(
                new X500Name(subject), keyPair.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(keyPair.getPrivate()));
        return toPem(csr);
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    private static String csrPem(String subject, KeyPair keyPair) throws Exception {
        PKCS10CertificationRequest csr = new JcaPKCS10CertificationRequestBuilder(
                new X500Name(subject), keyPair.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate()));
        return toPem(csr);
    }

    private static String toPem(Object object) throws Exception {
        StringWriter out = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(out)) {
            writer.writeObject(object);
        }
        return out.toString();
    }

    /** AWS's fixed 2049 expiry, capped at the CA's own: a CA created before this change lives ten years. */
    private static java.time.Instant expectedNotAfter(X509Certificate ca) {
        java.time.Instant aws = java.time.Instant.parse("2049-12-31T23:59:59Z");
        java.time.Instant caNotAfter = ca.getNotAfter().toInstant();
        return caNotAfter.isBefore(aws) ? caNotAfter : aws;
    }

    private static X509Certificate parse(String pem) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
    }
}
