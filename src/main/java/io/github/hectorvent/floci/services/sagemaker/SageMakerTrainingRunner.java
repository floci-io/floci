package io.github.hectorvent.floci.services.sagemaker;

import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ApplicationScoped
public class SageMakerTrainingRunner implements ContainerTeardown {
    private static final Logger LOG = Logger.getLogger(SageMakerTrainingRunner.class);
    private static final String LOG_GROUP = "/aws/sagemaker/TrainingJobs";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final EmulatorConfig config;
    private final ContainerDetector containerDetector;
    private final S3Service s3Service;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, String> containers = new ConcurrentHashMap<>();

    @Inject
    public SageMakerTrainingRunner(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                   ContainerLogStreamer logStreamer, EmulatorConfig config,
                                   ContainerDetector containerDetector, S3Service s3Service, ObjectMapper mapper) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.config = config;
        this.containerDetector = containerDetector;
        this.s3Service = s3Service;
        this.mapper = mapper;
    }

    public void runAsync(TrainingJobResource job, SageMakerService service) {
        executor.submit(() -> run(job, service));
    }

    private void run(TrainingJobResource job, SageMakerService service) {
        String containerId = null;
        Closeable logs = null;
        try {
            String image = SageMakerEndpointManager.string(job.algorithmSpecification.get("TrainingImage"));
            String name = ContainerStorageHelper.dockerName(config, "floci-sagemaker-training-" + job.trainingJobName);
            lifecycleManager.removeIfExists(name);
            ContainerBuilder.Builder builder = containerBuilder.newContainer(image)
                    .withName(name)
                    .withEnv(environment(job))
                    .withDockerNetwork(config.services().sagemaker().dockerNetwork())
                    .withHostDockerInternalOnLinux()
                    .withEmbeddedDns()
                    .withLogRotation();
            SageMakerEndpointManager.applyEntrypoint(builder, job.algorithmSpecification, "train");
            ContainerSpec spec = builder.build();
            containerId = lifecycleManager.create(spec);
            containers.put(job.trainingJobName, containerId);
            job.containerId = containerId;
            copyTrainingInput(containerId, job);
            lifecycleManager.startCreated(containerId, spec);
            job.trainingStartTime = System.currentTimeMillis();
            job.secondaryStatus = "Training";
            service.updateTrainingJob(job);
            logs = logStreamer.attach(containerId, LOG_GROUP, job.trainingJobName + "/algo-1", job.region,
                    "sagemaker:" + job.trainingJobName);
            Integer exit = waitForExit(containerId, timeout(job));
            if (exit == null) {
                lifecycleManager.stopAndRemove(containerId, logs);
                job.trainingJobStatus = "Failed";
                job.secondaryStatus = "Failed";
                job.failureReason = "Training job exceeded StoppingCondition.MaxRuntimeInSeconds";
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
            containers.remove(job.trainingJobName);
            job.trainingEndTime = System.currentTimeMillis();
            service.updateTrainingJob(job);
        } catch (Exception e) {
            LOG.warnv("SageMaker training job {0} failed: {1}", job.trainingJobName, e.getMessage());
            if (containerId != null) {
                lifecycleManager.stopAndRemove(containerId, logs);
            }
            containers.remove(job.trainingJobName);
            job.trainingJobStatus = "Failed";
            job.secondaryStatus = "Failed";
            job.failureReason = e.getMessage();
            job.trainingEndTime = System.currentTimeMillis();
            service.updateTrainingJob(job);
        }
    }

    public void stop(TrainingJobResource job) {
        String id = job.containerId != null ? job.containerId : containers.remove(job.trainingJobName);
        if (id != null) {
            lifecycleManager.stopAndRemove(id, null);
        }
    }

    @Override
    public void stopManagedContainers() {
        containers.forEach((name, id) -> lifecycleManager.stopAndRemove(id, null));
        containers.clear();
        executor.shutdownNow();
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

    private void copyTrainingInput(String containerId, TrainingJobResource job) throws Exception {
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
        lifecycleManager.getDockerClient().copyArchiveToContainerCmd(containerId)
                .withRemotePath("/")
                .withTarInputStream(new ByteArrayInputStream(out.toByteArray()))
                .exec();
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
        List<S3Object> objects = uri.key().isBlank()
                ? s3Service.listObjects(uri.bucket(), "", null, 1000)
                : s3Service.listObjects(uri.bucket(), uri.key(), null, 1000);
        for (S3Object object : objects) {
            S3Object full = s3Service.getObject(uri.bucket(), object.getKey());
            String base = object.getKey().substring(object.getKey().lastIndexOf('/') + 1);
            if (!base.isBlank()) {
                SageMakerEndpointManager.addFile(tar, "opt/ml/input/data/" + channelName + "/" + base, full.getData());
            }
        }
    }

    private Integer waitForExit(String containerId, Duration timeout) throws InterruptedException {
        long deadline = timeout.isZero() ? Long.MAX_VALUE : System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Integer exit = getExitCodeIfStopped(containerId);
            if (exit != null) {
                return exit;
            }
            Thread.sleep(500);
        }
        return null;
    }

    private Integer getExitCodeIfStopped(String containerId) {
        try {
            var inspect = lifecycleManager.getDockerClient().inspectContainerCmd(containerId).exec();
            if (Boolean.TRUE.equals(inspect.getState().getRunning())) {
                return null;
            }
            Long exit = inspect.getState().getExitCodeLong();
            return exit == null ? 0 : exit.intValue();
        } catch (NotFoundException e) {
            return 1;
        }
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
                    }).awaitCompletion(Duration.ofSeconds(2).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
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
