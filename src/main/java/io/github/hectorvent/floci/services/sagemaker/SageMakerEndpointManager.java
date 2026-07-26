package io.github.hectorvent.floci.services.sagemaker;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.EndpointResource;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.ModelResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ApplicationScoped
public class SageMakerEndpointManager implements ContainerTeardown {
    private static final Logger LOG = Logger.getLogger(SageMakerEndpointManager.class);
    private static final int PORT = 8080;
    private static final int DEFAULT_FILE_MODE = 0644;
    private static final int PING_LOG_FREQUENCY = 15;
    private static final Duration PING_TIMEOUT = Duration.ofSeconds(120);

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;
    private final ContainerDetector containerDetector;
    private final S3Service s3Service;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, String> containers = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Inject
    public SageMakerEndpointManager(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                    EmulatorConfig config, ContainerDetector containerDetector, S3Service s3Service) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
        this.containerDetector = containerDetector;
        this.s3Service = s3Service;
    }

    public void startEndpointAsync(EndpointResource endpoint, SageMakerService service) {
        executor.submit(() -> startEndpoint(endpoint, service));
    }

    private void startEndpoint(EndpointResource endpoint, SageMakerService service) {
        String containerId = null;
        try {
            ModelResource model = service.modelForEndpoint(endpoint);
            Map<String, Object> c = model.primaryContainer;
            String image = string(c.get("Image"));
            if (image == null) {
                throw new IllegalArgumentException("PrimaryContainer.Image is required");
            }
            String name = ContainerStorageHelper.dockerName(config, "floci-sagemaker-endpoint-" + endpoint.endpointName);
            lifecycleManager.removeIfExists(name);
            ContainerBuilder.Builder builder = containerBuilder.newContainer(image)
                    .withName(name)
                    .withEnv(environment(endpoint.region, map(c.get("Environment"))))
                    .withDockerNetwork(config.services().sagemaker().dockerNetwork())
                    .withHostDockerInternalOnLinux()
                    .withEmbeddedDns()
                    .withPortBinding(PORT, 0)
                    .withLogRotation();
            applyEntrypoint(builder, c, "serve");
            ContainerSpec spec = builder.build();
            containerId = lifecycleManager.create(spec);
            String modelDataUrl = string(c.get("ModelDataUrl"));
            if (modelDataUrl != null && !modelDataUrl.isBlank()) {
                copyModelData(containerId, modelDataUrl);
            }
            var info = lifecycleManager.startCreated(containerId, spec);
            var ep = info.getEndpoint(PORT);
            endpoint.containerId = containerId;
            endpoint.invokeHost = ep.host();
            endpoint.invokePort = ep.port();
            containers.put(endpoint.endpointName, containerId);
            waitForPing(endpoint.invokeHost, endpoint.invokePort);
            endpoint.endpointStatus = "InService";
            endpoint.failureReason = null;
            service.updateEndpoint(endpoint);
        } catch (Exception e) {
            LOG.warnv("SageMaker endpoint {0} failed: {1}", endpoint.endpointName, e.getMessage());
            if (containerId != null) {
                lifecycleManager.stopAndRemove(containerId, null);
            }
            endpoint.endpointStatus = "Failed";
            endpoint.failureReason = e.getMessage();
            service.updateEndpoint(endpoint);
        }
    }

    public void stopEndpoint(EndpointResource endpoint) {
        String containerId = endpoint.containerId != null ? endpoint.containerId : containers.remove(endpoint.endpointName);
        if (containerId != null) {
            lifecycleManager.stopAndRemove(containerId, null);
        }
    }

    @Override
    public void stopManagedContainers() {
        containers.forEach((name, id) -> lifecycleManager.stopAndRemove(id, null));
        containers.clear();
        executor.shutdownNow();
    }

    private List<String> environment(String region, Map<String, String> modelEnv) {
        List<String> env = new ArrayList<>();
        env.add("SAGEMAKER_BIND_TO_PORT=8080");
        env.add("SAGEMAKER_REGION=" + region);
        env.add("AWS_REGION=" + region);
        env.add("AWS_DEFAULT_REGION=" + region);
        env.add("AWS_ACCESS_KEY_ID=test");
        env.add("AWS_SECRET_ACCESS_KEY=test");
        env.add("AWS_SESSION_TOKEN=test");
        String endpoint = "http://" + resolveEndpointHostname() + ":" + config.port();
        env.add("AWS_ENDPOINT_URL=" + endpoint);
        env.add("FLOCI_ENDPOINT=" + endpoint);
        modelEnv.forEach((k, v) -> env.add(k + "=" + (v == null ? "" : v)));
        return env;
    }

    private String resolveEndpointHostname() {
        return containerDetector.isRunningInContainer() ? config.hostname().orElse(EmbeddedDnsServer.DEFAULT_SUFFIX) : "host.docker.internal";
    }

    static void applyEntrypoint(ContainerBuilder.Builder builder, Map<String, Object> container, String defaultCommand) {
        Object entrypoint = container.get("ContainerEntrypoint");
        Object arguments = container.get("ContainerArguments");
        if (entrypoint instanceof List<?> list && !list.isEmpty()) {
            builder.withEntrypoint(list.stream().map(String::valueOf).toList());
            if (arguments instanceof List<?> args) {
                builder.withCmd(args.stream().map(String::valueOf).toList());
            }
        } else if (arguments instanceof List<?> args && !args.isEmpty()) {
            builder.withCmd(args.stream().map(String::valueOf).toList());
        } else {
            builder.withCmd(List.of(defaultCommand));
        }
    }

    private void copyModelData(String containerId, String s3Uri) throws Exception {
        S3Uri uri = S3Uri.parse(s3Uri);
        S3Object object = s3Service.getObject(uri.bucket(), uri.key());
        byte[] tarData = tarModelData(object.getData(), uri.key());
        lifecycleManager.getDockerClient().copyArchiveToContainerCmd(containerId)
                .withRemotePath("/")
                .withTarInputStream(new ByteArrayInputStream(tarData))
                .exec();
    }

    private byte[] tarModelData(byte[] data, String key) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            if (key.endsWith(".tar.gz") || key.endsWith(".tgz")) {
                try (TarArchiveInputStream in = new TarArchiveInputStream(new GzipCompressorInputStream(new ByteArrayInputStream(data)))) {
                    TarArchiveEntry e;
                    while ((e = in.getNextEntry()) != null) {
                        if (!in.canReadEntryData(e) || e.isDirectory()) continue;
                        addFile(tar, "opt/ml/model/" + e.getName(), in.readAllBytes());
                    }
                }
            } else {
                addFile(tar, "opt/ml/model/" + key.substring(key.lastIndexOf('/') + 1), data);
            }
        }
        return out.toByteArray();
    }

    static void addFile(TarArchiveOutputStream tar, String path, byte[] data) throws Exception {
        TarArchiveEntry entry = new TarArchiveEntry(path);
        entry.setSize(data.length);
        entry.setMode(DEFAULT_FILE_MODE);
        tar.putArchiveEntry(entry);
        tar.write(data);
        tar.closeArchiveEntry();
    }

    private void waitForPing(String host, int port) throws Exception {
        long deadline = System.currentTimeMillis() + PING_TIMEOUT.toMillis();
        URI uri = URI.create("http://" + host + ":" + port + "/ping");
        int failures = 0;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build();
                if (httpClient.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
                failures++;
            } catch (ConnectException | HttpTimeoutException e) {
                failures++;
                if (failures % PING_LOG_FREQUENCY == 0) {
                    LOG.warnv("Still waiting for SageMaker endpoint ping after {0} failures: {1}", failures, e.getMessage());
                } else {
                    LOG.debugv("Waiting for SageMaker endpoint ping: {0}", e.getMessage());
                }
            } catch (Exception e) {
                failures++;
                LOG.warnv("Waiting for SageMaker endpoint ping failed: {0}", e.getMessage());
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Container did not pass /ping health check");
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> map(Object value) {
        if (value instanceof Map<?, ?> in) {
            java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
            in.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
            return out;
        }
        return Map.of();
    }

    static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
