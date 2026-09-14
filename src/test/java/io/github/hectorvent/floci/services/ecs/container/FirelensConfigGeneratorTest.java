package io.github.hectorvent.floci.services.ecs.container;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirelensConfigGeneratorTest {

    @Test
    void fluentBitConfigMatchesEcsAgentShape() {
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        options.put("Name", "cloudwatch");
        options.put("region", "us-east-1");
        options.put("log_group_name", "app");
        options.put("include-pattern", "*failure*");
        options.put("exclude-pattern", "*success*");
        options.put("log-driver-buffer-limit", "123");

        LinkedHashMap<String, Map<String, String>> byContainer = new LinkedHashMap<>();
        byContainer.put("app", options);

        String config = FirelensConfigGenerator.fluentBitConfig(new FirelensConfigGenerator.Context(
                "awsvpc", true, "mycluster",
                "arn:aws:ecs:us-east-1:000000000000:task/mycluster/abc",
                "taskdefinition:1", 100, "/extra.conf", byContainer));

        assertEquals("""
                [INPUT]
                    Name forward
                    unix_path /var/run/fluent.sock
                    Mem_Buf_Limit 50MB

                [INPUT]
                    Name forward
                    Listen 0.0.0.0
                    Port 24224

                [INPUT]
                    Name tcp
                    Tag firelens-healthcheck
                    Listen 127.0.0.1
                    Port 8877

                [FILTER]
                    Name grep
                    Match app-firelens*
                    Regex log *failure*

                [FILTER]
                    Name grep
                    Match app-firelens*
                    Exclude log *success*

                [FILTER]
                    Name record_modifier
                    Match *
                    Record ecs_cluster mycluster
                    Record ecs_task_arn arn:aws:ecs:us-east-1:000000000000:task/mycluster/abc
                    Record ecs_task_definition taskdefinition:1

                @INCLUDE /extra.conf

                [OUTPUT]
                    Name null
                    Match firelens-healthcheck

                [OUTPUT]
                    Name cloudwatch
                    Match app-firelens*
                    region us-east-1
                    log_group_name app

                """, config);
    }

    @Test
    void skipsGeneratedOutputWhenOnlyCustomFileIsUsed() {
        LinkedHashMap<String, Map<String, String>> byContainer = new LinkedHashMap<>();
        byContainer.put("app", Map.of());

        String config = FirelensConfigGenerator.fluentBitConfig(new FirelensConfigGenerator.Context(
                "bridge", false, "c", "arn", "fam:1", 0, null, byContainer));

        assertTrue(config.contains("Listen 0.0.0.0"));
        assertTrue(config.contains("Mem_Buf_Limit 25MB"));
        assertTrue(!config.contains("record_modifier"));
        assertTrue(!config.contains("Match app-firelens*"));
    }

    @Test
    void rejectsPluginOptionsWithoutName() {
        LinkedHashMap<String, Map<String, String>> byContainer = new LinkedHashMap<>();
        byContainer.put("app", Map.of("region", "us-east-1"));

        assertThrows(IllegalArgumentException.class, () -> FirelensConfigGenerator.fluentBitConfig(
                new FirelensConfigGenerator.Context("bridge", false, "c", "arn", "fam:1", 0, null, byContainer)));
    }
}
