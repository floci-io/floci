package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.ec2.model.InstanceConnectEndpoint;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CreateInstanceConnectEndpoint, DescribeInstanceConnectEndpoints and DeleteInstanceConnectEndpoint. */
class Ec2InstanceConnectEndpointServiceTest {

    private static final String REGION = "us-east-1";

    static Ec2Service newService() {
        return newService(new InMemoryStorageFactory("000000000000"));
    }

    static Ec2Service newService(InMemoryStorageFactory storage) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(true);
        return new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                storage);
    }

    @Test
    void lifecycleFiltersAndDefaults() {
        Ec2Service service = newService();
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
    void aLiveEndpointBlocksDeletingItsSubnetAndVpc() {
        Ec2Service service = newService();
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
    void aReplayedClientTokenReturnsTheFirstEndpoint() {
        Ec2Service service = newService();
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
        Ec2Service service = newService();
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
        Ec2Service service = newService();
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
        Ec2Service service = newService(storage);
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
                Ec2Service service = newService();
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
                Ec2Service service = newService();
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
}
