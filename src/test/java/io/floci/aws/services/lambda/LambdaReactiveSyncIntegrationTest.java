package io.floci.aws.services.lambda;

import io.floci.aws.testing.LambdaCodeFixtures;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies that updating a ZIP file in S3 automatically patches
 * running Lambda containers linked to that bucket/key.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaReactiveSyncIntegrationTest {

    private static final String BUCKET = "reactive-sync-bucket";
    private static final String KEY    = "function.zip";
    private static final String FN     = "reactive-sync-fn";
    private static final String REGIONAL_BUCKET = "reactive-sync-eu-bucket";
    private static final String REGIONAL_FN = "reactive-sync-eu-fn";
    private static final String S3_EU_AUTH =
            "AWS4-HMAC-SHA256 Credential=000000000000/20261125/eu-west-1/s3/aws4_request, SignedHeaders=host, Signature=abc";
    private static final String LAMBDA_EU_AUTH =
            "AWS4-HMAC-SHA256 Credential=000000000000/20261125/eu-west-1/lambda/aws4_request, SignedHeaders=host, Signature=abc";

    private static byte[] makeZip(String body) {
        return LambdaCodeFixtures.nodeJsZip("exports.handler = async (e) => ({ body: '" + body + "' });");
    }

    @Test
    @Order(1)
    void setupAndInitialInvoke() throws Exception {
        // 1. Create bucket and upload V1
        given().when().put("/" + BUCKET).then().statusCode(200);
        given().body(makeZip("v1")).when().put("/" + BUCKET + "/" + KEY).then().statusCode(200);

        // 2. Create function
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/role",
                    "Handler": "index.handler",
                    "Timeout": 30,
                    "Code": {
                        "S3Bucket": "%s",
                        "S3Key": "%s"
                    }
                }
                """.formatted(FN, BUCKET, KEY))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201);

        // 3. First invoke (starts container)
        given()
            .body("{}")
        .when()
            .post("/2015-03-31/functions/" + FN + "/invocations")
        .then()
            .statusCode(200)
            .body(containsString("v1"));
    }

    @Test
    @Order(2)
    void uploadV2AndVerifyAutoSync() throws Exception {
        // 1. Upload V2 to same bucket/key
        given().body(makeZip("v2")).when().put("/" + BUCKET + "/" + KEY).then().statusCode(200);

        // 2. Invoke again until the async event processing and Docker copy have landed. Should
        // see V2 without calling UpdateFunctionCode
        await("the reactive sync to reach the warm container").atMost(Duration.ofSeconds(30))
                .pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> given()
                        .body("{}")
                .when()
                        .post("/2015-03-31/functions/" + FN + "/invocations")
                .then()
                        .statusCode(200)
                        .body(containsString("v2")));
    }

    @Test
    @Order(3)
    void cleanup() {
        given().when().delete("/2015-03-31/functions/" + FN).then().statusCode(204);
    }

    @Test
    @Order(4)
    void uploadRedeploysAFunctionOutsideTheDefaultRegion() throws Exception {
        byte[] v1 = makeZip("eu-v1");
        byte[] v2 = makeZip("eu-v2");
        given().header("Authorization", S3_EU_AUTH)
            .body("<CreateBucketConfiguration><LocationConstraint>eu-west-1</LocationConstraint>"
                    + "</CreateBucketConfiguration>")
            .when().put("/" + REGIONAL_BUCKET).then().statusCode(200);
        given().header("Authorization", S3_EU_AUTH).body(v1)
            .when().put("/" + REGIONAL_BUCKET + "/" + KEY).then().statusCode(200);
        given()
            .header("Authorization", LAMBDA_EU_AUTH)
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/role",
                    "Handler": "index.handler",
                    "Code": {
                        "S3Bucket": "%s",
                        "S3Key": "%s"
                    }
                }
                """.formatted(REGIONAL_FN, REGIONAL_BUCKET, KEY))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201)
            .body("CodeSha256", equalTo(sha256(v1)));

        try {
            given().header("Authorization", S3_EU_AUTH).body(v2)
                .when().put("/" + REGIONAL_BUCKET + "/" + KEY).then().statusCode(200);

            await("the reactive sync to redeploy the eu-west-1 function").atMost(Duration.ofSeconds(10))
                    .pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() -> given()
                            .header("Authorization", LAMBDA_EU_AUTH)
                    .when()
                            .get("/2015-03-31/functions/" + REGIONAL_FN + "/configuration")
                    .then()
                            .statusCode(200)
                            .body("FunctionArn", containsString(":eu-west-1:"))
                            .body("CodeSha256", equalTo(sha256(v2))));
        } finally {
            given().header("Authorization", LAMBDA_EU_AUTH)
                .when().delete("/2015-03-31/functions/" + REGIONAL_FN).then().statusCode(204);
            given().header("Authorization", S3_EU_AUTH)
                .when().delete("/" + REGIONAL_BUCKET + "/" + KEY).then().statusCode(204);
            given().header("Authorization", S3_EU_AUTH)
                .when().delete("/" + REGIONAL_BUCKET).then().statusCode(204);
        }
    }

    /**
     * Buckets are per account, so two accounts can each own a bucket of the same name. An upload to one account's
     * bucket must not redeploy the other account's function from the same-named bucket and key: reactive sync
     * runs synchronously in the test profile, so the function is checked as soon as the upload returns.
     */
    @Test
    @Order(5)
    void anUploadToAnotherAccountsSameNamedBucketLeavesTheFunctionAlone() throws Exception {
        String victim = "000000000731";
        String attacker = "000000000732";
        String bucket = "reactive-sync-shared-name";
        byte[] own = makeZip("victim-v1");
        byte[] foreign = makeZip("attacker");
        for (String account : new String[] {victim, attacker}) {
            given().header("Authorization", auth(account, "s3")).when().put("/" + bucket).then().statusCode(200);
        }
        given().header("Authorization", auth(victim, "s3")).body(own)
            .when().put("/" + bucket + "/" + KEY).then().statusCode(200);
        given()
            .header("Authorization", auth(victim, "lambda"))
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "reactive-sync-victim-fn",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::%s:role/role",
                    "Handler": "index.handler",
                    "Code": {"S3Bucket": "%s", "S3Key": "%s"}
                }
                """.formatted(victim, bucket, KEY))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201)
            .body("CodeSha256", equalTo(sha256(own)));

        try {
            given().header("Authorization", auth(attacker, "s3")).body(foreign)
                .when().put("/" + bucket + "/" + KEY).then().statusCode(200);

            given().header("Authorization", auth(victim, "lambda"))
            .when()
                .get("/2015-03-31/functions/reactive-sync-victim-fn/configuration")
            .then()
                .statusCode(200)
                .body("CodeSha256", equalTo(sha256(own)));
        } finally {
            given().header("Authorization", auth(victim, "lambda"))
                .when().delete("/2015-03-31/functions/reactive-sync-victim-fn").then().statusCode(204);
            for (String account : new String[] {victim, attacker}) {
                given().header("Authorization", auth(account, "s3"))
                    .when().delete("/" + bucket + "/" + KEY).then().statusCode(204);
                given().header("Authorization", auth(account, "s3"))
                    .when().delete("/" + bucket).then().statusCode(204);
            }
        }
    }

    private static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20261125/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private static String sha256(byte[] data) throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
