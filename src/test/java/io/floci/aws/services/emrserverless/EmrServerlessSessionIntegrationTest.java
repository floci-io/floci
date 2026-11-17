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
import static io.floci.aws.testing.EmrServerlessRequests.tagsPath;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EMR Serverless sessions through the REST API: StartSession, GetSession, ListSessions, GetSessionEndpoint,
 * TerminateSession and GetResourceDashboard, tags on a session's ARN, and StopApplication ending an application's
 * sessions.
 */
@QuarkusTest
class EmrServerlessSessionIntegrationTest {

    private static final String REGION = "eu-west-1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/emr-serverless-session";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aSessionStartsIdleAndIsReportedListedConnectedToAndTerminated() {
        String applicationId = application("emr-7.13.0", true);

        Response started = emrServerless(REGION).body("""
                        {"clientToken": "%s", "executionRoleArn": "%s", "name": "notebook", "idleTimeoutMinutes": 30,
                         "configurationOverrides": {"runtimeConfiguration": [{"classification": "spark-defaults",
                           "properties": {"spark.executor.memory": "4g"}}]},
                         "tags": {"team": "data"}}
                        """.formatted(UUID.randomUUID(), ROLE))
                .when().post("/applications/" + applicationId + "/sessions")
                .then().statusCode(200)
                .body("applicationId", equalTo(applicationId))
                .body("arn", containsString(":/applications/" + applicationId + "/sessions/"))
                .extract().response();
        String sessionId = started.path("sessionId");
        String arn = started.path("arn");
        assertTrue(arn.startsWith("arn:aws:emr-serverless:eu-west-1:000000000000:/applications/"), arn);

        emrServerless(REGION).when().get("/applications/" + applicationId + "/sessions/" + sessionId)
                .then().statusCode(200)
                .body("session.sessionId", equalTo(sessionId))
                .body("session.arn", equalTo(arn))
                .body("session.name", equalTo("notebook"))
                .body("session.state", equalTo("IDLE"))
                .body("session.releaseLabel", equalTo("emr-7.13.0"))
                .body("session.executionRoleArn", equalTo(ROLE))
                .body("session.idleTimeoutMinutes", equalTo(30))
                .body("session.configurationOverrides.runtimeConfiguration[0].properties", notNullValue())
                .body("session.tags.team", equalTo("data"))
                .body("session.createdAt", notNullValue())
                .body("session.idleSince", notNullValue())
                .body("session.endedAt", nullValue());

        emrServerless(REGION).when().get("/applications/" + applicationId + "/sessions?states=IDLE")
                .then().statusCode(200)
                .body("sessions", hasSize(1))
                .body("sessions[0].sessionId", equalTo(sessionId))
                .body("sessions[0].state", equalTo("IDLE"));

        emrServerless(REGION).when().get("/applications/" + applicationId + "/sessions/" + sessionId + "/endpoint")
                .then().statusCode(200)
                .body("applicationId", equalTo(applicationId))
                .body("sessionId", equalTo(sessionId))
                .body("endpoint", endsWith("/_aws/emr-serverless/sessions/" + applicationId + "/" + sessionId))
                .body("authToken", matchesPattern("[0-9a-f]{64}"))
                .body("authTokenExpiresAt", notNullValue());

        emrServerless(REGION).when().delete("/applications/" + applicationId + "/sessions/" + sessionId)
                .then().statusCode(200)
                .body("applicationId", equalTo(applicationId))
                .body("sessionId", equalTo(sessionId));
        emrServerless(REGION).when().get("/applications/" + applicationId + "/sessions/" + sessionId)
                .then().statusCode(200)
                .body("session.state", equalTo("TERMINATED"))
                .body("session.endedAt", notNullValue());
        emrServerless(REGION).when().get("/applications/" + applicationId + "/sessions?states=IDLE")
                .then().statusCode(200)
                .body("sessions", hasSize(0));
    }

    @Test
    void aSessionNeedsSessionsEnabledAndARecentEnoughRelease() {
        emrServerless(REGION).body(start())
                .when().post("/applications/" + application("emr-7.13.0", false) + "/sessions")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        emrServerless(REGION).body(start())
                .when().post("/applications/" + application("emr-7.12.0", true) + "/sessions")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        emrServerless(REGION).body("{\"clientToken\": \"" + UUID.randomUUID() + "\"}")
                .when().post("/applications/" + application("emr-7.13.0", true) + "/sessions")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("executionRoleArn"));
        emrServerless(REGION).body("""
                        {"clientToken": "%s", "executionRoleArn": "%s",
                         "configurationOverrides": {"runtimeConfiguration": "not-a-list"}}
                        """.formatted(UUID.randomUUID(), ROLE))
                .when().post("/applications/" + application("emr-7.13.0", true) + "/sessions")
                .then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
        emrServerless(REGION).when().get("/applications/" + application("emr-7.13.0", true) + "/sessions/00fnone")
                .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void aSessionsArnTakesTags() {
        String applicationId = application("emr-7.13.0", true);
        String arn = emrServerless(REGION).body(start())
                .when().post("/applications/" + applicationId + "/sessions")
                .then().statusCode(200).extract().path("arn");

        emrServerless(REGION).urlEncodingEnabled(false).body("{\"tags\": {\"env\": \"dev\"}}")
                .when().post(tagsPath(arn))
                .then().statusCode(200);
        emrServerless(REGION).urlEncodingEnabled(false).when().get(tagsPath(arn))
                .then().statusCode(200)
                .body("tags.env", equalTo("dev"));
        emrServerless(REGION).urlEncodingEnabled(false).when().delete(tagsPath(arn) + "?tagKeys=env")
                .then().statusCode(200);
        emrServerless(REGION).urlEncodingEnabled(false).when().get(tagsPath(arn))
                .then().statusCode(200)
                .body("tags.env", nullValue());
    }

    @Test
    void stoppingTheApplicationTerminatesItsSessions() {
        String applicationId = application("emr-7.13.0", true);
        String sessionId = emrServerless(REGION).body(start())
                .when().post("/applications/" + applicationId + "/sessions")
                .then().statusCode(200).extract().path("sessionId");

        emrServerless(REGION).when().post("/applications/" + applicationId + "/stop").then().statusCode(200);

        emrServerless(REGION).when().get("/applications/" + applicationId + "/sessions/" + sessionId)
                .then().statusCode(200)
                .body("session.state", equalTo("TERMINATED"))
                .body("session.stateDetails", equalTo(EmrServerlessSessionService.APPLICATION_STOPPED));
    }

    @Test
    void aSessionsResourceDashboardLinkOpensAPageAboutIt() {
        String applicationId = application("emr-7.13.0", true);
        String sessionId = emrServerless(REGION).body(start())
                .when().post("/applications/" + applicationId + "/sessions")
                .then().statusCode(200).extract().path("sessionId");

        String url = emrServerless(REGION).when()
                .get("/applications/" + applicationId + "/dashboard?resourceType=SESSION&resourceId=" + sessionId)
                .then().statusCode(200).extract().path("url");

        page(url).then().statusCode(200).contentType(containsString("text/html"))
                .body(containsString("<h1>Session " + sessionId + "</h1>"))
                .body(containsString("<tr><th>State</th><td>IDLE</td></tr>"))
                .body(containsString(Html.escape(EmrServerlessDashboardController.NO_SESSION_UI)));
        emrServerless(REGION).when()
                .get("/applications/" + applicationId + "/dashboard?resourceType=JOB_RUN&resourceId=" + sessionId)
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    private String application(String releaseLabel, boolean sessionEnabled) {
        String applicationId = emrServerless(REGION).body("""
                        {"name": "sessions-%s", "releaseLabel": "%s", "type": "SPARK", "clientToken": "%s",
                         "interactiveConfiguration": {"sessionEnabled": %s}}
                        """.formatted(UUID.randomUUID(), releaseLabel, UUID.randomUUID(), sessionEnabled))
                .when().post("/applications")
                .then().statusCode(200).extract().path("applicationId");
        cleanup.register(() -> EmrServerlessRequests.deleteApplication(REGION, applicationId));
        return applicationId;
    }

    private static String start() {
        return "{\"clientToken\": \"" + UUID.randomUUID() + "\", \"executionRoleArn\": \"" + ROLE + "\"}";
    }

    // The page a link opens, asked for on the address this test's Floci listens on, which the link's base URL is not.
    private static Response page(String url) {
        URI link = URI.create(url);
        return given().urlEncodingEnabled(false).when().get(link.getRawPath() + "?" + link.getRawQuery());
    }
}
