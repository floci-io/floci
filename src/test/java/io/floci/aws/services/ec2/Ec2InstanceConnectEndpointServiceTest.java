package io.floci.aws.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.services.ec2.model.GroupIdentifier;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceConnectEndpoint;
import io.floci.aws.services.ec2.model.InstanceState;
import io.floci.aws.services.ec2.model.IpPermission;
import io.floci.aws.services.ec2.model.IpRange;
import io.floci.aws.services.ec2.model.NetworkInterface;
import io.floci.aws.services.ec2.model.PrefixListEntry;
import io.floci.aws.services.ec2.model.PrefixListId;
import io.floci.aws.services.ec2.model.Tag;
import io.floci.aws.services.ec2.model.UserIdGroupPair;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.floci.aws.testing.Ec2ServiceFixtures.mockModeService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CreateInstanceConnectEndpoint, DescribeInstanceConnectEndpoints and DeleteInstanceConnectEndpoint. */
class Ec2InstanceConnectEndpointServiceTest {

    private static final String REGION = "us-east-1";

    @Test
    void lifecycleFiltersAndDefaults() {
        Ec2Service service = mockModeService();
        String vpcId = service.createVpc(REGION, "10.71.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet(REGION, vpcId, "10.71.1.0/24", "us-east-1a").getSubnetId();
        String otherVpc = service.createVpc(REGION, "10.72.0.0/16", false).getVpcId();
        String foreignGroup = service.createSecurityGroup(REGION, "foreign", "x", otherVpc).getGroupId();

        InstanceConnectEndpoint endpoint = service.createInstanceConnectEndpoint(REGION, subnetId, List.of(),
                null, null, List.of(new Tag("Name", "kthw")), null);
        String id = endpoint.getInstanceConnectEndpointId();
        assertTrue(id.startsWith("eice-"), id);
        assertEquals("create-complete", endpoint.getState());
        assertEquals(id + ".localhost.floci.io", endpoint.getDnsName());
        assertEquals(vpcId, endpoint.getVpcId());
        assertEquals("ipv4", endpoint.getIpAddressType());
        assertFalse(endpoint.isPreserveClientIp(), "AWS's default");
        assertEquals(1, endpoint.getSecurityGroupIds().size(), "the VPC's default group when none is given");
        assertTrue(endpoint.getPrivateIpAddress().startsWith("10.71.1."), endpoint.getPrivateIpAddress());

        assertEquals(List.of(id), service.describeInstanceConnectEndpoints(REGION, List.of(),
                Map.of("vpc-id", List.of(vpcId), "state", List.of("create-complete"), "tag:Name", List.of("kthw")))
                .stream().map(InstanceConnectEndpoint::getInstanceConnectEndpointId).toList());
        assertTrue(service.describeInstanceConnectEndpoints(REGION, List.of(),
                Map.of("vpc-id", List.of(otherVpc))).isEmpty());
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class, () -> service
                .describeInstanceConnectEndpoints(REGION, List.of(), Map.of("bogus", List.of("x"))))
                .getErrorCode());
        assertEquals("InvalidParameter", assertThrows(AwsException.class, () -> service
                .createInstanceConnectEndpoint(REGION, subnetId, List.of(foreignGroup), null, null, List.of(), null))
                .getErrorCode());
        assertEquals("InvalidParameterCombination", assertThrows(AwsException.class, () -> service
                .createInstanceConnectEndpoint(REGION, subnetId, List.of(), true, "dualstack", List.of(), null))
                .getErrorCode());
        assertEquals("InvalidSubnetID.NotFound", assertThrows(AwsException.class, () -> service
                .createInstanceConnectEndpoint(REGION, "subnet-0000000000000000f", List.of(), null, null,
                        List.of(), null))
                .getErrorCode());

        assertEquals("delete-in-progress", service.deleteInstanceConnectEndpoint(REGION, id).getState());
        assertEquals("InvalidInstanceConnectEndpointId.NotFound", assertThrows(AwsException.class, () -> service
                .describeInstanceConnectEndpoints(REGION, List.of(id), Map.of())).getErrorCode());
    }

    @Test
    void endpointGroupsRemainUntilTheEndpointIsDeleted() {
        Ec2Service service = mockModeService();
        String vpc = service.createVpc(REGION, "10.73.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(REGION, vpc, "10.73.1.0/24", "us-east-1a").getSubnetId();
        String group = service.createSecurityGroup(REGION, "endpoint-group", "owned endpoint group", vpc).getGroupId();
        String unrelated = service.createSecurityGroup(REGION, "unrelated-group", "unattached group", vpc).getGroupId();
        InstanceConnectEndpoint endpoint = service.createInstanceConnectEndpoint(REGION, subnet, List.of(group),
                null, null, List.of(), null);
        String endpointId = endpoint.getInstanceConnectEndpointId();

        assertEquals("DependencyViolation", assertThrows(AwsException.class,
                () -> service.deleteSecurityGroup(REGION, group)).getErrorCode());
        assertEquals(group, service.describeSecurityGroups(REGION, List.of(group), List.of(), Map.of())
                .getFirst().getGroupId());
        assertEquals(List.of(group), service.describeInstanceConnectEndpoints(REGION, List.of(endpointId), Map.of())
                .getFirst().getSecurityGroupIds());
        service.deleteSecurityGroup(REGION, unrelated);
        assertTrue(service.describeSecurityGroups(REGION, List.of(unrelated), List.of(), Map.of()).isEmpty());

        service.deleteInstanceConnectEndpoint(REGION, endpointId);
        service.deleteSecurityGroup(REGION, group);
        assertTrue(service.describeSecurityGroups(REGION, List.of(group), List.of(), Map.of()).isEmpty());
    }

    @Test
    void aLiveEndpointBlocksDeletingItsSubnetAndVpc() {
        Ec2Service service = mockModeService();
        String vpcId = service.createVpc(REGION, "10.74.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet(REGION, vpcId, "10.74.1.0/24", "us-east-1a").getSubnetId();
        String id = service.createInstanceConnectEndpoint(REGION, subnetId, List.of(), null, null, List.of(), null)
                .getInstanceConnectEndpointId();

        assertEquals("DependencyViolation", assertThrows(AwsException.class,
                () -> service.deleteSubnet(REGION, subnetId)).getErrorCode());
        assertEquals("DependencyViolation", assertThrows(AwsException.class,
                () -> service.deleteVpc(REGION, vpcId)).getErrorCode());

        service.deleteInstanceConnectEndpoint(REGION, id);
        service.deleteSubnet(REGION, subnetId);
        service.deleteVpc(REGION, vpcId);
    }

    @Test
    void aNatGatewayCannotTakeTheAddressAnEndpointHolds() {
        Ec2Service service = mockModeService();
        String vpcId = service.createVpc(REGION, "10.75.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet(REGION, vpcId, "10.75.1.0/24", "us-east-1a").getSubnetId();
        InstanceConnectEndpoint endpoint = service.createInstanceConnectEndpoint(REGION, subnetId, List.of(),
                null, null, List.of(), null);
        String held = endpoint.getPrivateIpAddress();

        assertEquals("InvalidIPAddress.InUse", assertThrows(AwsException.class, () -> service
                .createNatGateway(REGION, subnetId, null, "private", held, List.of())).getErrorCode());

        // Once the endpoint is gone the address is free again, so the refusal above was the endpoint's.
        service.deleteInstanceConnectEndpoint(REGION, endpoint.getInstanceConnectEndpointId());
        assertEquals(held, service.createNatGateway(REGION, subnetId, null, "private", held, List.of())
                .getNatGatewayAddresses().get(0).getPrivateIp());
    }

    @Test
    void aReplayedClientTokenReturnsTheFirstEndpoint() {
        Ec2Service service = mockModeService();
        String vpcId = service.createVpc(REGION, "10.75.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet(REGION, vpcId, "10.75.1.0/24", "us-east-1a").getSubnetId();
        String otherSubnet = service.createSubnet(REGION, vpcId, "10.75.2.0/24", "us-east-1a").getSubnetId();

        String first = service.createInstanceConnectEndpoint(REGION, subnetId, List.of(), null, null, List.of(),
                "token-1").getInstanceConnectEndpointId();
        assertEquals(first, service.createInstanceConnectEndpoint(REGION, subnetId, List.of(), null, null,
                List.of(), "token-1").getInstanceConnectEndpointId());
        assertEquals(1, service.describeInstanceConnectEndpoints(REGION, List.of(), Map.of()).size());
        assertEquals("IdempotentParameterMismatch", assertThrows(AwsException.class, () -> service
                .createInstanceConnectEndpoint(REGION, otherSubnet, List.of(), null, null, List.of(), "token-1"))
                .getErrorCode());
    }

    @Test
    void aReplayedClientTokenWithOtherParametersIsAMismatch() {
        Ec2Service service = mockModeService();
        String vpcId = service.createVpc(REGION, "10.78.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet(REGION, vpcId, "10.78.1.0/24", "us-east-1a").getSubnetId();
        String group = service.createSecurityGroup(REGION, "eice", "x", vpcId).getGroupId();
        List<Tag> tags = List.of(new Tag("Name", "kthw"));
        String first = service.createInstanceConnectEndpoint(REGION, subnetId, List.of(group), false, "ipv4", tags,
                "token-78").getInstanceConnectEndpointId();

        // Defaults applied the same way match: omitted PreserveClientIp and IpAddressType are false and ipv4.
        assertEquals(first, service.createInstanceConnectEndpoint(REGION, subnetId, List.of(group), null, null,
                tags, "token-78").getInstanceConnectEndpointId());
        // Security groups are a set: the same groups in another order are the same request.
        String other = service.createSecurityGroup(REGION, "eice-2", "x", vpcId).getGroupId();
        String ordered = service.createInstanceConnectEndpoint(REGION, subnetId, List.of(group, other), null, null,
                tags, "token-78-sets").getInstanceConnectEndpointId();
        assertEquals(ordered, service.createInstanceConnectEndpoint(REGION, subnetId, List.of(other, group), null,
                null, tags, "token-78-sets").getInstanceConnectEndpointId());
        // A tag added after the create is not a creation parameter.
        service.createTags(REGION, List.of(first), List.of(new Tag("Later", "x")));
        assertEquals(first, service.createInstanceConnectEndpoint(REGION, subnetId, List.of(group), null, null,
                tags, "token-78").getInstanceConnectEndpointId());

        Map<String, Executable> retries = Map.of(
                "security groups", () -> service.createInstanceConnectEndpoint(REGION, subnetId, List.of(), null,
                        null, tags, "token-78"),
                "tags", () -> service.createInstanceConnectEndpoint(REGION, subnetId, List.of(group), null, null,
                        List.of(new Tag("Name", "other")), "token-78"),
                "ip address type", () -> service.createInstanceConnectEndpoint(REGION, subnetId, List.of(group),
                        null, "dualstack", tags, "token-78"),
                "preserve client ip", () -> service.createInstanceConnectEndpoint(REGION, subnetId, List.of(group),
                        true, null, tags, "token-78"));
        retries.forEach((what, retry) -> assertEquals("IdempotentParameterMismatch",
                assertThrows(AwsException.class, retry, what).getErrorCode(), what));
        assertEquals(2, service.describeInstanceConnectEndpoints(REGION, List.of(), Map.of()).size());
    }

    @Test
    void changingAReturnedEndpointLeavesTheStoredOneAlone() {
        Ec2Service service = mockModeService();
        String vpcId = service.createVpc(REGION, "10.76.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet(REGION, vpcId, "10.76.1.0/24", "us-east-1a").getSubnetId();
        InstanceConnectEndpoint created = service.createInstanceConnectEndpoint(REGION, subnetId, List.of(), null,
                null, List.of(new Tag("Name", "kthw")), null);
        created.setState("bogus");
        created.getSecurityGroupIds().clear();
        InstanceConnectEndpoint described = service.describeInstanceConnectEndpoints(REGION, List.of(), Map.of()).get(0);
        described.getTags().clear();

        InstanceConnectEndpoint again = service.describeInstanceConnectEndpoints(REGION, List.of(), Map.of()).get(0);
        assertEquals("create-complete", again.getState());
        assertEquals(1, again.getSecurityGroupIds().size());
        assertEquals(List.of("Name"), again.getTags().stream().map(Tag::getKey).toList());
    }

    @Test
    void describeListsWhatTheClientTokenReplaySees() {
        InMemoryStorageFactory storage = new InMemoryStorageFactory("000000000000");
        Ec2Service service = mockModeService(storage);
        String vpcId = service.createVpc(REGION, "10.77.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet(REGION, vpcId, "10.77.1.0/24", "us-east-1a").getSubnetId();
        String id = service.createInstanceConnectEndpoint(REGION, subnetId, List.of(), null, null, List.of(),
                "token-77").getInstanceConnectEndpointId();
        // Both scans pick a region's endpoints by store key; a record whose region field is unset
        // (written before the field existed, say) is still in the region its key names.
        StorageBackend<String, InstanceConnectEndpoint> store = storage.create("ec2",
                "ec2-instance-connect-endpoints.json", new TypeReference<Map<String, InstanceConnectEndpoint>>() {});
        InstanceConnectEndpoint stored = store.get(REGION + "::" + id).orElseThrow();
        stored.setRegion(null);
        store.put(REGION + "::" + id, stored);

        assertEquals(id, service.createInstanceConnectEndpoint(REGION, subnetId, List.of(), null, null, List.of(),
                "token-77").getInstanceConnectEndpointId());
        assertEquals(List.of(id), service.describeInstanceConnectEndpoints(REGION, List.of(), Map.of()).stream()
                .map(InstanceConnectEndpoint::getInstanceConnectEndpointId).toList());
    }

    @Test
    void concurrentCreatesWithOneClientTokenMakeOneEndpoint() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 50; round++) {
                Ec2Service service = mockModeService();
                String vpcId = service.createVpc(REGION, "10.73.0.0/16", false).getVpcId();
                String subnetId = service.createSubnet(REGION, vpcId, "10.73.1.0/24", "us-east-1a").getSubnetId();
                CountDownLatch go = new CountDownLatch(1);
                List<Future<String>> ids = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    ids.add(pool.submit(() -> {
                        go.await();
                        return service.createInstanceConnectEndpoint(REGION, subnetId, List.of(), null, null,
                                List.of(), "token-" + subnetId).getInstanceConnectEndpointId();
                    }));
                }
                go.countDown();
                Set<String> distinct = ConcurrentHashMap.newKeySet();
                for (Future<String> id : ids) {
                    distinct.add(id.get());
                }
                assertEquals(1, distinct.size(), "round " + round + ": " + distinct);
                assertEquals(1, service.describeInstanceConnectEndpoints(REGION, List.of(), Map.of()).size());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aSubnetDeletedDuringACreateNeverLeavesAnEndpointOnIt() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 200; round++) {
                Ec2Service service = mockModeService();
                String vpcId = service.createVpc(REGION, "10.74.0.0/16", false).getVpcId();
                String subnetId = service.createSubnet(REGION, vpcId, "10.74.1.0/24", "us-east-1a").getSubnetId();
                CountDownLatch go = new CountDownLatch(1);
                Future<?> create = pool.submit(() -> {
                    go.await();
                    try {
                        service.createInstanceConnectEndpoint(REGION, subnetId, List.of(), null, null, List.of(), null);
                    } catch (AwsException expected) {
                        // InvalidSubnetID.NotFound: the delete won.
                    }
                    return null;
                });
                Future<?> delete = pool.submit(() -> {
                    go.await();
                    try {
                        service.deleteSubnet(REGION, subnetId);
                    } catch (AwsException expected) {
                        // DependencyViolation: the create won.
                    }
                    return null;
                });
                go.countDown();
                create.get();
                delete.get();
                boolean subnetGone = service.describeSubnets(REGION, List.of(), Map.of()).stream()
                        .noneMatch(subnet -> subnetId.equals(subnet.getSubnetId()));
                boolean endpointOnIt = service.describeInstanceConnectEndpoints(REGION, List.of(), Map.of()).stream()
                        .anyMatch(endpoint -> subnetId.equals(endpoint.getSubnetId()));
                assertFalse(subnetGone && endpointOnIt, "round " + round + ": endpoint left on a deleted subnet");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void changingAReturnedOrRequestedTagLeavesTheCreationParametersAlone() {
        Ec2Service service = mockModeService();
        String vpcId = service.createVpc(REGION, "10.81.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet(REGION, vpcId, "10.81.1.0/24", "us-east-1a").getSubnetId();
        Tag requested = new Tag("Name", "kthw");
        String id = service.createInstanceConnectEndpoint(REGION, subnetId, List.of(), null, null,
                List.of(requested), "token-81").getInstanceConnectEndpointId();
        requested.setValue("changed-after-create");
        service.describeInstanceConnectEndpoints(REGION, List.of(), Map.of()).get(0)
                .getCreationTags().get(0).setValue("changed-on-a-copy");

        assertEquals(id, service.createInstanceConnectEndpoint(REGION, subnetId, List.of(), null, null,
                List.of(new Tag("Name", "kthw")), "token-81").getInstanceConnectEndpointId());
    }

    @Test
    void anUnknownFilterNameFailsWhenNoEndpointIsTestedAgainstIt() {
        Ec2Service service = mockModeService();
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class, () -> service
                .describeInstanceConnectEndpoints(REGION, List.of(), Map.of("bogus", List.of("x"))))
                .getErrorCode());

        String vpcId = service.createVpc(REGION, "10.80.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet(REGION, vpcId, "10.80.1.0/24", "us-east-1a").getSubnetId();
        service.createInstanceConnectEndpoint(REGION, subnetId, List.of(), null, null, List.of(), null);
        // An earlier filter that excludes every endpoint does not hide the bad name after it.
        List<Map.Entry<String, List<String>>> filters = List.of(
                Map.entry("vpc-id", List.of("vpc-00000000000000000")), Map.entry("bogus", List.of("x")));
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class, () -> service
                .describeInstanceConnectEndpoints(REGION, List.of(), filters)).getErrorCode());
    }

    @Test
    void tagValueMatchesAnEndpointCarryingThatValueUnderAnyKey() {
        Ec2Service service = mockModeService();
        String vpcId = service.createVpc(REGION, "10.81.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet(REGION, vpcId, "10.81.1.0/24", "us-east-1a").getSubnetId();
        String id = service.createInstanceConnectEndpoint(REGION, subnetId, List.of(), null, null,
                List.of(new Tag("Name", "kthw"), new Tag("Team", "infra")), null).getInstanceConnectEndpointId();

        assertEquals(List.of(id), service.describeInstanceConnectEndpoints(REGION, List.of(),
                Map.of("tag-value", List.of("nope", "infra")))
                .stream().map(InstanceConnectEndpoint::getInstanceConnectEndpointId).toList());
        assertTrue(service.describeInstanceConnectEndpoints(REGION, List.of(),
                Map.of("tag-value", List.of("Team"))).isEmpty(), "a key is not a value");
    }

    @Test
    void availabilityZoneAndOwnerIdAreRefusedLikeAnyFilterAwsDoesNotList() {
        Ec2Service service = mockModeService();
        String vpcId = service.createVpc(REGION, "10.82.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet(REGION, vpcId, "10.82.1.0/24", "us-east-1a").getSubnetId();
        service.createInstanceConnectEndpoint(REGION, subnetId, List.of(), null, null, List.of(), null);
        AwsException unknown = assertThrows(AwsException.class, () -> service
                .describeInstanceConnectEndpoints(REGION, List.of(), Map.of("bogus", List.of("x"))));
        for (String name : List.of("availability-zone", "owner-id")) {
            AwsException error = assertThrows(AwsException.class, () -> service
                    .describeInstanceConnectEndpoints(REGION, List.of(), Map.of(name, List.of("x"))), name);
            assertEquals(unknown.getErrorCode(), error.getErrorCode(), name);
            assertEquals(unknown.getHttpStatus(), error.getHttpStatus(), name);
            assertEquals("The filter '" + name + "' is invalid", error.getMessage(), name);
        }
    }

    @Test
    void theEndpointsInterfaceResolvesInDescribeNetworkInterfacesUntilItIsDeleted() {
        Ec2Service service = mockModeService();
        String vpcId = service.createVpc(REGION, "10.79.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet(REGION, vpcId, "10.79.1.0/24", "us-east-1a").getSubnetId();
        String otherSubnet = service.createSubnet(REGION, vpcId, "10.79.2.0/24", "us-east-1a").getSubnetId();
        String group = service.createSecurityGroup(REGION, "eice-sg", "x", vpcId).getGroupId();
        InstanceConnectEndpoint endpoint = service.createInstanceConnectEndpoint(REGION, subnetId, List.of(group),
                null, null, List.of(), null);
        String eniId = endpoint.getNetworkInterfaceIds().get(0);

        NetworkInterface ni = service.describeNetworkInterfaces(REGION, List.of(eniId), Map.of())
                .networkInterfaces().get(0);
        assertEquals(eniId, ni.getNetworkInterfaceId());
        assertEquals(subnetId, ni.getSubnetId());
        assertEquals(vpcId, ni.getVpcId());
        assertEquals("us-east-1a", ni.getAvailabilityZone());
        assertEquals(endpoint.getPrivateIpAddress(), ni.getPrivateIpAddress());
        assertEquals(List.of(group), ni.getGroups().stream().map(GroupIdentifier::getGroupId).toList());
        assertEquals(List.of("eice-sg"), ni.getGroups().stream().map(GroupIdentifier::getGroupName).toList());
        assertTrue(ni.isRequesterManaged());
        assertEquals("ec2_instance_connect_endpoint", ni.getInterfaceType());
        assertEquals("EC2 Instance Connect Endpoint " + endpoint.getInstanceConnectEndpointId(), ni.getDescription());

        // Filters see it the way they see any other interface, and only once.
        assertEquals(List.of(eniId), service.describeNetworkInterfaces(REGION, List.of(),
                Map.of("subnet-id", List.of(subnetId))).networkInterfaces().stream()
                .map(NetworkInterface::getNetworkInterfaceId).toList());
        assertEquals(List.of(eniId), service.describeNetworkInterfaces(REGION, List.of(),
                Map.of("interface-type", List.of("ec2_instance_connect_endpoint"), "group-id", List.of(group)))
                .networkInterfaces().stream().map(NetworkInterface::getNetworkInterfaceId).toList());
        assertTrue(service.describeNetworkInterfaces(REGION, List.of(), Map.of("subnet-id", List.of(otherSubnet)))
                .networkInterfaces().isEmpty());

        // A tag on the interface is the interface's own and goes with it.
        service.createTags(REGION, List.of(eniId), List.of(new Tag("Role", "eice")));
        assertEquals(List.of("Role"), service.describeNetworkInterfaces(REGION, List.of(eniId), Map.of())
                .networkInterfaces().get(0).getTagSet().stream().map(Tag::getKey).toList());

        service.deleteInstanceConnectEndpoint(REGION, endpoint.getInstanceConnectEndpointId());
        assertTrue(service.describeTags(REGION, Map.of("resource-id", List.of(eniId))).isEmpty());
        assertEquals("InvalidNetworkInterfaceID.NotFound", assertThrows(AwsException.class, () -> service
                .describeNetworkInterfaces(REGION, List.of(eniId), Map.of())).getErrorCode());
    }


    @Test
    void tunnelReachNeedsTheEndpointsEgressAndTheInstancesIngress() {
        Ec2Service service = mockModeService();
        String vpcId = service.createVpc(REGION, "10.73.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet(REGION, vpcId, "10.73.1.0/24", "us-east-1a").getSubnetId();
        String eiceGroup = service.createSecurityGroup(REGION, "eice", "x", vpcId).getGroupId();
        String hostGroup = service.createSecurityGroup(REGION, "host", "x", vpcId).getGroupId();
        service.revokeSecurityGroupEgress(REGION, eiceGroup, List.of(permission("-1", null, null, null, "0.0.0.0/0")));
        service.authorizeSecurityGroupEgress(REGION, eiceGroup, List.of(permission("tcp", 22, 22, hostGroup, null)));
        Instance instance = service.runInstances(REGION, "ami-1234567890abcdef0", "t3.micro", 1, 1, null,
                List.of(hostGroup), subnetId, null, List.of(), null, null).getInstances().getFirst();
        InstanceConnectEndpoint endpoint = service.createInstanceConnectEndpoint(REGION, subnetId,
                List.of(eiceGroup), null, null, List.of(), null);

        assertFalse(service.instanceConnectEndpointMayReach(endpoint, instance, 22), "no ingress on the host yet");
        service.authorizeSecurityGroupIngress(REGION, hostGroup, List.of(permission("tcp", 22, 22, eiceGroup, null)));
        assertTrue(service.instanceConnectEndpointMayReach(endpoint, instance, 22));
        assertFalse(service.instanceConnectEndpointMayReach(endpoint, instance, 3389), "the endpoint may only send 22");
        assertTrue(service.findRunningInstanceByPrivateIp(REGION, vpcId, instance.getPrivateIpAddress()).isEmpty(),
                "a pending instance is not a tunnel target");
        // Mock mode has no container to report the launch; mark it running as the launch would.
        service.getInstance(REGION, instance.getInstanceId()).orElseThrow().setState(InstanceState.running());
        assertEquals(instance.getInstanceId(), service.findRunningInstanceByPrivateIp(REGION, vpcId,
                instance.getPrivateIpAddress()).orElseThrow().getInstanceId());

        service.deleteInstanceConnectEndpoint(REGION, endpoint.getInstanceConnectEndpointId());
        assertTrue(service.findUsableInstanceConnectEndpoint(REGION, endpoint.getInstanceConnectEndpointId())
                .isEmpty());
    }

    @Test
    void tunnelReachHonoursPrefixListRules() {
        Ec2Service service = mockModeService();
        String vpcId = service.createVpc(REGION, "10.74.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet(REGION, vpcId, "10.74.1.0/24", "us-east-1a").getSubnetId();
        String eiceGroup = service.createSecurityGroup(REGION, "eice", "x", vpcId).getGroupId();
        String hostGroup = service.createSecurityGroup(REGION, "host", "x", vpcId).getGroupId();
        String subnetList = service.createManagedPrefixList(REGION, "subnet", "IPv4", 2,
                List.of(new PrefixListEntry("10.74.1.0/24", null)), List.of()).getPrefixListId();
        service.revokeSecurityGroupEgress(REGION, eiceGroup, List.of(permission("-1", null, null, null, "0.0.0.0/0")));
        service.authorizeSecurityGroupEgress(REGION, eiceGroup, List.of(prefixListPermission(subnetList)));
        Instance instance = service.runInstances(REGION, "ami-1234567890abcdef0", "t3.micro", 1, 1, null,
                List.of(hostGroup), subnetId, null, List.of(), null, null).getInstances().getFirst();
        InstanceConnectEndpoint endpoint = service.createInstanceConnectEndpoint(REGION, subnetId,
                List.of(eiceGroup), null, null, List.of(), null);

        assertFalse(service.instanceConnectEndpointMayReach(endpoint, instance, 22), "no ingress on the host yet");
        service.authorizeSecurityGroupIngress(REGION, hostGroup, List.of(prefixListPermission(subnetList)));
        assertTrue(service.instanceConnectEndpointMayReach(endpoint, instance, 22),
                "both sides name the subnet through a prefix list");
    }

    private static IpPermission prefixListPermission(String prefixListId) {
        IpPermission permission = permission("tcp", 22, 22, null, null);
        permission.setPrefixListIds(new ArrayList<>(List.of(new PrefixListId(prefixListId, null))));
        return permission;
    }

    private static IpPermission permission(String protocol, Integer from, Integer to, String groupId, String cidr) {
        IpPermission permission = new IpPermission();
        permission.setIpProtocol(protocol);
        permission.setFromPort(from);
        permission.setToPort(to);
        if (groupId != null) {
            UserIdGroupPair pair = new UserIdGroupPair();
            pair.setGroupId(groupId);
            permission.setUserIdGroupPairs(new ArrayList<>(List.of(pair)));
        }
        if (cidr != null) {
            IpRange range = new IpRange();
            range.setCidrIp(cidr);
            permission.setIpRanges(new ArrayList<>(List.of(range)));
        }
        return permission;
    }
}
