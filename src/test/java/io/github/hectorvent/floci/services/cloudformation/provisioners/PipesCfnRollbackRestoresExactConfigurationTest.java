package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.pipes.PipesPoller;
import io.github.hectorvent.floci.services.pipes.PipesService;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * CloudFormation puts a resource back to the configuration it carried before a failed update. For
 * {@code AWS::Pipes::Pipe} that includes the properties the pipe did not carry at all: an optional
 * property the failed update added is gone once the rollback is done.
 *
 * <p>The stack update here fails at a later resource, so this pipe's own update committed and
 * {@code rollbackUpdate} is the hook that puts it back. The pipe service is the real one over
 * in-memory storage, so the restore is measured against the semantics the service really applies.
 */
class PipesCfnRollbackRestoresExactConfigurationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String SOURCE_QUEUE_ARN = "arn:aws:sqs:us-east-1:000000000000:example-queue";
    private static final String TARGET_QUEUE_ARN = "arn:aws:sqs:us-east-1:000000000000:example-target-queue";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/example-pipe-role";
    private static final String ENRICHMENT_ARN =
            "arn:aws:lambda:us-east-1:000000000000:function:example-enrichment";

    private PipesService pipes;
    private PipesCfnProvisioner provisioner;
    private CloudFormationTemplateEngine engine;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenReturn(AccountAwareStorageBackend.inMemory(ACCOUNT_ID));
        pipes = spy(new PipesService(storageFactory, new RegionResolver(REGION, ACCOUNT_ID),
                mock(PipesPoller.class)));
        provisioner = new PipesCfnProvisioner(pipes, MAPPER);

        engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static JsonNode props(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception unparseableTemplate) {
            throw new IllegalArgumentException(unparseableTemplate);
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

    /** A pipe with no Description and no Enrichment, tagged {@code Env=dev}. */
    private static String pipeWithoutOptionalProperties() {
        return """
                {"Name": "MyPipe", "Source": "%s", "Target": "%s", "RoleArn": "%s",
                 "Tags": [{"Key": "Env", "Value": "dev"}]}
                """.formatted(SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN, ROLE_ARN);
    }

    /** The same pipe with a Description and an Enrichment, under the tag value given. */
    private static String pipeWithOptionalProperties(String description, String envTagValue) {
        return """
                {"Name": "MyPipe", "Source": "%s", "Target": "%s", "RoleArn": "%s",
                 "Description": "%s", "Enrichment": "%s",
                 "Tags": [{"Key": "Env", "Value": "%s"}]}
                """.formatted(SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN, ROLE_ARN, description,
                ENRICHMENT_ARN, envTagValue);
    }

    /** The pipe's own update commits; the stack fails further on and rolls this one back. */
    private StackResource committedUpdateOf(String createTemplate, String updateTemplate) {
        provision(createTemplate, null);
        return provision(updateTemplate, "MyPipe");
    }

    /**
     * A property the pipe never carried is not part of the configuration it goes back to, so the
     * rollback has to clear what the failed update added.
     */
    @Test
    void aPropertyTheFailedUpdateAddedIsGoneAfterTheRollback() {
        StackResource r = committedUpdateOf(pipeWithoutOptionalProperties(),
                pipeWithOptionalProperties("added by the failed update", "prod"));
        Pipe mutated = pipes.describePipe("MyPipe", REGION);
        assertEquals("added by the failed update", mutated.getDescription(),
                "the update wrote the description before the stack failed");
        assertEquals(ENRICHMENT_ARN, mutated.getEnrichment(),
                "the update wrote the enrichment before the stack failed");

        assertTrue(provisioner.rollbackUpdate(r));

        Pipe restored = pipes.describePipe("MyPipe", REGION);
        assertNull(restored.getDescription(),
                "the pipe carried no description before the update");
        assertNull(restored.getEnrichment(),
                "the pipe carried no enrichment before the update");
        assertEquals(Map.of("Env", "dev"), restored.getTags());
    }

    /** A property the pipe did carry goes back to the value it held, not to nothing. */
    @Test
    void aPropertyTheFailedUpdateChangedGoesBackToItsFormerValue() {
        StackResource r = committedUpdateOf(
                pipeWithOptionalProperties("the original description", "dev"),
                pipeWithOptionalProperties("the description the failed update wrote", "prod"));

        assertTrue(provisioner.rollbackUpdate(r));

        Pipe restored = pipes.describePipe("MyPipe", REGION);
        assertEquals("the original description", restored.getDescription());
        assertEquals(ENRICHMENT_ARN, restored.getEnrichment());
        assertEquals(TARGET_QUEUE_ARN, restored.getTarget());
        assertEquals(ROLE_ARN, restored.getRoleArn());
    }
}
