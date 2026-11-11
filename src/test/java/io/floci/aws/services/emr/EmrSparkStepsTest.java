package io.floci.aws.services.emr;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.emr.model.EmrStep;
import io.floci.aws.services.lambda.launcher.BootstrapProcess;
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

    // A cluster's Applications, as RunJobFlow stores them, in a case of their own.
    private static final String HIVE_AND_SPARK = "[{\"Name\": \"Spark\"}, {\"Name\": \"hive\"}]";
    private static final String RELEASE = "emr-7.5.0";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aCommandRunnerSparkSubmitStepRunsLocallyWithoutItsMasterOrDeployMode() {
        EmrStep step = step("command-runner.jar", null, "spark-submit", "--deploy-mode", "cluster",
                "--master=yarn", "--verbose", "--conf", "spark.executor.cores=2", "--class", "com.example.Report",
                "s3://bucket/jobs/report.jar", "--master", "kept");

        // Options come before the application, so --master after it is the application's own argument.
        assertEquals(List.of("--verbose", "--conf", "spark.executor.cores=2", "--class", "com.example.Report",
                "s3://bucket/jobs/report.jar", "--master", "kept"), EmrSparkSteps.application(step, false));
    }

    @Test
    void sparkSubmitMayBeNamedByItsPath() {
        assertEquals(List.of("s3://bucket/jobs/report.py"), EmrSparkSteps.application(
                step("command-runner.jar", null, "/usr/bin/spark-submit", "s3://bucket/jobs/report.py"), false));
    }

    @Test
    void aCustomJarRunsItsMainClassOrElseItsManifests() {
        assertEquals(List.of("--class", "com.example.Report", "s3://bucket/jobs/report.jar", "2026-10-25"),
                EmrSparkSteps.application(step("s3://bucket/jobs/report.jar", "com.example.Report", "2026-10-25"),
                        false));
        assertEquals(List.of("s3://bucket/jobs/report.jar"),
                EmrSparkSteps.application(step("s3://bucket/jobs/report.jar", null), false));
    }

    @Test
    void aCustomJarAtEmrsStreamingJarPathRunsTheImagesCopy() {
        assertEquals(List.of("/usr/lib/hadoop/hadoop-streaming.jar", "-input", "s3://bucket/in/"),
                EmrSparkSteps.application(step("/usr/lib/hadoop-mapreduce/hadoop-streaming.jar", null, "-input",
                        "s3://bucket/in/"), false));
        assertEquals(List.of("/home/hadoop/hadoop-streaming.jar"),
                EmrSparkSteps.application(step("/home/hadoop/hadoop-streaming.jar", null), false));
    }

    @Test
    void stepsOtherThanSparkAreNotRun() {
        assertNull(EmrSparkSteps.application(step("command-runner.jar", null, "hive-script", "--run-hive-script"),
                false));
        assertNull(EmrSparkSteps.application(step("command-runner.jar", null), false));
        assertNull(EmrSparkSteps.application(
                step("s3://us-east-1.elasticmapreduce/libs/script-runner/script-runner.jar", null, "s3://b/x.sh"),
                        false));
        assertNull(EmrSparkSteps.application(step(null, null), false));
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
    void streamingAndTheLzoIndexerRunAsTheCommandsTheyAre() {
        // The Management Guide's streaming step, as the AWS CLI turns Type=STREAMING into one.
        String[] streaming = {"hadoop-streaming", "-files", "s3://bucket/my_mapper.py,s3://bucket/my_reducer.py",
                "-mapper", "my_mapper.py", "-reducer", "my_reducer.py", "-input", "s3://bucket/my_input",
                "-output", "s3://bucket/my_output",
                "-cacheFile", "s3://bucket/sample_dataset.dat#sample_dataset_cached.dat"};
        String[] indexer = {"hadoop-lzo", "com.hadoop.compression.lzo.DistributedLzoIndexer", "s3://bucket/logs/"};

        assertEquals(List.of(streaming), EmrSparkSteps.command(step("command-runner.jar", null, streaming)));
        assertEquals(List.of(indexer), EmrSparkSteps.command(step("command-runner.jar", null, indexer)));
    }

    @Test
    void toolsTheImageDoesNotHaveAreNotRun() {
        assertNull(EmrSparkSteps.command(step("command-runner.jar", null, "pig-script", "--run-pig-script")));
        assertNull(EmrSparkSteps.command(step("command-runner.jar", null)));
        assertNull(EmrSparkSteps.command(step("script-runner.jar", null)));
        assertNull(EmrSparkSteps.command(step("s3://bucket/jars/app.jar", null, "1")));
    }

    @Test
    void aHiveStepsHiveGetsTheClustersHiveClassificationsThenTheArgumentsAfterHiveScriptsArgs() {
        // The AWS CLI's Hive step, Type=Hive,Args=[-f,...,-d,...], on a cluster with Hive.
        String configurations = """
                [{"Classification": "hive-site", "Properties": {"hive.exec.reducers.max": "8"}},
                 {"Classification": "spark-defaults", "Properties": {"spark.executor.cores": "2"}},
                 {"Classification": "tez-site", "Properties": {"tez.grouping.max-size": "268435456"}},
                 {"Classification": "core-site", "Properties": {"fs.s3a.connection.maximum": "50"}},
                 {"Classification": "hive-site", "Properties": {"hive.metastore.client.factory.class":
                   "com.amazonaws.glue.catalog.metastore.AWSGlueDataCatalogHiveClientFactory"}}]""";
        EmrSparkSteps.HiveCommand hive = EmrSparkSteps.hive(step("command-runner.jar", null, "hive-script",
                "--run-hive-script", "--args", "-f", "s3://bucket/queries/report.q", "-d", "INPUT=s3://bucket/in/",
                "--hiveconf", "hive.exec.reducers.max=4"), RELEASE, HIVE_AND_SPARK, configurations, objectMapper);

        assertEquals(List.of("hive-script", "--run-hive-script", "--args"), hive.runner());
        // core-site, then tez-site, then hive-site, as Hive reads them, and the step's own --hiveconf last.
        assertEquals(List.of("--hiveconf", "fs.s3a.connection.maximum=50",
                "--hiveconf", "tez.grouping.max-size=268435456",
                "--hiveconf", "hive.exec.reducers.max=8",
                "--hiveconf", "hive.metastore.client.factory.class="
                        + "com.amazonaws.glue.catalog.metastore.AWSGlueDataCatalogHiveClientFactory",
                "-f", "s3://bucket/queries/report.q", "-d", "INPUT=s3://bucket/in/",
                "--hiveconf", "hive.exec.reducers.max=4"), hive.arguments());
    }

    @Test
    void aHiveScriptStepWithoutArgsGetsThemAtItsEndAndAStepCanRunHiveItself() {
        EmrSparkSteps.HiveCommand script = EmrSparkSteps.hive(step("command-runner.jar", null, "hive-script",
                "--run-hive-script"), RELEASE, HIVE_AND_SPARK, null, objectMapper);
        EmrSparkSteps.HiveCommand hive = EmrSparkSteps.hive(step("command-runner.jar", null, "/usr/bin/hive",
                "-e", "select 1"), RELEASE, HIVE_AND_SPARK, null, objectMapper);

        assertEquals(List.of("hive-script", "--run-hive-script", "--args"), script.runner());
        assertEquals(List.of(), script.arguments());
        assertEquals(List.of("/usr/bin/hive-script", "--run-hive-script", "--args"), EmrSparkSteps.hive(step(
                "command-runner.jar", null, "/usr/bin/hive-script", "--run-hive-script", "--args", "-e", "select 1"),
                RELEASE, HIVE_AND_SPARK, null, objectMapper).runner());
        assertEquals(List.of("/usr/bin/hive"), hive.runner());
        assertEquals(List.of("-e", "select 1"), hive.arguments());
    }

    @Test
    void aStepRunsHiveOnlyWhenItsClusterInstallsHive() {
        EmrStep query = step("command-runner.jar", null, "hive-script", "--run-hive-script", "--args", "-f",
                "s3://bucket/queries/report.q");
        for (String applications : new String[]{"[{\"Name\": \"Spark\"}]", "[]", null, "[{"}) {
            assertNull(EmrSparkSteps.hive(query, RELEASE, applications, null, objectMapper));
        }
        // Where hive-script then runs, it says what EMR's says without Hive: "Hive isn't installed."
        assertEquals(query.getArgs(), EmrSparkSteps.command(query));
        for (EmrStep other : List.of(step("command-runner.jar", null, "spark-submit", "s3://bucket/app.py"),
                step("command-runner.jar", null, "hive-scripts"),
                step("script-runner.jar", null, "s3://bucket/hive.sh"),
                step("s3://bucket/jars/hive.jar", null, "hive"))) {
            assertNull(EmrSparkSteps.hive(other, RELEASE, HIVE_AND_SPARK, null, objectMapper));
        }
    }

    @Test
    void aClusterInstallsHiveWithHiveOrWithHCatalog() {
        assertTrue(EmrSparkSteps.installsHive(RELEASE, HIVE_AND_SPARK, objectMapper));
        // EMR installs Hive's client and metastore service with HCatalog too, in any case.
        assertTrue(EmrSparkSteps.installsHive(RELEASE, "[{\"Name\": \"HCatalog\"}, {\"Name\": \"Spark\"}]",
                objectMapper));
        assertTrue(EmrSparkSteps.installsHive(RELEASE, "[{\"Name\": \"hcatalog\"}]", objectMapper));
        for (String applications : new String[]{"[{\"Name\": \"Spark\"}]", "[]", null, "[{"}) {
            assertFalse(EmrSparkSteps.installsHive(RELEASE, applications, objectMapper));
        }
        // So a Hive step runs there, EMR's hive-script finding the hive it looks for.
        EmrStep query = step("command-runner.jar", null, "hive-script", "--run-hive-script", "--args", "-e",
                "show tables;");
        assertEquals(List.of("hive-script", "--run-hive-script", "--args"),
                EmrSparkSteps.hive(query, RELEASE, "[{\"Name\": \"HCatalog\"}]", null, objectMapper).runner());
    }

    @Test
    void aReleaseOfTheAwsRuntimeForSparkInstallsNeitherHiveNorHCatalog() {
        // Each of its releases "includes the following applications: AmazonCloudWatchAgent, Delta, Hudi, Iceberg,
        // JupyterEnterpriseGateway, Livy, and Spark."
        EmrStep query = step("command-runner.jar", null, "hive-script", "--run-hive-script", "--args", "-e",
                "show tables;");
        for (String release : new String[]{"emr-spark-8.0.0", "emr-spark-8.1.0"}) {
            assertFalse(EmrSparkSteps.installsHive(release, HIVE_AND_SPARK, objectMapper));
            assertFalse(EmrSparkSteps.installsHive(release, "[{\"Name\": \"HCatalog\"}]", objectMapper));
            assertNull(EmrSparkSteps.hive(query, release, HIVE_AND_SPARK, null, objectMapper));
        }
    }

    @Test
    void theClustersHiveEnvExportsAreItsHiveStepsEnvironment() {
        String configurations = """
                [{"Classification": "hive-env", "Configurations": [
                   {"Classification": "export", "Properties": {"HADOOP_HEAPSIZE": "2048"}}]},
                 {"Classification": "spark-env", "Configurations": [
                   {"Classification": "export", "Properties": {"PYSPARK_PYTHON": "/usr/bin/python3"}}]}]""";

        assertEquals(Map.of("HADOOP_HEAPSIZE", "2048"), EmrSparkSteps.hiveEnvironment(configurations, objectMapper));
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
    void aClustersHiveMetastoreServiceGetsItsCoreSiteThenItsHiveSiteWhichWins() {
        String configurations = """
                [{"Classification": "core-site", "Properties": {"fs.s3a.connection.maximum": "50",
                  "hive.exec.scratchdir": "/tmp/core"}},
                 {"Classification": "hive-site", "Properties": {"hive.exec.scratchdir": "/tmp/hive",
                  "hive.metastore.warehouse.dir": "s3://bucket/warehouse/"}},
                 {"Classification": "spark-hive-site", "Properties": {"hive.metastore.warehouse.dir": "s3://other/"}},
                 {"Classification": "tez-site", "Properties": {"tez.am.resource.memory.mb": "2048"}}]
                """;

        Map<String, String> settings = EmrSparkSteps.hiveMetastoreSettings(configurations, objectMapper);

        // Not spark-hive-site, Spark's own, nor tez-site, which the service does not read.
        assertEquals(Map.of("fs.s3a.connection.maximum", "50", "hive.exec.scratchdir", "/tmp/hive",
                "hive.metastore.warehouse.dir", "s3://bucket/warehouse/"), settings);
        assertEquals(List.of("fs.s3a.connection.maximum", "hive.exec.scratchdir", "hive.metastore.warehouse.dir"),
                List.copyOf(settings.keySet()));
        assertEquals(Map.of(), EmrSparkSteps.hiveMetastoreSettings(null, objectMapper));
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
    void theClustersHadoopClassificationsComeFromItsConfigurations() {
        // The Release Guide's mapred-site and hadoop-env examples, the later configuration winning.
        String configurations = """
                [{"Classification": "mapred-site", "Properties": {"mapred.tasktracker.map.tasks.maximum": "2",
                  "mapreduce.map.sort.spill.percent": "0.90"}},
                 {"Classification": "hadoop-log4j", "Properties": {"log4j.logger.org.apache.hadoop": "WARN"}},
                 {"Classification": "hadoop-env", "Properties": {"IGNORED": "1"}, "Configurations": [
                   {"Classification": "export", "Properties": {"HADOOP_USER_CLASSPATH_FIRST": "true",
                    "HADOOP_CLASSPATH": "/path/to/my.jar"}}]},
                 {"Classification": "mapred-site", "Properties": {"mapreduce.map.sort.spill.percent": "0.80"}},
                 {"Classification": "spark-env", "Configurations": [
                   {"Classification": "export", "Properties": {"PYSPARK_PYTHON": "/usr/bin/python3.11"}}]}]
                """;

        assertEquals(Map.of("mapred.tasktracker.map.tasks.maximum", "2", "mapreduce.map.sort.spill.percent", "0.80"),
                EmrSparkSteps.mapredSite(configurations, objectMapper));
        assertEquals(Map.of("log4j.logger.org.apache.hadoop", "WARN"),
                EmrSparkSteps.hadoopLog4j(configurations, objectMapper));
        assertEquals(Map.of("HADOOP_USER_CLASSPATH_FIRST", "true", "HADOOP_CLASSPATH", "/path/to/my.jar"),
                EmrSparkSteps.hadoopEnvironment(configurations, objectMapper));
        assertEquals(Map.of(), EmrSparkSteps.mapredSite(null, objectMapper));
        assertEquals(Map.of(), EmrSparkSteps.hadoopLog4j(null, objectMapper));
        assertEquals(Map.of(), EmrSparkSteps.hadoopEnvironment(null, objectMapper));
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
