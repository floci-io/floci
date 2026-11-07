package io.floci.aws.services.iam;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request no wire protocol claims, such as one carrying a {@code Smithy-Protocol} value Floci does
 * not serve it by, is still served while strict claiming is off, the default. It is checked as the
 * call its route runs: unsigned, a call to the JSON, CBOR or Query endpoint is refused, and a REST
 * request is checked as its route's action, whatever an {@code X-Amz-Target} header on it names.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class UnclaimedRequestEnforcementIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final byte[] EMPTY_CBOR_MAP = {(byte) 0xa0};

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void anUnsignedJsonCallWithAnUnrecognisedSmithyProtocolIsRefused() {
        given()
                .contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", "AmazonSQS.ListQueues")
                .header("smithy-protocol", "unrecognised")
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(403)
                .body("__type", equalTo("MissingAuthenticationTokenException"));
    }

    @Test
    void anUnsignedJsonCallCarryingTheRpcV2HeaderOffItsPathIsRefused() {
        // The header names a protocol Floci serves, but only on the rpcv2 path; at the root the JSON
        // endpoint serves the call.
        given()
                .contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", "AmazonSQS.ListQueues")
                .header("smithy-protocol", "rpc-v2-cbor")
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(403)
                .body("__type", equalTo("MissingAuthenticationTokenException"));
    }

    @Test
    void anUnsignedQueryCallWithAnUnrecognisedSmithyProtocolIsRefused() {
        given()
                .contentType("application/x-www-form-urlencoded")
                .header("smithy-protocol", "unrecognised")
                .formParam("Action", "ListQueues")
                .formParam("Version", "2012-11-05")
        .when()
                .post("/")
        .then()
                .statusCode(403)
                .body(containsString("<Code>MissingAuthenticationToken</Code>"));
    }

    @Test
    void anUnsignedCborCallWithAnUnrecognisedSmithyProtocolIsRefused() {
        byte[] body = given()
                .contentType("application/x-amz-cbor-1.1")
                .header("X-Amz-Target", "Kinesis_20131202.ListStreams")
                .header("smithy-protocol", "unrecognised")
                .body(EMPTY_CBOR_MAP)
        .when()
                .post("/")
        .then()
                .statusCode(403)
                .extract().asByteArray();

        assertTrue(new String(body, StandardCharsets.ISO_8859_1).contains("MissingAuthenticationToken"));
    }

    @Test
    void anUnsignedTargetedCallWithNoContentTypeIsRefused() throws IOException, InterruptedException {
        // No protocol claims an X-Amz-Target call without a content type, yet a route serves it. Rest
        // Assured always sends a content type with a body, so this one goes out by hand.
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(baseUri)
                        .header("X-Amz-Target", "Kinesis_20131202.ListStreams")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(EMPTY_CBOR_MAP))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.ISO_8859_1));

        assertEquals(403, response.statusCode());
        assertTrue(response.body().contains("MissingAuthenticationToken"), response.body());
    }

    @Test
    void aSignedJsonCallWithAnUnrecognisedSmithyProtocolIsCheckedAsItsTarget() {
        String lister = userWithPolicy(allow("sqs:ListQueues", "*"));
        String other = userWithPolicy(allow("sns:ListTopics", "*"));

        listQueues(lister).then().statusCode(200);
        listQueues(other).then()
                .statusCode(400)
                .body("__type", equalTo("AccessDeniedException"))
                .body("message", containsString("sqs:ListQueues"));
    }

    @Test
    void aRestRequestIsCheckedAsItsRouteWhateverTargetItCarries() {
        String bucket = "unclaimed-" + UUID.randomUUID().toString().substring(0, 8);
        given().header("Authorization", auth(ACCOUNT_ID, "s3")).when().put("/" + bucket)
                .then().statusCode(200);
        given().header("Authorization", auth(ACCOUNT_ID, "s3")).body("kept").when().put("/" + bucket + "/doc")
                .then().statusCode(200);
        String reader = userWithPolicy(allow("s3:GetObject", "arn:aws:s3:::" + bucket + "/*"));

        // Unclaimed, the request was checked as the s3:GetObject its target named, and the delete ran.
        given()
                .header("Authorization", auth(reader, "s3"))
                .header("X-Amz-Target", "AmazonS3.GetObject")
                .header("smithy-protocol", "unrecognised")
        .when()
                .delete("/" + bucket + "/doc")
        .then()
                .statusCode(403)
                .body(containsString("s3:DeleteObject"));

        given().header("Authorization", auth(reader, "s3")).when().get("/" + bucket + "/doc")
                .then().statusCode(200).body(equalTo("kept"));
    }

    private static Response listQueues(String accessKeyId) {
        return given()
                .header("Authorization", auth(accessKeyId, "sqs"))
                .contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", "AmazonSQS.ListQueues")
                .header("smithy-protocol", "unrecognised")
                .body("{}")
        .when()
                .post("/");
    }

    private static String allow(String action, String resource) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"%s","Resource":"%s"}]}"""
                .formatted(action, resource);
    }

    private String userWithPolicy(String document) {
        String userName = "unclaimed-" + UUID.randomUUID().toString().substring(0, 8);
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(),
                auth(ACCOUNT_ID, "iam"), userName).accessKeyId();
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT_ID, "iam"), userName, "only", document);
        return accessKeyId;
    }
}
