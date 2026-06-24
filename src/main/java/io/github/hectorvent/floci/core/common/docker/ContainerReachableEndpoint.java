package io.github.hectorvent.floci.core.common.docker;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;

/**
 * Computes the Floci base URL reachable from <em>inside</em> a Lambda container.
 *
 * <p>When the embedded DNS server is active, Lambda containers have it wired as their
 * resolver and can reach Floci by the configured hostname (or the default DNS suffix).
 * Otherwise we fall back to the raw Docker host address (e.g. {@code host.docker.internal}
 * or the bridge IP) resolved by {@link DockerHostResolver}.
 *
 * <p>This is the address that must back any URL handed to a Lambda for a callback —
 * most notably the {@code ResponseURL} a CloudFormation custom resource PUTs its result to.
 * Extracted from {@code ContainerLauncher} so both the launcher and the CloudFormation
 * provisioner share one definition of "how a container reaches Floci".
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

    /** The Floci {@code http://host:port} base URL reachable from inside a Lambda container. */
    public String baseUrl() {
        int flociPort = URI.create(config.baseUrl()).getPort();
        String flociHostname = embeddedDnsServer.getServerIp().isPresent()
                ? config.hostname().orElse(EmbeddedDnsServer.DEFAULT_SUFFIX)
                : dockerHostResolver.resolve();
        return "http://" + flociHostname + ":" + flociPort;
    }
}
