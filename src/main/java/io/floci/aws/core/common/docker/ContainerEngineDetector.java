package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Version;
import com.github.dockerjava.api.model.VersionComponent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/** Identifies the daemon behind the configured Docker-compatible socket. */
@ApplicationScoped
public class ContainerEngineDetector {

    private enum ContainerEngine { PODMAN, DOCKER, UNKNOWN }

    private static final Logger LOG = Logger.getLogger(ContainerEngineDetector.class);

    private final DockerClient dockerClient;
    private final AtomicBoolean warningLogged = new AtomicBoolean();
    private volatile ContainerEngine containerEngine;

    @Inject
    public ContainerEngineDetector(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    boolean isPodmanDaemon() {
        ContainerEngine cached = containerEngine;
        if (cached != null) {
            return cached == ContainerEngine.PODMAN;
        }
        return detectContainerEngine() == ContainerEngine.PODMAN;
    }

    private synchronized ContainerEngine detectContainerEngine() {
        if (containerEngine != null) {
            return containerEngine;
        }
        Version version;
        try {
            // The socket's daemon can differ from the JVM's OS or container runtime.
            version = dockerClient.versionCmd().exec();
        } catch (RuntimeException e) {
            if (warningLogged.compareAndSet(false, true)) {
                LOG.warnv(e, "Could not identify the container engine; retaining the Docker host-gateway mapping");
            }
            // A transient probe failure must not permanently classify a Podman daemon as unknown.
            return ContainerEngine.UNKNOWN;
        }
        if (version != null) {
            List<VersionComponent> components = version.getComponents();
            List<String> engineNames = Stream.concat(
                            Stream.of(version.getPlatform() == null ? null : version.getPlatform().getName()),
                            components == null ? Stream.empty()
                                    : components.stream().filter(Objects::nonNull).map(VersionComponent::getName))
                    .filter(Objects::nonNull)
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .toList();
            if (engineNames.stream().anyMatch(name -> name.contains("podman"))) {
                containerEngine = ContainerEngine.PODMAN;
                return containerEngine;
            }
            if (engineNames.stream().anyMatch(name -> name.contains("docker") || name.contains("moby"))) {
                containerEngine = ContainerEngine.DOCKER;
                return containerEngine;
            }
        }
        warnUnknownEngine("no Docker or Podman identity in /version");
        containerEngine = ContainerEngine.UNKNOWN;
        return containerEngine;
    }

    private void warnUnknownEngine(String reason) {
        if (warningLogged.compareAndSet(false, true)) {
            LOG.warnv("Could not identify the container engine ({0}); retaining the Docker host-gateway mapping",
                    reason);
        }
    }
}
