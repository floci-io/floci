package io.github.hectorvent.floci.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTestProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TLS on in Floci's self-signed mode, for the classes that test what it serves. Quarkus builds the
 * application once per distinct profile class, so they share one rather than each declaring their
 * own.
 *
 * <p>Under {@code @QuarkusTest} the server serves the leaf this class writes before boot, issued by
 * the local CA in {@link #TLS_DIR}. It carries the SANs {@link TlsConfigSource} uses by default,
 * which cover the CloudFront and ECR hosts, except {@code 127.0.0.1}, plus the API Gateway custom
 * domain {@link TlsHttp2HostRoutingIntegrationTest} routes to. The default wildcards cover one label only,
 * so a name two labels below {@code localhost.floci.io} stays uncovered until a test adds it. Each
 * run writes a fresh leaf and metadata, which also drops hostnames an earlier run learned at
 * runtime, so a name a test expects to be absent at boot really is. The CA is kept.
 *
 * <p>{@link EcrTlsDataPlaneDockerIntegrationTest} pushes through the ECR registry container, which
 * by default outlives the application and is adopted by name on the next start. This profile names
 * it in this run's {@link SidecarContainersProfile#namespace() sidecar namespace} and stops it at
 * shutdown, so the test never pushes into a registry a developer's Floci or another run owns and
 * leaves none behind. The other classes start no containers, so both overrides are inert for them.
 *
 * <p>The paths are compile-time constants so a test can use them without initializing this class,
 * which would write a new leaf under the running application.
 */
public class SelfSignedTlsProfile implements QuarkusTestProfile {

    public static final String DATA_DIR = "target/floci-self-signed-tls-test";

    public static final String TLS_DIR = DATA_DIR + "/tls";

    static {
        Path tlsDir = Path.of(TLS_DIR);
        try {
            Files.createDirectories(tlsDir);
            for (String name : List.of("floci-server.crt", "floci-server.key", "floci-server.metadata.json")) {
                Files.deleteIfExists(tlsDir.resolve(name));
            }
            FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDir);
            List<String> sans = new ArrayList<>(TlsConfigSource.DEFAULT_SAN_HOSTNAMES);
            // CustomDomainTlsIntegrationTest and TlsEnsureHostIntegrationTest connect by IP and send
            // a name the leaf must not cover yet as SNI. The JDK falls back to the peer address when
            // the SNI name does not match, so a 127.0.0.1 SAN would let those handshakes through.
            sans.remove("127.0.0.1");
            sans.add(TlsHttp2HostRoutingIntegrationTest.API_DOMAIN);
            CertificateGenerator.GeneratedCertificate leaf =
                    ca.issueServerCertificate("localhost", sans, KeyAlgorithm.RSA_2048, null);
            Files.writeString(tlsDir.resolve("floci-server.crt"), leaf.certificatePem());
            Files.writeString(tlsDir.resolve("floci-server.key"), leaf.privateKeyPem());
            Files.writeString(tlsDir.resolve("floci-server.metadata.json"),
                    new ObjectMapper().writeValueAsString(CertificateMetadata.create(sans, "dev")));
            ContainerCaBundle.write(tlsDir, ca.certificatePath());
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Could not prepare the TLS fixtures in " + tlsDir, e);
        }
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        Path tlsDir = Path.of(TLS_DIR).toAbsolutePath();
        return Map.of(
                "floci.tls.enabled", "true",
                "floci.tls.self-signed", "true",
                "floci.tls.aws-https-port", "0",
                "floci.storage.persistent-path", Path.of(DATA_DIR).toAbsolutePath().toString(),
                "quarkus.tls.key-store.pem.0.cert", tlsDir.resolve("floci-server.crt").toString(),
                "quarkus.tls.key-store.pem.0.key", tlsDir.resolve("floci-server.key").toString(),
                "quarkus.http.insecure-requests", "enabled",
                "floci.docker.resource-namespace", SidecarContainersProfile.namespace(),
                "floci.services.ecr.keep-running-on-shutdown", "false");
    }
}
