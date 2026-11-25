package io.floci.aws.services.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.FlociCertificateAuthority;
import io.floci.aws.core.common.Pem;
import io.floci.aws.services.acm.CertificateGenerator;
import io.floci.aws.testing.PartitionCleanup;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;

/** A device provisioned through CreateKeysAndCertificate, ready to present itself on the TLS listener. */
final class IotDeviceIdentity {

    private static final char[] PASSWORD = "device".toCharArray();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_ACCESS_KEY = "test";

    final String certificateId;
    final String certificateArn;
    final X509Certificate certificate;
    final PrivateKey privateKey;
    /** The region every request about this device is signed for; null sends them unsigned, to the default region. */
    private final String region;
    /** The access key every signed request about this device names; a 12-digit key signs as that account. */
    private final String accessKeyId;

    private IotDeviceIdentity(String certificateId, String certificateArn, X509Certificate certificate, PrivateKey privateKey,
                              String region, String accessKeyId) {
        this.certificateId = certificateId;
        this.certificateArn = certificateArn;
        this.certificate = certificate;
        this.privateKey = privateKey;
        this.region = region;
        this.accessKeyId = accessKeyId;
    }

    /** Provisions a device over the REST API, as an onboarding flow does. */
    static IotDeviceIdentity provision(boolean active) {
        return provisionIn(null, DEFAULT_ACCESS_KEY, active);
    }

    /**
     * Provisions a device in {@code region}, every request about it signed for that region, and has
     * {@code cleanup} deactivate and delete its certificate after the test.
     */
    static IotDeviceIdentity provision(String region, boolean active, PartitionCleanup cleanup) {
        IotDeviceIdentity device = provisionIn(region, DEFAULT_ACCESS_KEY, active);
        cleanup.register(device::delete);
        return device;
    }

    /**
     * Provisions a device in account {@code accountId} and {@code region}, every request about it
     * signed as that account for that region, and has {@code cleanup} deactivate and delete its
     * certificate after the test.
     */
    static IotDeviceIdentity provisionInAccount(String accountId, String region, boolean active, PartitionCleanup cleanup) {
        IotDeviceIdentity device = provisionIn(region, accountId, active);
        cleanup.register(device::delete);
        return device;
    }

    private static IotDeviceIdentity provisionIn(String region, String accessKeyId, boolean active) {
        JsonPath created = request(region, accessKeyId)
                .queryParam("setAsActive", active)
                .when().post("/keys-and-certificate")
                .then().statusCode(200)
                .extract().jsonPath();
        return new IotDeviceIdentity(
                created.getString("certificateId"),
                created.getString("certificateArn"),
                Pem.parseCertificate(created.getString("certificatePem")),
                Pem.parsePrivateKey(created.getString("keyPair.PrivateKey")),
                region, accessKeyId);
    }

    private static RequestSpecification request(String region, String accessKeyId) {
        return region == null ? given() : given().header("Authorization", auth(accessKeyId, region, "iot"));
    }

    private RequestSpecification request() {
        return request(region, accessKeyId);
    }

    /**
     * Provisions a device as {@link #provision(boolean)} does, and has {@code cleanup} deactivate
     * and delete its certificate after the test, once whatever was attached to it is detached.
     */
    static IotDeviceIdentity provision(boolean active, PartitionCleanup cleanup) {
        IotDeviceIdentity device = provision(active);
        cleanup.register(device::delete);
        return device;
    }

    /**
     * Creates a policy of {@code document} under a fresh name and attaches it to this device's
     * certificate; {@code cleanup} detaches and deletes it after the test. Returns the policy name.
     */
    String attachNewPolicy(String document, PartitionCleanup cleanup) {
        String policy = "device-policy-" + System.nanoTime();
        request()
                .contentType("application/json")
                .body(OBJECT_MAPPER.createObjectNode().put("policyDocument", document).toString())
                .when().post("/policies/" + policy)
                .then().statusCode(200);
        cleanup.register(() -> {
            request().queryParam("target", certificateArn).when().post("/target-policies/" + policy).then().statusCode(200);
            request().when().delete("/policies/" + policy).then().statusCode(200);
        });
        request().queryParam("target", certificateArn).when().put("/target-policies/" + policy).then().statusCode(200);
        return policy;
    }

    /**
     * Creates thing {@code thingName} and attaches this device's certificate to it as a principal;
     * {@code cleanup} detaches and deletes it after the test.
     */
    void attachNewThing(String thingName, PartitionCleanup cleanup) {
        request().contentType("application/json").body("{}").when().post("/things/" + thingName).then().statusCode(200);
        cleanup.register(() -> request().when().delete("/things/" + thingName).then().statusCode(200));
        request().header("x-amzn-principal", certificateArn).when().put("/things/" + thingName + "/principals").then().statusCode(200);
        cleanup.register(() -> request().header("x-amzn-principal", certificateArn)
                .when().delete("/things/" + thingName + "/principals").then().statusCode(200));
    }

    /** IoT Core deletes only an inactive certificate with nothing attached. */
    private void delete() {
        request().queryParam("newStatus", "INACTIVE").when().put("/certificates/" + certificateId).then().statusCode(200);
        request().when().delete("/certificates/" + certificateId).then().statusCode(200);
    }

    /** A certificate the Floci CA signed but IoT Core never registered. */
    static IotDeviceIdentity stranger(Path tlsDir) {
        CertificateGenerator.GeneratedCertificate issued = FlociCertificateAuthority.loadOrCreate(tlsDir).issueClientCertificate("stranger");
        return new IotDeviceIdentity("unregistered", "arn:aws:iot:us-east-1:000000000000:cert/unregistered",
                Pem.parseCertificate(issued.certificatePem()), Pem.parsePrivateKey(issued.privateKeyPem()), null,
                DEFAULT_ACCESS_KEY);
    }

    /** Presents the device certificate and trusts only the Floci CA. */
    SSLContext sslContext(Path tlsDir) throws Exception {
        KeyStore identity = KeyStore.getInstance("PKCS12");
        identity.load(null, null);
        identity.setKeyEntry("device", privateKey, PASSWORD, new Certificate[] {certificate});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(identity, PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), trustManagers(tlsDir), null);
        return context;
    }

    /** Trusts only the Floci CA and presents nothing: an anonymous client. */
    static SSLContext trustOnlySslContext(Path tlsDir) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers(tlsDir), null);
        return context;
    }

    private static TrustManager[] trustManagers(Path tlsDir) throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("floci", FlociCertificateAuthority.loadOrCreate(tlsDir).certificate());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        return tmf.getTrustManagers();
    }
}
