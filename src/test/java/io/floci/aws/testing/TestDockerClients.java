package io.floci.aws.testing;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;

import java.time.Duration;

/**
 * A Docker client for a test that has none from Floci's application, either because it runs outside it or because it
 * starts containers Floci does not own: the daemon the environment names, as {@code docker} itself would reach it.
 */
public final class TestDockerClients {

    private TestDockerClients() {
    }

    public static DockerClient local() {
        DefaultDockerClientConfig clientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(clientConfig.getDockerHost())
                .maxConnections(10)
                .connectionTimeout(Duration.ofSeconds(10))
                .responseTimeout(Duration.ofMinutes(5))
                .build();
        return DockerClientImpl.getInstance(clientConfig, httpClient);
    }
}
