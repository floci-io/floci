package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.Placement;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import io.github.hectorvent.floci.core.common.AwsException;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;

/**
 * {@code AWS::EC2::Instance} in isolation: the attributes Ref and Fn::GetAtt read once the launch
 * completes, launch-template fields filling what the properties leave unset, and the delete.
 * The launch-failure and rollback-marker paths are covered through the dispatcher by
 * {@code CloudFormationEc2ProvisionerTest}.
 */
class Ec2InstanceCfnProvisionerTest {

    private static final String TYPE = "AWS::EC2::Instance";

    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final Ec2InstanceCfnProvisioner provisioner = new Ec2InstanceCfnProvisioner(ec2);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void launchPublishesTheInstanceAttributes() throws Exception {
        Instance instance = new Instance();
        instance.setInstanceId("i-1");
        instance.setPrivateIpAddress("10.0.0.5");
        instance.setPublicIpAddress("54.0.0.5");
        instance.setPrivateDnsName("ip-10-0-0-5.ec2.internal");
        instance.setPublicDnsName("ec2-54-0-0-5.compute-1.amazonaws.com");
        instance.setPlacement(new Placement("us-east-1a"));
        stubLaunch(instance);
        StackResource r = resource("Server");

        provisioner.provision(r, props("{\"ImageId\": \"ami-1\"}"), ctx());

        assertEquals("i-1", r.getPhysicalId());
        assertEquals(Set.of("InstanceId", "PrivateIp", "PublicIp", "PrivateDnsName", "PublicDnsName",
                "AvailabilityZone"), r.getAttributes().keySet());
        assertEquals("10.0.0.5", r.getAttributes().get("PrivateIp"));
        assertEquals("us-east-1a", r.getAttributes().get("AvailabilityZone"));
        verify(ec2).awaitContainerLaunch(instance);
    }

    @Test
    void launchTemplateFillsWhatThePropertiesLeaveUnset() throws Exception {
        LaunchTemplateData data = new LaunchTemplateData();
        data.setImageId("ami-from-template");
        data.setInstanceType("m5.large");
        data.setKeyName("template-key");
        when(ec2.resolveLaunchTemplateData("us-east-1", "lt-1", null, "1")).thenReturn(data);
        Instance instance = new Instance();
        instance.setInstanceId("i-2");
        stubLaunch(instance);

        provisioner.provision(resource("Server"), props("""
                {"LaunchTemplate": {"LaunchTemplateId": "lt-1", "Version": "1"}}
                """), ctx());

        verify(ec2).runInstances(eq("us-east-1"), eq("ami-from-template"), eq("m5.large"), eq(1), eq(1),
                eq("template-key"), anyList(), any(), any(), anyList(), any(), any(), any());
    }

    @Test
    void provisionsAnInstanceDeclaredWithNoProperties() {
        Instance instance = new Instance();
        instance.setInstanceId("i-3");
        stubLaunch(instance);
        StackResource r = resource("Server");

        // A bare {"Type": "AWS::EC2::Instance"} has no Properties, so props is null. The registry
        // schema lists no required properties, so this is a valid template.
        provisioner.provision(r, null, ctx());

        assertEquals("i-3", r.getPhysicalId());
        assertEquals("i-3", r.getAttributes().get("InstanceId"));
    }

    @Test
    void deleteTerminatesTheInstance() {
        provisioner.delete(TYPE, "i-1", "us-east-1");

        verify(ec2).terminateInstances("us-east-1", List.of("i-1"));
    }

    @Test
    void deleteToleratesAnInstanceAlreadyGone() {
        doThrow(new AwsException("InvalidInstanceID.NotFound", "gone", 400))
                .when(ec2).terminateInstances("us-east-1", List.of("i-1"));

        assertDoesNotThrow(() -> provisioner.delete(TYPE, "i-1", "us-east-1"));
    }

    @Test
    void deletePropagatesAnUnexpectedError() {
        doThrow(new AwsException("DependencyViolation", "still in use", 400))
                .when(ec2).terminateInstances("us-east-1", List.of("i-1"));

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.delete(TYPE, "i-1", "us-east-1"));
        assertEquals("DependencyViolation", failure.getErrorCode());
    }

    private void stubLaunch(Instance instance) {
        Reservation reservation = new Reservation();
        reservation.getInstances().add(instance);
        when(ec2.runInstances(anyString(), any(), anyString(), anyInt(), anyInt(), any(), anyList(),
                any(), any(), anyList(), any(), any(), any())).thenReturn(reservation);
    }

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
    }

    private JsonNode props(String json) throws Exception {
        return mapper.readTree(json);
    }

    private static StackResource resource(String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(TYPE);
        r.setAttributes(new HashMap<>());
        return r;
    }
}
