package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ProtocolClaimerIntegrationTest {

    private static final String SQS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260702/us-east-1/sqs/aws4_request, "
                    + "SignedHeaders=host;x-amz-date, Signature=abc";

    @Inject
    ProtocolClaimer claimer;

    private Optional<ProtocolClaim> claim(String method, String path, String contentType,
                                          String smithyProtocol, String xAmzTarget, String authorization) {
        return claimer.claim(new ClaimSignals(method, path, contentType, smithyProtocol, xAmzTarget,
                authorization, null, new MultivaluedHashMap<>()));
    }

    @Test
    void smithyProtocolHeaderClaimsRpcV2CborAheadOfEverything() {
        // All signals at once: the Smithy-Protocol header wins by precedence.
        ProtocolClaim claim = claim("POST", "/service/DynamoDB_20120810/operation/ListTables",
                "application/x-amz-json-1.1", "rpc-v2-cbor", "DynamoDB_20120810.ListTables", null).orElseThrow();

        assertEquals(WireProtocol.RPCV2_CBOR, claim.protocol());
        assertEquals("dynamodb", claim.service().externalKey());
        assertEquals("ListTables", claim.operation());
    }

    @Test
    void rpcV2PathToleratesPrefixSegments() {
        ProtocolClaim claim = claim("POST", "/v1/service/Kinesis_20131202/operation/ListStreams",
                "application/cbor", "rpc-v2-cbor", null, null).orElseThrow();

        assertEquals(WireProtocol.RPCV2_CBOR, claim.protocol());
        assertEquals("kinesis", claim.service().externalKey());
    }

    @Test
    void headerlessCborOnRpcV2PathStillClaims() {
        ProtocolClaim claim = claim("POST", "/service/DynamoDB/operation/GetItem",
                "application/cbor", null, null, null).orElseThrow();

        assertEquals(WireProtocol.RPCV2_CBOR, claim.protocol());
        assertEquals("dynamodb", claim.service().externalKey());
    }

    @Test
    void rpcV2JsonIsRecognized() {
        ProtocolClaim claim = claim("POST", "/service/AmazonSQS/operation/ListQueues",
                "application/json", "rpc-v2-json", null, null).orElseThrow();

        assertEquals(WireProtocol.RPCV2_JSON, claim.protocol());
        assertEquals("sqs", claim.service().externalKey());
    }

    @Test
    void malformedRpcV2SignalsAreUnclaimable() {
        // Valid header value but wrong method.
        assertTrue(claim("GET", "/service/DynamoDB_20120810/operation/ListTables",
                null, "rpc-v2-cbor", null, null).isEmpty());
        // Valid header value but wrong path shape.
        assertTrue(claim("POST", "/", "application/cbor", "rpc-v2-cbor", null, null).isEmpty());
        // Unknown header value.
        assertTrue(claim("POST", "/service/DynamoDB_20120810/operation/ListTables",
                "application/cbor", "rpc-v2-msgpack", null, null).isEmpty());
        // Header value comparison is exact per spec (header NAME is case-insensitive, value is not).
        assertTrue(claim("POST", "/service/DynamoDB_20120810/operation/ListTables",
                "application/cbor", "RPC-V2-CBOR", null, null).isEmpty());
    }

    @Test
    void jsonVersionsSplitOnContentType() {
        ProtocolClaim json10 = claim("POST", "/", "application/x-amz-json-1.0",
                null, "DynamoDB_20120810.ListTables", null).orElseThrow();
        assertEquals(WireProtocol.AWS_JSON_1_0, json10.protocol());
        assertEquals("dynamodb", json10.service().externalKey());
        assertEquals("ListTables", json10.operation());

        ProtocolClaim json11 = claim("POST", "/", "application/x-amz-json-1.1",
                null, "AmazonSSM.DescribeParameters", null).orElseThrow();
        assertEquals(WireProtocol.AWS_JSON_1_1, json11.protocol());
        assertEquals("ssm", json11.service().externalKey());
    }

    @Test
    void mediaTypeParametersAndCasingAreNormalized() {
        ProtocolClaim claim = claim("POST", "/", "Application/X-Amz-Json-1.1; charset=utf-8",
                null, "AmazonSSM.DescribeParameters", null).orElseThrow();

        assertEquals(WireProtocol.AWS_JSON_1_1, claim.protocol());
    }

    @Test
    void legacyCborWithTargetAtRootClaimsCborTarget() {
        // Kinesis via aws-sdk-java: x-amz-cbor-1.1 + X-Amz-Target at root, no smithy header.
        ProtocolClaim claim = claim("POST", "/", "application/x-amz-cbor-1.1",
                null, "Kinesis_20131202.ListStreams", null).orElseThrow();

        assertEquals(WireProtocol.AWS_CBOR_TARGET, claim.protocol());
        assertEquals("kinesis", claim.service().externalKey());
        assertEquals("ListStreams", claim.operation());
    }

    @Test
    void targetWithForeignContentTypeIsUnclaimable() {
        assertTrue(claim("POST", "/", "text/plain",
                null, "AmazonSSM.DescribeParameters", null).isEmpty());
    }

    @Test
    void botoSwfJsonClaimsOnlyTheDocumentedRootTarget() {
        String target = "com.amazonaws.swf.service.model.SimpleWorkflowService.RegisterActivityType";
        ProtocolClaim legacy = claim("POST", "/", "Application/JSON; charset=UTF-8",
                null, target, null).orElseThrow();
        assertEquals(WireProtocol.AWS_JSON_1_0, legacy.protocol());
        assertEquals("swf", legacy.service().externalKey());
        assertEquals("RegisterActivityType", legacy.operation());
        assertEquals(target, legacy.target());

        assertTrue(claim("POST", "/", "text/plain", null, target, null).isEmpty());
        assertTrue(claim("POST", "/", "application/json", "rpc-v2-json", target, null).isEmpty());
        assertEquals("swf", claim("POST", "/", "application/json", null,
                "SimpleWorkflowService.RegisterActivityType", null).orElseThrow().service().externalKey());
        assertTrue(claim("POST", "/", "application/json", null,
                "com.amazonaws.swf.service.model.SimpleWorkflowServiceOther.RegisterActivityType", null).isEmpty());
        assertTrue(claim("POST", "/", "application/json", null,
                "AmazonSSM.DescribeParameters", null).isEmpty());
        assertEquals(WireProtocol.REST, claim("GET", "/", "application/json",
                null, target, null).orElseThrow().protocol());
        assertEquals(WireProtocol.REST, claim("POST", "/other", "application/json",
                null, target, null).orElseThrow().protocol());
    }

    @Test
    void unknownTargetStillClaimsProtocolWithoutService() {
        ProtocolClaim claim = claim("POST", "/", "application/x-amz-json-1.1",
                null, "NoSuchService.DoThing", null).orElseThrow();

        assertEquals(WireProtocol.AWS_JSON_1_1, claim.protocol());
        assertNull(claim.service());
        assertEquals("NoSuchService.DoThing", claim.target());
    }

    @Test
    void formPostAtRootClaimsQueryAndResolvesServiceFromCredentialScope() {
        ProtocolClaim withAuth = claim("POST", "/",
                "application/x-www-form-urlencoded; charset=utf-8", null, null, SQS_AUTH).orElseThrow();
        assertEquals(WireProtocol.AWS_QUERY, withAuth.protocol());
        assertEquals("sqs", withAuth.service().externalKey());

        ProtocolClaim withoutAuth = claim("POST", "/",
                "application/x-www-form-urlencoded", null, null, null).orElseThrow();
        assertEquals(WireProtocol.AWS_QUERY, withoutAuth.protocol());
        assertNull(withoutAuth.service());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Action", "Operation"})
    void cloudWatchGetsUseTheQueryClaimWithOrWithoutASignature(String actionParameter) {
        MultivaluedHashMap<String, String> query = new MultivaluedHashMap<>();
        query.putSingle(actionParameter, "GetMetricStatistics");
        query.putSingle("Version", "2010-08-01");
        for (String authorization : new String[] {null, SQS_AUTH.replace("/sqs/", "/monitoring/")}) {
            ProtocolClaim claim = claimer.claim(new ClaimSignals("GET", "/", null, null, null,
                    authorization, null, query)).orElseThrow();
            assertEquals(WireProtocol.AWS_QUERY, claim.protocol());
            assertEquals("monitoring", claim.service().externalKey());
            assertEquals("GetMetricStatistics", claim.operation());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /bucket",
            "POST, /"
    })
    void cloudWatchGetClaimRequiresARootGet(String method, String path) {
        MultivaluedHashMap<String, String> query = new MultivaluedHashMap<>();
        query.putSingle("Action", "GetMetricStatistics");
        ProtocolClaim claim = claimer.claim(new ClaimSignals(method, path, null, null, null,
                SQS_AUTH.replace("/sqs/", "/monitoring/"), null, query)).orElseThrow();
        assertEquals(WireProtocol.REST, claim.protocol());
    }

    @Test
    void nonRpcTrafficFallsThroughAsRest() {
        Optional<ProtocolClaim> get = claim("GET", "/", null, null, null, null);
        assertEquals(WireProtocol.REST, get.orElseThrow().protocol());

        Optional<ProtocolClaim> s3Post = claim("POST", "/my-bucket",
                "multipart/form-data; boundary=x", null, null, null);
        assertEquals(WireProtocol.REST, s3Post.orElseThrow().protocol());

        Optional<ProtocolClaim> lambdaPath = claim("POST", "/2015-03-31/functions",
                "application/json", null, null, null);
        assertEquals(WireProtocol.REST, lambdaPath.orElseThrow().protocol());
    }

    @Test
    void queryFormOnNonRootPathIsRest() {
        // Form posts on non-root paths (e.g. before SQS queue-URL rewriting) are
        // not query claims; the router filter rewrites them to "/" before this
        // claimer runs in the filter chain.
        ProtocolClaim claim = claim("POST", "/123456789012/my-queue",
                "application/x-www-form-urlencoded", null, null, null).orElseThrow();

        assertEquals(WireProtocol.REST, claim.protocol());
    }
}
