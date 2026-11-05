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
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::EC2::Instance} in isolation: the attributes Ref and Fn::GetAtt read once the launch
 * completes, launch-template fields filling what the properties leave unset, and the delete.
 * The launch-failure and rollback-marker paths are covered through the dispatcher by
 * {@code CloudFormationEc2ProvisionerTest}.
 */
class Ec2InstanceCfnProvisionerTest {

    private static final String TYPE = "AWS::EC2::Instance";
    private static final String PROFILE_A = "arn:aws:iam::000000000000:instance-profile/web";
    private static final String PROFILE_B = "arn:aws:iam::000000000000:instance-profile/web-v2";

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
        instance.setVpcId("vpc-1");
        instance.setState(InstanceState.running());
        stubLaunch(instance);
        StackResource r = resource("Server");

        provisioner.provision(r, props("{\"ImageId\": \"ami-1\"}"), ctx());

        assertEquals("i-1", r.getPhysicalId());
        assertEquals(Set.of("InstanceId", "PrivateIp", "PublicIp", "PrivateDnsName", "PublicDnsName",
                "AvailabilityZone", "VpcId", "State.Code", "State.Name",
                Ec2InstanceCfnProvisioner.DECLARED_TAG_KEYS_ATTR), r.getAttributes().keySet());
        assertEquals("10.0.0.5", r.getAttributes().get("PrivateIp"));
        assertEquals("us-east-1a", r.getAttributes().get("AvailabilityZone"));
        assertEquals("vpc-1", r.getAttributes().get("VpcId"));
        assertEquals("16", r.getAttributes().get("State.Code"));
        assertEquals("running", r.getAttributes().get("State.Name"));
        verify(ec2).awaitContainerLaunch(instance);
    }

    @Test
    void launchPublishesTheStateTheLaunchSettledTo() throws Exception {
        Instance pending = new Instance();
        pending.setInstanceId("i-1");
        pending.setState(InstanceState.pending());
        stubLaunch(pending);
        Instance running = new Instance();
        running.setInstanceId("i-1");
        running.setState(InstanceState.running());
        when(ec2.describeInstances("us-east-1", List.of("i-1"), null))
                .thenReturn(List.of(reservationOf(running)));
        StackResource r = resource("Server");

        provisioner.provision(r, props("{\"ImageId\": \"ami-1\"}"), ctx());

        assertEquals("16", r.getAttributes().get("State.Code"));
        assertEquals("running", r.getAttributes().get("State.Name"));
    }

    @Test
    void anInstanceWithoutAStateExposesNoStateAttributes() throws Exception {
        Instance instance = new Instance();
        instance.setInstanceId("i-stateless");
        stubLaunch(instance);
        StackResource r = resource("Server");
        r.getAttributes().put("State.Code", "80");
        r.getAttributes().put("State.Name", "stopped");

        provisioner.provision(r, props("{\"ImageId\": \"ami-1\"}"), ctx());

        assertEquals(Set.of("InstanceId", Ec2InstanceCfnProvisioner.DECLARED_TAG_KEYS_ATTR),
                r.getAttributes().keySet());
    }

    @Test
    void launchAppliesTheDeclaredPrivateIpAddress() throws Exception {
        Instance instance = new Instance();
        instance.setInstanceId("i-1");
        stubLaunch(instance);

        provisioner.provision(resource("Server"), props("""
                {"ImageId": "ami-1", "SubnetId": "subnet-1", "UserData": "boot",
                 "PrivateIpAddress": "10.0.1.50"}
                """), ctx());

        // Every other launch option keeps the value the shorter overloads default it to.
        ArgumentCaptor<String> privateIp = ArgumentCaptor.forClass(String.class);
        verify(ec2).runInstances(eq("us-east-1"), eq("ami-1"), eq("t3.micro"), eq(1), eq(1), isNull(),
                anyList(), eq("subnet-1"), isNull(), anyList(), eq("boot"), isNull(), isNull(), isNull(),
                eq(0), isNull(), isNull(), isNull(), eq("Ym9vdA=="), eq(false), isNull(), isNull(), isNull(),
                eq(false), privateIp.capture());
        assertEquals("10.0.1.50", privateIp.getValue());
    }

    @Test
    void launchWithoutAPrivateIpAddressLeavesItToTheSubnet() throws Exception {
        Instance instance = new Instance();
        instance.setInstanceId("i-1");
        stubLaunch(instance);

        provisioner.provision(resource("Server"), props("{\"ImageId\": \"ami-1\"}"), ctx());

        verify(ec2).runInstances(anyString(), any(), anyString(), anyInt(), anyInt(), any(), anyList(),
                any(), any(), anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(),
                anyBoolean(), any(), any(), any(), anyBoolean(), isNull());
    }

    @Test
    void launchTemplateFillsWhatThePropertiesLeaveUnset() throws Exception {
        LaunchTemplateData data = new LaunchTemplateData();
        data.setImageId("ami-from-template");
        data.setInstanceType("m5.large");
        data.setKeyName("template-key");
        when(ec2.resolveLaunchTemplateData("us-east-1", "lt-1", null, "1", false)).thenReturn(data);
        Instance instance = new Instance();
        instance.setInstanceId("i-2");
        stubLaunch(instance);

        provisioner.provision(resource("Server"), props("""
                {"LaunchTemplate": {"LaunchTemplateId": "lt-1", "Version": "1"}}
                """), ctx());

        verify(ec2).runInstances(eq("us-east-1"), eq("ami-from-template"), eq("m5.large"), eq(1), eq(1),
                eq("template-key"), anyList(), any(), any(), anyList(), any(), any(), any(),
                any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void aLaunchTemplateThatDoesNotResolveFailsTheInstance() throws Exception {
        when(ec2.resolveLaunchTemplateData("us-east-1", "lt-missing", null, null, false))
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
                anyList(), any(), any(), anyList(), any(), any(), any(),
                any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean(), any());
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
    void deleteWaitsUntilTheInstanceIsTerminated() {
        provisioner.terminatePollMillis = 1L;
        Instance shuttingDown = new Instance();
        shuttingDown.setInstanceId("i-1");
        shuttingDown.setState(InstanceState.shuttingDown());
        Instance terminated = new Instance();
        terminated.setInstanceId("i-1");
        terminated.setState(InstanceState.terminated());
        when(ec2.describeInstances("us-east-1", List.of("i-1"), null))
                .thenReturn(List.of(reservationOf(shuttingDown)))
                .thenReturn(List.of(reservationOf(shuttingDown)))
                .thenReturn(List.of(reservationOf(terminated)));

        assertDoesNotThrow(() -> provisioner.delete(TYPE, "i-1", "us-east-1"));

        InOrder order = inOrder(ec2);
        order.verify(ec2).terminateInstances("us-east-1", List.of("i-1"));
        order.verify(ec2, times(3)).describeInstances("us-east-1", List.of("i-1"), null);
    }

    @Test
    void deleteTreatsAnInstanceGoneAfterTerminateAsTerminated() {
        when(ec2.describeInstances("us-east-1", List.of("i-1"), null))
                .thenThrow(new AwsException("InvalidInstanceID.NotFound", "gone", 400));

        assertDoesNotThrow(() -> provisioner.delete(TYPE, "i-1", "us-east-1"));
    }

    @Test
    void deleteFailsWhenTheInstanceNeverReachesTerminated() {
        provisioner.terminateTimeoutMillis = 20L;
        provisioner.terminatePollMillis = 1L;
        Instance shuttingDown = new Instance();
        shuttingDown.setInstanceId("i-1");
        shuttingDown.setState(InstanceState.shuttingDown());
        when(ec2.describeInstances("us-east-1", List.of("i-1"), null))
                .thenReturn(List.of(reservationOf(shuttingDown)));

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.delete(TYPE, "i-1", "us-east-1"));
        assertTrue(failure.getMessage().contains("Instance i-1 did not reach terminated"), failure.getMessage());
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
                anyList(), any(), any(), anyList(), any(), any(), any(),
                any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void changedUserDataIsRewrittenAcrossAStopAndAStart() throws Exception {
        Instance i1 = reusable("i-1");
        i1.setUserData("old");
        i1.setState(InstanceState.running());
        StackResource r = resource("Server");

        provisioner.provision(r, props("{\"ImageId\": \"ami-1\", \"UserData\": \"new\"}"), ctx("i-1"));

        assertEquals("i-1", r.getPhysicalId());
        InOrder order = inOrder(ec2);
        order.verify(ec2).stopInstances("us-east-1", List.of("i-1"));
        order.verify(ec2).modifyInstanceUserData("us-east-1", "i-1", "new");
        order.verify(ec2).startInstances("us-east-1", List.of("i-1"));
        order.verify(ec2).awaitContainerLaunch(i1);
    }

    @Test
    void unchangedUserDataLeavesTheInstanceRunning() throws Exception {
        Instance i1 = reusable("i-1");
        i1.setUserData("same");
        i1.setState(InstanceState.running());

        provisioner.provision(resource("Server"), props("{\"ImageId\": \"ami-1\", \"UserData\": \"same\"}"),
                ctx("i-1"));

        verify(ec2, never()).stopInstances(anyString(), anyList());
        verify(ec2, never()).modifyInstanceUserData(anyString(), anyString(), any());
    }

    @Test
    void aStoppedInstanceGetsItsUserDataRewrittenWithoutARestart() throws Exception {
        Instance i1 = reusable("i-1");
        i1.setUserData("old");
        i1.setState(InstanceState.stopped());

        provisioner.provision(resource("Server"), props("{\"ImageId\": \"ami-1\", \"UserData\": \"new\"}"),
                ctx("i-1"));

        verify(ec2).modifyInstanceUserData("us-east-1", "i-1", "new");
        verify(ec2, never()).stopInstances(anyString(), anyList());
        verify(ec2, never()).startInstances(anyString(), anyList());
    }

    @Test
    void declaringAnIamInstanceProfileAssociatesItByResolvedArn() throws Exception {
        reusable("i-1");
        when(ec2.resolveIamInstanceProfileName("web")).thenReturn(PROFILE_A);
        StackResource r = resource("Server");

        provisioner.provision(r, props("{\"ImageId\": \"ami-1\", \"IamInstanceProfile\": \"web\"}"), ctx("i-1"));

        verify(ec2).associateIamInstanceProfile("us-east-1", "i-1", PROFILE_A);
        assertEquals(PROFILE_A, r.getAttributes().get("__FlociDeclaredIamInstanceProfile"));
        assertFalse(r.getAttributes().containsKey("IamInstanceProfile"));
    }

    @Test
    void changingTheIamInstanceProfileReplacesTheAssociation() throws Exception {
        Instance i1 = reusable("i-1");
        i1.setIamInstanceProfileArn(PROFILE_A);

        provisioner.provision(resource("Server"),
                props("{\"ImageId\": \"ami-1\", \"IamInstanceProfile\": \"" + PROFILE_B + "\"}"), ctx("i-1"));

        verify(ec2).replaceIamInstanceProfileAssociation("us-east-1",
                Ec2Service.iamInstanceProfileAssociationId("i-1"), PROFILE_B);
        verify(ec2, never()).associateIamInstanceProfile(anyString(), anyString(), anyString());
    }

    @Test
    void droppingADeclaredIamInstanceProfileDisassociatesIt() throws Exception {
        Instance i1 = reusable("i-1");
        i1.setIamInstanceProfileArn(PROFILE_A);
        StackResource r = resource("Server");
        r.getAttributes().put("__FlociDeclaredIamInstanceProfile", PROFILE_A);

        provisioner.provision(r, props("{\"ImageId\": \"ami-1\"}"), ctx("i-1"));

        verify(ec2).disassociateIamInstanceProfile("us-east-1", Ec2Service.iamInstanceProfileAssociationId("i-1"));
        assertFalse(r.getAttributes().containsKey("__FlociDeclaredIamInstanceProfile"));
    }

    @Test
    void aProfileTheTemplateNeverDeclaredIsKeptWhenItStaysUndeclared() throws Exception {
        Instance i1 = reusable("i-1");
        i1.setIamInstanceProfileArn(PROFILE_A);

        provisioner.provision(resource("Server"), props("{\"ImageId\": \"ami-1\"}"), ctx("i-1"));

        verify(ec2, never()).disassociateIamInstanceProfile(anyString(), anyString());
        verify(ec2, never()).replaceIamInstanceProfileAssociation(anyString(), anyString(), anyString());
    }

    @Test
    void launchResolvesAProfileNameThroughIam() throws Exception {
        Instance instance = new Instance();
        instance.setInstanceId("i-1");
        stubLaunch(instance);
        when(ec2.resolveIamInstanceProfileName("web")).thenReturn(PROFILE_A);
        StackResource r = resource("Server");

        provisioner.provision(r, props("{\"ImageId\": \"ami-1\", \"IamInstanceProfile\": \"web\"}"), ctx());

        ArgumentCaptor<String> profile = ArgumentCaptor.forClass(String.class);
        verify(ec2).runInstances(anyString(), any(), anyString(), anyInt(), anyInt(), any(), anyList(),
                any(), any(), anyList(), any(), profile.capture(), any(),
                any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean(), any());
        assertEquals(PROFILE_A, profile.getValue());
        assertEquals(PROFILE_A, r.getAttributes().get("__FlociDeclaredIamInstanceProfile"));
    }

    /** An instance the update finds and keeps: same image as the template, no createOnly change. */
    private Instance reusable(String instanceId) {
        Instance instance = new Instance();
        instance.setInstanceId(instanceId);
        instance.setImageId("ami-1");
        when(ec2.describeInstances("us-east-1", List.of(instanceId), null))
                .thenReturn(List.of(reservationOf(instance)));
        return instance;
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
                any(), any(), anyList(), any(), any(), any(),
                any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean(), any()))
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
                any(), any(), anyList(), any(), any(), any(),
                any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean(), any()))
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
                any(), any(), anyList(), any(), any(), any(),
                any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean(), any()))
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
                anyList(), any(), any(), anyList(), any(), any(), any(),
                any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean(), any());
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
                anyList(), any(), any(), anyList(), any(), any(), any(),
                any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void reusedInstanceRemovesOnlyTheTagKeysItOwns() throws Exception {
        Instance i1 = new Instance();
        i1.setInstanceId("i-1");
        i1.setImageId("ami-1");
        stubLaunch(i1);
        StackResource r = resource("Server");
        provisioner.provision(r, props("""
                {"ImageId": "ami-1", "Tags": [{"Key": "env", "Value": "prod"}, {"Key": "old", "Value": "stale"}]}
                """), ctx(null));

        when(ec2.describeInstances("us-east-1", List.of("i-1"), null))
                .thenReturn(List.of(reservationOf(i1)));
        // team was added through EC2, outside the stack, so AWS keeps it.
        when(ec2.describeTags(eq("us-east-1"), any())).thenReturn(List.of(
                tagEntry("i-1", "env", "prod"), tagEntry("i-1", "old", "stale"), tagEntry("i-1", "team", "core"),
                tagEntry("i-1", "aws:autoscaling:groupName", "group"),
                tagEntry("i-1", "AWS:cloudformation:stack-id", "stack")));
        provisioner.provision(r, props("""
                {"ImageId": "ami-1", "Tags": [{"Key": "env", "Value": "prod"}]}
                """), ctx("i-1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Tag>> removed = ArgumentCaptor.forClass(List.class);
        verify(ec2).deleteTags(eq("us-east-1"), eq(List.of("i-1")), removed.capture());
        assertEquals(1, removed.getValue().size());
        assertEquals("old", removed.getValue().get(0).getKey());
        assertEquals("[\"env\"]", r.getAttributes().get(Ec2InstanceCfnProvisioner.DECLARED_TAG_KEYS_ATTR));
    }

    @Test
    void reusedInstanceModifiesOnlyAChangedMetadataOptions() throws Exception {
        Instance i1 = new Instance();
        i1.setInstanceId("i-1");
        i1.setImageId("ami-1");
        stubLaunch(i1);
        StackResource r = resource("Server");
        provisioner.provision(r, props("""
                {"ImageId": "ami-1", "MetadataOptions": {"HttpTokens": "required"}}
                """), ctx(null));
        ArgumentCaptor<LaunchTemplateData.MetadataOptions> launched =
                ArgumentCaptor.forClass(LaunchTemplateData.MetadataOptions.class);
        verify(ec2).runInstances(anyString(), any(), anyString(), anyInt(), anyInt(), any(), anyList(),
                any(), any(), anyList(), any(), any(), any(), any(), anyInt(), any(), launched.capture(),
                any(), any(), anyBoolean(), any(), any(), any(), anyBoolean(), any());
        assertEquals("required", launched.getValue().getHttpTokens());

        LaunchTemplateData.MetadataOptions applied = LaunchTemplateData.MetadataOptions.launchDefaults();
        applied.setHttpTokens("required");
        i1.setMetadataOptions(applied);
        when(ec2.describeInstances("us-east-1", List.of("i-1"), null))
                .thenReturn(List.of(reservationOf(i1)));
        provisioner.provision(r, props("""
                {"ImageId": "ami-1", "MetadataOptions": {"HttpTokens": "required"}}
                """), ctx("i-1"));
        verify(ec2, never()).modifyInstanceMetadataOptions(any(), any(), any());

        provisioner.provision(r, props("""
                {"ImageId": "ami-1", "MetadataOptions": {"HttpTokens": "optional"}}
                """), ctx("i-1"));
        ArgumentCaptor<LaunchTemplateData.MetadataOptions> modified =
                ArgumentCaptor.forClass(LaunchTemplateData.MetadataOptions.class);
        verify(ec2).modifyInstanceMetadataOptions(eq("us-east-1"), eq("i-1"), modified.capture());
        assertEquals("optional", modified.getValue().getHttpTokens());
        verify(ec2, times(1)).runInstances(anyString(), any(), anyString(), anyInt(), anyInt(), any(), anyList(),
                any(), any(), anyList(), any(), any(), any(),
                any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void reusedInstanceResetsMetadataOptionsTheTemplateNoLongerDeclares() throws Exception {
        Instance i1 = new Instance();
        i1.setInstanceId("i-1");
        i1.setImageId("ami-1");
        stubLaunch(i1);
        StackResource r = resource("Server");
        provisioner.provision(r, props("""
                {"ImageId": "ami-1", "MetadataOptions": {"HttpTokens": "required", "HttpPutResponseHopLimit": 2}}
                """), ctx(null));
        LaunchTemplateData.MetadataOptions applied = LaunchTemplateData.MetadataOptions.launchDefaults();
        applied.setHttpTokens("required");
        applied.setHttpPutResponseHopLimit(2);
        i1.setMetadataOptions(applied);
        when(ec2.describeInstances("us-east-1", List.of("i-1"), null))
                .thenReturn(List.of(reservationOf(i1)));

        // The instance dropped its MetadataOptions, so what it set last time goes back to the launch
        // default, and what it never set is left alone.
        provisioner.provision(r, props("{\"ImageId\": \"ami-1\"}"), ctx("i-1"));

        ArgumentCaptor<LaunchTemplateData.MetadataOptions> reset =
                ArgumentCaptor.forClass(LaunchTemplateData.MetadataOptions.class);
        verify(ec2).modifyInstanceMetadataOptions(eq("us-east-1"), eq("i-1"), reset.capture());
        assertEquals("optional", reset.getValue().getHttpTokens());
        assertEquals(1, reset.getValue().getHttpPutResponseHopLimit());
        assertNull(reset.getValue().getHttpEndpoint());
    }

    /**
     * A changed LaunchTemplate keeps the instance, where AWS replaces it: what the new version
     * declares is reconciled in place, here an HttpTokens the new version no longer sets.
     */
    @Test
    void changingTheInstancesLaunchTemplateKeepsItAndReconcilesWhatItDeclares() throws Exception {
        LaunchTemplateData withTokens = new LaunchTemplateData();
        withTokens.setImageId("ami-1");
        LaunchTemplateData.MetadataOptions required = new LaunchTemplateData.MetadataOptions();
        required.setHttpTokens("required");
        withTokens.setMetadataOptions(required);
        LaunchTemplateData withoutTokens = new LaunchTemplateData();
        withoutTokens.setImageId("ami-1");
        when(ec2.resolveLaunchTemplateData("us-east-1", "lt-1", null, "1", false)).thenReturn(withTokens);
        when(ec2.resolveLaunchTemplateData("us-east-1", "lt-2", null, "2", false)).thenReturn(withoutTokens);
        Instance i1 = stubLaunches("i-1", "i-2").get(0);
        StackResource r = resource("Server");
        provisioner.provision(r, props("""
                {"LaunchTemplate": {"LaunchTemplateId": "lt-1", "Version": "1"}}
                """), ctx(null));
        LaunchTemplateData.MetadataOptions applied = LaunchTemplateData.MetadataOptions.launchDefaults();
        applied.setHttpTokens("required");
        i1.setMetadataOptions(applied);

        provisioner.provision(r, props("""
                {"LaunchTemplate": {"LaunchTemplateId": "lt-2", "Version": "2"}}
                """), ctx("i-1"));

        assertEquals("i-1", r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
        ArgumentCaptor<LaunchTemplateData.MetadataOptions> reset =
                ArgumentCaptor.forClass(LaunchTemplateData.MetadataOptions.class);
        verify(ec2).modifyInstanceMetadataOptions(eq("us-east-1"), eq("i-1"), reset.capture());
        assertEquals("optional", reset.getValue().getHttpTokens());
        verify(ec2, times(1)).runInstances(anyString(), any(), anyString(), anyInt(), anyInt(), any(), anyList(),
                any(), any(), anyList(), any(), any(), any(),
                any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean(), any());
    }

    /**
     * The engine restores the prior instance when a later resource fails a replacing update; its
     * records go back with it, so retrying the update decides from what that instance was declared
     * with rather than from the attempt that was rolled back: the HttpTokens it declared and the
     * retry drops returns to the launch default.
     */
    @Test
    void aRolledBackReplacementPutsBackTheRecordsOfTheInstanceItRestores() throws Exception {
        List<Instance> instances = stubLaunches("i-1", "i-2");
        StackResource r = launchRequiringTokensThenReplace(instances.get(0));
        instances.get(1).setState(InstanceState.terminated());
        provisioner.terminateTimeoutMillis = 20L;

        assertTrue(provisioner.rollbackUpdate(r));

        assertEquals("i-1", r.getPhysicalId());
        assertEquals(Map.of("__FlociDeclaredMetadataOptions", "HttpTokens", "__FlociDeclaredTagKeys", "[]"), declarations(r));
        assertFalse(r.getAttributes().containsKey("__FlociPriorDeclarations"));
        provisioner.provision(r, props("{\"ImageId\": \"ami-1\"}"), ctx("i-1"));
        assertEquals("i-1", r.getPhysicalId());
        ArgumentCaptor<LaunchTemplateData.MetadataOptions> reset =
                ArgumentCaptor.forClass(LaunchTemplateData.MetadataOptions.class);
        verify(ec2).modifyInstanceMetadataOptions(eq("us-east-1"), eq("i-1"), reset.capture());
        assertEquals("optional", reset.getValue().getHttpTokens());
    }

    /**
     * A rollback whose delete of the replacement fails has already named the prior instance again,
     * and a rollback that then skips the resource never calls back, so the records go back first.
     */
    @Test
    void aRollbackThatFailsToDeleteTheReplacementStillPutsBackTheRecords() throws Exception {
        List<Instance> instances = stubLaunches("i-1", "i-2");
        StackResource r = launchRequiringTokensThenReplace(instances.get(0));
        doThrow(new AwsException("InternalError", "terminate failed", 500))
                .when(ec2).terminateInstances("us-east-1", List.of("i-2"));

        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));

        assertEquals("i-1", r.getPhysicalId());
        assertEquals(Map.of("__FlociDeclaredMetadataOptions", "HttpTokens", "__FlociDeclaredTagKeys", "[]"), declarations(r));
        assertFalse(r.getAttributes().containsKey("__FlociPriorDeclarations"));
    }

    /**
     * A retried rollback goes through the delete it still owes: whether that delete fails again or
     * succeeds, the prior instance keeps the records the first attempt put back.
     */
    @Test
    void aRetriedRollbackKeepsThePriorRecordsWhicheverWayTheDeleteGoes() throws Exception {
        List<Instance> instances = stubLaunches("i-1", "i-2");
        StackResource r = launchRequiringTokensThenReplace(instances.get(0));
        when(ec2.terminateInstances("us-east-1", List.of("i-2")))
                .thenThrow(new AwsException("InternalError", "terminate failed", 500))
                .thenThrow(new AwsException("InternalError", "terminate failed", 500))
                .thenReturn(List.of());
        Map<String, String> declared = Map.of("__FlociDeclaredMetadataOptions", "HttpTokens", "__FlociDeclaredTagKeys", "[]");
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));

        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));
        assertEquals("i-1", r.getPhysicalId());
        assertEquals(declared, declarations(r));

        instances.get(1).setState(InstanceState.terminated());
        provisioner.terminateTimeoutMillis = 20L;
        assertTrue(provisioner.rollbackUpdate(r));
        assertEquals("i-1", r.getPhysicalId());
        assertEquals(declared, declarations(r));
        assertFalse(r.getAttributes().containsKey("__FlociPriorDeclarations"));
    }

    /** A saved copy that cannot be read does not hide the delete failure the rollback reports. */
    @Test
    void anUnreadableSavedCopyDoesNotHideTheDeleteFailure() throws Exception {
        List<Instance> instances = stubLaunches("i-1", "i-2");
        StackResource r = launchRequiringTokensThenReplace(instances.get(0));
        r.getAttributes().put("__FlociPriorDeclarations", "{not json");
        AwsException deleteFailure = new AwsException("InternalError", "terminate failed", 500);
        doThrow(deleteFailure).when(ec2).terminateInstances("us-east-1", List.of("i-2"));

        AwsException thrown = assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));

        assertEquals(deleteFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertTrue(thrown.getSuppressed()[0] instanceof IllegalStateException);
    }

    /**
     * A launch template change keeps the instance only while what the template supplies to the
     * createOnly ImageId stays the same; a new image replaces it, as a changed ImageId does.
     */
    @Test
    void aNewTemplateVersionWithAnotherImageStillReplacesTheInstance() throws Exception {
        LaunchTemplateData first = new LaunchTemplateData();
        first.setImageId("ami-1");
        LaunchTemplateData second = new LaunchTemplateData();
        second.setImageId("ami-2");
        when(ec2.resolveLaunchTemplateData("us-east-1", "lt-1", null, "1", false)).thenReturn(first);
        when(ec2.resolveLaunchTemplateData("us-east-1", "lt-1", null, "2", false)).thenReturn(second);
        stubLaunches("i-1", "i-2");
        StackResource r = resource("Server");
        provisioner.provision(r, props("""
                {"LaunchTemplate": {"LaunchTemplateId": "lt-1", "Version": "1"}}
                """), ctx(null));

        provisioner.provision(r, props("""
                {"LaunchTemplate": {"LaunchTemplateId": "lt-1", "Version": "2"}}
                """), ctx("i-1"));

        assertEquals("i-2", r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
    }

    /**
     * A stack update that changes only the instance's MetadataOptions in place, and then rolls back
     * because a later resource failed, puts the options and their record back and reports the
     * rollback done: otherwise the instance would keep options the stack no longer declares.
     */
    @Test
    void aRolledBackInPlaceUpdatePutsTheMetadataOptionsBack() throws Exception {
        Instance i1 = launchRequiringTokens();
        StackResource r = resource("Server");
        provisioner.provision(r, props("""
                {"ImageId": "ami-1", "MetadataOptions": {"HttpTokens": "required"}}
                """), ctx(null));
        Map<String, String> declared = declarations(r);

        provisioner.provision(r, props("""
                {"ImageId": "ami-1", "MetadataOptions": {"HttpTokens": "optional", "HttpPutResponseHopLimit": 3}}
                """), ctx("i-1"));
        assertTrue(provisioner.rollbackUpdate(r));

        ArgumentCaptor<LaunchTemplateData.MetadataOptions> applied =
                ArgumentCaptor.forClass(LaunchTemplateData.MetadataOptions.class);
        verify(ec2, times(2)).modifyInstanceMetadataOptions(eq("us-east-1"), eq("i-1"), applied.capture());
        assertEquals("optional", applied.getAllValues().get(0).getHttpTokens());
        LaunchTemplateData.MetadataOptions restored = applied.getAllValues().get(1);
        assertEquals("required", restored.getHttpTokens());
        assertEquals(1, restored.getHttpPutResponseHopLimit());
        assertEquals(i1.effectiveMetadataOptions().getHttpEndpoint(), restored.getHttpEndpoint());
        assertEquals(declared, declarations(r));
        assertFalse(r.getAttributes().containsKey("__FlociPriorInPlaceUpdate"));
    }

    /**
     * When the same update also changed something CloudFormation cannot put back in place here
     * (the instance type), the options still go back, and the rollback still reports that it could
     * not undo the update.
     */
    @Test
    void aRolledBackUpdateWithOtherInPlaceChangesStillPutsTheMetadataOptionsBack() throws Exception {
        Instance i1 = launchRequiringTokens();
        i1.setInstanceType("t3.micro");
        StackResource r = resource("Server");
        provisioner.provision(r, props("""
                {"ImageId": "ami-1", "InstanceType": "t3.micro", "MetadataOptions": {"HttpTokens": "required"}}
                """), ctx(null));

        provisioner.provision(r, props("""
                {"ImageId": "ami-1", "InstanceType": "t3.large", "MetadataOptions": {"HttpTokens": "optional"}}
                """), ctx("i-1"));
        assertFalse(provisioner.rollbackUpdate(r));

        ArgumentCaptor<LaunchTemplateData.MetadataOptions> applied =
                ArgumentCaptor.forClass(LaunchTemplateData.MetadataOptions.class);
        verify(ec2, times(2)).modifyInstanceMetadataOptions(eq("us-east-1"), eq("i-1"), applied.capture());
        assertEquals("required", applied.getAllValues().get(1).getHttpTokens());
        assertFalse(r.getAttributes().containsKey("__FlociPriorInPlaceUpdate"));
    }

    /** A tag change in the same update has no rollback either, so the options go back and the rollback reports it. */
    @Test
    void aRolledBackUpdateThatAlsoChangedTagsPutsTheOptionsBackButReportsItUndone() throws Exception {
        launchRequiringTokens();
        StackResource r = resource("Server");
        provisioner.provision(r, props("""
                {"ImageId": "ami-1", "MetadataOptions": {"HttpTokens": "required"}}
                """), ctx(null));

        provisioner.provision(r, props("""
                {"ImageId": "ami-1", "MetadataOptions": {"HttpTokens": "optional"},
                 "Tags": [{"Key": "env", "Value": "prod"}]}
                """), ctx("i-1"));
        assertFalse(provisioner.rollbackUpdate(r));

        ArgumentCaptor<LaunchTemplateData.MetadataOptions> applied =
                ArgumentCaptor.forClass(LaunchTemplateData.MetadataOptions.class);
        verify(ec2, times(2)).modifyInstanceMetadataOptions(eq("us-east-1"), eq("i-1"), applied.capture());
        assertEquals("required", applied.getAllValues().get(1).getHttpTokens());
    }

    /**
     * An update that changes only the tags, which have no rollback here, still has its declarations
     * put back, and the rollback reports that it could not undo the update; the options are untouched.
     */
    @Test
    void aRolledBackTagOnlyUpdatePutsTheRecordBackAndReportsItCouldNotUndoIt() throws Exception {
        launchRequiringTokens();
        StackResource r = resource("Server");
        provisioner.provision(r, props("""
                {"ImageId": "ami-1", "MetadataOptions": {"HttpTokens": "required"}}
                """), ctx(null));
        Map<String, String> declared = declarations(r);

        provisioner.provision(r, props("""
                {"ImageId": "ami-1", "MetadataOptions": {"HttpTokens": "required", "HttpPutResponseHopLimit": 1},
                 "Tags": [{"Key": "env", "Value": "prod"}]}
                """), ctx("i-1"));
        assertFalse(provisioner.rollbackUpdate(r));

        assertEquals(declared, declarations(r));
        assertFalse(r.getAttributes().containsKey("__FlociPriorInPlaceUpdate"));
        verify(ec2, never()).modifyInstanceMetadataOptions(any(), any(), any());
    }

    /**
     * The options are the update's last change: when the tags fail first, the engine restores the
     * resource without a saved copy, so the options must not have changed.
     */
    @Test
    void aTagFailureInAnInPlaceUpdateLeavesTheMetadataOptionsAlone() throws Exception {
        launchRequiringTokens();
        StackResource r = resource("Server");
        provisioner.provision(r, props("""
                {"ImageId": "ami-1", "MetadataOptions": {"HttpTokens": "required"}}
                """), ctx(null));
        doThrow(new AwsException("InternalError", "tags failed", 500))
                .when(ec2).createTags(eq("us-east-1"), eq(List.of("i-1")), anyList());

        assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"ImageId": "ami-1", "MetadataOptions": {"HttpTokens": "optional"},
                 "Tags": [{"Key": "env", "Value": "prod"}]}
                """), ctx("i-1")));

        verify(ec2, never()).modifyInstanceMetadataOptions(any(), any(), any());
    }

    /**
     * An update that declares an option the instance already has changes no EC2 setting, only the
     * declaration record. A rollback puts the record back and reports the update undone, without
     * touching the instance.
     */
    @Test
    void aRolledBackUpdateThatOnlyDeclaresTheCurrentOptionsPutsTheRecordBack() throws Exception {
        launchRequiringTokens();
        StackResource r = resource("Server");
        provisioner.provision(r, props("""
                {"ImageId": "ami-1", "MetadataOptions": {"HttpTokens": "required"}}
                """), ctx(null));
        Map<String, String> declared = declarations(r);

        provisioner.provision(r, props("""
                {"ImageId": "ami-1", "MetadataOptions": {"HttpTokens": "required", "HttpPutResponseHopLimit": 1}}
                """), ctx("i-1"));
        assertTrue(provisioner.rollbackUpdate(r));

        assertEquals(declared, declarations(r));
        assertFalse(r.getAttributes().containsKey("__FlociPriorInPlaceUpdate"));
        verify(ec2, never()).modifyInstanceMetadataOptions(any(), any(), any());
    }

    /**
     * A MetadataOptions member given as an empty string is a value, not an omission, and EC2 refuses
     * it, as it refuses any value outside the allowed set.
     */
    @Test
    void aBlankMetadataOptionFailsBeforeTheInstanceLaunches() throws Exception {
        StackResource r = resource("Server");

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"ImageId": "ami-1", "MetadataOptions": {"HttpTokens": ""}}
                """), ctx(null)));

        assertEquals("InvalidParameterValue", failure.getErrorCode());
        verify(ec2, never()).runInstances(anyString(), any(), anyString(), anyInt(), anyInt(), any(), anyList(),
                any(), any(), anyList(), any(), any(), any(),
                any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void aCommittedOrClearedInPlaceUpdateDropsTheSavedRecord() throws Exception {
        Instance i1 = launchRequiringTokens();
        StackResource r = resource("Server");
        provisioner.provision(r, props("""
                {"ImageId": "ami-1", "MetadataOptions": {"HttpTokens": "required"}}
                """), ctx(null));
        String optional = """
                {"ImageId": "ami-1", "MetadataOptions": {"HttpTokens": "optional"}}
                """;

        provisioner.provision(r, props(optional), ctx("i-1"));
        assertTrue(r.getAttributes().containsKey("__FlociPriorInPlaceUpdate"));
        provisioner.completeUpdate(r);
        assertFalse(r.getAttributes().containsKey("__FlociPriorInPlaceUpdate"));
        i1.getMetadataOptions().setHttpTokens("optional");

        provisioner.provision(r, props(optional.replace("optional", "required")), ctx("i-1"));
        assertTrue(r.getAttributes().containsKey("__FlociPriorInPlaceUpdate"));
        provisioner.clearUpdate(r);
        assertFalse(r.getAttributes().containsKey("__FlociPriorInPlaceUpdate"));
    }

    /**
     * One stubbed instance, i-1, of the default type, whose applied options require tokens, as a
     * launch declaring them gives.
     */
    private Instance launchRequiringTokens() {
        Instance i1 = stubLaunches("i-1").get(0);
        i1.setInstanceType("t3.micro");
        LaunchTemplateData.MetadataOptions applied = LaunchTemplateData.MetadataOptions.launchDefaults();
        applied.setHttpTokens("required");
        i1.setMetadataOptions(applied);
        return i1;
    }

    @Test
    void aCommittedOrClearedReplacementDropsTheSavedRecords() throws Exception {
        List<Instance> instances = stubLaunches("i-1", "i-2", "i-3", "i-4");
        StackResource committed = launchRequiringTokensThenReplace(instances.get(0));
        assertTrue(committed.getAttributes().containsKey("__FlociPriorDeclarations"));
        instances.get(0).setState(InstanceState.terminated());
        provisioner.terminateTimeoutMillis = 20L;
        provisioner.completeUpdate(committed);
        assertFalse(committed.getAttributes().containsKey("__FlociPriorDeclarations"));

        StackResource cleared = launchRequiringTokensThenReplace(instances.get(2));
        assertTrue(cleared.getAttributes().containsKey("__FlociPriorDeclarations"));
        provisioner.clearUpdate(cleared);
        assertFalse(cleared.getAttributes().containsKey("__FlociPriorDeclarations"));
    }

    /**
     * Launches {@code first} declaring HttpTokens required, then replaces it with the next stubbed
     * launch through an ImageId change that drops the MetadataOptions.
     */
    private StackResource launchRequiringTokensThenReplace(Instance first) throws Exception {
        StackResource r = resource("Server");
        provisioner.provision(r, props("""
                {"ImageId": "ami-1", "MetadataOptions": {"HttpTokens": "required"}}
                """), ctx(null));
        assertEquals(first.getInstanceId(), r.getPhysicalId());
        LaunchTemplateData.MetadataOptions applied = LaunchTemplateData.MetadataOptions.launchDefaults();
        applied.setHttpTokens("required");
        first.setMetadataOptions(applied);
        provisioner.provision(r, props("{\"ImageId\": \"ami-2\"}"), ctx(first.getInstanceId()));
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(Map.of(Ec2InstanceCfnProvisioner.DECLARED_TAG_KEYS_ATTR, "[]"), declarations(r));
        return r;
    }

    /**
     * With the same template and version (here {@code $Latest}) the instance is kept, and options
     * its template stopped declaring go back to their launch defaults.
     */
    @Test
    void reusedInstanceResetsOptionsItsTemplateStopsDeclaring() throws Exception {
        LaunchTemplateData withTokens = new LaunchTemplateData();
        withTokens.setImageId("ami-1");
        LaunchTemplateData.MetadataOptions required = new LaunchTemplateData.MetadataOptions();
        required.setHttpTokens("required");
        withTokens.setMetadataOptions(required);
        LaunchTemplateData withoutTokens = new LaunchTemplateData();
        withoutTokens.setImageId("ami-1");
        when(ec2.resolveLaunchTemplateData("us-east-1", "lt-1", null, "$Latest", false))
                .thenReturn(withTokens, withoutTokens);
        Instance i1 = stubLaunches("i-1").get(0);
        StackResource r = resource("Server");
        String template = """
                {"LaunchTemplate": {"LaunchTemplateId": "lt-1", "Version": "$Latest"}}
                """;
        provisioner.provision(r, props(template), ctx(null));
        LaunchTemplateData.MetadataOptions applied = LaunchTemplateData.MetadataOptions.launchDefaults();
        applied.setHttpTokens("required");
        i1.setMetadataOptions(applied);

        provisioner.provision(r, props(template), ctx("i-1"));

        assertEquals("i-1", r.getPhysicalId());
        ArgumentCaptor<LaunchTemplateData.MetadataOptions> reset =
                ArgumentCaptor.forClass(LaunchTemplateData.MetadataOptions.class);
        verify(ec2).modifyInstanceMetadataOptions(eq("us-east-1"), eq("i-1"), reset.capture());
        assertEquals("optional", reset.getValue().getHttpTokens());
    }

    @Test
    void invalidMetadataOptionsFailBeforeAReusedInstanceChanges() throws Exception {
        StackResource r = resource("Server");
        r.setPhysicalId("i-1");
        r.getAttributes().put("InstanceId", "i-1");
        Instance prior = new Instance();
        prior.setInstanceId("i-1");
        prior.setImageId("ami-1");
        prior.setInstanceType("t3.micro");
        when(ec2.describeInstances("us-east-1", List.of("i-1"), null))
                .thenReturn(List.of(reservationOf(prior)));

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"ImageId": "ami-1", "InstanceType": "t3.large", "MetadataOptions": {"HttpTokens": "Required"}}
                """), ctx("i-1")));

        assertEquals("InvalidParameterValue", failure.getErrorCode());
        verifyNoInteractions(ec2);
    }

    @Test
    void reservedUpdateTagsFailBeforeChangingTheInstanceOrItsTags() throws Exception {
        StackResource r = resource("Server");
        r.setPhysicalId("i-1");
        r.getAttributes().put("InstanceId", "i-1");

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"ImageId": "ami-1", "InstanceType": "t3.small",
                 "SecurityGroupIds": ["sg-new"], "UserData": "changed",
                 "Tags": [{"Key": "AWS:reserved", "Value": "invalid"}]}
                """), ctx("i-1")));

        assertEquals("InvalidParameterValue", failure.getErrorCode());
        assertEquals("i-1", r.getPhysicalId());
        assertEquals(Map.of("InstanceId", "i-1"), r.getAttributes());
        verifyNoInteractions(ec2);
    }

    @Test
    void reservedCreateTagsFailBeforeLaunchingAnInstance() throws Exception {
        assertThrows(AwsException.class, () -> provisioner.provision(resource("Server"), props("""
                {"ImageId": "ami-1", "Tags": [{"Key": "aws:reserved", "Value": "invalid"}]}
                """), ctx(null)));

        verifyNoInteractions(ec2);
    }

    @Test
    void tagReconciliationValidatesBeforeDeletingStaleTags() {
        assertThrows(AwsException.class, () -> Ec2Tags.reconcileOwned(ec2, "us-east-1", "i-1", Set.of("env"),
                Map.of("env", "new", "aWs:reserved", "invalid")));

        verifyNoInteractions(ec2);
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
                anyList(), any(), any(), anyList(), any(), any(), any(),
                any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean(), any());
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
                anyList(), any(), any(), anyList(), any(), any(), any(),
                any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean(), any());
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
                any(), any(), anyList(), any(), any(), any(),
                any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean(), any()))
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

    /** The provisioner's records of what the template declared, by attribute name. */
    private static Map<String, String> declarations(StackResource r) {
        return r.getAttributes().entrySet().stream().filter(e -> e.getKey().startsWith("__FlociDeclared"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** One launch per id, in order, each instance then found by DescribeInstances. */
    private List<Instance> stubLaunches(String... instanceIds) {
        List<Instance> instances = new ArrayList<>();
        List<Reservation> reservations = new ArrayList<>();
        for (String instanceId : instanceIds) {
            Instance instance = new Instance();
            instance.setInstanceId(instanceId);
            instance.setImageId("ami-1");
            instances.add(instance);
            reservations.add(reservationOf(instance));
            when(ec2.describeInstances("us-east-1", List.of(instanceId), null))
                    .thenReturn(List.of(reservationOf(instance)));
        }
        when(ec2.runInstances(anyString(), any(), anyString(), anyInt(), anyInt(), any(), anyList(),
                any(), any(), anyList(), any(), any(), any(),
                any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(reservations.get(0), reservations.subList(1, reservations.size()).toArray(Reservation[]::new));
        return instances;
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
                any(), any(), anyList(), any(), any(), any(),
                any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(reservation);
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
        when(engine.omitNoValue(any())).thenAnswer(inv -> inv.getArgument(0));
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
