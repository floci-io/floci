package io.floci.aws.services.lambda.launcher;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.services.iam.model.SessionCreds;
import io.floci.aws.services.lambda.LambdaLogDestinations;
import io.floci.aws.services.lambda.model.ContainerState;
import io.floci.aws.services.lambda.model.LambdaFunction;
import io.floci.aws.services.lambda.runtime.RuntimeApiServer;
import io.floci.aws.services.lambda.runtime.RuntimeApiServerFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs each Lambda execution environment as a process on the host Floci runs on, following the
 * custom runtime contract: the package's {@code bootstrap} starts in the package directory with
 * {@code AWS_LAMBDA_RUNTIME_API}, {@code _HANDLER}, {@code LAMBDA_TASK_ROOT} and the function's
 * environment, and polls the Runtime API server Floci started for it.
 *
 * <p>Only {@code provided.*} runtimes from a .zip package, or a hot-reload directory, are served.
 * The process has no isolation and no memory limit: it runs with the permissions of the user Floci
 * runs as, so this executor is meant for local development on a machine without Docker.
 */
@ApplicationScoped
@Typed(ProcessLauncher.class)
public class ProcessLauncher implements LambdaRuntimeLauncher {

    private static final Logger LOG = Logger.getLogger(ProcessLauncher.class);

    private static final String LOOPBACK = "127.0.0.1";

    /** How long a stopped environment's process gets to exit before it is killed, and then to die. */
    private static final long STOP_GRACE_SECONDS = 2;

    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final RuntimeApiServerFactory runtimeApiServerFactory;
    private final LaunchedContainerAwsEnv awsEnv;
    private final ContainerLogStreamer logStreamer;
    private final LambdaExecutionRoleCredentials executionRoleCredentials;
    private final String flociEndpoint;
    private final Map<String, Process> processes = new ConcurrentHashMap<>();

    @Inject
    public ProcessLauncher(EmulatorConfig config,
                           RegionResolver regionResolver,
                           RuntimeApiServerFactory runtimeApiServerFactory,
                           LaunchedContainerAwsEnv awsEnv,
                           ContainerLogStreamer logStreamer,
                           LambdaExecutionRoleCredentials executionRoleCredentials,
                           @ConfigProperty(name = "quarkus.http.host", defaultValue = "0.0.0.0") String flociHttpHost,
                           @ConfigProperty(name = "quarkus.http.port") int flociHttpPort) {
        this.config = config;
        this.regionResolver = regionResolver;
        this.runtimeApiServerFactory = runtimeApiServerFactory;
        this.awsEnv = awsEnv;
        this.logStreamer = logStreamer;
        this.executionRoleCredentials = executionRoleCredentials;
        this.flociEndpoint = "http://" + connectHost(flociHttpHost) + ":" + flociHttpPort;
    }

    /** The address a process on this machine reaches Floci's HTTP listener on. */
    private static String connectHost(String bindHost) {
        String host = bindHost.strip();
        if (host.isEmpty() || host.equals("0.0.0.0") || host.equals("::") || host.equals("[::]")) {
            return LOOPBACK;
        }
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    @Override
    public ContainerHandle launch(LambdaFunction fn) {
        Path taskRoot = taskRoot(fn);
        Path bootstrap = taskRoot.resolve("bootstrap");
        requireExecutableBootstrap(fn, bootstrap);
        LOG.infov("Launching process for function: {0}", fn.getFunctionName());

        RuntimeApiServer runtimeApiServer = runtimeApiServerFactory.create();
        runtimeApiServer.setFunctionMetadata(fn.getFunctionName(), fn.getVersion(), fn.getHandler(),
                fn.getAccountId());

        Process process = null;
        Optional<SessionCreds> roleCredentials = Optional.empty();
        String sessionAccountId = LambdaExecutionRoleCredentials.sessionAccountId(fn);
        try {
            String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            String region = regionResolver.regionOf(fn.getFunctionArn());
            String accountId = AwsArnUtils.accountOrDefault(fn.getFunctionArn(), config.defaultAccountId());
            String logGroup = LambdaLogDestinations.logGroup(fn);
            String logStream = logStreamer.generateLogStreamName(LambdaLogDestinations.logStreamSuffix(fn, shortId));

            ProcessBuilder builder = new ProcessBuilder(bootstrap.toString())
                    .directory(taskRoot.toFile())
                    .redirectErrorStream(true);
            Map<String, String> environment = builder.environment();
            environment.clear();
            String path = System.getenv("PATH");
            if (path != null) {
                environment.put("PATH", path);
            }
            roleCredentials = executionRoleCredentials.forFunction(fn);
            for (String entry : environment(fn, taskRoot, runtimeApiServer, region, accountId, logGroup, logStream,
                    roleCredentials)) {
                int separator = entry.indexOf('=');
                environment.put(entry.substring(0, separator), entry.substring(separator + 1));
            }

            process = builder.start();
            process.getOutputStream().close();
            runtimeApiServer.markContainerStarted();
            process.onExit().thenAccept(exited -> runtimeApiServer.handleRuntimeProcessExited(exited.exitValue()));
            forwardOutput(process, fn.getFunctionName(), accountId, logGroup, logStream, region);

            String processId = "process-" + fn.getFunctionName() + "-" + shortId;
            processes.put(processId, process);
            return new ContainerHandle(processId, fn.getFunctionName(), runtimeApiServer, ContainerState.WARM,
                    fn.isHotReload(), roleCredentials.map(SessionCreds::accessKeyId).orElse(null), sessionAccountId);
        } catch (IOException | RuntimeException e) {
            LOG.errorv(e, "Process launch failed for function {0}; cleaning up", fn.getFunctionName());
            boolean processGone = process == null || destroy(process);
            roleCredentials.ifPresent(credentials ->
                    executionRoleCredentials.unregister(sessionAccountId, credentials.accessKeyId()));
            try {
                runtimeApiServer.stop().get(5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException | CancellationException stopFailure) {
                LOG.debugv("Runtime API server stop failed during launch cleanup: {0}", stopFailure.getMessage());
            }
            releaseWhenGone(runtimeApiServer, process, processGone);
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("Could not start the bootstrap of function '" + fn.getFunctionName()
                    + "': " + e.getMessage(), e);
        }
    }

    @Override
    public void stop(ContainerHandle handle) {
        LOG.infov("Stopping process {0}", handle.getContainerId());
        handle.setState(ContainerState.STOPPED);
        RuntimeApiServer server = handle.getRuntimeApiServer();
        // Quiesce first, so the exit this stop causes is not reported as a runtime crash.
        server.quiesce();
        Process process = processes.remove(handle.getContainerId());
        boolean processGone = process == null || destroy(process);
        executionRoleCredentials.unregister(handle.getExecutionRoleSessionAccountId(),
                handle.getExecutionRoleAccessKeyId());
        try {
            server.close().get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | CancellationException e) {
            LOG.warnv(e, "RuntimeApiServer did not close cleanly for process {0}", handle.getContainerId());
        }
        if (!processGone) {
            LOG.warnv("Process {0} did not exit yet; its Runtime API port is released once it does",
                    handle.getContainerId());
        }
        releaseWhenGone(server, process, processGone);
    }

    /**
     * Returns the Runtime API port to the pool once the process is gone. A process that is still alive
     * may still poll the port, so it is released only when that process exits.
     */
    private void releaseWhenGone(RuntimeApiServer server, Process process, boolean processGone) {
        if (processGone) {
            runtimeApiServerFactory.release(server);
        } else {
            process.onExit().thenRun(() -> runtimeApiServerFactory.release(server));
        }
    }

    @Override
    public boolean isAlive(ContainerHandle handle) {
        Process process = processes.get(handle.getContainerId());
        return process != null && process.isAlive();
    }

    private static Path taskRoot(LambdaFunction fn) {
        if ("Image".equals(fn.getPackageType())) {
            throw new RuntimeException("Function '" + fn.getFunctionName() + "' is a container image; the process "
                    + "Lambda executor runs .zip packages only. Use the docker or kubernetes executor.");
        }
        if (fn.getRuntime() == null || !fn.getRuntime().startsWith("provided")) {
            throw new RuntimeException("Function '" + fn.getFunctionName() + "' uses runtime " + fn.getRuntime()
                    + "; the process Lambda executor runs provided.* runtimes only.");
        }
        if (fn.getLayers() != null && !fn.getLayers().isEmpty()) {
            throw new RuntimeException("Function '" + fn.getFunctionName() + "' has layers, which the process "
                    + "Lambda executor does not support.");
        }
        String location = fn.isHotReload() ? fn.getHotReloadHostPath() : fn.getCodeLocalPath();
        if (location == null) {
            throw new RuntimeException("No code location for function '" + fn.getFunctionName()
                    + "' (function has no deployed code)");
        }
        Path taskRoot = Path.of(location).toAbsolutePath().normalize();
        if (!Files.isDirectory(taskRoot)) {
            throw new RuntimeException("Code directory not found for function '" + fn.getFunctionName() + "': "
                    + taskRoot);
        }
        return taskRoot;
    }

    /**
     * Floci extracts a .zip package without its file modes, so its own copy of {@code bootstrap} is made
     * executable here, as the docker executor's copy into the container does. A hot-reload directory
     * belongs to the user and is used as it is.
     */
    private static void requireExecutableBootstrap(LambdaFunction fn, Path bootstrap) {
        if (!Files.isRegularFile(bootstrap)) {
            throw new RuntimeException("Function '" + fn.getFunctionName() + "' has no bootstrap at " + bootstrap);
        }
        if (!fn.isHotReload() && !Files.isExecutable(bootstrap)) {
            try {
                Set<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(bootstrap));
                permissions.add(PosixFilePermission.OWNER_EXECUTE);
                Files.setPosixFilePermissions(bootstrap, permissions);
            } catch (IOException e) {
                throw new RuntimeException("Could not make " + bootstrap + " executable: " + e.getMessage(), e);
            }
        }
        if (!Files.isExecutable(bootstrap)) {
            throw new RuntimeException("The bootstrap of function '" + fn.getFunctionName() + "' is not executable: "
                    + bootstrap);
        }
    }

    private List<String> environment(LambdaFunction fn, Path taskRoot, RuntimeApiServer runtimeApiServer,
                                     String region, String accountId, String logGroup, String logStream,
                                     Optional<SessionCreds> roleCredentials) {
        List<String> env = LambdaRuntimeEnv.of(fn, LOOPBACK + ":" + runtimeApiServer.getPort(), logGroup, logStream);
        env.add("LAMBDA_TASK_ROOT=" + taskRoot);
        env.addAll(awsEnv.sdkBaselineEnv(region, Optional.empty(), flociEndpoint, roleCredentials, accountId));
        if (fn.getEnvironment() != null) {
            // Same credential rule as the docker launcher: an execution role session wins, and otherwise
            // the function's own credentials apply only as a full triad.
            boolean userCredentialsApply = roleCredentials.isEmpty()
                    && ContainerLauncher.definesFullCredentialTriad(fn.getEnvironment());
            fn.getEnvironment().forEach((key, value) -> {
                if (!ContainerLauncher.isAwsCredentialVariable(key) || userCredentialsApply) {
                    env.add(key + "=" + value);
                }
            });
        }
        env.removeIf(value -> value.startsWith("AWS_LAMBDA_FUNCTION_VERSION="));
        env.add("AWS_LAMBDA_FUNCTION_VERSION=" + fn.getVersion());
        return env;
    }

    /**
     * Reads the process's output until it ends, to Floci's log and the function's CloudWatch stream.
     * The reader always starts: a pipe nobody drains blocks the process once its buffer fills.
     */
    private void forwardOutput(Process process, String functionName, String accountId, String logGroup,
                               String logStream, String region) {
        // A platform thread: a virtual thread blocked on a pipe read keeps its carrier, and enough of
        // them would stall every other virtual thread in Floci.
        Thread.ofPlatform().daemon().name("lambda-process-logs-" + functionName).start(() -> {
            logStreamer.ensureLogGroupAndStreamForAccount(accountId, logGroup, logStream, region);
            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    String trimmed = line.stripTrailing();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    LOG.infov("[lambda:{0}] {1}", functionName, trimmed);
                    logStreamer.streamToCloudWatchLogsForAccount(accountId, logGroup, logStream, region, trimmed);
                }
            } catch (IOException e) {
                LOG.debugv("Output of the {0} process ended: {1}", functionName, e.getMessage());
            }
        });
    }

    /** Stops the process and its children, returning whether the process is confirmed gone. */
    private static boolean destroy(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (process.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS) && allExited(descendants)) {
                return true;
            }
            descendants.forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            return process.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            descendants.forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            return !process.isAlive();
        }
    }

    /** Waits out the stop grace period for processes that were already asked to stop. */
    private static boolean allExited(List<ProcessHandle> handles) throws InterruptedException {
        CompletableFuture<?>[] exits = handles.stream().map(ProcessHandle::onExit).toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(exits).get(STOP_GRACE_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
    }
}
