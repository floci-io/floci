package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Builds (and caches on disk) a self-signed PKCS#12 keystore for the MQTT broker's TLS/WSS
 * listeners. Reuses the repository's {@link CertificateGenerator} for the certificate and key.
 *
 * <p>The certificate is self-signed for {@code localhost} / {@code 127.0.0.1}; clients must trust
 * it (e.g. {@code rejectUnauthorized: false} in mqtt.js, or a permissive trust store in the AWS
 * IoT Device SDK) since there is no public CA chain locally.
 */
final class IotBrokerKeystore {

    static final String ALIAS = "floci-iot";

    private IotBrokerKeystore() {
    }

    /**
     * Returns the keystore path, generating a self-signed keystore at {@code path} if absent.
     */
    static Path ensure(Path path, String password) throws Exception {
        if (Files.exists(path)) {
            return path;
        }
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        CertificateGenerator generator = new CertificateGenerator();
        CertificateGenerator.GeneratedCertificate generated = generator.generateCertificate(
                "localhost", List.of("localhost", "127.0.0.1"), KeyAlgorithm.RSA_2048);

        X509Certificate certificate = generator.parseCertificate(generated.certificatePem());
        PrivateKey privateKey = generator.parsePrivateKey(generated.privateKeyPem());

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry(ALIAS, privateKey, password.toCharArray(),
                new Certificate[]{certificate});

        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(path)) {
            keyStore.store(out, password.toCharArray());
        }
        return path;
    }
}
