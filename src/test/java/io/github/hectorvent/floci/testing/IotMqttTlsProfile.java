package io.github.hectorvent.floci.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.CertificateMetadata;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.quarkus.test.junit.QuarkusTestProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The IoT MQTT broker started at boot on a plaintext and a TLS listener, with TLS on in Floci's
 * self-signed mode, for the classes that test what devices reach: the TLS and mutual TLS
 * listeners, MQTT over WebSocket on the HTTP and HTTPS ports, custom authorizers and the plain
 * broker. Quarkus builds the application once per distinct profile class, so they share one
 * rather than each declaring their own.
 *
 * <p>Under {@code @QuarkusTest} the server serves the leaf this class writes before boot, issued by
 * the local CA in {@link #TLS_DIR}, as TlsConfigSource leaves it in production. TlsConfigSource
 * reads system properties, not profile overrides, so the profile lays the files down itself and
 * points the TLS registry's default entry at them. The leaf carries {@code 127.0.0.1}, so Paho's
 * hostname verification passes against {@code ssl://127.0.0.1} and {@code wss://127.0.0.1}. Its
 * wildcard covers one label below {@code localhost.floci.io}, so a name two labels below stays
 * uncovered until a test adds it. Each run writes a fresh leaf and metadata, which also drops
 * hostnames an earlier run learned at runtime. The CA is kept.
 *
 * <p>The overrides, and who relies on them:
 * <ul>
 *   <li>{@code floci.tls.*} and the two {@code quarkus.tls.key-store} entries serve that leaf on
 *   HTTPS and open the MQTT TLS listener, which Floci opens only while TLS is on. The TLS, mutual
 *   TLS, {@code wss://} and custom authorizer tests connect through them.
 *   {@code aws-https-port=0} keeps the TLS proxy from also binding 443, which an unprivileged test
 *   JVM cannot do and no class here uses.</li>
 *   <li>{@code floci.storage.persistent-path} is where the server keeps its TLS directory, so the CA
 *   it signs with is the one the tests trust from {@link #TLS_DIR}.</li>
 *   <li>{@code quarkus.http.insecure-requests} keeps plain HTTP served next to HTTPS, for RestAssured
 *   and the {@code ws://} tests.</li>
 *   <li>The {@code floci.services.iot.mqtt} entries start the broker at boot on fixed loopback ports,
 *   which every class dials. They differ from the ports of the other MQTT test profiles.</li>
 * </ul>
 *
 * <p>The TLS listener admits only registered device certificates whose policy allows the connect,
 * and the plaintext one stays permissive; the WebSocket bridge hands its sessions to the plaintext
 * listener. A class that only uses the plaintext listener and plain HTTP, such as
 * {@code IotMqttEnabledIntegrationTest}, never reaches the TLS listener or the HTTPS port, so TLS
 * is inert for it.
 *
 * <p>The paths and ports are compile-time constants so a test can use them without initializing
 * this class, which would write a new leaf under the running application.
 */
public class IotMqttTlsProfile implements QuarkusTestProfile {

    public static final String DATA_DIR = "target/floci-iot-mqtt-tls-test";

    public static final String TLS_DIR = DATA_DIR + "/tls";

    public static final int PLAIN_PORT = 18834;

    public static final int TLS_PORT = 18835;

    static {
        Path tlsDir = Path.of(TLS_DIR);
        try {
            Files.createDirectories(tlsDir);
            for (String name : List.of("floci-server.crt", "floci-server.key", "floci-server.metadata.json")) {
                Files.deleteIfExists(tlsDir.resolve(name));
            }
            FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDir);
            List<String> sans = List.of("localhost", "127.0.0.1", "*.localhost.floci.io");
            CertificateGenerator.GeneratedCertificate leaf =
                    ca.issueServerCertificate("localhost", sans, KeyAlgorithm.RSA_2048, null);
            Files.writeString(tlsDir.resolve("floci-server.crt"), leaf.certificatePem());
            Files.writeString(tlsDir.resolve("floci-server.key"), leaf.privateKeyPem());
            Files.writeString(tlsDir.resolve("floci-server.metadata.json"),
                    new ObjectMapper().writeValueAsString(CertificateMetadata.create(sans, "dev")));
        } catch (IOException e) {
            throw new IllegalStateException("Could not prepare the TLS fixtures in " + tlsDir, e);
        }
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        Path tlsDir = Path.of(TLS_DIR).toAbsolutePath();
        return Map.ofEntries(
                Map.entry("floci.tls.enabled", "true"),
                Map.entry("floci.tls.self-signed", "true"),
                Map.entry("floci.tls.aws-https-port", "0"),
                Map.entry("floci.storage.persistent-path", Path.of(DATA_DIR).toAbsolutePath().toString()),
                Map.entry("quarkus.tls.key-store.pem.0.cert", tlsDir.resolve("floci-server.crt").toString()),
                Map.entry("quarkus.tls.key-store.pem.0.key", tlsDir.resolve("floci-server.key").toString()),
                Map.entry("quarkus.http.insecure-requests", "enabled"),
                Map.entry("floci.services.iot.mqtt.enabled", "true"),
                Map.entry("floci.services.iot.mqtt.auto-start", "true"),
                Map.entry("floci.services.iot.mqtt.host", "127.0.0.1"),
                Map.entry("floci.services.iot.mqtt.port", Integer.toString(PLAIN_PORT)),
                Map.entry("floci.services.iot.mqtt.tls-port", Integer.toString(TLS_PORT)));
    }
}
