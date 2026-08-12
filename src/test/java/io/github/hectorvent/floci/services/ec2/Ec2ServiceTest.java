package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.services.ec2.model.BlockDeviceMapping;
import io.github.hectorvent.floci.services.ec2.model.EbsBlockDevice;
import io.github.hectorvent.floci.services.ec2.model.GroupIdentifier;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.ManagedPrefixList;
import io.github.hectorvent.floci.services.ec2.model.PrefixListEntry;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Snapshot;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.VpcEndpoint;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.model.VolumeAttachment;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
        InMemoryStorage<String, Snapshot> snapshotStore = new InMemoryStorage<>();
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
        private final Map<String, StorageBackend<String, ?>> overrides;

        private InMemoryStorageFactory() {
            this(Map.of());
        }

        private InMemoryStorageFactory(Map<String, StorageBackend<String, ?>> overrides) {
            super(null, null);
            this.overrides = overrides;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> StorageBackend<String, V> create(String serviceName, String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            StorageBackend<String, ?> override = overrides.get(fileName);
            if (override != null) {
                return (StorageBackend<String, V>) override;
            }
            return new InMemoryStorage<>();
        }
    }
}
