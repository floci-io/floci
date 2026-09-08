package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.restassured.path.json.JsonPath;

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
    private static final CertificateGenerator GENERATOR = new CertificateGenerator();

    final String certificateId;
    final String certificateArn;
    final X509Certificate certificate;
    final PrivateKey privateKey;

    private IotDeviceIdentity(String certificateId, String certificateArn, X509Certificate certificate, PrivateKey privateKey) {
        this.certificateId = certificateId;
        this.certificateArn = certificateArn;
        this.certificate = certificate;
        this.privateKey = privateKey;
    }

    /** Provisions a device over the REST API, as an onboarding flow does. */
    static IotDeviceIdentity provision(boolean active) {
        JsonPath created = given()
                .queryParam("setAsActive", active)
                .when().post("/keys-and-certificate")
                .then().statusCode(200)
                .extract().jsonPath();
        return new IotDeviceIdentity(
                created.getString("certificateId"),
                created.getString("certificateArn"),
                GENERATOR.parseCertificate(created.getString("certificatePem")),
                GENERATOR.parsePrivateKey(created.getString("keyPair.PrivateKey")));
    }

    /** A certificate the Floci CA signed but IoT Core never registered. */
    static IotDeviceIdentity stranger(Path tlsDir) {
        CertificateGenerator.GeneratedCertificate issued = FlociCertificateAuthority.loadOrCreate(tlsDir).issueClientCertificate("stranger");
        return new IotDeviceIdentity("unregistered", "arn:aws:iot:us-east-1:000000000000:cert/unregistered",
                GENERATOR.parseCertificate(issued.certificatePem()), GENERATOR.parsePrivateKey(issued.privateKeyPem()));
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
