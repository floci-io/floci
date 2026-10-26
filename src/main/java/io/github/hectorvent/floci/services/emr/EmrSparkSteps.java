package io.github.hectorvent.floci.services.emr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.emr.model.EmrStep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a step runs in the {@code docker} runner mode, which runs Spark steps only, each through
 * {@code spark-submit} in local mode: a {@code command-runner.jar} step running {@code spark-submit}, as
 * the Management Guide's Spark steps are written, and a custom JAR step, whose main class
 * {@code spark-submit} runs with the JAR on its class path.
 */
final class EmrSparkSteps {

    // No source gives AWS's wording for these; they are Floci's.
    static final String NOT_RUN = "Floci runs Spark steps only: spark-submit through command-runner.jar, or a"
            + " custom JAR.";
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
     * The {@code spark-defaults} properties of a cluster's {@code Configurations}, the JSON array
     * {@code RunJobFlow} was given, or none.
     */
    static Map<String, String> sparkDefaults(String configurations, ObjectMapper objectMapper) {
        Map<String, String> properties = new LinkedHashMap<>();
        if (configurations == null) {
            return properties;
        }
        try {
            for (JsonNode configuration : objectMapper.readTree(configurations)) {
                if (SPARK_DEFAULTS.equals(configuration.path("Classification").asText())) {
                    configuration.path("Properties").properties()
                            .forEach(property -> properties.put(property.getKey(), property.getValue().asText()));
                }
            }
        } catch (JsonProcessingException ignored) {
            // RunJobFlow stored the array as it came; one that does not parse holds no properties to apply.
            return properties;
        }
        return properties;
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

    // command-runner.jar is named alone; a path such as /usr/bin/spark-submit names spark-submit too.
    private static boolean named(String path, String name) {
        return path.equals(name) || path.endsWith("/" + name);
    }
}
