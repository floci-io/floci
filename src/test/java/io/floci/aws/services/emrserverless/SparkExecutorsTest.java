package io.floci.aws.services.emrserverless;

import io.floci.aws.services.emrserverless.SparkExecutors.Amount;
import io.floci.aws.services.emrserverless.SparkExecutors.Capacity;
import io.floci.aws.services.emrserverless.SparkExecutors.Size;
import io.floci.aws.services.emrserverless.SparkExecutors.Worker;
import io.floci.aws.services.emrserverless.SparkJobRunner.Executors;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An EMR Serverless Spark run's executors in containers of their own, as its driver wants them. */
class SparkExecutorsTest {

    // The image's own spark-defaults: 4 cores and 14G for the driver and each executor, dynamic allocation on.
    private static final Map<String, String> IMAGE_DEFAULTS = Map.of(
            "spark.executor.cores", "4", "spark.executor.memory", "14G",
            "spark.driver.cores", "4", "spark.driver.memory", "14G",
            "spark.dynamicAllocation.enabled", "true", "spark.executor.instances", "3");
    private static final Executors TWO = new Executors(2, null, 0, 0, 0, null);
    // The worker of a 4-core 14G executor, or driver: 14 GB and its 10% overhead rounded up, and a 20 GB disk.
    private static final Amount FOUR_CORES = new Amount(4, 16, 20);

    // Real time, as the driver is looked at by it.
    private final AtomicLong nanos = new AtomicLong(-5_000_000_000L);
    private final FakeDriver driver = new FakeDriver();
    private final FakeContainers containers = new FakeContainers();
    private final Capacity capacity = new Capacity();

    @Test
    void aWorkerOffersItsExecutorsCoresAndHeapAndHoldsItsMemoryWithOverheadAndItsDisk() {
        Size size = SparkExecutors.sizeOf(IMAGE_DEFAULTS);

        assertEquals(4, size.cores());
        assertEquals(14 * 1024, size.memoryMib());
        // 14 GB and 10% of it, rounded up to a whole GB: the 16 GB worker the user guide pairs with 4 vCPUs.
        assertEquals(FOUR_CORES, size.executor());
        assertEquals(FOUR_CORES, size.driver());
        // The overhead is at least 384 MB, or what the job sets; a bare number is MiB; a disk as the job sets it.
        Size small = SparkExecutors.sizeOf(Map.of("spark.executor.cores", "1", "spark.executor.memory", "2048",
                "spark.emr-serverless.executor.disk", "50G"));
        assertEquals(1, small.cores());
        assertEquals(2048, small.memoryMib());
        assertEquals(new Amount(1, 3, 50), small.executor());
        assertEquals(2, SparkExecutors.sizeOf(Map.of("spark.executor.memory", "1g",
                "spark.executor.memoryOverhead", "1g")).executor().memoryGb());
        // Spark's own defaults when the job sets nothing: one core, 1 GB.
        Size spark = SparkExecutors.sizeOf(Map.of());
        assertEquals(1, spark.cores());
        assertEquals(1024, spark.memoryMib());
        assertEquals(1, spark.driver().vcpus());
    }

    @Test
    void emrServerlesssOverheadFactorCountsUnlessTheRolesOwnIsSet() {
        // "spark.emr-serverless.memoryOverheadFactor | Sets the memory overhead to add to the driver and executor
        // container memory." 14 GB and half of it are 21 GB.
        Map<String, String> settings = new HashMap<>(IMAGE_DEFAULTS);
        settings.put("spark.emr-serverless.memoryOverheadFactor", "0.5");
        assertEquals(21, SparkExecutors.sizeOf(settings).executor().memoryGb());
        assertEquals(21, SparkExecutors.sizeOf(settings).driver().memoryGb());

        settings.put("spark.executor.memoryOverheadFactor", "0.25");
        settings.put("spark.driver.memoryOverhead", "2g");
        assertEquals(18, SparkExecutors.sizeOf(settings).executor().memoryGb());
        assertEquals(16, SparkExecutors.sizeOf(settings).driver().memoryGb());
    }

    @Test
    void anApplicationsCapacityTakesAWorkerOnlyWhileItsLimitsHaveRoomForIt() {
        // Beside the driver's 4 vCPUs, 16 GB and 20 GB of disk.
        assertEquals(2, taken(new Executors(9, null, 12, 0, 0, null)));
        assertEquals(1, taken(new Executors(9, null, 0, 40, 0, null)));
        assertEquals(3, taken(new Executors(9, null, 0, 0, 80, null)));
        assertEquals(0, taken(new Executors(9, null, 2, 0, 0, null)));
        assertEquals(9, taken(new Executors(9, null, 0, 0, 0, null)));
    }

    @Test
    void theRunsOfAnApplicationShareItsMaximumCapacity() {
        // Its capacity "is cumulative across all workers at any given point in time": 12 vCPUs, for two runs whose
        // drivers and executors take 4 each.
        Executors twelve = new Executors(9, null, 12, 0, 0, "application");
        driver.settings(IMAGE_DEFAULTS);
        driver.target(3);
        SparkExecutors first = new SparkExecutors(twelve, driver, containers, capacity, nanos::get);
        first.follow();
        FakeContainers others = new FakeContainers();
        SparkExecutors second = new SparkExecutors(twelve, driver, others, capacity, nanos::get);
        second.follow();

        assertEquals(List.of(1, 2), containers.started);
        // Its driver runs already, so it is held, beyond the capacity, and none of its executors starts.
        assertEquals(List.of(), others.started);
        assertEquals(new Amount(16, 64, 80), capacity.held());

        // Once the first run has ended, the second has room for two.
        first.removeAll();
        advance(SparkExecutors.LOOK_INTERVAL);
        second.follow();
        assertEquals(List.of(1, 2), others.started);
        assertEquals(new Amount(12, 48, 60), capacity.held());
        second.removeAll();
        assertEquals(Amount.NONE, capacity.held());
    }

    @Test
    void nothingStartsUntilTheDriversUiAnswers() {
        SparkExecutors executors = new SparkExecutors(TWO, driver, containers, capacity, nanos::get);

        executors.follow();

        assertEquals(List.of(), containers.started);
    }

    @Test
    void asManyExecutorsStartAsDynamicAllocationWantsUpToTheRunsLimit() {
        driver.settings(IMAGE_DEFAULTS);
        driver.target(3);
        SparkExecutors executors = new SparkExecutors(TWO, driver, containers, capacity, nanos::get);

        executors.follow();

        assertEquals(List.of(1, 2), containers.started);
        assertEquals(new Size(4, 14 * 1024, FOUR_CORES, FOUR_CORES), containers.sizes.getFirst());
        // Still wanted, none more.
        advance(SparkExecutors.LOOK_INTERVAL);
        executors.follow();
        assertEquals(List.of(1, 2), containers.started);
        assertEquals(2, executors.workers().size());
    }

    @Test
    void noExecutorStartsThatWouldTakeTheApplicationPastItsMaximumCapacity() {
        driver.settings(IMAGE_DEFAULTS);
        driver.target(5);
        SparkExecutors executors = new SparkExecutors(new Executors(9, null, 12, 0, 0, null), driver,
                containers, capacity, nanos::get);

        executors.follow();

        // 4 vCPUs for the driver's worker and 4 for each executor's, of 12.
        assertEquals(List.of(1, 2), containers.started);
    }

    @Test
    void anIdleExecutorAboveWhatSparkWantsGoesAndABusyOneStays() {
        driver.settings(IMAGE_DEFAULTS);
        driver.target(2);
        SparkExecutors executors = new SparkExecutors(TWO, driver, containers, capacity, nanos::get);
        executors.follow();

        driver.target(1);
        driver.coresUsed(Map.of("10.0.0.1", 4, "10.0.0.2", 0));
        advance(SparkExecutors.LOOK_INTERVAL);
        executors.follow();

        assertEquals(List.of("executor-2"), containers.removed);
        assertEquals(List.of(new Worker("executor-1", "10.0.0.1")), executors.workers());
        // Its worker's share of the capacity goes with it: the driver's and one executor's are left.
        assertEquals(new Amount(8, 32, 40), capacity.held());
        // Busy, it stays above the target until it is idle.
        driver.target(0);
        advance(SparkExecutors.LOOK_INTERVAL);
        executors.follow();
        assertEquals(List.of("executor-2"), containers.removed);
    }

    @Test
    void anExecutorWhoseContainerEndedIsStartedAgainWhileSparkStillWantsIt() {
        driver.settings(IMAGE_DEFAULTS);
        driver.target(2);
        SparkExecutors executors = new SparkExecutors(TWO, driver, containers, capacity, nanos::get);
        executors.follow();

        containers.ended.add("executor-1");
        advance(SparkExecutors.LOOK_INTERVAL);
        executors.follow();

        assertEquals(List.of(1, 2, 3), containers.started);
        assertEquals(List.of("executor-2", "executor-3"),
                executors.workers().stream().map(Worker::containerId).toList());
        // The container that ended goes from the daemon too, and its worker's share of the capacity with it.
        assertEquals(List.of("executor-1"), containers.removed);
        assertEquals(new Amount(12, 48, 60), capacity.held());
    }

    @Test
    void anEndedExecutorsContainerDockerWillNotRemoveYetIsRemovedWithTheRun() {
        driver.settings(IMAGE_DEFAULTS);
        driver.target(1);
        SparkExecutors executors = new SparkExecutors(TWO, driver, containers, capacity, nanos::get);
        executors.follow();
        containers.ended.add("executor-1");
        containers.refused.add("executor-1");
        advance(SparkExecutors.LOOK_INTERVAL);
        executors.follow();

        containers.refused.clear();
        executors.removeAll();

        assertEquals(List.of("executor-2", "executor-1"), containers.removed);
    }

    @Test
    void anExecutorWhoseContainerCouldNotStartGivesItsShareBackAndIsStartedAtTheNextLook() {
        driver.settings(IMAGE_DEFAULTS);
        driver.target(1);
        containers.failNextStart = true;
        SparkExecutors executors = new SparkExecutors(TWO, driver, containers, capacity, nanos::get);

        executors.follow();
        assertTrue(executors.workers().isEmpty());
        assertEquals(FOUR_CORES, capacity.held());

        advance(SparkExecutors.LOOK_INTERVAL);
        executors.follow();
        assertEquals(1, executors.workers().size());
    }

    @Test
    void withoutDynamicAllocationItsExecutorInstancesStart() {
        Map<String, String> fixed = new HashMap<>(IMAGE_DEFAULTS);
        fixed.put("spark.dynamicAllocation.enabled", "false");
        fixed.put("spark.executor.instances", "1");
        driver.settings(fixed);
        SparkExecutors executors = new SparkExecutors(TWO, driver, containers, capacity, nanos::get);

        executors.follow();

        assertEquals(List.of(1), containers.started);
    }

    @Test
    void aDriverWhoseUiNeverAnswersGetsOneExecutorThatFitsAnySettings() {
        driver.masterHasApplication = true;
        // Its sizes unknown, the one executor counts for nothing against a capacity smaller than what it offers.
        SparkExecutors executors = new SparkExecutors(new Executors(2, null, 4, 8, 20, null), driver, containers,
                capacity, nanos::get);
        executors.follow();
        advance(SparkExecutors.UI_SILENCE.minus(Duration.ofSeconds(1)));
        executors.follow();
        assertEquals(List.of(), containers.started);

        advance(SparkExecutors.LOOK_INTERVAL);
        executors.follow();

        assertEquals(List.of(1), containers.started);
        assertEquals(SparkExecutors.BLIND_SIZE, containers.sizes.getFirst());
        assertEquals(Amount.NONE, capacity.held());
    }

    @Test
    void theDriverIsLookedAtOnceALookInterval() {
        driver.settings(IMAGE_DEFAULTS);
        driver.target(1);
        SparkExecutors executors = new SparkExecutors(TWO, driver, containers, capacity, nanos::get);
        executors.follow();
        int asked = driver.asked;

        executors.follow();

        assertEquals(asked, driver.asked);
    }

    @Test
    void everyExecutorGoesWithTheRun() {
        driver.settings(IMAGE_DEFAULTS);
        driver.target(2);
        SparkExecutors executors = new SparkExecutors(TWO, driver, containers, capacity, nanos::get);
        executors.follow();

        executors.removeAll();

        assertEquals(List.of("executor-1", "executor-2"), containers.removed);
        assertTrue(executors.workers().isEmpty());
        assertEquals(Amount.NONE, capacity.held());
    }

    @Test
    void sizesReadAsSparkReadsThem() {
        assertEquals(14 * 1024, SparkExecutors.mebibytes("14G", 0));
        assertEquals(14 * 1024, SparkExecutors.mebibytes("14gb", 0));
        assertEquals(512, SparkExecutors.mebibytes("512m", 0));
        assertEquals(2048, SparkExecutors.mebibytes("2048", 0));
        assertEquals(7, SparkExecutors.mebibytes("not a size", 7));
        assertEquals(20, SparkExecutors.gigabytes(null));
        assertEquals(200, SparkExecutors.gigabytes("200G"));
    }

    @Test
    void sparkSubmitsOptionsEndAtItsPrimaryResource() {
        assertEquals(4, SparkExecutors.optionCount(List.of("--conf", "a=1", "--verbose", "--conf=b=2",
                "s3://bucket/app.py", "--conf", "c=3")));
        assertEquals(6, SparkExecutors.optionCount(List.of("-c", "a=1", "--name", "-x", "--supervise", "-v",
                "app.jar")));
        assertEquals(0, SparkExecutors.optionCount(List.of("app.jar", "--conf", "a=1")));
        assertEquals(2, SparkExecutors.optionCount(List.of("--conf", "a=1")));
        assertEquals(1, SparkExecutors.optionCount(List.of("--conf")));
    }

    @Test
    void theDriversContainerStartsTheMasterBeforeSparkSubmitAndAWorkerOffersOneExecutor() {
        // The master's address, the run's secret and the UI's kill links off come after the run's own options, which
        // cannot replace them, and before its primary resource.
        assertTrue(SparkExecutors.DRIVER_WRAPPER.contains("submit=$1; options=$2; shift 2\nexec \"$submit\""
                + " \"${@:1:$options}\" --master \"spark://$address:7077\" --conf \"spark.driver.host=$address\""
                + " --conf \"spark.authenticate.secret=$FLOCI_SPARK_SECRET\" --conf spark.ui.killEnabled=false"
                + " \"${@:$((options + 1))}\""));
        assertTrue(SparkExecutors.DRIVER_WRAPPER.contains("SPARK_MASTER_OPTS=\"-Dspark.authenticate.secret="
                + "$FLOCI_SPARK_SECRET -Dspark.master.rest.enabled=false -Dspark.ui.killEnabled=false\""));
        assertTrue(SparkExecutors.WORKER_SCRIPT.contains("--cores \"$FLOCI_EXECUTOR_CORES\" --memory"
                + " \"${FLOCI_EXECUTOR_MEMORY_MB}m\" --work-dir /tmp/floci-spark-work --host \"$address\""
                + " \"spark://$FLOCI_SPARK_MASTER:7077\""));
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    // How many 4-core 14G executors' workers a capacity holding such a driver's takes, of nine asked for.
    private static int taken(Executors limits) {
        Capacity capacity = new Capacity();
        capacity.hold(FOUR_CORES);
        int taken = 0;
        while (taken < 9 && capacity.take(FOUR_CORES, limits)) {
            taken++;
        }
        return taken;
    }

    /** A driver whose UI and master answer as a test sets them. */
    private static final class FakeDriver implements SparkExecutors.Driver {
        private String environment;
        private String metrics;
        private String master = "{\"workers\": [], \"activeapps\": []}";
        private boolean masterHasApplication;
        private int asked;

        void settings(Map<String, String> settings) {
            StringBuilder pairs = new StringBuilder();
            settings.forEach((key, value) -> pairs.append(pairs.isEmpty() ? "" : ",")
                    .append("[\"").append(key).append("\",\"").append(value).append("\"]"));
            environment = "{\"sparkProperties\": [" + pairs + "]}";
        }

        void target(int executors) {
            metrics = "{\"gauges\": {\"app-1.driver.ExecutorAllocationManager.executors.numberTargetExecutors\":"
                    + " {\"value\": " + executors + "}}}";
        }

        void coresUsed(Map<String, Integer> byHost) {
            StringBuilder workers = new StringBuilder();
            byHost.forEach((host, cores) -> workers.append(workers.isEmpty() ? "" : ",")
                    .append("{\"host\": \"").append(host).append("\", \"coresused\": ").append(cores).append("}"));
            master = "{\"workers\": [" + workers + "], \"activeapps\": [{\"id\": \"app-1\"}]}";
        }

        @Override
        public String get(int port, String path) {
            asked++;
            if (port == SparkExecutors.MASTER_UI_PORT) {
                return masterHasApplication ? "{\"workers\": [], \"activeapps\": [{\"id\": \"app-1\"}]}" : master;
            }
            if (environment == null) {
                return null;
            }
            return switch (path) {
                case "/api/v1/applications" -> "[{\"id\": \"app-1\"}]";
                case "/api/v1/applications/app-1/environment" -> environment;
                case "/metrics/json/" -> metrics;
                default -> null;
            };
        }
    }

    /** Executors' containers, each at 10.0.0.n for the nth, which run until a test ends them. */
    private static final class FakeContainers implements SparkExecutors.Containers {
        private final List<Integer> started = new ArrayList<>();
        private final List<Size> sizes = new ArrayList<>();
        private final List<String> removed = new ArrayList<>();
        private final Set<String> ended = new HashSet<>();
        private final Set<String> refused = new HashSet<>();
        private boolean failNextStart;

        @Override
        public Worker start(int index, Size size) {
            if (failNextStart) {
                failNextStart = false;
                throw new IllegalStateException("Docker refused to start it");
            }
            started.add(index);
            sizes.add(size);
            return new Worker("executor-" + index, "10.0.0." + index);
        }

        @Override
        public boolean running(String containerId) {
            return !ended.contains(containerId);
        }

        @Override
        public void remove(String containerId) {
            if (refused.contains(containerId)) {
                throw new IllegalStateException("Docker refused to remove it");
            }
            removed.add(containerId);
        }
    }
}
