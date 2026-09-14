package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService;
import io.github.hectorvent.floci.services.autoscaling.model.AsgOptionalFields;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.autoscaling.model.LaunchConfiguration;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
                eq("t3.micro"), any(), anyList(), any(), any(), any()))
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
                any(), any(), anyList(), any(), any(), any());
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
}
