package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.pipes.PipesService;
import io.github.hectorvent.floci.services.pipes.model.DesiredState;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::Pipes::Pipe}, whose provision body doubles as its update path.
 *
 * <p>{@code PipesService} is stubbed with the store it really keeps, so a second create under a
 * name already on file raises the production {@code ConflictException} instead of a bare
 * {@code verify(never())}.
 */
class PipesCfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String SOURCE_QUEUE_ARN = "arn:aws:sqs:us-east-1:000000000000:example-queue";
    private static final String TARGET_QUEUE_ARN = "arn:aws:sqs:us-east-1:000000000000:example-target-queue";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/example-pipe-role";
    private static final String NEW_TARGET_QUEUE_ARN = "arn:aws:sqs:us-east-1:000000000000:new-target-queue";
    private static final String ENRICHMENT_ARN =
            "arn:aws:lambda:us-east-1:000000000000:function:example-enrichment";

    private PipesService pipes;
    private PipesCfnProvisioner provisioner;
    private CloudFormationTemplateEngine engine;

    /** The pipes the stubbed service holds, keyed by name, standing in for its storage backend. */
    private Map<String, Pipe> pipesOnFile;

    @BeforeEach
    void setUp() {
        pipes = mock(PipesService.class);
        provisioner = new PipesCfnProvisioner(pipes, MAPPER);
        pipesOnFile = new LinkedHashMap<>();

        engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(i -> i.getArgument(0));

        when(pipes.createPipe(anyString(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyString())).thenAnswer(i -> {
            String name = i.getArgument(0);
            if (pipesOnFile.containsKey(name)) {
                throw new AwsException("ConflictException", "Pipe " + name + " already exists.", 409);
            }
            Pipe pipe = new Pipe();
            pipe.setName(name);
            pipe.setArn("arn:aws:pipes:us-east-1:000000000000:pipe/" + name);
            pipe.setSource(i.getArgument(1));
            pipe.setTarget(i.getArgument(2));
            pipe.setRoleArn(i.getArgument(3));
            pipe.setDescription(i.getArgument(4));
            pipe.setDesiredState(i.getArgument(5));
            pipe.setEnrichment(i.getArgument(6));
            pipe.setSourceParameters(i.getArgument(7));
            pipe.setTargetParameters(i.getArgument(8));
            pipe.setEnrichmentParameters(i.getArgument(9));
            Map<String, String> tags = i.getArgument(10);
            pipe.setTags(tags != null ? new HashMap<>(tags) : new HashMap<>());
            // The creation time is how a rollback tells the pipe this run created from one that
            // already stood under the same name, so the stub stamps it as the service does.
            pipe.setCreationTime(Instant.now());
            pipesOnFile.put(name, pipe);
            return pipe;
        });
        when(pipes.listTags(anyString(), anyString()))
                .thenAnswer(i -> pipeByArn(i.getArgument(1)).getTags());
        doAnswer(i -> {
            pipeByArn(i.getArgument(1)).getTags().putAll(i.<Map<String, String>>getArgument(2));
            return null;
        }).when(pipes).tagResource(anyString(), anyString(), any());
        doAnswer(i -> {
            Pipe pipe = pipeByArn(i.getArgument(1));
            i.<List<String>>getArgument(2).forEach(pipe.getTags()::remove);
            return null;
        }).when(pipes).untagResource(anyString(), anyString(), any());
        when(pipes.describePipe(anyString(), anyString())).thenAnswer(i -> {
            String name = i.getArgument(0);
            Pipe pipe = pipesOnFile.get(name);
            if (pipe == null) {
                throw new AwsException("NotFoundException", "Pipe " + name + " does not exist.", 404);
            }
            return pipe;
        });
        when(pipes.updatePipe(anyString(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyString())).thenAnswer(i -> {
            String name = i.getArgument(0);
            Pipe pipe = pipesOnFile.get(name);
            if (pipe == null) {
                throw new AwsException("NotFoundException", "Pipe " + name + " does not exist.", 404);
            }
            applyIfPresent(i.getArgument(1), pipe::setTarget);
            applyIfPresent(i.getArgument(2), pipe::setRoleArn);
            applyIfPresent(i.getArgument(3), pipe::setDescription);
            applyIfPresent(i.getArgument(4), pipe::setDesiredState);
            applyIfPresent(i.getArgument(5), pipe::setEnrichment);
            applyIfPresent(i.getArgument(6), pipe::setSourceParameters);
            applyIfPresent(i.getArgument(7), pipe::setTargetParameters);
            applyIfPresent(i.getArgument(8), pipe::setEnrichmentParameters);
            return pipe;
        });
        // restorePipe writes every property as given, clearing the ones passed as null; only the
        // desired state is left alone when unset, since currentState is read off it.
        when(pipes.restorePipe(anyString(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyString())).thenAnswer(i -> {
            String name = i.getArgument(0);
            Pipe pipe = pipesOnFile.get(name);
            if (pipe == null) {
                throw new AwsException("NotFoundException", "Pipe " + name + " does not exist.", 404);
            }
            pipe.setTarget(i.getArgument(1));
            pipe.setRoleArn(i.getArgument(2));
            pipe.setDescription(i.getArgument(3));
            applyIfPresent(i.<DesiredState>getArgument(4), pipe::setDesiredState);
            pipe.setEnrichment(i.getArgument(5));
            pipe.setSourceParameters(i.getArgument(6));
            pipe.setTargetParameters(i.getArgument(7));
            pipe.setEnrichmentParameters(i.getArgument(8));
            return pipe;
        });
        doAnswer(i -> {
            String name = i.getArgument(0);
            if (pipesOnFile.remove(name) == null) {
                throw new AwsException("NotFoundException", "Pipe " + name + " does not exist.", 404);
            }
            return null;
        }).when(pipes).deletePipe(anyString(), anyString());
    }

    /** UpdatePipe reads an absent value as "leave this one alone", which the stub mirrors. */
    private static <T> void applyIfPresent(T value, java.util.function.Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private Pipe pipeByArn(String arn) {
        return pipesOnFile.values().stream()
                .filter(pipe -> arn.equals(pipe.getArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Resource " + arn + " does not exist.", 404));
    }

    private static JsonNode props(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static StackResource stackResource(String priorPhysicalId) {
        StackResource r = new StackResource();
        r.setLogicalId("MyPipe");
        r.setResourceType("AWS::Pipes::Pipe");
        r.setPhysicalId(priorPhysicalId);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private StackResource provision(String json, String priorPhysicalId) {
        StackResource r = stackResource(priorPhysicalId);
        provisioner.provision(r, props(json),
                new ProvisionContext(engine, REGION, ACCOUNT_ID, "TestStack", priorPhysicalId));
        return r;
    }

    private static String pipeTemplate(String name, String source, String target) {
        return """
                {"Name": "%s", "Source": "%s", "Target": "%s", "RoleArn": "%s"}
                """.formatted(name, source, target, ROLE_ARN);
    }

    private static String taggedPipeTemplate(String tagsJson) {
        return """
                {"Name": "MyPipe", "Source": "%s", "Target": "%s", "RoleArn": "%s", "Tags": %s}
                """.formatted(SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN, ROLE_ARN, tagsJson);
    }

    /** Every property the update path can change, so a rollback has all of them to put back. */
    private static String pipeTemplateWithEveryUpdatableProperty(String target, String description,
                                                                 String tagsJson) {
        return """
                {"Name": "MyPipe", "Source": "%s", "Target": "%s", "RoleArn": "%s",
                 "Description": "%s", "DesiredState": "STOPPED", "Enrichment": "%s",
                 "TargetParameters": {"InputTemplate": "%s"}, "Tags": %s}
                """.formatted(SOURCE_QUEUE_ARN, target, ROLE_ARN, description, ENRICHMENT_ARN,
                description, tagsJson);
    }

    private static JsonNode targetParametersFor(String description) {
        return props("""
                {"InputTemplate": "%s"}
                """.formatted(description));
    }

    @Test
    void refIsThePipeNameAndGetAttExposesArn() {
        StackResource r = provision(pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), null);

        verify(pipes).createPipe(eq("MyPipe"), eq(SOURCE_QUEUE_ARN), eq(TARGET_QUEUE_ARN), eq(ROLE_ARN),
                any(), eq(DesiredState.RUNNING), any(), any(), any(), any(), any(), eq(REGION));
        assertEquals("MyPipe", r.getPhysicalId());
        assertEquals("arn:aws:pipes:us-east-1:000000000000:pipe/MyPipe", r.getAttributes().get("Arn"));
    }

    @Test
    void desiredStateStoppedIsHonouredAndAnythingElseRuns() {
        provision("""
                {"Name": "MyPipe", "DesiredState": "STOPPED"}
                """, null);
        verify(pipes).createPipe(any(), any(), any(), any(), any(), eq(DesiredState.STOPPED),
                any(), any(), any(), any(), any(), any());

        provision("""
                {"Name": "OtherPipe", "DesiredState": "SOMETHING_ELSE"}
                """, null);
        verify(pipes).createPipe(eq("OtherPipe"), any(), any(), any(), any(), eq(DesiredState.RUNNING),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void deleteReachesTheService() {
        pipesOnFile.put("MyPipe", new Pipe());
        provisioner.delete("AWS::Pipes::Pipe", "MyPipe", REGION);
        verify(pipes).deletePipe("MyPipe", REGION);
    }

    /**
     * provision() re-runs on every UpdateStack, so an unchanged name must update the pipe rather
     * than call createPipe again, which the service rejects with ConflictException.
     */
    @Test
    void anUnchangedNameUpdatesThePipeInsteadOfRecreatingIt() {
        StackResource created = provision(
                pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), null);

        StackResource updated = provision(
                pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, "arn:aws:sqs:us-east-1:000000000000:new-target-queue"),
                created.getPhysicalId());

        verify(pipes).updatePipe(eq("MyPipe"),
                eq("arn:aws:sqs:us-east-1:000000000000:new-target-queue"), eq(ROLE_ARN), any(),
                eq(DesiredState.RUNNING), any(), any(), any(), any(), eq(REGION));
        verify(pipes, never()).deletePipe(anyString(), anyString());
        assertEquals("MyPipe", updated.getPhysicalId(), "Ref is unchanged by the update");
        assertEquals("arn:aws:pipes:us-east-1:000000000000:pipe/MyPipe",
                updated.getAttributes().get("Arn"), "Fn::GetAtt Arn is unchanged by the update");
    }

    /** Source is create-only on AWS::Pipes::Pipe, which is why updatePipe takes no source. */
    @Test
    void aChangedSourceIsRefusedAsReplacementWorthy() {
        StackResource created = provision(
                pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), null);

        AwsException refusal = assertThrows(AwsException.class, () -> provision(
                pipeTemplate("MyPipe", "arn:aws:sqs:us-east-1:000000000000:other-queue", TARGET_QUEUE_ARN),
                created.getPhysicalId()));

        assertEquals("ValidationError", refusal.getErrorCode());
        assertEquals("Updating Source requires resource replacement, which is not supported.",
                refusal.getMessage());
        assertEquals(400, refusal.getHttpStatus());
        verify(pipes, never()).updatePipe(anyString(), any(), any(), any(), any(), any(), any(), any(),
                any(), anyString());
    }

    /**
     * A template with no Source is missing a required property, the same fault the create path
     * reports. Diagnosing it as a replacement sends the reader after a change that never happened.
     */
    @Test
    void anAbsentSourceOnUpdateReportsTheRequiredPropertyFault() {
        provision(pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), null);

        AwsException failure = assertThrows(AwsException.class, () -> provision("""
                {"Name": "MyPipe", "Target": "%s", "RoleArn": "%s"}
                """.formatted(TARGET_QUEUE_ARN, ROLE_ARN), "MyPipe"));

        assertEquals("ValidationException", failure.getErrorCode());
        assertEquals("Source is required", failure.getMessage());
        assertEquals(400, failure.getHttpStatus());
        verify(pipes, never()).updatePipe(anyString(), any(), any(), any(), any(), any(), any(), any(),
                any(), anyString());
    }

    /** A Source that resolves blank is the same missing required property, not a changed Source. */
    @Test
    void aBlankSourceOnUpdateReportsTheRequiredPropertyFault() {
        provision(pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), null);

        AwsException failure = assertThrows(AwsException.class,
                () -> provision(pipeTemplate("MyPipe", "", TARGET_QUEUE_ARN), "MyPipe"));

        assertEquals("ValidationException", failure.getErrorCode());
        assertEquals("Source is required", failure.getMessage());
    }

    @Test
    void aChangedTagValueReachesThePipe() {
        provision(taggedPipeTemplate("""
                [{"Key": "Env", "Value": "dev"}]
                """), null);

        provision(taggedPipeTemplate("""
                [{"Key": "Env", "Value": "prod"}]
                """), "MyPipe");

        verify(pipes).tagResource(REGION, "arn:aws:pipes:us-east-1:000000000000:pipe/MyPipe",
                Map.of("Env", "prod"));
        assertEquals(Map.of("Env", "prod"), pipesOnFile.get("MyPipe").getTags());
    }

    @Test
    void aTagDroppedFromTheTemplateIsRemovedFromThePipe() {
        provision(taggedPipeTemplate("""
                [{"Key": "Env", "Value": "dev"}, {"Key": "Owner", "Value": "example-team"}]
                """), null);

        provision(taggedPipeTemplate("""
                [{"Key": "Env", "Value": "dev"}]
                """), "MyPipe");

        verify(pipes).untagResource(REGION, "arn:aws:pipes:us-east-1:000000000000:pipe/MyPipe",
                List.of("Owner"));
        assertEquals(Map.of("Env", "dev"), pipesOnFile.get("MyPipe").getTags());
    }

    /**
     * No tags on either side leaves the pipe's tags alone. The stored tags are still read, since a
     * template that drops every tag has to have them removed.
     */
    @Test
    void noTagsOnEitherSideMutatesNoTag() {
        provision(pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), null);

        provision(pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), "MyPipe");

        verify(pipes, never()).tagResource(anyString(), anyString(), any());
        verify(pipes, never()).untagResource(anyString(), anyString(), any());
    }

    /**
     * On a rename provision creates the replacement and records the pipe left under the old name,
     * which is deleted only once the stack update commits and completeUpdate runs.
     */
    @Test
    void aRenameCreatesTheReplacementAndLeavesTheOriginalToTheCommittedCleanup() {
        provision(pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), null);

        StackResource renamed = provision(
                pipeTemplate("MyRenamedPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), "MyPipe");

        verify(pipes).createPipe(eq("MyRenamedPipe"), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
        verify(pipes, never()).deletePipe(anyString(), anyString());
        assertTrue(pipesOnFile.containsKey("MyPipe"), "the prior pipe outlives provision");
        assertEquals("MyRenamedPipe", renamed.getPhysicalId());
        assertEquals("arn:aws:pipes:us-east-1:000000000000:pipe/MyRenamedPipe",
                renamed.getAttributes().get("Arn"));

        assertTrue(provisioner.hasReplacementUpdate(renamed));
        assertEquals("MyPipe", provisioner.updateCleanupPhysicalId(renamed));

        UpdateCleanupResult cleanup = provisioner.completeUpdate(renamed);

        InOrder order = inOrder(pipes);
        order.verify(pipes).createPipe(eq("MyRenamedPipe"), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
        order.verify(pipes).deletePipe("MyPipe", REGION);
        assertEquals(new UpdateCleanupResult(true, true, "MyPipe", 0, null), cleanup);
        assertFalse(pipesOnFile.containsKey("MyPipe"), "the prior pipe is gone once cleanup runs");

        provisioner.clearUpdate(renamed);
        assertFalse(provisioner.hasReplacementUpdate(renamed),
                "the cleanup record is spent once the prior pipe is gone");
    }

    /** A pipe kept by UpdateReplacePolicy Retain is not deleted when the rename cleanup runs. */
    @Test
    void aRetainedPriorPipeSurvivesTheCommittedCleanup() {
        provision(pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), null);
        StackResource renamed = provision(
                pipeTemplate("MyRenamedPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), "MyPipe");
        renamed.setUpdateReplacePolicy("Retain");

        assertNull(provisioner.updateCleanupPhysicalId(renamed));
        assertEquals(new UpdateCleanupResult(true, true, "MyPipe", 0, null),
                provisioner.completeUpdate(renamed));
        verify(pipes, never()).deletePipe(anyString(), anyString());
        assertTrue(pipesOnFile.containsKey("MyPipe"));
    }

    /** A pipe with no rename behind it owes no cleanup, so the caller moves on to the next one. */
    @Test
    void aPipeNoRenameDisplacedOwesNoCleanup() {
        StackResource created = provision(
                pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), null);

        assertFalse(provisioner.hasReplacementUpdate(created));
        assertNull(provisioner.updateCleanupPhysicalId(created));
        assertEquals(new UpdateCleanupResult(false, true, null, 0, null),
                provisioner.completeUpdate(created));
        verify(pipes, never()).deletePipe(anyString(), anyString());
    }

    /**
     * A rename whose createPipe fails leaves the prior pipe on file, so the rollback walker must be
     * told not to restore something that was never deleted.
     */
    @Test
    void aFailedRenameKeepsThePriorPipeAndMarksTheResourceRestored() {
        provision(pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), null);
        Pipe squatter = new Pipe();
        squatter.setName("MyRenamedPipe");
        pipesOnFile.put("MyRenamedPipe", squatter);

        StackResource r = stackResource("MyPipe");
        JsonNode template = props(pipeTemplate("MyRenamedPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN));
        ProvisionContext ctx = new ProvisionContext(engine, REGION, ACCOUNT_ID, "TestStack", "MyPipe");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(r, template, ctx));

        assertEquals("ConflictException", failure.getErrorCode());
        assertTrue(pipesOnFile.containsKey("MyPipe"), "the prior pipe is still on file");
        verify(pipes, never()).deletePipe(anyString(), anyString());
        assertEquals("true", r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR));
        assertEquals("MyPipe", r.getPhysicalId(), "the resource still points at the prior pipe");
    }

    /**
     * The replacement is already on file by the time the old pipe is dropped, so a failing
     * deletePipe must not fail the resource: the update stays committed and the failure is reported
     * one attempt at a time, until the caller gives up on the third and names the pipe left behind.
     */
    @Test
    void aFailedDeleteOfTheRenamedPipeReportsEachAttemptAndKeepsTheUpdate() {
        provision(pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), null);
        doThrow(new RuntimeException("boom")).when(pipes).deletePipe("MyPipe", REGION);

        StackResource renamed = provision(
                pipeTemplate("MyRenamedPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), "MyPipe");

        assertEquals("MyRenamedPipe", renamed.getPhysicalId(),
                "the new pipe is still the resource's physical id");
        assertEquals("arn:aws:pipes:us-east-1:000000000000:pipe/MyRenamedPipe",
                renamed.getAttributes().get("Arn"));

        assertEquals(new UpdateCleanupResult(true, false, "MyPipe", 1, "boom"),
                provisioner.completeUpdate(renamed));
        assertEquals(new UpdateCleanupResult(true, false, "MyPipe", 2, "boom"),
                provisioner.completeUpdate(renamed));
        assertEquals(new UpdateCleanupResult(true, false, "MyPipe", 3, "boom"),
                provisioner.completeUpdate(renamed));
        assertEquals(new UpdateCleanupResult(true, false, "MyPipe", 3, "boom"),
                provisioner.completeUpdate(renamed),
                "a fourth call reports the same give-up without another delete");

        verify(pipes, times(3)).deletePipe("MyPipe", REGION);
        assertTrue(pipesOnFile.containsKey("MyPipe"), "the pipe the rename displaced is left behind");
    }

    /** A prior pipe already deleted out of band leaves the cleanup nothing to do. */
    @Test
    void aPriorPipeAlreadyGoneCompletesTheCleanupWithoutADelete() {
        provision(pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), null);
        StackResource renamed = provision(
                pipeTemplate("MyRenamedPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), "MyPipe");
        pipesOnFile.remove("MyPipe");

        assertEquals(new UpdateCleanupResult(true, true, "MyPipe", 0, null),
                provisioner.completeUpdate(renamed));
        verify(pipes, never()).deletePipe(anyString(), anyString());
    }

    /**
     * Only a missing pipe falls back to the create arm. Any other describePipe failure reaches the
     * user as itself, instead of being turned into ConflictException by a create that cannot work.
     */
    @Test
    void anErrorOtherThanNotFoundFromDescribePipePropagates() {
        provision(pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), null);
        doThrow(new AwsException("InternalFailure", "storage is unavailable", 500))
                .when(pipes).describePipe("MyPipe", REGION);

        AwsException failure = assertThrows(AwsException.class, () -> provision(
                pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), "MyPipe"));

        assertEquals("InternalFailure", failure.getErrorCode());
        assertEquals("storage is unavailable", failure.getMessage());
        assertEquals(500, failure.getHttpStatus());
    }

    /** A pipe with no template Name keeps its generated physical name across updates. */
    @Test
    void anUnnamedPipeKeepsItsGeneratedNameAcrossUpdates() {
        String unnamedTemplate = """
                {"Source": "%s", "Target": "%s", "RoleArn": "%s"}
                """.formatted(SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN, ROLE_ARN);

        StackResource created = provision(unnamedTemplate, null);
        assertTrue(created.getPhysicalId().startsWith("TestStack-MyPipe-"), created.getPhysicalId());

        StackResource updated = provision(unnamedTemplate, created.getPhysicalId());

        assertEquals(created.getPhysicalId(), updated.getPhysicalId());
        verify(pipes).updatePipe(eq(created.getPhysicalId()), any(), any(), any(), any(), any(), any(),
                any(), any(), eq(REGION));
        assertEquals(1, pipesOnFile.size(), "no second pipe was created under a fresh name");
    }

    /** A pipe deleted out of band since the prior deploy is created again under the same name. */
    @Test
    void aPipeDeletedOutOfBandFallsBackToCreate() {
        StackResource r = provision(pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), "MyPipe");

        verify(pipes).createPipe(eq("MyPipe"), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), eq(REGION));
        verify(pipes, never()).updatePipe(anyString(), any(), any(), any(), any(), any(), any(), any(),
                any(), anyString());
        assertEquals("MyPipe", r.getPhysicalId());
        assertNull(r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR));
    }

    /**
     * updatePipe lands before the tag calls, so a tagResource that fails would leave the pipe
     * carrying the new configuration under tags that are neither the old nor the new set. The
     * provisioner puts the configuration and the tags back from its snapshot before the failure
     * leaves it, and reports the failure that interrupted the update.
     */
    @Test
    void aPipeMutatedByAFailedUpdateIsRestoredBeforeTheFailureLeavesTheProvisioner() {
        provision(pipeTemplateWithEveryUpdatableProperty(TARGET_QUEUE_ARN, "the first target", """
                [{"Key": "Env", "Value": "dev"}, {"Key": "Owner", "Value": "example-team"}]
                """), null);
        doThrow(new AwsException("InternalFailure", "tagging is unavailable", 500))
                .when(pipes).tagResource(anyString(), anyString(), eq(Map.of("Env", "prod")));

        StackResource r = stackResource("MyPipe");
        JsonNode template = props(pipeTemplateWithEveryUpdatableProperty(
                NEW_TARGET_QUEUE_ARN, "the second target", """
                        [{"Key": "Env", "Value": "prod"}]
                        """));
        ProvisionContext ctx = new ProvisionContext(engine, REGION, ACCOUNT_ID, "TestStack", "MyPipe");

        AwsException reported = assertThrows(AwsException.class,
                () -> provisioner.provision(r, template, ctx));
        assertEquals("tagging is unavailable", reported.getMessage(),
                "the failure that interrupted the update is what the caller gets");

        verify(pipes).restorePipe("MyPipe", TARGET_QUEUE_ARN, ROLE_ARN, "the first target",
                DesiredState.STOPPED, ENRICHMENT_ARN, null, targetParametersFor("the first target"),
                null, REGION);
        Pipe restored = pipesOnFile.get("MyPipe");
        assertEquals(TARGET_QUEUE_ARN, restored.getTarget());
        assertEquals("the first target", restored.getDescription());
        assertEquals(targetParametersFor("the first target"), restored.getTargetParameters());
        assertEquals(Map.of("Env", "dev", "Owner", "example-team"), restored.getTags());
        assertNull(r.getAttributes().get(CfnRollback.PIPE_UPDATE_SNAPSHOT_ATTR),
                "the snapshot is spent once the pipe is back");
        assertNull(r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR),
                "the restore succeeded, so the stack rolls back cleanly");
        assertFalse(provisioner.rollbackUpdate(r),
                "the rollback hook finds nothing left to put back");
    }

    /**
     * A restore that fails itself leaves the pipe in a configuration nobody recorded. The reason
     * goes on the resource, which is what makes the stack report UPDATE_ROLLBACK_FAILED, and the
     * failure that interrupted the update still reaches the caller carrying it.
     */
    @Test
    void aRestoreThatFailsMarksTheResourceAndKeepsBothFailures() {
        provision(pipeTemplateWithEveryUpdatableProperty(TARGET_QUEUE_ARN, "the first target", """
                [{"Key": "Env", "Value": "dev"}]
                """), null);
        doThrow(new AwsException("InternalFailure", "tagging is unavailable", 500))
                .when(pipes).tagResource(anyString(), anyString(), eq(Map.of("Env", "prod")));
        doThrow(new AwsException("InternalFailure", "the pipe cannot be written", 500))
                .when(pipes).restorePipe(anyString(), any(), any(), any(), any(), any(), any(),
                        any(), any(), anyString());

        StackResource r = stackResource("MyPipe");
        JsonNode template = props(pipeTemplateWithEveryUpdatableProperty(
                NEW_TARGET_QUEUE_ARN, "the second target", """
                        [{"Key": "Env", "Value": "prod"}]
                        """));
        ProvisionContext ctx = new ProvisionContext(engine, REGION, ACCOUNT_ID, "TestStack", "MyPipe");

        AwsException reported = assertThrows(AwsException.class,
                () -> provisioner.provision(r, template, ctx));

        assertEquals("tagging is unavailable", reported.getMessage());
        assertEquals("the pipe cannot be written", reported.getSuppressed()[0].getMessage(),
                "the restore failure travels with the one that interrupted the update");
        assertEquals("the pipe cannot be written",
                r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    /**
     * A restore failure carrying no message still has to name something the stack event can show,
     * so the exception's own type is what the resource records.
     */
    @Test
    void aRestoreFailureWithoutAMessageIsRecordedUnderItsType() {
        provision(pipeTemplateWithEveryUpdatableProperty(TARGET_QUEUE_ARN, "the first target", """
                [{"Key": "Env", "Value": "dev"}]
                """), null);
        doThrow(new AwsException("InternalFailure", "tagging is unavailable", 500))
                .when(pipes).tagResource(anyString(), anyString(), eq(Map.of("Env", "prod")));
        doThrow(new IllegalStateException())
                .when(pipes).restorePipe(anyString(), any(), any(), any(), any(), any(), any(),
                        any(), any(), anyString());

        StackResource r = stackResource("MyPipe");
        JsonNode template = props(pipeTemplateWithEveryUpdatableProperty(
                NEW_TARGET_QUEUE_ARN, "the second target", """
                        [{"Key": "Env", "Value": "prod"}]
                        """));
        ProvisionContext ctx = new ProvisionContext(engine, REGION, ACCOUNT_ID, "TestStack", "MyPipe");

        assertThrows(AwsException.class, () -> provisioner.provision(r, template, ctx));

        assertEquals("IllegalStateException",
                r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    /** A pipe this stack update never mutated has no snapshot, so there is nothing to put back. */
    @Test
    void aPipeThisUpdateNeverMutatedIsNotRolledBack() {
        StackResource created = provision(
                pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), null);

        assertFalse(provisioner.rollbackUpdate(created));

        verify(pipes, never()).restorePipe(anyString(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyString());
    }

    /**
     * A snapshot describes the update in flight. The one a previous update left behind is dropped
     * before this run decides whether it mutates the pipe, so a rollback never puts back a target
     * the pipe stopped carrying two updates ago.
     */
    @Test
    void anUpdateThatFailsBeforeMutatingThePipeRollsNothingBack() {
        provision(pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), null);
        StackResource updated = provision(
                pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, NEW_TARGET_QUEUE_ARN), "MyPipe");

        StackResource r = stackResource("MyPipe");
        r.getAttributes().putAll(updated.getAttributes());
        JsonNode template = props(pipeTemplate("MyPipe",
                "arn:aws:sqs:us-east-1:000000000000:other-queue", NEW_TARGET_QUEUE_ARN));
        ProvisionContext ctx = new ProvisionContext(engine, REGION, ACCOUNT_ID, "TestStack", "MyPipe");

        assertThrows(AwsException.class, () -> provisioner.provision(r, template, ctx));

        assertFalse(provisioner.rollbackUpdate(r));
        assertEquals(NEW_TARGET_QUEUE_ARN, pipesOnFile.get("MyPipe").getTarget());
    }

    /**
     * A rename is a replacement, and a stack update that fails after it never commits. The rollback
     * points the resource back at the prior pipe, which was only displaced and is put back untouched,
     * and drops the replacement so the failed update leaves nothing behind.
     */
    @Test
    void aRenameRolledBackPointsAtThePriorPipeAndDropsTheReplacement() {
        provision(pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), null);
        StackResource renamed = provision(
                pipeTemplate("MyRenamedPipe", SOURCE_QUEUE_ARN, NEW_TARGET_QUEUE_ARN), "MyPipe");

        assertTrue(provisioner.rollbackUpdate(renamed));

        assertEquals("MyPipe", renamed.getPhysicalId(), "Ref names the prior pipe again");
        assertEquals("arn:aws:pipes:us-east-1:000000000000:pipe/MyPipe",
                renamed.getAttributes().get("Arn"), "Fn::GetAtt Arn names the prior pipe again");
        verify(pipes).deletePipe("MyRenamedPipe", REGION);
        assertFalse(pipesOnFile.containsKey("MyRenamedPipe"),
                "the replacement the failed update created is gone");
        assertEquals(TARGET_QUEUE_ARN, pipesOnFile.get("MyPipe").getTarget(),
                "the prior pipe was only displaced, so it is put back untouched");
        verify(pipes, never()).restorePipe(anyString(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyString());
        verify(pipes, never()).updatePipe(anyString(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyString());
        assertFalse(provisioner.hasReplacementUpdate(renamed),
                "the rename cleanup does not fire after a rollback");
        assertEquals(new UpdateCleanupResult(false, true, null, 0, null),
                provisioner.completeUpdate(renamed));
    }

    /**
     * Deleting the pipe standing under the replacement's name is only safe when this update is what
     * created it. Any other pipe found there belongs to somebody else, so the rollback refuses and
     * leaves both pipes where they are.
     */
    @Test
    void aRenameRollbackRefusesToDeleteAPipeThisUpdateDidNotCreate() {
        provision(pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), null);
        StackResource renamed = provision(
                pipeTemplate("MyRenamedPipe", SOURCE_QUEUE_ARN, NEW_TARGET_QUEUE_ARN), "MyPipe");
        Pipe unrelatedPipe = new Pipe();
        unrelatedPipe.setName("MyRenamedPipe");
        unrelatedPipe.setArn("arn:aws:pipes:us-east-1:000000000000:pipe/MyRenamedPipe");
        unrelatedPipe.setTarget(TARGET_QUEUE_ARN);
        unrelatedPipe.setCreationTime(Instant.ofEpochMilli(1_000_000L));
        pipesOnFile.put("MyRenamedPipe", unrelatedPipe);

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> provisioner.rollbackUpdate(renamed));

        assertEquals("Refusing to delete pipe MyRenamedPipe while rolling back resource MyPipe: "
                + "it was not created by this stack update, so both pipes are left as they are.",
                refusal.getMessage());
        verify(pipes, never()).deletePipe(anyString(), anyString());
        assertTrue(pipesOnFile.containsKey("MyPipe"), "the prior pipe is left alone");
        assertEquals(unrelatedPipe, pipesOnFile.get("MyRenamedPipe"),
                "the pipe under the replacement's name is left alone");
        assertEquals("MyPipe", renamed.getPhysicalId(),
                "the resource names the prior pipe the refusal keeps");
        assertFalse(provisioner.hasReplacementUpdate(renamed),
                "the rename cleanup is spent, so no later update deletes the prior pipe");
    }
}
