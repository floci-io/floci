package io.github.hectorvent.floci.services.ecs.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.NetworkBinding;
import io.github.hectorvent.floci.services.ecs.model.PortMapping;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ExposedPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages Docker container lifecycle for ECS tasks.
 * Starts one Docker container per ContainerDefinition in a task and attaches logs to CloudWatch.
 */
@ApplicationScoped
public class EcsContainerManager {

    private static final Logger LOG = Logger.getLogger(EcsContainerManager.class);

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;

    @Inject
    public EcsContainerManager(ContainerBuilder containerBuilder,
                               ContainerLifecycleManager lifecycleManager,
                               ContainerLogStreamer logStreamer,
                               ContainerDetector containerDetector,
                               EmulatorConfig config,
                               RegionResolver regionResolver) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.config = config;
        this.regionResolver = regionResolver;
    }

    /**
     * Starts Docker containers for all container definitions in a task.
     * Updates the task's container list in-place with runtime network bindings and docker IDs.
     */
    public EcsTaskHandle startTask(EcsTask task, TaskDefinition taskDef, String region) {
        String taskId = extractTaskId(task.getTaskArn());

        Map<String, String> containerIds = new LinkedHashMap<>();
        List<Closeable> logStreams = new ArrayList<>();
        List<Container> runtimeContainers = new ArrayList<>();

        for (ContainerDefinition def : taskDef.getContainerDefinitions()) {
            String containerName = "floci-ecs-" + taskId + "-" + def.getName();

            // Build container spec
            ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(def.getImage())
                    .withName(containerName)
                    .withEnv(buildEnvVars(def))
                    .withDockerNetwork(config.services().ecs().dockerNetwork())
                    .withLogRotation();

            // Add memory limit if specified
            if (def.getMemory() != null) {
                specBuilder.withMemoryMb(def.getMemory());
            }

            // Add port mappings. Publish to host only in native mode; in Docker
            // mode ECS consumers reach containers via the docker network IP.
            if (def.getPortMappings() != null) {
                boolean publishToHost = !containerDetector.isRunningInContainer();
                for (PortMapping pm : def.getPortMappings()) {
                    if (publishToHost) {
                        specBuilder.withDynamicPort(pm.containerPort());
                    } else {
                        specBuilder.withExposedPort(pm.containerPort());
                    }
                }
            }

            // Add command and entrypoint if specified
            if (def.getCommand() != null && !def.getCommand().isEmpty()) {
                specBuilder.withCmd(def.getCommand());
            }
            if (def.getEntryPoint() != null && !def.getEntryPoint().isEmpty()) {
                specBuilder.withEntrypoint(def.getEntryPoint());
            }

            ContainerSpec spec = specBuilder.build();

            // Create and start container
            ContainerInfo info = lifecycleManager.createAndStart(spec);
            String dockerId = info.containerId();

            LOG.infov("Created ECS container {0} for task {1} container {2}", dockerId, taskId, def.getName());

            // Resolve network bindings for ECS-specific model
            List<NetworkBinding> networkBindings = resolveNetworkBindings(dockerId, def);

            // Build ECS container model
            Container container = buildContainer(task.getTaskArn(), def, dockerId, networkBindings, region);
            runtimeContainers.add(container);
            containerIds.put(def.getName(), dockerId);

            // Attach log streaming
            String logGroup = "/ecs/" + taskDef.getFamily();
            String logStream = logStreamer.generateLogStreamName(def.getName() + "/" + taskId);

            Closeable logHandle = logStreamer.attach(
                    dockerId, logGroup, logStream, region,
                    "ecs:" + taskDef.getFamily() + ":" + def.getName());
            if (logHandle != null) {
                logStreams.add(logHandle);
            }
        }

        task.setContainers(runtimeContainers);
        task.setLastStatus(TaskStatus.RUNNING.name());
        task.setDesiredStatus(TaskStatus.RUNNING.name());
        task.setStartedAt(Instant.now());

        return new EcsTaskHandle(task.getTaskArn(), containerIds, logStreams);
    }

    /**
     * Stops and removes all Docker containers for a task.
     */
    public void stopTask(EcsTaskHandle handle) {
        stopTaskAndCollectExitCodes(handle);
    }

    /**
     * Closes log streams and removes already-stopped containers without re-inspecting exit codes.
     * Use this from the reconciler when exit codes have already been collected.
     */
    public void cleanupStoppedTask(EcsTaskHandle handle) {
        if (handle == null) {
            return;
        }
        for (Closeable logStream : handle.getLogStreams()) {
            try {
                logStream.close();
            } catch (Exception ignored) {
            }
        }
        for (String dockerId : handle.getContainerIds().values()) {
            lifecycleManager.stopAndRemove(dockerId, null);
        }
    }

    /**
     * Stops all containers, captures their exit codes, then removes them.
     * Stop happens before inspect so the exit code reflects the actual stop
     * signal (e.g. 137 for SIGKILL), not a stale pre-stop value.
     */
    public Map<String, Integer> stopTaskAndCollectExitCodes(EcsTaskHandle handle) {
        Map<String, Integer> exitCodes = new LinkedHashMap<>();
        if (handle == null) {
            return exitCodes;
        }

        for (Closeable logStream : handle.getLogStreams()) {
            try {
                logStream.close();
            } catch (Exception ignored) {
            }
        }

        // Phase 1: stop all containers (no-op for those already exited).
        for (String dockerId : handle.getContainerIds().values()) {
            try {
                lifecycleManager.getDockerClient().stopContainerCmd(dockerId).withTimeout(5).exec();
            } catch (NotFoundException ignored) {
            } catch (Exception e) {
                LOG.warnv("Error stopping ECS container {0}: {1}", dockerId, e.getMessage());
            }
        }

        // Phase 2: inspect exit codes, then remove.
        for (Map.Entry<String, String> entry : handle.getContainerIds().entrySet()) {
            String name = entry.getKey();
            String dockerId = entry.getValue();
            exitCodes.put(name, getExitCodeIfStopped(dockerId));
            try {
                lifecycleManager.getDockerClient().removeContainerCmd(dockerId).withForce(true).exec();
            } catch (NotFoundException ignored) {
            } catch (Exception e) {
                LOG.warnv("Error removing ECS container {0}: {1}", dockerId, e.getMessage());
            }
        }
        return exitCodes;
    }

    /**
     * Returns the exit code of a container that has already stopped, or {@code null}
     * if the container is still running or its state cannot be determined.
     * A missing container (externally removed) is treated as a clean exit (code 0).
     */
    public Integer getExitCodeIfStopped(String dockerId) {
        try {
            var inspect = lifecycleManager.getDockerClient().inspectContainerCmd(dockerId).exec();
            if (Boolean.TRUE.equals(inspect.getState().getRunning())) {
                return null;
            }
            Long code = inspect.getState().getExitCodeLong();
            return code != null ? code.intValue() : null;
        } catch (NotFoundException e) {
            return 0;
        } catch (Exception e) {
            LOG.debugv("Could not inspect container {0}: {1}", dockerId, e.getMessage());
            return null;
        }
    }

    private List<String> buildEnvVars(ContainerDefinition def) {
        List<String> envVars = new ArrayList<>();
        if (def.getEnvironment() != null) {
            for (var kv : def.getEnvironment()) {
                envVars.add(kv.name() + "=" + kv.value());
            }
        }
        return envVars;
    }

    /**
     * Resolves the host address at which a running task container is reachable from the
     * Floci process — used to register the container as an ELBv2 target.
     * <p>
     * Native mode: the container's port is published to a host port, reachable at
     * {@code 127.0.0.1}. Floci-in-Docker mode: the container is reached by its IP on the
     * shared Docker network. Returns {@code 127.0.0.1} as a safe fallback.
     */
    public String resolveContainerHost(Container container) {
        if (!containerDetector.isRunningInContainer()) {
            return "127.0.0.1";
        }
        String dockerId = container.getDockerId();
        if (dockerId == null || dockerId.isBlank()) {
            return "127.0.0.1";
        }
        try {
            var inspect = lifecycleManager.getDockerClient().inspectContainerCmd(dockerId).exec();
            var networks = inspect.getNetworkSettings().getNetworks();

            // A container can be on multiple networks; getNetworks() is unordered.
            // Pick an IP that the Floci/ELBv2 process can actually route to:
            // 1. the explicitly-configured ECS Docker network, if set;
            // 2. otherwise any user-defined network (Floci joins one when in Docker)
            //    in preference to the default bridge;
            // 3. otherwise the first non-blank IP.
            String configured = config.services().ecs().dockerNetwork().orElse(null);
            if (configured != null && !configured.isBlank()) {
                var net = networks.get(configured);
                if (net != null && isUsableIp(net.getIpAddress())) {
                    return net.getIpAddress();
                }
            }
            for (var entry : networks.entrySet()) {
                if (!isDefaultDockerNetwork(entry.getKey())
                        && isUsableIp(entry.getValue().getIpAddress())) {
                    return entry.getValue().getIpAddress();
                }
            }
            for (var net : networks.values()) {
                if (isUsableIp(net.getIpAddress())) {
                    return net.getIpAddress();
                }
            }
        } catch (Exception e) {
            LOG.warnv("Could not resolve container IP for {0}: {1}", dockerId, e.getMessage());
        }
        return "127.0.0.1";
    }

    private static boolean isUsableIp(String ip) {
        return ip != null && !ip.isBlank();
    }

    private static boolean isDefaultDockerNetwork(String name) {
        return "bridge".equals(name) || "host".equals(name) || "none".equals(name);
    }

    private List<NetworkBinding> resolveNetworkBindings(String dockerId, ContainerDefinition def) {
        List<NetworkBinding> bindings = new ArrayList<>();
        if (def.getPortMappings() == null || def.getPortMappings().isEmpty()) {
            return bindings;
        }

        DockerClient dockerClient = lifecycleManager.getDockerClient();
        var inspect = dockerClient.inspectContainerCmd(dockerId).exec();
        var portBindingsMap = inspect.getNetworkSettings().getPorts().getBindings();

        for (PortMapping pm : def.getPortMappings()) {
            ExposedPort ep = ExposedPort.tcp(pm.containerPort());
            var binding = portBindingsMap.get(ep);
            int hostPort = pm.containerPort();
            String bindIp = "0.0.0.0";

            if (!containerDetector.isRunningInContainer() && binding != null && binding.length > 0) {
                hostPort = Integer.parseInt(binding[0].getHostPortSpec());
                if (binding[0].getHostIp() != null && !binding[0].getHostIp().isBlank()) {
                    bindIp = binding[0].getHostIp();
                }
            }

            bindings.add(new NetworkBinding(bindIp, pm.containerPort(), hostPort, pm.protocol()));
        }
        return bindings;
    }

    private Container buildContainer(String taskArn, ContainerDefinition def, String dockerId,
                                     List<NetworkBinding> networkBindings, String region) {
        Container container = new Container();
        container.setTaskArn(taskArn);
        container.setName(def.getName());
        container.setImage(def.getImage());
        container.setLastStatus("RUNNING");
        container.setNetworkBindings(networkBindings);
        container.setDockerId(dockerId);
        container.setContainerArn(regionResolver.buildArn("ecs", region,
                "container/" + extractTaskId(taskArn) + "/" + def.getName()));
        return container;
    }

    private static String extractTaskId(String taskArn) {
        int slash = taskArn.lastIndexOf('/');
        return slash >= 0 ? taskArn.substring(slash + 1) : taskArn;
    }

    // Inner enum to avoid import cycle — mirrors model.TaskStatus for readability
    private enum TaskStatus {RUNNING}
}
