package io.floci.aws.services.emr;

import com.sun.net.httpserver.HttpServer;
import io.floci.aws.testing.ParallelLocks;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class PigDistributionTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration STALL = Duration.ofMillis(300);
    private static final byte[] OTHER = "another file".getBytes(StandardCharsets.UTF_8);

    // Releases the stalled download's server thread once the test is done with it.
    private final CountDownLatch released = new CountDownLatch(1);
    // A thread for each request, so the stalled one holds up no other.
    private final ExecutorService handlers = Executors.newCachedThreadPool();
    @TempDir
    Path downloads;
    private HttpServer server;
    private byte[] distribution;

    @BeforeEach
    void serve() throws IOException {
        distribution = distribution();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(handlers);
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/pig.tar.gz", exchange -> {
            exchange.sendResponseHeaders(200, distribution.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(distribution);
            }
        });
        server.createContext("/other.tar.gz", exchange -> {
            exchange.sendResponseHeaders(200, OTHER.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(OTHER);
            }
        });
        // Sends its headers and its first bytes, then nothing until the test is done.
        server.createContext("/stalled.tar.gz", exchange -> {
            exchange.sendResponseHeaders(200, distribution.length);
            OutputStream body = exchange.getResponseBody();
            body.write(distribution, 0, 16);
            body.flush();
            try {
                released.await(1, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        released.countDown();
        server.stop(0);
        handlers.shutdownNow();
    }

    @Test
    void theFirstUrlThatGivesTheDistributionWithItsChecksumIsTheOneDownloaded() throws Exception {
        Path downloaded = pig(TIMEOUT, "/missing", "/other.tar.gz", "/pig.tar.gz").download();

        try {
            assertArrayEquals(distribution, Files.readAllBytes(downloaded));
        } finally {
            Files.deleteIfExists(downloaded);
        }
    }

    @Test
    void aDownloadThatStallsPassesToTheNextUrl() throws Exception {
        Path downloaded = pig(STALL, "/stalled.tar.gz", "/pig.tar.gz").download();

        try {
            assertArrayEquals(distribution, Files.readAllBytes(downloaded));
        } finally {
            Files.deleteIfExists(downloaded);
        }
    }

    @Test
    void noUrlGivingItFailsWithWhatEachGaveAndLeavesNoFile() throws Exception {
        PigDistribution pig = pig(STALL, "/missing", "/other.tar.gz", "/stalled.tar.gz");

        IOException failure = assertThrows(IOException.class, pig::download);

        assertEquals("Could not download Apache Pig: " + url("/missing") + ": answered 404; " + url("/other.tar.gz")
                + ": has the SHA-256 " + sha256(OTHER) + ", not the " + sha256(distribution)
                + " floci.services.emr.pig.sha256 names; " + url("/stalled.tar.gz") + ": Read timed out",
                failure.getMessage());
        assertEquals(List.of(), leftovers());
    }

    @Test
    void noUrlAtAllFails() {
        IOException failure = assertThrows(IOException.class,
                () -> new PigDistribution(List.of(" "), "0".repeat(64), TIMEOUT, TIMEOUT, downloads).download());

        assertEquals("Could not download Apache Pig: no URL is set", failure.getMessage());
    }

    @Test
    void whatPigRunsFromGoesUnderItsFolderOwnedByRootAndTheRestStaysOut(@TempDir Path folder) throws Exception {
        Path archive = Files.write(folder.resolve("pig.tar.gz"), distribution);

        // Its Tez and that Tez's protobuf stay out, the image's taking their place through Floci's pig-env.sh.
        assertEquals(List.of("pig/ dir", "pig/bin/ dir", "pig/bin/pig 755", "pig/conf/pig.properties 644",
                "pig/lib/ dir", "pig/lib/jython.jar 644", "pig/lib/h3/orc-core.jar 644",
                "pig/pig-0.18.0-core-h3.jar 644", "pig/LICENSE.txt 644", "pig/conf/pig-env.sh 644"),
                entries(written(archive)));
    }

    @Test
    void pigsEnvironmentPutsTheImagesTezAheadOfTheClassPathAClusterSets(@TempDir Path folder) throws Exception {
        Path archive = Files.write(folder.resolve("pig.tar.gz"), distribution);

        assertEquals("export PIG_CLASSPATH=\"/usr/lib/tez/*:/usr/lib/tez/lib/*${PIG_CLASSPATH:+:$PIG_CLASSPATH}\"\n",
                content(written(archive), "pig/conf/pig-env.sh"));
    }

    @Test
    void theImagesNameHoldsTheDistributionsChecksum() {
        assertEquals("pig " + "a".repeat(64),
                new PigDistribution(List.of("https://downloads.example/pig.tar.gz"), "A".repeat(64), TIMEOUT, TIMEOUT,
                        downloads).identity());
    }

    @Test
    void pigOnThePathRunsItsLauncherByItsOwnPath() {
        assertEquals("#!/bin/bash\nexec /usr/lib/pig/bin/pig \"$@\"\n", PigDistribution.COMMAND);
    }

    @Test
    void aPathOutsideTheDistributionsFolderStaysOut() {
        assertEquals("pig/lib/a.jar", PigDistribution.kept("pig-0.18.0/lib/a.jar"));
        for (String path : new String[]{"/etc/passwd", "pig-0.18.0/lib/../../etc/passwd", "README.txt",
                "pig-0.18.0/lib/spark/spark-core.jar", "pig-0.18.0/lib/hadoop3-runtime/hadoop-common.jar",
                "pig-0.18.0/lib/h3/tez-api-0.10.3.jar", "pig-0.18.0/lib/protobuf-java-3.21.1.jar",
                "pig-0.18.0/docs/index.html", "pig-0.18.0/build.xml", null}) {
            assertNull(PigDistribution.kept(path), path);
        }
    }

    private PigDistribution pig(Duration readTimeout, String... paths) throws Exception {
        List<String> urls = new ArrayList<>();
        for (String path : paths) {
            urls.add(url(path));
        }
        return new PigDistribution(urls, sha256(distribution), TIMEOUT, readTimeout, downloads);
    }

    private String url(String path) {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + path;
    }

    // The files downloads left in their folder.
    private List<Path> leftovers() throws IOException {
        try (Stream<Path> files = Files.list(downloads)) {
            return files.toList();
        }
    }

    // A distribution as Apache's is laid out: its own folder, what Pig runs from, and what it does not.
    private static byte[] distribution() throws IOException {
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(gzipped))) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            directory(tar, "pig-0.18.0/");
            directory(tar, "pig-0.18.0/bin/");
            file(tar, "pig-0.18.0/bin/pig", 0755);
            file(tar, "pig-0.18.0/conf/pig.properties", 0644);
            directory(tar, "pig-0.18.0/docs/");
            file(tar, "pig-0.18.0/docs/index.html", 0644);
            directory(tar, "pig-0.18.0/lib/");
            file(tar, "pig-0.18.0/lib/jython.jar", 0644);
            file(tar, "pig-0.18.0/lib/h3/tez-api-0.10.3.jar", 0644);
            file(tar, "pig-0.18.0/lib/h3/orc-core.jar", 0644);
            file(tar, "pig-0.18.0/lib/protobuf-java-3.21.1.jar", 0644);
            file(tar, "pig-0.18.0/conf/pig-env.sh", 0644);
            file(tar, "pig-0.18.0/lib/spark/spark-core.jar", 0644);
            file(tar, "pig-0.18.0/lib/hadoop3-runtime/hadoop-common.jar", 0644);
            file(tar, "pig-0.18.0/src/org/apache/pig/Main.java", 0644);
            file(tar, "pig-0.18.0/pig-0.18.0-core-h3.jar", 0644);
            file(tar, "pig-0.18.0/LICENSE.txt", 0644);
            file(tar, "pig-0.18.0/build.xml", 0644);
        }
        return gzipped.toByteArray();
    }

    private static void directory(TarArchiveOutputStream tar, String name) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setIds(1000, 1000);
        tar.putArchiveEntry(entry);
        tar.closeArchiveEntry();
    }

    private static void file(TarArchiveOutputStream tar, String name, int mode) throws IOException {
        byte[] content = name.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(content.length);
        entry.setMode(mode);
        entry.setIds(1000, 1000);
        entry.setNames("builder", "builder");
        tar.putArchiveEntry(entry);
        tar.write(content);
        tar.closeArchiveEntry();
    }

    private static byte[] written(Path archive) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PigDistribution.write(archive, out);
        return out.toByteArray();
    }

    // Each entry: its name, then "dir" or its mode in octal; every one is root's, and none is a link.
    private static List<String> entries(byte[] archive) throws IOException {
        List<String> entries = new ArrayList<>();
        try (TarArchiveInputStream in = new TarArchiveInputStream(new ByteArrayInputStream(archive))) {
            TarArchiveEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                assertEquals("root:root:0:0", entry.getUserName() + ":" + entry.getGroupName() + ":"
                        + entry.getLongUserId() + ":" + entry.getLongGroupId(), entry.getName());
                assertFalse(entry.isSymbolicLink(), entry.getName());
                entries.add(entry.getName() + " " + (entry.isDirectory() ? "dir"
                        : Integer.toOctalString(entry.getMode() & 0777)));
            }
        }
        return entries;
    }

    private static String content(byte[] archive, String name) throws IOException {
        try (TarArchiveInputStream in = new TarArchiveInputStream(new ByteArrayInputStream(archive))) {
            TarArchiveEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().equals(name)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
