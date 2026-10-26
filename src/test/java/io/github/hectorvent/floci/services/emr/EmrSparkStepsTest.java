package io.github.hectorvent.floci.services.emr;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.emr.model.EmrStep;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EmrSparkStepsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aCommandRunnerSparkSubmitStepRunsLocallyWithoutItsMasterOrDeployMode() {
        EmrStep step = step("command-runner.jar", null, "spark-submit", "--deploy-mode", "cluster",
                "--master=yarn", "--verbose", "--conf", "spark.executor.cores=2", "--class", "com.example.Report",
                "s3://bucket/jobs/report.jar", "--master", "kept");

        // Options come before the application, so --master after it is the application's own argument.
        assertEquals(List.of("--verbose", "--conf", "spark.executor.cores=2", "--class", "com.example.Report",
                "s3://bucket/jobs/report.jar", "--master", "kept"), EmrSparkSteps.application(step));
    }

    @Test
    void sparkSubmitMayBeNamedByItsPath() {
        assertEquals(List.of("s3://bucket/jobs/report.py"), EmrSparkSteps.application(
                step("command-runner.jar", null, "/usr/bin/spark-submit", "s3://bucket/jobs/report.py")));
    }

    @Test
    void aCustomJarRunsItsMainClassOrElseItsManifests() {
        assertEquals(List.of("--class", "com.example.Report", "s3://bucket/jobs/report.jar", "2026-10-25"),
                EmrSparkSteps.application(step("s3://bucket/jobs/report.jar", "com.example.Report", "2026-10-25")));
        assertEquals(List.of("s3://bucket/jobs/report.jar"),
                EmrSparkSteps.application(step("s3://bucket/jobs/report.jar", null)));
    }

    @Test
    void stepsOtherThanSparkAreNotRun() {
        assertNull(EmrSparkSteps.application(step("command-runner.jar", null, "hive-script", "--run-hive-script")));
        assertNull(EmrSparkSteps.application(step("command-runner.jar", null)));
        assertNull(EmrSparkSteps.application(
                step("s3://us-east-1.elasticmapreduce/libs/script-runner/script-runner.jar", null, "s3://b/x.sh")));
        assertNull(EmrSparkSteps.application(step(null, null)));
    }

    @Test
    void theClustersSparkDefaultsComeFromItsConfigurations() {
        String configurations = """
                [{"Classification": "spark-env", "Properties": {"JAVA_HOME": "/usr/lib/jvm"}},
                 {"Classification": "spark-defaults", "Properties": {"spark.executor.cores": "2",
                  "spark.sql.shuffle.partitions": "8"}}]
                """;

        assertEquals(Map.of("spark.executor.cores", "2", "spark.sql.shuffle.partitions", "8"),
                EmrSparkSteps.sparkDefaults(configurations, objectMapper));
        assertEquals(Map.of(), EmrSparkSteps.sparkDefaults(null, objectMapper));
        assertEquals(Map.of(), EmrSparkSteps.sparkDefaults("[{", objectMapper));
    }

    private static EmrStep step(String jar, String mainClass, String... args) {
        EmrStep step = new EmrStep();
        step.setJar(jar);
        step.setMainClass(mainClass);
        step.setArgs(List.of(args));
        return step;
    }
}
