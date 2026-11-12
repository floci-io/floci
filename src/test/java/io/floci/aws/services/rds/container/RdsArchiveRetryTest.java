package io.floci.aws.services.rds.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerExecStubs;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.services.rds.model.DatabaseEngine;
import io.floci.aws.testing.DockerArchiveUploadFixture;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RdsArchiveRetryTest {
    private static final String CONTAINER = "rds-retry-id";

    @TempDir
    Path temporary;

    private DockerClient docker;
    private ContainerLifecycleManager lifecycle;
    private RdsContainerManager manager;

    @BeforeEach
    void setUp() {
        docker = mock(DockerClient.class);
        lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(lifecycle.create(any())).thenReturn(CONTAINER);
        when(lifecycle.startCreated(eq(CONTAINER), any())).thenReturn(new ContainerInfo(CONTAINER,
                Map.of(3306, new EndpointInfo("localhost", 3306))));
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(spec.build()).thenReturn(new ContainerSpec("test-image"));
        when(builder.newContainer(anyString())).thenReturn(spec);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().rds().dockerNetwork()).thenReturn(Optional.empty());
        when(config.storage().hostPersistentPath()).thenReturn(temporary.toString());
        when(config.storage().mode()).thenReturn("persistent");
        ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, "", "");
        manager = new RdsContainerManager(builder, lifecycle, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), config, new RegionResolver("us-east-1", "000000000000"),
                mock(ServiceConfigAccess.class), 0);
    }

    @AfterEach
    void tearDown() {
        manager.clear();
        manager.shutdownAutoPause();
    }

    @ParameterizedTest
    @CsvSource({"MYSQL,0", "MYSQL,73", "MYSQL,8192", "MARIADB,0", "MARIADB,73", "MARIADB,8192"})
    void masterGrantRetriesConsumedUploadBeforeStarting(DatabaseEngine engine, int consumedBytes) throws Exception {
        DockerArchiveUploadFixture uploads = failingUploads(consumedBytes, 1);

        RdsContainerHandle handle = manager.start("db", "volume", engine, "test-image", "we'ird\\", "password", "db");

        assertEquals(CONTAINER, handle.getContainerId());
        assertEquals(2, uploads.commands.size());
        assertEquals(1, uploads.completed.size());
        assertNotSame(uploads.streams.get(0), uploads.streams.get(1));
        verify(uploads.commands.get(1)).withRemotePath("/docker-entrypoint-initdb.d");
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new ByteArrayInputStream(uploads.completed.getFirst()))) {
            TarArchiveEntry entry = tar.getNextEntry();
            assertEquals("floci-master-grants.sql", entry.getName());
            assertEquals(0644, entry.getMode());
            assertArrayEquals("GRANT ALL PRIVILEGES ON *.* TO 'we\\'ird\\\\'@'%' WITH GRANT OPTION;"
                    .getBytes(StandardCharsets.UTF_8), tar.readAllBytes());
            assertNull(tar.getNextEntry());
        }
        InOrder order = inOrder(lifecycle, uploads.commands.get(1));
        order.verify(lifecycle).create(any());
        order.verify(uploads.commands.get(1)).exec();
        order.verify(lifecycle).startCreated(eq(CONTAINER), any());
        verify(docker, never()).execCreateCmd(anyString());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 73, 8192})
    void postgresRestoreRetriesBothEntriesBeforeRunningSql(int consumedBytes) throws Exception {
        DockerArchiveUploadFixture uploads = failingUploads(consumedBytes, 1);
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, "", "");
        String dump = "-- snapshot é\n".repeat(2048);

        manager.restorePostgresSnapshot(CONTAINER, "admin", dump);

        assertEquals(2, uploads.commands.size());
        assertEquals(1, uploads.completed.size());
        assertNotSame(uploads.streams.get(0), uploads.streams.get(1));
        verify(uploads.commands.get(1)).withRemotePath("/tmp");
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new ByteArrayInputStream(uploads.completed.getFirst()))) {
            assertTarEntry(tar, "dump.sql", dump);
            assertTarEntry(tar, "restore.sh", RdsContainerManager.postgresRestoreScript());
            assertNull(tar.getNextEntry());
        }
        assertEquals(List.of(List.of("sh", "/tmp/restore.sh", "admin"), List.of("rm", "-f", "/tmp/dump.sql")), commands);
        InOrder order = inOrder(uploads.commands.get(1), docker);
        order.verify(uploads.commands.get(1)).exec();
        order.verify(docker).execCreateCmd(CONTAINER);
    }

    @ParameterizedTest
    @CsvSource({"MYSQL,false", "MARIADB,false", "MYSQL,true", "MARIADB,true"})
    void failedGrantUploadCleansUpAndReleasesOwnership(DatabaseEngine engine, boolean permanent) {
        DockerArchiveUploadFixture uploads = failingUploads(73, Integer.MAX_VALUE);
        if (permanent) {
            uploads.failure = new NotFoundException("container missing");
        }

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> manager.start("db", "volume", engine, "test-image", "admin", "password", "db"));

        assertSame(uploads.failure, failure.getCause());
        assertEquals(permanent ? 1 : 6, uploads.commands.size());
        verify(lifecycle, never()).startCreated(anyString(), any());
        verify(lifecycle).stopAndRemoveStrict(CONTAINER, null);
        verify(docker, never()).execCreateCmd(anyString());

        uploads.failures = 0;
        assertEquals(CONTAINER,
                manager.start("db", "volume", engine, "test-image", "admin", "password", "db").getContainerId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedRestoreUploadNeverRunsSql(boolean permanent) {
        DockerArchiveUploadFixture uploads = failingUploads(73, Integer.MAX_VALUE);
        if (permanent) {
            uploads.failure = new NotFoundException("container missing");
        }

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> manager.restorePostgresSnapshot(CONTAINER, "admin", "SELECT 1;"));

        assertSame(uploads.failure, failure.getCause());
        assertEquals(permanent ? 1 : 6, uploads.commands.size());
        verify(docker, never()).execCreateCmd(anyString());
    }

    @Test
    void sqlFailureDoesNotRetryUploadOrSql() {
        DockerArchiveUploadFixture uploads = failingUploads(0, 0);
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, 1, "", "invalid dump");

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> manager.restorePostgresSnapshot(CONTAINER, "admin", "SELECT 1;"));

        assertTrue(failure.getMessage().contains("psql restore failed with exit code 1: invalid dump"));
        assertEquals(1, uploads.commands.size());
        assertEquals(List.of(List.of("sh", "/tmp/restore.sh", "admin"), List.of("rm", "-f", "/tmp/dump.sql")), commands);
    }

    @Test
    void sqlTransportFailureDoesNotRetryUploadOrSql() {
        DockerArchiveUploadFixture uploads = failingUploads(0, 0);
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, "", "");
        RuntimeException transportFailure = new RuntimeException(new IOException("Connection reset"));
        when(docker.execStartCmd(anyString())).thenThrow(transportFailure);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> manager.restorePostgresSnapshot(CONTAINER, "admin", "SELECT 1;"));

        assertSame(transportFailure, failure);
        assertEquals(1, uploads.commands.size());
        assertEquals(List.of(List.of("sh", "/tmp/restore.sh", "admin"), List.of("rm", "-f", "/tmp/dump.sql")), commands);
    }

    @Test
    void mysqlDumpWaitsForTheServerToAcceptTheMasterUser() {
        DockerArchiveUploadFixture uploads = failingUploads(0, 0);
        List<List<String>> envs = new ArrayList<>();
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER,
                List.of(2L, 2L, 0L), "-- dump", "", envs);

        String dump = manager.createMysqlSnapshot(DatabaseEngine.MYSQL, CONTAINER, "admin", "secret");

        assertEquals("-- dump", dump);
        assertEquals(1, uploads.commands.size());
        assertEquals(3, commands.size());
        assertEquals(List.of("sh", "/tmp/floci-mysql-dump.sh", "mysql", "mysqldump", "admin"),
                commands.getFirst().subList(0, 5));
        // The password reaches the exec as environment, never as a word of the command Docker records.
        assertEquals(List.of(List.of("MYSQL_PWD=secret"), List.of("MYSQL_PWD=secret"), List.of("MYSQL_PWD=secret")),
                envs);
        assertTrue(commands.stream().flatMap(List::stream).noneMatch(word -> word.contains("secret")));
    }

    @Test
    void mysqlRestorePassesThePasswordAsEnvironmentAndUploadsTheDumpUnreadableByTheServer() throws IOException {
        DockerArchiveUploadFixture uploads = failingUploads(0, 0);
        List<List<String>> envs = new ArrayList<>();
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, List.of(0L), "", "",
                envs);

        manager.restoreMysqlSnapshot(DatabaseEngine.MARIADB, CONTAINER, "admin", "secret", "-- dump");

        assertEquals(List.of(List.of("sh", "/tmp/restore.sh", "mariadb", "admin"), List.of("rm", "-f", "/tmp/dump.sql")), commands);
        assertEquals(List.of(List.of("MYSQL_PWD=secret")), envs);
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new ByteArrayInputStream(uploads.completed.getFirst()))) {
            TarArchiveEntry dump = tar.getNextEntry();
            assertEquals("dump.sql", dump.getName());
            // mysqld runs as an unprivileged user and must not be able to LOAD_FILE the dump.
            assertEquals(0600, dump.getMode());
        }
    }

    @Test
    void mysqlDumpScriptKeepsASchemaNameWithANewlineAsOneArgument(@TempDir Path dir) throws Exception {
        // Stand-ins for the client and the dump tool. The client answers the schema query the way
        // mysql -B does without -r: a newline in a name arrives as the two characters \n.
        Path client = dir.resolve("client");
        Files.writeString(client, """
                #!/bin/sh
                for last; do :; done
                case "$last" in
                  *information_schema.schemata*) printf 'a\\\\nb\\nplain\\n' ;;
                  *CONCAT*QUOTE*) printf '\\047u\\\\nser\\047@\\047%%\\047\\n' ;;
                  *QUOTE*) printf '\\047r\\\\nole\\047\\n' ;;
                  *SHOW*GRANTS*) printf 'GRANT USAGE ON *.* TO %s\\n' "${last#SHOW GRANTS FOR }" ;;
                  *SHOW*) printf '%s\\n' "$last" ;;
                esac
                """);
        Path dump = dir.resolve("dump");
        Files.writeString(dump, "#!/bin/sh\nfor a; do case \"$a\" in -*) ;; *) printf '<%s>' \"$a\" ;; esac; done\n");
        Path script = dir.resolve("dump.sh");
        Files.writeString(script, RdsContainerManager.mysqlDumpScript());
        for (Path p : List.of(client, dump)) {
            assertTrue(p.toFile().setExecutable(true));
        }

        Process process = new ProcessBuilder("sh", script.toString(), client.toString(), dump.toString(), "admin",
                "", "", "FALSE").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.waitFor(), output);
        assertTrue(output.startsWith("<a\nb><plain>"), output);
        // Roles, accounts and grantees are decoded too: each statement carries the real newline.
        assertTrue(output.contains("CREATE ROLE IF NOT EXISTS 'r\nole';"), output);
        assertTrue(output.contains("SHOW CREATE USER 'u\nser'@'%'"), output);
        // Each GRANT ends with one ';', after the whole name, not after the line the newline split.
        assertTrue(output.contains("GRANT USAGE ON *.* TO 'r\nole';\n"), output);
        assertTrue(output.contains("GRANT USAGE ON *.* TO 'u\nser'@'%';\n"), output);
    }

    @Test
    void restoreRemovesTheDumpEvenWhenItsExecDiesBeforeTheScriptCanClean() {
        failingUploads(0, 0);
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, "", "");
        RuntimeException killed = new RuntimeException(new IOException("exec killed"));
        when(docker.execStartCmd(anyString())).thenThrow(killed);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> manager.restoreMysqlSnapshot(DatabaseEngine.MYSQL, CONTAINER, "admin", "secret", "-- dump"));

        assertSame(killed, failure);
        assertEquals(List.of("rm", "-f", "/tmp/dump.sql"), commands.getLast());
    }

    /** Runs a restore script on a stub client or psql; returns the exit code, and whether the dump is left. */
    private record Restore(int exit, boolean dumpLeft, String mode) {
    }

    private Restore runRestoreScript(Path dir, String script, String mode, boolean takesClient) throws Exception {
        Path dump = dir.resolve("dump.sql");
        Files.writeString(dump, "SELECT 1;");
        Path stub = dir.resolve("stub");
        // down: the server does not answer; fail: it answers but the replay fails; up: all well.
        Files.writeString(stub, """
                #!/bin/sh
                case "$*" in
                  *-f*) [ "$MODE" = down ] && exit 2; [ "$MODE" = fail ] && exit 1; exit 0 ;;
                  *-e*) [ "$MODE" = down ] && exit 1; exit 0 ;;
                esac
                [ "$MODE" = down ] && exit 2
                [ "$MODE" = fail ] && exit 1
                exit 0
                """);
        Path psql = dir.resolve("psql");
        Files.copy(stub, psql);
        for (Path p : List.of(stub, psql)) {
            assertTrue(p.toFile().setExecutable(true));
        }
        Path file = dir.resolve("restore.sh");
        Files.writeString(file, script.replace("/tmp/dump", dir + "/dump"));
        List<String> command = new ArrayList<>(List.of("sh", file.toString()));
        if (takesClient) {
            command.add(stub.toString());
        }
        command.add("admin");
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        pb.environment().put("MODE", mode);
        pb.environment().put("PATH", dir + ":" + System.getenv("PATH"));
        Process process = pb.start();
        process.getInputStream().readAllBytes();
        return new Restore(process.waitFor(), Files.exists(dump), mode);
    }

    @ParameterizedTest
    @CsvSource({"up,0,false", "fail,1,false", "down,2,true"})
    void mysqlRestoreScriptRemovesTheDumpUnlessTheServerIsStillDown(String mode, int exit, boolean left,
            @TempDir Path dir) throws Exception {
        Restore result = runRestoreScript(dir, RdsContainerManager.mysqlRestoreScript(), mode, true);

        assertEquals(exit, result.exit());
        assertEquals(left, result.dumpLeft());
    }

    @ParameterizedTest
    @CsvSource({"up,0,false", "fail,1,false", "down,2,true"})
    void postgresRestoreScriptRemovesTheDumpUnlessTheServerIsStillDownAndKeepsItPrivate(String mode, int exit,
            boolean left, @TempDir Path dir) throws Exception {
        Restore result = runRestoreScript(dir, RdsContainerManager.postgresRestoreScript(), mode, false);

        assertEquals(exit, result.exit());
        assertEquals(left, result.dumpLeft());
        if (left) {
            assertEquals(java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(dir.resolve("dump.sql")));
        }
    }

    @Test
    void mysqlDumpFailureIsNotRetried() {
        failingUploads(0, 0);
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, 1, "",
                "Access denied");

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> manager.createMysqlSnapshot(DatabaseEngine.MARIADB, CONTAINER, "admin", "secret"));

        assertTrue(failure.getMessage().contains("mariadb-dump failed with exit code 1: Access denied"));
        assertEquals(1, commands.size());
    }

    private static void assertTarEntry(TarArchiveInputStream tar, String name, String content) throws IOException {
        TarArchiveEntry entry = tar.getNextEntry();
        assertEquals(name, entry.getName());
        assertEquals(name.endsWith(".sql") ? 0600 : 0755, entry.getMode());
        assertArrayEquals(content.getBytes(StandardCharsets.UTF_8), tar.readAllBytes());
    }

    private DockerArchiveUploadFixture failingUploads(int consumedBytes, int failures) {
        DockerArchiveUploadFixture uploads = new DockerArchiveUploadFixture(docker, CONTAINER);
        uploads.failures = failures;
        uploads.consumedBytes = consumedBytes;
        uploads.failure = new RuntimeException(new IOException("Connection reset"));
        return uploads;
    }
}
