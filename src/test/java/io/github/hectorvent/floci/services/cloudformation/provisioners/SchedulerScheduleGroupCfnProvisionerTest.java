package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.scheduler.SchedulerService;
import io.github.hectorvent.floci.services.scheduler.model.ScheduleGroup;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * github.com/floci-io/floci/issues/2396 - AWS::Scheduler::ScheduleGroup fell through to the
 * generic stub (fake physical id, no backing call), so a stack reporting CREATE_COMPLETE never
 * actually created the group. These tests exercise the provisioner in isolation.
 */
class SchedulerScheduleGroupCfnProvisionerTest {

    private final SchedulerService schedulerService = mock(SchedulerService.class);
    private final SchedulerScheduleGroupCfnProvisioner provisioner =
            new SchedulerScheduleGroupCfnProvisioner(schedulerService);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        return ctx(null);
    }

    /** An update-path context carries the physical id the previous provision assigned. */
    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId);
    }

    private StackResource resource(String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType("AWS::Scheduler::ScheduleGroup");
        r.setAttributes(new HashMap<>());
        return r;
    }

    private static ScheduleGroup group(String name, Map<String, String> tags) {
        ScheduleGroup g = new ScheduleGroup(name,
                "arn:aws:scheduler:us-east-1:000000000000:schedule-group/" + name,
                "ACTIVE", Instant.now(), Instant.now());
        g.getTags().putAll(tags);
        return g;
    }

    @Test
    void createsGroupAndSetsPhysicalIdAndArn() {
        when(schedulerService.createScheduleGroup(eq("my-group"), any(), eq("us-east-1")))
                .thenReturn(group("my-group", Map.of()));
        StackResource r = resource("MyGroup");
        ObjectNode props = mapper.createObjectNode().put("Name", "my-group");

        provisioner.provision(r, props, ctx());

        assertEquals("my-group", r.getPhysicalId());
        assertEquals("arn:aws:scheduler:us-east-1:000000000000:schedule-group/my-group",
                r.getAttributes().get("Arn"));
    }

    @Test
    void withoutNameGeneratesPhysicalName() {
        when(schedulerService.createScheduleGroup(anyString(), any(), eq("us-east-1")))
                .thenAnswer(inv -> group(inv.getArgument(0), Map.of()));
        StackResource r = resource("MyGroup");

        provisioner.provision(r, mapper.createObjectNode(), ctx());

        assertTrue(r.getPhysicalId().startsWith("my-stack-MyGroup-"),
                "generated name should follow <stack>-<logicalId>-<suffix> but was: " + r.getPhysicalId());
        assertTrue(r.getPhysicalId().length() <= 64, "schedule group names are capped at 64 characters");
    }

    @Test
    void withoutNameKeepsTheGeneratedNameAcrossUpdates() {
        // Name is createOnly in the registry schema: provision runs again on every UpdateStack, and
        // generating unconditionally would create a second group under a new name each time.
        when(schedulerService.createScheduleGroup(anyString(), any(), eq("us-east-1")))
                .thenAnswer(inv -> group(inv.getArgument(0), Map.of()));
        StackResource created = resource("MyGroup");
        provisioner.provision(created, mapper.createObjectNode(), ctx());
        String generatedName = created.getPhysicalId();
        when(schedulerService.getScheduleGroup(generatedName, "us-east-1"))
                .thenReturn(group(generatedName, Map.of()));

        StackResource updated = resource("MyGroup");
        provisioner.provision(updated, mapper.createObjectNode(), ctx(generatedName));

        assertEquals(generatedName, updated.getPhysicalId());
        verify(schedulerService, times(1)).createScheduleGroup(anyString(), any(), anyString());
    }

    @Test
    void passesResolvedTagsToCreateScheduleGroup() {
        when(schedulerService.createScheduleGroup(eq("my-group"), any(), eq("us-east-1")))
                .thenReturn(group("my-group", Map.of("Env", "prod")));
        StackResource r = resource("MyGroup");
        ObjectNode props = mapper.createObjectNode().put("Name", "my-group");
        ObjectNode tag = mapper.createObjectNode().put("Key", "Env").put("Value", "prod");
        props.set("Tags", mapper.createArrayNode().add(tag));

        provisioner.provision(r, props, ctx());

        verify(schedulerService).createScheduleGroup("my-group", Map.of("Env", "prod"), "us-east-1");
    }

    @Test
    void aGroupDeletedOutOfBandFailsTheUpdate() {
        // The reuse path reads the group this resource already owns. If it was deleted outside the
        // stack, the not-found error reaches the caller and the update fails, as CloudFormation
        // does; the old create-then-adopt path silently recreated it instead.
        when(schedulerService.getScheduleGroup("my-group", "us-east-1"))
                .thenThrow(new AwsException("ResourceNotFoundException", "ScheduleGroup not found: my-group", 404));
        StackResource r = resource("MyGroup");
        ObjectNode props = mapper.createObjectNode().put("Name", "my-group");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props, ctx("my-group")));

        assertEquals("ResourceNotFoundException", failure.getErrorCode());
        verify(schedulerService, never()).createScheduleGroup(anyString(), any(), anyString());
    }

    @Test
    void sameStackRetryAdoptsExistingGroup() {
        // The physical id the previous provision assigned matches the name this one resolves to, so
        // this logical resource's own group already exists: reconcile it instead of calling create,
        // which would answer ConflictException and fail the stack.
        when(schedulerService.getScheduleGroup("my-group", "us-east-1"))
                .thenReturn(group("my-group", Map.of()));
        StackResource r = resource("MyGroup");
        ObjectNode props = mapper.createObjectNode().put("Name", "my-group");

        provisioner.provision(r, props, ctx("my-group"));

        verify(schedulerService, never()).createScheduleGroup(anyString(), any(), anyString());
        assertEquals("my-group", r.getPhysicalId());
        assertEquals("arn:aws:scheduler:us-east-1:000000000000:schedule-group/my-group",
                r.getAttributes().get("Arn"));
    }

    @Test
    void createSetsCreationDateLastModificationDateAndState() {
        // pgermosen review on PR #2796: real AWS's Fn::GetAtt for AWS::Scheduler::ScheduleGroup
        // supports Arn, CreationDate, LastModificationDate and State; only Arn was populated,
        // so Fn::GetAtt MyGroup.State resolved to nothing.
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        Instant modified = Instant.parse("2026-01-02T00:00:00Z");
        ScheduleGroup g = new ScheduleGroup("my-group",
                "arn:aws:scheduler:us-east-1:000000000000:schedule-group/my-group",
                "ACTIVE", created, modified);
        when(schedulerService.createScheduleGroup(eq("my-group"), any(), eq("us-east-1"))).thenReturn(g);
        StackResource r = resource("MyGroup");
        ObjectNode props = mapper.createObjectNode().put("Name", "my-group");

        provisioner.provision(r, props, ctx());

        assertEquals(created.toString(), r.getAttributes().get("CreationDate"));
        assertEquals(modified.toString(), r.getAttributes().get("LastModificationDate"));
        assertEquals("ACTIVE", r.getAttributes().get("State"));
    }

    @Test
    void tagsWithIntrinsicKeyOrValueAreResolvedThroughTheEngine() {
        // pgermosen review on PR #2796 raised this as a gap; the loop already calls
        // ctx.engine().resolve(...) for both Key and Value, so this pins that a Ref/Fn::Sub node
        // resolves to the engine's answer rather than a raw JsonNode-to-text conversion of the
        // intrinsic object itself.
        when(schedulerService.createScheduleGroup(eq("my-group"), any(), eq("us-east-1")))
                .thenReturn(group("my-group", Map.of()));
        StackResource r = resource("MyGroup");
        ObjectNode props = mapper.createObjectNode().put("Name", "my-group");
        ObjectNode keyRef = mapper.createObjectNode();
        keyRef.put("Ref", "TagKeyParam");
        ObjectNode valueSub = mapper.createObjectNode();
        valueSub.put("Fn::Sub", "env-${AWS::Region}");
        ObjectNode tag = mapper.createObjectNode();
        tag.set("Key", keyRef);
        tag.set("Value", valueSub);
        props.set("Tags", mapper.createArrayNode().add(tag));

        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolve(keyRef)).thenReturn("resolved-key");
        when(engine.resolve(valueSub)).thenReturn("env-us-east-1");
        ProvisionContext ctx = new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");

        provisioner.provision(r, props, ctx);

        verify(schedulerService).createScheduleGroup(
                "my-group", Map.of("resolved-key", "env-us-east-1"), "us-east-1");
    }

    @Test
    void sameStackRetryRemovesTagsDroppedFromTheTemplate() {
        // pgermosen review on PR #2796: the retry path only ever added tags on conflict, so a tag
        // removed from the template stayed on the live resource across every subsequent update.
        when(schedulerService.getScheduleGroup("my-group", "us-east-1"))
                .thenReturn(group("my-group", Map.of("Old", "value", "Keep", "same")));
        StackResource r = resource("MyGroup");
        ObjectNode props = mapper.createObjectNode().put("Name", "my-group");
        ObjectNode tag = mapper.createObjectNode().put("Key", "Keep").put("Value", "same");
        props.set("Tags", mapper.createArrayNode().add(tag));

        provisioner.provision(r, props, ctx("my-group"));

        verify(schedulerService).untagScheduleGroup(eq("my-group"), eq("us-east-1"),
                argThat(keys -> keys.size() == 1 && keys.contains("Old")));
        verify(schedulerService).tagScheduleGroup("my-group", "us-east-1", Map.of("Keep", "same"));
    }

    @Test
    void sameStackRetryWithEmptyTemplateTagsRemovesAllExistingTags() {
        // The Tags property emptied entirely on the template is the same case as a dropped key,
        // just for every key at once - the whole existing tag set must come off.
        when(schedulerService.getScheduleGroup("my-group", "us-east-1"))
                .thenReturn(group("my-group", Map.of("Old", "value")));
        StackResource r = resource("MyGroup");
        ObjectNode props = mapper.createObjectNode().put("Name", "my-group");

        provisioner.provision(r, props, ctx("my-group"));

        verify(schedulerService).untagScheduleGroup(eq("my-group"), eq("us-east-1"),
                argThat(keys -> keys.size() == 1 && keys.contains("Old")));
        verify(schedulerService, never()).tagScheduleGroup(anyString(), anyString(), any());
    }

    @Test
    void sameStackRetryWithUnresolvableTagsDoesNotUntagExistingTags() {
        // Greptile review on PR #2796: the engine's Fn::If support is scalar-only, so a Tags
        // property wrapped in Fn::If (choosing between two tag lists) does not resolve to an array
        // here - props.get("Tags").isArray() is false, same as no Tags at all. Before this test, that
        // made the retry path's tag-diff see an empty desired set and untag every live key, actively
        // destroying tags a resolvable Tags property never asked to remove.
        when(schedulerService.getScheduleGroup("my-group", "us-east-1"))
                .thenReturn(group("my-group", Map.of("Old", "value")));
        StackResource r = resource("MyGroup");
        ObjectNode props = mapper.createObjectNode().put("Name", "my-group");
        ObjectNode condition = mapper.createObjectNode();
        condition.set("Fn::If", mapper.createArrayNode().add("UseProdTags")
                .add(mapper.createArrayNode()).add(mapper.createArrayNode()));
        props.set("Tags", condition);

        provisioner.provision(r, props, ctx("my-group"));

        verify(schedulerService, never()).untagScheduleGroup(anyString(), anyString(), any());
        verify(schedulerService, never()).tagScheduleGroup(anyString(), anyString(), any());
    }

    @Test
    void sameStackRetryWithNoTagChangesDoesNotCallUntagOrTag() {
        when(schedulerService.getScheduleGroup("my-group", "us-east-1"))
                .thenReturn(group("my-group", Map.of()));
        StackResource r = resource("MyGroup");
        ObjectNode props = mapper.createObjectNode().put("Name", "my-group");

        provisioner.provision(r, props, ctx("my-group"));

        verify(schedulerService, never()).untagScheduleGroup(anyString(), anyString(), any());
        verify(schedulerService, never()).tagScheduleGroup(anyString(), anyString(), any());
    }

    @Test
    void conflictForADifferentPhysicalIdIsNotAdopted() {
        // No prior physical id recorded (fresh create) colliding with someone else's group of the
        // same name: not this attempt's retry, must fail rather than silently adopting a stranger's
        // group.
        when(schedulerService.createScheduleGroup(eq("taken-name"), any(), eq("us-east-1")))
                .thenThrow(new AwsException("ConflictException", "already exists", 409));
        StackResource r = resource("MyGroup");
        ObjectNode props = mapper.createObjectNode().put("Name", "taken-name");

        AwsException thrown = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));

        assertEquals("ConflictException", thrown.getErrorCode());
        verify(schedulerService, never()).getScheduleGroup(anyString(), anyString());
    }

    @Test
    void deleteDelegatesToService() {
        provisioner.delete("AWS::Scheduler::ScheduleGroup", "my-group", "us-east-1");
        verify(schedulerService).deleteScheduleGroup("my-group", "us-east-1");
    }

    @Test
    void deleteAlreadyGoneIsTreatedAsSuccess() {
        doThrowNotFoundOnDelete("my-group");
        provisioner.delete("AWS::Scheduler::ScheduleGroup", "my-group", "us-east-1");
    }

    private void doThrowNotFoundOnDelete(String name) {
        org.mockito.Mockito.doThrow(new AwsException("ResourceNotFoundException", "not found", 404))
                .when(schedulerService).deleteScheduleGroup(name, "us-east-1");
    }
}
