package io.floci.aws.services.emr;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.RetryingTarCopier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Apache Pig for the image of a cluster that installs it, when {@code floci.services.emr.pig.enabled} is on: no AWS
 * image carries Pig, so Floci downloads Apache's binary distribution from the first of its URLs that gives it with its
 * SHA-256, and writes what Pig runs from as the archive of {@link #HOME}, EMR's folder for it. EMR 7.x
 * releases ship Pig 0.17.0, whose Apache build is "compatible with Hadoop 2.5+ releases"; Floci's default is 0.18.0,
 * whose {@code pig-0.18.0.tar.gz} "is compatible with Hadoop 3, Spark 3, Hive 3 and HBase 2", the Hadoop the images
 * hold. Pig runs on the image's Tez, as EMR's runs on EMR's: the Tez 0.10.3 its distribution bundles needs protobuf 3,
 * which it bundles too, and the image's Tez 0.10.2 protobuf 2.5, so the two would meet on one class path. Both of its own
 * stay out, and its {@code pig-env.sh} puts the image's Tez on Pig's class path ({@link #ENVIRONMENT}).
 */
@ApplicationScoped
public class PigDistribution {

    /** The folder Pig goes in, under its own name. */
    static final String FOLDER = "/usr/lib";
    static final String NAME = "pig";
    static final String HOME = FOLDER + "/" + NAME;
    /** The folder of the {@code pig} a step runs, as EMR's {@code pig-script} does the one it finds. */
    static final String COMMAND_FOLDER = "/usr/bin";
    /**
     * That {@code pig}, which runs Pig's launcher by the launcher's own path: the launcher takes Pig's folder from the
     * path it was run by, so a link would lead it to {@code /usr} instead.
     */
    static final String COMMAND = "#!/bin/bash\nexec " + HOME + "/bin/" + NAME + " \"$@\"\n";
    // What Pig runs from: its launcher, its configuration, its jars and its licence. The docs, the sources, the tests,
    // the jars of Pig on Spark, which EMR does not run, the Hadoop it bundles for a machine without one, and its Tez and
    // the protobuf that Tez needs stay out, as does a pig-env.sh, Floci writing its own.
    private static final Set<String> KEPT_FOLDERS = Set.of("bin", "conf", "lib");
    private static final List<String> LEFT_OUT = List.of("lib/spark/", "lib/hadoop2-runtime/", "lib/hadoop3-runtime/",
            "lib/h3/tez-", "lib/protobuf-java-", "conf/pig-env.sh");
    /**
     * Pig's {@code conf/pig-env.sh}, which its launcher reads before it builds its class path: the image's Tez, with
     * its protobuf 2.5, then the class path a cluster's {@code pig-env} sets, whose exports come first.
     */
    static final String ENVIRONMENT = "export PIG_CLASSPATH=\"/usr/lib/tez/*:/usr/lib/tez/lib/*"
            + "${PIG_CLASSPATH:+:$PIG_CLASSPATH}\"\n";
    private static final String ENVIRONMENT_FILE = NAME + "/conf/pig-env.sh";
    private static final Set<String> KEPT_FILES = Set.of("LICENSE.txt", "NOTICE.txt");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    // A download that sends nothing for this long has stalled, and the next URL is tried.
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);
    private static final int BUFFER_BYTES = 64 * 1024;

    private final List<URI> urls;
    private final String sha256;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final Path downloads;

    @Inject
    public PigDistribution(EmulatorConfig config) {
        this(config.services().emr().pig().urls(), config.services().emr().pig().sha256(), CONNECT_TIMEOUT,
                READ_TIMEOUT, Path.of(System.getProperty("java.io.tmpdir")));
    }

    PigDistribution(List<String> urls, String sha256, Duration connectTimeout, Duration readTimeout,
                    Path downloads) {
        this.urls = urls.stream().map(String::trim).filter(url -> !url.isEmpty()).map(URI::create).toList();
        this.sha256 = sha256.trim();
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.downloads = downloads;
    }

    /** What the image's name says it holds: the distribution, by its checksum. */
    String identity() {
        return "pig " + sha256.toLowerCase();
    }

    /**
     * Downloads the distribution to a temporary file, which the caller deletes, from the first URL that gives it whole
     * with its SHA-256: one that cannot be reached, answers otherwise, stalls for {@link #READ_TIMEOUT} or gives another
     * checksum passes to the next, so nothing goes into an image before it has been checked.
     */
    Path download() throws IOException {
        List<String> failures = new ArrayList<>();
        for (URI url : urls) {
            Path file = Files.createTempFile(downloads, "floci-pig-", ".tar.gz");
            try {
                fetch(url, file);
                return file;
            } catch (IOException e) {
                Files.deleteIfExists(file);
                failures.add(url + ": " + e.getMessage());
            }
        }
        throw new IOException("Could not download Apache Pig: " + (failures.isEmpty() ? "no URL is set"
                : String.join("; ", failures)));
    }

    private void fetch(URI url, Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        HttpURLConnection connection = (HttpURLConnection) url.toURL().openConnection();
        connection.setConnectTimeout((int) connectTimeout.toMillis());
        connection.setReadTimeout((int) readTimeout.toMillis());
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("answered " + status);
            }
            try (InputStream in = new DigestInputStream(connection.getInputStream(), digest);
                 OutputStream out = Files.newOutputStream(file)) {
                in.transferTo(out);
            }
        } finally {
            connection.disconnect();
        }
        String found = HexFormat.of().formatHex(digest.digest());
        if (!found.equalsIgnoreCase(sha256)) {
            throw new IOException("has the SHA-256 " + found + ", not the " + sha256
                    + " floci.services.emr.pig.sha256 names");
        }
    }

    /**
     * Writes the folders and files Pig runs from, of the gzipped archive {@code download}, to {@code out} as an archive
     * of {@link #FOLDER}, under {@link #NAME} in place of the distribution's own folder, owned by root, and
     * {@link #ENVIRONMENT} as its {@code pig-env.sh}.
     */
    static void write(Path download, OutputStream out) throws IOException {
        try (InputStream raw = Files.newInputStream(download);
             GZIPInputStream gzip = new GZIPInputStream(raw, BUFFER_BYTES);
             TarArchiveInputStream in = new TarArchiveInputStream(gzip);
             TarArchiveOutputStream tar = RetryingTarCopier.newTarStream(out)) {
            TarArchiveEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String path = kept(entry.getName());
                if (path == null || entry.isLink() && kept(entry.getLinkName()) == null) {
                    continue;
                }
                entry.setName(path);
                if (entry.isLink()) {
                    entry.setLinkName(kept(entry.getLinkName()));
                }
                entry.setIds(0, 0);
                entry.setNames("root", "root");
                tar.putArchiveEntry(entry);
                in.transferTo(tar);
                tar.closeArchiveEntry();
            }
            TarArchiveEntry environment = new TarArchiveEntry(ENVIRONMENT_FILE);
            byte[] lines = ENVIRONMENT.getBytes(StandardCharsets.UTF_8);
            environment.setSize(lines.length);
            environment.setMode(0644);
            environment.setIds(0, 0);
            environment.setNames("root", "root");
            tar.putArchiveEntry(environment);
            tar.write(lines);
            tar.closeArchiveEntry();
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // A path of the distribution, its own folder first, under NAME, or null for one Pig does not run from, or that
    // leaves the folder.
    static String kept(String path) {
        if (path == null || path.startsWith("/")) {
            return null;
        }
        int slash = path.indexOf('/');
        if (slash < 0) {
            return null;
        }
        String rest = path.substring(slash + 1);
        for (String segment : rest.split("/")) {
            if (segment.equals("..")) {
                return null;
            }
        }
        if (rest.isEmpty()) {
            return NAME + "/";
        }
        int folderEnd = rest.indexOf('/');
        boolean kept = folderEnd < 0
                ? KEPT_FILES.contains(rest) || rest.endsWith(".jar")
                : KEPT_FOLDERS.contains(rest.substring(0, folderEnd)) && LEFT_OUT.stream().noneMatch(rest::startsWith);
        return kept ? NAME + "/" + rest : null;
    }
}
