package io.github.hectorvent.floci.services.emr;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.emr.model.EmrStep;
import io.github.hectorvent.floci.services.lambda.launcher.BootstrapProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void anS3DistCpStepWritesFlocisS3DistCpAndRunsItThroughSparkSubmit(@TempDir Path folder) throws Exception {
        // The Release Guide's own S3DistCp step.
        List<String> command = EmrSparkSteps.command(step("command-runner.jar", null, "s3-dist-cp",
                "--s3Endpoint=s3.amazonaws.com", "--src=s3://bucket/logs/j-3GYXXXXXX9IOJ/node/",
                "--dest=hdfs:///output", "--srcPattern=.*[a-zA-Z,]+"));
        // A spark-submit that shows what it was given and keeps the program, in place of the image's.
        Path sparkSubmit = folder.resolve("spark-submit");
        Files.writeString(sparkSubmit, "#!/bin/sh\nprintf '%s|' \"$@\"\ncp \"$3\" " + folder.resolve("kept.py") + "\n");
        assertTrue(sparkSubmit.toFile().setExecutable(true));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("PATH", folder + ":" + System.getenv("PATH"));
        builder.environment().put("TMPDIR", folder.toString());

        BootstrapProcess.Result ended = BootstrapProcess.run(builder);

        assertEquals(List.of("bash", "-c"), command.subList(0, 2));
        assertEquals(new BootstrapProcess.Result(0, "--conf|spark.ui.enabled=false|" + folder + "/floci-s3-dist-cp.py|"
                + "--s3Endpoint=s3.amazonaws.com|--src=s3://bucket/logs/j-3GYXXXXXX9IOJ/node/|--dest=hdfs:///output|"
                + "--srcPattern=.*[a-zA-Z,]+|"), ended);
        String program;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(EmrSparkSteps.S3_DIST_CP_RESOURCE)) {
            program = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(program.stripTrailing(), Files.readString(folder.resolve("kept.py")).stripTrailing());
        assertFalse(program.lines().anyMatch(EmrSparkSteps.S3_DIST_CP_END::equals));
    }

    @Test
    void toolsTheImageDoesNotHaveAreNotRun() {
        for (String tool : List.of("hive-script", "pig-script", "hadoop-streaming", "hadoop-lzo")) {
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

    @Test
    void sparkHiveSitePropertiesReachSparkAsHadoopSettingsThatSparkDefaultsOverride() {
        // The Management Guide's Glue Data Catalog setting for Spark, and one spark-defaults overrides.
        String configurations = """
                [{"Classification": "spark-hive-site", "Properties": {
                  "hive.metastore.client.factory.class":
                    "com.amazonaws.glue.catalog.metastore.AWSGlueDataCatalogHiveClientFactory",
                  "hive.metastore.glue.catalogid": "111122223333"}},
                 {"Classification": "spark-defaults", "Properties": {
                  "spark.hadoop.hive.metastore.glue.catalogid": "444455556666"}}]
                """;

        assertEquals(Map.of("spark.hadoop.hive.metastore.client.factory.class",
                        "com.amazonaws.glue.catalog.metastore.AWSGlueDataCatalogHiveClientFactory",
                        "spark.hadoop.hive.metastore.glue.catalogid", "444455556666"),
                EmrSparkSteps.sparkDefaults(configurations, objectMapper));
    }

    @Test
    void theClustersSparkEnvExportsAreItsStepsEnvironment() {
        // The Release Guide's spark-env example, with a property outside export, which sets nothing.
        String configurations = """
                [{"Classification": "spark-env", "Properties": {"IGNORED": "1"}, "Configurations": [
                   {"Classification": "export", "Properties": {"JAVA_HOME": "/usr/lib/jvm/java-1.8.0",
                    "PYSPARK_PYTHON": "/usr/bin/python3.11"}, "Configurations": []}]},
                 {"Classification": "hadoop-env", "Properties": {}, "Configurations": [
                   {"Classification": "export", "Properties": {"HADOOP_HEAPSIZE": "2048"}}]}]
                """;

        assertEquals(Map.of("JAVA_HOME", "/usr/lib/jvm/java-1.8.0", "PYSPARK_PYTHON", "/usr/bin/python3.11"),
                EmrSparkSteps.sparkEnvironment(configurations, objectMapper));
        assertEquals(Map.of(), EmrSparkSteps.sparkEnvironment(null, objectMapper));
        assertEquals(Map.of(), EmrSparkSteps.sparkEnvironment("[{", objectMapper));
    }

    @Test
    void coreSitePropertiesReachSparkAsHadoopSettingsThatSparkHiveSiteAndSparkDefaultsOverride() {
        // The Release Guide's core-site example, with two settings the later classifications set again.
        String configurations = """
                [{"Classification": "core-site", "Properties": {"hadoop.security.groups.cache.secs": "250",
                  "fs.s3a.connection.maximum": "50", "hive.exec.scratchdir": "/tmp/core"}},
                 {"Classification": "spark-hive-site", "Properties": {"hive.exec.scratchdir": "/tmp/hive"}},
                 {"Classification": "spark-defaults", "Properties": {
                  "spark.hadoop.fs.s3a.connection.maximum": "100"}}]
                """;

        assertEquals(Map.of("spark.hadoop.hadoop.security.groups.cache.secs", "250",
                        "spark.hadoop.fs.s3a.connection.maximum", "100",
                        "spark.hadoop.hive.exec.scratchdir", "/tmp/hive"),
                EmrSparkSteps.sparkDefaults(configurations, objectMapper));
        // The hadoop command gets core-site's alone, as they are.
        assertEquals(Map.of("hadoop.security.groups.cache.secs", "250", "fs.s3a.connection.maximum", "50",
                "hive.exec.scratchdir", "/tmp/core"), EmrSparkSteps.coreSite(configurations, objectMapper));
        assertEquals(Map.of(), EmrSparkSteps.coreSite(null, objectMapper));
    }

    @Test
    void theClustersSparkLog4j2PropertiesComeFromItsConfigurations() {
        String configurations = """
                [{"Classification": "spark-log4j2", "Properties": {"rootLogger.level": "warn",
                  "logger.app.name": "com.example.app", "logger.app.level": "debug"}},
                 {"Classification": "spark-defaults", "Properties": {"spark.executor.cores": "2"}}]
                """;

        assertEquals(Map.of("rootLogger.level", "warn", "logger.app.name", "com.example.app",
                "logger.app.level", "debug"), EmrSparkSteps.sparkLog4j2(configurations, objectMapper));
        assertEquals(Map.of(), EmrSparkSteps.sparkLog4j2(null, objectMapper));
    }

    @Test
    void aCustomJarStepsPropertiesAreJavaSystemPropertiesOfTheJvmItsMainClassRunsIn() {
        EmrStep step = step("s3://bucket/jobs/app.jar", "com.example.Report", "2026-11-01");
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("report.title", "Nightly report");
        properties.put("report.owner", "it's the data team's");
        step.setProperties(properties);
        String configurations = """
                [{"Classification": "spark-env", "Properties": {}, "Configurations": [
                   {"Classification": "export", "Properties": {"SPARK_SUBMIT_OPTS": "-Dreport.region=eu",
                    "PYSPARK_PYTHON": "/usr/bin/python3.11"}}]}]
                """;

        // After the cluster's own, which they win over as later options; each quoted, a quote written '\''.
        String own = "'-Dreport.title=Nightly report' '-Dreport.owner=it'\\''s the data team'\\''s'";
        assertEquals(Map.of("SPARK_SUBMIT_OPTS", "-Dreport.region=eu " + own, "PYSPARK_PYTHON", "/usr/bin/python3.11"),
                EmrSparkSteps.environment(step, configurations, objectMapper));
        assertEquals(Map.of("SPARK_SUBMIT_OPTS", own), EmrSparkSteps.environment(step, null, objectMapper));
    }

    @Test
    void aRunnersPropertiesAreItsJvmsWhichFlociHasNoneOf() {
        Map<String, String> properties = Map.of("report.title", "Nightly report");
        EmrStep sparkSubmit = step("command-runner.jar", null, "spark-submit", "s3://bucket/jobs/report.py");
        EmrStep script = step("s3://eu-west-1.elasticmapreduce/libs/script-runner/script-runner.jar", null,
                "s3://bucket/scripts/prepare.sh");
        sparkSubmit.setProperties(properties);
        script.setProperties(properties);
        String configurations = """
                [{"Classification": "spark-env", "Configurations": [
                   {"Classification": "export", "Properties": {"PYSPARK_PYTHON": "/usr/bin/python3.11"}}]}]
                """;

        assertEquals(Map.of("PYSPARK_PYTHON", "/usr/bin/python3.11"),
                EmrSparkSteps.environment(sparkSubmit, configurations, objectMapper));
        assertEquals(Map.of("PYSPARK_PYTHON", "/usr/bin/python3.11"),
                EmrSparkSteps.environment(script, configurations, objectMapper));
        assertEquals(Map.of(), EmrSparkSteps.environment(step("s3://bucket/jobs/app.jar", null), null, objectMapper));
    }

    private static EmrStep step(String jar, String mainClass, String... args) {
        EmrStep step = new EmrStep();
        step.setJar(jar);
        step.setMainClass(mainClass);
        step.setArgs(List.of(args));
        return step;
    }
}
