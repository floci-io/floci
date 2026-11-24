package io.floci.aws.services.emrserverless;

import io.floci.aws.services.emrserverless.model.JobRun;
import io.floci.aws.services.emrserverless.model.RunWorkers;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EmrServerlessWorkersTest {

    private static final Instant STARTED = Instant.parse("2026-11-22T00:00:00Z");

    private static List<Integer> sizes(RunWorkers workers) {
        return List.of(workers.getDriverVcpu(), workers.getDriverMemoryGb(), workers.getDriverDiskGb(),
                workers.getExecutorVcpu(), workers.getExecutorMemoryGb(), workers.getExecutorDiskGb(),
                workers.getExecutors());
    }

    private static JobRun ran(long millis) {
        JobRun run = new JobRun();
        run.setStartedAt(STARTED);
        run.setEndedAt(STARTED.plusMillis(millis));
        return run;
    }

    @Test
    void anUnsetRunHasADefaultDriverAndThreeDefaultExecutors() {
        // 14G of Spark memory and its 10% overhead come to 15.4 GB, which a 4-vCPU worker rounds up to 16.
        assertEquals(List.of(4, 16, 20, 4, 16, 20, 3), sizes(EmrServerlessWorkers.plan(Map.of(), null)));
    }

    @Test
    void eachWorkerIsSizedToTheMemoryItsVcpusAllow() {
        RunWorkers workers = EmrServerlessWorkers.plan(Map.of(
                "spark.driver.cores", "2", "spark.driver.memory", "4g",
                "spark.executor.cores", "8", "spark.executor.memory", "20480m",
                "spark.emr-serverless.executor.disk", "50G"), null);

        // 4 GB and 384 MB of overhead round up to 5 GB; 20 GB and 2 GB to 24 GB, in 8 vCPUs' 4 GB steps.
        assertEquals(List.of(2, 5, 20, 8, 24, 50, 3), sizes(workers));
        assertEquals(List.of(4, 12, 20, 32, 120, 200, 3), sizes(EmrServerlessWorkers.plan(Map.of(
                "spark.driver.cores", "3", "spark.driver.memory", "8g", "spark.driver.memoryOverhead", "4096",
                "spark.executor.cores", "32", "spark.emr-serverless.executor.disk", "500G"), null)));
    }

    @Test
    void theExecutorsARunStartsWithFollowItsDynamicAllocation() {
        assertEquals(5, EmrServerlessWorkers.plan(Map.of("spark.dynamicAllocation.enabled", "false",
                "spark.executor.instances", "5"), null).getExecutors());
        assertEquals(6, EmrServerlessWorkers.plan(Map.of("spark.executor.instances", "6"), null).getExecutors());
        assertEquals(2, EmrServerlessWorkers.plan(Map.of("spark.dynamicAllocation.initialExecutors", "1",
                "spark.dynamicAllocation.minExecutors", "2"), null).getExecutors());
        assertEquals(1, EmrServerlessWorkers.plan(Map.of("spark.dynamicAllocation.maxExecutors", "1"), null)
                .getExecutors());
    }

    @Test
    void anAttemptUsesItsWorkersForItsSecondsAndIsBilledAMinuteAtLeastAndStorageOver20Gb() {
        RunWorkers defaults = EmrServerlessWorkers.plan(Map.of(), null);
        JobRun ninetySeconds = ran(90_000);
        EmrServerlessWorkers.account(ninetySeconds, defaults, null);

        // 16 vCPUs, 64 GB and 80 GB of disk for 90 seconds; none of the disk is over 20 GB a worker.
        assertEquals(Map.of("vCPUHour", 0.4, "memoryGBHour", 1.6, "storageGBHour", 2.0),
                ninetySeconds.getTotalResourceUtilization());
        assertEquals(Map.of("vCPUHour", 0.4, "memoryGBHour", 1.6, "storageGBHour", 0.0),
                ninetySeconds.getBilledResourceUtilization());

        RunWorkers bigDisks = EmrServerlessWorkers.plan(Map.of("spark.emr-serverless.executor.disk", "50g"), null);
        JobRun brief = ran(35_400);
        EmrServerlessWorkers.account(brief, bigDisks, null);

        // Rounded up to 36 seconds; billed for 60, and for 30 GB of each executor's disk.
        assertEquals(16 * 36 / 3600.0, brief.getTotalResourceUtilization().get("vCPUHour"));
        assertEquals(170 * 36 / 3600.0, brief.getTotalResourceUtilization().get("storageGBHour"));
        assertEquals(16 * 60 / 3600.0, brief.getBilledResourceUtilization().get("vCPUHour"));
        assertEquals(90 * 60 / 3600.0, brief.getBilledResourceUtilization().get("storageGBHour"));
    }

    @Test
    void anAttemptThatNeverStartedUsedNothingAndOneWithoutWorkersGivesNone() {
        JobRun neverStarted = new JobRun();
        neverStarted.setEndedAt(STARTED);
        EmrServerlessWorkers.account(neverStarted, EmrServerlessWorkers.plan(Map.of(), null), null);
        assertEquals(Map.of("vCPUHour", 0.0, "memoryGBHour", 0.0, "storageGBHour", 0.0),
                neverStarted.getBilledResourceUtilization());

        JobRun hive = ran(60_000);
        EmrServerlessWorkers.account(hive, null, null);
        assertNull(hive.getTotalResourceUtilization());
        assertNull(hive.getBilledResourceUtilization());
    }

    @Test
    void sparkSubmitsSizingOptionsWinOverTheSettingsTheySet() {
        // The properties as spark-defaults and --conf give them, which the options then set over.
        RunWorkers workers = EmrServerlessWorkers.plan(Map.of("spark.executor.memory", "4g",
                "spark.executor.cores", "2", "spark.dynamicAllocation.enabled", "false"),
                "--driver-cores 8 --driver-memory=20g --executor-memory 8g --num-executors=5 --class example.Main");

        assertEquals(List.of(8, 24, 20, 2, 9, 20, 5), sizes(workers));
    }

    @Test
    void settingsTooLargeForTheirTypesGiveTheLargestWorkersAndNoNegativeUse() {
        RunWorkers huge = EmrServerlessWorkers.plan(Map.of("spark.dynamicAllocation.enabled", "false",
                "spark.executor.instances", String.valueOf(Integer.MAX_VALUE),
                "spark.executor.memory", "99999999999999999999g", "spark.driver.memory", "9007199254740993p"), null);
        JobRun run = ran(3_600_000);
        EmrServerlessWorkers.account(run, huge, null);

        // The memory that does not read as a size takes the default; the executors are what was asked for.
        assertEquals(List.of(4, 16, 20, 4, 16, 20, Integer.MAX_VALUE), sizes(huge));
        assertEquals(4.0 + 4.0 * Integer.MAX_VALUE, run.getTotalResourceUtilization().get("vCPUHour"));
    }

    @Test
    void executorsWhoseContainersCameAndWentAreCountedForTheTimeEachRan() {
        RunWorkers defaults = EmrServerlessWorkers.plan(Map.of(), null);
        JobRun run = ran(100_000);
        EmrServerlessWorkers.account(run, defaults, List.of(30_000L, 90_500L));

        // The driver for 100 seconds and the executors for 121 between them; billed a minute at least each.
        assertEquals((4 * 100 + 4 * 121) / 3600.0, run.getTotalResourceUtilization().get("vCPUHour"));
        assertEquals((4 * 100 + 4 * 151) / 3600.0, run.getBilledResourceUtilization().get("vCPUHour"));
    }
}
