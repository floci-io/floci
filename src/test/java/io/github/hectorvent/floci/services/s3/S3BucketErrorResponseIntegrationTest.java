package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class S3BucketErrorResponseIntegrationTest {

    @Test
    void missingBucketIncludesBucketNameForBucketAndObjectRequests() {
        String bucket = "error-response-missing-bucket";

        given().when().get("/" + bucket).then().statusCode(404)
                .body(containsString("<Code>NoSuchBucket</Code>"))
                .body(containsString("<BucketName>" + bucket + "</BucketName>"));

        given().when().get("/" + bucket + "/key").then().statusCode(404)
                .body(containsString("<Code>NoSuchBucket</Code>"))
                .body(containsString("<BucketName>" + bucket + "</BucketName>"));
    }

    @Test
    void bucketConfigurationErrorsIncludeBucketName() {
        String bucket = "error-response-config-bucket";
        given().when().put("/" + bucket).then().statusCode(200);

        given().when().get("/" + bucket + "?tagging").then().statusCode(404)
                .body(containsString("<Code>NoSuchTagSet</Code>"))
                .body(containsString("<BucketName>" + bucket + "</BucketName>"));

        given().when().get("/" + bucket + "?policy").then().statusCode(404)
                .body(containsString("<Code>NoSuchBucketPolicy</Code>"))
                .body(containsString("<BucketName>" + bucket + "</BucketName>"));

        given().body("data").when().put("/" + bucket + "/key").then().statusCode(200);
        given().when().delete("/" + bucket).then().statusCode(409)
                .body(containsString("<Code>BucketNotEmpty</Code>"))
                .body(containsString("<BucketName>" + bucket + "</BucketName>"));

        given().when().delete("/" + bucket + "/key").then().statusCode(204);
        given().when().delete("/" + bucket).then().statusCode(204);
    }

    @Test
    void objectAndRequestErrorsDoNotIncludeBucketName() {
        String bucket = "error-response-object-bucket";
        given().when().put("/" + bucket).then().statusCode(200);

        given().when().get("/" + bucket + "/missing-key").then().statusCode(404)
                .body(containsString("<Code>NoSuchKey</Code>"))
                .body(not(containsString("<BucketName>")));

        given().when().post("/" + bucket).then().statusCode(400)
                .body(containsString("<Code>InvalidArgument</Code>"))
                .body(not(containsString("<BucketName>")));

        given().when().delete("/" + bucket).then().statusCode(204);
    }

    @Test
    void virtualHostedMissingBucketUsesResolvedName() {
        String bucket = "error-response-vhost-bucket";

        given().header("Host", bucket + ".localhost").when().get("/").then().statusCode(404)
                .body(containsString("<Code>NoSuchBucket</Code>"))
                .body(containsString("<BucketName>" + bucket + "</BucketName>"));
    }
}
