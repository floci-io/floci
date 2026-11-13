package io.floci.aws.services.emr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.emr.model.EmrCluster;
import io.floci.aws.services.emr.model.EmrStep;
import io.floci.aws.services.emrserverless.SparkJobRunner;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.floci.aws.testing.MutableClock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An {@link EmrService} on in-memory storage, with a clock the test moves and wakeups the test runs,
 * recording the {@code aws.emr} events it publishes. Each event is handed to {@link #target} as it is
 * published, as EventBridge's first delivery is, and checked to be published once the service is let go,
 * and, for a wakeup's, after it, as the timer's own thread never sends them. In the {@code docker} runner
 * mode a step's thread, and a bootstrap's, is one the test runs, with {@link #runStepThread}.
 */
final class EmrServiceFixture {

    static final String REGION = "us-east-1";
    static final String ACCOUNT = "000000000000";

    /** A rule's target, given each event as it is published. */
    @FunctionalInterface
    interface Target {
        void deliver(JsonNode event) throws Exception;
    }

    private record Wakeup(Instant due, Runnable task) {}

    /**
     * A cluster's storage as what the service asked of it, each removal refused while {@link #refuse} is set, and
     * {@link #whileCreating} run as each is made.
     */
    static final class RecordingStorage implements ClusterStorage {
        // The address each cluster's HDFS answers at.
        static final String HDFS_ADDRESS = "172.18.0.5";
        // The Docker network of a cluster's own each cluster's HDFS is on.
        static final String HDFS_NETWORK = "floci-aws-emr-hdfs";
        final List<String> created = new ArrayList<>();
        // Each HDFS started: its cluster, primary node's host name, image and Docker network.
        final List<List<String>> hdfs = new ArrayList<>();
        // Each HDFS's metastore service asked for.
        final List<HiveMetastore> metastores = new ArrayList<>();
        // Each HDFS's YARN asked for.
        final List<Yarn> yarns = new ArrayList<>();
        // Each cluster and S3 folder its YARN applications' logs were put in.
        final List<List<String>> containerLogs = new ArrayList<>();
        // Floci's address on a cluster's network, which a node running YARN gives its executors.
        static final String FLOCI_ON_NETWORK = "http://floci:4566";
        // Whether a cluster's primary node has a network of its own, as with a docker-network that is not shared.
        boolean ownNetwork;
        RuntimeException hdfsFailure;
        // Runs as each HDFS starts, with what tells it the step is past starting.
        Consumer<BooleanSupplier> whileStartingHdfs = stopped -> { };
        final List<String> removed = new ArrayList<>();
        final List<Set<String>> kept = new ArrayList<>();
        // Each resize asked for: its cluster and the nodes it wants.
        final List<ClusterStorage.Yarn> resizes = new ArrayList<>();
        boolean refuse;
        Runnable whileCreating = () -> { };

        @Override
        public SparkJobRunner.Storage volumes(String clusterId) {
            return new SparkJobRunner.Storage(clusterId + "-home", clusterId + "-mnt");
        }

        @Override
        public void create(String clusterId, String accountId, String region) {
            created.add(clusterId);
            whileCreating.run();
        }

        @Override
        public boolean ownNetwork(String dockerNetwork) {
            return ownNetwork;
        }

        @Override
        public void resize(String clusterId, Supplier<Yarn> yarn) {
            resizes.add(yarn.get());
        }

        @Override
        public void archiveContainerLogs(String clusterId, String accountId, String region, String logFolder) {
            containerLogs.add(List.of(clusterId, logFolder));
        }

        @Override
        public SparkJobRunner.Storage hdfs(String clusterId, String accountId, String region, String host,
                                           String image, String dockerNetwork, HiveMetastore hiveMetastore,
                                           Supplier<Yarn> yarn,
                                           BooleanSupplier stopped) {
            hdfs.add(Arrays.asList(clusterId, host, image, dockerNetwork, String.valueOf(hiveMetastore != null),
                    hiveMetastore != null ? hiveMetastore.role() : null));
            if (hiveMetastore != null) {
                metastores.add(hiveMetastore);
            }
            if (yarn != null) {
                yarns.add(yarn.get());
            }
            whileStartingHdfs.accept(stopped);
            if (hdfsFailure != null) {
                throw hdfsFailure;
            }
            SparkJobRunner.Storage storage = volumes(clusterId).withHdfs(host, HDFS_ADDRESS, HDFS_NETWORK);
            if (hiveMetastore != null) {
                storage = storage.withHiveMetastore("thrift://" + host + ":9083");
            }
            return yarn != null ? storage.withYarn(host, FLOCI_ON_NETWORK) : storage;
        }

        @Override
        public boolean remove(String clusterId) {
            removed.add(clusterId);
            return !refuse;
        }

        @Override
        public void removeAllBut(Set<String> kept) {
            this.kept.add(kept);
        }
    }

    /** Makes no image: answers {@link #IMAGE} for each, or throws {@link #failure} when it is set. */
    static final class RecordingReleaseImages implements ReleaseImages {
        static final String IMAGE = "sha256:" + "3".repeat(64);
        // Each image asked for: its release, then the two it is made of, then whether it has Hive.
        final List<List<String>> asked = new ArrayList<>();
        RuntimeException failure;

        @Override
        public String image(String releaseLabel, String sparkImage, String hiveImage, boolean withHive) {
            asked.add(List.of(releaseLabel, sparkImage, hiveImage, String.valueOf(withHive)));
            if (failure != null) {
                throw failure;
            }
            return IMAGE;
        }
    }

    final ObjectMapper objectMapper = new ObjectMapper();
    final List<JsonNode> published = new ArrayList<>();
    final MutableClock clock;
    final EmrEventPublisher publisher;
    // Not final: the events' check reads it, from the mock set up before the service is built.
    EmrService service;
    Target target = event -> { };
    private final List<Wakeup> wakeups = new ArrayList<>();
    private final List<Runnable> deliveries = new ArrayList<>();
    // The docker runner mode's step threads, not yet run.
    private final List<Runnable> stepThreads = new ArrayList<>();
    private boolean waking;
    private final InMemoryStorageFactory storage;
    private final SparkJobRunner runner;
    private final BootstrapRunner bootstrap;
    // The docker runner mode's; null in the immediate one.
    final RecordingStorage clusterStorage;
    final RecordingReleaseImages releaseImages;
    private final IamService iam;
    private final int[] durations;

    /** The service as configured by default, every duration 0. */
    EmrServiceFixture() {
        this(0, 0, 0);
    }

    EmrServiceFixture(int startupDelaySeconds, int stepDurationSeconds, int terminationDelaySeconds) {
        this(new InMemoryStorageFactory(ACCOUNT), new MutableClock(), null, null, null, null, null,
                startupDelaySeconds, stepDurationSeconds, terminationDelaySeconds);
    }

    /** The service in the {@code docker} runner mode, every duration 0, its steps run by {@code runner}. */
    static EmrServiceFixture docker(SparkJobRunner runner, IamService iam) {
        return docker(runner, null, iam);
    }

    /** As {@link #docker(SparkJobRunner, IamService)}, its clusters' bootstrap actions run by {@code bootstrap}. */
    static EmrServiceFixture docker(SparkJobRunner runner, BootstrapRunner bootstrap, IamService iam) {
        return new EmrServiceFixture(new InMemoryStorageFactory(ACCOUNT), new MutableClock(), runner, bootstrap,
                new RecordingStorage(), new RecordingReleaseImages(), iam, 0, 0, 0);
    }

    private EmrServiceFixture(InMemoryStorageFactory storage, MutableClock clock, SparkJobRunner runner,
                              BootstrapRunner bootstrap, RecordingStorage clusterStorage,
                              RecordingReleaseImages releaseImages, IamService iam, int... durations) {
        this.storage = storage;
        this.clock = clock;
        this.runner = runner;
        this.bootstrap = bootstrap;
        this.clusterStorage = clusterStorage;
        this.releaseImages = releaseImages;
        this.iam = iam;
        this.durations = durations;
        EventBridgeService eventBridge = mock(EventBridgeService.class);
        doAnswer(invocation -> {
            // The call that made the change has let go of the service by now.
            assertFalse(Thread.holdsLock(service), "published while EmrService was held");
            assertFalse(waking, "published by a wakeup itself");
            JsonNode event = objectMapper.createObjectNode()
                    .put("detail-type", invocation.<String>getArgument(1))
                    .set("detail", objectMapper.readTree(invocation.<String>getArgument(2)));
            published.add(event);
            target.deliver(event);
            return null;
        }).when(eventBridge).publishServiceEvent(eq("aws.emr"), anyString(), anyString(), isNull(), eq(REGION));
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        publisher = new EmrEventPublisher(eventBridge, objectMapper, regionResolver);
        service = new EmrService(storage, config(false), regionResolver, publisher, clock, new EmrService.Wakeups() {
            @Override
            public void at(Instant due, Runnable task) {
                wakeups.add(new Wakeup(due, task));
            }

            @Override
            public void deliver(Runnable delivery) {
                deliveries.add(delivery);
            }
        }, runner, bootstrap, clusterStorage, releaseImages, stepThreads::add, iam);
    }

    /** A new service on the same storage and clock, as after a restart of Floci with persistent storage. */
    EmrServiceFixture restart() {
        return new EmrServiceFixture(storage, clock, runner, bootstrap, clusterStorage, releaseImages, iam, durations);
    }

    /**
     * A new service on the same storage and clock, as {@link #restart} builds, but with {@code runner},
     * its wakeups set on {@code wakeups}, and leftover drivers removed when it starts.
     */
    EmrService restartReconciling(SparkJobRunner runner, EmrService.Wakeups wakeups) {
        return new EmrService(storage, config(true), new RegionResolver(REGION, ACCOUNT), publisher, clock, wakeups,
                runner, bootstrap, clusterStorage, releaseImages, stepThreads::add, iam);
    }

    /**
     * A new service on the same storage and clock, as {@link #restart} builds, whose Hive steps run in the image
     * {@code hiveImageTemplate} names, its steps' threads this fixture's, and its events not delivered.
     */
    EmrService withHiveImageTemplate(String hiveImageTemplate) {
        EmulatorConfig config = config(false);
        when(config.services().emr().hiveImageTemplate()).thenReturn(Optional.of(hiveImageTemplate));
        return new EmrService(storage, config, new RegionResolver(REGION, ACCOUNT), publisher, clock,
                mock(EmrService.Wakeups.class), runner, bootstrap, clusterStorage, releaseImages, stepThreads::add,
                iam);
    }

    private EmulatorConfig config(boolean reconcile) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().emr().defaultReleaseLabel()).thenReturn("emr-7.5.0");
        when(config.services().emr().clusterStartupDelaySeconds()).thenReturn(durations[0]);
        when(config.services().emr().stepDurationSeconds()).thenReturn(durations[1]);
        when(config.services().emr().clusterTerminationDelaySeconds()).thenReturn(durations[2]);
        when(config.services().emr().imageTemplate()).thenReturn(Optional.empty());
        when(config.services().emr().hiveImageTemplate()).thenReturn(Optional.empty());
        when(config.services().emr().dockerNetwork()).thenReturn(Optional.empty());
        when(config.services().emr().yarnNodeMemoryMb()).thenReturn(4096);
        when(config.services().emr().yarnNodeVcores()).thenReturn(4);
        when(config.services().emr().yarnMaxWorkerNodes()).thenReturn(4);
        when(config.services().emr().reconcileContainersOnStartup()).thenReturn(reconcile);
        return config;
    }

    /** Runs the oldest step thread not yet run, as the docker runner mode's pool would. */
    void runStepThread() {
        stepThreads.remove(0).run();
    }

    /** How many step threads have been started and not yet run. */
    int stepThreadsWaiting() {
        return stepThreads.size();
    }

    /**
     * Moves the clock on by {@code duration} and runs the wakeups due by then, as the service's timer
     * would, the ones they set included, each followed by the delivery of its events.
     */
    void pass(Duration duration) {
        clock.advance(duration);
        Instant now = clock.instant();
        List<Wakeup> due;
        while (!(due = wakeups.stream().filter(wakeup -> !wakeup.due().isAfter(now)).toList()).isEmpty()) {
            wakeups.removeAll(due);
            for (Wakeup wakeup : due) {
                waking = true;
                try {
                    wakeup.task().run();
                } finally {
                    waking = false;
                }
                while (!deliveries.isEmpty()) {
                    deliveries.remove(0).run();
                }
            }
        }
    }

    /** The times of the wakeups not yet run. */
    List<Instant> wakeupTimes() {
        return wakeups.stream().map(Wakeup::due).toList();
    }

    /** Each event's state, a step's followed by its name. */
    List<String> states() {
        return published.stream().map(event -> {
            JsonNode detail = event.path("detail");
            return detail.has("stepId")
                    ? detail.path("state").asText() + " " + detail.path("name").asText()
                    : detail.path("state").asText();
        }).toList();
    }

    static EmrCluster cluster(String name, boolean keepAlive, EmrStep... steps) {
        EmrCluster cluster = new EmrCluster();
        cluster.setName(name);
        cluster.setKeepJobFlowAliveWhenNoSteps(keepAlive);
        cluster.setSteps(new ArrayList<>(List.of(steps)));
        return cluster;
    }

    static EmrStep step(String name) {
        EmrStep step = new EmrStep();
        step.setName(name);
        step.setActionOnFailure("CONTINUE");
        return step;
    }
}
