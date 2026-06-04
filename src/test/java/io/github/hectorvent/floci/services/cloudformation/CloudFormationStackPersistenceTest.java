package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.cloudformation.model.ChangeSet;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackEvent;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the CloudFormation stack model graph survives a restart by round-tripping it through a
 * fresh {@link PersistentStorage} instance (criteria #10, #11). Mirrors {@code RulePersistenceTest}.
 */
class CloudFormationStackPersistenceTest {

    @TempDir
    Path tempDir;

    private static final String KEY = "000000000000/my-stack:us-east-1";

    @Test
    void stackGraphPersistsAcrossInstances() {
        Path filePath = tempDir.resolve("cloudformation-stacks.json");

        Stack stack = new Stack();
        stack.setStackId("arn:aws:cloudformation:us-east-1:000000000000:stack/my-stack/abc123");
        stack.setStackName("my-stack");
        stack.setRegion("us-east-1");
        stack.setStatus("CREATE_COMPLETE");
        stack.setTemplateBody("{\"Resources\":{}}");
        stack.getParameters().put("Env", "prod");
        stack.getOutputs().put("BucketArn", "arn:aws:s3:::my-bucket");
        stack.getExports().put("MyExport", "exported-value");
        stack.getTags().put("team", "platform");

        StackResource resource = new StackResource();
        resource.setLogicalId("MyBucket");
        resource.setPhysicalId("my-bucket");
        resource.setResourceType("AWS::S3::Bucket");
        resource.setStatus("CREATE_COMPLETE");
        resource.getAttributes().put("Arn", "arn:aws:s3:::my-bucket");
        stack.getResources().put("MyBucket", resource);

        StackEvent event = new StackEvent();
        event.setStackName("my-stack");
        event.setLogicalResourceId("MyBucket");
        event.setResourceStatus("CREATE_COMPLETE");
        stack.getEvents().add(event);

        ChangeSet changeSet = new ChangeSet();
        changeSet.setChangeSetName("initial-create");
        changeSet.setStackName("my-stack");
        changeSet.setStatus("CREATE_COMPLETE");
        stack.getChangeSets().put("initial-create", changeSet);

        var writer = new PersistentStorage<String, Stack>(
                filePath, new TypeReference<Map<String, Stack>>() {});
        writer.put(KEY, stack);

        // Fresh instance + load() simulates a Floci restart reading the same file
        var reader = new PersistentStorage<String, Stack>(
                filePath, new TypeReference<Map<String, Stack>>() {});
        reader.load();

        Optional<Stack> loadedOpt = reader.get(KEY);
        assertTrue(loadedOpt.isPresent(), "Stack should survive a fresh PersistentStorage + load()");
        Stack loaded = loadedOpt.get();

        assertEquals("my-stack", loaded.getStackName());
        assertEquals("us-east-1", loaded.getRegion());
        assertEquals("CREATE_COMPLETE", loaded.getStatus());
        assertEquals("arn:aws:cloudformation:us-east-1:000000000000:stack/my-stack/abc123", loaded.getStackId());
        assertNotNull(loaded.getCreationTime(), "Instant fields must round-trip");
        assertEquals("prod", loaded.getParameters().get("Env"));
        assertEquals("arn:aws:s3:::my-bucket", loaded.getOutputs().get("BucketArn"));
        assertEquals("exported-value", loaded.getExports().get("MyExport"));
        assertEquals("platform", loaded.getTags().get("team"));

        StackResource loadedResource = loaded.getResources().get("MyBucket");
        assertNotNull(loadedResource, "Nested stack resources must round-trip");
        assertEquals("my-bucket", loadedResource.getPhysicalId());
        assertEquals("AWS::S3::Bucket", loadedResource.getResourceType());
        assertEquals("CREATE_COMPLETE", loadedResource.getStatus());
        assertEquals("arn:aws:s3:::my-bucket", loadedResource.getAttributes().get("Arn"));

        assertEquals(1, loaded.getEvents().size());
        assertEquals("CREATE_COMPLETE", loaded.getEvents().get(0).getResourceStatus());
        assertTrue(loaded.getChangeSets().containsKey("initial-create"));
        assertEquals("CREATE_COMPLETE", loaded.getChangeSets().get("initial-create").getStatus());
    }

    @Test
    void ignoresUnknownPropertiesOnLoad() throws Exception {
        // A persisted file may contain fields a newer/older Floci version does not recognize.
        // Loading must not fail (forward/backward compatibility).
        Path filePath = tempDir.resolve("cloudformation-stacks.json");
        String legacyJson = """
                {
                  "%s" : {
                    "stackName" : "legacy-stack",
                    "region" : "us-east-1",
                    "status" : "CREATE_COMPLETE",
                    "fieldFromFutureVersion" : "whatever"
                  }
                }
                """.formatted(KEY);
        Files.writeString(filePath, legacyJson);

        var store = new PersistentStorage<String, Stack>(
                filePath, new TypeReference<Map<String, Stack>>() {});
        store.load();

        Optional<Stack> loaded = store.get(KEY);
        assertTrue(loaded.isPresent(), "Stacks with unknown extra fields should still load");
        assertEquals("legacy-stack", loaded.get().getStackName());
        assertEquals("CREATE_COMPLETE", loaded.get().getStatus());
    }
}
