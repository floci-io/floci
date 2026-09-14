package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.NetworkSettings;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2MultiNetworkMetadataIntegrationTest {
    @Test
    void restoredGuestCanReadMetadataThroughItsSharedNetworkAddress() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        when(config.services().ec2().imdsPort()).thenReturn(port);
        Vertx vertx = Vertx.vertx();
        Ec2MetadataServer server = new Ec2MetadataServer(vertx, config, null);
        DockerClient docker = mock(DockerClient.class);
        InspectContainerCmd command = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        NetworkSettings settings = mock(NetworkSettings.class);
        when(docker.inspectContainerCmd("guest")).thenReturn(command);
        when(command.exec()).thenReturn(response);
        when(response.getNetworkSettings()).thenReturn(settings);
        Map<String, ContainerNetwork> networks = new LinkedHashMap<>();
        networks.put("vpc", new ContainerNetwork().withIpv4Address("10.0.1.10"));
        networks.put("bridge", new ContainerNetwork().withIpv4Address("172.17.0.4"));
        // Use loopback for the actual HTTP client's source, standing in for the shared network.
        networks.put("shared", new ContainerNetwork().withIpv4Address("127.0.0.1"));
        when(settings.getNetworks()).thenReturn(networks);
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.isContainerRunning("guest")).thenReturn(true);
        Ec2ContainerManager manager = new Ec2ContainerManager(mock(ContainerBuilder.class), lifecycle,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), mock(DockerHostResolver.class),
                docker, mock(PortAllocator.class), config, server, mock(Ec2PortForwardManager.class),
                mock(RegionResolver.class), mock(ContainerNetworkReachability.class), mock(VpcNetworkManager.class));
        Instance instance = new Instance();
        instance.setInstanceId("i-multinetwork");
        instance.setDockerContainerId("guest");
        instance.setUserData("#!/bin/sh\necho metadata\n");
        String endpoint = "http://127.0.0.1:" + port;
        try (HttpClient client = HttpClient.newHttpClient()) {
            server.start().get(10, TimeUnit.SECONDS);
            assertTrue(manager.restoreMetadataRegistration(instance));
            HttpResponse<String> token = client.send(HttpRequest.newBuilder(URI.create(endpoint + "/latest/api/token"))
                    .timeout(Duration.ofSeconds(5)).header("X-aws-ec2-metadata-token-ttl-seconds", "60")
                    .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, token.statusCode());
            for (Map.Entry<String, String> entry : Map.of("meta-data/instance-id", "i-multinetwork",
                    "user-data", instance.getUserData()).entrySet()) {
                HttpResponse<String> metadata = client.send(HttpRequest.newBuilder(
                                URI.create(endpoint + "/latest/" + entry.getKey()))
                        .timeout(Duration.ofSeconds(5)).header("X-aws-ec2-metadata-token", token.body())
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, metadata.statusCode());
                assertEquals(entry.getValue(), metadata.body());
            }
        } finally {
            manager.stop();
            server.stop();
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }
}
