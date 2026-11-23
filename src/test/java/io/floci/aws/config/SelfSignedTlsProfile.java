package io.floci.aws.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.acm.CertificateGenerator;
import io.floci.aws.services.acm.model.KeyAlgorithm;
import io.floci.aws.testing.SidecarContainersProfile;
import io.floci.aws.testutil.FreePorts;
import io.quarkus.test.junit.QuarkusTestProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TLS on in Floci's self-signed mode, for the classes that test what it serves. Quarkus builds the
 * application once per distinct profile class, and keeps every one it built for the rest of the
 * test JVM, so they share this one rather than each declaring their own.
 *
 * <p>Under {@code @QuarkusTest} the server serves the leaf this class writes before boot, issued by
 * the local CA in {@link #TLS_DIR}. It carries the SANs {@link TlsConfigSource} uses by default,
 * which cover the CloudFront and ECR hosts and {@code host.docker.internal}, except
 * {@code 127.0.0.1}, plus the API Gateway custom domain {@link TlsHttp2HostRoutingIntegrationTest}
 * routes to. The default wildcards cover one label only, so a name two labels below
 * {@code localhost.floci.io} stays uncovered until a test adds it. Each run writes a fresh leaf and
 * metadata, which also drops hostnames an earlier run learned at runtime, so a name a test expects
 * to be absent at boot really is. The CA is kept. Next to it go the CA bundle and the Java trust
 * store Floci mounts into the containers it launches.
 *
 * <p>The overrides, and who relies on them:
 * <ul>
 *   <li>{@code floci.tls.*}, the two {@code quarkus.tls.key-store} entries and
 *   {@code floci.storage.persistent-path} serve that leaf on HTTPS, with the CA it was issued by
 *   behind {@code GET /_floci/ca.pem}, and keep plain HTTP served beside it. Every class here
 *   relies on them.</li>
 *   <li>{@code floci.tls.aws-https-port} set to a free port chosen when the class loads stands in
 *   for 443, which an unprivileged test JVM cannot bind, and {@code floci.services.elbv2.mock=false}
 *   runs the real ELBv2 data plane behind it. {@code ElbV2AwsHttpsPortIntegrationTest} relies on
 *   them, and awaits the proxy's actual bind result before connecting. It uses
 *   {@code floci.services.iot.endpoint-address} to check shared-port SNI precedence. The other
 *   classes only see the proxy listening on one more port, and create no load balancer.</li>
 *   <li>{@code floci.docker.resource-namespace} and
 *   {@code floci.services.ecr.keep-running-on-shutdown=false} name the ECR registry container in
 *   this run's {@link SidecarContainersProfile#namespace() sidecar namespace} and stop it at
 *   shutdown. By default it outlives the application and is adopted by name on the next start.
 *   {@link EcrTlsDataPlaneDockerIntegrationTest} pushes through it, and relies on them so it never
 *   pushes into a registry a developer's Floci or another run owns, and leaves none behind.</li>
 *   <li>{@code floci.services.ecr.tls-uri=true} advertises a repository and the login endpoint on
 *   {@code localhost.floci.io} over HTTPS. {@link EcrTlsUriIntegrationTest} relies on it.</li>
 * </ul>
 *
 * <p>Each is inert where no class relies on it. Only the ECR data plane class starts the registry
 * container, and only {@code ContainerCaBundleDockerIntegrationTest} starts any other container.
 * The repository URI setting is read by ECR's control plane and by EKS, and only
 * {@link EcrTlsUriIntegrationTest} calls either: the data plane class talks to the registry
 * endpoint directly.
 *
 * <p>A class joins if every setting here is either one it relies on or one that never reaches its
 * requests, and if any setting it adds never reaches the requests of a class already here. A class
 * that needs {@code 127.0.0.1} in the leaf takes {@code IotMqttTlsProfile}.
 *
 * <p>The paths are compile-time constants so a test can use them without initializing this class,
 * which would write a new leaf under the running application.
 */
public class SelfSignedTlsProfile implements QuarkusTestProfile {

    public static final String DATA_DIR = "target/floci-self-signed-tls-test";

    public static final String TLS_DIR = DATA_DIR + "/tls";

    public static final String IOT_ENDPOINT_HOST = "iot.example.test";

    private static final int AWS_HTTPS_PORT = freePort();

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
            ContainerCaBundle.writeJavaTrustStore(ContainerCaBundle.write(tlsDir, ca.certificatePath()));
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Could not prepare the TLS fixtures in " + tlsDir, e);
        }
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        Path tlsDir = Path.of(TLS_DIR).toAbsolutePath();
        return Map.ofEntries(
                Map.entry("floci.tls.enabled", "true"),
                Map.entry("floci.tls.self-signed", "true"),
                Map.entry("floci.tls.aws-https-port", String.valueOf(AWS_HTTPS_PORT)),
                Map.entry("floci.services.elbv2.mock", "false"),
                Map.entry("floci.services.iot.endpoint-address", IOT_ENDPOINT_HOST + ":8443"),
                Map.entry("floci.storage.persistent-path", Path.of(DATA_DIR).toAbsolutePath().toString()),
                Map.entry("quarkus.tls.key-store.pem.0.cert", tlsDir.resolve("floci-server.crt").toString()),
                Map.entry("quarkus.tls.key-store.pem.0.key", tlsDir.resolve("floci-server.key").toString()),
                Map.entry("quarkus.http.insecure-requests", "enabled"),
                Map.entry("floci.docker.resource-namespace", SidecarContainersProfile.namespace()),
                Map.entry("floci.services.ecr.keep-running-on-shutdown", "false"),
                Map.entry("floci.services.ecr.tls-uri", "true"));
    }

    private static int freePort() {
        try {
            return FreePorts.anyInterfacePort();
        } catch (IOException e) {
            throw new UncheckedIOException("No free port for the AWS-HTTPS proxy port", e);
        }
    }
}
