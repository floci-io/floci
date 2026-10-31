package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.testing.PartitionCleanup;
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

import static io.restassured.RestAssured.given;

/** A device provisioned through CreateKeysAndCertificate, ready to present itself on the TLS listener. */
final class IotDeviceIdentity {

    private static final char[] PASSWORD = "device".toCharArray();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    final String certificateId;
    final String certificateArn;
    final X509Certificate certificate;
    final PrivateKey privateKey;
    /** The Authorization header of the device's account, or null for the default account. */
    private final String authorization;

    private IotDeviceIdentity(String certificateId, String certificateArn, X509Certificate certificate, PrivateKey privateKey,
                              String authorization) {
        this.certificateId = certificateId;
        this.certificateArn = certificateArn;
        this.certificate = certificate;
        this.privateKey = privateKey;
        this.authorization = authorization;
    }

    /** Provisions a device over the REST API, as an onboarding flow does. */
    static IotDeviceIdentity provision(boolean active) {
        return provision(active, (String) null);
    }

    /** Provisions a device in the account {@code authorization} signs for, or in the default account when it is null. */
    static IotDeviceIdentity provision(boolean active, String authorization) {
        JsonPath created = request(authorization)
                .queryParam("setAsActive", active)
                .when().post("/keys-and-certificate")
                .then().statusCode(200)
                .extract().jsonPath();
        return new IotDeviceIdentity(
                created.getString("certificateId"),
                created.getString("certificateArn"),
                Pem.parseCertificate(created.getString("certificatePem")),
                Pem.parsePrivateKey(created.getString("keyPair.PrivateKey")),
                authorization);
    }

    /**
     * Provisions a device as {@link #provision(boolean)} does, and has {@code cleanup} deactivate
     * and delete its certificate after the test, once whatever was attached to it is detached.
     */
    static IotDeviceIdentity provision(boolean active, PartitionCleanup cleanup) {
        return provision(active, null, cleanup);
    }

    /** {@link #provision(boolean, PartitionCleanup)} in the account {@code authorization} signs for. */
    static IotDeviceIdentity provision(boolean active, String authorization, PartitionCleanup cleanup) {
        IotDeviceIdentity device = provision(active, authorization);
        cleanup.register(device::delete);
        return device;
    }

    /**
     * Creates a policy of {@code document} under a fresh name, in the device's account, and attaches
     * it to this device's certificate; {@code cleanup} detaches and deletes it after the test.
     * Returns the policy name.
     */
    String attachNewPolicy(String document, PartitionCleanup cleanup) {
        String policy = "device-policy-" + System.nanoTime();
        request(authorization)
                .contentType("application/json")
                .body(OBJECT_MAPPER.createObjectNode().put("policyDocument", document).toString())
                .when().post("/policies/" + policy)
                .then().statusCode(200);
        cleanup.register(() -> {
            request(authorization).queryParam("target", certificateArn).when().post("/target-policies/" + policy).then().statusCode(200);
            request(authorization).when().delete("/policies/" + policy).then().statusCode(200);
        });
        request(authorization).queryParam("target", certificateArn).when().put("/target-policies/" + policy).then().statusCode(200);
        return policy;
    }

    /** IoT Core deletes only an inactive certificate with nothing attached. */
    private void delete() {
        request(authorization).queryParam("newStatus", "INACTIVE").when().put("/certificates/" + certificateId).then().statusCode(200);
        request(authorization).when().delete("/certificates/" + certificateId).then().statusCode(200);
    }

    private static RequestSpecification request(String authorization) {
        return authorization == null ? given() : given().header("Authorization", authorization);
    }

    /** A certificate the Floci CA signed but IoT Core never registered. */
    static IotDeviceIdentity stranger(Path tlsDir) {
        CertificateGenerator.GeneratedCertificate issued = FlociCertificateAuthority.loadOrCreate(tlsDir).issueClientCertificate("stranger");
        return new IotDeviceIdentity("unregistered", "arn:aws:iot:us-east-1:000000000000:cert/unregistered",
                Pem.parseCertificate(issued.certificatePem()), Pem.parsePrivateKey(issued.privateKeyPem()), null);
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
