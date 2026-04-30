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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Manages Docker container lifecycle for EC2 instances.
 * Handles launch, stop, start, terminate, and reboot operations.
 * SSH key injection and UserData execution are performed asynchronously after launch.
 */
@ApplicationScoped
public class Ec2ContainerManager {

    private static final Logger LOG = Logger.getLogger(Ec2ContainerManager.class);

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

                // Build container spec — use tail -f /dev/null to keep container alive
                // regardless of the base image's default CMD.
                ContainerSpec spec = containerBuilder.newContainer(dockerImage)
                        .withName(containerName)
                        .withEnv("AWS_EC2_METADATA_SERVICE_ENDPOINT", imdsEndpoint)
                        .withEnv("AWS_EC2_INSTANCE_ID", instanceId)
                        .withEnv("AWS_DEFAULT_REGION", region)
                        .withPortBinding(22, sshHostPort)
                        .withHostDockerInternalOnLinux()
                        .withLogRotation()
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

                // Discover the container's bridge IP for IMDS registration
                String containerIp = getContainerBridgeIp(containerId);
                if (containerIp != null && !containerIp.isBlank()) {
                    instance.setContainerBridgeIp(containerIp);
                    metadataServer.registerContainer(containerIp, instanceId, instance);
                }

                // Set public-facing addresses
                instance.setPublicIpAddress("127.0.0.1");
                instance.setPublicDnsName("localhost");

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

                instance.setState(InstanceState.running());
                LOG.infov("EC2 instance {0} running in container {1} (SSH host port {2})",
                        instanceId, containerId, sshHostPort);

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
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOG.warnv("Error starting EC2 container {0}: {1}", containerId, e.getMessage());
            }
            instance.setState(InstanceState.running());
        });
    }

    /**
     * Terminates an instance: forcefully removes the container.
     * Updates state through shutting-down → terminated.
     * Sets terminatedAt for TTL pruning.
     */
    public void terminate(Instance instance) {
        String containerId = instance.getDockerContainerId();
        String containerIp = instance.getContainerBridgeIp();
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
            }
            metadataServer.unregisterContainer(containerIp);
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
            byte[] script = userData.getBytes(StandardCharsets.UTF_8);
            byte[] tar = buildSingleFileTar("user-data.sh", script, 0755);
            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withRemotePath("/tmp")
                    .withTarInputStream(new ByteArrayInputStream(tar))
                    .exec();

            String logGroup = "/aws/ec2/" + instanceId;
            String logStream = logStreamer.generateLogStreamName("user-data");

            // Execute the script and stream output to CloudWatch
            String execId = dockerClient.execCreateCmd(containerId)
                    .withCmd("sh", "/tmp/user-data.sh")
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
                LOG.warnv("UserData execution timed out for EC2 instance {0}", instanceId);
            }

            LOG.infov("UserData execution completed for EC2 instance {0}", instanceId);
        } catch (Exception e) {
            LOG.warnv("UserData execution failed for EC2 instance {0}: {1}", instanceId, e.getMessage());
        }
    }

    private void execInContainer(String containerId, String[] cmd, int timeoutSeconds) throws Exception {
        String execId = dockerClient.execCreateCmd(containerId)
                .withCmd(cmd)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec()
                .getId();

        CountDownLatch latch = new CountDownLatch(1);
        dockerClient.execStartCmd(execId).exec(new ResultCallback.Adapter<Frame>() {
            @Override
            public void onComplete() { latch.countDown(); }
            @Override
            public void onError(Throwable t) { latch.countDown(); }
        });
        latch.await(timeoutSeconds, TimeUnit.SECONDS);
    }

    private String getContainerBridgeIp(String containerId) {
        try {
            var inspect = dockerClient.inspectContainerCmd(containerId).exec();
            if (inspect.getNetworkSettings() != null) {
                var networks = inspect.getNetworkSettings().getNetworks();
                if (networks != null) {
                    ContainerNetwork bridge = networks.get("bridge");
                    if (bridge != null && bridge.getIpAddress() != null && !bridge.getIpAddress().isBlank()) {
                        return bridge.getIpAddress();
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
