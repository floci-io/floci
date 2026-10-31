package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.RetryingTarCopier;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.TrainingJobResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class SageMakerTrainingRunner implements ContainerTeardown, Resettable {
    private static final Logger LOG = Logger.getLogger(SageMakerTrainingRunner.class);
    private static final String LOG_GROUP = "/aws/sagemaker/TrainingJobs";
    // StoppingCondition: past MaxRuntimeInSeconds the algorithm gets SIGTERM and 120 seconds to save its model.
    private static final int MAX_RUNTIME_STOP_GRACE_SECONDS = 120;
    private static final Duration EXIT_POLL_INTERVAL = Duration.ofMillis(500);

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final EmulatorConfig config;
    private final ContainerDetector containerDetector;
    private final S3Service s3Service;
    private final ObjectMapper mapper;
    private final SageMakerGpuResolver gpuResolver;
    private final Duration maxRuntimeStopGrace;
    private final Duration exitPollInterval;
    // Replaced by afterReset() after a state reset, whose container teardown shuts this pool down.
    private volatile ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, String> containers = new ConcurrentHashMap<>();
    // Keys a stop that stop() has already committed to the store as "Stopped": run()'s exit-code
    // handling checks this before persisting, since a stop races the removal of the very container
    // waitForExit() is polling, and the container disappearing must not be relabeled a training
    // failure.
    private final Set<String> stopRequested = ConcurrentHashMap.newKeySet();

    @Inject
    public SageMakerTrainingRunner(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                   ContainerLogStreamer logStreamer, EmulatorConfig config,
                                   ContainerDetector containerDetector, S3Service s3Service, ObjectMapper mapper,
                                   SageMakerGpuResolver gpuResolver) {
        this(containerBuilder, lifecycleManager, logStreamer, config, containerDetector, s3Service, mapper,
                gpuResolver, Duration.ofSeconds(MAX_RUNTIME_STOP_GRACE_SECONDS), EXIT_POLL_INTERVAL);
    }

    SageMakerTrainingRunner(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                            ContainerLogStreamer logStreamer, EmulatorConfig config,
                            ContainerDetector containerDetector, S3Service s3Service, ObjectMapper mapper,
                            SageMakerGpuResolver gpuResolver, Duration maxRuntimeStopGrace,
                            Duration exitPollInterval) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.config = config;
        this.containerDetector = containerDetector;
        this.s3Service = s3Service;
        this.mapper = mapper;
        this.gpuResolver = gpuResolver;
        this.maxRuntimeStopGrace = maxRuntimeStopGrace;
        this.exitPollInterval = exitPollInterval;
    }

    public void runAsync(TrainingJobResource job, SageMakerService service) {
        runAsync(job, service, RetryingTarCopier.DEFAULT_BACKOFF_MS);
    }

    void runAsync(TrainingJobResource job, SageMakerService service, long backoffMillis) {
        executor.submit(() -> RequestScopes.runAs(job.accountId, job.region, () -> run(job, service, backoffMillis)));
    }

    private void run(TrainingJobResource job, SageMakerService service, long backoffMillis) {
        String key = key(job);
        String containerId = null;
        Closeable logs = null;
        try {
            String image = SageMakerEndpointManager.string(job.algorithmSpecification.get("TrainingImage"));
            String name = SageMakerEndpointManager.containerName(config, "training", job.accountId, job.region,
                    job.trainingJobName);
            lifecycleManager.removeIfExists(name);
            ContainerBuilder.Builder builder = containerBuilder.newContainer(image)
                    .withName(name)
                    .withEnv(environment(job))
                    .withDockerNetwork(config.services().sagemaker().dockerNetwork())
                    .withHostDockerInternalOnLinux()
                    .withEmbeddedDns()
                    .withLogRotation();
            SageMakerEndpointManager.applyEntrypoint(builder, job.algorithmSpecification, "train");
            // Throws when the requested instance type asks for hardware this host cannot
            // stand in for, so the job fails with a reason rather than quietly training
            // on CPU and producing an artifact that looks legitimate.
            gpuResolver.applyTo(builder, instanceType(job), instanceCount(job));
            ContainerSpec spec = builder.build();
            containerId = lifecycleManager.create(spec);
            containers.put(key, containerId);
            job.containerId = containerId;
            copyTrainingInput(containerId, job, backoffMillis);
            lifecycleManager.startCreated(containerId, spec);
            job.trainingStartTime = System.currentTimeMillis();
            job.secondaryStatus = "Training";
            service.updateTrainingJob(job);
            logs = logStreamer.attachForAccount(job.accountId, containerId, LOG_GROUP, job.trainingJobName + "/algo-1",
                    job.region, "sagemaker:" + job.trainingJobName);
            Integer exit = waitForExit(containerId, timeout(job));
            if (stopRequested.remove(key)) {
                // stop() already persisted Stopped and is tearing the container down itself;
                // whatever exit code we just observed (or failed to) is a side effect of that,
                // not a real outcome to report.
                containers.remove(key);
                return;
            }
            if (exit == null) {
                // SageMaker sends SIGTERM and waits so the algorithm can save its model, then uploads
                // whatever it saved: the container must outlive the stop for that upload.
                long graceDeadline = System.nanoTime() + maxRuntimeStopGrace.toNanos();
                try {
                    lifecycleManager.getDockerClient().stopContainerCmd(containerId)
                            .withTimeout(Math.toIntExact(maxRuntimeStopGrace.toSeconds())).exec();
                } catch (NotModifiedException alreadyStopped) {
                    LOG.debugv("SageMaker training container {0} exited before its runtime stop (304)", containerId);
                } catch (RuntimeException stopFailed) {
                    // The Docker client gave up (a Docker API timeout below the grace window) while the
                    // container may still be saving its model; the job still ran out of time.
                    LOG.warnv("SageMaker training container {0} did not confirm its runtime stop: {1}",
                            containerId, stopFailed.getMessage());
                    awaitExitOrKill(containerId, graceDeadline);
                }
                job.modelArtifactsS3ModelArtifacts = uploadModelArtifacts(containerId, job);
                lifecycleManager.stopAndRemove(containerId, logs);
                job.trainingJobStatus = "Stopped";
                job.secondaryStatus = "MaxRuntimeExceeded";
            } else if (exit == 0) {
                job.modelArtifactsS3ModelArtifacts = uploadModelArtifacts(containerId, job);
                lifecycleManager.stopAndRemove(containerId, logs);
                job.trainingJobStatus = "Completed";
                job.secondaryStatus = "Completed";
            } else {
                job.failureReason = readFailure(containerId, exit);
                lifecycleManager.stopAndRemove(containerId, logs);
                job.trainingJobStatus = "Failed";
                job.secondaryStatus = "Failed";
            }
            containers.remove(key);
            job.trainingEndTime = System.currentTimeMillis();
            service.updateTrainingJob(job);
        } catch (Exception e) {
            if (stopRequested.remove(key)) {
                containers.remove(key);
                return;
            }
            LOG.warnv("SageMaker training job {0} failed: {1}", job.trainingJobName, e.getMessage());
            if (containerId != null) {
                lifecycleManager.stopAndRemove(containerId, logs);
            }
            containers.remove(key);
            job.trainingJobStatus = "Failed";
            job.secondaryStatus = "Failed";
            job.failureReason = e.getMessage();
            job.trainingEndTime = System.currentTimeMillis();
            service.updateTrainingJob(job);
            if (e instanceof InterruptedException) {
                // Interrupted by a teardown's shutdownNow(): keep the flag for the pool thread.
                Thread.currentThread().interrupt();
            }
        }
    }

    public void stop(TrainingJobResource job) {
        stopRequested.add(key(job));
        String id = job.containerId != null ? job.containerId : containers.remove(key(job));
        if (id != null) {
            lifecycleManager.stopAndRemove(id, null);
        }
    }

    private static String key(TrainingJobResource job) {
        return SageMakerEndpointManager.resourceKey(job.accountId, job.region, job.trainingJobName);
    }

    @Override
    public synchronized void stopManagedContainers() {
        containers.forEach((name, id) -> lifecycleManager.stopAndRemove(id, null));
        containers.clear();
        executor.shutdownNow();
    }

    @Override
    public synchronized void clear() {
        stopRequested.clear();
    }

    /**
     * Runs at the end of every state reset, never on shutdown. The teardown shut the worker
     * pool down, so without a new one every later CreateTrainingJob would be rejected until
     * the emulator restarted. This hook rather than {@code clear()} because the controller
     * runs it even when the storage wipe or another service's {@code clear()} threw, and a
     * failed reset must not leave the pool terminated for good.
     */
    @Override
    public synchronized void afterReset() {
        if (executor.isShutdown()) {
            executor = Executors.newCachedThreadPool();
        }
    }

    boolean acceptsWork() {
        return !executor.isShutdown();
    }

    private List<String> environment(TrainingJobResource job) {
        List<String> env = new ArrayList<>();
        env.add("TRAINING_JOB_NAME=" + job.trainingJobName);
        env.add("TRAINING_JOB_ARN=" + job.trainingJobArn);
        env.add("SAGEMAKER_REGION=" + job.region);
        env.add("AWS_REGION=" + job.region);
        env.add("AWS_DEFAULT_REGION=" + job.region);
        env.add("AWS_ACCESS_KEY_ID=test");
        env.add("AWS_SECRET_ACCESS_KEY=test");
        env.add("AWS_SESSION_TOKEN=test");
        String endpoint = "http://" + resolveEndpointHostname() + ":" + config.port();
        env.add("AWS_ENDPOINT_URL=" + endpoint);
        env.add("FLOCI_ENDPOINT=" + endpoint);
        return env;
    }

    private String resolveEndpointHostname() {
        return containerDetector.isRunningInContainer() ? config.hostname().orElse(EmbeddedDnsServer.DEFAULT_SUFFIX) : "host.docker.internal";
    }

    void copyTrainingInput(String containerId, TrainingJobResource job, long backoffMillis) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            SageMakerEndpointManager.addFile(tar, "opt/ml/input/config/hyperparameters.json",
                    mapper.writeValueAsBytes(job.hyperParameters));
            SageMakerEndpointManager.addFile(tar, "opt/ml/input/config/resourceconfig.json",
                    "{\"current_host\":\"algo-1\",\"hosts\":[\"algo-1\"]}".getBytes(StandardCharsets.UTF_8));
            SageMakerEndpointManager.addFile(tar, "opt/ml/input/config/inputdataconfig.json",
                    mapper.writeValueAsBytes(inputDataConfig(job.inputDataConfig)));
            for (Map<String, Object> channel : job.inputDataConfig) {
                copyChannelObjects(tar, channel);
            }
        }
        byte[] tarData = out.toByteArray();
        RetryingTarCopier.copyStreamed(lifecycleManager.getDockerClient(), containerId, "/",
                "sagemaker-training-input", stream -> stream.write(tarData),
                RetryingTarCopier.DEFAULT_MAX_ATTEMPTS, backoffMillis);
    }

    private Map<String, Object> inputDataConfig(List<Map<String, Object>> channels) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> c : channels) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("ContentType", c.getOrDefault("ContentType", "application/octet-stream"));
            value.put("TrainingInputMode", c.getOrDefault("TrainingInputMode", "File"));
            out.put(String.valueOf(c.get("ChannelName")), value);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void copyChannelObjects(TarArchiveOutputStream tar, Map<String, Object> channel) throws Exception {
        String channelName = String.valueOf(channel.get("ChannelName"));
        Object dataSource = channel.get("DataSource");
        if (!(dataSource instanceof Map<?, ?> ds)) {
            return;
        }
        Object s3 = ds.get("S3DataSource");
        if (!(s3 instanceof Map<?, ?> s3ds)) {
            return;
        }
        String s3Uri = String.valueOf(s3ds.get("S3Uri"));
        S3Uri uri = S3Uri.parse(s3Uri);
        String token = null;
        do {
            S3Service.ListObjectsResult page = s3Service.listObjectsWithPrefixes(uri.bucket(), uri.key(), null, 1000, token, null);
            for (S3Object object : page.objects()) {
                // A folder marker has no data, and as a file it would collide with the folder's own objects.
                if (object.getKey().endsWith("/")) {
                    continue;
                }
                S3Object full = s3Service.getObject(uri.bucket(), object.getKey());
                String relative = relativeToPrefix(uri.key(), object.getKey());
                String safeRelative = safeRelativePath(relative);
                if (safeRelative != null && !safeRelative.isBlank()) {
                    SageMakerEndpointManager.addFile(tar, "opt/ml/input/data/" + channelName + "/" + safeRelative, full.getData());
                }
            }
            token = page.isTruncated() ? page.nextContinuationToken() : null;
        } while (token != null);
    }

    /**
     * The part of an object key below the channel's S3 prefix, preserving any subdirectory
     * structure instead of flattening every object to its basename (which loses structure and
     * collides same-named objects in different "folders" under the prefix).
     */
    private static String relativeToPrefix(String prefix, String key) {
        if (prefix.isBlank()) {
            return key;
        }
        String folder = prefix.endsWith("/") ? prefix : prefix + "/";
        if (key.startsWith(folder)) {
            return key.substring(folder.length());
        }
        // The prefix matched key without a folder boundary (e.g. prefix "input" matching
        // "input.csv" itself, or a same-named sibling key equal to the prefix): fall back to
        // the basename rather than a key that still carries the shared prefix.
        return key.equals(prefix) || key.startsWith(prefix)
                ? key.substring(key.lastIndexOf('/') + 1)
                : key;
    }

    /** Rejects an absolute path or one that escapes the channel directory via "..". */
    private static String safeRelativePath(String relative) {
        Path normalized = Path.of(relative).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..") || normalized.toString().equals("..")) {
            return null;
        }
        return normalized.toString();
    }

    private Integer waitForExit(String containerId, Duration timeout) throws InterruptedException {
        long deadline = timeout.isZero() ? Long.MAX_VALUE : System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Integer exit = getExitCodeIfStopped(containerId);
            if (exit != null) {
                return exit;
            }
            Thread.sleep(exitPollInterval.toMillis());
        }
        return null;
    }

    /**
     * Waits for the container to exit until the grace window that began with the stop request ends,
     * then kills it if it is still running, so its model is read only once nothing writes to it.
     */
    private void awaitExitOrKill(String containerId, long graceDeadlineNanos) throws InterruptedException {
        while (getExitCodeIfStopped(containerId) == null) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(graceDeadlineNanos - System.nanoTime());
            if (remainingMillis <= 0) {
                LOG.warnv("SageMaker training container {0} still ran when its stop window ended; killing it",
                        containerId);
                try {
                    lifecycleManager.getDockerClient().killContainerCmd(containerId).exec();
                } catch (RuntimeException killFailed) {
                    LOG.warnv("Could not kill SageMaker training container {0}: {1}", containerId,
                            killFailed.getMessage());
                }
                return;
            }
            Thread.sleep(Math.min(exitPollInterval.toMillis(), remainingMillis));
        }
    }

    private Integer getExitCodeIfStopped(String containerId) {
        try {
            InspectContainerResponse inspect = lifecycleManager.getDockerClient().inspectContainerCmd(containerId).exec();
            if (Boolean.TRUE.equals(inspect.getState().getRunning())) {
                return null;
            }
            Long exit = inspect.getState().getExitCodeLong();
            return exit == null ? 0 : exit.intValue();
        } catch (NotFoundException e) {
            throw new IllegalStateException("Training container was removed before it exited", e);
        }
    }

    private static String instanceType(TrainingJobResource job) {
        return SageMakerEndpointManager.string(job.resourceConfig.get("InstanceType"));
    }

    /** Absent or unparseable means one, matching how a single-instance job is described. */
    private static int instanceCount(TrainingJobResource job) {
        Object value = job.resourceConfig.get("InstanceCount");
        return value instanceof Number n ? n.intValue() : 1;
    }

    private Duration timeout(TrainingJobResource job) {
        Object value = job.stoppingCondition.get("MaxRuntimeInSeconds");
        if (value instanceof Number n) {
            return Duration.ofSeconds(n.longValue());
        }
        return Duration.ZERO;
    }

    private String uploadModelArtifacts(String containerId, TrainingJobResource job) throws Exception {
        byte[] data = modelTarGz(containerId);
        S3Uri out = S3Uri.parse(String.valueOf(job.outputDataConfig.get("S3OutputPath")));
        String prefix = out.key().isBlank() ? "" : (out.key().endsWith("/") ? out.key() : out.key() + "/");
        String key = prefix + job.trainingJobName + "/output/model.tar.gz";
        s3Service.putObject(out.bucket(), key, data, "application/x-tar", Map.of());
        return "s3://" + out.bucket() + "/" + key;
    }

    private byte[] modelTarGz(String containerId) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gz = new GzipCompressorOutputStream(out);
             TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gz);
             InputStream dockerTar = lifecycleManager.getDockerClient().copyArchiveFromContainerCmd(containerId, "/opt/ml/model").exec();
             TarArchiveInputStream tarIn = new TarArchiveInputStream(dockerTar)) {
            TarArchiveEntry e;
            String prefix = null;
            while ((e = tarIn.getNextEntry()) != null) {
                if (!tarIn.canReadEntryData(e) || e.isDirectory()) continue;
                String name = e.getName();
                if (prefix == null) {
                    int slash = name.indexOf('/');
                    prefix = slash >= 0 ? name.substring(0, slash + 1) : "";
                }
                if (!prefix.isBlank() && name.startsWith(prefix)) {
                    name = name.substring(prefix.length());
                }
                if (!name.isBlank()) {
                    SageMakerEndpointManager.addFile(tarOut, name, tarIn.readAllBytes());
                }
            }
        }
        return out.toByteArray();
    }

    private String readFailure(String containerId, int exitCode) {
        StringBuilder sb = new StringBuilder();
        try {
            lifecycleManager.getDockerClient().logContainerCmd(containerId)
                    .withStdErr(true).withTail(20)
                    .exec(new LogContainerResultCallback() {
                        @Override
                        public void onNext(Frame item) {
                            sb.append(new String(item.getPayload(), StandardCharsets.UTF_8));
                        }
                    }).awaitCompletion(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            LOG.warnv("Interrupted while reading SageMaker training failure log for container {0}: {1}", containerId, e.getMessage());
            Thread.currentThread().interrupt();
            return "Container exited with code " + exitCode;
        } catch (Exception e) {
            LOG.warnv("Could not read SageMaker training failure log for container {0}: {1}", containerId, e.getMessage());
        }
        String detail = sb.toString().trim();
        return detail.isBlank() ? "Container exited with code " + exitCode : detail;
    }
}
