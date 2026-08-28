package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnRollback;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CloudFormationResourceRegistry;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudFormationEc2ProvisionerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private Ec2Service ec2Service;
    private CloudFormationResourceProvisioner provisioner;

    @BeforeEach
    void setUp() {
        ec2Service = mock(Ec2Service.class);
        provisioner = new CloudFormationResourceProvisioner(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, mapper, null, null, null, null, null, null,
                ec2Service, null, null, null, null, null, null, null,
                null, null, new CloudFormationResourceRegistry(List.of()), null);
    }

    @Test
    void terminatedContainerLaunchMarksEc2ResourceCreateFailed() throws Exception {
        Instance instance = new Instance();
        instance.setInstanceId("i-launch-failed");
        instance.setState(InstanceState.terminated());
        Reservation reservation = new Reservation();
        reservation.getInstances().add(instance);
        when(ec2Service.runInstances(anyString(), anyString(), anyString(), anyInt(), anyInt(), any(), anyList(),
                any(), any(), anyList(), any(), any(), any())).thenReturn(reservation);
        doThrow(new AwsException("InternalError", "EC2 instance i-launch-failed failed to launch because its container terminated during launch", 500))
                .when(ec2Service).awaitContainerLaunch(instance);

        StackResource resource = provisioner.provision("Server", "AWS::EC2::Instance", properties(), engine(),
                "us-east-1", "000000000000", "test-stack");

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertEquals("i-launch-failed", resource.getPhysicalId());
        assertEquals("true", resource.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
        assertTrue(resource.getStatusReason().contains("container terminated during launch"));
        verify(ec2Service).awaitContainerLaunch(instance);
    }

    @Test
    void timedOutContainerLaunchMarksEc2ResourceCreateFailed() throws Exception {
        Instance instance = new Instance();
        instance.setInstanceId("i-launch-timed-out");
        instance.setState(InstanceState.pending());
        Reservation reservation = new Reservation();
        reservation.getInstances().add(instance);
        when(ec2Service.runInstances(anyString(), anyString(), anyString(), anyInt(), anyInt(), any(), anyList(),
                any(), any(), anyList(), any(), any(), any())).thenReturn(reservation);
        doThrow(new AwsException("InternalError",
                "EC2 instance i-launch-timed-out failed to launch because it did not reach running state before the launch timeout",
                500)).when(ec2Service).awaitContainerLaunch(instance);

        StackResource resource = provisioner.provision("Server", "AWS::EC2::Instance", properties(), engine(),
                "us-east-1", "000000000000", "test-stack");

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertEquals("i-launch-timed-out", resource.getPhysicalId());
        assertEquals("true", resource.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
        assertTrue(resource.getStatusReason().contains("launch timeout"));
        verify(ec2Service).awaitContainerLaunch(instance);
    }

    @Test
    void successfulContainerLaunchDoesNotKeepRollbackOwnershipMarker() throws Exception {
        Instance instance = new Instance();
        instance.setInstanceId("i-launch-succeeded");
        instance.setState(InstanceState.running());
        Reservation reservation = new Reservation();
        reservation.getInstances().add(instance);
        when(ec2Service.runInstances(anyString(), anyString(), anyString(), anyInt(), anyInt(), any(), anyList(),
                any(), any(), anyList(), any(), any(), any())).thenReturn(reservation);

        StackResource resource = provisioner.provision("Server", "AWS::EC2::Instance", properties(), engine(),
                "us-east-1", "000000000000", "test-stack");

        assertEquals("CREATE_COMPLETE", resource.getStatus());
        assertNull(resource.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
        verify(ec2Service).awaitContainerLaunch(instance);
    }

    private JsonNode properties() throws Exception {
        return mapper.readTree("""
                {"ImageId":"ami-12345678","InstanceType":"t3.micro"}
                """);
    }

    private CloudFormationTemplateEngine engine() {
        return new CloudFormationTemplateEngine("000000000000", "us-east-1", "test-stack", "stack/id",
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), mapper, (Function<String, String>) name -> null);
    }
}
