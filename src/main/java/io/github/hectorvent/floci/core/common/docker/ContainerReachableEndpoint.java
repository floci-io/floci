package io.github.hectorvent.floci.core.common.docker;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Computes the Floci base URL reachable from <em>inside</em> a Docker container that Floci launches.
 *
 * <p>When the embedded DNS server is active, containers built with
 * {@code ContainerBuilder.Builder.withEmbeddedDns()} have it wired as their resolver and can reach Floci by
 * the configured hostname (or the default DNS suffix). Otherwise we fall back to the raw Docker host
 * address (e.g. {@code host.docker.internal} or the bridge IP) resolved by {@link DockerHostResolver}.
 *
 * <p>This is the single definition of "how a container reaches Floci", shared by:
 * <ul>
 *   <li>{@link LaunchedContainerAwsEnv}, which sets the AWS endpoint for Lambda, ECS, Flink and MWAA
 *       containers</li>
 *   <li>{@code Ec2ContainerManager}, for the {@code AWS_ENDPOINT_URL} of EC2 instance containers</li>
 *   <li>{@code CustomResourceCfnProvisioner}, for the {@code ResponseURL} a custom resource
 *       Lambda PUTs its result to</li>
 *   <li>{@code FlociDuckClient}, for the S3 endpoint the DuckDB sidecar calls</li>
 * </ul>
 *
 * <p>Kubernetes pods do not use it: {@code KubernetesFlociAddressResolver} builds their endpoint
 * without the embedded DNS server.
 */
@ApplicationScoped
public class ContainerReachableEndpoint {

    private final EmulatorConfig config;
    private final DockerHostResolver dockerHostResolver;
    private final EmbeddedDnsServer embeddedDnsServer;
    private final int httpPort;

    @Inject
    public ContainerReachableEndpoint(EmulatorConfig config,
                                      DockerHostResolver dockerHostResolver,
                                      EmbeddedDnsServer embeddedDnsServer,
                                      @ConfigProperty(name = "quarkus.http.port") int httpPort) {
        this.config = config;
        this.dockerHostResolver = dockerHostResolver;
        this.embeddedDnsServer = embeddedDnsServer;
        this.httpPort = httpPort;
    }

    /**
     * The Floci {@code http://host:port} base URL reachable from inside a launched Docker container.
     *
     * <p>The port is the one Floci listens on, not the port in {@code floci.base-url}: base-url
     * advertises the host-facing address, which differs under a port mapping such as
     * {@code -p 4811:4566} and is refused inside the Docker network. That is
     * {@code quarkus.http.port}, which defaults to {@code floci.port} but can be overridden on its
     * own; with TLS enabled the HTTP server moves to a loopback-only internal port and the public
     * listener is {@code TlsProxyServer} on {@code floci.port}.
     */
    public String baseUrl() {
        return baseUrl(true);
    }

    /** Uses the Docker host when the container does not receive Floci's DNS resolver. */
    public String baseUrl(boolean useEmbeddedDns) {
        String flociHostname = useEmbeddedDns && embeddedDnsServer.getServerIp().isPresent()
                ? config.hostname().orElse(EmbeddedDnsServer.DEFAULT_SUFFIX)
                : dockerHostResolver.resolve();
        return "http://" + flociHostname + ":" + port();
    }

    /** The port of {@link #baseUrl()}: the one a launched container can reach Floci on. */
    public int port() {
        return config.tls().enabled() ? config.port() : httpPort;
    }
}
