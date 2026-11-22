package io.floci.aws.services.emrcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.emrserverless.SparkJobRunner;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmrContainersSparkJobsTest {

    private static final String ARN =
            "arn:aws:emr-containers:eu-west-1:123456789012:/virtualclusters/vc1/jobruns/run1";
    private static final String ROLE = "arn:aws:iam::123456789012:role/emr-job";

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode run(String jobDriver, String configurationOverrides) throws Exception {
        ObjectNode run = (ObjectNode) mapper.readTree("""
                {"id": "run1", "arn": "%s", "releaseLabel": "emr-7.2.0-20240610", "executionRoleArn": "%s",
                 "jobDriver": %s}
                """.formatted(ARN, ROLE, jobDriver));
        if (configurationOverrides != null) {
            run.set("configurationOverrides", mapper.readTree(configurationOverrides));
        }
        return run;
    }

    @Test
    void releaseLabelNamesItsImagesRepositoryAndTag() {
        assertEquals("emr-7.2.0:20240610", EmrContainersSparkJobs.containerImageTag("emr-7.2.0-20240610"));
        assertEquals("emr-7.2.0:latest", EmrContainersSparkJobs.containerImageTag("emr-7.2.0-latest"));
        assertEquals("emr-7.2.0-java11:latest", EmrContainersSparkJobs.containerImageTag("emr-7.2.0-java11-latest"));
        assertEquals("emr-7.2.0-spark-rapids:20240610",
                EmrContainersSparkJobs.containerImageTag("emr-7.2.0-spark-rapids-20240610"));
        assertEquals("emr-spark-8.0.0:20260421", EmrContainersSparkJobs.containerImageTag("emr-spark-8.0.0-20260421"));
        assertEquals("emr-7.2.0:latest", EmrContainersSparkJobs.containerImageTag("emr-7.2.0"));
    }

    @Test
    void imageIsTheReleasesBaseImageOnEcrPublicUnlessATemplateNamesAnother() {
        assertEquals("public.ecr.aws/emr-on-eks/spark/emr-7.13.0:latest",
                EmrContainersSparkJobs.image(null, "emr-7.13.0-latest"));
        assertEquals("registry.internal/emr-on-eks/spark/emr-7.2.0:20240610",
                EmrContainersSparkJobs.image("registry.internal/emr-on-eks/spark/%s", "emr-7.2.0-20240610"));
    }

    @Test
    void sparkSubmitRunsItsParametersEntryPointAndArgumentsInClientMode() throws Exception {
        ObjectNode run = run("""
                {"sparkSubmitJobDriver": {"entryPoint": "s3://bucket/pi.py", "entryPointArguments": ["10", "a b"],
                  "sparkSubmitParameters": "--conf spark.executor.instances=2 --archives s3://bucket/env.tar.gz#env"}}
                """, """
                {"applicationConfiguration": [
                   {"classification": "spark-defaults", "properties": {"spark.driver.memory": "2G"}},
                   {"classification": "spark-log4j", "properties": {"rootLogger.level": "WARN"}},
                   {"classification": "spark-defaults", "properties": {"spark.sql.shuffle.partitions": "4"}}]}
                """);

        SparkJobRunner.Job job = EmrContainersSparkJobs.job(run, null, "floci-net");

        assertEquals("emr-containers", job.service());
        assertEquals("run1", job.runId());
        assertEquals("123456789012", job.accountId());
        assertEquals("eu-west-1", job.region());
        assertEquals("emr-7.2.0-20240610", job.releaseLabel());
        assertEquals(SparkJobRunner.Engine.SPARK, job.engine());
        assertEquals("public.ecr.aws/emr-on-eks/spark/emr-7.2.0:20240610", job.imageTemplate());
        assertNull(job.customImage());
        assertEquals("floci-net", job.dockerNetwork());
        assertEquals(ROLE, job.executionRole());
        assertEquals(Map.of("spark.driver.memory", "2G", "spark.sql.shuffle.partitions", "4",
                "spark.submit.deployMode", "client"), job.sparkDefaults());
        assertEquals(List.of("--conf", "spark.executor.instances=2", "--archives", "s3://bucket/env.tar.gz#env",
                "s3://bucket/pi.py", "10", "a b"), job.application());
        assertNull(job.command());
        assertEquals(Duration.ZERO, job.timeout(), "a run of EMR on EKS has no timeout");
        assertEquals(List.of("s3://bucket/env.tar.gz#env"), job.archives());
        assertTrue(job.environment().isEmpty());
        assertNull(job.executors());
    }

    @Test
    void sparkSqlRunsItsFileWithItsParameters() throws Exception {
        ObjectNode run = run("""
                {"sparkSqlJobDriver": {"entryPoint": "s3://bucket/query.sql",
                  "sparkSqlParameters": "--conf spark.executor.instances=2 -hivevar table=reviews"}}
                """, null);

        SparkJobRunner.Job job = EmrContainersSparkJobs.job(run, null, null);

        assertEquals(List.of("spark-sql", "--conf", "spark.executor.instances=2", "-hivevar", "table=reviews", "-f",
                "s3://bucket/query.sql"), job.command());
        assertTrue(job.application().isEmpty());
        assertEquals(Map.of("spark.submit.deployMode", "client"), job.sparkDefaults());
    }

    @Test
    void driversImageAndEnvironmentComeFromItsKubernetesProperties() throws Exception {
        String parameters = "--conf spark.kubernetes.driver.container.image=custom/driver:1"
                + " --conf spark.kubernetes.driverEnv.PYSPARK_PYTHON=./env/bin/python";
        ObjectNode run = run("""
                {"sparkSubmitJobDriver": {"entryPoint": "local:///app/job.py", "sparkSubmitParameters": "%s"}}
                """.formatted(parameters), """
                {"applicationConfiguration": [{"classification": "spark-defaults",
                   "properties": {"spark.kubernetes.container.image": "custom/all:1"}}]}
                """);

        SparkJobRunner.Job job = EmrContainersSparkJobs.job(run, null, null);

        assertEquals("custom/driver:1", job.customImage(), "the driver's own image wins over the job's");
        assertEquals(Map.of("PYSPARK_PYTHON", "./env/bin/python"), job.environment());

        ObjectNode onlyJobImage = run("""
                {"sparkSubmitJobDriver": {"entryPoint": "local:///app/job.py",
                  "sparkSubmitParameters": "--conf spark.kubernetes.container.image=custom/all:2"}}
                """, null);
        assertEquals("custom/all:2", EmrContainersSparkJobs.job(onlyJobImage, null, null).customImage());
    }
}
