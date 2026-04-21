package io.github.hectorvent.floci.core.common.docker;

import io.github.hectorvent.floci.config.EmulatorConfig;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * CDI producer for the DockerClient singleton bean.
 */
@ApplicationScoped
public class DockerClientProducer {

    private static final Logger LOG = Logger.getLogger(DockerClientProducer.class);

    private final EmulatorConfig config;

    @Inject
    public DockerClientProducer(EmulatorConfig config) {
        this.config = config;
    }

    @Produces
    @ApplicationScoped
    public DockerClient dockerClient() {
        String dockerHost = config.docker().dockerHost();
        LOG.infov("Creating DockerClient for host: {0}", dockerHost);

        DefaultDockerClientConfig.Builder builder = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost);
        config.docker().dockerConfigPath().ifPresent(path -> {
            LOG.infov("Using Docker config path: {0}", path);
            builder.withDockerConfig(path);
        });
        DefaultDockerClientConfig clientConfig = builder.build();

        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(clientConfig.getDockerHost())
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofMinutes(5))
                .build();

        return DockerClientImpl.getInstance(clientConfig, httpClient);
    }
}
