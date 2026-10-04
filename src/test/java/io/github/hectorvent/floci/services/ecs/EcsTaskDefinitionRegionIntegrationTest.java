package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;

/**
 * Task definitions are regional: a family registered in two regions has its own revisions in each,
 * and a region lists and describes only its own.
 */
@QuarkusTest
class EcsTaskDefinitionRegionIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AmazonEC2ContainerServiceV20141113.";
    private static final String HOME = "eu-west-1";
    private static final String OTHER = "us-west-2";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification ecs(String region, String action) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260215/" + region
                        + "/ecs/aws4_request, SignedHeaders=host, Signature=abc")
                .header("X-Amz-Target", TARGET_PREFIX + action);
    }

    private static String register(String region, String family) {
        return ecs(region, "RegisterTaskDefinition")
            .body("""
                {"family": "%s", "containerDefinitions": [{"name": "app", "image": "nginx", "memory": 128}]}
                """.formatted(family))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("taskDefinition.revision", equalTo(1))
            .body("taskDefinition.taskDefinitionArn", containsString(":" + region + ":"))
            .extract().path("taskDefinition.taskDefinitionArn");
    }

    @Test
    void taskDefinitionsStayInTheirRegion() {
        String family = "region-td-" + UUID.randomUUID().toString().substring(0, 8);
        String homeArn = register(HOME, family);
        String otherArn = register(OTHER, family);

        ecs(HOME, "ListTaskDefinitions")
            .body("{\"familyPrefix\": \"" + family + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("taskDefinitionArns", contains(homeArn));
        ecs(OTHER, "ListTaskDefinitions")
            .body("{\"familyPrefix\": \"" + family + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("taskDefinitionArns", contains(otherArn));
        ecs("ap-southeast-1", "ListTaskDefinitionFamilies")
            .body("{\"familyPrefix\": \"" + family + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("families", empty());
        ecs(OTHER, "DescribeTaskDefinition")
            .body("{\"taskDefinition\": \"" + homeArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", endsWith("ClientException"));

        for (String region : new String[] {HOME, OTHER}) {
            ecs(region, "DeregisterTaskDefinition")
                .body("{\"taskDefinition\": \"" + family + ":1\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200);
            ecs(region, "DeleteTaskDefinitions")
                .body("{\"taskDefinitions\": [\"" + family + ":1\"]}")
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }
    }
}
