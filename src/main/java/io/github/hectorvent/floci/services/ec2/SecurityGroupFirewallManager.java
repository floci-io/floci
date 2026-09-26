package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Info;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.LocallyBuiltHelperImage;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Owns the protected namespace and the Floci-only nftables table for each managed ENI. */
@ApplicationScoped
public class SecurityGroupFirewallManager {

    private static final Logger LOG = Logger.getLogger(SecurityGroupFirewallManager.class);
    private final DockerClient dockerClient;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;
    private final Map<String, ProtectedEndpoint> endpoints = new HashMap<>();

    @Inject
    public SecurityGroupFirewallManager(DockerClient dockerClient, ContainerBuilder containerBuilder,
                                        ContainerLifecycleManager lifecycleManager, EmulatorConfig config) {
        this.dockerClient = dockerClient;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
    }

    public boolean enabled() {
        return config.network().securityGroupEnforcement().enabled();
    }

    @PostConstruct
    void quarantineSurvivingNamespaces() {
        if (!enabled()) {
            return;
        }
        try {
            String namespace = config.docker().resourceNamespace().orElse("");
            String owner = namespace.isBlank() ? String.valueOf(config.port()) : namespace + "/" + config.port();
            dockerClient.listContainersCmd().withShowAll(true)
                    .withLabelFilter(Map.of("floci.security-group-helper", "true"))
                    .exec().stream()
                    .filter(container -> owner.equals(container.getLabels().get("floci_owner_port")))
                    .forEach(container -> {
                        if ("running".equals(container.getState())) {
                            quarantine(container.getId());
                        } else {
                            stopNamespaceWorkloads(container.getId());
                        }
                        if ("ecs".equals(container.getLabels().get("io.floci.service"))) {
                            removeNamespaceWorkloads(container.getId());
                            lifecycleManager.removeIfExists(container.getId());
                        }
                    });
        } catch (Exception e) {
            LOG.warnv("Could not inspect surviving security-group namespaces: {0}", e.getMessage());
        }
    }

    /** The helper starts before any workload process and owns all published ports. */
    public Namespace createNamespace(String service, String resourceId, String accountId, String region,
                                     Optional<String> dockerNetwork, Map<Integer, Integer> portBindings) {
        if (!enabled()) {
            throw new IllegalStateException("Security-group enforcement is disabled");
        }
        Info daemon = dockerClient.infoCmd().exec();
        if (!"linux".equalsIgnoreCase(daemon.getOsType())) {
            throw new IllegalStateException("Security-group enforcement requires a Linux Docker daemon");
        }
        if (daemon.getSecurityOptions() != null && daemon.getSecurityOptions().stream()
                .anyMatch(option -> option.toLowerCase(Locale.ROOT).contains("rootless"))) {
            throw new IllegalStateException("Security-group enforcement requires rootful Docker");
        }
        ensureHelperImage();
        String name = ContainerStorageHelper.resourceName(config, "sg", null,
                resourceId.replaceAll("[^a-zA-Z0-9_.-]", "-"));
        String namespace = config.docker().resourceNamespace().orElse("");
        String owner = namespace.isBlank() ? String.valueOf(config.port()) : namespace + "/" + config.port();
        ContainerBuilder.Builder builder = containerBuilder.newContainer(config.network().securityGroupEnforcement().helperImage())
                .withName(name)
                .withDockerNetwork(dockerNetwork)
                .withEntrypoint(List.of("sh", "-c"))
                .withCmd(List.of("exec sleep 2147483647"))
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(service, resourceId, accountId, region))
                .withLabels(Map.of("floci.security-group-helper", "true", "floci_owner_port", owner));
        if (portBindings != null) {
            portBindings.forEach(builder::withPortBinding);
        }
        ContainerSpec spec = builder.build();
        String helperId = null;
        try {
            helperId = lifecycleManager.createAndStart(spec).containerId();
            String ip = dockerClient.inspectContainerCmd(helperId).exec().getNetworkSettings()
                    .getNetworks().values().stream().map(n -> n.getIpAddress())
                    .filter(address -> address != null && !address.isBlank())
                    .findFirst().orElseThrow(() -> new IllegalStateException("Firewall helper has no Docker IP"));
            apply(helperId, SecurityGroupNftCompiler.initialRuleset());
            return new Namespace(helperId, ip);
        } catch (Exception e) {
            if (helperId != null) {
                lifecycleManager.removeIfExists(helperId);
            }
            throw new IllegalStateException("Cannot prepare protected network namespace for " + resourceId, e);
        }
    }

    private void ensureHelperImage() {
        LocallyBuiltHelperImage.ensureBuilt(dockerClient, containerBuilder,
                config.network().securityGroupEnforcement().helperImage(), "floci/network-helper:local",
                "/docker/network-helper.Dockerfile");
    }

    /** Registers only after the default-deny table exists, before workload startup. */
    public synchronized void register(SecurityGroupNftCompiler.Endpoint endpoint, String helperId,
                                      Map<String, List<String>> prefixLists) {
        if (endpoints.values().stream().map(ProtectedEndpoint::endpoint)
                .anyMatch(existing -> !existing.eniId().equals(endpoint.eniId())
                        && existing.transportAddress().equals(endpoint.transportAddress()))) {
            quarantine(helperId);
            throw new IllegalStateException("Two managed ENIs share a Docker transport address");
        }
        ProtectedEndpoint previous = endpoints.put(endpoint.eniId(),
                new ProtectedEndpoint(endpoint, helperId, Map.copyOf(prefixLists)));
        try {
            reconcileAll();
        } catch (RuntimeException e) {
            if (previous == null) {
                endpoints.remove(endpoint.eniId());
            } else {
                endpoints.put(endpoint.eniId(), previous);
            }
            quarantine(helperId);
            throw e;
        }
    }

    public synchronized void unregister(String eniId) {
        ProtectedEndpoint removed = endpoints.remove(eniId);
        if (removed != null) {
            lifecycleManager.removeIfExists(removed.helperId());
            reconcileAll();
        }
    }

    public synchronized void updateGroups(String eniId, Set<String> groupIds,
                                          Map<String, SecurityGroup> groups,
                                          Map<String, List<String>> prefixLists) {
        ProtectedEndpoint current = endpoints.get(eniId);
        if (current == null) {
            return;
        }
        SecurityGroupNftCompiler.Endpoint identity = current.endpoint();
        List<SecurityGroup> attached = groupIds.stream().map(groups::get).toList();
        if (attached.isEmpty() || attached.stream().anyMatch(group -> group == null)) {
            quarantine(current.helperId());
            throw new IllegalArgumentException("Protected endpoint needs valid security groups");
        }
        SecurityGroupNftCompiler.Endpoint updated = new SecurityGroupNftCompiler.Endpoint(
                identity.accountId(), identity.region(), identity.vpcId(), identity.eniId(),
                identity.logicalAddress(), identity.transportAddress(), Set.copyOf(groupIds), attached);
        endpoints.put(eniId, new ProtectedEndpoint(updated, current.helperId(), Map.copyOf(prefixLists)));
        reconcileAll();
    }

    public synchronized void reconcileAll() {
        for (Map.Entry<String, ProtectedEndpoint> entry : new ArrayList<>(endpoints.entrySet())) {
            boolean running;
            try {
                running = Boolean.TRUE.equals(dockerClient.inspectContainerCmd(entry.getValue().helperId())
                        .exec().getState().getRunning());
            } catch (NotFoundException e) {
                running = false;
            }
            if (!running) {
                quarantine(entry.getValue().helperId());
                endpoints.remove(entry.getKey());
            }
        }
        List<SecurityGroupNftCompiler.Endpoint> peers = endpoints.values().stream()
                .map(ProtectedEndpoint::endpoint).toList();
        for (ProtectedEndpoint protectedEndpoint : new ArrayList<>(endpoints.values())) {
            try {
                String rules = SecurityGroupNftCompiler.compile(protectedEndpoint.endpoint(), peers,
                        protectedEndpoint.prefixLists());
                apply(protectedEndpoint.helperId(), rules);
            } catch (RuntimeException e) {
                endpoints.values().forEach(endpoint -> quarantine(endpoint.helperId()));
                throw e;
            }
        }
    }

    /** Refreshes every affected EC2 instance and ECS task after a control-plane rule change. */
    public synchronized void refreshPolicies(String region, Map<String, SecurityGroup> groups,
                                             Map<String, List<String>> prefixLists) {
        for (Map.Entry<String, ProtectedEndpoint> entry : new ArrayList<>(endpoints.entrySet())) {
            ProtectedEndpoint current = entry.getValue();
            SecurityGroupNftCompiler.Endpoint identity = current.endpoint();
            if (!region.equals(identity.region())) {
                continue;
            }
            List<SecurityGroup> attached = identity.groupIds().stream().map(groups::get).toList();
            if (attached.stream().anyMatch(group -> group == null)) {
                endpoints.values().forEach(endpoint -> quarantine(endpoint.helperId()));
                throw new IllegalStateException("A protected endpoint's security group disappeared");
            }
            SecurityGroupNftCompiler.Endpoint updated = new SecurityGroupNftCompiler.Endpoint(
                    identity.accountId(), identity.region(), identity.vpcId(), identity.eniId(),
                    identity.logicalAddress(), identity.transportAddress(), identity.groupIds(), attached);
            endpoints.put(entry.getKey(), new ProtectedEndpoint(updated, current.helperId(),
                    Map.copyOf(prefixLists)));
        }
        reconcileAll();
    }

    private void quarantine(String helperId) {
        try {
            apply(helperId, "flush chain inet floci_sg ingress\nflush chain inet floci_sg egress\n");
        } catch (Exception e) {
            LOG.errorv(e, "Failed to quarantine security-group helper {0}", helperId);
            try {
                stopNamespaceWorkloads(helperId);
                dockerClient.stopContainerCmd(helperId).withTimeout(0).exec();
            } catch (Exception stopFailure) {
                LOG.errorv(stopFailure, "Failed to stop workloads after quarantine failure for {0}", helperId);
            }
        }
    }

    private void stopNamespaceWorkloads(String helperId) {
        for (Container container : dockerClient.listContainersCmd().exec()) {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(container.getId()).exec();
            if (inspect.getHostConfig() != null && ("container:" + helperId)
                    .equals(inspect.getHostConfig().getNetworkMode())) {
                dockerClient.stopContainerCmd(container.getId()).withTimeout(0).exec();
            }
        }
    }

    private void removeNamespaceWorkloads(String helperId) {
        for (Container container : dockerClient.listContainersCmd().withShowAll(true).exec()) {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(container.getId()).exec();
            if (inspect.getHostConfig() != null && ("container:" + helperId)
                    .equals(inspect.getHostConfig().getNetworkMode())) {
                lifecycleManager.removeIfExists(container.getId());
            }
        }
    }

    private void apply(String helperId, String script) {
        for (int attempt = 0; attempt < 120; attempt++) {
            try {
                byte[] content = script.getBytes(StandardCharsets.UTF_8);
                ByteArrayOutputStream archive = new ByteArrayOutputStream(content.length + 1024);
                try (TarArchiveOutputStream tar = new TarArchiveOutputStream(archive)) {
                    TarArchiveEntry entry = new TarArchiveEntry("floci-sg.nft");
                    entry.setSize(content.length);
                    tar.putArchiveEntry(entry);
                    tar.write(content);
                    tar.closeArchiveEntry();
                }
                dockerClient.copyArchiveToContainerCmd(helperId)
                        .withRemotePath("/tmp")
                        .withTarInputStream(new ByteArrayInputStream(archive.toByteArray()))
                        .exec();
                String execId = dockerClient.execCreateCmd(helperId)
                        .withAttachStdout(true).withAttachStderr(true)
                        .withCmd("nft", "-f", "/tmp/floci-sg.nft").exec().getId();
                StringBuilder output = new StringBuilder();
                try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<Frame>() {
                    @Override public void onNext(Frame frame) {
                        output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                    }
                }) {
                    boolean completed = dockerClient.execStartCmd(execId)
                            .exec(callback).awaitCompletion(15, TimeUnit.SECONDS);
                    if (!completed) {
                        throw new IllegalStateException("nft command timed out");
                    }
                }
                Long exit = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
                if (exit != null && exit == 0) {
                    return;
                }
                if (output.toString().contains("not found") && attempt < 119) {
                    Thread.sleep(500);
                    continue;
                }
                throw new IllegalStateException("nft rejected security-group policy: " + output);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted installing security-group policy", e);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to install security-group policy", e);
            }
        }
    }

    public record Namespace(String helperId, String transportAddress) {}
    private record ProtectedEndpoint(SecurityGroupNftCompiler.Endpoint endpoint, String helperId,
                                     Map<String, List<String>> prefixLists) {}
}
