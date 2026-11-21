package io.floci.aws.services.emrserverless;

import io.floci.aws.core.common.Html;
import io.floci.aws.testing.EmrServerlessRequests;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.testing.EmrServerlessRequests.emrServerless;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GetDashboardForJobRun through the REST API, and the page its link opens on Floci: the run's attempt, its state and
 * where its driver's logs go, as Floci keeps no Spark UI or Spark History Server to open.
 */
@QuarkusTest
class EmrServerlessDashboardIntegrationTest {

    private static final String REGION = "eu-west-1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/emr-serverless-job";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aRunsDashboardLinkOpensAPageWithItsStateAndWhereItsDriverLogsGo() {
        String applicationId = application();
        String jobRunId = run(applicationId, """
                , "configurationOverrides": {"monitoringConfiguration": {
                    "s3MonitoringConfiguration": {"logUri": "s3://bucket/logs/"},
                    "cloudWatchLoggingConfiguration": {"enabled": true, "logGroupName": "/floci/runs"}}}""");

        String url = emrServerless(REGION).when()
                .get("/applications/" + applicationId + "/jobruns/" + jobRunId + "/dashboard")
                .then().statusCode(200).extract().path("url");

        assertTrue(url.contains("/_aws/emr-serverless/dashboard?"), url);
        page(url).then().statusCode(200).contentType(containsString("text/html"))
                .body(containsString("<h1>Job run " + jobRunId + "</h1>"))
                .body(containsString("<tr><th>State</th><td>SUCCESS</td></tr>"))
                .body(containsString("<tr><th>Attempt</th><td>1</td></tr>"))
                .body(containsString("<tr><th>Driver logs in S3</th><td>s3://bucket/logs/applications/"
                        + applicationId + "/jobs/" + jobRunId + "/SPARK_DRIVER/</td></tr>"))
                .body(containsString("<tr><th>Driver logs in CloudWatch Logs</th><td>/floci/runs</td></tr>"))
                .body(containsString(Html.escape(EmrServerlessDashboardController.NO_UI)));
    }

    @Test
    void aRunsPageShowsWhereItsLogsWentEvenOnceItsApplicationSendsThemElsewhere() {
        String applicationId = application();
        emrServerless(REGION).body("""
                        {"clientToken": "%s", "monitoringConfiguration": {
                          "s3MonitoringConfiguration": {"logUri": "s3://bucket/before/"}}}
                        """.formatted(UUID.randomUUID()))
                .when().patch("/applications/" + applicationId).then().statusCode(200);
        String jobRunId = run(applicationId, "");
        String url = emrServerless(REGION).when()
                .get("/applications/" + applicationId + "/jobruns/" + jobRunId + "/dashboard")
                .then().statusCode(200).extract().path("url");
        emrServerless(REGION).when().post("/applications/" + applicationId + "/stop").then().statusCode(200);

        emrServerless(REGION).body("""
                        {"clientToken": "%s", "monitoringConfiguration": {
                          "s3MonitoringConfiguration": {"logUri": "s3://bucket/after/"}}}
                        """.formatted(UUID.randomUUID()))
                .when().patch("/applications/" + applicationId).then().statusCode(200);

        page(url).then().statusCode(200)
                .body(containsString("<td>s3://bucket/before/applications/" + applicationId + "/jobs/" + jobRunId
                        + "/SPARK_DRIVER/</td>"))
                .body(not(containsString("s3://bucket/after/")));
    }

    @Test
    void aLinkWhoseSignatureIsChangedOpensARefusal() {
        String applicationId = application();
        String jobRunId = run(applicationId, "");
        String url = emrServerless(REGION).when()
                .get("/applications/" + applicationId + "/jobruns/" + jobRunId + "/dashboard")
                .then().statusCode(200).extract().path("url");
        int signature = url.indexOf("signature=") + "signature=".length();
        String changed = url.substring(0, signature) + (url.charAt(signature) == '0' ? '1' : '0')
                + url.substring(signature + 1);

        page(changed).then().statusCode(403)
                .body(containsString(Html.escape(EmrServerlessDashboardController.INVALID)))
                .body(not(containsString(jobRunId + "</td>")));
    }

    @Test
    void aRunOrAttemptThatIsNotThereIsRefusedAsGetJobRunRefusesIt() {
        String applicationId = application();
        String jobRunId = run(applicationId, "");
        String dashboard = "/applications/" + applicationId + "/jobruns/" + jobRunId + "/dashboard";

        emrServerless(REGION).when().get("/applications/" + applicationId + "/jobruns/00fmissing/dashboard")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        emrServerless(REGION).queryParam("attempt", 2).when().get(dashboard)
                .then().statusCode(404).body("message", equalTo("Job run " + jobRunId + " has no attempt 2"));
        emrServerless(REGION).queryParam("attempt", 0).when().get(dashboard)
                .then().statusCode(400).body("__type", equalTo("ValidationException"));
        // A run is in its application's Region alone, as GetJobRun finds it.
        emrServerless("us-east-1").when().get(dashboard)
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    private String application() {
        String applicationId = EmrServerlessRequests.createSparkApplication(REGION, "emr-7.5.0");
        cleanup.register(() -> EmrServerlessRequests.deleteApplication(REGION, applicationId));
        return applicationId;
    }

    // A run, ended before it is returned, so its application stops when the test is done.
    private static String run(String applicationId, String extra) {
        String jobRunId = EmrServerlessRequests.startJobRun(REGION, applicationId, """
                {"clientToken": "%s", "executionRoleArn": "%s",
                 "jobDriver": {"sparkSubmit": {"entryPoint": "s3://bucket/jobs/app.py"}}%s}
                """.formatted(UUID.randomUUID(), ROLE, extra));
        EmrServerlessRequests.awaitJobRun(REGION, applicationId, jobRunId);
        return jobRunId;
    }

    // The page a link opens, asked for on the address this test's Floci listens on, which the link's base URL is not.
    private static Response page(String url) {
        URI link = URI.create(url);
        return given().urlEncodingEnabled(false).when().get(link.getRawPath() + "?" + link.getRawQuery());
    }
}
