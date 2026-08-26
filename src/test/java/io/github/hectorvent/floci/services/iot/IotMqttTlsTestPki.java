package io.github.hectorvent.floci.services.iot;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Generates a throwaway PKI for the MQTT TLS integration tests: a trusted CA with a
 * server certificate and a device (client) certificate signed by it, plus a second,
 * untrusted CA with its own device certificate. Broker-side material is written as
 * PEM files for {@code floci.services.iot.mqtt.tls.*}; client-side material is
 * exposed as in-memory {@link SSLSocketFactory} instances for Paho.
 *
 * <p>{@code @QuarkusTest} loads this class once for the test profile and again in the
 * runtime classloader, so {@code INSTANCE} exists twice per JVM. The first instance
 * generates the PKI and records its directory in a system property; later instances
 * reload the identical material from disk, keeping broker config and test clients on
 * the same certificate chain.</p>
 */
final class IotMqttTlsTestPki {

    static final IotMqttTlsTestPki INSTANCE = generate();

    private final Path caPem;
    private final Path serverCertPem;
    private final Path serverKeyPem;
    private final X509Certificate caCert;
    private final KeyPair clientKeyPair;
    private final X509Certificate clientCert;
    private final KeyPair untrustedClientKeyPair;
    private final X509Certificate untrustedClientCert;

    private IotMqttTlsTestPki(Path caPem, Path serverCertPem, Path serverKeyPem,
                              X509Certificate caCert,
                              KeyPair clientKeyPair, X509Certificate clientCert,
                              KeyPair untrustedClientKeyPair, X509Certificate untrustedClientCert) {
        this.caPem = caPem;
        this.serverCertPem = serverCertPem;
        this.serverKeyPem = serverKeyPem;
        this.caCert = caCert;
        this.clientKeyPair = clientKeyPair;
        this.clientCert = clientCert;
        this.untrustedClientKeyPair = untrustedClientKeyPair;
        this.untrustedClientCert = untrustedClientCert;
    }

    String caPath() {
        return caPem.toAbsolutePath().toString();
    }

    String serverCertPath() {
        return serverCertPem.toAbsolutePath().toString();
    }

    String serverKeyPath() {
        return serverKeyPem.toAbsolutePath().toString();
    }

    SSLSocketFactory trustedClientSocketFactory() {
        return socketFactory(clientKeyPair.getPrivate(), clientCert);
    }

    SSLSocketFactory withoutClientCertSocketFactory() {
        return socketFactory(null, null);
    }

    SSLSocketFactory untrustedClientSocketFactory() {
        return socketFactory(untrustedClientKeyPair.getPrivate(), untrustedClientCert);
    }

    private static final String DIR_PROPERTY = "floci-tests.iot-mqtt-tls-dir";

    private static IotMqttTlsTestPki generate() {
        String existingDir = System.getProperty(DIR_PROPERTY);
        if (existingDir != null) {
            return load(Path.of(existingDir));
        }
        try {
            KeyPair caKeyPair = newKeyPair();
            X509Certificate caCert = newCaCertificate("CN=Floci IoT Test CA", caKeyPair);

            KeyPair serverKeyPair = newKeyPair();
            X509Certificate serverCert = newLeafCertificate(
                    "CN=localhost", serverKeyPair, caCert, caKeyPair, KeyPurposeId.id_kp_serverAuth);

            KeyPair clientKeyPair = newKeyPair();
            X509Certificate clientCert = newLeafCertificate(
                    "CN=floci-test-device", clientKeyPair, caCert, caKeyPair, KeyPurposeId.id_kp_clientAuth);

            KeyPair untrustedCaKeyPair = newKeyPair();
            X509Certificate untrustedCaCert = newCaCertificate("CN=Floci IoT Untrusted CA", untrustedCaKeyPair);
            KeyPair untrustedClientKeyPair = newKeyPair();
            X509Certificate untrustedClientCert = newLeafCertificate(
                    "CN=floci-untrusted-device", untrustedClientKeyPair,
                    untrustedCaCert, untrustedCaKeyPair, KeyPurposeId.id_kp_clientAuth);

            Path dir = Files.createTempDirectory("floci-iot-mqtt-tls");
            dir.toFile().deleteOnExit();
            Path caPem = writePem(dir.resolve("ca.pem"), caCert);
            Path serverCertPem = writePem(dir.resolve("server-cert.pem"), serverCert);
            Path serverKeyPem = writePem(dir.resolve("server-key.pem"), serverKeyPair.getPrivate());
            writePem(dir.resolve("client-cert.pem"), clientCert);
            writePem(dir.resolve("client-key.pem"), clientKeyPair.getPrivate());
            writePem(dir.resolve("untrusted-client-cert.pem"), untrustedClientCert);
            writePem(dir.resolve("untrusted-client-key.pem"), untrustedClientKeyPair.getPrivate());
            System.setProperty(DIR_PROPERTY, dir.toAbsolutePath().toString());

            return new IotMqttTlsTestPki(caPem, serverCertPem, serverKeyPem, caCert,
                    clientKeyPair, clientCert, untrustedClientKeyPair, untrustedClientCert);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate MQTT TLS test PKI", e);
        }
    }

    private static IotMqttTlsTestPki load(Path dir) {
        try {
            X509Certificate caCert = readCertificate(dir.resolve("ca.pem"));
            X509Certificate clientCert = readCertificate(dir.resolve("client-cert.pem"));
            KeyPair clientKeyPair = readKeyPair(dir.resolve("client-key.pem"), clientCert);
            X509Certificate untrustedClientCert = readCertificate(dir.resolve("untrusted-client-cert.pem"));
            KeyPair untrustedClientKeyPair = readKeyPair(dir.resolve("untrusted-client-key.pem"), untrustedClientCert);
            return new IotMqttTlsTestPki(dir.resolve("ca.pem"), dir.resolve("server-cert.pem"),
                    dir.resolve("server-key.pem"), caCert,
                    clientKeyPair, clientCert, untrustedClientKeyPair, untrustedClientCert);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load MQTT TLS test PKI from " + dir, e);
        }
    }

    private static X509Certificate readCertificate(Path file) throws Exception {
        try (var in = Files.newInputStream(file)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static KeyPair readKeyPair(Path file, X509Certificate certificate) throws Exception {
        try (Reader reader = Files.newBufferedReader(file); PEMParser parser = new PEMParser(reader)) {
            Object parsed = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            PrivateKey privateKey = parsed instanceof PEMKeyPair pemKeyPair
                    ? converter.getKeyPair(pemKeyPair).getPrivate()
                    : converter.getPrivateKey((org.bouncycastle.asn1.pkcs.PrivateKeyInfo) parsed);
            return new KeyPair(certificate.getPublicKey(), privateKey);
        }
    }

    private static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048, new SecureRandom());
        return keyGen.generateKeyPair();
    }

    private static X509Certificate newCaCertificate(String subjectDn, KeyPair keyPair) throws Exception {
        X500Name subject = new X500Name(subjectDn);
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, newSerial(), yesterday(), inOneYear(), subject, keyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        return sign(builder, keyPair.getPrivate());
    }

    private static X509Certificate newLeafCertificate(String subjectDn, KeyPair keyPair,
                                                      X509Certificate issuerCert, KeyPair issuerKeyPair,
                                                      KeyPurposeId extendedKeyUsage) throws Exception {
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuerCert, newSerial(), yesterday(), inOneYear(),
                new X500Name(subjectDn), keyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(extendedKeyUsage));
        if (KeyPurposeId.id_kp_serverAuth.equals(extendedKeyUsage)) {
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[]{
                    new GeneralName(GeneralName.dNSName, "localhost"),
                    new GeneralName(GeneralName.iPAddress, "127.0.0.1")
            }));
        }
        return sign(builder, issuerKeyPair.getPrivate());
    }

    private static X509Certificate sign(X509v3CertificateBuilder builder, PrivateKey issuerKey) throws Exception {
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(issuerKey);
        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    private static BigInteger newSerial() {
        return new BigInteger(128, new SecureRandom());
    }

    private static Date yesterday() {
        return Date.from(Instant.now().minus(1, ChronoUnit.DAYS));
    }

    private static Date inOneYear() {
        return Date.from(Instant.now().plus(365, ChronoUnit.DAYS));
    }

    private static Path writePem(Path file, Object object) throws IOException {
        StringWriter writer = new StringWriter();
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(writer)) {
            pemWriter.writeObject(object);
        }
        Files.writeString(file, writer.toString());
        file.toFile().deleteOnExit();
        return file;
    }

    private SSLSocketFactory socketFactory(PrivateKey clientKey, X509Certificate clientCertificate) {
        try {
            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("ca", caCert);
            trustManagerFactory.init(trustStore);

            KeyManagerFactory keyManagerFactory = null;
            if (clientKey != null) {
                keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                keyStore.load(null, null);
                keyStore.setKeyEntry("device", clientKey, new char[0], new Certificate[]{clientCertificate});
                keyManagerFactory.init(keyStore, new char[0]);
            }

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(
                    keyManagerFactory == null ? null : keyManagerFactory.getKeyManagers(),
                    trustManagerFactory.getTrustManagers(),
                    new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build test SSL socket factory", e);
        }
    }
}
