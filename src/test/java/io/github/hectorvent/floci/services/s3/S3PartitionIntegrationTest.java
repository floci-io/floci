package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.PartitionMatrix;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * S3's CreateBucket region rules are the same in every partition (moto's {@code aws_verified}
 * matrix): the {@code us-east-1} endpoint takes any constraint but its own, and every other
 * regional endpoint requires a constraint naming exactly its region. In a China deployment the
 * constraint is therefore mandatory, which is what the China SDKs send.
 */
@QuarkusTest
class S3PartitionIntegrationTest {

    private static final String UNSPECIFIED =
            "The unspecified location constraint is incompatible for the region specific endpoint this request was sent to.";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private static RequestSpecification s3(String region) {
        return given().header("Authorization", PartitionMatrix.sigV4Auth(region, "s3"));
    }

    private static String constraint(String region) {
        return "<CreateBucketConfiguration><LocationConstraint>" + region
                + "</LocationConstraint></CreateBucketConfiguration>";
    }

    private String bucketName(String label) {
        return "partition-" + label + "-" + Long.toString(System.nanoTime(), 36);
    }

    private void deleteLater(String region, String bucket) {
        cleanup.register(() -> s3(region).delete("/" + bucket));
    }

    @Test
    void aChinaEndpointRequiresItsOwnLocationConstraint() {
        String bucket = bucketName("cn");
        s3("cn-north-1").put("/" + bucket).then().statusCode(400)
                .body(containsString("<Code>IllegalLocationConstraintException</Code>"))
                .body(containsString("<Message>" + UNSPECIFIED + "</Message>"));

        s3("cn-north-1").contentType("application/xml").body(constraint("cn-north-1"))
                .put("/" + bucket).then().statusCode(200);
        deleteLater("cn-north-1", bucket);

        s3("cn-north-1").get("/" + bucket + "?location").then().statusCode(200)
                .body(containsString(">cn-north-1</LocationConstraint>"));

        s3("cn-north-1").contentType("application/xml").body(constraint("cn-north-1"))
                .put("/" + bucket).then().statusCode(409)
                .body(containsString("<Code>BucketAlreadyOwnedByYou</Code>"));
    }

    @Test
    void aRegionalEndpointRejectsAnotherRegionsConstraint() {
        String bucket = bucketName("mismatch");
        s3("us-west-2").contentType("application/xml").body(constraint("eu-central-1"))
                .put("/" + bucket).then().statusCode(400)
                .body(containsString("<Code>IllegalLocationConstraintException</Code>"))
                .body(containsString("<Message>The eu-central-1 location constraint is incompatible for the "
                        + "region specific endpoint this request was sent to.</Message>"));
        s3("cn-north-1").contentType("application/xml").body(constraint("cn-northwest-1"))
                .put("/" + bucket).then().statusCode(400)
                .body(containsString("<Code>IllegalLocationConstraintException</Code>"));
        // us-east-1 is only InvalidLocationConstraint on its own endpoint; anywhere else it is
        // just another mismatched region.
        s3("us-west-2").contentType("application/xml").body(constraint("us-east-1"))
                .put("/" + bucket).then().statusCode(400)
                .body(containsString("<Code>IllegalLocationConstraintException</Code>"))
                .body(containsString("<Message>The us-east-1 location constraint is incompatible for the "
                        + "region specific endpoint this request was sent to.</Message>"));
    }

    @Test
    void theGlobalEndpointTakesAnyConstraintButItsOwn() {
        String bucket = bucketName("global");
        s3("us-east-1").contentType("application/xml").body(constraint("eu-west-1"))
                .put("/" + bucket).then().statusCode(200);
        deleteLater("us-east-1", bucket);
        s3("us-east-1").get("/" + bucket + "?location").then().statusCode(200)
                .body(containsString(">eu-west-1</LocationConstraint>"));

        s3("us-east-1").contentType("application/xml").body(constraint("us-east-1"))
                .put("/" + bucketName("invalid")).then().statusCode(400)
                .body(containsString("<Code>InvalidLocationConstraint</Code>"))
                .body(containsString("<Message>The specified location-constraint is not valid.</Message>"));

        String virginia = bucketName("virginia");
        s3("us-east-1").put("/" + virginia).then().statusCode(200);
        deleteLater("us-east-1", virginia);
        s3("us-east-1").get("/" + virginia + "?location").then().statusCode(200)
                .body(containsString("<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"/>"));
    }
}
