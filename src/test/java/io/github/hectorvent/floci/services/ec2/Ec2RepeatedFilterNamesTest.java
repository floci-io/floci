package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Filter.N with one name given more than once, sent through the Query handler to a real service.
 * AWS joins every filter with an AND, so a repeated name narrows the answer as a second filter of
 * another name would, rather than replacing the filter before it.
 */
class Ec2RepeatedFilterNamesTest {

    private static final String REGION = "us-east-1";
    private static final String AMI = "ami-0abcdef1234567890";

    private Ec2Service service;
    private Ec2QueryHandler handler;
    private int sequence;
    private String vpcId;
    private String subnetId;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(true);
        when(ec2.mockPublicAddresses()).thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
        when(ec2.mockPublicAddress()).thenReturn(Optional.empty());
        Ec2ImageCatalog imageCatalog = new Ec2ImageCatalog();
        service = new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                new AmiImageResolver(imageCatalog), imageCatalog, new Ec2InstanceTypeCatalog(), new MemoryFactory());
        handler = new Ec2QueryHandler(service, mock(EmulatorConfig.class), mock(FlowLogService.class),
                mock(Ec2EbsEncryptionService.class), mock(Ec2BlockPublicAccessService.class),
                mock(Ec2IpamService.class), new RegionResolver(REGION, "000000000000"));
    }

    /**
     * Resources a tag filter reaches through the shared matcher, each with the action that lists
     * them and the element that carries their id.
     */
    enum Tagged {
        INSTANCE("DescribeInstances", "instanceId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.runInstance();
            }
        },
        VPC("DescribeVpcs", "vpcId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.newVpc();
            }
        },
        SUBNET("DescribeSubnets", "subnetId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.first(t.ok("CreateSubnet", "VpcId", t.vpc(), "CidrBlock", t.nextSubnetCidr()), "subnetId");
            }
        },
        SECURITY_GROUP("DescribeSecurityGroups", "groupId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.newSecurityGroup();
            }
        },
        SECURITY_GROUP_FOR_VPC("GetSecurityGroupsForVpc", "groupId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.newSecurityGroup();
            }

            @Override
            List<String> describeParams(Ec2RepeatedFilterNamesTest t) {
                return List.of("VpcId", t.vpc());
            }
        },
        INTERNET_GATEWAY("DescribeInternetGateways", "internetGatewayId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.first(t.ok("CreateInternetGateway"), "internetGatewayId");
            }
        },
        EGRESS_ONLY_INTERNET_GATEWAY("DescribeEgressOnlyInternetGateways", "egressOnlyInternetGatewayId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.first(t.ok("CreateEgressOnlyInternetGateway", "VpcId", t.newVpc()),
                        "egressOnlyInternetGatewayId");
            }
        },
        ROUTE_TABLE("DescribeRouteTables", "routeTableId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.first(t.ok("CreateRouteTable", "VpcId", t.vpc()), "routeTableId");
            }
        },
        ADDRESS("DescribeAddresses", "allocationId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.newAddress();
            }
        },
        VOLUME("DescribeVolumes", "volumeId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.newVolume();
            }
        },
        MANAGED_PREFIX_LIST("DescribeManagedPrefixLists", "prefixListId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.first(t.ok("CreateManagedPrefixList", "PrefixListName", "repeated-" + t.next(),
                        "AddressFamily", "IPv4", "MaxEntries", "5"), "prefixListId");
            }
        },
        LAUNCH_TEMPLATE("DescribeLaunchTemplates", "launchTemplateId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.first(t.ok("CreateLaunchTemplate", "LaunchTemplateName", "repeated-" + t.next(),
                        "LaunchTemplateData.InstanceType", "t3.micro"), "launchTemplateId");
            }
        },
        VPC_ENDPOINT("DescribeVpcEndpoints", "vpcEndpointId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.first(t.ok("CreateVpcEndpoint", "VpcId", t.vpc(),
                        "ServiceName", "com.amazonaws.us-east-1.s3"), "vpcEndpointId");
            }
        },
        NAT_GATEWAY("DescribeNatGateways", "natGatewayId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.first(t.ok("CreateNatGateway", "SubnetId", t.subnet(),
                        "AllocationId", t.newAddress()), "natGatewayId");
            }
        },
        SPOT_INSTANCE_REQUEST("DescribeSpotInstanceRequests", "spotInstanceRequestId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.first(t.ok("RequestSpotInstances", "InstanceCount", "1",
                        "LaunchSpecification.ImageId", AMI, "LaunchSpecification.InstanceType", "t3.micro"),
                        "spotInstanceRequestId");
            }
        },
        TRANSIT_GATEWAY("DescribeTransitGateways", "transitGatewayId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.newTransitGateway();
            }
        },
        TRANSIT_GATEWAY_ROUTE_TABLE("DescribeTransitGatewayRouteTables", "transitGatewayRouteTableId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.first(t.ok("CreateTransitGatewayRouteTable", "TransitGatewayId", t.newTransitGateway()),
                        "transitGatewayRouteTableId");
            }
        },
        TRANSIT_GATEWAY_VPC_ATTACHMENT("DescribeTransitGatewayVpcAttachments", "transitGatewayAttachmentId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.newVpcAttachment(t.newTransitGateway());
            }
        },
        TRANSIT_GATEWAY_ATTACHMENT("DescribeTransitGatewayAttachments", "transitGatewayAttachmentId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.newVpcAttachment(t.newTransitGateway());
            }
        },
        TRANSIT_GATEWAY_PEERING_ATTACHMENT("DescribeTransitGatewayPeeringAttachments", "transitGatewayAttachmentId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.first(t.ok("CreateTransitGatewayPeeringAttachment",
                        "TransitGatewayId", t.newTransitGateway(), "PeerTransitGatewayId", t.newTransitGateway(),
                        "PeerAccountId", "000000000000", "PeerRegion", REGION), "transitGatewayAttachmentId");
            }
        },
        VPC_PEERING_CONNECTION("DescribeVpcPeeringConnections", "vpcPeeringConnectionId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.first(t.ok("CreateVpcPeeringConnection", "VpcId", t.newVpc(), "PeerVpcId", t.newVpc()),
                        "vpcPeeringConnectionId");
            }
        },
        CAPACITY_RESERVATION("DescribeCapacityReservations", "capacityReservationId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.first(t.ok("CreateCapacityReservation", "InstanceType", "t3.micro",
                        "InstancePlatform", "Linux/UNIX", "AvailabilityZone", "us-east-1a", "InstanceCount", "1"),
                        "capacityReservationId");
            }
        },
        HOST("DescribeHosts", "hostId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.first(t.ok("AllocateHosts", "AvailabilityZone", "us-east-1a",
                        "InstanceType", "m5.large", "Quantity", "1"), "item");
            }
        },
        IMAGE("DescribeImages", "imageId") {
            @Override
            String create(Ec2RepeatedFilterNamesTest t) {
                return t.first(t.ok("RegisterImage", "Name", "repeated-" + t.next(),
                        "RootDeviceName", "/dev/xvda",
                        "BlockDeviceMapping.1.DeviceName", "/dev/xvda",
                        "BlockDeviceMapping.1.Ebs.VolumeSize", "8"), "imageId");
            }
        };

        private final String describeAction;
        private final String idElement;

        Tagged(String describeAction, String idElement) {
            this.describeAction = describeAction;
            this.idElement = idElement;
        }

        abstract String create(Ec2RepeatedFilterNamesTest t);

        List<String> describeParams(Ec2RepeatedFilterNamesTest t) {
            return List.of();
        }
    }

    @ParameterizedTest
    @EnumSource(Tagged.class)
    void tagKeyFiltersOfOneNameAreAnded(Tagged type) {
        String both = type.create(this);
        String one = type.create(this);
        ok("CreateTags", "ResourceId.1", both, "ResourceId.2", one, "Tag.1.Key", "RepeatedShared");
        ok("CreateTags", "ResourceId.1", both, "Tag.1.Key", "RepeatedOnly");

        assertEquals(Set.of(both, one), described(type, filter("tag-key", "RepeatedShared")));
        assertEquals(Set.of(both), described(type, filter("tag-key", "RepeatedOnly")));
        assertEquals(Set.of(both), described(type,
                filter("tag-key", "RepeatedOnly"), filter("tag-key", "RepeatedShared")));
        assertEquals(Set.of(both), described(type,
                filter("tag-key", "RepeatedShared"), filter("tag-key", "RepeatedOnly")));
    }

    @Test
    void tagFiltersOfOneKeyMustAllMatchTheValue() {
        String prod = runInstance();
        String dev = runInstance();
        ok("CreateTags", "ResourceId.1", prod, "Tag.1.Key", "Stage", "Tag.1.Value", "prod");
        ok("CreateTags", "ResourceId.1", dev, "Tag.1.Key", "Stage", "Tag.1.Value", "dev");

        assertEquals(Set.of(prod), ids(describe("DescribeInstances",
                filter("tag:Stage", "prod"), filter("tag:Stage", "prod", "dev")), "instanceId"));
        assertEquals(Set.of(), ids(describe("DescribeInstances",
                filter("tag:Stage", "prod"), filter("tag:Stage", "dev")), "instanceId"));
    }

    @Test
    void subnetsAndRouteTablesInTwoVpcsAtOnceAreNone() {
        String first = vpc();
        String second = newVpc();
        ok("CreateSubnet", "VpcId", first, "CidrBlock", nextSubnetCidr());
        String secondSubnet = first(ok("CreateSubnet", "VpcId", second,
                "CidrBlock", cidrOf(second).replace(".0.0/16", ".1.0/24")), "subnetId");

        assertEquals(Set.of(secondSubnet), ids(describe("DescribeSubnets",
                filter("vpc-id", second)), "subnetId"));
        assertEquals(Set.of(), ids(describe("DescribeSubnets",
                filter("vpc-id", first), filter("vpc-id", second)), "subnetId"));
        assertEquals(Set.of(), ids(describe("DescribeRouteTables",
                filter("vpc-id", first), filter("vpc-id", second)), "routeTableId"));
        assertEquals(Set.of(), ids(describe("DescribeSecurityGroups",
                filter("vpc-id", first), filter("vpc-id", second)), "groupId"));
    }

    @Test
    void vpcsWithTwoCidrFiltersMatchOnlyBoth() {
        String vpc = newVpc();
        assertEquals(Set.of(vpc), ids(describe("DescribeVpcs",
                filter("cidr", cidrOf(vpc)), filter("vpc-id", vpc)), "vpcId"));
        assertEquals(Set.of(), ids(describe("DescribeVpcs",
                filter("cidr", "10.249.0.0/16"), filter("cidr", cidrOf(vpc))), "vpcId"));
    }

    @Test
    void networkAclsInTwoVpcsAtOnceAreNone() {
        String first = vpc();
        String second = newVpc();
        String secondAcl = first(ok("CreateNetworkAcl", "VpcId", second), "networkAclId");

        assertTrue(ids(describe("DescribeNetworkAcls", filter("vpc-id", second)), "networkAclId")
                .contains(secondAcl));
        assertEquals(Set.of(), ids(describe("DescribeNetworkAcls",
                filter("vpc-id", first), filter("vpc-id", second)), "networkAclId"));
        assertEquals(Set.of(secondAcl), ids(describe("DescribeNetworkAcls",
                filter("default", "false"), filter("vpc-id", second)), "networkAclId"));
    }

    @Test
    void snapshotsMatchEveryFilter() {
        String first = "snap-0123456789abcdef1";
        String second = "snap-0123456789abcdef2";
        registerImageWithSnapshot(first, "8");
        registerImageWithSnapshot(second, "16");

        assertEquals(Set.of(second), ids(describe("DescribeSnapshots",
                filter("snapshot-id", second)), "snapshotId"));
        assertEquals(Set.of(), ids(describe("DescribeSnapshots",
                filter("snapshot-id", first), filter("snapshot-id", second)), "snapshotId"));
        assertEquals(Set.of(), ids(describe("DescribeSnapshots",
                filter("volume-size", "8"), filter("volume-size", "16")), "snapshotId"));
    }

    @Test
    void volumeModificationsOfTwoVolumesAtOnceAreNone() {
        String firstVolume = newVolume();
        String secondVolume = newVolume();
        ok("ModifyVolume", "VolumeId", firstVolume, "Size", "16");
        ok("ModifyVolume", "VolumeId", secondVolume, "Size", "16");

        assertEquals(Set.of(secondVolume), ids(describe("DescribeVolumesModifications",
                filter("volume-id", secondVolume)), "volumeId"));
        assertEquals(Set.of(), ids(describe("DescribeVolumesModifications",
                filter("volume-id", firstVolume), filter("volume-id", secondVolume)), "volumeId"));
    }

    @Test
    void catalogImagesMatchEveryNamePattern() {
        Set<String> amazonLinux = names(describe("DescribeImages", filter("name", "al2023-ami-*")));
        Set<String> arm = names(describe("DescribeImages", filter("name", "*arm64*")));
        Set<String> both = new LinkedHashSet<>(amazonLinux);
        both.retainAll(arm);
        assertTrue(!both.isEmpty() && both.size() < arm.size(),
                "the catalog needs an image matching both patterns and one matching only the second: " + arm);

        assertEquals(both, names(describe("DescribeImages",
                filter("name", "al2023-ami-*"), filter("name", "*arm64*"))));
    }

    @Test
    void aLookupImageIsSynthesizedOnlyWhenItSatisfiesEveryNameFilter() {
        for (List<String[]> filters : List.of(
                List.of(filter("name", "repeated-lookup-*-x86_64"), filter("name", "repeated-lookup-*")),
                List.of(filter("name", "repeated-lookup-*"), filter("name", "repeated-lookup-*-x86_64")))) {
            Set<String> synthesized = names(describe("DescribeImages", filters.toArray(String[][]::new)));
            assertEquals(1, synthesized.size(), synthesized.toString());
            String name = synthesized.iterator().next();
            assertTrue(name.startsWith("repeated-lookup-") && name.endsWith("-x86_64"), name);
        }

        assertEquals(Set.of(), names(describe("DescribeImages",
                filter("name", "repeated-first-*"), filter("name", "repeated-second-*"))));
        // Patterns that overlap, but the name built from either one breaks the other: no image is
        // made up, as AWS returns only images that exist.
        assertEquals(Set.of(), names(describe("DescribeImages",
                filter("name", "foo-*"), filter("name", "*-bar-*"))));
    }

    @Test
    void aLookupImageTakesTheValueEveryFilterOfANameAdmits() {
        String xml = describe("DescribeImages", filter("name", "repeated-scalar-*"),
                filter("architecture", "x86_64", "arm64"), filter("architecture", "arm64"),
                filter("owner-id", "111111111111", "222222222222"), filter("owner-id", "222222222222"),
                filter("state", "available", "pending"), filter("state", "pending"));

        assertEquals(1, names(xml).size(), xml);
        assertEquals(Set.of("arm64"), ids(xml, "architecture"));
        assertEquals(Set.of("222222222222"), ids(xml, "imageOwnerId"));
        assertEquals(Set.of("pending"), ids(xml, "imageState"));
    }

    @Test
    void securityGroupRulesOfTwoGroupsAtOnceAreNone() {
        String first = newSecurityGroup();
        String second = newSecurityGroup();
        Set<String> secondRules = ids(describe("DescribeSecurityGroupRules", filter("group-id", second)),
                "securityGroupRuleId");
        assertTrue(!secondRules.isEmpty(), "a new group carries its default egress rule");

        assertEquals(Set.of(), ids(describe("DescribeSecurityGroupRules",
                filter("group-id", first), filter("group-id", second)), "securityGroupRuleId"));
        String rule = secondRules.iterator().next();
        assertEquals(Set.of(), ids(describe("DescribeSecurityGroupRules",
                filter("security-group-rule-id", "sgr-00000000000000000"),
                filter("security-group-rule-id", rule)), "securityGroupRuleId"));
        assertEquals(Set.of(rule), ids(describe("DescribeSecurityGroupRules",
                filter("security-group-rule-id", rule), filter("group-id", first, second),
                filter("group-id", second)), "securityGroupRuleId"));
    }

    @Test
    void networkInterfacesInTwoSubnetsAtOnceAreNone() {
        String first = subnet();
        String second = first(ok("CreateSubnet", "VpcId", vpc(), "CidrBlock", nextSubnetCidr()), "subnetId");
        ok("CreateNetworkInterface", "SubnetId", first);
        String secondInterface = first(ok("CreateNetworkInterface", "SubnetId", second), "networkInterfaceId");

        assertEquals(Set.of(secondInterface), ids(describe("DescribeNetworkInterfaces",
                filter("subnet-id", second)), "networkInterfaceId"));
        assertEquals(Set.of(), ids(describe("DescribeNetworkInterfaces",
                filter("subnet-id", first), filter("subnet-id", second)), "networkInterfaceId"));
    }

    @Test
    void instanceCreditSpecificationsOfTwoInstancesAtOnceAreNone() {
        String first = runInstance();
        String second = runInstance();

        assertEquals(Set.of(second), ids(describe("DescribeInstanceCreditSpecifications",
                filter("instance-id", second)), "instanceId"));
        assertEquals(Set.of(), ids(describe("DescribeInstanceCreditSpecifications",
                filter("instance-id", first), filter("instance-id", second)), "instanceId"));
    }

    @Test
    void awsManagedPrefixListsOfTwoNamesAtOnceAreNone() {
        String s3 = "com.amazonaws.us-east-1.s3";
        String dynamodb = "com.amazonaws.us-east-1.dynamodb";

        assertEquals(Set.of(dynamodb), ids(describe("DescribePrefixLists",
                filter("prefix-list-name", dynamodb)), "prefixListName"));
        assertEquals(Set.of(), ids(describe("DescribePrefixLists",
                filter("prefix-list-name", s3), filter("prefix-list-name", dynamodb)), "prefixListName"));
        assertEquals(Set.of(dynamodb), ids(describe("DescribePrefixLists",
                filter("prefix-list-name", s3, dynamodb), filter("prefix-list-name", dynamodb)),
                "prefixListName"));
    }

    @Test
    void instanceTypeOfferingsMatchEveryInstanceTypeAndLocationFilter() {
        assertEquals(Set.of("t3.micro"), ids(describe("DescribeInstanceTypeOfferings",
                "LocationType", "region", filter("instance-type", "t3.micro", "m5.large"),
                filter("instance-type", "t3.micro")), "instanceType"));
        assertEquals(Set.of(), ids(describe("DescribeInstanceTypeOfferings",
                "LocationType", "region", filter("instance-type", "m5.large"),
                filter("instance-type", "t3.micro")), "instanceType"));
        assertEquals(Set.of(), ids(describe("DescribeInstanceTypeOfferings",
                "LocationType", "availability-zone", filter("instance-type", "t3.micro"),
                filter("location", "us-east-1a"), filter("location", "us-east-1b")), "location"));
    }

    /**
     * The InstanceType.N parameter, which Floci reads beside the instance-type filter, adds its
     * types to those every instance-type filter names, so repeating a filter or reordering the
     * filters leaves the answer as it was.
     */
    @Test
    void instanceTypeOfferingsKeepTheNamedTypesBesideEveryInstanceTypeFilter() {
        List<String> params = List.of("LocationType", "region", "InstanceType.1", "m5.large");
        Set<String> lone = ids(describe("DescribeInstanceTypeOfferings", params,
                filter("instance-type", "t3.micro")), "instanceType");
        assertEquals(Set.of("m5.large", "t3.micro"), lone);

        assertEquals(lone, ids(describe("DescribeInstanceTypeOfferings", params,
                filter("instance-type", "t3.micro"), filter("instance-type", "t3.micro")), "instanceType"));
        assertEquals(lone, ids(describe("DescribeInstanceTypeOfferings", params,
                filter("instance-type", "t3.micro", "m5.large"), filter("instance-type", "t3.micro")), "instanceType"));
        assertEquals(lone, ids(describe("DescribeInstanceTypeOfferings", params,
                filter("instance-type", "t3.micro"), filter("instance-type", "t3.micro", "m5.large")), "instanceType"));
        assertEquals(lone, ids(describe("DescribeInstanceTypeOfferings", params,
                filter("instance-type"), filter("instance-type", "t3.micro")), "instanceType"));
        assertEquals(lone, ids(describe("DescribeInstanceTypeOfferings", params,
                filter("instance-type", "t3.micro"), filter("instance-type")), "instanceType"));
    }

    /** An instance-type value may hold * and ?, as AWS takes, whichever filter of the name it is in. */
    @Test
    void instanceTypeOfferingsMatchInstanceTypeWildcards() {
        Set<String> t3 = ids(describe("DescribeInstanceTypeOfferings", "LocationType", "region",
                filter("instance-type", "t3.*")), "instanceType");
        assertTrue(t3.contains("t3.micro") && t3.stream().allMatch(type -> type.startsWith("t3.")), t3.toString());
        Set<String> micro = ids(describe("DescribeInstanceTypeOfferings", "LocationType", "region",
                filter("instance-type", "*.micro")), "instanceType");
        assertTrue(micro.size() > 1 && micro.stream().allMatch(type -> type.endsWith(".micro")), micro.toString());

        assertEquals(Set.of("t3.micro"), ids(describe("DescribeInstanceTypeOfferings", "LocationType", "region",
                filter("instance-type", "t3.mi?ro")), "instanceType"));
        assertEquals(Set.of("t3.micro"), ids(describe("DescribeInstanceTypeOfferings", "LocationType", "region",
                filter("instance-type", "t3.*"), filter("instance-type", "t3.micro")), "instanceType"));
        assertEquals(Set.of("t3.micro"), ids(describe("DescribeInstanceTypeOfferings", "LocationType", "region",
                filter("instance-type", "t3.micro"), filter("instance-type", "t3.*")), "instanceType"));
        assertEquals(Set.of("t3.micro"), ids(describe("DescribeInstanceTypeOfferings", "LocationType", "region",
                filter("instance-type", "t3.*"), filter("instance-type", "*.micro")), "instanceType"));

        Set<String> named = new LinkedHashSet<>(t3);
        named.add("m5.large");
        assertEquals(named, ids(describe("DescribeInstanceTypeOfferings",
                List.of("LocationType", "region", "InstanceType.1", "m5.large"),
                filter("instance-type", "t3.*")), "instanceType"));
    }

    /** A caller-supplied pattern of many stars is matched by the two-pointer glob, not a backtracking regex. */
    @Test
    void instanceTypeOfferingsMatchAPatternOfManyStarsQuickly() {
        String pattern = "*".repeat(30) + "#";
        assertEquals(Set.of(), assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> ids(describe("DescribeInstanceTypeOfferings", "LocationType", "region",
                        filter("instance-type", pattern)), "instanceType")));
    }

    @Test
    void spotPriceHistoryMatchesEveryFilter() {
        assertEquals(Set.of("t3.micro"), ids(describe("DescribeSpotPriceHistory",
                filter("instance-type", "t3.micro", "m5.large"), filter("instance-type", "t3.micro")),
                "instanceType"));
        assertEquals(Set.of(), ids(describe("DescribeSpotPriceHistory",
                filter("instance-type", "m5.large"), filter("instance-type", "t3.micro")), "instanceType"));
        assertEquals(Set.of(), ids(describe("DescribeSpotPriceHistory", filter("instance-type", "t3.micro"),
                filter("availability-zone", "us-east-1a"), filter("availability-zone", "us-east-1b")),
                "availabilityZone"));
        assertEquals(Set.of(), ids(describe("DescribeSpotPriceHistory", filter("instance-type", "t3.micro"),
                filter("product-description", "Linux/UNIX"), filter("product-description", "Windows")),
                "productDescription"));
    }

    @Test
    void transitGatewayRoutesMatchEveryFilter() {
        String gateway = newTransitGateway();
        String routeTable = first(ok("CreateTransitGatewayRouteTable", "TransitGatewayId", gateway),
                "transitGatewayRouteTableId");
        for (String cidr : List.of("10.230.0.0/16", "10.231.0.0/16")) {
            ok("CreateTransitGatewayRoute", "TransitGatewayRouteTableId", routeTable,
                    "DestinationCidrBlock", cidr, "Blackhole", "true");
        }

        assertEquals(Set.of("10.231.0.0/16"), ids(describe("SearchTransitGatewayRoutes",
                "TransitGatewayRouteTableId", routeTable,
                filter("route-search.exact-match", "10.231.0.0/16")), "destinationCidrBlock"));
        assertEquals(Set.of(), ids(describe("SearchTransitGatewayRoutes",
                "TransitGatewayRouteTableId", routeTable,
                filter("route-search.exact-match", "10.230.0.0/16"),
                filter("route-search.exact-match", "10.231.0.0/16")), "destinationCidrBlock"));
    }

    @Test
    void transitGatewayRouteTableMembersMatchEveryFilter() {
        String gateway = first(ok("CreateTransitGateway", "Options.DefaultRouteTableAssociation", "disable",
                "Options.DefaultRouteTablePropagation", "disable"), "transitGatewayId");
        String routeTable = first(ok("CreateTransitGatewayRouteTable", "TransitGatewayId", gateway),
                "transitGatewayRouteTableId");
        String first = newVpcAttachment(gateway);
        String second = newVpcAttachment(gateway);
        for (String attachment : List.of(first, second)) {
            ok("AssociateTransitGatewayRouteTable", "TransitGatewayRouteTableId", routeTable,
                    "TransitGatewayAttachmentId", attachment);
            ok("EnableTransitGatewayRouteTablePropagation", "TransitGatewayRouteTableId", routeTable,
                    "TransitGatewayAttachmentId", attachment);
        }

        for (String action : List.of("GetTransitGatewayRouteTableAssociations",
                "GetTransitGatewayRouteTablePropagations")) {
            assertEquals(Set.of(second), ids(describe(action, "TransitGatewayRouteTableId", routeTable,
                    filter("transit-gateway-attachment-id", second)), "transitGatewayAttachmentId"), action);
            assertEquals(Set.of(), ids(describe(action, "TransitGatewayRouteTableId", routeTable,
                    filter("transit-gateway-attachment-id", first),
                    filter("transit-gateway-attachment-id", second)), "transitGatewayAttachmentId"), action);
        }
    }

    @Test
    void repeatedSupportedNamesAreAcceptedWhereNamesAreChecked() {
        for (String action : List.of("DescribeVpnGateways", "DescribeEgressOnlyInternetGateways")) {
            ok(action, "Filter.1.Name", "tag-key", "Filter.1.Value.1", "a",
                    "Filter.2.Name", "tag-key", "Filter.2.Value.1", "b");
        }
        ok("DescribeSpotPriceHistory", "Filter.1.Name", "instance-type", "Filter.1.Value.1", "t3.micro",
                "Filter.2.Name", "instance-type", "Filter.2.Value.1", "t3.micro");
    }

    // ─── Fixtures ────────────────────────────────────────────────────────────

    private String vpc() {
        if (vpcId == null) {
            vpcId = newVpc();
        }
        return vpcId;
    }

    private String subnet() {
        if (subnetId == null) {
            subnetId = first(ok("CreateSubnet", "VpcId", vpc(), "CidrBlock", nextSubnetCidr()), "subnetId");
        }
        return subnetId;
    }

    private int next() {
        return ++sequence;
    }

    private String newVpc() {
        return first(ok("CreateVpc", "CidrBlock", "10." + (100 + next()) + ".0.0/16"), "vpcId");
    }

    private String nextSubnetCidr() {
        return "10." + (100 + vpcSequenceOf(vpc())) + "." + next() + ".0/24";
    }

    private int vpcSequenceOf(String vpc) {
        String cidr = cidrOf(vpc);
        return Integer.parseInt(cidr.split("\\.")[1]) - 100;
    }

    private String cidrOf(String vpc) {
        return first(ok("DescribeVpcs", "VpcId.1", vpc), "cidrBlock");
    }

    private String newSecurityGroup() {
        return first(ok("CreateSecurityGroup", "GroupName", "repeated-" + next(),
                "GroupDescription", "repeated filters", "VpcId", vpc()), "groupId");
    }

    private String newAddress() {
        return first(ok("AllocateAddress", "Domain", "vpc"), "allocationId");
    }

    private String newVolume() {
        return first(ok("CreateVolume", "AvailabilityZone", "us-east-1a", "Size", "8"), "volumeId");
    }

    private String newTransitGateway() {
        return first(ok("CreateTransitGateway"), "transitGatewayId");
    }

    private String newVpcAttachment(String gateway) {
        String vpc = newVpc();
        String cidr = cidrOf(vpc);
        String attachSubnet = first(ok("CreateSubnet", "VpcId", vpc,
                "CidrBlock", cidr.replace(".0.0/16", ".1.0/24")), "subnetId");
        return first(ok("CreateTransitGatewayVpcAttachment", "TransitGatewayId", gateway, "VpcId", vpc,
                "SubnetIds.1", attachSubnet), "transitGatewayAttachmentId");
    }

    private void registerImageWithSnapshot(String snapshotId, String volumeSize) {
        ok("RegisterImage", "Name", "repeated-" + next(), "RootDeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.DeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.Ebs.SnapshotId", snapshotId,
                "BlockDeviceMapping.1.Ebs.VolumeSize", volumeSize);
    }

    private String runInstance() {
        return first(ok("RunInstances", "ImageId", AMI, "InstanceType", "t3.micro",
                "MinCount", "1", "MaxCount", "1"), "instanceId");
    }

    // ─── Requests ────────────────────────────────────────────────────────────

    private static String[] filter(String name, String... values) {
        String[] filter = new String[values.length + 1];
        filter[0] = name;
        System.arraycopy(values, 0, filter, 1, values.length);
        return filter;
    }

    private Set<String> described(Tagged type, String[]... filters) {
        List<String> params = new ArrayList<>(type.describeParams(this));
        return ids(describe(type.describeAction, params, filters), type.idElement);
    }

    private String describe(String action, String[]... filters) {
        return describe(action, List.of(), filters);
    }

    private String describe(String action, String paramName, String paramValue, String[]... filters) {
        return describe(action, List.of(paramName, paramValue), filters);
    }

    private String describe(String action, List<String> params, String[]... filters) {
        List<String> all = new ArrayList<>(params);
        for (int i = 0; i < filters.length; i++) {
            all.add("Filter." + (i + 1) + ".Name");
            all.add(filters[i][0]);
            for (int j = 1; j < filters[i].length; j++) {
                all.add("Filter." + (i + 1) + ".Value." + j);
                all.add(filters[i][j]);
            }
        }
        return ok(action, all.toArray(String[]::new));
    }

    private String ok(String action, String... params) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        for (int i = 0; i < params.length; i += 2) {
            form.add(params[i], params[i + 1]);
        }
        Response response = handler.handle(action, form, REGION);
        String body = String.valueOf(response.getEntity());
        assertEquals(200, response.getStatus(), action + ": " + body);
        return body;
    }

    private String first(String xml, String element) {
        Matcher matcher = elementPattern(element).matcher(xml);
        assertTrue(matcher.find(), "no <" + element + "> in " + xml);
        return matcher.group(1).trim();
    }

    private static Set<String> ids(String xml, String element) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = elementPattern(element).matcher(xml);
        while (matcher.find()) {
            found.add(matcher.group(1).trim());
        }
        return found;
    }

    private static Set<String> names(String describeImagesXml) {
        return ids(describeImagesXml, "name");
    }

    private static Pattern elementPattern(String element) {
        return Pattern.compile("<" + element + ">([^<]*)</" + element + ">");
    }

    private static final class MemoryFactory extends StorageFactory {
        private MemoryFactory() {
            super(null, null);
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                        TypeReference<Map<String, V>> typeReference) {
            return AccountAwareStorageBackend.inMemory("000000000000");
        }
    }
}
