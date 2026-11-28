package io.floci.aws.services.glue;

import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.redshift.RedshiftConnections;
import io.floci.aws.services.redshift.RedshiftService;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.GlueRequests;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * End to end: a Glue 5.0 {@code glueetl} Spark job reads a table from a Floci Redshift cluster through a
 * Glue connection and temporary S3 staging directory, transforms the rows, and writes them into a destination
 * table on Redshift. Uses AWS's large Glue 5.0 image and runs in nightly checks.
 */
@QuarkusTest
@TestProfile(GlueRedshiftEtlNightlyIntegrationTest.DockerGlueRunnerProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
class GlueRedshiftEtlNightlyIntegrationTest {

    public static final class DockerGlueRunnerProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.glue.runner-mode", "docker");
        }
    }

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String CLUSTER_ID = "glue-etl-rs";
    private static final String ROLE_NAME = "glue-redshift-etl-role";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT_ID + ":role/" + ROLE_NAME;
    private static final String BUCKET = "glue-redshift-etl-bucket";
    private static final String JDBC_URL = "jdbc:redshift://" + CLUSTER_ID
            + ".abc123.us-east-1.redshift.amazonaws.com:5439/dev";
    private static final String TRUST_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
              "Principal":{"Service":["glue.amazonaws.com","redshift.amazonaws.com"]},
              "Action":"sts:AssumeRole"}]}""";
    private static final String ETL_SCRIPT = """
            import sys
            from awsglue.context import GlueContext
            from awsglue.job import Job
            from awsglue.transforms import *
            from awsglue.utils import getResolvedOptions
            from pyspark.context import SparkContext
            from pyspark.sql import functions as F

            args = getResolvedOptions(sys.argv, ["JOB_NAME", "connection_name", "temp_dir"])
            sc = SparkContext()
            glueContext = GlueContext(sc)
            job = Job(glueContext)
            job.init(args["JOB_NAME"], args)

            source = glueContext.create_dynamic_frame.from_options(
                connection_type="redshift",
                connection_options={
                    "useConnectionProperties": "true",
                    "connectionName": args["connection_name"],
                    "dbtable": "public.orders_in",
                    "redshiftTmpDir": args["temp_dir"],
                },
            )
            transformed = source.toDF().withColumn("doubled", F.col("amount") * 2)
            out_frame = source.fromDF(transformed, glueContext, "out_frame")
            glueContext.write_dynamic_frame.from_options(
                frame=out_frame,
                connection_type="redshift",
                connection_options={
                    "useConnectionProperties": "true",
                    "connectionName": args["connection_name"],
                    "dbtable": "public.orders_out",
                    "redshiftTmpDir": args["temp_dir"],
                },
            )
            job.commit()
            """;

    @Inject
    RedshiftService redshiftService;

    @Inject
    S3Service s3Service;

    @Inject
    IamService iamService;

    private Cluster cluster;

    @BeforeAll
    void setUpResources() throws Exception {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(),
                "Docker daemon must be available for Glue Redshift ETL integration tests");
        RestAssuredJsonUtils.configureAwsContentTypes();
        iamService.createRole(ROLE_NAME, "/", TRUST_POLICY, null, 3600, null);
        s3Service.createBucket(BUCKET, REGION);
        s3Service.putObject(BUCKET, "scripts/redshift_etl.py",
                ETL_SCRIPT.getBytes(StandardCharsets.UTF_8), "text/x-python", Map.of());
        cluster = redshiftService.createCluster(CLUSTER_ID, "dc2.large", "admin", "Secret123");
        try (Connection connection = RedshiftConnections.waitForConnection(cluster);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public.orders_in (id integer, amount integer)");
            statement.execute("INSERT INTO public.orders_in VALUES (1, 10), (2, 25)");
            statement.execute("CREATE TABLE public.orders_out (id integer, amount integer, doubled integer)");
        }
    }

    @AfterAll
    void tearDownResources() throws InterruptedException {
        if (DockerAvailability.isAvailable() && cluster != null) {
            redshiftService.deleteCluster(CLUSTER_ID);
        }
    }

    private static ValidatableResponse call(String action, String body) {
        return GlueRequests.call(action, ACCOUNT_ID, REGION, body);
    }

    @Test
    void sparkJobReadsFromAndWritesToRedshiftThroughTempS3() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String connectionName = "rs-etl-" + suffix;
        String jobName = "rs-etl-job-" + suffix;
        call("CreateConnection", """
                {"ConnectionInput": {"Name": "%s", "ConnectionType": "JDBC",
                 "ConnectionProperties": {"JDBC_CONNECTION_URL": "%s", "USERNAME": "admin", "PASSWORD": "Secret123"}}}
                """.formatted(connectionName, JDBC_URL)).statusCode(200);
        call("CreateJob", """
                {"Name": "%s", "Role": "%s", "GlueVersion": "5.0",
                 "Connections": {"Connections": ["%s"]},
                 "Command": {"Name": "glueetl", "ScriptLocation": "s3://%s/scripts/redshift_etl.py", "PythonVersion": "3"},
                 "DefaultArguments": {"--connection_name": "%s", "--temp_dir": "s3://%s/tmp/%s/"}}
                """.formatted(jobName, ROLE_ARN, connectionName, BUCKET, connectionName, BUCKET, suffix))
                .statusCode(200);

        String runId = call("StartJobRun", "{\"JobName\": \"" + jobName + "\"}")
                .statusCode(200).extract().path("JobRunId");
        Awaitility.await().atMost(Duration.ofMinutes(5)).pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> call("GetJobRun",
                        "{\"JobName\": \"" + jobName + "\", \"RunId\": \"" + runId + "\"}")
                        .body("JobRun.JobRunState", equalTo("SUCCEEDED")));

        List<String> rows = new ArrayList<>();
        try (Connection connection = RedshiftConnections.waitForConnection(cluster);
             Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(
                     "SELECT id || ':' || amount || ':' || doubled FROM public.orders_out ORDER BY id")) {
            while (results.next()) {
                rows.add(results.getString(1));
            }
        }
        assertThat(rows, contains("1:10:20", "2:25:50"));
    }

    @Test
    void sparkJobFailsWhenRedshiftCredentialsAreRejected() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String connectionName = "rs-bad-" + suffix;
        String jobName = "rs-bad-job-" + suffix;
        call("CreateConnection", """
                {"ConnectionInput": {"Name": "%s", "ConnectionType": "JDBC",
                 "ConnectionProperties": {"JDBC_CONNECTION_URL": "%s", "USERNAME": "admin", "PASSWORD": "wrong"}}}
                """.formatted(connectionName, JDBC_URL)).statusCode(200);
        call("CreateJob", """
                {"Name": "%s", "Role": "%s", "GlueVersion": "5.0",
                 "Connections": {"Connections": ["%s"]},
                 "Command": {"Name": "glueetl", "ScriptLocation": "s3://%s/scripts/redshift_etl.py", "PythonVersion": "3"},
                 "DefaultArguments": {"--connection_name": "%s", "--temp_dir": "s3://%s/tmp/%s/"}}
                """.formatted(jobName, ROLE_ARN, connectionName, BUCKET, connectionName, BUCKET, suffix))
                .statusCode(200);

        String runId = call("StartJobRun", "{\"JobName\": \"" + jobName + "\"}")
                .statusCode(200).extract().path("JobRunId");
        Awaitility.await().atMost(Duration.ofMinutes(5)).pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> call("GetJobRun",
                        "{\"JobName\": \"" + jobName + "\", \"RunId\": \"" + runId + "\"}")
                        .body("JobRun.JobRunState", equalTo("FAILED"))
                        .body("JobRun.ErrorMessage", notNullValue()));
    }
}
