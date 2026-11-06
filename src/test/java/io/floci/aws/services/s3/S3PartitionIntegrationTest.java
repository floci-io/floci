package io.floci.aws.services.s3;

import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.PartitionMatrix;
import io.floci.aws.testing.PartitionMatrix.PartitionCase;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * S3's CreateBucket region rules are the same in every partition (moto's {@code aws_verified}
 * matrix): the {@code us-east-1} endpoint takes any constraint but its own, and every other
 * regional endpoint requires a constraint naming exactly its region. In a non-commercial
 * deployment the constraint is therefore mandatory, which is what those SDKs send.
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

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void aNonCommercialEndpointRequiresItsOwnLocationConstraint(PartitionCase partitionCase) {
        String region = partitionCase.region();
        String bucket = bucketName(partitionCase.partition());
        s3(region).put("/" + bucket).then().statusCode(400)
                .body(containsString("<Code>IllegalLocationConstraintException</Code>"))
                .body(containsString("<Message>" + UNSPECIFIED + "</Message>"));

        s3(region).contentType("application/xml").body(constraint(region))
                .put("/" + bucket).then().statusCode(200);
        deleteLater(region, bucket);

        s3(region).get("/" + bucket + "?location").then().statusCode(200)
                .body(containsString(">" + region + "</LocationConstraint>"));

        s3(region).contentType("application/xml").body(constraint(region))
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
