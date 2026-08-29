package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Manages Docker container lifecycle for EC2 instances.
 * Handles launch, stop, start, terminate, and reboot operations.
 * SSH key injection and UserData execution are performed asynchronously after launch.
 * A running instance has a reachable container address, while best-effort link-local IMDS setup,
 * SSH initialization, and UserData may still be completing in the launch worker.
 */
@ApplicationScoped
public class Ec2ContainerManager {

    private static final Logger LOG = Logger.getLogger(Ec2ContainerManager.class);
    private static final String USER_DATA_SCRIPT_PATH = "/tmp/user-data.sh";
    private static final Pattern MIME_BOUNDARY = Pattern.compile("(?im)^content-type:\\s*multipart/[^;]+;\\s*boundary=\"?([^\";\\n\\r]+)\"?.*$");
    private static final List<String> ALLOWED_SSHD_PATHS = List.of("/usr/sbin/sshd", "/usr/local/sbin/sshd", "/sbin/sshd");
    /** Exit code the sshd install probe uses for "sshd is present but scp is not". See startSshd. */
    static final int SSH_CLIENT_MISSING_EXIT_CODE = 2;
    /** Base64 alphabet plus the line breaks base64-encoded UserData is commonly wrapped at. */
    private static final Pattern BASE64_BODY = Pattern.compile("[A-Za-z0-9+/\\s]+={0,2}");
    private static final int MAX_USER_DATA_DECODE_ROUNDS = 3;
    /** Mirrors AwsJsonCborController.decodeBody's cap: no AWS service should need more than
     *  10 MB decompressed, and it bounds how much a caller-controlled gzip stream can expand
     *  to in the shared emulator JVM. */
    private static final int MAX_DECOMPRESSED_USER_DATA_BYTES = 10 * 1024 * 1024;
    /** Caps concurrent UserData gzip decompressions across ALL instance launches, not just one.
     *  Launches run independently on {@link #executor}, an unbounded cached thread pool, so the
     *  per-payload cap above only bounds a single launch's allocation: without this, N concurrent
     *  RunInstances/CreateLaunchConfiguration calls, each smuggling a near-cap gzip payload, could
     *  together decompress N * 10 MB at once in the shared emulator JVM with no aggregate ceiling.
     *  4 permits budgets ~40 MB of decompressed UserData in flight at a time - a few multiples of
     *  the per-payload cap rather than 1, so an ordinary burst of legitimate concurrent launches
     *  (e.g. an Auto Scaling group launching a handful of instances together) is not serialized
     *  down to one decompression at a time, while a launch storm still cannot grow memory usage
     *  without bound. */
    private static final int MAX_CONCURRENT_USER_DATA_DECOMPRESSIONS = 4;
    /** Gates entry to {@link #gunzip}; see {@link #MAX_CONCURRENT_USER_DATA_DECOMPRESSIONS}. */
    private static final Semaphore USER_DATA_DECOMPRESSION_BUDGET =
            new Semaphore(MAX_CONCURRENT_USER_DATA_DECOMPRESSIONS);
    /** Test seam: when non-null, invoked by {@link #gunzip} right after it acquires a
     *  decompression-budget permit and before it starts decompressing, so tests can observe and
     *  serialize concurrent decompressions deterministically. Always null in production. */
    static volatile Runnable userDataDecompressionTestHook;

    static int containerBridgeIpAttempts = 30;
    static long containerBridgeIpPollMillis = 500;

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final DockerHostResolver dockerHostResolver;
    private final DockerClient dockerClient;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;
    private final Ec2MetadataServer metadataServer;
    private final Ec2PortForwardManager portForwardManager;
    private final RegionResolver regionResolver;
    private final ContainerNetworkReachability containerNetworkReachability;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ec2-container-launcher");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public Ec2ContainerManager(ContainerBuilder containerBuilder,
                               ContainerLifecycleManager lifecycleManager,
                               ContainerLogStreamer logStreamer,
                               ContainerDetector containerDetector,
                               DockerHostResolver dockerHostResolver,
                               DockerClient dockerClient,
                               PortAllocator portAllocator,
                               EmulatorConfig config,
                               Ec2MetadataServer metadataServer,
                               Ec2PortForwardManager portForwardManager,
                               RegionResolver regionResolver,
                               ContainerNetworkReachability containerNetworkReachability) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.dockerHostResolver = dockerHostResolver;
        this.dockerClient = dockerClient;
        this.portAllocator = portAllocator;
        this.config = config;
        this.regionResolver = regionResolver;
        this.metadataServer = metadataServer;
        this.portForwardManager = portForwardManager;
        this.containerNetworkReachability = containerNetworkReachability;
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    /**
     * Launches a Docker container for the given EC2 instance.
     * The instance starts in pending state; an async thread transitions it to running
     * and handles SSH key injection and UserData execution.
     *
     * @param instance    the EC2 instance model (mutated in-place as state transitions occur)
     * @param dockerImage Docker image URI resolved from the instance's AMI ID
     * @param publicKey   SSH public key content to inject (may be null)
     * @param region      AWS region (for CloudWatch log group naming)
     */
    public void launch(Instance instance, String dockerImage, String publicKey, String region) {
        launch(instance, ResolvedAmiImage.minimal(dockerImage), publicKey, region, Set.of());
    }

    public void launch(Instance instance, ResolvedAmiImage image, String publicKey, String region) {
        launch(instance, image, publicKey, region, Set.of());
    }

    /**
     * @param appPorts TCP ports opened by the instance's security groups to publish on the host
     *                 via socat sidecars once the container is running (empty for none)
     */
    public void launch(Instance instance, ResolvedAmiImage image, String publicKey, String region, Set<Integer> appPorts) {
        instance.setState(InstanceState.pending());

        executor.submit(() -> {
            try {
                String instanceId = instance.getInstanceId();
                // IMDS endpoint that this container should use
                String flociHost = dockerHostResolver.resolve();
                int imdsPort = config.services().ec2().imdsPort();
                StartedContainer started = createAndStartContainer(instance, image, region, flociHost, imdsPort);
                if (started == null) {
                    return;
                }
                int sshHostPort = started.sshHostPort();
                String containerId = started.containerId();

                if (isLaunchCancelled(instance)) {
                    failLaunch(instance);
                    return;
                }

                // Poll until Docker confirms the container is running
                boolean running = false;
                for (int i = 0; i < 30 && !running; i++) {
                    if (isLaunchCancelled(instance)) {
                        failLaunch(instance);
                        return;
                    }
                    running = lifecycleManager.isContainerRunning(containerId);
                    if (!running) {
                        Thread.sleep(500);
                    }
                }

                if (!running) {
                    LOG.warnv("EC2 instance {0} container {1} did not reach running state", instanceId, containerId);
                    failLaunch(instance);
                    return;
                }

                if (isLaunchCancelled(instance)) {
                    failLaunch(instance);
                    return;
                }

                // Discover the container's bridge IP for IMDS registration.
                // Docker can report the container as running before network
                // settings are populated; wait here so IMDS is registered
                // before link-local metadata validation and UserData run.
                String containerIp = waitForContainerBridgeIp(containerId, instanceId, instance);
                if (containerIp != null && !containerIp.isBlank()) {
                    instance.setContainerBridgeIp(containerIp);
                    exposeReachablePrivateAddress(instance, containerIp, config.services().ec2().awsFaithfulPrivateIp());
                    metadataServer.registerContainer(containerIp, instanceId, instance);
                }
                else {
                    LOG.warnv("EC2 instance {0} container {1} did not receive a usable bridge IP for IMDS",
                            instanceId, containerId);
                    failLaunch(instance);
                    return;
                }

                if (!markRunning(instance)) {
                    failLaunch(instance);
                    return;
                }

                // Set public-facing addresses only for instances whose subnet
                // opts in via MapPublicIpOnLaunch (#1984). Private-subnet
                // instances have no public IP/DNS, matching real EC2.
                if (instance.isAssociatePublicIp()) {
                    exposeReachablePublicAddress(instance);
                }

                LOG.infov("EC2 instance {0} running in container {1} (SSH host port {2})",
                        instanceId, containerId, String.valueOf(sshHostPort));

                // IMDS proxy setup is best effort and has its own bounded commands. The instance
                // is already running once Docker has assigned its reachable network address.
                configureLinkLocalMetadataEndpoint(containerId, instanceId, flociHost, imdsPort);

                // Publish security-group TCP ingress ports on the host via socat sidecars.
                if (appPorts != null && !appPorts.isEmpty()) {
                    portForwardManager.reconcile(instance, appPorts);
                }

                // Inject SSH public key, if one was provided
                if (publicKey != null && !publicKey.isBlank()) {
                    injectSshKey(containerId, publicKey);
                }
                // sshd runs on every instance regardless of whether a key pair was supplied,
                // matching real AWS AMIs (which start it as part of normal boot, independent of
                // key-pair association) - starting it only when a key was present meant
                // run-instances without --key-name produced a "connection refused" instead of AWS's
                // actual behavior: a running daemon with simply nothing to authenticate against.
                startSshd(containerId, instanceId);

                // Execute UserData
                String userData = instance.getUserData();
                if (userData != null && !userData.isBlank()) {
                    executeUserData(containerId, instanceId, userData, region);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failLaunch(instance);
            } catch (Exception e) {
                LOG.warnv("Failed to launch EC2 instance {0}: {1}", instance.getInstanceId(), e.getMessage());
                failLaunch(instance);
            }
        });
    }

    private StartedContainer createAndStartContainer(Instance instance, ResolvedAmiImage image, String region,
                                                     String flociHost, int imdsPort) {
        String instanceId = instance.getInstanceId();
        String containerName = ContainerStorageHelper.resourceName(config, "ec2", null, instanceId);
        String imdsEndpoint = "http://" + flociHost + ":" + imdsPort;
        String serviceEndpoint = "http://" + flociHost + ":4566";

        while (true) {
            if (isLaunchCancelled(instance)) {
                return null;
            }
            int sshHostPort = portAllocator.allocate(
                    config.services().ec2().sshPortRangeStart(),
                    config.services().ec2().sshPortRangeEnd());
            ContainerSpec spec = buildContainerSpec(containerName, image, region, serviceEndpoint, imdsEndpoint,
                    instanceId, sshHostPort);
            String containerId = null;
            boolean recorded = false;
            try {
                containerId = lifecycleManager.create(spec);
                if (!recordCreatedContainer(instance, containerId, sshHostPort)) {
                    lifecycleManager.removeIfExists(containerId);
                    portAllocator.release(sshHostPort);
                    return null;
                }
                recorded = true;
                lifecycleManager.startCreated(containerId, spec);
                return new StartedContainer(containerId, sshHostPort);
            } catch (Exception e) {
                boolean ownsCleanup = !recorded || clearRecordedContainer(instance, containerId, sshHostPort);
                if (!ownsCleanup) {
                    return null;
                }
                if (containerId != null) {
                    lifecycleManager.removeIfExists(containerId);
                }
                if (isHostPortCollision(e)) {
                    // Docker Desktop can own a published port without exposing it to a host-side
                    // ServerSocket probe. Keep it unavailable for this process and try the next port.
                    portAllocator.markReserved(sshHostPort);
                    LOG.warnv("EC2 instance {0} could not use SSH host port {1}; trying another port",
                            instanceId, String.valueOf(sshHostPort));
                    continue;
                }
                portAllocator.release(sshHostPort);
                throw e;
            }
        }
    }

    private ContainerSpec buildContainerSpec(String containerName, ResolvedAmiImage image, String region,
                                             String serviceEndpoint, String imdsEndpoint, String instanceId,
                                             int sshHostPort) {
        // Minimal images keep the historic tail command, while cloud-image AMI guests can boot their init.
        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image.dockerImage())
                .withName(containerName)
                .withEmbeddedDns()
                .withDockerNetwork(Optional.empty())
                .withEnv(localAwsEnvironment(region, serviceEndpoint, imdsEndpoint))
                .withEnv("AWS_EC2_INSTANCE_ID", instanceId)
                .withPortBinding(22, sshHostPort)
                .withHostDockerInternalOnLinux()
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                        "ec2", instanceId, regionResolver.getAccountId(), region))
                // EC2 instances expose IMDS on 169.254.169.254. Floci needs network administration
                // privileges in the local container to attach that link-local address.
                .withPrivileged(true)
                .withCmd(image.systemd() ? List.of("/sbin/init") : List.of("tail", "-f", "/dev/null"));
        if (image.systemd()) {
            specBuilder
                    .withCgroupnsMode("host")
                    .withMount(new Mount().withType(MountType.TMPFS).withTarget("/run"))
                    .withMount(new Mount().withType(MountType.TMPFS).withTarget("/run/lock"))
                    .withBind("/sys/fs/cgroup", "/sys/fs/cgroup");
        }
        return specBuilder.build();
    }

    private void failLaunch(Instance instance) {
        String containerId;
        int sshHostPort;
        String containerIp;
        boolean alreadyCleaned;
        synchronized (instance) {
            alreadyCleaned = isLaunchCancelledState(instance)
                    && instance.getDockerContainerId() == null
                    && instance.getSshHostPort() <= 0
                    && (instance.getContainerBridgeIp() == null || instance.getContainerBridgeIp().isBlank());
            instance.setState(InstanceState.terminated());
            containerId = instance.getDockerContainerId();
            sshHostPort = instance.getSshHostPort();
            containerIp = instance.getContainerBridgeIp();
            instance.setDockerContainerId(null);
            instance.setSshHostPort(0);
            instance.setContainerBridgeIp(null);
        }
        if (alreadyCleaned) {
            return;
        }
        try {
            portForwardManager.unpublishAll(instance);
        } catch (Exception e) {
            LOG.warnv("Error removing EC2 port forwards during failed launch of {0}: {1}",
                    instance.getInstanceId(), e.getMessage());
        }
        if (containerId != null) {
            try {
                lifecycleManager.removeIfExists(containerId);
            } catch (Exception e) {
                LOG.warnv("Error removing failed EC2 container {0}: {1}", containerId, e.getMessage());
            }
        }
        if (sshHostPort > 0) {
            portAllocator.release(sshHostPort);
        }
        if (containerIp != null && !containerIp.isBlank()) {
            try {
                metadataServer.unregisterContainer(containerIp, instance);
            } catch (Exception e) {
                LOG.warnv("Error unregistering failed EC2 instance {0}: {1}", instance.getInstanceId(), e.getMessage());
            }
        }
    }

    /**
     * Cancels a pending asynchronous launch. Returns {@code false} only when the instance reached
     * running state while the caller was waiting, in which case it must not be torn down as a timeout.
     *
     * <p>The terminal state is established atomically before cleanup. The launch worker checks that
     * state between phases, and {@link #recordCreatedContainer(Instance, String, int)} rejects and
     * removes a container whose blocking Docker create call returns after cancellation. A timed-out
     * CloudFormation waiter therefore cannot leave a late worker able to transition the instance to
     * running.</p>
     */
    boolean cancelLaunch(Instance instance) {
        synchronized (instance) {
            String state = instance.getState() != null ? instance.getState().getName() : null;
            if ("running".equals(state)) {
                return false;
            }
            instance.setState(InstanceState.terminated());
        }
        failLaunch(instance);
        return true;
    }

    private static boolean isLaunchCancelled(Instance instance) {
        synchronized (instance) {
            return isLaunchCancelledState(instance);
        }
    }

    private static boolean markRunning(Instance instance) {
        synchronized (instance) {
            if (isLaunchCancelledState(instance)) {
                return false;
            }
            instance.setState(InstanceState.running());
            return true;
        }
    }

    private static boolean recordCreatedContainer(Instance instance, String containerId, int sshHostPort) {
        synchronized (instance) {
            if (isLaunchCancelledState(instance)) {
                return false;
            }
            instance.setSshHostPort(sshHostPort);
            instance.setDockerContainerId(containerId);
            return true;
        }
    }

    private static boolean clearRecordedContainer(Instance instance, String containerId, int sshHostPort) {
        synchronized (instance) {
            if (!Objects.equals(containerId, instance.getDockerContainerId())
                    || sshHostPort != instance.getSshHostPort()) {
                return false;
            }
            instance.setDockerContainerId(null);
            instance.setSshHostPort(0);
            return true;
        }
    }

    private static boolean isLaunchCancelledState(Instance instance) {
        String state = instance.getState() != null ? instance.getState().getName() : null;
        return "shutting-down".equals(state) || "terminated".equals(state);
    }

    private static boolean isHostPortCollision(Exception exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && (message.toLowerCase(Locale.ROOT).contains("port is already allocated")
                    || message.toLowerCase(Locale.ROOT).contains("address already in use"))) {
                return true;
            }
        }
        return false;
    }

    private record StartedContainer(String containerId, int sshHostPort) {
    }

    /**
     * Synchronous stop for emulator shutdown: tears down the port-forward sidecars and
     * stops the container with a short timeout so N instances cannot exhaust the SIGTERM
     * grace window. Unlike {@link #stop}, runs on the caller's thread (the async executor
     * would be abandoned mid-flight during shutdown) and leaves state handling to the caller.
     */
    public void stopForShutdown(Instance instance) {
        String containerId = instance.getDockerContainerId();
        if (containerId == null) {
            return;
        }
        portForwardManager.unpublishAll(instance);
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(5).exec();
        } catch (NotFoundException e) {
            // already gone
        } catch (Exception e) {
            LOG.warnv("Error stopping EC2 container {0} on shutdown: {1}", containerId, e.getMessage());
        }
    }

    /**
     * Gracefully stops a running container (30 second timeout then SIGKILL).
     * Updates instance state through stopping → stopped.
     */
    public void stop(Instance instance) {
        String containerId = instance.getDockerContainerId();
        if (containerId == null) {
            instance.setState(InstanceState.stopped());
            return;
        }
        instance.setState(InstanceState.stopping());
        executor.submit(() -> {
            // Sidecars forward to the container's current IP, which Docker reassigns on the
            // next start; tear them down so no forward is left pointing at a stale address.
            portForwardManager.unpublishAll(instance);
            try {
                dockerClient.stopContainerCmd(containerId).withTimeout(30).exec();
            } catch (NotFoundException e) {
                // already gone
            } catch (Exception e) {
                LOG.warnv("Error stopping EC2 container {0}: {1}", containerId, e.getMessage());
            }
            instance.setState(InstanceState.stopped());
        });
    }

    /**
     * Starts a previously stopped container.
     * Updates instance state through pending → running.
     */
    public void start(Instance instance) {
        String containerId = instance.getDockerContainerId();
        if (containerId == null) {
            instance.setState(InstanceState.running());
            return;
        }
        instance.setState(InstanceState.pending());
        executor.submit(() -> {
            try {
                dockerClient.startContainerCmd(containerId).exec();
                boolean running = false;
                for (int i = 0; i < 20 && !running; i++) {
                    running = lifecycleManager.isContainerRunning(containerId);
                    if (!running) {
                        Thread.sleep(500);
                    }
                }
                String instanceId = instance.getInstanceId();
                String containerIp = waitForContainerBridgeIp(containerId, instanceId);
                if (containerIp != null && !containerIp.isBlank()) {
                    instance.setContainerBridgeIp(containerIp);
                    exposeReachablePrivateAddress(instance, containerIp, config.services().ec2().awsFaithfulPrivateIp());
                    // Docker hands out a new bridge IP on restart, so the previously reported
                    // public address can now point at another container entirely.
                    if (instance.isAssociatePublicIp() || instance.getPublicIpAddress() != null) {
                        exposeReachablePublicAddress(instance);
                    }
                    metadataServer.registerContainer(containerIp, instanceId, instance);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOG.warnv("Error starting EC2 container {0}: {1}", containerId, e.getMessage());
            }
            instance.setState(InstanceState.running());
        });
    }

    boolean isContainerRunning(String containerId) {
        return containerId != null && !containerId.isBlank() && lifecycleManager.isContainerRunning(containerId);
    }

    boolean restoreMetadataRegistration(Instance instance) {
        if (instance == null || instance.getDockerContainerId() == null) {
            return false;
        }
        String containerId = instance.getDockerContainerId();
        if (!lifecycleManager.isContainerRunning(containerId)) {
            return false;
        }

        String containerIp = getContainerBridgeIp(containerId);
        if (containerIp == null || containerIp.isBlank()) {
            containerIp = instance.getContainerBridgeIp();
        }
        if (containerIp == null || containerIp.isBlank()) {
            LOG.warnv("Could not restore IMDS registration for EC2 instance {0}: no container IP",
                    instance.getInstanceId());
            return false;
        }

        String previousContainerIp = instance.getContainerBridgeIp();
        if (previousContainerIp != null && !previousContainerIp.isBlank() && !previousContainerIp.equals(containerIp)) {
            metadataServer.unregisterContainer(previousContainerIp, instance);
        }
        instance.setContainerBridgeIp(containerIp);
        exposeReachablePrivateAddress(instance, containerIp, config.services().ec2().awsFaithfulPrivateIp());
        if (instance.isAssociatePublicIp() || instance.getPublicIpAddress() != null) {
            exposeReachablePublicAddress(instance);
        }
        metadataServer.registerContainer(containerIp, instance.getInstanceId(), instance);
        return true;
    }

    /**
     * The address Floci is willing to hand out as this instance's public address — chosen so
     * that whatever dials it reaches the guest on the service's own port.
     *
     * <p>Where container IPs are routable that is the container's IP: port 22 is really port
     * 22 there, and so is every other port the guest listens on, with no published mapping to
     * translate. Where they are not, it falls back to {@code 127.0.0.1}, which is where the
     * published high host ports live — reachable, though not on the ports AWS clients assume.
     *
     * @return the address, or null if the instance has no container IP yet
     */
    public String reachablePublicAddress(Instance instance) {
        if (instance == null) {
            return null;
        }
        String containerIp = instance.getContainerBridgeIp();
        if (containerIp == null || containerIp.isBlank()) {
            return null;
        }
        return containerNetworkReachability.isContainerIpRoutable(containerIp) ? containerIp : "127.0.0.1";
    }

    /**
     * Publishes {@link #reachablePublicAddress} as the instance's public IP and DNS name.
     * The DNS name is set to the same literal rather than an AWS-shaped
     * {@code ec2-…​.compute-1.amazonaws.com} hostname, because that hostname resolves nowhere
     * and callers that prefer PublicDnsName over PublicIpAddress would be handed a dead name.
     */
    void exposeReachablePublicAddress(Instance instance) {
        String publicAddress = reachablePublicAddress(instance);
        if (publicAddress == null) {
            return;
        }
        instance.setPublicIpAddress(publicAddress);
        instance.setPublicDnsName("127.0.0.1".equals(publicAddress) ? "localhost" : publicAddress);
    }

    static void exposeReachablePrivateAddress(Instance instance, String privateIp) {
        exposeReachablePrivateAddress(instance, privateIp, false);
    }

    /**
     * Overwrite the instance's reported private address with the container's
     * reachable bridge IP — unless {@code awsFaithful} is true (#1983), in which
     * case the CFN/subnet-allocated private IP set at launch is left untouched.
     * The container bridge IP is tracked separately (setContainerBridgeIp) and
     * used for routing/IMDS regardless of this flag.
     */
    static void exposeReachablePrivateAddress(Instance instance, String privateIp, boolean awsFaithful) {
        if (instance == null || privateIp == null || privateIp.isBlank()) {
            return;
        }
        if (awsFaithful) {
            return;
        }

        String privateDnsName = "ip-" + privateIp.replace('.', '-') + ".ec2.internal";
        instance.setPrivateIpAddress(privateIp);
        instance.setPrivateDnsName(privateDnsName);
        if (instance.getNetworkInterfaces() != null) {
            instance.getNetworkInterfaces().forEach(networkInterface -> {
                networkInterface.setPrivateIpAddress(privateIp);
                networkInterface.setPrivateDnsName(privateDnsName);
            });
        }
    }

    /**
     * Terminates an instance: forcefully removes the container.
     * Updates state through shutting-down → terminated.
     * Sets terminatedAt for TTL pruning.
     */
    public void terminate(Instance instance) {
        String containerId;
        String containerIp;
        int sshHostPort;
        synchronized (instance) {
            containerId = instance.getDockerContainerId();
            containerIp = instance.getContainerBridgeIp();
            sshHostPort = instance.getSshHostPort();
            instance.setState(InstanceState.shuttingDown());
        }
        executor.submit(() -> {
            portForwardManager.unpublishAll(instance);
            if (containerId != null) {
                try {
                    dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                } catch (NotFoundException e) {
                    // already gone
                } catch (Exception e) {
                    LOG.warnv("Error removing EC2 container {0}: {1}", containerId, e.getMessage());
                }
                try {
                    // iptables/veth teardown lags behind container removal; prevents port-reuse conflicts.
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            if (sshHostPort > 0) {
                portAllocator.release(sshHostPort);
            }
            metadataServer.unregisterContainer(containerIp, instance);
            instance.setState(InstanceState.terminated());
            instance.setTerminatedAt(System.currentTimeMillis());
        });
    }

    /**
     * Reboots an instance via docker restart.
     */
    public void reboot(Instance instance) {
        String containerId = instance.getDockerContainerId();
        if (containerId == null) {
            return;
        }
        executor.submit(() -> {
            try {
                dockerClient.restartContainerCmd(containerId).exec();
                LOG.infov("Rebooted EC2 container {0}", containerId);
            } catch (Exception e) {
                LOG.warnv("Error rebooting EC2 container {0}: {1}", containerId, e.getMessage());
            }
        });
    }

    public boolean isContainerRunning(Instance instance) {
        String containerId = instance.getDockerContainerId();
        return containerId != null && lifecycleManager.isContainerRunning(containerId);
    }

    private void injectSshKey(String containerId, String publicKey) {
        try {
            // Ensure .ssh directory exists with correct permissions
            execInContainer(containerId, new String[]{"sh", "-c",
                    "mkdir -p /root/.ssh && chmod 700 /root/.ssh"}, 10);

            // Copy authorized_keys via docker cp
            String keyContent = publicKey.trim() + "\n";
            byte[] tar = buildSingleFileTar("authorized_keys", keyContent.getBytes(StandardCharsets.UTF_8), 0600);
            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withRemotePath("/root/.ssh")
                    .withTarInputStream(new ByteArrayInputStream(tar))
                    .exec();

            execInContainer(containerId, new String[]{"chmod", "600", "/root/.ssh/authorized_keys"}, 5);
            LOG.infov("Injected SSH public key into container {0}", containerId);
        } catch (Exception e) {
            LOG.warnv("Could not inject SSH key into container {0}: {1}", containerId, e.getMessage());
        }
    }

    private void startSshd(String containerId, String instanceId) {
        try {
            // Exit 1 means sshd is absent and there is nothing to start; exit 2 means sshd is
            // there but the client package did not land. Only the first is fatal - see
            // sshdInstallProbeCommand() for why the probe distinguishes them.
            ContainerExecResult install = execInContainerForResult(containerId, sshdInstallProbeCommand(), 120);
            if (install.exitCode() == SSH_CLIENT_MISSING_EXIT_CODE) {
                LOG.warnv("sshd is available on EC2 instance {0} but the OpenSSH client package is not:"
                        + " scp is missing, so provisioners that upload files over scp will fail: {1}",
                        instanceId, install.summary());
            } else if (install.exitCode() != 0) {
                LOG.warnv("Could not install openssh-server for EC2 instance {0}: {1}",
                        instanceId, install.summary());
                return;
            }
            // Generate host keys
            ContainerExecResult keygen = execInContainerForResult(containerId, new String[]{"ssh-keygen", "-A"}, 10);
            if (keygen.exitCode() != 0) {
                LOG.warnv("Could not generate SSH host keys for EC2 instance {0}: {1}",
                        instanceId, keygen.summary());
                return;
            }
            // Modern OpenSSH refuses to start without its privilege-separation directory, and /run
            // is a fresh tmpfs in most container images, so /run/sshd genuinely isn't there yet.
            ContainerExecResult mkdir = execInContainerForResult(containerId,
                    new String[]{"mkdir", "-p", "/run/sshd"}, 5);
            if (mkdir.exitCode() != 0) {
                LOG.warnv("Could not create /run/sshd for EC2 instance {0}: {1}",
                        instanceId, mkdir.summary());
                return;
            }
            // Start sshd without -D so it daemonizes itself and survives this exec session. Since sshd
            // requires execution with an absolute path, several paths are tried until it starts
            for (String sshdPath : ALLOWED_SSHD_PATHS) {
                ContainerExecResult start = execInContainerForResult(containerId, new String[]{sshdPath}, 5);
                if (start.exitCode() != 0) {
                    LOG.warnv("Could not start sshd using path {0} for EC2 instance {1}: {2}", sshdPath, instanceId, start.summary());
                    continue;
                }
                LOG.infov("Started sshd in EC2 instance {0}", instanceId);
                return;
            }
        } catch (Exception e) {
            LOG.warnv("Could not start sshd in EC2 instance {0}: {1}", instanceId, e.getMessage());
        }
    }

    private void executeUserData(String containerId, String instanceId, String userData, String region) {
        try {
            String logGroup = "/aws/ec2/" + instanceId;
            String logStream = logStreamer.generateLogStreamName("user-data");

            List<String> shellScripts = userDataShellScripts(userData);
            if (shellScripts.isEmpty()) {
                LOG.warnv("UserData for EC2 instance {0} did not contain executable shellscript parts, "
                        + "so nothing it was meant to install has run. Floci executes cloud-init "
                        + "text/x-shellscript parts and bare '#!' scripts only.", instanceId);
                return;
            }

            // Execute the script and stream output to CloudWatch
            for (int i = 0; i < shellScripts.size(); i++) {
                executeUserDataShellScript(
                        containerId, instanceId, shellScripts.get(i), i + 1, shellScripts.size(),
                        logGroup, logStream, region
                );
            }
        } catch (Exception e) {
            LOG.warnv("UserData execution failed for EC2 instance {0}: {1}", instanceId, e.getMessage());
        }
    }

    private void executeUserDataShellScript(
            String containerId, String instanceId, String scriptContent, int partNumber, int partCount,
            String logGroup, String logStream, String region
    ) throws Exception {
        byte[] script = scriptContent.getBytes(StandardCharsets.UTF_8);
        byte[] tar = buildSingleFileTar("user-data.sh", script, 0755);
        dockerClient.copyArchiveToContainerCmd(containerId)
                .withRemotePath("/tmp")
                .withTarInputStream(new ByteArrayInputStream(tar))
                .exec();

        // Execute the script directly so Docker honors its shebang, matching cloud-init shellscript behavior.
        String execId = dockerClient.execCreateCmd(containerId)
                .withCmd(userDataExecutionCommand())
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec()
                .getId();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CountDownLatch latch = new CountDownLatch(1);

        dockerClient.execStartCmd(execId).exec(new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame frame) {
                byte[] payload = frame.getPayload();
                if (payload == null) return;
                try { output.write(payload); } catch (IOException ignored) {}
                String line = new String(payload, StandardCharsets.UTF_8).stripTrailing();
                if (!line.isEmpty()) {
                    logStreamer.streamToCloudWatchLogs(logGroup, logStream, region, line);
                }
            }
            @Override
            public void onComplete() { latch.countDown(); }
            @Override
            public void onError(Throwable t) { latch.countDown(); }
        });

        boolean completed = latch.await(30, TimeUnit.MINUTES);
        if (!completed) {
            LOG.warnv("UserData shellscript part {0}/{1} timed out for EC2 instance {2}", partNumber, partCount, instanceId);
            return;
        }

        Long exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
        if (exitCode != null && exitCode != 0) {
            LOG.warnv("UserData shellscript part {0}/{1} failed for EC2 instance {2} with exit code {3}: {4}",
                    partNumber, partCount, instanceId, exitCode, summarizeUserDataOutput(output));
            return;
        }

        LOG.infov("UserData shellscript part {0}/{1} completed for EC2 instance {2}: {3}",
                partNumber, partCount, instanceId, summarizeUserDataOutput(output));
    }

    static List<String> userDataShellScripts(String userData) {
        String decoded = decodeUserDataPayload(userData);
        if (decoded == null || decoded.isBlank()) {
            return List.of();
        }

        String normalized = decoded.replace("\r\n", "\n").replace('\r', '\n');
        String trimmed = normalized.stripLeading();
        if (trimmed.startsWith("#!")) {
            return List.of(normalized);
        }

        Matcher matcher = MIME_BOUNDARY.matcher(normalized);
        if (!matcher.find()) {
            return List.of();
        }

        String boundary = matcher.group(1).trim();
        if (boundary.isEmpty()) {
            return List.of();
        }

        List<String> scripts = new ArrayList<>();
        String marker = "--" + boundary;
        for (String segment : normalized.split(Pattern.quote(marker))) {
            String part = segment.stripLeading();
            if (part.isBlank() || part.startsWith("--")) {
                continue;
            }
            int headerEnd = part.indexOf("\n\n");
            if (headerEnd < 0) {
                continue;
            }
            String headers = part.substring(0, headerEnd);
            String body = part.substring(headerEnd + 2);
            if (hasShellscriptContentType(headers)) {
                scripts.add(body.stripTrailing() + "\n");
            }
        }
        return List.copyOf(scripts);
    }

    /**
     * Unwraps whatever encoding the UserData arrived in until a cloud-init document is
     * visible: gzip, base64, and base64-of-gzip, in any nesting the callers produce.
     *
     * <p>RunInstances decodes the wire-level base64 (and its gzip) before Floci ever stores
     * the value, but not every path does — CreateLaunchConfiguration stores what the client
     * sent, still base64 — and {@code data.cloudinit_config { gzip = true }}, the documented
     * way to pass a multipart cloud-init, produces a payload that survives one decode still
     * compressed. Left unwrapped it matches neither the {@code #!} nor the MIME test below and
     * the whole document is silently dropped, so the instance boots without anything its
     * user-data was supposed to install.
     *
     * @return the decoded document, or the input unchanged when it is not encoded
     */
    static String decodeUserDataPayload(String userData) {
        if (userData == null || userData.isBlank()) {
            return null;
        }
        byte[] payload = userData.getBytes(StandardCharsets.UTF_8);
        // Bounded so a crafted payload cannot make this loop forever; two rounds already
        // covers base64(gzip(document)), the deepest form in practice.
        for (int round = 0; round < MAX_USER_DATA_DECODE_ROUNDS; round++) {
            byte[] next = gunzip(payload);
            if (next == null) {
                next = base64Decode(payload);
            }
            if (next == null) {
                break;
            }
            payload = next;
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    /** @return the decompressed bytes, or null when the input does not start with the gzip magic */
    private static byte[] gunzip(byte[] payload) {
        if (payload.length < 2 || (payload[0] & 0xff) != 0x1f || (payload[1] & 0xff) != 0x8b) {
            return null;
        }
        // Aggregate bound (see MAX_CONCURRENT_USER_DATA_DECOMPRESSIONS): this call runs on
        // whichever launch worker submitted it to the unbounded #executor, independently of every
        // other concurrent launch, so the per-payload cap below is not enough on its own - it only
        // stops one caller from expanding past 10 MB, not many callers doing so at once. Blocking
        // here (rather than failing the launch) mirrors ContainerLauncher's POPULATE_SEMAPHORE: a
        // burst of legitimate concurrent launches queues briefly instead of being rejected.
        try {
            USER_DATA_DECOMPRESSION_BUDGET.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOG.warnv("Interrupted while waiting for UserData decompression budget; discarding payload");
            return null;
        }
        try {
            Runnable hook = userDataDecompressionTestHook;
            if (hook != null) {
                hook.run();
            }
            // Bounded the same way AwsJsonCborController.decodeBody is: a crafted payload can
            // otherwise expand to gigabytes of image-heap while the launch worker holds it, since
            // UserData is caller-controlled and this runs in the shared emulator JVM.
            byte[] buffer = new byte[64 * 1024];
            int totalRead = 0;
            ByteArrayOutputStream decompressed = new ByteArrayOutputStream();
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(payload))) {
                int read;
                while ((read = gzip.read(buffer)) != -1) {
                    totalRead += read;
                    if (totalRead > MAX_DECOMPRESSED_USER_DATA_BYTES) {
                        LOG.warnv("UserData decompressed past {0} bytes; discarding as oversized",
                                MAX_DECOMPRESSED_USER_DATA_BYTES);
                        return null;
                    }
                    decompressed.write(buffer, 0, read);
                }
                return decompressed.toByteArray();
            } catch (IOException e) {
                LOG.warnv("UserData starts with the gzip magic bytes but could not be decompressed: {0}", e.getMessage());
                return null;
            }
        } finally {
            USER_DATA_DECOMPRESSION_BUDGET.release();
        }
    }

    /**
     * @return the decoded bytes when the input is base64 that unwraps to something recognisable
     *         (gzip, a shebang, or MIME headers), null otherwise. The recognisability test is
     *         what keeps a plain shell script — which can be accidentally valid base64 — from
     *         being mangled into binary noise.
     */
    private static byte[] base64Decode(byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8).strip();
        if (text.isEmpty() || !BASE64_BODY.matcher(text).matches()) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getMimeDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (decoded.length < 2) {
            return null;
        }
        if ((decoded[0] & 0xff) == 0x1f && (decoded[1] & 0xff) == 0x8b) {
            return decoded;
        }
        String head = new String(decoded, 0, Math.min(decoded.length, 512), StandardCharsets.UTF_8).stripLeading();
        return head.startsWith("#!") || head.toLowerCase(Locale.ROOT).startsWith("content-type:")
                || head.toLowerCase(Locale.ROOT).startsWith("mime-version:") || head.startsWith("#cloud-config")
                ? decoded
                : null;
    }

    private static boolean hasShellscriptContentType(String headers) {
        for (String line : headers.split("\n")) {
            String lower = line.toLowerCase(Locale.ROOT).strip();
            if (lower.startsWith("content-type:") && lower.contains("text/x-shellscript")) {
                return true;
            }
        }
        return false;
    }

    static String[] userDataExecutionCommand() {
        return new String[]{USER_DATA_SCRIPT_PATH};
    }

    /**
     * The install-and-verify probe {@link #startSshd} execs in the guest. Extracted so tests can
     * assert against the string production actually issues rather than against a copy of it.
     *
     * <p>Installs openssh-server if absent. The trailing "command -v" checks make the script's own
     * exit code the source of truth for whether sshd is actually available afterward - without
     * them, a shell if/elif chain with no matching branch (no dnf/yum/apt-get/apk found) or whose
     * install command itself failed (e.g. no network yet, apt lock held) still exits 0 by bash
     * convention, so every later step in startSshd would silently no-op against a daemon that was
     * never installed while still logging success.
     *
     * <p>The client package is installed alongside the server because provisioning tools need scp
     * <em>on the instance</em>: Packer's default file transfer for a shell provisioner uploads the
     * script with scp, and real AMIs carry it. Installing only openssh-server leaves sftp-server
     * present but /usr/bin/scp absent, so the upload fails with "SCP failed to start. This usually
     * means that SCP is not properly installed on the remote system." The guard tests for scp too:
     * keying it on sshd alone would skip the install entirely on an image that already has the
     * server but no client. Package names differ - openssh-clients on rpm distributions,
     * openssh-client on Debian; apk's openssh already contains both.
     *
     * <p>The two failures are not equally fatal, so the script separates them: exit 1 means no sshd
     * and there is nothing to start, while exit 2 means sshd is there but the client package did
     * not land. A guest that can serve SSH but cannot scp is still worth starting - it just cannot
     * run a Packer shell provisioner - so that case warns and continues rather than leaving the
     * instance unreachable. Checking only sshd at the end would report that state as outright
     * success, which is the silent failure this probe exists to prevent.
     */
    static String[] sshdInstallProbeCommand() {
        return new String[]{"sh", "-c",
            "if ! command -v sshd >/dev/null 2>&1 || ! command -v scp >/dev/null 2>&1; then" +
                    "  if command -v dnf >/dev/null 2>&1; then dnf install -y openssh-server openssh-clients >/dev/null 2>&1;" +
                    // yum is probed after dnf, and the order is load-bearing: Amazon Linux
                    // 2023 and modern Fedora/RHEL ship both, and there dnf is the supported
                    // front end while yum is only a compatibility shim over it. Amazon Linux
                    // 2 -- reached by explicitly requesting ami-amazonlinux2, not the default
                    // image -- ships only yum, so without this branch the chain falls through
                    // and sshd is never installed.
                    "  elif command -v yum >/dev/null 2>&1; then yum install -y openssh-server openssh-clients >/dev/null 2>&1;" +
                    "  elif command -v apt-get >/dev/null 2>&1; then DEBIAN_FRONTEND=noninteractive apt-get install -y openssh-server openssh-client >/dev/null 2>&1;" +
                    "  elif command -v apk >/dev/null 2>&1; then apk add --no-cache openssh >/dev/null 2>&1;" +
                    "  fi;" +
                    "fi;" +
                    "command -v sshd >/dev/null 2>&1 || exit 1;" +
                    "command -v scp >/dev/null 2>&1 || exit 2"};
    }

    static String[] metadataProxyInstallCommand() {
        return new String[]{"sh", "-c", String.join("\n",
                "set -eu",
                "if command -v ip >/dev/null 2>&1 && command -v socat >/dev/null 2>&1 && command -v curl >/dev/null 2>&1; then exit 0; fi",
                "if command -v apt-get >/dev/null 2>&1; then",
                "  apt-get update -qq >/dev/null",
                "  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends iproute2 socat curl ca-certificates >/dev/null",
                "elif command -v dnf >/dev/null 2>&1; then",
                "  dnf install -y iproute socat curl ca-certificates >/dev/null",
                // Same gap as the sshd probe: Amazon Linux 2 has only yum, so on an instance
                // launched from ami-amazonlinux2 this chain reached its else branch and exited 1
                // with "No supported package manager found for IMDS proxy dependencies" --
                // leaving the instance without a link-local IMDS endpoint.
                "elif command -v yum >/dev/null 2>&1; then",
                "  yum install -y iproute socat curl ca-certificates >/dev/null",
                "elif command -v apk >/dev/null 2>&1; then",
                "  apk add --no-cache iproute2 socat curl ca-certificates >/dev/null",
                "else",
                "  echo 'No supported package manager found for IMDS proxy dependencies' >&2",
                "  exit 1",
                "fi")};
    }

    static String[] metadataProxyStartCommand(String flociHost, int imdsPort) {
        return new String[]{"sh", "-c", String.join("\n",
                "set -eu",
                "ip addr show dev lo | grep -q '169.254.169.254/32' || ip addr add 169.254.169.254/32 dev lo",
                "if [ -f /tmp/floci-imds-proxy.pid ] && kill -0 \"$(cat /tmp/floci-imds-proxy.pid)\" 2>/dev/null; then",
                "  exit 0",
                "fi",
                "nohup socat TCP-LISTEN:80,bind=169.254.169.254,fork,reuseaddr TCP:" + flociHost + ":" + imdsPort + " >/tmp/floci-imds-proxy.log 2>&1 &",
                "echo $! > /tmp/floci-imds-proxy.pid",
                "for i in 1 2 3 4 5 6 7 8 9 10 11 12; do",
                "  curl -fsS --max-time 1 http://169.254.169.254/latest/meta-data/instance-id >/dev/null && exit 0",
                "  sleep 1",
                "done",
                "cat /tmp/floci-imds-proxy.log >&2 || true",
                "exit 1")};
    }

    static List<String> localAwsEnvironment(String region, String serviceEndpoint, String imdsEndpoint) {
        return List.of(
                "AWS_EC2_METADATA_SERVICE_ENDPOINT=" + imdsEndpoint,
                "AWS_ENDPOINT_URL=" + serviceEndpoint,
                "AWS_DEFAULT_REGION=" + region,
                "AWS_REGION=" + region,
                "AWS_ACCESS_KEY_ID=test",
                "AWS_SECRET_ACCESS_KEY=test",
                "AWS_SESSION_TOKEN=test-session-token");
    }

    private static String summarizeUserDataOutput(ByteArrayOutputStream output) {
        String text = output.toString(StandardCharsets.UTF_8).stripTrailing();
        if (text.isBlank()) {
            return "(no output)";
        }
        int start = Math.max(0, text.length() - 2048);
        return text.substring(start);
    }

    private void configureLinkLocalMetadataEndpoint(String containerId, String instanceId, String flociHost, int imdsPort) {
        try {
            ContainerExecResult install = execInContainerForResult(containerId, metadataProxyInstallCommand(), 180);
            if (install.exitCode() != 0) {
                LOG.warnv("Could not install IMDS proxy dependencies for EC2 instance {0}: {1}",
                        instanceId, install.summary());
                return;
            }

            ContainerExecResult start = execInContainerForResult(containerId, metadataProxyStartCommand(flociHost, imdsPort), 30);
            if (start.exitCode() != 0) {
                LOG.warnv("Could not start link-local IMDS proxy for EC2 instance {0}: {1}",
                        instanceId, start.summary());
                return;
            }

            LOG.infov("Configured link-local IMDS endpoint for EC2 instance {0}", instanceId);
        } catch (Exception e) {
            LOG.warnv("Could not configure link-local IMDS endpoint for EC2 instance {0}: {1}", instanceId, e.getMessage());
        }
    }

    private void execInContainer(String containerId, String[] cmd, int timeoutSeconds) throws Exception {
        execInContainerForResult(containerId, cmd, timeoutSeconds);
    }

    private ContainerExecResult execInContainerForResult(String containerId, String[] cmd, int timeoutSeconds) throws Exception {
        String execId = dockerClient.execCreateCmd(containerId)
                .withCmd(cmd)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec()
                .getId();

        CountDownLatch latch = new CountDownLatch(1);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        dockerClient.execStartCmd(execId).exec(new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame frame) {
                if (frame.getPayload() != null) {
                    try { output.write(frame.getPayload()); } catch (IOException ignored) {}
                }
            }
            @Override
            public void onComplete() { latch.countDown(); }
            @Override
            public void onError(Throwable t) { latch.countDown(); }
        });
        boolean completed = latch.await(timeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            return new ContainerExecResult(-1, "Timed out after " + timeoutSeconds + "s");
        }
        Long exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
        return new ContainerExecResult(exitCode != null ? exitCode : -1, summarizeUserDataOutput(output));
    }

    record ContainerExecResult(long exitCode, String output) {
        String summary() {
            return output == null || output.isBlank() ? "(no output)" : output;
        }
    }

    private String getContainerBridgeIp(String containerId) {
        try {
            var inspect = dockerClient.inspectContainerCmd(containerId).exec();
            if (inspect.getNetworkSettings() != null) {
                var networks = inspect.getNetworkSettings().getNetworks();
                if (networks != null) {
                    Optional<String> preferredIp = preferredMetadataSourceIp(networks);
                    if (preferredIp.isPresent()) {
                        return preferredIp.get();
                    }
                }
                String ip = inspect.getNetworkSettings().getIpAddress();
                if (ip != null && !ip.isBlank()) {
                    return ip;
                }
            }
        } catch (Exception e) {
            LOG.warnv("Could not inspect container {0} for bridge IP: {1}", containerId, e.getMessage());
        }
        return null;
    }

    private String waitForContainerBridgeIp(String containerId, String instanceId) throws InterruptedException {
        return waitForContainerBridgeIp(containerId, instanceId, null);
    }

    private String waitForContainerBridgeIp(String containerId, String instanceId, Instance instance)
            throws InterruptedException {
        for (int i = 0; i < containerBridgeIpAttempts; i++) {
            if (instance != null && isLaunchCancelled(instance)) {
                return null;
            }
            String containerIp = getContainerBridgeIp(containerId);
            if (containerIp != null && !containerIp.isBlank()) {
                return containerIp;
            }
            Thread.sleep(containerBridgeIpPollMillis);
        }
        LOG.warnv("Timed out waiting for EC2 instance {0} container {1} bridge IP", instanceId, containerId);
        return null;
    }

    static Optional<String> preferredMetadataSourceIp(Map<String, ContainerNetwork> networks) {
        if (networks == null || networks.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> configuredNetworkIp = networks.entrySet().stream()
                .filter(entry -> !"bridge".equals(entry.getKey()))
                .map(Map.Entry::getValue)
                .map(ContainerNetwork::getIpAddress)
                .filter(ip -> ip != null && !ip.isBlank())
                .findFirst();
        if (configuredNetworkIp.isPresent()) {
            return configuredNetworkIp;
        }
        ContainerNetwork bridge = networks.get("bridge");
        if (bridge != null && bridge.getIpAddress() != null && !bridge.getIpAddress().isBlank()) {
            return Optional.of(bridge.getIpAddress());
        }
        return networks.values().stream()
                .map(ContainerNetwork::getIpAddress)
                .filter(ip -> ip != null && !ip.isBlank())
                .findFirst();
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
}
