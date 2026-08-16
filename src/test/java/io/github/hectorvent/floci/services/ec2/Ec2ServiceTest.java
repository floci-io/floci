package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.services.ec2.model.BlockDeviceMapping;
import io.github.hectorvent.floci.services.ec2.model.EbsBlockDevice;
import io.github.hectorvent.floci.services.ec2.model.GroupIdentifier;
import io.github.hectorvent.floci.services.ec2.model.Image;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.ManagedPrefixList;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroupRule;
import io.github.hectorvent.floci.services.ec2.model.PrefixListId;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.IpRange;
import io.github.hectorvent.floci.services.ec2.model.PrefixListEntry;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Snapshot;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.TransitGateway;
import io.github.hectorvent.floci.services.ec2.model.TransitGatewayOptions;
import io.github.hectorvent.floci.services.ec2.model.TransitGatewayRouteTable;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import io.github.hectorvent.floci.services.ec2.model.VpcEndpoint;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.model.VolumeAttachment;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Ec2ServiceTest {

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
    void runInstancesStoresArchitectureFromImageCatalog() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), new Ec2ImageCatalog(), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        Reservation reservation = service.runInstances("us-east-1", "ami-ubuntu2404-cloud-arm64", "t4g.medium",
                1, 1, null, List.of(), null, null, List.of(), null, null);

        assertEquals("arm64", reservation.getInstances().getFirst().getArchitecture());
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
    void launchTemplateVersionInheritsOmittedFieldsFromRequestedSourceVersion() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        LaunchTemplate template = service.createLaunchTemplate("us-east-1", "app-template",
                "ami-source", "t3.micro", "app-key", List.of("sg-source"),
                "source-user-data", "c291cmNlLXVzZXItZGF0YQ==",
                "arn:aws:iam::000000000000:instance-profile/app-profile",
                List.of(), List.of(new Tag("Role", "source")));

        service.createLaunchTemplateVersion("us-east-1", template.getLaunchTemplateId(), null,
                "1", null, "t3.small", null, List.of(), null, null, null, List.of());

        LaunchTemplate version = service.describeLaunchTemplateVersions(
                "us-east-1", template.getLaunchTemplateId(), null, List.of("2")).getFirst();
        assertEquals("ami-source", version.getImageId());
        assertEquals("t3.small", version.getInstanceType());
        assertEquals("app-key", version.getKeyName());
        assertEquals(List.of("sg-source"), version.getSecurityGroupIds());
        assertEquals("source-user-data", version.getUserData());
        assertEquals("c291cmNlLXVzZXItZGF0YQ==", version.getEncodedUserData());
        assertEquals("arn:aws:iam::000000000000:instance-profile/app-profile", version.getIamInstanceProfileArn());
        assertEquals("2", version.getLatestVersionNumber());
        assertEquals(1, version.getInstanceTags().size());
        assertEquals("Role", version.getInstanceTags().getFirst().getKey());
        assertEquals("source", version.getInstanceTags().getFirst().getValue());
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
    void endpointNetworkInterfacesSynthesizesStableEnisForInterfaceEndpoints() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        String subnetId = service.describeSubnets("us-east-1", List.of(),
                Map.of("vpc-id", List.of("vpc-default"))).getFirst().getSubnetId();
        VpcEndpoint endpoint = service.createVpcEndpoint("us-east-1", "vpc-default",
                "com.amazonaws.us-east-1.s3", "Interface",
                List.of(), List.of(subnetId), List.of(), null, List.of());
        service.createVpcEndpoint("us-east-1", "vpc-default",
                "com.amazonaws.us-east-1.dynamodb", "Gateway",
                List.of(), List.of(), List.of(), null, List.of());

        List<NetworkInterface> enis = service.endpointNetworkInterfaces("us-east-1");

        assertEquals(1, enis.size(), "only Interface endpoints have ENIs");
        NetworkInterface eni = enis.getFirst();
        assertEquals(subnetId, eni.getSubnetId());
        assertEquals("vpc-default", eni.getVpcId());
        assertEquals("VPC Endpoint Interface " + endpoint.getVpcEndpointId(), eni.getDescription());
        assertTrue(eni.getNetworkInterfaceId().startsWith("eni-"));

        NetworkInterface again = service.endpointNetworkInterfaces("us-east-1").getFirst();
        assertEquals(eni.getNetworkInterfaceId(), again.getNetworkInterfaceId());
        assertEquals(eni.getPrivateIpAddress(), again.getPrivateIpAddress());

        assertTrue(service.endpointNetworkInterfaces("eu-west-1").isEmpty(),
                "endpoints are regional");
    }

    @Test
    void modifyInstanceGroupsReassignsSecurityGroupsOnInstanceAndEni() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
        SecurityGroup web = service.createSecurityGroup("us-east-1", "web", "web sg", "vpc-default");
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
    void importKeyPairRejectsDuplicateKeyName() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        service.importKeyPair("us-east-1", "duplicate-key", "c3NoLXJzYSBBQUFB");

        AwsException error = assertThrows(AwsException.class,
                () -> service.importKeyPair("us-east-1", "duplicate-key", "c3NoLXJzYSBBQUFB"));
        assertEquals("InvalidKeyPair.Duplicate", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());

        // same name in another region is allowed
        service.importKeyPair("us-west-2", "duplicate-key", "c3NoLXJzYSBBQUFB");
    }

    @Test
    void importKeyPairRejectsNameAlreadyUsedByCreateKeyPair() {
        Ec2Service service = new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());

        service.createKeyPair("us-east-1", "shared-key-name");

        AwsException error = assertThrows(AwsException.class,
                () -> service.importKeyPair("us-east-1", "shared-key-name", "c3NoLXJzYSBBQUFB"));
        assertEquals("InvalidKeyPair.Duplicate", error.getErrorCode());
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
        Ec2ContainerManager containerManager = mock(Ec2ContainerManager.class);
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
        Ec2Service service = liveService(mock(Ec2ContainerManager.class), resolver);
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
    void createImageOnACatalogSourceCarriesItsRootDevice() {
        Ec2ImageCatalog catalog = mock(Ec2ImageCatalog.class);
        Ec2ImageCatalog.CatalogImage source = new Ec2ImageCatalog.CatalogImage();
        source.imageId = "ami-src";
        source.architecture = "x86_64";
        source.rootDeviceType = "ebs";
        source.rootDeviceName = "/dev/xvda";
        when(catalog.findByIdOrAlias("ami-src")).thenReturn(Optional.of(source));
        Ec2Service service = liveService(mock(Ec2ContainerManager.class), mock(AmiImageResolver.class), catalog);
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
                List.of(root.getEbs().getSnapshotId()), null, null);
        assertEquals(1, snapshots.size());
    }

    @Test
    void createImageTakesItsOwnSnapshotRatherThanTheSourceAmisOne() {
        Ec2Service service = liveService(mock(Ec2ContainerManager.class), mock(AmiImageResolver.class));
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

    private static String runOne(Ec2Service service, String imageId) {
        return service.runInstances("us-east-1", imageId, "t3.micro", 1, 1, null,
                List.of(), null, null, List.of(), null, null)
                .getInstances().getFirst().getInstanceId();
    }

    /** mock=false so the container-manager and resolver interactions actually happen. */
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

    // =========================================================================
    // Managed prefix lists
    // =========================================================================

    private static Ec2Service prefixListService() {
        return new Ec2Service(mockConfig(true), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
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

        var legacy = service.describePrefixLists("us-east-1", List.of(),
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

    private static EmulatorConfig mockConfig(boolean ec2Mock) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(ec2Mock);
        return config;
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private final Map<String, AccountAwareStorageBackend<?>> overrides;

        private InMemoryStorageFactory() {
            this(Map.of());
        }

        private InMemoryStorageFactory(Map<String, AccountAwareStorageBackend<?>> overrides) {
            super(null, null);
            this.overrides = overrides;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            AccountAwareStorageBackend<?> override = overrides.get(fileName);
            if (override != null) {
                return (AccountAwareStorageBackend<V>) override;
            }
            return AccountAwareStorageBackend.inMemory("000000000000");
        }
    }
}
