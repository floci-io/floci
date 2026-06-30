package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class IotJobsIntegrationTest {

    @Test
    void jobControlPlaneCreatesJobAndMaterializesThingExecution() {
        String thingArn = given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/things/mvp2-job-thing")
        .then()
            .statusCode(200)
            .extract()
            .path("thingArn");

        given()
            .contentType("application/json")
            .body("""
                {
                  "targets": ["%s"],
                  "document": "{\\"operation\\":\\"reboot\\"}",
                  "description": "mvp2 job",
                  "targetSelection": "SNAPSHOT"
                }
                """.formatted(thingArn))
        .when()
            .put("/jobs/mvp2-job")
        .then()
            .statusCode(200)
            .body("jobId", equalTo("mvp2-job"))
            .body("jobArn", notNullValue())
            .body("description", equalTo("mvp2 job"));

        given()
        .when()
            .get("/jobs/mvp2-job")
        .then()
            .statusCode(200)
            .body("job.jobId", equalTo("mvp2-job"))
            .body("job.status", equalTo("IN_PROGRESS"))
            .body("job.targets", hasItem(thingArn));

        given()
        .when()
            .get("/jobs")
        .then()
            .statusCode(200)
            .body("jobs.jobId", hasItem("mvp2-job"));

        given()
        .when()
            .get("/things/mvp2-job-thing/jobs")
        .then()
            .statusCode(200)
            .body("executionSummaries.jobId", hasItem("mvp2-job"));
    }

    @Test
    void jobsDataPlaneStartsAndUpdatesPendingExecution() {
        String thingArn = given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/things/mvp2-jobs-data-thing")
        .then()
            .statusCode(200)
            .extract()
            .path("thingArn");

        given()
            .contentType("application/json")
            .body("""
                {
                  "targets": ["%s"],
                  "document": "{\\"operation\\":\\"rotate\\"}"
                }
                """.formatted(thingArn))
        .when()
            .put("/jobs/mvp2-jobs-data-job")
        .then()
            .statusCode(200);

        given()
            .queryParam("endpointType", "iot:Jobs")
        .when()
            .get("/endpoint")
        .then()
            .statusCode(200)
            .body("endpointAddress", notNullValue());

        given()
        .when()
            .get("/things/mvp2-jobs-data-thing/jobs")
        .then()
            .statusCode(200)
            .body("queuedJobs.jobId", hasItem("mvp2-jobs-data-job"));

        given()
            .contentType("application/json")
            .body("{\"statusDetails\":{\"phase\":\"download\"}}")
        .when()
            .put("/things/mvp2-jobs-data-thing/jobs/$next")
        .then()
            .statusCode(200)
            .body("execution.jobId", equalTo("mvp2-jobs-data-job"))
            .body("execution.status", equalTo("IN_PROGRESS"))
            .body("execution.statusDetails.phase", equalTo("download"));

        given()
            .contentType("application/json")
            .body("""
                {
                  "status": "SUCCEEDED",
                  "expectedVersion": 2,
                  "includeJobExecutionState": true,
                  "includeJobDocument": true
                }
                """)
        .when()
            .post("/things/mvp2-jobs-data-thing/jobs/mvp2-jobs-data-job")
        .then()
            .statusCode(200)
            .body("executionState.status", equalTo("SUCCEEDED"))
            .body("executionState.versionNumber", equalTo(3))
            .body("jobDocument", equalTo("{\"operation\":\"rotate\"}"));

        given()
        .when()
            .get("/things/mvp2-jobs-data-thing/jobs/mvp2-jobs-data-job")
        .then()
            .statusCode(200)
            .body("execution.status", equalTo("SUCCEEDED"))
            .body("execution.versionNumber", equalTo(3));
    }
}
