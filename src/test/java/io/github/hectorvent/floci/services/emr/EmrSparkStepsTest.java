package io.github.hectorvent.floci.services.emr;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.emr.model.EmrStep;
import io.github.hectorvent.floci.services.lambda.launcher.BootstrapProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void aCommandRunnerStepThatIsNotSparkRunsItsCommand() {
        // The Release Guide's own example: download a script, make it executable, run it.
        List<String> bash = List.of("bash", "-c",
                "aws s3 cp s3://bucket/my-script.sh /home/hadoop; chmod u+x /home/hadoop/my-script.sh;"
                        + " cd /home/hadoop; ./my-script.sh");

        assertEquals(bash, EmrSparkSteps.command(step("command-runner.jar", null, bash.toArray(String[]::new))));
        assertEquals(List.of("python3", "-c", "print(1)"),
                EmrSparkSteps.command(step("command-runner.jar", null, "python3", "-c", "print(1)")));
        assertNull(EmrSparkSteps.command(
                step("command-runner.jar", null, "spark-submit", "s3://bucket/jobs/report.py")));
    }

    @Test
    void aScriptRunnerStepFetchesItsScriptAndRunsItWithItsArguments() {
        for (String jar : List.of("script-runner.jar",
                "s3://us-east-1.elasticmapreduce/libs/script-runner/script-runner.jar")) {
            List<String> command = EmrSparkSteps.command(step(jar, null, "s3://bucket/my-script.sh", "--day", "1"));

            assertEquals(List.of("bash", "-c"), command.subList(0, 2));
            assertTrue(command.get(2).contains("aws s3 cp --only-show-errors"));
            assertEquals(List.of("script-runner", "s3://bucket/my-script.sh", "--day", "1"),
                    command.subList(3, command.size()));
        }
    }

    @Test
    void aScriptRunnerScriptNamedWithoutAPathRunsFromTheWorkingDirectory(@TempDir Path workingDirectory)
            throws Exception {
        // Not executable yet: script-runner makes it so, as it does a script it fetched from S3.
        Files.writeString(workingDirectory.resolve("my-script.sh"), "#!/bin/sh\nprintf 'ran:%s|' \"$@\"\n");
        List<String> command = EmrSparkSteps.command(step("script-runner.jar", null, "my-script.sh", "--day", "1 2"));

        BootstrapProcess.Result ended =
                BootstrapProcess.run(new ProcessBuilder(command).directory(workingDirectory.toFile()));

        assertEquals(new BootstrapProcess.Result(0, "ran:--day|ran:1 2|"), ended);
    }

    @Test
    void toolsTheImageDoesNotHaveAreNotRun() {
        for (String tool : List.of("hive-script", "pig-script", "hadoop-streaming", "hadoop-lzo", "s3-dist-cp")) {
            assertNull(EmrSparkSteps.command(step("command-runner.jar", null, tool, "--src", "s3://bucket/in/")));
        }
        assertNull(EmrSparkSteps.command(step("command-runner.jar", null)));
        assertNull(EmrSparkSteps.command(step("script-runner.jar", null)));
        assertNull(EmrSparkSteps.command(step("s3://bucket/jars/app.jar", null, "1")));
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
