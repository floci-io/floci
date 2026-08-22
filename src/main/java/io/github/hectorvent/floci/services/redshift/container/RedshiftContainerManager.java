package io.github.hectorvent.floci.services.redshift.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

@ApplicationScoped
public class RedshiftContainerManager {

    private static final Logger LOG = Logger.getLogger(RedshiftContainerManager.class);

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;

    @Inject
    public RedshiftContainerManager(ContainerBuilder containerBuilder,
                                    ContainerLifecycleManager lifecycleManager,
                                    ContainerLogStreamer logStreamer,
                                    ContainerDetector containerDetector,
                                    EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.config = config;
    }

    public RedshiftContainerHandle start(String clusterIdentifier, String masterUsername, String masterPassword) {
        String image = config.services().redshift().imageVersion();
        String containerName = "floci-redshift-" + clusterIdentifier;

        List<String> envVars = List.of(
                "POSTGRES_USER=" + masterUsername,
                "POSTGRES_PASSWORD=" + masterPassword,
                "POSTGRES_DB=dev"
        );

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withEnv(envVars)
                .withDockerNetwork(config.services().redshift().dockerNetwork())
                .withLogRotation();

        int enginePort = 5432; // Default postgres port
        if (!containerDetector.isRunningInContainer()) {
            specBuilder.withDynamicPort(enginePort);
        } else {
            specBuilder.withExposedPort(enginePort);
        }

        ContainerSpec spec = specBuilder.build();
        ContainerInfo info = lifecycleManager.createAndStart(spec);
        EndpointInfo endpoint = info.getEndpoint(enginePort);

        RedshiftContainerHandle handle = new RedshiftContainerHandle(
                info.containerId(), clusterIdentifier, endpoint.host(), endpoint.port());

        try {
            Closeable stream = logStreamer.attach(info.containerId(), "/floci/redshift", clusterIdentifier, "us-east-1", "redshift:" + clusterIdentifier);
            handle.setLogStream(stream);
        } catch (Exception e) {
            LOG.warnv("Failed to stream logs for {0}", containerName);
        }
        
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return handle;
    }

    public void stop(String clusterIdentifier) {
        String containerName = "floci-redshift-" + clusterIdentifier;
        lifecycleManager.removeIfExists(containerName);
    }
}
