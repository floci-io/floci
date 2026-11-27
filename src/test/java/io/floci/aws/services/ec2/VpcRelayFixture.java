package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link VpcAddressRelays} over a mocked EC2 and Docker: EC2 leases the addresses of a Docker-backed subnet, whose
 * range is {@code 10.0.1.0/24}, in order from {@code 10.0.1.20}, takes one back once it is released, and attaches any container; Docker starts a relay as
 * {@code relay-1}, {@code relay-2}, ... and reports it running until it is removed. The caller tells the EC2 mock which
 * VPC a subnet is in, through {@code describeSubnets}.
 */
public final class VpcRelayFixture {

    public final Ec2Service ec2;
    public final DockerClient docker;
    public final ContainerLifecycleManager lifecycleManager;
    public final ContainerBuilder containerBuilder;
    public final ContainerBuilder.Builder builder;
    public final EmulatorConfig config;
    /** The relays Docker has. */
    public final List<String> present = new ArrayList<>();
    /** The addresses EC2 has leased. */
    public final Set<String> leased = new HashSet<>();
    /** Where the relays write themselves down, shared by every relays this fixture builds, as a restart shares it. */
    public final VpcAddressRelays.Placements placements = VpcAddressRelays.Placements.inMemory();
    private int created;
    private int nextHost = 20;

    public VpcRelayFixture(Ec2Service ec2) {
        this.ec2 = ec2;
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(ec2).runWithSubnetReferencesHeld(anyString(), any());
        when(ec2.allocateVpcPrivateIp(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            String address = "10.0.1." + nextHost++;
            leased.add(address);
            return Optional.of(address);
        });
        when(ec2.effectiveSubnetCidr(anyString(), anyString(), anyString())).thenReturn(Optional.of("10.0.1.0/24"));
        when(ec2.reserveVpcPrivateIp(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> leased.add(invocation.getArgument(3)));
        doAnswer(invocation -> leased.remove(invocation.<String>getArgument(3)))
                .when(ec2).releaseVpcPrivateIp(anyString(), anyString(), anyString(), anyString());
        when(ec2.attachContainerToVpc(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of("vpc-net"));

        docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(docker.inspectContainerCmd(any())).thenAnswer(invocation -> {
            if (!present.contains(invocation.<String>getArgument(0))) {
                throw new NotFoundException("gone");
            }
            InspectContainerCmd inspect = mock(InspectContainerCmd.class, RETURNS_DEEP_STUBS);
            when(inspect.exec().getState().getRunning()).thenReturn(true);
            return inspect;
        });
        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.getDockerClient()).thenReturn(docker);
        startsRelaysNormally();
        doAnswer(invocation -> present.remove(invocation.<String>getArgument(0)))
                .when(lifecycleManager).removeIfExistsStrict(any(String.class));

        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(new ContainerSpec("alpine/socat"));
        containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.port()).thenReturn(4566);
    }

    /** Docker starts each relay as {@code relay-1}, {@code relay-2}, ..., as it does until a test says otherwise. */
    public void startsRelaysNormally() {
        doAnswer(invocation -> {
            String id = "relay-" + ++created;
            invocation.<Consumer<String>>getArgument(1).accept(id);
            present.add(id);
            return new ContainerLifecycleManager.ContainerInfo(id, Map.of());
        }).when(lifecycleManager).createAndStart(any(ContainerSpec.class), any(Consumer.class));
    }

    /** Relays for {@code service}'s endpoints, on {@code network}, or Floci's default network when null. */
    public VpcAddressRelays relays(String service, String network) {
        return new VpcAddressRelays(new VpcContainerLeases(ec2, "Test endpoint", () -> docker),
                containerBuilder, lifecycleManager, config, service, "alpine/socat", network, placements);
    }
}
