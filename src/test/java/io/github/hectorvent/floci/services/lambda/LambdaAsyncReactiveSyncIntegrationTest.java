package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.services.lambdaweb.LambdaWebZips;
import io.github.hectorvent.floci.testing.LambdaCodeSourcesProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

/**
 * With asynchronous reactive S3 sync, the re-deploy runs on a background thread with no request of
 * its own, so it must read the object in the uploader's account rather than the default one.
 */
@QuarkusTest
@TestProfile(LambdaCodeSourcesProfile.class)
class LambdaAsyncReactiveSyncIntegrationTest {

    private static final String ACCOUNT = "000000000021";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260215/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=abc";
    private static final String LAMBDA_AUTH =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260215/us-east-1/lambda/aws4_request, SignedHeaders=host, Signature=abc";
    private static final String BUCKET = "async-reactive-sync-bucket";
    private static final String KEY = "function.zip";
    private static final String FN = "async-reactive-sync-fn";

    @Test
    void uploadFromANonDefaultAccountRedeploysTheFunctionInTheBackground() throws Exception {
        byte[] v1 = LambdaWebZips.indexJs("exports.handler = async () => 'v1';");
        byte[] v2 = LambdaWebZips.indexJs("exports.handler = async () => 'v2';");
        given().header("Authorization", S3_AUTH).when().put("/" + BUCKET).then().statusCode(200);
        given().header("Authorization", S3_AUTH).body(v1).when().put("/" + BUCKET + "/" + KEY).then().statusCode(200);
        given()
            .header("Authorization", LAMBDA_AUTH)
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::%s:role/role",
                    "Handler": "index.handler",
                    "Code": {
                        "S3Bucket": "%s",
                        "S3Key": "%s"
                    }
                }
                """.formatted(FN, ACCOUNT, BUCKET, KEY))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201)
            .body("CodeSha256", equalTo(sha256(v1)));

        given().header("Authorization", S3_AUTH).body(v2).when().put("/" + BUCKET + "/" + KEY).then().statusCode(200);

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
            given()
                .header("Authorization", LAMBDA_AUTH)
            .when()
                .get("/2015-03-31/functions/" + FN + "/configuration")
            .then()
                .statusCode(200)
                .body("LastUpdateStatus", equalTo("Successful"))
                .body("CodeSha256", equalTo(sha256(v2))));
    }

    private static String sha256(byte[] data) throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
