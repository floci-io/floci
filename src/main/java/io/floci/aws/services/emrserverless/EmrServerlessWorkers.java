package io.floci.aws.services.emrserverless;

import io.floci.aws.services.emrserverless.model.JobRun;
import io.floci.aws.services.emrserverless.model.RunWorkers;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The workers a Spark job run asks for, sized as EMR Serverless sizes them, and the resources they use while it runs
 * (EMR Serverless User Guide, "Supported worker configurations" and "Spark job properties").
 *
 * <p>A worker has the vCPUs its {@code cores} ask for, "1, 2, 4, 8, 16, or 32 vCPUs", and memory for its Spark memory
 * and that memory's overhead, "10% of container memory, with a minimum of 384 MB", rounded up to the sizes its vCPUs
 * allow, read as {@link SparkExecutors} reads them for an application's maximum capacity. Its disk is 20 GB to
 * 200 GB. Unset, the cores are 4, the memory "For workers smaller than 32 vCPUs, 14G" and "For 32 vCPUs, 109G", the
 * disk 20G, and the executors 3. With dynamic allocation on, as it is by default, a run starts with
 * {@code spark.dynamicAllocation.initialExecutors}, or {@code spark.executor.instances} where that is larger, as
 * Spark's own configuration reads, and no fewer than {@code minExecutors}.
 */
final class EmrServerlessWorkers {

    private static final List<Integer> VCPUS = List.of(1, 2, 4, 8, 16, 32);
    // The memory sizes of a 32-vCPU worker: "Three discrete values: 60 GB, 120 GB, or 244 GB".
    private static final List<Long> LARGEST_WORKER_MEMORY = List.of(60L, 120L, 244L);
    private static final int DEFAULT_CORES = 4;
    private static final long DEFAULT_MEMORY_MIB = 14 * 1024;
    private static final long LARGEST_WORKER_DEFAULT_MEMORY_MIB = 109 * 1024;
    private static final int DEFAULT_EXECUTORS = 3;
    private static final long MIN_DISK_GB = 20;
    private static final long MAX_DISK_GB = 200;
    // "You only pay for additional storage beyond 20 GB that you configure per worker."
    private static final long INCLUDED_DISK_GB = 20;
    // "The billed resources include a 1-minute minimum usage for workers" (the API reference's JobRun).
    private static final long MINIMUM_BILLED_MILLIS = 60_000;
    private static final double SECONDS_PER_HOUR = 3600;
    /**
     * spark-submit's options that size a run, and the property each sets, which "--conf" cannot override: Spark's
     * option parser takes them over a property of the same setting.
     */
    private static final Map<String, String> SIZE_OPTIONS = Map.of("--driver-cores", "spark.driver.cores",
            "--driver-memory", "spark.driver.memory", "--executor-cores", "spark.executor.cores",
            "--executor-memory", "spark.executor.memory", "--num-executors", "spark.executor.instances");

    private EmrServerlessWorkers() {
    }

    /**
     * The workers of a Spark run whose properties, its {@code spark-defaults} and {@code --conf} settings, these are,
     * with what its {@code sparkSubmitParameters}' sizing options set over them.
     */
    static RunWorkers plan(Map<String, String> properties, String sparkSubmitParameters) {
        Map<String, String> settings = new HashMap<>(properties);
        List<String> parameters = EmrServerlessSparkRunner.splitParameters(sparkSubmitParameters);
        for (int i = 0; i < parameters.size(); i++) {
            String parameter = parameters.get(i);
            int equals = parameter.indexOf('=');
            String option = equals > 0 ? parameter.substring(0, equals) : parameter;
            String property = SIZE_OPTIONS.get(option);
            if (property != null && equals > 0) {
                settings.put(property, parameter.substring(equals + 1));
            } else if (property != null && i + 1 < parameters.size()) {
                settings.put(property, parameters.get(++i));
            }
        }
        int driverVcpu = vcpus(settings.get("spark.driver.cores"));
        int executorVcpu = vcpus(settings.get("spark.executor.cores"));
        return new RunWorkers(driverVcpu, memoryGb(settings, "spark.driver", driverVcpu),
                diskGb(settings.get("spark.emr-serverless.driver.disk")), executorVcpu,
                memoryGb(settings, "spark.executor", executorVcpu),
                diskGb(settings.get("spark.emr-serverless.executor.disk")), executors(settings));
    }

    /**
     * Sets an ended attempt's resource utilization from its {@code workers}: "The aggregate vCPU, memory, and storage
     * resources used from the time the job starts to execute, until the time the job terminates, rounded up to the
     * nearest second", and what was billed for them, which includes "a 1-minute minimum usage for workers, plus
     * additional storage over 20 GB per worker". Its driver's worker runs for the whole attempt. Its executors' run
     * for {@code executorLifetimes}, each worker's from its start to its end, where its executors had containers that
     * came and went with Spark's target; else the executors it starts with run for the whole attempt. An attempt
     * that never started used none. Nothing is set without workers, as for a Hive run.
     */
    static void account(JobRun run, RunWorkers workers, List<Long> executorLifetimes) {
        if (workers == null) {
            return;
        }
        boolean started = run.getStartedAt() != null && run.getEndedAt() != null;
        long millis = started ? Duration.between(run.getStartedAt(), run.getEndedAt()).toMillis() : 0;
        double driverSeconds = seconds(millis);
        double driverBilled = started ? seconds(Math.max(millis, MINIMUM_BILLED_MILLIS)) : 0;
        double executorSeconds;
        double executorBilled;
        if (executorLifetimes != null) {
            executorSeconds = seconds(executorLifetimes.stream().mapToLong(Long::longValue).sum());
            executorBilled = seconds(executorLifetimes.stream()
                    .mapToLong(lifetime -> Math.max(lifetime, MINIMUM_BILLED_MILLIS)).sum());
        } else {
            executorSeconds = (double) workers.getExecutors() * driverSeconds;
            executorBilled = (double) workers.getExecutors() * driverBilled;
        }
        run.setTotalResourceUtilization(utilization(workers, driverSeconds, executorSeconds,
                workers.getDriverDiskGb(), workers.getExecutorDiskGb()));
        run.setBilledResourceUtilization(utilization(workers, driverBilled, executorBilled,
                Math.max(0, workers.getDriverDiskGb() - INCLUDED_DISK_GB),
                Math.max(0, workers.getExecutorDiskGb() - INCLUDED_DISK_GB)));
    }

    // Milliseconds rounded up to whole seconds.
    private static double seconds(long millis) {
        return Math.ceilDiv(millis, 1000);
    }

    private static Map<String, Double> utilization(RunWorkers workers, double driverSeconds, double executorSeconds,
                                                   long driverDiskGb, long executorDiskGb) {
        Map<String, Double> utilization = new LinkedHashMap<>();
        utilization.put("vCPUHour", hours(workers.getDriverVcpu(), driverSeconds, workers.getExecutorVcpu(),
                executorSeconds));
        utilization.put("memoryGBHour", hours(workers.getDriverMemoryGb(), driverSeconds,
                workers.getExecutorMemoryGb(), executorSeconds));
        utilization.put("storageGBHour", hours(driverDiskGb, driverSeconds, executorDiskGb, executorSeconds));
        return utilization;
    }

    private static double hours(long driverAmount, double driverSeconds, long executorAmount, double executorSeconds) {
        return (driverAmount * driverSeconds + executorAmount * executorSeconds) / SECONDS_PER_HOUR;
    }

    // The fewest vCPUs a worker can have for its cores, at most 32.
    private static int vcpus(String cores) {
        int asked = whole(cores, DEFAULT_CORES);
        return VCPUS.stream().filter(vcpu -> vcpu >= asked).findFirst().orElse(VCPUS.getLast());
    }

    /**
     * A worker's memory in GB: its Spark memory and that memory's overhead, rounded up to the sizes its vCPUs allow
     * ("Minimum 8 GB, maximum 30 GB, in 1 GB increments" for 4 vCPUs, say).
     */
    private static int memoryGb(Map<String, String> settings, String role, int vcpu) {
        long heap = SparkExecutors.mebibytes(settings.get(role + ".memory"),
                vcpu == VCPUS.getLast() ? LARGEST_WORKER_DEFAULT_MEMORY_MIB : DEFAULT_MEMORY_MIB);
        long needed = SparkExecutors.workerGb(heap, settings, role);
        if (vcpu == VCPUS.getLast()) {
            return LARGEST_WORKER_MEMORY.stream().filter(size -> size >= needed).findFirst()
                    .orElse(LARGEST_WORKER_MEMORY.getLast()).intValue();
        }
        long min = 2L * vcpu;
        long max = switch (vcpu) {
            case 1 -> 8;
            case 2 -> 16;
            case 4 -> 30;
            case 8 -> 60;
            default -> 120;
        };
        long step = vcpu <= 4 ? 1 : vcpu / 2;
        long steps = Math.ceilDiv(Math.max(0, Math.min(needed, max) - min), step);
        return (int) Math.min(max, min + steps * step);
    }

    // A worker's disk in GB, 20 to 200.
    private static int diskGb(String disk) {
        return (int) Math.max(MIN_DISK_GB, Math.min(MAX_DISK_GB, SparkExecutors.gigabytes(disk)));
    }

    /**
     * The executors a run starts with: {@code spark.executor.instances} without dynamic allocation; with it, its
     * {@code initialExecutors}, "If --num-executors (or spark.executor.instances) is set and larger than this value,
     * it will be used as the initial number of executors" (Spark's configuration), no fewer than {@code minExecutors}
     * and no more than {@code maxExecutors}.
     */
    private static int executors(Map<String, String> settings) {
        int instances = whole(settings.get("spark.executor.instances"), DEFAULT_EXECUTORS);
        if ("false".equalsIgnoreCase(settings.get("spark.dynamicAllocation.enabled"))) {
            return instances;
        }
        int initial = Math.max(whole(settings.get("spark.dynamicAllocation.initialExecutors"), DEFAULT_EXECUTORS),
                whole(settings.get("spark.dynamicAllocation.minExecutors"), 0));
        if (settings.containsKey("spark.executor.instances")) {
            initial = Math.max(initial, instances);
        }
        return Math.min(initial, whole(settings.get("spark.dynamicAllocation.maxExecutors"), initial));
    }

    private static int whole(String value, int fallback) {
        try {
            return value != null ? Math.max(0, Integer.parseInt(value.trim())) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
