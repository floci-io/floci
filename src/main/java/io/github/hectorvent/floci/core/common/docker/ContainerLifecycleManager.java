package io.github.hectorvent.floci.core.common.docker;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.ListVolumesResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import io.github.hectorvent.floci.config.ContainerCaBundle;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.EmulatorConfig.EcsServiceConfig.ImagePullBehavior;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService.LaunchImage;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Manages Docker container lifecycle operations including create, start, stop, and remove.
 * Consolidates common container management patterns used across Floci services.
 */
@ApplicationScoped
public class ContainerLifecycleManager {

    private static final Logger LOG = Logger.getLogger(ContainerLifecycleManager.class);

    /**
     * Label carrying a fresh id per {@code create()} invocation, used to tell this call's own
     * replayed create apart from a genuinely concurrent caller's, see
     * {@link #createWithConflictRecovery}.
     */
    static final String CREATE_ATTEMPT_LABEL = "floci_create_attempt_id";

    // Matches crun/runc's "join keyctl '<id>': Disk quota exceeded" error, which the OCI runtime
    // reports for the kernel session-keyring quota (kernel.keys.maxkeys), not actual disk space.
    // Under high concurrent container counts (podman machine's Fedora CoreOS default is 200 keys
    // per uid) this reads as a disk-space problem and sends operators looking in the wrong place.
    private static final Pattern KEYRING_QUOTA_PATTERN =
            Pattern.compile("join keyctl.*disk quota exceeded", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Matches 3-4 octal digits (e.g. "0755", "755", "2775"), matching CreationInfo.Permissions. */
    public static final Pattern PERMISSIONS_PATTERN = Pattern.compile("^[0-7]{3,4}$");

    private static final long NANO_CPUS_PER_CPU = 1_000_000_000L;
    private static final String CONTAINER_NETWORK_MODE_PREFIX = "container:";

    /** Host interface a port marked loopback-only publishes on. */
    private static final String LOOPBACK_HOST_IP = "127.0.0.1";

    private final Semaphore teardownSlots = new Semaphore(8, true);

    private final DockerClient dockerClient;
    private final ImageCacheService imageCacheService;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final long archiveRetryBackoffMillis;

    /** Volumes whose shared-ownership root has already been initialised this process (run-once guard). */
    private final ConcurrentHashMap<String, Boolean> initializedSharedVolumes = new ConcurrentHashMap<>();

    /** Daemon CPU count, resolved on first use. Null until then, so that empty can mean "unknown". */
    private final AtomicReference<OptionalInt> hostCpuCount = new AtomicReference<>();

    @Inject
    public ContainerLifecycleManager(DockerClient dockerClient,
                                     ImageCacheService imageCacheService,
                                     ContainerDetector containerDetector,
                                     EmulatorConfig config) {
        this(dockerClient, imageCacheService, containerDetector, config,
                RetryingTarCopier.DEFAULT_BACKOFF_MS);
    }

    ContainerLifecycleManager(DockerClient dockerClient,
                              ImageCacheService imageCacheService,
                              ContainerDetector containerDetector,
                              EmulatorConfig config,
                              long archiveRetryBackoffMillis) {
        this.dockerClient = dockerClient;
        this.imageCacheService = imageCacheService;
        this.containerDetector = containerDetector;
        this.config = config;
        this.archiveRetryBackoffMillis = archiveRetryBackoffMillis;
    }

    /**
     * Creates and immediately starts a container. Delegates to
     * {@link #create} and {@link #startCreated}. Suitable when no
     * filesystem modifications are needed between creation and start.
     *
     * @param spec the container specification
     * @return information about the created container including resolved endpoints and published ports
     */
    public ContainerInfo createAndStart(ContainerSpec spec) {
        return createAndStart(spec, false);
    }

    /** Starts a disposable container, reclaiming its anonymous volumes if startup fails. */
    public ContainerInfo createAndStartEphemeral(ContainerSpec spec) {
        return createAndStart(spec, true);
    }

    private ContainerInfo createAndStart(ContainerSpec spec, boolean removeAnonymousVolumes) {
        String resolvedImage = imageCacheService.ensureImageExists(spec.image());
        String containerId = create(spec, resolvedImage, null, removeAnonymousVolumes);
        try {
            return withPublishedHostPorts(startCreated(containerId, spec), spec);
        } catch (RuntimeException failure) {
            removeFailedContainer(containerId, failure, removeAnonymousVolumes);
            throw failure;
        }
    }

    private void removeFailedContainer(String containerId, RuntimeException failure, boolean removeAnonymousVolumes) {
        if (!removeAnonymousVolumes) {
            removeIfExists(containerId);
            return;
        }
        try {
            removeIfExistsStrict(containerId, true);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    /**
     * Resolves the image a container launch is about to run, pulling it as the ECS agent does
     * under {@code behavior}. A spec built from the returned image id runs exactly that image,
     * whatever a later pull of the same reference moves its tag to.
     */
    public LaunchImage resolveImageForLaunch(String image, ImagePullBehavior behavior) {
        return imageCacheService.resolveForLaunch(image, behavior);
    }

    /**
     * Creates a container without starting it. Use {@link #startCreated} to
     * start it after any pre-start setup (e.g. copying files into the
     * container filesystem).
     *
     * @param spec the container specification
     * @return the container ID
     */
    public String create(ContainerSpec spec) {
        String resolvedImage = imageCacheService.ensureImageExists(spec.image());
        return create(spec, resolvedImage, null);
    }

    /**
     * Creates a container for a specific Docker platform.
     *
     * @param spec the container specification
     * @param platform Docker platform, such as {@code linux/arm64}
     * @return the container ID
     */
    public String create(ContainerSpec spec, String platform) {
        String resolvedImage = imageCacheService.ensureImageExists(spec.image(), platform);
        return create(spec, resolvedImage, platform);
    }

    private String create(ContainerSpec spec, String resolvedImage, String platform) {
        return create(spec, resolvedImage, platform, false);
    }

    private String create(ContainerSpec spec, String resolvedImage, String platform, boolean removeAnonymousVolumes) {
        String containerId = createWithCaBundle(spec, resolvedImage, platform, removeAnonymousVolumes);
        if (spec.hasNetworkConfiguration()) {
            try {
                attachNetworkBeforeStart(containerId, spec);
            } catch (RuntimeException e) {
                removeFailedContainer(containerId, e, removeAnonymousVolumes);
                throw new IllegalStateException(
                        "Failed to attach pre-start network configuration for container " + containerId, e);
            }
        }
        return containerId;
    }

    private String createWithCaBundle(ContainerSpec spec, String resolvedImage, String platform,
                                      boolean removeAnonymousVolumes) {
        LOG.debugv("Creating container from spec: image={0}, name={1}", spec.image(), spec.name());

        // Built once: a dynamic port binding allocates its host port here.
        HostConfig hostConfig = buildHostConfig(spec);
        Optional<Path> caBundle = ContainerCaBundle.hostPath(config);
        if (caBundle.isEmpty()) {
            return createContainer(spec, resolvedImage, platform, hostConfig, spec.env());
        }
        String containerId = createContainer(spec, resolvedImage, platform, hostConfig,
                ContainerCaBundle.appendEnv(spec.env()));
        if (copyCaBundle(containerId, caBundle.get())) {
            return containerId;
        }
        // SSL_CERT_FILE and friends replace the image's trust store, so they must never name a
        // file that is not there. Start over without them: the container keeps its own trust.
        if (removeAnonymousVolumes) {
            removeIfExistsStrict(containerId, true);
        } else {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        }
        return createContainer(spec, resolvedImage, platform, hostConfig, spec.env());
    }

    private String createContainer(ContainerSpec spec, String resolvedImage, String platform,
                                   HostConfig hostConfig, List<String> env) {
        CreateContainerCmd createCmd = dockerClient.createContainerCmd(resolvedImage)
                .withHostConfig(hostConfig);

        if (platform != null && !platform.isBlank()) {
            createCmd.withPlatform(platform);
        }

        if (spec.name() != null) {
            createCmd.withName(spec.name());
        }
        if (spec.user() != null && !spec.user().isBlank()) {
            createCmd.withUser(spec.user());
        }
        if (env != null && !env.isEmpty()) {
            createCmd.withEnv(env);
        }
        if (spec.cmd() != null && !spec.cmd().isEmpty()) {
            createCmd.withCmd(spec.cmd());
        }
        if (spec.entrypoint() != null && !spec.entrypoint().isEmpty()) {
            createCmd.withEntrypoint(spec.entrypoint());
        }
        if (spec.workingDir() != null && !spec.workingDir().isBlank()) {
            createCmd.withWorkingDir(spec.workingDir());
        }
        if (spec.exposedPorts() != null && !spec.exposedPorts().isEmpty()) {
            ExposedPort[] exposed = spec.exposedPorts().stream()
                    .map(ExposedPort::tcp)
                    .toArray(ExposedPort[]::new);
            createCmd.withExposedPorts(exposed);
        }
        // A fresh id per create() call, not per spec: two calls can carry identical image, name,
        // and labels (a second, genuinely concurrent caller racing on the same fixed name), and
        // matchesSpec must be able to tell that apart from a replay of THIS call's own create
        // hitting a lost-response conflict, see createWithConflictRecovery.
        String createAttemptId = UUID.randomUUID().toString();
        Map<String, String> labels = mergedLabels(spec.labels());
        labels.put(CREATE_ATTEMPT_LABEL, createAttemptId);
        createCmd.withLabels(labels);

        String containerId = createWithConflictRecovery(createCmd, spec, createAttemptId);
        LOG.infov("Created container {0} (name={1}, not yet started)", containerId, spec.name());
        return containerId;
    }

    /**
     * Executes the create, recovering the container when a replay of it lost the race with its own
     * earlier attempt.
     *
     * <p>{@link RetryingDockerHttpClient} replays {@code POST /containers/create}, it has a
     * {@code bodyBytes} body, no hijacked input, and no {@code /exec} in its path, so it is
     * replayable by that decorator's rules. That replay is what makes this recovery necessary: a
     * create is not idempotent, so if the daemon accepts a named create but the response is lost to
     * a transient socket drop, the replay hits a 409 name conflict instead of the transient error
     * the retry exists to absorb. {@code DockerRetry} correctly classifies that 409 as
     * non-transient and rethrows it immediately, so without this it surfaces as a spurious build
     * failure, and leaks the container the first attempt actually created, untracked.
     *
     * <p>Recovery only fires for a named spec, and only adopts a candidate whose image, labels, and
     * this exact call's {@link #CREATE_ATTEMPT_LABEL} all match. Image and labels alone cannot tell
     * this call's own lost-response replay apart from a second, genuinely concurrent
     * {@code create()} racing on the same fixed name/image/labels, which would otherwise be adopted
     * as if it were this invocation's container.
     */
    private String createWithConflictRecovery(CreateContainerCmd createCmd, ContainerSpec spec,
                                              String createAttemptId) {
        try {
            return createCmd.exec().getId();
        } catch (ConflictException e) {
            if (spec.name() != null) {
                Optional<Container> existing = findByName(spec.name());
                if (existing.isPresent() && matchesSpec(existing.get(), spec, createAttemptId)) {
                    LOG.infov("Create for {0} hit a name conflict; an earlier attempt's response "
                            + "was lost but the container exists, adopting {1}",
                            spec.name(), existing.get().getId());
                    return existing.get().getId();
                }
            }
            throw e;
        }
    }

    /**
     * Applies endpoint IPAM while the container is still in CREATED state. docker-java does not
     * expose Docker's {@code NetworkingConfig} on create, but it does expose the equivalent
     * network-connect endpoint, so the container is reconnected before it starts.
     */
    private void attachNetworkBeforeStart(String containerId, ContainerSpec spec) {
        if (!spec.hasPortBindings()) {
            dockerClient.disconnectFromNetworkCmd()
                    .withContainerId(containerId)
                    .withNetworkId(spec.networkMode())
                    .exec();
        }
        ContainerNetwork endpoint = new ContainerNetwork().withIpamConfig(new LinkLocalIpam(spec.linkLocalIps()));
        dockerClient.connectToNetworkCmd()
                .withContainerId(containerId)
                .withNetworkId(spec.networkMode())
                .withContainerNetwork(endpoint)
                .exec();
    }

    /**
     * Docker Engine accepts LinkLocalIPs, but docker-java omits the model property. Registered for
     * reflection because Jackson finds the added getter reflectively in a native image.
     */
    @RegisterForReflection
    private static final class LinkLocalIpam extends ContainerNetwork.Ipam {
        private final List<String> linkLocalIps;

        private LinkLocalIpam(List<String> linkLocalIps) {
            this.linkLocalIps = List.copyOf(linkLocalIps);
        }

        @JsonProperty("LinkLocalIPs")
        public List<String> getLinkLocalIps() {
            return linkLocalIps;
        }
    }

    /**
     * Copies the CA bundle into the created, not yet started, container so runtimes that read
     * {@code SSL_CERT_FILE} and friends at init find it. A copy rather than a bind mount because
     * when Floci itself runs in Docker its persistent path is not a host path the daemon can mount.
     * Returns false, after logging why, when the copy failed; an image without {@code /etc} is the
     * expected reason, and the caller then recreates the container without the trust variables.
     */
    private boolean copyCaBundle(String containerId, Path bundle) {
        try {
            byte[] content = Files.readAllBytes(bundle);
            RetryingTarCopier.copyBytes(dockerClient, containerId, ContainerCaBundle.CONTAINER_DIR,
                    ContainerCaBundle.FILE_NAME, content, 0644,
                    RetryingTarCopier.DEFAULT_MAX_ATTEMPTS, archiveRetryBackoffMillis);
            LOG.debugv("Copied the CA bundle into container {0} at {1}", containerId, ContainerCaBundle.CONTAINER_PATH);
            return true;
        } catch (Exception e) {
            LOG.warnv(e, "Could not copy the CA bundle into container {0}; creating it again without the trust "
                    + "variables, so it keeps its own trust store and will not trust Floci HTTPS: {1}",
                    containerId, e.getMessage());
            return false;
        }
    }

    /**
     * Confirms a name-conflicting container is the one THIS create() call produced, not a stale or
     * differently-owned container, and not one from a genuinely concurrent create() sharing the same
     * fixed name, image, and spec labels. It must carry every label this call would have applied,
     * including a {@link #CREATE_ATTEMPT_LABEL} equal to this call's {@code createAttemptId}: the
     * one part of the label set a different concurrent call could never coincidentally reproduce,
     * and on its own sufficient proof of ownership.
     *
     * <p>Deliberately does NOT compare the image. The list API reports {@code getImage()} as an
     * image ID/digest rather than the tag once that tag has been re-pulled, retagged, or removed, so
     * a tag comparison would reject this call's own container and leak it untracked, the failure
     * this recovery exists to prevent, arising precisely under the daemon churn that makes a lost
     * create response likely. The attempt-id match already excludes every foreign container.
     */
    private boolean matchesSpec(Container existing, ContainerSpec spec, String createAttemptId) {
        Map<String, String> expectedLabels = mergedLabels(spec.labels());
        expectedLabels.put(CREATE_ATTEMPT_LABEL, createAttemptId);
        Map<String, String> actualLabels = existing.getLabels();
        return actualLabels != null && actualLabels.entrySet().containsAll(expectedLabels.entrySet());
    }

    /**
     * Starts a previously created container and resolves its endpoints.
     *
     * @param containerId the container ID returned by {@link #create}
     * @param spec the original spec (needed for network and endpoint resolution)
     * @return information about the running container including resolved endpoints
     */
    public ContainerInfo startCreated(String containerId, ContainerSpec spec) {
        startContainer(containerId);
        LOG.infov("Started container {0}", containerId);

        if (spec.networkMode() != null && !spec.networkMode().isBlank()
                && spec.publishesPorts() && !spec.hasNetworkConfiguration()) {
            try {
                dockerClient.connectToNetworkCmd()
                        .withContainerId(containerId)
                        .withNetworkId(spec.networkMode())
                        .exec();
                LOG.debugv("Connected container {0} to network {1}", containerId, spec.networkMode());
            } catch (NotFoundException e) {
                // Docker answers 404 when the network does not exist, which fails a container that
                // joins at creation, or when the container is already gone. Either way it cannot
                // reach its network, so fail the start instead of leaving it on the default bridge.
                // Docker's message says which of the two was missing.
                throw new IllegalStateException("Could not connect container " + containerId
                        + " to Docker network " + spec.networkMode() + ": " + e.getMessage(), e);
            } catch (Exception e) {
                LOG.warnv("Could not connect container {0} to network {1}: {2}",
                        containerId, spec.networkMode(), e.getMessage());
            }
        }

        Map<Integer, EndpointInfo> endpoints = resolveEndpoints(containerId, spec);
        return new ContainerInfo(containerId, endpoints);
    }

    private ContainerInfo withPublishedHostPorts(ContainerInfo info, ContainerSpec spec) {
        if (!spec.publishesPorts()) {
            return info;
        }

        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(info.containerId()).exec();
        Map<Integer, Integer> publishedHostPorts = new HashMap<>();
        for (Integer containerPort : spec.portBindings().keySet()) {
            OptionalInt published = readPublishedHostPort(inspect, containerPort);
            if (published.isPresent()) {
                publishedHostPorts.put(containerPort, published.getAsInt());
            }
        }
        return new ContainerInfo(info.containerId(), info.endpoints(), publishedHostPorts);
    }

    /**
     * Starts {@code containerId}, treating "already running" as success and translating
     * crun/runc's kernel-keyring-quota error into a message that names the actual cause and a fix,
     * instead of the misleading "Disk quota exceeded" the OCI runtime reports. Transient socket
     * blips are retried below this call, at the transport seam
     * ({@link RetryingDockerHttpClient}); no retry may live here, where it would compound backoff
     * on the transport's already-exhausted budget.
     *
     * <p>The 304 handling is what makes the transport's replay safe: when the daemon honoured a
     * start whose response was lost to a broken pipe, the replayed start meets HTTP 304, a
     * successful response at transport level, which docker-java converts to
     * {@link NotModifiedException} above it. That reports the outcome we wanted and is swallowed.
     * Letting it escape would turn a recovered blip into a hard launch failure, the exact bug
     * the retry exists to remove.
     *
     * <p>Package-private so tests can verify a given call site routes through this translation
     * rather than a raw {@code dockerClient.startContainerCmd(...)} call.
     */
    void startContainer(String containerId) {
        try {
            dockerClient.startContainerCmd(containerId).exec();
        } catch (NotModifiedException alreadyRunning) {
            LOG.debugv("Container {0} was already running (304), treating start as done",
                    containerId);
        } catch (DockerException e) {
            String message = e.getMessage();
            if (message != null && KEYRING_QUOTA_PATTERN.matcher(message).find()) {
                throw new IllegalStateException(
                        "Container start failed: the container runtime's kernel session-keyring is "
                                + "out of quota (kernel.keys.maxkeys), not disk space. This is common "
                                + "under many concurrent containers on podman machine's default Fedora "
                                + "CoreOS sysctls (200 keys per uid). Raise the limit in the VM, e.g.: "
                                + "podman machine ssh \"sudo sysctl -w kernel.keys.maxkeys=20000 "
                                + "kernel.keys.maxbytes=2000000\". Original error: " + message, e);
            }
            throw e;
        }
    }

    /**
     * Stops and removes a container, closing any associated log stream.
     *
     * @param containerId the container ID to stop and remove
     * @param logStream optional log stream to close (may be null)
     */
    public void stopAndRemove(String containerId, Closeable logStream) {
        stopAndRemove(containerId, logStream, 5);
    }

    /**
     * Stops and removes a container, closing any associated log stream.
     *
     * @param containerId the container ID to stop and remove
     * @param logStream optional log stream to close (may be null)
     * @param stopTimeoutSeconds seconds to wait for the container to stop cleanly after
     *        SIGTERM before Docker sends SIGKILL. Must be a non-negative integer.
     */
    public void stopAndRemove(String containerId, Closeable logStream, int stopTimeoutSeconds) {
        withTeardownSlot(() -> stopAndRemoveAdmitted(containerId, logStream, stopTimeoutSeconds));
    }

    private void stopAndRemoveAdmitted(String containerId, Closeable logStream, int stopTimeoutSeconds) {
        boolean stoppedOrMissing = stopAdmitted(containerId, stopTimeoutSeconds);

        // Force removal is the recovery path when Docker could not stop the container cleanly. It also
        // terminates the follow-log transport, allowing its terminal callback to drain the final tail.
        boolean removedOrMissing = false;
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            removedOrMissing = true;
            LOG.debugv("Removed container {0}", containerId);
        } catch (NotFoundException e) {
            // Already gone
            removedOrMissing = true;
        } catch (Exception e) {
            LOG.warnv("Error removing container {0}: {1}", containerId, e.getMessage());
        }

        if (logStream != null && (stoppedOrMissing || removedOrMissing)) {
            closeLogStreamAfterContainerStop(logStream);
        }
    }

    /**
     * Stops a container and leaves it in place, for its output to be read before it is removed: one that
     * had already exited, or is gone, counts as stopped.
     *
     * @param stopTimeoutSeconds seconds to wait for the container to stop cleanly after
     *        SIGTERM before Docker sends SIGKILL. Must be a non-negative integer.
     */
    public void stop(String containerId, int stopTimeoutSeconds) {
        withTeardownSlot(() -> stopAdmitted(containerId, stopTimeoutSeconds));
    }

    private boolean stopAdmitted(String containerId, int stopTimeoutSeconds) {
        LOG.infov("Stopping container {0}", containerId);
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(stopTimeoutSeconds).exec();
            return true;
        } catch (NotFoundException e) {
            LOG.debugv("Container {0} not found (already removed)", containerId);
            return true;
        } catch (NotModifiedException e) {
            // HTTP 304: the container had already exited, which is the outcome the stop wanted.
            LOG.debugv("Container {0} had already stopped", containerId);
            return true;
        } catch (Exception e) {
            LOG.warnv("Error stopping container {0}: {1}", containerId, e.getMessage());
            return false;
        }
    }

    /**
     * Releases lifecycle ownership of a log stream after its container has stopped. Docker's terminal
     * callback normally flushes the final tail; a bounded fallback handles a broken transport.
     */
    public void closeLogStreamAfterContainerStop(Closeable logStream) {
        try {
            logStream.close();
        } catch (Exception e) {
            LOG.debugv("Error closing log stream: {0}", e.getMessage());
        }
    }

    /**
     * Stops and removes a container, failing when Docker cannot confirm removal.
     *
     * <p>Most emulator shutdown paths intentionally use best-effort cleanup through
     * {@link #stopAndRemove(String, Closeable)}. Resource-deletion paths that must retain their
     * persisted record for a retry use this stricter variant instead. The log stream is closed
     * only once the container is gone; when removal fails it is left open for the retry.
     */
    public void stopAndRemoveStrict(String containerId, Closeable logStream) {
        stopAndRemoveStrict(containerId, logStream, 5);
    }

    public void stopAndRemoveStrict(String containerId, Closeable logStream, int stopTimeoutSeconds) {
        stopAndRemoveStrict(containerId, logStream, stopTimeoutSeconds, false);
    }

    /** Strict teardown that can also reclaim the container's anonymous volumes. */
    public void stopAndRemoveStrict(String containerId, Closeable logStream, int stopTimeoutSeconds,
                                     boolean removeAnonymousVolumes) {
        withTeardownSlot(() -> stopAndRemoveStrictAdmitted(
                containerId, logStream, stopTimeoutSeconds, removeAnonymousVolumes));
    }

    private void stopAndRemoveStrictAdmitted(String containerId, Closeable logStream, int stopTimeoutSeconds,
                                             boolean removeAnonymousVolumes) {
        LOG.infov("Stopping container {0}", containerId);

        Exception stopFailure = null;
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(stopTimeoutSeconds).exec();
        } catch (NotFoundException e) {
            LOG.debugv("Container {0} not found (already removed)", containerId);
            closeLogStreamIfPresent(logStream);
            return;
        } catch (NotModifiedException e) {
            // HTTP 304: the container had already exited, which is the outcome the stop wanted.
            LOG.debugv("Container {0} had already stopped", containerId);
        } catch (Exception e) {
            stopFailure = e;
            LOG.warnv("Error stopping container {0}: {1}", containerId, e.getMessage());
        }

        try {
            RemoveContainerCmd remove = dockerClient.removeContainerCmd(containerId).withForce(true);
            if (removeAnonymousVolumes) {
                remove.withRemoveVolumes(true);
            }
            remove.exec();
            LOG.debugv("Removed container {0}", containerId);
        } catch (NotFoundException e) {
            // Already gone, so cleanup succeeded.
        } catch (Exception e) {
            IllegalStateException cleanupFailure = new IllegalStateException(
                    "Failed to remove container " + containerId, e);
            if (stopFailure != null) {
                cleanupFailure.addSuppressed(stopFailure);
            }
            // The container still exists and may still be logging, so the stream stays open for
            // the caller's retry.
            throw cleanupFailure;
        }
        // Closed only once the container is stopped and gone, as in stopAndRemove, so the follower
        // can drain the final tail.
        closeLogStreamIfPresent(logStream);
    }

    private void withTeardownSlot(Runnable stopAndRemove) {
        teardownSlots.acquireUninterruptibly();
        try {
            stopAndRemove.run();
        } finally {
            teardownSlots.release();
        }
    }

    private void closeLogStreamIfPresent(Closeable logStream) {
        if (logStream != null) {
            closeLogStreamAfterContainerStop(logStream);
        }
    }

    /**
     * Creates a named volume if it does not already exist. Idempotent — safe to call on every
     * container start. Labels the volume {@code floci=true} and
     * {@code floci_emulator=floci-aws} (plus {@code floci_namespace} when configured) so both
     * {@code docker volume prune --filter label=floci=true} (all emulators) and
     * {@code --filter label=floci_emulator=floci-aws} (this emulator only) work.
     *
     * <p>Transient socket blips on both daemon calls here, the existence check and the create,
     * are retried at the transport seam ({@link RetryingDockerHttpClient}). The existence guard
     * is what keeps the transport's replay safe: {@code POST /volumes/create} with the same name
     * is itself idempotent, and when the daemon created the volume but the response was lost to
     * a broken pipe, the replayed create finds it already there while later calls see it exists
     * and do nothing, the volume analogue of start treating an HTTP 304 as success.
     */
    public void ensureVolume(String volumeName) {
        createVolumeIfAbsent(volumeName, ContainerStorageHelper.defaultLabels(config));
    }

    /**
     * {@link #ensureVolume} for a volume an operator named, created when absent without any
     * Floci label, the umbrella and extra ones alike. The operator named the volume, so its
     * lifecycle is theirs: nothing that sweeps Floci-labelled volumes may collect it, neither
     * a floci-scoped {@code docker volume prune} nor a testcontainers module reaping a run's
     * labelled child resources through labels injected into {@code floci.docker.extra-labels}.
     */
    public void ensureUnmanagedVolume(String volumeName) {
        if (volumeExists(volumeName)) {
            return;
        }
        dockerClient.createVolumeCmd()
                .withName(volumeName)
                // An explicit empty map, never mergedLabels: the operator-named volume
                // carries no Floci label, so no label-based sweep collects it.
                .withLabels(Map.of())
                .exec();
        LOG.debugv("Created unmanaged volume {0}", volumeName);
    }

    /** Creates a volume with resource ownership labels for recoverable cleanup. */
    public void ensureVolume(String volumeName, Map<String, String> labels) {
        createVolumeIfAbsent(volumeName, labels);
    }
    /** Creates the named volume when it is missing; returns whether it was created. */
    private boolean createVolumeIfAbsent(String volumeName, Map<String, String> labels) {
        if (volumeExists(volumeName)) {
            return false;
        }
        dockerClient.createVolumeCmd()
                .withName(volumeName)
                .withLabels(mergedLabels(labels))
                .exec();
        LOG.debugv("Created volume {0}", volumeName);
        return true;
    }

    /**
     * Default emulator labels overlaid with the spec's labels; a per-spec label
     * wins on key conflicts. Each new key with a legacy alias also gets that alias, with the
     * same value (see {@link ContainerStorageHelper#CONTAINER_LABEL_ALIASES}).
     */
    private Map<String, String> mergedLabels(Map<String, String> specLabels) {
        Map<String, String> labels = ContainerStorageHelper.defaultLabels(config);
        if (specLabels != null) {
            labels.putAll(specLabels);
        }
        return ContainerStorageHelper.CONTAINER_LABEL_ALIASES.withLegacyAliases(labels);
    }

    /**
     * Ensures the named volume exists (see {@link #ensureVolume}) and, when POSIX ownership is
     * requested, initialises the volume root once to emulate an Amazon EFS access point's
     * {@code RootDirectory.CreationInfo}. A Docker named volume is created {@code root:root 0755},
     * so a container whose image runs as a non-root {@code USER} (as ECS tasks and
     * access-point-mounted workloads typically do) cannot create files on it. This chowns/chmods
     * the mount root inside a short-lived helper container. A 4-digit octal
     * {@code rootPermissions} (e.g. {@code "2775"}) carries the setgid bit, so subdirectories
     * inherit the owner gid — matching {@code CreationInfo.Permissions} exactly.
     *
     * <p>The initialisation runs at most once per volume name per process. When no ownership is
     * configured (all of {@code ownerUid}/{@code ownerGid}/{@code rootPermissions} empty) this
     * degrades to a plain {@link #ensureVolume}, so the default behaviour is unchanged.
     *
     * @param volumeName      the named volume
     * @param ownerUid        owner uid for the volume root (EFS {@code CreationInfo.OwnerUid})
     * @param ownerGid        owner gid for the volume root (EFS {@code CreationInfo.OwnerGid})
     * @param rootPermissions octal permissions for the volume root (e.g. {@code "0777"}); empty skips init
     * @param initImage       lightweight image used for the one-off chown/chmod helper
     */
    public void ensureSharedVolume(String volumeName, OptionalInt ownerUid, OptionalInt ownerGid,
                                   Optional<String> rootPermissions, String initImage) {
        ensureSharedVolumeRoot(volumeName,
                ownerUid.isPresent() ? OptionalLong.of(ownerUid.getAsInt()) : OptionalLong.empty(),
                ownerGid.isPresent() ? OptionalLong.of(ownerGid.getAsInt()) : OptionalLong.empty(),
                rootPermissions, initImage);
    }

    /** True when {@code permissions} is 3 to 4 octal digits (e.g. "0755", "755", "2775"). */
    public static boolean isValidPermissions(String permissions) {
        return permissions != null && PERMISSIONS_PATTERN.matcher(permissions).matches();
    }

    /** Initialises an access point's root without narrowing AWS's unsigned 32-bit POSIX ids. */
    public void ensureSharedVolume(String volumeName, long ownerUid, long ownerGid,
                                   String rootPermissions, String initImage) {
        ensureSharedVolumeRoot(volumeName, OptionalLong.of(ownerUid), OptionalLong.of(ownerGid),
                Optional.of(rootPermissions), initImage);
    }

    private void ensureSharedVolumeRoot(String volumeName, OptionalLong ownerUid, OptionalLong ownerGid,
                                        Optional<String> rootPermissions, String initImage) {
        // A volume created now is root:root 0755 again, so a memo left by a volume of the same name
        // that was removed since (e.g. DeleteApplication, docker volume rm) is stale.
        if (createVolumeIfAbsent(volumeName, ContainerStorageHelper.defaultLabels(config))) {
            initializedSharedVolumes.remove(volumeName);
        }
        if (rootPermissions.isEmpty() && ownerUid.isEmpty() && ownerGid.isEmpty()) {
            return;
        }
        // An EFS access point's CreationInfo requires OwnerUid and OwnerGid together; reject a
        // partial ownership config rather than emitting a malformed `chown uid:` (whose trailing
        // colon makes chown resolve the login group and fail in busybox for an unknown uid).
        if (ownerUid.isPresent() != ownerGid.isPresent()) {
            throw new IllegalArgumentException(
                    "floci.storage.efs owner-uid and owner-gid must be set together");
        }
        // Validate before splicing into the helper's `sh -c`, matching CreationInfo.Permissions
        // (^[0-7]{3,4}$), so a typo can't produce a mangled script that soft-fails.
        rootPermissions.ifPresent(p -> {
            if (!isValidPermissions(p)) {
                throw new IllegalArgumentException(
                        "floci.storage.efs root-permissions must be 3-4 octal digits (e.g. \"0777\","
                                + " or \"2775\" for setgid): " + p);
            }
        });
        // computeIfAbsent runs the one-off init under a per-volume lock, so a concurrent launch for
        // the same volume waits for it to finish rather than mounting a still root:root 0755 root.
        // Returning null on failure leaves the volume unmemoised, so the next launch retries.
        initializedSharedVolumes.computeIfAbsent(volumeName, k -> {
            try {
                initSharedVolumeRoot(volumeName, ownerUid, ownerGid, rootPermissions, initImage);
                return Boolean.TRUE;
            } catch (RuntimeException e) {
                LOG.warnv("Failed to initialise shared volume {0} ownership: {1}", volumeName, e.getMessage());
                return null;
            }
        });
    }

    private void initSharedVolumeRoot(String volumeName, OptionalLong ownerUid, OptionalLong ownerGid,
                                      Optional<String> rootPermissions, String initImage) {
        String mount = "/floci-shared-volume";
        StringBuilder script = new StringBuilder();
        // ownerUid and ownerGid are validated to be present together by the caller, so the chown
        // always has both operands (no trailing colon). setgid is expressed via a 4-digit octal
        // rootPermissions (e.g. "2775"), matching CreationInfo.Permissions.
        if (ownerUid.isPresent() && ownerGid.isPresent()) {
            script.append("chown ").append(ownerUid.getAsLong()).append(':').append(ownerGid.getAsLong())
                    .append(' ').append(mount).append(" && ");
        }
        rootPermissions.ifPresent(p -> script.append("chmod ").append(p).append(' ').append(mount).append(" && "));
        script.append("true");

        String image = (initImage != null && !initImage.isBlank()) ? initImage : "busybox:stable";
        imageCacheService.ensureImageExists(image);

        HostConfig hostConfig = HostConfig.newHostConfig().withMounts(List.of(
                new Mount().withType(MountType.VOLUME).withSource(volumeName).withTarget(mount)));
        CreateContainerCmd command = dockerClient.createContainerCmd(image)
                .withHostConfig(hostConfig)
                .withCmd("sh", "-c", script.toString());
        runStorageInitHelper(command, "shared volume " + volumeName);
        LOG.infov("Initialised shared volume {0} root (cmd: {1})", volumeName, script);
    }

    /**
     * Makes a bind directory writable by the owner of the image's data directory. The
     * helper runs on the Docker daemon's host, using the workload image so custom image
     * ownership is respected. Only the mount root is changed; existing files are untouched.
     * Unlike shared-volume initialisation, failure propagates and every launch checks the root.
     */
    public void ensureBindDirectory(String hostPath, String image, String imageDataPath) {
        imageCacheService.ensureImageExists(image);
        String mount = "/floci-bind-directory";
        String script = "ownership=$(stat -c '%u:%g' \"$1\") && "
                + "chown \"$ownership\" \"$2\" && chmod u+rwx \"$2\"";
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withBinds(new Bind(hostPath, new Volume(mount)));
        CreateContainerCmd command = dockerClient.createContainerCmd(image)
                .withHostConfig(hostConfig)
                .withUser("0")
                .withEntrypoint("sh")
                .withCmd("-c", script, "floci-bind-init", imageDataPath, mount);
        runStorageInitHelper(command, "bind directory " + hostPath);
        LOG.infov("Initialised bind directory {0} using ownership from {1}:{2}", hostPath, image, imageDataPath);
    }

    private void runStorageInitHelper(CreateContainerCmd command, String description) {
        CreateContainerResponse created = command.withLabels(ContainerStorageHelper.defaultLabels(config)).exec();
        String helperId = created.getId();
        try {
            startContainer(helperId);
            Integer status = dockerClient.waitContainerCmd(helperId)
                    .exec(new WaitContainerResultCallback())
                    .awaitStatusCode(60, TimeUnit.SECONDS);
            if (status == null || status != 0) {
                throw new IllegalStateException("Storage init for " + description + " exited with status " + status);
            }
        } finally {
            try {
                dockerClient.removeContainerCmd(helperId).withForce(true).exec();
            } catch (Exception e) {
                LOG.warnv("Failed to remove storage init helper {0} for {1}: {2}",
                        helperId, description, e.getMessage());
            }
        }
    }

    /**
     * Removes a named Docker volume, ignoring errors if it does not exist or is still in use.
     * Returns whether the volume is confirmed gone: true if it was removed or was already absent,
     * false if Docker refused (e.g. still in use by a container) or the attempt failed for some
     * other reason (e.g. a transient daemon error). Callers that need to retry a removal should
     * treat false as "unconfirmed, try again later" rather than "definitely still there" - a single
     * boolean here can't always distinguish those two cases from the daemon's response alone.
     */
    public boolean removeVolume(String volumeName) {
        try {
            dockerClient.removeVolumeCmd(volumeName).exec();
            LOG.debugv("Removed volume {0}", volumeName);
            return true;
        } catch (NotFoundException e) {
            // Already gone, which satisfies the caller's goal just as much as removing it would.
            return true;
        } catch (Exception e) {
            LOG.warnv("Error removing volume {0}: {1}", volumeName, e.getMessage());
            return false;
        }
    }

    /** Removes a named Docker volume and propagates failures so callers can retry safely. */
    public void removeVolumeStrict(String volumeName) {
        try {
            dockerClient.removeVolumeCmd(volumeName).exec();
            LOG.debugv("Removed volume {0}", volumeName);
        } catch (NotFoundException e) {
            // Already gone — nothing to do.
        } catch (Exception e) {
            throw new IllegalStateException("Failed to remove volume " + volumeName, e);
        }
    }

    /**
     * Finds an existing container by name.
     *
     * @param name the container name to search for
     * @return the container if found
     */
    public Optional<Container> findByName(String name) {
        try {
            List<Container> containers = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .exec();

            for (Container c : containers) {
                String[] names = c.getNames();
                if (names == null) {
                    continue;
                }
                for (String n : names) {
                    // Docker prefixes names with /
                    if (n.equals("/" + name) || n.equals(name)) {
                        return Optional.of(c);
                    }
                }
            }
        } catch (Exception e) {
            LOG.debugv("Error searching for container {0}: {1}", name, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Reads the environment a container was created with. The {@link Container} summaries
     * returned by {@link #findByName} carry no environment, so callers that must compare an
     * adoption candidate's baked-in configuration against current settings have to inspect it.
     *
     * <p>An unreadable container is reported as {@link Optional#empty()} rather than as an empty
     * environment: callers act destructively on what they find here, and "the container declares
     * no variables" and "the runtime would not tell me" must not lead to the same decision.
     *
     * @param containerId the container ID to inspect
     * @return the container's environment as {@code KEY=value} entries, or empty if it could
     *     not be read at all
     */
    public Optional<List<String>> containerEnv(String containerId) {
        try {
            String[] env = dockerClient.inspectContainerCmd(containerId).exec().getConfig().getEnv();
            return Optional.of(env == null ? List.of() : List.of(env));
        } catch (Exception e) {
            LOG.debugv("Could not read environment of container {0}: {1}", containerId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Labels declared on an image, empty when they could not be read.
     *
     * <p>Ensures the image is present first, so the labels of an image that has never been pulled
     * are still readable. A failure is reported as empty rather than thrown: labels are advisory
     * metadata, and a caller that cannot read them must fall back to its own defaults rather than
     * fail the start it was preparing.
     */
    public Optional<Map<String, String>> imageLabels(String image) {
        try {
            String resolved = imageCacheService.ensureImageExists(image);
            Map<String, String> labels = dockerClient.inspectImageCmd(resolved).exec().getConfig().getLabels();
            return Optional.of(labels == null ? Map.of() : Map.copyOf(labels));
        } catch (Exception e) {
            LOG.debugv("Could not read labels of image {0}: {1}", image, e.getMessage());
            return Optional.empty();
        }
    }

    /** Whether a container exists and is running — distinguishing "gone" from "cannot tell". */
    public enum ContainerPresence {
        /** The container exists and is running. */
        RUNNING,
        /** The container exists but has exited or has not been started. */
        STOPPED,
        /** The container no longer exists. */
        ABSENT,
        /** The container runtime could not be reached, so its state is unknown. */
        UNKNOWN
    }

    /**
     * Probes whether a container is still there. Callers use this to tell a sidecar that has
     * genuinely vanished from one that is merely slow, so that recovery is triggered by the
     * former and never by the latter.
     *
     * @param containerId the container ID to probe, may be null
     * @return the container's presence, {@link ContainerPresence#UNKNOWN} if it could not be probed
     */
    public ContainerPresence presenceOf(String containerId) {
        if (containerId == null) {
            return ContainerPresence.ABSENT;
        }
        try {
            InspectContainerResponse.ContainerState state =
                    dockerClient.inspectContainerCmd(containerId).exec().getState();
            boolean running = state != null && Boolean.TRUE.equals(state.getRunning());
            return running ? ContainerPresence.RUNNING : ContainerPresence.STOPPED;
        } catch (NotFoundException e) {
            return ContainerPresence.ABSENT;
        } catch (Exception e) {
            LOG.debugv("Could not probe container {0}: {1}", containerId, e.getMessage());
            return ContainerPresence.UNKNOWN;
        }
    }

    /**
     * Adopts an existing container, starting it if stopped.
     * Useful for services like ECR that reuse containers across restarts.
     *
     * @param containerId the container ID to adopt
     * @param ports the container ports to resolve endpoints for
     * @return information about the adopted container
     */
    public ContainerInfo adopt(String containerId, List<Integer> ports) {
        return adopt(containerId, ports, Optional.empty());
    }

    /**
     * Adopts a container and resolves its endpoints on {@code preferredNetwork} when it is attached
     * there, as {@link #createAndStart} does with the spec's network. A container that is on the
     * default bridge as well as that network is otherwise reached at whichever address comes first.
     */
    public ContainerInfo adopt(String containerId, List<Integer> ports, Optional<String> preferredNetwork) {
        LOG.infov("Adopting existing container {0}", containerId);

        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
        boolean running = Boolean.TRUE.equals(inspect.getState().getRunning());

        if (!running) {
            startContainer(containerId);
            LOG.infov("Started adopted container {0}", containerId);
            inspect = dockerClient.inspectContainerCmd(containerId).exec();
        }

        Map<Integer, EndpointInfo> endpoints = new HashMap<>();
        Map<Integer, Integer> publishedHostPorts = new HashMap<>();
        for (int port : ports) {
            endpoints.put(port, resolveEndpoint(inspect, port, preferredNetwork.orElse(null)));
            OptionalInt published = readPublishedHostPort(inspect, port);
            if (published.isPresent()) {
                publishedHostPorts.put(port, published.getAsInt());
            }
        }

        return new ContainerInfo(containerId, endpoints, publishedHostPorts);
    }

    /**
     * Reads the host port a container's internal port is published on, independent of
     * whether Floci itself runs inside a container. Unlike {@link #resolveEndpoint} —
     * which switches to container-IP + internal port in container mode — this always
     * reads the port binding, for URIs consumed by the host-side Docker daemon.
     */
    private static OptionalInt readPublishedHostPort(InspectContainerResponse inspect, int containerPort) {
        Ports ports = inspect.getNetworkSettings().getPorts();
        if (ports != null) {
            Ports.Binding[] binding = ports.getBindings().get(ExposedPort.tcp(containerPort));
            if (binding != null && binding.length > 0) {
                try {
                    return OptionalInt.of(Integer.parseInt(binding[0].getHostPortSpec()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Removes a container by name if it exists. Useful for cleaning up stale containers
     * from previous runs before creating a new one.
     *
     * @param name the container name to remove
     */
    public void removeIfExists(String name) {
        try {
            dockerClient.removeContainerCmd(name).withForce(true).exec();
            LOG.infov("Removed stale container {0}", name);
        } catch (NotFoundException e) {
            // Not found - normal case
        } catch (Exception e) {
            LOG.debugv("Could not remove container {0}: {1}", name, e.getMessage());
        }
    }

    /**
     * Removes a container by name, failing when Docker cannot confirm removal.
     *
     * <p>Callers that are about to reuse a fixed container name must use this variant so a
     * daemon failure cannot be mistaken for successful stale-container cleanup.
     *
     * @param name the container name to remove
     */
    public void removeIfExistsStrict(String name) {
        removeIfExistsStrict(name, false);
    }

    /** Removes a container, optionally reclaiming anonymous volumes that are no longer in use. */
    public void removeIfExistsStrict(String name, boolean removeAnonymousVolumes) {
        try {
            RemoveContainerCmd remove = dockerClient.removeContainerCmd(name).withForce(true);
            if (removeAnonymousVolumes) {
                remove.withRemoveVolumes(true);
            }
            remove.exec();
            LOG.infov("Removed stale container {0}", name);
        } catch (NotFoundException e) {
            // Not found means the fixed name is available.
        } catch (Exception e) {
            throw new IllegalStateException("Failed to remove stale container " + name, e);
        }
    }

    /**
     * Returns whether the container is currently running. A missing container is treated as
     * not-running; any other Docker error (e.g. an inspect timeout under daemon overload) is also
     * treated as not-running, so a hung/dead container is not reused from the warm pool. A false
     * negative merely triggers a clean cold-start, which is far cheaper than blocking until the
     * function timeout. Callers that must not act on a failed probe use {@link #probeContainer}.
     *
     * @param containerId the container ID to inspect
     * @return true only if the container exists and is reported as running; false on any error
     */
    public boolean isContainerRunning(String containerId) {
        return probeContainer(containerId) == ContainerLiveness.RUNNING;
    }

    /**
     * Probes the container and tells a definite answer from a failed probe.
     *
     * @param containerId the container ID to inspect
     * @return RUNNING or NOT_RUNNING when Docker answered (a missing container is NOT_RUNNING),
     *         UNKNOWN when the inspect call itself failed
     */
    public ContainerLiveness probeContainer(String containerId) {
        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            return Boolean.TRUE.equals(inspect.getState().getRunning())
                    ? ContainerLiveness.RUNNING
                    : ContainerLiveness.NOT_RUNNING;
        } catch (NotFoundException e) {
            return ContainerLiveness.NOT_RUNNING;
        } catch (Exception e) {
            LOG.warnv("Liveness check failed for container {0}; state unknown: {1}",
                    containerId, e.getMessage());
            return ContainerLiveness.UNKNOWN;
        }
    }

    /**
     * Resolves the endpoint (host and port) to connect to a specific container port.
     *
     * @param containerId the container ID
     * @param containerPort the container port to resolve
     * @return the endpoint information
     */
    public EndpointInfo resolveEndpoint(String containerId, int containerPort) {
        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
        return resolveEndpoint(inspect, containerPort, null, true);
    }

    /**
     * Like {@link #resolveEndpoint(String, int)}, but prefers the container's address on
     * {@code preferredNetwork}, as {@link #createAndStart} does with the spec's network. A
     * container that publishes ports is created on the default bridge and connected to its
     * service network after start, so without a preference the bridge address, which Floci
     * usually cannot reach, can be returned instead.
     */
    public EndpointInfo resolveEndpoint(String containerId, int containerPort, String preferredNetwork) {
        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
        return resolveEndpoint(inspect, containerPort, preferredNetwork, true);
    }

    /**
     * Resolves the container's IP on its Docker network, independent of whether Floci runs
     * natively or in a container. Used for addresses that sibling containers (not Floci itself)
     * must dial — e.g. ElastiCache cluster-bus peering between Valkey nodes.
     */
    public String resolveContainerNetworkIp(String containerId, String preferredNetwork) {
        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
        return resolveContainerIp(inspect, preferredNetwork);
    }

    /**
     * Returns the underlying DockerClient for operations not covered by this manager.
     * Prefer using manager methods when available.
     */
    public DockerClient getDockerClient() {
        return dockerClient;
    }

    /**
     * Returns {@code true} if the container runtime (Docker, Moby, or Podman) has a volume
     * with the given name. The volume does not need to be attached to the current container.
     * <p>
     * This method uses the Docker Engine API ({@code /volumes/{name}}) which is supported
     * by Docker, Moby, and Podman runtimes on all operating systems.
     *
     * @param name the volume name to look up
     * @return {@code true} if the volume exists, {@code false} otherwise
     */
    public boolean volumeExists(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        // Is a Unix absolute or relative path (e.g. "/var/lib/data", "./data", "../data")
        if (name.startsWith("/") || name.startsWith(".")) {
            return false;
        }
        // Is a Windows absolute path (e.g. "C:\Users\data", "D:/sources/data")
        if (name.length() >= 3 && Character.isLetter(name.charAt(0))
                && name.charAt(1) == ':' && (name.charAt(2) == '\\' || name.charAt(2) == '/')) {
            return false;
        }
        try {
            dockerClient.inspectVolumeCmd(name).exec();
            LOG.debugv("Volume ''{0}'' exists in the container runtime", name);
            return true;
        } catch (NotFoundException e) {
            LOG.debugv("Volume ''{0}'' not found in the container runtime", name);
            return false;
        } catch (DockerException e) {
            LOG.warnv("Failed to inspect volume ''{0}'': {1}", name, e.getMessage());
            return false;
        }
    }

    /**
     * The labels on a named volume, or empty when it does not exist or the runtime cannot be
     * queried, so a caller deciding whether a volume is its own can fail closed.
     */
    public Optional<Map<String, String>> tryVolumeLabels(String name) {
        try {
            Map<String, String> labels = dockerClient.inspectVolumeCmd(name).exec().getLabels();
            return Optional.of(labels == null ? Map.of() : labels);
        } catch (NotFoundException e) {
            LOG.debugv("Volume ''{0}'' not found while reading its labels", name);
            return Optional.empty();
        } catch (DockerException e) {
            LOG.warnv("Failed to read labels of volume ''{0}'': {1}", name, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Attempts to take an authoritative snapshot of all named volumes in the container runtime.
     * An empty optional means the runtime could not be queried; it is intentionally distinct from
     * a successful query that returned an empty set so cleanup callers fail closed.
     */
    public Optional<Set<String>> tryListVolumeNames() {
        try {
            ListVolumesResponse response = dockerClient.listVolumesCmd().exec();
            if (response == null) {
                LOG.warn("Container runtime returned no volume-list response");
                return Optional.empty();
            }
            List<InspectVolumeResponse> volumes = response.getVolumes();
            if (volumes == null) {
                LOG.warn("Container runtime returned a volume-list response without an inventory");
                return Optional.empty();
            }
            Set<String> names = volumes.stream()
                    .map(InspectVolumeResponse::getName)
                    .filter(name -> name != null && !name.isBlank())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return Optional.of(names);
        } catch (RuntimeException e) {
            LOG.warnv("Failed to list container-runtime volumes: {0}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Capability names as a task definition or a caller gives them, with or without the
     * {@code CAP_} prefix and in any case. A name Docker does not know fails the create, as
     * {@code docker run --cap-add} does.
     */
    private static List<Capability> capabilities(List<String> names) {
        List<Capability> result = new ArrayList<>();
        for (String name : names) {
            String normalized = name.trim().toUpperCase(Locale.ROOT);
            if (normalized.startsWith("CAP_")) {
                normalized = normalized.substring("CAP_".length());
            }
            try {
                Capability capability = Capability.valueOf(normalized);
                if (!result.contains(capability)) {
                    result.add(capability);
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown Linux capability: " + name, e);
            }
        }
        return result;
    }

    private HostConfig buildHostConfig(ContainerSpec spec) {
        HostConfig hostConfig = HostConfig.newHostConfig();

        // Privileged mode (required for e.g. k3s containers)
        if (spec.privileged()) {
            hostConfig.withPrivileged(true);
        }
        LinuxOptions linux = spec.linuxOptions();
        List<Capability> capDrop = new ArrayList<>();
        List<Capability> capAdd = capabilities(linux.capAdd());
        if ("true".equals(ContainerStorageHelper.labelValue(
                spec.labels(), ContainerStorageHelper.SECURITY_GROUP_WORKLOAD_LABEL))) {
            // The security group is enforced inside the namespace the workload shares, so the
            // workload must not be able to rewrite it, whatever capabilities it asks for. Docker
            // applies a named add over a drop, so the two are taken out of the adds as well; ALL
            // among the adds is every capability but the dropped ones, so it can stay.
            capDrop.add(Capability.NET_ADMIN);
            capDrop.add(Capability.NET_RAW);
            capAdd.removeAll(capDrop);
        }
        for (Capability capability : capabilities(linux.capDrop())) {
            if (!capDrop.contains(capability)) {
                capDrop.add(capability);
            }
        }
        // The firewall helper only has to program nftables in the namespace it already owns,
        // which needs CAP_NET_ADMIN and nothing else that privileged mode would also grant.
        if ("true".equals(ContainerStorageHelper.labelValue(
                spec.labels(), ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL))
                && !capAdd.contains(Capability.NET_ADMIN)) {
            capAdd.add(Capability.NET_ADMIN);
        }
        if (!capDrop.isEmpty()) {
            hostConfig.withCapDrop(capDrop.toArray(new Capability[0]));
        }
        if (!capAdd.isEmpty()) {
            hostConfig.withCapAdd(capAdd.toArray(new Capability[0]));
        }
        if (!linux.devices().isEmpty()) {
            hostConfig.withDevices(linux.devices());
        }
        if (linux.shmSizeBytes() != null) {
            hostConfig.withShmSize(linux.shmSizeBytes());
        }
        if (!linux.tmpfs().isEmpty()) {
            hostConfig.withTmpFs(linux.tmpfs());
        }

        if (spec.cgroupnsMode() != null && !spec.cgroupnsMode().isBlank()) {
            hostConfig.withCgroupnsMode(spec.cgroupnsMode());
        }

        // Supplementary groups (Docker --group-add), e.g. to give a process access to a
        // group-shared volume without changing its primary uid/gid.
        if (spec.groupAdd() != null && !spec.groupAdd().isEmpty()) {
            hostConfig.withGroupAdd(spec.groupAdd());
        }

        // Memory limit
        if (spec.hasMemoryLimit()) {
            hostConfig.withMemory(spec.memoryBytes());
        }
        if (linux.memorySwapBytes() != null) {
            hostConfig.withMemorySwap(linux.memorySwapBytes());
        }
        if (linux.memorySwappiness() != null) {
            hostConfig.withMemorySwappiness(linux.memorySwappiness());
        }

        // CPU quota and weight
        if (spec.nanoCpus() != null && spec.nanoCpus() > 0) {
            hostConfig.withNanoCPUs(clampToHostCpus(spec.nanoCpus()));
        }
        if (spec.cpuShares() != null && spec.cpuShares() > 0) {
            hostConfig.withCpuShares(spec.cpuShares());
        }
        if (spec.readonlyRootfs()) {
            hostConfig.withReadonlyRootfs(true);
        }
        if (spec.init()) {
            hostConfig.withInit(true);
        }

        // Port bindings — services decide whether to request them based on their
        // own in-container-vs-native logic. When Floci runs inside Docker, most
        // backends are reached via the docker network IP, so services omit the
        // binding. ECR's sibling registry is the exception: it always publishes
        // its port because host-side docker clients (and CDK in compat tests)
        // connect via localhost:<hostPort>.
        if (spec.publishesPorts()) {
            Ports ports = new Ports();
            for (Map.Entry<Integer, Integer> entry : spec.portBindings().entrySet()) {
                int containerPort = entry.getKey();
                int hostPort = entry.getValue();
                ports.bind(ExposedPort.tcp(containerPort), bindingFor(spec, containerPort, hostPort));
                LOG.debugv("Port binding: {0} -> {1}", String.valueOf(containerPort),
                        hostPort == 0 ? "dynamic" : String.valueOf(hostPort));
            }
            hostConfig.withPortBindings(ports);
        } else if (spec.hasPortBindings()) {
            LOG.infov("Container {0} joins network {1}, which publishes no host ports; it serves {2} directly there",
                    spec.name(), spec.networkMode(), spec.portBindings().keySet());
        }

        // Network mode: only set during creation when no host ports are published.
        // withNetworkMode() + port bindings suppresses port publishing on macOS Docker Desktop,
        // so containers with published ports (e.g. ECR registry) connect to the network
        // after start via connectToNetworkCmd() instead. host, none and container:<id> publish
        // nothing and cannot be connected after creation, so they always go on the HostConfig.
        if (spec.networkMode() != null && !spec.networkMode().isBlank() && !spec.publishesPorts()) {
            hostConfig.withNetworkMode(spec.networkMode());
        }

        // Mounts (named volumes, bind mounts)
        if (spec.mounts() != null && !spec.mounts().isEmpty()) {
            hostConfig.withMounts(spec.mounts());
        }

        // Legacy binds
        if (spec.binds() != null && !spec.binds().isEmpty()) {
            hostConfig.withBinds(spec.binds().toArray(new Bind[0]));
        }

        if (spec.volumesFrom() != null && !spec.volumesFrom().isEmpty()) {
            hostConfig.withVolumesFrom(spec.volumesFrom());
        }

        // Docker rejects extra_hosts together with container:<id> network mode. Containers
        // sharing another container's network namespace already inherit its network path.
        if (spec.extraHosts() != null && !spec.extraHosts().isEmpty()
                && !isContainerNetworkMode(spec.networkMode())) {
            hostConfig.withExtraHosts(spec.extraHosts().toArray(new String[0]));
        }

        // Log configuration (log rotation)
        if (spec.hasLogConfig()) {
            hostConfig.withLogConfig(spec.logConfig());
        }

        // DNS servers — used to inject Floci's embedded DNS so spawned containers
        // can resolve *.localhost.floci.io to Floci's Docker network IP. Docker rejects them
        // together with container:<id> network mode, where the resolver comes from that container.
        if (spec.dnsServers() != null && !spec.dnsServers().isEmpty()
                && !isContainerNetworkMode(spec.networkMode())) {
            hostConfig.withDns(spec.dnsServers().toArray(new String[0]));
        }

        // Device requests (GPUs). Only set when a caller asked: a daemon configured with an
        // accelerator runtime as its default must not start handing devices to every
        // container Floci launches.
        if (spec.hasDeviceRequests()) {
            hostConfig.withDeviceRequests(spec.deviceRequests());
        }

        return hostConfig;
    }

    /**
     * The host interface a published port binds to.
     *
     * <p>An explicit address from {@code portBindingHostIps} wins, so a caller that reads the
     * address from configuration keeps control of it. {@code loopbackPortBindings} is the fixed
     * form of the same decision, for a port that is an implementation detail and must never leave
     * the host. A port in neither binds every interface, which is Docker's own default and what
     * every caller that does not ask has always got.
     *
     * <p>A host port of 0 leaves the port to the daemon, and the endpoint is read back from
     * inspect after start. A port Floci picked itself would be probed where Floci runs, not where
     * the daemon binds it: on Docker Desktop that is the VM, whose ephemeral range lies inside the
     * Mac's, so a VM socket on the same number fails the start with "address already in use".
     */
    private static Ports.Binding bindingFor(ContainerSpec spec, int containerPort, int hostPort) {
        String hostIp = spec.portBindingHostIps() == null
                ? null
                : spec.portBindingHostIps().get(containerPort);
        if (hostIp == null || hostIp.isBlank()) {
            hostIp = spec.loopbackPortBindings().contains(containerPort) ? LOOPBACK_HOST_IP : null;
        }
        return new Ports.Binding(hostIp, hostPort == 0 ? null : String.valueOf(hostPort));
    }

    /**
     * Holds a CPU quota to what the daemon will accept. Docker rejects a NanoCpus above the
     * host's CPU count ("range of CPUs is from 0.01 to 8.00, as there are only 8 CPUs
     * available"), while ECS accepts a task cpu of up to 196608 units, so a 16 vCPU task
     * asked for verbatim would simply fail to start on a smaller machine. Emulating the
     * larger task on fewer CPUs is closer to the caller's intent than refusing to run it.
     */
    private long clampToHostCpus(long nanoCpus) {
        OptionalInt cpus = hostCpuCount();
        if (cpus.isEmpty()) {
            return nanoCpus;
        }
        long ceiling = cpus.getAsInt() * NANO_CPUS_PER_CPU;
        if (nanoCpus <= ceiling) {
            return nanoCpus;
        }
        LOG.warnv("Requested {0} nanoCPUs but the Docker daemon reports {1} CPUs; clamping to {2}",
                nanoCpus, cpus.getAsInt(), ceiling);
        return ceiling;
    }

    /** The daemon's CPU count, resolved once. Empty when the daemon did not report one. */
    private OptionalInt hostCpuCount() {
        OptionalInt cached = hostCpuCount.get();
        if (cached != null) {
            return cached;
        }
        OptionalInt resolved = OptionalInt.empty();
        try {
            Integer ncpu = dockerClient.infoCmd().exec().getNCPU();
            if (ncpu != null && ncpu > 0) {
                resolved = OptionalInt.of(ncpu);
            }
        } catch (RuntimeException e) {
            LOG.debugv(e, "Could not read the Docker daemon's CPU count; leaving CPU quotas unclamped");
        }
        hostCpuCount.set(resolved);
        return resolved;
    }

    private static boolean isContainerNetworkMode(String networkMode) {
        return networkMode != null && networkMode.startsWith(CONTAINER_NETWORK_MODE_PREFIX);
    }

    private static boolean isHostNetworkMode(InspectContainerResponse inspect) {
        HostConfig hostConfig = inspect.getHostConfig();
        return hostConfig != null && "host".equals(hostConfig.getNetworkMode());
    }

    private static String networkNamespaceOwner(InspectContainerResponse inspect) {
        HostConfig hostConfig = inspect.getHostConfig();
        String networkMode = hostConfig == null ? null : hostConfig.getNetworkMode();
        return isContainerNetworkMode(networkMode)
                ? networkMode.substring(CONTAINER_NETWORK_MODE_PREFIX.length())
                : null;
    }

    private Map<Integer, EndpointInfo> resolveEndpoints(String containerId, ContainerSpec spec) {
        if (spec.exposedPorts() == null || spec.exposedPorts().isEmpty()) {
            return Map.of();
        }

        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
        Map<Integer, EndpointInfo> endpoints = new HashMap<>();

        for (int containerPort : spec.exposedPorts()) {
            endpoints.put(containerPort, resolveEndpoint(inspect, containerPort, spec.networkMode()));
        }

        return endpoints;
    }

    private EndpointInfo resolveEndpoint(InspectContainerResponse inspect, int containerPort) {
        return resolveEndpoint(inspect, containerPort, null);
    }

    private EndpointInfo resolveEndpoint(InspectContainerResponse inspect, int containerPort,
                                         String preferredNetwork) {
        return resolveEndpoint(inspect, containerPort, preferredNetwork, false);
    }

    private EndpointInfo resolveEndpoint(InspectContainerResponse inspect, int containerPort,
                                         String preferredNetwork, boolean requireReachable) {
        if (!containerDetector.isRunningInContainer()) {
            // Native mode: use localhost and the bound host port
            Map<ExposedPort, Ports.Binding[]> bindings = inspect.getNetworkSettings().getPorts().getBindings();
            Ports.Binding[] binding = bindings.get(ExposedPort.tcp(containerPort));

            if (binding != null && binding.length > 0) {
                int hostPort = Integer.parseInt(binding[0].getHostPortSpec());
                return new EndpointInfo("localhost", hostPort);
            }
            if (requireReachable) {
                throw new IllegalStateException(
                        "Container " + inspect.getId() + " has no published binding for port " + containerPort);
            }
            // Creation can still expose an internal-only port to another container.
            return new EndpointInfo("localhost", containerPort);
        }
        String host = resolveReachableHost(inspect, preferredNetwork);
        if (requireReachable && (host == null || host.isBlank())) {
            throw new IllegalStateException(
                    "Container " + inspect.getId() + " has no reachable address for port " + containerPort);
        }
        return new EndpointInfo(host, containerPort);
    }

    private String resolveReachableHost(InspectContainerResponse inspect, String preferredNetwork) {
        if (isHostNetworkMode(inspect)) {
            return "localhost";
        }
        String owner = networkNamespaceOwner(inspect);
        if (owner != null) {
            return resolveReachableHost(dockerClient.inspectContainerCmd(owner).exec(), preferredNetwork);
        }
        return resolveContainerIp(inspect, preferredNetwork);
    }

    private String resolveContainerIp(InspectContainerResponse inspect, String preferredNetwork) {
        Map<String, ContainerNetwork> networks = inspect.getNetworkSettings().getNetworks();
        if (networks != null) {
            // Prefer the configured network so that when the container is on both
            // bridge (default) and the service network, we return the right IP.
            if (preferredNetwork != null && networks.containsKey(preferredNetwork)) {
                String ip = networks.get(preferredNetwork).getIpAddress();
                if (ip != null && !ip.isBlank()) {
                    return ip;
                }
            }
            // Fall back to any network
            for (Map.Entry<String, ContainerNetwork> entry : networks.entrySet()) {
                String ip = entry.getValue().getIpAddress();
                if (ip != null && !ip.isBlank()) {
                    return ip;
                }
            }
        }
        // Fallback to the global IP
        return inspect.getNetworkSettings().getIpAddress();
    }

    /**
     * Information about a created or adopted container.
     *
     * @param containerId the Docker container ID
     * @param endpoints map of container port to resolved endpoint (host:port for connection)
     * @param publishedHostPorts map of container port to the host port it is published on;
     *                           a port without a binding is absent
     */
    public record ContainerInfo(
            String containerId,
            Map<Integer, EndpointInfo> endpoints,
            Map<Integer, Integer> publishedHostPorts
    ) {
        public ContainerInfo(String containerId, Map<Integer, EndpointInfo> endpoints) {
            this(containerId, endpoints, Map.of());
        }

        /**
         * Gets the endpoint for a specific container port.
         */
        public EndpointInfo getEndpoint(int containerPort) {
            return endpoints.get(containerPort);
        }

        /**
         * Gets the host port a container port is published on, regardless of whether
         * Floci itself runs inside a container. Empty when the port has no binding.
         */
        public OptionalInt publishedHostPort(int containerPort) {
            Integer published = publishedHostPorts.get(containerPort);
            return published != null ? OptionalInt.of(published) : OptionalInt.empty();
        }
    }

    /**
     * Network endpoint information for connecting to a container.
     *
     * @param host the host to connect to (localhost in native mode, container IP in Docker mode)
     * @param port the port to connect to
     */
    public record EndpointInfo(String host, int port) {
        @Override
        public String toString() {
            return host + ":" + port;
        }
    }
}
