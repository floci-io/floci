package io.floci.aws.services.cloudwatch.metrics;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.Status;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testutil.AwsRequestSigner;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class OtlpMetricsSignatureIntegrationTest {

    @TestHTTPResource("/v1/metrics")
    URI endpoint;

    @Inject
    OtlpMetricsService service;

    @Test
    void acceptsSignedRequestsAndEncodesSignatureErrorsAsOtlpStatus() throws Exception {
        AwsRequestSigner root = AwsRequestSigner.signedAs("test", "test", "monitoring");
        byte[] json = "{}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> signedHeaders = Map.of();
        given().contentType("application/json").headers(root.headersFor("POST", endpoint, signedHeaders, json))
                .body(json).post(endpoint).then().statusCode(200);
        given().contentType("application/json").headers(root.withSecret("wrong-secret")
                        .headersFor("POST", endpoint, signedHeaders, json))
                .body(json).post(endpoint).then().statusCode(403).body("code", equalTo(7));

        byte[] protobuf = {10, 0};
        byte[] response = given().contentType("application/x-protobuf").headers(root.withSecret("wrong-secret")
                        .headersFor("POST", endpoint, signedHeaders, protobuf))
                .body(protobuf).post(endpoint).then().statusCode(403).extract().asByteArray();
        assertEquals(7, Status.parseFrom(response).getCode());
    }

    @Test
    void rejectsIncompleteSignaturesAsOtlpStatus() {
        given().contentType("application/json")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=invalid")
                .body("{}").post(endpoint).then().statusCode(403).body("code", equalTo(7));
        given().contentType("application/json").header("Authorization", "Bearer invalid")
                .body("{}").post(endpoint).then().statusCode(403).body("code", equalTo(7));
    }

    @Test
    void storesPresignedRequestsInTheirSigningRegion() throws Exception {
        String name = "presigned_" + UUID.randomUUID().toString().replace("-", "");
        byte[] json = """
                {"resourceMetrics":[{"scopeMetrics":[{"metrics":[{"name":"%s",
                "gauge":{"dataPoints":[{"timeUnixNano":"%d","asInt":"1"}]}}]}]}]}
                """.formatted(name, Instant.now().toEpochMilli() * 1_000_000L).getBytes(StandardCharsets.UTF_8);
        AwsRequestSigner signer = AwsRequestSigner.signedAs("test", "test", "monitoring").inRegion("eu-west-1");
        given().urlEncodingEnabled(false).contentType("application/json").body(json)
                .post(signer.presignedUri("POST", endpoint, Map.of(), json)).then().statusCode(200);
        assertTrue(hasMetric("eu-west-1", name));
        assertFalse(hasMetric("us-east-1", name));
    }

    private boolean hasMetric(String region, String name) {
        return service.requests(region).stream().flatMap(bytes -> {
            try {
                return ExportMetricsServiceRequest.parseFrom(bytes).getResourceMetricsList().stream();
            } catch (InvalidProtocolBufferException e) {
                throw new AssertionError("Could not parse stored OTLP metrics", e);
            }
        }).flatMap(resource -> resource.getScopeMetricsList().stream())
                .flatMap(scope -> scope.getMetricsList().stream()).anyMatch(metric -> name.equals(metric.getName()));
    }
}
