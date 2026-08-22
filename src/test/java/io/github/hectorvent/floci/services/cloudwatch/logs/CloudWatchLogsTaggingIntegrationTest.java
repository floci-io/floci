package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogGroup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

/**
 * Regression coverage for issue #98: a CloudWatch Logs log group's tags were readable through the
 * log group's own {@code ListTagsForResource}/{@code ListTagsLogGroup} APIs but never surfaced
 * from {@code resourcegroupstaggingapi}'s {@code GetResources}, because {@link LogGroup} carries
 * tags but no ARN field of its own and no {@link io.github.hectorvent.floci.core.common.ArnSynthesizer}
 * existed to derive one for {@code TaggedResourceScanner}. See
 * {@link CloudWatchLogsArnSynthesizer}.
 */
@QuarkusTest
class CloudWatchLogsTaggingIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "Logs_20140328.";
    private static final String TAGGING_TARGET_PREFIX = "ResourceGroupsTaggingAPI_20170126.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createLogGroupWithTags_tagsReturnedByResourceGroupsTaggingApi() {
        String groupName = "/tagged-group-" + UUID.randomUUID().toString().substring(0, 8);
        String groupArn = "arn:aws:logs:us-east-1:000000000000:log-group:" + groupName;

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateLogGroup")
                .body("""
                        {
                          "logGroupName": "%s",
                          "tags": {
                            "tofu-estate": "demo",
                            "Environment": "dev"
                          }
                        }
                        """.formatted(groupName))
        .when().post("/")
        .then().statusCode(200);

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TAGGING_TARGET_PREFIX + "GetResources")
                .body("""
                        {
                          "ResourceARNList": ["%s"]
                        }
                        """.formatted(groupArn))
        .when().post("/")
        .then()
                .statusCode(200)
                .body("ResourceTagMappingList.size()", equalTo(1))
                .body("ResourceTagMappingList[0].ResourceARN", equalTo(groupArn))
                .body("ResourceTagMappingList[0].Tags.size()", equalTo(2))
                .body("ResourceTagMappingList[0].Tags.Key", hasItems("tofu-estate", "Environment"))
                .body("ResourceTagMappingList[0].Tags.Value", hasItems("demo", "dev"));
    }

    @Test
    void deleteLogGroup_removesItFromResourceGroupsTaggingApi() {
        String groupName = "/tagged-group-delete-" + UUID.randomUUID().toString().substring(0, 8);
        String groupArn = "arn:aws:logs:us-east-1:000000000000:log-group:" + groupName;

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateLogGroup")
                .body("""
                        {
                          "logGroupName": "%s",
                          "tags": {"Environment": "dev"}
                        }
                        """.formatted(groupName))
        .when().post("/")
        .then().statusCode(200);

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + "DeleteLogGroup")
                .body("""
                        {"logGroupName": "%s"}
                        """.formatted(groupName))
        .when().post("/")
        .then().statusCode(200);

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TAGGING_TARGET_PREFIX + "GetResources")
                .body("""
                        {
                          "ResourceARNList": ["%s"]
                        }
                        """.formatted(groupArn))
        .when().post("/")
        .then()
                .statusCode(200)
                .body("ResourceTagMappingList.size()", equalTo(0));
    }
}
