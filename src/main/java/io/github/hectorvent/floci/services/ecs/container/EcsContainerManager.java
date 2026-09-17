package io.github.hectorvent.floci.services.ecs.container;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.SecurityGroupFirewallManager;
import io.github.hectorvent.floci.services.ec2.SecurityGroupNftCompiler;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.PrefixListEntry;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.ContainerOverride;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.EfsVolumeConfiguration;
import io.github.hectorvent.floci.services.ecs.model.FirelensConfiguration;
import io.github.hectorvent.floci.services.ecs.model.LogConfiguration;
import io.github.hectorvent.floci.services.ecs.model.MountPoint;
import io.github.hectorvent.floci.services.ecs.model.NetworkBinding;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.PortMapping;
import io.github.hectorvent.floci.services.ecs.model.Secret;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.ecs.model.Volume;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.LogConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

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
    private final LaunchedContainerAwsEnv awsEnv;
    private final SsmService ssmService;
    private final SecretsManagerService secretsManagerService;
    private final S3Service s3Service;
    private final EcrRegistryManager ecrRegistryManager;
    private final HostVolumePolicy hostVolumePolicy;
    private Ec2Service ec2Service;
    private SecurityGroupFirewallManager firewallManager;

    @Inject
    public EcsContainerManager(ContainerBuilder containerBuilder,
                               ContainerLifecycleManager lifecycleManager,
                               ContainerLogStreamer logStreamer,
                               ContainerDetector containerDetector,
                               EmulatorConfig config,
                               RegionResolver regionResolver,
                               LaunchedContainerAwsEnv awsEnv,
                               SsmService ssmService,
                               SecretsManagerService secretsManagerService,
                               S3Service s3Service,
                               EcrRegistryManager ecrRegistryManager,
                               HostVolumePolicy hostVolumePolicy,
                               Ec2Service ec2Service,
                               SecurityGroupFirewallManager firewallManager) {
        this(containerBuilder, lifecycleManager, logStreamer, containerDetector, config, regionResolver,
                awsEnv, ssmService, secretsManagerService, s3Service, ecrRegistryManager, hostVolumePolicy);
        this.ec2Service = ec2Service;
        this.firewallManager = firewallManager;
    }

    public EcsContainerManager(ContainerBuilder containerBuilder,
                               ContainerLifecycleManager lifecycleManager,
                               ContainerLogStreamer logStreamer,
                               ContainerDetector containerDetector,
                               EmulatorConfig config,
                               RegionResolver regionResolver,
                               LaunchedContainerAwsEnv awsEnv,
                               SsmService ssmService,
                               SecretsManagerService secretsManagerService,
                               S3Service s3Service,
                               EcrRegistryManager ecrRegistryManager,
                               HostVolumePolicy hostVolumePolicy) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.config = config;
        this.regionResolver = regionResolver;
        this.awsEnv = awsEnv;
        this.ssmService = ssmService;
        this.secretsManagerService = secretsManagerService;
        this.s3Service = s3Service;
        this.hostVolumePolicy = hostVolumePolicy;
        this.ecrRegistryManager = ecrRegistryManager;
    }

    /**
     * Starts Docker containers for all container definitions in a task.
     * Updates the task's container list in-place with runtime network bindings and docker IDs.
     */
    public EcsTaskHandle startTask(EcsTask task, TaskDefinition taskDef,
                                   List<ContainerOverride> containerOverrides, String region) {
        String taskId = extractTaskId(task.getTaskArn());

        Map<String, String> containerIds = new LinkedHashMap<>();
        Map<String, Closeable> logStreamsByContainerId = new LinkedHashMap<>();
        List<Container> runtimeContainers = new ArrayList<>();

        // Task-level volumes consumed by per-container mountPoints: host volumes map their
        // name -> absolute host source path; efsVolumeConfiguration volumes map their
        // name -> EFS configuration (materialised below as a shared local Docker volume).
        Map<String, String> volumeSourcePaths = new LinkedHashMap<>();
        Map<String, EfsVolumeConfiguration> efsVolumes = new LinkedHashMap<>();
        if (taskDef.getVolumes() != null) {
            for (Volume v : taskDef.getVolumes()) {
                if (v.name() == null) {
                    continue;
                }
                if (v.hostSourcePath() != null) {
                    volumeSourcePaths.put(v.name(), v.hostSourcePath());
                } else if (v.efs() != null) {
                    efsVolumes.put(v.name(), v.efs());
                }
            }
        }

        Map<String, ContainerOverride> overridesByName = overridesByName(containerOverrides);
        Map<ContainerDefinition, List<String>> envVarsByContainer = new LinkedHashMap<>();
        // Resolved before any container is created, so a registry-startup failure can't leak one already started.
        Map<ContainerDefinition, String> imagesByContainer = new LinkedHashMap<>();
        for (ContainerDefinition def : taskDef.getContainerDefinitions()) {
            envVarsByContainer.put(def, buildEnvVars(def, overridesByName.get(def.getName()), region));
            imagesByContainer.put(def, ecrRegistryManager.rewriteImageUri(def.getImage()));
        }

        ContainerDefinition firelensRouter = findFirelensRouter(taskDef.getContainerDefinitions());
        Map<String, Map<String, String>> firelensLogOptions =
                awsFirelensLogOptions(taskDef.getContainerDefinitions());
        if (!firelensLogOptions.isEmpty() && firelensRouter == null) {
            throw new AwsException("ClientException",
                    "awsfirelens log driver requires a firelensConfiguration container", 400);
        }

        PreparedNetwork protectedNetwork = prepareNetwork(task, taskDef, region, taskId);

        String firelensVolumeName = null;
        String firelensSocketAddress = null;
        String firelensConfig = null;
        String firelensExternalConfig = null;
        if (firelensRouter != null) {
            firelensConfig = firelensConfig(task, taskDef, firelensRouter, firelensLogOptions);
            firelensExternalConfig = s3ExternalConfig(firelensRouter);
            firelensVolumeName = ContainerStorageHelper.dockerName(config, "floci-ecs-firelens-" + taskId);
            lifecycleManager.ensureVolume(firelensVolumeName);
            firelensSocketAddress = unixSocketAddress(firelensVolumeName);
        }

        List<ContainerDefinition> launchOrder =
                launchOrder(taskDef.getContainerDefinitions(), firelensRouter);
        String fluentHost = null;
        String networkModeName = taskDef.getNetworkMode() != null
                ? taskDef.getNetworkMode().name()
                : NetworkMode.bridge.name();

        try {
            for (ContainerDefinition def : launchOrder) {
                String containerName = ContainerStorageHelper.dockerName(config, "floci-ecs-" + taskId + "-" + def.getName());

                // RunTask containerOverrides matched by container name: command replaces
                // the task-def command; environment is merged over the task-def environment.
                ContainerOverride override = overridesByName.get(def.getName());

                List<String> env = new ArrayList<>(envVarsByContainer.get(def));
                if (fluentHost != null && def != firelensRouter
                        && FirelensConfigGenerator.addsTcpForward(networkModeName)) {
                    env.add("FLUENT_HOST=" + fluentHost);
                    env.add("FLUENT_PORT=" + FirelensConfigGenerator.FORWARD_PORT);
                }

                // Build container spec
                ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(imagesByContainer.get(def))
                        .withName(containerName)
                        .withEnv(env)
                        .withDockerNetwork(config.services().ecs().dockerNetwork())
                        // Resolve Floci's endpoint from inside the task container the same way Lambda
                        // containers do: host.docker.internal on Linux, plus Floci's embedded DNS so the
                        // reachable AWS_ENDPOINT_URL hostname resolves to Floci instead of the container's
                        // own loopback.
                        .withHostDockerInternalOnLinux()
                        .withEmbeddedDns()
                        .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                                "ecs", taskId, regionResolver.getAccountId(), region));
                if (protectedNetwork != null) {
                    specBuilder.withNetworkMode("container:" + protectedNetwork.namespace().helperId());
                    specBuilder.withLabels(Map.of("floci.security-group-workload", "true"));
                }

                boolean awsFirelens = isAwsFirelens(def);
                if (awsFirelens) {
                    specBuilder.withLogConfig(awsFirelensLogConfig(def, taskId, firelensSocketAddress));
                } else {
                    specBuilder.withLogRotation();
                }

                if (def == firelensRouter && firelensVolumeName != null) {
                    specBuilder.withNamedVolume(firelensVolumeName, "/var/run");
                }

                // Add memory limit if specified
                if (def.getMemory() != null) {
                    specBuilder.withMemoryMb(def.getMemory());
                }

                // Add port mappings. In bridge/host mode an explicit hostPort is
                // published to the Docker host literally, matching AWS bridge mode
                // (mirrors the ECR registry's fixed-port publishing). In awsvpc mode
                // every AWS task gets its own ENI, so a literal hostPort carries no
                // host-binding semantics and would collide across tasks on the single
                // local Docker host (#1778) — awsvpc mappings always get a dynamic
                // host port in native mode, or expose-only in Docker mode where ECS
                // consumers reach containers via the docker network IP.
                if (protectedNetwork == null && def.getPortMappings() != null) {
                    boolean awsvpc = taskDef.getNetworkMode() == NetworkMode.awsvpc;
                    boolean publishToHost = !containerDetector.isRunningInContainer();
                    for (PortMapping pm : def.getPortMappings()) {
                        if (!awsvpc && pm.hostPort() > 0) {
                            specBuilder.withPortBinding(pm.containerPort(), pm.hostPort());
                        } else if (publishToHost) {
                            specBuilder.withDynamicPort(pm.containerPort());
                        } else {
                            specBuilder.withExposedPort(pm.containerPort());
                        }
                    }
                }

                // Add command and entrypoint if specified. An override command (from
                // RunTask containerOverrides) takes precedence over the task-def command.
                List<String> effectiveCommand =
                        (override != null && override.getCommand() != null && !override.getCommand().isEmpty())
                                ? override.getCommand()
                                : def.getCommand();
                if (effectiveCommand != null && !effectiveCommand.isEmpty()) {
                    specBuilder.withCmd(effectiveCommand);
                }
                if (def.getEntryPoint() != null && !def.getEntryPoint().isEmpty()) {
                    specBuilder.withEntrypoint(def.getEntryPoint());
                }

                // Bind-mount task-level volumes referenced by this container's mountPoints.
                // The host source path resolves on the Docker daemon (sibling-container launch),
                // so it must be an absolute host path. Unresolved volume references are skipped.
                if (def.getMountPoints() != null) {
                    for (MountPoint mp : def.getMountPoints()) {
                        if (mp.containerPath() == null) {
                            continue;
                        }
                        String sourcePath = volumeSourcePaths.get(mp.sourceVolume());
                        EfsVolumeConfiguration efs = efsVolumes.get(mp.sourceVolume());
                        if (sourcePath != null) {
                            // Host volume: bind-mount an absolute path on the Docker host. Re-validate
                            // here (not just at RegisterTaskDefinition time): this narrows the window
                            // between validation and mount for a symlink swapped in afterward, and
                            // also covers task definitions persisted before this policy existed. A
                            // rejection is not swallowed, it propagates out of startTask and is
                            // surfaced by EcsService as the task's stoppedReason.
                            hostVolumePolicy.validate(sourcePath);
                            if (mp.readOnly()) {
                                specBuilder.withReadOnlyBind(sourcePath, mp.containerPath());
                            } else {
                                specBuilder.withBind(sourcePath, mp.containerPath());
                            }
                        } else if (efs != null) {
                            mountEfsVolume(specBuilder, efs, mp);
                        } else {
                            LOG.warnv("Skipping mountPoint with unresolved volume {0} on container {1}",
                                    mp.sourceVolume(), def.getName());
                        }
                    }
                }

                ContainerSpec spec = specBuilder.build();

                String dockerId;
                if (def == firelensRouter) {
                    dockerId = lifecycleManager.create(spec);
                    try {
                        copyFirelensConfig(dockerId, firelensConfig, isFluentdRouter(firelensRouter),
                                firelensExternalConfig);
                        lifecycleManager.startCreated(dockerId, spec);
                    } catch (RuntimeException e) {
                        lifecycleManager.removeIfExists(dockerId);
                        throw e;
                    }
                    fluentHost = inspectContainerIp(dockerId);
                } else {
                    ContainerInfo info = lifecycleManager.createAndStart(spec);
                    dockerId = info.containerId();
                }

                LOG.infov("Created ECS container {0} for task {1} container {2}", dockerId, taskId, def.getName());

                // Resolve network bindings for ECS-specific model
                List<NetworkBinding> networkBindings = resolveNetworkBindings(
                        protectedNetwork == null ? dockerId : protectedNetwork.namespace().helperId(), def);

                // Build ECS container model
                Container container = buildContainer(task.getTaskArn(), def, dockerId, networkBindings, region);
                runtimeContainers.add(container);
                containerIds.put(def.getName(), dockerId);

                // awsfirelens containers are shipped to Fluent Bit by Docker; don't also scrape json-file.
                if (!awsFirelens) {
                    String logGroup = "/ecs/" + taskDef.getFamily();
                    String logStream = logStreamer.generateLogStreamName(def.getName() + "/" + taskId);
                    Closeable logHandle = logStreamer.attach(
                            dockerId, logGroup, logStream, region,
                            "ecs:" + taskDef.getFamily() + ":" + def.getName());
                    if (logHandle != null) {
                        logStreamsByContainerId.put(dockerId, logHandle);
                    }
                }
            }
        } catch (Exception e) {
            for (String dockerId : containerIds.values()) {
                lifecycleManager.stopAndRemove(dockerId, null);
            }
            if (firelensVolumeName != null) {
                lifecycleManager.removeVolume(firelensVolumeName);
            }
            if (protectedNetwork != null) {
                firewallManager.unregister(protectedNetwork.eni().getNetworkInterfaceId());
                ec2Service.deleteNetworkInterface(region, protectedNetwork.eni().getNetworkInterfaceId());
            }
            throw e;
        }

        task.setContainers(runtimeContainers);
        task.setLastStatus(TaskStatus.RUNNING.name());
        task.setDesiredStatus(TaskStatus.RUNNING.name());
        task.setStartedAt(Instant.now());

        return new EcsTaskHandle(task.getTaskArn(), containerIds, logStreamsByContainerId,
                firelensVolumeName,
                protectedNetwork == null ? null : protectedNetwork.eni().getNetworkInterfaceId(), region);
    }

    private PreparedNetwork prepareNetwork(EcsTask task, TaskDefinition definition, String region, String taskId) {
        if (definition.getNetworkMode() != NetworkMode.awsvpc
                || firewallManager == null || !firewallManager.enabled()) {
            return null;
        }
        AwsVpcConfiguration awsvpc = task.getNetworkConfiguration() == null ? null
                : task.getNetworkConfiguration().getAwsvpcConfiguration();
        if (awsvpc == null || awsvpc.getSubnets() == null || awsvpc.getSubnets().isEmpty()) {
            throw new AwsException("ClientException", "awsvpc tasks require a subnet", 400);
        }
        NetworkInterface eni = ec2Service.createNetworkInterface(region, awsvpc.getSubnets().getFirst(),
                "ECS task " + task.getTaskArn(), null, List.of(), awsvpc.getSecurityGroups(), List.of());
        String eniId = eni.getNetworkInterfaceId();
        SecurityGroupFirewallManager.Namespace namespace = null;
        try {
            Map<Integer, Integer> bindings = new LinkedHashMap<>();
            if (!containerDetector.isRunningInContainer()) {
                for (ContainerDefinition container : definition.getContainerDefinitions()) {
                    if (container.getPortMappings() != null) {
                        container.getPortMappings().forEach(port -> bindings.put(port.containerPort(), 0));
                    }
                }
            }
            namespace = firewallManager.createNamespace("ecs", taskId, regionResolver.getAccountId(),
                    region, config.services().ecs().dockerNetwork(), bindings);
            List<String> groupIds = eni.getGroups().stream().map(g -> g.getGroupId()).toList();
            List<SecurityGroup> groups = ec2Service.describeSecurityGroups(region, groupIds, List.of(), Map.of());
            if (groups.size() != groupIds.size()) {
                throw new IllegalStateException("An ECS task security group could not be resolved");
            }
            Map<String, List<String>> prefixLists = new LinkedHashMap<>();
            for (SecurityGroup group : groups) {
                Stream.concat(group.getIpPermissions().stream(),
                                group.getIpPermissionsEgress().stream())
                        .flatMap(permission -> permission.getPrefixListIds().stream())
                        .map(reference -> reference.getPrefixListId())
                        .distinct()
                        .forEach(id -> prefixLists.put(id, ec2Service.getManagedPrefixListEntries(region, id, null)
                                .stream().map(PrefixListEntry::getCidr).toList()));
            }
            firewallManager.register(new SecurityGroupNftCompiler.Endpoint(regionResolver.getAccountId(),
                    region, eni.getVpcId(), eniId, eni.getPrivateIpAddress(),
                    namespace.transportAddress(), Set.copyOf(groupIds), groups), namespace.helperId(), prefixLists);
            task.setNetworkInterfaceId(eniId);
            task.setPrivateIpAddress(eni.getPrivateIpAddress());
            return new PreparedNetwork(eni, namespace);
        } catch (Exception e) {
            if (namespace != null) {
                lifecycleManager.removeIfExists(namespace.helperId());
            }
            ec2Service.deleteNetworkInterface(region, eniId);
            throw e;
        }
    }

    private record PreparedNetwork(NetworkInterface eni, SecurityGroupFirewallManager.Namespace namespace) {}

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
        for (String dockerId : handle.getContainerIds().values()) {
            lifecycleManager.stopAndRemove(dockerId, null);
        }
        cleanupProtectedNetwork(handle);
        new ArrayList<>(handle.getLogStreamsByContainerId().keySet())
                .forEach(dockerId -> finalizeLogStream(handle, dockerId));
        removeFirelensVolume(handle);
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

        // Phase 1: stop all containers (no-op for those already exited).
        Set<String> terminatedContainerIds = new HashSet<>();
        for (String dockerId : handle.getContainerIds().values()) {
            try {
                lifecycleManager.getDockerClient().stopContainerCmd(dockerId).withTimeout(5).exec();
                terminatedContainerIds.add(dockerId);
            } catch (NotFoundException ignored) {
                terminatedContainerIds.add(dockerId);
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
                terminatedContainerIds.add(dockerId);
            } catch (NotFoundException ignored) {
                terminatedContainerIds.add(dockerId);
            } catch (Exception e) {
                LOG.warnv("Error removing ECS container {0}: {1}", dockerId, e.getMessage());
            }
        }
        // A force removal terminates Docker's follow-log transport even when the preceding stop failed.
        // Preserve handles for any container that still may be running after both operations failed.
        terminatedContainerIds.forEach(dockerId -> finalizeLogStream(handle, dockerId));
        cleanupProtectedNetwork(handle);
        removeFirelensVolume(handle);
        return exitCodes;
    }

    private void removeFirelensVolume(EcsTaskHandle handle) {
        if (handle == null || handle.getFirelensVolumeName() == null) {
            return;
        }
        lifecycleManager.removeVolume(handle.getFirelensVolumeName());
    }

    private static ContainerDefinition findFirelensRouter(List<ContainerDefinition> defs) {
        if (defs == null) {
            return null;
        }
        for (ContainerDefinition def : defs) {
            FirelensConfiguration firelens = def.getFirelensConfiguration();
            if (firelens != null && ("fluentbit".equals(firelens.type()) || "fluentd".equals(firelens.type()))) {
                return def;
            }
        }
        return null;
    }

    private static boolean isFluentdRouter(ContainerDefinition router) {
        return router != null
                && router.getFirelensConfiguration() != null
                && "fluentd".equals(router.getFirelensConfiguration().type());
    }

    private static Map<String, Map<String, String>> awsFirelensLogOptions(List<ContainerDefinition> defs) {
        LinkedHashMap<String, Map<String, String>> options = new LinkedHashMap<>();
        if (defs == null) {
            return options;
        }
        for (ContainerDefinition def : defs) {
            if (!isAwsFirelens(def)) {
                continue;
            }
            LogConfiguration log = def.getLogConfiguration();
            options.put(def.getName(), log.options() != null ? log.options() : Map.of());
        }
        return options;
    }

    private static boolean isAwsFirelens(ContainerDefinition def) {
        LogConfiguration log = def.getLogConfiguration();
        return log != null && "awsfirelens".equals(log.logDriver());
    }

    private static List<ContainerDefinition> launchOrder(
            List<ContainerDefinition> defs, ContainerDefinition firelensRouter) {
        if (firelensRouter == null) {
            return defs;
        }
        List<ContainerDefinition> ordered = new ArrayList<>();
        ordered.add(firelensRouter);
        for (ContainerDefinition def : defs) {
            if (def != firelensRouter) {
                ordered.add(def);
            }
        }
        return ordered;
    }

    private FirelensConfigGenerator.Context firelensContext(
            EcsTask task, TaskDefinition taskDef, ContainerDefinition router,
            Map<String, Map<String, String>> logOptions) {
        FirelensConfiguration firelens = router.getFirelensConfiguration();
        Map<String, String> options = firelens.options() != null ? firelens.options() : Map.of();
        boolean metadata = !"false".equalsIgnoreCase(options.get("enable-ecs-log-metadata"));
        String external = null;
        String externalType = options.get("config-file-type");
        if ("file".equals(externalType)) {
            external = options.get("config-file-value");
        } else if ("s3".equals(externalType)) {
            // The agent downloads the object to this fixed path and @INCLUDEs it; Floci
            // writes it there directly with the generated config.
            external = isFluentdRouter(router)
                    ? "/fluentd/etc/external.conf"
                    : "/fluent-bit/etc/external.conf";
        }
        int memoryMb = 0;
        if (router.getMemoryReservation() != null) {
            memoryMb = router.getMemoryReservation();
        } else if (router.getMemory() != null) {
            memoryMb = router.getMemory();
        }
        String cluster = extractTaskId(task.getClusterArn());
        String familyRevision = taskDef.getFamily() + ":" + taskDef.getRevision();
        String networkMode = taskDef.getNetworkMode() != null
                ? taskDef.getNetworkMode().name()
                : NetworkMode.bridge.name();
        return new FirelensConfigGenerator.Context(
                networkMode, metadata, cluster, task.getTaskArn(), familyRevision,
                memoryMb, external, awsEnv.flociEndpoint(), logOptions);
    }

    private String firelensConfig(
            EcsTask task, TaskDefinition taskDef, ContainerDefinition router,
            Map<String, Map<String, String>> logOptions) {
        FirelensConfigGenerator.Context ctx = firelensContext(task, taskDef, router, logOptions);
        return isFluentdRouter(router)
                ? FirelensConfigGenerator.fluentdConfig(ctx)
                : FirelensConfigGenerator.fluentBitConfig(ctx);
    }

    private String unixSocketAddress(String volumeName) {
        try {
            String mountpoint = lifecycleManager.getDockerClient()
                    .inspectVolumeCmd(volumeName).exec().getMountpoint();
            if (mountpoint == null || mountpoint.isBlank()) {
                throw new AwsException("ClientException",
                        "FireLens socket volume has no mountpoint: " + volumeName, 500);
            }
            String path = mountpoint.endsWith("/")
                    ? mountpoint + "fluent.sock"
                    : mountpoint + "/fluent.sock";
            return "unix://" + path;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ClientException",
                    "unable to resolve FireLens socket path: " + e.getMessage(), 500);
        }
    }

    private LogConfig awsFirelensLogConfig(ContainerDefinition def, String taskId, String socketAddress) {
        LinkedHashMap<String, String> opts = new LinkedHashMap<>();
        opts.put("fluentd-address", socketAddress);
        opts.put("fluentd-async", "true");
        opts.put("fluentd-sub-second-precision", "true");
        opts.put("tag", def.getName() + "-firelens-" + taskId);
        Map<String, String> logOptions = def.getLogConfiguration().options();
        if (logOptions != null && logOptions.get("log-driver-buffer-limit") != null) {
            opts.put("fluentd-buffer-limit", logOptions.get("log-driver-buffer-limit"));
        }
        return new LogConfig(LogConfig.LoggingType.FLUENTD, opts);
    }

    /**
     * Reads a {@code config-file-type=s3} external config from Floci's S3, before any container is
     * created, so a missing object stops the task without leaking a started router.
     *
     * <p>Wording is the ECS agent's, and the error code is ResourceInitializationError so that
     * EcsService passes the message through verbatim as the task's stoppedReason rather than
     * wrapping it in its generic start-failure prefix.
     */
    private String s3ExternalConfig(ContainerDefinition router) {
        FirelensConfiguration firelens = router.getFirelensConfiguration();
        Map<String, String> options = firelens.options() != null ? firelens.options() : Map.of();
        if (!"s3".equals(options.get("config-file-type"))) {
            return null;
        }
        String value = options.get("config-file-value");
        // Registration already rejected a value that is not an S3 object ARN; this covers task
        // definitions stored before that check existed.
        AwsArnUtils.S3ObjectRef ref = AwsArnUtils.parseS3ObjectArn(value);
        if (ref == null) {
            throw firelensS3Failure("unable to parse s3 arn: " + value, 400);
        }
        try {
            S3Object object = s3Service.getObject(ref.bucket(), ref.key());
            return new String(object.getData(), StandardCharsets.UTF_8);
        } catch (AwsException e) {
            throw firelensS3Failure("unable to download s3 config " + ref.key()
                    + " from bucket " + ref.bucket() + ": " + e.getMessage(), e.getHttpStatus());
        }
    }

    /**
     * A task-resource failure that never reaches a client: EcsService catches it and uses the
     * message as the task's stoppedReason, keying off the ResourceInitializationError code to
     * skip its generic "Failed to start:" prefix. The prefix on the message itself is the agent's.
     */
    private static AwsException firelensS3Failure(String detail, int httpStatus) {
        return new AwsException("ResourceInitializationError",
                "Unable to download firelens s3 config file: " + detail, httpStatus);
    }

    private void copyFirelensConfig(
            String containerId, String configText, boolean fluentd, String externalConfig) {
        byte[] content = configText.getBytes(StandardCharsets.UTF_8);
        String fileName = fluentd ? "fluent.conf" : "fluent-bit.conf";
        String remotePath = fluentd ? "/fluentd/etc" : "/fluent-bit/etc";
        ByteArrayOutputStream archive = new ByteArrayOutputStream(content.length + 1024);
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(archive)) {
            putTarEntry(tar, fileName, content);
            if (externalConfig != null) {
                putTarEntry(tar, "external.conf", externalConfig.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new AwsException("ClientException",
                    "unable to write FireLens config: " + e.getMessage(), 500);
        }
        lifecycleManager.getDockerClient().copyArchiveToContainerCmd(containerId)
                .withRemotePath(remotePath)
                .withTarInputStream(new ByteArrayInputStream(archive.toByteArray()))
                .exec();
    }

    private static void putTarEntry(TarArchiveOutputStream tar, String name, byte[] content) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(content.length);
        tar.putArchiveEntry(entry);
        tar.write(content);
        tar.closeArchiveEntry();
    }

    private String inspectContainerIp(String dockerId) {
        try {
            InspectContainerResponse inspect = lifecycleManager.getDockerClient().inspectContainerCmd(dockerId).exec();
            Map<String, ContainerNetwork> networks = inspect.getNetworkSettings().getNetworks();
            String configured = config.services().ecs().dockerNetwork().orElse(null);
            if (configured != null && networks.get(configured) != null
                    && isUsableIp(networks.get(configured).getIpAddress())) {
                return networks.get(configured).getIpAddress();
            }
            for (Map.Entry<String, ContainerNetwork> entry : networks.entrySet()) {
                if (!isDefaultDockerNetwork(entry.getKey())
                        && isUsableIp(entry.getValue().getIpAddress())) {
                    return entry.getValue().getIpAddress();
                }
            }
            for (ContainerNetwork net : networks.values()) {
                if (isUsableIp(net.getIpAddress())) {
                    return net.getIpAddress();
                }
            }
        } catch (Exception e) {
            LOG.warnv("Could not resolve FireLens container IP for {0}: {1}", dockerId, e.getMessage());
        }
        return "127.0.0.1";
    }
    private void cleanupProtectedNetwork(EcsTaskHandle handle) {
        if (firewallManager != null && handle.getNetworkInterfaceId() != null) {
            firewallManager.unregister(handle.getNetworkInterfaceId());
            ec2Service.deleteNetworkInterface(handle.getRegion(), handle.getNetworkInterfaceId());
        }
    }

    private void finalizeLogStream(EcsTaskHandle handle, String dockerId) {
        Closeable logStream = handle.removeLogStream(dockerId);
        if (logStream != null) {
            lifecycleManager.closeLogStreamAfterContainerStop(logStream);
        }
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

    private List<String> buildEnvVars(ContainerDefinition def, ContainerOverride override, String region) {
        // AWS SDK baseline (endpoint + region + credentials) first so the task can reach the
        // emulator, then the task-def environment, then task-def secrets, then the override
        // environment. Later entries win on key conflict, so an explicit task-def value or
        // secret is never clobbered by the baseline.
        Map<String, String> envMap = new LinkedHashMap<>();
        for (String kv : awsEnv.sdkBaselineEnv(region, Optional.empty())) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                envMap.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        if (def.getEnvironment() != null) {
            for (var kv : def.getEnvironment()) {
                envMap.put(kv.name(), kv.value());
            }
        }
        if (def.getSecrets() != null) {
            for (Secret secret : def.getSecrets()) {
                envMap.put(secret.name(), resolveSecretValue(secret.valueFrom(), region));
            }
        }
        if (override != null && override.getEnvironment() != null) {
            for (var kv : override.getEnvironment()) {
                envMap.put(kv.name(), kv.value());
            }
        }
        List<String> envVars = new ArrayList<>();
        for (var entry : envMap.entrySet()) {
            envVars.add(entry.getKey() + "=" + entry.getValue());
        }
        return envVars;
    }

    private Map<String, ContainerOverride> overridesByName(List<ContainerOverride> containerOverrides) {
        Map<String, ContainerOverride> overrides = new LinkedHashMap<>();
        if (containerOverrides == null) {
            return overrides;
        }
        for (ContainerOverride override : containerOverrides) {
            if (override.getName() != null) {
                overrides.put(override.getName(), override);
            }
        }
        return overrides;
    }

    private String resolveSecretValue(String valueFrom, String region) {
        // A full ARN carries its own region; use it so cross-region references resolve
        // against the right store instead of the task's region. Bare SSM names fall back
        // to the task region.
        String secretRegion = arnRegion(valueFrom, region);
        String value;
        String jsonKey = null;
        try {
            if (AwsArnUtils.isArnFor(valueFrom, "secretsmanager")) {
                // The valueFrom may carry the ECS selector suffix
                // (:json-key:version-stage:version-id); the parser strips it so the base ARN
                // reaches SecretsManagerService intact, keeping its partial-ARN fallback working.
                var selector = SecretsManagerSelector.parse(valueFrom);
                jsonKey = selector.jsonKey();
                var secret = secretsManagerService.getSecretValue(selector.secretId(),
                        selector.versionId(), selector.versionStage(), secretRegion);
                value = secret == null ? null : secret.getSecretString();
            } else {
                String parameterName = ssmParameterName(valueFrom);
                var parameter = ssmService.getParameter(parameterName, secretRegion);
                value = parameter == null ? null : parameter.getValue();
            }
        } catch (AwsException e) {
            throw resourceInitializationError(valueFrom, e.getMessage(), e.getHttpStatus());
        }
        if (value == null) {
            // A Secrets Manager secret stored as SecretBinary (no SecretString) has no string
            // value to inject as an env var. Real AWS fails the task launch rather than starting
            // the container with a missing value, so surface the same ResourceInitializationError
            // instead of emitting a literal "NAME=null". Checked before JSON extraction: the real
            // agent nil-derefs on this input, so failing cleanly is a deliberate improvement.
            throw resourceInitializationError(valueFrom, "secret value is not a string", 400);
        }
        if (jsonKey != null) {
            try {
                value = SecretsManagerSelector.extractJsonKey(value, jsonKey);
            } catch (AwsException e) {
                throw resourceInitializationError(valueFrom, e.getMessage(), e.getHttpStatus());
            }
        }
        return value;
    }

    // The error code is ResourceInitializationError, not the underlying store's code: this
    // exception never reaches a client (it is caught in EcsService and rendered as the task's
    // stoppedReason), and EcsService keys off this code to pass the reason through as AWS's
    // exact wording rather than wrapping it in the generic "Failed to start:" prefix.
    private AwsException resourceInitializationError(String valueFrom, String detail, int httpStatus) {
        return new AwsException("ResourceInitializationError",
                "ResourceInitializationError: unable to pull secrets or registry auth: "
                        + valueFrom + ": " + detail,
                httpStatus);
    }

    /** The region embedded in an ARN {@code valueFrom} (4th segment), or {@code taskRegion} for a bare name. */
    private String arnRegion(String valueFrom, String taskRegion) {
        if (valueFrom != null && valueFrom.startsWith("arn:")) {
            String[] parts = valueFrom.split(":", 5);
            if (parts.length >= 4 && !parts[3].isBlank()) {
                return parts[3];
            }
        }
        return taskRegion;
    }

    private String ssmParameterName(String valueFrom) {
        if (AwsArnUtils.isArnFor(valueFrom, "ssm")) {
            int parameterMarker = valueFrom.indexOf(":parameter");
            if (parameterMarker >= 0) {
                return valueFrom.substring(parameterMarker + ":parameter".length());
            }
        }
        return valueFrom;
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

    private static String extractTaskId(String arn) {
        if (arn == null) {
            return "";
        }
        int slash = arn.lastIndexOf('/');
        return slash >= 0 ? arn.substring(slash + 1) : arn;
    }

    /**
     * Materialises an EFS-configured task volume as a shared local Docker named volume scoped to
     * both the file system and the mount's effective root (see {@link #efsVolumeName}), then
     * initialises the volume root's POSIX ownership to emulate the EFS access point's
     * RootDirectory.CreationInfo (no-op unless {@code floci.storage.efs} configures owner/permissions)
     * and applies the configured PosixUser emulation (uid[:gid] and/or supplementary group) so a
     * non-root task image can read/write the shared volume.
     */
    private void mountEfsVolume(ContainerBuilder.Builder specBuilder, EfsVolumeConfiguration efs, MountPoint mp) {
        String efsVolumeName = efsVolumeName(efs.fileSystemId(), efs.accessPointId(), efs.rootDirectory());
        var efsCfg = config.storage().efs();
        lifecycleManager.ensureSharedVolume(efsVolumeName,
                efsCfg.ownerUid(), efsCfg.ownerGid(), efsCfg.rootPermissions(),
                efsCfg.initImage());
        specBuilder.withNamedVolume(efsVolumeName, mp.containerPath(), mp.readOnly());
        efsCfg.mountUser().ifPresent(u -> {
            if (!u.matches("^\\d+(:\\d+)?$")) {
                throw new IllegalArgumentException(
                        "floci.storage.efs.mount-user must be \"uid\" or \"uid:gid\": " + u);
            }
            specBuilder.withUser(u);
        });
        efsCfg.mountGroupAdd().ifPresent(gid -> specBuilder.withGroupAdd(String.valueOf(gid)));
    }

    /**
     * Name of the local Docker named volume backing an EFS-configured task volume, scoped to
     * both the file system and the mount's effective root so two mounts of the same file system
     * with a different {@code rootDirectory}/{@code accessPointId} land on isolated volumes
     * while identical configurations keep sharing one, matching how AWS scopes an EFS mount to
     * a subpath. When {@code accessPointId} is set, it determines the effective root: on real
     * AWS an access point's own root directory takes precedence over any {@code rootDirectory}
     * on the volume.
     */
    static String efsVolumeName(String fileSystemId, String accessPointId, String rootDirectory) {
        if (accessPointId != null && !accessPointId.isBlank()) {
            return "floci-efs-" + fileSystemId + "-" + sha256Hex("accessPoint:" + accessPointId);
        }
        String normalizedRoot = normalizeRootDirectory(rootDirectory);
        if ("/".equals(normalizedRoot)) {
            return "floci-efs-" + fileSystemId;
        }
        return "floci-efs-" + fileSystemId + "-" + sha256Hex(normalizedRoot);
    }

    /**
     * Canonicalises a {@code rootDirectory} path so syntactically different but equivalent
     * paths (missing/blank, a missing leading slash, repeated slashes, a trailing slash) resolve
     * to the same effective root instead of silently splitting shared storage across local
     * volumes that AWS would treat as identical.
     */
    private static String normalizeRootDirectory(String rootDirectory) {
        if (rootDirectory == null || rootDirectory.isBlank()) {
            return "/";
        }
        String collapsed = rootDirectory.trim().replaceAll("/+", "/");
        if (collapsed.length() > 1 && collapsed.endsWith("/")) {
            collapsed = collapsed.substring(0, collapsed.length() - 1);
        }
        return collapsed.startsWith("/") ? collapsed : "/" + collapsed;
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but not available", e);
        }
    }

    // Inner enum to avoid import cycle — mirrors model.TaskStatus for readability
    private enum TaskStatus {RUNNING}
}
