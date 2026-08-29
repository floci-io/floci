package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
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
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /**
     * Regression test for the RouteAlreadyExists/InvalidRoute.NotFound gap Greptile flagged on
     * the DestinationCidrBlock canonicalization fix: a route persisted BEFORE that fix stores its
     * destination in whatever spelling CreateRoute was originally called with (here
     * {@code 100.68.0.18/18} rather than the canonical {@code 100.68.0.0/18}). Without
     * canonicalizing on load, that legacy spelling never matches a newly-canonicalized incoming
     * destination under strict string comparison, so CreateRoute could create a duplicate,
     * ReplaceRoute could wrongly 404, and DeleteRoute could silently no-op.
     *
     * <p>This hand-writes a pre-fix-shaped {@code ec2-route-tables.json} directly (bypassing
     * {@link io.github.hectorvent.floci.services.ec2.model.Route}'s own setter, which now
     * canonicalizes) to simulate a file written by a pre-canonicalization Floci, then restarts
     * over it and asserts: (a) the reloaded route reads back canonical; (b) CreateRoute with an
     * equivalent-but-differently-spelled destination is detected as a duplicate rather than
     * creating a second route; (c) ReplaceRoute and DeleteRoute using the legacy, non-canonical
     * spelling still find the same route. Reverting the {@code Route.setDestinationCidrBlock}
     * change alone fails this test: (a) reads back {@code 100.68.0.18/18} instead of
     * {@code 100.68.0.0/18}, and (b)/(c) then also fail because CreateRoute/ReplaceRoute/
     * DeleteRoute never see the same string as the stored legacy route.
     */
    @Test
    void legacyNonCanonicalRouteDestinationCanonicalizesOnRestart(@TempDir Path dir) throws IOException {
        String routeTableId = "rtb-legacycidr01";
        String vpcId = "vpc-legacycidr01";
        String gatewayId = "igw-legacycidr01";
        String legacyJson = """
                {
                  "%s::%s": {
                    "routeTableId": "%s",
                    "vpcId": "%s",
                    "ownerId": "000000000000",
                    "region": "%s",
                    "routes": [
                      {
                        "destinationCidrBlock": "100.68.0.18/18",
                        "gatewayId": "%s",
                        "state": "active",
                        "origin": "CreateRoute"
                      }
                    ],
                    "associations": [],
                    "tags": []
                  }
                }
                """.formatted(REGION, routeTableId, routeTableId, vpcId, REGION, gatewayId);
        Files.writeString(dir.resolve("ec2-route-tables.json"), legacyJson);

        Ec2Service restarted = newService(dir);

        // (a) the loaded route's destination reads back canonical.
        RouteTable loaded =
                restarted.describeRouteTables(REGION, List.of(routeTableId), Map.of()).getFirst();
        assertEquals(1, loaded.getRoutes().size());
        assertEquals("100.68.0.0/18", loaded.getRoutes().getFirst().getDestinationCidrBlock(),
                "legacy route destination must be canonicalized on load");

        // (b) CreateRoute with an equivalent-but-differently-spelled destination is a duplicate,
        // not a new route, of the legacy one.
        AwsException duplicate = assertThrows(AwsException.class, () -> restarted.createRoute(
                REGION, routeTableId, "100.68.63.255/18", null, null, "igw-other00000000000", null, null));
        assertEquals("RouteAlreadyExists", duplicate.getErrorCode());

        // (c) ReplaceRoute against the legacy route's original, non-canonical spelling finds the
        // same route rather than throwing InvalidRoute.NotFound.
        restarted.replaceRoute(REGION, routeTableId, "100.68.0.18/18", null, null, "igw-replaced0000000", null);
        RouteTable afterReplace =
                restarted.describeRouteTables(REGION, List.of(routeTableId), Map.of()).getFirst();
        assertEquals(1, afterReplace.getRoutes().size(), "replace must update the existing route in place");
        assertEquals("igw-replaced0000000", afterReplace.getRoutes().getFirst().getGatewayId());
        assertEquals("100.68.0.0/18", afterReplace.getRoutes().getFirst().getDestinationCidrBlock());

        // (c) DeleteRoute against the same legacy spelling actually removes the route rather than
        // reporting success while leaving it behind.
        restarted.deleteRoute(REGION, routeTableId, "100.68.0.18/18", null, null);
        RouteTable afterDelete =
                restarted.describeRouteTables(REGION, List.of(routeTableId), Map.of()).getFirst();
        assertEquals(0, afterDelete.getRoutes().size(), "delete must actually remove the legacy route");
    }

    /**
     * Regression test for the pre-unified-data schema migration in LaunchTemplate /
     * LaunchTemplateData: a launch template persisted before that PR stored the IAM instance
     * profile as a bare {@code iamInstanceProfileArn} string and instance tags as a flat
     * {@code instanceTags} list, both directly on LaunchTemplate and, separately, on each entry
     * of its {@code versions} map. Without the legacy {@code @JsonSetter}s those two keys are
     * unrecognized by the current model and ignoreUnknown drops them, so a template that used to
     * carry an IAM profile and instance tags would come back from a restart with neither.
     *
     * <p>This writes a hand-built ec2-launch-templates.json in the pre-migration shape directly
     * (rather than producing it by running old code), then loads a fresh Ec2Service over it and
     * asserts the profile ARN and instance tag survive - covering both the versions-map entry
     * (the path DescribeLaunchTemplateVersions and AutoScaling actually read) and the top-level
     * fields (the path ensureLaunchTemplateVersions falls back to for a template with no
     * versions map at all, i.e. one only ever read via {@code getData()} pre-migration).
     */
    @Test
    void legacyIamProfileAndInstanceTagsSurviveRestart(@TempDir Path dir) throws java.io.IOException {
        String legacyJson = """
                {
                  "us-east-1::lt-legacy-versioned": {
                    "launchTemplateId": "lt-legacy-versioned",
                    "launchTemplateName": "legacy-versioned",
                    "defaultVersionNumber": "1",
                    "latestVersionNumber": "1",
                    "region": "us-east-1",
                    "iamInstanceProfileArn": "arn:aws:iam::000000000000:instance-profile/legacy-profile",
                    "instanceTags": [ { "key": "Name", "value": "legacy-versioned" } ],
                    "versions": {
                      "1": {
                        "imageId": "ami-legacy",
                        "instanceType": "t3.micro",
                        "iamInstanceProfileArn": "arn:aws:iam::000000000000:instance-profile/legacy-profile",
                        "instanceTags": [ { "key": "Name", "value": "legacy-versioned" } ]
                      }
                    }
                  },
                  "us-east-1::lt-legacy-no-versions": {
                    "launchTemplateId": "lt-legacy-no-versions",
                    "launchTemplateName": "legacy-no-versions",
                    "defaultVersionNumber": "1",
                    "latestVersionNumber": "1",
                    "region": "us-east-1",
                    "imageId": "ami-legacy",
                    "instanceType": "t3.micro",
                    "iamInstanceProfileArn": "arn:aws:iam::000000000000:instance-profile/legacy-profile",
                    "instanceTags": [ { "key": "Name", "value": "legacy-no-versions" } ],
                    "versions": {}
                  }
                }
                """;
        Files.writeString(dir.resolve("ec2-launch-templates.json"), legacyJson);

        Ec2Service restarted = newService(dir);

        LaunchTemplateData versioned = restarted
                .describeLaunchTemplateVersions(REGION, "lt-legacy-versioned", null, List.of())
                .getFirst()
                .getData();
        assertEquals("arn:aws:iam::000000000000:instance-profile/legacy-profile",
                restarted.iamInstanceProfileArn(versioned),
                "IAM instance profile on a legacy versions-map entry must survive restart");
        assertEquals(List.of("legacy-versioned"),
                versioned.getInstanceTags().stream().map(Tag::getValue).toList(),
                "instance tags on a legacy versions-map entry must survive restart");

        LaunchTemplateData noVersions = restarted
                .describeLaunchTemplateVersions(REGION, "lt-legacy-no-versions", null, List.of())
                .getFirst()
                .getData();
        assertEquals("arn:aws:iam::000000000000:instance-profile/legacy-profile",
                restarted.iamInstanceProfileArn(noVersions),
                "IAM instance profile on a legacy template with no versions map must survive restart");
        assertEquals(List.of("legacy-no-versions"),
                noVersions.getInstanceTags().stream().map(Tag::getValue).toList(),
                "instance tags on a legacy template with no versions map must survive restart");
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
