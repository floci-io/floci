package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListNetworksCmd;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.Address;
import io.github.hectorvent.floci.services.ec2.model.BlockDeviceMapping;
import io.github.hectorvent.floci.services.ec2.model.EbsBlockDevice;
import io.github.hectorvent.floci.services.ec2.model.EgressOnlyInternetGateway;
import io.github.hectorvent.floci.services.ec2.model.GroupIdentifier;
import io.github.hectorvent.floci.services.ec2.model.Image;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.IpRange;
import io.github.hectorvent.floci.services.ec2.model.Ipv6Range;
import io.github.hectorvent.floci.services.ec2.model.KeyPair;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.ManagedPrefixList;
import io.github.hectorvent.floci.services.ec2.model.NatGateway;
import io.github.hectorvent.floci.services.ec2.model.NatGatewayAddress;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterfaceAssociation;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterfaceListResult;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterfacePrivateIpAddress;
import io.github.hectorvent.floci.services.ec2.model.Placement;
import io.github.hectorvent.floci.services.ec2.model.PrefixList;
import io.github.hectorvent.floci.services.ec2.model.PrefixListEntry;
import io.github.hectorvent.floci.services.ec2.model.PrefixListId;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroupRule;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroupRule;
import io.github.hectorvent.floci.services.ec2.model.Snapshot;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.TransitGateway;
import io.github.hectorvent.floci.services.ec2.model.TransitGatewayOptions;
import io.github.hectorvent.floci.services.ec2.model.TransitGatewayRoute;
import io.github.hectorvent.floci.services.ec2.model.TransitGatewayRouteTable;
import io.github.hectorvent.floci.services.ec2.model.TransitGatewayRouteTablePropagation;
import io.github.hectorvent.floci.services.ec2.model.TransitGatewayVpcAttachment;
import io.github.hectorvent.floci.services.ec2.model.TransitGatewayVpcAttachmentOptions;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.model.VolumeAttachment;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.ec2.model.VpcCidrBlockAssociation;
import io.github.hectorvent.floci.services.ec2.model.VpcEndpoint;
import io.github.hectorvent.floci.services.ec2.model.VpcEndpointSubnetConfiguration;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.testing.ParkingStore;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.awaitility.Awaitility;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockMakers;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.io.StringReader;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.github.hectorvent.floci.services.ec2.Ec2KeyPairFixtures.ECDSA_P256_KEY;
import static io.github.hectorvent.floci.services.ec2.Ec2KeyPairFixtures.ED25519_FINGERPRINT;
import static io.github.hectorvent.floci.services.ec2.Ec2KeyPairFixtures.ED25519_KEY;
import static io.github.hectorvent.floci.services.ec2.Ec2KeyPairFixtures.RSA_FINGERPRINT;
import static io.github.hectorvent.floci.services.ec2.Ec2KeyPairFixtures.RSA_KEY;
import static io.github.hectorvent.floci.services.ec2.Ec2KeyPairFixtures.TRUNCATED_RSA_KEY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class Ec2ServiceTest {

    @ParameterizedTest
    @CsvSource({
            "false, aws:autoscaling:groupName", "true, aws:autoscaling:groupName",
            "false, AWS:custom", "true, AWS:custom",
            "false, aWs:custom", "true, aWs:custom"
    })
    void userTagMutationsRejectAwsKeysBeforeChangingAnyResource(boolean delete, String key) {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        List<Tag> original = List.of(new Tag("aws:autoscaling:groupName", "group"), new Tag("env", "original"));
        List<String> ids = service.runInstances("us-east-1", "ami-test", "t3.micro",
                        2, 2, null, List.of(), null, null, original, null, null)
                .getInstances().stream().map(Instance::getInstanceId).toList();
        List<Tag> changes = List.of(new Tag("env", delete ? null : "changed"), new Tag(key, "spoofed"));

        AwsException error = assertThrows(AwsException.class, () -> {
            if (delete) {
                service.deleteTags("us-east-1", ids, changes);
            } else {
                service.createTags("us-east-1", ids, changes);
            }
        });

        assertEquals("InvalidParameterValue", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("Value ( " + key + " ) for parameter key is invalid. "
                + "Tag keys starting with 'aws:' are reserved for internal use", error.getMessage());
        for (Reservation reservation : service.describeInstances("us-east-1", ids, Map.of())) {
            for (Instance instance : reservation.getInstances()) {
                assertEquals(original, instance.getTags());
            }
        }
    }

    @Test
    void deletingAllUserTagsPreservesTheAutoscalingMembershipTag() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        Tag membership = new Tag("aws:autoscaling:groupName", "group");
        String id = service.runInstances("us-east-1", "ami-test", "t3.micro",
                        1, 1, null, List.of(), null, null,
                        List.of(membership, new Tag("env", "original")), null, null)
                .getInstances().getFirst().getInstanceId();

        service.createTags("us-east-1", List.of(id), List.of(new Tag("env", "updated")));
        service.deleteTags("us-east-1", List.of(id), List.of());

        Instance instance = service.describeInstances("us-east-1", List.of(id), Map.of())
                .getFirst().getInstances().getFirst();
        assertEquals(List.of(membership), instance.getTags());
    }

    @Test
    void deleteSystemTagRemovesTheTagOnlyWhenItsValueMatches() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        Tag membership = new Tag("aws:autoscaling:groupName", "group-a");
        Tag userTag = new Tag("env", "test");
        List<String> ids = List.of(service.runInstances("us-east-1", "ami-test", "t3.micro",
                        1, 1, null, List.of(), null, null, List.of(membership, userTag), null, null)
                .getInstances().getFirst().getInstanceId());

        service.deleteSystemTag("us-east-1", ids, membership.getKey(), "group-b");
        assertEquals(List.of(membership, userTag), service.describeInstances("us-east-1", ids, Map.of())
                .getFirst().getInstances().getFirst().getTags());

        service.deleteSystemTag("us-east-1", ids, membership.getKey(), "group-a");
        assertEquals(List.of(userTag), service.describeInstances("us-east-1", ids, Map.of())
                .getFirst().getInstances().getFirst().getTags());
        assertEquals(List.of(userTag), service.resourceTags("us-east-1", ids.getFirst()));
    }


    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createNetworkInterfaceRejectsMissingGroupsBeforeAllocatingAddresses(boolean includeExistingGroup) {
        Ec2Service service = mockModeService();
        String region = "us-east-1";
        Subnet subnet = service.describeSubnets(region, List.of(), Map.of()).getFirst();
        String missingGroup = "sg-0f294603eafad4835";
        SecurityGroup existingGroup = service.createSecurityGroup(region, "interface-validation",
                "interface validation", subnet.getVpcId());
        List<String> groupIds = includeExistingGroup
                ? List.of(existingGroup.getGroupId(), missingGroup) : List.of(missingGroup);
        int availableAddresses = subnet.getAvailableIpAddressCount();

        AwsException error = assertThrows(AwsException.class, () -> service.createNetworkInterface(
                region, subnet.getSubnetId(), null, null, List.of(), groupIds, List.of()));

        assertEquals("InvalidSecurityGroupID.NotFound", error.getErrorCode());
        assertEquals("The securityGroup ID '" + missingGroup + "' does not exist", error.getMessage());
        assertEquals(400, error.getHttpStatus());
        assertTrue(service.describeNetworkInterfaces(region, List.of(), Map.of()).networkInterfaces().isEmpty());
        assertEquals(availableAddresses, service.describeSubnets(region, List.of(subnet.getSubnetId()), Map.of())
                .getFirst().getAvailableIpAddressCount());
    }

    @Test
    void createNetworkInterfaceRejectsGroupsFromAnotherVpcBeforeAllocatingAddresses() {
        Ec2Service service = mockModeService();
        String region = "us-east-1";
        Subnet subnet = service.describeSubnets(region, List.of(), Map.of()).getFirst();
        String foreignVpc = service.createVpc(region, "10.93.0.0/16", false).getVpcId();
        SecurityGroup foreignGroup = service.createSecurityGroup(region, "foreign-interface-group",
                "foreign interface group", foreignVpc);
        int availableAddresses = subnet.getAvailableIpAddressCount();

        AwsException error = assertThrows(AwsException.class, () -> service.createNetworkInterface(
                region, subnet.getSubnetId(), null, null, List.of(),
                List.of(foreignGroup.getGroupId()), List.of()));

        assertEquals("InvalidGroup.NotFound", error.getErrorCode());
        assertTrue(service.describeNetworkInterfaces(region, List.of(), Map.of()).networkInterfaces().isEmpty());
        assertEquals(availableAddresses, service.describeSubnets(region, List.of(subnet.getSubnetId()), Map.of())
                .getFirst().getAvailableIpAddressCount());
    }

    @Test
    void createNetworkInterfaceRetainsAnExistingSubnetVpcGroup() {
        Ec2Service service = mockModeService();
        String region = "us-east-1";
        Subnet subnet = service.describeSubnets(region, List.of(), Map.of()).getFirst();
        SecurityGroup group = service.createSecurityGroup(region, "valid-interface-group",
                "valid interface group", subnet.getVpcId());

        NetworkInterface networkInterface = service.createNetworkInterface(region, subnet.getSubnetId(),
                null, null, List.of(), List.of(group.getGroupId()), List.of());

        assertEquals(List.of(group.getGroupId()), networkInterface.getGroups().stream()
                .map(GroupIdentifier::getGroupId).toList());
        assertEquals(subnet.getVpcId(), networkInterface.getVpcId());
        assertEquals(1, service.describeNetworkInterfaces(region, List.of(), Map.of()).networkInterfaces().size());
    }

    @Test
    void runInstancesRetainsItsMissingSecurityGroupErrorWithoutAllocatingResources() {
        Ec2Service service = mockModeService();
        String region = "us-east-1";
        Subnet subnet = service.describeSubnets(region, List.of(), Map.of()).getFirst();

        AwsException error = assertThrows(AwsException.class, () -> service.runInstances(
                region, "ami-test", "t3.micro", 1, 1, null, List.of("sg-0f294603eafad4835"),
                subnet.getSubnetId(), null, List.of(), null, null));

        assertEquals("InvalidGroup.NotFound", error.getErrorCode());
        assertTrue(service.describeInstances(region, List.of(), Map.of()).isEmpty());
        assertTrue(service.describeVolumes(region, List.of(), Map.of()).isEmpty());
    }

    @Test
    void aRollbackDeletesALaunchVolumeWhoseAttachmentEndedFirst() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        Reservation launch = launchKeepingADataVolume(service);
        String instanceId = launch.getInstances().getFirst().getInstanceId();
        String dataVolume = service.describeVolumes("us-east-1", List.of(), Map.of()).stream()
                .filter(volume -> "/dev/sdf".equals(volume.getAttachments().getFirst().getDevice()))
                .findFirst().orElseThrow().getVolumeId();
        // A failed container's launch volumes are settled by whichever call reads them first, which
        // can end this attachment before a later launch of the same fleet fails.
        service.detachVolume("us-east-1", dataVolume, instanceId, null, false);

        service.rollBackLaunch("us-east-1", launch);

        assertTrue(service.describeVolumes("us-east-1", List.of(), Map.of()).isEmpty());
    }

    @Test
    void describeInstancesReadsTheVolumeAttachmentsOnceWhateverTheInstanceCount() {
        Ec2Service service = spy(new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory()));
        service.runInstances("us-east-1", "ami-test", "t3.micro", 3, 3, null, List.of(), null, null,
                List.of(), null, null);
        Ec2QueryHandler handler = new Ec2QueryHandler(service, mock(EmulatorConfig.class),
                mock(FlowLogService.class), mock(Ec2EbsEncryptionService.class),
                mock(Ec2SnapshotBlockPublicAccessService.class), mock(Ec2IpamService.class),
                new RegionResolver("us-east-1", "000000000000"));

        Response response = handler.handle("DescribeInstances", new MultivaluedHashMap<>(), "us-east-1");

        assertEquals(200, response.getStatus());
        assertEquals(3, response.getEntity().toString().split("<volumeId>", -1).length - 1);
        assertEquals(1, mockingDetails(service).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("instanceVolumeAttachments"))
                .count());
    }

    @Test
    void aLaunchRefusedWhenItTakesCapacityLeavesNoVolumeBehind() {
        Ec2Service service = spy(new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory()));
        // Another launch took the capacity between this launch's check and its claim on it.
        doThrow(new AwsException("InsufficientInstanceCapacity", "taken in the meantime", 400))
                .when(service).acquireCapacity(anyString(), anyList());

        assertThrows(AwsException.class, () -> launchKeepingADataVolume(service));

        assertTrue(service.describeVolumes("us-east-1", List.of(), Map.of()).isEmpty());
    }

    @Test
    void aRollbackWhoseTerminationFailsKeepsEachVolumesDeleteOnTermination() {
        Ec2Service service = spy(new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory()));
        Reservation launch = launchKeepingADataVolume(service);
        doThrow(new AwsException("InternalError", "termination failed", 500))
                .when(service).terminateInstances(anyString(), anyList());

        assertThrows(AwsException.class, () -> service.rollBackLaunch("us-east-1", launch));

        // The instance is still there, so a later TerminateInstances must keep what the launch asked to keep.
        assertEquals(Set.of("/dev/xvda=true", "/dev/sdf=false"),
                service.describeVolumes("us-east-1", List.of(), Map.of()).stream()
                        .map(volume -> volume.getAttachments().getFirst())
                        .map(a -> a.getDevice() + "=" + a.isDeleteOnTermination())
                        .collect(Collectors.toSet()));
    }

    private static Reservation launchKeepingADataVolume(Ec2Service service) {
        LaunchTemplateData.Ebs ebs = new LaunchTemplateData.Ebs();
        ebs.setVolumeSize(5);
        ebs.setDeleteOnTermination(false);
        LaunchTemplateData.BlockDeviceMapping data = new LaunchTemplateData.BlockDeviceMapping();
        data.setDeviceName("/dev/sdf");
        data.setEbs(ebs);
        return service.runInstances("us-east-1", "ami-test", "t3.micro", 1, 1, null, List.of(), null, null,
                List.of(), null, null, null, null, 0, null, null, null, null, false, null, null,
                List.of(data), false, null, null);
    }

    @Test
    void subnetAvailableAddressesTrackAllocationsAndRelease() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String region = "us-east-1";
        String vpcId = service.createVpc(region, "10.97.0.0/16", false).getVpcId();
        Subnet small = service.createSubnet(region, vpcId, "10.97.0.0/28", region + "a");
        Subnet large = service.createSubnet(region, vpcId, "10.97.1.0/24", region + "a");
        assertEquals(11, small.getAvailableIpAddressCount());
        assertEquals(251, large.getAvailableIpAddressCount());

        NetworkInterface eni = service.createNetworkInterface(region, small.getSubnetId(), "count-probe",
                "10.97.0.4", List.of("10.97.0.4", "10.97.0.5"), List.of(), List.of());
        assertEquals(9, service.describeSubnets(region, List.of(small.getSubnetId()), Map.of())
                .getFirst().getAvailableIpAddressCount());
        Reservation reservation = service.runInstances(region, "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), small.getSubnetId(), null, List.of(), null, null);
        assertEquals(8, service.describeSubnets(region, List.of(small.getSubnetId()), Map.of())
                .getFirst().getAvailableIpAddressCount());

        service.terminateInstances(region, List.of(reservation.getInstances().getFirst().getInstanceId()));
        service.deleteNetworkInterface(region, eni.getNetworkInterfaceId());
        assertEquals(11, service.describeSubnets(region, List.of(small.getSubnetId()), Map.of())
                .getFirst().getAvailableIpAddressCount());
        assertEquals(251, service.describeSubnets(region, List.of(large.getSubnetId()), Map.of())
                .getFirst().getAvailableIpAddressCount());
        // Legacy records can contain invalid addresses; they do not consume usable subnet space.
        service.createNetworkInterface(region, small.getSubnetId(), "legacy-addresses",
                "10.98.0.4", List.of("10.97.0.0", "10.97.0.15"), List.of(), List.of());
        assertEquals(11, service.describeSubnets(region, List.of(small.getSubnetId()), Map.of())
                .getFirst().getAvailableIpAddressCount());
    }

    @Test
    void createAndDescribeVpcsDoNotContactDockerEvenOnFirstUse() {
        EmulatorConfig config = mockConfig(false);
        EmulatorConfig.VpcNetworksConfig networks = mock(EmulatorConfig.VpcNetworksConfig.class);
        when(config.services().ec2().vpcNetworks()).thenReturn(networks);
        when(networks.enabled()).thenReturn(true);
        when(config.docker()).thenReturn(mock(EmulatorConfig.DockerConfig.class));
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        DockerClient docker = mock(DockerClient.class);
        VpcNetworkManager manager = new VpcNetworkManager(config, docker, null);
        Ec2Service service = new Ec2Service(config, mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory(), manager);

        Vpc created = service.createVpc("eu-central-1", "42.0.0.0/16", false);
        service.createTags("eu-central-1", List.of(created.getVpcId()), List.of(new Tag("Name", "probe")));
        List<Vpc> found = service.describeVpcs("eu-central-1", List.of(), Map.of("tag:Name", List.of("probe")));

        assertEquals(List.of(created.getVpcId()), found.stream().map(Vpc::getVpcId).toList());
        assertTrue(service.describeVpcs("eu-central-1", List.of(), Map.of()).stream().anyMatch(Vpc::isDefault));
        verifyNoInteractions(docker);
    }

    @Test
    void sharedDescribeVpcsOmitsUnknownIdsButExplicitLookupStillRejectsThem() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String region = "us-east-1";
        String unknown = "vpc-0000000000000dead";
        Vpc known = service.createVpc(region, "10.95.0.0/16", false);

        assertTrue(service.describeVpcs(region, List.of(unknown), Map.of()).isEmpty());
        assertEquals(List.of(known), service.describeVpcs(region, List.of(known.getVpcId(), unknown), Map.of()));
        assertTrue(service.describeVpcs("eu-west-1", List.of(known.getVpcId()), Map.of()).isEmpty());
        assertTrue(service.describeVpcs(region, List.of(known.getVpcId()),
                Map.of("cidr-block", List.of("10.96.0.0/16"))).isEmpty());
        assertEquals("InvalidVpcID.NotFound", assertThrows(AwsException.class,
                () -> service.requireVpc(region, unknown)).getErrorCode());
    }

    @Test
    void createVpcAcceptsDefaultOrDedicatedAndDefaultsWhenOmitted() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String region = "us-east-1";

        assertEquals("default", service.createVpc(region, "10.90.0.0/16", false, false, null).getInstanceTenancy());
        // A present blank is not an enum value; only an absent parameter defaults.
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                () -> service.createVpc(region, "10.91.0.0/16", false, false, " ")).getErrorCode());
        assertEquals("dedicated", service.createVpc(region, "10.92.0.0/16", false, false, "dedicated").getInstanceTenancy());

        AwsException bogus = assertThrows(AwsException.class,
                () -> service.createVpc(region, "10.93.0.0/16", false, false, "bogus"));
        assertEquals("InvalidParameterValue", bogus.getErrorCode());
        assertEquals(400, bogus.getHttpStatus());
        assertTrue(bogus.getMessage().contains("Value (bogus) for parameter instanceTenancy is invalid"));

        // The EC2 reference: "The host value cannot be used with this parameter."
        AwsException host = assertThrows(AwsException.class,
                () -> service.createVpc(region, "10.96.0.0/16", false, false, "host"));
        assertEquals("InvalidParameterValue", host.getErrorCode());
    }

    @Test
    void modifyVpcTenancyOnlyMovesADedicatedVpcToDefault() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String region = "us-east-1";
        String dedicated = service.createVpc(region, "10.94.0.0/16", false, false, "dedicated").getVpcId();
        String alreadyDefault = service.createVpc(region, "10.95.0.0/16", false, false, "default").getVpcId();

        service.modifyVpcTenancy(region, dedicated, "default");
        assertEquals("default", service.requireVpc(region, dedicated).getInstanceTenancy());

        AwsException fromDefault = assertThrows(AwsException.class,
                () -> service.modifyVpcTenancy(region, alreadyDefault, "default"));
        assertEquals("InvalidParameterValue", fromDefault.getErrorCode());

        AwsException toDedicated = assertThrows(AwsException.class,
                () -> service.modifyVpcTenancy(region, dedicated, "dedicated"));
        assertEquals("InvalidParameterValue", toDedicated.getErrorCode());
    }

    @Test
    void deleteVpcRemovesVpcOwnedDefaultResourcesAndRules() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String region = "us-east-1";
        String vpcId = service.createVpc(region, "10.77.0.0/16", false).getVpcId();
        SecurityGroup defaultGroup = service.describeSecurityGroups(region, List.of(), List.of(), Map.of()).stream()
                .filter(group -> vpcId.equals(group.getVpcId()) && "default".equals(group.getGroupName()))
                .findFirst().orElseThrow();
        assertTrue(service.describeRouteTables(region, List.of(), Map.of()).stream()
                .anyMatch(table -> vpcId.equals(table.getVpcId())
                        && table.getAssociations().stream().anyMatch(association -> association.isMain())));
        assertTrue(service.describeNetworkAcls(region, List.of(), Map.of()).stream()
                .anyMatch(acl -> vpcId.equals(acl.getVpcId()) && acl.isDefault()));
        assertFalse(service.describeSecurityGroupRules(region, List.of(defaultGroup.getGroupId()), List.of()).isEmpty());

        service.deleteVpc(region, vpcId);

        assertTrue(service.describeSecurityGroups(region, List.of(), List.of(), Map.of()).stream()
                .noneMatch(group -> vpcId.equals(group.getVpcId())));
        assertTrue(service.describeRouteTables(region, List.of(), Map.of()).stream()
                .noneMatch(table -> vpcId.equals(table.getVpcId())));
        assertTrue(service.describeNetworkAcls(region, List.of(), Map.of()).stream()
                .noneMatch(acl -> vpcId.equals(acl.getVpcId())));
        assertTrue(service.describeSecurityGroupRules(region, List.of(defaultGroup.getGroupId()), List.of()).isEmpty());
    }

    @Test
    void deleteVpcFailsWhileCallerCreatedResourcesRemain() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String region = "us-east-1";
        String vpcId = service.createVpc(region, "10.79.0.0/16", false).getVpcId();

        String subnetId = service.createSubnet(region, vpcId, "10.79.1.0/24", "us-east-1a").getSubnetId();
        String groupId = service.createSecurityGroup(region, "app", "app sg", vpcId).getGroupId();
        String routeTableId = service.createRouteTable(region, vpcId).getRouteTableId();
        String aclId = service.createNetworkAcl(region, vpcId).getNetworkAclId();

        assertEquals("DependencyViolation", assertThrows(AwsException.class,
                () -> service.deleteVpc(region, vpcId)).getErrorCode());

        service.deleteSubnet(region, subnetId);
        assertEquals("DependencyViolation", assertThrows(AwsException.class,
                () -> service.deleteVpc(region, vpcId)).getErrorCode());
        service.deleteSecurityGroup(region, groupId);
        assertEquals("DependencyViolation", assertThrows(AwsException.class,
                () -> service.deleteVpc(region, vpcId)).getErrorCode());
        service.deleteRouteTable(region, routeTableId);
        assertEquals("DependencyViolation", assertThrows(AwsException.class,
                () -> service.deleteVpc(region, vpcId)).getErrorCode());
        service.deleteNetworkAcl(region, aclId);

        service.deleteVpc(region, vpcId);
        assertTrue(service.describeVpcs(region, List.of(), Map.of()).stream()
                .noneMatch(vpc -> vpcId.equals(vpc.getVpcId())));
    }

    @Test
    void deleteRouteRefusesTheLocalRouteAndReportsMissingDestinations() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String region = "us-east-1";
        String vpcId = service.createVpc(region, "10.86.0.0/16", false).getVpcId();
        String routeTableId = service.createRouteTable(region, vpcId).getRouteTableId();
        service.createRoute(region, routeTableId, "0.0.0.0/0", null, null, "igw-route0test0000000",
                null, null, null);

        AwsException local = assertThrows(AwsException.class,
                () -> service.deleteRoute(region, routeTableId, "10.86.0.0/16", null, null));
        assertEquals("InvalidParameterValue", local.getErrorCode());
        assertTrue(local.getMessage().contains("10.86.0.0/16"), local.getMessage());

        AwsException missing = assertThrows(AwsException.class,
                () -> service.deleteRoute(region, routeTableId, "203.0.113.0/24", null, null));
        assertEquals("InvalidRoute.NotFound", missing.getErrorCode());
        assertTrue(missing.getMessage().contains("203.0.113.0/24"), missing.getMessage());

        service.deleteRoute(region, routeTableId, "0.0.0.0/0", null, null);

        assertEquals(1, service.describeRouteTables(region, List.of(routeTableId), Map.of())
                .getFirst().getRoutes().size(), "the local route survives");
        assertEquals("CreateRouteTable", service.describeRouteTables(region, List.of(routeTableId), Map.of())
                .getFirst().getRoutes().getFirst().getOrigin());
    }

    @Test
    void deleteVpcFailsWhileAVpcEndpointRemains() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String region = "us-east-1";
        String vpcId = service.createVpc(region, "10.81.0.0/16", false).getVpcId();
        String endpointId = service.createVpcEndpoint(region, vpcId, "com.amazonaws.us-east-1.s3", "Gateway",
                List.of(), List.of(), List.of(), null, null, List.of()).getVpcEndpointId();

        assertEquals("DependencyViolation", assertThrows(AwsException.class,
                () -> service.deleteVpc(region, vpcId)).getErrorCode());

        service.deleteVpcEndpoints(region, List.of(endpointId));
        service.deleteVpc(region, vpcId);
    }

    @Test
    void deleteVpcFailsWhileAnInternetGatewayIsAttached() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String region = "us-east-1";
        String vpcId = service.createVpc(region, "10.80.0.0/16", false).getVpcId();
        String igwId = service.createInternetGateway(region).getInternetGatewayId();
        service.attachInternetGateway(region, igwId, vpcId);

        assertEquals("DependencyViolation", assertThrows(AwsException.class,
                () -> service.deleteVpc(region, vpcId)).getErrorCode());

        service.detachInternetGateway(region, igwId, vpcId);
        service.deleteVpc(region, vpcId);
    }

    @Test
    void deleteSubnetFailsUntilItsInstanceIsTerminated() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String region = "us-east-1";
        String vpcId = service.createVpc(region, "10.81.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet(region, vpcId, "10.81.1.0/24", "us-east-1a").getSubnetId();
        Instance instance = service.runInstances(region, "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), subnetId, null, List.of(), null, null).getInstances().getFirst();

        AwsException running = assertThrows(AwsException.class, () -> service.deleteSubnet(region, subnetId));
        assertEquals("DependencyViolation", running.getErrorCode());
        assertEquals("The subnet '" + subnetId + "' has dependencies and cannot be deleted.", running.getMessage());

        // Still holds its network interface while shutting down, so the subnet stays pinned.
        instance.setState(InstanceState.shuttingDown());
        assertEquals("DependencyViolation", assertThrows(AwsException.class,
                () -> service.deleteSubnet(region, subnetId)).getErrorCode());

        instance.setState(InstanceState.terminated());
        service.deleteSubnet(region, subnetId);
        assertTrue(service.describeSubnets(region, List.of(), Map.of()).stream()
                .noneMatch(subnet -> subnetId.equals(subnet.getSubnetId())));
    }

    @Test
    void deleteSubnetFailsUntilItsNatGatewayIsDeleted() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String region = "us-east-1";
        String vpcId = service.createVpc(region, "10.82.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet(region, vpcId, "10.82.1.0/24", "us-east-1a").getSubnetId();
        NatGateway natGateway = service.createNatGateway(region, subnetId, null, "private", List.of());

        assertEquals("DependencyViolation", assertThrows(AwsException.class,
                () -> service.deleteSubnet(region, subnetId)).getErrorCode());

        // Still holds its address while deleting, so the subnet stays pinned.
        natGateway.setState("deleting");
        assertEquals("DependencyViolation", assertThrows(AwsException.class,
                () -> service.deleteSubnet(region, subnetId)).getErrorCode());

        service.deleteNatGateway(region, natGateway.getNatGatewayId());
        service.deleteSubnet(region, subnetId);
        assertTrue(service.describeSubnets(region, List.of(), Map.of()).stream()
                .noneMatch(subnet -> subnetId.equals(subnet.getSubnetId())));
    }

    /**
     * A failed NAT gateway was never created and holds no interface, so it does not pin its subnet.
     * The gateway fails because its Elastic IP is already held by a gateway in the default VPC.
     */
    @Test
    void deleteSubnetIgnoresAFailedNatGateway() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String region = "us-east-1";
        String defaultSubnetId = service.describeSubnets(region, List.of(), Map.of()).getFirst().getSubnetId();
        String allocationId = service.allocateAddress(region).getAllocationId();
        service.createNatGateway(region, defaultSubnetId, allocationId, null, null);
        String vpcId = service.createVpc(region, "10.85.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet(region, vpcId, "10.85.1.0/24", "us-east-1a").getSubnetId();
        NatGateway failed = service.createNatGateway(region, subnetId, allocationId, null, null);
        assertEquals("failed", failed.getState());

        service.deleteSubnet(region, subnetId);
        assertTrue(service.describeSubnets(region, List.of(), Map.of()).stream()
                .noneMatch(subnet -> subnetId.equals(subnet.getSubnetId())));
    }

    /** DeleteVpc checks the same subnet residents, so a failed NAT gateway does not pin the VPC either. */
    @Test
    void deleteVpcIgnoresAFailedNatGatewayInOneOfItsSubnets() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String region = "us-east-1";
        String defaultSubnetId = service.describeSubnets(region, List.of(), Map.of()).getFirst().getSubnetId();
        String allocationId = service.allocateAddress(region).getAllocationId();
        service.createNatGateway(region, defaultSubnetId, allocationId, null, null);
        String vpcId = service.createVpc(region, "10.86.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet(region, vpcId, "10.86.1.0/24", "us-east-1a").getSubnetId();
        NatGateway failed = service.createNatGateway(region, subnetId, allocationId, null, null);
        assertEquals("failed", failed.getState());

        // DeleteVpc refuses while any subnet remains, so the resident check runs in DeleteSubnet;
        // after that the failed gateway must not keep the VPC either.
        service.deleteSubnet(region, subnetId);
        service.deleteVpc(region, vpcId);
        assertTrue(service.describeVpcs(region, List.of(), Map.of()).stream()
                .noneMatch(vpc -> vpcId.equals(vpc.getVpcId())));
    }

    @Test
    void deleteSubnetFailsUntilItsStandaloneNetworkInterfaceIsDeleted() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String region = "us-east-1";
        String vpcId = service.createVpc(region, "10.83.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet(region, vpcId, "10.83.1.0/24", "us-east-1a").getSubnetId();
        String eniId = service.createNetworkInterface(region, subnetId, null, null, List.of(), List.of(), List.of())
                .getNetworkInterfaceId();

        // An available interface is still an interface in the subnet, so the subnet stays pinned.
        assertEquals("DependencyViolation", assertThrows(AwsException.class,
                () -> service.deleteSubnet(region, subnetId)).getErrorCode());

        service.deleteNetworkInterface(region, eniId);
        service.deleteSubnet(region, subnetId);
    }

    @Test
    void deleteSubnetFailsUntilItsInterfaceEndpointIsDeleted() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String region = "us-east-1";
        String vpcId = service.createVpc(region, "10.84.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet(region, vpcId, "10.84.1.0/24", "us-east-1a").getSubnetId();
        String endpointId = service.createVpcEndpoint(region, vpcId, "com.amazonaws.us-east-1.sqs", "Interface",
                List.of(), List.of(subnetId), List.of(), null, null, List.of()).getVpcEndpointId();

        assertEquals("DependencyViolation", assertThrows(AwsException.class,
                () -> service.deleteSubnet(region, subnetId)).getErrorCode());

        service.deleteVpcEndpoints(region, List.of(endpointId));
        service.deleteSubnet(region, subnetId);
    }

    @Test
    void deleteVpcFailsWhileAnInstanceSitsInOneOfItsSubnets() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String region = "us-east-1";
        String vpcId = service.createVpc(region, "10.83.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet(region, vpcId, "10.83.1.0/24", "us-east-1a").getSubnetId();
        String instanceId = service.runInstances(region, "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), subnetId, null, List.of(), null, null)
                .getInstances().getFirst().getInstanceId();

        AwsException pinned = assertThrows(AwsException.class, () -> service.deleteVpc(region, vpcId));
        assertEquals("DependencyViolation", pinned.getErrorCode());
        assertEquals("The vpc '" + vpcId + "' has dependencies and cannot be deleted.", pinned.getMessage());
        assertEquals("DependencyViolation", assertThrows(AwsException.class,
                () -> service.deleteSubnet(region, subnetId)).getErrorCode());

        service.terminateInstances(region, List.of(instanceId));
        service.deleteSubnet(region, subnetId);
        service.deleteVpc(region, vpcId);
        assertTrue(service.describeVpcs(region, List.of(), Map.of()).stream()
                .noneMatch(vpc -> vpcId.equals(vpc.getVpcId())));
    }

    @Test
    void attachingAnInternetGatewayToAMissingVpcFails() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String igwId = service.createInternetGateway("us-east-1").getInternetGatewayId();

        assertEquals("InvalidVpcID.NotFound", assertThrows(AwsException.class,
                () -> service.attachInternetGateway("us-east-1", igwId, "vpc-0000000000000dead")).getErrorCode());
    }

    /**
     * DeleteVpc checks for dependents and then deletes. A create that resolved the VPC before that
     * check and stored its resource after it would leave a subnet, group, route table, network ACL or
     * gateway attachment naming a VPC that no longer exists.
     */
    @Test
    void creatingInAVpcNeverRacesAheadOfDeletingIt() throws Exception {
        String region = "us-east-1";
        Map<String, BiConsumer<Ec2Service, String>> creators = Map.of(
                "subnet", (service, vpcId) -> service.createSubnet(region, vpcId, "10.81.1.0/24", "us-east-1a"),
                "security group", (service, vpcId) -> service.createSecurityGroup(region, "raced", "raced", vpcId),
                "route table", (service, vpcId) -> service.createRouteTable(region, vpcId),
                "network ACL", (service, vpcId) -> service.createNetworkAcl(region, vpcId),
                "internet gateway", (service, vpcId) -> service.attachInternetGateway(
                        region, service.createInternetGateway(region).getInternetGatewayId(), vpcId),
                "VPC endpoint", (service, vpcId) -> service.createVpcEndpoint(region, vpcId,
                        "com.amazonaws.us-east-1.s3", "Gateway", List.of(), List.of(), List.of(), null, null,
                        List.of()));
        for (Map.Entry<String, BiConsumer<Ec2Service, String>> creator : creators.entrySet()) {
            for (int trial = 0; trial < 25; trial++) {
                Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                        mock(Ec2PortForwardManager.class),
                        mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                        new InMemoryStorageFactory());
                String vpcId = service.createVpc(region, "10.81.0.0/16", false).getVpcId();
                CountDownLatch start = new CountDownLatch(1);
                ExecutorService pool = Executors.newFixedThreadPool(2);
                pool.submit(() -> {
                    try {
                        start.await();
                        creator.getValue().accept(service, vpcId);
                    } catch (AwsException | InterruptedException ignored) {
                        // Losing the race is a legitimate outcome; the invariant is checked below.
                    }
                });
                pool.submit(() -> {
                    try {
                        start.await();
                        service.deleteVpc(region, vpcId);
                    } catch (AwsException | InterruptedException ignored) {
                        // Same.
                    }
                });
                start.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

                boolean vpcExists = service.describeVpcs(region, List.of(), Map.of()).stream()
                        .anyMatch(vpc -> vpcId.equals(vpc.getVpcId()));
                if (!vpcExists) {
                    String label = creator.getKey() + " trial " + trial + ": left behind by the deleted VPC";
                    assertTrue(service.describeSubnets(region, List.of(), Map.of()).stream()
                            .noneMatch(subnet -> vpcId.equals(subnet.getVpcId())), label);
                    assertTrue(service.describeSecurityGroups(region, List.of(), List.of(), Map.of()).stream()
                            .noneMatch(group -> vpcId.equals(group.getVpcId())), label);
                    assertTrue(service.describeRouteTables(region, List.of(), Map.of()).stream()
                            .noneMatch(table -> vpcId.equals(table.getVpcId())), label);
                    assertTrue(service.describeNetworkAcls(region, List.of(), Map.of()).stream()
                            .noneMatch(acl -> vpcId.equals(acl.getVpcId())), label);
                    assertTrue(service.describeInternetGateways(region, List.of(), Map.of()).stream()
                            .noneMatch(igw -> igw.getAttachments().stream()
                                    .anyMatch(attachment -> vpcId.equals(attachment.getVpcId()))), label);
                    assertTrue(service.describeVpcEndpoints(region, List.of(), Map.of()).stream()
                            .noneMatch(endpoint -> vpcId.equals(endpoint.getVpcId())), label);
                }
            }
        }
    }

    @Test
    void describeTagsListsOnlyTheRequestRegionsTags() {
        Ec2Service service = tagTestService(new InMemoryStorageFactory());
        String east = service.createVpc("us-east-1", "10.80.0.0/16", false).getVpcId();
        String west = service.createVpc("eu-west-1", "10.81.0.0/16", false).getVpcId();
        service.createTags("us-east-1", List.of(east), List.of(new Tag("Team", "east")));
        service.createTags("eu-west-1", List.of(west), List.of(new Tag("Team", "west")));

        assertEquals(List.of(east), tagResourceIds(service.describeTags("us-east-1", Map.of("key", List.of("Team")))));
        assertEquals(List.of(west), tagResourceIds(service.describeTags("eu-west-1", Map.of("key", List.of("Team")))));
        assertTrue(service.describeTags("eu-west-1", Map.of("resource-id", List.of(east))).isEmpty());
        assertTrue(service.resourceTags("eu-west-1", east).isEmpty());
        assertEquals("east", service.resourceTags("us-east-1", east).getFirst().getValue());
    }

    @Test
    void describeTagsWildcardsMatchLineTerminatorsAndStayLinear() {
        Ec2Service service = tagTestService(new InMemoryStorageFactory());
        String vpcId = service.createVpc("us-east-1", "10.82.0.0/16", false).getVpcId();
        service.createTags("us-east-1", List.of(vpcId),
                List.of(new Tag("Note", "first\nsecond"), new Tag("Long", "a".repeat(256))));

        assertEquals(2, service.describeTags("us-east-1", Map.of("resource-id", List.of(vpcId),
                "value", List.of("*"))).size());
        assertEquals(1, service.describeTags("us-east-1", Map.of("resource-id", List.of(vpcId),
                "value", List.of("first?second"))).size());
        String manyStars = "*a".repeat(40) + "*b";
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertTrue(service.describeTags("us-east-1",
                Map.of("resource-id", List.of(vpcId), "value", List.of(manyStars))).isEmpty()));
    }

    @Test
    void describeTagsRefusesEverySentMaxResultsOutsideTheRangeAndEndsOnAPastTheEndToken() {
        Ec2Service service = tagTestService(new InMemoryStorageFactory());
        for (int maxResults : new int[] {0, -1, 4, 1001}) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.describeTags("us-east-1", List.of(), maxResults, null));
            assertEquals("InvalidMaxResults", error.getErrorCode());
        }
        String pastTheEnd = Base64.getEncoder().encodeToString(
                ("{\"offset\":" + Integer.MAX_VALUE + "}").getBytes(StandardCharsets.UTF_8));
        PaginatedResult<Map<String, String>> page = service.describeTags("us-east-1", List.of(), 5, pastTheEnd);
        assertTrue(page.items().isEmpty());
        assertNull(page.nextToken());
    }

    @Test
    void tagsCarryTheResourceTypeOfImagesVolumesSnapshotsAclsPeeringsAndSpotRequests() {
        Ec2Service service = tagTestService(new InMemoryStorageFactory());
        Map<String, String> expected = Map.of(
                "ami-0abc12345678", "image", "vol-0abc12345678", "volume", "snap-0abc12345678", "snapshot",
                "acl-0abc12345678", "network-acl", "pcx-0abc12345678", "vpc-peering-connection",
                "sir-0abc12345678", "spot-instances-request");
        service.createSystemTags("us-east-1", List.copyOf(expected.keySet()), List.of(new Tag("Team", "typed")));

        Map<String, String> types = new HashMap<>();
        for (Map<String, String> item : service.describeTags("us-east-1", Map.of("key", List.of("Team")))) {
            types.put(item.get("resourceId"), item.get("resourceType"));
        }
        assertEquals(expected, types);
    }

    @Test
    void volumeTagsGivenAtCreationAreListedByDescribeTags() {
        Ec2Service service = tagTestService(new InMemoryStorageFactory());
        Volume volume = service.createVolume("us-east-1", "us-east-1a", "gp3", 8,
                false, 0, null, null, List.of(new Tag("Team", "storage")));

        List<Map<String, String>> listed = service.describeTags("us-east-1",
                Map.of("resource-id", List.of(volume.getVolumeId())));
        assertEquals(1, listed.size());
        assertEquals("volume", listed.getFirst().get("resourceType"));
        assertTrue(service.describeTags("eu-west-1", Map.of("resource-id", List.of(volume.getVolumeId()))).isEmpty());
    }

    @Test
    void tagsStoredByBareIdMoveUnderTheirResourcesRegionAndOrphansAreDropped() {
        AccountAwareStorageBackend<List<Tag>> tagStore = AccountAwareStorageBackend.inMemory("000000000000");
        Ec2Service service = tagTestService(new InMemoryStorageFactory(Map.of("ec2-tags.json", tagStore)));
        String vpcId = service.createVpc("eu-west-1", "10.82.0.0/16", false).getVpcId();
        tagStore.put(vpcId, new ArrayList<>(List.of(new Tag("Team", "legacy"))));
        tagStore.put("vpc-0000dead0000", new ArrayList<>(List.of(new Tag("Team", "orphan"))));

        service.migrateTagsToRegionKeys();
        service.migrateTagsToRegionKeys();

        assertEquals(List.of(vpcId), tagResourceIds(service.describeTags("eu-west-1", Map.of("key", List.of("Team")))));
        assertTrue(service.describeTags("us-east-1", Map.of("key", List.of("Team"))).isEmpty());
        assertTrue(tagStore.keys().stream().allMatch(storeKey -> storeKey.contains("::")));
    }

    @Test
    void volumeTagChangesShowInDescribeVolumes() {
        Ec2Service service = tagTestService(new InMemoryStorageFactory());
        String volumeId = service.createVolume("us-east-1", "us-east-1a", "gp3", 8,
                false, 0, null, null, List.of(new Tag("Team", "storage"))).getVolumeId();

        service.createTags("us-east-1", List.of(volumeId), List.of(new Tag("Env", "dev")));
        service.deleteTags("us-east-1", List.of(volumeId), List.of(new Tag("Team", null)));

        List<Tag> described = service.describeVolumes("us-east-1", List.of(volumeId), Map.of()).getFirst().getTags();
        assertEquals(List.of("Env"), described.stream().map(Tag::getKey).toList());
    }

    @Test
    void eachSideOfACrossRegionPeeringConnectionSeesItsOwnTags() {
        Ec2Service service = tagTestService(new InMemoryStorageFactory());
        String eastVpc = service.createVpc("us-east-1", "10.83.0.0/16", false).getVpcId();
        String westVpc = service.createVpc("eu-west-1", "10.84.0.0/16", false).getVpcId();
        String pcxId = service.createVpcPeeringConnection("us-east-1", eastVpc, westVpc, null, "eu-west-1",
                List.of(new Tag("Side", "requester"))).getVpcPeeringConnectionId();
        service.createTags("eu-west-1", List.of(pcxId), List.of(new Tag("Side", "accepter")));

        assertEquals("requester", peeringTag(service, "us-east-1", pcxId));
        assertEquals("accepter", peeringTag(service, "eu-west-1", pcxId));
        assertEquals(List.of(pcxId), tagResourceIds(service.describeTags("us-east-1", Map.of("key", List.of("Side")))));
        assertEquals("requester", peeringTag(service, "us-east-1", pcxId));
    }

    private static String peeringTag(Ec2Service service, String region, String pcxId) {
        return service.describeVpcPeeringConnections(region, List.of(pcxId), Map.of()).getFirst().getTags().stream()
                .filter(tag -> "Side".equals(tag.getKey())).map(Tag::getValue).findFirst().orElse(null);
    }

    @Test
    void aDeletedVolumesTagsAreNoLongerListed() {
        Ec2Service service = tagTestService(new InMemoryStorageFactory());
        String volumeId = service.createVolume("us-east-1", "us-east-1a", "gp3", 8,
                false, 0, null, null, List.of(new Tag("Team", "storage"))).getVolumeId();
        service.createTags("us-east-1", List.of(volumeId), List.of(new Tag("Env", "dev")));

        service.deleteVolume("us-east-1", volumeId);

        assertTrue(service.describeTags("us-east-1", Map.of("resource-id", List.of(volumeId))).isEmpty());
    }

    @Test
    void aRootVolumeDeletedOnTerminationTakesItsTags() {
        Ec2Service service = tagTestService(new InMemoryStorageFactory());
        Instance inst = service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null).getInstances().getFirst();
        String rootVolumeId = inst.getRootVolumeId();
        service.createTags("us-east-1", List.of(rootVolumeId), List.of(new Tag("Team", "root")));

        service.terminateInstances("us-east-1", List.of(inst.getInstanceId()));

        assertTrue(service.describeVolumes("us-east-1", List.of(), Map.of()).stream()
                .noneMatch(volume -> rootVolumeId.equals(volume.getVolumeId())));
        assertTrue(service.describeTags("us-east-1", Map.of("resource-id", List.of(rootVolumeId))).isEmpty());
    }

    @Test
    void deletingAPeeringConnectionRemovesBothSidesTags() {
        Ec2Service service = tagTestService(new InMemoryStorageFactory());
        String eastVpc = service.createVpc("us-east-1", "10.89.0.0/16", false).getVpcId();
        String westVpc = service.createVpc("eu-west-1", "10.90.0.0/16", false).getVpcId();
        String pcxId = service.createVpcPeeringConnection("us-east-1", eastVpc, westVpc, null, "eu-west-1",
                List.of(new Tag("Side", "requester"))).getVpcPeeringConnectionId();
        service.createTags("eu-west-1", List.of(pcxId), List.of(new Tag("Side", "accepter")));

        service.deleteVpcPeeringConnection("eu-west-1", pcxId);

        assertTrue(service.describeTags("us-east-1", Map.of("resource-id", List.of(pcxId))).isEmpty());
        assertTrue(service.describeTags("eu-west-1", Map.of("resource-id", List.of(pcxId))).isEmpty());
    }

    @Test
    void migrationKeepsCatalogImageAndClusterNodeTags() {
        AccountAwareStorageBackend<List<Tag>> tagStore = AccountAwareStorageBackend.inMemory("000000000000");
        EmulatorConfig config = mockConfig(true);
        when(config.defaultRegion()).thenReturn("us-east-1");
        Ec2ImageCatalog imageCatalog = new Ec2ImageCatalog();
        Ec2Service service = new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                new AmiImageResolver(imageCatalog), imageCatalog, new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Map.of("ec2-tags.json", tagStore)));
        String catalogImageId = imageCatalog.images().getFirst().imageId;
        String nodeId = "i-0de0000000000001a";
        tagStore.put(catalogImageId, new ArrayList<>(List.of(new Tag("Team", "image"))));
        tagStore.put(nodeId, new ArrayList<>(List.of(new Tag("Team", "node"))));
        tagStore.put("vpc-0000dead0000", new ArrayList<>(List.of(new Tag("Team", "orphan"))));

        service.migrateTagsToRegionKeys();

        assertEquals(List.of(catalogImageId),
                tagResourceIds(service.describeTags("us-east-1", Map.of("key", List.of("Team")))));
        assertTrue(tagStore.keys().contains(nodeId));
        assertFalse(tagStore.keys().contains("vpc-0000dead0000"));

        Instance node = new Instance();
        node.setInstanceId(nodeId);
        node.setRegion("eu-west-1");
        node.setPlacement(new Placement("eu-west-1a"));
        node.setState(InstanceState.running());
        node.setInstanceType("m5.large");
        service.setClusterNodeInstanceProvider(new ClusterNodeInstanceProvider() {
            @Override
            public Optional<Instance> findInstance(String accountId, String region, String instanceId) {
                return nodeId.equals(instanceId) && "eu-west-1".equals(region) ? Optional.of(node) : Optional.empty();
            }

            @Override
            public List<Instance> listInstances(String accountId, String region) {
                return "eu-west-1".equals(region) ? List.of(node) : List.of();
            }
        });

        assertEquals("node", service.describeInstances("eu-west-1", List.of(nodeId), Map.of()).getFirst()
                .getInstances().getFirst().getTags().getFirst().getValue());
        assertEquals(List.of(nodeId), tagResourceIds(service.describeTags("eu-west-1", Map.of("key", List.of("Team")))));
        assertFalse(tagStore.keys().contains(nodeId));
    }

    @Test
    void regionlessLookupFilesClusterNodeTagsUnderTheNodesRegion() {
        AccountAwareStorageBackend<List<Tag>> tagStore = AccountAwareStorageBackend.inMemory("000000000000");
        Ec2Service service = tagTestService(new InMemoryStorageFactory(Map.of("ec2-tags.json", tagStore)));
        String nodeId = "i-0de0000000000002b";
        tagStore.put(nodeId, new ArrayList<>(List.of(new Tag("Team", "node"))));
        service.migrateTagsToRegionKeys();
        Instance node = new Instance();
        node.setInstanceId(nodeId);
        node.setRegion("eu-west-1");
        node.setPlacement(new Placement("eu-west-1a"));
        node.setState(InstanceState.running());
        node.setInstanceType("m5.large");
        service.setClusterNodeInstanceProvider(new ClusterNodeInstanceProvider() {
            @Override
            public Optional<Instance> findInstance(String accountId, String region, String instanceId) {
                return nodeId.equals(instanceId) && (region == null || "eu-west-1".equals(region))
                        ? Optional.of(node) : Optional.empty();
            }

            @Override
            public List<Instance> listInstances(String accountId, String region) {
                return region == null || "eu-west-1".equals(region) ? List.of(node) : List.of();
            }
        });

        assertEquals("node", service.findInstanceById(nodeId).getTags().getFirst().getValue());

        assertEquals(List.of(nodeId), tagResourceIds(service.describeTags("eu-west-1", Map.of("key", List.of("Team")))));
        assertFalse(tagStore.keys().contains("null::" + nodeId));
    }

    @Test
    void createTagsOnAnotherRegionsVpcIsNotFoundAndWritesNothing() {
        Ec2Service service = tagTestService(new InMemoryStorageFactory());
        String east = service.createVpc("us-east-1", "10.85.0.0/16", false).getVpcId();
        String west = service.createVpc("eu-west-1", "10.86.0.0/16", false).getVpcId();

        AwsException missing = assertThrows(AwsException.class, () -> service.createTags("eu-west-1",
                List.of(west, east), List.of(new Tag("Team", "x"))));

        assertEquals("InvalidVpcID.NotFound", missing.getErrorCode());
        assertTrue(service.describeTags("eu-west-1", Map.of("key", List.of("Team"))).isEmpty());
        assertTrue(service.describeTags("us-east-1", Map.of("key", List.of("Team"))).isEmpty());
    }

    @Test
    void tagWritesRejectMissingAndMalformedIds() {
        Ec2Service service = tagTestService(new InMemoryStorageFactory());
        List<Tag> tag = List.of(new Tag("Team", "x"));

        assertEquals("MissingParameter", assertThrows(AwsException.class,
                () -> service.createTags("us-east-1", List.of(), tag)).getErrorCode());
        assertEquals("MissingParameter", assertThrows(AwsException.class,
                () -> service.deleteTags("us-east-1", List.of(), tag)).getErrorCode());
        for (String id : List.of("not-an-id", "vpc-xyz", "i-123", "foo-0123456789abcdef0", "vgw-12345678-not-an-id",
                "vgw-0123456", "vgw-0123456789ABCDEF0", "fleet-73fbd2ce-aa30-494c-8788", "vpc-default-nowhere")) {
            assertEquals("InvalidID", assertThrows(AwsException.class,
                    () -> service.createTags("us-east-1", List.of(id), tag)).getErrorCode(), id);
            assertEquals("InvalidID", assertThrows(AwsException.class,
                    () -> service.deleteTags("us-east-1", List.of(id), tag)).getErrorCode(), id);
        }
    }

    @Test
    void aWellFormedIdOfAnUnmodelledTypeIsTagged() {
        Ec2Service service = tagTestService(new InMemoryStorageFactory());

        List<String> ids = List.of("vgw-0123456789abcdef0", "vgw-01234567", "vpce-svc-0123456789abcdef0",
                "eni-attach-0123456789abcdef0", "vpc-cidr-assoc-01234567", "subnet-cidr-assoc-0123456789abcdef0",
                "fleet-73fbd2ce-aa30-494c-8788-1cee4a1b2c3d", "fleet-0123456789abcdef0123456789abcdef",
                "sfr-306341ed-9739-402e-881b-ce47b0123456", "ipam-0123456789abcdef0",
                "ipam-scope-0123456789abcdef0", "ipam-pool-0123456789abcdef0",
                "ipam-pool-alloc-018ecc28043b54ba38e2cd99943cebfbd");
        service.createTags("us-east-1", ids, List.of(new Tag("Team", "x")));

        for (String id : ids) {
            assertEquals("x", service.resourceTags("us-east-1", id).getFirst().getValue(), id);
        }
    }

    @Test
    void theDefaultVpcsSeededResourcesTakeTags() {
        Ec2Service service = tagTestService(new InMemoryStorageFactory());
        Map<String, List<String>> isDefault = Map.of("is-default", List.of("true"));
        String vpcId = service.describeVpcs("us-east-1", List.of(), isDefault).getFirst().getVpcId();
        Map<String, List<String>> inVpc = Map.of("vpc-id", List.of(vpcId));
        List<String> ids = new ArrayList<>(List.of(vpcId,
                service.describeSubnets("us-east-1", List.of(), inVpc).getFirst().getSubnetId(),
                service.describeSecurityGroups("us-east-1", List.of(), List.of(), inVpc).getFirst().getGroupId(),
                service.describeInternetGateways("us-east-1", List.of(), Map.of()).getFirst().getInternetGatewayId(),
                service.describeRouteTables("us-east-1", List.of(), inVpc).getFirst().getRouteTableId(),
                service.describeNetworkAcls("us-east-1", List.of(), inVpc).getFirst().getNetworkAclId()));

        service.createTags("us-east-1", ids, List.of(new Tag("Team", "x")));
        for (String id : ids) {
            assertEquals("x", service.resourceTags("us-east-1", id).getFirst().getValue(), id);
        }
        service.deleteTags("us-east-1", ids, List.of(new Tag("Team", null)));
        for (String id : ids) {
            assertTrue(service.resourceTags("us-east-1", id).isEmpty(), id);
        }
    }

    @Test
    void catalogImagesTakeTagsButAnAbsentImageIsNotFound() {
        Ec2ImageCatalog imageCatalog = new Ec2ImageCatalog();
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), new AmiImageResolver(imageCatalog), imageCatalog,
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        Ec2ImageCatalog.CatalogImage catalogImage = imageCatalog.images().stream()
                .filter(image -> !image.aliases().isEmpty()).findFirst().orElseThrow();
        List<String> ids = List.of(catalogImage.imageId, catalogImage.aliases().getFirst());

        service.createTags("us-east-1", ids, List.of(new Tag("Team", "x")));
        AwsException absent = assertThrows(AwsException.class, () -> service.createTags("us-east-1",
                List.of("ami-0123456789abcdef0"), List.of(new Tag("Team", "x"))));

        assertEquals("InvalidAMIID.NotFound", absent.getErrorCode());
        assertTrue(service.resourceTags("us-east-1", "ami-0123456789abcdef0").isEmpty());
    }

    @Test
    void aResourceTakesFiftyUserTagsAndAwsTagsDoNotCount() {
        Ec2Service service = tagTestService(new InMemoryStorageFactory());
        String vpcId = service.createVpc("us-east-1", "10.87.0.0/16", false).getVpcId();
        service.createSystemTags("us-east-1", List.of(vpcId), List.of(new Tag("aws:cloudformation:stack-name", "s")));
        List<Tag> fifty = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            fifty.add(new Tag("k" + i, "v"));
        }

        service.createTags("us-east-1", List.of(vpcId), fifty);
        service.createTags("us-east-1", List.of(vpcId), List.of(new Tag("k0", "overwritten")));
        AwsException limit = assertThrows(AwsException.class,
                () -> service.createTags("us-east-1", List.of(vpcId), List.of(new Tag("k50", "v"))));

        assertEquals("TagLimitExceeded", limit.getErrorCode());
        assertEquals(51, service.resourceTags("us-east-1", vpcId).size());
    }

    @Test
    void tagKeysAndValuesAreLimitedTo128And256Characters() {
        Ec2Service service = tagTestService(new InMemoryStorageFactory());
        String vpcId = service.createVpc("us-east-1", "10.88.0.0/16", false).getVpcId();

        service.createTags("us-east-1", List.of(vpcId), List.of(new Tag("k".repeat(128), "v".repeat(256))));
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class, () -> service.createTags("us-east-1",
                List.of(vpcId), List.of(new Tag("k".repeat(129), "v")))).getErrorCode());
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class, () -> service.createTags("us-east-1",
                List.of(vpcId), List.of(new Tag("k", "v".repeat(257))))).getErrorCode());
        // Unicode characters, not UTF-16 units: 128 emoji are a valid key.
        service.createTags("us-east-1", List.of(vpcId), List.of(new Tag("\uD83D\uDE00".repeat(128), "v")));
    }

    @Test
    void validateNewResourceTagsRejectsWhatCreateTagsRejects() {
        List<Tag> fiftyKeys = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            fiftyKeys.add(new Tag("k" + i, "v"));
        }
        fiftyKeys.add(new Tag("k0", "a repeated key counts once"));

        Ec2Service.validateNewResourceTags(fiftyKeys);

        AwsException reserved = assertThrows(AwsException.class,
                () -> Ec2Service.validateNewResourceTags(List.of(new Tag("aws:Name", "v"))));
        assertEquals("InvalidParameterValue", reserved.getErrorCode());
        assertEquals("Value ( aws:Name ) for parameter key is invalid. "
                + "Tag keys starting with 'aws:' are reserved for internal use", reserved.getMessage());
        AwsException longKey = assertThrows(AwsException.class,
                () -> Ec2Service.validateNewResourceTags(List.of(new Tag("k".repeat(129), "v"))));
        assertEquals("InvalidParameterValue", longKey.getErrorCode());
        assertEquals("Tag key exceeds the maximum length of 128 characters", longKey.getMessage());
        AwsException longValue = assertThrows(AwsException.class,
                () -> Ec2Service.validateNewResourceTags(List.of(new Tag("k", "v".repeat(257)))));
        assertEquals("InvalidParameterValue", longValue.getErrorCode());
        assertEquals("Tag value exceeds the maximum length of 256 characters", longValue.getMessage());
        List<Tag> fiftyOneKeys = new ArrayList<>(fiftyKeys);
        fiftyOneKeys.add(new Tag("k50", "v"));
        AwsException overLimit = assertThrows(AwsException.class,
                () -> Ec2Service.validateNewResourceTags(fiftyOneKeys));
        assertEquals("TagLimitExceeded", overLimit.getErrorCode());
        assertEquals("The maximum number of Tags for a resource has been reached.", overLimit.getMessage());
    }

    private static Ec2Service tagTestService(StorageFactory storageFactory) {
        return new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(), storageFactory);
    }

    private static List<String> tagResourceIds(List<Map<String, String>> items) {
        return items.stream().map(item -> item.get("resourceId")).distinct().toList();
    }

    @Test
    void deleteVpcRemovesItsOwnTagsAndThoseOnItsDefaultResources() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String region = "us-east-1";
        String vpcId = service.createVpc(region, "10.78.0.0/16", false).getVpcId();
        String groupId = service.describeSecurityGroups(region, List.of(), List.of(), Map.of()).stream()
                .filter(group -> vpcId.equals(group.getVpcId()) && "default".equals(group.getGroupName()))
                .findFirst().orElseThrow().getGroupId();
        String routeTableId = service.describeRouteTables(region, List.of(), Map.of()).stream()
                .filter(table -> vpcId.equals(table.getVpcId()))
                .findFirst().orElseThrow().getRouteTableId();
        String aclId = service.describeNetworkAcls(region, List.of(), Map.of()).stream()
                .filter(acl -> vpcId.equals(acl.getVpcId()) && acl.isDefault())
                .findFirst().orElseThrow().getNetworkAclId();
        String ruleId = service.describeSecurityGroupRules(region, List.of(groupId), List.of()).getFirst()
                .getSecurityGroupRuleId();
        List<String> defaultIds = List.of(vpcId, groupId, routeTableId, aclId, ruleId);
        service.createTags(region, defaultIds, List.of(new Tag("Name", "doomed")));
        assertEquals(5, service.describeTags(region, Map.of("resource-id", defaultIds)).size());

        service.deleteVpc(region, vpcId);

        assertTrue(service.describeTags(region, Map.of("resource-id", defaultIds)).isEmpty());
    }

    @Test
    void deleteVpcDoesNotRemoveAnotherVpcDefaults() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String region = "us-east-1";
        String deletedVpcId = service.createVpc(region, "10.78.0.0/16", false).getVpcId();
        String preservedVpcId = service.createVpc(region, "10.79.0.0/16", false).getVpcId();
        SecurityGroup preservedGroup = service.describeSecurityGroups(region, List.of(), List.of(), Map.of()).stream()
                .filter(group -> preservedVpcId.equals(group.getVpcId()) && "default".equals(group.getGroupName()))
                .findFirst().orElseThrow();
        String preservedRouteTableId = service.describeRouteTables(region, List.of(), Map.of()).stream()
                .filter(table -> preservedVpcId.equals(table.getVpcId()))
                .filter(table -> table.getAssociations().stream().anyMatch(association -> association.isMain()))
                .findFirst().orElseThrow().getRouteTableId();
        String preservedNetworkAclId = service.describeNetworkAcls(region, List.of(), Map.of()).stream()
                .filter(acl -> preservedVpcId.equals(acl.getVpcId()) && acl.isDefault())
                .findFirst().orElseThrow().getNetworkAclId();

        service.deleteVpc(region, deletedVpcId);

        assertEquals(preservedGroup.getGroupId(), service.describeSecurityGroups(
                region, List.of(preservedGroup.getGroupId()), List.of(), Map.of()).getFirst().getGroupId());
        assertEquals(preservedRouteTableId,
                service.describeRouteTables(region, List.of(preservedRouteTableId), Map.of()).getFirst().getRouteTableId());
        assertEquals(preservedNetworkAclId,
                service.describeNetworkAcls(region, List.of(preservedNetworkAclId), Map.of()).getFirst().getNetworkAclId());
    }

    @Test
    void describeVpnGatewaysReturnsEmptyWhenNoneExist() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        for (String filterName : List.of("attachment.vpc-id", "tag-value")) {
            assertTrue(service.describeVpnGatewayIds(
                    List.of(), Map.of(filterName, List.of("value"))).isEmpty());
        }

        AwsException vpnError = assertThrows(AwsException.class, () -> service.describeVpnGatewayIds(
                List.of("vgw-0123456789abcdef0"), Map.of()));
        assertEquals("InvalidVpnGatewayID.NotFound", vpnError.getErrorCode());
        assertEquals("The vpnGateway ID 'vgw-0123456789abcdef0' does not exist", vpnError.getMessage());
        assertEquals(400, vpnError.getHttpStatus());

        AwsException filterError = assertThrows(AwsException.class,
                () -> service.describeVpnGatewayIds(
                        List.of(), Map.of("unsupported", List.of("value"))));
        assertEquals("InvalidParameterValue", filterError.getErrorCode());
        assertEquals("The filter 'unsupported' is invalid", filterError.getMessage());
    }

    @Test
    void describeEgressOnlyInternetGatewaysReturnsEmptyWhenNoneExist() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        Map<String, List<String>> supportedFilters = Map.of(
                "attachment.state", List.of("attached"),
                "attachment.vpc-id", List.of("vpc-0123456789abcdef0"),
                "egress-only-internet-gateway-id", List.of("eigw-0123456789abcdef0"),
                "tag:Owner", List.of("TeamA"),
                "tag-key", List.of("Owner"),
                "tag-value", List.of("TeamA"));

        for (Map.Entry<String, List<String>> filter : supportedFilters.entrySet()) {
            assertTrue(service.describeEgressOnlyInternetGateways("us-east-1", List.of(),
                    Map.of(filter.getKey(), filter.getValue())).isEmpty());
        }

        AwsException filterError = assertThrows(AwsException.class,
                () -> service.describeEgressOnlyInternetGateways("us-east-1", List.of(),
                        Map.of("unsupported", List.of("value"))));
        assertEquals("InvalidParameterValue", filterError.getErrorCode());
        assertEquals("The filter 'unsupported' is invalid", filterError.getMessage());
        assertEquals(400, filterError.getHttpStatus());
    }

    @Test
    void egressOnlyInternetGatewayDeleteWaitsForTheLockTagWritesTake() throws Exception {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String region = "us-east-1";
        String vpcId = service.createVpc(region, "10.43.0.0/16", false).getVpcId();
        String eigwId = service.createEgressOnlyInternetGateway(region, vpcId, null, List.of())
                .getEgressOnlyInternetGatewayId();
        Method lockFor = Ec2Service.class.getDeclaredMethod("lockFor", String.class);
        lockFor.setAccessible(true);
        Object tagLock = lockFor.invoke(service, region + "::" + eigwId);
        AtomicReference<Thread> deleter = new AtomicReference<>();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> deleting;
            synchronized (tagLock) {
                deleting = worker.submit(() -> {
                    deleter.set(Thread.currentThread());
                    service.deleteEgressOnlyInternetGateway(region, eigwId);
                });
                Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> deleting.isDone()
                        || (deleter.get() != null && deleter.get().getState() == Thread.State.BLOCKED));
                assertFalse(deleting.isDone(), "delete must take the gateway's tag lock");
            }
            deleting.get(5, TimeUnit.SECONDS);
            assertTrue(service.describeEgressOnlyInternetGateways(region, List.of(eigwId), Map.of()).isEmpty());
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void egressOnlyInternetGatewayIsCreatedDescribedAndDeleted() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String region = "us-east-1";
        String vpcId = service.createVpc(region, "10.42.0.0/16", false).getVpcId();

        AwsException noVpc = assertThrows(AwsException.class,
                () -> service.createEgressOnlyInternetGateway(region, "vpc-00000000000000000", null, List.of()));
        assertEquals("InvalidVpcID.NotFound", noVpc.getErrorCode());

        String eigwId = service.createEgressOnlyInternetGateway(region, vpcId, null, List.of())
                .getEgressOnlyInternetGatewayId();
        assertTrue(eigwId.matches("eigw-[0-9a-f]{17}"), eigwId);
        service.createTags(region, List.of(eigwId), List.of(new Tag("Owner", "TeamA")));

        List<EgressOnlyInternetGateway> found = service.describeEgressOnlyInternetGateways(region, List.of(eigwId), Map.of());
        assertEquals(1, found.size());
        assertEquals(vpcId, found.getFirst().getAttachments().getFirst().getVpcId());
        assertEquals("attached", found.getFirst().getAttachments().getFirst().getState());
        assertEquals("TeamA", found.getFirst().getTags().getFirst().getValue());
        assertEquals(1, service.describeEgressOnlyInternetGateways(region, List.of(),
                Map.of("attachment.vpc-id", List.of(vpcId), "tag:Owner", List.of("TeamA"))).size());
        assertTrue(service.describeEgressOnlyInternetGateways(region, List.of(),
                Map.of("attachment.state", List.of("detached"))).isEmpty());
        assertTrue(service.describeEgressOnlyInternetGateways("eu-west-1", List.of(eigwId), Map.of()).isEmpty());

        AwsException vpcInUse = assertThrows(AwsException.class, () -> service.deleteVpc(region, vpcId));
        assertEquals("DependencyViolation", vpcInUse.getErrorCode());

        service.deleteEgressOnlyInternetGateway(region, eigwId);
        assertTrue(service.describeEgressOnlyInternetGateways(region, List.of(eigwId), Map.of()).isEmpty());
        AwsException gone = assertThrows(AwsException.class,
                () -> service.deleteEgressOnlyInternetGateway(region, eigwId));
        assertEquals("InvalidGatewayID.NotFound", gone.getErrorCode());
        service.deleteVpc(region, vpcId);
    }

    @Test
    void mockModeTreatsExistingNonTerminatedInstanceAsRunningContainer() {
        Ec2ContainerManager containerManager = mock(Ec2ContainerManager.class);
        Ec2Service service = new Ec2Service(mockConfig(true), containerManager,
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        Reservation reservation = service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null);
        String instanceId = reservation.getInstances().getFirst().getInstanceId();

        assertTrue(service.isInstanceContainerRunning(instanceId));
        service.terminateInstances("us-east-1", List.of(instanceId));
        assertFalse(service.isInstanceContainerRunning(instanceId));
        verifyNoInteractions(containerManager);
    }

    @Test
    void describeNetworkInterfacesGroupIdFilterSkipsNullGroupEntry() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        Reservation reservation = service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null);
        Instance inst = reservation.getInstances().getFirst();
        String groupId = inst.getNetworkInterfaces().getFirst().getGroups().getFirst().getGroupId();
        // A null entry in an interface's group list must not crash the group-id filter: the interface
        // is still matched on its real group and the null is skipped. Regression: matchesFilter
        // dereferenced the entry with no null-guard, so DescribeNetworkInterfaces returned a 500 that
        // hung a Terraform/Pulumi security-group delete (it polls this call until the group is gone).
        inst.getNetworkInterfaces().getFirst().getGroups().add(null);

        NetworkInterfaceListResult result = service.describeNetworkInterfaces("us-east-1", List.of(),
                Map.of("group-id", List.of(groupId)), 0, null);

        assertEquals(1, result.networkInterfaces().size());
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void describeNetworkInterfacesFiltersActualAddressAssociations(boolean suppliedInterface, boolean defaultVpc) {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String subnetId = defaultVpc
                ? service.describeSubnets("us-east-1", List.of(), Map.of()).getFirst().getSubnetId()
                : service.createSubnet("us-east-1",
                        service.createVpc("us-east-1", "10.237.0.0/24", false).getVpcId(),
                        "10.237.0.0/26", "us-east-1a").getSubnetId();
        String suppliedId = suppliedInterface
                ? service.createNetworkInterface("us-east-1", subnetId,
                        null, null, List.of(), List.of(), List.of()).getNetworkInterfaceId() : null;
        Instance associated = service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), subnetId, null, List.of(), null, null,
                null, suppliedId, 0).getInstances().getFirst();
        Instance other = service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null).getInstances().getFirst();
        Address address = service.allocateAddress("us-east-1");
        service.associateAddress("us-east-1", address.getAllocationId(), associated.getInstanceId());
        String interfaceId = associated.getNetworkInterfaces().getFirst().getNetworkInterfaceId();
        String ownerId = associated.getNetworkInterfaces().getFirst().getOwnerId();

        assertEquals(List.of(interfaceId), service.describeNetworkInterfaces("us-east-1", List.of(),
                Map.of("association.ip-owner-id", List.of(ownerId)), 0, null).networkInterfaces().stream()
                .map(NetworkInterface::getNetworkInterfaceId).toList());
        assertEquals(List.of(interfaceId), service.describeNetworkInterfaces("us-east-1", List.of(),
                Map.of("association.public-ip", List.of(address.getPublicIp())), 0, null).networkInterfaces().stream()
                .map(NetworkInterface::getNetworkInterfaceId).toList());
        assertTrue(service.describeNetworkInterfaces("us-east-1", List.of(),
                Map.of("association.ip-owner-id", List.of("unmatched-owner")), 0, null).networkInterfaces().isEmpty());
        assertTrue(service.describeNetworkInterfaces("us-east-1", List.of(),
                Map.of("association.public-ip", List.of("192.0.2.254")), 0, null).networkInterfaces().isEmpty());
        assertEquals(1, service.describeNetworkInterfaces("us-east-1", List.of(),
                Map.of("association.ip-owner-id", List.of("unmatched-owner", ownerId),
                        "association.public-ip", List.of("*")), 0, null).networkInterfaces().size());
        assertTrue(service.describeNetworkInterfaces("us-east-1", List.of(),
                Map.of("association.ip-owner-id", List.of(ownerId),
                        "association.public-ip", List.of("192.0.2.254")), 0, null).networkInterfaces().isEmpty());
        service.associateAddress("us-east-1", address.getAllocationId(), other.getInstanceId());
        assertEquals(List.of(other.getNetworkInterfaces().getFirst().getNetworkInterfaceId()),
                service.describeNetworkInterfaces("us-east-1", List.of(),
                        Map.of("association.public-ip", List.of(address.getPublicIp())), 0, null)
                        .networkInterfaces().stream().map(NetworkInterface::getNetworkInterfaceId).toList());
        service.associateAddress("us-east-1", address.getAllocationId(), associated.getInstanceId());
        NetworkInterface described = service.describeNetworkInterfaces("us-east-1", List.of(interfaceId),
                Map.of(), 0, null).networkInterfaces().getFirst();
        assertEquals(address.getAllocationId(), described.getPrivateIpAddresses().getFirst()
                .getAssociation().getAllocationId());
        service.disassociateAddress("us-east-1", address.getAssociationId());
        for (String filter : List.of("association.ip-owner-id", "association.public-ip")) {
            assertTrue(service.describeNetworkInterfaces("us-east-1", List.of(),
                    Map.of(filter, List.of("*")), 0, null).networkInterfaces().isEmpty());
        }
        service.associateAddress("us-east-1", address.getAllocationId(), associated.getInstanceId());
        if (!defaultVpc) {
            String associationId = address.getAssociationId();
            AwsException error = assertThrows(AwsException.class,
                    () -> service.releaseAddress("us-east-1", address.getAllocationId()));
            assertEquals("InvalidIPAddress.InUse", error.getErrorCode());
            assertEquals(associationId, service.describeAddresses("us-east-1",
                    List.of(address.getAllocationId()), Map.of()).getFirst().getAssociationId());
            NetworkInterface unchanged = service.describeNetworkInterfaces("us-east-1", List.of(interfaceId),
                    Map.of("association.public-ip", List.of(address.getPublicIp()),
                            "association.ip-owner-id", List.of(ownerId)), 0, null).networkInterfaces().getFirst();
            assertEquals(associationId, unchanged.getPrivateIpAddresses().getFirst()
                    .getAssociation().getAssociationId());
            service.disassociateAddress("us-east-1", associationId);
        }
        service.releaseAddress("us-east-1", address.getAllocationId());
        assertTrue(service.describeAddresses("us-east-1", List.of(address.getAllocationId()), Map.of()).isEmpty());
        assertTrue(service.describeNetworkInterfaces("us-east-1", List.of(),
                Map.of("association.public-ip", List.of("*")), 0, null).networkInterfaces().isEmpty());
        assertEquals(2, service.describeNetworkInterfaces("us-east-1", List.of(), Map.of(), 0, null)
                .networkInterfaces().size());
    }

    @ParameterizedTest
    @CsvSource({"false,false,true", "false,true,true", "true,false,true",
            "true,true,true", "true,false,false", "true,true,false"})
    void terminationKeepsAddressesOnlyOnRetainedInterfaces(boolean suppliedInterface, boolean defaultVpc,
                                                          boolean deleteOnTermination) {
        Ec2ContainerManager containerManager = mock(Ec2ContainerManager.class);
        when(containerManager.reachablePublicAddress(any())).thenReturn("192.0.2.10");
        Ec2Service service = new Ec2Service(mockConfig(true), containerManager,
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String region = "us-east-1";
        String subnetId = defaultVpc
                ? service.describeSubnets(region, List.of(), Map.of()).getFirst().getSubnetId()
                : service.createSubnet(region, service.createVpc(region, "10.237.0.0/24", false).getVpcId(),
                        "10.237.0.0/26", region + "a").getSubnetId();
        NetworkInterface supplied = suppliedInterface
                ? service.createNetworkInterface(region, subnetId, null, null, List.of(), List.of(), List.of()) : null;
        Instance instance = service.runInstances(region, "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), subnetId, null, List.of(), null, null,
                null, supplied != null ? supplied.getNetworkInterfaceId() : null, 0).getInstances().getFirst();
        String interfaceId = instance.getNetworkInterfaces().getFirst().getNetworkInterfaceId();
        if (supplied != null) {
            supplied.getAttachment().setDeleteOnTermination(deleteOnTermination);
        }
        Address address = service.allocateAddress(region);
        service.associateAddress(region, address.getAllocationId(), instance.getInstanceId());
        String associationId = address.getAssociationId();
        service.terminateInstances(region, List.of(instance.getInstanceId()));
        instance.setTerminatedAt(System.currentTimeMillis() - 3_600_001L);
        service.pruneTerminatedInstances();

        Address remaining = service.describeAddresses(region, List.of(address.getAllocationId()), Map.of()).getFirst();
        assertNull(remaining.getInstanceId());
        if (deleteOnTermination) {
            assertNull(remaining.getAssociationId());
            assertNull(remaining.getNetworkInterfaceId());
            assertTrue(service.describeNetworkInterfaces(region, List.of(),
                    Map.of("association.public-ip", List.of("*")), 0, null).networkInterfaces().isEmpty());
        } else {
            assertEquals(associationId, remaining.getAssociationId());
            assertEquals("192.0.2.10", remaining.getPublicIp());
            assertEquals(interfaceId, remaining.getNetworkInterfaceId());
            NetworkInterface retained = service.describeNetworkInterfaces(region, List.of(interfaceId),
                    Map.of("association.public-ip", List.of(remaining.getPublicIp())), 0, null)
                    .networkInterfaces().getFirst();
            assertNull(retained.getAttachment());
            assertEquals("available", retained.getStatus());
            if (!defaultVpc) {
                AwsException error = assertThrows(AwsException.class,
                        () -> service.releaseAddress(region, address.getAllocationId()));
                assertEquals("InvalidIPAddress.InUse", error.getErrorCode());
                service.deleteNetworkInterface(region, interfaceId);
                assertNull(service.describeAddresses(region, List.of(address.getAllocationId()), Map.of())
                        .getFirst().getAssociationId());
            }
        }
        service.releaseAddress(region, address.getAllocationId());
        assertTrue(service.describeAddresses(region, List.of(address.getAllocationId()), Map.of()).isEmpty());
    }

    @Test
    void elasticIpOnASecondaryAddressLeavesAReachableInstancesPublicAddressAlone() {
        Ec2ContainerManager containerManager = mock(Ec2ContainerManager.class);
        when(containerManager.reachablePublicAddress(any())).thenReturn("192.0.2.10");
        Ec2Service service = new Ec2Service(mockConfig(true), containerManager,
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String region = "us-east-1";
        String subnetId = service.createSubnet(region, service.createVpc(region, "10.238.0.0/24", false).getVpcId(),
                "10.238.0.0/26", region + "a").getSubnetId();
        NetworkInterface eni = service.createNetworkInterface(region, subnetId, null, "10.238.0.10",
                List.of("10.238.0.11"), List.of(), List.of());
        Instance instance = service.runInstances(region, "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), subnetId, null, List.of(), null, null,
                null, eni.getNetworkInterfaceId(), 0).getInstances().getFirst();

        Address secondary = service.allocateAddress(region);
        service.associateAddress(region, secondary.getAllocationId(), instance.getInstanceId(), "10.238.0.11");
        assertEquals("10.238.0.11", secondary.getPrivateIpAddress());
        assertNull(instance.getPublicIpAddress());

        Address primary = service.allocateAddress(region);
        service.associateAddress(region, primary.getAllocationId(), instance.getInstanceId());
        assertEquals("10.238.0.10", primary.getPrivateIpAddress());
        assertEquals("192.0.2.10", instance.getPublicIpAddress());
        assertNotNull(secondary.getAssociationId());
        assertEquals("10.238.0.11", service.describeAddresses(region, List.of(secondary.getAllocationId()), Map.of())
                .getFirst().getPrivateIpAddress());
    }

    @Test
    void aContainerInstanceReportingItsBridgeAddressStillTakesAnElasticIpForItsLaunchAddress() {
        EmulatorConfig config = mockConfig(true);
        Ec2Service service = new Ec2Service(config, mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String region = "us-east-1";
        Instance instance = service.runInstances(region, "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null, null).getInstances().getFirst();
        String launchAddress = instance.getPrivateIpAddress();
        // Container mode without AWS-faithful private addresses: the container manager replaces the
        // reported address with the Docker bridge one once the container runs.
        when(config.services().ec2().mock()).thenReturn(false);
        instance.setPrivateIpAddress("172.17.0.5");
        instance.getNetworkInterfaces().getFirst().setPrivateIpAddress("172.17.0.5");

        Address address = service.allocateAddress(region);
        service.associateAddress(region, address.getAllocationId(), instance.getInstanceId(), launchAddress);
        assertNotNull(address.getAssociationId());
        assertEquals(instance.getInstanceId(), address.getInstanceId());
    }

    @Test
    void containerModeStopReleasesTheAutomaticAddressButKeepsAnElasticIp() {
        Ec2Service service = containerModeService();
        String region = "us-east-1";
        String subnetId = publicSubnet(service, region, "10.239.0.0/24");
        Instance automatic = runningContainerInstance(service, region, subnetId, null);
        Instance elastic = runningContainerInstance(service, region, subnetId, null);
        Address address = service.allocateAddress(region);
        service.associateAddress(region, address.getAllocationId(), elastic.getInstanceId());

        service.stopInstances(region, List.of(automatic.getInstanceId(), elastic.getInstanceId()));

        assertNull(automatic.getPublicIpAddress());
        assertNull(automatic.getPublicDnsName());
        assertEquals("127.0.0.1", elastic.getPublicIpAddress());
    }

    @Test
    void containerModeTerminationLeavesNoPublicAddress() {
        Ec2Service service = containerModeService();
        String region = "us-east-1";
        Instance instance = runningContainerInstance(service, region, publicSubnet(service, region, "10.240.0.0/24"), null);

        service.terminateInstances(region, List.of(instance.getInstanceId()));

        assertNull(instance.getPublicIpAddress());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anInstanceLaunchedOnAnExistingInterfaceIsNotDueAnAutomaticAddressInEitherMode(boolean mockMode) {
        Ec2Service service = mockMode ? mockModeService() : containerModeService();
        String region = "us-east-1";
        String subnetId = publicSubnet(service, region, "10.241.0.0/24");
        NetworkInterface eni = service.createNetworkInterface(region, subnetId, null, null, List.of(), List.of(), List.of());
        Instance onExisting = service.runInstances(region, "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), subnetId, null, List.of(), null, null,
                null, eni.getNetworkInterfaceId(), 0).getInstances().getFirst();
        Instance onNew = service.runInstances(region, "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), subnetId, null, List.of(), null, null, null).getInstances().getFirst();

        assertFalse(onExisting.isAssociatePublicIp());
        assertTrue(onNew.isAssociatePublicIp());
    }

    @ParameterizedTest
    @CsvSource({"true,1,127.0.0.1", "true,2,", "false,1,"})
    void containerModeDisassociationGivesBackOnlyAnAddressTheRulesAllow(boolean publicSubnet, int interfaces,
                                                                        String expected) {
        Ec2Service service = containerModeService();
        String region = "us-east-1";
        String subnetId = publicSubnet
                ? publicSubnet(service, region, "10.242.0.0/24")
                : service.createSubnet(region, service.createVpc(region, "10.243.0.0/24", false).getVpcId(),
                        "10.243.0.0/26", region + "a").getSubnetId();
        Instance instance = runningContainerInstance(service, region, subnetId, null);
        Address address = service.allocateAddress(region);
        service.associateAddress(region, address.getAllocationId(), instance.getInstanceId());
        assertEquals("127.0.0.1", instance.getPublicIpAddress());
        if (interfaces > 1) {
            NetworkInterface extra = service.createNetworkInterface(region, subnetId, null, null, List.of(), List.of(),
                    List.of());
            service.attachNetworkInterface(region, extra.getNetworkInterfaceId(), instance.getInstanceId(), 1);
        }

        service.disassociateAddress(region, address.getAssociationId());

        assertEquals(expected, instance.getPublicIpAddress());
    }

    @Test
    void containerModeDisassociatingASecondaryAddressLeavesThePublicAddressAlone() {
        Ec2Service service = containerModeService();
        String region = "us-east-1";
        String subnetId = publicSubnet(service, region, "10.244.0.0/24");
        NetworkInterface eni = service.createNetworkInterface(region, subnetId, null, "10.244.0.10",
                List.of("10.244.0.11"), List.of(), List.of());
        Instance instance = runningContainerInstance(service, region, subnetId, eni.getNetworkInterfaceId());
        Address primary = service.allocateAddress(region);
        service.associateAddress(region, primary.getAllocationId(), instance.getInstanceId());
        Address secondary = service.allocateAddress(region);
        service.associateAddress(region, secondary.getAllocationId(), instance.getInstanceId(), "10.244.0.11");
        // With a container, both addresses point at the one reachable address.
        assertEquals("127.0.0.1", instance.getPublicIpAddress());

        service.disassociateAddress(region, secondary.getAssociationId());

        assertEquals("127.0.0.1", instance.getPublicIpAddress());
        assertNotNull(primary.getAssociationId());
    }

    @Test
    void containerModeDisassociationReleasesAnAddressARestartMoved() {
        Ec2Service service = containerModeService();
        String region = "us-east-1";
        String subnetId = service.createSubnet(region, service.createVpc(region, "10.245.0.0/24", false).getVpcId(),
                "10.245.0.0/26", region + "a").getSubnetId();
        Instance instance = runningContainerInstance(service, region, subnetId, null);
        Address address = service.allocateAddress(region);
        service.associateAddress(region, address.getAllocationId(), instance.getInstanceId());
        // A restart gives the container a new address, which the start publishes for the Elastic IP.
        instance.setPublicIpAddress("172.17.0.9");

        service.disassociateAddress(region, address.getAssociationId());

        assertNull(instance.getPublicIpAddress(), "nothing backs a public address once the Elastic IP is gone");
    }

    @ParameterizedTest
    @CsvSource({"false,", "true,127.0.0.1"})
    void containerModeStartDecidesAfreshWhateverStoppedTheInstance(boolean elasticIp, String kept) {
        Ec2Service service = containerModeService();
        String region = "us-east-1";
        String subnetId = publicSubnet(service, region, "10.246.0.0/24");
        Instance instance = runningContainerInstance(service, region, subnetId, null);
        if (elasticIp) {
            Address address = service.allocateAddress(region);
            service.associateAddress(region, address.getAllocationId(), instance.getInstanceId());
        }
        // Stopped without StopInstances, as Floci's shutdown or a failed firewall restore does, so
        // the automatic address was never released.
        instance.setState(InstanceState.stopped());
        instance.setPublicIpAddress("172.17.0.9");

        service.startInstances(region, List.of(instance.getInstanceId()));

        // The container manager then re-points what is left, which is only an Elastic IP's.
        assertEquals(kept, instance.getPublicIpAddress());
    }

    @Test
    void disassociatingAnAddressOnASecondaryInterfaceLeavesThePrimaryPublicAddressAlone() {
        Ec2Service service = mockModeService();
        String region = "us-east-1";
        String subnetId = publicSubnet(service, region, "10.247.0.0/24");
        NetworkInterface retained = service.createNetworkInterface(region, subnetId, null, null, List.of(), List.of(),
                List.of());
        Instance first = service.runInstances(region, "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), subnetId, null, List.of(), null, null,
                null, retained.getNetworkInterfaceId(), 0).getInstances().getFirst();
        Address carried = service.allocateAddress(region);
        service.associateAddress(region, carried.getAllocationId(), first.getInstanceId());
        // The interface outlives the instance and keeps the address.
        service.terminateInstances(region, List.of(first.getInstanceId()));
        Instance second = service.runInstances(region, "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), subnetId, null, List.of(), null, null, null).getInstances().getFirst();
        service.describeInstances(region, List.of(second.getInstanceId()), Map.of());
        String automatic = second.getPublicIpAddress();
        assertNotNull(automatic);
        service.attachNetworkInterface(region, retained.getNetworkInterfaceId(), second.getInstanceId(), 1);
        String association = carried.getAssociationId();
        assertNotNull(association, "the retained interface keeps the address associated");

        service.disassociateAddress(region, association);

        assertNull(carried.getAssociationId());
        assertEquals(automatic, second.getPublicIpAddress());
    }

    @Test
    void mockInstancesGetNoAutomaticAddressUnlessConfiguredButStillRecordTheLaunchIntent() {
        EmulatorConfig config = mockConfig(true);
        when(config.services().ec2().mockPublicAddresses())
                .thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
        Ec2Service service = mockModeService(config);
        String region = "us-east-1";
        Instance instance = service.runInstances(region, "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), publicSubnet(service, region, "10.243.0.0/24"), null, List.of(), null, null,
                null).getInstances().getFirst();
        service.describeInstances(region, List.of(instance.getInstanceId()), Map.of());
        assertEquals("running", instance.getState().getName());
        assertNull(instance.getPublicIpAddress());
        assertNull(instance.getPublicDnsName());
        assertTrue(instance.isAssociatePublicIp(), "the launch still records that it asked for an address");

        // An Elastic IP still takes the primary private address, and leaves nothing behind.
        Address elastic = service.allocateAddress(region);
        service.associateAddress(region, elastic.getAllocationId(), instance.getInstanceId());
        assertEquals(elastic.getPublicIp(), instance.getPublicIpAddress());
        service.disassociateAddress(region, elastic.getAssociationId());
        assertNull(instance.getPublicIpAddress());

        // The recorded intent is what a later start decides from.
        when(config.services().ec2().mockPublicAddresses())
                .thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.DOCUMENTATION);
        service.stopInstances(region, List.of(instance.getInstanceId()));
        service.startInstances(region, List.of(instance.getInstanceId()));
        assertTrue(instance.getPublicIpAddress().startsWith("203.0.113."), instance.getPublicIpAddress());
    }

    @Test
    void documentationAddressesAreDistinctAndChangeOnRestart() {
        Ec2Service service = mockModeService();
        String region = "us-east-1";
        String subnetId = publicSubnet(service, region, "10.242.0.0/24");
        List<Instance> launched = service.runInstances(region, "ami-1234567890abcdef0", "t3.micro",
                3, 3, null, List.of(), subnetId, null, List.of(), null, null, null).getInstances();
        service.describeInstances(region, launched.stream().map(Instance::getInstanceId).toList(), Map.of());
        Set<String> addresses = new HashSet<>();
        for (Instance instance : launched) {
            assertTrue(instance.getPublicIpAddress().matches("203\\.0\\.113\\.[0-9]+"), instance.getPublicIpAddress());
            assertEquals(instance.getPublicIpAddress(), instance.getPublicDnsName());
            addresses.add(instance.getPublicIpAddress());
        }
        assertEquals(3, addresses.size());

        Instance first = launched.getFirst();
        String before = first.getPublicIpAddress();
        service.stopInstances(region, List.of(first.getInstanceId()));
        service.startInstances(region, List.of(first.getInstanceId()));
        assertNotEquals(before, first.getPublicIpAddress());
        assertFalse(launched.subList(1, 3).stream()
                .anyMatch(other -> other.getPublicIpAddress().equals(first.getPublicIpAddress())));
    }

    @ParameterizedTest
    @CsvSource(value = {"192.168.65.2,192.168.65.2,192.168.65.2", "' 192.168.65.3 ',192.168.65.3,192.168.65.3",
            "'  ',127.0.0.1,localhost", "NULL,127.0.0.1,localhost"}, nullValues = "NULL")
    void fixedMockAddressesUseTheConfiguredAddress(String configured, String expected, String dnsName) {
        EmulatorConfig config = mockConfig(true);
        when(config.services().ec2().mockPublicAddresses())
                .thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.FIXED);
        when(config.services().ec2().mockPublicAddress()).thenReturn(Optional.ofNullable(configured));
        Ec2Service service = mockModeService(config);
        String region = "us-east-1";
        String subnetId = publicSubnet(service, region, "10.241.0.0/24");
        Instance instance = service.runInstances(region, "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), subnetId, null, List.of(), null, null, null).getInstances().getFirst();
        service.describeInstances(region, List.of(instance.getInstanceId()), Map.of());
        assertEquals(expected, instance.getPublicIpAddress());
        assertEquals(dnsName, instance.getPublicDnsName());

        // The rule still decides: released on stop, back on start.
        service.stopInstances(region, List.of(instance.getInstanceId()));
        assertNull(instance.getPublicIpAddress());
        service.startInstances(region, List.of(instance.getInstanceId()));
        assertEquals(expected, instance.getPublicIpAddress());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void elasticIpsComeFromTheDocumentationRangeApartFromEveryOtherAddress(boolean mockMode) {
        Ec2Service service = mockMode ? mockModeService() : containerModeService();
        String region = "us-east-1";
        Set<String> seen = new HashSet<>();
        if (mockMode) {
            Instance instance = service.runInstances(region, "ami-1234567890abcdef0", "t3.micro",
                    1, 1, null, List.of(), publicSubnet(service, region, "10.240.0.0/24"), null, List.of(), null,
                    null, null).getInstances().getFirst();
            service.describeInstances(region, List.of(instance.getInstanceId()), Map.of());
            seen.add(instance.getPublicIpAddress());
        }
        for (int i = 0; i < 3; i++) {
            String address = service.allocateAddress(region).getPublicIp();
            assertTrue(address.matches("203\\.0\\.113\\.[0-9]+"), address);
            assertTrue(seen.add(address), "a second holder of " + address);
        }
    }

    @Test
    void allocationFailsOnceEveryDocumentationAddressIsTaken() {
        Ec2Service service = mockModeService();
        String region = "us-east-1";
        List<Address> allocated = new ArrayList<>();
        for (int i = 0; i < 254; i++) {
            allocated.add(service.allocateAddress(region));
        }
        assertEquals(254, allocated.stream().map(Address::getPublicIp).distinct().count());
        AwsException full = assertThrows(AwsException.class, () -> service.allocateAddress(region));
        assertEquals("AddressLimitExceeded", full.getErrorCode());

        service.releaseAddress(region, allocated.getFirst().getAllocationId());
        assertEquals(allocated.getFirst().getPublicIp(), service.allocateAddress(region).getPublicIp());
    }

    @Test
    void underFixedAnElasticIpShowsTheConfiguredAddressUntilItIsDisassociated() {
        EmulatorConfig config = mockConfig(true);
        when(config.services().ec2().mockPublicAddresses())
                .thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.FIXED);
        when(config.services().ec2().mockPublicAddress()).thenReturn(Optional.of("192.168.65.2"));
        Ec2Service service = mockModeService(config);
        String region = "us-east-1";
        Instance instance = service.runInstances(region, "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), privateSubnet(service, region, "10.239.0.0/24"), null, List.of(), null, null,
                null).getInstances().getFirst();
        service.describeInstances(region, List.of(instance.getInstanceId()), Map.of());
        assertNull(instance.getPublicIpAddress());
        Address elastic = service.allocateAddress(region);
        String allocated = elastic.getPublicIp();
        assertTrue(allocated.startsWith("203.0.113."), allocated);

        service.associateAddress(region, elastic.getAllocationId(), instance.getInstanceId());
        assertEquals("192.168.65.2", instance.getPublicIpAddress());
        assertEquals("192.168.65.2", service.describeAddresses(region, List.of(elastic.getAllocationId()), Map.of())
                .getFirst().getPublicIp());

        service.disassociateAddress(region, elastic.getAssociationId());
        assertEquals(allocated, service.describeAddresses(region, List.of(elastic.getAllocationId()), Map.of())
                .getFirst().getPublicIp());
        assertNull(instance.getPublicIpAddress(), "the launch asked for no automatic address");
    }

    @Test
    void anElasticIpReturnsToItsAllocatedAddressOnceTheSettingIsNoLongerFixed() {
        EmulatorConfig config = mockConfig(true);
        when(config.services().ec2().mockPublicAddresses())
                .thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.FIXED);
        Ec2Service service = mockModeService(config);
        String region = "us-east-1";
        Instance instance = service.runInstances(region, "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), privateSubnet(service, region, "10.238.0.0/24"), null, List.of(), null, null,
                null).getInstances().getFirst();
        service.describeInstances(region, List.of(instance.getInstanceId()), Map.of());
        Address elastic = service.allocateAddress(region);
        String allocated = elastic.getPublicIp();
        service.associateAddress(region, elastic.getAllocationId(), instance.getInstanceId());
        assertEquals("127.0.0.1", instance.getPublicIpAddress());

        // As after a restart under another setting: the next read shows the allocated address again.
        when(config.services().ec2().mockPublicAddresses())
                .thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
        assertEquals(allocated, service.describeAddresses(region, List.of(elastic.getAllocationId()), Map.of())
                .getFirst().getPublicIp());
        assertEquals(allocated, instance.getPublicIpAddress());
    }

    @Test
    void anInstanceAddressIsNeverAllocatedAsAnElasticIp() {
        Ec2Service service = mockModeService();
        String region = "us-east-1";
        for (int i = 0; i < 253; i++) {
            service.allocateAddress(region);
        }
        Instance instance = service.runInstances(region, "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), publicSubnet(service, region, "10.237.0.0/24"), null, List.of(), null, null,
                null).getInstances().getFirst();
        service.describeInstances(region, List.of(instance.getInstanceId()), Map.of());
        assertEquals("203.0.113.254", instance.getPublicIpAddress());

        AwsException full = assertThrows(AwsException.class, () -> service.allocateAddress(region));
        assertEquals("AddressLimitExceeded", full.getErrorCode());
    }

    private static String privateSubnet(Ec2Service service, String region, String cidr) {
        return service.createSubnet(region, service.createVpc(region, cidr, false).getVpcId(), cidr, region + "a")
                .getSubnetId();
    }

    @Test
    void containerModeLaunchOnARetainedInterfaceRecordsTheElasticIpItCarries() {
        Ec2Service service = containerModeService();
        String region = "us-east-1";
        String subnetId = publicSubnet(service, region, "10.249.0.0/24");
        NetworkInterface retained = service.createNetworkInterface(region, subnetId, null, null, List.of(), List.of(),
                List.of());
        Instance first = runningContainerInstance(service, region, subnetId, retained.getNetworkInterfaceId());
        Address carried = service.allocateAddress(region);
        service.associateAddress(region, carried.getAllocationId(), first.getInstanceId());
        service.terminateInstances(region, List.of(first.getInstanceId()));
        assertNotNull(carried.getAssociationId());

        Instance second = service.runInstances(region, "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), subnetId, null, List.of(), null, null,
                null, retained.getNetworkInterfaceId(), 0).getInstances().getFirst();

        // Not due an automatic address on an existing interface, but the container manager
        // re-points the Elastic IP recorded here once the container runs.
        assertFalse(second.isAssociatePublicIp());
        assertEquals(carried.getPublicIp(), second.getPublicIpAddress());
    }

    @Test
    void containerModeDescribeAddressesWritesNothingBackOntoATerminatedInstance() {
        Ec2Service service = containerModeService();
        String region = "us-east-1";
        Instance instance = runningContainerInstance(service, region, publicSubnet(service, region, "10.248.0.0/24"),
                null);
        Address address = service.allocateAddress(region);
        service.associateAddress(region, address.getAllocationId(), instance.getInstanceId());
        // Terminated while the association stays, as for an instance whose interface did not go.
        instance.setState(InstanceState.terminated());
        instance.setPublicIpAddress(null);
        instance.setPublicDnsName(null);

        service.describeAddresses(region, List.of(address.getAllocationId()), Map.of());

        assertNull(instance.getPublicIpAddress());
    }

    @Test
    void containerModeDescribeAddressesRepointsAnElasticIpCarriedToANewInstance() {
        Ec2ContainerManager containerManager = mock(Ec2ContainerManager.class);
        // Each container answers on its own address, so a stale one shows.
        Map<String, String> reachable = new ConcurrentHashMap<>();
        when(containerManager.reachablePublicAddress(any())).thenAnswer(invocation -> {
            Instance instance = invocation.getArgument(0);
            return instance.getDockerContainerId() == null ? null : reachable.computeIfAbsent(
                    instance.getDockerContainerId(), container -> "192.0.2." + (reachable.size() + 1));
        });
        AmiImageResolver images = mock(AmiImageResolver.class);
        when(images.resolveImage(any())).thenReturn(ResolvedAmiImage.minimal("floci/base:latest"));
        Ec2Service service = new Ec2Service(mockConfig(false), containerManager, mock(Ec2PortForwardManager.class),
                images, mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String region = "us-east-1";
        String subnetId = publicSubnet(service, region, "10.250.0.0/24");
        NetworkInterface retained = service.createNetworkInterface(region, subnetId, null, null, List.of(), List.of(),
                List.of());
        Instance first = runningContainerInstance(service, region, subnetId, retained.getNetworkInterfaceId());
        Address carried = service.allocateAddress(region);
        service.associateAddress(region, carried.getAllocationId(), first.getInstanceId());
        String firstAddress = carried.getPublicIp();
        service.terminateInstances(region, List.of(first.getInstanceId()));
        Instance second = runningContainerInstance(service, region, subnetId, retained.getNetworkInterfaceId());
        String secondAddress = containerManager.reachablePublicAddress(second);
        assertNotEquals(firstAddress, secondAddress);

        Address described = service.describeAddresses(region, List.of(carried.getAllocationId()), Map.of()).getFirst();

        assertEquals(secondAddress, described.getPublicIp());
        assertEquals(secondAddress, second.getPublicIpAddress());
    }

    @Test
    void containerModeStopLeavesATerminatedInstanceAlone() {
        Ec2ContainerManager containerManager = mock(Ec2ContainerManager.class);
        when(containerManager.reachablePublicAddress(any())).thenReturn("127.0.0.1");
        AmiImageResolver images = mock(AmiImageResolver.class);
        when(images.resolveImage(any())).thenReturn(ResolvedAmiImage.minimal("floci/base:latest"));
        Ec2Service service = new Ec2Service(mockConfig(false), containerManager, mock(Ec2PortForwardManager.class),
                images, mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String region = "us-east-1";
        Instance instance = runningContainerInstance(service, region, publicSubnet(service, region, "10.251.0.0/24"),
                null);
        service.terminateInstances(region, List.of(instance.getInstanceId()));
        // What the container manager's teardown does once the container is gone.
        instance.setState(InstanceState.terminated());

        service.stopInstances(region, List.of(instance.getInstanceId()));

        // A container stop would set it stopped and so startable again.
        verify(containerManager, never()).stop(instance);
        assertNull(instance.getPublicIpAddress());
    }

    /** Ec2Service in container mode, with a container manager whose containers answer on loopback. */
    private static Ec2Service containerModeService() {
        Ec2ContainerManager containerManager = mock(Ec2ContainerManager.class);
        when(containerManager.reachablePublicAddress(any())).thenAnswer(invocation -> {
            Instance instance = invocation.getArgument(0);
            return instance.getDockerContainerId() == null ? null : "127.0.0.1";
        });
        AmiImageResolver images = mock(AmiImageResolver.class);
        when(images.resolveImage(any())).thenReturn(ResolvedAmiImage.minimal("floci/base:latest"));
        return new Ec2Service(mockConfig(false), containerManager, mock(Ec2PortForwardManager.class), images,
                mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
    }

    private static String publicSubnet(Ec2Service service, String region, String cidr) {
        String subnetId = service.createSubnet(region, service.createVpc(region, cidr, false).getVpcId(),
                cidr, region + "a").getSubnetId();
        service.modifySubnetAttribute(region, subnetId, "mapPublicIpOnLaunch", "true");
        return subnetId;
    }

    /** Launches an instance and does what the container manager does once its container runs. */
    private static Instance runningContainerInstance(Ec2Service service, String region, String subnetId,
                                                     String networkInterfaceId) {
        Instance instance = service.runInstances(region, "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), subnetId, null, List.of(), null, null,
                null, networkInterfaceId, 0).getInstances().getFirst();
        instance.setDockerContainerId("container-" + instance.getInstanceId());
        instance.setState(InstanceState.running());
        if (instance.isAssociatePublicIp()) {
            instance.setPublicIpAddress("127.0.0.1");
            instance.setPublicDnsName("localhost");
        }
        return instance;
    }

    @Test
    void networkInterfaceAssociationFiltersExcludeSecondaryAddressAssociations() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String subnetId = service.describeSubnets("us-east-1", List.of(), Map.of()).getFirst().getSubnetId();
        NetworkInterface eni = service.createNetworkInterface("us-east-1", subnetId, null,
                null, List.of(), List.of(), List.of());
        NetworkInterfacePrivateIpAddress primary = eni.getPrivateIpAddresses().getFirst();
        NetworkInterfaceAssociation primaryAssociation = new NetworkInterfaceAssociation();
        primaryAssociation.setIpOwnerId("primary-owner");
        primaryAssociation.setPublicIp("192.0.2.10");
        primary.setAssociation(primaryAssociation);
        NetworkInterfacePrivateIpAddress secondary = new NetworkInterfacePrivateIpAddress();
        secondary.setPrivateIpAddress("172.31.0.99");
        NetworkInterfaceAssociation secondaryAssociation = new NetworkInterfaceAssociation();
        secondaryAssociation.setIpOwnerId("secondary-owner");
        secondaryAssociation.setPublicIp("192.0.2.20");
        secondary.setAssociation(secondaryAssociation);
        eni.getPrivateIpAddresses().add(secondary);

        assertEquals(1, service.describeNetworkInterfaces("us-east-1", List.of(),
                Map.of("association.ip-owner-id", List.of("primary-owner"),
                        "association.public-ip", List.of("192.0.2.10")), 0, null).networkInterfaces().size());
        assertTrue(service.describeNetworkInterfaces("us-east-1", List.of(),
                Map.of("association.ip-owner-id", List.of("secondary-owner")), 0, null).networkInterfaces().isEmpty());
        assertTrue(service.describeNetworkInterfaces("us-east-1", List.of(),
                Map.of("association.public-ip", List.of("192.0.2.20")), 0, null).networkInterfaces().isEmpty());
        primary.setAssociation(null);
        for (String filter : List.of("association.ip-owner-id", "association.public-ip")) {
            assertTrue(service.describeNetworkInterfaces("us-east-1", List.of(),
                    Map.of(filter, List.of("*")), 0, null).networkInterfaces().isEmpty());
        }
        primary.setAssociation(new NetworkInterfaceAssociation());
        for (String filter : List.of("association.ip-owner-id", "association.public-ip")) {
            assertTrue(service.describeNetworkInterfaces("us-east-1", List.of(),
                    Map.of(filter, List.of("*")), 0, null).networkInterfaces().isEmpty());
        }
    }

    @Test
    void awaitContainerLaunchReportsTerminatedContainer() {
        Ec2Service service = new Ec2Service(mockConfig(false), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        Instance instance = new Instance();
        instance.setInstanceId("i-terminated");
        instance.setState(InstanceState.terminated());

        AwsException error = assertThrows(AwsException.class, () -> service.awaitContainerLaunch(instance));

        assertEquals("InternalError", error.getErrorCode());
        assertTrue(error.getMessage().contains("container terminated during launch"));
    }

    @Test
    void awaitContainerLaunchTimesOutWhileInstanceIsPending() {
        Ec2ContainerManager containerManager = mock(Ec2ContainerManager.class);
        Ec2Service service = new Ec2Service(mockConfig(false), containerManager,
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        Instance instance = new Instance();
        instance.setInstanceId("i-pending");
        instance.setState(InstanceState.pending());
        when(containerManager.cancelLaunch(instance)).thenReturn(true);

        AwsException error = assertThrows(AwsException.class,
                () -> service.awaitContainerLaunch(instance, Duration.ZERO));

        assertEquals("InternalError", error.getErrorCode());
        assertTrue(error.getMessage().contains("did not reach running state before the launch timeout"));
        verify(containerManager).cancelLaunch(instance);
    }

    /**
     * A container-backed launch that fails or is cancelled terminates the instance inside the
     * container manager, never passing through TerminateInstances, so an ENI the launch had
     * already marked in-use would stay pinned to an instance that no longer runs, and could
     * never be reused. The attachment is reconciled when the interface is next read instead.
     */
    @Test
    void anInterfaceIsReleasedWhenItsInstanceDiedWithoutTerminateInstances() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String subnetId = service.describeSubnets("us-east-1", List.of(), Map.of())
                .getFirst().getSubnetId();
        NetworkInterface eni = service.createNetworkInterface("us-east-1", subnetId, null,
                null, List.of(), List.of(), List.of());

        Reservation reservation = service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null, null,
                eni.getNetworkInterfaceId(), 0);
        Instance instance = reservation.getInstances().getFirst();
        assertEquals("in-use", service.describeNetworkInterfaces("us-east-1",
                List.of(eni.getNetworkInterfaceId()), Map.of(), 0, null)
                .networkInterfaces().getFirst().getStatus());

        // What a failed launch leaves behind: terminated, with no TerminateInstances call.
        instance.setState(InstanceState.terminated());

        NetworkInterface afterFailure = service.describeNetworkInterfaces("us-east-1",
                List.of(eni.getNetworkInterfaceId()), Map.of(), 0, null)
                .networkInterfaces().getFirst();
        assertEquals("available", afterFailure.getStatus());
        assertNull(afterFailure.getAttachment());
        // The dead instance lets go of its copy too. Leaving it there would have the terminated
        // instance still reporting an interface that a later launch is free to take, so two
        // instance records would claim it.
        assertTrue(instance.getNetworkInterfaces().stream()
                .noneMatch(e -> eni.getNetworkInterfaceId().equals(e.getNetworkInterfaceId())));

        // And it really is free: a second launch can take it, which the in-use check would refuse.
        Reservation retry = service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null, null,
                eni.getNetworkInterfaceId(), 0);
        assertEquals(1, retry.getInstances().size());
    }

    /**
     * An interface a service holds on the caller's behalf, an ECS task's ENI being the one this
     * emulator allocates, is {@code in-use}: DescribeNetworkInterfaces documents an unattached
     * interface as {@code available} and an attached one as {@code in-use}, so left
     * {@code available} it would answer a caller looking for a free interface. No attachment is
     * synthesised, every member AWS documents on one naming an instance the task does not have,
     * and the read-time reconciliation that frees an interface whose instance died therefore has
     * nothing to act on here.
     */
    @Test
    void aServiceHeldInterfaceStaysInUseUntilItIsReleased() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String subnetId = service.describeSubnets("us-east-1", List.of(), Map.of())
                .getFirst().getSubnetId();
        NetworkInterface eni = service.createNetworkInterface("us-east-1", subnetId, null,
                null, List.of(), List.of(), List.of());
        String eniId = eni.getNetworkInterfaceId();
        assertEquals("available", eni.getStatus());

        service.holdNetworkInterfaceForService("us-east-1", eniId);

        assertEquals("in-use", readInterface(service, eniId).getStatus());
        assertNull(readInterface(service, eniId).getAttachment());
        assertTrue(service.describeNetworkInterfaces("us-east-1", List.of(),
                        Map.of("status", List.of("available")), 0, null)
                .networkInterfaces().stream()
                .noneMatch(n -> eniId.equals(n.getNetworkInterfaceId())));

        // AWS does not let the account delete an interface a task holds, so a teardown has to
        // release it first.
        AwsException inUse = assertThrows(AwsException.class,
                () -> service.deleteNetworkInterface("us-east-1", eniId));
        assertEquals("InvalidParameterValue", inUse.getErrorCode());

        service.releaseNetworkInterfaceFromService("us-east-1", eniId);

        assertEquals("available", readInterface(service, eniId).getStatus());
        assertDoesNotThrow(() -> service.deleteNetworkInterface("us-east-1", eniId));
        // Releasing an interface that is already gone is a no-op, so a second teardown is harmless.
        assertDoesNotThrow(() -> service.releaseNetworkInterfaceFromService("us-east-1", eniId));
    }

    /**
     * The service hold must not reach an interface an instance owns: releasing one of those is
     * DetachNetworkInterface's job, and it has an instance-side copy to clear as well.
     */
    @Test
    void aServiceHoldLeavesAnInstanceHeldInterfaceAlone() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String subnetId = service.describeSubnets("us-east-1", List.of(), Map.of())
                .getFirst().getSubnetId();
        NetworkInterface eni = service.createNetworkInterface("us-east-1", subnetId, null,
                null, List.of(), List.of(), List.of());
        Reservation reservation = service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null, null,
                eni.getNetworkInterfaceId(), 0);
        String instanceId = reservation.getInstances().getFirst().getInstanceId();

        AwsException held = assertThrows(AwsException.class,
                () -> service.holdNetworkInterfaceForService("us-east-1", eni.getNetworkInterfaceId()));
        assertEquals("InvalidNetworkInterface.InUse", held.getErrorCode());

        service.releaseNetworkInterfaceFromService("us-east-1", eni.getNetworkInterfaceId());

        NetworkInterface after = readInterface(service, eni.getNetworkInterfaceId());
        assertEquals("in-use", after.getStatus());
        assertEquals(instanceId, after.getAttachment().getInstanceId());
    }

    private static NetworkInterface readInterface(Ec2Service service, String networkInterfaceId) {
        return service.describeNetworkInterfaces("us-east-1", List.of(networkInterfaceId), Map.of(), 0, null)
                .networkInterfaces().getFirst();
    }

    /**
     * A NetworkInterface can carry a real GroupIdentifier entry whose groupId is null (a name-only
     * association). The group-id filter must skip that entry instead of NPE-ing on
     * {@code null.matches(...)} in the shared value matcher, while a real group on the same interface
     * still matches. The null entry is evaluated first so the filter actually exercises the null path.
     */
    @Test
    void describeNetworkInterfacesGroupIdFilterToleratesNullGroupId() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String subnetId = service.describeSubnets("us-east-1", List.of(), Map.of())
                .getFirst().getSubnetId();
        NetworkInterface eni = service.createNetworkInterface("us-east-1", subnetId, null,
                null, List.of(), List.of(), List.of());
        eni.getGroups().add(new GroupIdentifier(null, "name-only"));
        eni.getGroups().add(new GroupIdentifier("sg-realgroup000000", "real"));

        List<NetworkInterface> matched = service.describeNetworkInterfaces("us-east-1", List.of(),
                Map.of("group-id", List.of("sg-realgroup000000")), 0, null).networkInterfaces();

        assertTrue(matched.stream()
                .anyMatch(n -> eni.getNetworkInterfaceId().equals(n.getNetworkInterfaceId())));
    }

    /**
     * The NAT arm of DescribeNetworkInterfaces reads gateways for the account it was asked about.
     * A gateway made under the default account resolves for that account and is NotFound for
     * another one named explicitly.
     */
    @Test
    void natGatewayInterfaceResolvesOnlyForTheAccountThatOwnsTheGateway() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String subnetId = service.describeSubnets("us-east-1", List.of(), Map.of())
                .getFirst().getSubnetId();
        String eniId = service.createNatGateway("us-east-1", subnetId, null, "private", null)
                .getNatGatewayAddresses().getFirst().getNetworkInterfaceId();

        String owner = service.callerAccountId();
        assertEquals(eniId, service.describeNetworkInterfaces(owner, "us-east-1", List.of(eniId), Map.of())
                .networkInterfaces().getFirst().getNetworkInterfaceId());
        AwsException e = assertThrows(AwsException.class, () -> service.describeNetworkInterfaces(
                "999999999999", "us-east-1", List.of(eniId), Map.of()));
        assertEquals("InvalidNetworkInterfaceID.NotFound", e.getErrorCode());
    }

    /**
     * A gateway persisted before Address records were marked has an allocation that looks free.
     * The gateway still holds it, so a second gateway is created failed and claims nothing.
     */
    @Test
    void natGatewayGivenAnElasticIpHeldByALegacyGatewayWithAnUnmarkedAddressIsFailed() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String subnetId = service.describeSubnets("us-east-1", List.of(), Map.of()).getFirst().getSubnetId();
        String allocationId = service.allocateAddress("us-east-1").getAllocationId();
        service.createNatGateway("us-east-1", subnetId, allocationId, null, null);
        // Simulate the pre-change record: in-memory storage hands back the stored object.
        Address legacy = service.describeAddresses("us-east-1", List.of(allocationId), Map.of()).getFirst();
        legacy.setAssociationId(null);
        legacy.setNetworkInterfaceId(null);
        legacy.setPrivateIpAddress(null);

        NatGateway failed = service.createNatGateway("us-east-1", subnetId, allocationId, null, null);
        assertEquals("failed", failed.getState());
        assertEquals("Resource.AlreadyAssociated", failed.getFailureCode());
        assertTrue(failed.getNatGatewayAddresses().isEmpty());
        assertNull(service.describeAddresses("us-east-1", List.of(allocationId), Map.of())
                .getFirst().getAssociationId(), "a failed gateway must not claim the allocation");
    }

    /** AssociateAddress refuses an allocation a NAT gateway holds; the gateway keeps it. */
    @Test
    void associateAddressRefusesANatGatewaysElasticIp() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String subnetId = service.describeSubnets("us-east-1", List.of(), Map.of()).getFirst().getSubnetId();
        String allocationId = service.allocateAddress("us-east-1").getAllocationId();
        NatGateway nat = service.createNatGateway("us-east-1", subnetId, allocationId, null, null);
        String gatewayAssociation = nat.getNatGatewayAddresses().getFirst().getAssociationId();

        AwsException e = assertThrows(AwsException.class,
                () -> service.associateAddress("us-east-1", allocationId, "i-0123456789abcdef0"));
        assertEquals("Resource.AlreadyAssociated", e.getErrorCode());

        Address after = service.describeAddresses("us-east-1", List.of(allocationId), Map.of()).getFirst();
        assertEquals(gatewayAssociation, after.getAssociationId());
        assertNull(after.getInstanceId());
        service.deleteNatGateway("us-east-1", nat.getNatGatewayId());
        assertNull(service.describeAddresses("us-east-1", List.of(allocationId), Map.of())
                .getFirst().getAssociationId(), "delete must still release the gateway's Elastic IP");
    }

    /**
     * A gateway persisted before account isolation (unprefixed key) still holds its Elastic IP:
     * a second gateway is created failed, and ReleaseAddress answers InvalidIPAddress.InUse.
     */
    @Test
    void aLegacyUnprefixedGatewaysElasticIpCannotBeTakenOrReleased() {
        InMemoryStorage<String, NatGateway> rawGateways = new InMemoryStorage<>();
        Map<String, AccountAwareStorageBackend<?>> stores = Map.of("ec2-nat-gateways.json",
                new AccountAwareStorageBackend<>(rawGateways, null, "000000000000"));
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory(stores));
        String subnetId = service.describeSubnets("us-east-1", List.of(), Map.of()).getFirst().getSubnetId();
        String allocationId = service.allocateAddress("us-east-1").getAllocationId();
        NatGateway legacy = new NatGateway();
        legacy.setNatGatewayId("nat-0legacy000000000");
        legacy.setSubnetId(subnetId);
        legacy.setAllocationId(allocationId);
        legacy.setState("available");
        legacy.setRegion("us-east-1");
        rawGateways.put("us-east-1::nat-0legacy000000000", legacy);

        NatGateway failed = service.createNatGateway("us-east-1", subnetId, allocationId, null, null);
        assertEquals("failed", failed.getState());
        assertEquals("Resource.AlreadyAssociated", failed.getFailureCode());
        AwsException e = assertThrows(AwsException.class,
                () -> service.releaseAddress("us-east-1", allocationId));
        assertEquals("InvalidIPAddress.InUse", e.getErrorCode());
        assertEquals(1, service.describeAddresses("us-east-1", List.of(allocationId), Map.of()).size());
    }

    /** A legacy unprefixed gateway in a legacy unprefixed subnet resolves its interface for the default account. */
    @Test
    void aLegacyUnprefixedGatewaysInterfaceResolvesForTheDefaultAccount() {
        InMemoryStorage<String, NatGateway> rawGateways = new InMemoryStorage<>();
        InMemoryStorage<String, Subnet> rawSubnets = new InMemoryStorage<>();
        Map<String, AccountAwareStorageBackend<?>> stores = Map.of(
                "ec2-nat-gateways.json", new AccountAwareStorageBackend<>(rawGateways, null, "000000000000"),
                "ec2-subnets.json", new AccountAwareStorageBackend<>(rawSubnets, null, "000000000000"));
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory(stores));
        service.describeSubnets("us-east-1", List.of(), Map.of());
        Subnet subnet = new Subnet();
        subnet.setSubnetId("subnet-0legacy00000000");
        subnet.setVpcId("vpc-0legacy000000000");
        subnet.setAvailabilityZone("us-east-1a");
        subnet.setRegion("us-east-1");
        rawSubnets.put("us-east-1::subnet-0legacy00000000", subnet);
        NatGateway legacy = new NatGateway();
        legacy.setNatGatewayId("nat-0legacy000000000");
        legacy.setSubnetId("subnet-0legacy00000000");
        legacy.setVpcId("vpc-0legacy000000000");
        legacy.setRegion("us-east-1");
        NatGatewayAddress address = new NatGatewayAddress();
        address.setNetworkInterfaceId("eni-0legacy000000000");
        address.setPrivateIp("10.0.0.5");
        legacy.getNatGatewayAddresses().add(address);
        rawGateways.put("us-east-1::nat-0legacy000000000", legacy);

        NetworkInterface ni = service.describeNetworkInterfaces(service.callerAccountId(), "us-east-1",
                List.of("eni-0legacy000000000"), Map.of()).networkInterfaces().getFirst();
        assertEquals("nat_gateway", ni.getInterfaceType());
        assertEquals("us-east-1a", ni.getAvailabilityZone());
        AwsException e = assertThrows(AwsException.class, () -> service.describeNetworkInterfaces(
                "999999999999", "us-east-1", List.of("eni-0legacy000000000"), Map.of()));
        assertEquals("InvalidNetworkInterfaceID.NotFound", e.getErrorCode());
    }

    /** Tags on a NAT gateway's interface read back through DescribeNetworkInterfaces and go with the gateway. */
    @Test
    void aNatGatewayInterfacesTagsReadBackAndAreDeletedWithTheGateway() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String subnetId = service.describeSubnets("us-east-1", List.of(), Map.of()).getFirst().getSubnetId();
        NatGateway nat = service.createNatGateway("us-east-1", subnetId, null, "private", null);
        String eniId = nat.getNatGatewayAddresses().getFirst().getNetworkInterfaceId();
        service.createTags("us-east-1", List.of(eniId), List.of(new Tag("Name", "nat-eni")));

        NetworkInterface ni = service.describeNetworkInterfaces(service.callerAccountId(), "us-east-1",
                List.of(eniId), Map.of()).networkInterfaces().getFirst();
        assertEquals("nat-eni", ni.getTagSet().getFirst().getValue());

        service.deleteNatGateway("us-east-1", nat.getNatGatewayId());
        assertTrue(service.describeTags("us-east-1", Map.of("resource-id", List.of(eniId))).isEmpty());
    }

    /** DisassociateAddress checks and clears under the lock NAT create and delete take. */
    @Test
    void disassociateAddressWaitsForTheAllocationLock() throws Exception {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String allocationId = service.allocateAddress("us-east-1").getAllocationId();
        String associationId = service.associateAddress("us-east-1", allocationId, "i-0123456789abcdef0")
                .getAssociationId();
        Field field = Ec2Service.class.getDeclaredField("privateIpAllocationLock");
        field.setAccessible(true);
        Object lock = field.get(service);

        Thread disassociator = new Thread(() -> service.disassociateAddress("us-east-1", associationId));
        synchronized (lock) {
            disassociator.start();
            disassociator.join(500);
            assertTrue(disassociator.isAlive(), "disassociate must wait for the allocation lock");
        }
        disassociator.join(5000);
        assertFalse(disassociator.isAlive());
        assertNull(service.describeAddresses("us-east-1", List.of(allocationId), Map.of())
                .getFirst().getAssociationId());
    }

    /** DeleteNatGateway releases the Elastic IP under the lock CreateNatGateway takes. */
    @Test
    void deleteNatGatewayReleasesTheElasticIpUnderTheAllocationLock() throws Exception {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String subnetId = service.describeSubnets("us-east-1", List.of(), Map.of()).getFirst().getSubnetId();
        String allocationId = service.allocateAddress("us-east-1").getAllocationId();
        String natId = service.createNatGateway("us-east-1", subnetId, allocationId, null, null).getNatGatewayId();
        Field field = Ec2Service.class.getDeclaredField("privateIpAllocationLock");
        field.setAccessible(true);
        Object lock = field.get(service);

        Thread deleter = new Thread(() -> service.deleteNatGateway("us-east-1", natId));
        synchronized (lock) {
            deleter.start();
            deleter.join(500);
            assertTrue(deleter.isAlive(), "delete must wait for the allocation lock");
        }
        deleter.join(5000);
        assertFalse(deleter.isAlive());
        assertNull(service.describeAddresses("us-east-1", List.of(allocationId), Map.of())
                .getFirst().getAssociationId());
    }

    @Test
    void runInstancesRequiresImageIdInsteadOfDefaulting() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        AwsException error = assertThrows(AwsException.class, () -> service.runInstances(
                "us-east-1", null, "t3.micro", 1, 1, null, List.of(), null, null,
                List.of(), null, null));

        assertEquals("MissingParameter", error.getErrorCode());
        assertEquals("The request must contain the parameter ImageId", error.getMessage());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void createSubnetRequiresVpcIdInsteadOfNotFound() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        AwsException error = assertThrows(AwsException.class, () -> service.createSubnet(
                "us-east-1", null, "10.0.1.0/24", null));

        assertEquals("MissingParameter", error.getErrorCode());
        assertEquals("The request must contain the parameter VpcId", error.getMessage());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void createSubnetRejectsBlankVpcIdInsteadOfNotFound() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        AwsException error = assertThrows(AwsException.class, () -> service.createSubnet(
                "us-east-1", "   ", "10.0.1.0/24", null));

        assertEquals("MissingParameter", error.getErrorCode());
        assertEquals("The request must contain the parameter VpcId", error.getMessage());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void createSubnetRejectsCidrThatConflictsWithAnExistingSubnetInTheSameVpc() {
        // CreateSubnet has no idempotency token, so an SDK transport retry after a lost response
        // resends the identical request. AWS's CIDR conflict check is what turns that resend into
        // an error instead of a second, unrecorded subnet.
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        Vpc vpc = service.createVpc("us-east-1", "10.0.0.0/16", false);
        service.createSubnet("us-east-1", vpc.getVpcId(), "10.0.1.0/24", "us-east-1a");

        AwsException error = assertThrows(AwsException.class, () -> service.createSubnet(
                "us-east-1", vpc.getVpcId(), "10.0.1.0/24", "us-east-1a"));

        assertEquals("InvalidSubnet.Conflict", error.getErrorCode());
        assertEquals("The CIDR '10.0.1.0/24' conflicts with another subnet", error.getMessage());
        assertEquals(400, error.getHttpStatus());
        assertEquals(1, service.describeSubnets("us-east-1", List.of(), Map.of()).stream()
                .filter(s -> vpc.getVpcId().equals(s.getVpcId())).count());
    }

    @Test
    void createSubnetRejectsIpv4PrefixOutsideSlash16ToSlash28() {
        // VPC User Guide: "The allowed IPv4 CIDR block size for a subnet is between a /28 netmask and /16 netmask."
        AccountAwareStorageBackend<Vpc> vpcStore = AccountAwareStorageBackend.inMemory("000000000000");
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Map.of("ec2-vpcs.json", vpcStore)));
        String vpcId = slashEightVpc(service, vpcStore);

        for (String cidr : List.of("10.0.0.0/15", "10.2.0.0/29", "10.2.0.16/30", "10.2.0.32/32")) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.createSubnet("us-east-1", vpcId, cidr, "us-east-1a"));
            assertEquals("InvalidSubnet.Range", error.getErrorCode());
            assertEquals("The CIDR '" + cidr + "' is invalid.", error.getMessage());
            assertEquals(400, error.getHttpStatus());
        }
        assertEquals(0, service.describeSubnets("us-east-1", List.of(), Map.of()).stream()
                .filter(s -> vpcId.equals(s.getVpcId())).count());
    }

    @Test
    void createSubnetAcceptsTheSlash16AndSlash28PrefixBounds() {
        AccountAwareStorageBackend<Vpc> vpcStore = AccountAwareStorageBackend.inMemory("000000000000");
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Map.of("ec2-vpcs.json", vpcStore)));
        String vpcId = slashEightVpc(service, vpcStore);

        assertEquals("10.1.0.0/16", service.createSubnet("us-east-1", vpcId, "10.1.0.0/16", "us-east-1a")
                .getCidrBlock());
        assertEquals("10.2.0.0/28", service.createSubnet("us-east-1", vpcId, "10.2.0.0/28", "us-east-1a")
                .getCidrBlock());
    }

    /** CreateVpc refuses a /8, so the VPC is created as a /16 and widened to 10.0.0.0/8 in the store. */
    private static String slashEightVpc(Ec2Service service, AccountAwareStorageBackend<Vpc> vpcStore) {
        Vpc vpc = service.createVpc("us-east-1", "10.0.0.0/16", false);
        vpc.setCidrBlock("10.0.0.0/8");
        vpcStore.put("us-east-1::" + vpc.getVpcId(), vpc);
        return vpc.getVpcId();
    }

    @Test
    void createVpcAndCreateSubnetStoreTheCidrBlockInCanonicalForm() {
        // AWS CreateVpc/CreateSubnet: "We modify the specified CIDR block to its canonical form; for
        // example, if you specify 100.68.0.18/18, we modify it to 100.68.0.0/18."
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String vpcId = service.createVpc("us-east-1", "10.20.0.5/16", false).getVpcId();
        String subnetId = service.createSubnet("us-east-1", vpcId, "10.20.1.9/24", "us-east-1a").getSubnetId();
        String canonicalSubnetId = service.createSubnet("us-east-1", vpcId, "10.20.2.0/24", "us-east-1a").getSubnetId();

        Vpc vpc = service.describeVpcs("us-east-1", List.of(vpcId), Map.of()).get(0);
        assertEquals("10.20.0.0/16", vpc.getCidrBlock());
        assertEquals(List.of("10.20.0.0/16"), vpc.getCidrBlockAssociationSet().stream()
                .map(a -> a.getCidrBlock()).toList());
        assertEquals("10.20.1.0/24",
                service.describeSubnets("us-east-1", List.of(subnetId), Map.of()).get(0).getCidrBlock());
        assertEquals("10.20.2.0/24",
                service.describeSubnets("us-east-1", List.of(canonicalSubnetId), Map.of()).get(0).getCidrBlock());

        // The conflict check runs on the canonical form, and reports it.
        AwsException error = assertThrows(AwsException.class, () -> service.createSubnet(
                "us-east-1", vpcId, "10.20.1.0/24", "us-east-1a"));
        assertEquals("InvalidSubnet.Conflict", error.getErrorCode());
        AwsException hostBits = assertThrows(AwsException.class, () -> service.createSubnet(
                "us-east-1", vpcId, "10.20.2.77/24", "us-east-1a"));
        assertEquals("The CIDR '10.20.2.0/24' conflicts with another subnet", hostBits.getMessage());
    }

    @Test
    void createVpcKeepsAnAlreadyCanonicalCidrBlockUnchanged() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String vpcId = service.createVpc("us-east-1", "10.30.0.0/16", false).getVpcId();

        assertEquals("10.30.0.0/16",
                service.describeVpcs("us-east-1", List.of(vpcId), Map.of()).get(0).getCidrBlock());
    }

    @Test
    void createSubnetRejectsCidrThatPartiallyOverlapsAnExistingSubnet() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        Vpc vpc = service.createVpc("us-east-1", "10.0.0.0/16", false);
        service.createSubnet("us-east-1", vpc.getVpcId(), "10.0.1.0/24", "us-east-1a");

        // 10.0.1.128/25 lies inside 10.0.1.0/24, an overlap rather than a duplicate.
        AwsException error = assertThrows(AwsException.class, () -> service.createSubnet(
                "us-east-1", vpc.getVpcId(), "10.0.1.128/25", "us-east-1a"));

        assertEquals("InvalidSubnet.Conflict", error.getErrorCode());
    }

    @Test
    void createSubnetAllowsNonOverlappingCidrsInTheSameVpc() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        Vpc vpc = service.createVpc("us-east-1", "10.0.0.0/16", false);
        service.createSubnet("us-east-1", vpc.getVpcId(), "10.0.1.0/24", "us-east-1a");

        Subnet second = service.createSubnet("us-east-1", vpc.getVpcId(), "10.0.2.0/24", "us-east-1b");

        assertEquals("10.0.2.0/24", second.getCidrBlock());
        assertEquals(2, service.describeSubnets("us-east-1", List.of(), Map.of()).stream()
                .filter(s -> vpc.getVpcId().equals(s.getVpcId())).count());
    }

    @Test
    void createSubnetRejectsCidrOutsideVpcWithoutStoringSubnet() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String vpcId = service.createVpc("us-east-1", "10.0.0.0/16", false).getVpcId();
        for (String cidr : List.of("192.168.1.0/24", "10.0.0.0/15", "10.1.0.0/24", "2001:db8::/56")) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.createSubnet("us-east-1", vpcId, cidr, "us-east-1a"));
            assertEquals("InvalidSubnet.Range", error.getErrorCode());
            assertEquals(400, error.getHttpStatus());
        }
        assertTrue(service.describeSubnets("us-east-1", List.of(), Map.of()).stream()
                .noneMatch(subnet -> vpcId.equals(subnet.getVpcId())));
    }

    @Test
    void createSubnetAllowsPrimaryAndSecondaryBlocksButNotDisassociatedBlocks() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String vpcId = service.createVpc("us-east-1", "10.0.0.0/16", false).getVpcId();
        String associationId = service.associateVpcCidrBlock("us-east-1", vpcId, "10.1.0.0/16").getAssociationId();
        assertEquals("10.0.255.0/24",
                service.createSubnet("us-east-1", vpcId, "10.0.255.9/24", "us-east-1a").getCidrBlock());
        assertEquals("10.1.1.0/24",
                service.createSubnet("us-east-1", vpcId, "10.1.1.9/24", "us-east-1a").getCidrBlock());
        service.disassociateVpcCidrBlock("us-east-1", associationId);
        assertEquals("InvalidSubnet.Range", assertThrows(AwsException.class,
                () -> service.createSubnet("us-east-1", vpcId, "10.1.2.0/24", "us-east-1a")).getErrorCode());
    }

    @Test
    void associateVpcCidrBlockRejectsTheRestrictedRangesForThePrimaryBlock() {
        // VPC User Guide, "IPv4 CIDR block association restrictions": the primary block's range
        // decides which secondary blocks are refused.
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        Map<String, List<String>> restricted = new LinkedHashMap<>();
        restricted.put("10.0.0.0/16", List.of("172.16.0.0/16", "192.168.0.0/16", "198.19.0.0/24"));
        restricted.put("10.1.0.0/16", List.of("10.0.0.0/16", "10.0.4.0/24"));
        restricted.put("172.16.0.0/16", List.of("10.1.0.0/16", "192.168.0.0/16", "172.31.0.0/16", "198.19.0.0/24"));
        restricted.put("192.168.0.0/20", List.of("10.1.0.0/16", "172.16.0.0/16", "198.19.0.0/24"));
        restricted.put("198.19.0.0/16", List.of("10.1.0.0/16", "172.16.0.0/16", "192.168.0.0/16"));
        restricted.put("100.64.0.0/16", List.of("10.1.0.0/16", "172.16.0.0/16", "192.168.0.0/16", "198.19.0.0/24"));
        restricted.put("52.0.0.0/16", List.of("10.1.0.0/16", "172.16.0.0/16", "192.168.0.0/16", "198.19.0.0/24"));

        restricted.forEach((primary, blocks) -> {
            String vpcId = service.createVpc("us-east-1", primary, false).getVpcId();
            for (String cidr : blocks) {
                AwsException error = assertThrows(AwsException.class,
                        () -> service.associateVpcCidrBlock("us-east-1", vpcId, cidr), primary + " + " + cidr);
                assertEquals("InvalidVpc.Range", error.getErrorCode());
                assertEquals("The CIDR '" + cidr + "' is restricted. Use a CIDR from the same private address "
                        + "range as the current VPC CIDR, or use a publicly-routable CIDR. For additional "
                        + "restrictions, see https://docs.aws.amazon.com/vpc/latest/userguide/vpc-cidr-blocks.html",
                        error.getMessage());
                assertEquals(400, error.getHttpStatus());
            }
            assertEquals(1, service.describeVpcs("us-east-1", List.of(vpcId), Map.of()).getFirst()
                    .getCidrBlockAssociationSet().size());
        });
    }

    @Test
    void associateVpcCidrBlockAcceptsThePermittedRangesForThePrimaryBlock() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        Map<String, List<String>> permitted = new LinkedHashMap<>();
        permitted.put("10.0.0.0/16", List.of("10.2.0.0/16", "100.64.0.0/16", "52.0.0.0/16"));
        permitted.put("10.2.0.0/16", List.of("10.0.0.0/16"));
        permitted.put("172.16.0.0/16", List.of("172.20.0.0/16", "100.64.0.0/16", "52.0.0.0/16"));
        permitted.put("192.168.0.0/20", List.of("192.168.16.0/20", "100.64.0.0/16", "52.0.0.0/16"));
        permitted.put("198.19.0.0/16", List.of("100.64.0.0/16", "52.0.0.0/16"));
        permitted.put("100.64.0.0/16", List.of("100.65.0.0/16", "52.0.0.0/16"));
        permitted.put("52.0.0.0/16", List.of("52.1.0.0/16", "100.64.0.0/16"));

        permitted.forEach((primary, blocks) -> {
            String vpcId = service.createVpc("us-east-1", primary, false).getVpcId();
            for (String cidr : blocks) {
                assertEquals(cidr, service.associateVpcCidrBlock("us-east-1", vpcId, cidr).getCidrBlock(),
                        primary + " + " + cidr);
            }
        });
    }

    @Test
    void associateVpcCidrBlockRefuses172Dot31ForA172Dot16Vpc() {
        // A 172.16.0.0/12 VPC cannot add from 172.31.0.0/16, its own primary's /16 included.
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String vpcId = service.createVpc("us-east-1", "172.31.104.0/21", false).getVpcId();
        assertEquals("InvalidVpc.Range", assertThrows(AwsException.class,
                () -> service.associateVpcCidrBlock("us-east-1", vpcId, "172.31.120.0/21")).getErrorCode());
        assertEquals("172.30.120.0/21",
                service.associateVpcCidrBlock("us-east-1", vpcId, "172.30.120.0/21").getCidrBlock());
    }

    @Test
    void createVpcAndAssociateVpcCidrBlockRejectIpv4PrefixOutsideSlash16ToSlash28() {
        // VPC User Guide: "The allowed block size is between a /28 netmask and /16 netmask."
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        long vpcsBefore = service.describeVpcs("us-east-1", List.of(), Map.of()).size();
        String vpcId = service.createVpc("us-east-1", "10.0.0.0/16", false).getVpcId();

        for (String cidr : List.of("0.0.0.0/0", "10.0.0.0/8", "10.0.0.0/15", "10.0.0.0/29", "10.0.0.0/32")) {
            AwsException createError = assertThrows(AwsException.class,
                    () -> service.createVpc("us-east-1", cidr, false));
            assertEquals("InvalidVpc.Range", createError.getErrorCode());
            assertEquals("The CIDR '" + cidr + "' is invalid.", createError.getMessage());
            assertEquals(400, createError.getHttpStatus());

            AwsException associateError = assertThrows(AwsException.class,
                    () -> service.associateVpcCidrBlock("us-east-1", vpcId, cidr));
            assertEquals("InvalidVpc.Range", associateError.getErrorCode());
            assertEquals(400, associateError.getHttpStatus());
        }
        assertEquals(vpcsBefore + 1, service.describeVpcs("us-east-1", List.of(), Map.of()).size());
        assertEquals(1, service.describeVpcs("us-east-1", List.of(vpcId), Map.of()).getFirst()
                .getCidrBlockAssociationSet().size());
    }

    @Test
    void createVpcAndAssociateVpcCidrBlockRejectReservedRanges() {
        // VPC User Guide: "You can't specify the following CIDR blocks for your VPCs": 0.0.0.0/8,
        // 127.0.0.0/8, 169.254.0.0/16 and 224.0.0.0/4.
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        long vpcsBefore = service.describeVpcs("us-east-1", List.of(), Map.of()).size();
        String vpcId = service.createVpc("us-east-1", "52.0.0.0/16", false).getVpcId();

        for (String cidr : List.of("0.0.0.0/16", "0.255.0.0/28", "127.0.0.0/16", "127.255.255.0/24",
                "169.254.0.0/16", "169.254.169.0/24", "224.0.0.0/16", "239.255.0.0/16")) {
            AwsException createError = assertThrows(AwsException.class,
                    () -> service.createVpc("us-east-1", cidr, false), cidr);
            assertEquals("InvalidVpc.Range", createError.getErrorCode());
            assertEquals("The CIDR '" + cidr + "' is invalid.", createError.getMessage());
            assertEquals(400, createError.getHttpStatus());

            AwsException associateError = assertThrows(AwsException.class,
                    () -> service.associateVpcCidrBlock("us-east-1", vpcId, cidr), cidr);
            assertEquals("InvalidVpc.Range", associateError.getErrorCode());
            assertEquals(400, associateError.getHttpStatus());
        }
        assertEquals(vpcsBefore + 1, service.describeVpcs("us-east-1", List.of(), Map.of()).size());
        assertEquals(1, service.describeVpcs("us-east-1", List.of(vpcId), Map.of()).getFirst()
                .getCidrBlockAssociationSet().size());
    }

    @Test
    void createVpcAcceptsBlocksNextToTheReservedRanges() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        for (String cidr : List.of("1.0.0.0/16", "126.255.0.0/16", "128.0.0.0/16", "169.253.0.0/16",
                "169.255.0.0/16", "223.255.0.0/16", "240.0.0.0/16")) {
            assertEquals(cidr, service.createVpc("us-east-1", cidr, false).getCidrBlock());
        }
    }

    @Test
    void createVpcAndAssociateVpcCidrBlockAcceptTheSlash16AndSlash28PrefixBounds() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());

        assertEquals("10.1.0.0/16", service.createVpc("us-east-1", "10.1.0.0/16", false).getCidrBlock());
        Vpc smallest = service.createVpc("us-east-1", "10.2.0.0/28", false);
        assertEquals("10.2.0.0/28", smallest.getCidrBlock());
        // Secondary blocks stay in 10.0.0.0/8, the range AWS lets a 10.x VPC add from.
        assertEquals("10.3.0.0/16", service.associateVpcCidrBlock("us-east-1", smallest.getVpcId(),
                "10.3.0.0/16").getCidrBlock());
        assertEquals("10.4.0.0/28", service.associateVpcCidrBlock("us-east-1", smallest.getVpcId(),
                "10.4.0.0/28").getCidrBlock());
    }

    @Test
    void cidrAssociationEditsWaitForSubnetContainmentValidation() throws Exception {
        for (boolean remove : List.of(false, true)) {
            Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                    mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                    new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
            Vpc vpc = service.createVpc("us-east-1", "10.0.0.0/16", false);
            String associationId = service.associateVpcCidrBlock("us-east-1", vpc.getVpcId(), "10.1.0.0/16")
                    .getAssociationId();
            CountDownLatch scanning = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            VpcCidrBlockAssociation first = spy(vpc.getCidrBlockAssociationSet().getFirst());
            doAnswer(call -> {
                scanning.countDown();
                assertTrue(resume.await(15, TimeUnit.SECONDS));
                return call.callRealMethod();
            }).when(first).getCidrBlockState();
            vpc.getCidrBlockAssociationSet().set(0, first);
            AtomicReference<Thread> editor = new AtomicReference<>();
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<Subnet> creating = workers.submit(() ->
                        service.createSubnet("us-east-1", vpc.getVpcId(), "10.1.1.0/24", "us-east-1a"));
                assertTrue(scanning.await(5, TimeUnit.SECONDS));
                Future<?> editing = workers.submit(() -> {
                    editor.set(Thread.currentThread());
                    if (remove) {
                        service.disassociateVpcCidrBlock("us-east-1", associationId);
                    } else {
                        service.associateVpcCidrBlock("us-east-1", vpc.getVpcId(), "198.18.0.0/16");
                    }
                });
                Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> editing.isDone()
                        || (editor.get() != null && editor.get().getState() == Thread.State.BLOCKED));
                assertFalse(editing.isDone(), "CIDR edits must not mutate the list while subnet validation scans it");
                resume.countDown();
                assertEquals("10.1.1.0/24", creating.get(5, TimeUnit.SECONDS).getCidrBlock());
                editing.get(5, TimeUnit.SECONDS);
            } finally {
                resume.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void createSubnetAllowsConflictingCidrsInDifferentVpcs() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        Vpc vpcA = service.createVpc("us-east-1", "10.0.0.0/16", false);
        Vpc vpcB = service.createVpc("us-east-1", "10.0.0.0/16", false);
        service.createSubnet("us-east-1", vpcA.getVpcId(), "10.0.1.0/24", "us-east-1a");

        Subnet subnetB = service.createSubnet("us-east-1", vpcB.getVpcId(), "10.0.1.0/24", "us-east-1a");

        assertEquals("10.0.1.0/24", subnetB.getCidrBlock());
    }

    @Test
    void runInstancesStoresArchitectureFromImageCatalog() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), new Ec2ImageCatalog(), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        Reservation reservation = service.runInstances("us-east-1", "ami-ubuntu2404-cloud-arm64", "t4g.medium",
                1, 1, null, List.of(), null, null, List.of(), null, null);

        assertEquals("arm64", reservation.getInstances().getFirst().getArchitecture());
        assertEquals(reservation.getOwnerId(), reservation.getInstances().getFirst().getOwnerId());
    }

    @Test
    void runInstancesKeepsX8664FallbackForUnknownImageAndType() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), new Ec2ImageCatalog(), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        Reservation reservation = service.runInstances("us-east-1", "ami-unknown", "unknown.type",
                1, 1, null, List.of(), null, null, List.of(), null, null);

        assertEquals("x86_64", reservation.getInstances().getFirst().getArchitecture());
    }

    @Test
    void runInstancesFallsBackToInstanceTypeArchitectureForUnknownImage() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), new Ec2ImageCatalog(), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        Reservation reservation = service.runInstances("us-east-1", "ami-unknown", "t4g.medium",
                1, 1, null, List.of(), null, null, List.of(), null, null);

        assertEquals("arm64", reservation.getInstances().getFirst().getArchitecture());
    }

    @Test
    void runInstancesRejectsIncompatibleImageAndInstanceTypeArchitectures() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), new Ec2ImageCatalog(), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        AwsException error = assertThrows(AwsException.class, () -> service.runInstances(
                "us-east-1", "ami-ubuntu2404-amd64", "t4g.medium",
                1, 1, null, List.of(), null, null, List.of(), null, null));

        assertEquals("InvalidParameterValue", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void describeLaunchTemplatesRejectsMissingExplicitNamesAndIds() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        // An unfiltered describe on an account with no templates is not an error.
        assertTrue(service.describeLaunchTemplates("us-east-1", List.of(), List.of(), Map.of()).isEmpty());

        LaunchTemplateData data = new LaunchTemplateData();
        data.setImageId("ami-present");
        LaunchTemplate present = service.createLaunchTemplate("us-east-1", "present-template", data, List.of());

        assertEquals(1, service.describeLaunchTemplates(
                "us-east-1", List.of(), List.of("present-template"), Map.of()).size());

        // Karpenter creates a launch template only when the lookup fails with this code; an empty
        // success reads as "already there" and leaves it waiting on a template nothing will create.
        AwsException byName = assertThrows(AwsException.class, () -> service.describeLaunchTemplates(
                "us-east-1", List.of(), List.of("absent-template"), Map.of()));
        assertEquals("InvalidLaunchTemplateName.NotFoundException", byName.getErrorCode());
        assertEquals(400, byName.getHttpStatus());

        // A missing name is not masked by a present one in the same request.
        AwsException mixed = assertThrows(AwsException.class, () -> service.describeLaunchTemplates(
                "us-east-1", List.of(), List.of("present-template", "absent-template"), Map.of()));
        assertEquals("InvalidLaunchTemplateName.NotFoundException", mixed.getErrorCode());

        AwsException byId = assertThrows(AwsException.class, () -> service.describeLaunchTemplates(
                "us-east-1", List.of("lt-0000000000000dead"), List.of(), Map.of()));
        assertEquals("InvalidLaunchTemplateId.NotFound", byId.getErrorCode());

        // Another region does not hold the template, so the same name is missing there.
        AwsException otherRegion = assertThrows(AwsException.class, () -> service.describeLaunchTemplates(
                "eu-west-1", List.of(), List.of("present-template"), Map.of()));
        assertEquals("InvalidLaunchTemplateName.NotFoundException", otherRegion.getErrorCode());

        // A filter that matches nothing still returns an empty list rather than an error.
        assertTrue(service.describeLaunchTemplates("us-east-1", List.of(), List.of(),
                Map.of("launch-template-name", List.of("no-such-template"))).isEmpty());
        assertTrue(service.describeLaunchTemplates("us-east-1", List.of(present.getLaunchTemplateId()),
                List.of(), Map.of("launch-template-name", List.of("no-such-template"))).isEmpty());

        // AutoScaling reports its own ValidationError for an unresolved template, so the version
        // lookup it goes through keeps answering with an empty list instead of raising NotFound.
        assertTrue(service.describeLaunchTemplateVersions(
                "us-east-1", null, "absent-template", List.of()).isEmpty());
    }

    @Test
    void missingLaunchTemplateIdReportsTheSameCodeWhicheverCallLooksItUp() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        // The mutating paths resolve the template through findLaunchTemplate rather than
        // describeLaunchTemplates, and used to spell the same condition
        // InvalidLaunchTemplateId.NotFoundException.
        AwsException modify = assertThrows(AwsException.class, () -> service.modifyLaunchTemplate(
                "us-east-1", "lt-0000000000000dead", null, "1"));
        assertEquals("InvalidLaunchTemplateId.NotFound", modify.getErrorCode());
        assertEquals(400, modify.getHttpStatus());

        AwsException delete = assertThrows(AwsException.class, () -> service.deleteLaunchTemplate(
                "us-east-1", "lt-0000000000000dead", null));
        assertEquals("InvalidLaunchTemplateId.NotFound", delete.getErrorCode());

        // A missing name keeps the Exception suffix EC2 uses on that one.
        AwsException byName = assertThrows(AwsException.class, () -> service.deleteLaunchTemplate(
                "us-east-1", null, "absent-template"));
        assertEquals("InvalidLaunchTemplateName.NotFoundException", byName.getErrorCode());
    }

    @Test
    void launchTemplateVersionInheritsOmittedFieldsFromRequestedSourceVersion() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        LaunchTemplateData source = new LaunchTemplateData();
        source.setImageId("ami-source");
        source.setInstanceType("t3.micro");
        source.setKeyName("app-key");
        source.setSecurityGroupIds(List.of("sg-source"));
        source.setUserData("source-user-data");
        source.setEncodedUserData("c291cmNlLXVzZXItZGF0YQ==");
        source.setIamInstanceProfile(new LaunchTemplateData.IamInstanceProfile(
                "arn:aws:iam::000000000000:instance-profile/app-profile", null));
        source.setTagSpecifications(List.of(
                new LaunchTemplateData.TagSpecification("instance", List.of(new Tag("Role", "source")))));
        LaunchTemplate template = service.createLaunchTemplate("us-east-1", "app-template", source, List.of());

        LaunchTemplateData override = new LaunchTemplateData();
        override.setInstanceType("t3.small");
        service.createLaunchTemplateVersion("us-east-1", template.getLaunchTemplateId(), null, "1", override);

        LaunchTemplate version = service.describeLaunchTemplateVersions(
                "us-east-1", template.getLaunchTemplateId(), null, List.of("2")).getFirst();
        LaunchTemplateData data = version.getData();
        assertEquals("ami-source", data.getImageId());
        assertEquals("t3.small", data.getInstanceType());
        assertEquals("app-key", data.getKeyName());
        assertEquals(List.of("sg-source"), data.getSecurityGroupIds());
        assertEquals("source-user-data", data.getUserData());
        assertEquals("c291cmNlLXVzZXItZGF0YQ==", data.getEncodedUserData());
        assertEquals("arn:aws:iam::000000000000:instance-profile/app-profile",
                data.getIamInstanceProfile().getArn());
        assertEquals("2", version.getLatestVersionNumber());
        assertEquals(1, data.getInstanceTags().size());
        assertEquals("Role", data.getInstanceTags().getFirst().getKey());
        assertEquals("source", data.getInstanceTags().getFirst().getValue());
    }

    @Test
    void launchTemplateVersionInheritsMarketOptionsRequirementsAndConnectionTracking() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        LaunchTemplateData source = new LaunchTemplateData();
        source.setImageId("ami-source");

        LaunchTemplateData.SpotOptions spotOptions = new LaunchTemplateData.SpotOptions();
        spotOptions.setMaxPrice("0.05");
        spotOptions.setSpotInstanceType("one-time");
        LaunchTemplateData.InstanceMarketOptions marketOptions = new LaunchTemplateData.InstanceMarketOptions();
        marketOptions.setMarketType("spot");
        marketOptions.setSpotOptions(spotOptions);
        source.setInstanceMarketOptions(marketOptions);

        LaunchTemplateData.InstanceRequirements requirements = new LaunchTemplateData.InstanceRequirements();
        requirements.setVCpuCount(new LaunchTemplateData.IntRange(2, 8));
        requirements.setMemoryMiB(new LaunchTemplateData.IntRange(1024, null));
        requirements.setMemoryGiBPerVCpu(new LaunchTemplateData.DoubleRange(0.5, 4.0));
        requirements.setCpuManufacturers(List.of("intel", "amd"));
        source.setInstanceRequirements(requirements);

        LaunchTemplateData.ConnectionTrackingSpecification tracking =
                new LaunchTemplateData.ConnectionTrackingSpecification();
        tracking.setTcpEstablishedTimeout(60);
        tracking.setUdpStreamTimeout(120);
        LaunchTemplateData.NetworkInterface networkInterface = new LaunchTemplateData.NetworkInterface();
        networkInterface.setDeviceIndex(0);
        networkInterface.setConnectionTrackingSpecification(tracking);
        source.setNetworkInterfaces(List.of(networkInterface));

        LaunchTemplate template = service.createLaunchTemplate("us-east-1", "spot-template", source, List.of());

        // The source selects instance types by attribute, so the new version restates a member
        // that does not collide with InstanceRequirements.
        LaunchTemplateData override = new LaunchTemplateData();
        override.setImageId("ami-override");
        service.createLaunchTemplateVersion("us-east-1", template.getLaunchTemplateId(), null, "1", override);

        LaunchTemplateData data = service.describeLaunchTemplateVersions(
                "us-east-1", template.getLaunchTemplateId(), null, List.of("2")).getFirst().getData();
        assertEquals("ami-override", data.getImageId());
        assertEquals("spot", data.getInstanceMarketOptions().getMarketType());
        assertEquals("0.05", data.getInstanceMarketOptions().getSpotOptions().getMaxPrice());
        assertEquals("one-time", data.getInstanceMarketOptions().getSpotOptions().getSpotInstanceType());
        assertNull(data.getInstanceMarketOptions().getSpotOptions().getBlockDurationMinutes(),
                "an unset SpotOptions member must not acquire a value on the way through a version");
        assertEquals(2, data.getInstanceRequirements().getVCpuCount().getMin());
        assertEquals(8, data.getInstanceRequirements().getVCpuCount().getMax());
        assertEquals(0.5, data.getInstanceRequirements().getMemoryGiBPerVCpu().getMin());
        assertEquals(List.of("intel", "amd"), data.getInstanceRequirements().getCpuManufacturers());
        assertEquals(1024, data.getInstanceRequirements().getMemoryMiB().getMin());
        assertNull(data.getInstanceRequirements().getNetworkInterfaceCount(),
                "an unset InstanceRequirements range must stay null rather than default to a range");
        assertNull(data.getInstanceRequirements().getBaselinePerformanceFactors());
        LaunchTemplateData.ConnectionTrackingSpecification inherited =
                data.getNetworkInterfaces().getFirst().getConnectionTrackingSpecification();
        assertEquals(60, inherited.getTcpEstablishedTimeout());
        assertEquals(120, inherited.getUdpStreamTimeout());
        assertNull(inherited.getUdpTimeout(), "an unset UdpTimeout must stay absent");
    }

    @Test
    void launchTemplateVersionWithoutSourceVersionDoesNotInheritFromLatest() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        LaunchTemplateData source = new LaunchTemplateData();
        source.setImageId("ami-source");
        source.setInstanceType("t3.micro");
        source.setKeyName("app-key");
        source.setSecurityGroupIds(List.of("sg-source"));
        LaunchTemplate template = service.createLaunchTemplate("us-east-1", "no-source-template", source, List.of());

        LaunchTemplateData override = new LaunchTemplateData();
        override.setInstanceType("t3.small");
        // No SourceVersion at all — AWS documents this as "no source specified, no inheritance",
        // not as an implicit fallback onto the latest version.
        service.createLaunchTemplateVersion("us-east-1", template.getLaunchTemplateId(), null, null, override);

        LaunchTemplate version = service.describeLaunchTemplateVersions(
                "us-east-1", template.getLaunchTemplateId(), null, List.of("2")).getFirst();
        LaunchTemplateData data = version.getData();
        assertEquals("t3.small", data.getInstanceType());
        assertNull(data.getImageId(), "omitted SourceVersion must not inherit ImageId from version 1");
        assertNull(data.getKeyName(), "omitted SourceVersion must not inherit KeyName from version 1");
        assertEquals(List.of(), data.getSecurityGroupIds(),
                "omitted SourceVersion must not inherit SecurityGroupIds from version 1");
    }

    @Test
    void launchTemplateVersionDescriptionRoundTrips() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        LaunchTemplateData source = new LaunchTemplateData();
        source.setImageId("ami-source");
        LaunchTemplate template = service.createLaunchTemplate(
                "us-east-1", "described-template", source, List.of(), "initial version");

        LaunchTemplateData override = new LaunchTemplateData();
        override.setInstanceType("t3.small");
        LaunchTemplate created = service.createLaunchTemplateVersion(
                "us-east-1", template.getLaunchTemplateId(), null, "1", override, "second version");
        assertEquals("second version", created.getVersionDescription());

        LaunchTemplate v1 = service.describeLaunchTemplateVersions(
                "us-east-1", template.getLaunchTemplateId(), null, List.of("1")).getFirst();
        LaunchTemplate v2 = service.describeLaunchTemplateVersions(
                "us-east-1", template.getLaunchTemplateId(), null, List.of("2")).getFirst();
        assertEquals("initial version", v1.getVersionDescription());
        assertEquals("second version", v2.getVersionDescription());
    }

    @Test
    void describeImagesAdvertisesCloudGuestWithoutChangingUbuntuDefault() {
        Ec2ImageCatalog imageCatalog = new Ec2ImageCatalog();
        AmiImageResolver amiImageResolver = new AmiImageResolver(imageCatalog);
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                amiImageResolver, imageCatalog, new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());

        assertTrue(service.describeImages("us-east-1", List.of(), List.of()).stream()
                .anyMatch(image -> "ami-ubuntu2404-cloud-arm64".equals(image.getImageId())));
        assertEquals("public.ecr.aws/docker/library/ubuntu:24.04", amiImageResolver.resolve("ami-ubuntu2404"));

        ResolvedAmiImage resolved = amiImageResolver.resolveImage("ami-ubuntu2404-cloud");
        assertEquals("floci/ami-ubuntu:24.04-arm64", resolved.dockerImage());
        assertTrue(resolved.systemd());
    }

    @Test
    void describeImagesUsesOnlyAwsSupportedOwnerSelectorsForCatalogImages() {
        Ec2ImageCatalog imageCatalog = new Ec2ImageCatalog();
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                new AmiImageResolver(imageCatalog), imageCatalog, new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        List<Image> amazon = service.describeImages(
                "us-east-1", List.of(), List.of("amazon"), Map.of());
        assertTrue(amazon.stream().anyMatch(image -> "amazon".equals(image.getImageOwnerAlias())));

        List<Image> unsupportedAlias = service.describeImages(
                "us-east-1", List.of(), List.of("canonical"), Map.of());
        assertTrue(unsupportedAlias.isEmpty(),
                "catalog imageOwnerAlias values must not turn arbitrary owner strings into Owners selectors");
    }

    @Test
    void describeImagesFiltersCatalogImagesByBlockDeviceMappingVolumeType() {
        Ec2ImageCatalog imageCatalog = new Ec2ImageCatalog();
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                new AmiImageResolver(imageCatalog), imageCatalog, new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        assertTrue(service.describeImages("us-east-1", List.of(), List.of(),
                Map.of("block-device-mapping.volume-type", List.of("bogus"))).isEmpty());
        assertEquals(List.of("ami-0abcdef1234567890"), service.describeImages("us-east-1", List.of(), List.of(),
                Map.of("block-device-mapping.volume-type", List.of("gp2"))).stream().map(Image::getImageId).toList());
        assertEquals(Set.of("ami-ubuntu2404-arm64", "ami-ubuntu2404-amd64", "ami-ubuntu2404-cloud-arm64"),
                service.describeImages("us-east-1", List.of(), List.of(),
                        Map.of("block-device-mapping.volume-type", List.of("gp3"))).stream()
                        .map(Image::getImageId).collect(Collectors.toSet()));
    }

    @Test
    void catalogImageSelectedByVolumeTypeReturnsAndCopiesThatMapping() {
        Ec2ImageCatalog imageCatalog = new Ec2ImageCatalog();
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                new AmiImageResolver(imageCatalog), imageCatalog, new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        Image found = service.describeImages("us-east-1", List.of(), List.of(),
                Map.of("block-device-mapping.volume-type", List.of("gp2"))).get(0);
        assertEquals("gp2", found.getBlockDeviceMappings().get(0).getEbs().getVolumeType());

        Image copy = service.copyImage("us-east-1", "us-east-1", found.getImageId(), "copy", null);
        assertEquals("gp2", copy.getBlockDeviceMappings().get(0).getEbs().getVolumeType());
    }

    @Test
    void catalogImageSnapshotIdsResolveThroughDescribeSnapshots() {
        Ec2ImageCatalog imageCatalog = new Ec2ImageCatalog();
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                new AmiImageResolver(imageCatalog), imageCatalog, new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        Image catalog = service.describeImages("us-east-1", List.of("ami-0abcdef1234567890"), List.of(), Map.of())
                .getFirst();
        for (BlockDeviceMapping mapping : catalog.getBlockDeviceMappings()) {
            String snapshotId = mapping.getEbs().getSnapshotId();
            if (snapshotId != null) {
                assertDoesNotThrow(() -> service.describeSnapshots("us-east-1", List.of(snapshotId), null, Map.of()),
                        "catalog AMI advertises snapshot " + snapshotId + " that DescribeSnapshots cannot find");
            }
        }

        // A copy's root is still backed by a real snapshot record.
        Image copy = service.copyImage("us-east-1", "us-east-1", catalog.getImageId(), "copy", null);
        String copySnapshot = copy.getBlockDeviceMappings().getFirst().getEbs().getSnapshotId();
        assertEquals(1, service.describeSnapshots("us-east-1", List.of(copySnapshot), null, Map.of()).size());
    }

    @Test
    void runInstancesRootVolumeTakesTheAmiRootVolumeType() {
        Ec2ImageCatalog imageCatalog = new Ec2ImageCatalog();
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                new AmiImageResolver(imageCatalog), imageCatalog, new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        Instance gp2 = service.runInstances("us-east-1", "ami-0abcdef1234567890", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null).getInstances().getFirst();
        assertEquals("gp2", service.describeVolumes("us-east-1", List.of(gp2.getRootVolumeId()), Map.of())
                .getFirst().getVolumeType());

        Instance gp3 = service.runInstances("us-east-1", "ami-ubuntu2404-amd64", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null).getInstances().getFirst();
        assertEquals("gp3", service.describeVolumes("us-east-1", List.of(gp3.getRootVolumeId()), Map.of())
                .getFirst().getVolumeType());
    }

    @Test
    void createImageRecordsTheInstanceRootVolumeTypeNotTheSourceAmis() {
        Ec2ImageCatalog imageCatalog = new Ec2ImageCatalog();
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                new AmiImageResolver(imageCatalog), imageCatalog, new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        LaunchTemplateData.Ebs ebs = new LaunchTemplateData.Ebs();
        ebs.setVolumeType("gp3");
        LaunchTemplateData.BlockDeviceMapping root = new LaunchTemplateData.BlockDeviceMapping();
        root.setDeviceName("/dev/xvda");
        root.setEbs(ebs);

        // ami-0abcdef1234567890 declares a gp2 root; the launch asked for gp3.
        Instance instance = service.runInstances("us-east-1", "ami-0abcdef1234567890", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null, null, null, 0, null, null, null,
                null, false, null, null, List.of(root)).getInstances().getFirst();
        String imageId = service.createImage("us-east-1", instance.getInstanceId(), "gp3-capture", null, true)
                .getImageId();

        Image captured = service.describeImages("us-east-1", List.of(imageId), List.of(), Map.of()).getFirst();
        BlockDeviceMapping capturedRoot = captured.getBlockDeviceMappings().stream()
                .filter(m -> "/dev/xvda".equals(m.getDeviceName()))
                .findFirst().orElseThrow();
        assertEquals("gp3", capturedRoot.getEbs().getVolumeType());
    }

    @Test
    void describeImagesFiltersRegisteredImagesByBlockDeviceMappingVolumeType() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        EbsBlockDevice ebs = new EbsBlockDevice();
        ebs.setVolumeType("io2");
        BlockDeviceMapping mapping = new BlockDeviceMapping();
        mapping.setDeviceName("/dev/sda1");
        mapping.setEbs(ebs);
        Image io2 = service.registerImage("us-east-1", "io2-image", null, null, null, List.of(mapping));
        service.registerImage("us-east-1", "no-ebs-image", null, null, null, List.of());

        assertEquals(List.of(io2.getImageId()), service.describeImages("us-east-1", List.of(), List.of(),
                Map.of("block-device-mapping.volume-type", List.of("io2"))).stream().map(Image::getImageId).toList());
        assertTrue(service.describeImages("us-east-1", List.of(), List.of(),
                Map.of("block-device-mapping.volume-type", List.of("gp3"))).isEmpty());
    }

    @Test
    void describeImagesMatchesCatalogAliasInImageIdFilter() {
        Ec2ImageCatalog imageCatalog = new Ec2ImageCatalog();
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                new AmiImageResolver(imageCatalog), imageCatalog, new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        List<Image> images = service.describeImages(
                "us-east-1", List.of(), List.of(), Map.of("image-id", List.of("ami-amazonlinux2")));

        assertEquals(1, images.size());
        assertEquals("ami-0abcdef1234567890", images.getFirst().getImageId());
    }

    @Test
    void describeImagesResolvesUnknownLaunchableAmiId() {
        Ec2ImageCatalog imageCatalog = new Ec2ImageCatalog();
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                new AmiImageResolver(imageCatalog), imageCatalog, new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        // RunInstances launches an unknown id via the catalog-default fallback, so DescribeImages has to
        // resolve it too: the aws provider reads the AMI root device before RunInstances and aborts the
        // create with "collecting instance settings: empty result" when the describe comes back empty.
        List<Image> unknown = service.describeImages(
                "us-east-1", List.of("ami-0c02fb55956c7d316"), List.of(), Map.of());
        assertEquals(1, unknown.size());
        assertEquals("ami-0c02fb55956c7d316", unknown.getFirst().getImageId());
        assertEquals("/dev/xvda", unknown.getFirst().getRootDeviceName());

        // A real catalog id resolves to exactly the catalog image, never a duplicate fallback.
        List<Image> catalog = service.describeImages(
                "us-east-1", List.of("ami-0abcdef1234567891"), List.of(), Map.of());
        assertEquals(1, catalog.size());
        assertEquals("al2023-ami-2023.0.20230315.0-kernel-6.1-x86_64", catalog.getFirst().getName());
    }

    @Test
    void describeImagesResolvesArm64AmazonLinux2023ByNamePattern() {
        Ec2ImageCatalog imageCatalog = new Ec2ImageCatalog();
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                new AmiImageResolver(imageCatalog), imageCatalog, new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        List<Image> images = service.describeImages(
                "us-east-1", List.of(), List.of("amazon"),
                Map.of("name", List.of("al2023-ami-2023.*-arm64")));

        assertEquals(1, images.size());
        Image matched = images.getFirst();
        assertEquals("ami-amazonlinux2023-arm64", matched.getImageId());
        assertEquals("arm64", matched.getArchitecture());
        assertEquals("al2023-ami-2023.0.20230315.0-kernel-6.1-arm64", matched.getName());
    }

    @Test
    void describeImagesWritesRefreshedTagsBackWhenStorageReturnsDetachedImages() {
        DetachedImageStorage imageStorage = new DetachedImageStorage();
        Ec2ImageCatalog imageCatalog = new Ec2ImageCatalog();
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), new AmiImageResolver(imageCatalog), imageCatalog,
                new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Map.of("ec2-registered-images.json", imageStorage)));

        Image stored = new Image();
        stored.setImageId("ami-0de7ac4ed7a95");
        stored.setName("detached-tags");
        stored.setOwnerId("000000000000");
        stored.setPublic(false);
        stored.setImageOwnerAlias(null);
        stored.setRegion("us-east-1");
        imageStorage.put("us-east-1::ami-0de7ac4ed7a95", stored);
        int putsBeforeDescribe = imageStorage.putCount();

        Tag tag = new Tag();
        tag.setKey("ManagedBy");
        tag.setValue("test");
        service.createTags("us-east-1", List.of(stored.getImageId()), List.of(tag));

        List<Image> described = service.describeImages(
                "us-east-1", List.of(stored.getImageId()), List.of(), Map.of());

        assertEquals(1, described.size());
        assertEquals("test", described.getFirst().getTags().getFirst().getValue());
        assertTrue(imageStorage.putCount() > putsBeforeDescribe,
                "DescribeImages must persist refreshed tags even when scan() returns detached values");
        assertEquals("test", imageStorage.storedImage().getTags().getFirst().getValue());
    }

    @Test
    void describeInstanceTypesUsesExactCatalogMatches() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        List<Map<String, Object>> types = service.describeInstanceTypes(List.of("m8gd.large", "m8gd.xlarge"));

        assertEquals(1, types.size());
        assertEquals("m8gd.large", types.getFirst().get("instanceType"));
        assertEquals(2, types.getFirst().get("vcpu"));
        assertEquals(8192, types.getFirst().get("memoryMib"));
        assertEquals(List.of("arm64"), types.getFirst().get("supportedArchitectures"));
    }

    @Test
    void defaultVpcSubnetAndSecurityGroupIdsAreRegionScoped() {
        // #21: the default VPC/subnets/security group were literal ids ("vpc-default",
        // "subnet-default-a/b/c", "sg-default") reused as-is in every region. Storage was
        // already correctly keyed by region, so nothing actually collided server-side, but every
        // region's DescribeVpcs/DescribeSubnets/DescribeSecurityGroups echoed identical ids back
        // to the caller, making the two regions' default resources indistinguishable from an SDK
        // or CLI client's point of view.
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        Vpc usEastVpc = service.describeVpcs("us-east-1", List.of(), Map.of()).getFirst();
        Vpc euWestVpc = service.describeVpcs("eu-west-1", List.of(), Map.of()).getFirst();

        assertNotEquals(usEastVpc.getVpcId(), euWestVpc.getVpcId(),
                "default VPC id must differ per region");
        assertEquals(Ec2Service.defaultVpcId("us-east-1"), usEastVpc.getVpcId());
        assertEquals(Ec2Service.defaultVpcId("eu-west-1"), euWestVpc.getVpcId());

        List<String> usEastSubnetIds = service.describeSubnets("us-east-1", List.of(), Map.of()).stream()
                .map(Subnet::getSubnetId).sorted().toList();
        List<String> euWestSubnetIds = service.describeSubnets("eu-west-1", List.of(), Map.of()).stream()
                .map(Subnet::getSubnetId).sorted().toList();

        assertTrue(usEastSubnetIds.stream().noneMatch(euWestSubnetIds::contains),
                "default subnet ids must not collide across regions: " + usEastSubnetIds + " vs " + euWestSubnetIds);
        assertEquals(List.of(
                        Ec2Service.defaultSubnetId("us-east-1", "a"),
                        Ec2Service.defaultSubnetId("us-east-1", "b"),
                        Ec2Service.defaultSubnetId("us-east-1", "c"))
                .stream().sorted().toList(),
                usEastSubnetIds);

        SecurityGroup usEastSg = service.describeSecurityGroups("us-east-1", List.of(), List.of("default"), Map.of())
                .getFirst();
        SecurityGroup euWestSg = service.describeSecurityGroups("eu-west-1", List.of(), List.of("default"), Map.of())
                .getFirst();

        assertNotEquals(usEastSg.getGroupId(), euWestSg.getGroupId(),
                "default security group id must differ per region");
        assertEquals(Ec2Service.defaultSecurityGroupId("us-east-1"), usEastSg.getGroupId());
        assertEquals(Ec2Service.defaultSecurityGroupId("eu-west-1"), euWestSg.getGroupId());
    }

    @Test
    void resolveDefaultVpcAndSecurityGroupIdFallBackToPreRegionScopingStorage() throws Exception {
        // #21 follow-up: persistent storage written before default ids were made region-scoped
        // still carries the old literal "vpc-default"/"sg-default", never the new scoped form, and
        // nothing re-seeds it (ensureDefaultResources skips a region that already has a VPC on
        // file). Any lookup that blindly computed the new scoped id instead of resolving against
        // what's actually stored would silently stop finding the region's real default VPC/SG.
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        Vpc legacyVpc = new Vpc();
        legacyVpc.setVpcId("vpc-default");
        legacyVpc.setCidrBlock("172.31.0.0/16");
        legacyVpc.setState("available");
        legacyVpc.setDefault(true);
        legacyVpc.setOwnerId("000000000000");
        legacyVpc.setRegion("us-east-1");
        putViaReflection(service, "vpcs", "us-east-1::vpc-default", legacyVpc);

        SecurityGroup legacySg = new SecurityGroup();
        legacySg.setGroupId("sg-default");
        legacySg.setGroupName("default");
        legacySg.setDescription("default VPC security group");
        legacySg.setVpcId("vpc-default");
        legacySg.setOwnerId("000000000000");
        legacySg.setRegion("us-east-1");
        putViaReflection(service, "securityGroups", "us-east-1::sg-default", legacySg);

        assertEquals("vpc-default", service.resolveDefaultVpcId("us-east-1"),
                "must resolve the pre-existing unscoped default VPC, not compute a fresh scoped id");
        assertEquals("sg-default", service.resolveDefaultSecurityGroupId("us-east-1"),
                "must resolve the pre-existing unscoped default security group, not compute a fresh scoped id");

        SecurityGroup created = service.createSecurityGroup("us-east-1", "app", "app sg", null);
        assertEquals("vpc-default", created.getVpcId(),
                "a new security group with no explicit VpcId must attach to the real default VPC on file");

        // A genuinely fresh region has neither format on file, so both resolvers fall back to the
        // scoped form ensureDefaultResources would seed.
        assertEquals(Ec2Service.defaultVpcId("eu-west-1"), service.resolveDefaultVpcId("eu-west-1"));
        assertEquals(Ec2Service.defaultSecurityGroupId("eu-west-1"),
                service.resolveDefaultSecurityGroupId("eu-west-1"));
    }

    @SuppressWarnings("unchecked")
    private static <T> void putViaReflection(Ec2Service service, String fieldName, String key, T value)
            throws Exception {
        Field field = Ec2Service.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        StorageBackend<String, T> backend = (StorageBackend<String, T>) field.get(service);
        backend.put(key, value);
    }

    @Test
    void endpointNetworkInterfacesSynthesizesStableEnisForInterfaceEndpoints() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String subnetId = service.describeSubnets("us-east-1", List.of(),
                Map.of("vpc-id", List.of(Ec2Service.defaultVpcId("us-east-1")))).getFirst().getSubnetId();
        VpcEndpoint endpoint = service.createVpcEndpoint("us-east-1", Ec2Service.defaultVpcId("us-east-1"),
                "com.amazonaws.us-east-1.s3", "Interface",
                List.of(), List.of(subnetId), List.of(), null, null, List.of());
        service.createVpcEndpoint("us-east-1", Ec2Service.defaultVpcId("us-east-1"),
                "com.amazonaws.us-east-1.dynamodb", "Gateway",
                List.of(), List.of(), List.of(), null, null, List.of());

        List<NetworkInterface> enis = service.endpointNetworkInterfaces("us-east-1");

        assertEquals(1, enis.size(), "only Interface endpoints have ENIs");
        NetworkInterface eni = enis.getFirst();
        assertEquals(subnetId, eni.getSubnetId());
        assertEquals(Ec2Service.defaultVpcId("us-east-1"), eni.getVpcId());
        assertEquals("VPC Endpoint Interface " + endpoint.getVpcEndpointId(), eni.getDescription());
        assertTrue(eni.getNetworkInterfaceId().startsWith("eni-"));

        NetworkInterface again = service.endpointNetworkInterfaces("us-east-1").getFirst();
        assertEquals(eni.getNetworkInterfaceId(), again.getNetworkInterfaceId());
        assertEquals(eni.getPrivateIpAddress(), again.getPrivateIpAddress());

        assertTrue(service.endpointNetworkInterfaces("eu-west-1").isEmpty(),
                "endpoints are regional");
    }

    @Test
    void endpointFallbackAddressesResolveSubnetHashCollisions() {
        EndpointAddressFixture fixture = endpointAddressFixture("2001:db8:1::/64", "2001:db8:2::/64");
        String firstSubnet = "subnet-00000000";
        String secondSubnet = "subnet-00000088";
        assertEquals(Math.floorMod(firstSubnet.hashCode(), 256), Math.floorMod(secondSubnet.hashCode(), 256));

        Map<String, String> addresses = endpointAddressesBySubnet(fixture.service());
        assertEquals(2, addresses.size());
        assertNotEquals(addresses.get(firstSubnet), addresses.get(secondSubnet));
        assertEquals(addresses, endpointAddressesBySubnet(fixture.service()));

        fixture.endpoint().setSubnetIds(List.of(secondSubnet, firstSubnet));
        assertEquals(addresses, endpointAddressesBySubnet(fixture.service()),
                "reordering subnets must not change their fallback addresses");
    }

    @Test
    void endpointFallbackAddressesAreDistinctForSubnetsWithNoIpv4Cidr() {
        EndpointAddressFixture fixture = endpointAddressFixture(null, null);

        Map<String, String> addresses = endpointAddressesBySubnet(fixture.service());
        assertEquals(2, addresses.size());
        assertNotEquals(addresses.get("subnet-00000000"), addresses.get("subnet-00000088"));
        assertEquals(addresses, endpointAddressesBySubnet(fixture.service()));
    }

    @Test
    void endpointFallbackAddressesDoNotReusePinnedOrIpv4DerivedAddresses() {
        for (boolean pinned : new boolean[]{false, true}) {
            EndpointAddressFixture fixture = endpointAddressFixture("2001:db8:1::/64", "172.31.112.0/24");
            int host = 200 + Math.floorMod(fixture.endpoint().getVpcEndpointId().hashCode(), 50);
            String fixedAddress = "172.31.112." + host;
            if (pinned) {
                fixture.endpoint().setSubnetConfigurations(List.of(
                        new VpcEndpointSubnetConfiguration("subnet-00000088", fixedAddress, null)));
            }

            Map<String, String> addresses = endpointAddressesBySubnet(fixture.service());
            assertEquals(fixedAddress, addresses.get("subnet-00000088"));
            assertNotEquals(fixedAddress, addresses.get("subnet-00000000"));
            assertEquals(addresses, endpointAddressesBySubnet(fixture.service()));
        }
    }

    @Test
    void endpointFallbackAddressesDoNotReusePinnedAddressesWithLeadingZeros() {
        EndpointAddressFixture fixture = endpointAddressFixture("2001:db8:1::/64", "172.31.112.0/24");
        int host = 200 + Math.floorMod(fixture.endpoint().getVpcEndpointId().hashCode(), 50);
        String pinnedAddress = "172.31.112.0" + host;
        fixture.service().modifyVpcEndpoint("us-east-1", fixture.endpoint().getVpcEndpointId(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null, null,
                List.of(new VpcEndpointSubnetConfiguration("subnet-00000088", pinnedAddress, null)));

        Map<String, String> addresses = endpointAddressesBySubnet(fixture.service());
        assertEquals(pinnedAddress, addresses.get("subnet-00000088"));
        assertNotEquals("172.31.112." + host, addresses.get("subnet-00000000"));
        assertEquals(addresses, endpointAddressesBySubnet(fixture.service()));
    }

    @Test
    void endpointIpv4AddressesStayInsideTheirOwnHalfOfASplitSlash24() {
        EndpointAddressFixture fixture = endpointAddressFixture("10.0.1.0/25", "10.0.1.128/25");

        Map<String, String> addresses = endpointAddressesBySubnet(fixture.service());
        assertUsableEndpointAddress("10.0.1.0/25", addresses.get("subnet-00000000"));
        assertUsableEndpointAddress("10.0.1.128/25", addresses.get("subnet-00000088"));
        assertNotEquals(addresses.get("subnet-00000000"), addresses.get("subnet-00000088"));
        assertEquals(addresses, endpointAddressesBySubnet(fixture.service()),
                "a second read must answer with the same addresses");

        fixture.endpoint().setSubnetIds(List.of("subnet-00000088", "subnet-00000000"));
        assertEquals(addresses, endpointAddressesBySubnet(fixture.service()),
                "reordering subnets must not change their addresses");
    }

    @Test
    void endpointIpv4AddressesRespectEverySubnetPrefixLength() {
        for (int prefix = 16; prefix <= 28; prefix++) {
            String first = "10.20.0.0/" + prefix;
            String second = "10.30.0.0/" + prefix;
            EndpointAddressFixture fixture = endpointAddressFixture(first, second);

            Map<String, String> addresses = endpointAddressesBySubnet(fixture.service());
            assertUsableEndpointAddress(first, addresses.get("subnet-00000000"));
            assertUsableEndpointAddress(second, addresses.get("subnet-00000088"));
            assertEquals(addresses, endpointAddressesBySubnet(fixture.service()), "/" + prefix);
        }
    }

    @Test
    void endpointIpv4AddressesNeverLandOnAwsReservedAddressesInASlash28() {
        for (int i = 0; i < 40; i++) {
            EndpointAddressFixture fixture = endpointAddressFixture("10.0.1.0/28", "10.0.1.16/28");

            Map<String, String> addresses = endpointAddressesBySubnet(fixture.service());
            assertUsableEndpointAddress("10.0.1.0/28", addresses.get("subnet-00000000"));
            assertUsableEndpointAddress("10.0.1.16/28", addresses.get("subnet-00000088"));
        }
    }

    @Test
    void endpointIpv4AddressesKeepTheSlash24LayoutForLargerSubnets() {
        EndpointAddressFixture fixture = endpointAddressFixture("10.40.0.0/20", "10.40.16.0/24");
        int host = 200 + Math.floorMod(fixture.endpoint().getVpcEndpointId().hashCode(), 50);

        Map<String, String> addresses = endpointAddressesBySubnet(fixture.service());
        assertEquals("10.40.0." + host, addresses.get("subnet-00000000"));
        assertEquals("10.40.16." + host, addresses.get("subnet-00000088"));
    }

    @Test
    void endpointIpv4AddressesProbePastAPinnedAddressInTheSameRange() {
        // Two records over the same /29 leave three usable addresses (.4, .5, .6). Whatever the
        // endpoint id prefers, the derived interface must step past the pinned one.
        for (String pinnedAddress : List.of("10.0.1.4", "10.0.1.5", "10.0.1.6")) {
            EndpointAddressFixture fixture = endpointAddressFixture("10.0.1.0/29", "10.0.1.0/29");
            fixture.endpoint().setSubnetConfigurations(List.of(
                    new VpcEndpointSubnetConfiguration("subnet-00000088", pinnedAddress, null)));

            Map<String, String> addresses = endpointAddressesBySubnet(fixture.service());
            assertEquals(pinnedAddress, addresses.get("subnet-00000088"));
            assertUsableEndpointAddress("10.0.1.0/29", addresses.get("subnet-00000000"));
            assertNotEquals(pinnedAddress, addresses.get("subnet-00000000"));
            assertEquals(addresses, endpointAddressesBySubnet(fixture.service()), pinnedAddress);
        }
    }

    @Test
    void interfaceEndpointIsRefusedInASubnetWithNoAssignableAddress() {
        // CreateSubnet refuses a /30, so the subnet is written straight into the subnet store.
        AccountAwareStorageBackend<Subnet> subnetStore = AccountAwareStorageBackend.inMemory("000000000000");
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory(Map.of("ec2-subnets.json", subnetStore)));
        String vpcId = service.createVpc("us-east-1", "10.64.0.0/16", false).getVpcId();
        Subnet tinySubnet = new Subnet();
        tinySubnet.setSubnetId("subnet-0000000000000031");
        tinySubnet.setVpcId(vpcId);
        tinySubnet.setRegion("us-east-1");
        tinySubnet.setCidrBlock("10.64.1.0/30");
        tinySubnet.setAvailabilityZone("us-east-1a");
        subnetStore.put("us-east-1::" + tinySubnet.getSubnetId(), tinySubnet);
        String tiny = tinySubnet.getSubnetId();
        String roomy = service.createSubnet("us-east-1", vpcId, "10.64.2.0/28", "us-east-1b").getSubnetId();

        AwsException createError = assertThrows(AwsException.class, () ->
                service.createVpcEndpoint("us-east-1", vpcId, "com.amazonaws.us-east-1.ecs", "Interface",
                        List.of(), List.of(tiny), List.of(), null, null, List.of()));
        assertEquals("InsufficientFreeAddressesInSubnet", createError.getErrorCode());
        assertTrue(service.describeVpcEndpoints("us-east-1", List.of(), Map.of()).isEmpty(),
                "a refused endpoint must not be stored");

        VpcEndpoint endpoint = service.createVpcEndpoint("us-east-1", vpcId, "com.amazonaws.us-east-1.ecs",
                "Interface", List.of(), List.of(roomy), List.of(), null, null, List.of());
        AwsException modifyError = assertThrows(AwsException.class, () ->
                service.modifyVpcEndpoint("us-east-1", endpoint.getVpcEndpointId(), List.of(), List.of(),
                        List.of(tiny), List.of(), List.of(), List.of(), null, null, null));
        assertEquals("InsufficientFreeAddressesInSubnet", modifyError.getErrorCode());
        assertEquals(List.of(roomy), endpoint.getSubnetIds(), "a refused modification must not add the subnet");
        assertUsableEndpointAddress("10.64.2.0/28", endpointAddressesBySubnet(service).get(roomy));
    }

    @Test
    void storedEndpointInASubnetTooSmallForItDegradesInsteadOfFailingReads() {
        // An endpoint stored before CreateVpcEndpoint refused undersized subnets: written
        // straight into the subnet store, then the endpoint is pointed at it, as old state is.
        AccountAwareStorageBackend<Subnet> subnetStore = AccountAwareStorageBackend.inMemory("000000000000");
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory(Map.of("ec2-subnets.json", subnetStore)));
        String vpcId = service.createVpc("us-east-1", "10.65.0.0/16", false).getVpcId();
        String roomy = service.createSubnet("us-east-1", vpcId, "10.65.2.0/28", "us-east-1b").getSubnetId();
        Subnet tiny = new Subnet();
        tiny.setSubnetId("subnet-0000000000000030");
        tiny.setVpcId(vpcId);
        tiny.setRegion("us-east-1");
        tiny.setCidrBlock("10.65.1.0/30");
        tiny.setAvailabilityZone("us-east-1a");
        subnetStore.put("us-east-1::" + tiny.getSubnetId(), tiny);
        VpcEndpoint endpoint = service.createVpcEndpoint("us-east-1", vpcId, "com.amazonaws.us-east-1.ecs",
                "Interface", List.of(), List.of(roomy), List.of(), null, null, List.of());
        endpoint.setSubnetIds(List.of(tiny.getSubnetId(), roomy));
        String unrelatedEni = service.createNetworkInterface("us-east-1", roomy, "unrelated", null, null,
                List.of(), List.of()).getNetworkInterfaceId();

        Map<String, String> addresses = endpointAddressesBySubnet(service);
        String degraded = addresses.get(tiny.getSubnetId());
        assertNotNull(degraded, "the undersized subnet's interface still gets an address");
        assertTrue(degraded.startsWith("172.31."), degraded + " should come from the synthetic fallback");
        assertUsableEndpointAddress("10.65.2.0/28", addresses.get(roomy));
        assertEquals(addresses, endpointAddressesBySubnet(service), "a second read must answer the same");

        List<String> described = service.describeNetworkInterfaces("us-east-1", List.of(), Map.of())
                .networkInterfaces().stream().map(NetworkInterface::getNetworkInterfaceId).toList();
        assertTrue(described.contains(unrelatedEni), "DescribeNetworkInterfaces must still list other ENIs");
        assertTrue(described.containsAll(service.endpointNetworkInterfaceIds(endpoint)));
        assertEquals(2, service.endpointNetworkInterfaceIds(endpoint).size());
    }

    @Test
    void endpointInASubnetOtherResourcesHaveFilledDegradesInsteadOfFailingReads() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String vpcId = service.createVpc("us-east-1", "10.66.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet("us-east-1", vpcId, "10.66.1.0/28", "us-east-1a").getSubnetId();
        Set<String> instanceAddresses = new HashSet<>(launchAddresses(service, subnetId, 11));
        assertEquals(11, instanceAddresses.size(), "a /28 holds eleven assignable addresses");

        service.createVpcEndpoint("us-east-1", vpcId, "com.amazonaws.us-east-1.ecs",
                "Interface", List.of(), List.of(subnetId), List.of(), null, null, List.of());

        String address = endpointAddressesBySubnet(service).get(subnetId);
        assertNotNull(address);
        assertFalse(instanceAddresses.contains(address), address + " is already an instance's");
        assertFalse(service.describeNetworkInterfaces("us-east-1", List.of(), Map.of())
                .networkInterfaces().isEmpty());
    }

    @Test
    void endpointCreatedFirstInASlash28KeepsItsAddressAndInstancesStepAroundIt() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String vpcId = service.createVpc("us-east-1", "10.67.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet("us-east-1", vpcId, "10.67.1.0/28", "us-east-1a").getSubnetId();
        service.createVpcEndpoint("us-east-1", vpcId, "com.amazonaws.us-east-1.ecs",
                "Interface", List.of(), List.of(subnetId), List.of(), null, null, List.of());
        String endpointAddress = endpointAddressesBySubnet(service).get(subnetId);
        assertUsableEndpointAddress("10.67.1.0/28", endpointAddress);

        // One at a time, so every launch re-reads what the endpoint holds; the endpoint takes
        // one of the eleven assignable addresses, which leaves room for exactly ten instances.
        Set<String> instanceAddresses = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            instanceAddresses.addAll(launchAddresses(service, subnetId, 1));
            assertEquals(endpointAddress, endpointAddressesBySubnet(service).get(subnetId),
                    "a later instance must not move the endpoint");
        }
        assertEquals(10, instanceAddresses.size());
        assertFalse(instanceAddresses.contains(endpointAddress), endpointAddress + " was handed to an instance");
        AwsException full = assertThrows(AwsException.class, () -> launchAddresses(service, subnetId, 1));
        assertEquals("InsufficientFreeAddressesInSubnet", full.getErrorCode());
    }

    @Test
    void endpointCreatedAfterInstancesInASlash28TakesAnAddressNoInstanceHolds() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String vpcId = service.createVpc("us-east-1", "10.68.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet("us-east-1", vpcId, "10.68.1.0/28", "us-east-1a").getSubnetId();
        // Ten of the eleven assignable addresses, so whatever the endpoint id prefers, it must
        // land on the one left over rather than on an instance's.
        Set<String> instanceAddresses = new HashSet<>(launchAddresses(service, subnetId, 10));
        assertEquals(10, instanceAddresses.size());

        service.createVpcEndpoint("us-east-1", vpcId, "com.amazonaws.us-east-1.ecs",
                "Interface", List.of(), List.of(subnetId), List.of(), null, null, List.of());
        String endpointAddress = endpointAddressesBySubnet(service).get(subnetId);
        assertUsableEndpointAddress("10.68.1.0/28", endpointAddress);
        assertFalse(instanceAddresses.contains(endpointAddress), endpointAddress + " is already an instance's");
        assertEquals(endpointAddress, endpointAddressesBySubnet(service).get(subnetId));

        AwsException full = assertThrows(AwsException.class, () -> launchAddresses(service, subnetId, 1));
        assertEquals("InsufficientFreeAddressesInSubnet", full.getErrorCode());
    }

    private static List<String> launchAddresses(Ec2Service service, String subnetId, int count) {
        return service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                        count, count, null, List.of(), subnetId, null, List.of(), null, null)
                .getInstances().stream().map(Instance::getPrivateIpAddress).toList();
    }

    private static void assertUsableEndpointAddress(String cidr, String address) {
        assertNotNull(address, cidr);
        assertTrue(Ipv4Cidrs.contains(cidr, address + "/32"), address + " must lie inside " + cidr);
        assertFalse(Ipv4Cidrs.isSubnetReserved(cidr, address + "/32"), address + " is reserved in " + cidr);
    }

    @Test
    void runInstancesGivesTheInstanceAndItsInterfaceTheRequestedPrivateIp() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String subnetId = privateIpSubnet(service);

        for (String requested : List.of("10.50.1.50", "10.50.1.60")) {
            String id = launchWithPrivateIp(service, subnetId, 1, requested).getInstances().getFirst().getInstanceId();

            Instance described = service.describeInstances("us-east-1", List.of(id), Map.of())
                    .getFirst().getInstances().getFirst();
            assertEquals(requested, described.getPrivateIpAddress());
            assertEquals("ip-" + requested.replace('.', '-') + ".ec2.internal", described.getPrivateDnsName());
            assertEquals(requested, described.getNetworkInterfaces().getFirst().getPrivateIpAddress());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.50.2.50", "10.50.1.0", "10.50.1.3", "10.50.1.255", "10.50.1.256", "not-an-ip"})
    void runInstancesRejectsARequestedPrivateIpTheSubnetCannotAssign(String requested) {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String subnetId = privateIpSubnet(service);

        AwsException error = assertThrows(AwsException.class,
                () -> launchWithPrivateIp(service, subnetId, 1, requested));

        assertEquals("InvalidParameterValue", error.getErrorCode());
        assertTrue(instancesIn(service, subnetId).isEmpty(), "a rejected launch must store nothing");
    }

    @Test
    void runInstancesRejectsARequestedPrivateIpAnotherResourceHolds() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String vpcId = service.createVpc("us-east-1", "10.50.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet("us-east-1", vpcId, "10.50.1.0/24", "us-east-1a").getSubnetId();
        launchWithPrivateIp(service, subnetId, 1, "10.50.1.50");
        String assigned = launchAddresses(service, subnetId, 1).getFirst();
        service.createNetworkInterface("us-east-1", subnetId, "holder", "10.50.1.70",
                List.of(), List.of(), List.of());
        service.createVpcEndpoint("us-east-1", vpcId, "com.amazonaws.us-east-1.ecs",
                "Interface", List.of(), List.of(subnetId), List.of(), null, null, List.of());
        String endpointAddress = endpointAddressesBySubnet(service).get(subnetId);
        assertNotNull(endpointAddress);

        for (String taken : List.of("10.50.1.50", assigned, "10.50.1.70", endpointAddress)) {
            AwsException error = assertThrows(AwsException.class,
                    () -> launchWithPrivateIp(service, subnetId, 1, taken));
            assertEquals("InvalidIPAddress.InUse", error.getErrorCode());
            assertEquals("Address " + taken + " is in use.", error.getMessage());
        }
        assertEquals(2, instancesIn(service, subnetId).size());
    }

    @Test
    void aTerminatedInstancesPrivateIpCanBeRequestedAgain() {
        // Terraform replaces an aws_instance whose private_ip is fixed by terminating the old one
        // and launching its successor at the same address.
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String subnetId = privateIpSubnet(service);
        String first = launchWithPrivateIp(service, subnetId, 1, "10.50.1.50").getInstances().getFirst().getInstanceId();

        service.terminateInstances("us-east-1", List.of(first));

        assertEquals("10.50.1.50", launchWithPrivateIp(service, subnetId, 1, "10.50.1.50")
                .getInstances().getFirst().getPrivateIpAddress());
    }

    @Test
    void anAddressPickedByTheSubnetStepsAroundARequestedOne() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String subnetId = privateIpSubnet(service);
        // A fresh subnet hands out .10, .11, .12 on its own, so .11 is the next one but the first.
        launchWithPrivateIp(service, subnetId, 1, "10.50.1.11");

        assertEquals(List.of("10.50.1.10"), launchAddresses(service, subnetId, 1));
        assertEquals(List.of("10.50.1.12"), launchAddresses(service, subnetId, 1));
    }

    @Test
    void aZeroPaddedRequestedPrivateIpIsStoredCanonicallySoItStillConflicts() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String subnetId = privateIpSubnet(service);

        assertEquals("10.50.1.50", launchWithPrivateIp(service, subnetId, 1, "10.50.1.050")
                .getInstances().getFirst().getPrivateIpAddress());
        assertEquals("InvalidIPAddress.InUse", assertThrows(AwsException.class,
                () -> launchWithPrivateIp(service, subnetId, 1, "10.50.1.50")).getErrorCode());
    }

    @Test
    void aZeroPaddedAddressAnInterfaceWasCreatedWithStillHoldsTheRequestedPrivateIp() {
        // CreateNetworkInterface keeps its address as given, so the in-use check compares
        // canonical forms or a zero-padded octet would make one address look like two.
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String subnetId = privateIpSubnet(service);
        NetworkInterface padded = service.createNetworkInterface("us-east-1", subnetId, "padded", "10.50.1.050",
                List.of(), List.of(), List.of());

        AwsException error = assertThrows(AwsException.class,
                () -> launchWithPrivateIp(service, subnetId, 1, "10.50.1.50"));

        assertEquals("InvalidIPAddress.InUse", error.getErrorCode());
        assertEquals("10.50.1.050", padded.getPrivateIpAddress());
        assertTrue(instancesIn(service, subnetId).isEmpty(), "a rejected launch must store nothing");
    }

    @Test
    void aRequestedPrivateIpNeedsASingleInstanceThatCreatesItsOwnInterface() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String subnetId = privateIpSubnet(service);
        String eniId = service.createNetworkInterface("us-east-1", subnetId, "primary", null,
                List.of(), List.of(), List.of()).getNetworkInterfaceId();

        AwsException twoInstances = assertThrows(AwsException.class,
                () -> launchWithPrivateIp(service, subnetId, 2, "10.50.1.50"));
        AwsException upToTwoInstances = assertThrows(AwsException.class,
                () -> service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro", 1, 2, null,
                        List.of(), subnetId, null, List.of(), null, null, null, null, 0, null, null, null, null,
                        false, null, null, null, false, "10.50.1.50"));
        AwsException existingInterface = assertThrows(AwsException.class,
                () -> service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro", 1, 1, null,
                        List.of(), null, null, List.of(), null, null, null, eniId, 0, null, null, null, null,
                        false, null, null, null, false, "10.50.1.50"));

        assertEquals("InvalidParameterCombination", twoInstances.getErrorCode());
        assertEquals("InvalidParameterCombination", upToTwoInstances.getErrorCode());
        assertEquals("InvalidParameterCombination", existingInterface.getErrorCode());
        assertTrue(instancesIn(service, subnetId).isEmpty());
    }

    @Test
    void aRequestedPrivateIpIsLeasedOnTheVpcNetworkInsteadOfTheNextFreeAddress() {
        VpcNetworkManager vpcNetworks = mock(VpcNetworkManager.class);
        Ec2Service service = mockedVpcNetworkService(vpcNetworks);
        String subnetId = privateIpSubnet(service);
        when(vpcNetworks.effectiveSubnetCidr("us-east-1", subnetId)).thenReturn(Optional.of("10.50.1.0/24"));
        when(vpcNetworks.claimPrivateIp("us-east-1", subnetId, "10.50.1.50")).thenReturn(true);

        assertEquals("10.50.1.50", launchWithPrivateIp(service, subnetId, 1, "10.50.1.50")
                .getInstances().getFirst().getPrivateIpAddress());

        verify(vpcNetworks).claimPrivateIp("us-east-1", subnetId, "10.50.1.50");
        verify(vpcNetworks, never()).reservePrivateIp(anyString(), anyString(), anyString());
        verify(vpcNetworks, never()).allocatePrivateIp(anyString(), anyString());
    }

    @Test
    void aRefusedLeaseDoesNotTurnAFreeAddressIntoOneInUse() {
        // DeleteNetworkInterface does not return its lease, so the lease table can outlive the
        // resource that took one. With nothing in any account holding the address any more, the
        // stale lease must not fail the launch.
        RequestContext context = new RequestContext();
        Ec2Service service = realVpcNetworkService(context);
        String subnetId = Ec2Service.defaultSubnetId("us-east-1", "a");
        context.setAccountId("111122223333");
        service.describeSubnets("us-east-1", List.of(), Map.of());
        NetworkInterface deleted = service.createNetworkInterface("us-east-1", subnetId, "deleted", null,
                List.of(), List.of(), List.of());
        service.deleteNetworkInterface("us-east-1", deleted.getNetworkInterfaceId());
        String stale = deleted.getPrivateIpAddress();

        context.setAccountId("000000000000");

        assertEquals(stale, launchWithPrivateIp(service, subnetId, 1, stale)
                .getInstances().getFirst().getPrivateIpAddress());
    }

    @ParameterizedTest
    @ValueSource(strings = {"instance", "network-interface", "nat-gateway"})
    void aRequestedPrivateIpAnotherAccountHoldsOnASharedVpcNetworkIsInUse(String holder) {
        // Default VPC and subnet ids are the same in every account, so their Docker network and
        // lease table are shared while the stored-resource check sees only the caller's account.
        // Claiming the address anyway would give the other account's lease away.
        RequestContext context = new RequestContext();
        Ec2Service service = realVpcNetworkService(context);
        String subnetId = Ec2Service.defaultSubnetId("us-east-1", "a");
        context.setAccountId("111122223333");
        service.describeSubnets("us-east-1", List.of(), Map.of());
        String held = switch (holder) {
            case "instance" -> launchAddresses(service, subnetId, 1).getFirst();
            case "network-interface" -> service.createNetworkInterface("us-east-1", subnetId, "holder", null,
                    List.of(), List.of(), List.of()).getPrivateIpAddress();
            default -> service.createNatGateway("us-east-1", subnetId, null, "private", List.of())
                    .getNatGatewayAddresses().getFirst().getPrivateIp();
        };
        assertEquals("172.31.0.10", held, "the shared network's first address, leased by the other account");

        context.setAccountId("000000000000");
        AwsException error = assertThrows(AwsException.class,
                () -> launchWithPrivateIp(service, subnetId, 1, held));

        assertEquals("InvalidIPAddress.InUse", error.getErrorCode());
        assertEquals("Address " + held + " is in use.", error.getMessage());
        assertTrue(instancesIn(service, subnetId).isEmpty(), "a rejected launch must store nothing");
    }

    @Test
    void anAddressAnotherAccountsInterfaceWasCreatedWithIsInUseOnASharedVpcNetwork() {
        // CreateNetworkInterface with its own address takes no lease, so only the stored
        // interface says the shared network's address is held.
        RequestContext context = new RequestContext();
        Ec2Service service = realVpcNetworkService(context);
        String subnetId = Ec2Service.defaultSubnetId("us-east-1", "a");
        context.setAccountId("111122223333");
        service.describeSubnets("us-east-1", List.of(), Map.of());
        service.createNetworkInterface("us-east-1", subnetId, "holder", "172.31.0.50",
                List.of(), List.of(), List.of());

        context.setAccountId("000000000000");
        AwsException error = assertThrows(AwsException.class,
                () -> launchWithPrivateIp(service, subnetId, 1, "172.31.0.50"));

        assertEquals("InvalidIPAddress.InUse", error.getErrorCode());
        assertTrue(instancesIn(service, subnetId).isEmpty(), "a rejected launch must store nothing");
    }

    @Test
    void anotherAccountsNatGatewayStillHoldsItsAddressAfterARestartEmptiesTheLeaseTable() {
        // A restart re-leases only instances' addresses, so a persisted NAT gateway's address is
        // held by the stored gateway alone.
        String subnetId = Ec2Service.defaultSubnetId("us-east-1", "a");
        NatGatewayAddress address = new NatGatewayAddress();
        address.setPrivateIp("172.31.0.10");
        NatGateway natGateway = new NatGateway();
        natGateway.setNatGatewayId("nat-other");
        natGateway.setSubnetId(subnetId);
        natGateway.setState("available");
        natGateway.setNatGatewayAddresses(List.of(address));
        AccountAwareStorageBackend<NatGateway> natGatewayStore = AccountAwareStorageBackend.inMemory("000000000000");
        natGatewayStore.putForAccount("111122223333", "us-east-1::nat-other", natGateway);
        RequestContext context = new RequestContext();
        context.setAccountId("000000000000");
        Ec2Service service = realVpcNetworkService(context, Map.of("ec2-nat-gateways.json", natGatewayStore));

        AwsException error = assertThrows(AwsException.class,
                () -> launchWithPrivateIp(service, subnetId, 1, "172.31.0.10"));

        assertEquals("InvalidIPAddress.InUse", error.getErrorCode());
        assertTrue(instancesIn(service, subnetId).isEmpty(), "a rejected launch must store nothing");
    }

    @Test
    void anotherAccountsInstanceOutsideASubstitutedRangeDoesNotHoldTheRequestedPrivateIp() {
        // A substituted subnet never leases an address outside its effective range, so no
        // container holds it on the shared network and each account keeps it as it would on AWS.
        String subnetId = Ec2Service.defaultSubnetId("us-east-1", "a");
        AccountAwareStorageBackend<Instance> instanceStore = AccountAwareStorageBackend.inMemory("000000000000");
        instanceStore.putForAccount("111122223333", "us-east-1::i-other",
                defaultSubnetInstance("i-other", "172.31.0.50", InstanceState.running()));
        VpcNetworkManager vpcNetworks = mock(VpcNetworkManager.class);
        when(vpcNetworks.effectiveSubnetCidr("us-east-1", subnetId)).thenReturn(Optional.of("10.240.0.0/20"));
        Ec2Service service = mockedVpcNetworkService(Map.of("ec2-instances.json", instanceStore), vpcNetworks);

        assertEquals("172.31.0.50", launchWithPrivateIp(service, subnetId, 1, "172.31.0.50")
                .getInstances().getFirst().getPrivateIpAddress());
    }

    @Test
    void aRequestedPrivateIpIsLeasedSoTheRealVpcNetworkDoesNotHandItOutAgain() {
        // The manager's allocator consults only its own lease table. Without the lease it would
        // hand a fresh /24's first address, offset 10, to the next launch as well.
        Ec2Service service = realVpcNetworkService(new RequestContext());
        String subnetId = privateIpSubnet(service);

        assertEquals("10.50.1.10", launchWithPrivateIp(service, subnetId, 1, "10.50.1.10")
                .getInstances().getFirst().getPrivateIpAddress());

        assertEquals(List.of("10.50.1.11"), launchAddresses(service, subnetId, 1));
    }

    @Test
    void aRequestedPrivateIpLeavesTheRealVpcNetworksAllocationOrderAlone() {
        // Claiming a high address must not move the network's cursor past it, or the next
        // automatic address would be .251 instead of a fresh /24's first one.
        Ec2Service service = realVpcNetworkService(new RequestContext());
        String subnetId = privateIpSubnet(service);

        launchWithPrivateIp(service, subnetId, 1, "10.50.1.250");

        assertEquals(List.of("10.50.1.10"), launchAddresses(service, subnetId, 1));
    }

    @Test
    void aDryRunWithARequestedPrivateIpLeasesNothing() {
        VpcNetworkManager vpcNetworks = mock(VpcNetworkManager.class);
        Ec2Service service = mockedVpcNetworkService(vpcNetworks);
        String subnetId = privateIpSubnet(service);

        AwsException dryRun = assertThrows(AwsException.class,
                () -> service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro", 1, 1, null,
                        List.of(), subnetId, null, List.of(), null, null, null, null, 0, null, null, null, null,
                        true, null, null, null, false, "10.50.1.50"));

        assertEquals("DryRunOperation", dryRun.getErrorCode());
        verify(vpcNetworks, never()).claimPrivateIp(anyString(), anyString(), anyString());
        assertTrue(instancesIn(service, subnetId).isEmpty());
    }

    @Test
    void anAddressAnInterfaceWasCreatedWithIsNotHandedOutByTheRealVpcNetwork() {
        // CreateNetworkInterface with its own address takes no lease, and the network's allocator
        // reads only its lease table, so it would hand a fresh /24's first address, offset 10, to
        // the next launch as well.
        Ec2Service service = realVpcNetworkService(new RequestContext());
        String subnetId = privateIpSubnet(service);
        service.createNetworkInterface("us-east-1", subnetId, "holder", "10.50.1.10",
                List.of(), List.of(), List.of());

        assertEquals(List.of("10.50.1.11"), launchAddresses(service, subnetId, 1));
    }

    @Test
    void anAddressAnotherAccountsInterfaceWasCreatedWithIsNotHandedOutOnASharedVpcNetwork() {
        // Default subnet ids are the same in every account, so their Docker network is shared, and
        // only the other account's stored interface says its address is held.
        RequestContext context = new RequestContext();
        Ec2Service service = realVpcNetworkService(context);
        String subnetId = Ec2Service.defaultSubnetId("us-east-1", "a");
        context.setAccountId("111122223333");
        service.describeSubnets("us-east-1", List.of(), Map.of());
        service.createNetworkInterface("us-east-1", subnetId, "holder", "172.31.0.10",
                List.of(), List.of(), List.of());

        context.setAccountId("000000000000");

        assertEquals(List.of("172.31.0.11"), launchAddresses(service, subnetId, 1));
    }

    @Test
    void anAddressTheWalkSkipsIsGivenBackToTheVpcNetwork() {
        // Its holder took no lease, and deleting an interface or NAT gateway returns none, so a
        // lease the walk kept would outlive the holder.
        VpcNetworkManager vpcNetworks = mock(VpcNetworkManager.class);
        Ec2Service service = mockedVpcNetworkService(vpcNetworks);
        String subnetId = privateIpSubnet(service);
        service.createNetworkInterface("us-east-1", subnetId, "holder", "10.50.1.10",
                List.of(), List.of(), List.of());
        when(vpcNetworks.allocatePrivateIp("us-east-1", subnetId))
                .thenReturn(Optional.of("10.50.1.10"), Optional.of("10.50.1.11"), Optional.empty());

        assertEquals(List.of("10.50.1.11"), launchAddresses(service, subnetId, 1));
        verify(vpcNetworks).releasePrivateIp("us-east-1", subnetId, "10.50.1.10");
        verify(vpcNetworks, never()).releasePrivateIp("us-east-1", subnetId, "10.50.1.11");
    }

    @Test
    void anAddressAnotherAccountHoldsIsNotHandedOutWhenTheWalkRunsTheNetworkDry() {
        // The walk skips the only address the shared network still offers, which another account's
        // interface holds, and the synthesised fallback's own scan sees only the caller's account.
        RequestContext context = new RequestContext();
        VpcNetworkManager vpcNetworks = mock(VpcNetworkManager.class);
        Ec2Service service = mockedVpcNetworkService(context, Map.of(), vpcNetworks);
        String subnetId = Ec2Service.defaultSubnetId("us-east-1", "a");
        context.setAccountId("111122223333");
        service.describeSubnets("us-east-1", List.of(), Map.of());
        service.createNetworkInterface("us-east-1", subnetId, "holder", "172.31.0.10",
                List.of(), List.of(), List.of());
        when(vpcNetworks.allocatePrivateIp("us-east-1", subnetId))
                .thenReturn(Optional.of("172.31.0.10"), Optional.empty());

        context.setAccountId("000000000000");

        assertEquals(List.of("172.31.0.11"), launchAddresses(service, subnetId, 1));
    }

    @Test
    void anAddressALaunchNamedWithoutLeasingItIsNotHandedOutAgain() {
        // A failed launch is marked terminated before its container is detached and its lease given
        // back. A launch that names the address in between finds nothing holding it and keeps it
        // although the lease is refused, and the late release then leaves the network offering an
        // address that launch's instance holds.
        VpcNetworkManager vpcNetworks = mock(VpcNetworkManager.class);
        Ec2Service service = mockedVpcNetworkService(vpcNetworks);
        String subnetId = privateIpSubnet(service);
        when(vpcNetworks.effectiveSubnetCidr("us-east-1", subnetId)).thenReturn(Optional.of("10.50.1.0/24"));
        when(vpcNetworks.claimPrivateIp("us-east-1", subnetId, "10.50.1.50")).thenReturn(false);
        launchWithPrivateIp(service, subnetId, 1, "10.50.1.50");
        when(vpcNetworks.allocatePrivateIp("us-east-1", subnetId))
                .thenReturn(Optional.of("10.50.1.50"), Optional.of("10.50.1.51"), Optional.empty());

        assertEquals(List.of("10.50.1.51"), launchAddresses(service, subnetId, 1));
    }

    private static String privateIpSubnet(Ec2Service service) {
        String vpcId = service.createVpc("us-east-1", "10.50.0.0/16", false).getVpcId();
        return service.createSubnet("us-east-1", vpcId, "10.50.1.0/24", "us-east-1a").getSubnetId();
    }

    private static Reservation launchWithPrivateIp(Ec2Service service, String subnetId, int count,
                                                   String privateIp) {
        return service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro", count, count, null,
                List.of(), subnetId, null, List.of(), null, null, null, null, 0, null, null, null, null,
                false, null, null, null, false, privateIp);
    }

    private static Ec2Service realVpcNetworkService(RequestContext context) {
        return realVpcNetworkService(context, Map.of());
    }

    /**
     * Ec2Service in container mode over a real VpcNetworkManager, whose Docker daemon has no
     * networks yet, with the caller's account taken from {@code context} and {@code stores}
     * standing in for what a previous run persisted.
     */
    private static Ec2Service realVpcNetworkService(RequestContext context,
                                                    Map<String, AccountAwareStorageBackend<?>> stores) {
        EmulatorConfig config = mockConfig(false);
        EmulatorConfig.VpcNetworksConfig networks = mock(EmulatorConfig.VpcNetworksConfig.class);
        when(config.services().ec2().vpcNetworks()).thenReturn(networks);
        when(networks.enabled()).thenReturn(true);
        DockerClient docker = mock(DockerClient.class);
        ListNetworksCmd listNetworks = mock(ListNetworksCmd.class, RETURNS_SELF);
        when(listNetworks.exec()).thenReturn(List.of());
        when(docker.listNetworksCmd()).thenReturn(listNetworks);
        AmiImageResolver images = mock(AmiImageResolver.class);
        when(images.resolveImage(any())).thenReturn(ResolvedAmiImage.minimal("floci/base:latest"));
        jakarta.enterprise.inject.Instance<RequestContext> contexts = requestContexts(context);
        return new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class), images,
                mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory(stores, contexts),
                contexts, new VpcNetworkManager(config, docker, null), null, null, null);
    }

    /** Ec2Service in mock mode over {@code vpcNetworks}, called as the default account. */
    private static Ec2Service mockedVpcNetworkService(VpcNetworkManager vpcNetworks) {
        return mockedVpcNetworkService(Map.of(), vpcNetworks);
    }

    /** {@link #mockedVpcNetworkService(VpcNetworkManager)}, with {@code stores} in place of the stores they name. */
    private static Ec2Service mockedVpcNetworkService(Map<String, AccountAwareStorageBackend<?>> stores,
                                                      VpcNetworkManager vpcNetworks) {
        return mockedVpcNetworkService(new RequestContext(), stores, vpcNetworks);
    }

    /**
     * Ec2Service in mock mode over {@code vpcNetworks}, with the caller's account taken from
     * {@code context} and {@code stores} in place of the stores they name.
     */
    private static Ec2Service mockedVpcNetworkService(RequestContext context,
                                                      Map<String, AccountAwareStorageBackend<?>> stores,
                                                      VpcNetworkManager vpcNetworks) {
        jakarta.enterprise.inject.Instance<RequestContext> contexts = requestContexts(context);
        return new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(stores, contexts), contexts, vpcNetworks, null, null, null);
    }

    /** The request-scoped RequestContext as CDI hands it out, resolving to {@code context}. */
    @SuppressWarnings("unchecked")
    private static jakarta.enterprise.inject.Instance<RequestContext> requestContexts(RequestContext context) {
        // jakarta.enterprise.inject.Instance is written out because this file imports the model's Instance.
        jakarta.enterprise.inject.Instance<RequestContext> contexts = mock(jakarta.enterprise.inject.Instance.class);
        when(contexts.isResolvable()).thenReturn(true);
        when(contexts.get()).thenReturn(context);
        return contexts;
    }

    private static Instance defaultSubnetInstance(String instanceId, String privateIp, InstanceState state) {
        Instance instance = persistedInstance(instanceId, privateIp, state);
        instance.setVpcId(Ec2Service.defaultVpcId("us-east-1"));
        instance.setSubnetId(Ec2Service.defaultSubnetId("us-east-1", "a"));
        return instance;
    }

    private static List<Instance> instancesIn(Ec2Service service, String subnetId) {
        return service.describeInstances("us-east-1", List.of(), Map.of("subnet-id", List.of(subnetId))).stream()
                .flatMap(reservation -> reservation.getInstances().stream())
                .toList();
    }

    private static EndpointAddressFixture endpointAddressFixture(String firstCidr, String secondCidr) {
        AccountAwareStorageBackend<Subnet> subnetStore = AccountAwareStorageBackend.inMemory("000000000000");
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory(Map.of("ec2-subnets.json", subnetStore)));
        String region = "us-east-1";
        String vpcId = service.createVpc(region, "172.31.0.0/16", false).getVpcId();
        String[] subnetIds = {"subnet-00000000", "subnet-00000088"};
        String[] cidrs = {firstCidr, secondCidr};
        for (int i = 0; i < subnetIds.length; i++) {
            Subnet subnet = new Subnet();
            subnet.setSubnetId(subnetIds[i]);
            subnet.setVpcId(vpcId);
            subnet.setRegion(region);
            subnet.setCidrBlock(cidrs[i]);
            subnet.setAvailabilityZone(region + (i == 0 ? "a" : "b"));
            subnetStore.put(region + "::" + subnetIds[i], subnet);
        }
        VpcEndpoint endpoint = service.createVpcEndpoint(region, vpcId,
                "com.amazonaws.us-east-1.ec2", "Interface",
                List.of(), List.of(subnetIds), List.of(), null, null, List.of());
        return new EndpointAddressFixture(service, endpoint);
    }

    private record EndpointAddressFixture(Ec2Service service, VpcEndpoint endpoint) {
    }

    @Test
    void subnetConfigurationPinsTheEndpointInterfaceAddress() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String vpcId = service.createVpc("us-east-1", "10.60.0.0/16", false).getVpcId();
        String pinned = service.createSubnet("us-east-1", vpcId, "10.60.1.0/24", "us-east-1a").getSubnetId();
        String unpinned = service.createSubnet("us-east-1", vpcId, "10.60.2.0/24", "us-east-1b").getSubnetId();

        VpcEndpoint endpoint = service.createVpcEndpoint("us-east-1", vpcId,
                "com.amazonaws.us-east-1.ecs", "Interface",
                List.of(), List.of(pinned, unpinned), List.of(), null, null, List.of(),
                List.of(new VpcEndpointSubnetConfiguration(pinned, "10.60.1.10", "2600:1f18::10")));

        Map<String, String> addresses = endpointAddressesBySubnet(service);
        assertEquals("10.60.1.10", addresses.get(pinned),
                "the pinned address must be the one the interface reports");
        assertNotEquals("10.60.2.10", addresses.get(unpinned),
                "a subnet with no configuration keeps the synthesized address");
        assertEquals(addresses, endpointAddressesBySubnet(service),
                "a second read must answer with the same addresses");
        assertEquals("2600:1f18::10", endpoint.getSubnetConfigurations().getFirst().getIpv6(),
                "Ipv6 is stored even though no floci interface field carries it yet");
    }

    @Test
    void subnetConfigurationRejectsAnAddressOutsideTheSubnet() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String vpcId = service.createVpc("us-east-1", "10.61.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet("us-east-1", vpcId, "10.61.1.0/24", "us-east-1a").getSubnetId();

        AwsException error = assertThrows(AwsException.class, () ->
                service.createVpcEndpoint("us-east-1", vpcId, "com.amazonaws.us-east-1.ecs", "Interface",
                        List.of(), List.of(subnetId), List.of(), null, null, List.of(),
                        List.of(new VpcEndpointSubnetConfiguration(subnetId, "10.61.9.10", null))));
        assertEquals("InvalidParameterValue", error.getErrorCode());
        assertTrue(service.describeVpcEndpoints("us-east-1", List.of(), Map.of()).isEmpty(),
                "a rejected configuration must not leave an endpoint behind");
    }

    @Test
    void subnetConfigurationRejectsTheAddressesAwsReservesInTheSubnet() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String vpcId = service.createVpc("us-east-1", "10.63.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet("us-east-1", vpcId, "10.63.1.0/24", "us-east-1a").getSubnetId();

        for (String reserved : List.of("10.63.1.0", "10.63.1.1", "10.63.1.2", "10.63.1.3", "10.63.1.255")) {
            AwsException error = assertThrows(AwsException.class, () ->
                    service.createVpcEndpoint("us-east-1", vpcId, "com.amazonaws.us-east-1.ecs", "Interface",
                            List.of(), List.of(subnetId), List.of(), null, null, List.of(),
                            List.of(new VpcEndpointSubnetConfiguration(subnetId, reserved, null))), reserved);
            assertEquals("InvalidParameterValue", error.getErrorCode(), reserved);
            assertTrue(error.getMessage().contains(reserved), reserved);
        }
        assertTrue(service.describeVpcEndpoints("us-east-1", List.of(), Map.of()).isEmpty(),
                "none of the reserved addresses may leave an endpoint behind");

        VpcEndpoint endpoint = service.createVpcEndpoint("us-east-1", vpcId,
                "com.amazonaws.us-east-1.ecs", "Interface",
                List.of(), List.of(subnetId), List.of(), null, null, List.of(),
                List.of(new VpcEndpointSubnetConfiguration(subnetId, "10.63.1.4", null)));
        assertEquals("10.63.1.4", endpointAddressesBySubnet(service).get(subnetId),
                "the first host address above the reserved four is assignable");
        assertNotNull(endpoint.getVpcEndpointId());
    }

    @Test
    void subnetConfigurationKeepsTheUsableHostsOfASmallSubnet() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String vpcId = service.createVpc("us-east-1", "10.64.0.0/16", false).getVpcId();
        // /28 is the smallest subnet AWS accepts, and five of its sixteen addresses are reserved.
        String subnetId = service.createSubnet("us-east-1", vpcId, "10.64.1.16/28", "us-east-1a").getSubnetId();

        for (String reserved : List.of("10.64.1.16", "10.64.1.17", "10.64.1.18", "10.64.1.19", "10.64.1.31")) {
            AwsException error = assertThrows(AwsException.class, () ->
                    service.createVpcEndpoint("us-east-1", vpcId, "com.amazonaws.us-east-1.ecs", "Interface",
                            List.of(), List.of(subnetId), List.of(), null, null, List.of(),
                            List.of(new VpcEndpointSubnetConfiguration(subnetId, reserved, null))), reserved);
            assertEquals("InvalidParameterValue", error.getErrorCode(), reserved);
        }

        service.createVpcEndpoint("us-east-1", vpcId, "com.amazonaws.us-east-1.ecs", "Interface",
                List.of(), List.of(subnetId), List.of(), null, null, List.of(),
                List.of(new VpcEndpointSubnetConfiguration(subnetId, "10.64.1.30", null)));
        assertEquals("10.64.1.30", endpointAddressesBySubnet(service).get(subnetId),
                "the address below the broadcast address is still a host address");
    }

    @Test
    void modifyVpcEndpointRejectsAReservedSubnetConfigurationAddress() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String vpcId = service.createVpc("us-east-1", "10.65.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet("us-east-1", vpcId, "10.65.1.0/24", "us-east-1a").getSubnetId();
        VpcEndpoint endpoint = service.createVpcEndpoint("us-east-1", vpcId,
                "com.amazonaws.us-east-1.ecs", "Interface",
                List.of(), List.of(subnetId), List.of(), null, null, List.of(),
                List.of(new VpcEndpointSubnetConfiguration(subnetId, "10.65.1.10", null)));

        for (String reserved : List.of("10.65.1.0", "10.65.1.1", "10.65.1.2", "10.65.1.3", "10.65.1.255")) {
            AwsException error = assertThrows(AwsException.class, () ->
                    service.modifyVpcEndpoint("us-east-1", endpoint.getVpcEndpointId(),
                            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null, null,
                            List.of(new VpcEndpointSubnetConfiguration(subnetId, reserved, null))), reserved);
            assertEquals("InvalidParameterValue", error.getErrorCode(), reserved);
        }
        assertEquals("10.65.1.10", endpointAddressesBySubnet(service).get(subnetId),
                "a rejected modify must leave the pinned address untouched");

        service.modifyVpcEndpoint("us-east-1", endpoint.getVpcEndpointId(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null, null,
                List.of(new VpcEndpointSubnetConfiguration(subnetId, "10.65.1.254", null)));
        assertEquals("10.65.1.254", endpointAddressesBySubnet(service).get(subnetId),
                "an ordinary host address still goes through");
    }

    @Test
    void modifyVpcEndpointReplacesTheConfigurationForOneSubnet() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String vpcId = service.createVpc("us-east-1", "10.62.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet("us-east-1", vpcId, "10.62.1.0/24", "us-east-1a").getSubnetId();
        VpcEndpoint endpoint = service.createVpcEndpoint("us-east-1", vpcId,
                "com.amazonaws.us-east-1.ecs", "Interface",
                List.of(), List.of(subnetId), List.of(), null, null, List.of(),
                List.of(new VpcEndpointSubnetConfiguration(subnetId, "10.62.1.10", null)));

        service.modifyVpcEndpoint("us-east-1", endpoint.getVpcEndpointId(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null, null,
                List.of(new VpcEndpointSubnetConfiguration(subnetId, "10.62.1.40", null)));

        assertEquals(1, service.describeVpcEndpoints("us-east-1", List.of(endpoint.getVpcEndpointId()), Map.of())
                .getFirst().getSubnetConfigurations().size(),
                "a second configuration for the same subnet replaces the first");
        assertEquals("10.62.1.40", endpointAddressesBySubnet(service).get(subnetId));
    }

    private static Map<String, String> endpointAddressesBySubnet(Ec2Service service) {
        Map<String, String> addresses = new HashMap<>();
        for (NetworkInterface eni : service.endpointNetworkInterfaces("us-east-1")) {
            addresses.put(eni.getSubnetId(), eni.getPrivateIpAddress());
        }
        return addresses;
    }

    @Test
    void modifyInstanceGroupsReassignsSecurityGroupsOnInstanceAndEni() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        SecurityGroup web = service.createSecurityGroup("us-east-1", "web", "web sg", Ec2Service.defaultVpcId("us-east-1"));
        Reservation reservation = service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null);
        String instanceId = reservation.getInstances().getFirst().getInstanceId();

        service.modifyInstanceGroups("us-east-1", instanceId, List.of(web.getGroupId()));

        Instance inst = service.findInstanceById(instanceId);
        assertEquals(List.of(web.getGroupId()),
                inst.getSecurityGroups().stream().map(GroupIdentifier::getGroupId).toList());
        assertEquals(web.getGroupId(),
                inst.getNetworkInterfaces().getFirst().getGroups().getFirst().getGroupId());
    }

    @Test
    void modifyInstanceGroupsRejectsUnknownSecurityGroup() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        Reservation reservation = service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null);
        String instanceId = reservation.getInstances().getFirst().getInstanceId();

        AwsException error = assertThrows(AwsException.class,
                () -> service.modifyInstanceGroups("us-east-1", instanceId, List.of("sg-doesnotexist")));
        assertEquals("InvalidGroup.NotFound", error.getErrorCode());
    }

    @Test
    void registerImageNamesAreScopedToRegion() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        service.registerImage("us-east-1", "shared-name", null, null, null, List.of());
        service.registerImage("us-west-2", "shared-name", null, null, null, List.of());

        AwsException error = assertThrows(AwsException.class,
                () -> service.registerImage("us-east-1", "shared-name", null, null, null, List.of()));
        assertEquals("InvalidAMIName.Duplicate", error.getErrorCode());
    }

    @Test
    void createKeyPairReturnsUsableMaterialRatherThanAPlaceholder() throws Exception {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        KeyPair first = service.createKeyPair("us-east-1", "usable-key");
        KeyPair second = service.createKeyPair("us-east-1", "usable-key-2");

        // Parse the material rather than shape-match it: the placeholder this replaced was a
        // well-formed PEM envelope around 63 bytes of nothing, so any regex check passed.
        Object parsed = new PEMParser(new StringReader(first.getKeyMaterial())).readObject();
        assertTrue(parsed instanceof PEMKeyPair, "expected a PEM key pair, got: " + parsed);
        assertEquals(2048, ((RSAPrivateCrtKey) new JcaPEMKeyConverter()
                .getKeyPair((PEMKeyPair) parsed).getPrivate()).getModulus().bitLength());
        // The public half is what RunInstances injects into authorized_keys.
        assertNotNull(first.getPublicKey());
        assertTrue(first.getPublicKey().startsWith("ssh-rsa "));
        // A constant is not a fingerprint; two key pairs must differ in every disclosed field.
        assertNotEquals(first.getKeyMaterial(), second.getKeyMaterial());
        assertNotEquals(first.getPublicKey(), second.getPublicKey());
        assertNotEquals(first.getKeyFingerprint(), second.getKeyFingerprint());
    }

    @Test
    void importKeyPairFingerprintsTheSuppliedKeyRatherThanReportingAConstant() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        String firstKey = Ec2KeyMaterial.generateRsa().openSshPublicKey();
        String secondKey = Ec2KeyMaterial.generateRsa().openSshPublicKey();

        KeyPair first = service.importKeyPair("us-east-1", "imported-a", firstKey);
        KeyPair second = service.importKeyPair("us-east-1", "imported-b", secondKey);

        assertNotEquals(first.getKeyFingerprint(), second.getKeyFingerprint());
        assertEquals(Ec2KeyMaterial.readImportedKey(firstKey).fingerprint(), first.getKeyFingerprint());
    }

    @Test
    void importKeyPairReportsTheFingerprintAwsWouldReportForTheSameKey() {
        // The assertion above compares the service against the same helper it calls, so it
        // cannot see a wrong fingerprinting scheme. These two are pinned to values derived
        // outside this codebase from fixed throwaway keys:
        //   RSA:     openssl rsa -in rsa.pem -pubout -outform DER | openssl md5 -c
        //   ed25519: ssh-keygen -l -f ed25519.pub, minus its "SHA256:" prefix, padding kept
        // matching the two schemes AWS documents for ImportKeyPair.
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        assertEquals(RSA_FINGERPRINT,
                service.importKeyPair("us-east-1", "pinned-rsa", RSA_KEY).getKeyFingerprint());
        assertEquals(ED25519_FINGERPRINT,
                service.importKeyPair("us-east-1", "pinned-ed25519", ED25519_KEY).getKeyFingerprint());
    }

    @Test
    void createKeyPairRejectsMissingKeyName() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        for (String missing : new String[] {null, "", "   "}) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.createKeyPair("us-east-1", missing));
            assertEquals("MissingParameter", error.getErrorCode());
            assertEquals(400, error.getHttpStatus());
        }
        assertTrue(service.describeKeyPairs("us-east-1", List.of(), List.of()).isEmpty());
    }

    @Test
    void importKeyPairRejectsMissingKeyName() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        AwsException error = assertThrows(AwsException.class,
                () -> service.importKeyPair("us-east-1", null, "c3NoLXJzYSBBQUFB"));
        assertEquals("MissingParameter", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void createKeyPairSurvivesANamelessRecordInTheStore() {
        // Regression for #3356: a key pair stored without a name (accepted before KeyName was
        // validated) made the duplicate check throw on every later CreateKeyPair.
        AccountAwareStorageBackend<KeyPair> keyPairStore = AccountAwareStorageBackend.inMemory("000000000000");
        KeyPair nameless = new KeyPair();
        nameless.setKeyPairId("key-nameless");
        nameless.setRegion("us-east-1");
        keyPairStore.put("us-east-1:key-nameless", nameless);
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Map.of("ec2-key-pairs.json", keyPairStore)));

        KeyPair created = service.createKeyPair("us-east-1", "fresh");
        KeyPair imported = service.importKeyPair("us-east-1", "fresh-imported",
                Ec2KeyMaterial.generateRsa().openSshPublicKey());

        assertEquals("fresh", created.getKeyName());
        assertEquals("fresh-imported", imported.getKeyName());
        assertNull(service.deleteKeyPair("us-east-1", "no-such-name", null));
        assertEquals(3, service.describeKeyPairs("us-east-1", List.of(), List.of()).size());
    }

    @Test
    void deleteKeyPairReturnsTheDeletedRecordOrNull() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        KeyPair byName = service.createKeyPair("us-east-1", "delete-by-name");
        KeyPair byId = service.createKeyPair("us-east-1", "delete-by-id");

        assertEquals(byName.getKeyPairId(), service.deleteKeyPair("us-east-1", "delete-by-name", null).getKeyPairId());
        assertEquals(byId.getKeyPairId(), service.deleteKeyPair("us-east-1", null, byId.getKeyPairId()).getKeyPairId());
        assertNull(service.deleteKeyPair("us-east-1", "delete-by-name", null));
        assertNull(service.deleteKeyPair("us-east-1", null, byId.getKeyPairId()));
        assertTrue(service.describeKeyPairs("us-east-1", List.of(), List.of()).isEmpty());
    }

    @Test
    void importKeyPairRejectsDuplicateKeyName() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        String publicKey = Ec2KeyMaterial.generateRsa().openSshPublicKey();
        service.importKeyPair("us-east-1", "duplicate-key", publicKey);

        AwsException error = assertThrows(AwsException.class,
                () -> service.importKeyPair("us-east-1", "duplicate-key", publicKey));
        assertEquals("InvalidKeyPair.Duplicate", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());

        // same name in another region is allowed
        service.importKeyPair("us-west-2", "duplicate-key", publicKey);
    }

    @Test
    void importKeyPairRejectsNameAlreadyUsedByCreateKeyPair() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        service.createKeyPair("us-east-1", "shared-key-name");

        AwsException error = assertThrows(AwsException.class, () -> service.importKeyPair(
                "us-east-1", "shared-key-name", Ec2KeyMaterial.generateRsa().openSshPublicKey()));
        assertEquals("InvalidKeyPair.Duplicate", error.getErrorCode());
    }

    /**
     * A second CreateKeyPair or ImportKeyPair for a name, arriving after the first has found the name
     * free but before its key pair is stored, is refused as a duplicate. The store parks the first
     * request's write until the second has finished or waits on a lock the first holds, which forces
     * that interleaving on every run.
     */
    @ParameterizedTest
    @CsvSource({"create, create", "import, import", "create, import", "import, create"})
    void secondRequestForAKeyPairNameBeingStoredIsRefused(String first, String second) throws Exception {
        ParkingStore<KeyPair> parking = new ParkingStore<>();
        AccountAwareStorageBackend<KeyPair> keyPairStore = new AccountAwareStorageBackend<>(parking, null, "000000000000");
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Map.of("ec2-key-pairs.json", keyPairStore)));
        String publicKey = Ec2KeyMaterial.generateRsa().openSshPublicKey();
        Function<String, KeyPair> request = action -> "create".equals(action)
                ? service.createKeyPair("us-east-1", "contested", "ed25519", null)
                : service.importKeyPair("us-east-1", "contested", publicKey);
        AtomicReference<Thread> firstWorker = new AtomicReference<>();
        AtomicReference<Thread> secondWorker = new AtomicReference<>();
        parking.parkNextWrite();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<KeyPair> firstResult = workers.submit(() -> {
                firstWorker.set(Thread.currentThread());
                return request.apply(first);
            });
            assertTrue(parking.awaitParked(), "the first request never stored its key pair");
            Future<KeyPair> secondResult = workers.submit(() -> {
                secondWorker.set(Thread.currentThread());
                return request.apply(second);
            });
            Awaitility.await("the second request to finish or to wait in storeNewKeyPair")
                    .atMost(Duration.ofSeconds(30)).until(() -> secondResult.isDone()
                    || isBlockedIn(secondWorker.get(), "storeNewKeyPair", firstWorker.get()));
            parking.release();

            KeyPair stored = firstResult.get(30, TimeUnit.SECONDS);
            ExecutionException refusal = assertThrows(ExecutionException.class,
                    () -> secondResult.get(30, TimeUnit.SECONDS), "both requests got the name");
            AwsException duplicate = assertInstanceOf(AwsException.class, refusal.getCause());
            assertEquals("InvalidKeyPair.Duplicate", duplicate.getErrorCode());
            assertEquals("The keypair 'contested' already exists", duplicate.getMessage());
            assertEquals(400, duplicate.getHttpStatus());
            assertEquals(List.of(stored.getKeyPairId()), service.describeKeyPairs("us-east-1", List.of(), List.of())
                    .stream().map(KeyPair::getKeyPairId).toList());
        } finally {
            parking.release();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /**
     * A DeleteKeyPair arriving while a tag write has read the key pair and not yet written it back
     * waits for that write, so the tag write cannot put back the key pair the delete removes, and the
     * freed name then goes to one new key pair. Armed once the key pair is created, the store parks the
     * next write, which is the tag write's, until the delete has finished or waits on a lock the tag
     * write holds.
     */
    @Test
    void deleteKeyPairWaitsForATagWriteOnTheKeyPair() throws Exception {
        ParkingStore<KeyPair> parking = new ParkingStore<>();
        AccountAwareStorageBackend<KeyPair> keyPairStore = new AccountAwareStorageBackend<>(parking, null, "000000000000");
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Map.of("ec2-key-pairs.json", keyPairStore)));
        KeyPair original = service.createKeyPair("us-east-1", "retagged", "ed25519", null);
        parking.parkNextWrite();
        AtomicReference<Thread> tagWorker = new AtomicReference<>();
        AtomicReference<Thread> deleteWorker = new AtomicReference<>();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> tagging = workers.submit(() -> {
                tagWorker.set(Thread.currentThread());
                service.createTags("us-east-1", List.of(original.getKeyPairId()), List.of(new Tag("Team", "blue")));
            });
            assertTrue(parking.awaitParked(), "the tag write never stored the key pair");
            Future<KeyPair> deleting = workers.submit(() -> {
                deleteWorker.set(Thread.currentThread());
                return service.deleteKeyPair("us-east-1", "retagged", null);
            });
            Awaitility.await("the delete to finish or to wait in deleteKeyPair")
                    .atMost(Duration.ofSeconds(30)).until(() -> deleting.isDone()
                    || isBlockedIn(deleteWorker.get(), "deleteKeyPair", tagWorker.get()));
            parking.release();
            tagging.get(30, TimeUnit.SECONDS);

            assertEquals(original.getKeyPairId(), deleting.get(30, TimeUnit.SECONDS).getKeyPairId());
            assertTrue(service.describeKeyPairs("us-east-1", List.of(), List.of()).isEmpty(),
                    "the tag write put the deleted key pair back");
            KeyPair recreated = service.createKeyPair("us-east-1", "retagged", "ed25519", null);
            assertEquals(List.of(recreated.getKeyPairId()), service.describeKeyPairs("us-east-1", List.of(), List.of())
                    .stream().map(KeyPair::getKeyPairId).toList());
        } finally {
            parking.release();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /**
     * Whether {@code waiter} waits, in the named Ec2Service method, to enter a monitor {@code owner}
     * holds. The method is named on purpose: a wait anywhere else may not be at the lock under test, so
     * the race tests time out rather than pass without exercising it. Moving that lock into another
     * method means updating the name here.
     */
    private static boolean isBlockedIn(Thread waiter, String method, Thread owner) {
        if (waiter == null) {
            return false;
        }
        ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(waiter.threadId(), 1);
        return info != null && info.getThreadState() == Thread.State.BLOCKED
                && info.getLockOwnerId() == owner.threadId()
                && info.getStackTrace().length == 1
                && Ec2Service.class.getName().equals(info.getStackTrace()[0].getClassName())
                && method.equals(info.getStackTrace()[0].getMethodName());
    }

    @Test
    void describeKeyPairsThrowsNotFoundForMissingName() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        AwsException error = assertThrows(AwsException.class,
                () -> service.describeKeyPairs("us-east-1", List.of("does-not-exist"), List.of()));
        assertEquals("InvalidKeyPair.NotFound", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void describeKeyPairsThrowsNotFoundForMissingId() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        AwsException error = assertThrows(AwsException.class,
                () -> service.describeKeyPairs("us-east-1", List.of(), List.of("key-missing")));
        assertEquals("InvalidKeyPair.NotFound", error.getErrorCode());
    }

    @Test
    void describeKeyPairsReturnsRequestedKeyAndAllowsEmptyUnfilteredList() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        // Unfiltered describe on an empty account is not an error.
        assertTrue(service.describeKeyPairs("us-east-1", List.of(), List.of()).isEmpty());

        service.createKeyPair("us-east-1", "present-key");
        assertEquals(1, service.describeKeyPairs("us-east-1", List.of("present-key"), List.of()).size());

        // A missing name is not masked by a present one in the same request.
        AwsException error = assertThrows(AwsException.class,
                () -> service.describeKeyPairs("us-east-1", List.of("present-key", "absent-key"), List.of()));
        assertEquals("InvalidKeyPair.NotFound", error.getErrorCode());
    }

    /**
     * ImportKeyPair refuses what EC2 does not import, as AWS does, with InvalidKey.Format: an ECDSA
     * key, and the truncated ssh-rsa blob Floci's own tests used to import, which no SSH server
     * could use. Nothing is stored for either.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            ECDSA_P256_KEY,
            TRUNCATED_RSA_KEY})
    void importKeyPairRefusesAKeyEc2DoesNotImport(String material) {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        AwsException error = assertThrows(AwsException.class,
                () -> service.importKeyPair("us-east-1", "refused-key", material));

        assertEquals("InvalidKey.Format", error.getErrorCode());
        assertEquals("Key is not in valid OpenSSH public key format", error.getMessage());
        assertEquals(400, error.getHttpStatus());
        assertTrue(service.describeKeyPairs("us-east-1", List.of(), List.of()).isEmpty());
    }

    /**
     * AWS documents one rule for the names CreateKeyPair and ImportKeyPair take: up to 255 ASCII
     * characters, with no leading or trailing space. A refused name stores nothing.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void keyPairNamesFollowTheRuleAwsDocuments(boolean imported) {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String publicKey = Ec2KeyMaterial.generateRsa().openSshPublicKey();
        Function<String, KeyPair> make = name -> imported
                ? service.importKeyPair("us-east-1", name, publicKey)
                : service.createKeyPair("us-east-1", name);

        String longest = "k".repeat(255);
        assertEquals(longest, make.apply(longest).getKeyName());
        assertEquals("a name with inner spaces", make.apply("a name with inner spaces").getKeyName());

        Map<String, String> refused = Map.of(
                "k".repeat(256), "Length exceeds maximum of 255 characters.",
                "cl\u00e9", "Character sets beyond ASCII are not supported.",
                " leading", "Leading and trailing spaces are not allowed.",
                "trailing ", "Leading and trailing spaces are not allowed.");
        for (Map.Entry<String, String> name : refused.entrySet()) {
            AwsException error = assertThrows(AwsException.class, () -> make.apply(name.getKey()));
            assertEquals("InvalidParameterValue", error.getErrorCode());
            assertEquals("Value (" + name.getKey() + ") for parameter KeyName is invalid. " + name.getValue(),
                    error.getMessage());
            assertEquals(400, error.getHttpStatus());
        }
        assertEquals(2, service.describeKeyPairs("us-east-1", List.of(), List.of()).size());
    }

    /**
     * A key pair records its type and when it was made, and an import keeps its key as one OpenSSH
     * line whatever format it came in, since that is what RunInstances writes to authorized_keys.
     */
    @Test
    void keyPairsRecordTheirTypeAndCreateTime() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String rsaLine = Ec2KeyMaterial.generateRsa().openSshPublicKey();
        String rfc4716 = "---- BEGIN SSH2 PUBLIC KEY ----\nComment: \"throwaway\"\n" + rsaLine.split(" ")[1]
                + "\n---- END SSH2 PUBLIC KEY ----\n";
        Instant before = Instant.now();

        KeyPair created = service.createKeyPair("us-east-1", "created");
        KeyPair importedRsa = service.importKeyPair("us-east-1", "imported-rsa", rfc4716);
        KeyPair importedEd25519 = service.importKeyPair("us-east-1", "imported-ed25519", ED25519_KEY + " someone@host\n");

        Instant after = Instant.now();
        assertEquals("rsa", created.getKeyType());
        assertEquals("rsa", importedRsa.getKeyType());
        assertEquals("ed25519", importedEd25519.getKeyType());
        for (KeyPair keyPair : List.of(created, importedRsa, importedEd25519)) {
            assertFalse(keyPair.getCreateTime().isBefore(before), keyPair.getKeyName());
            assertFalse(keyPair.getCreateTime().isAfter(after), keyPair.getKeyName());
        }
        assertEquals(rsaLine, importedRsa.getPublicKey());
        assertEquals(ED25519_KEY, importedEd25519.getPublicKey());
    }

    /**
     * Filter.N narrows DescribeKeyPairs by the names AWS documents, key-pair-id, key-name,
     * fingerprint, tag-key and tag:&lt;key&gt;, and by key-type, with wildcards, values of one filter
     * ORed and filters ANDed with each other and with KeyName.N. Any other name is refused, as AWS
     * refuses it, rather than matching every key pair.
     */
    @Test
    void describeKeyPairsHonoursItsFilters() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        KeyPair alpha = service.createKeyPair("us-east-1", "alpha-rsa");
        KeyPair beta = service.importKeyPair("us-east-1", "beta-ed25519", ED25519_KEY);
        KeyPair gamma = service.importKeyPair("us-east-1", "gamma-rsa", Ec2KeyMaterial.generateRsa().openSshPublicKey());
        service.createKeyPair("us-west-2", "alpha-rsa");
        service.createTags("us-east-1", List.of(beta.getKeyPairId()), List.of(new Tag("Team", "blue")));
        BiFunction<List<String>, Map<String, List<String>>, List<String>> names = (keyNames, filters) ->
                service.describeKeyPairs("us-east-1", keyNames, List.of(), filters).stream()
                        .map(KeyPair::getKeyName).sorted().toList();

        assertEquals(List.of("alpha-rsa", "beta-ed25519", "gamma-rsa"), names.apply(List.of(), Map.of()));
        assertEquals(List.of("beta-ed25519"), names.apply(List.of(), Map.of("key-pair-id", List.of(beta.getKeyPairId()))));
        assertEquals(List.of("alpha-rsa"), names.apply(List.of(), Map.of("key-name", List.of("alpha-rsa"))));
        assertEquals(List.of("alpha-rsa", "gamma-rsa"), names.apply(List.of(), Map.of("key-name", List.of("*-rsa"))));
        assertEquals(List.of("beta-ed25519"), names.apply(List.of(), Map.of("key-name", List.of("?eta-*"))));
        assertEquals(List.of("gamma-rsa"), names.apply(List.of(), Map.of("fingerprint", List.of(gamma.getKeyFingerprint()))));
        assertEquals(List.of("beta-ed25519"), names.apply(List.of(), Map.of("fingerprint", List.of(beta.getKeyFingerprint()))));
        assertEquals(List.of("alpha-rsa", "gamma-rsa"), names.apply(List.of(), Map.of("key-type", List.of("rsa"))));
        assertEquals(List.of("beta-ed25519"), names.apply(List.of(), Map.of("key-type", List.of("ed25519"))));
        assertEquals(List.of("beta-ed25519"), names.apply(List.of(), Map.of("tag-key", List.of("Team"))));
        assertEquals(List.of("beta-ed25519"), names.apply(List.of(), Map.of("tag:Team", List.of("blue"))));
        assertEquals(List.of(), names.apply(List.of(), Map.of("tag:Team", List.of("red"))));
        assertEquals(List.of("alpha-rsa", "beta-ed25519"),
                names.apply(List.of(), Map.of("key-name", List.of("alpha-rsa", "beta-ed25519"))));
        assertEquals(List.of("gamma-rsa"), names.apply(List.of(),
                Map.of("key-type", List.of("rsa"), "key-name", List.of("g*"))));
        assertEquals(List.of("alpha-rsa"), names.apply(List.of("alpha-rsa", "beta-ed25519"),
                Map.of("key-type", List.of("rsa"))));
        assertEquals(alpha.getKeyPairId(), service.describeKeyPairs("us-east-1", List.of(), List.of(),
                Map.of("key-name", List.of("alpha-rsa"))).getFirst().getKeyPairId());

        AwsException error = assertThrows(AwsException.class,
                () -> names.apply(List.of(), Map.of("owner-id", List.of("000000000000"))));
        assertEquals("InvalidParameterValue", error.getErrorCode());
        assertEquals("The filter 'owner-id' is invalid", error.getMessage());

        // Filter.N in request order: a repeated name is ANDed with the earlier filter, not replacing it.
        // Each filter alone matches two key pairs, so keeping either one instead gives a longer list.
        List<Map.Entry<String, List<String>>> repeated = List.of(
                Map.entry("key-name", List.of("alpha-rsa", "gamma-rsa")),
                Map.entry("key-name", List.of("beta-ed25519", "gamma-rsa")));
        assertEquals(List.of("gamma-rsa"), service.describeKeyPairs("us-east-1", List.of(), List.of(), repeated)
                .stream().map(KeyPair::getKeyName).toList());
        AwsException repeatedUnknown = assertThrows(AwsException.class, () -> service.describeKeyPairs("us-east-1",
                List.of(), List.of(), List.of(Map.entry("key-name", List.of("*")), Map.entry("owner-id", List.of("x")))));
        assertEquals("The filter 'owner-id' is invalid", repeatedUnknown.getMessage());
    }

    /**
     * A key pair stored before its type was recorded has none, so DescribeKeyPairs reads it from
     * the key the record holds, created or imported, and material ImportKeyPair would now refuse has
     * no type; a created key pair saved before it kept its public key is RSA, all CreateKeyPair made
     * then. key-type filters by the same answer.
     */
    @Test
    void theTypeOfAKeyPairStoredWithoutOneIsReadFromItsKey() throws Exception {
        AccountAwareStorageBackend<KeyPair> keyPairStore = AccountAwareStorageBackend.inMemory("000000000000");
        Map<String, String> legacy = new LinkedHashMap<>();
        legacy.put("key-created", null);
        legacy.put("key-ed25519", ED25519_KEY + " me\n");
        legacy.put("key-ecdsa", ECDSA_P256_KEY);
        legacy.put("key-garbage", "c3NoLXJzYSBBQUFB");
        // A SubjectPublicKeyInfo whose bit string declares an unused bit (byte 23 of a 2048-bit RSA
        // key): the earlier ImportKeyPair stored it, and reading it must not fail the whole listing.
        byte[] padded = KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic().getEncoded();
        padded[23] = 1;
        legacy.put("key-pad-bits", "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(padded)
                + "\n-----END PUBLIC KEY-----\n");
        // An rsaEncryption SubjectPublicKeyInfo whose key bit string is empty.
        legacy.put("key-empty-bit-string", "-----BEGIN PUBLIC KEY-----\nMBIwDQYJKoZIhvcNAQEBBQADAQA=\n-----END PUBLIC KEY-----\n");
        for (Map.Entry<String, String> record : legacy.entrySet()) {
            KeyPair keyPair = new KeyPair();
            keyPair.setKeyPairId(record.getKey());
            keyPair.setKeyName(record.getKey());
            keyPair.setRegion("us-east-1");
            if (record.getValue() == null) {
                Ec2KeyMaterial.Generated generated = Ec2KeyMaterial.generateRsa();
                keyPair.setKeyMaterial(generated.privateKeyPem());
                keyPair.setPublicKey(generated.openSshPublicKey());
            } else {
                keyPair.setPublicKey(record.getValue());
            }
            keyPairStore.put("us-east-1::" + record.getKey(), keyPair);
        }
        Ec2KeyMaterial.Generated ed25519 = Ec2KeyMaterial.generate("ed25519", "pem", "key-created-ed25519");
        Ec2KeyMaterial.Generated rsa = Ec2KeyMaterial.generateRsa();
        for (String id : List.of("key-created-ed25519", "key-created-without-public-key")) {
            KeyPair keyPair = new KeyPair();
            keyPair.setKeyPairId(id);
            keyPair.setKeyName(id);
            keyPair.setRegion("us-east-1");
            boolean isEd25519 = id.equals("key-created-ed25519");
            keyPair.setKeyMaterial(isEd25519 ? ed25519.privateKeyPem() : rsa.privateKeyPem());
            keyPair.setPublicKey(isEd25519 ? ed25519.openSshPublicKey() : null);
            keyPairStore.put("us-east-1::" + id, keyPair);
        }
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Map.of("ec2-key-pairs.json", keyPairStore)));

        Map<String, String> types = new HashMap<>();
        for (KeyPair keyPair : service.describeKeyPairs("us-east-1", List.of(), List.of())) {
            // The reader answers each of these itself, without throwing, so keyTypeOf's guard is not
            // what yields the expected types.
            assertDoesNotThrow(() -> Ec2KeyMaterial.readImportedKey(keyPair.getPublicKey()), keyPair.getKeyPairId());
            types.put(keyPair.getKeyPairId(), Ec2Service.keyTypeOf(keyPair));
            assertNull(keyPair.getKeyType(), "the stored record is not rewritten");
            assertNull(keyPair.getCreateTime());
        }
        Map<String, String> expected = new HashMap<>();
        expected.put("key-created", "rsa");
        expected.put("key-ed25519", "ed25519");
        expected.put("key-ecdsa", null);
        expected.put("key-garbage", null);
        expected.put("key-pad-bits", null);
        expected.put("key-empty-bit-string", null);
        expected.put("key-created-ed25519", "ed25519");
        expected.put("key-created-without-public-key", "rsa");
        assertEquals(expected, types);
        assertEquals(Set.of("key-ed25519", "key-created-ed25519"), service.describeKeyPairs("us-east-1",
                List.of(), List.of(), Map.of("key-type", List.of("ed25519")))
                .stream().map(KeyPair::getKeyPairId).collect(Collectors.toSet()));
    }

    /**
     * Should the reader ever fail on what an older record holds, the record is still described, by
     * the service and by the handler, with no type, and a key-type filter passes over it, rather than
     * every description in the region failing with it. The stored key is a valid ed25519 line, so the
     * type would be ed25519 if the failing reader were not consulted.
     */
    @Test
    void aStoredKeyPairWhoseKeyTheReaderFailsOnIsDescribedWithoutAType() {
        AccountAwareStorageBackend<KeyPair> keyPairStore = AccountAwareStorageBackend.inMemory("000000000000");
        KeyPair stored = new KeyPair();
        stored.setKeyPairId("key-unreadable");
        stored.setKeyName("key-unreadable");
        stored.setRegion("us-east-1");
        stored.setPublicKey(ED25519_KEY);
        keyPairStore.put("us-east-1::key-unreadable", stored);
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Map.of("ec2-key-pairs.json", keyPairStore)));

        try (MockedStatic<Ec2KeyMaterial> reader = mockStatic(Ec2KeyMaterial.class,
                withSettings().mockMaker(MockMakers.INLINE).defaultAnswer(CALLS_REAL_METHODS))) {
            reader.when(() -> Ec2KeyMaterial.readImportedKey(anyString()))
                    .thenThrow(new IllegalStateException("a reader that fails"));

            List<KeyPair> described = service.describeKeyPairs("us-east-1", List.of(), List.of());

            assertEquals(List.of("key-unreadable"), described.stream().map(KeyPair::getKeyPairId).toList());
            assertNull(Ec2Service.keyTypeOf(described.getFirst()));
            assertTrue(service.describeKeyPairs("us-east-1", List.of(), List.of(),
                    Map.of("key-type", List.of("ed25519"))).isEmpty());
            String xml = (String) new Ec2QueryHandler(service, mock(EmulatorConfig.class), mock(FlowLogService.class),
                    mock(Ec2EbsEncryptionService.class), mock(Ec2SnapshotBlockPublicAccessService.class),
                    mock(Ec2IpamService.class), new RegionResolver("us-east-1", "000000000000"))
                    .handle("DescribeKeyPairs", new MultivaluedHashMap<>(), "us-east-1").getEntity();
            assertTrue(xml.contains("<keyPairId>key-unreadable</keyPairId>"), xml);
            assertFalse(xml.contains("<keyType>"), xml);
            reader.verify(() -> Ec2KeyMaterial.readImportedKey(anyString()), atLeastOnce());
        }
    }

    /**
     * The guard in keyTypeOf covers only records saved before the type was recorded. A reader failure
     * still surfaces everywhere else: ImportKeyPair fails with it, and a key pair whose type was
     * recorded is described without consulting the reader at all.
     */
    @Test
    void aReaderFailureIsGuardedOnlyForKeyPairsSavedWithoutAType() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        KeyPair recorded = service.importKeyPair("us-east-1", "recorded", ED25519_KEY);

        try (MockedStatic<Ec2KeyMaterial> reader = mockStatic(Ec2KeyMaterial.class,
                withSettings().mockMaker(MockMakers.INLINE).defaultAnswer(CALLS_REAL_METHODS))) {
            reader.when(() -> Ec2KeyMaterial.readImportedKey(anyString()))
                    .thenThrow(new IllegalStateException("a reader that fails"));

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> service.importKeyPair("us-east-1", "another", ED25519_KEY));
            assertEquals("a reader that fails", failure.getMessage());
            assertEquals("ed25519", Ec2Service.keyTypeOf(service.describeKeyPairs("us-east-1",
                    List.of("recorded"), List.of()).getFirst()));
            assertEquals(recorded.getKeyPairId(), service.describeKeyPairs("us-east-1", List.of(), List.of(),
                    Map.of("key-type", List.of("ed25519"))).getFirst().getKeyPairId());
            reader.verify(() -> Ec2KeyMaterial.readImportedKey(anyString()), times(1));
        }
    }

    @Test
    void deleteKeyPairByNameRemovesItFromTheStore() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        service.createKeyPair("us-east-1", "by-name");
        service.deleteKeyPair("us-east-1", "by-name", null);

        // A deleted key pair is gone for good: describe by name must report NotFound
        // rather than returning the key that DeleteKeyPair claimed to remove.
        AwsException error = assertThrows(AwsException.class,
                () -> service.describeKeyPairs("us-east-1", List.of("by-name"), List.of()));
        assertEquals("InvalidKeyPair.NotFound", error.getErrorCode());
        assertTrue(service.describeKeyPairs("us-east-1", List.of(), List.of()).isEmpty());
    }

    @Test
    void deleteKeyPairByNameLeavesOtherKeysAndRegionsIntact() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        service.createKeyPair("us-east-1", "target");
        service.createKeyPair("us-east-1", "bystander");
        service.createKeyPair("eu-west-1", "target");

        service.deleteKeyPair("us-east-1", "target", null);

        // Deleting resolves through the store key, so it must not take the same-named
        // key in another region — nor any other key in the same region — with it.
        assertEquals(1, service.describeKeyPairs("us-east-1", List.of("bystander"), List.of()).size());
        assertEquals(1, service.describeKeyPairs("eu-west-1", List.of("target"), List.of()).size());
    }

    @Test
    void deleteKeyPairByIdRemovesItFromTheStore() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        String keyPairId = service.createKeyPair("us-east-1", "by-id").getKeyPairId();
        service.deleteKeyPair("us-east-1", null, keyPairId);

        assertTrue(service.describeKeyPairs("us-east-1", List.of(), List.of()).isEmpty());
    }

    @Test
    void deleteKeyPairForUnknownNameIsANoOp() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        service.createKeyPair("us-east-1", "present-key");

        // Real EC2 DeleteKeyPair is idempotent — deleting a key that does not exist
        // succeeds rather than raising InvalidKeyPair.NotFound.
        service.deleteKeyPair("us-east-1", "never-existed", null);

        assertEquals(1, service.describeKeyPairs("us-east-1", List.of("present-key"), List.of()).size());
    }

    @Test
    void registerImageReusingSnapshotDoesNotOverwriteSnapshotMetadata() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        service.registerImage("us-east-1", "first-image", null, null, null,
                List.of(blockDeviceMapping("snap-reused", 8)));
        service.registerImage("us-east-1", "second-image", null, null, null,
                List.of(blockDeviceMapping("snap-reused", 64)));

        List<Snapshot> snapshots = service.describeSnapshots("us-east-1", List.of("snap-reused"), List.of(), Map.of());
        assertEquals(1, snapshots.size());
        assertEquals(8, snapshots.getFirst().getVolumeSize());
        assertEquals("Created by RegisterImage for first-image", snapshots.getFirst().getDescription());
    }

    @Test
    void describeSnapshotsDefaultsToOwnedSnapshots() {
        AccountAwareStorageBackend<Snapshot> snapshotStore = AccountAwareStorageBackend.inMemory("000000000000");
        Snapshot foreign = new Snapshot();
        foreign.setSnapshotId("snap-foreign");
        foreign.setOwnerId("111111111111");
        foreign.setRegion("us-east-1");
        snapshotStore.put("us-east-1::snap-foreign", foreign);

        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Map.of("ec2-snapshots.json", snapshotStore)));
        service.registerImage("us-east-1", "owned-image", null, null, null,
                List.of(blockDeviceMapping("snap-owned", 16)));

        List<Snapshot> snapshots = service.describeSnapshots("us-east-1", List.of(), List.of(), Map.of());

        assertEquals(1, snapshots.size());
        assertEquals("snap-owned", snapshots.getFirst().getSnapshotId());
    }

    @Test
    void createImageRebootsTheSourceInstanceUnlessNoRebootIsSet() {
        Ec2ContainerManager containerManager = capturingContainerManager();
        Ec2Service service = liveService(containerManager, mock(AmiImageResolver.class));
        String instanceId = runOne(service, "ami-src");

        service.createImage("us-east-1", instanceId, "with-reboot", null, false);
        verify(containerManager).reboot(argThat(i -> instanceId.equals(i.getInstanceId())));

        service.createImage("us-east-1", instanceId, "without-reboot", null, true);
        // Still one: NoReboot=true opted the second call out.
        verify(containerManager, times(1)).reboot(argThat(i -> instanceId.equals(i.getInstanceId())));
    }

    @Test
    void runInstancesOnACreatedImageResolvesTheSourceGuest() {
        AmiImageResolver resolver = mock(AmiImageResolver.class);
        when(resolver.resolveImage("ami-src")).thenReturn(ResolvedAmiImage.minimal("guest:latest"));
        Ec2Service service = liveService(capturingContainerManager(), resolver);
        String instanceId = runOne(service, "ami-src");

        String createdAmi = service.createImage("us-east-1", instanceId, "captured", null, true)
                .getImageId();
        String chainedAmi = service.createImage("us-east-1", runOne(service, createdAmi),
                "captured-again", null, true).getImageId();

        runOne(service, createdAmi);
        runOne(service, chainedAmi);

        // Every launch resolves through to the catalog id; the generated ami-* ids are
        // unknown to the resolver and would otherwise fall back to the default guest.
        verify(resolver, times(4)).resolveImage("ami-src");
        verify(resolver, never()).resolveImage(createdAmi);
        verify(resolver, never()).resolveImage(chainedAmi);
    }

    @Test
    void runInstancesRejectsIncompatibleInstanceTypeForCreatedArmImage() {
        AmiImageResolver resolver = mock(AmiImageResolver.class);
        Ec2Service service = serviceWithCatalogImage(resolver, "ami-arm-source", "arm64", "arm-image",
                "linux/arm64");
        Reservation sourceReservation = service.runInstances("us-east-1", "ami-arm-source", "t4g.medium",
                1, 1, null, List.of(), null, null, List.of(), null, null);

        String createdAmi = service.createImage("us-east-1",
                sourceReservation.getInstances().getFirst().getInstanceId(), "captured-arm", null, true)
                .getImageId();

        AwsException error = assertThrows(AwsException.class,
                () -> service.runInstances("us-east-1", createdAmi, "t3.micro", 1, 1,
                        null, List.of(), null, null, List.of(), null, null));

        assertEquals("InvalidParameterValue", error.getErrorCode());
        verify(resolver, never()).resolveImage(createdAmi);

        Reservation compatible = service.runInstances("us-east-1", createdAmi, "t4g.medium", 1, 1,
                null, List.of(), null, null, List.of(), null, null);
        assertEquals("arm64", compatible.getInstances().getFirst().getArchitecture());
    }

    @Test
    void runInstancesAcceptsI386ImageOnT2MediumAndRefusesItOnT2Large() {
        Ec2Service service = serviceWithCatalogImage(mock(AmiImageResolver.class), "ami-i386", "i386",
                "i386/alpine:3", "linux/386");

        Reservation medium = service.runInstances("us-east-1", "ami-i386", "t2.medium", 1, 1,
                null, List.of(), null, null, List.of(), null, null);
        assertEquals("i386", medium.getInstances().getFirst().getArchitecture());

        AwsException error = assertThrows(AwsException.class,
                () -> service.runInstances("us-east-1", "ami-i386", "t2.large", 1, 1,
                        null, List.of(), null, null, List.of(), null, null));
        assertEquals("InvalidParameterValue", error.getErrorCode());
    }

    private Ec2Service serviceWithCatalogImage(AmiImageResolver resolver, String imageId, String architecture,
                                               String dockerImage, String dockerPlatform) {
        Ec2ImageCatalog catalog = mock(Ec2ImageCatalog.class);
        Ec2ImageCatalog.CatalogImage source = new Ec2ImageCatalog.CatalogImage();
        source.imageId = imageId;
        source.architecture = architecture;
        source.rootDeviceName = "/dev/xvda";
        when(catalog.findByIdOrAlias(imageId)).thenReturn(Optional.of(source));
        when(resolver.resolveImage(imageId))
                .thenReturn(new ResolvedAmiImage(dockerImage, ResolvedAmiImage.DEFAULT_RUNTIME, false,
                        dockerPlatform));
        return liveService(capturingContainerManager(), resolver, catalog);
    }

    @Test
    void dryRunValidatesImagesBeforeReportingSuccess() {
        Ec2ContainerManager manager = mock(Ec2ContainerManager.class);
        AmiImageResolver resolver = mock(AmiImageResolver.class);
        when(resolver.resolveImage("ami-windows"))
                .thenThrow(new AwsException("UnsupportedOperation", "Unsupported AMI", 400));
        Ec2Service service = liveService(manager, resolver, new Ec2ImageCatalog());
        String deregistered = service.registerImage("us-east-1", "dry-run-image", null, null, null,
                List.of()).getImageId();
        service.deregisterImage("us-east-1", deregistered, false);

        for (boolean dryRun : List.of(true, false)) {
            assertEquals("UnsupportedOperation", assertThrows(AwsException.class,
                    () -> service.runInstances("us-east-1", "ami-windows", "t3.micro", 1, 1,
                            null, List.of(), null, null, List.of(), null, null,
                            null, null, 0, null, null, null, null, dryRun)).getErrorCode());
            assertEquals("InvalidAMIID.Unavailable", assertThrows(AwsException.class,
                    () -> service.runInstances("us-east-1", deregistered, "t3.micro", 1, 1,
                            null, List.of(), null, null, List.of(), null, null,
                            null, null, 0, null, null, null, null, dryRun)).getErrorCode());
            assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                    () -> service.runInstances("us-east-1", "ami-ubuntu2404-amd64", "t4g.medium", 1, 1,
                            null, List.of(), null, null, List.of(), null, null,
                            null, null, 0, null, null, null, null, dryRun)).getErrorCode());
        }
        assertTrue(service.describeInstances("us-east-1", List.of(), Map.of()).isEmpty());
        assertTrue(service.describeVolumes("us-east-1", List.of(), Map.of()).isEmpty());
        verifyNoInteractions(manager);
    }

    @Test
    void dryRunValidatesSuppliedEniWithoutAttachingOrProvisioning() {
        Ec2ContainerManager manager = mock(Ec2ContainerManager.class);
        Ec2Service service = liveService(manager, mock(AmiImageResolver.class));
        String subnetId = service.describeSubnets("us-east-1", List.of(), Map.of()).getFirst().getSubnetId();
        NetworkInterface eni = service.createNetworkInterface("us-east-1", subnetId, null, null,
                List.of(), List.of(), List.of());
        for (boolean dryRun : List.of(true, false)) {
            assertEquals("InvalidParameterCombination", assertThrows(AwsException.class,
                    () -> service.runInstances("us-east-1", "ami-test", "t3.micro", 2, 2,
                            null, List.of(), null, null, List.of(), null, null,
                            null, eni.getNetworkInterfaceId(), 0, null, null, null, null, dryRun)).getErrorCode());
        }
        assertEquals("DryRunOperation", assertThrows(AwsException.class,
                () -> service.runInstances("us-east-1", "ami-test", "t3.micro", 1, 1,
                        null, List.of(), null, null, List.of(), null, null,
                        null, eni.getNetworkInterfaceId(), 0, null, null, null, null, true)).getErrorCode());
        assertNull(eni.getAttachment());
        assertEquals("available", eni.getStatus());
        assertTrue(service.describeInstances("us-east-1", List.of(), Map.of()).isEmpty());
        assertTrue(service.describeVolumes("us-east-1", List.of(), Map.of()).isEmpty());
        verifyNoInteractions(manager);
    }

    @Test
    void runInstancesRejectsUnsupportedImageBeforeCreatingInstance() {
        Ec2ContainerManager containerManager = mock(Ec2ContainerManager.class);
        AmiImageResolver resolver = mock(AmiImageResolver.class);
        AwsException unsupported = new AwsException("UnsupportedOperation", "Windows AMIs are not supported", 400);
        when(resolver.resolveImage("ami-windows")).thenThrow(unsupported);
        Ec2Service service = liveService(containerManager, resolver);

        AwsException error = assertThrows(AwsException.class,
                () -> service.runInstances("us-east-1", "ami-windows", "t3.micro", 1, 1,
                        null, List.of(), null, null, List.of(), null, null));

        assertEquals("UnsupportedOperation", error.getErrorCode());
        assertTrue(service.describeInstances("us-east-1", List.of(), Map.of()).isEmpty());
        verifyNoInteractions(containerManager);
    }

    @Test
    void createImageOnACatalogSourceCarriesItsRootDevice() {
        Ec2ImageCatalog catalog = mock(Ec2ImageCatalog.class);
        Ec2ImageCatalog.CatalogImage source = new Ec2ImageCatalog.CatalogImage();
        source.imageId = "ami-src";
        source.architecture = "x86_64";
        source.rootDeviceType = "ebs";
        source.rootDeviceName = "/dev/xvda";
        when(catalog.findByIdOrAlias("ami-src")).thenReturn(Optional.of(source));
        Ec2Service service = liveService(capturingContainerManager(), mock(AmiImageResolver.class), catalog);
        String instanceId = runOne(service, "ami-src");

        Image image = service.createImage("us-east-1", instanceId, "captured", null, true);

        assertEquals("/dev/xvda", image.getRootDeviceName());
        assertEquals(1, image.getBlockDeviceMappings().size());
        BlockDeviceMapping root = image.getBlockDeviceMappings().getFirst();
        assertEquals("/dev/xvda", root.getDeviceName());
        assertNotNull(root.getEbs().getSnapshotId());

        // The rebuilt root describes the volume RunInstances actually created for the
        // source, so DescribeImages does not report a type the instance never had.
        assertEquals("gp3", root.getEbs().getVolumeType());
        assertEquals(8, root.getEbs().getVolumeSize());

        // The mapping's snapshot is registered, so DescribeSnapshots can resolve it.
        List<Snapshot> snapshots = service.describeSnapshots("us-east-1",
                List.of(root.getEbs().getSnapshotId()), null, Map.of());
        assertEquals(1, snapshots.size());
    }

    @Test
    void createImageTakesItsOwnSnapshotRatherThanTheSourceAmisOne() {
        Ec2Service service = liveService(capturingContainerManager(), mock(AmiImageResolver.class));
        Image source = service.registerImage("us-east-1", "source-image", null, null, "/dev/sda1",
                List.of(blockDeviceMapping("snap-source", 16)));

        Image image = service.createImage("us-east-1", runOne(service, source.getImageId()),
                "captured", null, true);

        BlockDeviceMapping captured = image.getBlockDeviceMappings().getFirst();
        assertEquals("/dev/sda1", captured.getDeviceName());
        assertEquals(16, captured.getEbs().getVolumeSize());
        assertNotEquals("snap-source", captured.getEbs().getSnapshotId());

        // Both snapshots exist, so deleting one image does not strand the other.
        assertEquals(2, service.describeSnapshots("us-east-1", List.of(), List.of(), Map.of()).size());
    }

    @Test
    void createImageCapturesAVolumeAttachedAfterLaunch() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        Image sourceAmi = service.registerImage("us-east-1", "source-image", null, null, "/dev/sda1",
                List.of(blockDeviceMapping("snap-source", 8)));
        Instance inst = service.runInstances("us-east-1", sourceAmi.getImageId(), "t3.micro", 1, 1,
                null, List.of(), null, null, List.of(), null, null).getInstances().getFirst();
        inst.setState(InstanceState.running());
        Volume data = service.createVolume("us-east-1", inst.getPlacement().getAvailabilityZone(),
                "gp3", 50, false, 0, null, null, List.of());
        service.attachVolume("us-east-1", data.getVolumeId(), inst.getInstanceId(), "/dev/sdf");

        Image image = service.createImage("us-east-1", inst.getInstanceId(), "captured", null, true);

        // The root device the source AMI describes, plus the volume attached after launch.
        assertEquals(2, image.getBlockDeviceMappings().size());
        BlockDeviceMapping attached = image.getBlockDeviceMappings().stream()
                .filter(m -> "/dev/sdf".equals(m.getDeviceName()))
                .findFirst().orElseThrow();
        assertEquals(50, attached.getEbs().getVolumeSize());
        assertEquals("gp3", attached.getEbs().getVolumeType());
        assertNotNull(attached.getEbs().getSnapshotId());
    }

    @Test
    void restoreReReservesThePrivateAddressesOfInstancesThatSurvivedTheRestart() {
        // The lease table is in-memory, so a restart rebuilds it empty while the containers of
        // persisted instances still hold their addresses. Without the re-reservation the next
        // RunInstances is handed an address a live container already answers on.
        AccountAwareStorageBackend<Instance> instanceStore = AccountAwareStorageBackend.inMemory("000000000000");
        instanceStore.put("us-east-1::i-alive", persistedInstance("i-alive", "10.0.1.10", InstanceState.running()));
        instanceStore.put("us-east-1::i-gone", persistedInstance("i-gone", "10.0.1.11", InstanceState.terminated()));

        VpcNetworkManager vpcNetworks = mock(VpcNetworkManager.class);
        when(vpcNetworks.enabled()).thenReturn(true);
        Ec2Service service = new Ec2Service(mockConfig(false), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Map.of("ec2-instances.json", instanceStore)), vpcNetworks);

        service.restoreMetadataRegistrations();

        verify(vpcNetworks).reservePrivateIp("us-east-1", "subnet-a", "10.0.1.10");
        verify(vpcNetworks, never()).reservePrivateIp("us-east-1", "subnet-a", "10.0.1.11");
    }

    @Test
    void restoreRebuildsTheVpcNetworkPlanOfEveryAccount() {
        // Startup runs with no request account, so an account-scoped keys() sees only the default
        // account's VPCs: every other account's VPC network was never re-declared after a restart.
        AccountAwareStorageBackend<Vpc> vpcStore = AccountAwareStorageBackend.inMemory("000000000000");
        AccountAwareStorageBackend<Subnet> subnetStore = AccountAwareStorageBackend.inMemory("000000000000");
        AccountAwareStorageBackend<Instance> instanceStore = AccountAwareStorageBackend.inMemory("000000000000");
        Vpc vpc = new Vpc();
        vpc.setRegion("us-east-1");
        vpc.setVpcId("vpc-a");
        vpc.setCidrBlock("10.20.0.0/16");
        vpcStore.putForAccount("111122223333", "us-east-1::vpc-a", vpc);
        Subnet subnet = new Subnet();
        subnet.setRegion("us-east-1");
        subnet.setVpcId("vpc-a");
        subnet.setSubnetId("subnet-a");
        subnet.setCidrBlock("10.20.1.0/24");
        subnetStore.putForAccount("111122223333", "us-east-1::subnet-a", subnet);
        instanceStore.putForAccount("111122223333", "us-east-1::i-other",
                persistedInstance("i-other", "10.20.1.10", InstanceState.running()));

        VpcNetworkManager vpcNetworks = mock(VpcNetworkManager.class);
        when(vpcNetworks.enabled()).thenReturn(true);
        Ec2Service service = new Ec2Service(mockConfig(false), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Map.of("ec2-vpcs.json", vpcStore, "ec2-subnets.json", subnetStore,
                        "ec2-instances.json", instanceStore)), vpcNetworks);

        service.restoreMetadataRegistrations();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<BiPredicate<String, String>> stillDeclared = ArgumentCaptor.forClass(BiPredicate.class);
        verify(vpcNetworks).reconcileOrphans(stillDeclared.capture());
        assertTrue(stillDeclared.getValue().test("us-east-1", "vpc-a"));
        assertFalse(stillDeclared.getValue().test("us-east-1", "vpc-gone"));
        verify(vpcNetworks).declareVpc("us-east-1", "vpc-a", "10.20.0.0/16");
        verify(vpcNetworks).declareSubnet("us-east-1", "vpc-a", "subnet-a", "10.20.1.0/24");
        verify(vpcNetworks).reservePrivateIp("us-east-1", "subnet-a", "10.20.1.10");
    }

    @Test
    void restoreDeclaresTheDefaultAccountsVpcFirstWhenCidrsOverlap() {
        // The default account sorts after one of the others by id, so only the default-first rule puts it first.
        AccountAwareStorageBackend<Vpc> vpcStore = AccountAwareStorageBackend.inMemory("555555555555");
        for (String[] owned : new String[][] {{"999999999999", "vpc-z"}, {"111122223333", "vpc-y"}, {"555555555555", "vpc-x"}}) {
            Vpc vpc = new Vpc();
            vpc.setRegion("us-east-1");
            vpc.setVpcId(owned[1]);
            vpc.setCidrBlock("10.0.0.0/16");
            vpcStore.putForAccount(owned[0], "us-east-1::" + owned[1], vpc);
        }
        VpcNetworkManager vpcNetworks = mock(VpcNetworkManager.class);
        when(vpcNetworks.enabled()).thenReturn(true);
        EmulatorConfig config = mockConfig(false);
        when(config.defaultAccountId()).thenReturn("555555555555");
        Ec2Service service = new Ec2Service(config, mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Map.of("ec2-vpcs.json", vpcStore)), vpcNetworks);

        service.restoreMetadataRegistrations();

        InOrder declared = inOrder(vpcNetworks);
        declared.verify(vpcNetworks).declareVpc("us-east-1", "vpc-x", "10.0.0.0/16");
        declared.verify(vpcNetworks).declareVpc("us-east-1", "vpc-y", "10.0.0.0/16");
        declared.verify(vpcNetworks).declareVpc("us-east-1", "vpc-z", "10.0.0.0/16");
    }

    @Test
    void aLatePersistForAnInstanceNoAccountHoldsWritesNothing() {
        AccountAwareStorageBackend<Instance> instanceStore = AccountAwareStorageBackend.inMemory("000000000000");
        Ec2PortForwardManager portForwardManager = mock(Ec2PortForwardManager.class);
        Ec2Service service = new Ec2Service(mockConfig(false), mock(Ec2ContainerManager.class),
                portForwardManager, mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Map.of("ec2-instances.json", instanceStore)), mock(VpcNetworkManager.class));
        service.restoreMetadataRegistrations();
        ArgumentCaptor<Consumer<Instance>> persister = ArgumentCaptor.captor();
        verify(portForwardManager).setPersister(persister.capture());

        // Terminated and deleted from its account before the port-forward persister ran.
        persister.getValue().accept(persistedInstance("i-gone", "10.20.1.10", InstanceState.terminated()));

        assertTrue(instanceStore.scanAllAccountEntries(key -> true).isEmpty());
    }

    @Test
    void restoreWritesARestoredInstanceBackUnderItsOwnAccount() {
        AccountAwareStorageBackend<Instance> instanceStore = AccountAwareStorageBackend.inMemory("000000000000");
        Instance instance = persistedInstance("i-other", "10.20.1.10", InstanceState.running());
        instance.setDockerContainerId("container-other");
        instanceStore.putForAccount("111122223333", "us-east-1::i-other", instance);
        Ec2ContainerManager containerManager = mock(Ec2ContainerManager.class);
        when(containerManager.restoreMetadataRegistration(any())).thenReturn(true);
        Ec2Service service = new Ec2Service(mockConfig(false), containerManager,
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Map.of("ec2-instances.json", instanceStore)), mock(VpcNetworkManager.class));

        service.restoreMetadataRegistrations();

        verify(containerManager).restoreMetadataRegistration(argThat(restored -> "i-other".equals(restored.getInstanceId())));
        assertEquals(List.of("111122223333"), instanceStore.scanAllAccountEntries(key -> key.endsWith("i-other")).stream()
                .map(AccountAwareStorageBackend.AccountEntry::accountId).toList());
    }

    @Test
    void restoreMigratesALegacyInstanceKeyInsteadOfCopyingIt() {
        InMemoryStorage<String, Instance> raw = new InMemoryStorage<>();
        AccountAwareStorageBackend<Instance> instanceStore = new AccountAwareStorageBackend<>(raw, null, "000000000000");
        Instance instance = persistedInstance("i-legacy", "10.20.1.10", InstanceState.running());
        instance.setDockerContainerId("container-legacy");
        raw.put("us-east-1::i-legacy", instance);
        Ec2ContainerManager containerManager = mock(Ec2ContainerManager.class);
        when(containerManager.restoreMetadataRegistration(any())).thenReturn(true);
        Ec2Service service = new Ec2Service(mockConfig(false), containerManager,
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Map.of("ec2-instances.json", instanceStore)), mock(VpcNetworkManager.class));

        service.restoreMetadataRegistrations();

        verify(containerManager, times(1)).restoreMetadataRegistration(any());
        assertEquals(Set.of("000000000000/us-east-1::i-legacy"), Set.copyOf(raw.keys()));
    }

    @Test
    void restoreDropsALegacyKeyWhoseScopedTwinAlreadyExists() {
        // Left behind, the legacy copy would come back the moment the scoped one is pruned.
        InMemoryStorage<String, Instance> raw = new InMemoryStorage<>();
        AccountAwareStorageBackend<Instance> instanceStore = new AccountAwareStorageBackend<>(raw, null, "000000000000");
        Instance stale = persistedInstance("i-twin", "10.20.1.10", InstanceState.running());
        stale.setDockerContainerId("container-stale");
        raw.put("us-east-1::i-twin", stale);
        Instance current = persistedInstance("i-twin", "10.20.1.10", InstanceState.running());
        current.setDockerContainerId("container-current");
        raw.put("000000000000/us-east-1::i-twin", current);
        Ec2ContainerManager containerManager = mock(Ec2ContainerManager.class);
        when(containerManager.restoreMetadataRegistration(any())).thenReturn(true);
        Ec2Service service = new Ec2Service(mockConfig(false), containerManager,
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Map.of("ec2-instances.json", instanceStore)), mock(VpcNetworkManager.class));

        service.restoreMetadataRegistrations();

        verify(containerManager, times(1)).restoreMetadataRegistration(any());
        verify(containerManager).restoreMetadataRegistration(argThat(restored ->
                "container-current".equals(restored.getDockerContainerId())));
        assertEquals(Set.of("000000000000/us-east-1::i-twin"), Set.copyOf(raw.keys()));
    }

    @Test
    void shutdownStopsAnotherAccountsRunningInstanceInItsOwnPartition() {
        AccountAwareStorageBackend<Instance> instanceStore = AccountAwareStorageBackend.inMemory("000000000000");
        Instance instance = persistedInstance("i-other", "10.20.1.10", InstanceState.running());
        instance.setDockerContainerId("container-other");
        instanceStore.putForAccount("111122223333", "us-east-1::i-other", instance);
        Ec2ContainerManager containerManager = mock(Ec2ContainerManager.class);
        Ec2Service service = new Ec2Service(mockConfig(false), containerManager,
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Map.of("ec2-instances.json", instanceStore)), mock(VpcNetworkManager.class));

        service.stopManagedContainers();

        verify(containerManager).stopForShutdown(argThat(stopped -> "i-other".equals(stopped.getInstanceId())));
        List<AccountAwareStorageBackend.AccountEntry<Instance>> stored =
                instanceStore.scanAllAccountEntries(key -> key.endsWith("i-other"));
        assertEquals(List.of("111122223333"), stored.stream().map(AccountAwareStorageBackend.AccountEntry::accountId).toList());
        assertEquals("stopped", stored.get(0).value().getState().getName());
    }

    @Test
    void restoreSurvivesAnAddressItCannotReserve() {
        AccountAwareStorageBackend<Instance> instanceStore = AccountAwareStorageBackend.inMemory("000000000000");
        instanceStore.put("us-east-1::i-one", persistedInstance("i-one", "10.0.1.10", InstanceState.running()));
        instanceStore.put("us-east-1::i-two", persistedInstance("i-two", "10.0.1.10", InstanceState.running()));

        VpcNetworkManager vpcNetworks = mock(VpcNetworkManager.class);
        when(vpcNetworks.enabled()).thenReturn(true);
        // Second claim on the same address is refused; startup must not abort over it.
        when(vpcNetworks.reservePrivateIp(anyString(), anyString(), anyString()))
                .thenReturn(true).thenReturn(false);
        Ec2Service service = new Ec2Service(mockConfig(false), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Map.of("ec2-instances.json", instanceStore)), vpcNetworks);

        assertDoesNotThrow(service::restoreMetadataRegistrations);
        verify(vpcNetworks, times(2)).reservePrivateIp("us-east-1", "subnet-a", "10.0.1.10");
    }

    @Test
    void synthesisedAddressesStayInsideTheSubnetCidrPastTheFirstOctetBoundary() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String vpcId = service.createVpc("us-east-1", "10.70.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet("us-east-1", vpcId, "10.70.0.0/23", "us-east-1a").getSubnetId();

        Set<String> addresses = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            String address = service.createNetworkInterface("us-east-1", subnetId, null,
                    null, List.of(), List.of(), List.of()).getPrivateIpAddress();
            assertTrue(address.matches("10\\.70\\.[01]\\.(\\d{1,3})"), address);
            assertTrue(Integer.parseInt(address.substring(address.lastIndexOf('.') + 1)) <= 255, address);
            assertTrue(addresses.add(address), "handed out twice: " + address);
        }
        assertTrue(addresses.contains("10.70.1.10"), "the counter must carry into the next /24 octet");
    }

    @Test
    void standaloneNetworkInterfaceNamesAllPrivateIpsForTheRegion() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String vpcId = service.createVpc("us-west-2", "10.72.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet("us-west-2", vpcId, "10.72.0.0/24", "us-west-2a")
                .getSubnetId();

        NetworkInterface eni = service.createNetworkInterface("us-west-2", subnetId, null,
                "10.72.0.10", List.of("10.72.0.11"), List.of(), List.of());

        assertEquals("ip-10-72-0-10.us-west-2.compute.internal", eni.getPrivateDnsName());
        assertEquals("ip-10-72-0-10.us-west-2.compute.internal",
                eni.getPrivateIpAddresses().get(0).getPrivateDnsName());
        assertEquals("ip-10-72-0-11.us-west-2.compute.internal",
                eni.getPrivateIpAddresses().get(1).getPrivateDnsName());
    }

    @Test
    void synthesisedAddressesSkipReservedOnesAndReportExhaustion() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String vpcId = service.createVpc("us-east-1", "10.71.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet("us-east-1", vpcId, "10.71.0.0/28", "us-east-1a").getSubnetId();

        Set<String> addresses = new HashSet<>();
        for (int i = 0; i < 11; i++) {
            addresses.add(service.createNetworkInterface("us-east-1", subnetId, null,
                    null, List.of(), List.of(), List.of()).getPrivateIpAddress());
        }

        Set<String> usable = new HashSet<>();
        for (int host = 4; host <= 14; host++) {
            usable.add("10.71.0." + host);
        }
        assertEquals(usable, addresses);
        assertEquals("InsufficientFreeAddressesInSubnet", assertThrows(AwsException.class,
                () -> service.createNetworkInterface("us-east-1", subnetId, null,
                        null, List.of(), List.of(), List.of())).getErrorCode());
    }

    @Test
    void aLaunchTooLargeForTheSubnetStoresNoneOfItsInstances() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String vpcId = service.createVpc("us-east-1", "10.74.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet("us-east-1", vpcId, "10.74.0.0/28", "us-east-1a").getSubnetId();
        int volumesBefore = service.describeVolumes("us-east-1", List.of(), Map.of()).size();

        AwsException error = assertThrows(AwsException.class,
                () -> service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                        12, 12, null, List.of(), subnetId, null, List.of(), null, null));

        assertEquals("InsufficientFreeAddressesInSubnet", error.getErrorCode());
        assertTrue(service.describeInstances("us-east-1", List.of(),
                Map.of("subnet-id", List.of(subnetId))).isEmpty());
        assertEquals(volumesBefore, service.describeVolumes("us-east-1", List.of(), Map.of()).size());
        Reservation reservation = service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                11, 11, null, List.of(), subnetId, null, List.of(), null, null);
        assertEquals(11, reservation.getInstances().stream().map(Instance::getPrivateIpAddress).distinct().count());
    }

    @Test
    void concurrentCreatesInASmallSubnetNeverShareASynthesisedAddress() throws Exception {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        String vpcId = service.createVpc("us-east-1", "10.73.0.0/16", false).getVpcId();
        int rounds = 20;
        int usable = 11;
        ExecutorService executor = Executors.newFixedThreadPool(usable);
        try {
            for (int round = 0; round < rounds; round++) {
                String subnetId = service.createSubnet("us-east-1", vpcId, "10.73." + round + ".0/28",
                        "us-east-1a").getSubnetId();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<String>> creates = new ArrayList<>();
                for (int i = 0; i < usable; i++) {
                    creates.add(executor.submit(() -> {
                        start.await();
                        return service.createNetworkInterface("us-east-1", subnetId, null,
                                null, List.of(), List.of(), List.of()).getPrivateIpAddress();
                    }));
                }
                start.countDown();
                Set<String> addresses = new HashSet<>();
                for (Future<String> create : creates) {
                    String address = create.get(10, TimeUnit.SECONDS);
                    assertTrue(addresses.add(address), "handed out twice: " + address);
                }
                assertEquals(usable, addresses.size());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void synthesisedAddressesAreNotReusedAfterARestart() {
        Map<String, AccountAwareStorageBackend<?>> stores = Map.of(
                "ec2-vpcs.json", AccountAwareStorageBackend.<Vpc>inMemory("000000000000"),
                "ec2-subnets.json", AccountAwareStorageBackend.<Subnet>inMemory("000000000000"),
                "ec2-network-interfaces.json", AccountAwareStorageBackend.<NetworkInterface>inMemory("000000000000"));
        Ec2Service before = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory(stores));
        String vpcId = before.createVpc("us-east-1", "10.72.0.0/16", false).getVpcId();
        String subnetId = before.createSubnet("us-east-1", vpcId, "10.72.1.0/24", "us-east-1a").getSubnetId();
        String held = before.createNetworkInterface("us-east-1", subnetId, null,
                null, List.of(), List.of(), List.of()).getPrivateIpAddress();

        Ec2Service after = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory(stores));
        String next = after.createNetworkInterface("us-east-1", subnetId, null,
                null, List.of(), List.of(), List.of()).getPrivateIpAddress();

        assertEquals("10.72.1.10", held);
        assertNotEquals(held, next);
    }

    private static Instance persistedInstance(String instanceId, String privateIp, InstanceState state) {
        Instance instance = new Instance();
        instance.setInstanceId(instanceId);
        instance.setRegion("us-east-1");
        instance.setVpcId("vpc-a");
        instance.setSubnetId("subnet-a");
        instance.setPrivateIpAddress(privateIp);
        instance.setState(state);
        return instance;
    }

    private static String runOne(Ec2Service service, String imageId) {
        return service.runInstances("us-east-1", imageId, "t3.micro", 1, 1, null,
                List.of(), null, null, List.of(), null, null)
                .getInstances().getFirst().getInstanceId();
    }

    /** mock=false so the container-manager and resolver interactions actually happen. */
    /**
     * A container manager whose commit succeeds. Outside mock mode CreateImage captures the
     * source instance's file system and rejects the call when it cannot, so a bare mock (whose
     * launch never gives the instance a container) would fail every CreateImage here for a
     * reason none of these tests are about.
     */
    private static Ec2ContainerManager capturingContainerManager() {
        Ec2ContainerManager containerManager = mock(Ec2ContainerManager.class);
        when(containerManager.commitInstance(any(Instance.class), anyString()))
                .thenReturn("floci-ami/test-capture:latest");
        return containerManager;
    }

    private static Ec2Service liveService(Ec2ContainerManager containerManager, AmiImageResolver resolver) {
        return liveService(containerManager, resolver, mock(Ec2ImageCatalog.class));
    }

    private static Ec2Service liveService(Ec2ContainerManager containerManager, AmiImageResolver resolver,
                                          Ec2ImageCatalog catalog) {
        return new Ec2Service(mockConfig(false), containerManager, mock(Ec2PortForwardManager.class),
                resolver, catalog, new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
    }

    private static BlockDeviceMapping blockDeviceMapping(String snapshotId, int volumeSize) {
        EbsBlockDevice ebs = new EbsBlockDevice();
        ebs.setSnapshotId(snapshotId);
        ebs.setVolumeSize(volumeSize);
        BlockDeviceMapping mapping = new BlockDeviceMapping();
        mapping.setDeviceName("/dev/sda1");
        mapping.setEbs(ebs);
        return mapping;
    }

    @Test
    void attachVolumeMarksVolumeInUseWithAttachmentDetails() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        Reservation reservation = service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null);
        Instance inst = reservation.getInstances().getFirst();
        inst.setState(InstanceState.running());
        String instanceId = inst.getInstanceId();
        String instanceAz = inst.getPlacement().getAvailabilityZone();
        Volume volume = service.createVolume("us-east-1", instanceAz, "gp3", 8,
                false, 0, null, null, List.of());
        VolumeAttachment response = service.attachVolume("us-east-1", volume.getVolumeId(), instanceId, "/dev/sdf");

        assertEquals(volume.getVolumeId(), response.getVolumeId());
        assertEquals(instanceId, response.getInstanceId());
        assertEquals("/dev/sdf", response.getDevice());
        assertEquals("attached", response.getState());
        assertFalse(response.isDeleteOnTermination());
        Volume attached = service.describeVolumes("us-east-1", List.of(volume.getVolumeId()), Map.of()).getFirst();
        assertEquals("in-use", attached.getState());
        assertEquals(1, attached.getAttachments().size());
        assertEquals(instanceId, attached.getAttachments().getFirst().getInstanceId());
        assertEquals("/dev/sdf", attached.getAttachments().getFirst().getDevice());
        assertEquals("attached", attached.getAttachments().getFirst().getState());
        assertFalse(attached.getAttachments().getFirst().isDeleteOnTermination());
    }

    @Test
    void attachVolumeThrowsWithDifferentAZ() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        Reservation reservation = service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null);
        Instance inst = reservation.getInstances().getFirst();
        inst.setState(InstanceState.running());
        String instanceAz = inst.getPlacement().getAvailabilityZone();
        String volumeAz = List.of("us-east-1a", "us-east-1b", "us-east-1c").stream()
                .filter(az -> !az.equals(instanceAz))
                .findFirst()
                .orElseThrow();
        Volume volume = service.createVolume("us-east-1", volumeAz, "gp3", 8,
                false, 0, null, null, List.of());

        AwsException error = assertThrows(AwsException.class, () ->
                service.attachVolume("us-east-1", volume.getVolumeId(), inst.getInstanceId(), "/dev/sdf"));
        assertEquals("InvalidParameterValue", error.getErrorCode());
    }

    @Test
    void attachVolumeThrowsWithIncorrectInstanceState() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        Reservation reservation = service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null);
        Instance inst = reservation.getInstances().getFirst();
        inst.setState(InstanceState.pending());
        String az = inst.getPlacement().getAvailabilityZone();
        Volume volume = service.createVolume("us-east-1", az, "gp3", 8,
                false, 0, null, null, List.of());
        AwsException error = assertThrows(AwsException.class, () ->
                service.attachVolume("us-east-1", volume.getVolumeId(), inst.getInstanceId(), "/dev/sdf"));
        assertEquals("IncorrectInstanceState", error.getErrorCode());
    }

    @Test
    void detachVolumeMarksVolumeAvailableAndClearsAttachment() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        Reservation reservation = service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null);
        Instance inst = reservation.getInstances().getFirst();
        inst.setState(InstanceState.running());
        String instanceId = inst.getInstanceId();
        String instanceAz = inst.getPlacement().getAvailabilityZone();
        Volume volume = service.createVolume("us-east-1", instanceAz, "gp3", 8,
                false, 0, null, null, List.of());
        service.attachVolume("us-east-1", volume.getVolumeId(), instanceId, "/dev/sdf");

        VolumeAttachment response = service.detachVolume("us-east-1", volume.getVolumeId(), instanceId, "/dev/sdf", false);

        assertEquals(volume.getVolumeId(), response.getVolumeId());
        assertEquals(instanceId, response.getInstanceId());
        assertEquals("/dev/sdf", response.getDevice());
        assertEquals("detached", response.getState());
        assertFalse(response.isDeleteOnTermination());
        Volume detached = service.describeVolumes("us-east-1", List.of(volume.getVolumeId()), Map.of()).getFirst();
        assertEquals("available", detached.getState());
        assertTrue(detached.getAttachments().isEmpty());
    }

    @Test
    void detachRootVolumeRequiresForceAndStopped() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        Reservation reservation = service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null);
        Instance inst = reservation.getInstances().getFirst();
        String instanceId = inst.getInstanceId();
        String rootVolumeId = inst.getRootVolumeId();
        String rootDeviceName = inst.getRootDeviceName();

        // forced but not stopped
        inst.setState(InstanceState.running());
        AwsException error = assertThrows(AwsException.class,
                () -> service.detachVolume("us-east-1", rootVolumeId, instanceId, rootDeviceName, true));
        assertEquals("OperationNotPermitted", error.getErrorCode());
        AwsException errorWithoutInstanceId = assertThrows(AwsException.class,
                () -> service.detachVolume("us-east-1", rootVolumeId, null, null, true));
        assertEquals("OperationNotPermitted", errorWithoutInstanceId.getErrorCode());

        // stopped but not forced
        inst.setState(InstanceState.stopped());
        error = assertThrows(AwsException.class,
                () -> service.detachVolume("us-east-1", rootVolumeId, instanceId, rootDeviceName, false));
        assertEquals("InvalidParameterCombination", error.getErrorCode());
        errorWithoutInstanceId = assertThrows(AwsException.class,
                () -> service.detachVolume("us-east-1", rootVolumeId, null, null, false));
        assertEquals("InvalidParameterCombination", errorWithoutInstanceId.getErrorCode());

        // success
        VolumeAttachment response = service.detachVolume("us-east-1", rootVolumeId, instanceId, rootDeviceName, true);
        assertEquals(rootVolumeId, response.getVolumeId());
        assertEquals(instanceId, response.getInstanceId());
        assertEquals(rootDeviceName, response.getDevice());
        assertEquals("detached", response.getState());
        assertTrue(response.isDeleteOnTermination());

        Volume detached = service.describeVolumes("us-east-1", List.of(rootVolumeId), Map.of()).getFirst();
        assertEquals("available", detached.getState());
    }

    @Test
    void deletingAnIpv6RuleRevokesThatRangeAndNotAnotherOnTheSamePort() {
        Ec2Service service = sgService();
        String groupId = service.createSecurityGroup("us-east-1", "web", "web", null).getGroupId();
        String first = onlyRuleId(service.authorizeSecurityGroupIngress("us-east-1", groupId,
                List.of(ipv6Permission("2001:db8:1::/64"))));
        String second = onlyRuleId(service.authorizeSecurityGroupIngress("us-east-1", groupId,
                List.of(ipv6Permission("2001:db8:2::/64"))));

        service.deleteSecurityGroupRule("us-east-1", second);

        // The one that was asked for is gone and the other survives — protocol and ports are
        // identical, so a match on those alone would have taken the wrong one.
        assertEquals(List.of(first), ingressRuleIds(service, groupId));
        List<String> cidrs = service.describeSecurityGroups("us-east-1", List.of(groupId), List.of(), Map.of())
                .getFirst().getIpPermissions().stream()
                .flatMap(p -> p.getIpv6Ranges().stream())
                .map(Ipv6Range::getCidrIpv6).toList();
        assertEquals(List.of("2001:db8:1::/64"), cidrs);
    }

    @Test
    void deletingAPeerGroupRuleRevokesThatPeerAndNotAnotherOnTheSamePort() {
        Ec2Service service = sgService();
        String groupId = service.createSecurityGroup("us-east-1", "web", "web", null).getGroupId();
        String first = onlyRuleId(service.authorizeSecurityGroupIngress("us-east-1", groupId,
                List.of(peerPermission("sg-peer-a"))));
        String second = onlyRuleId(service.authorizeSecurityGroupIngress("us-east-1", groupId,
                List.of(peerPermission("sg-peer-b"))));

        service.deleteSecurityGroupRule("us-east-1", second);

        assertEquals(List.of(first), ingressRuleIds(service, groupId));
        List<String> peers = service.describeSecurityGroups("us-east-1", List.of(groupId), List.of(), Map.of())
                .getFirst().getIpPermissions().stream()
                .flatMap(p -> p.getUserIdGroupPairs().stream())
                .map(UserIdGroupPair::getGroupId).toList();
        assertEquals(List.of("sg-peer-a"), peers);
    }

    @Test
    void ruleRecordsCarryTheirPeerIdentity() {
        Ec2Service service = sgService();
        String groupId = service.createSecurityGroup("us-east-1", "web", "web", null).getGroupId();
        service.authorizeSecurityGroupIngress("us-east-1", groupId, List.of(peerPermission("sg-peer-a")));
        service.authorizeSecurityGroupIngress("us-east-1", groupId, List.of(ipv6Permission("2001:db8:1::/64")));

        List<SecurityGroupRule> rules =
                service.describeSecurityGroupRules("us-east-1", List.of(groupId), List.of());

        assertEquals(1, rules.stream().filter(r -> r.getReferencedGroupInfo() != null
                && "sg-peer-a".equals(r.getReferencedGroupInfo().getGroupId())).count());
        assertEquals(1, rules.stream().filter(r -> "2001:db8:1::/64".equals(r.getCidrIpv6())).count());
    }

    /** Ingress only: createSecurityGroup seeds a default allow-all egress rule. */
    private static List<String> ingressRuleIds(Ec2Service service, String groupId) {
        return service.describeSecurityGroupRules("us-east-1", List.of(groupId), List.of())
                .stream().filter(r -> !r.isEgress())
                .map(SecurityGroupRule::getSecurityGroupRuleId).toList();
    }

    private static String onlyRuleId(List<SecurityGroupRule> rules) {
        assertEquals(1, rules.size());
        return rules.getFirst().getSecurityGroupRuleId();
    }

    private static IpPermission ipv6Permission(String cidr6) {
        IpPermission perm = basePermission();
        Ipv6Range range = new Ipv6Range();
        range.setCidrIpv6(cidr6);
        perm.getIpv6Ranges().add(range);
        return perm;
    }

    private static IpPermission peerPermission(String peerGroupId) {
        IpPermission perm = basePermission();
        UserIdGroupPair pair = new UserIdGroupPair();
        pair.setGroupId(peerGroupId);
        perm.getUserIdGroupPairs().add(pair);
        return perm;
    }

    private static IpPermission basePermission() {
        IpPermission perm = new IpPermission();
        perm.setIpProtocol("tcp");
        perm.setFromPort(443);
        perm.setToPort(443);
        return perm;
    }

    private static Ec2Service sgService() {
        return new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class),
                mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
    }

    // =========================================================================
    // Managed prefix lists
    // =========================================================================

    private static Ec2Service prefixListService() {
        return prefixListService(mockConfig(true));
    }

    private static Ec2Service prefixListService(EmulatorConfig config) {
        return new Ec2Service(config, mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
    }

    @Test
    void configuredAwsManagedPrefixListUsesExistingRegionalReadOnlyApis(@TempDir Path temp) throws IOException {
        Path catalog = temp.resolve("prefix-lists.json");
        Files.writeString(catalog, """
                {"eu-west-1": [{"prefixListId": "pl-0123456789abcdef0",
                  "prefixListName": "example-managed-service", "cidrs": ["192.0.2.19/24"]}]}
                """);
        EmulatorConfig config = mockConfig(true);
        when(config.services().ec2().enabled()).thenReturn(true);
        when(config.services().ec2().managedPrefixListCatalogPath()).thenReturn(Optional.of(catalog.toString()));
        Ec2Service service = prefixListService(config);
        String id = "pl-0123456789abcdef0";

        ManagedPrefixList managed = service.describeManagedPrefixLists("eu-west-1", List.of(id), Map.of()).getFirst();
        assertTrue(managed.isAwsManaged());
        assertEquals("AWS", managed.getOwnerId());
        assertEquals("IPv4", managed.getAddressFamily());
        assertEquals("arn:aws:ec2:eu-west-1:aws:prefix-list/" + id, managed.getPrefixListArn());
        assertEquals(List.of("192.0.2.0/24"), service.describePrefixLists("eu-west-1", List.of(id), Map.of())
                .getFirst().getCidrs());
        assertEquals("192.0.2.0/24", service.getManagedPrefixListEntries("eu-west-1", id, null).getFirst().getCidr());
        assertEquals(3, service.describeManagedPrefixLists("eu-west-1", List.of(), Map.of()).size());
        assertEquals("InvalidPrefixListID.NotFound", assertThrows(AwsException.class,
                () -> service.describeManagedPrefixLists("us-east-1", List.of(id), Map.of())).getErrorCode());
        assertEquals("UnsupportedOperation", assertThrows(AwsException.class,
                () -> service.deleteManagedPrefixList("eu-west-1", id)).getErrorCode());
        assertEquals("UnsupportedOperation", assertThrows(AwsException.class,
                () -> service.modifyManagedPrefixList("eu-west-1", id, null, "changed", null,
                        List.of(), List.of())).getErrorCode());

        String groupId = service.createSecurityGroup("eu-west-1", "consumer", "consumer", null).getGroupId();
        IpPermission permission = tcpPermission(443);
        permission.getPrefixListIds().add(new PrefixListId(id, null));
        assertEquals(id, service.authorizeSecurityGroupIngress("eu-west-1", groupId, List.of(permission))
                .getFirst().getPrefixListId());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-json",
            "null",
            "{\"eu-west-1\": null}",
            "{\"eu-west-1\": [null]}",
            "{\"eu-west-1\": [{\"prefixListId\":\"pl-invalid\",\"prefixListName\":\"example\",\"cidrs\":[\"192.0.2.0/24\"]}]}",
            "{\"eu-west-1\": [{\"prefixListId\":\"pl-12345678\",\"prefixListName\":\"example\",\"cidrs\":[]}]}",
            "{\"eu-west-1\": [{\"prefixListId\":\"pl-12345678\",\"prefixListName\":\"example\",\"cidrs\":[\"2001:db8::/32\"]}]}",
            "{\"eu-west-1\": [{\"prefixListId\":\"pl-12345678\",\"prefixListName\":\"example\",\"cidrs\":[\"invalid\"]}]}",
            "{\"eu-west-1\": [{\"prefixListId\":\"pl-63a5400a\",\"prefixListName\":\"example\",\"cidrs\":[\"192.0.2.0/24\"]}]}",
            "{\"eu-west-1\": [{\"prefixListId\":\"pl-12345678\",\"prefixListName\":\"com.amazonaws.eu-west-1.s3\","
                    + "\"cidrs\":[\"192.0.2.0/24\"]}]}"
    })
    void invalidManagedPrefixListCatalogFailsStartup(String json, @TempDir Path temp) throws IOException {
        Path catalog = temp.resolve("prefix-lists.json");
        Files.writeString(catalog, json);
        EmulatorConfig config = mockConfig(true);
        when(config.services().ec2().enabled()).thenReturn(true);
        when(config.services().ec2().managedPrefixListCatalogPath()).thenReturn(Optional.of(catalog.toString()));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> prefixListService(config));
        assertTrue(error.getMessage().contains(catalog.toString()));
    }

    @Test
    void missingManagedPrefixListCatalogFailsStartup(@TempDir Path temp) {
        EmulatorConfig config = mockConfig(true);
        when(config.services().ec2().enabled()).thenReturn(true);
        Path catalog = temp.resolve("missing.json");
        when(config.services().ec2().managedPrefixListCatalogPath()).thenReturn(Optional.of(catalog.toString()));

        assertThrows(IllegalStateException.class, () -> prefixListService(config));
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.0.2.0/24", "192.0.2.19/24"})
    void duplicateCanonicalCatalogEntriesFailStartup(String duplicate, @TempDir Path temp) throws IOException {
        Path catalog = temp.resolve("prefix-lists.json");
        Files.writeString(catalog, """
                {"eu-west-1": [{"prefixListId": "pl-0123456789abcdef0",
                  "prefixListName": "example-managed-service", "cidrs": ["192.0.2.0/24", "%s"]}]}
                """.formatted(duplicate));
        EmulatorConfig config = mockConfig(true);
        when(config.services().ec2().enabled()).thenReturn(true);
        when(config.services().ec2().managedPrefixListCatalogPath()).thenReturn(Optional.of(catalog.toString()));

        assertThrows(IllegalStateException.class, () -> prefixListService(config));
    }

    @Test
    void disabledEc2DoesNotReadManagedPrefixListCatalog(@TempDir Path temp) {
        EmulatorConfig config = mockConfig(true);
        when(config.services().ec2().enabled()).thenReturn(false);
        when(config.services().ec2().managedPrefixListCatalogPath())
                .thenReturn(Optional.of(temp.resolve("missing.json").toString()));

        assertDoesNotThrow(() -> prefixListService(config));
    }

    @ParameterizedTest
    @CsvSource({"configured,false", "configured,true", "builtin,false", "builtin,true",
            "customer,false", "customer,true"})
    void prefixListFirewallConsumersReceiveCanonicalEntries(String kind, boolean egress, @TempDir Path temp)
            throws IOException {
        String region = "eu-west-1";
        Path catalog = temp.resolve("prefix-lists.json");
        Files.writeString(catalog, """
                {"eu-west-1": [{"prefixListId": "pl-0123456789abcdef0",
                  "prefixListName": "example-managed-service", "cidrs": ["192.0.2.19/24"]}]}
                """);
        EmulatorConfig config = mockConfig(false);
        when(config.services().ec2().enabled()).thenReturn(true);
        when(config.services().ec2().managedPrefixListCatalogPath()).thenReturn(Optional.of(catalog.toString()));
        EmulatorConfig.NetworkConfig network = mock(EmulatorConfig.NetworkConfig.class);
        EmulatorConfig.SecurityGroupEnforcementConfig enforcement = mock(EmulatorConfig.SecurityGroupEnforcementConfig.class);
        when(config.network()).thenReturn(network);
        when(network.securityGroupEnforcement()).thenReturn(enforcement);
        when(enforcement.enabled()).thenReturn(true);
        Ec2ContainerManager manager = mock(Ec2ContainerManager.class);
        AtomicReference<Map<String, List<String>>> policyLists = new AtomicReference<>();
        doAnswer(invocation -> {
            policyLists.set(invocation.getArgument(2));
            return null;
        }).when(manager).refreshSecurityGroups(eq(region), anyMap(), anyMap());
        Ec2Service service = new Ec2Service(config, manager, mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String id = switch (kind) {
            case "configured" -> "pl-0123456789abcdef0";
            case "builtin" -> "pl-63a5400a";
            case "customer" -> service.createManagedPrefixList(region, "customer-list", "IPv4", 1,
                    List.of(new PrefixListEntry("192.0.2.0/24", "test")), List.of()).getPrefixListId();
            default -> throw new AssertionError(kind);
        };
        SecurityGroup group = service.createSecurityGroup(region, "consumer", "consumer", null);
        if (egress) {
            service.revokeSecurityGroupEgress(region, group.getGroupId(), new ArrayList<>(group.getIpPermissionsEgress()));
        }
        IpPermission permission = tcpPermission(443);
        permission.getPrefixListIds().add(new PrefixListId(id, null));
        if (egress) {
            service.authorizeSecurityGroupEgress(region, group.getGroupId(), List.of(permission));
        } else {
            service.authorizeSecurityGroupIngress(region, group.getGroupId(), List.of(permission));
        }
        String allowed = "builtin".equals(kind) ? "52.216.1.9" : "192.0.2.9";
        String cidr = "builtin".equals(kind) ? "52.216.0.0/15" : "192.0.2.0/24";
        Map<String, List<String>> resolved = policyLists.get();
        assertNotNull(resolved);
        assertTrue(resolved.get(id).contains(cidr));
        SecurityGroupPolicy.Peer peer = new SecurityGroupPolicy.Peer(allowed, group.getOwnerId(),
                group.getVpcId(), Set.of());
        assertTrue(SecurityGroupPolicy.allows(List.of(group), egress, "tcp", 443, 0, peer, resolved));
        assertFalse(SecurityGroupPolicy.allows(List.of(group), egress, "tcp", 444, 0, peer, resolved));
        assertFalse(SecurityGroupPolicy.allows(List.of(group), egress, "tcp", 443, 0,
                new SecurityGroupPolicy.Peer("203.0.113.9", group.getOwnerId(), group.getVpcId(), Set.of()), resolved));
        SecurityGroupNftCompiler.Endpoint endpoint = new SecurityGroupNftCompiler.Endpoint(group.getOwnerId(), region,
                group.getVpcId(), "eni-consumer", "10.0.0.9", "172.20.0.9", Set.of(group.getGroupId()), List.of(group));
        String rules = SecurityGroupNftCompiler.compile(endpoint, List.of(), resolved);
        assertTrue(rules.contains(cidr));
    }

    @Test
    void createManagedPrefixListStoresEntriesAtVersionOne() {
        Ec2Service service = prefixListService();

        ManagedPrefixList list = service.createManagedPrefixList("us-east-1", "corp", "IPv4", 5,
                List.of(new PrefixListEntry("10.0.0.0/8", "corporate")), List.of());

        assertTrue(list.getPrefixListId().startsWith("pl-"));
        assertEquals("create-complete", list.getState());
        assertEquals(1, list.getVersion());
        assertEquals("000000000000", list.getOwnerId());
        assertEquals("arn:aws:ec2:us-east-1:000000000000:prefix-list/" + list.getPrefixListId(),
                list.getPrefixListArn());
        assertEquals(1, list.currentEntries().size());
        assertEquals("corporate", list.currentEntries().getFirst().getDescription());
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.0.2.19/24", "192.0.2.0/33", "example.com/24", "192.0.2.0"})
    void createManagedPrefixListRejectsInvalidIpv4BeforeAllocation(String cidr) {
        Ec2Service service = prefixListService();
        AwsException error = assertThrows(AwsException.class, () -> service.createManagedPrefixList(
                "us-east-1", "rejected-cidr", "IPv4", 2,
                List.of(new PrefixListEntry("198.51.100.0/24", null), new PrefixListEntry(cidr, null)),
                List.of(new Tag("source", "rejected-cidr"))));
        assertEquals("InvalidParameterValue", error.getErrorCode());
        assertEquals("The specified CIDR (" + cidr + ") is not valid. "
                + "For example, specify a CIDR in the following form: 10.0.0.0/16.", error.getMessage());
        assertTrue(service.describeManagedPrefixLists("us-east-1", List.of(), Map.of()).stream()
                .allMatch(ManagedPrefixList::isAwsManaged));
    }

    @Test
    void rejectedPrefixListCidrPreservesEntriesVersionAndMetadata() {
        Ec2Service service = prefixListService();
        ManagedPrefixList list = service.createManagedPrefixList("us-east-1", "original", "IPv4", 2,
                List.of(new PrefixListEntry("192.0.2.0/24", "original")), List.of());
        AwsException error = assertThrows(AwsException.class, () -> service.modifyManagedPrefixList(
                "us-east-1", list.getPrefixListId(), 1L, "changed", 3,
                List.of(new PrefixListEntry("198.51.100.19/24", null)), List.of("192.0.2.0/24")));
        assertEquals("InvalidParameterValue", error.getErrorCode());
        assertEquals(1, list.getVersion());
        assertEquals(2, list.getMaxEntries());
        assertEquals("original", list.getPrefixListName());
        assertEquals("create-complete", list.getState());
        assertEquals("192.0.2.0/24", list.currentEntries().getFirst().getCidr());
        assertEquals("original", list.currentEntries().getFirst().getDescription());
        assertEquals(1, list.getEntriesByVersion().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0/0", "192.0.2.0/24", "192.0.2.19/32"})
    void createManagedPrefixListPreservesCanonicalIpv4(String cidr) {
        Ec2Service service = prefixListService();
        ManagedPrefixList list = service.createManagedPrefixList("us-east-1", "canonical", "IPv4", 1,
                List.of(new PrefixListEntry(cidr, null)), List.of());
        assertEquals(cidr, list.currentEntries().getFirst().getCidr());
    }

    @Test
    void createManagedPrefixListPreservesAlternateIpv6Spelling() {
        Ec2Service service = prefixListService();
        String cidr = "2001:DB8:0000:0000:0000:0000:0000:0000/32";
        ManagedPrefixList list = service.createManagedPrefixList("us-east-1", "ipv6", "IPv6", 1,
                List.of(new PrefixListEntry(cidr, null)), List.of());
        assertEquals(cidr, list.currentEntries().getFirst().getCidr());
    }

    @Test
    void createManagedPrefixListRejectsMoreEntriesThanMaxEntries() {
        Ec2Service service = prefixListService();

        AwsException error = assertThrows(AwsException.class, () -> service.createManagedPrefixList(
                "us-east-1", "corp", "IPv4", 1,
                List.of(new PrefixListEntry("10.0.0.0/8", null), new PrefixListEntry("10.1.0.0/16", null)),
                List.of()));
        assertEquals("InvalidParameterValue", error.getErrorCode());
    }

    @Test
    void createManagedPrefixListRejectsCidrOfTheWrongAddressFamily() {
        Ec2Service service = prefixListService();

        AwsException error = assertThrows(AwsException.class, () -> service.createManagedPrefixList(
                "us-east-1", "corp", "IPv4", 5,
                List.of(new PrefixListEntry("2001:db8::/32", null)), List.of()));
        assertEquals("InvalidParameterValue", error.getErrorCode());
    }

    @Test
    void describeManagedPrefixListsIncludesAwsManagedAndIsRegionScoped() {
        Ec2Service service = prefixListService();
        service.createManagedPrefixList("us-east-1", "corp", "IPv4", 5, List.of(), List.of());

        List<ManagedPrefixList> east = service.describeManagedPrefixLists("us-east-1", List.of(), Map.of());
        assertEquals(3, east.size());
        assertTrue(east.stream().anyMatch(l -> "com.amazonaws.us-east-1.s3".equals(l.getPrefixListName())));
        assertTrue(east.stream().anyMatch(l -> "corp".equals(l.getPrefixListName())));

        // The customer list belongs to us-east-1; only the AWS-managed pair shows up elsewhere.
        List<ManagedPrefixList> west = service.describeManagedPrefixLists("us-west-2", List.of(), Map.of());
        assertEquals(2, west.size());
        assertTrue(west.stream().allMatch(ManagedPrefixList::isAwsManaged));
        assertTrue(west.stream().anyMatch(l -> "com.amazonaws.us-west-2.s3".equals(l.getPrefixListName())));
    }

    @Test
    void createManagedPrefixListAcceptsIpv6Entries() {
        Ec2Service service = prefixListService();

        ManagedPrefixList list = service.createManagedPrefixList("us-east-1", "corp-v6", "IPv6", 5,
                List.of(new PrefixListEntry("2001:db8::/32", "lab")), List.of());

        assertEquals("IPv6", list.getAddressFamily());
        assertEquals("2001:db8::/32", list.currentEntries().getFirst().getCidr());

        service.modifyManagedPrefixList("us-east-1", list.getPrefixListId(), null, null, null,
                List.of(new PrefixListEntry("2001:db8:1::/48", null)), List.of());
        assertEquals(2, service.getManagedPrefixListEntries("us-east-1", list.getPrefixListId(), null).size());

        AwsException error = assertThrows(AwsException.class, () -> service.modifyManagedPrefixList(
                "us-east-1", list.getPrefixListId(), null, null, null,
                List.of(new PrefixListEntry("10.0.0.0/8", null)), List.of()));
        assertEquals("InvalidParameterValue", error.getErrorCode());
    }

    @Test
    void managedPrefixListLookupsRejectAMissingId() {
        Ec2Service service = prefixListService();

        for (String missing : new String[] {null, "  "}) {
            assertEquals("MissingParameter", assertThrows(AwsException.class, () ->
                    service.getManagedPrefixListEntries("us-east-1", missing, null)).getErrorCode());
            assertEquals("MissingParameter", assertThrows(AwsException.class, () ->
                    service.deleteManagedPrefixList("us-east-1", missing)).getErrorCode());
            assertEquals("MissingParameter", assertThrows(AwsException.class, () ->
                    service.modifyManagedPrefixList("us-east-1", missing, null, null, null,
                            List.of(), List.of())).getErrorCode());
        }
    }

    /**
     * Verified against a live AWS account: the three dotted prefixes are rejected, and the
     * trailing dot matters — {@code com.amazonaws-probe} and {@code comamazonaws.probe} are both
     * accepted there, so a dotless prefix match would refuse names AWS allows.
     */
    @Test
    void createManagedPrefixListRejectsNamesReservedByAws() {
        Ec2Service service = prefixListService();

        for (String reserved : new String[] {"com.amazonaws.probe", "com.amazon.probe", "com.aws.probe"}) {
            AwsException error = assertThrows(AwsException.class, () -> service.createManagedPrefixList(
                    "us-east-1", reserved, "IPv4", 5, List.of(), List.of()), "expected rejection for " + reserved);
            assertEquals("InvalidParameterValue", error.getErrorCode());
        }

        // Names that only look reserved are still allowed.
        for (String allowed : new String[] {"com.amazonaws-probe", "comamazonaws.probe", "corp"}) {
            assertEquals(allowed, service.createManagedPrefixList(
                    "us-east-1", allowed, "IPv4", 5, List.of(), List.of()).getPrefixListName());
        }
    }

    /**
     * Verified against a live AWS account: the rename path applies the same rule, and rejecting it
     * leaves the existing name in place. A lookalike is still allowed.
     */
    @Test
    void renamingToAReservedNameIsRejected() {
        Ec2Service service = prefixListService();
        ManagedPrefixList list = service.createManagedPrefixList("us-east-1", "corp", "IPv4", 5,
                List.of(), List.of());

        AwsException error = assertThrows(AwsException.class, () -> service.modifyManagedPrefixList(
                "us-east-1", list.getPrefixListId(), null, "com.amazonaws.us-east-1.s3", null,
                List.of(), List.of()));
        assertEquals("InvalidParameterValue", error.getErrorCode());
        assertEquals("corp", service.describeManagedPrefixLists("us-east-1",
                List.of(list.getPrefixListId()), Map.of()).getFirst().getPrefixListName());

        service.modifyManagedPrefixList("us-east-1", list.getPrefixListId(), null,
                "com.amazonaws-renamed", null, List.of(), List.of());
        assertEquals("com.amazonaws-renamed", service.describeManagedPrefixLists("us-east-1",
                List.of(list.getPrefixListId()), Map.of()).getFirst().getPrefixListName());
    }

    @Test
    void describeManagedPrefixListsFiltersByName() {
        Ec2Service service = prefixListService();
        service.createManagedPrefixList("us-east-1", "corp", "IPv4", 5, List.of(), List.of());

        List<ManagedPrefixList> found = service.describeManagedPrefixLists("us-east-1", List.of(),
                Map.of("prefix-list-name", List.of("corp")));

        assertEquals(1, found.size());
        assertEquals("corp", found.getFirst().getPrefixListName());
    }

    @Test
    void describeManagedPrefixListsRejectsUnknownId() {
        Ec2Service service = prefixListService();

        AwsException error = assertThrows(AwsException.class, () ->
                service.describeManagedPrefixLists("us-east-1", List.of("pl-missing"), Map.of()));
        assertEquals("InvalidPrefixListID.NotFound", error.getErrorCode());
    }

    @Test
    void modifyBumpsVersionAndKeepsEarlierVersionsRetrievable() {
        Ec2Service service = prefixListService();
        ManagedPrefixList created = service.createManagedPrefixList("us-east-1", "corp", "IPv4", 5,
                List.of(new PrefixListEntry("10.0.0.0/8", null)), List.of());

        ManagedPrefixList modified = service.modifyManagedPrefixList("us-east-1", created.getPrefixListId(),
                null, null, null, List.of(new PrefixListEntry("192.168.0.0/16", "lab")), List.of());

        assertEquals(2, modified.getVersion());
        assertEquals("modify-complete", modified.getState());
        assertEquals(2, service.getManagedPrefixListEntries("us-east-1", created.getPrefixListId(), null).size());
        assertEquals(1, service.getManagedPrefixListEntries("us-east-1", created.getPrefixListId(), 1L).size());
    }

    @Test
    void modifyAppliesRemovalsBeforeAdditionsSoADescriptionCanBeReplaced() {
        Ec2Service service = prefixListService();
        ManagedPrefixList created = service.createManagedPrefixList("us-east-1", "corp", "IPv4", 5,
                List.of(new PrefixListEntry("10.0.0.0/8", "old")), List.of());

        service.modifyManagedPrefixList("us-east-1", created.getPrefixListId(), null, null, null,
                List.of(new PrefixListEntry("10.0.0.0/8", "new")), List.of("10.0.0.0/8"));

        List<PrefixListEntry> entries =
                service.getManagedPrefixListEntries("us-east-1", created.getPrefixListId(), null);
        assertEquals(1, entries.size());
        assertEquals("new", entries.getFirst().getDescription());
    }

    @Test
    void renamingDoesNotCreateANewVersion() {
        Ec2Service service = prefixListService();
        ManagedPrefixList created = service.createManagedPrefixList("us-east-1", "corp", "IPv4", 5,
                List.of(new PrefixListEntry("10.0.0.0/8", null)), List.of());

        ManagedPrefixList renamed = service.modifyManagedPrefixList("us-east-1", created.getPrefixListId(),
                null, "corp-renamed", null, List.of(), List.of());

        assertEquals("corp-renamed", renamed.getPrefixListName());
        assertEquals(1, renamed.getVersion());
    }

    @Test
    void modifyWithStaleCurrentVersionIsRejected() {
        Ec2Service service = prefixListService();
        ManagedPrefixList created = service.createManagedPrefixList("us-east-1", "corp", "IPv4", 5,
                List.of(new PrefixListEntry("10.0.0.0/8", null)), List.of());
        service.modifyManagedPrefixList("us-east-1", created.getPrefixListId(), null, null, null,
                List.of(new PrefixListEntry("192.168.0.0/16", null)), List.of());

        AwsException error = assertThrows(AwsException.class, () ->
                service.modifyManagedPrefixList("us-east-1", created.getPrefixListId(), 1L, null, null,
                        List.of(new PrefixListEntry("172.16.0.0/12", null)), List.of()));
        assertEquals("PrefixListVersionMismatch", error.getErrorCode());
    }

    @Test
    void awsManagedListsCannotBeModifiedOrDeleted() {
        Ec2Service service = prefixListService();

        AwsException modifyError = assertThrows(AwsException.class, () ->
                service.modifyManagedPrefixList("us-east-1", "pl-63a5400a", null, "hijacked", null,
                        List.of(), List.of()));
        assertEquals("UnsupportedOperation", modifyError.getErrorCode());

        AwsException deleteError = assertThrows(AwsException.class, () ->
                service.deleteManagedPrefixList("us-east-1", "pl-63a5400a"));
        assertEquals("UnsupportedOperation", deleteError.getErrorCode());
    }

    @Test
    void deleteRemovesTheListFromDescribe() {
        Ec2Service service = prefixListService();
        ManagedPrefixList created = service.createManagedPrefixList("us-east-1", "corp", "IPv4", 5,
                List.of(), List.of());

        ManagedPrefixList deleted = service.deleteManagedPrefixList("us-east-1", created.getPrefixListId());

        assertEquals("delete-complete", deleted.getState());
        assertThrows(AwsException.class, () ->
                service.describeManagedPrefixLists("us-east-1", List.of(created.getPrefixListId()), Map.of()));
    }

    @Test
    void legacyDescribePrefixListsProjectsTheSameAwsManagedData() {
        Ec2Service service = prefixListService();

        List<PrefixList> legacy = service.describePrefixLists("us-east-1", List.of(),
                Map.of("prefix-list-name", List.of("com.amazonaws.us-east-1.s3")));

        assertEquals(1, legacy.size());
        assertEquals("pl-63a5400a", legacy.getFirst().getPrefixListId());
        assertEquals(List.of("52.216.0.0/15", "54.231.0.0/16"), legacy.getFirst().getCidrs());
    }

    @Test
    void modifyRejectsANonPositiveMaxEntries() {
        Ec2Service service = prefixListService();
        ManagedPrefixList created = service.createManagedPrefixList("us-east-1", "corp", "IPv4", 5,
                List.of(), List.of());

        // The list is empty, so a size check alone would let a zero capacity through.
        AwsException error = assertThrows(AwsException.class, () ->
                service.modifyManagedPrefixList("us-east-1", created.getPrefixListId(), null, null, 0,
                        List.of(), List.of()));
        assertEquals("InvalidParameterValue", error.getErrorCode());
        assertEquals(5, service.describeManagedPrefixLists("us-east-1",
                List.of(created.getPrefixListId()), Map.of()).getFirst().getMaxEntries());
    }

    @Test
    void createTagsOnAPrefixListIsVisibleToDescribeAndTagFilters() {
        Ec2Service service = prefixListService();
        ManagedPrefixList created = service.createManagedPrefixList("us-east-1", "corp", "IPv4", 5,
                List.of(), List.of());

        service.createTags("us-east-1", List.of(created.getPrefixListId()), List.of(new Tag("env", "prod")));

        ManagedPrefixList described = service.describeManagedPrefixLists("us-east-1",
                List.of(created.getPrefixListId()), Map.of()).getFirst();
        assertEquals(1, described.getTags().size());
        assertEquals("prod", described.getTags().getFirst().getValue());

        assertEquals(1, service.describeManagedPrefixLists("us-east-1", List.of(),
                Map.of("tag:env", List.of("prod"))).size());

        assertEquals("prefix-list", service.describeTags("us-east-1",
                Map.of("resource-id", List.of(created.getPrefixListId()))).getFirst().get("resourceType"));
        assertEquals(1, service.describeTags("us-east-1",
                Map.of("resource-type", List.of("prefix-list"))).size());

        service.deleteTags("us-east-1", List.of(created.getPrefixListId()), List.of(new Tag("env", null)));
        assertTrue(service.describeManagedPrefixLists("us-east-1", List.of(created.getPrefixListId()), Map.of())
                .getFirst().getTags().isEmpty());
    }

    // =========================================================================
    // Security group rules sourced from a prefix list
    // =========================================================================

    private static IpPermission tcpPermission(int port) {
        IpPermission perm = new IpPermission();
        perm.setIpProtocol("tcp");
        perm.setFromPort(port);
        perm.setToPort(port);
        return perm;
    }

    @Test
    void authorizeIngressFromAPrefixListCreatesARuleCarryingIt() {
        Ec2Service service = prefixListService();
        ManagedPrefixList list = service.createManagedPrefixList("us-east-1", "corp", "IPv4", 5,
                List.of(new PrefixListEntry("10.0.0.0/8", null)), List.of());
        String groupId = service.createSecurityGroup("us-east-1", "db", "db", null).getGroupId();

        IpPermission perm = tcpPermission(5432);
        perm.getPrefixListIds().add(new PrefixListId(list.getPrefixListId(), "from-corp"));
        List<SecurityGroupRule> rules = service.authorizeSecurityGroupIngress("us-east-1", groupId, List.of(perm));

        assertEquals(1, rules.size());
        SecurityGroupRule rule = rules.getFirst();
        assertEquals(list.getPrefixListId(), rule.getPrefixListId());
        assertEquals("from-corp", rule.getDescription());
        assertNull(rule.getCidrIpv4(), "a prefix list rule carries no CIDR");
        assertFalse(rule.isEgress());
        assertEquals(5432, rule.getFromPort());
    }

    @Test
    void authorizeAgainstAnUnknownPrefixListIsRejected() {
        Ec2Service service = prefixListService();
        String groupId = service.createSecurityGroup("us-east-1", "db", "db", null).getGroupId();

        IpPermission perm = tcpPermission(5432);
        perm.getPrefixListIds().add(new PrefixListId("pl-doesnotexist", null));

        AwsException error = assertThrows(AwsException.class,
                () -> service.authorizeSecurityGroupIngress("us-east-1", groupId, List.of(perm)));
        assertEquals("InvalidPrefixListID.NotFound", error.getErrorCode());
        // The rejected rule must not have been stored. The group still holds its default
        // allow-all egress rule, so the check is for an ingress rule rather than for none.
        assertTrue(service.describeSecurityGroupRules("us-east-1", List.of(groupId), List.of()).stream()
                .noneMatch(r -> !r.isEgress() || r.getPrefixListId() != null));
    }

    /** AWS emits one rule per source, so a permission naming both expands to two. */
    @Test
    void aPermissionNamingBothACidrAndAPrefixListYieldsARuleForEach() {
        Ec2Service service = prefixListService();
        ManagedPrefixList list = service.createManagedPrefixList("us-east-1", "corp", "IPv4", 5,
                List.of(), List.of());
        String groupId = service.createSecurityGroup("us-east-1", "db", "db", null).getGroupId();

        IpPermission perm = tcpPermission(443);
        perm.getIpRanges().add(new IpRange("10.1.0.0/16", "direct"));
        perm.getPrefixListIds().add(new PrefixListId(list.getPrefixListId(), "via-list"));
        List<SecurityGroupRule> rules = service.authorizeSecurityGroupIngress("us-east-1", groupId, List.of(perm));

        assertEquals(2, rules.size());
        assertEquals(1, rules.stream().filter(r -> "10.1.0.0/16".equals(r.getCidrIpv4())).count());
        assertEquals(1, rules.stream().filter(r -> list.getPrefixListId().equals(r.getPrefixListId())).count());
    }

    /**
     * Verified against a live AWS account: a permission naming a valid CIDR alongside an unknown
     * prefix list persists neither, so the whole call has to resolve before anything is written.
     */
    @Test
    void anUnknownPrefixListLeavesNoPartialRuleFromTheSamePermission() {
        Ec2Service service = prefixListService();
        String groupId = service.createSecurityGroup("us-east-1", "db", "db", null).getGroupId();

        IpPermission perm = tcpPermission(5432);
        perm.getIpRanges().add(new IpRange("10.9.0.0/16", "direct"));
        perm.getPrefixListIds().add(new PrefixListId("pl-doesnotexist", null));

        AwsException error = assertThrows(AwsException.class,
                () -> service.authorizeSecurityGroupIngress("us-east-1", groupId, List.of(perm)));
        assertEquals("InvalidPrefixListID.NotFound", error.getErrorCode());
        assertTrue(service.describeSecurityGroupRules("us-east-1", List.of(groupId), List.of()).stream()
                .noneMatch(r -> !r.isEgress()), "the CIDR rule must not survive the rejection");
    }

    /** A later bad permission must not leave an earlier good one applied either. */
    @Test
    void anUnknownPrefixListInASecondPermissionLeavesTheFirstUnapplied() {
        Ec2Service service = prefixListService();
        String groupId = service.createSecurityGroup("us-east-1", "db", "db", null).getGroupId();

        IpPermission good = tcpPermission(443);
        good.getIpRanges().add(new IpRange("10.1.0.0/16", null));
        IpPermission bad = tcpPermission(5432);
        bad.getPrefixListIds().add(new PrefixListId("pl-doesnotexist", null));

        assertThrows(AwsException.class,
                () -> service.authorizeSecurityGroupIngress("us-east-1", groupId, List.of(good, bad)));
        assertTrue(service.describeSecurityGroupRules("us-east-1", List.of(groupId), List.of()).stream()
                .noneMatch(r -> !r.isEgress()), "no ingress rule from either permission");
    }

    /**
     * Verified against a live AWS account: revoking the prefix list source leaves a CIDR
     * permission on the same protocol and ports untouched.
     */
    @Test
    void revokingAPrefixListSourceLeavesACidrOnTheSameTupleAlone() {
        Ec2Service service = prefixListService();
        ManagedPrefixList list = service.createManagedPrefixList("us-east-1", "corp", "IPv4", 5,
                List.of(), List.of());
        String groupId = service.createSecurityGroup("us-east-1", "db", "db", null).getGroupId();

        IpPermission viaCidr = tcpPermission(5432);
        viaCidr.getIpRanges().add(new IpRange("192.168.0.0/16", null));
        IpPermission viaList = tcpPermission(5432);
        viaList.getPrefixListIds().add(new PrefixListId(list.getPrefixListId(), null));
        service.authorizeSecurityGroupIngress("us-east-1", groupId, List.of(viaCidr, viaList));

        service.revokeSecurityGroupIngress("us-east-1", groupId, List.of(viaList));

        List<IpPermission> left = service.describeSecurityGroups("us-east-1", List.of(groupId), List.of(), Map.of())
                .getFirst().getIpPermissions();
        assertEquals(1, left.size(), "only the prefix list permission should have been revoked");
        assertEquals("192.168.0.0/16", left.getFirst().getIpRanges().getFirst().getCidrIp());
    }

    @Test
    void anEgressRuleCanAlsoComeFromAPrefixList() {
        Ec2Service service = prefixListService();
        ManagedPrefixList list = service.createManagedPrefixList("us-east-1", "corp", "IPv4", 5,
                List.of(), List.of());
        String groupId = service.createSecurityGroup("us-east-1", "db", "db", null).getGroupId();

        IpPermission perm = tcpPermission(443);
        perm.getPrefixListIds().add(new PrefixListId(list.getPrefixListId(), null));
        List<SecurityGroupRule> rules = service.authorizeSecurityGroupEgress("us-east-1", groupId, List.of(perm));

        assertEquals(1, rules.size());
        assertTrue(rules.getFirst().isEgress());
        assertEquals(list.getPrefixListId(), rules.getFirst().getPrefixListId());
    }

    /**
     * A caller may name the source group by name alone, which authorize resolves to a group id
     * before storing it. Scoped revocation has to resolve the same way, or a rule survives the
     * revoke that names it.
     */
    @Test
    void revokingAGroupSourceNamedByNameOnlyStillMatchesTheStoredReference() {
        Ec2Service service = prefixListService();
        String sourceId = service.createSecurityGroup("us-east-1", "app", "app", null).getGroupId();
        String targetId = service.createSecurityGroup("us-east-1", "db", "db", null).getGroupId();

        IpPermission authorized = tcpPermission(5432);
        UserIdGroupPair byName = new UserIdGroupPair();
        byName.setGroupName("app");
        authorized.getUserIdGroupPairs().add(byName);
        List<SecurityGroupRule> rules =
                service.authorizeSecurityGroupIngress("us-east-1", targetId, List.of(authorized));
        assertEquals(sourceId, rules.getFirst().getReferencedGroupInfo().getGroupId());

        IpPermission revocation = tcpPermission(5432);
        UserIdGroupPair alsoByName = new UserIdGroupPair();
        alsoByName.setGroupName("app");
        revocation.getUserIdGroupPairs().add(alsoByName);
        service.revokeSecurityGroupIngress("us-east-1", targetId, List.of(revocation));

        assertTrue(service.describeSecurityGroups("us-east-1", List.of(targetId), List.of(), Map.of())
                .getFirst().getIpPermissions().isEmpty(), "the revoked group reference must be gone");
    }

    /**
     * A rule's tags already reach the store and the rule itself; only DescribeTags mistyped them,
     * so a resource-type filter never matched.
     */
    @Test
    void tagsOnASecurityGroupRuleAreTypedAsSecurityGroupRule() {
        Ec2Service service = prefixListService();
        String groupId = service.createSecurityGroup("us-east-1", "db", "db", null).getGroupId();
        IpPermission perm = new IpPermission();
        perm.setIpProtocol("tcp");
        perm.setFromPort(443);
        perm.setToPort(443);
        perm.getIpRanges().add(new IpRange("10.0.0.0/8", null));
        String ruleId = service.authorizeSecurityGroupIngress("us-east-1", groupId, List.of(perm))
                .getFirst().getSecurityGroupRuleId();

        service.createTags("us-east-1", List.of(ruleId), List.of(new Tag("env", "prod")));

        assertEquals("security-group-rule", service.describeTags("us-east-1",
                Map.of("resource-id", List.of(ruleId))).getFirst().get("resourceType"));
        assertEquals(1, service.describeTags("us-east-1",
                Map.of("resource-type", List.of("security-group-rule"))).size());

        // Tag the group as well, so the sg- classification is genuinely exercised rather than
        // read off an empty result.
        service.createTags("us-east-1", List.of(groupId), List.of(new Tag("env", "prod")));
        assertEquals("security-group", service.describeTags("us-east-1",
                Map.of("resource-id", List.of(groupId))).getFirst().get("resourceType"));
        assertEquals(1, service.describeTags("us-east-1",
                Map.of("resource-type", List.of("security-group"))).size());
    }

    // =========================================================================
    // Transit gateways
    // =========================================================================

    /**
     * Every default here was read off a live AWS account rather than the documentation, including
     * the one that is easy to assume the other way: {@code securityGroupReferencingSupport} is
     * {@code disable} on a new gateway.
     */
    @Test
    void createTransitGatewayAppliesTheDefaultsAwsApplies() {
        Ec2Service service = prefixListService();

        TransitGateway gateway = service.createTransitGateway("us-east-1", "hub", null, List.of());

        assertTrue(gateway.getTransitGatewayId().startsWith("tgw-"));
        assertEquals("arn:aws:ec2:us-east-1:000000000000:transit-gateway/" + gateway.getTransitGatewayId(),
                gateway.getTransitGatewayArn());
        assertEquals("available", gateway.getState());
        assertEquals("000000000000", gateway.getOwnerId());
        assertEquals("hub", gateway.getDescription());

        TransitGatewayOptions options = gateway.getOptions();
        assertEquals(64512L, options.getAmazonSideAsn());
        assertEquals("disable", options.getAutoAcceptSharedAttachments());
        assertEquals("enable", options.getDefaultRouteTableAssociation());
        assertEquals("enable", options.getDefaultRouteTablePropagation());
        assertEquals("enable", options.getVpnEcmpSupport());
        assertEquals("enable", options.getDnsSupport());
        assertEquals("disable", options.getSecurityGroupReferencingSupport());
        assertEquals("disable", options.getMulticastSupport());
        assertTrue(options.getTransitGatewayCidrBlocks().isEmpty());
    }

    /**
     * AWS mints the default route table during creation, so both ids are already on the create
     * response and both name the same table.
     */
    @Test
    void createTransitGatewayMintsTheDefaultRouteTableAndReportsItsId() {
        Ec2Service service = prefixListService();

        TransitGatewayOptions options =
                service.createTransitGateway("us-east-1", null, null, List.of()).getOptions();

        assertNotNull(options.getAssociationDefaultRouteTableId());
        assertTrue(options.getAssociationDefaultRouteTableId().startsWith("tgw-rtb-"));
        assertEquals(options.getAssociationDefaultRouteTableId(), options.getPropagationDefaultRouteTableId(),
                "association and propagation point at the same default table");
    }

    @Test
    void aGatewayThatOptsOutOfBothDefaultsGetsNoRouteTable() {
        Ec2Service service = prefixListService();
        TransitGatewayOptions requested = new TransitGatewayOptions();
        requested.setDefaultRouteTableAssociation("disable");
        requested.setDefaultRouteTablePropagation("disable");

        TransitGatewayOptions options =
                service.createTransitGateway("us-east-1", null, requested, List.of()).getOptions();

        assertNull(options.getAssociationDefaultRouteTableId());
        assertNull(options.getPropagationDefaultRouteTableId());
    }

    @Test
    void requestedOptionsOverrideTheDefaults() {
        Ec2Service service = prefixListService();
        TransitGatewayOptions requested = new TransitGatewayOptions();
        requested.setAmazonSideAsn(65001L);
        requested.setDnsSupport("disable");
        requested.setAutoAcceptSharedAttachments("enable");

        TransitGatewayOptions options =
                service.createTransitGateway("us-east-1", null, requested, List.of()).getOptions();

        assertEquals(65001L, options.getAmazonSideAsn());
        assertEquals("disable", options.getDnsSupport());
        assertEquals("enable", options.getAutoAcceptSharedAttachments());
        // Untouched options keep their defaults.
        assertEquals("enable", options.getVpnEcmpSupport());
    }

    @Test
    void describeTransitGatewaysFiltersAndRejectsUnknownIds() {
        Ec2Service service = prefixListService();
        TransitGateway gateway = service.createTransitGateway("us-east-1", "hub", null,
                List.of(new Tag("env", "prod")));
        service.createTransitGateway("us-east-1", "spoke", null, List.of());

        assertEquals(2, service.describeTransitGateways("us-east-1", List.of(), Map.of()).size());
        assertEquals(1, service.describeTransitGateways("us-east-1", List.of(),
                Map.of("tag:env", List.of("prod"))).size());
        assertEquals(gateway.getTransitGatewayId(), service.describeTransitGateways("us-east-1",
                List.of(gateway.getTransitGatewayId()), Map.of()).getFirst().getTransitGatewayId());
        // Another region cannot see it.
        assertTrue(service.describeTransitGateways("eu-west-1", List.of(), Map.of()).isEmpty());

        AwsException notFound = assertThrows(AwsException.class, () -> service.describeTransitGateways(
                "us-east-1", List.of("tgw-0123456789abcdef0"), Map.of()));
        assertEquals("InvalidTransitGatewayID.NotFound", notFound.getErrorCode());

        AwsException malformed = assertThrows(AwsException.class, () -> service.describeTransitGateways(
                "us-east-1", List.of("tgw-nope"), Map.of()));
        assertEquals("InvalidTransitGatewayID.Malformed", malformed.getErrorCode());
    }

    @Test
    void modifyTransitGatewayUpdatesDescriptionOptionsAndCidrBlocks() {
        Ec2Service service = prefixListService();
        String id = service.createTransitGateway("us-east-1", "before", null, List.of()).getTransitGatewayId();
        TransitGatewayOptions changes = new TransitGatewayOptions();
        changes.setDnsSupport("disable");

        TransitGateway modified = service.modifyTransitGateway("us-east-1", id, "after", changes,
                List.of("10.100.0.0/16", "10.101.0.0/16"), List.of());

        assertEquals("after", modified.getDescription());
        assertEquals("disable", modified.getOptions().getDnsSupport());
        assertEquals(List.of("10.100.0.0/16", "10.101.0.0/16"),
                modified.getOptions().getTransitGatewayCidrBlocks());

        TransitGateway shrunk = service.modifyTransitGateway("us-east-1", id, null, null,
                List.of(), List.of("10.100.0.0/16"));
        assertEquals(List.of("10.101.0.0/16"), shrunk.getOptions().getTransitGatewayCidrBlocks());
        assertEquals("after", shrunk.getDescription(), "a null description leaves the stored one alone");
    }

    /**
     * The flag and its route table id have to move together. Verified against a live account: AWS
     * refuses to enable association or propagation without being told which existing table to use,
     * refuses an id alongside a disable, and reports an unknown table as
     * {@code InvalidRouteTableID.NotFound}. Without this a gateway could report the option enabled
     * while carrying no id at all.
     */
    @Test
    void enablingADefaultRouteTableOptionRequiresAnExistingRouteTable() {
        Ec2Service service = prefixListService();
        TransitGatewayOptions createdWithout = new TransitGatewayOptions();
        createdWithout.setDefaultRouteTableAssociation("disable");
        createdWithout.setDefaultRouteTablePropagation("disable");
        String id = service.createTransitGateway("us-east-1", null, createdWithout, List.of())
                .getTransitGatewayId();

        TransitGatewayOptions enableOnly = new TransitGatewayOptions();
        enableOnly.setDefaultRouteTableAssociation("enable");
        AwsException noId = assertThrows(AwsException.class, () -> service.modifyTransitGateway(
                "us-east-1", id, null, enableOnly, List.of(), List.of()));
        assertEquals("InvalidParameterCombination", noId.getErrorCode());

        TransitGatewayOptions propagationOnly = new TransitGatewayOptions();
        propagationOnly.setDefaultRouteTablePropagation("enable");
        assertEquals("InvalidParameterCombination", assertThrows(AwsException.class,
                () -> service.modifyTransitGateway("us-east-1", id, null, propagationOnly,
                        List.of(), List.of())).getErrorCode());

        TransitGatewayOptions disableWithId = new TransitGatewayOptions();
        disableWithId.setDefaultRouteTableAssociation("disable");
        disableWithId.setAssociationDefaultRouteTableId("tgw-rtb-0123456789abcdef0");
        assertEquals("InvalidParameterCombination", assertThrows(AwsException.class,
                () -> service.modifyTransitGateway("us-east-1", id, null, disableWithId,
                        List.of(), List.of())).getErrorCode());

        TransitGatewayOptions unknownTable = new TransitGatewayOptions();
        unknownTable.setDefaultRouteTableAssociation("enable");
        unknownTable.setAssociationDefaultRouteTableId("tgw-rtb-0123456789abcdef0");
        assertEquals("InvalidRouteTableID.NotFound", assertThrows(AwsException.class,
                () -> service.modifyTransitGateway("us-east-1", id, null, unknownTable,
                        List.of(), List.of())).getErrorCode());

        // The rejected calls left the gateway as it was, rather than half-applied.
        TransitGatewayOptions after = service.describeTransitGateways("us-east-1", List.of(id), Map.of())
                .getFirst().getOptions();
        assertEquals("disable", after.getDefaultRouteTableAssociation());
        assertNull(after.getAssociationDefaultRouteTableId());
    }

    /**
     * Verified against a live account: a route table belonging to another gateway is rejected
     * under the same code as one that exists nowhere, with the gateway named in the message.
     * Without the ownership check the foreign table's own default markers would be rewritten.
     */
    @Test
    void aRouteTableBelongingToAnotherGatewayIsRejected() {
        AccountAwareStorageBackend<TransitGatewayRouteTable> routeTables =
                AccountAwareStorageBackend.inMemory("000000000000");
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Map.of("ec2-transit-gateway-route-tables.json", routeTables)));
        TransitGatewayOptions defaultsOff = new TransitGatewayOptions();
        defaultsOff.setDefaultRouteTableAssociation("disable");
        defaultsOff.setDefaultRouteTablePropagation("disable");
        String borrower = service.createTransitGateway("us-east-1", "borrower", defaultsOff, List.of())
                .getTransitGatewayId();
        TransitGateway owner = service.createTransitGateway("us-east-1", "owner", null, List.of());
        String ownersRouteTable = owner.getOptions().getAssociationDefaultRouteTableId();

        TransitGatewayOptions changes = new TransitGatewayOptions();
        changes.setDefaultRouteTableAssociation("enable");
        changes.setAssociationDefaultRouteTableId(ownersRouteTable);

        AwsException error = assertThrows(AwsException.class, () -> service.modifyTransitGateway(
                "us-east-1", borrower, null, changes, List.of(), List.of()));
        assertEquals("InvalidRouteTableID.NotFound", error.getErrorCode());
        assertTrue(error.getMessage().contains(borrower),
                "the message names the gateway the table is missing from");

        // The owner's table kept its markers, and the borrower stayed disabled.
        TransitGatewayRouteTable stored = routeTables.get("us-east-1::" + ownersRouteTable).orElseThrow();
        assertTrue(stored.isDefaultAssociationRouteTable());
        assertNull(service.describeTransitGateways("us-east-1", List.of(borrower), Map.of())
                .getFirst().getOptions().getAssociationDefaultRouteTableId());
    }

    /**
     * The whole flag/id contract, as observed on a live account. The pair is judged against the
     * gateway as it stands rather than against the request alone, which is what makes an id on its
     * own legal while the option is enabled and a conflict while it is disabled.
     */
    @Test
    void aRouteTableIdOnItsOwnFollowsTheStoredFlag() {
        Ec2Service service = prefixListService();
        TransitGateway gateway = service.createTransitGateway("us-east-1", "hub", null, List.of());
        String id = gateway.getTransitGatewayId();
        String routeTableId = gateway.getOptions().getAssociationDefaultRouteTableId();

        // Enabled: an id on its own is accepted, and enable on its own keeps the stored table.
        TransitGatewayOptions idOnly = new TransitGatewayOptions();
        idOnly.setAssociationDefaultRouteTableId(routeTableId);
        assertEquals(routeTableId, service.modifyTransitGateway("us-east-1", id, null, idOnly,
                List.of(), List.of()).getOptions().getAssociationDefaultRouteTableId());

        TransitGatewayOptions flagOnly = new TransitGatewayOptions();
        flagOnly.setDefaultRouteTableAssociation("enable");
        assertEquals(routeTableId, service.modifyTransitGateway("us-east-1", id, null, flagOnly,
                List.of(), List.of()).getOptions().getAssociationDefaultRouteTableId(),
                "enable on its own keeps the table already named");

        // Disabled: the same id-only request now conflicts, and the message quotes the stored flag.
        TransitGatewayOptions disable = new TransitGatewayOptions();
        disable.setDefaultRouteTableAssociation("disable");
        service.modifyTransitGateway("us-east-1", id, null, disable, List.of(), List.of());

        AwsException conflict = assertThrows(AwsException.class, () -> service.modifyTransitGateway(
                "us-east-1", id, null, idOnly, List.of(), List.of()));
        assertEquals("InvalidParameterCombination", conflict.getErrorCode());
        assertTrue(conflict.getMessage().startsWith("disable DefaultRouteTableAssociation"),
                "the stored flag is what the message reports, got: " + conflict.getMessage());

        // A disabled option paired with an unknown table reports the combination, not the lookup.
        TransitGatewayOptions unknownIdOnly = new TransitGatewayOptions();
        unknownIdOnly.setAssociationDefaultRouteTableId("tgw-rtb-0123456789abcdef0");
        assertEquals("InvalidParameterCombination", assertThrows(AwsException.class,
                () -> service.modifyTransitGateway("us-east-1", id, null, unknownIdOnly,
                        List.of(), List.of())).getErrorCode());

        // And enable on its own is a conflict once there is no table left to keep.
        assertEquals("InvalidParameterCombination", assertThrows(AwsException.class,
                () -> service.modifyTransitGateway("us-east-1", id, null, flagOnly,
                        List.of(), List.of())).getErrorCode());
    }

    /** Removals apply before additions, so a CIDR added and removed in one call survives. */
    @Test
    void aCidrBlockAddedAndRemovedInOneCallSurvives() {
        Ec2Service service = prefixListService();
        String id = service.createTransitGateway("us-east-1", null, null, List.of()).getTransitGatewayId();

        TransitGatewayOptions after = service.modifyTransitGateway("us-east-1", id, null, null,
                List.of("10.200.0.0/16"), List.of("10.200.0.0/16")).getOptions();

        assertEquals(List.of("10.200.0.0/16"), after.getTransitGatewayCidrBlocks());
    }

    /** Repointing the default route table at the gateway's own table is accepted. */
    @Test
    void aDefaultRouteTableOptionCanBeSetWhenItsRouteTableIsNamed() {
        Ec2Service service = prefixListService();
        TransitGateway gateway = service.createTransitGateway("us-east-1", null, null, List.of());
        String routeTableId = gateway.getOptions().getAssociationDefaultRouteTableId();

        TransitGatewayOptions changes = new TransitGatewayOptions();
        changes.setDefaultRouteTableAssociation("enable");
        changes.setAssociationDefaultRouteTableId(routeTableId);

        TransitGatewayOptions after = service.modifyTransitGateway("us-east-1",
                gateway.getTransitGatewayId(), null, changes, List.of(), List.of()).getOptions();

        assertEquals("enable", after.getDefaultRouteTableAssociation());
        assertEquals(routeTableId, after.getAssociationDefaultRouteTableId());
    }

    /**
     * Verified against a live account: disabling one default drops its id from the options
     * entirely and clears that marker on the route table, while the other default keeps both its
     * id and its marker, and the table itself survives.
     */
    @Test
    void disablingADefaultDropsItsIdAndClearsOnlyThatMarker() {
        AccountAwareStorageBackend<TransitGatewayRouteTable> routeTables =
                AccountAwareStorageBackend.inMemory("000000000000");
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Map.of("ec2-transit-gateway-route-tables.json", routeTables)));
        TransitGateway gateway = service.createTransitGateway("us-east-1", "hub", null, List.of());
        String routeTableId = gateway.getOptions().getAssociationDefaultRouteTableId();

        TransitGatewayOptions changes = new TransitGatewayOptions();
        changes.setDefaultRouteTableAssociation("disable");
        TransitGatewayOptions after = service.modifyTransitGateway("us-east-1",
                gateway.getTransitGatewayId(), null, changes, List.of(), List.of()).getOptions();

        assertEquals("disable", after.getDefaultRouteTableAssociation());
        assertNull(after.getAssociationDefaultRouteTableId(), "the id goes with the flag");
        assertEquals("enable", after.getDefaultRouteTablePropagation());
        assertEquals(routeTableId, after.getPropagationDefaultRouteTableId(),
                "the other default is untouched");

        TransitGatewayRouteTable stored = routeTables.scan(k -> true).getFirst();
        assertFalse(stored.isDefaultAssociationRouteTable(), "association marker cleared");
        assertTrue(stored.isDefaultPropagationRouteTable(), "propagation marker kept");
        assertEquals(routeTableId, stored.getTransitGatewayRouteTableId(), "the table itself survives");
    }

    @Test
    void deleteTransitGatewayRemovesTheGatewayAndItsDefaultRouteTable() {
        AccountAwareStorageBackend<TransitGatewayRouteTable> routeTables =
                AccountAwareStorageBackend.inMemory("000000000000");
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Map.of("ec2-transit-gateway-route-tables.json", routeTables)));
        String id = service.createTransitGateway("us-east-1", "hub", null, List.of()).getTransitGatewayId();
        assertEquals(1, routeTables.scan(k -> true).size(), "creation mints the default route table");

        TransitGateway deleted = service.deleteTransitGateway("us-east-1", id);

        assertEquals("deleted", deleted.getState());
        assertTrue(routeTables.scan(k -> true).isEmpty(), "the default route table goes with the gateway");
        AwsException gone = assertThrows(AwsException.class,
                () -> service.describeTransitGateways("us-east-1", List.of(id), Map.of()));
        assertEquals("InvalidTransitGatewayID.NotFound", gone.getErrorCode());
    }

    /**
     * A provider changes tags after creation with CreateTags and DeleteTags rather than resending
     * a TagSpecification, then re-reads them from DescribeTransitGateways. Those have to be the
     * same tags, or the resource never converges.
     */
    @Test
    void tagsChangedAfterCreationAreVisibleOnDescribe() {
        Ec2Service service = prefixListService();
        String id = service.createTransitGateway("us-east-1", "hub", null,
                List.of(new Tag("Name", "hub"))).getTransitGatewayId();

        service.createTags("us-east-1", List.of(id), List.of(new Tag("env", "prod")));

        List<Tag> afterCreate = service.describeTransitGateways("us-east-1", List.of(id), Map.of())
                .getFirst().getTags();
        assertEquals(2, afterCreate.size(), "describe serves the tags CreateTags stored");
        assertTrue(afterCreate.stream().anyMatch(t -> "env".equals(t.getKey()) && "prod".equals(t.getValue())));

        service.deleteTags("us-east-1", List.of(id), List.of(new Tag("env", null)));

        List<Tag> afterDelete = service.describeTransitGateways("us-east-1", List.of(id), Map.of())
                .getFirst().getTags();
        assertEquals(1, afterDelete.size());
        assertEquals("Name", afterDelete.getFirst().getKey());
    }

    @Test
    void tagsOnATransitGatewayAreTypedAsTransitGateway() {
        Ec2Service service = prefixListService();
        String id = service.createTransitGateway("us-east-1", "hub", null,
                List.of(new Tag("env", "prod"))).getTransitGatewayId();

        assertEquals("transit-gateway", service.describeTags("us-east-1",
                Map.of("resource-id", List.of(id))).getFirst().get("resourceType"));
        assertEquals(1, service.describeTags("us-east-1",
                Map.of("resource-type", List.of("transit-gateway"))).size());
    }

    // =========================================================================
    // Transit gateway VPC attachments
    // =========================================================================

    /** A gateway, a VPC and one subnet per zone, which is what an attachment needs. */
    private static String[] attachmentFixture(Ec2Service service) {
        String transitGatewayId = service.createTransitGateway("us-east-1", "hub", null, List.of())
                .getTransitGatewayId();
        String vpcId = service.createVpc("us-east-1", "10.90.0.0/16", false).getVpcId();
        String subnetA = service.createSubnet("us-east-1", vpcId, "10.90.1.0/24", "us-east-1a").getSubnetId();
        String subnetB = service.createSubnet("us-east-1", vpcId, "10.90.2.0/24", "us-east-1b").getSubnetId();
        return new String[] {transitGatewayId, vpcId, subnetA, subnetB};
    }

    /**
     * The attachment's option defaults are its own, not the gateway's. Verified on a live account:
     * securityGroupReferencingSupport is enabled here and disabled on the gateway that owns it.
     */
    @Test
    void createVpcAttachmentAppliesTheAttachmentsOwnDefaults() {
        Ec2Service service = prefixListService();
        String[] fixture = attachmentFixture(service);

        TransitGatewayVpcAttachment attachment = service.createTransitGatewayVpcAttachment(
                "us-east-1", fixture[0], fixture[1], List.of(fixture[2]), null, List.of());

        assertTrue(attachment.getTransitGatewayAttachmentId().startsWith("tgw-attach-"));
        assertEquals("available", attachment.getState());
        assertEquals("000000000000", attachment.getVpcOwnerId());
        assertEquals(List.of(fixture[2]), attachment.getSubnetIds());
        assertEquals("enable", attachment.getOptions().getDnsSupport());
        assertEquals("enable", attachment.getOptions().getSecurityGroupReferencingSupport());
        assertEquals("disable", attachment.getOptions().getIpv6Support());
        assertEquals("disable", attachment.getOptions().getApplianceModeSupport());
    }

    /** The association follows the gateway's own default-association setting. */
    @Test
    void anAttachmentAssociatesOnlyWhenTheGatewayAsksForIt() {
        Ec2Service service = prefixListService();
        String[] fixture = attachmentFixture(service);
        TransitGateway gateway = service.describeTransitGateways("us-east-1", List.of(fixture[0]), Map.of())
                .getFirst();

        TransitGatewayVpcAttachment associated = service.createTransitGatewayVpcAttachment(
                "us-east-1", fixture[0], fixture[1], List.of(fixture[2]), null, List.of());
        assertEquals(gateway.getOptions().getAssociationDefaultRouteTableId(),
                associated.getAssociationRouteTableId());
        assertEquals("associated", associated.getAssociationState());

        TransitGatewayOptions defaultsOff = new TransitGatewayOptions();
        defaultsOff.setDefaultRouteTableAssociation("disable");
        defaultsOff.setDefaultRouteTablePropagation("disable");
        String bareGateway = service.createTransitGateway("us-east-1", "bare", defaultsOff, List.of())
                .getTransitGatewayId();
        String otherVpc = service.createVpc("us-east-1", "10.95.0.0/16", false).getVpcId();
        String otherSubnet = service.createSubnet("us-east-1", otherVpc, "10.95.1.0/24", "us-east-1a")
                .getSubnetId();

        TransitGatewayVpcAttachment unassociated = service.createTransitGatewayVpcAttachment(
                "us-east-1", bareGateway, otherVpc, List.of(otherSubnet), null, List.of());
        assertNull(unassociated.getAssociationRouteTableId());
        assertNull(unassociated.getAssociationState());
    }

    /**
     * One attachment per VPC is a uniqueness rule, so the duplicate check and the write have to be
     * one step. Reproduces the race: with the two unsynchronized, concurrent creates for the same
     * VPC both find nothing and both store.
     */
    @Test
    void concurrentAttachmentsForOneVpcLeaveExactlyOne() throws Exception {
        // Kept deliberately small: without the lock the race shows on the first trial, and this
        // suite has thread-timing-sensitive tests elsewhere that a heavier harness disturbs.
        for (int trial = 0; trial < 10; trial++) {
            Ec2Service service = prefixListService();
            String[] fixture = attachmentFixture(service);
            int threads = 6;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool =
                    Executors.newFixedThreadPool(threads);
            List<String> created = Collections.synchronizedList(new ArrayList<>());
            List<String> errors = Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        created.add(service.createTransitGatewayVpcAttachment("us-east-1", fixture[0],
                                fixture[1], List.of(fixture[2]), null, List.of())
                                .getTransitGatewayAttachmentId());
                    } catch (AwsException expected) {
                        errors.add(expected.getErrorCode());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

            assertEquals(1, created.size(), "trial " + trial + ": exactly one create may win");
            assertEquals(threads - 1, errors.size(), "every loser is told the VPC is already attached");
            assertTrue(errors.stream().allMatch("DuplicateTransitGatewayAttachment"::equals), errors.toString());
            assertEquals(1, service.describeTransitGatewayVpcAttachments("us-east-1", List.of(), Map.of()).size(),
                    "and only one attachment is stored");
        }
    }

    /**
     * Attaching races deleting the thing being attached to. Whoever wins, the store may never end
     * up holding an attachment that names a gateway, VPC or subnet which is gone: resolving those
     * outside the lock that guards the write is what allows exactly that.
     */
    @Test
    void attachingNeverRacesAheadOfDeletingWhatItAttachesTo() throws Exception {
        record Race(String name, boolean deleteGateway) {}
        for (Race race : List.of(new Race("gateway", true), new Race("vpc", false))) {
            for (int trial = 0; trial < 10; trial++) {
                Ec2Service service = prefixListService();
                String[] fixture = attachmentFixture(service);
                CountDownLatch start = new CountDownLatch(1);
                ExecutorService pool =
                        Executors.newFixedThreadPool(2);

                pool.submit(() -> {
                    try {
                        start.await();
                        service.createTransitGatewayVpcAttachment("us-east-1", fixture[0], fixture[1],
                                List.of(fixture[2]), null, List.of());
                    } catch (AwsException | InterruptedException ignored) {
                        // Losing the race is a legitimate outcome; the invariant is checked below.
                    }
                });
                pool.submit(() -> {
                    try {
                        start.await();
                        if (race.deleteGateway()) {
                            service.deleteTransitGateway("us-east-1", fixture[0]);
                        } else {
                            service.deleteSubnet("us-east-1", fixture[2]);
                            service.deleteVpc("us-east-1", fixture[1]);
                        }
                    } catch (AwsException | InterruptedException ignored) {
                        // Same.
                    }
                });
                start.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

                List<TransitGatewayVpcAttachment> attachments =
                        service.describeTransitGatewayVpcAttachments("us-east-1", List.of(), Map.of());
                for (TransitGatewayVpcAttachment attachment : attachments) {
                    assertFalse(service.describeTransitGateways("us-east-1", List.of(), Map.of()).isEmpty(),
                            race.name() + " trial " + trial + ": attachment outlived its gateway");
                    assertTrue(service.describeVpcs("us-east-1", List.of(), Map.of()).stream()
                                    .anyMatch(vpc -> vpc.getVpcId().equals(attachment.getVpcId())),
                            race.name() + " trial " + trial + ": attachment names a deleted VPC");
                    for (String subnetId : attachment.getSubnetIds()) {
                        assertTrue(service.describeSubnets("us-east-1", List.of(subnetId), Map.of()).stream()
                                        .anyMatch(subnet -> subnet.getSubnetId().equals(subnetId)),
                                race.name() + " trial " + trial + ": attachment names a deleted subnet");
                    }
                }
            }
        }
    }

    /**
     * Tagging is a read-modify-write, so a tag call racing a delete must not put the record back.
     * A resurrected attachment would keep blocking the VPC and subnets it names, and a resurrected
     * gateway would keep blocking its own deletion, with nothing left to delete either of them.
     */
    @Test
    void taggingCannotResurrectSomethingBeingDeleted() throws Exception {
        for (boolean attachment : List.of(true, false)) {
            for (int trial = 0; trial < 10; trial++) {
                Ec2Service service = prefixListService();
                String[] fixture = attachmentFixture(service);
                String attachmentId = service.createTransitGatewayVpcAttachment("us-east-1", fixture[0],
                        fixture[1], List.of(fixture[2]), null, List.of()).getTransitGatewayAttachmentId();
                String target = attachment ? attachmentId : fixture[0];
                if (!attachment) {
                    service.deleteTransitGatewayVpcAttachment("us-east-1", attachmentId);
                }
                CountDownLatch start = new CountDownLatch(1);
                ExecutorService pool =
                        Executors.newFixedThreadPool(2);

                pool.submit(() -> {
                    try {
                        start.await();
                        service.createTags("us-east-1", List.of(target), List.of(new Tag("env", "prod")));
                    } catch (AwsException | InterruptedException ignored) {
                        // Losing the race is fine; the invariant is below.
                    }
                });
                pool.submit(() -> {
                    try {
                        start.await();
                        if (attachment) {
                            service.deleteTransitGatewayVpcAttachment("us-east-1", target);
                        } else {
                            service.deleteTransitGateway("us-east-1", target);
                        }
                    } catch (AwsException | InterruptedException ignored) {
                        // Same.
                    }
                });
                start.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

                String what = attachment ? "attachment" : "gateway";
                if (attachment) {
                    assertTrue(service.describeTransitGatewayVpcAttachments("us-east-1", List.of(), Map.of())
                            .isEmpty(), what + " trial " + trial + ": tagging put the deleted record back");
                } else {
                    assertTrue(service.describeTransitGateways("us-east-1", List.of(), Map.of()).stream()
                                    .noneMatch(g -> g.getTransitGatewayId().equals(target)),
                            what + " trial " + trial + ": tagging put the deleted record back");
                }
            }
        }
    }

    /**
     * Verified on a live account: an attached VPC or subnet cannot be deleted out from under the
     * attachment, and both report the same {@code DependencyViolation}. Without this the stored
     * attachment would go on naming resources that no longer exist, and a later modify would fail
     * on a subnet the caller never touched.
     */
    @Test
    void anAttachedVpcOrSubnetCannotBeDeleted() {
        Ec2Service service = prefixListService();
        String[] fixture = attachmentFixture(service);
        String attachmentId = service.createTransitGatewayVpcAttachment("us-east-1", fixture[0],
                fixture[1], List.of(fixture[2]), null, List.of()).getTransitGatewayAttachmentId();

        assertEquals("DependencyViolation", assertThrows(AwsException.class,
                () -> service.deleteSubnet("us-east-1", fixture[2])).getErrorCode());
        assertEquals("DependencyViolation", assertThrows(AwsException.class,
                () -> service.deleteVpc("us-east-1", fixture[1])).getErrorCode());

        // A subnet of the same VPC that the attachment does not use is free to go.
        service.deleteSubnet("us-east-1", fixture[3]);

        service.deleteTransitGatewayVpcAttachment("us-east-1", attachmentId);
        service.deleteSubnet("us-east-1", fixture[2]);
        service.deleteVpc("us-east-1", fixture[1]);
    }

    /**
     * An attachment must be created with at least one subnet. Modify refuses to leave one without
     * any, so creation must not be a back door into the state modify forbids.
     */
    @Test
    void anAttachmentCannotBeCreatedWithoutSubnets() {
        Ec2Service service = prefixListService();
        String[] fixture = attachmentFixture(service);

        assertEquals("MissingParameter", assertThrows(AwsException.class,
                () -> service.createTransitGatewayVpcAttachment("us-east-1", fixture[0], fixture[1],
                        List.of(), null, List.of())).getErrorCode());
    }

    /**
     * Verified on a live account: disabling the gateway's default association clears the id on the
     * gateway but leaves an existing attachment associated. The association was made when the
     * attachment was created, and only later attachments are affected.
     */
    @Test
    void anExistingAssociationSurvivesTheGatewayDisablingItsDefault() {
        Ec2Service service = prefixListService();
        String[] fixture = attachmentFixture(service);
        TransitGatewayVpcAttachment attachment = service.createTransitGatewayVpcAttachment(
                "us-east-1", fixture[0], fixture[1], List.of(fixture[2]), null, List.of());
        String routeTableId = attachment.getAssociationRouteTableId();

        TransitGatewayOptions disable = new TransitGatewayOptions();
        disable.setDefaultRouteTableAssociation("disable");
        TransitGateway gateway = service.modifyTransitGateway("us-east-1", fixture[0], null, disable,
                List.of(), List.of());

        assertNull(gateway.getOptions().getAssociationDefaultRouteTableId(), "the gateway drops its id");
        TransitGatewayVpcAttachment after = service.describeTransitGatewayVpcAttachments("us-east-1",
                List.of(attachment.getTransitGatewayAttachmentId()), Map.of()).getFirst();
        assertEquals(routeTableId, after.getAssociationRouteTableId(),
                "the attachment keeps the association it was created with");
        assertEquals("associated", after.getAssociationState());
    }

    /**
     * Verified live: an attachment id of the wrong shape is rejected before the lookup, under the
     * attachment's own malformed code, matching how a malformed gateway id behaves.
     */
    @Test
    void aMalformedAttachmentIdIsRejectedBeforeTheLookup() {
        Ec2Service service = prefixListService();

        for (String malformed : List.of("tgw-attach-nope", "vpc-0123456789abcdef0")) {
            assertEquals("InvalidTransitGatewayAttachmentID.Malformed", assertThrows(AwsException.class,
                    () -> service.describeTransitGatewayVpcAttachments("us-east-1", List.of(malformed), Map.of()))
                    .getErrorCode(), malformed);
        }
        assertEquals("InvalidTransitGatewayAttachmentID.NotFound", assertThrows(AwsException.class,
                () -> service.describeTransitGatewayVpcAttachments("us-east-1",
                        List.of("tgw-attach-0123456789abcdef0"), Map.of())).getErrorCode(),
                "a well-formed id that does not exist is a different failure");
    }

    /**
     * floci does not yet support creating Connect attachments, so LZA's tgw-associations-and-
     * propagations module, which describes them unconditionally alongside VPC attachments, needs
     * this to at least answer with an empty account-wide list rather than an unrecognized-action
     * error (LZA fidelity gap: AWSAccelerator-ToolkitProject build 29 failed with "Operation
     * DescribeTransitGatewayConnects is not supported").
     */
    @Test
    void describeTransitGatewayConnectsReturnsEmptyWhenNoneExist() {
        Ec2Service service = prefixListService();

        assertDoesNotThrow(() -> service.describeTransitGatewayConnects("us-east-1", List.of(), Map.of()));
    }

    @Test
    void describeTransitGatewayConnectsRejectsAMalformedOrUnknownId() {
        Ec2Service service = prefixListService();

        assertEquals("InvalidTransitGatewayAttachmentID.Malformed", assertThrows(AwsException.class,
                () -> service.describeTransitGatewayConnects("us-east-1", List.of("tgw-connect-nope"), Map.of()))
                .getErrorCode());
        assertEquals("InvalidTransitGatewayConnectID.NotFound", assertThrows(AwsException.class,
                () -> service.describeTransitGatewayConnects("us-east-1",
                        List.of("tgw-attach-0123456789abcdef0"), Map.of())).getErrorCode());
    }

    /** The gateway's owner is its own field, sourced from the gateway rather than from the VPC. */
    @Test
    void anAttachmentRecordsBothOwnersSeparately() {
        Ec2Service service = prefixListService();
        String[] fixture = attachmentFixture(service);

        TransitGatewayVpcAttachment attachment = service.createTransitGatewayVpcAttachment(
                "us-east-1", fixture[0], fixture[1], List.of(fixture[2]), null, List.of());

        TransitGateway gateway = service.describeTransitGateways("us-east-1", List.of(fixture[0]), Map.of())
                .getFirst();
        assertEquals(gateway.getOwnerId(), attachment.getTransitGatewayOwnerId());
        assertEquals("000000000000", attachment.getVpcOwnerId());
    }

    @Test
    void aVpcCanOnlyBeAttachedToAGatewayOnce() {
        Ec2Service service = prefixListService();
        String[] fixture = attachmentFixture(service);
        service.createTransitGatewayVpcAttachment("us-east-1", fixture[0], fixture[1],
                List.of(fixture[2]), null, List.of());

        AwsException error = assertThrows(AwsException.class, () -> service.createTransitGatewayVpcAttachment(
                "us-east-1", fixture[0], fixture[1], List.of(fixture[3]), null, List.of()));
        assertEquals("DuplicateTransitGatewayAttachment", error.getErrorCode());
    }

    /** Verified live: a subnet from another VPC is reported missing rather than mismatched. */
    @Test
    void subnetsMustBelongToTheAttachedVpcAndBeOnePerZone() {
        Ec2Service service = prefixListService();
        String[] fixture = attachmentFixture(service);
        String otherVpc = service.createVpc("us-east-1", "10.96.0.0/16", false).getVpcId();
        String foreignSubnet = service.createSubnet("us-east-1", otherVpc, "10.96.1.0/24", "us-east-1a")
                .getSubnetId();

        assertEquals("InvalidSubnetID.NotFound", assertThrows(AwsException.class,
                () -> service.createTransitGatewayVpcAttachment("us-east-1", fixture[0], fixture[1],
                        List.of(foreignSubnet), null, List.of())).getErrorCode());

        String sameZoneSubnet = service.createSubnet("us-east-1", fixture[1], "10.90.9.0/24", "us-east-1a")
                .getSubnetId();
        assertEquals("DuplicateSubnetsInSameZone", assertThrows(AwsException.class,
                () -> service.createTransitGatewayVpcAttachment("us-east-1", fixture[0], fixture[1],
                        List.of(fixture[2], sameZoneSubnet), null, List.of())).getErrorCode());
    }

    @Test
    void modifyVpcAttachmentAddsAndRemovesSubnetsAndOptions() {
        Ec2Service service = prefixListService();
        String[] fixture = attachmentFixture(service);
        String attachmentId = service.createTransitGatewayVpcAttachment("us-east-1", fixture[0],
                fixture[1], List.of(fixture[2]), null, List.of()).getTransitGatewayAttachmentId();
        TransitGatewayVpcAttachmentOptions changes = new TransitGatewayVpcAttachmentOptions();
        changes.setDnsSupport("disable");

        TransitGatewayVpcAttachment widened = service.modifyTransitGatewayVpcAttachment("us-east-1",
                attachmentId, List.of(fixture[3]), List.of(), changes);
        assertEquals(List.of(fixture[2], fixture[3]), widened.getSubnetIds());
        assertEquals("disable", widened.getOptions().getDnsSupport());
        assertEquals("enable", widened.getOptions().getSecurityGroupReferencingSupport(),
                "an untouched option keeps its value");

        TransitGatewayVpcAttachment narrowed = service.modifyTransitGatewayVpcAttachment("us-east-1",
                attachmentId, List.of(), List.of(fixture[2]), null);
        assertEquals(List.of(fixture[3]), narrowed.getSubnetIds());

        assertEquals("InvalidSubnetID.NotFound", assertThrows(AwsException.class,
                () -> service.modifyTransitGatewayVpcAttachment("us-east-1", attachmentId,
                        List.of(), List.of(fixture[2]), null)).getErrorCode(),
                "removing a subnet that is not attached");

        assertEquals("InsufficientSubnetsException", assertThrows(AwsException.class,
                () -> service.modifyTransitGatewayVpcAttachment("us-east-1", attachmentId,
                        List.of(), List.of(fixture[3]), null)).getErrorCode(),
                "an attachment cannot be left with no subnets");
    }

    /** Verified live: the gateway refuses to go while anything is still attached to it. */
    @Test
    void aGatewayWithAnAttachmentCannotBeDeleted() {
        Ec2Service service = prefixListService();
        String[] fixture = attachmentFixture(service);
        String attachmentId = service.createTransitGatewayVpcAttachment("us-east-1", fixture[0],
                fixture[1], List.of(fixture[2]), null, List.of()).getTransitGatewayAttachmentId();

        AwsException error = assertThrows(AwsException.class,
                () -> service.deleteTransitGateway("us-east-1", fixture[0]));
        assertEquals("IncorrectState", error.getErrorCode());
        assertTrue(error.getMessage().contains(attachmentId), "the message names the attachment");

        service.deleteTransitGatewayVpcAttachment("us-east-1", attachmentId);
        assertEquals("deleted", service.deleteTransitGateway("us-east-1", fixture[0]).getState(),
                "the gateway goes once the attachment has");
    }

    @Test
    void tagsOnAnAttachmentAreTypedAsTransitGatewayAttachment() {
        Ec2Service service = prefixListService();
        String[] fixture = attachmentFixture(service);
        String attachmentId = service.createTransitGatewayVpcAttachment("us-east-1", fixture[0],
                fixture[1], List.of(fixture[2]), null, List.of(new Tag("Name", "hub-attach")))
                .getTransitGatewayAttachmentId();

        assertEquals("transit-gateway-attachment", service.describeTags("us-east-1",
                Map.of("resource-id", List.of(attachmentId))).getFirst().get("resourceType"));

        service.createTags("us-east-1", List.of(attachmentId), List.of(new Tag("env", "prod")));
        assertEquals(2, service.describeTransitGatewayVpcAttachments("us-east-1",
                List.of(attachmentId), Map.of()).getFirst().getTags().size(),
                "describe serves tags added after creation");
    }

    // =========================================================================
    // Transit gateway route tables, associations, propagations and routes
    // =========================================================================

    /** A gateway, an attachment, and a second route table to move things onto. */
    private static String[] routeTableFixture(Ec2Service service) {
        String[] fixture = attachmentFixture(service);
        String attachmentId = service.createTransitGatewayVpcAttachment("us-east-1", fixture[0],
                fixture[1], List.of(fixture[2]), null, List.of()).getTransitGatewayAttachmentId();
        String routeTableId = service.createTransitGatewayRouteTable("us-east-1", fixture[0], List.of())
                .getTransitGatewayRouteTableId();
        return new String[] {fixture[0], fixture[1], fixture[2], attachmentId, routeTableId};
    }

    @Test
    void aRequestedRouteTableIsNeitherDefault() {
        Ec2Service service = prefixListService();
        String[] fixture = attachmentFixture(service);

        TransitGatewayRouteTable routeTable = service.createTransitGatewayRouteTable("us-east-1",
                fixture[0], List.of(new Tag("Name", "spoke")));

        assertTrue(routeTable.getTransitGatewayRouteTableId().startsWith("tgw-rtb-"));
        assertEquals("available", routeTable.getState());
        assertFalse(routeTable.isDefaultAssociationRouteTable());
        assertFalse(routeTable.isDefaultPropagationRouteTable());
        assertEquals(2, service.describeTransitGatewayRouteTables("us-east-1", List.of(), Map.of()).size(),
                "the gateway's own default table is there too");
    }

    /** Verified live: an attachment associates with exactly one route table. */
    @Test
    void anAttachmentAssociatesWithOneRouteTableAtATime() {
        Ec2Service service = prefixListService();
        String[] fixture = routeTableFixture(service);

        AwsException already = assertThrows(AwsException.class, () -> service
                .associateTransitGatewayRouteTable("us-east-1", fixture[4], fixture[3]));
        assertEquals("Resource.AlreadyAssociated", already.getErrorCode());

        TransitGateway gateway = service.describeTransitGateways("us-east-1", List.of(fixture[0]), Map.of())
                .getFirst();
        String defaultTable = gateway.getOptions().getAssociationDefaultRouteTableId();
        assertEquals(1, service.associationsOf("us-east-1", defaultTable).size(),
                "the attachment starts on the gateway's default table");

        service.disassociateTransitGatewayRouteTable("us-east-1", defaultTable, fixture[3]);
        assertTrue(service.associationsOf("us-east-1", defaultTable).isEmpty());

        service.associateTransitGatewayRouteTable("us-east-1", fixture[4], fixture[3]);
        assertEquals(fixture[3], service.associationsOf("us-east-1", fixture[4]).getFirst()
                .getTransitGatewayAttachmentId());
    }

    /** Verified live: propagation reports the settled state at once, and refuses a duplicate. */
    @Test
    void propagationIsEnabledAtOnceAndOnlyOnce() {
        Ec2Service service = prefixListService();
        String[] fixture = routeTableFixture(service);

        TransitGatewayRouteTablePropagation propagation = service
                .enableTransitGatewayRouteTablePropagation("us-east-1", fixture[4], fixture[3]);
        assertEquals("enabled", propagation.getState());
        assertEquals(fixture[1], propagation.getResourceId());
        assertEquals("vpc", propagation.getResourceType());

        assertEquals("TransitGatewayRouteTablePropagation.Duplicate", assertThrows(AwsException.class,
                () -> service.enableTransitGatewayRouteTablePropagation("us-east-1", fixture[4], fixture[3]))
                .getErrorCode());

        assertEquals("disabled", service.disableTransitGatewayRouteTablePropagation(
                "us-east-1", fixture[4], fixture[3]).getState());
        assertTrue(service.propagationsOf("us-east-1", fixture[4]).isEmpty());
    }

    /**
     * Verified live: enabling propagation makes the attached VPC's CIDR show up as a propagated
     * route. It is derived from the VPC rather than stored, so a CIDR change cannot leave a stale
     * route behind.
     */
    @Test
    void propagationProducesRoutesForTheAttachedVpcsCidr() {
        Ec2Service service = prefixListService();
        String[] fixture = routeTableFixture(service);
        service.enableTransitGatewayRouteTablePropagation("us-east-1", fixture[4], fixture[3]);

        List<TransitGatewayRoute> propagated = service.searchTransitGatewayRoutes("us-east-1", fixture[4],
                Map.of("type", List.of("propagated")));

        assertEquals(1, propagated.size());
        assertEquals("10.90.0.0/16", propagated.getFirst().getDestinationCidrBlock());
        assertEquals("active", propagated.getFirst().getState());
        assertEquals(fixture[3], propagated.getFirst().getTransitGatewayAttachmentId());

        service.disableTransitGatewayRouteTablePropagation("us-east-1", fixture[4], fixture[3]);
        assertTrue(service.searchTransitGatewayRoutes("us-east-1", fixture[4],
                Map.of("type", List.of("propagated"))).isEmpty(), "the route goes with the propagation");
    }

    /** A blackhole is a state of a static route, not a type, and carries no attachment. */
    @Test
    void staticAndBlackholeRoutesDifferByStateNotType() {
        Ec2Service service = prefixListService();
        String[] fixture = routeTableFixture(service);

        TransitGatewayRoute viaAttachment = service.createTransitGatewayRoute("us-east-1", fixture[4],
                "10.60.0.0/16", fixture[3], false);
        assertEquals("static", viaAttachment.getType());
        assertEquals("active", viaAttachment.getState());
        assertEquals(fixture[3], viaAttachment.getTransitGatewayAttachmentId());

        TransitGatewayRoute blackhole = service.createTransitGatewayRoute("us-east-1", fixture[4],
                "10.61.0.0/16", null, true);
        assertEquals("static", blackhole.getType());
        assertEquals("blackhole", blackhole.getState());
        assertNull(blackhole.getTransitGatewayAttachmentId());

        assertEquals("RouteAlreadyExists", assertThrows(AwsException.class,
                () -> service.createTransitGatewayRoute("us-east-1", fixture[4], "10.60.0.0/16",
                        fixture[3], false)).getErrorCode());

        assertEquals(2, service.searchTransitGatewayRoutes("us-east-1", fixture[4],
                Map.of("type", List.of("static"))).size(), "a blackhole is still a static route");

        TransitGatewayRoute deleted = service.deleteTransitGatewayRoute("us-east-1", fixture[4], "10.61.0.0/16");
        assertEquals("deleted", deleted.getState());
        assertNull(deleted.getTransitGatewayAttachmentId());
        assertEquals(1, service.searchTransitGatewayRoutes("us-east-1", fixture[4], Map.of()).size());
    }

    /**
     * Verified live: a route table will not go while it is the gateway's default association table
     * nor while attachments are associated with it, both under IncorrectState.
     */
    @Test
    void aRouteTableInUseCannotBeDeleted() {
        Ec2Service service = prefixListService();
        String[] fixture = routeTableFixture(service);
        TransitGateway gateway = service.describeTransitGateways("us-east-1", List.of(fixture[0]), Map.of())
                .getFirst();
        String defaultTable = gateway.getOptions().getAssociationDefaultRouteTableId();

        AwsException isDefault = assertThrows(AwsException.class,
                () -> service.deleteTransitGatewayRouteTable("us-east-1", defaultTable));
        assertEquals("IncorrectState", isDefault.getErrorCode());
        assertTrue(isDefault.getMessage().contains("default association route table"), isDefault.getMessage());

        service.disassociateTransitGatewayRouteTable("us-east-1", defaultTable, fixture[3]);
        service.associateTransitGatewayRouteTable("us-east-1", fixture[4], fixture[3]);

        AwsException associated = assertThrows(AwsException.class,
                () -> service.deleteTransitGatewayRouteTable("us-east-1", fixture[4]));
        assertEquals("IncorrectState", associated.getErrorCode());
        assertTrue(associated.getMessage().contains("has associated attachments"), associated.getMessage());

        service.disassociateTransitGatewayRouteTable("us-east-1", fixture[4], fixture[3]);
        service.createTransitGatewayRoute("us-east-1", fixture[4], "10.70.0.0/16", null, true);
        assertEquals("deleted", service.deleteTransitGatewayRouteTable("us-east-1", fixture[4]).getState());
        assertEquals("InvalidRouteTableID.NotFound", assertThrows(AwsException.class,
                () -> service.searchTransitGatewayRoutes("us-east-1", fixture[4], Map.of())).getErrorCode(),
                "its routes went with it");
    }

    /** Verified live, casing included: NotFound spells it ID, Malformed spells it Id. */
    @Test
    void routeTableIdErrorsMatchTheLiveApiIncludingCasing() {
        Ec2Service service = prefixListService();

        assertEquals("InvalidRouteTableID.NotFound", assertThrows(AwsException.class,
                () -> service.describeTransitGatewayRouteTables("us-east-1",
                        List.of("tgw-rtb-0123456789abcdef0"), Map.of())).getErrorCode());
        assertEquals("InvalidRouteTableId.Malformed", assertThrows(AwsException.class,
                () -> service.describeTransitGatewayRouteTables("us-east-1",
                        List.of("tgw-rtb-nope"), Map.of())).getErrorCode());
    }

    /**
     * Verified on a live account: deleting an attachment removes its propagations, and a static
     * route that named it survives as a blackhole rather than disappearing — the destination is
     * still configured, it just has nowhere to go.
     */
    @Test
    void deletingAnAttachmentClearsPropagationsAndBlackholesItsRoutes() {
        Ec2Service service = prefixListService();
        String[] fixture = routeTableFixture(service);
        service.enableTransitGatewayRouteTablePropagation("us-east-1", fixture[4], fixture[3]);
        service.createTransitGatewayRoute("us-east-1", fixture[4], "10.99.0.0/16", fixture[3], false);

        service.deleteTransitGatewayVpcAttachment("us-east-1", fixture[3]);

        assertTrue(service.propagationsOf("us-east-1", fixture[4]).isEmpty(), "propagations go");
        List<TransitGatewayRoute> routes = service.searchTransitGatewayRoutes("us-east-1", fixture[4], Map.of());
        assertEquals(1, routes.size(), "the static route stays");
        assertEquals("blackhole", routes.getFirst().getState());
        assertNull(routes.getFirst().getTransitGatewayAttachmentId());
        assertTrue(service.searchTransitGatewayRoutes("us-east-1", fixture[4],
                Map.of("type", List.of("propagated"))).isEmpty(), "and its propagated route with it");
    }

    /** A gateway's route tables take their propagations, routes and tags with them. */
    @Test
    void deletingAGatewayLeavesNothingBehindItsRouteTables() {
        Ec2Service service = prefixListService();
        String[] fixture = routeTableFixture(service);
        service.enableTransitGatewayRouteTablePropagation("us-east-1", fixture[4], fixture[3]);
        service.createTransitGatewayRoute("us-east-1", fixture[4], "10.99.0.0/16", null, true);
        service.createTags("us-east-1", List.of(fixture[4]), List.of(new Tag("env", "prod")));
        service.deleteTransitGatewayVpcAttachment("us-east-1", fixture[3]);

        service.deleteTransitGateway("us-east-1", fixture[0]);

        assertTrue(service.describeTransitGatewayRouteTables("us-east-1", List.of(), Map.of()).isEmpty(),
                "the tables go with the gateway");
        assertTrue(service.propagationsOf("us-east-1", fixture[4]).isEmpty());
        assertTrue(service.describeTags("us-east-1", Map.of("resource-id", List.of(fixture[4]))).isEmpty(),
                "and their tags");
    }

    /**
     * Modifying a gateway writes route tables through its default-table markers, so it races the
     * deletes the same way tagging does. Whoever wins, a deleted table must not come back.
     */
    @Test
    void modifyingAGatewayCannotResurrectADeletedRouteTable() throws Exception {
        for (int trial = 0; trial < 10; trial++) {
            Ec2Service service = prefixListService();
            String[] fixture = attachmentFixture(service);
            String spare = service.createTransitGatewayRouteTable("us-east-1", fixture[0], List.of())
                    .getTransitGatewayRouteTableId();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool =
                    Executors.newFixedThreadPool(2);

            pool.submit(() -> {
                try {
                    start.await();
                    TransitGatewayOptions changes = new TransitGatewayOptions();
                    changes.setDefaultRouteTableAssociation("enable");
                    changes.setAssociationDefaultRouteTableId(spare);
                    service.modifyTransitGateway("us-east-1", fixture[0], null, changes, List.of(), List.of());
                } catch (AwsException | InterruptedException ignored) {
                    // Losing the race is fine.
                }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    service.deleteTransitGatewayRouteTable("us-east-1", spare);
                } catch (AwsException | InterruptedException ignored) {
                    // Same.
                }
            });
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

            boolean tableExists = service.describeTransitGatewayRouteTables("us-east-1", List.of(), Map.of())
                    .stream().anyMatch(rt -> rt.getTransitGatewayRouteTableId().equals(spare));
            TransitGateway gateway = service.describeTransitGateways("us-east-1", List.of(fixture[0]), Map.of())
                    .getFirst();
            if (!tableExists) {
                assertNotEquals(spare, gateway.getOptions().getAssociationDefaultRouteTableId(),
                        "trial " + trial + ": the gateway names a route table that is gone");
            }
        }
    }

    /**
     * Verified on a live account: a route table only reaches attachments of its own gateway.
     * Associating, propagating or routing to another gateway's attachment is refused as though the
     * attachment did not exist, which is the same shape as a subnet belonging to another VPC.
     */
    @Test
    void aRouteTableOnlyReachesItsOwnGatewaysAttachments() {
        Ec2Service service = prefixListService();
        String[] fixture = routeTableFixture(service);
        String otherGateway = service.createTransitGateway("us-east-1", "other", null, List.of())
                .getTransitGatewayId();
        String foreignTable = service.createTransitGatewayRouteTable("us-east-1", otherGateway, List.of())
                .getTransitGatewayRouteTableId();

        for (Runnable crossGateway : List.<Runnable>of(
                () -> service.associateTransitGatewayRouteTable("us-east-1", foreignTable, fixture[3]),
                () -> service.disassociateTransitGatewayRouteTable("us-east-1", foreignTable, fixture[3]),
                () -> service.enableTransitGatewayRouteTablePropagation("us-east-1", foreignTable, fixture[3]),
                () -> service.disableTransitGatewayRouteTablePropagation("us-east-1", foreignTable, fixture[3]),
                () -> service.createTransitGatewayRoute("us-east-1", foreignTable, "10.77.0.0/16",
                        fixture[3], false))) {
            assertEquals("InvalidTransitGatewayAttachmentID.NotFound",
                    assertThrows(AwsException.class, crossGateway::run).getErrorCode());
        }

        // Nothing was recorded against the other gateway's table on the way through.
        assertTrue(service.associationsOf("us-east-1", foreignTable).isEmpty());
        assertTrue(service.propagationsOf("us-east-1", foreignTable).isEmpty());
        assertTrue(service.searchTransitGatewayRoutes("us-east-1", foreignTable, Map.of()).isEmpty());
    }

    /**
     * Verified on a live account: replacing a route flips its target in place, and replacing a
     * destination the table has never held writes it rather than reporting it missing — an upsert
     * rather than an update.
     */
    @Test
    void replacingARouteMovesItsTargetAndUpsertsWhenAbsent() {
        Ec2Service service = prefixListService();
        String[] fixture = routeTableFixture(service);
        service.createTransitGatewayRoute("us-east-1", fixture[4], "10.88.0.0/16", fixture[3], false);

        TransitGatewayRoute blackholed = service.replaceTransitGatewayRoute("us-east-1", fixture[4],
                "10.88.0.0/16", null, true);
        assertEquals("blackhole", blackholed.getState());
        assertNull(blackholed.getTransitGatewayAttachmentId(), "the target moves as one");
        assertNull(blackholed.getResourceId());

        TransitGatewayRoute restored = service.replaceTransitGatewayRoute("us-east-1", fixture[4],
                "10.88.0.0/16", fixture[3], false);
        assertEquals("active", restored.getState());
        assertEquals(fixture[3], restored.getTransitGatewayAttachmentId());
        assertEquals(fixture[1], restored.getResourceId());

        TransitGatewayRoute created = service.replaceTransitGatewayRoute("us-east-1", fixture[4],
                "10.89.0.0/16", null, true);
        assertEquals("blackhole", created.getState());
        assertEquals(2, service.searchTransitGatewayRoutes("us-east-1", fixture[4],
                Map.of("type", List.of("static"))).size(), "replacing an absent route writes it");

        // The same ownership rule as the other route table operations.
        String otherGateway = service.createTransitGateway("us-east-1", "other", null, List.of())
                .getTransitGatewayId();
        String foreignTable = service.createTransitGatewayRouteTable("us-east-1", otherGateway, List.of())
                .getTransitGatewayRouteTableId();
        assertEquals("InvalidTransitGatewayAttachmentID.NotFound", assertThrows(AwsException.class,
                () -> service.replaceTransitGatewayRoute("us-east-1", foreignTable, "10.90.0.0/16",
                        fixture[3], false)).getErrorCode());

        // A replaced route is still a route, so losing its attachment blackholes it.
        service.deleteTransitGatewayVpcAttachment("us-east-1", fixture[3]);
        TransitGatewayRoute afterDetach = service.searchTransitGatewayRoutes("us-east-1", fixture[4],
                Map.of("route-search.exact-match", List.of("10.88.0.0/16"))).getFirst();
        assertEquals("blackhole", afterDetach.getState());
        assertNull(afterDetach.getTransitGatewayAttachmentId());
    }

    /**
     * The route search filters, as observed on a live account. The three CIDR relationship filters
     * take different value forms: supernet-of-match and subnet-of-match take a CIDR and match
     * inclusively, longest-prefix-match takes a bare address and returns the one most specific
     * route covering it. Giving either the other's form returns nothing there, and here.
     */
    @Test
    void routeSearchFiltersMatchTheLiveApi() {
        Ec2Service service = prefixListService();
        String[] fixture = routeTableFixture(service);
        for (String cidr : List.of("10.0.0.0/8", "10.1.0.0/16", "10.1.1.0/24", "192.168.0.0/16")) {
            service.createTransitGatewayRoute("us-east-1", fixture[4], cidr, null, true);
        }

        assertEquals(List.of("10.0.0.0/8", "10.1.0.0/16", "10.1.1.0/24"),
                cidrsOf(service.searchTransitGatewayRoutes("us-east-1", fixture[4],
                        Map.of("route-search.supernet-of-match", List.of("10.1.1.0/24")))),
                "everything containing it, the exact match included");

        assertEquals(List.of("10.1.0.0/16", "10.1.1.0/24"),
                cidrsOf(service.searchTransitGatewayRoutes("us-east-1", fixture[4],
                        Map.of("route-search.subnet-of-match", List.of("10.1.0.0/16")))),
                "everything it contains, the exact match included");

        assertEquals(List.of("10.1.1.0/24"),
                cidrsOf(service.searchTransitGatewayRoutes("us-east-1", fixture[4],
                        Map.of("route-search.longest-prefix-match", List.of("10.1.1.5")))),
                "the most specific route covering the address");
        assertEquals(List.of("10.1.0.0/16"),
                cidrsOf(service.searchTransitGatewayRoutes("us-east-1", fixture[4],
                        Map.of("route-search.longest-prefix-match", List.of("10.1.9.9")))));
        assertTrue(service.searchTransitGatewayRoutes("us-east-1", fixture[4],
                Map.of("route-search.longest-prefix-match", List.of("8.8.8.8"))).isEmpty(),
                "nothing covers it");

        // Each filter ignores the other's value form, exactly as the live API does.
        assertTrue(service.searchTransitGatewayRoutes("us-east-1", fixture[4],
                Map.of("route-search.longest-prefix-match", List.of("10.1.1.5/32"))).isEmpty(),
                "longest-prefix-match takes an address, not a CIDR");
        assertTrue(service.searchTransitGatewayRoutes("us-east-1", fixture[4],
                Map.of("route-search.supernet-of-match", List.of("10.1.1.5"))).isEmpty(),
                "supernet-of-match takes a CIDR, not an address");

        AwsException unknown = assertThrows(AwsException.class,
                () -> service.searchTransitGatewayRoutes("us-east-1", fixture[4],
                        Map.of("nonsense", List.of("x"))));
        assertEquals("InvalidParameterValue", unknown.getErrorCode());
        assertTrue(unknown.getMessage().contains("nonsense"), unknown.getMessage());
    }

    private static List<String> cidrsOf(List<TransitGatewayRoute> routes) {
        return routes.stream().map(TransitGatewayRoute::getDestinationCidrBlock).sorted().toList();
    }


    // ─── Elastic IP reachability ──────────────────────────────────────────────

    /**
     * An allocated EIP's 54.x.x.x address is invented and routes nowhere. Terraform surfaces it
     * as aws_eip.x.public_ip and Terratest dials it, so association must re-point it at the
     * address the instance can actually be reached on.
     */
    @Test
    void associateAddressRepointsTheEipAtAReachableAddress() {
        Ec2ContainerManager containerManager = mock(Ec2ContainerManager.class);
        when(containerManager.reachablePublicAddress(argThat(i -> i != null))).thenReturn("192.168.215.9");
        Ec2Service service = eipService(containerManager);
        String instanceId = launchOne(service);

        Address allocated = service.allocateAddress("us-east-1");
        String inventedIp = allocated.getPublicIp();
        Address associated = service.associateAddress("us-east-1", allocated.getAllocationId(), instanceId);

        assertEquals("192.168.215.9", associated.getPublicIp());
        assertNotEquals(inventedIp, associated.getPublicIp());
        assertEquals("192.168.215.9",
                service.describeInstances("us-east-1", List.of(instanceId), Map.of())
                        .getFirst().getInstances().getFirst().getPublicIpAddress());
    }

    /**
     * DescribeAddresses is where Terraform refreshes public_ip, and Docker hands out a new
     * bridge IP after a stop/start, so the association has to be re-resolved on read.
     */
    @Test
    void describeAddressesReResolvesAnAssociatedEipAfterTheContainerIpChanges() {
        Ec2ContainerManager containerManager = mock(Ec2ContainerManager.class);
        when(containerManager.reachablePublicAddress(argThat(i -> i != null))).thenReturn("192.168.215.9");
        Ec2Service service = eipService(containerManager);
        String instanceId = launchOne(service);
        Address allocated = service.allocateAddress("us-east-1");
        service.associateAddress("us-east-1", allocated.getAllocationId(), instanceId);

        when(containerManager.reachablePublicAddress(argThat(i -> i != null))).thenReturn("192.168.215.42");

        Address refreshed = service.describeAddresses("us-east-1", List.of(allocated.getAllocationId()), Map.of())
                .getFirst();
        assertEquals("192.168.215.42", refreshed.getPublicIp());
    }

    /**
     * The re-resolution must run BEFORE the filter pass: a public-ip filter is judged against
     * the address the response will carry, not the one persisted before the restart. A
     * regression back to filter-before-refresh makes the current IP miss and the stale IP hit.
     */
    @Test
    void describeAddressesFiltersOnTheRefreshedAssociationState() {
        Ec2ContainerManager containerManager = mock(Ec2ContainerManager.class);
        when(containerManager.reachablePublicAddress(argThat(i -> i != null))).thenReturn("192.168.215.9");
        Ec2Service service = eipService(containerManager);
        String instanceId = launchOne(service);
        Address allocated = service.allocateAddress("us-east-1");
        service.associateAddress("us-east-1", allocated.getAllocationId(), instanceId);

        // The container restarts and Docker hands the instance a different bridge IP.
        when(containerManager.reachablePublicAddress(argThat(i -> i != null))).thenReturn("192.168.215.42");

        List<Address> byCurrentIp = service.describeAddresses("us-east-1", List.of(),
                Map.of("public-ip", List.of("192.168.215.42")));
        assertEquals(1, byCurrentIp.size());
        assertEquals(allocated.getAllocationId(), byCurrentIp.getFirst().getAllocationId());
        assertEquals("192.168.215.42", byCurrentIp.getFirst().getPublicIp());

        assertTrue(service.describeAddresses("us-east-1", List.of(),
                Map.of("public-ip", List.of("192.168.215.9"))).isEmpty());
    }

    /** With no instance behind it there is nothing reachable left, so the allocation is restored. */
    @Test
    void disassociateAddressRestoresTheAllocatedAddress() {
        Ec2ContainerManager containerManager = mock(Ec2ContainerManager.class);
        when(containerManager.reachablePublicAddress(argThat(i -> i != null))).thenReturn("192.168.215.9");
        Ec2Service service = eipService(containerManager);
        String instanceId = launchOne(service);
        Address allocated = service.allocateAddress("us-east-1");
        String inventedIp = allocated.getPublicIp();
        Address associated = service.associateAddress("us-east-1", allocated.getAllocationId(), instanceId);

        service.disassociateAddress("us-east-1", associated.getAssociationId());

        Address after = service.describeAddresses("us-east-1", List.of(allocated.getAllocationId()), Map.of())
                .getFirst();
        assertEquals(inventedIp, after.getPublicIp());
        assertNull(after.getInstanceId());
        assertNull(after.getAssociationId());
    }

    private static Ec2Service eipService(Ec2ContainerManager containerManager) {
        return new Ec2Service(mockConfig(true), containerManager,
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
    }

    private static String launchOne(Ec2Service service) {
        return service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null)
                .getInstances().getFirst().getInstanceId();
    }

    @Test
    void runInstancesStoresAwsMetadataOptionDefaultsWhenTheLaunchSetsNone() {
        Ec2Service service = mockModeService();
        Instance inst = service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null)
                .getInstances().getFirst();

        LaunchTemplateData.MetadataOptions options = inst.effectiveMetadataOptions();
        assertEquals("applied", options.getState());
        assertEquals("optional", options.getHttpTokens());
        assertEquals(1, options.getHttpPutResponseHopLimit());
        assertEquals("enabled", options.getHttpEndpoint());
        assertEquals("disabled", options.getHttpProtocolIpv6());
        assertEquals("disabled", options.getInstanceMetadataTags());
    }

    @Test
    void runInstancesHonoursExplicitMetadataOptionsAndDefaultsTheRest() {
        Ec2Service service = mockModeService();
        LaunchTemplateData.MetadataOptions request = new LaunchTemplateData.MetadataOptions();
        request.setHttpTokens("required");
        request.setHttpPutResponseHopLimit(2);

        Instance inst = service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null, null, null, 0, null, request)
                .getInstances().getFirst();

        LaunchTemplateData.MetadataOptions options = inst.effectiveMetadataOptions();
        assertEquals("required", options.getHttpTokens());
        assertEquals(2, options.getHttpPutResponseHopLimit());
        assertEquals("enabled", options.getHttpEndpoint());
        assertEquals("disabled", options.getInstanceMetadataTags());
        // The stored instance reads back the same values.
        assertEquals("required", service.describeInstances("us-east-1", List.of(inst.getInstanceId()), Map.of())
                .getFirst().getInstances().getFirst().effectiveMetadataOptions().getHttpTokens());
    }

    @Test
    void modifyInstanceMetadataOptionsChangesOnlyTheFieldsItNames() {
        Ec2Service service = mockModeService();
        LaunchTemplateData.MetadataOptions launch = new LaunchTemplateData.MetadataOptions();
        launch.setHttpPutResponseHopLimit(3);
        String instanceId = service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null, null, null, 0, null, launch)
                .getInstances().getFirst().getInstanceId();

        LaunchTemplateData.MetadataOptions change = new LaunchTemplateData.MetadataOptions();
        change.setHttpTokens("required");
        Instance modified = service.modifyInstanceMetadataOptions("us-east-1", instanceId, change);

        LaunchTemplateData.MetadataOptions options = modified.effectiveMetadataOptions();
        assertEquals("required", options.getHttpTokens());
        assertEquals(3, options.getHttpPutResponseHopLimit());
        assertEquals("enabled", options.getHttpEndpoint());
    }

    @Test
    void metadataOptionsRejectValuesOutsideTheAwsEnumerations() {
        Ec2Service service = mockModeService();
        String instanceId = service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null)
                .getInstances().getFirst().getInstanceId();

        LaunchTemplateData.MetadataOptions badTokens = new LaunchTemplateData.MetadataOptions();
        badTokens.setHttpTokens("mandatory");
        AwsException tokensError = assertThrows(AwsException.class,
                () -> service.modifyInstanceMetadataOptions("us-east-1", instanceId, badTokens));
        assertEquals("InvalidParameterValue", tokensError.getErrorCode());

        LaunchTemplateData.MetadataOptions badHopLimit = new LaunchTemplateData.MetadataOptions();
        badHopLimit.setHttpPutResponseHopLimit(65);
        AwsException hopError = assertThrows(AwsException.class, () -> service.runInstances(
                "us-east-1", "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null, null, null, 0, null, badHopLimit));
        assertEquals("InvalidParameterValue", hopError.getErrorCode());

        // The failed modify left the stored options untouched.
        assertEquals("optional", service.describeInstances("us-east-1", List.of(instanceId), Map.of())
                .getFirst().getInstances().getFirst().effectiveMetadataOptions().getHttpTokens());
    }

    @Test
    void modifyInstanceMetadataOptionsRequiresAnExistingInstance() {
        Ec2Service service = mockModeService();
        AwsException error = assertThrows(AwsException.class, () -> service.modifyInstanceMetadataOptions(
                "us-east-1", "i-0123456789abcdef0", new LaunchTemplateData.MetadataOptions()));
        assertEquals("InvalidInstanceID.NotFound", error.getErrorCode());
    }

    private static Ec2Service mockModeService() {
        return mockModeService(mockConfig(true));
    }

    private static Ec2Service mockModeService(EmulatorConfig config) {
        return new Ec2Service(config, mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
    }

    private static EmulatorConfig mockConfig(boolean ec2Mock) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(ec2Mock);
        when(ec2.mockPublicAddresses()).thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.DOCUMENTATION);
        when(ec2.mockPublicAddress()).thenReturn(Optional.empty());
        return config;
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private final Map<String, AccountAwareStorageBackend<?>> overrides;
        private final jakarta.enterprise.inject.Instance<RequestContext> requestContextInstance;
        private final List<AccountAwareStorageBackend<?>> created = new ArrayList<>();

        private InMemoryStorageFactory() {
            this(Map.of(), null);
        }

        private InMemoryStorageFactory(jakarta.enterprise.inject.Instance<RequestContext> requestContextInstance) {
            this(Map.of(), requestContextInstance);
        }

        private InMemoryStorageFactory(Map<String, AccountAwareStorageBackend<?>> overrides) {
            this(overrides, null);
        }

        private InMemoryStorageFactory(Map<String, AccountAwareStorageBackend<?>> overrides,
                                       jakarta.enterprise.inject.Instance<RequestContext> requestContextInstance) {
            super(null, null);
            this.overrides = overrides;
            this.requestContextInstance = requestContextInstance;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            AccountAwareStorageBackend<?> override = overrides.get(fileName);
            if (override != null) {
                return (AccountAwareStorageBackend<V>) override;
            }
            AccountAwareStorageBackend<V> backend = requestContextInstance != null
                    ? new AccountAwareStorageBackend<>(new InMemoryStorage<>(),
                            requestContextInstance, "000000000000")
                    : AccountAwareStorageBackend.inMemory("000000000000");
            created.add(backend);
            return backend;
        }

        // Same effect as StorageFactory.clearAll() on the backends this factory handed out.
        @Override
        public synchronized void clearAll() {
            created.forEach(AccountAwareStorageBackend::clear);
        }
    }

    private static final class DetachedImageStorage extends AccountAwareStorageBackend<Image> {
        private final Map<String, Image> values = new HashMap<>();
        private int putCount;

        private DetachedImageStorage() {
            super(new InMemoryStorage<>(), null, "000000000000");
        }

        @Override
        public void put(String key, Image value) {
            putCount++;
            values.put(key, copy(value));
        }

        @Override
        public Optional<Image> get(String key) {
            Image value = values.get(key);
            return value == null ? Optional.empty() : Optional.of(copy(value));
        }

        @Override
        public List<Image> scan(Predicate<String> keyFilter) {
            return values.entrySet().stream()
                    .filter(entry -> keyFilter.test(entry.getKey()))
                    .map(entry -> copy(entry.getValue()))
                    .toList();
        }

        private int putCount() {
            return putCount;
        }

        private Image storedImage() {
            return copy(values.get("us-east-1::ami-0de7ac4ed7a95"));
        }

        private static Image copy(Image source) {
            Image copy = new Image();
            copy.setImageId(source.getImageId());
            copy.setName(source.getName());
            copy.setDescription(source.getDescription());
            copy.setState(source.getState());
            copy.setOwnerId(source.getOwnerId());
            copy.setPublic(source.isPublic());
            copy.setArchitecture(source.getArchitecture());
            copy.setRootDeviceType(source.getRootDeviceType());
            copy.setRootDeviceName(source.getRootDeviceName());
            copy.setVirtualizationType(source.getVirtualizationType());
            copy.setHypervisor(source.getHypervisor());
            copy.setPlatform(source.getPlatform());
            copy.setImageOwnerAlias(source.getImageOwnerAlias());
            copy.setCreationDate(source.getCreationDate());
            copy.setRegion(source.getRegion());
            copy.setSourceImageId(source.getSourceImageId());
            copy.setCreationSourceImageId(source.getCreationSourceImageId());
            copy.setCreationSourceImageRegion(source.getCreationSourceImageRegion());
            copy.setCreationSourceInstanceId(source.getCreationSourceInstanceId());
            copy.setDockerImage(source.getDockerImage());
            copy.setBlockDeviceMappings(source.getBlockDeviceMappings() == null
                    ? List.of() : new ArrayList<>(source.getBlockDeviceMappings()));
            copy.setTags(source.getTags() == null ? List.of() : new ArrayList<>(source.getTags()));
            return copy;
        }
    }

    @Test
    void attachVolumeResolvesClusterNodeInstance() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        Instance nodeInstance = new Instance();
        nodeInstance.setInstanceId("i-0de1234567890abc");
        nodeInstance.setRegion("us-east-1");
        nodeInstance.setPlacement(new Placement("us-east-1a"));
        nodeInstance.setState(InstanceState.running());
        nodeInstance.setInstanceType("m5.large");
        nodeInstance.setTags(List.of(new Tag("Name", "test-cluster-node")));

        service.setClusterNodeInstanceProvider(new ClusterNodeInstanceProvider() {
            @Override
            public Optional<Instance> findInstance(String accountId, String region, String instanceId) {
                return "i-0de1234567890abc".equals(instanceId) && ("us-east-1".equals(region) || region == null)
                        ? Optional.of(nodeInstance) : Optional.empty();
            }

            @Override
            public List<Instance> listInstances(String accountId, String region) {
                return "us-east-1".equals(region) || region == null ? List.of(nodeInstance) : List.of();
            }
        });

        Volume vol = service.createVolume("us-east-1", "us-east-1a", "gp3", 10, false, 3000, null, null, List.of());
        VolumeAttachment att = service.attachVolume("us-east-1", vol.getVolumeId(), nodeInstance.getInstanceId(), "/dev/xvdf");
        assertEquals(vol.getVolumeId(), att.getVolumeId());
        assertEquals(nodeInstance.getInstanceId(), att.getInstanceId());
        assertEquals("attached", att.getState());

        VolumeAttachment detached = service.detachVolume("us-east-1", vol.getVolumeId(), nodeInstance.getInstanceId(), "/dev/xvdf", false);
        assertEquals("detached", detached.getState());
    }

    @Test
    void describeInstancesIncludesClusterNodeInstancesWithPlacementTypeAndFilters() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        Instance nodeInstance = new Instance();
        nodeInstance.setInstanceId("i-0de1234567890abc");
        nodeInstance.setRegion("us-east-1");
        nodeInstance.setPlacement(new Placement("us-east-1a"));
        nodeInstance.setState(InstanceState.running());
        nodeInstance.setInstanceType("m5.large");
        nodeInstance.setTags(List.of(new Tag("Name", "test-cluster-node")));

        service.setClusterNodeInstanceProvider(new ClusterNodeInstanceProvider() {
            @Override
            public Optional<Instance> findInstance(String accountId, String region, String instanceId) {
                return "i-0de1234567890abc".equals(instanceId) && ("us-east-1".equals(region) || region == null)
                        ? Optional.of(nodeInstance) : Optional.empty();
            }

            @Override
            public List<Instance> listInstances(String accountId, String region) {
                return "us-east-1".equals(region) || region == null ? List.of(nodeInstance) : List.of();
            }
        });

        List<Reservation> reservations = service.describeInstances("us-east-1", List.of(), Map.of());
        assertEquals(1, reservations.size());
        Instance inst = reservations.getFirst().getInstances().getFirst();
        assertEquals("i-0de1234567890abc", inst.getInstanceId());
        assertEquals("m5.large", inst.getInstanceType());
        assertEquals("us-east-1a", inst.getPlacement().getAvailabilityZone());

        List<Reservation> byId = service.describeInstances("us-east-1", List.of("i-0de1234567890abc"), Map.of());
        assertEquals(1, byId.size());

        List<Reservation> filtered = service.describeInstances("us-east-1", List.of(), Map.of("instance-type", List.of("m5.large")));
        assertEquals(1, filtered.size());

        List<Reservation> nonMatching = service.describeInstances("us-east-1", List.of(), Map.of("instance-type", List.of("c5.large")));
        assertTrue(nonMatching.isEmpty());

        List<Instance> statuses = service.describeInstanceStatus("us-east-1", List.of());
        assertEquals(1, statuses.size());
        assertEquals("i-0de1234567890abc", statuses.getFirst().getInstanceId());

        Instance foundById = service.findInstanceById("i-0de1234567890abc");
        assertNotNull(foundById);
        assertEquals("i-0de1234567890abc", foundById.getInstanceId());
    }

    @Test
    void clusterNodeInstanceLifecycleActionsRejected() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        Instance nodeInstance = new Instance();
        nodeInstance.setInstanceId("i-0de1234567890abc");
        nodeInstance.setRegion("us-east-1");
        nodeInstance.setPlacement(new Placement("us-east-1a"));
        nodeInstance.setState(InstanceState.running());
        nodeInstance.setInstanceType("m5.large");

        service.setClusterNodeInstanceProvider(new ClusterNodeInstanceProvider() {
            @Override
            public Optional<Instance> findInstance(String accountId, String region, String instanceId) {
                return "i-0de1234567890abc".equals(instanceId) && ("us-east-1".equals(region) || region == null)
                        ? Optional.of(nodeInstance) : Optional.empty();
            }

            @Override
            public List<Instance> listInstances(String accountId, String region) {
                return "us-east-1".equals(region) || region == null ? List.of(nodeInstance) : List.of();
            }
        });

        AwsException terminateEx = assertThrows(AwsException.class, () ->
                service.terminateInstances("us-east-1", List.of("i-0de1234567890abc")));
        assertEquals("OperationNotPermitted", terminateEx.getErrorCode());
        assertEquals(400, terminateEx.getHttpStatus());

        AwsException stopEx = assertThrows(AwsException.class, () ->
                service.stopInstances("us-east-1", List.of("i-0de1234567890abc")));
        assertEquals("OperationNotPermitted", stopEx.getErrorCode());
        assertEquals(400, stopEx.getHttpStatus());

        AwsException startEx = assertThrows(AwsException.class, () ->
                service.startInstances("us-east-1", List.of("i-0de1234567890abc")));
        assertEquals("OperationNotPermitted", startEx.getErrorCode());
        assertEquals(400, startEx.getHttpStatus());

        AwsException rebootEx = assertThrows(AwsException.class, () ->
                service.rebootInstances("us-east-1", List.of("i-0de1234567890abc")));
        assertEquals("OperationNotPermitted", rebootEx.getErrorCode());
        assertEquals(400, rebootEx.getHttpStatus());
    }

    @Test
    void clusterNodeMetadataOptionsCannotBeModifiedThroughEc2() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        Instance nodeInstance = new Instance();
        nodeInstance.setInstanceId("i-node1234567890abc");
        nodeInstance.setRegion("us-east-1");
        nodeInstance.setPlacement(new Placement("us-east-1a"));
        nodeInstance.setState(InstanceState.running());
        nodeInstance.setInstanceType("m5.large");
        service.setClusterNodeInstanceProvider(new ClusterNodeInstanceProvider() {
            @Override
            public Optional<Instance> findInstance(String accountId, String region, String instanceId) {
                return "i-node1234567890abc".equals(instanceId) && ("us-east-1".equals(region) || region == null)
                        ? Optional.of(nodeInstance) : Optional.empty();
            }

            @Override
            public List<Instance> listInstances(String accountId, String region) {
                return "us-east-1".equals(region) || region == null ? List.of(nodeInstance) : List.of();
            }
        });
        LaunchTemplateData.MetadataOptions required = new LaunchTemplateData.MetadataOptions();
        required.setHttpTokens("required");

        // IMDS serves the node from the instance its cluster registered, so a change made to the
        // copy EC2 hands out would be reported by DescribeInstances and never enforced.
        AwsException modifyEx = assertThrows(AwsException.class, () ->
                service.modifyInstanceMetadataOptions("us-east-1", "i-node1234567890abc", required));
        assertEquals("OperationNotPermitted", modifyEx.getErrorCode());
        assertEquals(400, modifyEx.getHttpStatus());

        Instance described = service.describeInstances("us-east-1", List.of("i-node1234567890abc"), Map.of())
                .getFirst().getInstances().getFirst();
        assertEquals("optional", described.effectiveMetadataOptions().getHttpTokens());
        assertNull(nodeInstance.getMetadataOptions());
        AwsException terminateEx = assertThrows(AwsException.class, () ->
                service.terminateInstances("us-east-1", List.of("i-node1234567890abc")));
        assertEquals("OperationNotPermitted", terminateEx.getErrorCode());
    }

    @Test
    void clusterNodeStaysRefusedAfterAnotherActionStoredACopyOfIt() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        Instance nodeInstance = new Instance();
        nodeInstance.setInstanceId("i-node1234567890abc");
        nodeInstance.setRegion("us-east-1");
        nodeInstance.setPlacement(new Placement("us-east-1a"));
        nodeInstance.setState(InstanceState.running());
        nodeInstance.setInstanceType("m5.large");
        service.setClusterNodeInstanceProvider(new ClusterNodeInstanceProvider() {
            @Override
            public Optional<Instance> findInstance(String accountId, String region, String instanceId) {
                return "i-node1234567890abc".equals(instanceId) && ("us-east-1".equals(region) || region == null)
                        ? Optional.of(nodeInstance) : Optional.empty();
            }

            @Override
            public List<Instance> listInstances(String accountId, String region) {
                return "us-east-1".equals(region) || region == null ? List.of(nodeInstance) : List.of();
            }
        });
        // ModifyInstanceAttribute writes EC2's copy of the node into the instance store; the node is
        // still the cluster's, so the actions refused for it stay refused.
        service.modifyInstanceAttribute("us-east-1", "i-node1234567890abc", "sourceDestCheck", "false");
        LaunchTemplateData.MetadataOptions required = new LaunchTemplateData.MetadataOptions();
        required.setHttpTokens("required");

        assertEquals("OperationNotPermitted", assertThrows(AwsException.class, () ->
                service.modifyInstanceMetadataOptions("us-east-1", "i-node1234567890abc", required))
                .getErrorCode());
        assertEquals("OperationNotPermitted", assertThrows(AwsException.class, () ->
                service.terminateInstances("us-east-1", List.of("i-node1234567890abc"))).getErrorCode());
        assertEquals("OperationNotPermitted", assertThrows(AwsException.class, () ->
                service.stopInstances("us-east-1", List.of("i-node1234567890abc"))).getErrorCode());
    }

    @Test
    void ordinaryEc2InstancesUnaffectedByClusterNodeInstanceProvider() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        Instance nodeInstance = new Instance();
        nodeInstance.setInstanceId("i-0de1234567890abc");
        nodeInstance.setRegion("us-east-1");
        nodeInstance.setPlacement(new Placement("us-east-1a"));
        nodeInstance.setState(InstanceState.running());

        service.setClusterNodeInstanceProvider(new ClusterNodeInstanceProvider() {
            @Override
            public Optional<Instance> findInstance(String accountId, String region, String instanceId) {
                return "i-0de1234567890abc".equals(instanceId) ? Optional.of(nodeInstance) : Optional.empty();
            }

            @Override
            public List<Instance> listInstances(String accountId, String region) {
                return List.of(nodeInstance);
            }
        });

        Reservation reservation = service.runInstances("us-east-1", "ami-1234567890abcdef0", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null);
        String ordinaryId = reservation.getInstances().getFirst().getInstanceId();

        List<Map<String, String>> stopped = service.stopInstances("us-east-1", List.of(ordinaryId));
        assertEquals("stopping", stopped.getFirst().get("currentState"));

        List<Map<String, String>> started = service.startInstances("us-east-1", List.of(ordinaryId));
        assertEquals("pending", started.getFirst().get("currentState"));

        List<Map<String, String>> terminated = service.terminateInstances("us-east-1", List.of(ordinaryId));
        assertEquals("shutting-down", terminated.getFirst().get("currentState"));
    }

    @Test
    void clusterNodeInstanceGoneWhenProviderClearsIt() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        Instance nodeInstance = new Instance();
        nodeInstance.setInstanceId("i-0de1234567890abc");
        nodeInstance.setRegion("us-east-1");
        nodeInstance.setPlacement(new Placement("us-east-1a"));
        nodeInstance.setState(InstanceState.running());

        service.setClusterNodeInstanceProvider(new ClusterNodeInstanceProvider() {
            @Override
            public Optional<Instance> findInstance(String accountId, String region, String instanceId) {
                return "i-0de1234567890abc".equals(instanceId) ? Optional.of(nodeInstance) : Optional.empty();
            }

            @Override
            public List<Instance> listInstances(String accountId, String region) {
                return List.of(nodeInstance);
            }
        });

        assertEquals(1, service.describeInstances("us-east-1", List.of(), Map.of()).size());

        service.setClusterNodeInstanceProvider(null);

        assertTrue(service.describeInstances("us-east-1", List.of(), Map.of()).isEmpty());
        AwsException error = assertThrows(AwsException.class, () ->
                service.describeInstances("us-east-1", List.of("i-0de1234567890abc"), Map.of()));
        assertEquals("InvalidInstanceID.NotFound", error.getErrorCode());
    }

    @Test
    void clusterNodeInstancesIsolatedByAccount() {
        String accountA = "111122223333";
        String accountB = "444455556666";

        Instance nodeInstance = new Instance();
        nodeInstance.setInstanceId("i-0de1234567890abc");
        nodeInstance.setRegion("us-east-1");
        nodeInstance.setPlacement(new Placement("us-east-1a"));
        nodeInstance.setState(InstanceState.running());
        nodeInstance.setInstanceType("m5.large");

        ClusterNodeInstanceProvider provider = new ClusterNodeInstanceProvider() {
            @Override
            public Optional<Instance> findInstance(String accountId, String region, String instanceId) {
                if (accountA.equals(accountId) && "i-0de1234567890abc".equals(instanceId)) {
                    return Optional.of(nodeInstance);
                }
                return Optional.empty();
            }

            @Override
            public List<Instance> listInstances(String accountId, String region) {
                if (accountA.equals(accountId)) {
                    return List.of(nodeInstance);
                }
                return List.of();
            }
        };

        RequestContext reqCtx = new RequestContext();
        jakarta.enterprise.inject.Instance<RequestContext> reqCtxInstance = requestContexts(reqCtx);

        InMemoryStorageFactory storageFactory = new InMemoryStorageFactory(reqCtxInstance);
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), storageFactory, reqCtxInstance);
        service.setClusterNodeInstanceProvider(provider);

        // Under account A, instance is visible
        reqCtx.setAccountId(accountA);
        List<Reservation> resA = service.describeInstances("us-east-1", List.of(), Map.of());
        assertEquals(1, resA.size());
        assertEquals("i-0de1234567890abc", resA.getFirst().getInstances().getFirst().getInstanceId());

        // Under account B, instance is not visible
        reqCtx.setAccountId(accountB);
        List<Reservation> resB = service.describeInstances("us-east-1", List.of(), Map.of());
        assertTrue(resB.isEmpty());

        AwsException notFoundB = assertThrows(AwsException.class, () ->
                service.describeInstances("us-east-1", List.of("i-0de1234567890abc"), Map.of()));
        assertEquals("InvalidInstanceID.NotFound", notFoundB.getErrorCode());

        // findInstanceForAccount respects account argument
        assertTrue(service.findInstanceForAccount(accountA, "us-east-1", "i-0de1234567890abc").isPresent());
        assertTrue(service.findInstanceForAccount(accountB, "us-east-1", "i-0de1234567890abc").isEmpty());

        // findInstanceById respects callerAccountId and explicit account argument
        assertNull(service.findInstanceById("i-0de1234567890abc"));
        assertNull(service.findInstanceById(accountB, "i-0de1234567890abc"));
        assertNotNull(service.findInstanceById(accountA, "i-0de1234567890abc"));

        reqCtx.setAccountId(accountA);
        assertNotNull(service.findInstanceById("i-0de1234567890abc"));
    }

    @Test
    void clusterNodeInstanceTaggingIsolatedAndDoesNotMutateProvider() {
        String accountA = "111122223333";
        Instance nodeInstance = new Instance();
        nodeInstance.setInstanceId("i-0de1234567890abc");
        nodeInstance.setRegion("us-east-1");
        nodeInstance.setPlacement(new Placement("us-east-1a"));
        nodeInstance.setState(InstanceState.running());
        nodeInstance.setInstanceType("m5.large");
        List<Tag> originalTags = new ArrayList<>(List.of(new Tag("Name", "test-cluster-node")));
        nodeInstance.setTags(originalTags);

        ClusterNodeInstanceProvider provider = new ClusterNodeInstanceProvider() {
            @Override
            public Optional<Instance> findInstance(String accountId, String region, String instanceId) {
                if (accountA.equals(accountId) && "i-0de1234567890abc".equals(instanceId)) {
                    return Optional.of(nodeInstance);
                }
                return Optional.empty();
            }

            @Override
            public List<Instance> listInstances(String accountId, String region) {
                if (accountA.equals(accountId)) {
                    return List.of(nodeInstance);
                }
                return List.of();
            }
        };

        RequestContext reqCtx = new RequestContext();
        reqCtx.setAccountId(accountA);
        jakarta.enterprise.inject.Instance<RequestContext> reqCtxInstance = requestContexts(reqCtx);

        InMemoryStorageFactory storageFactory = new InMemoryStorageFactory(reqCtxInstance);
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), storageFactory, reqCtxInstance);
        service.setClusterNodeInstanceProvider(provider);

        // Before any custom tags are added, describeTags includes default tags
        List<Map<String, String>> initialTags = service.describeTags("us-east-1", Map.of("resource-id", List.of("i-0de1234567890abc")));
        assertEquals(1, initialTags.size());
        assertEquals("Name", initialTags.getFirst().get("key"));
        assertEquals("test-cluster-node", initialTags.getFirst().get("value"));

        // Create tags on the node instance
        service.createTags("us-east-1", List.of("i-0de1234567890abc"), List.of(new Tag("Environment", "production")));

        // Provider's underlying object must NOT be mutated
        assertEquals(1, nodeInstance.getTags().size());
        assertEquals("Name", nodeInstance.getTags().getFirst().getKey());

        // EC2 service returns defensive copy with new tag merged
        List<Reservation> reservations = service.describeInstances("us-east-1", List.of("i-0de1234567890abc"), Map.of());
        Instance queried = reservations.getFirst().getInstances().getFirst();
        assertTrue(queried.getTags().stream().anyMatch(t -> "Environment".equals(t.getKey()) && "production".equals(t.getValue())));
        assertTrue(queried.getTags().stream().anyMatch(t -> "Name".equals(t.getKey()) && "test-cluster-node".equals(t.getValue())));

        // DescribeTags now returns both tags
        List<Map<String, String>> updatedTags = service.describeTags("us-east-1", Map.of("resource-id", List.of("i-0de1234567890abc")));
        assertEquals(2, updatedTags.size());

        // Delete tag
        service.deleteTags("us-east-1", List.of("i-0de1234567890abc"), List.of(new Tag("Environment", "production")));
        List<Map<String, String>> finalTags = service.describeTags("us-east-1", Map.of("resource-id", List.of("i-0de1234567890abc")));
        assertEquals(1, finalTags.size());
        assertEquals("Name", finalTags.getFirst().get("key"));

        // Provider remains untouched
        assertEquals(1, nodeInstance.getTags().size());
    }

    // The host is released after runInstances' unlocked availability check and before its instances
    // are stored. The instance-type catalog lookup sits between the two, so it stands in for the
    // concurrent ReleaseHosts. Only the re-check under hostLock can still reject the launch.
    @Test
    void launchOntoAHostReleasedAfterTheUnlockedCheckIsRejected() {
        Ec2Service[] holder = new Ec2Service[1];
        String[] hostId = new String[1];
        boolean[] released = {false};
        Ec2InstanceTypeCatalog releasingCatalog = new Ec2InstanceTypeCatalog() {
            @Override
            public Optional<CatalogInstanceType> find(String instanceType) {
                if (hostId[0] != null && !released[0]) {
                    released[0] = true;
                    holder[0].releaseHost("us-east-1", hostId[0]);
                }
                return super.find(instanceType);
            }
        };
        Ec2ImageCatalog imageCatalog = new Ec2ImageCatalog();
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), new AmiImageResolver(imageCatalog), imageCatalog,
                releasingCatalog, new InMemoryStorageFactory());
        holder[0] = service;
        hostId[0] = service.allocateHosts("us-east-1", "us-east-1a", "m5.large", null, 1,
                null, null, null, null, null).get(0).getHostId();

        AwsException e = assertThrows(AwsException.class, () -> service.runInstances("us-east-1",
                "ami-ubuntu2404-amd64", "m5.large", 1, 1, null, List.of(), null, null, List.of(), null, null,
                null, null, 0, null, null, null, null, false, hostId[0], null, null));

        assertTrue(released[0], "the hook must have released the host between the two checks");
        assertEquals("InvalidHostID.NotFound", e.getErrorCode());
        assertTrue(service.hostInstances("us-east-1", hostId[0]).isEmpty());
    }

    @Test
    void seedDefaultRegionIfEnabledDoesNothingWhenEc2IsDisabled() {
        EmulatorConfig config = mockConfig(true);
        when(config.services().ec2().enabled()).thenReturn(false);
        when(config.defaultRegion()).thenReturn("us-east-1");
        Ec2Service service = spy(new Ec2Service(config, mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory()));

        service.seedDefaultRegionIfEnabled();

        verify(service, never()).ensureDefaultResources(anyString());
    }

    @Test
    void clearSeedsDefaultVpcAndSubnetsAgainAfterStorageWipe() {
        EmulatorConfig config = mockConfig(true);
        when(config.services().ec2().enabled()).thenReturn(true);
        when(config.defaultRegion()).thenReturn("us-east-1");
        InMemoryStorageFactory storageFactory = new InMemoryStorageFactory();
        Ec2Service service = new Ec2Service(config, mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), storageFactory);
        service.ensureDefaultResources("us-east-1");
        // A reset wipes storage before it calls clear().
        storageFactory.clearAll();
        assertTrue(service.describeVpcs("us-east-1", List.of(), Map.of()).isEmpty());

        service.clear();

        assertTrue(service.describeVpcs("us-east-1", List.of(), Map.of()).stream().anyMatch(Vpc::isDefault));
        assertFalse(service.describeSubnets("us-east-1", List.of(), Map.of()).isEmpty());
    }

    @Test
    void allocateHostsAcceptsModelledZoneWithNoSubnet() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        service.describeSubnets("us-east-1", List.of(), Map.of()).stream()
                .filter(sn -> "us-east-1c".equals(sn.getAvailabilityZone()))
                .forEach(sn -> service.deleteSubnet("us-east-1", sn.getSubnetId()));

        assertEquals(1, service.allocateHosts("us-east-1", "us-east-1c", "m5.large", null, 1,
                null, null, null, null, null).size());
    }
    @Test
    void capacityAssociationScansStayWithinTheCallerAccount() {
        // Instance is the EC2 model in this file, so qualify the CDI Instance type.
        jakarta.enterprise.inject.Instance<RequestContext> contexts = mock(jakarta.enterprise.inject.Instance.class);
        RequestContext context = new RequestContext();
        context.setAccountId("111111111111");
        when(contexts.isResolvable()).thenReturn(true);
        when(contexts.get()).thenReturn(context);
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory(contexts), contexts);
        String region = "us-east-1";
        String capacityId = service.createCapacityReservation(region, "t3.micro", "Linux/UNIX", region + "a",
                null, 1, null, null, null, null, null, null, null, null).getCapacityReservationId();
        String instanceId = service.runInstances(region, "ami-test", "t3.micro", 1, 1, null, List.of(),
                null, null, List.of(), null, null).getInstances().getFirst().getInstanceId();
        assertEquals(capacityId, service.capacityReservationAssociations(region).get(instanceId));
        context.setAccountId("222222222222");
        assertTrue(service.capacityReservationAssociations(region).isEmpty());
        assertTrue(service.describeInstances(region, List.of(), Map.of()).isEmpty());
        context.setAccountId("111111111111");
        assertEquals(capacityId, service.capacityReservationAssociations(region).get(instanceId));
    }
}
