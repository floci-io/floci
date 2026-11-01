package io.github.hectorvent.floci.services.emr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.emr.model.EmrCluster;
import io.github.hectorvent.floci.services.emr.model.EmrStep;
import io.github.hectorvent.floci.services.emrserverless.EmrServerlessSparkRunner;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a step runs in the {@code docker} runner mode, in a container of the release's EMR Serverless image.
 * A Spark step runs through {@code spark-submit} in local mode: a {@code command-runner.jar} step running
 * {@code spark-submit}, as the Management Guide's Spark steps are written, and a custom JAR step, whose main
 * class {@code spark-submit} runs with the JAR on its class path. Any other {@code command-runner.jar} step
 * runs its command, and a {@code script-runner.jar} step its script, as the Release Guide's "Run commands
 * and scripts on an Amazon EMR cluster" describes them; an {@code s3-dist-cp} one runs Floci's own S3DistCp.
 */
final class EmrSparkSteps {

    // No source gives AWS's wording for these; they are Floci's.
    static final String NOT_RUN = "Floci does not run this step: hive-script, pig-script, hadoop-streaming and"
            + " hadoop-lzo are not in the image steps run in.";
    static final String INTERRUPTED = "Floci stopped while the step was running.";

    private static final String COMMAND_RUNNER = "command-runner.jar";
    private static final String SCRIPT_RUNNER = "script-runner.jar";
    private static final String SPARK_SUBMIT = "spark-submit";
    // Local mode is Floci's, so a step's own master and deploy mode are dropped.
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
    private static final String SPARK_LOG4J2 = "spark-log4j2";
    // Spark puts a spark.hadoop. setting in its Hadoop configuration, ahead of core-site.xml and hive-site.xml.
    private static final String SPARK_HADOOP_PREFIX = "spark.hadoop.";
    // spark-submit gives the JVM it runs an application's main class in the Java options of this variable.
    private static final String SPARK_SUBMIT_OPTS = "SPARK_SUBMIT_OPTS";
    // The Release Guide's command-runner.jar tools that the EMR Serverless image does not have; its hadoop
    // has no MapReduce to run streaming or the LZO indexer.
    private static final Set<String> UNAVAILABLE_TOOLS = Set.of("hive-script", "pig-script", "hadoop-streaming",
            "hadoop-lzo");
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
     * {@code spark-submit}, without its {@code --master} and {@code --deploy-mode}: options come before the
     * application, so one after it is the application's own and kept. For a custom JAR, its main class, if
     * the step names one (otherwise {@code spark-submit} reads the JAR's manifest), the JAR and the step's
     * arguments.
     */
    static List<String> application(EmrStep step) {
        String jar = step.getJar();
        if (jar == null || jar.isBlank() || named(jar, SCRIPT_RUNNER)) {
            return null;
        }
        List<String> args = step.getArgs() != null ? step.getArgs() : List.of();
        if (named(jar, COMMAND_RUNNER)) {
            return !args.isEmpty() && named(args.getFirst(), SPARK_SUBMIT)
                    ? withoutMasterOrDeployMode(args.subList(1, args.size())) : null;
        }
        List<String> application = new ArrayList<>();
        if (step.getMainClass() != null && !step.getMainClass().isBlank()) {
            application.add("--class");
            application.add(step.getMainClass());
        }
        application.add(jar);
        application.addAll(args);
        return application;
    }

    /**
     * The command a step that is not a Spark step runs, or null for one Floci does not run: a
     * {@code command-runner.jar} step's arguments, its command first, unless it names a tool the image does
     * not have; Floci's S3DistCp with the arguments of an {@code s3-dist-cp} one; a {@code script-runner.jar}
     * step's script, the first of its arguments, with the rest.
     */
    static List<String> command(EmrStep step) {
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
            return named(args.getFirst(), SPARK_SUBMIT) || UNAVAILABLE_TOOLS.contains(args.getFirst())
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
     * The S3 folder a step's logs go to, or null when its cluster has no {@code LogUri}: "If a value is not
     * provided, logs are not created". The Management Guide's "Log locations" puts step logs at
     * {@code <LogUri>/<cluster-id>/steps/<step-id>/}.
     */
    static String logFolder(EmrCluster cluster, EmrStep step) {
        return EmrServerlessSparkRunner.logFolder(cluster.getLogUri(),
                cluster.getId() + "/steps/" + step.getId() + "/");
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

    /** The properties of a cluster's {@code core-site}, which its steps' {@code hadoop} command gets too. */
    static Map<String, String> coreSite(String configurations, ObjectMapper objectMapper) {
        return properties(configurations, objectMapper, CORE_SITE);
    }

    /** The properties of a cluster's {@code spark-log4j2}, which its steps' Spark gets in its log4j2.properties. */
    static Map<String, String> sparkLog4j2(String configurations, ObjectMapper objectMapper) {
        return properties(configurations, objectMapper, SPARK_LOG4J2);
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
        Map<String, String> variables = new LinkedHashMap<>();
        for (JsonNode configuration : classified(configurations, objectMapper, SPARK_ENV)) {
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
        if (configurations == null) {
            return matching;
        }
        try {
            for (JsonNode configuration : objectMapper.readTree(configurations)) {
                if (classification.equals(configuration.path("Classification").asText())) {
                    matching.add(configuration);
                }
            }
        } catch (JsonProcessingException ignored) {
            // RunJobFlow stored the array as it came; one that does not parse holds no properties to apply.
            return matching;
        }
        return matching;
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
     * Floci's S3DistCp: the image has neither {@code s3-dist-cp} nor MapReduce, so the command writes Floci's
     * program into the container and runs it through {@code spark-submit}, which the command wrapper gives
     * Floci's S3 and the cluster's HDFS. Read once, when the first {@code s3-dist-cp} step comes.
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
