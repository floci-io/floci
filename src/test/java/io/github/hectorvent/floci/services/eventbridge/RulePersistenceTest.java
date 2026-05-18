package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.eventbridge.model.Rule;
import io.github.hectorvent.floci.services.eventbridge.model.RuleState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RulePersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void rulePersistsAcrossInstances() {
        Path filePath = tempDir.resolve("eventbridge-rules.json");
        String key = "000000000000/rule:us-east-1:default/my-rule";

        Rule rule = new Rule();
        rule.setName("my-rule");
        rule.setArn("arn:aws:events:us-east-1:000000000000:rule/my-rule");
        rule.setAccountId("000000000000");
        rule.setEventBusName("default");
        rule.setScheduleExpression("rate(5 minutes)");
        rule.setState(RuleState.ENABLED);

        var writer = new PersistentStorage<String, Rule>(
                filePath, new TypeReference<Map<String, Rule>>() {});
        writer.put(key, rule);

        var reader = new PersistentStorage<String, Rule>(
                filePath, new TypeReference<Map<String, Rule>>() {});
        reader.load();

        Optional<Rule> loaded = reader.get(key);
        assertTrue(loaded.isPresent(),
                "Rule should survive a fresh PersistentStorage instance + load()");
        assertEquals("my-rule", loaded.get().getName());
        assertEquals("arn:aws:events:us-east-1:000000000000:rule/my-rule", loaded.get().getArn());
        assertEquals("rate(5 minutes)", loaded.get().getScheduleExpression());
        assertEquals(RuleState.ENABLED, loaded.get().getState());
    }

    @Test
    void ignoresUnknownPropertiesOnLoad() throws Exception {
        // A persisted file may contain fields the current Rule class does not
        // recognize: either a derived field written by an older Floci version
        // (e.g. "region") or a brand new field from a future version. Loading
        // must not fail in either case.
        Path filePath = tempDir.resolve("eventbridge-rules.json");
        String key = "000000000000/rule:us-east-1:default/legacy-rule";
        String legacyJson = """
                {
                  "%s" : {
                    "name" : "legacy-rule",
                    "arn" : "arn:aws:events:us-east-1:000000000000:rule/legacy-rule",
                    "accountId" : "000000000000",
                    "eventBusName" : "default",
                    "scheduleExpression" : "rate(5 minutes)",
                    "state" : "ENABLED",
                    "fieldFromFutureVersion" : "whatever"
                  }
                }
                """.formatted(key);
        Files.writeString(filePath, legacyJson);

        var store = new PersistentStorage<String, Rule>(
                filePath, new TypeReference<Map<String, Rule>>() {});
        store.load();

        Optional<Rule> loaded = store.get(key);
        assertTrue(loaded.isPresent(),
                "Rules with unknown extra fields should still load");
        assertEquals("legacy-rule", loaded.get().getName());
    }
}
