package io.floci.aws.services.cloudwatch.metrics;

import com.google.rpc.Status;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class OtlpMetricsSignatureIntegrationTest {

    @TestHTTPResource("/v1/metrics")
    URI endpoint;

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
}
