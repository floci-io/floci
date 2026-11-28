package io.floci.aws.services.datasync;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * DataSync agents, locations and tasks are Regional: each is listed and reached by its ARN only in the Region it was
 * created in. Another Region answers {@code InvalidRequestException}, as it does for an ARN that names nothing.
 */
@QuarkusTest
class DataSyncRegionScopingIntegrationTest {

    private static final String HOME = "us-east-1";
    private static final String OTHER = "eu-west-1";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/datasync-bucket-access";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void anAgentALocationAndATaskAreListedAndReachedOnlyInTheirRegion() {
        String agentArn = call(HOME, "CreateAgent", "{\"ActivationKey\":\"AAAAA-1AAAA-BB1CC-DDDDD-EEEEE\"}")
                .statusCode(200).extract().path("AgentArn");
        String source = s3Location(HOME, "regional-source");
        String destination = s3Location(HOME, "regional-destination");
        String taskArn = call(HOME, "CreateTask", "{\"SourceLocationArn\":\"" + source
                + "\",\"DestinationLocationArn\":\"" + destination + "\"}").statusCode(200).extract().path("TaskArn");
        try {
            call(OTHER, "ListAgents", "{}").statusCode(200).body("Agents.AgentArn", not(hasItem(agentArn)));
            call(OTHER, "ListLocations", "{}").statusCode(200)
                    .body("Locations.LocationArn", not(hasItem(source)));
            call(OTHER, "ListTasks", "{}").statusCode(200).body("Tasks.TaskArn", not(hasItem(taskArn)));

            invalid(call(OTHER, "DescribeAgent", "{\"AgentArn\":\"" + agentArn + "\"}"));
            invalid(call(OTHER, "UpdateAgent", "{\"AgentArn\":\"" + agentArn + "\",\"Name\":\"moved\"}"));
            invalid(call(OTHER, "DeleteAgent", "{\"AgentArn\":\"" + agentArn + "\"}"));
            invalid(call(OTHER, "DescribeLocationS3", "{\"LocationArn\":\"" + source + "\"}"));
            invalid(call(OTHER, "DeleteLocation", "{\"LocationArn\":\"" + source + "\"}"));
            invalid(call(OTHER, "DescribeTask", "{\"TaskArn\":\"" + taskArn + "\"}"));
            invalid(call(OTHER, "UpdateTask", "{\"TaskArn\":\"" + taskArn + "\",\"Name\":\"moved\"}"));
            invalid(call(OTHER, "DeleteTask", "{\"TaskArn\":\"" + taskArn + "\"}"));
            invalid(call(OTHER, "ListTagsForResource", "{\"ResourceArn\":\"" + taskArn + "\"}"));
            invalid(call(OTHER, "TagResource", "{\"ResourceArn\":\"" + agentArn
                    + "\",\"Tags\":[{\"Key\":\"k\",\"Value\":\"v\"}]}"));
            String otherDestination = s3Location(OTHER, "regional-other");
            try {
                invalid(call(OTHER, "CreateTask", "{\"SourceLocationArn\":\"" + source
                        + "\",\"DestinationLocationArn\":\"" + otherDestination + "\"}"));
            } finally {
                call(OTHER, "DeleteLocation", "{\"LocationArn\":\"" + otherDestination + "\"}").statusCode(200);
            }

            call(HOME, "ListAgents", "{}").statusCode(200).body("Agents.AgentArn", hasItem(agentArn));
            call(HOME, "ListLocations", "{}").statusCode(200).body("Locations.LocationArn", hasItem(source));
            call(HOME, "ListTasks", "{}").statusCode(200).body("Tasks.TaskArn", hasItem(taskArn));
            call(HOME, "DescribeAgent", "{\"AgentArn\":\"" + agentArn + "\"}").statusCode(200)
                    .body("AgentArn", equalTo(agentArn));
            call(HOME, "DescribeLocationS3", "{\"LocationArn\":\"" + source + "\"}").statusCode(200)
                    .body("LocationArn", equalTo(source));
            call(HOME, "DescribeTask", "{\"TaskArn\":\"" + taskArn + "\"}").statusCode(200)
                    .body("TaskArn", equalTo(taskArn));
        } finally {
            call(HOME, "DeleteTask", "{\"TaskArn\":\"" + taskArn + "\"}");
            call(HOME, "DeleteLocation", "{\"LocationArn\":\"" + source + "\"}");
            call(HOME, "DeleteLocation", "{\"LocationArn\":\"" + destination + "\"}");
            call(HOME, "DeleteAgent", "{\"AgentArn\":\"" + agentArn + "\"}");
        }
    }

    private static String s3Location(String region, String bucket) {
        return call(region, "CreateLocationS3", "{\"S3BucketArn\":\"arn:aws:s3:::" + bucket
                + "\",\"S3Config\":{\"BucketAccessRoleArn\":\"" + ROLE_ARN + "\"}}")
                .statusCode(200).extract().path("LocationArn");
    }

    private static ValidatableResponse call(String region, String action, String body) {
        return given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "FmrsService." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/" + region + "/datasync/aws4_request")
                .body(body)
                .when().post("/").then();
    }

    private static void invalid(ValidatableResponse response) {
        response.statusCode(400).body("__type", equalTo("InvalidRequestException"));
    }
}
