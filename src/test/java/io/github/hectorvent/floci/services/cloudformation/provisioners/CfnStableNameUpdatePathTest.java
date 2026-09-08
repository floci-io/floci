package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.ecr.EcrService;
import io.github.hectorvent.floci.services.ecr.model.Repository;
import io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.scheduler.SchedulerService;
import io.github.hectorvent.floci.services.scheduler.model.ScheduleGroup;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.firehose.FirehoseService;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription;
import io.github.hectorvent.floci.services.pipes.PipesService;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The second UpdateStack on a resource whose name floci generated.
 *
 * <p>This is the coverage whose absence let a regression through. {@code stablePhysicalName} keeps a
 * generated name steady across updates, which is the fix it exists for, but {@code provision} is
 * also the update path: with a steady name, a provisioner that creates unconditionally now calls a
 * create API with a name that already exists. Firehose answers that with
 * {@code ResourceInUseException} and Pipes with {@code ConflictException}, so the second update
 * fails where it previously (wrongly) created a duplicate under a fresh random name.
 *
 * <p>Every test here provisions twice: once with no prior physical id (create), then again with the
 * id the first pass assigned (update). Asserting on the create call is what makes the defect
 * visible, because asserting only that no exception is thrown would pass against a mock that never
 * rejects duplicates the way the real services do.
 */
class CfnStableNameUpdatePathTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return priorPhysicalId == null
                ? new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack")
                : new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId);
    }

    private StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setResourceType(type);
        r.setLogicalId(logicalId);
        return r;
    }

    @Test
    void firehoseUpdateReconcilesTheExistingStreamInsteadOfRecreatingIt() {
        FirehoseService firehose = mock(FirehoseService.class);
        when(firehose.createDeliveryStream(anyString(), any(), any()))
                .thenReturn("arn:aws:firehose:us-east-1:000000000000:deliverystream/created");
        FirehoseCfnProvisioner provisioner = new FirehoseCfnProvisioner(firehose);

        var props = mapper.createObjectNode();
        props.set("S3DestinationConfiguration",
                mapper.createObjectNode().put("BucketARN", "arn:aws:s3:::bucket"));
        var createTags = props.putArray("Tags");
        createTags.addObject().put("Key", "env").put("Value", "dev");
        createTags.addObject().put("Key", "team").put("Value", "a");

        StackResource created = resource("AWS::KinesisFirehose::DeliveryStream", "Stream");
        provisioner.provision(created, props, ctx(null));
        String generatedName = created.getPhysicalId();

        DeliveryStreamDescription existing = new DeliveryStreamDescription();
        existing.setDeliveryStreamARN("arn:aws:firehose:us-east-1:000000000000:deliverystream/existing");
        existing.setVersionId("1");
        existing.getTags().add(new DeliveryStreamDescription.Tag("env", "dev"));
        existing.getTags().add(new DeliveryStreamDescription.Tag("team", "a"));
        when(firehose.describeDeliveryStream(generatedName)).thenReturn(existing);

        var updateProps = mapper.createObjectNode();
        updateProps.set("S3DestinationConfiguration",
                mapper.createObjectNode().put("BucketARN", "arn:aws:s3:::bucket"));
        updateProps.putArray("Tags").addObject().put("Key", "env").put("Value", "prod");

        StackResource updated = resource("AWS::KinesisFirehose::DeliveryStream", "Stream");
        provisioner.provision(updated, updateProps, ctx(generatedName));

        assertEquals(generatedName, updated.getPhysicalId(), "the generated name must stay stable");
        assertEquals("arn:aws:firehose:us-east-1:000000000000:deliverystream/existing",
                updated.getAttributes().get("Arn"), "Arn must come from the existing stream");
        verify(firehose).updateDestination(eq(generatedName), eq("1"), any(), any());
        // UpdateDestination carries no tags: the dropped key is untagged and the changed one
        // re-tagged, since a tag call alone never removes anything.
        verify(firehose).untagDeliveryStream(generatedName, List.of("team"));
        verify(firehose).tagDeliveryStream(eq(generatedName), argThat(tags ->
                tags.size() == 1 && "env".equals(tags.get(0).getKey())
                        && "prod".equals(tags.get(0).getValue())));
        // The regression: exactly one create, from the first pass. A second one with the
        // now-stable name is what raises ResourceInUseException.
        verify(firehose, times(1)).createDeliveryStream(eq(generatedName), any(), any());
    }

    @Test
    void pipesUpdateReconcilesTheExistingPipeInsteadOfRecreatingIt() {
        PipesService pipes = mock(PipesService.class);
        Pipe createdPipe = new Pipe();
        createdPipe.setArn("arn:aws:pipes:us-east-1:000000000000:pipe/created");
        Pipe updatedPipe = new Pipe();
        updatedPipe.setArn("arn:aws:pipes:us-east-1:000000000000:pipe/existing");
        when(pipes.createPipe(anyString(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyString())).thenReturn(createdPipe);
        when(pipes.updatePipe(anyString(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyString())).thenReturn(updatedPipe);
        PipesCfnProvisioner provisioner = new PipesCfnProvisioner(pipes, mapper);

        var props = mapper.createObjectNode()
                .put("Source", "arn:aws:sqs:us-east-1:000000000000:src")
                .put("Target", "arn:aws:sqs:us-east-1:000000000000:dst")
                .put("RoleArn", "arn:aws:iam::000000000000:role/r");
        props.putArray("Tags").addObject().put("Key", "env").put("Value", "prod");

        StackResource created = resource("AWS::Pipes::Pipe", "Pipe");
        provisioner.provision(created, props, ctx(null));
        String generatedName = created.getPhysicalId();

        Pipe existing = new Pipe();
        existing.setSource("arn:aws:sqs:us-east-1:000000000000:src");
        when(pipes.describePipe(generatedName, "us-east-1")).thenReturn(existing);
        when(pipes.listTags("us-east-1", "arn:aws:pipes:us-east-1:000000000000:pipe/existing"))
                .thenReturn(Map.of("env", "dev", "team", "a"));

        StackResource updated = resource("AWS::Pipes::Pipe", "Pipe");
        provisioner.provision(updated, props, ctx(generatedName));

        assertEquals(generatedName, updated.getPhysicalId(), "the generated name must stay stable");
        assertEquals("arn:aws:pipes:us-east-1:000000000000:pipe/existing",
                updated.getAttributes().get("Arn"), "Arn must come from the existing pipe");
        verify(pipes).updatePipe(eq(generatedName), any(), any(), any(), any(), any(), any(), any(),
                any(), anyString());
        // UpdatePipe carries no Tags: the dropped key is untagged and the changed one re-tagged
        // by ARN.
        verify(pipes).untagResource("us-east-1",
                "arn:aws:pipes:us-east-1:000000000000:pipe/existing", List.of("team"));
        verify(pipes).tagResource("us-east-1",
                "arn:aws:pipes:us-east-1:000000000000:pipe/existing", Map.of("env", "prod"));
        // The regression: exactly one create, from the first pass. A second one with the
        // now-stable name is what raises ConflictException.
        verify(pipes, times(1)).createPipe(eq(generatedName), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void pipesUpdateRefusesAChangedSourceInsteadOfSilentlyKeepingTheOldOne() {
        PipesService pipes = mock(PipesService.class);
        Pipe existing = new Pipe();
        existing.setSource("arn:aws:sqs:us-east-1:000000000000:old");
        existing.setArn("arn:aws:pipes:us-east-1:000000000000:pipe/existing");
        when(pipes.describePipe("my-stack-Pipe-0123456789ab", "us-east-1")).thenReturn(existing);
        when(pipes.updatePipe(anyString(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyString())).thenReturn(existing);
        PipesCfnProvisioner provisioner = new PipesCfnProvisioner(pipes, mapper);

        // Source is createOnly. With the name reused there is no replacement to move to, which is
        // the update CloudFormation refuses for a custom-named resource.
        JsonNode props = mapper.createObjectNode()
                .put("Source", "arn:aws:sqs:us-east-1:000000000000:new")
                .put("Target", "arn:aws:sqs:us-east-1:000000000000:dst")
                .put("RoleArn", "arn:aws:iam::000000000000:role/r");

        StackResource r = resource("AWS::Pipes::Pipe", "Pipe");
        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props, ctx("my-stack-Pipe-0123456789ab")));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(pipes, never()).updatePipe(anyString(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyString());
        verify(pipes, never()).createPipe(anyString(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyString());
    }

    @Test
    void schedulerUpdateReconcilesTheGroupItAlreadyOwnsInsteadOfRecreatingIt() {
        SchedulerService scheduler = mock(SchedulerService.class);
        when(scheduler.createScheduleGroup(anyString(), any(), anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return new ScheduleGroup(name, "arn:aws:scheduler:us-east-1:000000000000:schedule-group/" + name,
                    "ACTIVE", Instant.now(), Instant.now());
        });
        when(scheduler.getScheduleGroup(anyString(), anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return new ScheduleGroup(name, "arn:aws:scheduler:us-east-1:000000000000:schedule-group/" + name,
                    "ACTIVE", Instant.now(), Instant.now());
        });
        SchedulerScheduleGroupCfnProvisioner provisioner = new SchedulerScheduleGroupCfnProvisioner(scheduler);
        JsonNode props = mapper.createObjectNode();

        StackResource created = resource("AWS::Scheduler::ScheduleGroup", "Group");
        provisioner.provision(created, props, ctx(null));
        String generatedName = created.getPhysicalId();

        StackResource updated = resource("AWS::Scheduler::ScheduleGroup", "Group");
        provisioner.provision(updated, props, ctx(generatedName));

        assertEquals(generatedName, updated.getPhysicalId(), "the generated name must stay stable");
        // createScheduleGroup answers ConflictException for a name that exists. Exactly one create.
        verify(scheduler, times(1)).createScheduleGroup(eq(generatedName), any(), anyString());
    }

    @Test
    void sqsUpdateReconcilesTheQueueItAlreadyOwnsInsteadOfRecreatingIt() {
        SqsService sqs = mock(SqsService.class);
        when(sqs.createQueue(anyString(), any(), anyString()))
                .thenAnswer(inv -> new Queue(inv.getArgument(0), "http://q/" + inv.getArgument(0)));
        SqsCfnProvisioner provisioner = new SqsCfnProvisioner(sqs);
        JsonNode props = mapper.createObjectNode().put("VisibilityTimeout", 45);

        StackResource created = resource("AWS::SQS::Queue", "Queue");
        provisioner.provision(created, props, ctx(null));
        String queueUrl = created.getPhysicalId();
        String generatedName = created.getAttributes().get("QueueName");

        // The physical id is the URL, so the prior name travels in the attributes the engine
        // hands back to provision on an update.
        StackResource updated = resource("AWS::SQS::Queue", "Queue");
        updated.setAttributes(new HashMap<>(created.getAttributes()));
        provisioner.provision(updated, props, ctx(queueUrl));

        assertEquals(queueUrl, updated.getPhysicalId(), "the queue URL must stay stable");
        assertEquals(generatedName, updated.getAttributes().get("QueueName"));
        // createQueue on an existing name answers QueueAlreadyExists once an attribute differs, so
        // the update goes through SetQueueAttributes. Exactly one create.
        verify(sqs, times(1)).createQueue(eq(generatedName), any(), anyString());
        verify(sqs).setQueueAttributes(eq(queueUrl), any(), anyString());
    }

    @Test
    void microvmImageUpdateGoesThroughUpdateImageInsteadOfRecreatingIt() {
        LambdaMicrovmsService microvms = mock(LambdaMicrovmsService.class);
        when(microvms.createImage(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenAnswer(inv -> microvmImage(inv.getArgument(2)));
        when(microvms.updateImage(anyString(), anyString(), any(), any(), any(), any()))
                .thenAnswer(inv -> microvmImage(inv.getArgument(1)));
        LambdaMicrovmsCfnProvisioner provisioner = new LambdaMicrovmsCfnProvisioner(microvms);
        JsonNode props = mapper.createObjectNode()
                .put("BaseImageArn", "arn:aws:lambda:us-east-1::base-image:nodejs")
                .put("BuildRoleArn", "arn:aws:iam::000000000000:role/build");

        StackResource created = resource("AWS::Lambda::MicrovmImage", "Image");
        provisioner.provision(created, props, ctx(null));
        String generatedName = created.getPhysicalId();

        StackResource updated = resource("AWS::Lambda::MicrovmImage", "Image");
        provisioner.provision(updated, props, ctx(generatedName));

        assertEquals(generatedName, updated.getPhysicalId(), "the generated name must stay stable");
        // createImage on an existing image silently mints a new version rather than rejecting, so
        // here the assertion is that the update went to the schema's update handler instead.
        verify(microvms, times(1)).createImage(anyString(), anyString(), eq(generatedName), any(), any(), any(), any());
        verify(microvms).updateImage(eq("us-east-1"), eq(generatedName), any(), any(), any(), any());
    }

    private static LambdaMicrovmsService.MicrovmImage microvmImage(String name) {
        LambdaMicrovmsService.MicrovmImage image = new LambdaMicrovmsService.MicrovmImage();
        image.name = name;
        image.imageArn = "arn:aws:lambda:us-east-1:000000000000:microvm-image:" + name;
        image.latestActiveImageVersion = "1.0";
        return image;
    }

    @Test
    void s3UpdateDoesNotRecreateTheBucketItAlreadyOwns() {
        S3Service s3 = mock(S3Service.class);
        S3CfnProvisioner provisioner = new S3CfnProvisioner(s3);
        JsonNode props = mapper.createObjectNode();

        StackResource created = resource("AWS::S3::Bucket", "Bucket");
        provisioner.provision(created, props, ctx(null));
        String generatedName = created.getPhysicalId();

        StackResource updated = resource("AWS::S3::Bucket", "Bucket");
        provisioner.provision(updated, props, ctx(generatedName));

        assertEquals(generatedName, updated.getPhysicalId(), "the generated name must stay stable");
        // The regression: outside us-east-1, a second CreateBucket on a bucket this account owns
        // answers BucketAlreadyOwnedByYou. Exactly one create, from the first pass.
        verify(s3, times(1)).createBucket(eq(generatedName), anyString());
    }

    @Test
    void ecrUpdateReconcilesTheRepositoryItAlreadyOwnsInsteadOfRecreatingIt() {
        EcrService ecr = mock(EcrService.class);
        Repository createdRepo = new Repository();
        createdRepo.setRepositoryArn("arn:aws:ecr:us-east-1:000000000000:repository/created");
        createdRepo.setRepositoryUri("000000000000.dkr.ecr.us-east-1.amazonaws.com/created");
        Repository existingRepo = new Repository();
        existingRepo.setRepositoryArn("arn:aws:ecr:us-east-1:000000000000:repository/existing");
        existingRepo.setRepositoryUri("000000000000.dkr.ecr.us-east-1.amazonaws.com/existing");
        when(ecr.createRepository(anyString(), isNull(), any(), isNull(), isNull(), isNull(), any(),
                anyString())).thenReturn(createdRepo);
        when(ecr.putImageTagMutability(anyString(), isNull(), eq("IMMUTABLE"), anyString()))
                .thenReturn(existingRepo);
        EcrCfnProvisioner provisioner = new EcrCfnProvisioner(ecr);

        var createProps = mapper.createObjectNode().put("ImageTagMutability", "MUTABLE");
        createProps.set("LifecyclePolicy", mapper.createObjectNode().put("LifecyclePolicyText", "{}"));
        createProps.put("RepositoryPolicyText", "{}");
        StackResource created = resource("AWS::ECR::Repository", "Repo");
        provisioner.provision(created, createProps, ctx(null));
        String generatedName = created.getPhysicalId();
        when(ecr.listTagsForResource(generatedName, null, "us-east-1"))
                .thenReturn(Map.of("env", "dev", "team", "a"));

        // The update changes mutability and one tag, drops the other tag and both policies.
        var updateProps = mapper.createObjectNode().put("ImageTagMutability", "IMMUTABLE");
        updateProps.putArray("Tags").addObject().put("Key", "env").put("Value", "prod");
        StackResource updated = resource("AWS::ECR::Repository", "Repo");
        provisioner.provision(updated, updateProps, ctx(generatedName));

        assertEquals(generatedName, updated.getPhysicalId(), "the generated name must stay stable");
        assertEquals("arn:aws:ecr:us-east-1:000000000000:repository/existing",
                updated.getAttributes().get("Arn"), "Arn must come from the existing repository");
        // Exactly one create, from the first pass: the update reconciles rather than colliding.
        verify(ecr, times(1)).createRepository(anyString(), isNull(), any(), isNull(), isNull(),
                isNull(), any(), anyString());
        verify(ecr).putImageTagMutability(generatedName, null, "IMMUTABLE", "us-east-1");
        // TagResource alone leaves unspecified tags in place, so the dropped key is untagged.
        verify(ecr).untagResource(generatedName, null, List.of("team"), "us-east-1");
        verify(ecr).tagResource(generatedName, null, Map.of("env", "prod"), "us-east-1");
        verify(ecr).deleteLifecyclePolicy(generatedName, null, "us-east-1");
        verify(ecr).deleteRepositoryPolicy(generatedName, null, "us-east-1");
        verify(ecr, never()).describeRepositories(anyList(), isNull(), anyString());
    }

    @Test
    void ecrUpdateThatDropsImageTagMutabilityRestoresTheMutableDefault() {
        EcrService ecr = mock(EcrService.class);
        Repository createdRepo = new Repository();
        createdRepo.setRepositoryArn("arn:aws:ecr:us-east-1:000000000000:repository/created");
        createdRepo.setRepositoryUri("000000000000.dkr.ecr.us-east-1.amazonaws.com/created");
        Repository existingRepo = new Repository();
        existingRepo.setRepositoryArn("arn:aws:ecr:us-east-1:000000000000:repository/existing");
        existingRepo.setRepositoryUri("000000000000.dkr.ecr.us-east-1.amazonaws.com/existing");
        when(ecr.createRepository(anyString(), isNull(), any(), isNull(), isNull(), isNull(), any(),
                anyString())).thenReturn(createdRepo);
        when(ecr.putImageTagMutability(anyString(), isNull(), anyString(), anyString()))
                .thenReturn(existingRepo);
        EcrCfnProvisioner provisioner = new EcrCfnProvisioner(ecr);

        StackResource created = resource("AWS::ECR::Repository", "Repo");
        provisioner.provision(created, mapper.createObjectNode().put("ImageTagMutability", "IMMUTABLE"),
                ctx(null));
        String generatedName = created.getPhysicalId();

        // The property is gone from the template. Omitting it means the API default, MUTABLE,
        // not the IMMUTABLE the repository was created with.
        StackResource updated = resource("AWS::ECR::Repository", "Repo");
        provisioner.provision(updated, mapper.createObjectNode(), ctx(generatedName));

        assertEquals("arn:aws:ecr:us-east-1:000000000000:repository/existing",
                updated.getAttributes().get("Arn"));
        verify(ecr).putImageTagMutability(generatedName, null, "MUTABLE", "us-east-1");
        verify(ecr, never()).describeRepositories(anyList(), isNull(), anyString());
    }

    @Test
    void ecrReplacingUpdateCreatesTheNewlyNamedRepository() {
        EcrService ecr = mock(EcrService.class);
        Repository createdRepo = new Repository();
        createdRepo.setRepositoryArn("arn:aws:ecr:us-east-1:000000000000:repository/renamed");
        createdRepo.setRepositoryUri("000000000000.dkr.ecr.us-east-1.amazonaws.com/renamed");
        when(ecr.createRepository(eq("renamed"), isNull(), any(), isNull(), isNull(), isNull(), any(),
                anyString())).thenReturn(createdRepo);
        EcrCfnProvisioner provisioner = new EcrCfnProvisioner(ecr);

        // The template now names the repository, so this update derives a different name from
        // the prior physical id. That is a replacement and must create; reconciling the prior
        // repository under the new name would mutate something that does not exist under it.
        // This is why the guard asks reusesPriorEntity and not isUpdate.
        StackResource r = resource("AWS::ECR::Repository", "Repo");
        provisioner.provision(r, mapper.createObjectNode().put("RepositoryName", "renamed"),
                ctx("my-stack-repo-0123456789ab"));

        assertEquals("renamed", r.getPhysicalId());
        verify(ecr).createRepository(eq("renamed"), isNull(), any(), isNull(), isNull(), isNull(),
                any(), anyString());
        verify(ecr, never()).listTagsForResource(anyString(), isNull(), anyString());
        verify(ecr, never()).describeRepositories(anyList(), isNull(), anyString());
    }

    @Test
    void ecrCreateFailsOnANameAnotherRepositoryAlreadyHas() {
        EcrService ecr = mock(EcrService.class);
        when(ecr.createRepository(anyString(), isNull(), any(), isNull(), isNull(), isNull(), any(),
                anyString()))
                .thenThrow(new AwsException("RepositoryAlreadyExistsException",
                        "The repository already exists", 400));
        EcrCfnProvisioner provisioner = new EcrCfnProvisioner(ecr);

        // No prior physical id: this stack does not own the colliding repository. Adopting it
        // would put someone else's repository under this stack's delete; CloudFormation fails.
        StackResource r = resource("AWS::ECR::Repository", "Repo");
        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(r,
                mapper.createObjectNode().put("RepositoryName", "taken"), ctx(null)));

        assertEquals("RepositoryAlreadyExistsException", failure.getErrorCode());
        verify(ecr, never()).describeRepositories(anyList(), isNull(), anyString());
        verify(ecr, never()).tagResource(anyString(), isNull(), any(), anyString());
        verify(ecr, never()).putImageTagMutability(anyString(), isNull(), any(), anyString());
    }

    @Test
    void aReplacingUpdateStillCreates() {
        PipesService pipes = mock(PipesService.class);
        Pipe pipe = new Pipe();
        pipe.setArn("arn:aws:pipes:us-east-1:000000000000:pipe/new");
        when(pipes.createPipe(anyString(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyString())).thenReturn(pipe);
        PipesCfnProvisioner provisioner = new PipesCfnProvisioner(pipes, mapper);

        // The template now names the pipe, so this update derives a different name from the prior
        // physical id. That is a replacement, and it must create rather than update a pipe that
        // does not exist under the new name. This is why the guard asks reusesPriorEntity and not
        // isUpdate.
        JsonNode props = mapper.createObjectNode()
                .put("Name", "explicitly-named")
                .put("Source", "arn:aws:sqs:us-east-1:000000000000:src")
                .put("Target", "arn:aws:sqs:us-east-1:000000000000:dst")
                .put("RoleArn", "arn:aws:iam::000000000000:role/r");

        StackResource r = resource("AWS::Pipes::Pipe", "Pipe");
        provisioner.provision(r, props, ctx("my-stack-Pipe-0123456789ab"));

        assertEquals("explicitly-named", r.getPhysicalId());
        verify(pipes).createPipe(eq("explicitly-named"), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyString());
        verify(pipes, never()).updatePipe(anyString(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyString());
    }
}
