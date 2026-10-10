package io.github.hectorvent.floci.services.redshift.container;

import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class RedshiftContainerManager {

    private static final Logger LOG = Logger.getLogger(RedshiftContainerManager.class);
    private static final String DEFAULT_DATABASE = "dev";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final Map<String, RedshiftContainerHandle> containers = new ConcurrentHashMap<>();

    @Inject
    public RedshiftContainerManager(ContainerBuilder containerBuilder,
                                    ContainerLifecycleManager lifecycleManager,
                                    ContainerLogStreamer logStreamer,
                                    ContainerDetector containerDetector,
                                    EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.config = config;
    }

    public RedshiftContainerHandle start(String accountId, String clusterIdentifier, String masterUsername, String masterPassword) {
        return start(accountId, clusterIdentifier, masterUsername, masterPassword, DEFAULT_DATABASE);
    }

    /** Starts the container with {@code databaseName} as its one database, instead of the default {@code dev}. */
    public RedshiftContainerHandle start(String accountId, String clusterIdentifier, String masterUsername,
                                         String masterPassword, String databaseName) {
        String image = config.services().redshift().imageVersion();
        String containerName = containerName(accountId, clusterIdentifier);
        // Only the current name: no volume backs this container, so removing a legacy-named survivor
        // would destroy cluster data that adoptOrStart does not look for.
        lifecycleManager.removeIfExists(containerName);

        List<String> envVars = List.of(
                "POSTGRES_USER=" + masterUsername,
                "POSTGRES_PASSWORD=" + masterPassword,
                "POSTGRES_DB=" + databaseName
        );

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withEnv(envVars)
                .withCmd(List.of("postgres", "-c", "allow_system_table_mods=on"))
                .withDockerNetwork(config.services().redshift().dockerNetwork())
                .withLogRotation();

        int enginePort = 5432; // Default postgres port
        if (!containerDetector.isRunningInContainer()) {
            specBuilder.withDynamicPort(enginePort);
        } else {
            specBuilder.withExposedPort(enginePort);
        }

        ContainerSpec spec = specBuilder.build();
        ContainerInfo info = lifecycleManager.createAndStart(spec);
        return initializeAndRegisterHandle(info, accountId, clusterIdentifier, masterUsername, containerName, enginePort,
                databaseName);
    }

    /**
     * Starts a cluster's container the same way {@link #start}, except that a container
     * already running under this cluster's name (e.g. Floci's own process restarted but
     * Docker did not) is adopted instead of destroyed and recreated. The container has no
     * persistent volume, so a blind recreate would silently discard the cluster's data;
     * adopting preserves it whenever the physical container survived. If no such container
     * exists (first start, or the container itself was removed), this falls back to
     * {@link #start}, and in that case the previous data is unrecoverable, matching this
     * project's decision not to back Redshift containers with a Docker volume.
     */
    public RedshiftContainerHandle adoptOrStart(String accountId, String clusterIdentifier, String masterUsername, String masterPassword) {
        return adoptOrStart(accountId, clusterIdentifier, masterUsername, masterPassword, DEFAULT_DATABASE);
    }

    /** As {@link #adoptOrStart(String, String, String, String)} for a container whose database is not {@code dev}. */
    public RedshiftContainerHandle adoptOrStart(String accountId, String clusterIdentifier, String masterUsername,
                                                String masterPassword, String databaseName) {
        String containerName = containerName(accountId, clusterIdentifier);
        Optional<Container> existing = lifecycleManager.findByName(containerName);
        if (existing.isEmpty()) {
            LOG.warnv("No surviving container for cluster {0}; starting a fresh empty one: the previous"
                    + " contents are not recoverable (no Docker volume backs Redshift containers)", clusterIdentifier);
            return start(accountId, clusterIdentifier, masterUsername, masterPassword, databaseName);
        }

        LOG.infov("Adopting existing container {0} for cluster {1} to avoid discarding its data",
                containerName, clusterIdentifier);
        int enginePort = 5432;
        ContainerInfo info = lifecycleManager.adopt(existing.get().getId(), List.of(enginePort));
        return initializeAndRegisterHandle(info, accountId, clusterIdentifier, masterUsername, containerName, enginePort,
                databaseName);
    }

    private RedshiftContainerHandle initializeAndRegisterHandle(
            ContainerInfo info,
            String accountId,
            String clusterIdentifier,
            String masterUsername,
            String containerName,
            int enginePort,
            String databaseName) {
        EndpointInfo endpoint = info.getEndpoint(enginePort);
        RedshiftContainerHandle handle = new RedshiftContainerHandle(
                info.containerId(), clusterIdentifier, endpoint.host(), endpoint.port());

        try {
            Closeable stream = logStreamer.attach(
                    info.containerId(),
                    "/floci/redshift",
                    clusterIdentifier,
                    config.defaultRegion(),
                    "redshift:" + clusterIdentifier);
            handle.setLogStream(stream);
        } catch (Exception e) {
            LOG.warnv("Failed to stream logs for {0}", containerName);
        }

        waitForReady(containerName, info.containerId(), masterUsername, databaseName);
        bootstrapCatalog(info.containerId(), masterUsername, databaseName);

        containers.put(containerKey(accountId, clusterIdentifier), handle);
        return handle;
    }

    public void stop(String accountId, String clusterIdentifier) {
        containers.remove(containerKey(accountId, clusterIdentifier));
        lifecycleManager.removeIfExists(containerName(accountId, clusterIdentifier));
    }

    public Optional<RedshiftContainerHandle> getContainer(String accountId, String clusterIdentifier) {
        return Optional.ofNullable(containers.get(containerKey(accountId, clusterIdentifier)));
    }

    /** Distinguishes clusters that share an identifier across accounts, e.g. for the in-memory map key and Docker container name. */
    private static String containerKey(String accountId, String clusterIdentifier) {
        return accountId + "/" + clusterIdentifier;
    }

    private String containerName(String accountId, String clusterIdentifier) {
        return ContainerStorageHelper.dockerName(config, "redshift-" + accountId + "-" + clusterIdentifier);
    }

    private RedshiftContainerHandle requireHandle(String accountId, String clusterIdentifier) {
        RedshiftContainerHandle handle = containers.get(containerKey(accountId, clusterIdentifier));
        if (handle == null) {
            throw new AwsException("ClusterNotFound", "Cluster container for " + clusterIdentifier + " not found", 404);
        }
        return handle;
    }

    private static String userOrDefault(String username) {
        return (username != null && !username.isBlank()) ? username : "postgres";
    }

    private static String databaseOrDefault(String dbname) {
        return (dbname != null && !dbname.isBlank()) ? dbname : DEFAULT_DATABASE;
    }

    /** Schema {@code bootstrap-catalog.sql} owns; the catalog views read it, so snapshots leave it alone. */
    public static final String BOOTSTRAP_SCHEMA = "floci_internal";

    public void takeSnapshot(String accountId, String clusterIdentifier, String username, String dbname, Path outputFile) {
        takeSnapshot(accountId, clusterIdentifier, username, dbname, outputFile, false);
    }

    /**
     * Dumps the database with pg_dump. With {@code skipBootstrapSchema} the {@link #BOOTSTRAP_SCHEMA}
     * is left out, which a dump meant to be replayed into a live, already bootstrapped container needs.
     */
    public void takeSnapshot(String accountId, String clusterIdentifier, String username, String dbname,
                             Path outputFile, boolean skipBootstrapSchema) {
        RedshiftContainerHandle handle = requireHandle(accountId, clusterIdentifier);

        String effectiveUser = userOrDefault(username);
        String effectiveDb = databaseOrDefault(dbname);

        String[] cmd = skipBootstrapSchema
                ? new String[]{"pg_dump", "-U", effectiveUser, "--exclude-schema=" + BOOTSTRAP_SCHEMA, effectiveDb,
                        "-f", "/tmp/dump.sql"}
                : new String[]{"pg_dump", "-U", effectiveUser, effectiveDb, "-f", "/tmp/dump.sql"};
        try {
            ContainerExec.Result result = execInContainer(handle.getContainerId(), cmd, 30);
            if (result.exitCode() != 0) {
                LOG.warnv("pg_dump failed for cluster {0} (exit {1}): {2}", clusterIdentifier, result.exitCode(), result.stderr());
                throw new AwsException("InternalFailure", "Failed to create snapshot for cluster " + clusterIdentifier + ": " + result.stderr(), 500);
            }

            try (InputStream in = lifecycleManager.getDockerClient().copyArchiveFromContainerCmd(handle.getContainerId(), "/tmp/dump.sql").exec();
                TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
                tar.getNextTarEntry();
                Files.copy(tar, outputFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorv(e, "Error executing pg_dump for cluster {0}", clusterIdentifier);
            throw new AwsException("InternalFailure", "Failed to execute pg_dump for cluster " + clusterIdentifier + ": " + e.getMessage(), 500);
        }
    }

    public void takeSnapshot(String accountId, String clusterIdentifier, String username, Path outputFile) {
        takeSnapshot(accountId, clusterIdentifier, username, "dev", outputFile);
    }

    public void alterUserPassword(String accountId, String clusterIdentifier, String username, String newPassword) {
        alterUserPassword(accountId, clusterIdentifier, username, newPassword, DEFAULT_DATABASE);
    }

    /** As the four-argument form, connecting to {@code databaseName} instead of {@code dev}. */
    public void alterUserPassword(String accountId, String clusterIdentifier, String username, String newPassword,
                                  String databaseName) {
        RedshiftContainerHandle handle = requireHandle(accountId, clusterIdentifier);
        String effectiveUser = userOrDefault(username);
        // Validate effectiveUser against safe SQL identifier pattern to prevent SQL injection.
        // psql -c sends the query string to Postgres, which allows multiple ;-separated statements,
        // so even argv-escaping doesn't protect against a username like "postgres; DROP TABLE ..."
        if (!effectiveUser.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
            throw new AwsException("InvalidParameterValue", "Username must be a valid SQL identifier", 400);
        }
        // AWS ModifyCluster rejects ', ", \, / and @ in MasterUserPassword and enforces an
        // 8-64 length plus at least one uppercase, one lowercase and one digit, and we enforce
        // the same set for parity. The single-quote rejection is doubly load-bearing: the
        // password is spliced into a psql -c SQL literal here, so ' would also break that
        // literal, but it is AWS-correct regardless, so do not "fix" it away by escaping.
        if (newPassword != null) {
            for (char forbidden : new char[]{'\'', '"', '\\', '/', '@'}) {
                if (newPassword.indexOf(forbidden) >= 0) {
                    throw new AwsException("InvalidParameterValue",
                            "MasterUserPassword must not contain ', \", \\, / or @", 400);
                }
            }
            if (newPassword.length() < 8 || newPassword.length() > 64) {
                throw new AwsException("InvalidParameterValue",
                        "MasterUserPassword must be between 8 and 64 characters in length", 400);
            }
            if (!newPassword.matches(".*[A-Z].*") || !newPassword.matches(".*[a-z].*")
                    || !newPassword.matches(".*[0-9].*")) {
                throw new AwsException("InvalidParameterValue",
                        "MasterUserPassword must contain at least one uppercase letter, one lowercase letter and one number", 400);
            }
        }
        String sql = "ALTER USER " + effectiveUser + " PASSWORD '" + newPassword + "'";
        String[] cmd = new String[]{"psql", "-U", effectiveUser, "-d", databaseName, "-c", sql};
        try {
            ContainerExec.Result result = execInContainer(handle.getContainerId(), cmd, 15);
            if (result.exitCode() != 0) {
                // Deliberately omit result.stderr() from the log and the exception: psql echoes
                // the failing ALTER USER statement, which contains the password literal.
                LOG.warnv("ALTER USER command failed for cluster {0} (exit {1})", clusterIdentifier, result.exitCode());
                throw new AwsException("InternalFailure", "Failed to change master password for cluster " + clusterIdentifier, 500);
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorv(e, "Error changing master password for cluster {0}", clusterIdentifier);
            throw new AwsException("InternalFailure", "Failed to change master password for cluster " + clusterIdentifier + ": " + e.getMessage(), 500);
        }
    }

    public void createSnapshot(String accountId, Cluster cluster, Path outputFile) {
        if (cluster == null) {
            throw new AwsException("InvalidParameterValue", "Cluster cannot be null", 400);
        }
        takeSnapshot(accountId, cluster.getClusterIdentifier(), cluster.getMasterUsername(), "dev", outputFile);
    }

    public void restoreSnapshot(String accountId, String clusterIdentifier, String username, String dbname, Path sqlDumpFile) {
        restoreSnapshot(accountId, clusterIdentifier, username, dbname, sqlDumpFile, false);
    }

    /**
     * Replays the dump with psql. With {@code stopOnError} the first failing statement aborts the
     * replay and fails the call, instead of psql skipping it and exiting successfully.
     */
    public void restoreSnapshot(String accountId, String clusterIdentifier, String username, String dbname,
                                Path sqlDumpFile, boolean stopOnError) {
        RedshiftContainerHandle handle = requireHandle(accountId, clusterIdentifier);

        if (sqlDumpFile == null || !Files.exists(sqlDumpFile)) {
            LOG.infov("Empty snapshot dump for cluster {0}, skipping restore", clusterIdentifier);
            return;
        }

        String effectiveUser = userOrDefault(username);
        String effectiveDb = databaseOrDefault(dbname);
        String fileName = sqlDumpFile.getFileName().toString();

        try {
            lifecycleManager.getDockerClient().copyArchiveToContainerCmd(handle.getContainerId())
                    .withHostResource(sqlDumpFile.toString())
                    .withRemotePath("/tmp")
                    .exec();

            String[] cmd = stopOnError
                    ? new String[]{"psql", "-U", effectiveUser, "-d", effectiveDb, "-v", "ON_ERROR_STOP=1",
                            "-f", "/tmp/" + fileName}
                    : new String[]{"psql", "-U", effectiveUser, "-d", effectiveDb, "-f", "/tmp/" + fileName};
            ContainerExec.Result result = execInContainer(handle.getContainerId(), cmd, 60);
            if (result.exitCode() != 0) {
                LOG.warnv("psql restore failed for cluster {0} (exit {1}): {2}", clusterIdentifier, result.exitCode(), result.stderr());
                throw new AwsException("InternalFailure", "Failed to restore snapshot for cluster " + clusterIdentifier + ": " + result.stderr(), 500);
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorv(e, "Error restoring snapshot for cluster {0}", clusterIdentifier);
            throw new AwsException("InternalFailure", "Failed to restore snapshot for cluster " + clusterIdentifier + ": " + e.getMessage(), 500);
        }
    }

    private static final String DROP_USER_SCHEMAS_SQL = "DO $$ DECLARE s text; BEGIN "
            + "PERFORM lo_unlink(oid) FROM pg_largeobject_metadata; "
            + "FOR s IN SELECT nspname FROM pg_namespace WHERE nspname NOT IN ('pg_catalog', 'information_schema') "
            + "AND nspname <> '" + BOOTSTRAP_SCHEMA + "' AND nspname NOT LIKE 'pg\\_%' LOOP EXECUTE format('DROP SCHEMA %I CASCADE', s); END LOOP; "
            + "CREATE SCHEMA public; END $$;";

    /**
     * Drops every large object and every user schema except the bootstrap one, and recreates an
     * empty {@code public} one, so a following {@link #restoreSnapshot} replays into a clean
     * database rather than appending to what is already there.
     */
    public void resetUserSchemas(String accountId, String clusterIdentifier, String username, String dbname) {
        RedshiftContainerHandle handle = requireHandle(accountId, clusterIdentifier);
        String effectiveUser = userOrDefault(username);
        String effectiveDb = databaseOrDefault(dbname);
        String[] cmd = new String[]{"psql", "-U", effectiveUser, "-d", effectiveDb, "-c",
                DROP_USER_SCHEMAS_SQL};
        try {
            ContainerExec.Result result = execInContainer(handle.getContainerId(), cmd, 30);
            if (result.exitCode() != 0) {
                LOG.warnv("Schema reset failed for cluster {0} (exit {1}): {2}", clusterIdentifier,
                        result.exitCode(), result.stderr());
                throw new AwsException("InternalFailure", "Failed to reset the database of " + clusterIdentifier
                        + ": " + result.stderr(), 500);
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorv(e, "Error resetting the database of cluster {0}", clusterIdentifier);
            throw new AwsException("InternalFailure", "Failed to reset the database of " + clusterIdentifier
                    + ": " + e.getMessage(), 500);
        }
    }

    public void restoreSnapshot(String accountId, String clusterIdentifier, String username, Path sqlDumpFile) {
        restoreSnapshot(accountId, clusterIdentifier, username, "dev", sqlDumpFile);
    }

    public void restoreSnapshot(String accountId, Cluster cluster, Path sqlDumpFile) {
        if (cluster == null) {
            throw new AwsException("InvalidParameterValue", "Cluster cannot be null", 400);
        }
        restoreSnapshot(accountId, cluster.getClusterIdentifier(), cluster.getMasterUsername(), "dev", sqlDumpFile);
    }

    private ContainerExec.Result execInContainer(String containerId, String[] cmd, int timeoutSeconds) {
        return ContainerExec.run(lifecycleManager.getDockerClient(), containerId, cmd, timeoutSeconds);
    }

    private byte[] buildSingleFileTar(String filename, byte[] content, int mode) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bos)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            TarArchiveEntry entry = new TarArchiveEntry(filename);
            entry.setSize(content.length);
            entry.setMode(mode);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
        }
        return bos.toByteArray();
    }
    private void waitForReady(String containerName, String containerId, String username, String dbName) {
        String effectiveUser = userOrDefault(username);
        String[] cmd = {
                "psql",
                "-h", "127.0.0.1",
                "-v", "ON_ERROR_STOP=1",
                "-U", effectiveUser,
                "-d", dbName,
                "-c", "SELECT 1"
        };
        execUntilSuccess(containerName, containerId, cmd, "PostgreSQL readiness check");
    }

    private void execUntilSuccess(String containerName, String containerId, String[] cmd, String description) {
        String lastOutput = "";
        for (int attempt = 1; attempt <= 60; attempt++) {
            try {
                ContainerExec.Result result = execInContainer(containerId, cmd, 5);
                lastOutput = result.stderr();
                if (result.exitCode() == 0) {
                    LOG.infov("Initialized {0} in Redshift container {1}", description, containerName);
                    return;
                }
            } catch (Exception e) {
                lastOutput = e.getMessage();
            }
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted initializing " + description + " in " + containerName, e);
            }
        }
        throw new IllegalStateException("Timed out initializing " + description + " in " + containerName + ": " + lastOutput);
    }

    /**
     * Bootstraps Redshift catalog and system views in the dev database so BI
     * and migration tooling can inspect metadata without relation-does-not-exist errors.
     */
    void bootstrapCatalog(String containerId, String username, String dbName) {
        String effectiveUser = userOrDefault(username);
        String effectiveDb = (dbName != null && !dbName.isBlank()) ? dbName : "dev";
        try (InputStream in = getClass().getResourceAsStream("/redshift/bootstrap-catalog.sql")) {
            if (in == null) {
                LOG.warnv("Redshift bootstrap-catalog.sql not found on classpath; skipping catalog seed for container {0}", containerId);
                return;
            }
            byte[] sqlBytes = in.readAllBytes();
            byte[] tarBytes = buildSingleFileTar("bootstrap-catalog.sql", sqlBytes, 0644);
            lifecycleManager.getDockerClient().copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(new ByteArrayInputStream(tarBytes))
                    .withRemotePath("/tmp")
                    .exec();

            List<String> targetDbs = effectiveDb.equals("template1")
                    ? List.of("template1")
                    : List.of("template1", effectiveDb);
            for (String db : targetDbs) {
                String[] cmd = new String[]{
                        "psql",
                        "-h", "127.0.0.1",
                        "-v", "ON_ERROR_STOP=1",
                        "-U", effectiveUser,
                        "-d", db,
                        "-f", "/tmp/bootstrap-catalog.sql"
                };
                ContainerExec.Result result = execInContainer(containerId, cmd, 30);
                if (result.exitCode() != 0) {
                    LOG.warnv("Redshift catalog bootstrap for {0} exited with code {1}: {2}", db, result.exitCode(), result.stderr());
                } else {
                    LOG.infov("Redshift catalog views bootstrapped successfully for {0} in container {1}", db, containerId);
                }
            }
        } catch (Exception e) {
            LOG.warnv(e, "Error bootstrapping Redshift catalog views for container {0}", containerId);
        }
    }

}
