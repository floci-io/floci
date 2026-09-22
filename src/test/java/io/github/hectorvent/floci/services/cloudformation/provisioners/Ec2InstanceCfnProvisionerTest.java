package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.GroupIdentifier;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.Placement;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void aLaunchTemplateThatDoesNotResolveFailsTheInstance() throws Exception {
        when(ec2.resolveLaunchTemplateData("us-east-1", "lt-missing", null, null))
                .thenThrow(new AwsException("InvalidLaunchTemplateId.NotFound",
                        "The specified launch template does not exist.", 400));

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(resource("Server"), props("""
                {"LaunchTemplate": {"LaunchTemplateId": "lt-missing"}}
                """), ctx()));

        assertEquals("InvalidLaunchTemplateId.NotFound", e.getErrorCode());
    }

    @Test
    void aLaunchTemplateResolvingToNoValueLaunchesWithoutIt() throws Exception {
        Instance instance = new Instance();
        instance.setInstanceId("i-nolt");
        stubLaunch(instance);

        // An Fn::If selecting AWS::NoValue resolves to an empty node, which on AWS means the property
        // is absent: the instance launches without a template rather than failing to resolve one.
        StackResource r = resource("Server");
        provisioner.provision(r, props("""
                {"LaunchTemplate": ""}
                """), ctx());

        assertEquals("i-nolt", r.getPhysicalId());
        verify(ec2).runInstances(eq("us-east-1"), any(), eq("t3.micro"), eq(1), eq(1), any(),
                anyList(), any(), any(), anyList(), any(), any(), any());
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

    @Test
    void updateWithoutACreateOnlyChangeReusesTheInstance() throws Exception {
        Instance i1 = new Instance();
        i1.setInstanceId("i-1");
        i1.setImageId("ami-1");
        stubLaunch(i1);
        StackResource r = resource("Server");
        provisioner.provision(r, props("{\"ImageId\": \"ami-1\"}"), ctx(null));
        assertEquals("i-1", r.getPhysicalId());

        when(ec2.describeInstances("us-east-1", List.of("i-1"), null))
                .thenReturn(List.of(reservationOf(i1)));
        provisioner.provision(r, props("{\"ImageId\": \"ami-1\", \"InstanceType\": \"t3.small\"}"),
                ctx("i-1"));

        assertEquals("i-1", r.getPhysicalId());
        verify(ec2, times(1)).runInstances(anyString(), any(), anyString(), anyInt(), anyInt(), any(),
                anyList(), any(), any(), anyList(), any(), any(), any());
    }

    @Test
    void changingImageIdReplacesTheInstanceAndRecordsTheCleanup() throws Exception {
        Instance i1 = new Instance();
        i1.setInstanceId("i-1");
        i1.setImageId("ami-1");
        Instance i2 = new Instance();
        i2.setInstanceId("i-2");
        i2.setImageId("ami-2");
        when(ec2.runInstances(anyString(), any(), anyString(), anyInt(), anyInt(), any(), anyList(),
                any(), any(), anyList(), any(), any(), any()))
                .thenReturn(reservationOf(i1), reservationOf(i2));
        StackResource r = resource("Server");
        provisioner.provision(r, props("{\"ImageId\": \"ami-1\"}"), ctx(null));
        assertEquals("i-1", r.getPhysicalId());

        when(ec2.describeInstances("us-east-1", List.of("i-1"), null))
                .thenReturn(List.of(reservationOf(i1)));
        provisioner.provision(r, props("{\"ImageId\": \"ami-2\"}"), ctx("i-1"));

        assertEquals("i-2", r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals("i-1", provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void changingPrivateIpAddressReplacesTheInstanceAndRecordsTheCleanup() throws Exception {
        Instance i1 = new Instance();
        i1.setInstanceId("i-1");
        i1.setImageId("ami-1");
        Instance i2 = new Instance();
        i2.setInstanceId("i-2");
        i2.setImageId("ami-1");
        when(ec2.runInstances(anyString(), any(), anyString(), anyInt(), anyInt(), any(), anyList(),
                any(), any(), anyList(), any(), any(), any()))
                .thenReturn(reservationOf(i1), reservationOf(i2));
        StackResource r = resource("Server");
        provisioner.provision(r, props("{\"ImageId\": \"ami-1\", \"PrivateIpAddress\": \"10.0.0.5\"}"), ctx(null));
        assertEquals("i-1", r.getPhysicalId());

        when(ec2.describeInstances("us-east-1", List.of("i-1"), null))
                .thenReturn(List.of(reservationOf(i1)));
        provisioner.provision(r, props("{\"ImageId\": \"ami-1\", \"PrivateIpAddress\": \"10.0.0.6\"}"), ctx("i-1"));

        assertEquals("i-2", r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals("i-1", provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void changingAvailabilityZoneReplacesTheInstance() throws Exception {
        Instance i1 = new Instance();
        i1.setInstanceId("i-1");
        i1.setImageId("ami-1");
        Instance i2 = new Instance();
        i2.setInstanceId("i-2");
        i2.setImageId("ami-1");
        when(ec2.runInstances(anyString(), any(), anyString(), anyInt(), anyInt(), any(), anyList(),
                any(), any(), anyList(), any(), any(), any()))
                .thenReturn(reservationOf(i1), reservationOf(i2));
        StackResource r = resource("Server");
        provisioner.provision(r, props("{\"ImageId\": \"ami-1\", \"AvailabilityZone\": \"us-east-1a\"}"), ctx(null));

        when(ec2.describeInstances("us-east-1", List.of("i-1"), null))
                .thenReturn(List.of(reservationOf(i1)));
        provisioner.provision(r, props("{\"ImageId\": \"ami-1\", \"AvailabilityZone\": \"us-east-1b\"}"), ctx("i-1"));

        assertEquals("i-2", r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void unchangedDeclaredCreateOnlyPropertiesReuseTheInstance() throws Exception {
        Instance i1 = new Instance();
        i1.setInstanceId("i-1");
        i1.setImageId("ami-1");
        stubLaunch(i1);
        StackResource r = resource("Server");
        String template = "{\"ImageId\": \"ami-1\", \"PrivateIpAddress\": \"10.0.0.5\", "
                + "\"AvailabilityZone\": \"us-east-1a\"}";
        provisioner.provision(r, props(template), ctx(null));

        when(ec2.describeInstances("us-east-1", List.of("i-1"), null))
                .thenReturn(List.of(reservationOf(i1)));
        provisioner.provision(r, props(template), ctx("i-1"));

        assertEquals("i-1", r.getPhysicalId());
        verify(ec2, times(1)).runInstances(anyString(), any(), anyString(), anyInt(), anyInt(), any(),
                anyList(), any(), any(), anyList(), any(), any(), any());
    }

    @Test
    void aNewlyDeclaredCreateOnlyValueWithNoPriorRecordDoesNotReplaceTheInstance() throws Exception {
        Instance i1 = new Instance();
        i1.setInstanceId("i-1");
        i1.setImageId("ami-1");
        stubLaunch(i1);
        StackResource r = resource("Server");
        // The prior provision declared no PrivateIpAddress, so none was recorded; a first declared
        // value is not treated as a change, only recorded for next time.
        provisioner.provision(r, props("{\"ImageId\": \"ami-1\"}"), ctx(null));

        when(ec2.describeInstances("us-east-1", List.of("i-1"), null))
                .thenReturn(List.of(reservationOf(i1)));
        provisioner.provision(r, props("{\"ImageId\": \"ami-1\", \"PrivateIpAddress\": \"10.0.0.5\"}"), ctx("i-1"));

        assertEquals("i-1", r.getPhysicalId());
        verify(ec2, times(1)).runInstances(anyString(), any(), anyString(), anyInt(), anyInt(), any(),
                anyList(), any(), any(), anyList(), any(), any(), any());
    }

    @Test
    void reusedInstanceReconcilesTagsInPlace() throws Exception {
        Instance i1 = new Instance();
        i1.setInstanceId("i-1");
        i1.setImageId("ami-1");
        stubLaunch(i1);
        StackResource r = resource("Server");
        provisioner.provision(r, props("""
                {"ImageId": "ami-1", "Tags": [{"Key": "env", "Value": "prod"}]}
                """), ctx(null));

        when(ec2.describeInstances("us-east-1", List.of("i-1"), null))
                .thenReturn(List.of(reservationOf(i1)));
        when(ec2.describeTags(eq("us-east-1"), any())).thenReturn(List.of(
                tagEntry("i-1", "env", "prod"), tagEntry("i-1", "old", "stale")));
        provisioner.provision(r, props("""
                {"ImageId": "ami-1", "Tags": [{"Key": "env", "Value": "prod"}]}
                """), ctx("i-1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Tag>> removed = ArgumentCaptor.forClass(List.class);
        verify(ec2).deleteTags(eq("us-east-1"), eq(List.of("i-1")), removed.capture());
        assertEquals(1, removed.getValue().size());
        assertEquals("old", removed.getValue().get(0).getKey());
    }

    @Test
    void reusedInstanceResizesInstanceTypeInPlace() throws Exception {
        Instance i1 = new Instance();
        i1.setInstanceId("i-1");
        i1.setImageId("ami-1");
        i1.setInstanceType("t3.micro");
        stubLaunch(i1);
        StackResource r = resource("Server");
        provisioner.provision(r, props("{\"ImageId\": \"ami-1\", \"InstanceType\": \"t3.micro\"}"), ctx(null));

        when(ec2.describeInstances("us-east-1", List.of("i-1"), null))
                .thenReturn(List.of(reservationOf(i1)));
        provisioner.provision(r, props("{\"ImageId\": \"ami-1\", \"InstanceType\": \"t3.small\"}"), ctx("i-1"));

        assertEquals("i-1", r.getPhysicalId());
        verify(ec2).modifyInstanceAttribute("us-east-1", "i-1", "instanceType", "t3.small");
    }

    @Test
    void reusedInstanceReattachesChangedSecurityGroups() throws Exception {
        Instance i1 = new Instance();
        i1.setInstanceId("i-1");
        i1.setImageId("ami-1");
        i1.setSecurityGroups(new ArrayList<>(List.of(new GroupIdentifier("sg-1", "old"))));
        stubLaunch(i1);
        StackResource r = resource("Server");
        provisioner.provision(r, props("{\"ImageId\": \"ami-1\"}"), ctx(null));

        when(ec2.describeInstances("us-east-1", List.of("i-1"), null))
                .thenReturn(List.of(reservationOf(i1)));
        provisioner.provision(r, props("{\"ImageId\": \"ami-1\", \"SecurityGroupIds\": [\"sg-2\"]}"), ctx("i-1"));

        verify(ec2).modifyInstanceGroups("us-east-1", "i-1", List.of("sg-2"));
    }

    @Test
    void reusedInstanceLeavesUnchangedMutablePropertiesAlone() throws Exception {
        Instance i1 = new Instance();
        i1.setInstanceId("i-1");
        i1.setImageId("ami-1");
        i1.setInstanceType("t3.small");
        i1.setSecurityGroups(new ArrayList<>(List.of(new GroupIdentifier("sg-1", "keep"))));
        stubLaunch(i1);
        StackResource r = resource("Server");
        String template = "{\"ImageId\": \"ami-1\", \"InstanceType\": \"t3.small\", "
                + "\"SecurityGroupIds\": [\"sg-1\"]}";
        provisioner.provision(r, props(template), ctx(null));

        when(ec2.describeInstances("us-east-1", List.of("i-1"), null))
                .thenReturn(List.of(reservationOf(i1)));
        provisioner.provision(r, props(template), ctx("i-1"));

        verify(ec2, never()).modifyInstanceAttribute(anyString(), anyString(), anyString(), anyString());
        verify(ec2, never()).modifyInstanceGroups(anyString(), anyString(), anyList());
    }

    @Test
    void updateCreatesAFreshInstanceWhenThePriorOneWasTerminatedOutOfBand() throws Exception {
        Instance created = new Instance();
        created.setInstanceId("i-9");
        created.setImageId("ami-1");
        stubLaunch(created);
        when(ec2.describeInstances("us-east-1", List.of("i-1"), null))
                .thenThrow(new AwsException("InvalidInstanceID.NotFound", "gone", 400));
        StackResource r = resource("Server");

        assertDoesNotThrow(() -> provisioner.provision(r, props("{\"ImageId\": \"ami-1\"}"), ctx("i-1")));

        assertEquals("i-9", r.getPhysicalId());
        verify(ec2).runInstances(anyString(), any(), anyString(), anyInt(), anyInt(), any(),
                anyList(), any(), any(), anyList(), any(), any(), any());
    }

    @Test
    void updateDoesNotReuseATerminatedPriorInstance() throws Exception {
        Instance terminated = new Instance();
        terminated.setInstanceId("i-1");
        terminated.setImageId("ami-1");
        terminated.setState(InstanceState.terminated());
        Instance created = new Instance();
        created.setInstanceId("i-9");
        created.setImageId("ami-1");
        stubLaunch(created);
        when(ec2.describeInstances("us-east-1", List.of("i-1"), null))
                .thenReturn(List.of(reservationOf(terminated)));
        StackResource r = resource("Server");

        provisioner.provision(r, props("{\"ImageId\": \"ami-1\"}"), ctx("i-1"));

        assertEquals("i-9", r.getPhysicalId());
        verify(ec2).runInstances(anyString(), any(), anyString(), anyInt(), anyInt(), any(),
                anyList(), any(), any(), anyList(), any(), any(), any());
    }

    @Test
    void replacementDoesNotInheritStaleAttributesFromThePriorInstance() throws Exception {
        Instance withPublicIp = new Instance();
        withPublicIp.setInstanceId("i-1");
        withPublicIp.setImageId("ami-1");
        withPublicIp.setPublicIpAddress("54.0.0.5");
        Instance withoutPublicIp = new Instance();
        withoutPublicIp.setInstanceId("i-2");
        withoutPublicIp.setImageId("ami-2");
        when(ec2.runInstances(anyString(), any(), anyString(), anyInt(), anyInt(), any(), anyList(),
                any(), any(), anyList(), any(), any(), any()))
                .thenReturn(reservationOf(withPublicIp), reservationOf(withoutPublicIp));
        StackResource r = resource("Server");
        provisioner.provision(r, props("{\"ImageId\": \"ami-1\"}"), ctx(null));
        assertEquals("54.0.0.5", r.getAttributes().get("PublicIp"));

        when(ec2.describeInstances("us-east-1", List.of("i-1"), null))
                .thenReturn(List.of(reservationOf(withPublicIp)));
        provisioner.provision(r, props("{\"ImageId\": \"ami-2\"}"), ctx("i-1"));

        assertEquals("i-2", r.getPhysicalId());
        assertFalse(r.getAttributes().containsKey("PublicIp"));
    }

    private static Reservation reservationOf(Instance instance) {
        Reservation reservation = new Reservation();
        reservation.getInstances().add(instance);
        return reservation;
    }

    private static Map<String, String> tagEntry(String resourceId, String key, String value) {
        Map<String, String> entry = new HashMap<>();
        entry.put("resourceId", resourceId);
        entry.put("key", key);
        entry.put("value", value);
        return entry;
    }

    private void stubLaunch(Instance instance) {
        Reservation reservation = new Reservation();
        reservation.getInstances().add(instance);
        when(ec2.runInstances(anyString(), any(), anyString(), anyInt(), anyInt(), any(), anyList(),
                any(), any(), anyList(), any(), any(), any())).thenReturn(reservation);
    }

    private ProvisionContext ctx() {
        return ctx(null);
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId);
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
