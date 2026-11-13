package io.floci.aws.services.emrserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.emrserverless.SparkJobRunner.Executors;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An EMR Serverless Spark run's executors, each in a container of its own, as each has a worker of its own on EMR
 * Serverless, rather than in its driver's container in Spark's local mode. The driver's container runs Spark's
 * standalone master beside the driver, which {@link #DRIVER_WRAPPER} starts at the container's own address before it
 * hands over to {@code spark-submit}, whose master that is; an executor's container runs a standalone worker offering
 * one executor's cores and memory, {@link #WORKER_SCRIPT}, which the master gives the driver's next executor. The three
 * share a secret, as the image's {@code spark-defaults} turn on Spark's authentication, which the master and the
 * worker read too. Floci follows the driver, through its UI: an executor's size from its settings, once, and how many
 * executors Spark's dynamic allocation wants from its metrics, at each look, and starts containers, or removes idle
 * ones, to match, up to the run's limit and so that the workers of its application's runs, their drivers' among them,
 * stay within that application's maximum capacity ({@link Capacity}), as "no new resources will be created once any one
 * of the defined limits is hit".
 */
final class SparkExecutors {

    private static final Logger LOG = Logger.getLogger(SparkExecutors.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The variable the run's secret is in, in the driver's container and each executor's. */
    static final String SECRET_VARIABLE = "FLOCI_SPARK_SECRET";
    /** The driver container's address, where an executor's worker finds the master, in its container. */
    static final String MASTER_VARIABLE = "FLOCI_SPARK_MASTER";
    /** The cores and memory, in MiB, an executor's worker offers, in its container. */
    static final String CORES_VARIABLE = "FLOCI_EXECUTOR_CORES";
    static final String MEMORY_VARIABLE = "FLOCI_EXECUTOR_MEMORY_MB";
    /** The component label of an executor's container. */
    static final String COMPONENT = "emr-serverless-executor";
    /** The ports the driver's UI and the master's answer on, in the driver's container. */
    static final int DRIVER_UI_PORT = 4040;
    static final int MASTER_UI_PORT = 8080;
    private static final int MASTER_PORT = 7077;
    private static final String SPARK_CLASS = "/usr/lib/spark/bin/spark-class";
    // The image's spark-defaults turn authentication on, which the master and the worker read too, so each gets the
    // run's secret; Spark 4's master refuses it while its REST submission server is on, which nothing here uses; and
    // its UI, which Floci only reads, kills nothing, as any container on the network reaches it.
    private static final String MASTER_OPTIONS = "-Dspark.authenticate.secret=$" + SECRET_VARIABLE
            + " -Dspark.master.rest.enabled=false -Dspark.ui.killEnabled=false";

    /**
     * Runs before {@code spark-submit}, given as its first argument, then how many of the rest are its options
     * ({@link #optionCount}), the rest being its own: starts the master at the container's address, its log in /tmp,
     * waits for its port, then runs {@code spark-submit} with that master, the driver reached at that address, the
     * run's secret and its UI's kill links off, after the run's own options, so that none of theirs replaces them, and
     * before its primary resource. A master that exits first ends the run, its log on standard error.
     */
    static final String DRIVER_WRAPPER = String.join("\n",
            "address=$(hostname -i | awk '{print $1}')",
            "SPARK_MASTER_OPTS=\"" + MASTER_OPTIONS + "\" " + SPARK_CLASS + " org.apache.spark.deploy.master.Master"
                    + " --host \"$address\" --port " + MASTER_PORT + " --webui-port " + MASTER_UI_PORT
                    + " > /tmp/floci-spark-master.log 2>&1 &",
            "master=$!",
            "until (exec 3<>\"/dev/tcp/$address/" + MASTER_PORT + "\") 2>/dev/null; do",
            "  kill -0 $master 2>/dev/null || { cat /tmp/floci-spark-master.log >&2; exit 1; }",
            "  sleep 0.2",
            "done",
            "submit=$1; options=$2; shift 2",
            "exec \"$submit\" \"${@:1:$options}\" --master \"spark://$address:" + MASTER_PORT + "\""
                    + " --conf \"spark.driver.host=$address\" --conf \"spark.authenticate.secret=$" + SECRET_VARIABLE
                    + "\" --conf spark.ui.killEnabled=false \"${@:$((options + 1))}\"");

    // spark-submit's options that take a value, as its option parser (SparkSubmitOptionParser) names them; its other
    // options are switches, and its first argument that is no option is its primary resource.
    private static final Set<String> VALUE_OPTIONS = Set.of("--archives", "--class", "--conf", "-c", "--deploy-mode",
            "--driver-class-path", "--driver-cores", "--driver-java-options", "--driver-library-path", "--driver-memory",
            "--exclude-packages", "--executor-cores", "--executor-memory", "--files", "--jars", "--keytab", "--kill",
            "--master", "--name", "--num-executors", "--packages", "--principal", "--properties-file", "--proxy-user",
            "--py-files", "--queue", "--remote", "--repositories", "--status", "--total-executor-cores");

    /**
     * An executor's container: a standalone worker at the container's address, offering the cores and memory its
     * variables give, its executors' folders in /tmp, the image's own not being its user's.
     */
    static final String WORKER_SCRIPT = String.join("\n",
            "address=$(hostname -i | awk '{print $1}')",
            "SPARK_WORKER_OPTS=\"-Dspark.authenticate.secret=$" + SECRET_VARIABLE + "\" exec " + SPARK_CLASS
                    + " org.apache.spark.deploy.worker.Worker --cores \"$" + CORES_VARIABLE + "\""
                    + " --memory \"${" + MEMORY_VARIABLE + "}m\" --work-dir /tmp/floci-spark-work"
                    + " --host \"$address\" \"spark://$" + MASTER_VARIABLE + ":" + MASTER_PORT + "\"");

    // How often the driver is looked at, and how long its UI may stay silent once the master has its application
    // before executors start without its settings, both in real time rather than the clock Floci keeps.
    static final Duration LOOK_INTERVAL = Duration.ofSeconds(2);
    static final Duration UI_SILENCE = Duration.ofSeconds(30);
    // What a worker offers when the driver's UI never answers: enough for any one executor's settings. With the run's
    // settings unknown, it and the driver's worker count for nothing against the application's maximum capacity.
    static final Size BLIND_SIZE = new Size(32, 256 * 1024, Amount.NONE, Amount.NONE);
    // EMR Serverless's own defaults for a worker's disk, and Spark's for an executor's memory overhead.
    private static final long DEFAULT_DISK_GB = 20;
    private static final long MINIMUM_OVERHEAD_MIB = 384;
    private static final double DEFAULT_OVERHEAD_FACTOR = 0.10;
    // EMR Serverless's overhead factor for the driver's and the executors' workers alike.
    private static final String EMR_OVERHEAD_FACTOR = "spark.emr-serverless.memoryOverheadFactor";
    private static final Pattern SIZE = Pattern.compile("(\\d+)\\s*([kmgtp]?)b?", Pattern.CASE_INSENSITIVE);

    /** The driver's container, which answers a GET of a path on one of its ports, or null when nothing does. */
    interface Driver {
        String get(int port, String path);
    }

    /** The executors' containers. */
    interface Containers {

        /** Starts the {@code index}th executor's container, its worker offering {@code size}'s cores and memory. */
        Worker start(int index, Size size);

        /** Whether the container still runs. */
        boolean running(String containerId);

        /** Stops and removes it. */
        void remove(String containerId);
    }

    /** An executor's container and its address, which its worker registers with the master at. */
    record Worker(String containerId, String address) {
    }

    /**
     * What one executor's worker offers and holds, and what the driver's holds: the executor's cores and memory in MiB,
     * which the worker offers Spark, and what its worker and the driver's count toward the application's maximum
     * capacity.
     */
    record Size(int cores, long memoryMib, Amount executor, Amount driver) {
    }

    /** What a worker counts toward its application's maximum capacity: its vCPUs, and its memory and disk in GB. */
    record Amount(long vcpus, long memoryGb, long diskGb) {
        static final Amount NONE = new Amount(0, 0, 0);
    }

    /**
     * What the workers of an application's runs hold at once, which its runs share, so that together they stay within
     * its maximum capacity, as it "is cumulative across all workers at any given point in time"; a run's driver is held
     * once its size is known, whatever room is left, as it already runs.
     */
    static final class Capacity {
        private long vcpus;
        private long memoryGb;
        private long diskGb;
        private int runs;

        /** Takes {@code amount} when {@code limits} have room for it beside what is held, each limit 0 for none. */
        synchronized boolean take(Amount amount, Executors limits) {
            if (over(vcpus + amount.vcpus(), limits.vcpus()) || over(memoryGb + amount.memoryGb(), limits.memoryGb())
                    || over(diskGb + amount.diskGb(), limits.diskGb())) {
                return false;
            }
            hold(amount);
            return true;
        }

        /** Takes {@code amount} whatever room is left. */
        synchronized void hold(Amount amount) {
            vcpus += amount.vcpus();
            memoryGb += amount.memoryGb();
            diskGb += amount.diskGb();
        }

        /** Gives {@code amount} back. */
        synchronized void release(Amount amount) {
            vcpus -= amount.vcpus();
            memoryGb -= amount.memoryGb();
            diskGb -= amount.diskGb();
        }

        /** What is held, as vCPUs, memory and disk. */
        synchronized Amount held() {
            return new Amount(vcpus, memoryGb, diskGb);
        }

        /** One more run shares this capacity. */
        synchronized Capacity joined() {
            runs++;
            return this;
        }

        /** One run no longer shares it; true once none does. */
        synchronized boolean left() {
            return --runs <= 0;
        }

        private static boolean over(long held, long limit) {
            return limit > 0 && held > limit;
        }
    }

    private final Executors executors;
    private final Driver driver;
    private final Containers containers;
    private final Capacity capacity;
    private final LongSupplier nanoTime;
    private final List<Worker> workers = new ArrayList<>();
    // Containers that ended or were removed while Docker would not remove them, to try again at the run's end.
    private final List<String> leftovers = new ArrayList<>();
    private int nextIndex = 1;
    private boolean looked;
    private long nextLook;
    private Long applicationSeen;
    private Size size;
    // Read with the size: whether Spark's dynamic allocation is on, and, when it is not, how many executors it wants.
    private boolean dynamic = true;
    private int instances;

    SparkExecutors(Executors executors, Driver driver, Containers containers, Capacity capacity,
                   LongSupplier nanoTime) {
        this.executors = executors;
        this.driver = driver;
        this.containers = containers;
        this.capacity = capacity;
        this.nanoTime = nanoTime;
    }

    /** The executors' containers that run now. */
    List<Worker> workers() {
        return List.copyOf(workers);
    }

    /**
     * Looks at the driver, at most once a {@link #LOOK_INTERVAL}, and starts or removes executors' containers to match
     * what it wants, removing those that ended. A look that fails changes nothing more, and the next one tries again.
     */
    void follow() {
        long now = nanoTime.getAsLong();
        if (looked && now - nextLook < 0) {
            return;
        }
        looked = true;
        nextLook = now + LOOK_INTERVAL.toNanos();
        try {
            for (Iterator<Worker> each = workers.iterator(); each.hasNext(); ) {
                Worker worker = each.next();
                if (!containers.running(worker.containerId())) {
                    each.remove();
                    capacity.release(size.executor());
                    remove(worker.containerId());
                }
            }
            if (size == null) {
                size = readSize(now);
                if (size == null) {
                    return;
                }
                capacity.hold(size.driver());
            }
            Integer target = target();
            if (target == null) {
                return;
            }
            int wanted = Math.min(target, executors.containers());
            while (workers.size() < wanted && capacity.take(size.executor(), executors)) {
                Worker worker;
                try {
                    worker = containers.start(nextIndex++, size);
                } catch (RuntimeException e) {
                    capacity.release(size.executor());
                    throw e;
                }
                workers.add(worker);
            }
            if (workers.size() > wanted) {
                removeIdle(workers.size() - wanted);
            }
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not follow the executors of a Spark run");
        }
    }

    /** Removes every executor's container, and gives back what its workers held, as the run has ended. */
    void removeAll() {
        for (Worker worker : workers) {
            capacity.release(size.executor());
            remove(worker.containerId());
        }
        workers.clear();
        for (String containerId : List.copyOf(leftovers)) {
            leftovers.remove(containerId);
            remove(containerId);
        }
        if (size != null) {
            capacity.release(size.driver());
            size = null;
        }
    }

    /** How many of spark-submit's {@code arguments} are its options, before its primary resource and its arguments. */
    static int optionCount(List<String> arguments) {
        int count = 0;
        while (count < arguments.size() && arguments.get(count).startsWith("-")) {
            // "--conf=key=value" is one argument, "--conf key=value" two.
            count += VALUE_OPTIONS.contains(arguments.get(count)) ? 2 : 1;
        }
        return Math.min(count, arguments.size());
    }

    // Removes an executor's container; one Docker will not remove yet is tried again at the run's end.
    private void remove(String containerId) {
        try {
            containers.remove(containerId);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not remove executor container {0}", containerId);
            if (!leftovers.contains(containerId)) {
                leftovers.add(containerId);
            }
        }
    }

    // An executor's size, from the driver's settings once its UI answers; or, once the master has had the driver's
    // application for UI_SILENCE without its UI answering, as a job that turned it off would have it, BLIND_SIZE.
    private Size readSize(long now) {
        String applications = driver.get(DRIVER_UI_PORT, "/api/v1/applications");
        if (applications == null) {
            if (applicationSeen == null && masterHasApplication()) {
                applicationSeen = now;
            }
            if (applicationSeen != null && now - applicationSeen >= UI_SILENCE.toNanos()) {
                LOG.warnv("The driver's UI of a Spark run does not answer, so its executors start without its"
                        + " settings; spark.ui.enabled=false keeps Floci from sizing them");
                dynamic = false;
                instances = 1;
                return BLIND_SIZE;
            }
            return null;
        }
        JsonNode list = parse(applications);
        if (list == null || !list.isArray() || list.isEmpty()) {
            return null;
        }
        String environment = driver.get(DRIVER_UI_PORT,
                "/api/v1/applications/" + list.get(0).path("id").asText() + "/environment");
        JsonNode properties = environment != null ? parse(environment) : null;
        if (properties == null) {
            return null;
        }
        Map<String, String> settings = new HashMap<>();
        for (JsonNode pair : properties.path("sparkProperties")) {
            settings.put(pair.path(0).asText(), pair.path(1).asText());
        }
        dynamic = Boolean.parseBoolean(settings.getOrDefault("spark.dynamicAllocation.enabled", "false"));
        instances = (int) Math.max(0, number(settings.get("spark.executor.instances"), 2));
        return sizeOf(settings);
    }

    /** What one executor's worker offers and holds, and the driver's, from a run's Spark settings. */
    static Size sizeOf(Map<String, String> settings) {
        int cores = (int) Math.max(1, number(settings.get("spark.executor.cores"), 1));
        long memory = mebibytes(settings.get("spark.executor.memory"), 1024);
        long driverMemory = mebibytes(settings.get("spark.driver.memory"), 1024);
        return new Size(cores, memory,
                new Amount(cores, workerGb(memory, settings, "spark.executor"),
                        gigabytes(settings.get("spark.emr-serverless.executor.disk"))),
                new Amount(Math.max(1, number(settings.get("spark.driver.cores"), 1)),
                        workerGb(driverMemory, settings, "spark.driver"),
                        gigabytes(settings.get("spark.emr-serverless.driver.disk"))));
    }

    // A worker's memory in whole GB: the JVM's heap and its overhead, rounded up, as worker memory comes in steps of
    // 1 GB. The overhead is the role's memoryOverhead when set, else its share of the heap, by the role's
    // memoryOverheadFactor, else EMR Serverless's, else 10%, and at least 384 MB, as "The overhead has a default
    // value of 10% of container memory, with a minimum of 384 MB".
    private static long workerGb(long heapMib, Map<String, String> settings, String role) {
        String overhead = settings.get(role + ".memoryOverhead");
        String factor = settings.getOrDefault(role + ".memoryOverheadFactor", settings.get(EMR_OVERHEAD_FACTOR));
        long overheadMib = overhead != null ? mebibytes(overhead, MINIMUM_OVERHEAD_MIB)
                : Math.max(MINIMUM_OVERHEAD_MIB, (long) (heapMib * fraction(factor)));
        return (heapMib + overheadMib + 1023) / 1024;
    }

    private static double fraction(String value) {
        try {
            return value != null ? Double.parseDouble(value.trim()) : DEFAULT_OVERHEAD_FACTOR;
        } catch (NumberFormatException e) {
            return DEFAULT_OVERHEAD_FACTOR;
        }
    }

    // How many executors Spark wants: dynamic allocation's target, from the driver's metrics, or, without it, its
    // executor instances.
    private Integer target() {
        if (!dynamic) {
            return instances;
        }
        String metrics = driver.get(DRIVER_UI_PORT, "/metrics/json/");
        JsonNode gauges = metrics != null ? parse(metrics) : null;
        if (gauges == null) {
            return null;
        }
        for (Map.Entry<String, JsonNode> gauge : gauges.path("gauges").properties()) {
            if (gauge.getKey().endsWith("ExecutorAllocationManager.executors.numberTargetExecutors")) {
                return gauge.getValue().path("value").asInt();
            }
        }
        return null;
    }

    private boolean masterHasApplication() {
        String state = driver.get(MASTER_UI_PORT, "/json/");
        JsonNode master = state != null ? parse(state) : null;
        return master != null && !master.path("activeapps").isEmpty();
    }

    // Removes up to count executors' containers whose worker runs no executor, newest first, as the master tells.
    private void removeIdle(int count) {
        String state = driver.get(MASTER_UI_PORT, "/json/");
        JsonNode master = state != null ? parse(state) : null;
        if (master == null) {
            return;
        }
        Map<String, Integer> coresUsed = new HashMap<>();
        for (JsonNode worker : master.path("workers")) {
            coresUsed.put(worker.path("host").asText(), worker.path("coresused").asInt());
        }
        for (int i = workers.size() - 1; i >= 0 && count > 0; i--) {
            Worker worker = workers.get(i);
            if (Integer.valueOf(0).equals(coresUsed.get(worker.address()))) {
                containers.remove(worker.containerId());
                workers.remove(i);
                capacity.release(size.executor());
                count--;
            }
        }
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            return null;
        }
    }

    private static long number(String value, long otherwise) {
        if (value == null) {
            return otherwise;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    // A size as Spark takes it, "14G" or "1024m", in MiB, a bare number being MiB, as spark.executor.memory has it.
    static long mebibytes(String value, long otherwise) {
        Matcher size = value != null ? SIZE.matcher(value.trim()) : null;
        if (size == null || !size.matches()) {
            return otherwise;
        }
        long amount = Long.parseLong(size.group(1));
        return switch (size.group(2).toLowerCase(Locale.ROOT)) {
            case "k" -> Math.max(1, amount / 1024);
            case "g" -> amount * 1024;
            case "t" -> amount * 1024 * 1024;
            case "p" -> amount * 1024 * 1024 * 1024;
            default -> amount;
        };
    }

    // A disk size in GB, as "20G" gives it, EMR Serverless's 20 GB when not set.
    static long gigabytes(String value) {
        return value != null ? Math.max(1, (mebibytes(value, DEFAULT_DISK_GB * 1024) + 1023) / 1024) : DEFAULT_DISK_GB;
    }
}
