package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/**
 * DescribeTaskDefinition must return a top-level {@code tags} array when the request
 * asks for {@code "include": ["TAGS"]}, and must omit the key otherwise.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EcsTaskDefinitionTagsIntegrationTest {

    private static final String TARGET_PREFIX = "AmazonEC2ContainerServiceV20141113.";
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String FAMILY = "taskdef-tags-family";
    private static final String UNTAGGED_FAMILY = "taskdef-no-tags-family";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static io.restassured.specification.RequestSpecification ecs(String action) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + action);
    }

    private static String registerBody(String family, String tags) {
        return """
            {
                "family": "%s",
                "containerDefinitions": [
                    {"name": "app", "image": "nginx:latest", "essential": true}
                ]%s
            }
            """.formatted(family, tags);
    }

    @Test
    @Order(1)
    void registerTaskDefinitionWithTags() {
        ecs("RegisterTaskDefinition")
            .body(registerBody(FAMILY, """
                ,
                "tags": [{"key": "tofu-estate", "value": "probe1"}]"""))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("taskDefinition.family", equalTo(FAMILY));
    }

    @Test
    @Order(2)
    void describeTaskDefinitionWithIncludeTagsReturnsTags() {
        ecs("DescribeTaskDefinition")
            .body("""
                {"taskDefinition": "%s", "include": ["TAGS"]}
                """.formatted(FAMILY))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("taskDefinition.family", equalTo(FAMILY))
            .body("tags", hasSize(1))
            .body("tags[0].key", equalTo("tofu-estate"))
            .body("tags[0].value", equalTo("probe1"));
    }

    @Test
    @Order(3)
    void describeTaskDefinitionWithoutIncludeOmitsTags() {
        ecs("DescribeTaskDefinition")
            .body("""
                {"taskDefinition": "%s"}
                """.formatted(FAMILY))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("taskDefinition.family", equalTo(FAMILY))
            .body("tags", nullValue());
    }

    @Test
    @Order(4)
    void includeTagsOnUntaggedTaskDefinitionReturnsEmptyArray() {
        ecs("RegisterTaskDefinition")
            .body(registerBody(UNTAGGED_FAMILY, ""))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        ecs("DescribeTaskDefinition")
            .body("""
                {"taskDefinition": "%s", "include": ["TAGS"]}
                """.formatted(UNTAGGED_FAMILY))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("tags", hasSize(0));
    }

    @Test
    @Order(5)
    void tagsMatchListTagsForResource() {
        String arn = ecs("DescribeTaskDefinition")
            .body("""
                {"taskDefinition": "%s"}
                """.formatted(FAMILY))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("taskDefinition.taskDefinitionArn");

        ecs("ListTagsForResource")
            .body("""
                {"resourceArn": "%s"}
                """.formatted(arn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("tags", hasSize(1))
            .body("tags[0].key", equalTo("tofu-estate"));
    }
}
