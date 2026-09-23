package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.services.ecs.model.Attribute;
import io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.ecs.model.CapacityProvider;
import io.github.hectorvent.floci.services.ecs.model.CapacityProviderStrategyItem;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.ContainerDependency;
import io.github.hectorvent.floci.services.ecs.model.ContainerImage;
import io.github.hectorvent.floci.services.ecs.model.ContainerInstance;
import io.github.hectorvent.floci.services.ecs.model.ContainerOverride;
import io.github.hectorvent.floci.services.ecs.model.Deployment;
import io.github.hectorvent.floci.services.ecs.model.EcsCluster;
import io.github.hectorvent.floci.services.ecs.model.EcsLoadBalancer;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.EfsVolumeConfiguration;
import io.github.hectorvent.floci.services.ecs.model.EnvironmentFile;
import io.github.hectorvent.floci.services.ecs.model.EphemeralStorage;
import io.github.hectorvent.floci.services.ecs.model.Failure;
import io.github.hectorvent.floci.services.ecs.model.FirelensConfiguration;
import io.github.hectorvent.floci.services.ecs.model.HealthCheck;
import io.github.hectorvent.floci.services.ecs.model.KeyValuePair;
import io.github.hectorvent.floci.services.ecs.model.LogConfiguration;
import io.github.hectorvent.floci.services.ecs.model.ManagedAgent;
import io.github.hectorvent.floci.services.ecs.model.MountPoint;
import io.github.hectorvent.floci.services.ecs.model.NetworkBinding;
import io.github.hectorvent.floci.services.ecs.model.PortMapping;
import io.github.hectorvent.floci.services.ecs.model.ProtectedTask;
import io.github.hectorvent.floci.services.ecs.model.RuntimePlatform;
import io.github.hectorvent.floci.services.ecs.model.Secret;
import io.github.hectorvent.floci.services.ecs.model.ServiceDeployment;
import io.github.hectorvent.floci.services.ecs.model.ServiceEvent;
import io.github.hectorvent.floci.services.ecs.model.ServiceRevision;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.ecs.model.TaskNetworkInterface;
import io.github.hectorvent.floci.services.ecs.model.TaskOverride;
import io.github.hectorvent.floci.services.ecs.model.TaskSet;
import io.github.hectorvent.floci.services.ecs.model.Volume;
import io.github.hectorvent.floci.services.ecs.model.VolumeFrom;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders ECS domain objects to the JSON 1.1 shapes the data plane returns.
 *
 * <p>Split from {@link EcsJsonHandler} so that request parsing and response rendering, which had
 * grown to comparable size, stay separately readable. The handler dispatches and parses; this
 * writes.
 */
@ApplicationScoped
public class EcsResponseWriter {

    private final EcsService service;
    private final ObjectMapper objectMapper;

    @Inject
    public EcsResponseWriter(EcsService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    // ── Clusters ──────────────────────────────────────────────────────────────

    /**
     * The include values DescribeClusters understands. Every operation other than DescribeClusters
     * returns the cluster whole, so {@link #clusterNode(EcsCluster)} passes all of them.
     */
    public static final Set<String> ALL_CLUSTER_INCLUDES =
            Set.of("ATTACHMENTS", "CONFIGURATIONS", "SETTINGS", "STATISTICS", "TAGS");

    public ObjectNode clusterNode(EcsCluster c) {
        return clusterNode(c, ALL_CLUSTER_INCLUDES);
    }

    /**
     * Renders a cluster, holding back the members DescribeClusters only returns when the request
     * asked for them: "If this field is omitted, this information isn't included."
     *
     * <p>The gated members are emitted whenever they are asked for, empty array and all, so a
     * client can tell an empty answer from a withheld one.
     */
    public ObjectNode clusterNode(EcsCluster c, Set<String> include) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("clusterArn", c.getClusterArn());
        n.put("clusterName", c.getClusterName());
        n.put("status", c.getStatus());
        n.put("registeredContainerInstancesCount", c.getRegisteredContainerInstancesCount());
        n.put("runningTasksCount", c.getRunningTasksCount());
        n.put("pendingTasksCount", c.getPendingTasksCount());
        n.put("activeServicesCount", c.getActiveServicesCount());
        if (include.contains("SETTINGS") && c.getSettings() != null && !c.getSettings().isEmpty()) {
            ArrayNode settings = objectMapper.createArrayNode();
            c.getSettings().forEach(s -> {
                ObjectNode sn = objectMapper.createObjectNode();
                sn.put("name", s.name());
                sn.put("value", s.value());
                settings.add(sn);
            });
            n.set("settings", settings);
        }
        if (include.contains("STATISTICS")) {
            ArrayNode statistics = objectMapper.createArrayNode();
            service.clusterStatistics(c).forEach(kv -> {
                ObjectNode sn = objectMapper.createObjectNode();
                sn.put("name", kv.name());
                sn.put("value", kv.value());
                statistics.add(sn);
            });
            n.set("statistics", statistics);
        }
        if (include.contains("ATTACHMENTS")) {
            // Floci creates no managed scaling policies, so a cluster never has attachments.
            n.set("attachments", objectMapper.createArrayNode());
        }
        if (include.contains("CONFIGURATIONS") && c.getConfiguration() != null) {
            n.set("configuration", objectMapper.valueToTree(c.getConfiguration()));
        }
        if (c.getCapacityProviders() != null) {
            ArrayNode cp = objectMapper.createArrayNode();
            c.getCapacityProviders().forEach(cp::add);
            n.set("capacityProviders", cp);
        }
        if (c.getDefaultCapacityProviderStrategy() != null) {
            n.set("defaultCapacityProviderStrategy",
                    objectMapper.valueToTree(c.getDefaultCapacityProviderStrategy()));
        }
        if (c.getServiceConnectDefaults() != null) {
            n.set("serviceConnectDefaults", objectMapper.valueToTree(c.getServiceConnectDefaults()));
        }
        if (include.contains("TAGS") && c.getTags() != null && !c.getTags().isEmpty()) {
            n.set("tags", tagsNode(c.getTags()));
        }
        return n;
    }

    // ── Task definitions ──────────────────────────────────────────────────────

    public ObjectNode taskDefinitionNode(TaskDefinition td) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("taskDefinitionArn", td.getTaskDefinitionArn());
        n.put("family", td.getFamily());
        n.put("revision", td.getRevision());
        n.put("status", td.getStatus());
        if (td.getNetworkMode() != null) {
            n.put("networkMode", td.getNetworkMode().name());
        }
        if (td.getCpu() != null) { n.put("cpu", td.getCpu()); }
        if (td.getMemory() != null) { n.put("memory", td.getMemory()); }
        if (td.getTaskRoleArn() != null) { n.put("taskRoleArn", td.getTaskRoleArn()); }
        if (td.getExecutionRoleArn() != null) { n.put("executionRoleArn", td.getExecutionRoleArn()); }
        if (td.getPidMode() != null) { n.put("pidMode", td.getPidMode()); }
        if (td.getIpcMode() != null) { n.put("ipcMode", td.getIpcMode()); }
        if (td.getEphemeralStorage() != null) {
            n.set("ephemeralStorage", ephemeralStorageNode(td.getEphemeralStorage()));
        }
        if (td.getRuntimePlatform() != null) {
            RuntimePlatform platform = td.getRuntimePlatform();
            ObjectNode platformNode = objectMapper.createObjectNode();
            if (platform.cpuArchitecture() != null) {
                platformNode.put("cpuArchitecture", platform.cpuArchitecture());
            }
            if (platform.operatingSystemFamily() != null) {
                platformNode.put("operatingSystemFamily", platform.operatingSystemFamily());
            }
            n.set("runtimePlatform", platformNode);
        }
        if (td.getRequiresCompatibilities() != null && !td.getRequiresCompatibilities().isEmpty()) {
            ArrayNode arr = objectMapper.createArrayNode();
            td.getRequiresCompatibilities().forEach(arr::add);
            n.set("requiresCompatibilities", arr);
        }
        if (td.getCompatibilities() != null && !td.getCompatibilities().isEmpty()) {
            ArrayNode arr = objectMapper.createArrayNode();
            td.getCompatibilities().forEach(arr::add);
            n.set("compatibilities", arr);
        }
        if (td.getRequiresAttributes() != null && !td.getRequiresAttributes().isEmpty()) {
            ArrayNode arr = objectMapper.createArrayNode();
            td.getRequiresAttributes().forEach(a -> arr.add(attributeNode(a)));
            n.set("requiresAttributes", arr);
        }

        ArrayNode containers = objectMapper.createArrayNode();
        if (td.getContainerDefinitions() != null) {
            for (ContainerDefinition def : td.getContainerDefinitions()) {
                containers.add(containerDefinitionNode(def));
            }
        }
        n.set("containerDefinitions", containers);
        if (td.getVolumes() != null && !td.getVolumes().isEmpty()) {
            n.set("volumes", volumesNode(td.getVolumes()));
        }
        putInstant(n, "registeredAt", td.getRegisteredAt());
        putInstant(n, "deregisteredAt", td.getDeregisteredAt());
        putInstant(n, "deleteRequestedAt", td.getDeleteRequestedAt());
        if (td.getRegisteredBy() != null) { n.put("registeredBy", td.getRegisteredBy()); }
        if (td.getTags() != null && !td.getTags().isEmpty()) {
            n.set("tags", tagsNode(td.getTags()));
        }
        EcsJsonPassthrough.write(n, td.getUnparsed(), objectMapper);
        return n;
    }

    private ArrayNode volumesNode(List<Volume> volumes) {
        ArrayNode vols = objectMapper.createArrayNode();
        for (Volume v : volumes) {
            ObjectNode vNode = objectMapper.createObjectNode();
            vNode.put("name", v.name());
            if (v.hostSourcePath() != null) {
                ObjectNode host = objectMapper.createObjectNode();
                host.put("sourcePath", v.hostSourcePath());
                vNode.set("host", host);
            }
            if (v.efs() != null) {
                EfsVolumeConfiguration e = v.efs();
                ObjectNode efs = objectMapper.createObjectNode();
                efs.put("fileSystemId", e.fileSystemId());
                if (e.rootDirectory() != null) {
                    efs.put("rootDirectory", e.rootDirectory());
                }
                if (e.transitEncryption() != null) {
                    efs.put("transitEncryption", e.transitEncryption());
                }
                if (e.transitEncryptionPort() != null) {
                    efs.put("transitEncryptionPort", e.transitEncryptionPort());
                }
                if (e.accessPointId() != null || e.iam() != null) {
                    ObjectNode auth = objectMapper.createObjectNode();
                    if (e.accessPointId() != null) {
                        auth.put("accessPointId", e.accessPointId());
                    }
                    if (e.iam() != null) {
                        auth.put("iam", e.iam());
                    }
                    efs.set("authorizationConfig", auth);
                }
                vNode.set("efsVolumeConfiguration", efs);
            }
            EcsJsonPassthrough.write(vNode, v.unparsed(), objectMapper);
            vols.add(vNode);
        }
        return vols;
    }

    public ObjectNode containerDefinitionNode(ContainerDefinition def) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("name", def.getName());
        n.put("image", def.getImage());
        n.put("essential", def.isEssential());
        if (def.getCpu() != null) { n.put("cpu", def.getCpu()); }
        if (def.getMemory() != null) { n.put("memory", def.getMemory()); }
        if (def.getMemoryReservation() != null) { n.put("memoryReservation", def.getMemoryReservation()); }

        if (def.getPortMappings() != null && !def.getPortMappings().isEmpty()) {
            ArrayNode pms = objectMapper.createArrayNode();
            for (PortMapping pm : def.getPortMappings()) {
                ObjectNode pmNode = objectMapper.createObjectNode();
                pmNode.put("containerPort", pm.containerPort());
                pmNode.put("hostPort", pm.hostPort());
                pmNode.put("protocol", pm.protocol());
                if (pm.name() != null) { pmNode.put("name", pm.name()); }
                if (pm.appProtocol() != null) { pmNode.put("appProtocol", pm.appProtocol()); }
                if (pm.containerPortRange() != null) {
                    pmNode.put("containerPortRange", pm.containerPortRange());
                }
                pms.add(pmNode);
            }
            n.set("portMappings", pms);
        }

        if (def.getEntryPoint() != null && !def.getEntryPoint().isEmpty()) {
            n.set("entryPoint", stringArray(def.getEntryPoint()));
        }
        if (def.getCommand() != null && !def.getCommand().isEmpty()) {
            n.set("command", stringArray(def.getCommand()));
        }
        if (def.getEnvironment() != null && !def.getEnvironment().isEmpty()) {
            n.set("environment", keyValuePairsNode(def.getEnvironment()));
        }
        if (def.getEnvironmentFiles() != null && !def.getEnvironmentFiles().isEmpty()) {
            n.set("environmentFiles", environmentFilesNode(def.getEnvironmentFiles()));
        }
        if (def.getSecrets() != null && !def.getSecrets().isEmpty()) {
            n.set("secrets", secretsNode(def.getSecrets()));
        }
        if (def.getMountPoints() != null && !def.getMountPoints().isEmpty()) {
            ArrayNode mps = objectMapper.createArrayNode();
            for (MountPoint mp : def.getMountPoints()) {
                ObjectNode mpNode = objectMapper.createObjectNode();
                mpNode.put("sourceVolume", mp.sourceVolume());
                mpNode.put("containerPath", mp.containerPath());
                mpNode.put("readOnly", mp.readOnly());
                mps.add(mpNode);
            }
            n.set("mountPoints", mps);
        }
        if (def.getVolumesFrom() != null && !def.getVolumesFrom().isEmpty()) {
            ArrayNode volumesFrom = objectMapper.createArrayNode();
            for (VolumeFrom volumeFrom : def.getVolumesFrom()) {
                ObjectNode volumeFromNode = objectMapper.createObjectNode();
                volumeFromNode.put("sourceContainer", volumeFrom.sourceContainer());
                volumeFromNode.put("readOnly", volumeFrom.readOnly());
                volumesFrom.add(volumeFromNode);
            }
            n.set("volumesFrom", volumesFrom);
        }
        if (def.getDependsOn() != null && !def.getDependsOn().isEmpty()) {
            ArrayNode dependsOn = objectMapper.createArrayNode();
            for (ContainerDependency dependency : def.getDependsOn()) {
                ObjectNode dependencyNode = objectMapper.createObjectNode();
                dependencyNode.put("containerName", dependency.containerName());
                dependencyNode.put("condition", dependency.condition());
                dependsOn.add(dependencyNode);
            }
            n.set("dependsOn", dependsOn);
        }

        if (def.getLogConfiguration() != null) {
            LogConfiguration logConfig = def.getLogConfiguration();
            ObjectNode logNode = objectMapper.createObjectNode();
            if (logConfig.logDriver() != null) {
                logNode.put("logDriver", logConfig.logDriver());
            }
            if (logConfig.options() != null) {
                ObjectNode options = objectMapper.createObjectNode();
                logConfig.options().forEach(options::put);
                logNode.set("options", options);
            }
            if (logConfig.secretOptions() != null) {
                logNode.set("secretOptions", secretsNode(logConfig.secretOptions()));
            }
            n.set("logConfiguration", logNode);
        }

        if (def.getFirelensConfiguration() != null) {
            FirelensConfiguration firelens = def.getFirelensConfiguration();
            ObjectNode firelensNode = objectMapper.createObjectNode();
            if (firelens.type() != null) {
                firelensNode.put("type", firelens.type());
            }
            if (firelens.options() != null) {
                ObjectNode options = objectMapper.createObjectNode();
                firelens.options().forEach(options::put);
                firelensNode.set("options", options);
            }
            n.set("firelensConfiguration", firelensNode);
        }
        if (def.getHealthCheck() != null) {
            HealthCheck hc = def.getHealthCheck();
            ObjectNode hcNode = objectMapper.createObjectNode();
            if (hc.command() != null) {
                hcNode.set("command", stringArray(hc.command()));
            }
            if (hc.interval() != null) { hcNode.put("interval", hc.interval()); }
            if (hc.timeout() != null) { hcNode.put("timeout", hc.timeout()); }
            if (hc.retries() != null) { hcNode.put("retries", hc.retries()); }
            if (hc.startPeriod() != null) { hcNode.put("startPeriod", hc.startPeriod()); }
            n.set("healthCheck", hcNode);
        }

        if (def.getStartTimeout() != null) { n.put("startTimeout", def.getStartTimeout()); }
        if (def.getStopTimeout() != null) { n.put("stopTimeout", def.getStopTimeout()); }
        if (def.getUser() != null) { n.put("user", def.getUser()); }
        if (def.getWorkingDirectory() != null) { n.put("workingDirectory", def.getWorkingDirectory()); }
        if (def.getHostname() != null) { n.put("hostname", def.getHostname()); }
        if (def.getReadonlyRootFilesystem() != null) {
            n.put("readonlyRootFilesystem", def.getReadonlyRootFilesystem());
        }
        if (def.getPrivileged() != null) { n.put("privileged", def.getPrivileged()); }
        if (def.getDisableNetworking() != null) { n.put("disableNetworking", def.getDisableNetworking()); }
        if (def.getInteractive() != null) { n.put("interactive", def.getInteractive()); }
        if (def.getPseudoTerminal() != null) { n.put("pseudoTerminal", def.getPseudoTerminal()); }
        if (def.getLinks() != null && !def.getLinks().isEmpty()) {
            n.set("links", stringArray(def.getLinks()));
        }
        if (def.getDnsServers() != null && !def.getDnsServers().isEmpty()) {
            n.set("dnsServers", stringArray(def.getDnsServers()));
        }
        if (def.getDnsSearchDomains() != null && !def.getDnsSearchDomains().isEmpty()) {
            n.set("dnsSearchDomains", stringArray(def.getDnsSearchDomains()));
        }
        if (def.getDockerSecurityOptions() != null && !def.getDockerSecurityOptions().isEmpty()) {
            n.set("dockerSecurityOptions", stringArray(def.getDockerSecurityOptions()));
        }
        if (def.getDockerLabels() != null && !def.getDockerLabels().isEmpty()) {
            ObjectNode labels = objectMapper.createObjectNode();
            def.getDockerLabels().forEach(labels::put);
            n.set("dockerLabels", labels);
        }
        if (def.getRepositoryCredentialsParameter() != null) {
            n.putObject("repositoryCredentials")
                    .put("credentialsParameter", def.getRepositoryCredentialsParameter());
        }
        EcsJsonPassthrough.write(n, def.getUnparsed(), objectMapper);
        return n;
    }

    // ── Tasks ─────────────────────────────────────────────────────────────────

    /**
     * Renders a task for a describe, where its tags are reported only when the caller asked for
     * them with {@code include: ["TAGS"]}.
     */
    public ObjectNode taskNode(EcsTask t, boolean includeTags) {
        ObjectNode n = taskNode(t);
        if (!includeTags) {
            n.remove("tags");
        }
        return n;
    }

    /** Renders an ECS task to its data-plane JSON shape. Reused by the Step Functions
     *  ecs:runTask integration ({@link io.github.hectorvent.floci.services.stepfunctions.AslExecutor}). */
    public ObjectNode taskNode(EcsTask t) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("taskArn", t.getTaskArn());
        n.put("clusterArn", t.getClusterArn());
        n.put("taskDefinitionArn", t.getTaskDefinitionArn());
        n.put("lastStatus", t.getLastStatus());
        n.put("desiredStatus", t.getDesiredStatus());
        // A task placed through a capacity provider reports both: the provider it was placed
        // through and the launch type that provider resolves to. (A service reports only one of
        // the two, which is why serviceNode does it differently.)
        if (t.getCapacityProviderName() != null) {
            n.put("capacityProviderName", t.getCapacityProviderName());
        }
        if (t.getLaunchType() != null) {
            n.put("launchType", t.getLaunchType().name());
        }
        if (t.getPlatformVersion() != null) { n.put("platformVersion", t.getPlatformVersion()); }
        if (t.getPlatformFamily() != null) { n.put("platformFamily", t.getPlatformFamily()); }
        if (t.getCpu() != null) { n.put("cpu", t.getCpu()); }
        if (t.getMemory() != null) { n.put("memory", t.getMemory()); }
        if (t.getEphemeralStorage() != null) {
            n.set("ephemeralStorage", ephemeralStorageNode(t.getEphemeralStorage()));
            // A Fargate task also reports the storage it actually got, which is the same size here.
            if (t.getPlatformVersion() != null) {
                n.set("fargateEphemeralStorage", ephemeralStorageNode(t.getEphemeralStorage()));
            }
        }
        if (t.getGroup() != null) { n.put("group", t.getGroup()); }
        if (t.getStartedBy() != null) { n.put("startedBy", t.getStartedBy()); }
        if (t.getContainerInstanceArn() != null) { n.put("containerInstanceArn", t.getContainerInstanceArn()); }
        if (t.getAvailabilityZone() != null) { n.put("availabilityZone", t.getAvailabilityZone()); }
        if (t.getConnectivity() != null) { n.put("connectivity", t.getConnectivity()); }
        putInstant(n, "connectivityAt", t.getConnectivityAt());
        if (t.getHealthStatus() != null) { n.put("healthStatus", t.getHealthStatus()); }
        if (t.getStopCode() != null) { n.put("stopCode", t.getStopCode()); }
        n.put("enableExecuteCommand", t.isEnableExecuteCommand());
        n.put("version", t.getVersion());
        putInstant(n, "createdAt", t.getCreatedAt());
        putInstant(n, "startedAt", t.getStartedAt());
        putInstant(n, "stoppingAt", t.getStoppingAt());
        putInstant(n, "stoppedAt", t.getStoppedAt());
        putInstant(n, "pullStartedAt", t.getPullStartedAt());
        putInstant(n, "pullStoppedAt", t.getPullStoppedAt());
        putInstant(n, "executionStoppedAt", t.getExecutionStoppedAt());
        if (t.getStoppedReason() != null) { n.put("stoppedReason", t.getStoppedReason()); }
        // A task always reports overrides, with one containerOverrides entry per container even
        // when the request overrode nothing, which is what DescribeTasks answers with.
        n.set("overrides", taskOverrideNode(t));
        if (t.getAttributes() != null && !t.getAttributes().isEmpty()) {
            ArrayNode attrs = objectMapper.createArrayNode();
            t.getAttributes().forEach(a -> attrs.add(attributeNode(a)));
            n.set("attributes", attrs);
        }
        if (t.getNetworkInterfaceId() != null) {
            n.putArray("attachments").add(eniAttachmentNode(t));
        }

        ArrayNode containers = objectMapper.createArrayNode();
        if (t.getContainers() != null) {
            for (Container c : t.getContainers()) {
                containers.add(containerNode(c));
            }
        }
        n.set("containers", containers);
        if (t.getTags() != null && !t.getTags().isEmpty()) {
            n.set("tags", tagsNode(t.getTags()));
        }
        return n;
    }

    private ObjectNode eniAttachmentNode(EcsTask t) {
        ObjectNode attachment = objectMapper.createObjectNode();
        attachment.put("id", t.getAttachmentId() != null
                ? t.getAttachmentId() : "eni-attach-" + t.getNetworkInterfaceId());
        attachment.put("type", "ElasticNetworkInterface");
        attachment.put("status", t.getAttachmentStatus() != null ? t.getAttachmentStatus() : "ATTACHED");
        ArrayNode details = objectMapper.createArrayNode();
        if (t.getNetworkConfiguration() != null
                && t.getNetworkConfiguration().getAwsvpcConfiguration() != null
                && !t.getNetworkConfiguration().getAwsvpcConfiguration().getSubnets().isEmpty()) {
            details.addObject().put("name", "subnetId").put("value",
                    t.getNetworkConfiguration().getAwsvpcConfiguration().getSubnets().getFirst());
        }
        details.addObject().put("name", "networkInterfaceId").put("value", t.getNetworkInterfaceId());
        if (t.getMacAddress() != null) {
            details.addObject().put("name", "macAddress").put("value", t.getMacAddress());
        }
        if (t.getPrivateDnsName() != null) {
            details.addObject().put("name", "privateDnsName").put("value", t.getPrivateDnsName());
        }
        details.addObject().put("name", "privateIPv4Address").put("value", t.getPrivateIpAddress());
        attachment.set("details", details);
        return attachment;
    }

    private ObjectNode containerNode(Container c) {
        ObjectNode cn = objectMapper.createObjectNode();
        cn.put("containerArn", c.getContainerArn());
        cn.put("taskArn", c.getTaskArn());
        cn.put("name", c.getName());
        cn.put("image", c.getImage());
        cn.put("lastStatus", c.getLastStatus());
        if (c.getExitCode() != null) { cn.put("exitCode", c.getExitCode()); }
        if (c.getReason() != null) { cn.put("reason", c.getReason()); }
        if (c.getHealthStatus() != null) { cn.put("healthStatus", c.getHealthStatus()); }
        if (c.getRuntimeId() != null) { cn.put("runtimeId", c.getRuntimeId()); }
        if (c.getImageDigest() != null) { cn.put("imageDigest", c.getImageDigest()); }
        // A container definition that asked for no CPU units reports zero, not nothing.
        cn.put("cpu", c.getCpu() != null ? c.getCpu() : "0");
        if (c.getMemory() != null) { cn.put("memory", c.getMemory()); }
        if (c.getMemoryReservation() != null) { cn.put("memoryReservation", c.getMemoryReservation()); }

        ArrayNode bindings = objectMapper.createArrayNode();
        if (c.getNetworkBindings() != null) {
            for (NetworkBinding nb : c.getNetworkBindings()) {
                ObjectNode bn = objectMapper.createObjectNode();
                bn.put("bindIP", nb.bindIP());
                bn.put("containerPort", nb.containerPort());
                bn.put("hostPort", nb.hostPort());
                bn.put("protocol", nb.protocol());
                bindings.add(bn);
            }
        }
        cn.set("networkBindings", bindings);
        if (c.getNetworkInterfaces() != null && !c.getNetworkInterfaces().isEmpty()) {
            ArrayNode interfaces = objectMapper.createArrayNode();
            for (TaskNetworkInterface ni : c.getNetworkInterfaces()) {
                ObjectNode in = objectMapper.createObjectNode();
                if (ni.attachmentId() != null) { in.put("attachmentId", ni.attachmentId()); }
                if (ni.privateIpv4Address() != null) { in.put("privateIpv4Address", ni.privateIpv4Address()); }
                if (ni.ipv6Address() != null) { in.put("ipv6Address", ni.ipv6Address()); }
                interfaces.add(in);
            }
            cn.set("networkInterfaces", interfaces);
        }
        if (c.getManagedAgents() != null && !c.getManagedAgents().isEmpty()) {
            ArrayNode agents = objectMapper.createArrayNode();
            for (ManagedAgent agent : c.getManagedAgents()) {
                ObjectNode an = objectMapper.createObjectNode();
                an.put("name", agent.name());
                an.put("lastStatus", agent.lastStatus());
                if (agent.reason() != null) { an.put("reason", agent.reason()); }
                putInstant(an, "lastStartedAt", agent.lastStartedAt());
                agents.add(an);
            }
            cn.set("managedAgents", agents);
        }
        return cn;
    }

    /**
     * The task's overrides. Every container is listed, carrying whatever the request overrode for
     * it and nothing more, so a client that walks {@code containerOverrides} sees the same entries
     * AWS returns rather than an empty list for a task nobody overrode.
     */
    private ObjectNode taskOverrideNode(EcsTask task) {
        TaskOverride overrides = task.getOverrides() != null ? task.getOverrides() : new TaskOverride();
        Map<String, ContainerOverride> byName = new LinkedHashMap<>();
        if (task.getContainers() != null) {
            task.getContainers().forEach(container -> {
                ContainerOverride placeholder = new ContainerOverride();
                placeholder.setName(container.getName());
                byName.put(container.getName(), placeholder);
            });
        }
        if (overrides.getContainerOverrides() != null) {
            overrides.getContainerOverrides().forEach(override -> byName.put(override.getName(), override));
        }
        return taskOverrideNode(overrides, List.copyOf(byName.values()));
    }

    private ObjectNode taskOverrideNode(TaskOverride overrides, List<ContainerOverride> perContainer) {
        ObjectNode n = objectMapper.createObjectNode();
        ArrayNode containerOverrides = objectMapper.createArrayNode();
        for (ContainerOverride co : perContainer) {
            ObjectNode con = objectMapper.createObjectNode();
            con.put("name", co.getName());
            if (co.getCommand() != null && !co.getCommand().isEmpty()) {
                con.set("command", stringArray(co.getCommand()));
            }
            if (co.getEnvironment() != null && !co.getEnvironment().isEmpty()) {
                con.set("environment", keyValuePairsNode(co.getEnvironment()));
            }
            if (co.getEnvironmentFiles() != null && !co.getEnvironmentFiles().isEmpty()) {
                con.set("environmentFiles", environmentFilesNode(co.getEnvironmentFiles()));
            }
            if (co.getCpu() != null) { con.put("cpu", co.getCpu()); }
            if (co.getMemory() != null) { con.put("memory", co.getMemory()); }
            if (co.getMemoryReservation() != null) {
                con.put("memoryReservation", co.getMemoryReservation());
            }
            containerOverrides.add(con);
        }
        n.set("containerOverrides", containerOverrides);
        if (overrides.getCpu() != null) { n.put("cpu", overrides.getCpu()); }
        if (overrides.getMemory() != null) { n.put("memory", overrides.getMemory()); }
        if (overrides.getTaskRoleArn() != null) { n.put("taskRoleArn", overrides.getTaskRoleArn()); }
        if (overrides.getExecutionRoleArn() != null) {
            n.put("executionRoleArn", overrides.getExecutionRoleArn());
        }
        if (overrides.getEphemeralStorage() != null) {
            n.set("ephemeralStorage", ephemeralStorageNode(overrides.getEphemeralStorage()));
        }
        return n;
    }

    // ── Services ──────────────────────────────────────────────────────────────

    /** Renders a service for a describe, gating its tags on {@code include: ["TAGS"]}. */
    public ObjectNode serviceNode(EcsServiceModel s, boolean includeTags) {
        ObjectNode n = serviceNode(s);
        if (!includeTags) {
            n.remove("tags");
        }
        return n;
    }

    public ObjectNode serviceNode(EcsServiceModel s) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("serviceArn", s.getServiceArn());
        n.put("serviceName", s.getServiceName());
        n.put("clusterArn", s.getClusterArn());
        n.put("taskDefinition", s.getTaskDefinition());
        n.put("desiredCount", s.getDesiredCount());
        n.put("runningCount", s.getRunningCount());
        n.put("pendingCount", s.getPendingCount());
        n.put("status", s.getStatus());
        if (s.getCapacityProviderStrategy() != null && !s.getCapacityProviderStrategy().isEmpty()) {
            n.set("capacityProviderStrategy",
                    capacityProviderStrategyNode(s.getCapacityProviderStrategy()));
        } else if (s.getLaunchType() != null) {
            n.put("launchType", s.getLaunchType().name());
        }
        if (s.getPlatformVersion() != null) { n.put("platformVersion", s.getPlatformVersion()); }
        if (s.getPlatformFamily() != null) { n.put("platformFamily", s.getPlatformFamily()); }
        putInstant(n, "createdAt", s.getCreatedAt());
        if (s.getNamespace() != null) { n.put("namespace", s.getNamespace()); }
        if (s.getRoleArn() != null) { n.put("roleArn", s.getRoleArn()); }
        // Services persisted before these fields existed read back with the AWS defaults.
        n.put("schedulingStrategy", s.getSchedulingStrategy() != null
                ? s.getSchedulingStrategy() : EcsService.DEFAULT_SCHEDULING_STRATEGY);
        n.putObject("deploymentController").put("type", s.getDeploymentController() != null
                ? s.getDeploymentController() : EcsService.DEFAULT_DEPLOYMENT_CONTROLLER);
        n.put("availabilityZoneRebalancing", s.getAvailabilityZoneRebalancing() != null
                ? s.getAvailabilityZoneRebalancing() : EcsService.DEFAULT_AZ_REBALANCING_UNSET);
        n.put("enableExecuteCommand", s.isEnableExecuteCommand());
        n.put("enableECSManagedTags", s.isEnableECSManagedTags());
        if (s.getPropagateTags() != null) { n.put("propagateTags", s.getPropagateTags()); }
        if (s.getHealthCheckGracePeriodSeconds() != null) {
            n.put("healthCheckGracePeriodSeconds", s.getHealthCheckGracePeriodSeconds());
        }
        if (s.getDeploymentConfiguration() != null) {
            n.set("deploymentConfiguration", objectMapper.valueToTree(s.getDeploymentConfiguration()));
        }
        if (s.getServiceRegistries() != null && !s.getServiceRegistries().isEmpty()) {
            n.set("serviceRegistries", objectMapper.valueToTree(s.getServiceRegistries()));
        }
        if (s.getTags() != null && !s.getTags().isEmpty()) {
            n.set("tags", tagsNode(s.getTags()));
        }
        if (s.getLoadBalancers() != null && !s.getLoadBalancers().isEmpty()) {
            ArrayNode lbs = objectMapper.createArrayNode();
            for (EcsLoadBalancer lb : s.getLoadBalancers()) {
                lbs.add(loadBalancerNode(lb));
            }
            n.set("loadBalancers", lbs);
        }
        if (s.getNetworkConfiguration() != null
                && s.getNetworkConfiguration().getAwsvpcConfiguration() != null) {
            n.set("networkConfiguration",
                    networkConfigurationNode(s.getNetworkConfiguration().getAwsvpcConfiguration()));
        }
        ArrayNode deployments = objectMapper.createArrayNode();
        service.deploymentsFor(s).forEach(d -> deployments.add(deploymentNode(d)));
        n.set("deployments", deployments);
        // Both are always reported, empty and all: a service under the ECS controller has no task
        // sets, and one that has not converged yet has no events, and a client has to be able to
        // tell that from a member the emulator simply never writes.
        ArrayNode taskSets = objectMapper.createArrayNode();
        service.taskSetsFor(s).forEach(ts -> taskSets.add(taskSetNode(ts)));
        n.set("taskSets", taskSets);
        ArrayNode events = objectMapper.createArrayNode();
        service.eventsFor(s).forEach(e -> events.add(serviceEventNode(e)));
        n.set("events", events);
        // The service points at the deployment and revision it is currently on, which is how a
        // caller gets from DescribeServices to DescribeServiceDeployments without listing first.
        ServiceDeployment current = service.currentServiceDeployment(s);
        if (current != null) {
            n.put("currentServiceDeployment", current.getServiceDeploymentArn());
            if (current.getTargetServiceRevisionArn() != null) {
                ObjectNode revision = objectMapper.createObjectNode();
                revision.put("arn", current.getTargetServiceRevisionArn());
                revision.put("requestedTaskCount", s.getDesiredCount());
                revision.put("runningTaskCount", s.getRunningCount());
                revision.put("pendingTaskCount", s.getPendingCount());
                n.set("currentServiceRevisions", objectMapper.createArrayNode().add(revision));
            }
        }
        EcsJsonPassthrough.write(n, s.getUnparsed(), objectMapper);
        return n;
    }

    private ObjectNode serviceEventNode(ServiceEvent event) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("id", event.id());
        putInstant(n, "createdAt", event.createdAt());
        n.put("message", event.message());
        return n;
    }

    private ObjectNode loadBalancerNode(EcsLoadBalancer lb) {
        ObjectNode n = objectMapper.createObjectNode();
        if (lb.getTargetGroupArn() != null) { n.put("targetGroupArn", lb.getTargetGroupArn()); }
        if (lb.getLoadBalancerName() != null) { n.put("loadBalancerName", lb.getLoadBalancerName()); }
        if (lb.getContainerName() != null) { n.put("containerName", lb.getContainerName()); }
        if (lb.getContainerPort() != null) { n.put("containerPort", lb.getContainerPort()); }
        if (lb.getAdvancedConfiguration() != null) {
            n.set("advancedConfiguration", objectMapper.valueToTree(lb.getAdvancedConfiguration()));
        }
        return n;
    }

    private ObjectNode networkConfigurationNode(AwsVpcConfiguration awsvpc) {
        ObjectNode awsvpcNode = objectMapper.createObjectNode();
        awsvpcNode.set("subnets", stringArray(awsvpc.getSubnets()));
        awsvpcNode.set("securityGroups", stringArray(awsvpc.getSecurityGroups()));
        if (awsvpc.getAssignPublicIp() != null) {
            awsvpcNode.put("assignPublicIp", awsvpc.getAssignPublicIp());
        }
        ObjectNode networkConfig = objectMapper.createObjectNode();
        networkConfig.set("awsvpcConfiguration", awsvpcNode);
        return networkConfig;
    }

    public ObjectNode deploymentNode(Deployment d) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("id", d.getId());
        n.put("status", d.getStatus());
        n.put("taskDefinition", d.getTaskDefinition());
        n.put("desiredCount", d.getDesiredCount());
        n.put("pendingCount", d.getPendingCount());
        n.put("runningCount", d.getRunningCount());
        n.put("failedTasks", d.getFailedTasks());
        n.put("rolloutState", d.getRolloutState());
        n.put("rolloutStateReason", d.getRolloutStateReason());
        if (d.getCapacityProviderStrategy() != null && !d.getCapacityProviderStrategy().isEmpty()) {
            n.set("capacityProviderStrategy",
                    capacityProviderStrategyNode(d.getCapacityProviderStrategy()));
        } else if (d.getLaunchType() != null) {
            n.put("launchType", d.getLaunchType().name());
        }
        if (d.getPlatformVersion() != null) { n.put("platformVersion", d.getPlatformVersion()); }
        if (d.getPlatformFamily() != null) { n.put("platformFamily", d.getPlatformFamily()); }
        if (d.getNetworkConfiguration() != null
                && d.getNetworkConfiguration().getAwsvpcConfiguration() != null) {
            n.set("networkConfiguration",
                    networkConfigurationNode(d.getNetworkConfiguration().getAwsvpcConfiguration()));
        }
        putInstant(n, "createdAt", d.getCreatedAt());
        putInstant(n, "updatedAt", d.getUpdatedAt());
        if (d.getServiceConnectConfiguration() != null) {
            n.set("serviceConnectConfiguration", objectMapper.valueToTree(d.getServiceConnectConfiguration()));
        }
        return n;
    }

    // ── Everything else ───────────────────────────────────────────────────────

    public ObjectNode failureNode(Failure f) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("arn", f.arn());
        n.put("reason", f.reason());
        if (f.detail() != null) { n.put("detail", f.detail()); }
        return n;
    }

    public ObjectNode containerInstanceNode(ContainerInstance ci) {
        return containerInstanceNode(ci, true, true);
    }

    /**
     * @param includeTags DescribeContainerInstances returns tags only for {@code include: ["TAGS"]}
     * @param includeHealth and the health status only for
     *                      {@code include: ["CONTAINER_INSTANCE_HEALTH"]}: "If this field is
     *                      omitted, tags and container instance health status aren't included in
     *                      the response."
     */
    public ObjectNode containerInstanceNode(ContainerInstance ci, boolean includeTags,
                                             boolean includeHealth) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("containerInstanceArn", ci.getContainerInstanceArn());
        n.put("ec2InstanceId", ci.getEc2InstanceId());
        n.put("status", ci.getStatus());
        if (ci.getStatusReason() != null) { n.put("statusReason", ci.getStatusReason()); }
        n.put("runningTasksCount", ci.getRunningTasksCount());
        n.put("pendingTasksCount", ci.getPendingTasksCount());
        n.put("agentConnected", ci.isAgentConnected());
        if (ci.getAgentUpdateStatus() != null) { n.put("agentUpdateStatus", ci.getAgentUpdateStatus()); }
        if (ci.getCapacityProviderName() != null) {
            n.put("capacityProviderName", ci.getCapacityProviderName());
        }
        n.put("version", ci.getVersion());
        putInstant(n, "registeredAt", ci.getRegisteredAt());
        if (ci.getVersionInfo() != null) {
            n.set("versionInfo", objectMapper.valueToTree(ci.getVersionInfo()));
        }
        if (ci.getRegisteredResources() != null) {
            n.set("registeredResources", objectMapper.valueToTree(ci.getRegisteredResources()));
        }
        if (ci.getRemainingResources() != null) {
            n.set("remainingResources", objectMapper.valueToTree(ci.getRemainingResources()));
        }
        if (ci.getAttributes() != null && !ci.getAttributes().isEmpty()) {
            ArrayNode attrs = objectMapper.createArrayNode();
            ci.getAttributes().forEach(a -> attrs.add(attributeNode(a)));
            n.set("attributes", attrs);
        }
        if (includeHealth) {
            n.set("healthStatus", containerInstanceHealthNode(ci));
        }
        if (includeTags && ci.getTags() != null && !ci.getTags().isEmpty()) {
            n.set("tags", tagsNode(ci.getTags()));
        }
        return n;
    }

    /**
     * The instance's health. Floci runs no agent health checks of its own, so the one check it can
     * answer honestly is {@code AGENT_CONNECTIVITY}, taken from the registration state the
     * instance already tracks, and the overall status follows it.
     */
    private ObjectNode containerInstanceHealthNode(ContainerInstance ci) {
        String status = ci.isAgentConnected() ? "OK" : "IMPAIRED";
        ObjectNode health = objectMapper.createObjectNode();
        health.put("overallStatus", status);
        ObjectNode connectivity = objectMapper.createObjectNode();
        connectivity.put("type", "AGENT_CONNECTIVITY");
        connectivity.put("status", status);
        putInstant(connectivity, "lastUpdated", ci.getRegisteredAt());
        putInstant(connectivity, "lastStatusChange", ci.getRegisteredAt());
        health.set("details", objectMapper.createArrayNode().add(connectivity));
        return health;
    }

    /** @param includeTags DescribeCapacityProviders returns tags only for {@code include: ["TAGS"]}. */
    public ObjectNode capacityProviderNode(CapacityProvider cp, boolean includeTags) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("name", cp.getName());
        n.put("status", cp.getStatus());
        if (cp.getCapacityProviderArn() != null) { n.put("capacityProviderArn", cp.getCapacityProviderArn()); }
        if (cp.getUpdateStatus() != null) { n.put("updateStatus", cp.getUpdateStatus()); }
        if (cp.getType() != null) { n.put("type", cp.getType()); }
        // The provider's own configuration, which a client that created it reads back to confirm
        // what it registered. Dropping it reads as drift in Terraform's aws_ecs_capacity_provider.
        if (cp.getAutoScalingGroupProvider() != null) {
            n.set("autoScalingGroupProvider", objectMapper.valueToTree(cp.getAutoScalingGroupProvider()));
        }
        if (includeTags && cp.getTags() != null && !cp.getTags().isEmpty()) {
            n.set("tags", tagsNode(cp.getTags()));
        }
        return n;
    }

    public ObjectNode capacityProviderNode(CapacityProvider cp) {
        return capacityProviderNode(cp, true);
    }

    public ObjectNode taskSetNode(TaskSet ts) {
        return taskSetNode(ts, true);
    }

    /** @param includeTags DescribeTaskSets returns tags only for {@code include: ["TAGS"]}. */
    public ObjectNode taskSetNode(TaskSet ts, boolean includeTags) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("id", ts.getId());
        n.put("taskSetArn", ts.getTaskSetArn());
        n.put("serviceArn", ts.getServiceArn());
        n.put("clusterArn", ts.getClusterArn());
        n.put("taskDefinition", ts.getTaskDefinition());
        n.put("status", ts.getStatus());
        n.put("computedDesiredCount", ts.getComputedDesiredCount());
        n.put("pendingCount", ts.getPendingCount());
        n.put("runningCount", ts.getRunningCount());
        n.put("stabilityStatus", ts.getStabilityStatus());
        putInstant(n, "stabilityStatusAt", ts.getStabilityStatusAt());
        if (ts.getLaunchType() != null) { n.put("launchType", ts.getLaunchType().name()); }
        if (ts.getCapacityProviderStrategy() != null && !ts.getCapacityProviderStrategy().isEmpty()) {
            n.set("capacityProviderStrategy",
                    capacityProviderStrategyNode(ts.getCapacityProviderStrategy()));
        }
        if (ts.getPlatformVersion() != null) { n.put("platformVersion", ts.getPlatformVersion()); }
        if (ts.getPlatformFamily() != null) { n.put("platformFamily", ts.getPlatformFamily()); }
        if (ts.getExternalId() != null) { n.put("externalId", ts.getExternalId()); }
        if (ts.getStartedBy() != null) { n.put("startedBy", ts.getStartedBy()); }
        if (ts.getNetworkConfiguration() != null
                && ts.getNetworkConfiguration().getAwsvpcConfiguration() != null) {
            n.set("networkConfiguration",
                    networkConfigurationNode(ts.getNetworkConfiguration().getAwsvpcConfiguration()));
        }
        if (ts.getLoadBalancers() != null && !ts.getLoadBalancers().isEmpty()) {
            ArrayNode lbs = objectMapper.createArrayNode();
            ts.getLoadBalancers().forEach(lb -> lbs.add(loadBalancerNode(lb)));
            n.set("loadBalancers", lbs);
        }
        if (ts.getServiceRegistries() != null && !ts.getServiceRegistries().isEmpty()) {
            n.set("serviceRegistries", objectMapper.valueToTree(ts.getServiceRegistries()));
        }
        ObjectNode scale = objectMapper.createObjectNode();
        scale.put("value", ts.getScaleValue());
        scale.put("unit", ts.getScaleUnit());
        n.set("scale", scale);
        putInstant(n, "createdAt", ts.getCreatedAt());
        putInstant(n, "updatedAt", ts.getUpdatedAt());
        if (includeTags && ts.getTags() != null && !ts.getTags().isEmpty()) {
            n.set("tags", tagsNode(ts.getTags()));
        }
        return n;
    }

    /**
     * A service deployment. AWS's {@code ServiceDeployment} shape carries no
     * {@code taskDefinition}: the deployment points at the revision it targets, and the revision
     * names the task definition, so that is the only route reported here.
     */
    public ObjectNode serviceDeploymentNode(ServiceDeployment d) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("serviceDeploymentArn", d.getServiceDeploymentArn());
        n.put("serviceArn", d.getServiceArn());
        n.put("clusterArn", d.getClusterArn());
        n.put("status", d.getStatus());
        putInstant(n, "createdAt", d.getCreatedAt());
        putInstant(n, "startedAt", d.getStartedAt());
        putInstant(n, "finishedAt", d.getFinishedAt());
        putInstant(n, "updatedAt", d.getUpdatedAt());
        if (d.getTargetServiceRevisionArn() != null) {
            n.set("targetServiceRevision",
                    serviceRevisionSummaryNode(d.getTargetServiceRevisionArn(), d.getServiceArn()));
        }
        ArrayNode sources = objectMapper.createArrayNode();
        if (d.getSourceServiceRevisionArns() != null) {
            d.getSourceServiceRevisionArns()
                    .forEach(arn -> sources.add(serviceRevisionSummaryNode(arn, d.getServiceArn())));
        }
        n.set("sourceServiceRevisions", sources);
        return n;
    }

    /** The counts a revision summary reports track the service the revision belongs to. */
    private ObjectNode serviceRevisionSummaryNode(String revisionArn, String serviceArn) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("arn", revisionArn);
        EcsServiceModel svc = service.serviceByArn(serviceArn);
        if (svc != null) {
            n.put("requestedTaskCount", svc.getDesiredCount());
            n.put("runningTaskCount", svc.getRunningCount());
            n.put("pendingTaskCount", svc.getPendingCount());
        }
        return n;
    }

    public ObjectNode serviceRevisionNode(ServiceRevision r) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("serviceRevisionArn", r.getServiceRevisionArn());
        n.put("serviceArn", r.getServiceArn());
        n.put("clusterArn", r.getClusterArn());
        n.put("taskDefinition", r.getTaskDefinition());
        if (r.getCapacityProviderStrategy() != null && !r.getCapacityProviderStrategy().isEmpty()) {
            n.set("capacityProviderStrategy",
                    capacityProviderStrategyNode(r.getCapacityProviderStrategy()));
        } else if (r.getLaunchType() != null) {
            n.put("launchType", r.getLaunchType().name());
        }
        if (r.getPlatformVersion() != null) { n.put("platformVersion", r.getPlatformVersion()); }
        if (r.getPlatformFamily() != null) { n.put("platformFamily", r.getPlatformFamily()); }
        if (r.getLoadBalancers() != null && !r.getLoadBalancers().isEmpty()) {
            ArrayNode lbs = objectMapper.createArrayNode();
            r.getLoadBalancers().forEach(lb -> lbs.add(loadBalancerNode(lb)));
            n.set("loadBalancers", lbs);
        }
        if (r.getServiceRegistries() != null && !r.getServiceRegistries().isEmpty()) {
            n.set("serviceRegistries", objectMapper.valueToTree(r.getServiceRegistries()));
        }
        if (r.getNetworkConfiguration() != null
                && r.getNetworkConfiguration().getAwsvpcConfiguration() != null) {
            n.set("networkConfiguration",
                    networkConfigurationNode(r.getNetworkConfiguration().getAwsvpcConfiguration()));
        }
        if (r.getServiceConnectConfiguration() != null) {
            n.set("serviceConnectConfiguration",
                    objectMapper.valueToTree(r.getServiceConnectConfiguration()));
        }
        if (r.getContainerImages() != null && !r.getContainerImages().isEmpty()) {
            ArrayNode images = objectMapper.createArrayNode();
            for (ContainerImage image : r.getContainerImages()) {
                ObjectNode imageNode = objectMapper.createObjectNode();
                imageNode.put("containerName", image.containerName());
                imageNode.put("image", image.image());
                if (image.imageDigest() != null) { imageNode.put("imageDigest", image.imageDigest()); }
                images.add(imageNode);
            }
            n.set("containerImages", images);
        }
        // Floci runs no GuardDuty runtime monitoring, which is what this reports.
        n.put("guardDutyEnabled", false);
        putInstant(n, "createdAt", r.getCreatedAt());
        return n;
    }

    public ObjectNode protectedTaskNode(ProtectedTask pt) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("taskArn", pt.taskArn());
        n.put("protectionEnabled", pt.protectionEnabled());
        putInstant(n, "expirationDate", pt.expirationDate());
        return n;
    }

    public ObjectNode attributeNode(Attribute a) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("name", a.name());
        if (a.value() != null) { n.put("value", a.value()); }
        if (a.targetType() != null) { n.put("targetType", a.targetType()); }
        if (a.targetId() != null) { n.put("targetId", a.targetId()); }
        return n;
    }

    public ObjectNode settingNode(EcsService.AccountSetting setting) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("name", setting.name());
        n.put("value", setting.value());
        if (setting.principalArn() != null) { n.put("principalArn", setting.principalArn()); }
        if (setting.type() != null) { n.put("type", setting.type()); }
        return n;
    }

    public ArrayNode tagsNode(Map<String, String> tags) {
        ArrayNode arr = objectMapper.createArrayNode();
        tags.forEach((k, v) -> {
            ObjectNode tag = objectMapper.createObjectNode();
            tag.put("key", k);
            tag.put("value", v);
            arr.add(tag);
        });
        return arr;
    }

    public ArrayNode capacityProviderStrategyNode(List<CapacityProviderStrategyItem> strategy) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (CapacityProviderStrategyItem item : strategy) {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("capacityProvider", item.capacityProvider());
            n.put("weight", item.weight());
            n.put("base", item.base());
            arr.add(n);
        }
        return arr;
    }

    private ObjectNode ephemeralStorageNode(EphemeralStorage storage) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("sizeInGiB", storage.sizeInGiB());
        return n;
    }

    private ArrayNode keyValuePairsNode(List<KeyValuePair> pairs) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (KeyValuePair kv : pairs) {
            ObjectNode kvNode = objectMapper.createObjectNode();
            kvNode.put("name", kv.name());
            kvNode.put("value", kv.value());
            arr.add(kvNode);
        }
        return arr;
    }

    private ArrayNode environmentFilesNode(List<EnvironmentFile> files) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (EnvironmentFile file : files) {
            ObjectNode fileNode = objectMapper.createObjectNode();
            fileNode.put("value", file.value());
            fileNode.put("type", file.type());
            arr.add(fileNode);
        }
        return arr;
    }

    private ArrayNode secretsNode(List<Secret> secrets) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (Secret secret : secrets) {
            ObjectNode secretNode = objectMapper.createObjectNode();
            secretNode.put("name", secret.name());
            secretNode.put("valueFrom", secret.valueFrom());
            arr.add(secretNode);
        }
        return arr;
    }

    private ArrayNode stringArray(List<String> values) {
        ArrayNode arr = objectMapper.createArrayNode();
        if (values != null) {
            values.forEach(arr::add);
        }
        return arr;
    }

    /** ECS timestamps go on the wire as epoch seconds with a fractional part. */
    private static void putInstant(ObjectNode target, String field, Instant value) {
        if (value != null) {
            target.put(field, value.toEpochMilli() / 1000.0);
        }
    }
}
