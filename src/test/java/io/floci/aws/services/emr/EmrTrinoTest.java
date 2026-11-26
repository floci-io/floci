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
        Map<String, String> catalog = EmrTrino.catalog("hive", Map.of(), ENDPOINT, "us-east-1");

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

        Map<String, String> catalog = EmrTrino.catalog("hive", classification, ENDPOINT, "eu-west-1");

        assertEquals(List.of("connector.name=hive", "hive.metastore=glue", "hive.metastore.glue.region=eu-west-1",
                "hive.metastore.glue.endpoint-url=" + ENDPOINT, "fs.native-s3.enabled=true",
                "fs.hadoop.enabled=true", "s3.endpoint=" + ENDPOINT, "s3.region=eu-west-1",
                "s3.path-style-access=false", "hive.metastore.glue.catalogid=111122223333"),
                List.of(EmrTrino.properties(catalog).split("\n")));
    }

    @Test
    void deltaIsTrinosDeltaLakeConnectorOnTheClustersMetastoreAsTheGuidesTutorialHasIt() {
        // "trino-connector-delta" with "hive.metastore.uri":"thrift://localhost:9083" and no connector of its own.
        Map<String, String> catalog = EmrTrino.catalog("delta", Map.of("hive.metastore.uri",
                "thrift://localhost:9083"), ENDPOINT, "us-east-1");

        assertEquals("delta_lake", catalog.get("connector.name"));
        assertEquals("thrift://localhost:9083", catalog.get("hive.metastore.uri"));
        assertEquals(ENDPOINT, catalog.get("s3.endpoint"));
        // Its INSERT INTO a table in S3, which Trino makes only so; the classification may say otherwise.
        assertEquals("true", catalog.get("delta.enable-non-concurrent-writes"));
        assertEquals("false", EmrTrino.catalog("delta", Map.of("delta.enable-non-concurrent-writes", "false"),
                ENDPOINT, "us-east-1").get("delta.enable-non-concurrent-writes"));
    }

    @Test
    void icebergIsOnFlocisGlueWithItsCatalogTypeGlueElseOnTheClustersMetastore() {
        Map<String, String> glue = EmrTrino.catalog("iceberg", Map.of("connector.name", "iceberg",
                "iceberg.catalog.type", "glue"), ENDPOINT, "us-east-1");
        Map<String, String> metastore = EmrTrino.catalog("iceberg", Map.of("connector.name", "iceberg"), ENDPOINT,
                "us-east-1");
        Map<String, String> rest = EmrTrino.catalog("iceberg", Map.of("iceberg.catalog.type", "rest"), ENDPOINT,
                "us-east-1");

        assertEquals("glue", glue.get("iceberg.catalog.type"));
        assertEquals(ENDPOINT, glue.get("hive.metastore.glue.endpoint-url"));
        assertEquals("us-east-1", glue.get("hive.metastore.glue.region"));
        assertNull(glue.get("hive.metastore.uri"));
        // Trino's Iceberg catalog is a Hive metastore by default.
        assertEquals("thrift://localhost:9083", metastore.get("hive.metastore.uri"));
        assertNull(metastore.get("hive.metastore.glue.endpoint-url"));
        // Another kind of catalog is the classification's alone, on Floci's S3.
        assertNull(rest.get("hive.metastore.uri"));
        assertNull(rest.get("hive.metastore.glue.endpoint-url"));
        assertEquals(ENDPOINT, rest.get("s3.endpoint"));
    }

    @Test
    void aCatalogNotInS3IsItsConnectorAndTheClassificationsPropertiesAlone() {
        assertEquals(Map.of("connector.name", "tpch"), EmrTrino.catalog("tpch", Map.of(), ENDPOINT, "us-east-1"));
        assertEquals(Map.of("connector.name", "raptor_legacy"), EmrTrino.catalog("raptor", Map.of(), ENDPOINT,
                "us-east-1"));
        assertEquals(Map.of("connector.name", "postgresql", "connection-url", "jdbc:postgresql://db:5432/app"),
                EmrTrino.catalog("postgresql", Map.of("connection-url", "jdbc:postgresql://db:5432/app"), ENDPOINT,
                        "us-east-1"));
    }

    @Test
    void theCatalogsAreHiveThenEachTheClusterConfigures() {
        Map<String, Map<String, String>> connectors = new LinkedHashMap<>();
        connectors.put("tpch", Map.of());
        connectors.put("hive", Map.of("hive.metastore", "glue"));

        Map<String, Map<String, String>> catalogs = EmrTrino.catalogs(connectors, ENDPOINT, "us-east-1");

        assertEquals(List.of("hive", "tpch"), List.copyOf(catalogs.keySet()));
        assertEquals("glue", catalogs.get("hive").get("hive.metastore"));
        assertEquals(List.of("hive"), List.copyOf(EmrTrino.catalogs(Map.of(), ENDPOINT, "us-east-1").keySet()));
    }

    @Test
    void theCoordinatorRunsTheQueriesTooOnEmrsPortOrTheOneTrinoConfigNames() {
        assertEquals(List.of("coordinator=true", "node-scheduler.include-coordinator=true",
                "http-server.http.port=8889", "discovery.uri=http://localhost:8889"),
                List.of(EmrTrino.properties(EmrTrino.config(Map.of())).split("\n")));
        // "To change the port, use the presto-config configuration classification to set the http-server.http.port
        // property", trino-config for Trino: its discovery follows.
        Map<String, String> moved = EmrTrino.config(Map.of("http-server.http.port", "8890",
                "query.max-memory", "1GB"));
        assertEquals(List.of("coordinator=true", "node-scheduler.include-coordinator=true",
                "http-server.http.port=8890", "discovery.uri=http://localhost:8890", "query.max-memory=1GB"),
                List.of(EmrTrino.properties(moved).split("\n")));
        assertEquals(8890, EmrTrino.port(moved));
        assertEquals(8889, EmrTrino.port(Map.of("http-server.http.port", "eighty")));
        assertEquals("http://ip-10-0-0-1.ec2.internal:8890", EmrTrino.server("ip-10-0-0-1.ec2.internal", 8890));
        assertEquals(List.of("/usr/bin/trino", "--server", "localhost:8890", "--execute", "SELECT 1"),
                EmrTrino.readyQuery(8890));
    }

    @Test
    void theEntrypointWritesTheConfigurationTheCatalogsFlociGivesAloneAndANodeIdTrinoTakes(@TempDir Path etc)
            throws Exception {
        Files.createDirectories(etc.resolve("catalog"));
        Files.writeString(etc.resolve("catalog/tpcds.properties"), "connector.name=tpcds\n");
        Files.writeString(etc.resolve("node.properties"), "node.environment=docker\n");
        // The image's /etc/trino here, and its Trino left out.
        String entrypoint = EmrTrino.ENTRYPOINT.replace("/etc/trino", etc.toString())
                .replace("exec /usr/lib/trino/bin/run-trino", "true");
        assertTrue(EmrTrino.ENTRYPOINT.endsWith("exec /usr/lib/trino/bin/run-trino"));
        ProcessBuilder builder = new ProcessBuilder("bash", "-c", entrypoint).redirectErrorStream(true);
        String config = EmrTrino.properties(EmrTrino.config(Map.of()));
        builder.environment().put(EmrTrino.CONFIG_VARIABLE, config);
        builder.environment().put(EmrTrino.CATALOG_PREFIX + "hive", "connector.name=hive");
        builder.environment().put(EmrTrino.CATALOG_PREFIX + "iceberg",
                "connector.name=iceberg\niceberg.catalog.type=glue");
        // The primary node's host name, which the container shares.
        builder.environment().put("HOSTNAME", "ip-10-0-0-1.ec2.internal");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);

        assertEquals(config + "\n", Files.readString(etc.resolve("config.properties")));
        try (Stream<Path> catalogs = Files.list(etc.resolve("catalog"))) {
            assertEquals(List.of("hive.properties", "iceberg.properties"),
                    catalogs.map(path -> path.getFileName().toString()).sorted().toList());
        }
        assertEquals("connector.name=hive\n", Files.readString(etc.resolve("catalog/hive.properties")));
        assertEquals("connector.name=iceberg\niceberg.catalog.type=glue\n",
                Files.readString(etc.resolve("catalog/iceberg.properties")));
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
