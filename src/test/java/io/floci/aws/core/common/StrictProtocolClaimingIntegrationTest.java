package io.floci.aws.core.common;

import io.floci.aws.testing.IndependentServiceSettingsProfile;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Verifies the floci.protocols.strict-claiming enforcement: RPC-signaled
 * requests that no supported wire protocol claims are rejected per the Smithy
 * wire-protocol-selection guide, while REST and well-formed RPC traffic is
 * untouched. The setting comes from {@link IndependentServiceSettingsProfile}.
 */
@QuarkusTest
@TestProfile(IndependentServiceSettingsProfile.class)
class StrictProtocolClaimingIntegrationTest {

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void rpcV2JsonIsRecognizedAndRejected() {
        given()
            .contentType("application/json")
            .header("Smithy-Protocol", "rpc-v2-json")
            .body("{}")
        .when()
            .post("/service/AmazonSQS/operation/ListQueues")
        .then()
            .statusCode(400)
            .header("x-amzn-query-error", equalTo("UnknownOperationException;Sender"))
            .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void unknownSmithyProtocolValueIsRejected() {
        given()
            .contentType("application/cbor")
            .header("Smithy-Protocol", "rpc-v2-msgpack")
            .body(new byte[]{(byte) 0xbf, (byte) 0xff})
        .when()
            .post("/service/DynamoDB_20120810/operation/ListTables")
        .then()
            .statusCode(404)
            .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void targetPostWithForeignContentTypeIsRejected() {
        given()
            .contentType("text/plain")
            .header("X-Amz-Target", "AmazonSSM.DescribeParameters")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void wellFormedRpcTrafficIsUnaffected() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "AmazonSSM.DescribeParameters")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void restTrafficIsUnaffected() {
        // S3 list-buckets at root bypasses claiming enforcement.
        given()
        .when()
            .get("/")
        .then()
            .statusCode(200);

        // A POST on a REST path reaches its normal handler (here S3's
        // /{bucket}/{key} catch-all) instead of the claimer's rejection,
        // which would carry the x-amzn-query-error header.
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/no/such/rest/path")
        .then()
            .header("x-amzn-query-error", nullValue());
    }
}