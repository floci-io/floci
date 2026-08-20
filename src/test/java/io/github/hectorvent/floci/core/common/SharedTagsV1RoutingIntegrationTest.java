package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Routing guard for the REST tag endpoints several AWS services share.
 *
 * <p>Every assertion here is about which service answers, never about tag content: the
 * defect this exists for (lex00/floci#72) was a controller claiming
 * {@code /v1/tags/{resourceArn: .+}} for one service, so every other service's
 * {@code TagResource} on that path was answered - wrongly, and with that service's own
 * error text - by AppSync.
 *
 * <p>The tests assert on unknown resources deliberately. A 404 or 400 naming the RIGHT
 * service proves the request reached the right handler without needing a fixture, and it
 * is exactly the axis a greedy catch-all destroys.
 */
@QuarkusTest
class SharedTagsV1RoutingIntegrationTest {

    private static final String APPSYNC_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/appsync/aws4_request";
    private static final String BATCH_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/batch/aws4_request";

    @Test
    void appSyncStillOwnsAppSyncArnsOnTheSharedV1Path() {
        given()
            .header("Authorization", APPSYNC_AUTH)
            .contentType("application/json")
            .body("{\"tags\":{\"team\":\"platform\"}}")
        .when()
            .post("/v1/tags/arn:aws:appsync:us-east-1:000000000000:apis/no-such-api")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"))
            .body("message", containsString("GraphQL API not found"));
    }

    @Test
    void batchArnsReachBatchRatherThanAppSyncsCatchAll() {
        given()
            .header("Authorization", BATCH_AUTH)
            .contentType("application/json")
            .body("{\"tags\":{\"tofu-estate\":\"crossing\"}}")
        .when()
            .post("/v1/tags/arn:aws:batch:us-east-1:000000000000:job-queue/no-such-queue")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ClientException"))
            .body("message", containsString("Job queue not found"))
            .body("message", not(containsString("GraphQL API not found")));
    }

    @Test
    void batchComputeEnvironmentAndJobDefinitionArnsAlsoReachBatch() {
        given()
            .header("Authorization", BATCH_AUTH)
        .when()
            .get("/v1/tags/arn:aws:batch:us-east-1:000000000000:compute-environment/no-such-ce")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ClientException"))
            .body("message", containsString("Compute environment not found"));

        given()
            .header("Authorization", BATCH_AUTH)
        .when()
            .delete("/v1/tags/arn:aws:batch:us-east-1:000000000000:job-definition/no-such-def:1"
                    + "?tagKeys=tofu-estate")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ClientException"))
            .body("message", containsString("Job definition not found"));
    }

    /**
     * The path prefix is part of the dispatcher's lookup key. EKS registers a
     * {@link TagHandler} on the unversioned {@code /tags} prefix, so it must NOT be served
     * at {@code /v1/tags} - and Batch, registered on {@code /v1/tags}, must not be served at
     * {@code /tags}. Without prefix keying both would answer on both paths, which is the
     * same over-reach as the catch-all, one layer down.
     */
    @Test
    void eachPrefixServesOnlyTheServicesRegisteredForIt() {
        given()
            .header("Authorization", BATCH_AUTH)
        .when()
            .get("/v1/tags/arn:aws:eks:us-east-1:000000000000:cluster/some-cluster")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", containsString("Invalid resource ARN"));

        given()
            .header("Authorization", BATCH_AUTH)
        .when()
            .get("/tags/arn:aws:batch:us-east-1:000000000000:job-queue/no-such-queue")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", containsString("Invalid resource ARN"));
    }
}
