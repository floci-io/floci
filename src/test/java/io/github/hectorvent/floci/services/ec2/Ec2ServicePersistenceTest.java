package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.ec2.model.Address;
import io.github.hectorvent.floci.services.ec2.model.BlockDeviceMapping;
import io.github.hectorvent.floci.services.ec2.model.DhcpConfiguration;
import io.github.hectorvent.floci.services.ec2.model.DhcpOptions;
import io.github.hectorvent.floci.services.ec2.model.EbsBlockDevice;
import io.github.hectorvent.floci.services.ec2.model.Image;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InternetGateway;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.KeyPair;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.ManagedPrefixList;
import io.github.hectorvent.floci.services.ec2.model.NatGateway;
import io.github.hectorvent.floci.services.ec2.model.NetworkAcl;
import io.github.hectorvent.floci.services.ec2.model.PrefixListEntry;
import io.github.hectorvent.floci.services.ec2.model.RouteTable;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroupRule;
import io.github.hectorvent.floci.services.ec2.model.Snapshot;
import io.github.hectorvent.floci.services.ec2.model.SpotInstanceRequest;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.ec2.model.VpcEndpoint;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.services.ec2.Ec2KeyPairFixtures.ED25519_FINGERPRINT;
import static io.github.hectorvent.floci.services.ec2.Ec2KeyPairFixtures.ED25519_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void dhcpOptionsSurviveRestart(@TempDir Path dir) {
        Ec2Service original = newService(dir);
        String id = original.createDhcpOptions(REGION,
                List.of(new DhcpConfiguration("domain-name", List.of("persistent.example")))).getDhcpOptionsId();

        DhcpOptions restored = newService(dir).describeDhcpOptions(REGION, List.of(id), List.of(), 0, null)
                .items().getFirst();
        assertEquals(id, restored.getDhcpOptionsId());
        assertEquals("persistent.example", restored.getConfigurations().getFirst().getValues().getFirst());
    }

    @Test
    void startupRestoresDefaultDhcpOptionsReferencedByLegacyVpc(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("ec2-vpcs.json"), """
                {
                  "us-east-1::vpc-legacy": {
                    "vpcId": "vpc-legacy",
                    "region": "us-east-1",
                    "ownerId": "000000000000",
                    "dhcpOptionsId": "dopt-default"
                  }
                }
                """);

        Ec2Service restarted = newService(dir);

        DhcpOptions restored = restarted.describeDhcpOptions(REGION, List.of("dopt-default"), List.of(), 0, null)
                .items().getFirst();
        assertEquals("dopt-default", restored.getDhcpOptionsId());
    }

    @Test
    void createVpcRestoresDeletedDefaultDhcpOptionsAfterRestart(@TempDir Path dir) {
        Ec2Service original = newService(dir);
        original.ensureDefaultResources(REGION);
        String defaultVpcId = original.describeVpcs(REGION, List.of(), List.of()).stream()
                .filter(Vpc::isDefault).findFirst().orElseThrow().getVpcId();
        original.associateDhcpOptions(REGION, "default", defaultVpcId);
        original.deleteDhcpOptions(REGION, "dopt-default");

        Ec2Service restarted = newService(dir);
        restarted.ensureDefaultResources(REGION);
        assertTrue(restarted.describeDhcpOptions(REGION, List.of(), List.of(), 0, null)
                .items().isEmpty());
        assertEquals("dopt-default", restarted.createVpc(REGION, "10.78.0.0/16", false).getDhcpOptionsId());
        DhcpOptions restored = restarted.describeDhcpOptions(REGION, List.of("dopt-default"), List.of(), 0, null)
                .items().getFirst();
        assertEquals("dopt-default", restored.getDhcpOptionsId());
        assertEquals("default", restarted.requireVpc(REGION, defaultVpcId).getDhcpOptionsId());
    }

    @Test
    void elasticIpMetadataSurvivesRestart(@TempDir Path dir) {
        String region = "us-west-2";
        String pool = "ipv4pool-ec2-0123456789abcdef0";
        String borderGroup = "us-west-2-lax-1";
        Ec2Service first = newService(dir);
        Address allocated = first.allocateAddress(region, pool, borderGroup);

        Ec2Service restarted = newService(dir);
        Address described = restarted.describeAddresses(
                region, List.of(allocated.getAllocationId()), Map.of()).getFirst();

        assertEquals(allocated.getAllocationId(), described.getAllocationId());
        assertEquals(pool, described.getPublicIpv4Pool());
        assertEquals(borderGroup, described.getNetworkBorderGroup());
    }

    @Test
    void legacyElasticIpMetadataDefaultsToAmazonAndItsStoredRegion(@TempDir Path dir) throws IOException {
        String region = "us-west-2";
        String allocationId = "eipalloc-0123456789abcdef0";
        String legacyJson = """
                {
                  "%s::%s": {
                    "allocationId": "%s",
                    "publicIp": "54.1.2.3",
                    "domain": "vpc",
                    "region": "%s"
                  }
                }
                """.formatted(region, allocationId, allocationId, region);
        Files.writeString(dir.resolve("ec2-addresses.json"), legacyJson);

        Ec2Service restarted = newService(dir);
        Address described = restarted.describeAddresses(region, List.of(allocationId), Map.of()).getFirst();

        assertEquals("amazon", described.getPublicIpv4Pool());
        assertEquals(region, described.getNetworkBorderGroup());
    }

    @Test
    void deletedVpcDefaultResourcesRemainAbsentAfterRestart(@TempDir Path dir) {
        Ec2Service first = newService(dir);
        Vpc vpc = first.createVpc(REGION, "10.77.0.0/16", false);
        SecurityGroup defaultGroup = first.describeSecurityGroups(REGION, List.of(), List.of(), Map.of()).stream()
                .filter(group -> vpc.getVpcId().equals(group.getVpcId()) && "default".equals(group.getGroupName()))
                .findFirst().orElseThrow();

        first.deleteVpc(REGION, vpc.getVpcId());
        Ec2Service restarted = newService(dir);

        assertTrue(restarted.describeVpcs(REGION, List.of(), Map.of()).stream()
                .noneMatch(candidate -> vpc.getVpcId().equals(candidate.getVpcId())));
        assertTrue(restarted.describeSecurityGroups(REGION, List.of(), List.of(), Map.of()).stream()
                .noneMatch(group -> vpc.getVpcId().equals(group.getVpcId())));
        assertTrue(restarted.describeSecurityGroupRules(
                REGION, List.of(defaultGroup.getGroupId()), List.of()).isEmpty());
        assertTrue(restarted.describeRouteTables(REGION, List.of(), Map.of()).stream()
                .noneMatch(table -> vpc.getVpcId().equals(table.getVpcId())));
        assertTrue(restarted.describeNetworkAcls(REGION, List.of(), Map.of()).stream()
                .noneMatch(acl -> vpc.getVpcId().equals(acl.getVpcId())));
    }

    @Test
    void emptyNetworkDiscoverySurvivesRestart(@TempDir Path dir) {
        Ec2Service first = newService(dir);
        assertTrue(first.describeVpnGatewayIds(List.of(), Map.of()).isEmpty());
        assertTrue(first.describeEgressOnlyInternetGateways("us-east-1", List.of(), Map.of()).isEmpty());

        Ec2Service restarted = newService(dir);
        assertTrue(restarted.describeVpnGatewayIds(
                List.of(), Map.of("attachment.vpc-id", List.of("vpc-0123456789abcdef0"))).isEmpty());
        assertTrue(restarted.describeEgressOnlyInternetGateways("us-east-1", List.of(), Map.of("tag:Owner", List.of("TeamA"))).isEmpty());
    }

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

    /**
     * Issue #5580: a subnet persisted before zone ids took AWS's shape stored
     * {@code ap-southeast-1-az1}, which DescribeAvailabilityZones no longer publishes.
     */
    @Test
    void legacySubnetZoneIdReadsBackAsTheIdDescribeAvailabilityZonesPublishes(@TempDir Path dir)
            throws IOException {
        Files.writeString(dir.resolve("ec2-subnets.json"), """
                {
                  "ap-southeast-1::subnet-legacy": {
                    "subnetId": "subnet-legacy",
                    "vpcId": "vpc-legacy",
                    "cidrBlock": "172.31.0.0/20",
                    "state": "available",
                    "availabilityZone": "ap-southeast-1b",
                    "availabilityZoneId": "ap-southeast-1-az2",
                    "region": "ap-southeast-1"
                  }
                }
                """);

        Ec2Service restarted = newService(dir);

        Subnet subnet = restarted.describeSubnets("ap-southeast-1", List.of("subnet-legacy"), Map.of())
                .getFirst();
        assertEquals("apse1-az2", subnet.getAvailabilityZoneId());
        List<Map<String, String>> zones = restarted.describeAvailabilityZones("ap-southeast-1", List.of(),
                List.of(subnet.getAvailabilityZoneId()), List.of());
        assertEquals("ap-southeast-1b", zones.getFirst().get("zoneName"));
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

    /**
     * Creation provenance is stored beside the runtime ancestor rather than derived from it, so a
     * restart reads both back from the image files and a copy still names its immediate source.
     */
    @Test
    void imageCreationProvenanceSurvivesRestartApartFromTheRuntimeAncestor(@TempDir Path dir) {
        Ec2Service first = newService(dir);
        String instanceId = first.runInstances(REGION, "ami-0abcdef1234567890", "t3.micro", 1, 1, null,
                List.of(), null, null, List.of(), null, null).getInstances().getFirst().getInstanceId();
        Image captured = first.createImage(REGION, instanceId, "provenance-capture", null, true);
        Image copied = first.copyImage("us-west-2", REGION, captured.getImageId(), "provenance-copy", null);

        Ec2Service restarted = newService(dir);

        Image restoredCapture = restarted.describeImages(REGION, List.of(captured.getImageId()), List.of(), Map.of())
                .getFirst();
        assertEquals("ami-0abcdef1234567890", restoredCapture.getCreationSourceImageId());
        assertEquals(REGION, restoredCapture.getCreationSourceImageRegion());
        assertEquals(instanceId, restoredCapture.getCreationSourceInstanceId());
        Image restoredCopy = restarted.describeImages("us-west-2", List.of(copied.getImageId()), List.of(), Map.of())
                .getFirst();
        assertEquals(captured.getImageId(), restoredCopy.getCreationSourceImageId());
        assertEquals(REGION, restoredCopy.getCreationSourceImageRegion());
        assertNull(restoredCopy.getCreationSourceInstanceId());
        assertEquals("ami-0abcdef1234567890", restoredCopy.getSourceImageId());
    }

    /**
     * An image store as Floci wrote it before creation provenance was kept: a CreateImage AMI and its
     * cross-region copy, each holding only its launchable ancestor, the catalog AMI, under
     * {@code sourceImageId}. Both load and report no provenance, rather than passing that ancestor
     * off as the image they were made from, and a copy made after the upgrade names its source.
     */
    @Test
    void imagesSavedBeforeCreationProvenanceWasKeptLoadWithoutIt(@TempDir Path dir) throws IOException {
        String capture = "ami-d62643c08afd5b4dd";
        String copy = "ami-a6f93de002cd6d39b";
        String legacyJson = """
                {
                  "us-west-2::%2$s" : {
                    "imageId" : "%2$s",
                    "name" : "legacy-copy",
                    "description" : "legacy-copy",
                    "state" : "available",
                    "ownerId" : "000000000000",
                    "architecture" : "x86_64",
                    "rootDeviceType" : "ebs",
                    "rootDeviceName" : "/dev/xvda",
                    "virtualizationType" : "hvm",
                    "hypervisor" : "xen",
                    "platform" : null,
                    "imageOwnerAlias" : null,
                    "creationDate" : "2026-10-20T01:38:17.112Z",
                    "region" : "us-west-2",
                    "sourceImageId" : "ami-0abcdef1234567890",
                    "dockerImage" : null,
                    "blockDeviceMappings" : [ {
                      "deviceName" : "/dev/xvda",
                      "ebs" : {
                        "snapshotId" : "snap-551e5e74928952115",
                        "volumeSize" : 8,
                        "volumeType" : "gp2",
                        "deleteOnTermination" : true,
                        "encrypted" : null
                      }
                    } ],
                    "tags" : [ ],
                    "public" : false
                  },
                  "us-east-1::%1$s" : {
                    "imageId" : "%1$s",
                    "name" : "legacy-capture",
                    "description" : "legacy-capture",
                    "state" : "available",
                    "ownerId" : "000000000000",
                    "architecture" : "x86_64",
                    "rootDeviceType" : "ebs",
                    "rootDeviceName" : "/dev/xvda",
                    "virtualizationType" : "hvm",
                    "hypervisor" : "xen",
                    "platform" : null,
                    "imageOwnerAlias" : null,
                    "creationDate" : "2026-10-20T01:38:17.105Z",
                    "region" : "us-east-1",
                    "sourceImageId" : "ami-0abcdef1234567890",
                    "dockerImage" : null,
                    "blockDeviceMappings" : [ {
                      "deviceName" : "/dev/xvda",
                      "ebs" : {
                        "snapshotId" : "snap-932ddc3942d093a00",
                        "volumeSize" : 8,
                        "volumeType" : "gp2",
                        "deleteOnTermination" : true,
                        "encrypted" : null
                      }
                    } ],
                    "tags" : [ ],
                    "public" : false
                  }
                }
                """.formatted(capture, copy);
        Files.writeString(dir.resolve("ec2-registered-images.json"), legacyJson);

        Ec2Service restarted = newService(dir);

        Image restoredCapture = restarted.describeImages(REGION, List.of(capture), List.of(), Map.of()).getFirst();
        Image restoredCopy = restarted.describeImages("us-west-2", List.of(copy), List.of(), Map.of()).getFirst();
        assertEquals("legacy-capture", restoredCapture.getName());
        assertEquals("ami-0abcdef1234567890", restoredCopy.getSourceImageId());
        for (Image restored : List.of(restoredCapture, restoredCopy)) {
            assertNull(restored.getCreationSourceImageId());
            assertNull(restored.getCreationSourceImageRegion());
            assertNull(restored.getCreationSourceInstanceId());
        }
        assertTrue(restarted.describeImages("us-west-2", List.of(), List.of(),
                Map.of("source-image-id", List.of("*"))).isEmpty());

        Image copiedNow = restarted.copyImage("us-west-2", REGION, capture, "copied-after-upgrade", null);
        assertEquals(capture, copiedNow.getCreationSourceImageId());
        assertEquals(REGION, copiedNow.getCreationSourceImageRegion());
        assertEquals("ami-0abcdef1234567890", copiedNow.getSourceImageId());
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
                REGION, routeTableId, "100.68.63.255/18", null, null, "igw-other00000000000", null, null, null));
        assertEquals("RouteAlreadyExists", duplicate.getErrorCode());

        // (c) ReplaceRoute against the legacy route's original, non-canonical spelling finds the
        // same route rather than throwing InvalidRoute.NotFound.
        restarted.replaceRoute(REGION, routeTableId, "100.68.0.18/18", null, null, "igw-replaced0000000", null, null);
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
    void legacyIamProfileAndInstanceTagsSurviveRestart(@TempDir Path dir) throws IOException {
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
                versioned.getIamInstanceProfile().getArn(),
                "IAM instance profile on a legacy versions-map entry must survive restart");
        assertEquals(List.of("legacy-versioned"),
                versioned.getInstanceTags().stream().map(Tag::getValue).toList(),
                "instance tags on a legacy versions-map entry must survive restart");

        LaunchTemplateData noVersions = restarted
                .describeLaunchTemplateVersions(REGION, "lt-legacy-no-versions", null, List.of())
                .getFirst()
                .getData();
        assertEquals("arn:aws:iam::000000000000:instance-profile/legacy-profile",
                noVersions.getIamInstanceProfile().getArn(),
                "IAM instance profile on a legacy template with no versions map must survive restart");
        assertEquals(List.of("legacy-no-versions"),
                noVersions.getInstanceTags().stream().map(Tag::getValue).toList(),
                "instance tags on a legacy template with no versions map must survive restart");
    }

    @Test
    void legacyLaunchTemplateUserDataDecodesOnRead(@TempDir Path dir) throws IOException {
        String encoded = "IyEvYmluL2Jhc2gKZWNobyBoaQo=";
        String legacyJson = """
                {
                  "us-east-1::lt-legacy-user-data": {
                    "launchTemplateId": "lt-legacy-user-data",
                    "launchTemplateName": "legacy-user-data",
                    "defaultVersionNumber": "1",
                    "latestVersionNumber": "1",
                    "region": "us-east-1",
                    "data": { "encodedUserData": "%s" },
                    "versions": {
                      "1": { "encodedUserData": "%s" }
                    }
                  }
                }
                """.formatted(encoded, encoded);
        Files.writeString(dir.resolve("ec2-launch-templates.json"), legacyJson);

        Ec2Service restarted = newService(dir);
        LaunchTemplateData resolved = restarted.resolveLaunchTemplateData(
                REGION, "lt-legacy-user-data", null, "1");
        assertEquals(encoded, resolved.getEncodedUserData());
        assertEquals("#!/bin/bash\necho hi\n", resolved.getUserData());

        LaunchTemplateData described = restarted.describeLaunchTemplateVersions(
                REGION, "lt-legacy-user-data", null, List.of("1")).getFirst().getData();
        assertEquals(encoded, described.getEncodedUserData());
        assertNull(described.getUserData());
        assertNull(restarted.describeLaunchTemplates(REGION, List.of("lt-legacy-user-data"),
                List.of(), Map.of()).getFirst().getData().getUserData());
    }

    @Test
    void keyPairTypeAndCreateTimeSurviveRestart(@TempDir Path dir) {
        Ec2Service first = newService(dir);
        KeyPair created = first.createKeyPair(REGION, "restart-created");
        KeyPair imported = first.importKeyPair(REGION, "restart-imported", ED25519_KEY);

        Ec2Service restarted = newService(dir);
        Map<String, KeyPair> described = new HashMap<>();
        for (KeyPair keyPair : restarted.describeKeyPairs(REGION, List.of(), List.of())) {
            described.put(keyPair.getKeyName(), keyPair);
        }

        assertEquals("rsa", described.get("restart-created").getKeyType());
        assertEquals(created.getCreateTime(), described.get("restart-created").getCreateTime());
        assertEquals("ed25519", described.get("restart-imported").getKeyType());
        assertEquals(imported.getCreateTime(), described.get("restart-imported").getCreateTime());
    }

    /**
     * Key pairs saved before keyType and createTime were recorded still load and are described:
     * with no createTime, which was never known, and with the type read from the stored key, rsa for
     * the created one and ed25519 for the imported one. The imported material is kept exactly as
     * the older ImportKeyPair stored it, comment and newline included.
     */
    @Test
    void keyPairsSavedBeforeTypeAndCreateTimeWereRecordedStillLoad(@TempDir Path dir) throws IOException {
        Ec2KeyMaterial.Generated generated = Ec2KeyMaterial.generateRsa();
        String importedKey = ED25519_KEY + " me@host\n";
        String legacyJson = """
                {
                  "us-east-1::key-0aaaaaaaaaaaaaaaa": {
                    "keyName": "legacy-created",
                    "keyPairId": "key-0aaaaaaaaaaaaaaaa",
                    "keyFingerprint": "%s",
                    "keyMaterial": %s,
                    "publicKey": "%s",
                    "region": "us-east-1",
                    "tags": []
                  },
                  "us-east-1::key-0bbbbbbbbbbbbbbbb": {
                    "keyName": "legacy-imported",
                    "keyPairId": "key-0bbbbbbbbbbbbbbbb",
                    "keyFingerprint": "%s",
                    "publicKey": %s,
                    "region": "us-east-1",
                    "tags": [{"key": "Team", "value": "blue"}]
                  }
                }
                """.formatted(generated.fingerprint(), jsonString(generated.privateKeyPem()),
                generated.openSshPublicKey(), ED25519_FINGERPRINT, jsonString(importedKey));
        Files.writeString(dir.resolve("ec2-key-pairs.json"), legacyJson);

        Ec2Service restarted = newService(dir);
        Map<String, KeyPair> described = new HashMap<>();
        for (KeyPair keyPair : restarted.describeKeyPairs(REGION, List.of(), List.of())) {
            described.put(keyPair.getKeyName(), keyPair);
        }

        assertEquals(2, described.size());
        assertEquals("rsa", Ec2Service.keyTypeOf(described.get("legacy-created")));
        assertEquals("ed25519", Ec2Service.keyTypeOf(described.get("legacy-imported")));
        assertNull(described.get("legacy-created").getCreateTime());
        assertNull(described.get("legacy-imported").getCreateTime());
        assertEquals(importedKey, described.get("legacy-imported").getPublicKey());
        assertEquals(List.of("legacy-imported"), restarted.describeKeyPairs(REGION, List.of(), List.of(),
                Map.of("key-type", List.of("ed25519"), "tag:Team", List.of("blue"))).stream()
                .map(KeyPair::getKeyName).toList());

        String xml = (String) new Ec2QueryHandler(restarted, mock(EmulatorConfig.class), mock(FlowLogService.class),
                mock(Ec2EbsEncryptionService.class), mock(Ec2SnapshotBlockPublicAccessService.class),
                mock(Ec2IpamService.class), new RegionResolver(REGION, "000000000000"))
                .handle("DescribeKeyPairs", new MultivaluedHashMap<>(), REGION).getEntity();
        assertTrue(xml.contains("<keyType>rsa</keyType>"), xml);
        assertTrue(xml.contains("<keyType>ed25519</keyType>"), xml);
        assertFalse(xml.contains("<createTime>"), xml);
    }

    @Test
    void volumeKmsKeyIdSurvivesRestart(@TempDir Path dir) {
        String keyArn = "arn:aws:kms:us-east-1:000000000000:key/1234abcd-12ab-34cd-56ef-1234567890ab";
        String created = query(newService(dir), "CreateVolume", "AvailabilityZone", "us-east-1a", "Size", "1",
                "Encrypted", "true", "KmsKeyId", keyArn);
        String volumeId = XmlParser.extractFirst(created, "volumeId", null);

        String described = query(newService(dir), "DescribeVolumes", "VolumeId.1", volumeId);

        assertEquals(List.of("true"), XmlParser.extractAll(described, "encrypted"), described);
        assertEquals(List.of(keyArn), XmlParser.extractAll(described, "kmsKeyId"), described);
    }

    /**
     * A volume store as Floci wrote it before a volume kept its KMS key: an encrypted volume
     * attached to an instance and an unencrypted one. Both load as they were stored, and
     * DescribeVolumes leaves {@code kmsKeyId} out: the key the encrypted one was created with was
     * never recorded, and the region's default may have changed since.
     */
    @Test
    void volumesSavedBeforeTheirKmsKeyWasKeptLoadWithoutOne(@TempDir Path dir) throws IOException {
        String legacyJson = """
                {
                  "us-east-1::vol-0aaaaaaaaaaaaaaaa" : {
                    "volumeId" : "vol-0aaaaaaaaaaaaaaaa",
                    "volumeType" : "gp3",
                    "size" : 20,
                    "state" : "in-use",
                    "availabilityZone" : "us-east-1a",
                    "encrypted" : true,
                    "iops" : 0,
                    "throughput" : 125,
                    "snapshotId" : null,
                    "createTime" : "2026-10-20T01:38:17.105Z",
                    "multiAttachEnabled" : null,
                    "region" : "us-east-1",
                    "tags" : [ {
                      "key" : "Team",
                      "value" : "blue"
                    } ],
                    "attachments" : [ {
                      "instanceId" : "i-0aaaaaaaaaaaaaaaa",
                      "device" : "/dev/sdf",
                      "state" : "attached",
                      "attachTime" : "2026-10-20T01:39:02.411Z",
                      "deleteOnTermination" : false,
                      "volumeId" : "vol-0aaaaaaaaaaaaaaaa"
                    } ]
                  },
                  "us-east-1::vol-0bbbbbbbbbbbbbbbb" : {
                    "volumeId" : "vol-0bbbbbbbbbbbbbbbb",
                    "volumeType" : "gp2",
                    "size" : 8,
                    "state" : "available",
                    "availabilityZone" : "us-east-1b",
                    "encrypted" : false,
                    "iops" : 0,
                    "throughput" : null,
                    "snapshotId" : null,
                    "createTime" : "2026-10-20T01:38:17.112Z",
                    "multiAttachEnabled" : null,
                    "region" : "us-east-1",
                    "tags" : [ ],
                    "attachments" : [ ]
                  }
                }
                """;
        Files.writeString(dir.resolve("ec2-volumes.json"), legacyJson);

        Ec2Service restarted = newService(dir);

        Map<String, Volume> restored = new HashMap<>();
        for (Volume volume : restarted.describeVolumes(REGION, List.of(), Map.of())) {
            restored.put(volume.getVolumeId(), volume);
        }
        assertEquals(2, restored.size());
        Volume encrypted = restored.get("vol-0aaaaaaaaaaaaaaaa");
        assertTrue(encrypted.isEncrypted());
        assertEquals(20, encrypted.getSize());
        assertEquals(125, encrypted.getThroughput());
        assertEquals("i-0aaaaaaaaaaaaaaaa", encrypted.getAttachments().getFirst().getInstanceId());
        assertEquals("blue", encrypted.getTags().getFirst().getValue());
        assertFalse(restored.get("vol-0bbbbbbbbbbbbbbbb").isEncrypted());

        String described = query(restarted, "DescribeVolumes");
        List<String> volumeIds = XmlParser.extractAll(described, "volumeId");
        assertTrue(volumeIds.contains("vol-0aaaaaaaaaaaaaaaa"), described);
        assertTrue(volumeIds.contains("vol-0bbbbbbbbbbbbbbbb"), described);
        assertTrue(XmlParser.extractAll(described, "encrypted").contains("true"), described);
        assertEquals(List.of(), XmlParser.extractAll(described, "kmsKeyId"), described);
    }

    private static String query(Ec2Service service, String action, String... params) {
        MultivaluedHashMap<String, String> form = new MultivaluedHashMap<>();
        for (int i = 0; i < params.length; i += 2) {
            form.add(params[i], params[i + 1]);
        }
        Response response = new Ec2QueryHandler(service, mock(EmulatorConfig.class),
                mock(FlowLogService.class), mock(Ec2EbsEncryptionService.class),
                mock(Ec2SnapshotBlockPublicAccessService.class), mock(Ec2IpamService.class),
                new RegionResolver(REGION, "000000000000"))
                .handle(action, form, REGION);
        String body = String.valueOf(response.getEntity());
        assertEquals(200, response.getStatus(), action + ": " + body);
        return body;
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
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
        when(ec2.mockPublicAddresses()).thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
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
                load(dir, "ec2-tags.json", new TypeReference<Map<String, List<Tag>>>() {}),
                load(dir, "ec2-dhcp-options.json", new TypeReference<Map<String, DhcpOptions>>() {}));
    }

    private <V> StorageBackend<String, V> load(Path dir, String file, TypeReference<Map<String, V>> type) {
        PersistentStorage<String, V> backend = new PersistentStorage<>(dir.resolve(file), type);
        backend.load();
        return backend;
    }
}
