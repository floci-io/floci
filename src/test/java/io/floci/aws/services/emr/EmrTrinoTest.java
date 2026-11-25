package io.floci.aws.services.emr;

import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// It names Trino's port, localhost:8889, and opens no socket.
@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class EmrTrinoTest {

    private static final String ENDPOINT = "http://floci:4566";
    private static final String SERVER = "http://ip-10-0-0-1.ec2.internal:8889";

    @Test
    void theImageIsOfTheTrinoTheReleaseShips() {
        assertEquals("trinodb/trino:446", EmrTrino.image("trinodb/trino:%s", "emr-7.5.0"));
        assertEquals("trinodb/trino:479", EmrTrino.image("trinodb/trino:%s", "emr-7.14.0"));
        // The AWS runtime for Apache Spark ships none, and a release Floci does not list has no version.
        assertNull(EmrTrino.image("trinodb/trino:%s", "emr-spark-8.0.0"));
        assertNull(EmrTrino.image("trinodb/trino:%s", "emr-9.9.9"));
        // An image with no %s serves every release.
        assertEquals("registry.internal/trino:446", EmrTrino.image("registry.internal/trino:446", "emr-9.9.9"));
    }

    @Test
    void theHiveCatalogIsTheClustersMetastoreOnFlocisS3() {
        Map<String, String> catalog = EmrTrino.hiveCatalog(Map.of(), ENDPOINT, "us-east-1");

        assertEquals(List.of("connector.name=hive", "hive.metastore.uri=thrift://localhost:9083",
                "fs.native-s3.enabled=true", "fs.hadoop.enabled=true", "s3.endpoint=" + ENDPOINT,
                "s3.region=us-east-1", "s3.path-style-access=true"),
                List.of(EmrTrino.properties(catalog).split("\n")));
    }

    @Test
    void theHiveCatalogIsFlocisGlueWhenTheClassificationSaysSoItsOwnPropertiesWinning() {
        Map<String, String> classification = new LinkedHashMap<>();
        classification.put("hive.metastore", "glue");
        classification.put("hive.metastore.glue.catalogid", "111122223333");
        classification.put("s3.path-style-access", "false");

        Map<String, String> catalog = EmrTrino.hiveCatalog(classification, ENDPOINT, "eu-west-1");

        assertEquals(List.of("connector.name=hive", "hive.metastore=glue", "hive.metastore.glue.region=eu-west-1",
                "hive.metastore.glue.endpoint-url=" + ENDPOINT, "fs.native-s3.enabled=true",
                "fs.hadoop.enabled=true", "s3.endpoint=" + ENDPOINT, "s3.region=eu-west-1",
                "s3.path-style-access=false", "hive.metastore.glue.catalogid=111122223333"),
                List.of(EmrTrino.properties(catalog).split("\n")));
    }

    @Test
    void theCoordinatorRunsTheQueriesTooOnEmrsPort() {
        assertEquals(List.of("coordinator=true", "node-scheduler.include-coordinator=true",
                "http-server.http.port=8889", "discovery.uri=http://localhost:8889"),
                List.of(EmrTrino.CONFIG.split("\n")));
    }

    @Test
    void theEntrypointWritesTheConfigurationTheHiveCatalogAloneAndANodeIdTrinoTakes(@TempDir Path etc)
            throws Exception {
        Files.createDirectories(etc.resolve("catalog"));
        Files.writeString(etc.resolve("catalog/tpch.properties"), "connector.name=tpch\n");
        Files.writeString(etc.resolve("node.properties"), "node.environment=docker\n");
        // The image's /etc/trino here, and its Trino left out.
        String entrypoint = EmrTrino.ENTRYPOINT.replace("/etc/trino", etc.toString())
                .replace("exec /usr/lib/trino/bin/run-trino", "true");
        assertTrue(EmrTrino.ENTRYPOINT.endsWith("exec /usr/lib/trino/bin/run-trino"));
        ProcessBuilder builder = new ProcessBuilder("bash", "-c", entrypoint).redirectErrorStream(true);
        builder.environment().put(EmrTrino.CONFIG_VARIABLE, EmrTrino.CONFIG);
        builder.environment().put(EmrTrino.CATALOG_VARIABLE, "connector.name=hive");
        // The primary node's host name, which the container shares.
        builder.environment().put("HOSTNAME", "ip-10-0-0-1.ec2.internal");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);

        assertEquals(EmrTrino.CONFIG + "\n", Files.readString(etc.resolve("config.properties")));
        try (Stream<Path> catalogs = Files.list(etc.resolve("catalog"))) {
            assertEquals(List.of("hive.properties"), catalogs.map(path -> path.getFileName().toString()).toList());
        }
        assertEquals("connector.name=hive\n", Files.readString(etc.resolve("catalog/hive.properties")));
        // Trino takes letters, digits, hyphens and underscores in a node's id, and the image's run-trino names none
        // once node.properties does.
        assertEquals("node.environment=docker\nnode.id=ip-10-0-0-1-ec2-internal\n",
                Files.readString(etc.resolve("node.properties")));
    }

    @Test
    void trinoCliReachesTheClustersCoordinatorUnlessTheStepNamesAServer(@TempDir Path folder) throws Exception {
        // A java that prints its arguments, one to a line, in place of the image's Java 17.
        Path java = folder.resolve("java");
        Files.writeString(java, "#!/bin/sh\nprintf '%s\\n' \"$@\"\n");
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path command = folder.resolve("trino-cli");
        assertTrue(EmrTrino.COMMAND.contains("/usr/lib/jvm/jre-17/bin/java"));
        Files.writeString(command, EmrTrino.COMMAND.replace("/usr/lib/jvm/jre-17/bin/java", java.toString()));

        assertEquals(List.of("-jar", EmrTrino.CLI, "--server", SERVER, "--execute", "SHOW CATALOGS"),
                run(command, SERVER, "--execute", "SHOW CATALOGS"));
        assertEquals(List.of("-jar", EmrTrino.CLI, "--server", "localhost:8889", "--version"),
                run(command, null, "--version"));
        assertEquals(List.of("-jar", EmrTrino.CLI, "--server=http://other:8080", "--version"),
                run(command, SERVER, "--server=http://other:8080", "--version"));
        assertEquals(List.of("-jar", EmrTrino.CLI, "--execute", "SELECT 1", "--server", "http://other:8080"),
                run(command, SERVER, "--execute", "SELECT 1", "--server", "http://other:8080"));
    }

    // What command passes its java, run with server as its FLOCI_TRINO_SERVER, unless that is null.
    private static List<String> run(Path command, String server, String... arguments)
            throws IOException, InterruptedException {
        List<String> line = new ArrayList<>(List.of("bash", command.toString()));
        line.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(line).redirectErrorStream(true);
        builder.environment().remove(EmrTrino.SERVER_VARIABLE);
        if (server != null) {
            builder.environment().put(EmrTrino.SERVER_VARIABLE, server);
        }
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        return List.of(output.strip().split("\n"));
    }
}
