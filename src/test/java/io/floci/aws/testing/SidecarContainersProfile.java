package io.floci.aws.testing;

import io.floci.aws.services.lambda.launcher.ImageCacheService;
import io.floci.aws.testutil.FreePorts;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTestProfile;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared by every integration test that runs a real sidecar container, started by its manager
 * exactly as in production: the AppSync GraphQL sidecar, the CodeArtifact Reposilite, Verdaccio,
 * BaGetter and pypiserver sidecars, the Verified Permissions Cedar sidecar, the floci-duck sidecar
 * behind the CUR Parquet emitter and Redshift Spectrum, ECS task containers and ElastiCache
 * serverless caches. Quarkus builds the application once per distinct profile class, so these
 * classes share one application rather than each paying for their own.
 *
 * <p>Namespaces the containers as {@code floci-aws-sidecar-test-<hostkey>-<pid>-<sidecar>}. Each
 * manager removes any container of its name before starting one, and the test config uses the same
 * empty namespace as a developer's running Floci, whose live sidecars would otherwise be killed.
 * Every sidecar type keeps its own name inside the namespace, so they never collide with each
 * other. The test JVM's process id keeps two runs on one machine apart, since two live processes
 * never share an id; the host key, a short hash of the host name and the process id namespace,
 * does the same for runs on other machines or in containers that share the Docker daemon, whose
 * process ids mean nothing here. A normal shutdown stops the sidecars; the containers and volumes
 * of a run that was killed are removed by the next run on the same host key, in
 * {@link #removeAbandonedSidecars()}.
 *
 * <p>Also sets the HTTP port, {@code floci.port} and {@code floci.base-url} together. The AppSync
 * resolver callback URL a launched container uses is built from {@code quarkus.http.port}, which in
 * production is the port Floci listens on. Under {@code @QuarkusTest} the application listens on
 * the test port instead, so without this the
 * sidecar would call back to 4566 and every resolver-backed field would fail with a connection
 * error, and floci-duck would reach for Floci's S3 on the wrong port. The port is a free one
 * chosen once when the class loads, rather than a fixed number, so two runs on the same machine do
 * not collide on it. It is released before Quarkus binds it, so another process can still take it
 * in between; the application then fails to start rather than sharing anything with that process.
 *
 * <p>Names both billing emitters as off, so a CUR or BCM report is only written when a test drives
 * the emitter itself. {@code CurEmissionScheduler} acts only on {@code daily} and
 * {@code synchronous}, so every other value leaves the emitters idle; the configured test default
 * already resolves to one of those inert values, and spelling it out here keeps the assumption
 * visible for the CUR classes that rely on it.
 *
 * <p>It also carries the settings of other classes that share this application, and who relies on
 * them:
 * <ul>
 *   <li>{@code floci.services.bedrock-runtime.backend=proxy}, with {@code proxy.url} on port
 *   {@value #BEDROCK_PROXY_PORT}, sends Converse to a stand-in OpenAI-compatible server each Bedrock
 *   proxy class starts and stops itself. {@code proxy.api-key=test-key} and {@code proxy.model-mapping},
 *   which maps {@value #BEDROCK_PROXY_MAPPED_MODEL_ID} to {@code claude-3-haiku}, are what
 *   {@code BedrockProxyIntegrationTest} checks. {@code proxy.passthrough=true} forwards a model id
 *   with no mapping as it is, for {@code BedrockProxyPassthroughIntegrationTest},
 *   {@code BedrockProxyCancellationIntegrationTest} and {@code BedrockProxyLoggingIntegrationTest}.
 *   {@code proxy.error-body-timeout-millis=10} shortens the deadline for an upstream error body, so
 *   the stalled-error-body tests in {@code BedrockProxyLoggingIntegrationTest} do not each wait the
 *   one-second default.</li>
 *   <li>{@code floci.services.dsql.mock=false} backs each DSQL cluster with a real database in
 *   Docker, on an ephemeral port. The {@code Dsql*IntegrationTest} data-plane classes rely on it.</li>
 *   <li>{@code floci.services.iot.endpoint-address=}{@value #IOT_ENDPOINT_ADDRESS} is what
 *   DescribeEndpoint returns for every endpoint type. {@code IotDescribeEndpointOverrideIntegrationTest}
 *   relies on it. {@code floci.services.iot.rule-sql-strict=true} rejects a topic rule whose SQL is
 *   outside the subset Floci evaluates. {@code IotTopicRuleSqlStrictIntegrationTest} relies on
 *   it.</li>
 *   <li>{@code floci.partitions.allow-unknown-regions=true} gives a region label no partition
 *   publishes a namespace of its own. {@code PartitionUnknownRegionAllowedIntegrationTest} and
 *   {@code ColonRegionRejectionIntegrationTest} rely on it.</li>
 *   <li>{@code floci.services.ec2instanceconnect.enabled=false} turns EC2 Instance Connect off.
 *   {@code InstanceConnectEnablementIntegrationTest} relies on it.</li>
 *   <li>{@code floci.storage.services.rekognition.mode=persistent} keeps Rekognition collections on
 *   disk, in {@code rekognition-collections.json} under the test default
 *   {@code floci.storage.persistent-path}. {@code RekognitionCollectionIntegrationTest} relies on
 *   it.</li>
 * </ul>
 *
 * <p>Each of these is inert for the other classes: each is read by a single service, Bedrock
 * Runtime, DSQL, IoT DescribeEndpoint and topic rule creation, the region check on
 * a request whose region no partition publishes, EC2 Instance Connect, and Rekognition storage, and
 * no other class here calls that service or sends such a region. The persistent path stays the test
 * default every application uses, where Timestream for InfluxDB backups and Lambda code-volume
 * markers are written whatever the storage mode; the Rekognition file is the only one this profile
 * adds there, and only Rekognition reads it.
 *
 * <p>A class joins only if no other class here calls the service its setting changes, and if the
 * containers it starts tolerate the longer names the namespace gives them: an EMR cluster's node
 * container name passes the 63-character DNS label limit with it, so its HDFS cannot resolve its
 * datanodes, which is why the {@link ContainerDataPlaneProfile} classes stay apart. A profile that
 * extends this one starts from {@link #containerOverrides()} instead, so these settings stay with the
 * classes they were checked against.
 */
public class SidecarContainersProfile implements QuarkusTestProfile {

    private static final String NAMESPACE_PREFIX = "sidecar-test-";

    private static final String HOST_KEY = hostKey();

    private static final String NAMESPACE = NAMESPACE_PREFIX + HOST_KEY + "-" + ProcessHandle.current().pid();

    private static final int TEST_PORT = freePort();

    private static boolean abandonedSidecarsRemoved;

    /** Port of the stand-in OpenAI-compatible server the Bedrock proxy classes start. */
    public static final int BEDROCK_PROXY_PORT = 18930;

    /** The model id the Bedrock proxy maps to {@code claude-3-haiku}. */
    public static final String BEDROCK_PROXY_MAPPED_MODEL_ID = "anthropic.claude-3-haiku-20240307-v1:0";

    /** What IoT DescribeEndpoint returns for every endpoint type. */
    public static final String IOT_ENDPOINT_ADDRESS = "iot.example.localhost.floci.io";

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(containerOverrides());
        overrides.putAll(Map.ofEntries(
                Map.entry("floci.services.bedrock-runtime.backend", "proxy"),
                Map.entry("floci.services.bedrock-runtime.proxy.url", "http://127.0.0.1:" + BEDROCK_PROXY_PORT + "/v1"),
                Map.entry("floci.services.bedrock-runtime.proxy.api-key", "test-key"),
                Map.entry("floci.services.bedrock-runtime.proxy.model-mapping",
                        BEDROCK_PROXY_MAPPED_MODEL_ID + "=claude-3-haiku"),
                Map.entry("floci.services.bedrock-runtime.proxy.passthrough", "true"),
                Map.entry("floci.services.bedrock-runtime.proxy.error-body-timeout-millis", "10"),
                Map.entry("floci.services.dsql.mock", "false"),
                Map.entry("floci.services.iot.endpoint-address", IOT_ENDPOINT_ADDRESS),
                Map.entry("floci.services.iot.rule-sql-strict", "true"),
                Map.entry("floci.partitions.allow-unknown-regions", "true"),
                Map.entry("floci.services.ec2instanceconnect.enabled", "false"),
                Map.entry("floci.storage.services.rekognition.mode", "persistent")));
        return overrides;
    }

    /**
     * The namespace, port and billing emitter settings alone, without the service settings this
     * profile's own classes add. A profile that extends this one for classes of its own starts from
     * these, so that a setting added here for one class never reaches an application it was not
     * checked against.
     */
    public static Map<String, String> containerOverrides() {
        return Map.of(
                "floci.docker.resource-namespace", NAMESPACE,
                "quarkus.http.test-port", String.valueOf(TEST_PORT),
                "quarkus.http.port", String.valueOf(TEST_PORT),
                "floci.port", String.valueOf(TEST_PORT),
                "floci.base-url", "http://localhost:" + TEST_PORT,
                "floci.services.cur.emit-mode", "off",
                "floci.services.bcm-data-exports.emit-mode", "off");
    }

    /**
     * This run's namespace, for a profile of another kind whose classes start containers that need
     * the same isolation. Its containers are then removed by {@link #requireDockerAndImage} too when
     * a killed run leaves them behind.
     */
    public static String namespace() {
        return NAMESPACE;
    }

    /**
     * Identifies this machine and process id namespace, the scope a process id is valid in. On
     * Linux the namespace is {@code /proc/self/ns/pid}; elsewhere the host name has to do alone.
     */
    private static String hostKey() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ignored) {
            // An unresolvable host name still leaves the process id namespace to tell hosts apart.
            host = "unknown-host";
        }
        String pidNamespace;
        try {
            pidNamespace = Files.readSymbolicLink(Path.of("/proc/self/ns/pid")).toString();
        } catch (IOException | UnsupportedOperationException ignored) {
            // Not Linux: there is no process id namespace to read, so the host name is the key.
            pidNamespace = "";
        }
        return Integer.toHexString((host + "|" + pidNamespace).hashCode());
    }

    private static int freePort() {
        try {
            return FreePorts.anyInterfacePort();
        } catch (IOException e) {
            throw new UncheckedIOException("No free port for the sidecar test application", e);
        }
    }

    /**
     * Skips the calling class without Docker. Locally it is also skipped when the sidecar image named
     * by {@code imageProperty} is not present, so a developer without registry access is not stuck;
     * in CI a missing image is a failure, since a vanished tag must not pass silently.
     */
    public static void requireDockerAndImage(String imageProperty) {
        requireDocker();
        String image = ConfigProvider.getConfig().getValue(imageProperty, String.class);
        Assumptions.assumeTrue(imageUsable(image), "Sidecar image " + image + " is not present locally");
    }

    /**
     * Skips the calling class or test without Docker, for one whose manager pulls the image it
     * needs itself, and removes what killed runs left behind, as {@link #requireDockerAndImage} does.
     */
    public static void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for sidecar integration tests");
        removeAbandonedSidecars();
    }

    /**
     * Removes the containers and volumes of earlier runs that were killed before they could remove
     * them: those in a {@code sidecar-test-<hostkey>-<pid>} namespace carrying this host key, whose
     * process no longer exists. The containers would otherwise keep running and hold their published
     * host ports, and no later run would ever share their names or owner label to replace them or
     * sweep their volumes, such as an ECS task's. The containers go first, since a volume still
     * mounted cannot be removed. Those of this run and of any other live run are left alone, and so
     * is every namespace with another host key, whose process id cannot be checked from here. Runs
     * once per JVM, from the first class that needs Docker.
     */
    private static synchronized void removeAbandonedSidecars() {
        if (abandonedSidecarsRemoved) {
            return;
        }
        abandonedSidecarsRemoved = true;
        for (String line : output("docker", "ps", "-a", "--filter", "label=floci_namespace",
                "--format", "{{.ID}} {{.Label \"floci_namespace\"}}")) {
            String[] idAndNamespace = line.split(" ", 2);
            if (idAndNamespace.length == 2 && isAbandoned(idAndNamespace[1])) {
                run("docker", "rm", "-f", idAndNamespace[0]);
            }
        }
        for (String line : output("docker", "volume", "ls", "--filter", "label=floci_namespace",
                "--format", "{{.Name}} {{.Label \"floci_namespace\"}}")) {
            String[] nameAndNamespace = line.split(" ", 2);
            if (nameAndNamespace.length == 2 && isAbandoned(nameAndNamespace[1])) {
                run("docker", "volume", "rm", nameAndNamespace[0]);
            }
        }
    }

    private static boolean isAbandoned(String namespace) {
        String sameHost = NAMESPACE_PREFIX + HOST_KEY + "-";
        if (!namespace.startsWith(sameHost)) {
            return false;
        }
        try {
            long pid = Long.parseLong(namespace.substring(sameHost.length()));
            return ProcessHandle.of(pid).isEmpty();
        } catch (NumberFormatException notOurs) {
            // Some other namespace that happens to share the prefix: not one this profile made.
            return false;
        }
    }

    private static boolean isDockerAvailable() {
        return run("docker", "version", "--format", "{{.Server.Version}}");
    }

    private static boolean imageUsable(String image) {
        if ("true".equals(System.getenv("CI"))) {
            // A cold pull can exceed the HTTP client's deadline. Finish the manager's bounded
            // image preparation in class setup, before any request starts that manager.
            Arc.container().instance(ImageCacheService.class).get().ensureImageExists(image);
            return true;
        }
        return run("docker", "image", "inspect", image);
    }

    private static boolean run(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception ignored) {
            // Docker (or the image) isn't available; the caller turns this into a skipped test,
            // not a failure, so there is nothing more useful to log here.
            return false;
        }
    }

    private static List<String> output(String... command) {
        try {
            Process process = new ProcessBuilder(command).start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.waitFor() == 0 ? stdout.lines().toList() : List.of();
        } catch (IOException ignored) {
            // The Docker check just passed, so a failure here only means there is nothing to
            // clean up this time; the next run tries again.
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }
}
