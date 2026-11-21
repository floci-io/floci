package io.floci.aws.services.emrserverless;

import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.ListVolumesResponse;
import com.github.dockerjava.api.model.Container;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLiveness;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The kept histories of EMR Serverless Spark runs and sessions in the docker runner mode. Each run's or session's
 * driver keeps its Spark event logs in a Docker volume of its own, mounted where the release's image writes them
 * ({@code spark.eventLog.dir file:///var/log/spark/apps}), and once it has ended a Spark History Server reads them, in
 * a container of its own, made from the release's image, that starts on a link to it and stops once no link to it is
 * valid. "By default, EMR Serverless stores application logs securely in Amazon EMR managed storage for a maximum of
 * 30 days" (EMR Serverless user guide, "Storing logs"): its owner, {@link EmrServerlessAppUis}, removes a history once
 * that has passed, or once its run or session has gone, every history on a reset, and the containers serving them on
 * a shutdown.
 */
@ApplicationScoped
public class EmrServerlessHistories {

    private static final Logger LOG = Logger.getLogger(EmrServerlessHistories.class);

    /** Where a driver writes its Spark event logs, as the release's image's {@code spark-defaults} say. */
    static final String EVENT_LOGS = "/var/log/spark/apps";
    /** How long a history is kept after its run or session has ended. */
    static final Duration KEPT = Duration.ofDays(30);
    /** The component labels of a history's volume and of the container serving it. */
    static final String VOLUME_COMPONENT = "emr-serverless-history";
    static final String SERVER_COMPONENT = "emr-serverless-history-ui";
    private static final int SPARK_HISTORY_PORT = EmrServerlessSparkRunner.SPARK_HISTORY_WEB_PORT;
    // The Spark History Server on the event logs, on its own port, its logs in /tmp, as the image's own folder for them
    // is not its user's.
    private static final String SERVER_SCRIPT = "SPARK_LOG_DIR=/tmp SPARK_HISTORY_OPTS=\""
            + "-Dspark.history.fs.logDirectory=file://" + EVENT_LOGS + " -Dspark.history.ui.port=" + SPARK_HISTORY_PORT
            + "\" exec"
            + " /usr/lib/spark/bin/spark-class org.apache.spark.deploy.history.HistoryServer";
    private static final String[] ANSWERS = {"curl", "-sf", "-m", "5",
            "http://127.0.0.1:" + SPARK_HISTORY_PORT + "/api/v1/version"};

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;
    private final Executor starts;
    // The histories whose server is being started, so that one link's start is not repeated by the next.
    private final Set<String> starting = ConcurrentHashMap.newKeySet();
    // The containers serving histories this process started, which a shutdown removes.
    private final Set<String> servers = ConcurrentHashMap.newKeySet();
    // Counts the teardowns and sweeps, so that a server whose start one ran through goes.
    private final AtomicLong teardowns = new AtomicLong();

    @Inject
    public EmrServerlessHistories(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                  EmulatorConfig config) {
        this(containerBuilder, lifecycleManager, config,
                task -> Thread.ofPlatform().daemon().name("emr-serverless-history").start(task));
    }

    EmrServerlessHistories(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                           EmulatorConfig config, Executor starts) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
        this.starts = starts;
    }

    String volume(String runId) {
        return volume(config, runId);
    }

    /** The volume a run's or session's driver keeps its event logs in. */
    static String volume(EmulatorConfig config, String runId) {
        return ContainerStorageHelper.dockerName(config, "emr-serverless-" + runId + "-history");
    }

    private String serverName(String runId) {
        return ContainerStorageHelper.dockerName(config, "emr-serverless-" + runId + "-history-ui");
    }

    /**
     * The labels of a run's or session's history volume, or, as {@code component}, of the container serving it, with
     * the Floci deployment that keeps it, so that another sharing the Docker daemon leaves it alone.
     */
    static Map<String, String> labels(EmulatorConfig config, String runId, String accountId, String region,
                                      String component) {
        Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.resourceIdentityLabels(
                EmrServerlessJobRunService.SERVICE, runId, accountId, region));
        labels.put(ContainerStorageHelper.COMPONENT_LABEL, component);
        labels.put(ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config));
        return labels;
    }

    /**
     * The ids of those of {@code records}, runs or sessions, whose kept history stays: those that have not ended, as
     * {@code ended} tells, and those that ended after {@code cutoff}, as {@code endedAt} tells.
     */
    static <T> Set<String> stillKept(List<T> records, Function<T, String> id, Predicate<T> ended,
                                     Function<T, Instant> endedAt, Instant cutoff) {
        Set<String> kept = new HashSet<>();
        for (T record : records) {
            Instant end = endedAt.apply(record);
            if (!ended.test(record) || end == null || end.isAfter(cutoff)) {
                kept.add(id.apply(record));
            }
        }
        return kept;
    }

    /** Whether a run's or session's history is kept: its volume is there. A Docker error goes to the caller. */
    boolean kept(String runId) {
        return lifecycleManager.volumeExists(volume(runId));
    }

    /**
     * Where Floci reaches the Spark History Server of a run's or session's kept history, once it answers; empty until
     * then, the server started, on {@code network}, from {@code image}, where it is not running.
     */
    Optional<ContainerLifecycleManager.EndpointInfo> server(String runId, String accountId, String region,
                                                           String image, String network) {
        String name = serverName(runId);
        try {
            ContainerLiveness liveness = lifecycleManager.probeContainer(name);
            if (liveness == ContainerLiveness.RUNNING) {
                if (ContainerExec.run(lifecycleManager.getDockerClient(), name, ANSWERS, 10).exitCode() != 0) {
                    return Optional.empty();
                }
                return Optional.of(lifecycleManager.resolveEndpoint(name, SPARK_HISTORY_PORT, network));
            }
            if (liveness == ContainerLiveness.NOT_RUNNING && starting.add(runId)) {
                long teardown = teardowns.get();
                starts.execute(() -> {
                    try {
                        start(runId, accountId, region, image, network, teardown);
                    } catch (RuntimeException e) {
                        LOG.warnv(e, "Could not start the history of EMR Serverless run {0}: {1}", runId,
                                e.getMessage());
                    } finally {
                        starting.remove(runId);
                    }
                });
            }
        } catch (RuntimeException e) {
            LOG.warnv(e, "The history of EMR Serverless run {0} is not reachable: {1}", runId, e.getMessage());
        }
        return Optional.empty();
    }

    // The container serving a kept history, with its volume, its port published on the host's loopback interface
    // alone; none once a teardown or a sweep has run since it was asked for, or the history has gone, whose volume the
    // container would make again, empty.
    private void start(String runId, String accountId, String region, String image, String network, long teardown) {
        if (teardowns.get() != teardown || !kept(runId)) {
            return;
        }
        String name = serverName(runId);
        lifecycleManager.removeIfExistsStrict(name);
        // Known before it starts, so a shutdown while it starts removes it.
        servers.add(name);
        lifecycleManager.createAndStart(containerBuilder.newContainer(image)
                .withName(name)
                .withEntrypoint(List.of("/bin/bash", "-c", SERVER_SCRIPT))
                .withNamedVolume(volume(runId), EVENT_LOGS)
                .withLoopbackPortBinding(SPARK_HISTORY_PORT, 0)
                .withDockerNetwork(Optional.ofNullable(network))
                .withLogRotation()
                .withLabels(labels(config, runId, accountId, region, SERVER_COMPONENT))
                .build());
        // A teardown or a sweep while it started may have missed it, and a history removed meanwhile is a volume
        // Docker made again, empty and unlabelled, for it.
        boolean recreated = !labelled(volume(runId));
        if (teardowns.get() != teardown || recreated) {
            lifecycleManager.removeIfExistsStrict(name);
            servers.remove(name);
            if (recreated) {
                lifecycleManager.removeVolume(volume(runId));
            }
            return;
        }
        LOG.infov("Started the history of EMR Serverless run {0}", runId);
    }

    // Whether the volume is a kept history's, as the runner made it, rather than one Docker made for a mount.
    private boolean labelled(String volume) {
        Map<String, String> labels = lifecycleManager.getDockerClient().inspectVolumeCmd(volume).exec().getLabels();
        return labels != null && VOLUME_COMPONENT.equals(labels.get(ContainerStorageHelper.COMPONENT_LABEL));
    }

    /** Stops the container serving a run's or session's kept history; the history stays. */
    void stop(String runId) {
        try {
            lifecycleManager.removeIfExistsStrict(serverName(runId));
            servers.remove(serverName(runId));
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not stop the history of EMR Serverless run {0}: {1}", runId, e.getMessage());
        }
    }

    /**
     * Removes the kept histories, and the containers serving them, of this Floci's runs and sessions {@code kept}
     * refuses.
     */
    void removeAllBut(Predicate<String> kept) {
        teardowns.incrementAndGet();
        try {
            for (Container container : listServers()) {
                String runId = runIdOf(container.getLabels());
                if (!kept.test(runId)) {
                    lifecycleManager.removeIfExistsStrict(container.getId());
                    servers.remove(serverName(runId));
                }
            }
            ListVolumesResponse response = lifecycleManager.getDockerClient().listVolumesCmd()
                    .withFilter("label", List.of(componentLabel(VOLUME_COMPONENT), ownerLabel())).exec();
            List<InspectVolumeResponse> volumes = response != null && response.getVolumes() != null
                    ? response.getVolumes() : List.of();
            for (InspectVolumeResponse volume : volumes) {
                if (!kept.test(runIdOf(volume.getLabels()))) {
                    lifecycleManager.removeVolume(volume.getName());
                }
            }
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not remove the kept histories of EMR Serverless runs: {0}", e.getMessage());
        }
    }

    // The containers serving this Floci's kept histories.
    private List<Container> listServers() {
        return lifecycleManager.getDockerClient().listContainersCmd().withShowAll(true)
                .withLabelFilter(List.of(componentLabel(SERVER_COMPONENT), ownerLabel())).exec();
    }

    private String ownerLabel() {
        return ContainerStorageHelper.OWNER_LABEL + "=" + ContainerStorageHelper.ownerIdentity(config);
    }

    private static String runIdOf(Map<String, String> labels) {
        return labels != null ? labels.get(ContainerStorageHelper.RESOURCE_ID_LABEL) : null;
    }

    private static String componentLabel(String component) {
        return ContainerStorageHelper.COMPONENT_LABEL + "=" + component;
    }

    /** Removes the containers an earlier process of this Floci left serving kept histories, which start again. */
    void removeLeftoverServers() {
        try {
            for (Container container : listServers()) {
                lifecycleManager.removeIfExistsStrict(container.getId());
            }
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not remove the containers left serving EMR Serverless kept histories: {0}",
                    e.getMessage());
        }
    }

    /** Stops every container serving a kept history this process started; the histories stay for a restart. */
    void stopServers() {
        teardowns.incrementAndGet();
        for (String name : Set.copyOf(servers)) {
            try {
                lifecycleManager.removeIfExistsStrict(name);
                servers.remove(name);
            } catch (RuntimeException e) {
                LOG.warnv(e, "Could not stop the container {0} of an EMR Serverless kept history: {1}", name,
                        e.getMessage());
            }
        }
    }
}
