package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.hectorvent.floci.testing.CfnRequests.cfn;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies {@code DeleteStack}'s {@code RetainResources}: a stack whose delete failed can be deleted
 * again while keeping the resources it names, and a request naming them anywhere else is refused
 * with the texts AWS answers.
 */
@QuarkusTest
class CloudFormationDeleteStackRetainResourcesIntegrationTest {

    private static final String INVALID_STATE = "The specified resources to retain must be in a valid state."
            + " Do not specify resources that are in the DELETE_COMPLETE state.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void retainedBucketKeepsItsDataWhileTheRestOfTheStackIsDeleted() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String data = "cfn-retain-data-" + suffix;
        String spare = "cfn-retain-spare-" + suffix;
        String stackName = "cfn-retain-" + suffix;
        String stackId = deleteFailedStack(stackName, data, spare, "cfn-retain-other-" + suffix);
        given().when().delete("/" + spare + "/object.txt").then().statusCode(204);

        deleteStack(stackName, "DataBucket").then().statusCode(200);

        CfnStackWaits.awaitStatus(stackId, "DELETE_COMPLETE");
        given().when().get("/" + data + "/object.txt").then().statusCode(200).body(containsString("kept"));
        bucketRequest(spare).then().statusCode(404);
        String events = cfn("DescribeStackEvents", stackId)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().asString();
        Map<String, String> newest = XmlParser.extractGroups(events, "member").stream()
                .filter(member -> "DataBucket".equals(member.get("LogicalResourceId")))
                .findFirst().orElseThrow();
        assertEquals("DELETE_SKIPPED", newest.get("ResourceStatus"));
        assertNull(newest.get("ResourceStatusReason"));
        given().when().delete("/" + data + "/object.txt");
        given().header("Host", data + ".localhost").when().delete("/");
    }

    @Test
    void retainingAResourceTheStackNoLongerHasIsRefused() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String data = "cfn-retain-gone-data-" + suffix;
        String spare = "cfn-retain-gone-spare-" + suffix;
        String stackName = "cfn-retain-gone-" + suffix;
        String stackId = deleteFailedStack(stackName, data, spare, "cfn-retain-gone-other-" + suffix);

        // OtherBucket was deleted by the failed delete, Nope was never a resource of the stack.
        for (String retained : new String[] {"OtherBucket", "Nope"}) {
            assertValidationError(deleteStack(stackName, retained), INVALID_STATE);
        }

        assertThat(describeStacks(stackId), containsString("<StackStatus>DELETE_FAILED</StackStatus>"));
        given().when().delete("/" + data + "/object.txt");
        given().when().delete("/" + spare + "/object.txt");
        deleteStack(stackName).then().statusCode(200);
        CfnStackWaits.awaitStatus(stackId, "DELETE_COMPLETE");
    }

    @Test
    void retainingIsRefusedUnlessTheStackIsDeleteFailed() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-retain-live-" + suffix;
        String stackName = "cfn-retain-live-" + suffix;
        String stackId = createStack(stackName, """
            {"Resources": {"Bucket": {"Type": "AWS::S3::Bucket", "Properties": {"BucketName": "%s"}}}}
            """.formatted(bucket));
        CfnStackWaits.awaitStatus(stackId, "CREATE_COMPLETE");

        assertValidationError(deleteStack(stackName, "Bucket"), "Invalid operation on stack [" + stackId
                + "]. When you delete a stack, specify which resources to retain only when the stack is in the"
                + " DELETE_FAILED state.");
        assertThat(describeStacks(stackId), containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        // An empty list is the one the AWS CLI sends for --retain-resources with no ids: no list at all.
        cfn("DeleteStack", stackName)
                .formParam("RetainResources", "")
            .when()
                .post("/")
            .then()
                .statusCode(200);
        CfnStackWaits.awaitStatus(stackId, "DELETE_COMPLETE");
        bucketRequest(bucket).then().statusCode(404);
    }

    @Test
    void malformedRetainListIsRefusedBeforeTheStackIsLookedUp() {
        String missing = "cfn-retain-missing-" + Long.toString(System.nanoTime(), 36);
        String tooLong = "a".repeat(256);
        String lengths = "' at 'resourcesToRetain' failed to satisfy constraint: Member must satisfy constraint:"
                + " [Member must have length less than or equal to 255, Member must have length greater than"
                + " or equal to 1]";

        assertValidationError(deleteStack(missing, ""), "1 validation error detected: Value '[]" + lengths);
        assertValidationError(deleteStack(missing, "x", tooLong),
                "1 validation error detected: Value '[x, " + tooLong + "]" + lengths);
        assertValidationError(deleteStack(missing, "x", "x"), "The logical IDs specified for the resources to"
                + " retain are not all unique. Specify unique logical IDs for all of the resources that you want"
                + " to retain.");
        deleteStack(missing, "x", "a".repeat(255)).then().statusCode(200);
    }

    /**
     * A stack of three buckets whose delete failed on the two holding an object: DataBucket and
     * SpareBucket are DELETE_FAILED, the empty OtherBucket is DELETE_COMPLETE.
     */
    private static String deleteFailedStack(String stackName, String data, String spare, String other) {
        String stackId = createStack(stackName, """
            {
              "Resources": {
                "DataBucket": {"Type": "AWS::S3::Bucket", "Properties": {"BucketName": "%s"}},
                "SpareBucket": {"Type": "AWS::S3::Bucket", "Properties": {"BucketName": "%s"}},
                "OtherBucket": {"Type": "AWS::S3::Bucket", "Properties": {"BucketName": "%s"}}
              }
            }
            """.formatted(data, spare, other));
        CfnStackWaits.awaitStatus(stackId, "CREATE_COMPLETE");
        for (String bucket : new String[] {data, spare}) {
            given().contentType("text/plain").body("kept").when().put("/" + bucket + "/object.txt")
                    .then().statusCode(200);
        }
        deleteStack(stackName).then().statusCode(200);
        CfnStackWaits.awaitStatus(stackId, "DELETE_FAILED");
        bucketRequest(other).then().statusCode(404);
        return stackId;
    }

    private static void assertValidationError(Response response, String message) {
        response.then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", equalTo(message));
    }

    private static String createStack(String stackName, String template) {
        String xml = cfn("CreateStack", stackName)
                .formParam("TemplateBody", template)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().asString();
        int start = xml.indexOf("<StackId>") + "<StackId>".length();
        return xml.substring(start, xml.indexOf("</StackId>", start));
    }

    private static Response deleteStack(String stackName, String... retainResources) {
        RequestSpecification request = cfn("DeleteStack", stackName);
        for (int i = 0; i < retainResources.length; i++) {
            request.formParam("RetainResources.member." + (i + 1), retainResources[i]);
        }
        return request.when().post("/");
    }

    private static String describeStacks(String stackId) {
        return cfn("DescribeStacks", stackId)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().asString();
    }

    private static Response bucketRequest(String bucket) {
        return given().header("Host", bucket + ".localhost").when().get("/");
    }
}
