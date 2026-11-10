package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.autoscaling.AutoScalingService;
import io.floci.aws.services.autoscaling.model.AsgOptionalFields;
import io.floci.aws.services.autoscaling.model.AutoScalingGroup;
import io.floci.aws.services.autoscaling.model.AvailabilityZoneDistribution;
import io.floci.aws.services.autoscaling.model.CapacityReservationSpecification;
import io.floci.aws.services.autoscaling.model.InstanceMaintenancePolicy;
import io.floci.aws.services.autoscaling.model.LaunchConfiguration;
import io.floci.aws.services.autoscaling.model.MixedInstancesPolicy;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Auto Scaling group and launch configuration CFN provisioner in isolation, against a mocked
 * {@link AutoScalingService}.
 */
class AutoScalingGroupCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String GROUP = "AWS::AutoScaling::AutoScalingGroup";
    private static final String LAUNCH_CONFIGURATION = "AWS::AutoScaling::LaunchConfiguration";
    private static final String ASG_ARN =
            "arn:aws:autoscaling:us-east-1:000000000000:autoScalingGroup:uuid:autoScalingGroupName/my-asg";

    private final AutoScalingService autoScaling = mock(AutoScalingService.class);
    private final AutoScalingGroupCfnProvisioner provisioner =
            new AutoScalingGroupCfnProvisioner(autoScaling);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        return ctx(null);
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveStringList(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            List<String> values = new ArrayList<>();
            if (node != null && node.isArray()) {
                node.forEach(element -> values.add(element.asText()));
            }
            return values;
        });
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack", priorPhysicalId);
    }

    private static StackResource resource(String type, String logicalId, String priorPhysicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setPhysicalId(priorPhysicalId);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private static AutoScalingGroup group(String name) {
        AutoScalingGroup asg = new AutoScalingGroup();
        asg.setAutoScalingGroupName(name);
        asg.setAutoScalingGroupArn(ASG_ARN);
        return asg;
    }

    @Test
    void groupCreateRecordsTheSchemaArnAttribute() {
        // AutoScalingGroupARN is the schema's only readOnlyProperty, so it is the name real
        // CloudFormation resolves. The monolith only ever wrote "Arn", which meant
        // Fn::GetAtt [Asg, AutoScalingGroupARN] fell through to the literal "Asg.AutoScalingGroupARN".
        when(autoScaling.createAutoScalingGroup(eq(REGION), eq("my-asg"), any(), any(), any(), any(),
                any(), anyInt(), anyInt(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyList(),
                any(), anyInt(), anyList(), anyMap(), anyMap(), any()))
                .thenReturn(group("my-asg"));
        StackResource r = resource(GROUP, "Asg", null);
        ObjectNode props = mapper.createObjectNode()
                .put("AutoScalingGroupName", "my-asg")
                .put("MinSize", "1")
                .put("MaxSize", "3");

        provisioner.provision(r, props, ctx());

        assertEquals("my-asg", r.getPhysicalId());
        assertEquals(ASG_ARN, r.getAttributes().get("AutoScalingGroupARN"));
        // Kept beside it: templates and the existing integration test read this name.
        assertEquals(ASG_ARN, r.getAttributes().get("Arn"));
    }

    @Test
    void groupCreateCarriesTheOptionalFieldsTheTemplateSet() {
        // #3494 added these four to the service but left the CloudFormation path passing
        // AsgOptionalFields.none(), so a template setting them was silently dropped.
        when(autoScaling.createAutoScalingGroup(eq(REGION), eq("my-asg"), any(), any(), any(), any(),
                any(), anyInt(), anyInt(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyList(),
                any(), anyInt(), anyList(), anyMap(), anyMap(), any()))
                .thenReturn(group("my-asg"));
        StackResource r = resource(GROUP, "Asg", null);
        ObjectNode props = mapper.createObjectNode()
                .put("AutoScalingGroupName", "my-asg")
                .put("MinSize", "1")
                .put("MaxSize", "3")
                .put("DesiredCapacityType", "vcpu")
                .put("CapacityRebalance", "true")
                .put("MaxInstanceLifetime", "86400")
                .put("DefaultInstanceWarmup", "300");

        provisioner.provision(r, props, ctx());

        ArgumentCaptor<AsgOptionalFields> captor = ArgumentCaptor.forClass(AsgOptionalFields.class);
        verify(autoScaling).createAutoScalingGroup(eq(REGION), eq("my-asg"), any(), any(), any(), any(),
                any(), anyInt(), anyInt(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyList(),
                any(), anyInt(), anyList(), anyMap(), anyMap(), captor.capture());
        AsgOptionalFields optional = captor.getValue();
        assertEquals("vcpu", optional.desiredCapacityType());
        assertEquals(Boolean.TRUE, optional.capacityRebalance());
        assertEquals(86400, optional.maxInstanceLifetime());
        assertEquals(300, optional.defaultInstanceWarmup());
    }

    @Test
    void groupOptionalFieldsStayNullWhenTheTemplateOmitsThem() {
        // Absent must stay null, not false or zero: applyToExistingGroup overwrites only the
        // members that are set, matching what UpdateAutoScalingGroup does on the Query path.
        when(autoScaling.createAutoScalingGroup(eq(REGION), eq("my-asg"), any(), any(), any(), any(),
                any(), anyInt(), anyInt(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyList(),
                any(), anyInt(), anyList(), anyMap(), anyMap(), any()))
                .thenReturn(group("my-asg"));
        StackResource r = resource(GROUP, "Asg", null);
        ObjectNode props = mapper.createObjectNode().put("AutoScalingGroupName", "my-asg");

        provisioner.provision(r, props, ctx());

        ArgumentCaptor<AsgOptionalFields> captor = ArgumentCaptor.forClass(AsgOptionalFields.class);
        verify(autoScaling).createAutoScalingGroup(eq(REGION), eq("my-asg"), any(), any(), any(), any(),
                any(), anyInt(), anyInt(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyList(),
                any(), anyInt(), anyList(), anyMap(), anyMap(), captor.capture());
        AsgOptionalFields optional = captor.getValue();
        assertNull(optional.desiredCapacityType());
        assertNull(optional.capacityRebalance());
        assertNull(optional.maxInstanceLifetime());
        assertNull(optional.defaultInstanceWarmup());
    }

    @Test
    void groupCreateCarriesTheOptionalStructuresAndMetricsCollection() {
        when(autoScaling.createAutoScalingGroup(eq(REGION), eq("my-asg"), any(), any(), any(), any(),
                any(), anyInt(), anyInt(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyList(),
                any(), anyInt(), anyList(), anyMap(), anyMap(), any()))
                .thenReturn(group("my-asg"));
        StackResource r = resource(GROUP, "Asg", null);
        ObjectNode props = mapper.createObjectNode()
                .put("AutoScalingGroupName", "my-asg")
                .put("MinSize", "1")
                .put("MaxSize", "3")
                .put("ServiceLinkedRoleARN", "role-arn");
        props.putObject("InstanceMaintenancePolicy")
                .put("MinHealthyPercentage", 90)
                .put("MaxHealthyPercentage", 120);
        props.putObject("AvailabilityZoneDistribution").put("CapacityDistributionStrategy", "balanced-only");
        ObjectNode reservation = props.putObject("CapacityReservationSpecification")
                .put("CapacityReservationPreference", "capacity-reservations-only");
        reservation.putObject("CapacityReservationTarget").putArray("CapacityReservationIds").add("cr-1");
        ObjectNode metrics = props.putArray("MetricsCollection").addObject().put("Granularity", "1Minute");
        metrics.putArray("Metrics").add("GroupMinSize").add("GroupMaxSize");
        ObjectNode launchTemplate = props.putObject("MixedInstancesPolicy").putObject("LaunchTemplate");
        launchTemplate.putObject("LaunchTemplateSpecification").put("LaunchTemplateId", "lt-123");
        launchTemplate.putArray("Overrides").addObject()
                .put("InstanceType", "t3.micro")
                .put("WeightedCapacity", "2");

        provisioner.provision(r, props, ctx());

        ArgumentCaptor<MixedInstancesPolicy> policyCaptor = ArgumentCaptor.forClass(MixedInstancesPolicy.class);
        ArgumentCaptor<AsgOptionalFields> captor = ArgumentCaptor.forClass(AsgOptionalFields.class);
        verify(autoScaling).createAutoScalingGroup(eq(REGION), eq("my-asg"), any(), any(), any(), any(),
                policyCaptor.capture(), anyInt(), anyInt(), anyInt(), anyInt(), anyList(), anyList(), anyList(),
                anyList(), any(), anyInt(), anyList(), anyMap(), anyMap(), captor.capture());
        AsgOptionalFields optional = captor.getValue();
        assertEquals("role-arn", optional.serviceLinkedRoleArn());
        assertEquals(90, optional.instanceMaintenancePolicy().getMinHealthyPercentage());
        assertEquals(120, optional.instanceMaintenancePolicy().getMaxHealthyPercentage());
        assertEquals("balanced-only", optional.availabilityZoneDistribution().getCapacityDistributionStrategy());
        assertEquals("capacity-reservations-only",
                optional.capacityReservationSpecification().getCapacityReservationPreference());
        assertEquals(List.of("cr-1"), optional.capacityReservationSpecification().getCapacityReservationIds());
        assertEquals("2", policyCaptor.getValue().getLaunchTemplate().getOverrides().getFirst().getWeightedCapacity());
        verify(autoScaling).enableMetricsCollection(REGION, "my-asg", List.of("GroupMinSize", "GroupMaxSize"), "1Minute");
    }

    @Test
    void groupCreateLeavesTheOptionalStructuresNullAndMetricsAloneWhenTheTemplateOmitsThem() {
        when(autoScaling.createAutoScalingGroup(eq(REGION), eq("my-asg"), any(), any(), any(), any(),
                any(), anyInt(), anyInt(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyList(),
                any(), anyInt(), anyList(), anyMap(), anyMap(), any()))
                .thenReturn(group("my-asg"));
        StackResource r = resource(GROUP, "Asg", null);
        ObjectNode props = mapper.createObjectNode().put("AutoScalingGroupName", "my-asg");

        provisioner.provision(r, props, ctx());

        ArgumentCaptor<AsgOptionalFields> captor = ArgumentCaptor.forClass(AsgOptionalFields.class);
        verify(autoScaling).createAutoScalingGroup(eq(REGION), eq("my-asg"), any(), any(), any(), any(),
                any(), anyInt(), anyInt(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyList(),
                any(), anyInt(), anyList(), anyMap(), anyMap(), captor.capture());
        AsgOptionalFields optional = captor.getValue();
        assertNull(optional.serviceLinkedRoleArn());
        assertNull(optional.instanceMaintenancePolicy());
        assertNull(optional.availabilityZoneDistribution());
        assertNull(optional.capacityReservationSpecification());
        verify(autoScaling, never()).enableMetricsCollection(anyString(), anyString(), anyList(), anyString());
    }

    @Test
    void groupCreateRejectsAMetricsCollectionEntryWithoutGranularity() {
        StackResource r = resource(GROUP, "Asg", null);
        ObjectNode props = mapper.createObjectNode().put("AutoScalingGroupName", "my-asg");
        props.putArray("MetricsCollection").addObject().putArray("Metrics").add("GroupMinSize");

        AwsException error = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));

        assertEquals("ValidationError", error.getErrorCode());
        verify(autoScaling, never()).createAutoScalingGroup(any(), any(), any(), any(), any(), any(),
                any(), anyInt(), anyInt(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyList(),
                any(), anyInt(), anyList(), anyMap(), anyMap(), any());
    }

    @Test
    void groupCreateRejectsAnUnknownMetricBeforeCreatingTheGroup() {
        StackResource r = resource(GROUP, "Asg", null);
        ObjectNode props = mapper.createObjectNode().put("AutoScalingGroupName", "my-asg");
        props.putArray("MetricsCollection").addObject().put("Granularity", "1Minute")
                .putArray("Metrics").add("CPUUtilization");

        AwsException error = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));

        assertEquals("ValidationError", error.getErrorCode());
        verify(autoScaling, never()).createAutoScalingGroup(any(), any(), any(), any(), any(), any(),
                any(), anyInt(), anyInt(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyList(),
                any(), anyInt(), anyList(), anyMap(), anyMap(), any());
    }

    @Test
    void groupCreateRejectsACapacityReservationSpecificationWithoutPreference() {
        StackResource r = resource(GROUP, "Asg", null);
        ObjectNode props = mapper.createObjectNode().put("AutoScalingGroupName", "my-asg");
        props.putObject("CapacityReservationSpecification").putObject("CapacityReservationTarget")
                .putArray("CapacityReservationIds").add("cr-1");

        AwsException error = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));

        assertEquals("ValidationError", error.getErrorCode());
    }

    @Test
    void groupUpdateSetsEnabledMetricsToExactlyWhatTheTemplateLists() {
        when(autoScaling.describeAutoScalingGroups(REGION, List.of("my-asg")))
                .thenReturn(List.of(group("my-asg")));
        StackResource r = resource(GROUP, "Asg", "my-asg");
        ObjectNode props = mapper.createObjectNode().put("AutoScalingGroupName", "my-asg");
        props.putArray("MetricsCollection").addObject().put("Granularity", "1Minute")
                .putArray("Metrics").add("GroupTotalInstances");

        provisioner.provision(r, props, ctx("my-asg"));

        verify(autoScaling).disableMetricsCollection(REGION, "my-asg", List.of());
        verify(autoScaling).enableMetricsCollection(REGION, "my-asg", List.of("GroupTotalInstances"), "1Minute");
    }

    @Test
    void groupUpdateReconcilesTheSameNameInsteadOfCreatingItAgain() {
        // provision() re-runs on every UpdateStack. createAutoScalingGroup throws AlreadyExists, so
        // a same-named group already on file has to go through updateAutoScalingGroup.
        when(autoScaling.describeAutoScalingGroups(REGION, List.of("my-asg")))
                .thenReturn(List.of(group("my-asg")));
        StackResource r = resource(GROUP, "Asg", "my-asg");
        ObjectNode props = mapper.createObjectNode()
                .put("AutoScalingGroupName", "my-asg")
                .put("MinSize", "2")
                .put("MaxSize", "6")
                .put("DesiredCapacityType", "units");

        provisioner.provision(r, props, ctx("my-asg"));

        ArgumentCaptor<AsgOptionalFields> captor = ArgumentCaptor.forClass(AsgOptionalFields.class);
        verify(autoScaling).updateAutoScalingGroup(eq(REGION), eq("my-asg"), any(), any(), any(), any(),
                any(), eq(2), eq(6), anyInt(), anyInt(), anyList(), anyList(), any(), anyInt(), anyList(),
                captor.capture());
        assertEquals("units", captor.getValue().desiredCapacityType());
        verify(autoScaling, never()).createAutoScalingGroup(anyString(), anyString(), any(), any(), any(),
                any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyList(), anyList(), anyList(),
                anyList(), any(), anyInt(), anyList(), anyMap(), anyMap(), any());
    }

    @Test
    void launchConfigurationCreateSetsTheNameAsPhysicalId() {
        LaunchConfiguration lc = new LaunchConfiguration();
        lc.setLaunchConfigurationName("my-lc");
        lc.setLaunchConfigurationArn("arn:aws:autoscaling:us-east-1:000000000000:launchConfiguration:my-lc");
        when(autoScaling.createLaunchConfiguration(eq(REGION), eq("my-lc"), any(), eq("ami-1"),
                eq("t3.micro"), any(), anyList(), any(), any(), any(), any(), anyList(), any()))
                .thenReturn(lc);
        StackResource r = resource(LAUNCH_CONFIGURATION, "Lc", null);
        ObjectNode props = mapper.createObjectNode()
                .put("LaunchConfigurationName", "my-lc")
                .put("ImageId", "ami-1")
                .put("InstanceType", "t3.micro");

        provisioner.provision(r, props, ctx());

        assertEquals("my-lc", r.getPhysicalId());
        assertEquals("arn:aws:autoscaling:us-east-1:000000000000:launchConfiguration:my-lc",
                r.getAttributes().get("Arn"));
    }

    @Test
    void launchConfigurationUpdateLeavesTheExistingOneAlone() {
        // Launch configurations have no update API on real AWS, so a same-named one already on file
        // must not be re-created.
        LaunchConfiguration lc = new LaunchConfiguration();
        lc.setLaunchConfigurationName("my-lc");
        lc.setLaunchConfigurationArn("arn:aws:autoscaling:us-east-1:000000000000:launchConfiguration:my-lc");
        when(autoScaling.describeLaunchConfigurations(REGION, List.of("my-lc"))).thenReturn(List.of(lc));
        StackResource r = resource(LAUNCH_CONFIGURATION, "Lc", "my-lc");
        ObjectNode props = mapper.createObjectNode()
                .put("LaunchConfigurationName", "my-lc")
                .put("ImageId", "ami-2");

        provisioner.provision(r, props, ctx("my-lc"));

        verify(autoScaling, never()).createLaunchConfiguration(anyString(), anyString(), any(), any(),
                any(), any(), anyList(), any(), any(), any(), any(), anyList(), any());
        assertEquals("my-lc", r.getPhysicalId());
    }

    @Test
    void deleteRoutesEachTypeToItsOwnCall() {
        provisioner.delete(LAUNCH_CONFIGURATION, "my-lc", REGION);
        verify(autoScaling).deleteLaunchConfiguration(REGION, "my-lc");

        provisioner.delete(GROUP, "my-asg", REGION);
        verify(autoScaling).deleteAutoScalingGroup(REGION, "my-asg", true);
    }

    @Test
    void anUnsupportedTypeIsRejectedRatherThanSilentlyIgnored() {
        StackResource r = resource("AWS::AutoScaling::ScalingPolicy", "Policy", null);
        ProvisionContext ctx = ctx();
        ObjectNode props = mapper.createObjectNode();

        assertThrows(IllegalStateException.class, () -> provisioner.provision(r, props, ctx));
        assertThrows(IllegalStateException.class,
                () -> provisioner.delete("AWS::AutoScaling::ScalingPolicy", "p", REGION));
        verify(autoScaling, never()).deleteAutoScalingGroup(anyString(), anyString(), anyBoolean());
    }

    @Test
    void rollbackUpdateRestoresSnapshotWhenPresent() {
        AutoScalingGroup existing = group("my-asg");
        existing.setLaunchTemplateId("lt-123");
        existing.setLaunchTemplateVersion("1");
        existing.setMinSize(1);
        existing.setMaxSize(5);
        existing.setDesiredCapacity(2);
        existing.setAvailabilityZones(List.of("us-east-1a"));

        when(autoScaling.describeAutoScalingGroups(eq(REGION), eq(List.of("my-asg"))))
                .thenReturn(List.of(existing));

        StackResource r = resource(GROUP, "Asg", "my-asg");

        ObjectNode props = mapper.createObjectNode();
        props.put("AutoScalingGroupName", "my-asg");
        ObjectNode lt = props.putObject("LaunchTemplate");
        lt.put("LaunchTemplateId", "lt-123");
        lt.put("Version", "2");
        props.put("MinSize", 3);
        props.put("MaxSize", 8);
        props.put("DesiredCapacity", 4);
        props.putArray("AvailabilityZones").add("us-east-1a");

        provisioner.provision(r, props, ctx("my-asg"));

        String snapshot = r.getAttributes().get(CfnRollback.ASG_UPDATE_SNAPSHOT_ATTR);
        assertTrue(snapshot != null && !snapshot.isBlank());

        clearInvocations(autoScaling);

        boolean rolledBack = provisioner.rollbackUpdate(r);
        assertTrue(rolledBack);

        ArgumentCaptor<Integer> minSizeCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> maxSizeCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> versionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AsgOptionalFields> optionalFieldsCaptor = ArgumentCaptor.forClass(AsgOptionalFields.class);

        verify(autoScaling).updateAutoScalingGroup(
                eq(REGION),
                eq("my-asg"),
                isNull(),
                eq("lt-123"),
                isNull(),
                versionCaptor.capture(),
                isNull(),
                minSizeCaptor.capture(),
                maxSizeCaptor.capture(),
                anyInt(),
                anyInt(),
                any(),
                any(),
                any(),
                anyInt(),
                any(),
                optionalFieldsCaptor.capture()
        );

        assertEquals("1", versionCaptor.getValue());
        assertEquals(1, minSizeCaptor.getValue());
        assertEquals(5, maxSizeCaptor.getValue());

        AsgOptionalFields optionalFields = optionalFieldsCaptor.getValue();
        assertEquals("units", optionalFields.desiredCapacityType());
        assertEquals(Boolean.FALSE, optionalFields.capacityRebalance());
        assertEquals(0, optionalFields.maxInstanceLifetime());
        assertEquals(AsgOptionalFields.DEFAULT_INSTANCE_WARMUP_REMOVAL_SENTINEL,
                optionalFields.defaultInstanceWarmup());
        // The group had no maintenance policy before the update, so rollback clears whatever the
        // update set, and restores the empty set of enabled metrics.
        assertEquals(InstanceMaintenancePolicy.REMOVAL_SENTINEL,
                optionalFields.instanceMaintenancePolicy().getMinHealthyPercentage());
        assertEquals(InstanceMaintenancePolicy.REMOVAL_SENTINEL,
                optionalFields.instanceMaintenancePolicy().getMaxHealthyPercentage());
        verify(autoScaling).disableMetricsCollection(REGION, "my-asg", List.of());

        // Subsequent rollback should return false since snapshot is spent
        assertFalse(provisioner.rollbackUpdate(r));
    }

    @Test
    void rollbackUpdateClearsMembersTheGroupDidNotHaveBeforeTheUpdate() {
        // UpdateAutoScalingGroup has no clearing value for these three, so passing the snapshot's
        // null through it would leave what the failed update added.
        AutoScalingGroup existing = group("my-asg");
        existing.setMinSize(1);
        existing.setMaxSize(5);
        when(autoScaling.describeAutoScalingGroups(eq(REGION), eq(List.of("my-asg"))))
                .thenReturn(List.of(existing));
        StackResource r = resource(GROUP, "Asg", "my-asg");
        ObjectNode props = mapper.createObjectNode()
                .put("AutoScalingGroupName", "my-asg")
                .put("ServiceLinkedRoleARN", "role-arn");
        props.putObject("AvailabilityZoneDistribution").put("CapacityDistributionStrategy", "balanced-only");
        props.putObject("CapacityReservationSpecification").put("CapacityReservationPreference", "none");
        provisioner.provision(r, props, ctx("my-asg"));
        existing.setServiceLinkedRoleArn("role-arn");
        existing.setAvailabilityZoneDistribution(new AvailabilityZoneDistribution("balanced-only"));
        CapacityReservationSpecification reservation = new CapacityReservationSpecification();
        reservation.setCapacityReservationPreference("none");
        existing.setCapacityReservationSpecification(reservation);

        assertTrue(provisioner.rollbackUpdate(r));

        assertNull(existing.getServiceLinkedRoleArn());
        assertNull(existing.getAvailabilityZoneDistribution());
        assertNull(existing.getCapacityReservationSpecification());
        verify(autoScaling).saveAutoScalingGroupIfPresent(existing);
    }

    @Test
    void groupCreatePassesBlankRoleAndWeightToTheServiceForValidation() {
        // The service rejects an empty ServiceLinkedRoleARN or WeightedCapacity. Turning either
        // into null here would read as "not set" and let the stack through.
        when(autoScaling.createAutoScalingGroup(eq(REGION), eq("my-asg"), any(), any(), any(), any(),
                any(), anyInt(), anyInt(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyList(),
                any(), anyInt(), anyList(), anyMap(), anyMap(), any()))
                .thenReturn(group("my-asg"));
        StackResource r = resource(GROUP, "Asg", null);
        ObjectNode props = mapper.createObjectNode()
                .put("AutoScalingGroupName", "my-asg")
                .put("ServiceLinkedRoleARN", "");
        ObjectNode launchTemplate = props.putObject("MixedInstancesPolicy").putObject("LaunchTemplate");
        launchTemplate.putObject("LaunchTemplateSpecification").put("LaunchTemplateId", "lt-123");
        launchTemplate.putArray("Overrides").addObject()
                .put("InstanceType", "t3.micro")
                .put("WeightedCapacity", "");

        provisioner.provision(r, props, ctx());

        ArgumentCaptor<MixedInstancesPolicy> policyCaptor = ArgumentCaptor.forClass(MixedInstancesPolicy.class);
        ArgumentCaptor<AsgOptionalFields> captor = ArgumentCaptor.forClass(AsgOptionalFields.class);
        verify(autoScaling).createAutoScalingGroup(eq(REGION), eq("my-asg"), any(), any(), any(), any(),
                policyCaptor.capture(), anyInt(), anyInt(), anyInt(), anyInt(), anyList(), anyList(), anyList(),
                anyList(), any(), anyInt(), anyList(), anyMap(), anyMap(), captor.capture());
        assertEquals("", captor.getValue().serviceLinkedRoleArn());
        assertEquals("", policyCaptor.getValue().getLaunchTemplate().getOverrides().getFirst().getWeightedCapacity());
    }

    @Test
    void clearUpdateRemovesSnapshotAttribute() {
        StackResource r = resource(GROUP, "Asg", "my-asg");
        r.getAttributes().put(CfnRollback.ASG_UPDATE_SNAPSHOT_ATTR, "{\"name\":\"my-asg\"}");

        provisioner.clearUpdate(r);
        assertNull(r.getAttributes().get(CfnRollback.ASG_UPDATE_SNAPSHOT_ATTR));
    }
}
