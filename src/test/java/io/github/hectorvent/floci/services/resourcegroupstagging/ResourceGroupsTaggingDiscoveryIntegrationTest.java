package io.github.hectorvent.floci.services.resourcegroupstagging;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * GetResources, GetTagKeys and GetTagValues see the tags a resource's own service holds, so a
 * queue, function or log group tagged through SQS, Lambda or CloudWatch Logs is discoverable
 * without ever being tagged through the Resource Groups Tagging API.
 */
@QuarkusTest
class ResourceGroupsTaggingDiscoveryIntegrationTest {

    private static final String TAGGING_TARGET = "ResourceGroupsTaggingAPI_20170126.";
    private static final String JSON_1_0 = "application/x-amz-json-1.0";
    private static final String JSON_1_1 = "application/x-amz-json-1.1";
    private static final String ARN_PREFIX = "arn:aws:%s:us-east-1:000000000000:";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void sqsQueueIsDiscoveredByTypeAndItsOwnTags() {
        String name = "discovery-" + unique();
        String marker = unique();
        String arn = ARN_PREFIX.formatted("sqs") + name;
        String queueUrl = sqs("CreateQueue", """
                {"QueueName": "%s", "tags": {"fd": "%s", "pn": "p1", "pt": "t1"}}
                """.formatted(name, marker))
            .then()
            .statusCode(200)
            .extract().path("QueueUrl");

        getResources("""
                {"ResourceTypeFilters": ["sqs:queue"], "TagFilters": [%s, %s, %s]}
                """.formatted(tagFilter("fd", marker), tagFilter("pn", "p1"), tagFilter("pt", "t1")))
            .body("ResourceTagMappingList.ResourceARN", contains(arn))
            .body("ResourceTagMappingList[0].Tags.size()", equalTo(3))
            .body("ResourceTagMappingList[0].Tags.find { it.Key == 'fd' }.Value", equalTo(marker))
            .body("ResourceTagMappingList[0].Tags.find { it.Key == 'pn' }.Value", equalTo("p1"))
            .body("ResourceTagMappingList[0].Tags.find { it.Key == 'pt' }.Value", equalTo("t1"));

        sqs("TagQueue", """
                {"QueueUrl": "%s", "Tags": {"added": "yes"}}
                """.formatted(queueUrl)).then().statusCode(200);
        getResources(markerAndAddedFilter("sqs:queue", marker))
            .body("ResourceTagMappingList.ResourceARN", contains(arn));

        sqs("UntagQueue", """
                {"QueueUrl": "%s", "TagKeys": ["added"]}
                """.formatted(queueUrl)).then().statusCode(200);
        getResources(markerAndAddedFilter("sqs:queue", marker))
            .body("ResourceTagMappingList", empty());

        sqs("DeleteQueue", """
                {"QueueUrl": "%s"}
                """.formatted(queueUrl)).then().statusCode(200);
        getResources(markerFilter("sqs:queue", marker))
            .body("ResourceTagMappingList", empty());
    }

    @Test
    void lambdaFunctionIsDiscoveredByTypeAndItsOwnTags() {
        String name = "discovery-" + unique();
        String marker = unique();
        String arn = given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "Tags": {"fd": "%s"}
                }
                """.formatted(name, marker))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201)
            .extract().path("FunctionArn");
        assertThat(arn, equalTo(ARN_PREFIX.formatted("lambda") + "function:" + name));

        getResources(markerFilter("lambda:function", marker))
            .body("ResourceTagMappingList.ResourceARN", contains(arn))
            .body("ResourceTagMappingList[0].Tags.find { it.Key == 'fd' }.Value", equalTo(marker));

        given()
            .contentType("application/json")
            .body("""
                {"Tags": {"added": "yes"}}
                """)
        .when()
            .post("/2017-03-31/tags/" + arn)
        .then()
            .statusCode(204);
        getResources(markerAndAddedFilter("lambda:function", marker))
            .body("ResourceTagMappingList.ResourceARN", contains(arn));

        given()
            .queryParam("tagKeys", "added")
        .when()
            .delete("/2017-03-31/tags/" + arn)
        .then()
            .statusCode(204);
        getResources(markerAndAddedFilter("lambda:function", marker))
            .body("ResourceTagMappingList", empty());

        given()
        .when()
            .delete("/2015-03-31/functions/" + name)
        .then()
            .statusCode(204);
        getResources(markerFilter("lambda:function", marker))
            .body("ResourceTagMappingList", empty());
    }

    @Test
    void logGroupIsDiscoveredWithoutWildcardSuffix() {
        String name = "/probe/" + unique();
        String marker = unique();
        logs("CreateLogGroup", """
                {"logGroupName": "%s", "tags": {"fd": "%s"}}
                """.formatted(name, marker)).then().statusCode(200);

        getResources(markerFilter("logs:log-group", marker))
            .body("ResourceTagMappingList.ResourceARN", contains(ARN_PREFIX.formatted("logs") + "log-group:" + name));

        logs("TagLogGroup", """
                {"logGroupName": "%s", "tags": {"added": "yes"}}
                """.formatted(name)).then().statusCode(200);
        getResources(markerAndAddedFilter("logs:log-group", marker))
            .body("ResourceTagMappingList.ResourceARN", contains(ARN_PREFIX.formatted("logs") + "log-group:" + name));

        logs("UntagLogGroup", """
                {"logGroupName": "%s", "tags": ["added"]}
                """.formatted(name)).then().statusCode(200);
        getResources(markerAndAddedFilter("logs:log-group", marker))
            .body("ResourceTagMappingList", empty());

        logs("DeleteLogGroup", """
                {"logGroupName": "%s"}
                """.formatted(name)).then().statusCode(200);
        getResources(markerFilter("logs:log-group", marker))
            .body("ResourceTagMappingList", empty());
    }

    @Test
    void tagKeysAndValuesIncludeKeySetOnlyThroughSqs() {
        String key = "sqs-only-" + unique();
        String value = unique();
        String queueUrl = sqs("CreateQueue", """
                {"QueueName": "discovery-%s", "tags": {"%s": "%s"}}
                """.formatted(unique(), key, value))
            .then()
            .statusCode(200)
            .extract().path("QueueUrl");

        assertThat(allTagKeys(), hasItem(key));
        tagging("GetTagValues", """
                {"Key": "%s"}
                """.formatted(key))
            .then()
            .statusCode(200)
            .body("TagValues", contains(value));

        sqs("DeleteQueue", """
                {"QueueUrl": "%s"}
                """.formatted(queueUrl)).then().statusCode(200);
    }

    private static List<String> allTagKeys() {
        List<String> keys = new ArrayList<>();
        String token = "";
        do {
            Response response = tagging("GetTagKeys", """
                    {"PaginationToken": "%s"}
                    """.formatted(token));
            response.then().statusCode(200);
            keys.addAll(response.jsonPath().getList("TagKeys", String.class));
            token = response.jsonPath().getString("PaginationToken");
        } while (token != null && !token.isEmpty());
        return keys;
    }

    private static String markerFilter(String resourceType, String marker) {
        return """
                {"ResourceTypeFilters": ["%s"], "TagFilters": [%s]}
                """.formatted(resourceType, tagFilter("fd", marker));
    }

    private static String markerAndAddedFilter(String resourceType, String marker) {
        return """
                {"ResourceTypeFilters": ["%s"], "TagFilters": [%s, {"Key": "added"}]}
                """.formatted(resourceType, tagFilter("fd", marker));
    }

    private static String tagFilter(String key, String value) {
        return """
                {"Key": "%s", "Values": ["%s"]}""".formatted(key, value);
    }

    private static ValidatableResponse getResources(String body) {
        return tagging("GetResources", body).then().statusCode(200);
    }

    private static Response tagging(String action, String body) {
        return given()
            .header("X-Amz-Target", TAGGING_TARGET + action)
            .contentType(JSON_1_1)
            .body(body)
        .when()
            .post("/");
    }

    private static Response sqs(String action, String body) {
        return given()
            .header("X-Amz-Target", "AmazonSQS." + action)
            .contentType(JSON_1_0)
            .body(body)
        .when()
            .post("/");
    }

    private static Response logs(String action, String body) {
        return given()
            .header("X-Amz-Target", "Logs_20140328." + action)
            .contentType(JSON_1_1)
            .body(body)
        .when()
            .post("/");
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
