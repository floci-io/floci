package io.floci.aws.services.emr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.emr.model.EmrCluster;
import io.floci.aws.services.emr.model.EmrStep;
import io.floci.aws.services.emrserverless.EmrServerlessSparkRunner;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a step runs in the {@code docker} runner mode, in a container of the release's EMR Serverless image.
 * A Spark step runs through {@code spark-submit} in local mode: a {@code command-runner.jar} step running
 * {@code spark-submit}, as the Management Guide's Spark steps are written, and a custom JAR step, whose main
 * class {@code spark-submit} runs with the JAR on its class path, a MapReduce job's in local mode. Any other
 * {@code command-runner.jar} step runs its command, and a {@code script-runner.jar} step its script, as the
 * Release Guide's "Run commands and scripts on an Amazon EMR cluster" describes them, a
 * {@code hadoop-streaming} or {@code hadoop-lzo} one through the image's {@code hadoop}, with MapReduce in local
 * mode; an {@code s3-dist-cp} one runs Floci's own S3DistCp. On a cluster with Hive, whose image holds Hive too, a
 * {@code command-runner.jar} step running {@code hive-script}, as the AWS CLI writes a Hive step, or {@code hive}
 * runs Hive. One running {@code pig-script}, as the AWS CLI writes a Pig step, runs EMR's, which runs the
 * {@code pig} Floci adds to a cluster with Pig, with Pig's configuration of its own ({@link #pigEnvironment}).
 */
final class EmrSparkSteps {

    // No source gives AWS's wording for these; they are Floci's.
    static final String NOT_RUN = "Floci does not run this step: Pig is not in the image steps run in."
            + " floci.services.emr.pig.enabled adds Apache Pig to it.";
    static final String INTERRUPTED = "Floci stopped while the step was running.";

    private static final String COMMAND_RUNNER = "command-runner.jar";
    private static final String SCRIPT_RUNNER = "script-runner.jar";
    private static final String SPARK_SUBMIT = "spark-submit";
    // Local mode is Floci's, so a step's own master and deploy mode are dropped, unless its cluster runs YARN.
    private static final Set<String> DROPPED_OPTIONS = Set.of("--master", "--deploy-mode");
    // spark-submit's options that take no value (SparkSubmitOptionParser's switches); every other takes one.
    private static final Set<String> SWITCHES = Set.of("--help", "-h", "--supervise", "--usage-error", "--verbose",
            "-v", "--version");
    private static final String SPARK_DEFAULTS = "spark-defaults";
    // The Release Guide's Spark classifications: spark-env "Sets values in the spark-env.sh file", through its
    // export classification ("If any classification ends in env, use the export sub-classification"), and
    // spark-hive-site "Sets values in the hive-site.xml for Spark".
    private static final String SPARK_ENV = "spark-env";
    private static final String EXPORT = "export";
    private static final String SPARK_HIVE_SITE = "spark-hive-site";
    // The release pages' core-site, which will "Change values in Hadoop's core-site.xml file", and spark-log4j2,
    // which will "Change values in Spark's log4j2.properties file".
    private static final String CORE_SITE = "core-site";
    private static final String HDFS_SITE = "hdfs-site";
    private static final String SPARK_LOG4J2 = "spark-log4j2";
    // And mapred-site, which will "Change values in the MapReduce application's mapred-site.xml file", hadoop-log4j
    // "Change values in Hadoop's log4j.properties file", and hadoop-env "Change values in the Hadoop environment for
    // all Hadoop components", through its export classification.
    private static final String MAPRED_SITE = "mapred-site";
    private static final String HADOOP_LOG4J = "hadoop-log4j";
    private static final String HADOOP_ENV = "hadoop-env";
    // Spark puts a spark.hadoop. setting in its Hadoop configuration, ahead of core-site.xml and hive-site.xml.
    private static final String SPARK_HADOOP_PREFIX = "spark.hadoop.";
    // spark-submit gives the JVM it runs an application's main class in the Java options of this variable.
    private static final String SPARK_SUBMIT_OPTS = "SPARK_SUBMIT_OPTS";
    // The AWS CLI's Pig step on a release is command-runner.jar running "pig-script --run-pig-script --args" and the
    // step's arguments; the image has EMR's pig-script, which runs pig with them, or says "Pig isn't installed.", as
    // the image has no pig unless Floci adds it.
    private static final String PIG_SCRIPT = "pig-script";
    private static final String PIG = "pig";
    // The Release Guide's Pig classifications: pig-properties will "Change values in Pig's pig.properties file",
    // pig-log4j "Change values in Pig's log4j.properties file", and pig-env "Change values in the Pig environment",
    // through its export classification. Pig 0.16.0 and later "use Apache Tez", the Release Guide's Pig considerations
    // say: "You can explicitly set exectype=mapreduce in the pig-properties configuration classification to use
    // MapReduce." A cluster's tez-site is Tez's, which Pig takes as it does a Hadoop property, ahead of pig-properties.
    private static final String PIG_PROPERTIES = "pig-properties";
    private static final String PIG_LOG4J = "pig-log4j";
    private static final String PIG_ENV = "pig-env";
    private static final String TEZ_SITE = "tez-site";
    private static final String PIG_EXECTYPE = "exectype";
    private static final String PIG_TEZ = "tez";
    // Pig's setting for the log4j.properties it reads, which then is the one the cluster's pig-log4j changes.
    private static final String PIG_LOG4J_CONF = "log4jconf";
    // Pig sets the file system of each of a script's inputs to the job's working directory, the default file system's
    // home of the user it runs as, which S3A, Floci's s3://, refuses when it names another file system. Named without
    // one, it is the same folder in HDFS, and Hadoop fills in the user.
    private static final String WORKING_DIR = "mapreduce.job.working.dir";
    private static final String USER_HOME = "/user/${user.name}";
    // The AWS CLI's Hive step on a release is command-runner.jar running "hive-script --run-hive-script --args"
    // and the step's arguments, the ones the image's hive-script runs hive with; a step can also run hive itself.
    private static final String HIVE_SCRIPT = "hive-script";
    private static final String HIVE_SCRIPT_ARGS = "--args";
    private static final String HIVE = "hive";
    private static final String HCATALOG = "hcatalog";
    // The Hive classifications a Hive step's hive gets, each property a --hiveconf, in the order Hive reads their
    // files, a later one winning: core-site, tez-site, which will "Change values in Tez's tez-site.xml file", and
    // hive-site, which will "Change values in Hive's hive-site.xml"; and hive-env's exports, which will "Change
    // values in the Hive environment".
    private static final String HIVE_SITE = "hive-site";
    private static final List<String> HIVE_CLASSIFICATIONS = List.of(CORE_SITE, TEZ_SITE, HIVE_SITE);
    private static final String HIVE_ENV = "hive-env";
    // EMR's MapReduce jars are in /usr/lib/hadoop-mapreduce, as the image's own hadoop-streaming script has it,
    // which the image does not have; its streaming jar is in /usr/lib/hadoop.
    private static final Map<String, String> IMAGE_JARS = Map.of(
            "/usr/lib/hadoop-mapreduce/hadoop-streaming.jar", "/usr/lib/hadoop/hadoop-streaming.jar");
    private static final String S3_DIST_CP = "s3-dist-cp";
    static final String S3_DIST_CP_RESOURCE = "emr/s3-dist-cp.py";
    // The line that ends the program where the command writes it out.
    static final String S3_DIST_CP_END = "FLOCI_S3_DIST_CP";
    // script-runner.jar fetches its script from S3 with the step's credentials, the image's AWS CLI reaching
    // Floci's S3, and runs it with its arguments; a path that is not in S3 is run as it is, a bare name from
    // the working directory when a file there has it, as exec would otherwise look for it on the PATH only.
    private static final String SCRIPT_RUNNER_COMMAND = String.join("\n",
            "script=$1",
            "shift",
            "case \"$script\" in",
            "  s3://*|s3n://*|s3a://*)",
            "    mkdir -p /tmp/floci-script-runner",
            "    local_copy=\"/tmp/floci-script-runner/${script##*/}\"",
            "    aws s3 cp --only-show-errors \"s3://${script#*://}\" \"$local_copy\" || exit $?",
            "    script=$local_copy",
            "    ;;",
            "  */*)",
            "    ;;",
            "  *)",
            "    if [ -f \"$script\" ]; then script=\"./$script\"; fi",
            "    ;;",
            "esac",
            "chmod u+x \"$script\"",
            "exec \"$script\" \"$@\"");

    private EmrSparkSteps() {
    }

    /**
     * What follows Floci's own settings on {@code spark-submit}'s command line for {@code step}, or null for
     * a step Floci does not run. For {@code command-runner.jar spark-submit ...}, the arguments after
     * {@code spark-submit}, without its {@code --master} and {@code --deploy-mode} unless its cluster runs YARN
     * ({@code onYarn}), where they hold as on EMR: options come before the application, so one after it is the
     * application's own and kept. For a custom JAR, its main class, if
     * the step names one (otherwise {@code spark-submit} reads the JAR's manifest), the JAR and the step's
     * arguments. A JAR EMR has in {@code /usr/lib/hadoop-mapreduce} is the image's copy of it.
     */
    static List<String> application(EmrStep step, boolean onYarn) {
        String jar = step.getJar();
        if (jar == null || jar.isBlank() || named(jar, SCRIPT_RUNNER)) {
            return null;
        }
        List<String> args = step.getArgs() != null ? step.getArgs() : List.of();
        if (named(jar, COMMAND_RUNNER)) {
            return !args.isEmpty() && named(args.getFirst(), SPARK_SUBMIT)
                    ? onYarn ? List.copyOf(args.subList(1, args.size()))
                    : withoutMasterOrDeployMode(args.subList(1, args.size())) : null;
        }
        List<String> application = new ArrayList<>();
        if (step.getMainClass() != null && !step.getMainClass().isBlank()) {
            application.add("--class");
            application.add(step.getMainClass());
        }
        application.add(IMAGE_JARS.getOrDefault(jar, jar));
        application.addAll(args);
        return application;
    }

    /**
     * The command a step that is not a Spark step runs, or null for one Floci does not run: a
     * {@code command-runner.jar} step's arguments, its command first, unless it runs {@code pig-script} on a cluster
     * that installs Pig, which Floci has not added ({@code pigMissing}), where EMR's would run it; on one that does not,
     * EMR's {@code pig-script} runs and finds no {@code pig}, as it does there. Floci's S3DistCp with the arguments of an
     * {@code s3-dist-cp} one; a {@code script-runner.jar} step's script, the first of its arguments, with the rest.
     */
    static List<String> command(EmrStep step, boolean pigMissing) {
        String jar = step.getJar();
        List<String> args = step.getArgs() != null ? step.getArgs() : List.of();
        if (jar == null || args.isEmpty()) {
            return null;
        }
        if (named(jar, COMMAND_RUNNER)) {
            if (S3_DIST_CP.equals(args.getFirst())) {
                List<String> command = new ArrayList<>(List.of("bash", "-c", S3DistCp.COMMAND, S3_DIST_CP));
                command.addAll(args.subList(1, args.size()));
                return command;
            }
            return named(args.getFirst(), SPARK_SUBMIT) || pigMissing && named(args.getFirst(), PIG_SCRIPT)
                    ? null : List.copyOf(args);
        }
        if (named(jar, SCRIPT_RUNNER)) {
            List<String> command = new ArrayList<>(List.of("bash", "-c", SCRIPT_RUNNER_COMMAND, "script-runner"));
            command.addAll(args);
            return command;
        }
        return null;
    }

    /**
     * How a step runs Hive, or null for a step that does not, or whose cluster does not install Hive (see
     * {@link #installsHive}), where {@code hive-script} finds no {@code hive} as EMR's does without it: a
     * {@code command-runner.jar} step running {@code hive-script}, up to its {@code --args} (added at the end of one
     * without), or {@code hive}, and what its {@code hive} gets after
     * Floci's settings: the cluster's Hive classifications, then the step's own arguments, so that a
     * {@code --hiveconf} of the step's replaces a classification's property, as it replaces a hive-site.xml one.
     */
    static HiveCommand hive(EmrStep step, String releaseLabel, String applications, String configurations,
            ObjectMapper objectMapper) {
        String jar = step.getJar();
        List<String> args = step.getArgs() != null ? step.getArgs() : List.of();
        if (jar == null || !named(jar, COMMAND_RUNNER) || args.isEmpty()
                || !installsHive(releaseLabel, applications, objectMapper)) {
            return null;
        }
        List<String> runner;
        List<String> own;
        if (named(args.getFirst(), HIVE_SCRIPT)) {
            int split = args.indexOf(HIVE_SCRIPT_ARGS);
            runner = new ArrayList<>(split < 0 ? args : args.subList(0, split));
            runner.add(HIVE_SCRIPT_ARGS);
            own = split < 0 ? List.of() : args.subList(split + 1, args.size());
        } else if (named(args.getFirst(), HIVE)) {
            runner = List.of(args.getFirst());
            own = args.subList(1, args.size());
        } else {
            return null;
        }
        List<String> arguments = new ArrayList<>();
        for (String classification : HIVE_CLASSIFICATIONS) {
            arguments.addAll(EmrServerlessSparkRunner.hiveSettings(properties(configurations, objectMapper,
                    classification)));
        }
        arguments.addAll(own);
        return new HiveCommand(List.copyOf(runner), arguments);
    }

    /**
     * A step's Hive: {@code runner}, what runs in place of {@code hive}, and {@code arguments}, what follows Floci's
     * settings.
     */
    record HiveCommand(List<String> runner, List<String> arguments) {
    }

    /** The variables a cluster's {@code hive-env} exports, which its Hive steps get. */
    static Map<String, String> hiveEnvironment(String configurations, ObjectMapper objectMapper) {
        return exports(configurations, objectMapper, HIVE_ENV);
    }

    /**
     * Whether a cluster installs Hive: its {@code Applications}, the JSON array RunJobFlow was given ("A
     * case-insensitive list of applications for Amazon EMR to install"), name Hive, or HCatalog, with which EMR
     * installs Hive's client, {@code hive-client}, the {@code hive} that EMR's {@code hive-script} looks for, and its
     * HCatalog server, {@code hcatalog-server}, whose {@code hcat_server.sh} runs {@code hive --service metastore}.
     * HCatalog "allows you to access Hive metastore tables within Pig, Spark SQL, and/or custom MapReduce
     * applications". Such a cluster's Hive steps run, its steps' image has Hive, and its primary node runs Hive's
     * metastore service. A release of the AWS runtime for Apache Spark has neither: "This release includes the
     * following applications: AmazonCloudWatchAgent, Delta, Hudi, Iceberg, JupyterEnterpriseGateway, Livy, and
     * Spark."
     */
    static boolean installsHive(String releaseLabel, String applications, ObjectMapper objectMapper) {
        return installs(releaseLabel, applications, objectMapper, HIVE, HCATALOG);
    }

    /**
     * Whether a cluster installs Pig: its {@code Applications} name it, unless its release is of the AWS runtime for
     * Apache Spark, whose release notes say "Pig and Oozie are not included".
     */
    static boolean installsPig(String releaseLabel, String applications, ObjectMapper objectMapper) {
        return installs(releaseLabel, applications, objectMapper, PIG);
    }

    /**
     * Whether a cluster installs Trino: its {@code Applications} name it, unless its release is of the AWS runtime for
     * Apache Spark, whose applications do not include it.
     */
    static boolean installsTrino(String releaseLabel, String applications, ObjectMapper objectMapper) {
        return installs(releaseLabel, applications, objectMapper, EmrTrino.APPLICATION);
    }

    /**
     * The properties of a cluster's {@code trino-connector-<catalog>} classifications by catalog, in the order they
     * come, a later configuration's winning, each of which changes "Trino's {@code <catalog>.properties} file". One
     * whose catalog is not a name Trino takes ({@link EmrTrino#CATALOG_NAME}) names none.
     */
    static Map<String, Map<String, String>> trinoConnectors(String configurations, ObjectMapper objectMapper) {
        Map<String, Map<String, String>> connectors = new LinkedHashMap<>();
        for (JsonNode configuration : stored(configurations, objectMapper)) {
            String classification = configuration.path("Classification").asText();
            if (!classification.startsWith(EmrTrino.CONNECTOR_PREFIX)) {
                continue;
            }
            String catalog = classification.substring(EmrTrino.CONNECTOR_PREFIX.length());
            if (!EmrTrino.CATALOG_NAME.matcher(catalog).matches()) {
                continue;
            }
            Map<String, String> properties = connectors.computeIfAbsent(catalog, name -> new LinkedHashMap<>());
            configuration.path("Properties").properties()
                    .forEach(property -> properties.put(property.getKey(), property.getValue().asText()));
        }
        return connectors;
    }

    /** The properties of a cluster's {@code trino-config}, which its Trino's {@code config.properties} gets. */
    static Map<String, String> trinoConfig(String configurations, ObjectMapper objectMapper) {
        return properties(configurations, objectMapper, EmrTrino.CONFIG_CLASSIFICATION);
    }

    /** The properties of a cluster's {@code trino-node}, which its Trino's {@code node.properties} gets. */
    static Map<String, String> trinoNode(String configurations, ObjectMapper objectMapper) {
        return properties(configurations, objectMapper, EmrTrino.NODE_CLASSIFICATION);
    }

    /** The properties of a cluster's {@code trino-log}, which make its Trino's {@code log.properties}. */
    static Map<String, String> trinoLog(String configurations, ObjectMapper objectMapper) {
        return properties(configurations, objectMapper, EmrTrino.LOG_CLASSIFICATION);
    }

    /**
     * The variables a cluster's {@code trino-env} exports, its nested {@code export} classification's properties, as
     * "If any classification ends in env, use the export sub-classification", which its Trino's environment gets.
     */
    static Map<String, String> trinoEnvironment(String configurations, ObjectMapper objectMapper) {
        return exports(configurations, objectMapper, EmrTrino.ENV_CLASSIFICATION);
    }

    // Whether a cluster's Applications name any of names, in any case, unless its release is of the AWS runtime for
    // Apache Spark, which installs none of Hive, HCatalog, Pig and Trino.
    private static boolean installs(String releaseLabel, String applications, ObjectMapper objectMapper,
                                    String... names) {
        return (releaseLabel == null || !EmrServerlessSparkRunner.sparkRuntime(releaseLabel))
                && names(applications, objectMapper, names);
    }

    /** Whether a cluster's stored {@code Applications} name any of {@code names}, in any case. */
    static boolean names(String applications, ObjectMapper objectMapper, String... names) {
        for (JsonNode application : stored(applications, objectMapper)) {
            String name = application.path("Name").asText();
            for (String installed : names) {
                if (installed.equalsIgnoreCase(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The variables a step's command gets on a cluster with Floci's Pig, which make its {@code pig} read a Pig
     * configuration of its own: {@code pig.properties} lines that run Pig on Tez, as EMR's does, from a working
     * directory S3A takes, then the cluster's {@code tez-site} and {@code pig-properties}, a later one winning, and,
     * with a {@code pig-log4j}, Pig's setting to read the {@code log4j.properties} that gets its lines;
     * {@code pig-env}'s exports start its {@code pig-env.sh}.
     */
    static Map<String, String> pigEnvironment(String configurations, ObjectMapper objectMapper) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put(PIG_EXECTYPE, PIG_TEZ);
        properties.put(WORKING_DIR, USER_HOME);
        Map<String, String> log4j = properties(configurations, objectMapper, PIG_LOG4J);
        if (!log4j.isEmpty()) {
            properties.put(PIG_LOG4J_CONF, EmrServerlessSparkRunner.PIG_CONF + "/log4j.properties");
        }
        properties.putAll(properties(configurations, objectMapper, TEZ_SITE));
        properties.putAll(properties(configurations, objectMapper, PIG_PROPERTIES));
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put(EmrServerlessSparkRunner.PIG_PROPERTIES_VARIABLE,
                EmrServerlessSparkRunner.propertiesLines(properties));
        if (!log4j.isEmpty()) {
            variables.put(EmrServerlessSparkRunner.PIG_LOG4J_VARIABLE, EmrServerlessSparkRunner.propertiesLines(log4j));
        }
        Map<String, String> exports = exports(configurations, objectMapper, PIG_ENV);
        if (!exports.isEmpty()) {
            variables.put(EmrServerlessSparkRunner.PIG_ENV_VARIABLE, EmrServerlessSparkRunner.exportLines(exports));
        }
        return variables;
    }

    /**
     * The S3 folder its YARN applications' logs go to, or null when a cluster has no {@code LogUri}: the Management
     * Guide's {@code /cluster-id/containers}, "Application container logs. The logs for each YARN application are
     * stored in these locations".
     */
    static String containerLogFolder(EmrCluster cluster) {
        return EmrServerlessSparkRunner.logFolder(cluster.getLogUri(), cluster.getId() + "/containers/");
    }

    /**
     * The S3 folder a step's application container logs go to: its own log destination's {@code containers} folder,
     * as "the application's container logs will also be uploaded to the location specified in the
     * StepMonitoringConfiguration", where it has one ({@link #logUri}), and else its cluster's.
     */
    static String containerLogFolder(EmrCluster cluster, EmrStep step) {
        return EmrServerlessSparkRunner.logFolder(logUri(cluster, step), cluster.getId() + "/containers/");
    }

    /**
     * Where a node removed from the cluster puts its containers' logs: the running steps' own destination when they
     * share one, as those logs are their applications', and else the cluster's. A node removed between steps, or while
     * steps with other destinations run, uses the cluster's.
     */
    static String nodeLogFolder(EmrCluster cluster) {
        Set<String> folders = new HashSet<>();
        String folder = containerLogFolder(cluster);
        for (EmrStep step : cluster.getSteps()) {
            if (EmrClusterLifecycle.RUNNING.equals(step.getState())
                    || EmrClusterLifecycle.CANCEL_PENDING.equals(step.getState())) {
                folder = containerLogFolder(cluster, step);
                folders.add(String.valueOf(folder));
            }
        }
        return folders.size() == 1 ? folder : containerLogFolder(cluster);
    }

    /**
     * The S3 folder a step's logs go to, or null when its cluster has no {@code LogUri}: "If a value is not
     * provided, logs are not created". The Management Guide's "Log locations" puts step logs at
     * {@code <LogUri>/<cluster-id>/steps/<step-id>/}, under the step's own destination where it has one.
     */
    static String logFolder(EmrCluster cluster, EmrStep step) {
        return EmrServerlessSparkRunner.logFolder(logUri(cluster, step),
                cluster.getId() + "/steps/" + step.getId() + "/");
    }

    /**
     * Where a step's logs are published: the {@code LogUri} of its StepMonitoringConfiguration, which "Beginning in
     * Amazon EMR version 7.11" "take[s] precedence over any cluster-wide logging settings", and else its cluster's.
     * None without the cluster's: "If your cluster does not enable cluster logging, step logs will not be uploaded to
     * S3 even if you provide a StepMonitoringConfiguration" (Management Guide, "Customizing the log location for step
     * log files"). The guide gives no folder layout under the step's own destination; it is the cluster's.
     */
    static String logUri(EmrCluster cluster, EmrStep step) {
        if (cluster.getLogUri() == null || step.getLogUri() == null
                || !EmrService.releaseAtLeast(cluster.getReleaseLabel(), 7, 11, 0)) {
            return cluster.getLogUri();
        }
        return step.getLogUri();
    }

    /**
     * The Spark properties of a cluster's {@code Configurations}, the JSON array {@code RunJobFlow} was given,
     * that a step's {@code spark-submit} gets as defaults: {@code spark-defaults}'s, and {@code core-site}'s and
     * {@code spark-hive-site}'s as {@code spark.hadoop.} settings, which Spark's Hadoop configuration, and its
     * Hive client's, take as they would {@code core-site.xml} and {@code hive-site.xml}. A setting in
     * {@code spark-hive-site} wins over the same one in {@code core-site}, as {@code hive-site.xml} does, and a
     * {@code spark.hadoop.} setting in {@code spark-defaults} over both.
     */
    static Map<String, String> sparkDefaults(String configurations, ObjectMapper objectMapper) {
        Map<String, String> properties = new LinkedHashMap<>();
        coreSite(configurations, objectMapper)
                .forEach((key, value) -> properties.put(SPARK_HADOOP_PREFIX + key, value));
        properties(configurations, objectMapper, SPARK_HIVE_SITE)
                .forEach((key, value) -> properties.put(SPARK_HADOOP_PREFIX + key, value));
        properties.putAll(properties(configurations, objectMapper, SPARK_DEFAULTS));
        return properties;
    }

    /** The properties of a cluster's {@code hdfs-site}, of which Floci's HDFS applies {@code dfs.replication}. */
    static Map<String, String> hdfsSite(String configurations, ObjectMapper objectMapper) {
        return properties(configurations, objectMapper, HDFS_SITE);
    }

    /** The properties of a cluster's {@code core-site}, which its steps' {@code hadoop} command gets too. */
    static Map<String, String> coreSite(String configurations, ObjectMapper objectMapper) {
        return properties(configurations, objectMapper, CORE_SITE);
    }

    /**
     * The properties of a cluster's {@code core-site}, then its {@code hive-site}, which configure its Hive metastore
     * service as they do on EMR's primary node.
     */
    static Map<String, String> hiveMetastoreSettings(String configurations, ObjectMapper objectMapper) {
        Map<String, String> settings = new LinkedHashMap<>(properties(configurations, objectMapper, CORE_SITE));
        settings.putAll(properties(configurations, objectMapper, HIVE_SITE));
        return settings;
    }

    /** The properties of a cluster's {@code spark-log4j2}, which its steps' Spark gets in its log4j2.properties. */
    static Map<String, String> sparkLog4j2(String configurations, ObjectMapper objectMapper) {
        return properties(configurations, objectMapper, SPARK_LOG4J2);
    }

    /** The properties of a cluster's {@code mapred-site}, which its steps' MapReduce gets in its mapred-site.xml. */
    static Map<String, String> mapredSite(String configurations, ObjectMapper objectMapper) {
        return properties(configurations, objectMapper, MAPRED_SITE);
    }

    /** The properties of a cluster's {@code hadoop-log4j}, which its steps' {@code hadoop} command logs with. */
    static Map<String, String> hadoopLog4j(String configurations, ObjectMapper objectMapper) {
        return properties(configurations, objectMapper, HADOOP_LOG4J);
    }

    /** The variables a cluster's {@code hadoop-env} exports, which its steps' {@code hadoop} command gets. */
    static Map<String, String> hadoopEnvironment(String configurations, ObjectMapper objectMapper) {
        return exports(configurations, objectMapper, HADOOP_ENV);
    }

    /**
     * The environment variables a step's container gets: its cluster's {@code spark-env} exports and, for a
     * custom JAR step, its {@code Properties}, "Java properties that are set when the step runs. You can use
     * these properties to pass key-value pairs to your main function", as system properties of the JVM its main
     * class runs in, after any Java options the cluster exports to {@code spark-submit}, so that a step's own
     * win. A {@code command-runner.jar} or {@code script-runner.jar} step's would be those of its runner's JVM,
     * which Floci has none of, so they go nowhere.
     */
    static Map<String, String> environment(EmrStep step, String configurations, ObjectMapper objectMapper) {
        Map<String, String> variables = sparkEnvironment(configurations, objectMapper);
        String jar = step.getJar();
        if (step.getProperties() != null && !step.getProperties().isEmpty() && jar != null && !jar.isBlank()
                && !named(jar, COMMAND_RUNNER) && !named(jar, SCRIPT_RUNNER)) {
            variables.merge(SPARK_SUBMIT_OPTS, javaOptions(step.getProperties()),
                    (exported, own) -> exported + " " + own);
        }
        return variables;
    }

    /**
     * The environment variables a cluster's {@code spark-env} exports, its nested {@code export}
     * classification's properties, or none.
     */
    static Map<String, String> sparkEnvironment(String configurations, ObjectMapper objectMapper) {
        return exports(configurations, objectMapper, SPARK_ENV);
    }

    // The properties of the export classification nested in one classification, a later configuration's winning.
    private static Map<String, String> exports(String configurations, ObjectMapper objectMapper,
                                               String classification) {
        Map<String, String> variables = new LinkedHashMap<>();
        for (JsonNode configuration : classified(configurations, objectMapper, classification)) {
            for (JsonNode nested : configuration.path("Configurations")) {
                if (EXPORT.equals(nested.path("Classification").asText())) {
                    nested.path("Properties").properties()
                            .forEach(property -> variables.put(property.getKey(), property.getValue().asText()));
                }
            }
        }
        return variables;
    }

    // The properties of one classification in a cluster's Configurations, a later configuration's winning.
    private static Map<String, String> properties(String configurations, ObjectMapper objectMapper,
                                                  String classification) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (JsonNode configuration : classified(configurations, objectMapper, classification)) {
            configuration.path("Properties").properties()
                    .forEach(property -> properties.put(property.getKey(), property.getValue().asText()));
        }
        return properties;
    }

    // Each property as a -D option, in single quotes, which spark-submit's launcher reads as a shell would,
    // keeping what they hold, a quote itself written '\''.
    private static String javaOptions(Map<String, String> properties) {
        List<String> options = new ArrayList<>();
        properties.forEach((key, value) -> options.add("'" + ("-D" + key + "=" + value).replace("'", "'\\''") + "'"));
        return String.join(" ", options);
    }

    // The configurations of one classification in a cluster's Configurations, in their order.
    private static List<JsonNode> classified(String configurations, ObjectMapper objectMapper,
                                             String classification) {
        List<JsonNode> matching = new ArrayList<>();
        for (JsonNode configuration : stored(configurations, objectMapper)) {
            if (classification.equals(configuration.path("Classification").asText())) {
                matching.add(configuration);
            }
        }
        return matching;
    }

    // A JSON array of a cluster's, which RunJobFlow stored as it came, or an empty one for none: one that does not
    // parse holds no properties to apply and names no application.
    private static JsonNode stored(String json, ObjectMapper objectMapper) {
        if (json == null) {
            return objectMapper.createArrayNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ignored) {
            return objectMapper.createArrayNode();
        }
    }

    private static List<String> withoutMasterOrDeployMode(List<String> arguments) {
        List<String> kept = new ArrayList<>();
        int i = 0;
        while (i < arguments.size() && arguments.get(i).startsWith("-")) {
            String option = arguments.get(i);
            int equals = option.indexOf('=');
            String name = option.startsWith("--") && equals > 0 ? option.substring(0, equals) : option;
            // A switch, or an option written --name=value, is one argument; any other option takes the next.
            int length = SWITCHES.contains(option) || !name.equals(option) ? 1 : 2;
            List<String> words = arguments.subList(i, Math.min(i + length, arguments.size()));
            if (!DROPPED_OPTIONS.contains(name)) {
                kept.addAll(words);
            }
            i += words.size();
        }
        kept.addAll(arguments.subList(i, arguments.size()));
        return kept;
    }

    /**
     * Floci's S3DistCp: the image has no {@code s3-dist-cp}, so the command writes Floci's program into the
     * container and runs it through {@code spark-submit}, which the command wrapper gives Floci's S3 and the
     * cluster's HDFS. Read once, when the first {@code s3-dist-cp} step comes.
     */
    private static final class S3DistCp {
        static final String COMMAND = String.join("\n",
                "program=\"${TMPDIR:-/tmp}/floci-s3-dist-cp.py\"",
                "cat > \"$program\" <<'" + S3_DIST_CP_END + "'",
                program(),
                S3_DIST_CP_END,
                "exec spark-submit --conf spark.ui.enabled=false \"$program\" \"$@\"");

        private static String program() {
            try (InputStream in = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream(S3_DIST_CP_RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException(S3_DIST_CP_RESOURCE + " is missing from the classpath");
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read " + S3_DIST_CP_RESOURCE, e);
            }
        }
    }

    // command-runner.jar is named alone; a path such as /usr/bin/spark-submit names spark-submit too.
    private static boolean named(String path, String name) {
        return path.equals(name) || path.endsWith("/" + name);
    }
}
