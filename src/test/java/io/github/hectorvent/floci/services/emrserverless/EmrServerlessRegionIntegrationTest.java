package io.github.hectorvent.floci.services.emrserverless;

import io.github.hectorvent.floci.testing.EmrServerlessRequests;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.testing.EmrServerlessRequests.tagsPath;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class EmrServerlessRegionIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification emr(String region) {
        return EmrServerlessRequests.emrServerless(region).urlEncodingEnabled(false);
    }

    @Test
    void applicationsAndClientTokensStayInTheirCreatingRegion() {
        String token = UUID.randomUUID().toString();
        String body = """
                {"name":"regional","releaseLabel":"emr-7.5.0","type":"SPARK",
                 "clientToken":"%s","tags":{"team":"data"}}
                """.formatted(token);

        Response east = emr("us-east-1").body(body).when().post("/applications");
        east.then().statusCode(200).body("arn", startsWith("arn:aws:emr-serverless:us-east-1:"));
        String eastId = east.jsonPath().getString("applicationId");
        String eastArn = east.jsonPath().getString("arn");

        Response west = emr("eu-west-1").body(body).when().post("/applications");
        west.then().statusCode(200).body("arn", startsWith("arn:aws:emr-serverless:eu-west-1:"))
                .body("applicationId", not(equalTo(eastId)));
        String westId = west.jsonPath().getString("applicationId");
        String westArn = west.jsonPath().getString("arn");

        emr("us-east-1").body(body).when().post("/applications")
                .then().statusCode(200).body("applicationId", equalTo(eastId));
        emr("eu-west-1").body(body).when().post("/applications")
                .then().statusCode(200).body("applicationId", equalTo(westId));

        emr("us-east-1").when().get("/applications/" + eastId)
                .then().statusCode(200).body("application.arn", equalTo(eastArn));
        emr("eu-west-1").when().get("/applications/" + westId)
                .then().statusCode(200).body("application.arn", equalTo(westArn));
        emr("eu-west-1").when().get("/applications/" + eastId)
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        emr("us-east-1").when().get("/applications/" + westId)
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));

        emr("us-east-1").when().get("/applications")
                .then().statusCode(200)
                .body("applications.id", hasItem(eastId))
                .body("applications.id", not(hasItem(westId)));
        emr("eu-west-1").when().get("/applications")
                .then().statusCode(200)
                .body("applications.id", hasItem(westId))
                .body("applications.id", not(hasItem(eastId)));

        emr("us-east-1").when().get(tagsPath(eastArn))
                .then().statusCode(200).body("tags.team", equalTo("data"));
        emr("eu-west-1").when().get(tagsPath(eastArn))
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        emr("eu-west-1").body("{\"tags\":{\"team\":\"wrong-region\"}}")
                .when().post(tagsPath(eastArn))
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        emr("us-east-1").when().get(tagsPath(eastArn))
                .then().statusCode(200).body("tags.team", equalTo("data"));
    }

    @Test
    void jobRunTagsStayInTheRunsRegion() {
        String applicationId = emr("us-east-1").body("""
                        {"name":"regional-runs","releaseLabel":"emr-7.5.0","type":"SPARK","clientToken":"%s"}
                        """.formatted(UUID.randomUUID()))
                .when().post("/applications")
                .then().statusCode(200).extract().path("applicationId");
        String runArn = emr("us-east-1").body("""
                        {"clientToken":"%s","executionRoleArn":"arn:aws:iam::000000000000:role/emr-serverless-job",
                         "jobDriver":{"sparkSubmit":{"entryPoint":"s3://bucket/jobs/app.jar"}},"tags":{"team":"data"}}
                        """.formatted(UUID.randomUUID()))
                .when().post("/applications/" + applicationId + "/jobruns")
                .then().statusCode(200).extract().path("arn");

        emr("eu-west-1").when().get(tagsPath(runArn))
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        emr("eu-west-1").body("{\"tags\":{\"team\":\"wrong-region\"}}")
                .when().post(tagsPath(runArn))
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        emr("eu-west-1").when().delete(tagsPath(runArn) + "?tagKeys=team")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        emr("us-east-1").when().get(tagsPath(runArn))
                .then().statusCode(200).body("tags.team", equalTo("data"));
    }
}
