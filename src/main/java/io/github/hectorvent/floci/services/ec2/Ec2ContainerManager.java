package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages Docker container lifecycle for EC2 instances.
 * Handles launch, stop, start, terminate, and reboot operations.
 * SSH key injection and UserData execution are performed asynchronously after launch.
 */
@ApplicationScoped
public class Ec2ContainerManager {

    private static final Logger LOG = Logger.getLogger(Ec2ContainerManager.class);
    private static final String USER_DATA_SCRIPT_PATH = "/tmp/user-data.sh";
    private static final Pattern MIME_BOUNDARY = Pattern.compile("(?im)^content-type:\\s*multipart/[^;]+;\\s*boundary=\"?([^\";\\n\\r]+)\"?.*$");
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
                               Ec2MetadataServer metadataServer) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.dockerHostResolver = dockerHostResolver;
        this.dockerClient = dockerClient;
        this.portAllocator = portAllocator;
        this.config = config;
        this.metadataServer = metadataServer;
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
        instance.setState(InstanceState.pending());

        executor.submit(() -> {
            try {
                String instanceId = instance.getInstanceId();
                String containerName = "floci-ec2-" + instanceId;

                // Allocate SSH host port
                int sshHostPort = portAllocator.allocate(
                        config.services().ec2().sshPortRangeStart(),
                        config.services().ec2().sshPortRangeEnd());
                instance.setSshHostPort(sshHostPort);

                // IMDS endpoint that this container should use
                String flociHost = dockerHostResolver.resolve();
                int imdsPort = config.services().ec2().imdsPort();
                String imdsEndpoint = "http://" + flociHost + ":" + imdsPort;
                String serviceEndpoint = "http://" + flociHost + ":4566";

                // Build container spec — use tail -f /dev/null to keep container alive
                // regardless of the base image's default CMD.
                ContainerSpec spec = containerBuilder.newContainer(dockerImage)
                        .withName(containerName)
                        .withEmbeddedDns()
                        .withDockerNetwork(Optional.empty())
                        .withEnv(localAwsEnvironment(region, serviceEndpoint, imdsEndpoint))
                        .withEnv("AWS_EC2_INSTANCE_ID", instanceId)
                        .withPortBinding(22, sshHostPort)
                        .withHostDockerInternalOnLinux()
                        .withLogRotation()
                        // EC2 instances expose IMDS on 169.254.169.254. Floci
                        // needs network administration privileges in the local
                        // container to attach that link-local address.
                        .withPrivileged(true)
                        .withCmd(List.of("tail", "-f", "/dev/null"))
                        .build();

                // Create container without starting it
                String containerId = lifecycleManager.create(spec);
                instance.setDockerContainerId(containerId);

                // Start the container
                lifecycleManager.startCreated(containerId, spec);

                // Poll until Docker confirms the container is running
                boolean running = false;
                for (int i = 0; i < 30 && !running; i++) {
                    running = lifecycleManager.isContainerRunning(containerId);
                    if (!running) {
                        Thread.sleep(500);
                    }
                }

                if (!running) {
                    LOG.warnv("EC2 instance {0} container {1} did not reach running state", instanceId, containerId);
                    instance.setState(InstanceState.terminated());
                    return;
                }

                // Discover the container's bridge IP for IMDS registration.
                // Docker can report the container as running before network
                // settings are populated; wait here so IMDS is registered
                // before link-local metadata validation and UserData run.
                String containerIp = waitForContainerBridgeIp(containerId, instanceId);
                if (containerIp != null && !containerIp.isBlank()) {
                    instance.setContainerBridgeIp(containerIp);
                    exposeReachablePrivateAddress(instance, containerIp);
                    metadataServer.registerContainer(containerIp, instanceId, instance);
                }
                else {
                    LOG.warnv("EC2 instance {0} container {1} did not receive a usable bridge IP for IMDS",
                            instanceId, containerId);
                    instance.setState(InstanceState.terminated());
                    return;
                }

                configureLinkLocalMetadataEndpoint(containerId, instanceId, flociHost, imdsPort);

                // Set public-facing addresses
                instance.setPublicIpAddress("127.0.0.1");
                instance.setPublicDnsName("localhost");

                instance.setState(InstanceState.running());
                LOG.infov("EC2 instance {0} running in container {1} (SSH host port {2})",
                        instanceId, containerId, String.valueOf(sshHostPort));

                // Inject SSH public key
                if (publicKey != null && !publicKey.isBlank()) {
                    injectSshKey(containerId, publicKey);
                    startSshd(containerId, instanceId);
                }

                // Execute UserData
                String userData = instance.getUserData();
                if (userData != null && !userData.isBlank()) {
                    executeUserData(containerId, instanceId, userData, region);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                instance.setState(InstanceState.terminated());
            } catch (Exception e) {
                LOG.warnv("Failed to launch EC2 instance {0}: {1}", instance.getInstanceId(), e.getMessage());
                instance.setState(InstanceState.terminated());
            }
        });
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
                    exposeReachablePrivateAddress(instance, containerIp);
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
        exposeReachablePrivateAddress(instance, containerIp);
        metadataServer.registerContainer(containerIp, instance.getInstanceId(), instance);
        return true;
    }

    static void exposeReachablePrivateAddress(Instance instance, String privateIp) {
        if (instance == null || privateIp == null || privateIp.isBlank()) {
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
        String containerId = instance.getDockerContainerId();
        String containerIp = instance.getContainerBridgeIp();
        int sshHostPort = instance.getSshHostPort();
        instance.setState(InstanceState.shuttingDown());
        executor.submit(() -> {
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
            // Install openssh-server if absent
            execInContainer(containerId, new String[]{"sh", "-c",
                    "if ! command -v sshd >/dev/null 2>&1; then" +
                    "  if command -v dnf >/dev/null 2>&1; then dnf install -y openssh-server >/dev/null 2>&1;" +
                    "  elif command -v apt-get >/dev/null 2>&1; then DEBIAN_FRONTEND=noninteractive apt-get install -y openssh-server >/dev/null 2>&1;" +
                    "  elif command -v apk >/dev/null 2>&1; then apk add --no-cache openssh >/dev/null 2>&1;" +
                    "  fi;" +
                    "fi"}, 120);
            // Generate host keys
            execInContainer(containerId, new String[]{"ssh-keygen", "-A"}, 10);
            // Start sshd without -D so it daemonizes itself and survives this exec session
            execInContainer(containerId, new String[]{"/usr/sbin/sshd"}, 5);
            LOG.infov("Started sshd in EC2 instance {0}", instanceId);
        } catch (Exception e) {
            LOG.warnv("Could not start sshd in EC2 instance {0}: {1}", instanceId, e.getMessage());
        }
    }

    private void executeUserData(String containerId, String instanceId, String userData, String region) {
        try {
            List<String> shellScripts = userDataShellScripts(userData);
            if (shellScripts.isEmpty()) {
                LOG.infov("UserData for EC2 instance {0} did not contain executable shellscript parts", instanceId);
                return;
            }

            for (int i = 0; i < shellScripts.size(); i++) {
                executeUserDataShellScript(containerId, instanceId, shellScripts.get(i), i + 1, shellScripts.size());
            }
        } catch (Exception e) {
            LOG.warnv("UserData execution failed for EC2 instance {0}: {1}", instanceId, e.getMessage());
        }
    }

    private void executeUserDataShellScript(String containerId, String instanceId, String scriptContent, int partNumber, int partCount) throws Exception {
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
                if (frame.getPayload() != null) {
                    try { output.write(frame.getPayload()); } catch (IOException ignored) {}
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
        if (userData == null || userData.isBlank()) {
            return List.of();
        }

        String normalized = userData.replace("\r\n", "\n").replace('\r', '\n');
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

    static String[] metadataProxyInstallCommand() {
        return new String[]{"sh", "-c", String.join("\n",
                "set -eu",
                "if command -v ip >/dev/null 2>&1 && command -v socat >/dev/null 2>&1 && command -v curl >/dev/null 2>&1; then exit 0; fi",
                "if command -v apt-get >/dev/null 2>&1; then",
                "  apt-get update -qq >/dev/null",
                "  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends iproute2 socat curl ca-certificates >/dev/null",
                "elif command -v dnf >/dev/null 2>&1; then",
                "  dnf install -y iproute socat curl ca-certificates >/dev/null",
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
        for (int i = 0; i < containerBridgeIpAttempts; i++) {
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
