package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A DeleteObject conditioned on an ETag needs {@code s3:GetObject} as well as {@code s3:DeleteObject};
 * {@code If-Match: *} needs only the delete. Per the S3 conditional-deletes guide, not a measurement.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class S3ConditionalDeleteIamEnforcementIntegrationTest {

    private static final String ADMIN = "test";
    private static final String REGION = "us-east-1";

    @Test
    void anETagConditionedDeleteAlsoNeedsGetObject() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "conddel-" + suffix;
        String user = "conddel-" + suffix;
        given().header("Authorization", auth(ADMIN, "s3")).when().put("/" + bucket).then().statusCode(200);
        for (String key : new String[] {"etag.txt", "star.txt"}) {
            given().header("Authorization", auth(ADMIN, "s3")).body("seed")
                    .when().put("/" + bucket + "/" + key).then().statusCode(200);
        }
        given().formParam("Action", "CreateUser").formParam("UserName", user)
                .header("Authorization", auth(ADMIN, "iam")).when().post("/").then().statusCode(200);
        String accessKeyId = given().formParam("Action", "CreateAccessKey").formParam("UserName", user)
                .header("Authorization", auth(ADMIN, "iam")).when().post("/").then().statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
        String eTag = given().header("Authorization", auth(ADMIN, "s3")).when().head("/" + bucket + "/etag.txt")
                .then().statusCode(200).extract().header("ETag");
        String objects = "arn:aws:s3:::" + bucket + "/*";

        policy(user, "{\"Effect\":\"Allow\",\"Action\":\"s3:DeleteObject\",\"Resource\":\"" + objects + "\"}");
        assertEquals(403, delete(accessKeyId, bucket, "etag.txt", eTag), "an ETag condition with no GetObject");
        assertEquals(403, given().header("Authorization", auth(accessKeyId, "s3")).header("If-Match", eTag)
                .when().delete("/" + bucket + "/etag.txt?x-id=DeleteObject").statusCode(),
                "an SDK's x-id query parameter does not skip the check");
        assertEquals(204, delete(accessKeyId, bucket, "star.txt", "*"), "the existence check needs only DeleteObject");

        // AbortMultipartUpload shares the DELETE verb and the s3:DeleteObject action here, but is not
        // a conditional object delete, so an If-Match on it asks for no GetObject.
        String uploadId = given().header("Authorization", auth(ADMIN, "s3"))
                .when().post("/" + bucket + "/mpu.txt?uploads").then().statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
        policy(user, "{\"Effect\":\"Allow\",\"Action\":[\"s3:DeleteObject\",\"s3:AbortMultipartUpload\"],\"Resource\":\""
                + objects + "\"}");
        assertEquals(204, given().header("Authorization", auth(accessKeyId, "s3")).header("If-Match", eTag)
                .when().delete("/" + bucket + "/mpu.txt?uploadId=" + uploadId).statusCode(),
                "an abort carrying If-Match is not a conditional delete");

        policy(user, "{\"Effect\":\"Allow\",\"Action\":[\"s3:DeleteObject\",\"s3:GetObject\"],\"Resource\":\""
                + objects + "\"}");
        assertEquals(204, delete(accessKeyId, bucket, "etag.txt", eTag), "with GetObject the condition is evaluated");
    }

    private static void policy(String user, String statement) {
        given().formParam("Action", "PutUserPolicy").formParam("UserName", user).formParam("PolicyName", "p")
                .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[" + statement + "]}")
                .header("Authorization", auth(ADMIN, "iam")).when().post("/").then().statusCode(200);
    }

    private static int delete(String accessKeyId, String bucket, String key, String ifMatch) {
        return given().header("Authorization", auth(accessKeyId, "s3")).header("If-Match", ifMatch)
                .when().delete("/" + bucket + "/" + key).statusCode();
    }

    private static String auth(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260629/" + REGION + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
