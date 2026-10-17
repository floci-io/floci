package io.github.hectorvent.floci.services.lambdaweb;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.core.common.docker.RetryingTarCopier;
import io.github.hectorvent.floci.core.common.docker.StreamingDocker;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.iam.model.SessionCreds;
import io.github.hectorvent.floci.services.lambda.launcher.ImageResolver;
import io.github.hectorvent.floci.services.lambda.launcher.LambdaExecutionRoleCredentials;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunction;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionRevision;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * The execution environments of Web Function endpoints: Node.js server containers of a revision,
 * started by requests that find no room, reused while requests keep coming and removed after the
 * Lambda idle timeout. The runtime loads {@code index.js}, or the file {@code AWS_LAMBDA_NODEJS_ENTRYPOINT} names,
 * from {@code /var/task} with the node flags AWS uses.
 */
@ApplicationScoped
public class LambdaWebEnvironmentManager implements ContainerTeardown {

    static final int APP_PORT = 3000;
    static final String TASK_DIR = "/var/task";
    static final int MEMORY_MB = 2048;
    static final Duration LISTEN_TIMEOUT = Duration.ofSeconds(10);
    static final List<String> NODE_FLAGS = List.of("--expose-gc", "--max-http-header-size", "135168",
            "--no-experimental-require-module", "--max-semi-space-size=102", "--max-old-space-size=1024");
    private static final Logger LOG = Logger.getLogger(LambdaWebEnvironmentManager.class);
    private static final DateTimeFormatter LOG_STREAM_DATE =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);
    private static final Duration READY_POLL = Duration.ofMillis(200);
    private static final int LISTEN_PROBE_TIMEOUT_SECONDS = 2;
    private static final String LISTEN_PROBE = "require('node:net').connect(" + APP_PORT + ", '127.0.0.1')"
            + ".once('connect', () => process.exit(0)).once('error', () => process.exit(1))";
    private static final int LOG_TAIL_LINES = 20;
    private static final Duration LOG_TAIL_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration EXIT_WATCH_REARM = Duration.ofSeconds(1);
    private static final Duration EXIT_STATUS_TIMEOUT = Duration.ofSeconds(5);

    /**
     * One container of an endpoint's pool. It joins the pool before it starts, so requests that
     * arrive while it initializes wait for it instead of starting more. {@code acquire} reserves it
     * for one request and {@code release} gives the reservation back; the idle reaper only stops an
     * environment with no reservation.
     */
    public static final class Environment {
        private final String poolKey;
        private final String revisionId;
        private final String accountId;
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private volatile String containerId;
        private volatile String host;
        private volatile int port;
        private volatile String logStream;
        private volatile SessionCreds roleCredentials;
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicLong lastUsed = new AtomicLong(System.nanoTime());
        private final AtomicBoolean alive = new AtomicBoolean(true);
        /** The {@link System#nanoTime()} at which Floci saw the process end. */
        private final CompletableFuture<Long> exitSeen = new CompletableFuture<>();

        Environment(String poolKey, String revisionId, String accountId) {
            this.poolKey = poolKey;
            this.revisionId = revisionId;
            this.accountId = accountId;
        }

        private boolean isReady() {
            return ready.isDone() && !ready.isCompletedExceptionally();
        }

        public String containerId() {
            return containerId;
        }

        public String host() {
            return host;
        }

        public int port() {
            return port;
        }

        public String logStream() {
            return logStream;
        }

        public boolean isAlive() {
            return alive.get();
        }

        public void release() {
            inFlight.decrementAndGet();
            lastUsed.set(System.nanoTime());
        }

        private void reserve() {
            inFlight.incrementAndGet();
            lastUsed.set(System.nanoTime());
        }

        /**
         * Whether Floci saw the process end before {@code nanoTime}. A request sent after that never
         * reached the app, so it gets a fresh environment, as on AWS. Floci sees an exit tens of
         * milliseconds after it happens. A request sent in between fails, so it never runs twice.
         */
        public boolean exitedBefore(long nanoTime) {
            Long seen = exitSeen.getNow(null);
            return seen != null && seen - nanoTime < 0;
        }

        /** Completes once Floci has seen the process end, or after {@code limit} while it still runs. */
        public CompletableFuture<Void> awaitExit(Duration limit) {
            return exitSeen.copy().orTimeout(limit.toNanos(), TimeUnit.NANOSECONDS).handle((seen, timedOut) -> null);
        }

        void sawExit(long nanoTime) {
            exitSeen.complete(nanoTime);
        }
    }

    /** The environment did not come up. {@code page} is what the endpoint answers. */
    public static final class StartFailure extends RuntimeException {
        private final transient LambdaWebErrorPages.Page page;

        StartFailure(LambdaWebErrorPages.Page page) {
            super(page.errorType());
            this.page = page;
        }

        LambdaWebErrorPages.Page page() {
            return page;
        }
    }

    /** Every environment of the endpoint is full and {@code maxEnvironments} allows no more. */
    public static final class Throttled extends RuntimeException {
        Throttled() {
            super(LambdaWebErrorPages.ENDPOINT_MAX_ENV_THROTTLE.errorType());
        }
    }

    private final EmulatorConfig config;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final DockerClient streamingDocker;
    private final ImageResolver imageResolver;
    private final EcrRegistryManager ecrRegistryManager;
    private final S3Service s3Service;
    private final ZipExtractor zipExtractor;
    private final LaunchedContainerAwsEnv awsEnv;
    private final LambdaExecutionRoleCredentials executionRoleCredentials;
    /** The environments of each endpoint in each region. A list is only touched inside a map operation on its key. */
    private final ConcurrentHashMap<String, List<Environment>> pools = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "lambda-web-environments");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public LambdaWebEnvironmentManager(EmulatorConfig config, ContainerBuilder containerBuilder,
                                       ContainerLifecycleManager lifecycleManager,
                                       @StreamingDocker DockerClient streamingDocker, ImageResolver imageResolver,
                                       EcrRegistryManager ecrRegistryManager, S3Service s3Service,
                                       ZipExtractor zipExtractor, LaunchedContainerAwsEnv awsEnv,
                                       LambdaExecutionRoleCredentials executionRoleCredentials) {
        this.config = config;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.streamingDocker = streamingDocker;
        this.imageResolver = imageResolver;
        this.ecrRegistryManager = ecrRegistryManager;
        this.s3Service = s3Service;
        this.zipExtractor = zipExtractor;
        this.awsEnv = awsEnv;
        this.executionRoleCredentials = executionRoleCredentials;
        long period = Math.max(1, Math.min(30, config.services().lambda().containerIdleTimeoutSeconds() / 2 + 1));
        reaper.scheduleWithFixedDelay(this::reapIdle, period, period, TimeUnit.SECONDS);
    }

    /**
     * An environment of the endpoint for {@code revision} with room for one more request, reserved
     * for it. The least busy one is taken, the one idle longest on a tie, so requests rotate. When
     * all are full a new one starts. At {@code maxEnvironments} an idle environment of another
     * revision makes room, and without one the request is {@link Throttled}. {@code endpointKey}
     * names one creation of the endpoint in one region, so a recreated endpoint starts afresh. The
     * caller releases the environment when the request is over. Blocks on Docker and S3.
     */
    public Environment acquire(String accountId, String region, WebFunction fn, String endpointKey,
                               Integer maxEnvironments, WebFunctionRevision revision) {
        int perEnvironment = Math.max(1, revision.serviceConfig.maxConcurrencyPerEnvironment);
        AtomicReference<Pick> picked = new AtomicReference<>();
        pools.compute(endpointKey, (key, current) -> {
            List<Environment> pool = current == null ? new ArrayList<>() : current;
            picked.set(pick(pool, endpointKey, accountId, revision.revisionId, perEnvironment, maxEnvironments));
            return pool;
        });
        Pick pick = picked.get();
        if (pick.retired() != null) {
            reaper.execute(() -> stopRetired(pick.retired()));
        }
        Environment chosen = pick.environment();
        if (!pick.starter()) {
            awaitReady(chosen);
            return chosen;
        }
        try {
            start(chosen, region, fn, revision);
        } catch (RuntimeException e) {
            chosen.alive.set(false);
            removeFromPool(chosen);
            chosen.ready.completeExceptionally(e);
            throw e;
        }
        if (!chosen.isAlive()) {
            IllegalStateException stopped = new IllegalStateException("The environment was stopped while it started");
            chosen.ready.completeExceptionally(stopped);
            stopContainer(chosen);
            throw stopped;
        }
        chosen.ready.complete(null);
        return chosen;
    }

    private record Pick(Environment environment, boolean starter, Environment retired) {
    }

    private static Pick pick(List<Environment> pool, String endpointKey, String accountId, String revisionId,
                             int perEnvironment, Integer maxEnvironments) {
        Environment open = pool.stream()
                .filter(each -> each.isAlive() && each.revisionId.equals(revisionId)
                        && each.inFlight.get() < perEnvironment)
                .min(Comparator.comparingInt((Environment each) -> each.inFlight.get())
                        .thenComparingLong(each -> each.lastUsed.get()))
                .orElse(null);
        if (open != null) {
            open.reserve();
            return new Pick(open, false, null);
        }
        Environment retired = null;
        if (maxEnvironments != null && pool.size() >= maxEnvironments) {
            retired = pool.stream()
                    .filter(each -> !each.revisionId.equals(revisionId) && isIdle(each, 0))
                    .min(Comparator.comparingLong(each -> each.lastUsed.get()))
                    .orElseThrow(Throttled::new);
            pool.remove(retired);
        }
        Environment started = new Environment(endpointKey, revisionId, accountId);
        pool.add(started);
        started.reserve();
        return new Pick(started, true, retired);
    }

    private static boolean isIdle(Environment environment, long idleNanos) {
        return environment.isAlive() && environment.isReady() && environment.inFlight.get() == 0
                && System.nanoTime() - environment.lastUsed.get() >= idleNanos;
    }

    private void stopRetired(Environment environment) {
        try {
            LOG.infov("Removing the Lambda Web environment {0} of revision {1} to make room",
                    environment.containerId, environment.revisionId);
            discard(environment);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not remove the Lambda Web environment {0}", environment.containerId);
        }
    }

    private static void awaitReady(Environment environment) {
        try {
            environment.ready.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void removeFromPool(Environment environment) {
        pools.computeIfPresent(environment.poolKey, (key, pool) -> {
            pool.remove(environment);
            return pool.isEmpty() ? null : pool;
        });
    }

    /** Stops an environment, with the one second AWS gives the process after SIGTERM. */
    public void discard(Environment environment) {
        removeFromPool(environment);
        if (!environment.alive.compareAndSet(true, false)) {
            return;
        }
        if (environment.containerId == null) {
            return;
        }
        try {
            exitCodeIfStopped(environment.containerId).ifPresent(exitCode ->
                    LOG.infov("The Lambda Web environment {0} had exited with status {1}: {2}",
                            environment.containerId, exitCode, lastLogLines(environment.containerId)));
        } catch (RuntimeException e) {
            LOG.debugv(e, "Could not inspect the Lambda Web environment {0}", environment.containerId);
        }
        stopContainer(environment);
    }

    private void stopContainer(Environment environment) {
        try {
            lifecycleManager.stopAndRemove(environment.containerId, null, 1);
        } finally {
            forgetCredentials(environment);
        }
    }

    @Override
    public void stopManagedContainers() {
        allEnvironments().forEach(this::discard);
    }

    /** The live environment that writes {@code logStream}, the one a response names. */
    Environment environmentOf(String logStream) {
        return allEnvironments().stream()
                .filter(each -> logStream.equals(each.logStream))
                .findFirst()
                .orElseThrow();
    }

    private List<Environment> allEnvironments() {
        List<Environment> all = new ArrayList<>();
        for (String key : pools.keySet()) {
            pools.computeIfPresent(key, (ignored, pool) -> {
                all.addAll(pool);
                return pool;
            });
        }
        return all;
    }

    @PreDestroy
    void shutdown() {
        reaper.shutdownNow();
    }

    private void reapIdle() {
        long idleNanos = TimeUnit.SECONDS.toNanos(config.services().lambda().containerIdleTimeoutSeconds());
        for (Environment environment : allEnvironments()) {
            AtomicBoolean idle = new AtomicBoolean();
            pools.computeIfPresent(environment.poolKey, (key, pool) -> {
                if (isIdle(environment, idleNanos) && pool.remove(environment)) {
                    idle.set(true);
                }
                return pool.isEmpty() ? null : pool;
            });
            if (!idle.get()) {
                continue;
            }
            try {
                LOG.infov("Removing the idle Lambda Web environment {0}", environment.containerId);
                discard(environment);
            } catch (RuntimeException e) {
                LOG.warnv(e, "Could not remove the idle Lambda Web environment {0}", environment.containerId);
            }
        }
    }

    private void start(Environment environment, String region, WebFunction fn, WebFunctionRevision revision) {
        String accountId = environment.accountId;
        String image = ecrRegistryManager.rewriteImageUri(imageResolver.resolve(revision.buildConfig.runtime));
        String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String name = ContainerStorageHelper.prefixedDockerName(config, "floci-lambda-web",
                fn.functionName + "-" + revision.revisionId + "-" + shortId);
        Map<String, String> userEnv = revision.serviceConfig.environmentVariables == null
                ? Map.of() : revision.serviceConfig.environmentVariables;
        List<String> cmd = new ArrayList<>(NODE_FLAGS);
        cmd.add(userEnv.getOrDefault("AWS_LAMBDA_NODEJS_ENTRYPOINT", "index.js"));
        Optional<SessionCreds> roleCredentials = executionRoleCredentials.forRole(
                revision.serviceConfig.executionRoleArn, accountId, fn.functionName);
        ContainerSpec spec = containerBuilder.newContainer(image)
                .withName(name)
                .withEnv(environment(accountId, region, revision, userEnv, roleCredentials))
                .withEntrypoint(List.of("/var/lang/bin/node"))
                .withCmd(cmd)
                .withWorkingDir(TASK_DIR)
                .withMemoryMb(MEMORY_MB)
                .withLoopbackPortBinding(APP_PORT, 0)
                .withDockerNetwork(config.services().lambda().dockerNetwork())
                .withHostDockerInternalOnLinux()
                .withEmbeddedDns()
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels("lambda-web", fn.functionName, accountId, region))
                .build();
        String containerId;
        try {
            containerId = lifecycleManager.create(spec);
        } catch (RuntimeException e) {
            roleCredentials.ifPresent(creds -> executionRoleCredentials.unregister(accountId, creds.accessKeyId()));
            throw e;
        }
        try {
            copyCode(containerId, accountId, region, fn, revision);
            ContainerInfo info = lifecycleManager.startCreated(containerId, spec);
            EndpointInfo endpoint = info.getEndpoint(APP_PORT);
            awaitListening(containerId);
            environment.containerId = containerId;
            environment.host = endpoint.host();
            environment.port = endpoint.port();
            environment.logStream = LOG_STREAM_DATE.format(Instant.now()) + "/" + fn.functionName + "["
                    + revision.revisionId + "]" + UUID.randomUUID().toString().replace("-", "");
            environment.roleCredentials = roleCredentials.orElse(null);
            watchExit(environment);
            LOG.infov("Started the Lambda Web environment {0} of {1} revision {2} at {3}:{4}",
                    containerId, fn.functionName, revision.revisionId, endpoint.host(), endpoint.port());
        } catch (RuntimeException e) {
            lifecycleManager.stopAndRemove(containerId, null, 1);
            roleCredentials.ifPresent(creds -> executionRoleCredentials.unregister(accountId, creds.accessKeyId()));
            throw e;
        }
    }

    private List<String> environment(String accountId, String region, WebFunctionRevision revision,
                                     Map<String, String> userEnv, Optional<SessionCreds> roleCredentials) {
        List<String> env = new ArrayList<>();
        env.add("AWS_LAMBDA_HTTP_ENDPOINT=0.0.0.0:" + APP_PORT);
        env.add("AWS_LAMBDA_INITIALIZATION_TYPE=native-http");
        env.add("AWS_EXECUTION_ENV=AWS_Lambda_" + revision.buildConfig.runtime + "-lite");
        env.add("AWS_LAMBDA_FUNCTION_MEMORY_SIZE=" + MEMORY_MB);
        env.add("AWS_LAMBDA_LOG_LEVEL=" + revision.serviceConfig.applicationLogLevel);
        env.add("AWS_ACCOUNT_ID=" + accountId);
        env.add("NODE_PATH=" + TASK_DIR);
        env.addAll(awsEnv.sdkBaselineEnv(region, Optional.empty(), roleCredentials, accountId));
        userEnv.forEach((name, value) -> env.add(name + "=" + value));
        return env;
    }

    private void forgetCredentials(Environment environment) {
        SessionCreds credentials = environment.roleCredentials;
        if (credentials != null) {
            executionRoleCredentials.unregister(environment.accountId, credentials.accessKeyId());
        }
    }

    /** The code goes in by {@code docker cp}, so it works when Floci itself runs in a container. */
    private void copyCode(String containerId, String accountId, String region, WebFunction fn,
                          WebFunctionRevision revision) {
        WebFunctionRevision.BuildConfig build = revision.buildConfig;
        byte[] zip = RequestScopes.callAs(accountId, region,
                () -> s3Service.getObject(build.bucket, build.key, build.versionId)).getData();
        Path dir;
        try {
            dir = Files.createTempDirectory("floci-lambda-web-");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not make room for the code of " + fn.functionName, e);
        }
        try {
            zipExtractor.extractTo(zip, dir, config.services().lambda().zipMaxEntries());
            RetryingTarCopier.copyStreamed(lifecycleManager.getDockerClient(), containerId, TASK_DIR,
                    "web-" + fn.functionName, out -> tar(dir, out));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not unpack the code of " + fn.functionName + " revision "
                    + revision.revisionId, e);
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void tar(Path dir, OutputStream out) throws IOException {
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out); Stream<Path> walk = Files.walk(dir)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            List<Path> paths = walk.filter(path -> !path.equals(dir)).sorted().toList();
            for (Path path : paths) {
                boolean directory = Files.isDirectory(path);
                String name = dir.relativize(path).toString().replace('\\', '/');
                TarArchiveEntry entry = new TarArchiveEntry(path.toFile(), directory ? name + "/" : name);
                entry.setMode(directory || Files.isExecutable(path) ? 0755 : 0644);
                tar.putArchiveEntry(entry);
                if (!directory) {
                    Files.copy(path, tar);
                }
                tar.closeArchiveEntry();
            }
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            LOG.debugv(e, "Could not remove the unpacked code at {0}", dir);
        }
    }

    private void awaitListening(String containerId) {
        long deadline = System.nanoTime() + LISTEN_TIMEOUT.toNanos();
        while (true) {
            Optional<Long> exitCode = exitCodeIfStopped(containerId);
            if (exitCode.isPresent()) {
                LOG.infov("The Lambda Web environment {0} exited with status {1} before it listened: {2}",
                        containerId, exitCode.get(), lastLogLines(containerId));
                throw new StartFailure(exitCode.get() == 0
                        ? LambdaWebErrorPages.RUNTIME_EXIT : LambdaWebErrorPages.INIT_CODE_ERROR);
            }
            if (isListening(containerId)) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new StartFailure(LambdaWebErrorPages.TIMEOUT_LISTEN_ERROR);
            }
            try {
                Thread.sleep(READY_POLL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StartFailure(LambdaWebErrorPages.TIMEOUT_LISTEN_ERROR);
            }
        }
    }

    /**
     * Probes from inside the container. A published port on Docker Desktop accepts a connection
     * before anything listens behind it, so a connect from the host proves nothing.
     */
    private boolean isListening(String containerId) {
        try {
            ContainerExec.Result result = ContainerExec.runMerged(lifecycleManager.getDockerClient(), containerId,
                    new String[] {"/var/lang/bin/node", "-e", LISTEN_PROBE}, LISTEN_PROBE_TIMEOUT_SECONDS);
            return !result.timedOut() && result.exitCode() == 0;
        } catch (RuntimeException e) {
            LOG.debugv(e, "Could not probe the Lambda Web environment {0}", containerId);
            return false;
        }
    }

    private String lastLogLines(String containerId) {
        StringBuilder lines = new StringBuilder();
        try {
            lifecycleManager.getDockerClient().logContainerCmd(containerId)
                    .withStdOut(true).withStdErr(true).withTail(LOG_TAIL_LINES)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            lines.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                        }
                    }).awaitCompletion(LOG_TAIL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOG.debugv(e, "Could not read the log of the Lambda Web environment {0}", containerId);
        }
        return lines.toString().strip();
    }

    private Optional<InspectContainerResponse.ContainerState> stoppedState(String containerId) {
        try {
            InspectContainerResponse.ContainerState state =
                    lifecycleManager.getDockerClient().inspectContainerCmd(containerId).exec().getState();
            return state == null || Boolean.TRUE.equals(state.getRunning()) ? Optional.empty() : Optional.of(state);
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }

    private Optional<Long> exitCodeIfStopped(String containerId) {
        try {
            lifecycleManager.getDockerClient().inspectContainerCmd(containerId).exec();
        } catch (NotFoundException e) {
            return Optional.of(-1L);
        }
        return stoppedState(containerId).map(state -> {
            Long exitCode = state.getExitCodeLong();
            return exitCode == null ? -1L : exitCode;
        });
    }

    private Integer exitStatus(String containerId) {
        try {
            return lifecycleManager.getDockerClient().waitContainerCmd(containerId)
                    .exec(new WaitContainerResultCallback())
                    .awaitStatusCode(EXIT_STATUS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            LOG.debugv(e, "Could not read the exit status of the Lambda Web environment {0}", containerId);
            return null;
        }
    }

    /**
     * A process that exits between requests is invisible to clients: the next request starts anew.
     * The attach stream ends when the process exits, and Floci times that on its own clock. The exit
     * time Docker records comes from the daemon's clock, which can be seconds off. The output is
     * dropped here. A reader that falls behind would block the app's writes.
     */
    private void watchExit(Environment environment) {
        try {
            streamingDocker.attachContainerCmd(environment.containerId)
                    .withStdOut(true).withStdErr(true).withFollowStream(true)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onComplete() {
                            super.onComplete();
                            exited(environment, System.nanoTime());
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            super.onError(throwable);
                            LOG.debugv(throwable, "The exit watch of the Lambda Web environment {0} dropped",
                                    environment.containerId);
                            rearmExitWatch(environment);
                        }
                    });
        } catch (RuntimeException e) {
            LOG.debugv(e, "Could not watch the exit of the Lambda Web environment {0}", environment.containerId);
            rearmExitWatch(environment);
        }
    }

    private void rearmExitWatch(Environment environment) {
        if (!environment.isAlive() || reaper.isShutdown()) {
            return;
        }
        reaper.schedule(() -> {
            if (!environment.isAlive()) {
                return;
            }
            Optional<InspectContainerResponse.ContainerState> stopped;
            try {
                stopped = stoppedState(environment.containerId);
            } catch (RuntimeException e) {
                stopped = Optional.empty();
            }
            if (stopped.isPresent()) {
                exited(environment, System.nanoTime());
                return;
            }
            watchExit(environment);
        }, EXIT_WATCH_REARM.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** The environment leaves the pool before waiters hear of the exit, so the request after a failed one starts anew. */
    private void exited(Environment environment, long seenAt) {
        boolean running = environment.alive.compareAndSet(true, false);
        if (running) {
            removeFromPool(environment);
        }
        environment.sawExit(seenAt);
        if (!running) {
            return;
        }
        // Docker can take seconds to report the status under load. The reaper stays free to rearm dropped watches.
        Thread.ofVirtual().name("lambda-web-exit").start(() -> {
            try {
                LOG.infov("The Lambda Web environment {0} exited with status {1}: {2}",
                        environment.containerId, exitStatus(environment.containerId),
                        lastLogLines(environment.containerId));
                lifecycleManager.stopAndRemove(environment.containerId, null, 0);
            } finally {
                forgetCredentials(environment);
            }
        });
    }
}
