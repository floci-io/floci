package io.github.hectorvent.floci.services.lambda.launcher.kubernetes;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.lambda.LambdaLayerService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.launcher.ContainerHandle;
import io.github.hectorvent.floci.services.lambda.launcher.ContainerLauncher;
import io.github.hectorvent.floci.services.lambda.launcher.ImageResolver;
import io.github.hectorvent.floci.services.lambda.launcher.LambdaRuntimeLauncher;
import io.github.hectorvent.floci.services.lambda.model.ContainerState;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServerFactory;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs each Lambda execution environment as a Kubernetes pod. Mirrors
 * {@link ContainerLauncher}'s contract: the Runtime API server is started before the
 * pod so the runtime connects on boot, and any launch failure after the server is
 * allocated releases its port and reaps the half-built pod.
 *
 * <p>Pods reach Floci over the network only (no bind mounts, no host gateway):
 * an init container downloads the deployment package from Floci's S3, and the
 * runtime polls the Runtime API at the address {@link KubernetesFlociAddressResolver}
 * advertises. Hot reload is therefore unsupported here.
 */
@ApplicationScoped
@Typed(KubernetesPodLauncher.class)
public class KubernetesPodLauncher implements LambdaRuntimeLauncher {

    private static final Logger LOG = Logger.getLogger(KubernetesPodLauncher.class);

    static final String CA_CONFIG_MAP_NAME = "floci-lambda-ca";
    static final String CA_CONFIG_MAP_KEY = "ca.crt";

    /**
     * Generous enough for a node's first pull of a multi-hundred-MB runtime image;
     * a pod that will never run (bad image, failing init) is detected much earlier
     * via its terminal waiting/terminated states.
     */
    private static final int POD_STARTUP_TIMEOUT_SECONDS = 300;

    /**
     * How long a stop waits for a deleted pod to actually disappear before giving up
     * and keeping its Runtime API port reserved. A force delete normally clears within
     * a second; the cap only bites when the API server or kubelet is stuck.
     */
    private static final int POD_DELETE_TIMEOUT_SECONDS = 15;

    /**
     * Waiting-state reasons treated as fatal for the cold start. ErrImagePull is
     * deliberately absent: it appears on the first failed pull attempt, which the
     * kubelet retries; only the backoff state marks repeated failures.
     */
    private static final Set<String> TERMINAL_WAITING_REASONS = Set.of(
            "ImagePullBackOff", "InvalidImageName", "CrashLoopBackOff",
            "CreateContainerError", "CreateContainerConfigError", "RunContainerError");

    private final KubernetesClient client;
    private final EmulatorConfig config;
    private final RuntimeApiServerFactory runtimeApiServerFactory;
    private final ImageResolver imageResolver;
    private final KubernetesFlociAddressResolver addressResolver;
    private final LaunchedContainerAwsEnv awsEnv;
    private final LambdaLayerService layerService;
    private final LambdaPodSpecFactory podSpecFactory;
    private final KubernetesPodLogStreamer logStreamer;
    private final S3Service s3Service;

    private final Object orphanSweepLock = new Object();
    private volatile boolean orphansSwept = false;
    /** Names of pods created by this process, so a retried orphan sweep never deletes them. */
    private final Set<String> ownPodNames = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean caConfigMapApplied = new AtomicBoolean(false);
    private final AtomicBoolean awsConfigPathWarned = new AtomicBoolean(false);

    @Inject
    public KubernetesPodLauncher(KubernetesClient client,
                                 EmulatorConfig config,
                                 RuntimeApiServerFactory runtimeApiServerFactory,
                                 ImageResolver imageResolver,
                                 KubernetesFlociAddressResolver addressResolver,
                                 LaunchedContainerAwsEnv awsEnv,
                                 LambdaLayerService layerService,
                                 LambdaPodSpecFactory podSpecFactory,
                                 KubernetesPodLogStreamer logStreamer,
                                 S3Service s3Service) {
        this.client = client;
        this.config = config;
        this.runtimeApiServerFactory = runtimeApiServerFactory;
        this.imageResolver = imageResolver;
        this.addressResolver = addressResolver;
        this.awsEnv = awsEnv;
        this.layerService = layerService;
        this.podSpecFactory = podSpecFactory;
        this.logStreamer = logStreamer;
        this.s3Service = s3Service;
    }

    /**
     * Forces the lazy client bean (and its underlying Vert.x HTTP client) to be
     * built on the caller's thread. Called from the startup observer so the HTTP
     * client is not born on a request's Vert.x context, whose close at shutdown
     * would leave every later Kubernetes API call failing with "Client is closed".
     */
    public void initializeClient() {
        client.getConfiguration();
        // Resolve the address pods use to reach Floci now, so the most common
        // misconfiguration (running outside the cluster without a floci-address)
        // fails startup instead of every later cold start.
        addressResolver.resolve();
    }

    @Override
    public ContainerHandle launch(LambdaFunction fn) {
        if (fn.isHotReload()) {
            throw new RuntimeException("Hot reload requires a bind mount and is not supported by the "
                    + "kubernetes Lambda executor. Use the docker executor for hot-reload functions.");
        }
        if (config.services().lambda().awsConfigPath().filter(s -> !s.isBlank()).isPresent()
                && awsConfigPathWarned.compareAndSet(false, true)) {
            LOG.warn("floci.services.lambda.aws-config-path is a bind mount and is ignored by the "
                    + "kubernetes executor; pods receive placeholder credentials instead");
        }

        var namespace = namespace();
        sweepOrphansOnce(namespace);
        LOG.infov("Launching pod for function: {0}", fn.getFunctionName());

        var runtimeApiServer = runtimeApiServerFactory.create();

        // Mirrors ContainerLauncher: any failure after the runtime-api server is allocated
        // must release its port and delete a half-created pod, or cold-start bursts leak
        // ports until the pool is exhausted.
        String podName = null;
        try {
            var imagePackage = "Image".equals(fn.getPackageType()) && fn.getImageUri() != null;
            // No emulated-ECR rewrite here: the kubelet pulls images, and Floci's loopback
            // registry is not reachable from cluster nodes. URIs pass through unchanged.
            var image = imagePackage ? fn.getImageUri() : imageResolver.resolve(fn.getRuntime());

            var shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            podName = LambdaPodSpecFactory.podName(fn.getFunctionName(), shortId);
            var region = AwsArnUtils.regionOrDefault(fn.getFunctionArn(), config.defaultRegion());
            var cwLogGroup = "/aws/lambda/" + fn.getFunctionName();
            var cwLogStream = logStreamer.logStreamName(shortId);

            var codeDownloadUrl = imagePackage ? null : codeDownloadUrl(fn, region);
            var layerUrls = imagePackage ? List.<String>of() : layerDownloadUrls(fn, region);

            var caCert = config.tls().enabled()
                    ? ContainerLauncher.resolveFlociCaCertPath(true, config.tls().certPath(),
                            config.storage().persistentPath())
                    : Optional.<Path>empty();
            var caConfigMap = caCert.map(cert -> ensureCaConfigMap(namespace, cert));

            var env = new ArrayList<String>();
            env.add("AWS_LAMBDA_RUNTIME_API=" + addressResolver.resolve() + ":" + runtimeApiServer.getPort());
            env.add("AWS_LAMBDA_FUNCTION_NAME=" + fn.getFunctionName());
            env.add("AWS_LAMBDA_FUNCTION_MEMORY_SIZE=" + fn.getMemorySize());
            env.add("AWS_LAMBDA_FUNCTION_TIMEOUT=" + fn.getTimeout());
            env.add("AWS_LAMBDA_FUNCTION_VERSION=$LATEST");
            env.add("AWS_LAMBDA_LOG_GROUP_NAME=" + cwLogGroup);
            env.add("AWS_LAMBDA_LOG_STREAM_NAME=" + cwLogStream);
            if (fn.getHandler() != null && !fn.getHandler().isBlank()) {
                env.add("_HANDLER=" + fn.getHandler());
            }
            env.addAll(awsEnv.sdkBaselineEnv(region, Optional.empty(), addressResolver.flociBaseUrl(),
                    Optional.empty(), AwsArnUtils.accountOrDefault(fn.getFunctionArn(), config.defaultAccountId())));
            env.addAll(ContainerLauncher.flociCaEnv(caCert));
            if (fn.getEnvironment() != null) {
                // Same all-or-nothing rule as ContainerLauncher: this launcher never has
                // execution-role credentials, so the function's own Environment may only supply
                // AWS credential vars when it defines the full triad — a partial set must never
                // join the owner-account baseline and split its credential tuple.
                boolean userDefinesFullCredentialTriad =
                        ContainerLauncher.definesFullCredentialTriad(fn.getEnvironment());
                fn.getEnvironment().forEach((k, v) -> {
                    if (!ContainerLauncher.isAwsCredentialVariable(k) || userDefinesFullCredentialTriad) {
                        env.add(k + "=" + v);
                    }
                });
            }

            var imageConfig = imagePackage
                    ? new LambdaPodSpecFactory.ImageConfig(fn.getImageConfigEntryPoint(),
                            fn.getImageConfigCommand(), fn.getImageConfigWorkingDirectory())
                    : null;

            var pod = podSpecFactory.buildPod(podName, fn.getFunctionName(), image, env,
                    codeDownloadUrl, layerUrls, isProvidedRuntime(fn.getRuntime()),
                    imagePackage ? null : fn.getHandler(), imageConfig, fn.getMemorySize(), caConfigMap);

            ownPodNames.add(podName);
            client.pods().inNamespace(namespace).resource(pod).create();
            LOG.infov("Created pod {0} for function {1}", podName, fn.getFunctionName());

            awaitRunning(namespace, podName, fn.getFunctionName());

            var handle = new ContainerHandle(podName, fn.getFunctionName(),
                    runtimeApiServer, ContainerState.WARM, false);
            // Log streaming is non-essential and runs after the pod is already Running,
            // so a failure here (e.g. a missing pods/log RBAC verb) must not tear down a
            // healthy execution environment.
            try {
                var logHandle = logStreamer.attach(namespace, podName, cwLogGroup, cwLogStream,
                        region, "lambda:" + fn.getFunctionName());
                handle.setLogStream(logHandle);
            } catch (Exception logFailure) {
                LOG.warnv("Log streaming for pod {0} could not start; the environment still "
                        + "runs but its logs will not reach CloudWatch: {1}", podName,
                        logFailure.getMessage());
            }
            return handle;
        } catch (RuntimeException e) {
            LOG.errorv(e, "Pod launch failed for function {0}; cleaning up", fn.getFunctionName());
            boolean podGone = podName == null || deletePod(namespace, podName);
            // Stop the server before releasing its port number, or the still-listening
            // Vert.x server makes the port unusable for every later cold start.
            try {
                runtimeApiServer.stop().get(5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception stopFailure) {
                LOG.debugv("Runtime API server stop failed during launch cleanup: {0}",
                        stopFailure.getMessage());
            }
            // Same rule as stop(): the port number goes back to the pool only once the pod
            // is confirmed gone. A half-created pod that later reaches Running polls
            // AWS_LAMBDA_RUNTIME_API on this port, and a released port could by then be
            // serving a different environment.
            if (podGone) {
                try {
                    runtimeApiServerFactory.release(runtimeApiServer);
                } catch (Exception ignore) {
                    LOG.debugv("Runtime API port release failed during launch cleanup: {0}", ignore.getMessage());
                }
            } else {
                LOG.warnv("Keeping the Runtime API port reserved for pod {0} because its delete "
                        + "did not succeed.", podName);
            }
            throw e;
        }
    }

    @Override
    public void stop(ContainerHandle handle) {
        LOG.infov("Stopping pod {0}", handle.getContainerId());
        handle.setState(ContainerState.STOPPED);
        try {
            handle.getRuntimeApiServer().stop().get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | CancellationException e) {
            // CancellationException is unchecked; catch it here so a cancelled stop future
            // cannot skip the log-stream close, pod deletion, and port release below.
            LOG.warnv(e, "RuntimeApiServer did not close cleanly for pod {0}", handle.getContainerId());
        }
        if (handle.getLogStream() != null) {
            try {
                handle.getLogStream().close();
            } catch (Exception e) {
                LOG.debugv("Closing log stream for pod {0} failed: {1}",
                        handle.getContainerId(), e.getMessage());
            }
        }
        // Return the Runtime API port to the pool only once the pod is gone. If the delete
        // did not succeed, the pod's runtime may still be polling that port, and reusing it
        // for another cold start could hand that runtime a different function's invocation,
        // so keep the port reserved until a later sweep reaps the pod.
        if (deletePod(namespace(), handle.getContainerId())) {
            try {
                runtimeApiServerFactory.release(handle.getRuntimeApiServer());
            } catch (Exception e) {
                LOG.warnv("Runtime API port release failed for pod {0}: {1}",
                        handle.getContainerId(), e.getMessage());
            }
        } else {
            LOG.warnv("Keeping the Runtime API port reserved for pod {0} because its delete "
                    + "did not succeed.", handle.getContainerId());
        }
    }

    @Override
    public boolean isAlive(ContainerHandle handle) {
        try {
            var pod = client.pods().inNamespace(namespace()).withName(handle.getContainerId()).get();
            return pod != null
                    && pod.getStatus() != null
                    && "Running".equals(pod.getStatus().getPhase())
                    && pod.getMetadata().getDeletionTimestamp() == null;
        } catch (Exception e) {
            // A missing pod comes back as null above; reaching here means the API server
            // itself was unreachable. Unlike docker's local socket the API server is remote,
            // so a transient blip must not read as "dead" — that would cull the whole warm
            // pool at once. Assume alive; a genuinely dead pod fails the next invocation.
            LOG.warnv("Liveness probe for pod {0} could not reach the API server, assuming "
                    + "alive: {1}", handle.getContainerId(), e.getMessage());
            return true;
        }
    }

    private String namespace() {
        return config.services().lambda().kubernetes().namespace();
    }

    /**
     * Deletes pods left behind by a previous Floci process. They can never be adopted:
     * their runtimes poll Runtime API ports that died with that process.
     *
     * <p>Every launch blocks here until one sweep has succeeded; a failed sweep is
     * retried by the next launch. Launches proceed after a failed sweep and their
     * pods carry the same labels, so a retried sweep skips every name in
     * {@link #ownPodNames} and deletion targets the listed pod names rather than
     * the label selector. Both are required so a retried or concurrent sweep can
     * never kill a fresh pod of this process.
     */
    private void sweepOrphansOnce(String namespace) {
        if (orphansSwept) {
            return;
        }
        synchronized (orphanSweepLock) {
            if (orphansSwept) {
                return;
            }
            try {
                var orphans = client.pods().inNamespace(namespace)
                        .withLabels(LambdaPodSpecFactory.managedPodSelector())
                        .list().getItems().stream()
                        .filter(pod -> !ownPodNames.contains(pod.getMetadata().getName()))
                        .toList();
                if (!orphans.isEmpty()) {
                    LOG.infov("Deleting {0} orphaned Lambda pod(s) from a previous run in namespace {1}",
                            orphans.size(), namespace);
                    for (var orphan : orphans) {
                        client.pods().inNamespace(namespace)
                                .withName(orphan.getMetadata().getName())
                                .withGracePeriod(0)
                                .delete();
                    }
                }
                orphansSwept = true;
            } catch (KubernetesClientException e) {
                if (e.getCode() == 401 || e.getCode() == 403) {
                    // A permission gap never heals within this process, so stop retrying
                    // it on every cold start; the ServiceAccount simply cannot sweep.
                    LOG.warnv("Orphaned Lambda pod sweep is not permitted in namespace {0} "
                            + "(the ServiceAccount lacks pod list/delete); skipping it for this "
                            + "process: {1}", namespace, e.getMessage());
                    orphansSwept = true;
                } else {
                    LOG.warnv("Orphaned Lambda pod sweep failed in namespace {0} and will be "
                            + "retried on the next launch: {1}", namespace, e.getMessage());
                }
            } catch (Exception e) {
                LOG.warnv("Orphaned Lambda pod sweep failed in namespace {0} and will be retried "
                        + "on the next launch: {1}", namespace, e.getMessage());
            }
        }
    }

    private String codeDownloadUrl(LambdaFunction fn, String region) {
        var bucket = LambdaService.tasksBucketName(region);
        var key = LambdaService.codeObjectKey(fn);
        try {
            s3Service.headObject(bucket, key);
        } catch (AwsException e) {
            throw new RuntimeException("Deployment package for function '" + fn.getFunctionName()
                    + "' is not available at s3://" + bucket + "/" + key
                    + " — the kubernetes executor downloads code from Floci's S3, and the copy "
                    + "stored at deploy time is missing. Re-deploy the function code.", e);
        }
        var account = fn.getAccountId() != null ? fn.getAccountId() : config.defaultAccountId();
        return downloadUrl(bucket, key, account, region);
    }

    private List<String> layerDownloadUrls(LambdaFunction fn, String functionRegion) {
        if (fn.getLayers() == null || fn.getLayers().isEmpty()) {
            return List.of();
        }
        var urls = new ArrayList<String>();
        for (var layerArn : fn.getLayers()) {
            var layer = layerService.resolveLayerByArn(layerArn);
            if (layer == null) {
                LOG.warnv("Could not resolve layer ARN {0} for function {1}", layerArn, fn.getFunctionName());
                continue;
            }
            // The archive lives under the layer's own region and account (the canonical
            // ARN assigned at publish), which may differ from the function's.
            var arn = layer.getLayerVersionArn();
            var layerRegion = AwsArnUtils.regionOrDefault(arn, functionRegion);
            var account = AwsArnUtils.accountOrDefault(arn, config.defaultAccountId());
            var bucket = LambdaService.tasksBucketName(layerRegion);
            var key = LambdaService.layerObjectKey(account, layer.getLayerName(), layer.getVersion());
            try {
                s3Service.headObject(bucket, key);
            } catch (AwsException e) {
                throw new RuntimeException("Layer zip for '" + layerArn + "' is not available at s3://"
                        + bucket + "/" + key + " — layers published before kubernetes executor support "
                        + "have no stored archive. Re-publish the layer version.", e);
            }
            urls.add(downloadUrl(bucket, key, account, layerRegion));
        }
        return urls;
    }

    /**
     * Download URL for a tasks-bucket object. The init container fetches it over plain
     * HTTP with no SigV4 header, so an {@code X-Amz-Credential} query steers Floci's
     * account filter to the object's owning account. Without it a function or layer
     * owned by a non-default account resolves the default-account prefix and 404s. A
     * 12-digit account id doubles as its own access key id (LocalStack convention).
     */
    private String downloadUrl(String bucket, String key, String account, String region) {
        return addressResolver.downloadBaseUrl() + "/" + bucket + "/"
                + LambdaService.encodeObjectPath(key)
                + "?X-Amz-Credential=" + account + "%2F00010101%2F" + region + "%2Fs3%2Faws4_request";
    }

    private void awaitRunning(String namespace, String podName, String functionName) {
        var timeoutSeconds = POD_STARTUP_TIMEOUT_SECONDS;
        Pod pod;
        try {
            // A null pod (deleted out-of-band) is terminal too, otherwise the wait
            // would block the invocation for the full timeout.
            pod = client.pods().inNamespace(namespace).withName(podName)
                    .waitUntilCondition(p -> p == null
                                    || (p.getStatus() != null && (isRunning(p) || hasTerminalFailure(p))),
                            timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Pod " + podName + " for function '" + functionName
                    + "' did not reach Running within " + timeoutSeconds + "s: " + e.getMessage(), e);
        }
        if (pod == null || !isRunning(pod)) {
            throw new RuntimeException("Pod " + podName + " for function '" + functionName
                    + "' failed to start: " + describeFailure(pod));
        }
    }

    private static boolean isRunning(Pod pod) {
        return "Running".equals(pod.getStatus().getPhase());
    }

    private static boolean hasTerminalFailure(Pod pod) {
        var phase = pod.getStatus().getPhase();
        // Succeeded means the runtime exited before serving — terminal for a server pod.
        if ("Failed".equals(phase) || "Succeeded".equals(phase)) {
            return true;
        }
        return unschedulableReason(pod) != null || describeTerminalReason(pod) != null;
    }

    private static String unschedulableReason(Pod pod) {
        if (pod.getStatus().getConditions() == null) {
            return null;
        }
        for (var condition : pod.getStatus().getConditions()) {
            // A pod the scheduler cannot place — e.g. a memory request above every node's
            // capacity — stays Pending with no container statuses; fail the cold start
            // instead of blocking the invoker for the full startup timeout.
            if ("PodScheduled".equals(condition.getType()) && "False".equals(condition.getStatus())
                    && "Unschedulable".equals(condition.getReason())) {
                return "Unschedulable: " + condition.getMessage();
            }
        }
        return null;
    }

    private static String describeTerminalReason(Pod pod) {
        var statuses = new ArrayList<ContainerStatus>();
        if (pod.getStatus().getInitContainerStatuses() != null) {
            statuses.addAll(pod.getStatus().getInitContainerStatuses());
        }
        if (pod.getStatus().getContainerStatuses() != null) {
            statuses.addAll(pod.getStatus().getContainerStatuses());
        }
        for (var status : statuses) {
            if (status.getState() == null) {
                continue;
            }
            var waiting = status.getState().getWaiting();
            // getReason() is null while a reason is absent; Set.of(...).contains(null)
            // throws, which would abort an otherwise healthy cold start.
            if (waiting != null && waiting.getReason() != null
                    && TERMINAL_WAITING_REASONS.contains(waiting.getReason())) {
                return status.getName() + ": " + waiting.getReason()
                        + " (" + waiting.getMessage() + ")";
            }
            if (status.getState().getTerminated() != null
                    && status.getState().getTerminated().getExitCode() != null
                    && status.getState().getTerminated().getExitCode() != 0) {
                return status.getName() + ": exited with code "
                        + status.getState().getTerminated().getExitCode();
            }
        }
        return null;
    }

    private static String describeFailure(Pod pod) {
        if (pod == null || pod.getStatus() == null) {
            return "pod no longer exists";
        }
        var reason = describeTerminalReason(pod);
        if (reason == null) {
            reason = unschedulableReason(pod);
        }
        return reason != null ? reason : "phase=" + pod.getStatus().getPhase();
    }

    /**
     * Publishes Floci's CA cert as a ConfigMap so pods can trust Floci's HTTPS endpoint.
     * Applied once per process; the cert cannot rotate within a Floci lifetime.
     */
    private String ensureCaConfigMap(String namespace, Path caCertPath) {
        if (caConfigMapApplied.get()) {
            return CA_CONFIG_MAP_NAME;
        }
        try {
            var pem = Files.readString(caCertPath);
            var configMap = new ConfigMapBuilder()
                    .withNewMetadata()
                    .withName(CA_CONFIG_MAP_NAME)
                    .withLabels(LambdaPodSpecFactory.managedPodSelector())
                    .endMetadata()
                    .addToData(CA_CONFIG_MAP_KEY, pem)
                    .build();
            client.configMaps().inNamespace(namespace).resource(configMap)
                    .createOr(NonDeletingOperation::update);
            caConfigMapApplied.set(true);
            return CA_CONFIG_MAP_NAME;
        } catch (Exception e) {
            throw new RuntimeException("Could not publish Floci CA cert ConfigMap '" + CA_CONFIG_MAP_NAME
                    + "' in namespace " + namespace + ": " + e.getMessage(), e);
        }
    }

    /**
     * Force-deletes the pod and waits for it to actually disappear. Returns whether the
     * pod is confirmed gone: only then is it safe to reuse resources tied to it (its
     * Runtime API port), because until the object vanishes the runtime container may
     * still be polling that port. A rejected delete or a wait that times out returns
     * false so the caller keeps the port reserved.
     */
    private boolean deletePod(String namespace, String podName) {
        // Dropped from the own-pod set even when the delete fails: the pod is
        // abandoned either way, and a retried orphan sweep may still collect it.
        ownPodNames.remove(podName);
        try {
            client.pods().inNamespace(namespace).withName(podName).withGracePeriod(0).delete();
            client.pods().inNamespace(namespace).withName(podName)
                    .waitUntilCondition(p -> p == null, POD_DELETE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            LOG.warnv("Pod {0} was not confirmed deleted within {1}s: {2}",
                    podName, POD_DELETE_TIMEOUT_SECONDS, e.getMessage());
            return false;
        }
    }

    private static boolean isProvidedRuntime(String runtime) {
        return runtime != null && runtime.startsWith("provided");
    }
}
