package io.github.hectorvent.floci.services.mwaa;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.mwaa.model.Environment;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Manages the Docker lifecycle of the Postgres + Apache Airflow container pair backing a real-mode
 * MWAA environment. Not used when {@code floci.services.mwaa.mock=true}.
 *
 * <p>Neither container publishes a host port for the Postgres side — it is only ever reached by the
 * sibling Airflow container, over the Docker network, so its address is resolved directly from the
 * Docker daemon (container IP), the same way {@code EksClusterManager} avoids relying on
 * container-name DNS on the default bridge network. The Airflow container, unlike Postgres, must
 * also be reachable by Floci itself (the readiness poller and {@code MwaaWebProxy}'s relay target),
 * so it follows the same host-port pattern as the Neptune/RDS backend containers: a dynamic host
 * port when Floci runs natively, none when Floci itself runs inside Docker.
 */
@ApplicationScoped
public class MwaaEnvironmentManager {

    private static final Logger LOG = Logger.getLogger(MwaaEnvironmentManager.class);

    private static final int POSTGRES_PORT = 5432;
    private static final int AIRFLOW_WEBSERVER_PORT = 8080;
    private static final String DAGS_MOUNT = "/opt/airflow/dags";
    private static final String LOGS_MOUNT = "/opt/airflow/logs";
    /** The OS user the stock apache/airflow image runs its process as (uid 50000) — distinct from
     *  the Airflow web-UI admin login username configured via {@code _AIRFLOW_WWW_USER_USERNAME}. */
    private static final String CONTAINER_OS_USER = "airflow";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final LaunchedContainerAwsEnv awsEnv;

    @Inject
    public MwaaEnvironmentManager(ContainerBuilder containerBuilder,
                                  ContainerLifecycleManager lifecycleManager,
                                  ContainerDetector containerDetector,
                                  EmulatorConfig config,
                                  LaunchedContainerAwsEnv awsEnv) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.config = config;
        this.awsEnv = awsEnv;
    }

    /**
     * Starts the Postgres metadata database, waits for it to accept connections, then starts the
     * Airflow container (LocalExecutor) wired to it. Updates {@code environment} with the resolved
     * container ids and the Airflow container's Floci-reachable host/port. Throws on failure; the
     * caller (MwaaService) is responsible for catching and marking the environment CREATE_FAILED.
     *
     * @param startupScriptContent the {@code StartupScriptS3Path} object's bytes (already fetched
     *                              from S3 by the caller), or {@code null}/empty if the environment
     *                              has none configured
     */
    public void startEnvironment(Environment environment, String airflowVersion, byte[] startupScriptContent) {
        String name = environment.getName();
        String dbPassword = generateSecret(24);
        environment.setDbPassword(dbPassword);

        String dbContainerName = dbContainerName(config, name);
        String dbVolume = dbContainerName;
        lifecycleManager.removeIfExists(dbContainerName);
        lifecycleManager.ensureVolume(dbVolume);

        ContainerSpec dbSpec = containerBuilder.newContainer(config.services().mwaa().defaultPostgresImage())
                .withName(dbContainerName)
                .withEnv(List.of(
                        "POSTGRES_USER=airflow",
                        "POSTGRES_PASSWORD=" + dbPassword,
                        "POSTGRES_DB=airflow"))
                .withNamedVolume(dbVolume, "/var/lib/postgresql/data")
                .withDockerNetwork(config.services().mwaa().dockerNetwork())
                .withExposedPort(POSTGRES_PORT)
                .withLogRotation()
                .build();

        String dbContainerId = lifecycleManager.create(dbSpec);
        lifecycleManager.startCreated(dbContainerId, dbSpec);
        environment.setDbContainerId(dbContainerId);
        waitForPostgresReady(dbContainerId);
        String dbIp = resolveContainerIp(dbContainerId, dbSpec.networkMode());

        LOG.infov("MWAA Postgres container {0} ready for environment {1} at {2}:{3}",
                dbContainerId, name, dbIp, String.valueOf(POSTGRES_PORT));

        startAirflowContainer(environment, airflowVersion, dbIp, dbPassword, startupScriptContent);
    }

    // Package-private (not private) so MwaaEnvironmentManagerTest can exercise container-spec
    // construction directly with a mocked ContainerLifecycleManager, without needing to also mock
    // the exec-based Postgres readiness wait that startEnvironment() performs first.
    void startAirflowContainer(Environment environment, String airflowVersion, String dbIp, String dbPassword,
                               byte[] startupScriptContent) {
        String name = environment.getName();
        String airflowContainerName = airflowContainerName(config, name);
        String dagsVolume = airflowContainerName + "-dags";
        String logsVolume = airflowContainerName + "-logs";

        lifecycleManager.removeIfExists(airflowContainerName);
        lifecycleManager.ensureVolume(dagsVolume);
        lifecycleManager.ensureVolume(logsVolume);

        String image = "apache/airflow:%s-python3.12".formatted(airflowVersion);
        String adminUser = "admin";
        String adminPassword = generateSecret(24);
        String sqlAlchemyConn = "postgresql+psycopg2://airflow:" + dbPassword + "@" + dbIp + ":" + POSTGRES_PORT + "/airflow";

        // Points DAG code's own AWS SDK calls (boto3, botocore) at Floci itself, the same way
        // Lambda/ECS containers already do via LaunchedContainerAwsEnv — otherwise a real DAG's
        // boto3.client("s3") etc. would target real AWS instead of this emulator.
        List<String> env = new ArrayList<>(awsEnv.sdkBaselineEnv(config.defaultRegion(), Optional.empty()));
        env.addAll(List.of(
                "AIRFLOW__CORE__EXECUTOR=LocalExecutor",
                "AIRFLOW__DATABASE__SQL_ALCHEMY_CONN=" + sqlAlchemyConn,
                "AIRFLOW__CORE__SQL_ALCHEMY_CONN=" + sqlAlchemyConn,
                "AIRFLOW__CORE__FERNET_KEY=" + generateFernetKey(),
                "AIRFLOW__WEBSERVER__SECRET_KEY=" + generateSecret(32),
                "AIRFLOW__CORE__LOAD_EXAMPLES=false",
                "_AIRFLOW_WWW_USER_USERNAME=" + adminUser,
                "_AIRFLOW_WWW_USER_PASSWORD=" + adminPassword));

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(airflowContainerName)
                .withEnv(env)
                // Overrides the stock apache/airflow entrypoint, which is designed to run a single
                // role per container. This runs migrations, the admin bootstrap (using the stock
                // _AIRFLOW_WWW_USER_* var names, since the stock entrypoint's own auto-creation
                // logic is bypassed along with the rest of it), then backgrounds the scheduler and
                // execs the webserver as PID 1 so container signals reach it directly.
                .withEntrypoint(List.of("sh", "-c"))
                .withCmd(List.of(airflowBootstrapScript()))
                .withNamedVolume(dagsVolume, DAGS_MOUNT)
                .withNamedVolume(logsVolume, LOGS_MOUNT)
                .withDockerNetwork(config.services().mwaa().dockerNetwork())
                .withLogRotation();

        if (!containerDetector.isRunningInContainer()) {
            specBuilder.withDynamicPort(AIRFLOW_WEBSERVER_PORT);
        } else {
            specBuilder.withExposedPort(AIRFLOW_WEBSERVER_PORT);
        }

        ContainerSpec spec = specBuilder.build();
        // create -> inject startup.sh (if configured) + the sudoers grant -> start, so both exist
        // before the entrypoint runs — same sequencing EksClusterManager uses to inject
        // registries.yaml/the webhook kubeconfig before the k3s container's entrypoint boots.
        String containerId = lifecycleManager.create(spec);
        ContainerInfo info;
        try {
            // Real MWAA documents startup scripts using "sudo yum install ..." as the baseline way
            // to install OS packages — sudo is assumed to just work for the airflow user. The stock
            // apache/airflow image ships sudo but requires a password, so without this grant every
            // sudo line in a script copied from AWS's own docs would hang/fail. Granted unconditionally
            // (not only when a startup script is configured), matching that it's a baseline capability
            // of the environment on real MWAA, not something tied to having a script at all.
            // Best-effort: absence of sudo access doesn't mean requested configuration was silently
            // skipped, just that "sudo" lines in a startup script won't work.
            copyFileIntoContainer(containerId, "/etc/sudoers.d", "floci-airflow-nopasswd",
                    (CONTAINER_OS_USER + " ALL=(ALL) NOPASSWD:ALL\n").getBytes(StandardCharsets.UTF_8));
            if (startupScriptContent != null && startupScriptContent.length > 0) {
                // Unlike the sudoers grant (best-effort infra) or DAG-file sync (a bad DAG must never
                // fail the environment), a *configured* startup script is the user's explicit request —
                // if injection fails, the entrypoint's "if [ -f /startup.sh ]" guard would just silently
                // skip it and the environment would reach AVAILABLE without ever having applied the
                // requested setup. Hard-fail instead, matching real MWAA gating environment creation on
                // startup-script failure.
                if (!copyFileIntoContainer(containerId, "/", "startup.sh", startupScriptContent)) {
                    throw new IllegalStateException(
                            "Could not inject the configured startup script into MWAA container "
                                    + containerId + " for environment " + environment.getName());
                }
            }
            info = lifecycleManager.startCreated(containerId, spec);
        } catch (Exception e) {
            lifecycleManager.removeIfExists(containerId);
            throw e;
        }
        environment.setAirflowContainerId(info.containerId());

        ContainerLifecycleManager.EndpointInfo endpoint = info.getEndpoint(AIRFLOW_WEBSERVER_PORT);
        if (endpoint != null) {
            environment.setAirflowInternalHost(endpoint.host());
            environment.setAirflowInternalPort(endpoint.port());
        } else {
            environment.setAirflowInternalHost("localhost");
            environment.setAirflowInternalPort(AIRFLOW_WEBSERVER_PORT);
        }

        LOG.infov("Started Airflow container {0} for MWAA environment {1} (image {2}), reachable at {3}:{4}",
                info.containerId(), name, image, environment.getAirflowInternalHost(),
                String.valueOf(environment.getAirflowInternalPort()));
    }

    /**
     * Polls the Airflow container's unauthenticated {@code /health} endpoint and requires both
     * {@code metadatabase.status} and {@code scheduler.status} to be {@code "healthy"}.
     */
    public boolean isReady(Environment environment) {
        String host = environment.getAirflowInternalHost();
        if (host == null || environment.getAirflowContainerId() == null) {
            return false;
        }
        String url = "http://" + host + ":" + environment.getAirflowInternalPort() + "/health";
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            if (conn.getResponseCode() != 200) {
                return false;
            }
            String body;
            try (var in = conn.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            return body.contains("\"metadatabase\"") && body.contains("\"scheduler\"")
                    && healthySection(body, "metadatabase") && healthySection(body, "scheduler");
        } catch (Exception e) {
            return false;
        }
    }

    /** Crude but dependency-free check for {@code "<section>":{"status":"healthy"...}} in the /health JSON. */
    static boolean healthySection(String body, String section) {
        int idx = body.indexOf("\"" + section + "\"");
        if (idx < 0) {
            return false;
        }
        int statusIdx = body.indexOf("\"status\"", idx);
        if (statusIdx < 0) {
            return false;
        }
        int healthyIdx = body.indexOf("\"healthy\"", statusIdx);
        int nextSectionCommaIdx = body.indexOf("},", idx);
        return healthyIdx >= 0 && (nextSectionCommaIdx < 0 || healthyIdx < nextSectionCommaIdx + 2);
    }

    /** Stops and removes both containers and all three named volumes for the given environment. */
    public void stopEnvironment(Environment environment) {
        if (config.services().mwaa().keepRunningOnShutdown()) {
            LOG.infov("Leaving MWAA containers for environment {0} running", environment.getName());
            return;
        }
        String name = environment.getName();
        if (environment.getAirflowContainerId() != null) {
            lifecycleManager.stopAndRemove(environment.getAirflowContainerId(), null);
        }
        if (environment.getDbContainerId() != null) {
            lifecycleManager.stopAndRemove(environment.getDbContainerId(), null);
        }
        String airflowContainerName = airflowContainerName(config, name);
        lifecycleManager.removeVolume(dbContainerName(config, name));
        lifecycleManager.removeVolume(airflowContainerName + "-dags");
        lifecycleManager.removeVolume(airflowContainerName + "-logs");
        LOG.infov("Stopped MWAA containers for environment {0}", name);
    }

    /** Writes {@code content} to {@code DAGS_MOUNT/relativePath} inside the Airflow container. */
    public void copyDagFile(Environment environment, String relativePath, byte[] content) {
        copyFileIntoContainer(environment.getAirflowContainerId(), DAGS_MOUNT, relativePath, content);
    }

    /** Removes {@code DAGS_MOUNT/relativePath} from the Airflow container (best-effort). */
    public void removeDagFile(Environment environment, String relativePath) {
        try {
            execInContainer(environment.getAirflowContainerId(), new String[]{"rm", "-f", DAGS_MOUNT + "/" + relativePath});
        } catch (Exception e) {
            LOG.warnv("Could not remove DAG {0} from environment {1}: {2}",
                    relativePath, environment.getName(), e.getMessage());
        }
    }

    /** Writes requirements.txt into the container and runs {@code pip install -r} against it. */
    public void installRequirements(Environment environment, byte[] requirementsContent) {
        String containerId = environment.getAirflowContainerId();
        if (containerId == null) {
            return;
        }
        copyFileIntoContainer(containerId, "/tmp", "mwaa-requirements.txt", requirementsContent);
        try {
            ExecResult result = execInContainer(containerId,
                    new String[]{"pip", "install", "--no-cache-dir", "-r", "/tmp/mwaa-requirements.txt"});
            if (result.exitCode() != 0) {
                LOG.warnv("pip install -r requirements.txt exited {0} for environment {1}: {2}",
                        String.valueOf(result.exitCode()), environment.getName(), result.stderr());
            } else {
                LOG.infov("Installed requirements.txt for MWAA environment {0}", environment.getName());
            }
        } catch (Exception e) {
            LOG.warnv("Failed to install requirements for environment {0}: {1}", environment.getName(), e.getMessage());
        }
    }

    /** Runs {@code airflow <cliCommand>} inside the Airflow container via {@code sh -c}. */
    public ExecResult runAirflowCli(String airflowContainerId, String cliCommand) throws Exception {
        return execInContainer(airflowContainerId, new String[]{"sh", "-c", "airflow " + cliCommand});
    }

    /** Routed through {@link ContainerStorageHelper} so multiple Floci instances sharing one Docker
     *  daemon (via {@code FLOCI_DOCKER_RESOURCE_NAMESPACE}) don't collide, same as every other
     *  Docker-backed service (EKS, RDS, ...). {@code config} may be {@code null} — the helper treats
     *  that as "no namespace configured" and returns the base name unchanged. */
    static String dbContainerName(EmulatorConfig config, String environmentName) {
        return ContainerStorageHelper.dockerName(config, "floci-mwaa-" + environmentName + "-db");
    }

    static String airflowContainerName(EmulatorConfig config, String environmentName) {
        return ContainerStorageHelper.dockerName(config, "floci-mwaa-" + environmentName + "-airflow");
    }

    /**
     * Environment variables a startup script must not be able to permanently override — mirrors
     * real MWAA's documented "reserved environment variables" (which AWS silently restores after a
     * startup script runs), scoped to the subset Floci itself configures and depends on: the
     * LocalExecutor/DB/security wiring generated in {@link #startAirflowContainer}, plus the
     * AWS-SDK-redirection vars from {@link LaunchedContainerAwsEnv} that this environment's own
     * AWS-facing DAG code relies on to reach Floci instead of real AWS. Unlike AWS's full ~30-entry
     * list, this omits Celery/StatsD-only vars Floci never sets — LocalExecutor has no broker, so
     * there is nothing of Floci's there to protect.
     */
    private static final List<String> PROTECTED_ENV_VARS = List.of(
            "AIRFLOW__CORE__EXECUTOR",
            "AIRFLOW__DATABASE__SQL_ALCHEMY_CONN",
            "AIRFLOW__CORE__SQL_ALCHEMY_CONN",
            "AIRFLOW__CORE__FERNET_KEY",
            "AIRFLOW__WEBSERVER__SECRET_KEY",
            "AIRFLOW__CORE__LOAD_EXAMPLES",
            "AIRFLOW_HOME",
            "AWS_DEFAULT_REGION",
            "AWS_REGION",
            "AWS_ACCESS_KEY_ID",
            "AWS_SECRET_ACCESS_KEY",
            "AWS_SESSION_TOKEN",
            "AWS_ENDPOINT_URL",
            "FLOCI_HOSTNAME",
            "FLOCI_ENDPOINT");

    /**
     * Overridden ENTRYPOINT script: run the optional startup script (if {@code /startup.sh} was
     * injected before the container started), migrate the metadata database, idempotently
     * bootstrap the admin user from the stock {@code _AIRFLOW_WWW_USER_*} env var names,
     * background the scheduler, then {@code exec} the webserver so it becomes PID 1.
     *
     * <p>The startup script is {@code .}-sourced (not run as a subshell) so any environment
     * variables it exports — its documented purpose on real MWAA, mirroring
     * {@code StartupScriptS3Path} — are visible to the migrate/scheduler/webserver steps that
     * follow in the same shell. {@code || exit 1} converts a failing sourced script (one that
     * doesn't itself call {@code exit}) into a hard stop of this whole entrypoint, so — matching
     * real MWAA gating environment creation on startup-script failure — a broken startup script
     * prevents Airflow from ever starting rather than being silently ignored.
     *
     * <p>{@link #PROTECTED_ENV_VARS} are snapshotted to {@code _FLOCI_ORIG_*} names before the
     * script runs and re-exported from those snapshots immediately after, so a script that
     * overwrites e.g. {@code AIRFLOW__CORE__FERNET_KEY} or {@code AWS_ENDPOINT_URL} — accidentally
     * or otherwise — can't actually corrupt the environment Floci just set up, matching real MWAA
     * restoring reserved variables to their managed values.
     *
     * <p>The migrate + user-create steps are joined with {@code &&} (each blocks until the
     * previous succeeds) and only "airflow scheduler" is backgrounded with {@code &} — backgrounding
     * the whole chain (as in {@code "migrate && scheduler & exec webserver"}) would let the webserver
     * start racing the migration instead of after it, since {@code &} has lower precedence than
     * {@code &&} and would apply to the entire left-hand chain.
     */
    static String airflowBootstrapScript() {
        String snapshot = PROTECTED_ENV_VARS.stream()
                .map(v -> "_FLOCI_ORIG_" + v + "=\"$" + v + "\"")
                .collect(Collectors.joining("\n", "", "\n"));
        String restore = PROTECTED_ENV_VARS.stream()
                .map(v -> "export " + v + "=\"$_FLOCI_ORIG_" + v + "\"")
                .collect(Collectors.joining("\n", "", "\n"));

        return snapshot
                + "if [ -f /startup.sh ]; then . /startup.sh || exit 1; fi\n"
                + restore
                + "airflow db migrate && "
                + "(airflow users create --username \"$_AIRFLOW_WWW_USER_USERNAME\" "
                + "--password \"$_AIRFLOW_WWW_USER_PASSWORD\" --firstname Admin --lastname User "
                + "--role Admin --email admin@example.com || true); "
                + "airflow scheduler & "
                + "exec airflow webserver";
    }

    private void waitForPostgresReady(String containerId) {
        Exception last = null;
        for (int attempt = 1; attempt <= 60; attempt++) {
            try {
                ExecResult result = execInContainer(containerId, new String[]{"pg_isready", "-U", "airflow"});
                if (result.exitCode() == 0) {
                    return;
                }
            } catch (Exception e) {
                last = e;
            }
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for MWAA Postgres readiness", e);
            }
        }
        throw new IllegalStateException("Timed out waiting for MWAA Postgres container " + containerId
                + " to become ready" + (last != null ? ": " + last.getMessage() : ""));
    }

    private String resolveContainerIp(String containerId, String preferredNetwork) {
        InspectContainerResponse inspect = lifecycleManager.getDockerClient().inspectContainerCmd(containerId).exec();
        Map<String, ContainerNetwork> networks = inspect.getNetworkSettings().getNetworks();
        if (networks != null) {
            if (preferredNetwork != null && networks.containsKey(preferredNetwork)) {
                String ip = networks.get(preferredNetwork).getIpAddress();
                if (ip != null && !ip.isBlank()) {
                    return ip;
                }
            }
            for (ContainerNetwork net : networks.values()) {
                if (net.getIpAddress() != null && !net.getIpAddress().isBlank()) {
                    return net.getIpAddress();
                }
            }
        }
        return inspect.getNetworkSettings().getIpAddress();
    }

    /**
     * @return {@code true} on success, {@code false} on failure (logged as a warning either way).
     *         Callers for whom a missing file is tolerable (DAG sync, the sudoers grant) can ignore
     *         the result; callers for whom it isn't (the configured startup script) must check it.
     */
    private boolean copyFileIntoContainer(String containerId, String remoteDir, String relativePath, byte[] content) {
        if (containerId == null) {
            return false;
        }
        try {
            lifecycleManager.getDockerClient()
                    .copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(new ByteArrayInputStream(tarSingleFile(relativePath, content)))
                    .withRemotePath(remoteDir)
                    .exec();
            return true;
        } catch (Exception e) {
            LOG.warnv("Could not copy {0} into MWAA container {1}: {2}", relativePath, containerId, e.getMessage());
            return false;
        }
    }

    private static byte[] tarSingleFile(String entryName, byte[] content) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
                TarArchiveEntry entry = new TarArchiveEntry(entryName);
                entry.setSize(content.length);
                entry.setMode(0644);
                tar.putArchiveEntry(entry);
                tar.write(content);
                tar.closeArchiveEntry();
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build in-memory tar for " + entryName, e);
        }
    }

    ExecResult execInContainer(String containerId, String[] cmd) throws Exception {
        var dockerClient = lifecycleManager.getDockerClient();
        var exec = dockerClient.execCreateCmd(containerId)
                .withCmd(cmd)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        boolean completed = dockerClient.execStartCmd(exec.getId())
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        if (frame.getStreamType() == StreamType.STDERR) {
                            stderr.writeBytes(frame.getPayload());
                        } else {
                            stdout.writeBytes(frame.getPayload());
                        }
                    }
                })
                .awaitCompletion(30, TimeUnit.SECONDS);

        if (!completed) {
            throw new RuntimeException("exec timed out in container " + containerId);
        }
        Long exitCode = dockerClient.inspectExecCmd(exec.getId()).exec().getExitCodeLong();
        return new ExecResult(exitCode != null ? exitCode : -1,
                stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private static String generateSecret(int bytes) {
        byte[] buf = new byte[bytes];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /** A Fernet key must be 32 url-safe base64-encoded bytes (Airflow's {@code AIRFLOW__CORE__FERNET_KEY}). */
    private static String generateFernetKey() {
        byte[] buf = new byte[32];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().encodeToString(buf);
    }

    public record ExecResult(long exitCode, String stdout, String stderr) {}
}
