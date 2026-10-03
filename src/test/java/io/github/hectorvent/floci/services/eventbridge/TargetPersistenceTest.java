package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TargetPersistenceTest {

    @TempDir
    Path directory;

    @Test
    void targetRoleSurvivesPersistentStorageReload() {
        Path file = directory.resolve("targets.json");
        TypeReference<Map<String, List<Target>>> type = new TypeReference<>() {};
        PersistentStorage<String, List<Target>> writer = new PersistentStorage<>(file, type);
        Target target = new Target("workflow", "arn:aws:states:us-east-1:000000000000:stateMachine:example", null, null);
        target.setRoleArn("arn:aws:iam::000000000000:role/path/eventbridge-target");
        target.setRetryPolicy(new Target.RetryPolicy(2, 60));
        target.setDeadLetterConfig(new Target.DeadLetterConfig("arn:aws:sqs:us-east-1:000000000000:dlq"));
        writer.put("rule", List.of(target));

        PersistentStorage<String, List<Target>> reader = new PersistentStorage<>(file, type);
        reader.load();

        Target restored = reader.get("rule").orElseThrow().getFirst();
        assertEquals(target.getRoleArn(), restored.getRoleArn());
        assertEquals(target.getRetryPolicy(), restored.getRetryPolicy());
        assertEquals(target.getDeadLetterConfig(), restored.getDeadLetterConfig());
    }

    @Test
    void legacyStoredTargetsLoadWithoutARole() throws Exception {
        Path file = directory.resolve("targets.json");
        Files.writeString(file, """
                {"rule":[{"id":"queue","arn":"arn:aws:sqs:us-east-1:000000000000:queue"}]}
                """);
        PersistentStorage<String, List<Target>> reader = new PersistentStorage<>(file, new TypeReference<>() {});
        reader.load();

        Target restored = reader.get("rule").orElseThrow().getFirst();
        assertEquals("queue", restored.getId());
        assertNull(restored.getRoleArn());
    }
}
