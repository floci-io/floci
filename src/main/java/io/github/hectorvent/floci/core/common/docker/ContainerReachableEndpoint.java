package io.github.hectorvent.floci.core.common.docker;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;

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
 *   <li>{@code CloudFormationResourceProvisioner}, for the {@code ResponseURL} a custom resource
 *       Lambda PUTs its result to</li>
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

    @Inject
    public ContainerReachableEndpoint(EmulatorConfig config,
                                      DockerHostResolver dockerHostResolver,
                                      EmbeddedDnsServer embeddedDnsServer) {
        this.config = config;
        this.dockerHostResolver = dockerHostResolver;
        this.embeddedDnsServer = embeddedDnsServer;
    }

    /** The Floci {@code http://host:port} base URL reachable from inside a launched Docker container. */
    public String baseUrl() {
        int flociPort = URI.create(config.baseUrl()).getPort();
        String flociHostname = embeddedDnsServer.getServerIp().isPresent()
                ? config.hostname().orElse(EmbeddedDnsServer.DEFAULT_SUFFIX)
                : dockerHostResolver.resolve();
        return "http://" + flociHostname + ":" + flociPort;
    }
}
