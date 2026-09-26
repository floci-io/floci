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
import io.github.hectorvent.floci.services.ecs.model.ContainerDependency;
import io.github.hectorvent.floci.services.ecs.model.ContainerOverride;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.EfsVolumeConfiguration;
import io.github.hectorvent.floci.services.ecs.model.FirelensConfiguration;
import io.github.hectorvent.floci.services.ecs.model.LogConfiguration;
import io.github.hectorvent.floci.services.ecs.model.ManagedAgent;
import io.github.hectorvent.floci.services.ecs.model.MountPoint;
import io.github.hectorvent.floci.services.ecs.model.NetworkBinding;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.PortMapping;
import io.github.hectorvent.floci.services.ecs.model.Secret;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.ecs.model.TaskNetworkInterface;
import io.github.hectorvent.floci.services.ecs.model.Volume;
import io.github.hectorvent.floci.services.ecs.model.VolumeFrom;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.StatsCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.LogConfig;
import com.github.dockerjava.api.model.StatisticNetworksConfig;
import com.github.dockerjava.api.model.Statistics;
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
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Manages Docker container lifecycle for ECS tasks.
 * Starts one Docker container per ContainerDefinition in a task and attaches logs to CloudWatch.
 */
@ApplicationScoped
public class EcsContainerManager {

    private static final Logger LOG = Logger.getLogger(EcsContainerManager.class);

    private static final String ATTACHMENT_DELETED = "DELETED";

    /** EC2 error codes the task ENI path can raise, none of which RunTask declares. */
    private static final Set<String> EC2_NETWORK_LOOKUP_FAILURES =
            Set.of("InvalidSubnetID.NotFound", "InvalidGroup.NotFound");
    /** How long a container waits for a dependency that has to COMPLETE, SUCCEED or get HEALTHY. */
    private static final int DEPENDENCY_WAIT_SECONDS = 60;
    private static final long DEPENDENCY_POLL_MILLIS = 200;
    /**
     * The baseline credential variables that must not reach a container being given task-role
     * credentials: the SDK would prefer them over the credential endpoint.
     */
    private static final List<String> BASELINE_CREDENTIAL_KEYS =
            List.of("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN");
    /** How long a killed container gets to register as exited before its code is read. */
    private static final int KILL_SETTLE_SECONDS = 5;
    /** How long a stats sample gets before the task metadata endpoint answers without one. */
    private static final int STATS_SAMPLE_SECONDS = 5;
    /**
     * Docker writes {@code 0001-01-01T00:00:00Z} for an event that has not happened. Anything at
     * or before the Unix epoch is that zero value rather than a time a container reached.
     */
    private static final Instant DOCKER_ZERO_TIMESTAMP = Instant.EPOCH;

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
    private final Ec2Service ec2Service;
    private final SecurityGroupFirewallManager firewallManager;
    private final EcsTaskRoleCredentials taskRoleCredentials;
    private final EcsCredentialsProxy credentialsProxy;
    private final EcsTaskLinkLocalAddresses linkLocalAddresses;

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
                               SecurityGroupFirewallManager firewallManager,
                               EcsTaskRoleCredentials taskRoleCredentials,
                               EcsCredentialsProxy credentialsProxy,
                               EcsTaskLinkLocalAddresses linkLocalAddresses) {
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
        this.ec2Service = ec2Service;
        this.firewallManager = firewallManager;
        this.taskRoleCredentials = taskRoleCredentials;
        this.credentialsProxy = credentialsProxy;
        this.linkLocalAddresses = linkLocalAddresses;
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
        // The dependencies a caller can leave out: EC2 and the firewall for tasks that never use
        // an ENI or a security group, and the three credential collaborators for a Floci that
        // does not vend task-role credentials. Every call site that needs them uses the
        // constructor above; the code paths that touch them all check first.
        this(containerBuilder, lifecycleManager, logStreamer, containerDetector, config, regionResolver,
                awsEnv, ssmService, secretsManagerService, s3Service, ecrRegistryManager, hostVolumePolicy,
                null, null, null, null, null);
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
        ContainerDefinition firelensRouter = findFirelensRouter(taskDef.getContainerDefinitions());
        Map<String, Map<String, String>> firelensLogOptions =
                awsFirelensLogOptions(taskDef.getContainerDefinitions());
        if (!firelensLogOptions.isEmpty() && firelensRouter == null) {
            throw new AwsException("ClientException",
                    "awsfirelens log driver requires a firelensConfiguration container", 400);
        }

        List<ContainerDefinition> launchOrder = orderForDependencies(
                launchOrder(taskDef.getContainerDefinitions(), firelensRouter), firelensRouter);

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
        // Both resolved before any environment is built: whether the task gets role credentials
        // decides whether its containers may also carry Floci's baseline static credentials,
        // which would otherwise win over the role in the SDK credential chain.
        PreparedNetwork protectedNetwork = prepareNetwork(task, taskDef, region, taskId);
        TaskRoleCredentialEndpoint taskRoleEndpoint = prepareTaskRoleCredentials(task, taskDef, protectedNetwork);
        Map<ContainerDefinition, List<String>> envVarsByContainer = new LinkedHashMap<>();
        // Resolved before any container is created, so a registry-startup failure can't leak one already started.
        Map<ContainerDefinition, String> imagesByContainer = new LinkedHashMap<>();
        // The task metadata id has to exist before the container does: its own environment carries
        // the URI, so it cannot be derived from the Docker id the daemon hands back afterwards.
        Map<String, String> metadataIdsByContainer = new LinkedHashMap<>();
        String firelensVolumeName = null;
        String firelensSocketAddress = null;
        String firelensConfig = null;
        String firelensExternalConfig = null;
        try {
            for (ContainerDefinition def : launchOrder) {
                String metadataId = UUID.randomUUID().toString().replace("-", "");
                metadataIdsByContainer.put(def.getName(), metadataId);
                envVarsByContainer.put(def, buildEnvVars(def, overridesByName.get(def.getName()), region,
                        metadataId, taskRoleEndpoint.vending()));
                imagesByContainer.put(def, ecrRegistryManager.rewriteImageUri(def.getImage()));
            }

            if (firelensRouter != null) {
                firelensConfig = firelensConfig(task, taskDef, firelensRouter, firelensLogOptions);
                firelensExternalConfig = s3ExternalConfig(firelensRouter);
                firelensVolumeName = ContainerStorageHelper.dockerName(config, "ecs-firelens-" + taskId);
                lifecycleManager.ensureVolume(firelensVolumeName);
                firelensSocketAddress = unixSocketAddress(firelensVolumeName);
            }
        } catch (RuntimeException e) {
            // Anything here can throw before a single container exists: an unresolvable secret,
            // a registry that will not start, a FireLens config that will not render. The
            // credentials are already issued by now and the caller only marks the task STOPPED,
            // so without this they would stay valid with nothing behind them until they expire.
            revokeTaskRoleCredentials(task.getTaskArn());
            releaseTaskLinkLocalAddresses(task.getTaskArn());
            throw e;
        }

        String fluentHost = null;
        String networkModeName = taskDef.getNetworkMode() != null
                ? taskDef.getNetworkMode().name()
                : NetworkMode.bridge.name();

        try {
            for (ContainerDefinition def : launchOrder) {
                awaitDependencies(def, containerIds);
                String containerName = ContainerStorageHelper.dockerName(config, "ecs-" + taskId + "-" + def.getName());

                // RunTask containerOverrides matched by container name: command replaces
                // the task-def command; environment is merged over the task-def environment.
                ContainerOverride override = overridesByName.get(def.getName());

                List<String> env = new ArrayList<>(envVarsByContainer.get(def));
                if (fluentHost != null && def != firelensRouter
                        && FirelensConfigGenerator.addsTcpForward(networkModeName)) {
                    env.add("FLUENT_HOST=" + fluentHost);
                    env.add("FLUENT_PORT=" + FirelensConfigGenerator.FORWARD_PORT);
                }
                // Every container in the task is pointed at the same relative URI, the way real
                // ECS vends one credential path per task rather than per container.
                if (taskRoleEndpoint.vending()) {
                    env.add("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI=" + taskRoleEndpoint.relativeUri());
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
                // Its own address in the credential endpoint's range, so this container has a
                // connected route to 169.254.170.2 at all. One per container, not per task: each
                // has its own network namespace and so its own routing table.
                if (taskRoleEndpoint.vending()) {
                    specBuilder.withLinkLocalIp(
                            linkLocalAddresses.allocate(taskRoleEndpoint.network(), task.getTaskArn()));
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

                // Resource limits. A container-level memory is its own hard limit; without one the
                // container is capped at the task's memory, which is what a Fargate task gets
                // (the whole task shares one allocation). The task's cpu becomes a quota and a
                // container-level cpu becomes its share of that quota, as on ECS.
                if (def.getMemory() != null) {
                    specBuilder.withMemoryMb(def.getMemory());
                } else {
                    Integer taskMemoryMb = parseTaskMemoryMb(task.getMemory());
                    if (taskMemoryMb != null) {
                        specBuilder.withMemoryMb(taskMemoryMb);
                    }
                }
                Integer taskCpuUnits = parseTaskCpuUnits(task.getCpu());
                if (taskCpuUnits != null) {
                    specBuilder.withCpuUnits(taskCpuUnits);
                }
                if (def.getCpu() != null && def.getCpu() > 0) {
                    specBuilder.withCpuShares(def.getCpu());
                }
                if (def.getUser() != null) {
                    specBuilder.withUser(def.getUser());
                }
                if (def.getWorkingDirectory() != null) {
                    specBuilder.withWorkingDir(def.getWorkingDirectory());
                }
                if (Boolean.TRUE.equals(def.getReadonlyRootFilesystem())) {
                    specBuilder.withReadonlyRootfs();
                }

                // Add port mappings. In bridge/host mode an explicit hostPort is
                // published to the Docker host literally, matching AWS bridge mode
                // (mirrors the ECR registry's fixed-port publishing). In awsvpc mode
                // every AWS task gets its own ENI, so a literal hostPort carries no
                // host-binding semantics and would collide across tasks on the single
                // local Docker host (#1778) — awsvpc mappings always get a dynamic
                // host port in native mode, or expose-only in Docker mode where ECS
                // consumers reach containers via the docker network IP. The explicit
                // emulator opt-in below publishes a stable port for host-side clients.
                if (protectedNetwork == null && def.getPortMappings() != null) {
                    boolean awsvpc = taskDef.getNetworkMode() == NetworkMode.awsvpc;
                    boolean publishAwsvpcPorts = awsvpc
                            && config.services().ecs().publishAwsvpcPortsToHost();
                    boolean publishDynamicPortsToHost = !containerDetector.isRunningInContainer();
                    for (PortMapping pm : def.getPortMappings()) {
                        if (publishAwsvpcPorts) {
                            int hostPort = pm.hostPort() > 0 ? pm.hostPort() : pm.containerPort();
                            LOG.warnv("Publishing ECS awsvpc container port {0} on host port {1}; "
                                            + "multiple tasks cannot share this host port",
                                    pm.containerPort(), hostPort);
                            specBuilder.withPortBinding(pm.containerPort(), hostPort);
                        } else if (!awsvpc && pm.hostPort() > 0) {
                            specBuilder.withPortBinding(pm.containerPort(), pm.hostPort());
                        } else if (publishDynamicPortsToHost) {
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

                if (def.getVolumesFrom() != null) {
                    for (VolumeFrom volumeFrom : def.getVolumesFrom()) {
                        String sourceContainerId = containerIds.get(volumeFrom.sourceContainer());
                        if (sourceContainerId == null) {
                            throw new IllegalStateException("ECS volumesFrom source container "
                                    + volumeFrom.sourceContainer() + " has not started");
                        }
                        specBuilder.withVolumesFrom(sourceContainerId, volumeFrom.readOnly());
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
                Container container = buildContainer(task.getTaskArn(), def, dockerId, networkBindings, region,
                        metadataIdsByContainer.get(def.getName()));
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
            boolean allRemoved = true;
            for (String dockerId : containerIds.values()) {
                if (!stopAndConfirmRemoved(dockerId)) {
                    allRemoved = false;
                }
            }
            if (firelensVolumeName != null) {
                lifecycleManager.removeVolume(firelensVolumeName);
            }
            if (protectedNetwork != null) {
                releaseTaskNetwork(task, region);
            }
            revokeTaskRoleCredentials(task.getTaskArn());
            // A container left behind by a failed removal is still on the network holding its
            // address, exactly as on the stop path.
            if (allRemoved) {
                releaseTaskLinkLocalAddresses(task.getTaskArn());
            }
            throw e;
        }

        Map<String, Container> runtimeContainersByName = new LinkedHashMap<>();
        for (Container container : runtimeContainers) {
            runtimeContainersByName.put(container.getName(), container);
        }
        List<Container> containersInDefinitionOrder = new ArrayList<>();
        for (ContainerDefinition definition : taskDef.getContainerDefinitions()) {
            Container container = runtimeContainersByName.get(definition.getName());
            if (container != null) {
                containersInDefinitionOrder.add(container);
            }
        }
        // An awsvpc task's containers share the task ENI, and each reports it.
        if (task.getNetworkInterfaceId() != null) {
            List<TaskNetworkInterface> taskInterfaces = List.of(new TaskNetworkInterface(
                    task.getAttachmentId(), task.getPrivateIpAddress(), null));
            containersInDefinitionOrder.forEach(container -> container.setNetworkInterfaces(taskInterfaces));
        }
        // ECS Exec is gated on the agent reporting RUNNING, which a client checks before it tries
        // to open a session.
        if (task.isEnableExecuteCommand()) {
            List<ManagedAgent> agents = List.of(ManagedAgent.executeCommandAgent(Instant.now()));
            containersInDefinitionOrder.forEach(container -> container.setManagedAgents(agents));
        }
        task.setContainers(containersInDefinitionOrder);
        task.setLastStatus(TaskStatus.RUNNING.name());
        task.setDesiredStatus(TaskStatus.RUNNING.name());
        task.setStartedAt(Instant.now());

        Map<String, Integer> stopTimeouts = new LinkedHashMap<>();
        for (ContainerDefinition def : taskDef.getContainerDefinitions()) {
            if (def.getStopTimeout() != null) {
                stopTimeouts.put(def.getName(), def.getStopTimeout());
            }
        }
        return new EcsTaskHandle(task.getTaskArn(), containerIds, logStreamsByContainerId,
                firelensVolumeName,
                protectedNetwork == null ? null : protectedNetwork.eni().getNetworkInterfaceId(), region,
                stopTimeouts);
    }

    /** A task-level {@code memory} is MiB as a plain number, or a {@code "1GB"}-style string. */
    private static Integer parseTaskMemoryMb(String memory) {
        if (memory == null || memory.isBlank()) {
            return null;
        }
        String trimmed = memory.trim().toUpperCase();
        try {
            if (trimmed.endsWith("GB")) {
                return (int) Math.round(Double.parseDouble(trimmed.substring(0, trimmed.length() - 2).trim()) * 1024);
            }
            return Integer.parseInt(trimmed.replaceAll("(?i)mib|mb", "").trim());
        } catch (NumberFormatException e) {
            LOG.debugv("Ignoring unparseable task memory {0}", memory);
            return null;
        }
    }

    /** A task-level {@code cpu} is CPU units as a plain number, or a {@code "0.5vCPU"}-style string. */
    private static Integer parseTaskCpuUnits(String cpu) {
        if (cpu == null || cpu.isBlank()) {
            return null;
        }
        String trimmed = cpu.trim().toUpperCase();
        try {
            if (trimmed.endsWith("VCPU")) {
                return (int) Math.round(
                        Double.parseDouble(trimmed.substring(0, trimmed.length() - 4).trim()) * 1024);
            }
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            LOG.debugv("Ignoring unparseable task cpu {0}", cpu);
            return null;
        }
    }

    /**
     * Holds a container's start until the containers it declared a {@code dependsOn} on have
     * reached the state it asked for. {@code START} is satisfied by the launch order alone;
     * {@code COMPLETE} and {@code SUCCESS} wait for the dependency to exit (and {@code SUCCESS}
     * additionally requires it to have exited cleanly); {@code HEALTHY} waits for its health check
     * to pass.
     *
     * <p>The wait is bounded by the dependent container's {@code startTimeout}, defaulting to
     * {@value #DEPENDENCY_WAIT_SECONDS} seconds rather than AWS's longer agent default, because
     * RunTask here is synchronous and a caller is holding the request open. A dependency that does
     * not get there in time fails the task.
     */
    private void awaitDependencies(ContainerDefinition definition, Map<String, String> startedContainerIds) {
        if (definition.getDependsOn() == null || definition.getDependsOn().isEmpty()) {
            return;
        }
        long timeoutSeconds = definition.getStartTimeout() != null
                ? definition.getStartTimeout() : DEPENDENCY_WAIT_SECONDS;
        for (ContainerDependency dependency : definition.getDependsOn()) {
            String condition = dependency.condition();
            if (ContainerDependency.START.equals(condition)) {
                continue;
            }
            String dockerId = startedContainerIds.get(dependency.containerName());
            if (dockerId == null) {
                continue;
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (!dependencySatisfied(dockerId, condition)) {
                if (System.nanoTime() > deadline) {
                    throw new AwsException("ClientException",
                            "Container '" + definition.getName() + "' did not start because container '"
                                    + dependency.containerName() + "' did not reach " + condition
                                    + " within " + timeoutSeconds + " seconds.", 400);
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(DEPENDENCY_POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for ECS container dependency "
                            + dependency.containerName(), e);
                }
            }
        }
    }

    private boolean dependencySatisfied(String dockerId, String condition) {
        if (ContainerDependency.HEALTHY.equals(condition)) {
            return "healthy".equals(inspectHealthStatus(dockerId));
        }
        Integer exitCode = getExitCodeIfStopped(dockerId);
        if (exitCode == null) {
            return false;
        }
        if (ContainerDependency.SUCCESS.equals(condition) && exitCode != 0) {
            throw new AwsException("ClientException",
                    "A dependency container exited with code " + exitCode
                            + ", so the containers depending on its SUCCESS were not started.", 400);
        }
        return true;
    }

    /**
     * A container's health in ECS's vocabulary: {@code HEALTHY} or {@code UNHEALTHY} once its
     * health check has reported, {@code UNKNOWN} while it is still starting or when the container
     * declares no health check at all.
     */
    public String ecsHealthStatus(String dockerId) {
        String status = inspectHealthStatus(dockerId);
        if (status == null) {
            return "UNKNOWN";
        }
        return switch (status) {
            case "healthy" -> "HEALTHY";
            case "unhealthy" -> "UNHEALTHY";
            default -> "UNKNOWN";
        };
    }

    /** The Docker health status of a container, or null when it declares no health check. */
    private String inspectHealthStatus(String dockerId) {
        try {
            InspectContainerResponse inspect = lifecycleManager.getDockerClient()
                    .inspectContainerCmd(dockerId).exec();
            return inspect.getState() != null && inspect.getState().getHealth() != null
                    ? inspect.getState().getHealth().getStatus() : null;
        } catch (Exception e) {
            LOG.debugv("Could not read the health of container {0}: {1}", dockerId, e.getMessage());
            return null;
        }
    }

    /**
     * A container's Docker stats as the task metadata endpoint reports them: the latest sample,
     * plus the network byte rates. The rates are null when they cannot be derived, and Docker
     * never reports them itself: they are a delta the ECS agent takes across two samples.
     */
    public record ContainerStats(Statistics statistics, Double rxBytesPerSecond, Double txBytesPerSecond) {}

    /**
     * Reads a container's stats from the daemon. Empty when there is nothing to read: the
     * container has no Docker id, it has already gone, or the daemon is unreachable.
     *
     * <p>The stats stream is read rather than a single {@code stream=false} document, because the
     * network rates are a delta between consecutive samples. Docker sends the first sample at once
     * and the next on its collection tick a second later, which is the same second AWS documents a
     * container as having to run before its stats are available.
     */
    public Optional<ContainerStats> sampleContainerStats(String dockerId) {
        if (dockerId == null || dockerId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(sampleContainerStats(List.of(dockerId)).get(dockerId));
    }

    /**
     * Reads several containers' stats in one pass, keyed by Docker id. A container the daemon has
     * nothing to say about is absent from the map rather than present with an empty sample.
     *
     * <p>Every stream is opened before any of them is waited on, and the waits share one deadline.
     * Docker's stats stream is asynchronous, so the containers tick alongside each other and the
     * call costs about the single collection tick one container costs, however many containers the
     * task has. That matters on {@code /task/stats}, which is the path a sidecar polls: sampling
     * the containers one after another would cost a tick each.
     */
    public Map<String, ContainerStats> sampleContainerStats(List<String> dockerIds) {
        Map<String, ContainerStats> sampled = new LinkedHashMap<>();
        if (dockerIds == null || dockerIds.isEmpty()) {
            return sampled;
        }
        Map<String, OpenStatsStream> streams = openStatsStreams(dockerIds);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STATS_SAMPLE_SECONDS);
            for (Map.Entry<String, OpenStatsStream> stream : streams.entrySet()) {
                awaitPair(stream.getKey(), stream.getValue().samples(), deadline);
                containerStats(stream.getValue().samples().collected())
                        .ifPresent(stats -> sampled.put(stream.getKey(), stats));
            }
        } finally {
            for (OpenStatsStream stream : streams.values()) {
                closeQuietly(stream.samples());
                closeQuietly(stream.command());
            }
        }
        return sampled;
    }

    /** A stats stream the daemon is already filling, and the command that has to be closed with it. */
    private record OpenStatsStream(StatsCmd command, ConsecutiveStatsSamples samples) {}

    /** Starts every container's stats stream, skipping the ones the daemon will not open. */
    private Map<String, OpenStatsStream> openStatsStreams(List<String> dockerIds) {
        Map<String, OpenStatsStream> streams = new LinkedHashMap<>();
        for (String dockerId : dockerIds) {
            if (dockerId == null || dockerId.isBlank() || streams.containsKey(dockerId)) {
                continue;
            }
            StatsCmd command = null;
            try {
                command = lifecycleManager.getDockerClient().statsCmd(dockerId);
                ConsecutiveStatsSamples samples = new ConsecutiveStatsSamples();
                command.exec(samples);
                streams.put(dockerId, new OpenStatsStream(command, samples));
            } catch (RuntimeException e) {
                closeQuietly(command);
                LOG.debugv("Could not sample stats for container {0}: {1}", dockerId, e.getMessage());
            }
        }
        return streams;
    }

    /** Waits for one stream's pair of samples, never past the deadline the whole pass shares. */
    private static void awaitPair(String dockerId, ConsecutiveStatsSamples samples, long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        try {
            if (remaining <= 0 || !samples.awaitPair(remaining, TimeUnit.NANOSECONDS)) {
                LOG.debugv("Docker sent fewer than two stats samples for container {0} within {1}s",
                        dockerId, STATS_SAMPLE_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.debugv("Interrupted while sampling stats for container {0}", dockerId);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException | RuntimeException e) {
            LOG.debugv("Could not close a Docker stats stream: {0}", e.getMessage());
        }
    }

    /** The latest sample, with rates when two samples a measurable interval apart arrived. */
    private static Optional<ContainerStats> containerStats(List<Statistics> samples) {
        if (samples.isEmpty()) {
            return Optional.empty();
        }
        Statistics latest = samples.getLast();
        if (samples.size() < 2) {
            return Optional.of(new ContainerStats(latest, null, null));
        }
        Statistics earlier = samples.getFirst();
        double seconds = secondsBetween(earlier.getRead(), latest.getRead());
        Long rxDelta = byteDelta(earlier, latest, StatisticNetworksConfig::getRxBytes);
        Long txDelta = byteDelta(earlier, latest, StatisticNetworksConfig::getTxBytes);
        if (seconds <= 0 || rxDelta == null || txDelta == null) {
            return Optional.of(new ContainerStats(latest, null, null));
        }
        return Optional.of(new ContainerStats(latest, rxDelta / seconds, txDelta / seconds));
    }

    /** The counter's movement across the two samples, or null when neither reports an interface. */
    private static Long byteDelta(Statistics earlier, Statistics latest,
                                  Function<StatisticNetworksConfig, Long> counter) {
        Long earlierBytes = totalBytes(earlier, counter);
        Long latestBytes = totalBytes(latest, counter);
        if (earlierBytes == null || latestBytes == null) {
            return null;
        }
        return Math.max(0, latestBytes - earlierBytes);
    }

    private static Long totalBytes(Statistics statistics,
                                   Function<StatisticNetworksConfig, Long> counter) {
        Map<String, StatisticNetworksConfig> networks = statistics.getNetworks();
        if (networks == null || networks.isEmpty()) {
            return null;
        }
        long total = 0;
        for (StatisticNetworksConfig network : networks.values()) {
            Long bytes = counter.apply(network);
            total += bytes != null ? bytes : 0;
        }
        return total;
    }

    /** The interval between two Docker read timestamps, or 0 when either cannot be read. */
    private static double secondsBetween(String earlier, String latest) {
        if (earlier == null || latest == null) {
            return 0;
        }
        try {
            return Duration.between(Instant.parse(earlier), Instant.parse(latest)).toNanos() / 1_000_000_000.0;
        } catch (DateTimeParseException e) {
            LOG.debugv("Docker stats carried an unreadable read timestamp: {0}", e.getMessage());
            return 0;
        }
    }

    /** Docker's stats stream never ends on its own; this takes the first two samples and stops. */
    private static final class ConsecutiveStatsSamples extends ResultCallback.Adapter<Statistics> {

        private static final int WANTED = 2;

        private final CountDownLatch enough = new CountDownLatch(WANTED);
        private final List<Statistics> collected = new CopyOnWriteArrayList<>();

        @Override
        public void onNext(Statistics statistics) {
            if (collected.size() < WANTED) {
                collected.add(statistics);
                enough.countDown();
            }
        }

        @Override
        public void onError(Throwable throwable) {
            releaseWaiter();
            super.onError(throwable);
        }

        @Override
        public void onComplete() {
            releaseWaiter();
            super.onComplete();
        }

        /** A stream that ends or fails early must not hold the caller until the timeout. */
        private void releaseWaiter() {
            while (enough.getCount() > 0) {
                enough.countDown();
            }
        }

        boolean awaitPair(long timeout, TimeUnit unit) throws InterruptedException {
            return enough.await(timeout, unit);
        }

        List<Statistics> collected() {
            return List.copyOf(collected);
        }
    }

    private List<ContainerDefinition> orderForDependencies(List<ContainerDefinition> definitions,
                                                          ContainerDefinition firelensRouter) {
        Map<String, ContainerDefinition> definitionsByName = new LinkedHashMap<>();
        for (ContainerDefinition definition : definitions) {
            definitionsByName.put(definition.getName(), definition);
        }

        List<ContainerDefinition> ordered = new ArrayList<>();
        Set<ContainerDefinition> visiting = new HashSet<>();
        Set<ContainerDefinition> visited = new HashSet<>();
        for (ContainerDefinition definition : definitions) {
            addAfterDependencies(definition, definitionsByName, firelensRouter, visiting, visited, ordered);
        }
        return ordered;
    }

    private void addAfterDependencies(ContainerDefinition definition,
                                      Map<String, ContainerDefinition> definitionsByName,
                                      ContainerDefinition firelensRouter,
                                      Set<ContainerDefinition> visiting,
                                      Set<ContainerDefinition> visited,
                                      List<ContainerDefinition> ordered) {
        if (visited.contains(definition)) {
            return;
        }
        if (!visiting.add(definition)) {
            throw new IllegalArgumentException("ECS container dependencies contain a cycle at container "
                    + definition.getName());
        }
        if (isAwsFirelens(definition)) {
            addAfterDependencies(firelensRouter, definitionsByName, firelensRouter, visiting, visited, ordered);
        }
        if (definition.getVolumesFrom() != null) {
            for (VolumeFrom volumeFrom : definition.getVolumesFrom()) {
                ContainerDefinition source = definitionsByName.get(volumeFrom.sourceContainer());
                if (source == null) {
                    throw new IllegalArgumentException("ECS volumesFrom references unknown source container "
                            + volumeFrom.sourceContainer());
                }
                addAfterDependencies(source, definitionsByName, firelensRouter, visiting, visited, ordered);
            }
        }
        if (definition.getDependsOn() != null) {
            for (ContainerDependency dependency : definition.getDependsOn()) {
                ContainerDefinition source = definitionsByName.get(dependency.containerName());
                if (source == null) {
                    throw new IllegalArgumentException("ECS dependsOn references unknown container "
                            + dependency.containerName());
                }
                addAfterDependencies(source, definitionsByName, firelensRouter, visiting, visited, ordered);
            }
        }
        visiting.remove(definition);
        visited.add(definition);
        ordered.add(definition);
    }

    /**
     * Gives an {@code awsvpc} task the elastic network interface AWS gives it: its own ENI in the
     * requested subnet, with a private address of its own and the task's security groups.
     *
     * <p>Called for every {@code awsvpc} task, in Docker and in mock mode alike, because the ENI is
     * control-plane state that DescribeTasks reports (a client waits on
     * {@code attachments[].details[?name=='privateIPv4Address']} to find the task) rather than a
     * property of the containers. Security-group enforcement, which additionally puts the task's
     * containers in a protected network namespace, stays opt-in on top of it.
     *
     * <p>A subnet or security group that does not exist fails the task, reported as the
     * {@code InvalidParameterException} RunTask declares rather than the EC2 code
     * {@link Ec2Service#createNetworkInterface} raises.
     */
    public void attachTaskNetwork(EcsTask task, TaskDefinition definition, String region) {
        if (definition.getNetworkMode() != NetworkMode.awsvpc || ec2Service == null) {
            return;
        }
        AwsVpcConfiguration awsvpc = task.getNetworkConfiguration() == null ? null
                : task.getNetworkConfiguration().getAwsvpcConfiguration();
        if (awsvpc == null || awsvpc.getSubnets() == null || awsvpc.getSubnets().isEmpty()) {
            throw new AwsException("InvalidParameterException",
                    "Network Configuration must be provided when networkMode 'awsvpc' is specified.", 400);
        }
        String subnetId = awsvpc.getSubnets().getFirst();
        NetworkInterface eni;
        try {
            eni = ec2Service.createNetworkInterface(region, subnetId,
                    "ECS task " + task.getTaskArn(), null, List.of(), awsvpc.getSecurityGroups(), List.of());
        } catch (AwsException e) {
            throw asEcsNetworkRejection(e);
        }
        // A running task holds its interface, so EC2 must report it "in-use" rather than
        // "available" as a fresh one starts out, and must refuse to delete it under the task.
        ec2Service.holdNetworkInterfaceForService(region, eni.getNetworkInterfaceId());
        task.setNetworkInterfaceId(eni.getNetworkInterfaceId());
        task.setPrivateIpAddress(eni.getPrivateIpAddress());
        task.setMacAddress(eni.getMacAddress());
        task.setPrivateDnsName(eni.getPrivateDnsName());
        task.setAttachmentId("eni-attach-" + eni.getNetworkInterfaceId());
        ec2Service.describeSubnets(region, List.of(subnetId), Map.of()).stream()
                .findFirst()
                .ifPresent(subnet -> task.setAvailabilityZone(subnet.getAvailabilityZone()));
    }

    /**
     * Restates an EC2 lookup failure as the error RunTask declares. A caller naming a subnet or a
     * security group that is not there asked for something the task cannot have, and
     * {@code InvalidSubnetID.NotFound} is not in RunTask's error list, so an SDK sees it as an
     * unmodelled failure rather than the InvalidParameterException AWS returns. Anything else the
     * ENI path raises is already an ECS-shaped error and travels unchanged.
     */
    private static AwsException asEcsNetworkRejection(AwsException e) {
        if (!EC2_NETWORK_LOOKUP_FAILURES.contains(e.getErrorCode())) {
            return e;
        }
        return new AwsException("InvalidParameterException", e.getMessage(), 400);
    }

    /**
     * Releases a stopped task's ENI. The interface id stays on the task, with its attachment
     * reported as {@code DELETED}, because AWS keeps describing a stopped task's attachment.
     * Idempotent: the status is flipped first, so a second teardown path does nothing.
     */
    public void releaseTaskNetwork(EcsTask task, String region) {
        String eniId = task.getNetworkInterfaceId();
        if (eniId == null || ec2Service == null || ATTACHMENT_DELETED.equals(task.getAttachmentStatus())) {
            return;
        }
        task.setAttachmentStatus(ATTACHMENT_DELETED);
        if (firewallManager != null) {
            firewallManager.unregister(eniId);
        }
        try {
            // EC2 refuses to delete an interface that is in use, and attachTaskNetwork marked
            // this one as held by the task, so the hold goes before the interface does.
            ec2Service.releaseNetworkInterfaceFromService(region, eniId);
            ec2Service.deleteNetworkInterface(region, eniId);
        } catch (RuntimeException e) {
            LOG.debugv("Could not delete the ENI {0} of task {1}: {2}",
                    eniId, task.getTaskArn(), e.getMessage());
        }
    }

    /** The ENI {@link #attachTaskNetwork} allocated for this task, or null if it is already gone. */
    private NetworkInterface existingTaskEni(EcsTask task, String region) {
        if (task.getNetworkInterfaceId() == null) {
            return null;
        }
        try {
            return ec2Service.describeNetworkInterfaces(region, List.of(task.getNetworkInterfaceId()),
                            Map.of(), 0, null)
                    .networkInterfaces().stream().findFirst().orElse(null);
        } catch (RuntimeException e) {
            LOG.debugv("Could not resolve the ENI {0} of task {1}, allocating a new one: {2}",
                    task.getNetworkInterfaceId(), task.getTaskArn(), e.getMessage());
            return null;
        }
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
        // attachTaskNetwork already allocated the task ENI; the namespace is built around it.
        NetworkInterface eni = existingTaskEni(task, region);
        if (eni == null) {
            eni = ec2Service.createNetworkInterface(region, awsvpc.getSubnets().getFirst(),
                    "ECS task " + task.getTaskArn(), null, List.of(), awsvpc.getSecurityGroups(), List.of());
            ec2Service.holdNetworkInterfaceForService(region, eni.getNetworkInterfaceId());
        }
        String eniId = eni.getNetworkInterfaceId();
        SecurityGroupFirewallManager.Namespace namespace = null;
        try {
            Map<Integer, Integer> bindings = namespacePortBindings(
                    definition,
                    containerDetector.isRunningInContainer(),
                    config.services().ecs().publishAwsvpcPortsToHost());
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
            ec2Service.releaseNetworkInterfaceFromService(region, eniId);
            ec2Service.deleteNetworkInterface(region, eniId);
            throw e;
        }
    }

    static Map<Integer, Integer> namespacePortBindings(
            TaskDefinition definition, boolean runningInContainer, boolean publishAwsvpcPortsToHost) {
        Map<Integer, Integer> bindings = new LinkedHashMap<>();
        if (runningInContainer && !publishAwsvpcPortsToHost) {
            return bindings;
        }
        for (ContainerDefinition container : definition.getContainerDefinitions()) {
            if (container.getPortMappings() == null) {
                continue;
            }
            for (PortMapping port : container.getPortMappings()) {
                int hostPort = publishAwsvpcPortsToHost
                        ? (port.hostPort() > 0 ? port.hostPort() : port.containerPort())
                        : 0;
                bindings.put(port.containerPort(), hostPort);
            }
        }
        return bindings;
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
        boolean allRemoved = true;
        for (String dockerId : handle.getContainerIds().values()) {
            if (!stopAndConfirmRemoved(dockerId)) {
                allRemoved = false;
            }
        }
        cleanupProtectedNetwork(handle);
        new ArrayList<>(handle.getLogStreamsByContainerId().keySet())
                .forEach(dockerId -> finalizeLogStream(handle, dockerId));
        removeFirelensVolume(handle);
        revokeTaskRoleCredentials(handle.getTaskArn());
        if (allRemoved) {
            releaseTaskLinkLocalAddresses(handle.getTaskArn());
        }
    }

    /**
     * Stops and removes a container, reporting whether it is really gone.
     *
     * <p>{@code stopAndRemove} logs a failed removal and returns normally, which is the right
     * default for callers that only want the container off the machine on a best-effort basis. It
     * is not enough here: a container that survived removal still holds its link-local address,
     * and releasing it would hand the same address to the next task. The strict variant throws on
     * that case, which is the signal this needs.
     */
    private boolean stopAndConfirmRemoved(String dockerId) {
        try {
            lifecycleManager.stopAndRemoveStrict(dockerId, null);
            return true;
        } catch (RuntimeException e) {
            LOG.warnv("Error removing ECS container {0}: {1}", dockerId, e.getMessage());
            return false;
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

        // Phase 1: stop all containers (no-op for those already exited), each given the grace
        // period its container definition asked for.
        Set<String> terminatedContainerIds = new HashSet<>();
        for (Map.Entry<String, String> entry : handle.getContainerIds().entrySet()) {
            if (handle.isContainerRemoved(entry.getKey())) {
                continue;
            }
            String dockerId = entry.getValue();
            try {
                lifecycleManager.getDockerClient().stopContainerCmd(dockerId)
                        .withTimeout(handle.stopTimeoutFor(entry.getKey())).exec();
                terminatedContainerIds.add(dockerId);
            } catch (NotFoundException ignored) {
                terminatedContainerIds.add(dockerId);
            } catch (Exception e) {
                LOG.warnv("Error stopping ECS container {0}: {1}", dockerId, e.getMessage());
            }
            killIfStillRunning(dockerId);
        }

        // Phase 2: inspect exit codes, then remove.
        for (Map.Entry<String, String> entry : handle.getContainerIds().entrySet()) {
            String name = entry.getKey();
            if (handle.isContainerRemoved(name)) {
                exitCodes.put(name, handle.getRecordedExitCode(name));
                continue;
            }
            String dockerId = entry.getValue();
            Integer exitCode = handle.getRecordedExitCode(name);
            if (exitCode == null) {
                exitCode = getExitCodeIfStopped(dockerId);
                handle.recordExitCode(name, exitCode);
            }
            exitCodes.put(name, exitCode);
            // Read before the removal below, which is the last moment the daemon still knows it.
            handle.recordFinishedAt(name, getFinishedAtIfStopped(dockerId));
            try {
                lifecycleManager.getDockerClient().removeContainerCmd(dockerId).withForce(true).exec();
                terminatedContainerIds.add(dockerId);
                handle.recordContainerRemoved(name);
            } catch (NotFoundException ignored) {
                terminatedContainerIds.add(dockerId);
                handle.recordContainerRemoved(name);
            } catch (Exception e) {
                LOG.warnv("Error removing ECS container {0}: {1}", dockerId, e.getMessage());
                exitCodes.put(name, null);
            }
        }
        // A force removal terminates Docker's follow-log transport even when the preceding stop failed.
        // Preserve handles for any container that still may be running after both operations failed.
        terminatedContainerIds.forEach(dockerId -> finalizeLogStream(handle, dockerId));
        if (handle.allContainersRemoved()) {
            cleanupProtectedNetwork(handle);
            removeFirelensVolume(handle);
            // Only now: a container that survived a failed removal still holds its address.
            releaseTaskLinkLocalAddresses(handle.getTaskArn());
        }
        // Revoked whether or not removal succeeded, unlike everything above. A task being
        // stopped must not keep usable credentials just because Docker could not remove one of
        // its containers: the retry that would eventually revoke them depends on the handle
        // being retained, which only happens while a log stream is still open, so a container
        // without one (a FireLens router, say) would strand them for the life of the process.
        revokeTaskRoleCredentials(handle.getTaskArn());
        return exitCodes;
    }

    /**
     * Makes sure a container really is stopped before its exit code is read.
     *
     * <p>A container that ignores SIGTERM only dies when its {@code stopTimeout} elapses, and the
     * Docker client can give up on the stop request before the daemon gets there. Either way the
     * container would still be running when the exit code is collected, and the task would report
     * no exit code at all, so it is killed here.
     */
    private void killIfStillRunning(String dockerId) {
        if (getExitCodeIfStopped(dockerId) != null) {
            return;
        }
        try {
            lifecycleManager.getDockerClient().killContainerCmd(dockerId).exec();
        } catch (NotFoundException ignored) {
            return; // already gone, and its exit code is whatever the earlier inspect saw
        } catch (Exception e) {
            LOG.warnv("Error killing ECS container {0}: {1}", dockerId, e.getMessage());
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(KILL_SETTLE_SECONDS);
        while (System.nanoTime() < deadline && getExitCodeIfStopped(dockerId) == null) {
            try {
                TimeUnit.MILLISECONDS.sleep(DEPENDENCY_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
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
        ContainerDefinition router = null;
        for (ContainerDefinition def : defs) {
            FirelensConfiguration firelens = def.getFirelensConfiguration();
            if (firelens == null) {
                continue;
            }
            if (!"fluentbit".equalsIgnoreCase(firelens.type()) && !"fluentd".equalsIgnoreCase(firelens.type())) {
                throw new AwsException("ClientException",
                        "FireLens configuration type must be fluentbit or fluentd.", 400);
            }
            if (router != null) {
                throw new AwsException("ClientException",
                        "A task definition can have only one FireLens log router.", 400);
            }
            if (def.getPortMappings() != null && def.getPortMappings().stream()
                    .anyMatch(port -> port.containerPort() == 24224)) {
                throw new AwsException("ClientException",
                        "FireLens port 24224 must not be exposed.", 400);
            }
            router = def;
        }
        return router;
    }

    private static boolean isFluentdRouter(ContainerDefinition router) {
        return router != null
                && router.getFirelensConfiguration() != null
                && "fluentd".equalsIgnoreCase(router.getFirelensConfiguration().type());
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
    /**
     * Tears down the firewall registration of a protected task. The ENI itself outlives this:
     * it belongs to the task, not to its containers, and {@link #releaseTaskNetwork} frees it when
     * the task reaches STOPPED.
     */
    /**
     * Issues the task's role credentials and makes sure the endpoint that serves them is up,
     * before any of its containers start.
     *
     * <p>Empty when the task asks for no role, when the feature is off, when the role cannot be
     * resolved, or when the task is security-group protected: those containers join an isolated
     * helper namespace rather than the shared network the proxy holds its address on, so a proxy
     * there would not be reachable and this refuses rather than handing out an endpoint the task
     * cannot use. A proxy that cannot be established at all throws, since credentials whose
     * endpoint is unreachable would fail later and further from the cause.
     */
    private TaskRoleCredentialEndpoint prepareTaskRoleCredentials(EcsTask task, TaskDefinition taskDef,
                                                                  PreparedNetwork protectedNetwork) {
        String taskRoleArn = taskDef.getTaskRoleArn();
        if (taskRoleArn == null || taskRoleArn.isBlank()) {
            return TaskRoleCredentialEndpoint.none();
        }
        if (taskRoleCredentials == null || credentialsProxy == null
                || !config.services().ecs().taskRoleCredentials().enabled()) {
            LOG.debugv("Task {0} asks for role {1}, but ECS task-role credentials are disabled",
                    task.getTaskArn(), taskRoleArn);
            return TaskRoleCredentialEndpoint.none();
        }
        if (protectedNetwork != null) {
            LOG.warnv("Task {0} asks for role {1}, but its containers run in a security-group protected "
                    + "namespace, which the task-role credential endpoint does not reach yet",
                    task.getTaskArn(), taskRoleArn);
            return TaskRoleCredentialEndpoint.none();
        }
        Optional<String> network = containerBuilder.resolveDockerNetwork(config.services().ecs().dockerNetwork());
        if (network.isEmpty()) {
            LOG.warnv("Task {0} asks for role {1}, but no Docker network is configured for ECS tasks, so "
                    + "the credential endpoint has nowhere to listen", task.getTaskArn(), taskRoleArn);
            return TaskRoleCredentialEndpoint.none();
        }
        Optional<String> relativeUri = taskRoleCredentials.issue(
                task.getTaskArn(), taskRoleArn, regionResolver.getAccountId(), Instant.now());
        if (relativeUri.isEmpty()) {
            LOG.warnv("Task {0} asks for role {1}, which could not be resolved; its containers start "
                    + "without AWS_CONTAINER_CREDENTIALS_RELATIVE_URI", task.getTaskArn(), taskRoleArn);
            return TaskRoleCredentialEndpoint.none();
        }
        try {
            credentialsProxy.ensureProxyOn(network.get());
        } catch (RuntimeException e) {
            // The session is already registered at this point but the task will never start, and
            // the launch-failure cleanup below only covers what happens after this method
            // returns. Without this the credentials would stay valid with no task behind them.
            revokeTaskRoleCredentials(task.getTaskArn());
            releaseTaskLinkLocalAddresses(task.getTaskArn());
            throw e;
        }
        return new TaskRoleCredentialEndpoint(relativeUri.get(), network.get());
    }

    /**
     * Invalidates a task's credentials. Safe whenever the task is going away, and deliberately
     * not conditional on cleanup having succeeded: a stopping task keeping usable credentials is
     * worse than one whose containers are slow to disappear.
     */
    private void revokeTaskRoleCredentials(String taskArn) {
        if (taskRoleCredentials != null) {
            taskRoleCredentials.revoke(taskArn);
        }
    }

    /**
     * Returns a task's addresses to the pool. Unlike the credentials above, this is only safe
     * once its containers are actually gone: a container that outlived a failed removal still
     * holds its address on the network, and handing the same one to the next task would make
     * which container answers depend on ARP. An address left reserved is the cheaper mistake,
     * the range is a /16 and a restart clears it.
     */
    private void releaseTaskLinkLocalAddresses(String taskArn) {
        if (linkLocalAddresses != null) {
            linkLocalAddresses.releaseAll(taskArn);
        }
    }

    /**
     * What a task's containers need to reach the credential endpoint: the relative URI to publish
     * to them, and the network to give each of them an address on. Both are set or neither is,
     * which {@link #vending()} answers. Absence is a null rather than an {@code Optional} field,
     * which this codebase does not use.
     */
    private record TaskRoleCredentialEndpoint(String relativeUri, String network) {
        static TaskRoleCredentialEndpoint none() {
            return new TaskRoleCredentialEndpoint(null, null);
        }

        /** Whether this task gets credentials at all: both fields are set, or neither is. */
        boolean vending() {
            return relativeUri != null;
        }
    }

    private void cleanupProtectedNetwork(EcsTaskHandle handle) {
        if (firewallManager != null && handle.getNetworkInterfaceId() != null) {
            firewallManager.unregister(handle.getNetworkInterfaceId());
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

    private List<String> buildEnvVars(ContainerDefinition def, ContainerOverride override, String region,
                                      String metadataId, boolean vendingTaskRoleCredentials) {
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
        if (vendingTaskRoleCredentials) {
            // The SDK credential chain reads the environment before the container-credentials
            // endpoint (botocore resolves env_provider ahead of container_provider), so leaving
            // the baseline keys in place would silently shadow the task role with Floci's own
            // credentials and the role would never be used. Real ECS does not put these in a
            // task container either. Removed before the task's own environment is applied, so a
            // task that deliberately sets its own credentials still keeps them.
            BASELINE_CREDENTIAL_KEYS.forEach(envMap::remove);
        }
        // The task metadata endpoint, which an application, the ECS SDK integrations and the
        // aws-for-fluent-bit init process all read from this variable.
        String flociEndpoint = awsEnv.flociEndpoint();
        if (metadataId != null && flociEndpoint != null) {
            envMap.put("ECS_CONTAINER_METADATA_URI_V4", flociEndpoint + "/v4/" + metadataId);
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
                                     List<NetworkBinding> networkBindings, String region,
                                     String metadataId) {
        Container container = new Container();
        container.setTaskArn(taskArn);
        container.setName(def.getName());
        container.setImage(def.getImage());
        container.setLastStatus("RUNNING");
        container.setNetworkBindings(networkBindings);
        container.setDockerId(dockerId);
        container.setRuntimeId(dockerId);
        container.setMetadataId(metadataId);
        container.setHealthStatus(def.getHealthCheck() != null ? "UNKNOWN" : null);
        if (def.getCpu() != null) {
            container.setCpu(String.valueOf(def.getCpu()));
        }
        if (def.getMemory() != null) {
            container.setMemory(String.valueOf(def.getMemory()));
        }
        if (def.getMemoryReservation() != null) {
            container.setMemoryReservation(String.valueOf(def.getMemoryReservation()));
        }
        container.setContainerArn(regionResolver.buildArn("ecs", region,
                "container/" + extractTaskId(taskArn) + "/" + def.getName()));
        applyLaunchTimestamps(container, dockerId);
        return container;
    }

    /**
     * Stamps the container with Docker's own create and start times. A task's containers start one
     * at a time, holding for each other's {@code dependsOn} conditions, so these are the container's
     * and not the task's.
     */
    private void applyLaunchTimestamps(Container container, String dockerId) {
        InspectContainerResponse inspect = inspectOrNull(dockerId);
        if (inspect == null) {
            return;
        }
        container.setCreatedAt(dockerTimestamp(inspect.getCreated()));
        if (inspect.getState() != null) {
            container.setStartedAt(dockerTimestamp(inspect.getState().getStartedAt()));
        }
    }

    /**
     * When a container that has already stopped finished. Read before the container is removed,
     * since the daemon forgets it afterwards.
     */
    public Instant getFinishedAtIfStopped(String dockerId) {
        InspectContainerResponse inspect = inspectOrNull(dockerId);
        if (inspect == null || inspect.getState() == null
                || Boolean.TRUE.equals(inspect.getState().getRunning())) {
            return null;
        }
        return dockerTimestamp(inspect.getState().getFinishedAt());
    }

    private InspectContainerResponse inspectOrNull(String dockerId) {
        try {
            return lifecycleManager.getDockerClient().inspectContainerCmd(dockerId).exec();
        } catch (Exception e) {
            LOG.debugv("Could not inspect container {0}: {1}", dockerId, e.getMessage());
            return null;
        }
    }

    /** A Docker RFC 3339 timestamp, or null for its zero value, which means "never happened". */
    private static Instant dockerTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Instant instant = Instant.parse(value);
            return instant.isAfter(DOCKER_ZERO_TIMESTAMP) ? instant : null;
        } catch (DateTimeParseException e) {
            LOG.debugv("Docker reported an unreadable timestamp {0}: {1}", value, e.getMessage());
            return null;
        }
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
     * both the file system and the mount's effective root (see {@link #efsVolumeToken}), then
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
    static String efsVolumeToken(String fileSystemId, String accessPointId, String rootDirectory) {
        if (accessPointId != null && !accessPointId.isBlank()) {
            return "efs-" + fileSystemId + "-" + sha256Hex("accessPoint:" + accessPointId);
        }
        String normalizedRoot = normalizeRootDirectory(rootDirectory);
        if ("/".equals(normalizedRoot)) {
            return "efs-" + fileSystemId;
        }
        return "efs-" + fileSystemId + "-" + sha256Hex(normalizedRoot);
    }

    /**
     * The volume name for {@link #efsVolumeToken}. EFS volumes hold user data but carry no
     * persisted-name record, so probe: one created before the {@code floci-aws-} migration keeps
     * its legacy name, and its data, forever. Only when no legacy volume exists is the current
     * name used. The probe stays indefinitely; it is what makes upgrades across several versions
     * safe.
     */
    private String efsVolumeName(String fileSystemId, String accessPointId, String rootDirectory) {
        String token = efsVolumeToken(fileSystemId, accessPointId, rootDirectory);
        String legacyName = ContainerStorageHelper.legacyDockerName(config, token);
        if (lifecycleManager.volumeExists(legacyName)) {
            return legacyName;
        }
        return ContainerStorageHelper.dockerName(config, token);
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
