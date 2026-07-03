package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Exercises the smithy-rpc-v2-cbor wire format exactly as the AWS SDKs send it:
 * POST /service/{serviceShapeName}/operation/{operation} where the service name
 * segment is the X-Amz-Target prefix without its trailing dot (e.g.
 * DynamoDB_20120810, Kinesis_20131202, GraniteServiceVersion20100801), with the
 * smithy-protocol request header and a generic application/cbor content type.
 * Also covers the sibling legacy format (x-amz-cbor-1.1 + X-Amz-Target at the
 * root path) and content-type normalization during protocol transitions.
 * Captured from aws-sdk-java 2.46.7 — see local/plan/smithy/captured/SUMMARY.md.
 */
@QuarkusTest
class SmithyRpcV2RoutingIntegrationTest {

    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void dynamoDbRoutesViaTargetPrefixServiceName() throws Exception {
        JsonNode createRequest = JSON_MAPPER.readTree("""
            {
                "TableName": "RpcV2WireTable",
                "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                "ProvisionedThroughput": {"ReadCapacityUnits": 1, "WriteCapacityUnits": 1}
            }
            """);

        byte[] created = given()
            .contentType("application/cbor")
            .accept("application/cbor")
            .header("smithy-protocol", "rpc-v2-cbor")
            .body(CBOR_MAPPER.writeValueAsBytes(createRequest))
        .when()
            .post("/service/DynamoDB_20120810/operation/CreateTable")
        .then()
            .statusCode(200)
            .header("smithy-protocol", equalTo("rpc-v2-cbor"))
            .contentType("application/cbor")
            .extract().asByteArray();

        assertThat(CBOR_MAPPER.readTree(created)
                .path("TableDescription").path("TableName").asText(), equalTo("RpcV2WireTable"));

        given()
            .contentType("application/cbor")
            .accept("application/cbor")
            .header("smithy-protocol", "rpc-v2-cbor")
            .body(CBOR_MAPPER.writeValueAsBytes(JSON_MAPPER.readTree("""
                {"TableName": "RpcV2WireTable"}
                """)))
        .when()
            .post("/service/DynamoDB_20120810/operation/DeleteTable")
        .then()
            .statusCode(200);
    }

    @Test
    void kinesisRoutesViaTargetPrefixServiceName() throws Exception {
        byte[] response = given()
            .contentType("application/cbor")
            .accept("application/cbor")
            .header("smithy-protocol", "rpc-v2-cbor")
            .body(CBOR_MAPPER.writeValueAsBytes(JSON_MAPPER.createObjectNode()))
        .when()
            .post("/service/Kinesis_20131202/operation/ListStreams")
        .then()
            .statusCode(200)
            .header("smithy-protocol", equalTo("rpc-v2-cbor"))
            .contentType("application/cbor")
            .extract().asByteArray();

        assertThat(CBOR_MAPPER.readTree(response).path("StreamNames"), notNullValue());
    }

    @Test
    void cloudWatchRoutesViaGraniteServiceName() throws Exception {
        byte[] response = given()
            .contentType("application/cbor")
            .accept("application/cbor")
            .header("smithy-protocol", "rpc-v2-cbor")
            .body(CBOR_MAPPER.writeValueAsBytes(JSON_MAPPER.createObjectNode()))
        .when()
            .post("/service/GraniteServiceVersion20100801/operation/ListMetrics")
        .then()
            .statusCode(200)
            .header("smithy-protocol", equalTo("rpc-v2-cbor"))
            .contentType("application/cbor")
            .extract().asByteArray();

        assertThat(CBOR_MAPPER.readTree(response).path("Metrics"), notNullValue());
    }

    @Test
    void unknownServiceNameReturns404() throws Exception {
        given()
            .contentType("application/cbor")
            .accept("application/cbor")
            .header("smithy-protocol", "rpc-v2-cbor")
            .body(CBOR_MAPPER.writeValueAsBytes(JSON_MAPPER.createObjectNode()))
        .when()
            .post("/service/NoSuchService/operation/DoThing")
        .then()
            .statusCode(404);
    }

    @Test
    void legacyCborTargetAtRootEchoesAmzCborContentType() throws Exception {
        // The live Kinesis format from aws-sdk-java: x-amz-cbor-1.1 body with
        // X-Amz-Target at the root path, no smithy-protocol request header.
        byte[] response = given()
            .contentType("application/x-amz-cbor-1.1")
            .header("X-Amz-Target", "Kinesis_20131202.ListStreams")
            .body(CBOR_MAPPER.writeValueAsBytes(JSON_MAPPER.createObjectNode()))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/x-amz-cbor-1.1")
            .extract().asByteArray();

        assertThat(CBOR_MAPPER.readTree(response).path("StreamNames"), notNullValue());
    }

    @Test
    void smithyProtocolHeaderNormalizesDriftedContentType() throws Exception {
        // A rpcv2Cbor request whose Content-Type drifted away from application/cbor
        // must still route: the smithy-protocol header is the definitive claim
        // signal and AwsCborContentTypeFilter normalizes the media type for it.
        given()
            .contentType("application/octet-stream")
            .header("smithy-protocol", "rpc-v2-cbor")
            .body(CBOR_MAPPER.writeValueAsBytes(JSON_MAPPER.createObjectNode()))
        .when()
            .post("/service/Kinesis_20131202/operation/ListStreams")
        .then()
            .statusCode(200)
            .header("smithy-protocol", equalTo("rpc-v2-cbor"));
    }

    @Test
    void straySmithyHeaderOutsideRpcV2PathDoesNotRewriteContentType() {
        // A Smithy-Protocol header on a non-rpcv2 path must not trigger content
        // type normalization: later filters (e.g. S3VirtualHostFilter) rely on
        // seeing the original management content type, and the request must
        // still reach its normal JSON controller.
        given()
            .contentType("application/x-amz-json-1.1")
            .header("Smithy-Protocol", "rpc-v2-cbor")
            .header("X-Amz-Target", "AmazonSSM.DescribeParameters")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void amzCbor11OnRpcV2PathEchoesOriginalContentType() throws Exception {
        given()
            .contentType("application/x-amz-cbor-1.1")
            .header("smithy-protocol", "rpc-v2-cbor")
            .body(CBOR_MAPPER.writeValueAsBytes(JSON_MAPPER.createObjectNode()))
        .when()
            .post("/service/Kinesis_20131202/operation/ListStreams")
        .then()
            .statusCode(200)
            .contentType("application/x-amz-cbor-1.1");
    }
}
