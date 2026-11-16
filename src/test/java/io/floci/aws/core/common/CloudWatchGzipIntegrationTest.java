package io.floci.aws.core.common;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.floci.aws.testing.GzipFixtures.gzip;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class CloudWatchGzipIntegrationTest {

    private static final String JSON_1_0 = "application/x-amz-json-1.0";
    private static final String TARGET = "GraniteServiceVersion20100801.";
    private static final AwsRequestSigner ROOT = AwsRequestSigner.signedAs("test", "test", "monitoring");

    @TestHTTPResource("/")
    URI endpoint;

    @BeforeAll
    static void configureAwsContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void compressedAndPlainPublicationStoreTheMetric(boolean compressed) throws Exception {
        String namespace = namespace();
        byte[] plain = publication(namespace);
        request(ROOT, "PutMetricData", compressed ? gzip(plain) : plain, compressed ? "gzip" : null)
                .then().statusCode(200);
        list(namespace).then().statusCode(200).body("Metrics[0].MetricName", equalTo("Compressed"));
        long now = Instant.now().getEpochSecond();
        byte[] statistics = ("""
                {"Namespace":"%s","MetricName":"Compressed","StartTime":%d,"EndTime":%d,
                 "Period":60,"Statistics":["Sum"]}
                """.formatted(namespace, now - 120, now + 120)).getBytes(StandardCharsets.UTF_8);
        request(ROOT, "GetMetricStatistics", statistics, null).then().statusCode(200)
                .body("Datapoints[0].Sum", equalTo(7.0F));
    }

    @ParameterizedTest
    @ValueSource(ints = {1024 * 1024 + 1, 5 * 1024 * 1024})
    void decodedBodiesWithinTheLimitAreAccepted(int size) throws Exception {
        String namespace = namespace();
        request(ROOT, "PutMetricData", gzip(paddedPublication(namespace, size)), "gzip")
                .then().statusCode(200);
        list(namespace).then().body("Metrics[0].MetricName", equalTo("Compressed"));
    }

    @Test
    void anOversizeDecodedBodyCannotPublishAMetric() throws Exception {
        String namespace = namespace();
        request(ROOT, "PutMetricData", gzip(paddedPublication(namespace, 5 * 1024 * 1024 + 1)), "gzip")
                .then().statusCode(413).body("__type", equalTo("RequestEntityTooLargeException"));
        assertAbsent(namespace);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-header", "truncated", "invalid-crc"})
    void malformedCompressedBodiesCannotPublishAMetric(String corruption) throws Exception {
        String namespace = namespace();
        byte[] body = gzip(publication(namespace));
        switch (corruption) {
            case "invalid-header" -> body[0] = 0;
            case "truncated" -> body = Arrays.copyOf(body, body.length - 5);
            case "invalid-crc" -> body[body.length - 8] ^= 1;
            default -> throw new IllegalArgumentException(corruption);
        }
        request(ROOT, "PutMetricData", body, "gzip").then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        assertAbsent(namespace);
    }

    @Test
    void decodedInvalidJsonRetainsTheSerializationError() throws Exception {
        request(ROOT, "PutMetricData", gzip("{".getBytes(StandardCharsets.UTF_8)), "gzip")
                .then().statusCode(400).body("__type", equalTo("SerializationException"));
    }

    @Test
    void authenticationRejectsMalformedCompressedBytesBeforeDecoding() throws Exception {
        request(ROOT.withSecret("wrong-secret"), "PutMetricData", new byte[]{1, 2, 3}, "gzip")
                .then().statusCode(403).body("__type", equalTo("InvalidSignatureException"));
    }

    @Test
    void iamStillDeniesPublicationAndAllowsOnlyTheGrantedRegion() throws Exception {
        String user = "gzip-" + UUID.randomUUID();
        String key = null;
        iam("CreateUser", Map.of("UserName", user)).then().statusCode(200);
        try {
            Response credentials = iam("CreateAccessKey", Map.of("UserName", user));
            credentials.then().statusCode(200);
            key = credentials.xmlPath().getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
            String secret = credentials.xmlPath()
                    .getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.SecretAccessKey");
            AwsRequestSigner signer = AwsRequestSigner.signedAs(key, secret, "monitoring");
            String denied = namespace();
            request(signer, "PutMetricData", gzip(publication(denied)), "gzip").then().statusCode(400)
                    .body("__type", equalTo("AccessDeniedException"));
            assertAbsent(denied);

            iam("PutUserPolicy", Map.of("UserName", user, "PolicyName", "regional-publication", "PolicyDocument", """
                    {"Version":"2012-10-17","Statement":[{
                      "Effect":"Allow","Action":"cloudwatch:PutMetricData","Resource":"*",
                      "Condition":{"StringEquals":{"aws:RequestedRegion":"us-east-1"}}
                    }]}
                    """)).then().statusCode(200);
            String allowed = namespace();
            request(signer, "PutMetricData", gzip(publication(allowed)), "gzip").then().statusCode(200);
            list(allowed).then().body("Metrics[0].MetricName", equalTo("Compressed"));
            String wrongRegion = namespace();
            request(signer.inRegion("us-west-2"), "PutMetricData", gzip(publication(wrongRegion)), "gzip")
                    .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
            request(ROOT.inRegion("us-west-2"), "ListMetrics",
                    ("{\"Namespace\":\"" + wrongRegion + "\"}").getBytes(StandardCharsets.UTF_8), null)
                    .then().body("Metrics", empty());
        } finally {
            if (key != null) {
                iam("DeleteAccessKey", Map.of("UserName", user, "AccessKeyId", key)).then().statusCode(200);
            }
            iam("DeleteUserPolicy", Map.of("UserName", user, "PolicyName", "regional-publication"));
            iam("DeleteUser", Map.of("UserName", user)).then().statusCode(200);
        }
    }

    @Test
    void otherCloudWatchOperationsKeepTheirExistingBodyHandling() throws Exception {
        request(ROOT, "ListMetrics", gzip("{}".getBytes(StandardCharsets.UTF_8)), "gzip")
                .then().statusCode(400).body("__type", equalTo("SerializationException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AWSStepFunctions.ListStateMachines", "TrentService.ListKeys"})
    void otherServicesKeepTheirExistingBodyHandling(String target) throws Exception {
        String service = target.startsWith("TrentService") ? "kms" : "states";
        String contentType = service.equals("kms") ? "application/x-amz-json-1.1" : JSON_1_0;
        byte[] body = gzip("{}".getBytes(StandardCharsets.UTF_8));
        signedPost(AwsRequestSigner.signedAs("test", "test", service), target, contentType, body, "gzip")
                .then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
    }

    private Response list(String namespace) throws Exception {
        return request(ROOT, "ListMetrics",
                ("{\"Namespace\":\"" + namespace + "\"}").getBytes(StandardCharsets.UTF_8), null);
    }

    private void assertAbsent(String namespace) throws Exception {
        list(namespace).then().statusCode(200).body("Metrics", empty());
    }

    private Response request(AwsRequestSigner signer, String operation, byte[] body, String encoding) throws Exception {
        return signedPost(signer, TARGET + operation, JSON_1_0, body, encoding);
    }

    private Response signedPost(AwsRequestSigner signer, String target, String contentType,
                                byte[] body, String encoding) throws Exception {
        Map<String, String> signed = new HashMap<>();
        signed.put("x-amz-target", target);
        if (encoding != null) {
            signed.put("content-encoding", encoding);
        }
        return given().headers(signed).headers(signer.headersFor("POST", endpoint, signed, body))
                .contentType(contentType).body(body).post(endpoint);
    }

    private Response iam(String action, Map<String, String> parameters) throws Exception {
        String body = "Action=" + action + "&Version=2010-05-08&" + parameters.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return given().headers(AwsRequestSigner.signedAs("test", "test", "iam")
                        .headersFor("POST", endpoint, Map.of(), body.getBytes(StandardCharsets.UTF_8)))
                .contentType("application/x-www-form-urlencoded").body(body).post(endpoint);
    }

    private static String namespace() {
        return "Floci/Gzip/" + UUID.randomUUID();
    }

    private static byte[] publication(String namespace) {
        return ("{\"Namespace\":\"" + namespace
                + "\",\"MetricData\":[{\"MetricName\":\"Compressed\",\"Value\":7,\"Unit\":\"Count\"}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] paddedPublication(String namespace, int size) {
        byte[] plain = publication(namespace);
        byte[] padded = Arrays.copyOf(plain, size);
        Arrays.fill(padded, plain.length, padded.length, (byte) ' ');
        return padded;
    }
}
