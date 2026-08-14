package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.ec2.model.Address;
import io.github.hectorvent.floci.services.ec2.model.BlockDeviceMapping;
import io.github.hectorvent.floci.services.ec2.model.EbsBlockDevice;
import io.github.hectorvent.floci.services.ec2.model.ManagedPrefixList;
import io.github.hectorvent.floci.services.ec2.model.PrefixListEntry;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InternetGateway;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import io.github.hectorvent.floci.services.ec2.model.Image;
import io.github.hectorvent.floci.services.ec2.model.NetworkAcl;
import io.github.hectorvent.floci.services.ec2.model.KeyPair;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.NatGateway;
import io.github.hectorvent.floci.services.ec2.model.RouteTable;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroupRule;
import io.github.hectorvent.floci.services.ec2.model.Snapshot;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.ec2.model.VpcEndpoint;
import io.github.hectorvent.floci.services.ec2.model.SpotInstanceRequest;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for issue #1297 (persistent-restart case). EC2 networking and instance metadata
 * must be persisted via StorageFactory so that the VPC/subnet ids CloudFormation exports survive a
 * Floci restart. Before the fix Ec2Service used plain in-memory maps, so after a restart the
 * persisted CloudFormation exports/stack referenced VPC/subnet ids that EC2 had lost
 * (describe-subnets returned [] and ELBv2 failed with SubnetNotFound).
 *
 * <p>This builds an Ec2Service over PersistentStorage in a temp dir, creates a VPC/subnet, then
 * builds a SECOND Ec2Service over the SAME files (simulating a process restart) and asserts the
 * resources are still visible.
 */
class Ec2ServicePersistenceTest {

    private static final String REGION = "us-east-1";

    @Test
    void vpcAndSubnetSurviveRestart(@TempDir Path dir) {
        Ec2Service first = newService(dir);
        Vpc vpc = first.createVpc(REGION, "10.0.0.0/16", false);
        Subnet subnet = first.createSubnet(REGION, vpc.getVpcId(), "10.0.1.0/24", REGION + "a");

        // A fresh service over the same persistent files = a restart with the same data dir.
        Ec2Service restarted = newService(dir);

        List<Vpc> vpcs = restarted.describeVpcs(REGION, List.of(vpc.getVpcId()), Map.of());
        assertEquals(1, vpcs.size(), "VPC must survive restart");
        assertEquals("10.0.0.0/16", vpcs.get(0).getCidrBlock());

        List<Subnet> subnets = restarted.describeSubnets(REGION, List.of(subnet.getSubnetId()), Map.of());
        assertEquals(1, subnets.size(), "Subnet must survive restart");
        assertEquals(vpc.getVpcId(), subnets.get(0).getVpcId());
        assertEquals("10.0.1.0/24", subnets.get(0).getCidrBlock());
    }

    @Test
    void registeredImageAndSnapshotSurviveRestart(@TempDir Path dir) {
        Ec2Service first = newService(dir);
        Image image = first.registerImage(REGION, "persisted-image", "persisted image", "x86_64",
                "/dev/sda1", List.of(blockDeviceMapping("snap-persisted", 12)));

        Ec2Service restarted = newService(dir);

        List<Image> images = restarted.describeImages(REGION, List.of(image.getImageId()), List.of(), Map.of());
        assertEquals(1, images.size(), "registered image must survive restart");
        assertEquals("persisted-image", images.getFirst().getName());
        assertEquals("snap-persisted",
                images.getFirst().getBlockDeviceMappings().getFirst().getEbs().getSnapshotId());

        List<Snapshot> snapshots = restarted.describeSnapshots(REGION, List.of("snap-persisted"), List.of(), Map.of());
        assertEquals(1, snapshots.size(), "linked snapshot must survive restart");
        assertEquals(12, snapshots.getFirst().getVolumeSize());
    }

    @Test
    void managedPrefixListAndItsVersionHistorySurviveRestart(@TempDir Path dir) {
        Ec2Service first = newService(dir);
        ManagedPrefixList created = first.createManagedPrefixList(REGION, "persisted-list", "IPv4", 5,
                List.of(new PrefixListEntry("10.0.0.0/8", "corporate")), List.of());
        first.modifyManagedPrefixList(REGION, created.getPrefixListId(), null, null, null,
                List.of(new PrefixListEntry("192.168.0.0/16", "lab")), List.of());

        Ec2Service restarted = newService(dir);

        List<ManagedPrefixList> lists =
                restarted.describeManagedPrefixLists(REGION, List.of(created.getPrefixListId()), Map.of());
        assertEquals(1, lists.size(), "managed prefix list must survive restart");
        assertEquals("persisted-list", lists.getFirst().getPrefixListName());
        assertEquals(2, lists.getFirst().getVersion());

        // Entry history is a nested map on the model, so a restart is the first place a broken
        // serialization round trip would show up.
        assertEquals(2,
                restarted.getManagedPrefixListEntries(REGION, created.getPrefixListId(), null).size());
        List<PrefixListEntry> firstVersion =
                restarted.getManagedPrefixListEntries(REGION, created.getPrefixListId(), 1L);
        assertEquals(1, firstVersion.size(), "earlier version must survive restart");
        assertEquals("corporate", firstVersion.getFirst().getDescription());
    }

    @Test
    void securityGroupRuleSourcesSurviveRestart(@TempDir Path dir) {
        Ec2Service first = newService(dir);
        Vpc vpc = first.createVpc(REGION, "10.0.0.0/16", false);
        SecurityGroup source = first.createSecurityGroup(REGION, "source-sg", "traffic source", vpc.getVpcId());
        SecurityGroup target = first.createSecurityGroup(REGION, "target-sg", "traffic target", vpc.getVpcId());

        IpPermission perm = new IpPermission();
        perm.setIpProtocol("tcp");
        perm.setFromPort(443);
        perm.setToPort(443);
        UserIdGroupPair pair = new UserIdGroupPair();
        pair.setGroupId(source.getGroupId());
        pair.setDescription("from-source-sg");
        perm.getUserIdGroupPairs().add(pair);
        first.authorizeSecurityGroupIngress(REGION, target.getGroupId(), List.of(perm));

        // A fresh service over the same persistent files = a restart with the same data dir. Note
        // PersistentStorage.load() quarantines a broken deserialization into an EMPTY store, so the
        // assertions below have to touch restored content or they would pass through that failure.
        Ec2Service restarted = newService(dir);

        SecurityGroup restoredGroup =
                restarted.describeSecurityGroups(REGION, List.of(target.getGroupId()), List.of(), Map.of()).getFirst();
        List<UserIdGroupPair> restoredPairs = restoredGroup.getIpPermissions().getFirst().getUserIdGroupPairs();
        assertEquals(1, restoredPairs.size(), "group reference must survive restart");
        assertEquals(source.getGroupId(), restoredPairs.getFirst().getGroupId());
        assertEquals("000000000000", restoredPairs.getFirst().getUserId());
        assertEquals("from-source-sg", restoredPairs.getFirst().getDescription());

        // referencedGroupInfo is a nested object on the flattened rule, so a restart is the first
        // place a broken serialization round trip would show up.
        List<SecurityGroupRule> ingress =
                restarted.describeSecurityGroupRules(REGION, List.of(target.getGroupId()), List.of()).stream()
                        .filter(r -> !r.isEgress())
                        .toList();
        assertEquals(1, ingress.size(), "one rule per source, and only the ingress rule here");
        assertEquals(source.getGroupId(), ingress.getFirst().getReferencedGroupInfo().getGroupId());
        assertEquals("000000000000", ingress.getFirst().getReferencedGroupInfo().getUserId());
        assertEquals("from-source-sg", ingress.getFirst().getDescription());
    }

    private BlockDeviceMapping blockDeviceMapping(String snapshotId, int volumeSize) {
        EbsBlockDevice ebs = new EbsBlockDevice();
        ebs.setSnapshotId(snapshotId);
        ebs.setVolumeSize(volumeSize);
        BlockDeviceMapping mapping = new BlockDeviceMapping();
        mapping.setDeviceName("/dev/sda1");
        mapping.setEbs(ebs);
        return mapping;
    }

    private Ec2Service newService(Path dir) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(true);
        Ec2ImageCatalog imageCatalog = new Ec2ImageCatalog();
        return new Ec2Service(config, null, mock(Ec2PortForwardManager.class),
                new AmiImageResolver(imageCatalog), imageCatalog,
                new Ec2InstanceTypeCatalog(),
                load(dir, "ec2-vpcs.json", new TypeReference<Map<String, Vpc>>() {}),
                load(dir, "ec2-subnets.json", new TypeReference<Map<String, Subnet>>() {}),
                load(dir, "ec2-security-groups.json", new TypeReference<Map<String, SecurityGroup>>() {}),
                load(dir, "ec2-security-group-rules.json", new TypeReference<Map<String, SecurityGroupRule>>() {}),
                load(dir, "ec2-internet-gateways.json", new TypeReference<Map<String, InternetGateway>>() {}),
                load(dir, "ec2-route-tables.json", new TypeReference<Map<String, RouteTable>>() {}),
                load(dir, "ec2-key-pairs.json", new TypeReference<Map<String, KeyPair>>() {}),
                load(dir, "ec2-addresses.json", new TypeReference<Map<String, Address>>() {}),
                load(dir, "ec2-instances.json", new TypeReference<Map<String, Instance>>() {}),
                load(dir, "ec2-volumes.json", new TypeReference<Map<String, Volume>>() {}),
                load(dir, "ec2-registered-images.json", new TypeReference<Map<String, Image>>() {}),
                load(dir, "ec2-snapshots.json", new TypeReference<Map<String, Snapshot>>() {}),
                load(dir, "ec2-launch-templates.json", new TypeReference<Map<String, LaunchTemplate>>() {}),
                load(dir, "ec2-vpc-endpoints.json", new TypeReference<Map<String, VpcEndpoint>>() {}),
                load(dir, "ec2-nat-gateways.json", new TypeReference<Map<String, NatGateway>>() {}),
                load(dir, "ec2-spot-instance-requests.json", new TypeReference<Map<String, SpotInstanceRequest>>() {}),
                load(dir, "ec2-network-acls.json", new TypeReference<Map<String, NetworkAcl>>() {}),
                load(dir, "ec2-managed-prefix-lists.json", new TypeReference<Map<String, ManagedPrefixList>>() {}),
                load(dir, "ec2-tags.json", new TypeReference<Map<String, List<Tag>>>() {}));
    }

    private <V> StorageBackend<String, V> load(Path dir, String file, TypeReference<Map<String, V>> type) {
        PersistentStorage<String, V> backend = new PersistentStorage<>(dir.resolve(file), type);
        backend.load();
        return backend;
    }
}
