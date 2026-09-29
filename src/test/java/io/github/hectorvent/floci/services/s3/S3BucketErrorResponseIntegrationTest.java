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
    void missingCorsConfigurationIncludesBucketNameButMissingMetricsDoesNot() {
        String bucket = "error-response-cors-bucket";
        String corsXml = "<CORSConfiguration><CORSRule><AllowedOrigin>*</AllowedOrigin>"
                + "<AllowedMethod>GET</AllowedMethod></CORSRule></CORSConfiguration>";
        given().when().put("/" + bucket).then().statusCode(200);

        given().when().get("/" + bucket + "?cors").then().statusCode(404)
                .body(containsString("<Code>NoSuchCORSConfiguration</Code>"))
                .body(containsString("<BucketName>" + bucket + "</BucketName>"));

        given().contentType("application/xml").body(corsXml)
                .when().put("/" + bucket + "?cors").then().statusCode(200);
        given().when().delete("/" + bucket + "?cors").then().statusCode(204);
        given().when().get("/" + bucket + "?cors").then().statusCode(404)
                .body(containsString("<Code>NoSuchCORSConfiguration</Code>"))
                .body(containsString("<BucketName>" + bucket + "</BucketName>"));

        given().when().get("/" + bucket + "?metrics&id=absent").then().statusCode(404)
                .body(containsString("<Code>NoSuchConfiguration</Code>"))
                .body(not(containsString("<BucketName>")));

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

    @Test
    void multipartPreconditionErrorRetainsCondition() {
        String bucket = "error-response-multipart-bucket";
        String key = "key";
        given().when().put("/" + bucket).then().statusCode(200);
        String eTag = given().body("existing").when().put("/" + bucket + "/" + key)
                .then().statusCode(200).extract().header("ETag");
        String uploadId = given().when().post("/" + bucket + "/" + key + "?uploads")
                .then().statusCode(200).extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
        String completeXml = "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber>"
                + "<ETag>unused</ETag></Part></CompleteMultipartUpload>";

        given().header("If-Match", "wrong-etag").body(completeXml)
                .when().post("/" + bucket + "/" + key + "?uploadId=" + uploadId)
                .then().statusCode(412)
                .body(containsString("<Code>PreconditionFailed</Code>"))
                .body(containsString("<Condition>If-Match</Condition>"))
                .body(not(containsString("<BucketName>")));
        given().header("If-None-Match", eTag).body(completeXml)
                .when().post("/" + bucket + "/" + key + "?uploadId=" + uploadId)
                .then().statusCode(412)
                .body(containsString("<Condition>If-None-Match</Condition>"));

        given().when().delete("/" + bucket + "/" + key + "?uploadId=" + uploadId).then().statusCode(204);
        given().when().delete("/" + bucket + "/" + key).then().statusCode(204);
        given().when().delete("/" + bucket).then().statusCode(204);
    }
}
