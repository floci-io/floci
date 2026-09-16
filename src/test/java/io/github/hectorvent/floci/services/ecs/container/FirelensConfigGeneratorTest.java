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
                "taskdefinition:1", 100, "/extra.conf", null, byContainer));

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
                "bridge", false, "c", "arn", "fam:1", 0, null, null, byContainer));

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
                new FirelensConfigGenerator.Context(
                        "bridge", false, "c", "arn", "fam:1", 0, null, null, byContainer)));
    }

    @Test
    void pointsAwsOutputsAtFlociUnlessTheTaskDefinitionSetAnEndpoint() {
        LinkedHashMap<String, String> app = new LinkedHashMap<>();
        app.put("Name", "s3");
        app.put("region", "us-east-1");
        app.put("bucket", "logs");

        LinkedHashMap<String, String> explicit = new LinkedHashMap<>();
        explicit.put("Name", "s3");
        explicit.put("endpoint", "https://real.example.com");

        LinkedHashMap<String, String> http = new LinkedHashMap<>();
        http.put("Name", "s3");
        http.put("endpoint", "http://minio:9000");

        LinkedHashMap<String, String> metrics = new LinkedHashMap<>();
        metrics.put("Name", "stdout");

        LinkedHashMap<String, Map<String, String>> byContainer = new LinkedHashMap<>();
        byContainer.put("app", app);
        byContainer.put("explicit", explicit);
        byContainer.put("http", http);
        byContainer.put("metrics", metrics);

        String config = FirelensConfigGenerator.fluentBitConfig(new FirelensConfigGenerator.Context(
                "bridge", false, "c", "arn", "fam:1", 0, null, "http://host.docker.internal:4566",
                byContainer));

        assertEquals("""
                [INPUT]
                    Name forward
                    unix_path /var/run/fluent.sock
                    Mem_Buf_Limit 25MB

                [INPUT]
                    Name forward
                    Listen 0.0.0.0
                    Port 24224

                [INPUT]
                    Name tcp
                    Tag firelens-healthcheck
                    Listen 127.0.0.1
                    Port 8877

                [OUTPUT]
                    Name null
                    Match firelens-healthcheck

                [OUTPUT]
                    Name s3
                    Match app-firelens*
                    region us-east-1
                    bucket logs
                    Endpoint http://host.docker.internal:4566
                    tls Off

                [OUTPUT]
                    Name s3
                    Match explicit-firelens*
                    endpoint https://real.example.com

                [OUTPUT]
                    Name s3
                    Match http-firelens*
                    endpoint http://minio:9000
                    tls Off

                [OUTPUT]
                    Name stdout
                    Match metrics-firelens*

                """, config);
    }

    /**
     * The upstream C plugins read {@code endpoint} as a bare host name and always dial TLS, so a
     * Floci URL is a guaranteed DNS failure and a bare Floci host is a certificate failure. The
     * Go plugins parse a URL, so those are still pointed at Floci.
     */
    @Test
    void injectsEndpointOnlyIntoPluginsThatReadAUrl() {
        LinkedHashMap<String, String> kinesis = new LinkedHashMap<>();
        kinesis.put("Name", "kinesis_streams");
        kinesis.put("region", "us-east-1");
        kinesis.put("stream", "logs");

        LinkedHashMap<String, String> cwLogs = new LinkedHashMap<>();
        cwLogs.put("Name", "cloudwatch_logs");
        cwLogs.put("region", "us-east-1");
        cwLogs.put("log_group_name", "app");

        LinkedHashMap<String, String> goCloudwatch = new LinkedHashMap<>();
        goCloudwatch.put("Name", "cloudwatch");
        goCloudwatch.put("region", "us-east-1");
        goCloudwatch.put("log_group_name", "app");

        LinkedHashMap<String, Map<String, String>> byContainer = new LinkedHashMap<>();
        byContainer.put("kinesis", kinesis);
        byContainer.put("cwlogs", cwLogs);
        byContainer.put("gocw", goCloudwatch);

        String config = FirelensConfigGenerator.fluentBitConfig(new FirelensConfigGenerator.Context(
                "bridge", false, "c", "arn", "fam:1", 0, null, "http://host.docker.internal:4566",
                byContainer));

        assertTrue(config.contains("""
                [OUTPUT]
                    Name kinesis_streams
                    Match kinesis-firelens*
                    region us-east-1
                    stream logs

                """), config);
        assertTrue(config.contains("""
                [OUTPUT]
                    Name cloudwatch_logs
                    Match cwlogs-firelens*
                    region us-east-1
                    log_group_name app

                """), config);
        assertTrue(config.contains("""
                [OUTPUT]
                    Name cloudwatch
                    Match gocw-firelens*
                    region us-east-1
                    log_group_name app
                    Endpoint http://host.docker.internal:4566
                    tls Off

                """), config);
    }
}
