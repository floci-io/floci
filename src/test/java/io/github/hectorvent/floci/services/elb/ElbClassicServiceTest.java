package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.elb.model.ClassicHealthCheck;
import io.github.hectorvent.floci.services.elb.model.ClassicListener;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ElbClassicServiceTest {

    private static final String REGION = "us-west-2";

    @Mock
    Ec2Service ec2Service;

    @Mock
    ElbClassicHealthChecker healthChecker;

    private ElbClassicService service;

    @BeforeEach
    void setUp() {
        service = new ElbClassicService(ec2Service, healthChecker, null, null);
        lenient().when(ec2Service.describeSubnets(anyString(), anyList(), any()))
                .thenAnswer(invocation -> {
                    List<String> ids = invocation.getArgument(1);
                    return ids.stream().map(id -> {
                        Subnet subnet = new Subnet();
                        subnet.setSubnetId(id);
                        subnet.setVpcId("vpc-1");
                        subnet.setAvailabilityZone(REGION + id.substring(id.length() - 1));
                        return subnet;
                    }).toList();
                });
    }

    private static ClassicListener httpListener() {
        ClassicListener listener = new ClassicListener();
        listener.setProtocol("HTTP");
        listener.setLoadBalancerPort(80);
        listener.setInstanceProtocol("HTTP");
        listener.setInstancePort(8080);
        return listener;
    }

    private ClassicLoadBalancer create(String name) {
        return service.createLoadBalancer(REGION, name, List.of(httpListener()),
                List.of(), List.of("subnet-a"), List.of("sg-1"), null, Map.of());
    }

    @Test
    void createGivesADnsNameAndTheAwsDefaultHealthCheck() {
        ClassicLoadBalancer lb = create("my-elb");

        assertTrue(lb.getDnsName().startsWith("my-elb-"));
        assertEquals("internet-facing", lb.getScheme());
        assertEquals("vpc-1", lb.getVpcId());
        assertEquals(List.of(REGION + "a"), lb.getAvailabilityZones());
        // AWS's documented defaults for a load balancer that has never been configured.
        assertEquals("TCP:80", lb.getHealthCheck().getTarget());
        assertEquals(30, lb.getHealthCheck().getInterval());
        assertEquals(10, lb.getHealthCheck().getHealthyThreshold());
        verify(healthChecker).startMonitoring(lb);
    }

    @Test
    void createRejectsAMissingNameWithTheClassicParameterName() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createLoadBalancer(REGION, null, List.of(httpListener()),
                        List.of(), List.of(), List.of(), null, Map.of()));
        assertEquals("ValidationError", e.getErrorCode());
        assertTrue(e.getMessage().contains("LoadBalancerName"), e.getMessage());
    }

    @Test
    void createRejectsAListenerMissingARequiredMember() {
        ClassicListener incomplete = new ClassicListener();
        incomplete.setProtocol("HTTP");
        incomplete.setLoadBalancerPort(80);
        AwsException e = assertThrows(AwsException.class, () ->
                service.createLoadBalancer(REGION, "my-elb", List.of(incomplete),
                        List.of(), List.of(), List.of(), null, Map.of()));
        assertEquals("ValidationError", e.getErrorCode());
    }

    @Test
    void createRejectsAnEmptyListenerListNamingTheMissingMember() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createLoadBalancer(REGION, "my-elb", List.of(),
                        List.of(), List.of("subnet-a"), List.of("sg-1"), null, Map.of()));
        assertEquals("ValidationError", e.getErrorCode());
        assertTrue(e.getMessage().contains("listener"), e.getMessage());
    }

    /**
     * CreateLoadBalancer's Errors table documents InvalidConfigurationRequest at HTTP 409, not
     * 400 — a client that retries on 409 but fails hard on 400 depends on the difference.
     */
    @Test
    void subnetsSpanningTwoVpcsAreRejectedWithTheDocumentedStatus() {
        when(ec2Service.describeSubnets(anyString(), anyList(), any()))
                .thenAnswer(invocation -> {
                    List<String> ids = invocation.getArgument(1);
                    return ids.stream().map(id -> {
                        Subnet subnet = new Subnet();
                        subnet.setSubnetId(id);
                        subnet.setVpcId("vpc-" + id.charAt(id.length() - 1));
                        subnet.setAvailabilityZone(REGION + id.charAt(id.length() - 1));
                        return subnet;
                    }).toList();
                });

        AwsException e = assertThrows(AwsException.class, () ->
                service.createLoadBalancer(REGION, "my-elb", List.of(httpListener()),
                        List.of(), List.of("subnet-a", "subnet-b"), List.of("sg-1"), null, Map.of()));
        assertEquals("InvalidConfigurationRequest", e.getErrorCode());
        assertEquals(409, e.getHttpStatus());
    }

    @Test
    void createRejectsADuplicateName() {
        create("my-elb");
        AwsException e = assertThrows(AwsException.class, () -> create("my-elb"));
        assertEquals("DuplicateLoadBalancerName", e.getErrorCode());
    }

    @Test
    void loadBalancersAreScopedByRegion() {
        create("my-elb");
        assertTrue(service.hasLoadBalancer(REGION, "my-elb"));
        assertTrue(service.describeLoadBalancers("eu-west-1", null).isEmpty());
    }

    @Test
    void configureHealthCheckStoresAndRearmsTheChecker() {
        ClassicLoadBalancer lb = create("my-elb");
        ClassicHealthCheck hc = new ClassicHealthCheck();
        hc.setTarget("HTTP:8080/health");
        hc.setInterval(10);
        hc.setTimeout(3);
        hc.setHealthyThreshold(2);
        hc.setUnhealthyThreshold(2);

        service.configureHealthCheck(REGION, "my-elb", hc);

        assertEquals("HTTP:8080/health", lb.getHealthCheck().getTarget());
        verify(healthChecker).healthCheckChanged(lb);
    }

    @Test
    void configureHealthCheckEnforcesTheRangesTheModelDeclares() {
        create("my-elb");
        ClassicHealthCheck hc = new ClassicHealthCheck();
        hc.setTarget("HTTP:8080/");
        hc.setInterval(301);
        hc.setTimeout(3);
        hc.setHealthyThreshold(2);
        hc.setUnhealthyThreshold(2);

        AwsException e = assertThrows(AwsException.class,
                () -> service.configureHealthCheck(REGION, "my-elb", hc));
        assertEquals("ValidationError", e.getErrorCode());
    }

    @Test
    void registerAndDeregisterInstancesAreIdempotent() {
        create("my-elb");
        service.registerInstances(REGION, "my-elb", List.of("i-1"));
        assertEquals(List.of("i-1"), service.registerInstances(REGION, "my-elb", List.of("i-1")));
        assertEquals(List.of(), service.deregisterInstances(REGION, "my-elb", List.of("i-1")));
        verify(healthChecker).deregisterInstances(REGION, "my-elb", List.of("i-1"));
    }

    @Test
    void describeInstanceHealthRejectsAnInstanceThatIsNotRegistered() {
        create("my-elb");
        AwsException e = assertThrows(AwsException.class,
                () -> service.describeInstanceHealth(REGION, "my-elb", List.of("i-unknown")));
        assertEquals("InvalidInstance", e.getErrorCode());
    }

    @Test
    void modifyAttributesLeavesUnsentMembersAtTheirDefaults() {
        create("my-elb");
        service.modifyLoadBalancerAttributes(REGION, "my-elb",
                new ElbClassicService.AttributeUpdate(true, null, null, null, null,
                        true, 300, null, Map.of()));

        ClassicLoadBalancer lb = service.requireLoadBalancer(REGION, "my-elb");
        assertTrue(lb.getAttributes().isCrossZoneLoadBalancingEnabled());
        assertTrue(lb.getAttributes().isConnectionDrainingEnabled());
        assertEquals(300, lb.getAttributes().getConnectionDrainingTimeout());
        assertEquals(60, lb.getAttributes().getIdleTimeout());
        assertNull(lb.getAttributes().getAccessLogS3BucketName());
    }

    @Test
    void deleteIsSilentForALoadBalancerThatDoesNotExist() {
        service.deleteLoadBalancer(REGION, "never-existed");
        assertThrows(AwsException.class, () -> service.requireLoadBalancer(REGION, "never-existed"));
    }

    @Test
    void addingAConflictingListenerOnAPortInUseIsRejected() {
        create("my-elb");
        ClassicListener conflicting = new ClassicListener();
        conflicting.setProtocol("TCP");
        conflicting.setLoadBalancerPort(80);
        conflicting.setInstancePort(9999);

        AwsException e = assertThrows(AwsException.class, () ->
                service.createLoadBalancerListeners(REGION, "my-elb", List.of(conflicting)));
        assertEquals("DuplicateListener", e.getErrorCode());
    }

    @Test
    void repeatingAnIdenticalListenerIsAccepted() {
        ClassicLoadBalancer lb = create("my-elb");
        service.createLoadBalancerListeners(REGION, "my-elb", List.of(httpListener()));
        assertEquals(1, lb.getListeners().size());
    }
}
