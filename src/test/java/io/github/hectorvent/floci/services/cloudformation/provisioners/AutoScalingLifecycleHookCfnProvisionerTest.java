package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The Auto Scaling lifecycle-hook CFN provisioner in isolation. */
class AutoScalingLifecycleHookCfnProvisionerTest {

    private final AutoScalingService autoScaling = mock(AutoScalingService.class);
    private final AutoScalingLifecycleHookCfnProvisioner provisioner =
            new AutoScalingLifecycleHookCfnProvisioner(autoScaling);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
    }

    private StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("Hook");
        r.setResourceType("AWS::AutoScaling::LifecycleHook");
        r.setAttributes(new HashMap<>());
        return r;
    }

    @Test
    void hookIsPutOnTheGroupAndNamesThePhysicalId() {
        StackResource r = resource();
        ObjectNode props = mapper.createObjectNode()
                .put("AutoScalingGroupName", "the-asg")
                .put("LifecycleHookName", "the-hook")
                .put("LifecycleTransition", "autoscaling:EC2_INSTANCE_TERMINATING")
                .put("NotificationTargetARN", "arn:aws:sns:us-east-1:000000000000:drain")
                .put("RoleARN", "arn:aws:iam::000000000000:role/asg-hook")
                .put("NotificationMetadata", "drain-me")
                .put("HeartbeatTimeout", 120)
                .put("DefaultResult", "CONTINUE");

        provisioner.provision(r, props, ctx());

        verify(autoScaling).putLifecycleHook("us-east-1", "the-asg", "the-hook",
                "autoscaling:EC2_INSTANCE_TERMINATING",
                "arn:aws:sns:us-east-1:000000000000:drain",
                "arn:aws:iam::000000000000:role/asg-hook",
                "drain-me", 120, "CONTINUE");
        assertEquals("the-hook", r.getPhysicalId());
    }

    @Test
    void hookWithoutNameGetsAGeneratedName() {
        StackResource r = resource();
        ObjectNode props = mapper.createObjectNode().put("AutoScalingGroupName", "the-asg");

        provisioner.provision(r, props, ctx());

        // <stack>-<logicalId>-<suffix>
        assertEquals("my-stack-Hook", r.getPhysicalId().replaceAll("-[0-9a-f]{12}$", ""));
        assertTrue(r.getPhysicalId().length() <= 255);
        verify(autoScaling).putLifecycleHook("us-east-1", "the-asg", r.getPhysicalId(),
                null, null, null, null, null, null);
    }

    @Test
    void provisionRecordsTheOwningGroupForDelete() {
        StackResource r = resource();
        ObjectNode props = mapper.createObjectNode()
                .put("AutoScalingGroupName", "the-asg")
                .put("LifecycleHookName", "the-hook");

        provisioner.provision(r, props, ctx());

        assertEquals("the-asg", r.getAttributes().get("AutoScalingGroupName"));
    }

    @Test
    void deleteScopesToTheOwningGroup() {
        StackResource r = resource();
        provisioner.provision(r, mapper.createObjectNode()
                .put("AutoScalingGroupName", "the-asg")
                .put("LifecycleHookName", "the-hook"), ctx());

        provisioner.delete(r, "us-east-1");

        // Hook names are unique only within a group; the by-name form would also remove an
        // identically named hook belonging to an unrelated Auto Scaling group.
        verify(autoScaling).deleteLifecycleHook("us-east-1", "the-asg", "the-hook");
        verify(autoScaling, never()).deleteLifecycleHookByName(anyString(), anyString());
    }

    @Test
    void deleteFallsBackToTheNameWhenNoGroupWasRecorded() {
        StackResource r = resource();
        r.setPhysicalId("the-hook");

        provisioner.delete(r, "us-east-1");

        verify(autoScaling).deleteLifecycleHookByName("us-east-1", "the-hook");
        verify(autoScaling, never()).deleteLifecycleHook(anyString(), anyString(), anyString());
    }

    @Test
    void deleteRemovesTheHookByName() {
        provisioner.delete("AWS::AutoScaling::LifecycleHook", "the-hook", "us-east-1");
        verify(autoScaling).deleteLifecycleHookByName("us-east-1", "the-hook");
    }

    @Test
    void deleteWithoutPhysicalIdIsSkipped() {
        provisioner.delete("AWS::AutoScaling::LifecycleHook", null, "us-east-1");
        provisioner.delete("AWS::AutoScaling::LifecycleHook", "  ", "us-east-1");
        verifyNoInteractions(autoScaling);
    }
}
