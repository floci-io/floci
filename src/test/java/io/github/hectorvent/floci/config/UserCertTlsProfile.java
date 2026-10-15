package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.quarkus.test.junit.QuarkusTestProfile;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.util.List;
import java.util.Map;

/**
 * TLS on with a user-provided certificate rather than Floci's self-signed one: a self-signed
 * {@code localhost} certificate generated once per run, with plain HTTP still enabled beside
 * HTTPS. The certificate file carries the private key after the certificate, as some users'
 * combined PEMs do, so a test can check the key is never served back.
 *
 * <p>The paths are compile-time constants so a test can use them without initializing this class,
 * which would generate a new certificate under the running application.
 */
public class UserCertTlsProfile implements QuarkusTestProfile {

    private static final String DIR = "target/floci-user-cert-tls-test";

    /** The certificate, followed by its private key. */
    public static final String CERT_FILE = DIR + "/user-test.crt";

    public static final String DATA_DIR = DIR + "/data";

    private static final String KEY_FILE = DIR + "/user-test.key";

    static {
        try {
            Files.createDirectories(Path.of(DIR));
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            CertificateGenerator.GeneratedCertificate cert = new CertificateGenerator().generateSelfSignedCertificate(
                    "localhost", List.of("localhost", "127.0.0.1"), KeyAlgorithm.RSA_2048);
            Files.writeString(Path.of(CERT_FILE), cert.certificatePem() + cert.privateKeyPem());
            Files.writeString(Path.of(KEY_FILE), cert.privateKeyPem());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not generate the user certificate in " + DIR, e);
        }
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        String certFile = Path.of(CERT_FILE).toAbsolutePath().toString();
        String keyFile = Path.of(KEY_FILE).toAbsolutePath().toString();
        return Map.of(
                "floci.tls.enabled", "true",
                "floci.tls.self-signed", "false",
                "floci.tls.cert-path", certFile,
                "floci.tls.key-path", keyFile,
                "floci.storage.persistent-path", Path.of(DATA_DIR).toAbsolutePath().toString(),
                "quarkus.http.ssl.certificate.files", certFile,
                "quarkus.http.ssl.certificate.key-files", keyFile,
                "quarkus.http.insecure-requests", "enabled");
    }
}
