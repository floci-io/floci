package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.NetworkSettings;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2MultiNetworkMetadataIntegrationTest {
    @Test
    void restoredGuestCanReadMetadataThroughItsSharedNetworkAddress() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        try (Ec2MetadataTestFixture fixture = new Ec2MetadataTestFixture()) {
            fixture.startUnregistered(config);
            Ec2MetadataServer server = fixture.server();
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
                    mock(RegionResolver.class), mock(ContainerNetworkReachability.class), mock(VpcNetworkManager.class),
                    mock(ContainerReachableEndpoint.class));
            Instance instance = new Instance();
            instance.setInstanceId("i-multinetwork");
            instance.setDockerContainerId("guest");
            instance.setUserData("#!/bin/sh\necho metadata\n");
            try {
                assertTrue(manager.restoreMetadataRegistration(instance));
                HttpResponse<String> token = fixture.putToken("60");
                assertEquals(200, token.statusCode());
                for (Map.Entry<String, String> entry : Map.of("meta-data/instance-id", "i-multinetwork",
                        "user-data", instance.getUserData()).entrySet()) {
                    HttpResponse<String> metadata = fixture.get("/latest/" + entry.getKey(), token.body());
                    assertEquals(200, metadata.statusCode());
                    assertEquals(entry.getValue(), metadata.body());
                }
            } finally {
                manager.stop();
            }
        }
    }
}
