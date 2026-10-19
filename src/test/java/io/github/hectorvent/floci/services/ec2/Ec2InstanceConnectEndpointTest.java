package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.model.InstanceConnectEndpoint;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CreateInstanceConnectEndpoint, DescribeInstanceConnectEndpoints and DeleteInstanceConnectEndpoint. */
class Ec2InstanceConnectEndpointTest {

    private static final String REGION = "us-east-1";

    static Ec2Service newService() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(true);
        return new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory("000000000000"));
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
}
