package io.github.hectorvent.floci.services.emrserverless;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Runs a Spark application, or a Hive query, to its end, in the EMR services' {@code docker} runner mode: an
 * EMR Serverless job run, or a step of an EMR cluster. The service holds the run's state and calls this
 * off the request thread; {@link EmrServerlessSparkRunner} is the Docker implementation, and a test
 * supplies its own.
 */
public interface SparkJobRunner {

    /**
     * Runs {@code job} and returns how it ended, telling {@code progress} once its Spark driver runs and
     * when it passes its timeout. Blocks until then. A run {@code progress} reports as cancelled before
     * its driver runs never starts it.
     */
    Outcome run(Job job, Progress progress);

    /**
     * Stops the run's driver, now or as soon as it starts, so {@link #run} returns {@link Outcome#halted};
     * a run {@link #run} has already returned from is left as it is.
     */
    void stop(String runId);

    /**
     * Removes the drivers of {@code service}'s runs an earlier Floci process left running, which no thread
     * follows any more. The service calls it before any run of its own can start.
     */
    void removeLeftoverDrivers(String service);

    /** What a run tells the service, and asks it, while it goes. */
    interface Progress {

        /** The run's Spark driver runs. */
        void started();

        /** The run has passed its timeout, and its driver is about to be stopped. */
        void timedOut();

        /**
         * Whether the run was cancelled, or is gone, as after a reset: its driver must not start, and one
         * that runs is stopped.
         */
        boolean cancelled();
    }

    /** What a run's driver runs: Spark's {@code spark-submit}, or Hive's {@code hive}. */
    enum Engine {
        SPARK,
        HIVE
    }

    /**
     * What a run needs: the service it belongs to and its id there, which name its container; its account
     * and Region; its release; its engine; the image template and Docker network to run it in (a null template
     * is the release's public EMR Serverless image for the engine, a null network the global default);
     * {@code customImage}, when not null, the image the run's application names, which it runs in place of the
     * template's, resolved
     * again for each run; the execution role whose credentials it gets; the {@code spark-defaults} properties
     * that apply to it;
     * {@code application}, what follows Floci's own settings on {@code spark-submit}'s command line (the
     * run's options, then its application and that application's arguments), or on {@code hive}'s for a Hive run
     * (its settings, then its query files and parameters); or {@code command}, when not
     * null, what the container runs in place of {@code spark-submit}, where a {@code spark-submit} it starts
     * gets Floci's settings and the {@code spark-defaults} as defaults (its {@code application} is then
     * empty), or, for a Hive run, what runs in place of {@code hive}, followed by Floci's settings and the
     * {@code application} (an EMR step's {@code hive-script ... --args}); how long it may run
     * ({@link Duration#ZERO} for no limit); the S3 folder its driver's {@code stdout.gz} and
     * {@code stderr.gz} go to once it ends, ending in a slash (null for none); where in CloudWatch Logs
     * its driver's output goes while it runs (null for nowhere but Floci's log); the environment variables
     * its container gets besides Floci's own, which they replace where a name is the same (empty for none);
     * its {@code spark.archives}, each a path with, after a {@code #}, the directory it is unpacked to, of
     * which those in S3 are unpacked into the container's working directory before the driver starts (empty
     * for none); the storage of the cluster it runs on (null for none, as for an EMR Serverless run); the
     * {@code core-site.xml} properties its {@code hadoop} command gets, on a cluster, after those of the
     * cluster's storage, which they replace where a name is the same (empty for none); the
     * {@code log4j2.properties} lines its Spark gets after those of its configuration, which they replace where
     * a key is the same (empty for none); and, on a cluster, the {@code mapred-site.xml} properties its
     * MapReduce gets, the {@code log4j.properties} lines and the {@code hadoop-env.sh} exports its {@code hadoop}
     * command gets (each empty for none).
     */
    record Job(String service, String runId, String accountId, String region, String releaseLabel, Engine engine,
               String imageTemplate, String customImage, String dockerNetwork, String executionRole,
               Map<String, String> sparkDefaults, List<String> application, List<String> command, Duration timeout,
               String logFolder, CloudWatchLogs cloudWatchLogs, Map<String, String> environment,
               List<String> archives, Storage storage, Map<String, String> coreSite,
               Map<String, String> sparkLog4j2, Map<String, String> mapredSite, Map<String, String> hadoopLog4j,
               Map<String, String> hadoopEnvironment) {

        /** This job, on {@code storage}. */
        public Job onStorage(Storage storage) {
            return new Job(service, runId, accountId, region, releaseLabel, engine, imageTemplate, customImage,
                    dockerNetwork, executionRole, sparkDefaults, application, command, timeout, logFolder,
                    cloudWatchLogs, environment, archives, storage, coreSite, sparkLog4j2, mapredSite, hadoopLog4j,
                    hadoopEnvironment);
        }

        /** This job, run in {@code image}. */
        public Job inImage(String image) {
            return new Job(service, runId, accountId, region, releaseLabel, engine, image, customImage, dockerNetwork,
                    executionRole, sparkDefaults, application, command, timeout, logFolder, cloudWatchLogs, environment,
                    archives, storage, coreSite, sparkLog4j2, mapredSite, hadoopLog4j, hadoopEnvironment);
        }
    }

    /**
     * An EMR cluster's storage: the Docker volumes every one of its steps' containers mounts, {@code home} at
     * {@code /home/hadoop} and {@code mnt} at {@code /mnt}; and its HDFS, whose NameNode is on the cluster's
     * primary node, named {@code hdfsHost}, which a step's container reaches at {@code hdfsAddress} on the Docker
     * network {@code hdfsNetwork}, the cluster's own, which the container joins (all three null until it runs, the
     * network also on the host's own network, which steps share with it).
     */
    record Storage(String home, String mnt, String hdfsHost, String hdfsAddress, String hdfsNetwork) {

        /**
         * The component label of an EMR cluster's HDFS container, which carries its service's labels as a driver
         * does but is the cluster's storage's to keep or remove, not a driver.
         */
        public static final String HDFS_COMPONENT = "emr-hdfs";

        /** A cluster's volumes, before its HDFS runs. */
        public Storage(String home, String mnt) {
            this(home, mnt, null, null, null);
        }

        /** This storage, its HDFS's NameNode on {@code host}, at {@code address} on {@code network}. */
        public Storage withHdfs(String host, String address, String network) {
            return new Storage(home, mnt, host, address, network);
        }
    }

    /**
     * A log group in the run's account and Region, and the log streams the driver's standard output and
     * standard error go to in it, either null for that output to go nowhere but Floci's log.
     */
    record CloudWatchLogs(String logGroup, String stdoutStream, String stderrStream) {
    }

    /**
     * How a run ended: its driver's exit code, or that it ran out of time, was stopped, or could not
     * start ({@code failure} says why).
     */
    record Outcome(Integer exitCode, boolean timedOut, boolean stopped, String failure) {

        public static Outcome exitedWith(int exitCode) {
            return new Outcome(exitCode, false, false, null);
        }

        public static Outcome outOfTime() {
            return new Outcome(null, true, false, null);
        }

        public static Outcome halted() {
            return new Outcome(null, false, true, null);
        }

        public static Outcome couldNotStart(String failure) {
            return new Outcome(null, false, false, failure);
        }
    }
}
