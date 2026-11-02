package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies that a JSON 1.0 {@code PutMetricData} whose body is gzip-compressed, as the AWS CLI and
 * SDKs send any body of 10 KiB or more, is authenticated over the compressed bytes as sent, decided
 * by IAM, and only then decompressed for the handler, within the 10 MiB the CBOR protocol already
 * allows a decompressed body.
 *
 * <p>Signature validation and IAM enforcement come from {@link SignedIamEnforcementProfile}.
 */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class CloudWatchMetricsGzipJsonIntegrationTest {

    private static final String JSON_1_0 = "application/x-amz-json-1.0";
    private static final String TARGET = "GraniteServiceVersion20100801.";
    private static final int DECOMPRESSED_LIMIT = 10 * 1024 * 1024;
    private static final AwsRequestSigner ROOT = AwsRequestSigner.signedAs("test", "test", "monitoring");

    @TestHTTPResource("/")
    URI endpoint;

    @BeforeAll
    static void configureAwsContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "gzip", "GZIP"})
    void aCompressedPublicationIsStoredLikeAPlainOne(String encoding) throws Exception {
        String namespace = namespace();
        byte[] plain = publication(namespace);
        boolean compressed = !"none".equals(encoding);
        request(ROOT, "PutMetricData", compressed ? gzip(plain) : plain, compressed ? encoding : null)
                .then().statusCode(200);
        long now = Instant.now().getEpochSecond();
        byte[] statistics = ("""
                {"Namespace":"%s","MetricName":"Compressed","StartTime":%d,"EndTime":%d,
                 "Period":60,"Statistics":["Sum"]}
                """.formatted(namespace, now - 120, now + 120)).getBytes(StandardCharsets.UTF_8);
        request(ROOT, "GetMetricStatistics", statistics, null).then().statusCode(200)
                .body("Datapoints[0].Sum", equalTo(7.0F));
    }

    @Test
    void anOperationNameTheHandlerUpperCasesIsDecompressedToo() throws Exception {
        String namespace = namespace();
        request(ROOT, "putMetricData", gzip(publication(namespace)), "gzip").then().statusCode(200);
        list(namespace).then().statusCode(200).body("Metrics[0].MetricName", equalTo("Compressed"));
    }

    @Test
    void aBodyThatDecompressesToTheLimitIsAccepted() throws Exception {
        String namespace = namespace();
        request(ROOT, "PutMetricData", gzip(paddedPublication(namespace, DECOMPRESSED_LIMIT)), "gzip")
                .then().statusCode(200);
        list(namespace).then().statusCode(200).body("Metrics[0].MetricName", equalTo("Compressed"));
    }

    @Test
    void aBodyThatDecompressesPastTheLimitIsRefused() throws Exception {
        String namespace = namespace();
        request(ROOT, "PutMetricData", gzip(paddedPublication(namespace, DECOMPRESSED_LIMIT + 1)), "gzip")
                .then().statusCode(413)
                .header("x-amzn-query-error", "PayloadTooLargeException;Sender")
                .body("__type", equalTo("PayloadTooLargeException"));
        assertAbsent(namespace);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-header", "truncated", "invalid-crc"})
    void aBodyThatIsNotValidGzipIsRefused(String corruption) throws Exception {
        String namespace = namespace();
        byte[] body = gzip(publication(namespace));
        switch (corruption) {
            case "invalid-header" -> body[0] = 0;
            case "truncated" -> body = Arrays.copyOf(body, body.length - 5);
            case "invalid-crc" -> body[body.length - 8] ^= 1;
            default -> throw new IllegalArgumentException(corruption);
        }
        request(ROOT, "PutMetricData", body, "gzip").then().statusCode(400)
                .header("x-amzn-query-error", "InvalidRequestException;Sender")
                .body("__type", equalTo("InvalidRequestException"));
        assertAbsent(namespace);
    }

    @Test
    void anEmptyBodyMarkedGzipReadsAsNoInputLikeAnEmptyPlainBody() throws Exception {
        Response plain = request(ROOT, "PutMetricData", new byte[0], null);
        request(ROOT, "PutMetricData", new byte[0], "gzip").then()
                .statusCode(plain.statusCode())
                .body(equalTo(plain.asString()));
    }

    @Test
    void aDecompressedBodyThatIsNotJsonIsASerializationError() throws Exception {
        request(ROOT, "PutMetricData", gzip("{".getBytes(StandardCharsets.UTF_8)), "gzip")
                .then().statusCode(400).body("__type", equalTo("SerializationException"));
    }

    @Test
    void theSignatureIsCheckedOverTheCompressedBytesBeforeTheyAreDecompressed() throws Exception {
        String namespace = namespace();
        byte[] plain = publication(namespace);
        Map<String, String> signed = signedHeaders("PutMetricData", "gzip");
        given().headers(signed).headers(ROOT.headersFor("POST", endpoint, signed, plain))
                .contentType(JSON_1_0).body(gzip(plain)).post(endpoint)
                .then().statusCode(403).body("__type", equalTo("InvalidSignatureException"));
        request(ROOT.withSecret("wrong-secret"), "PutMetricData", new byte[]{1, 2, 3}, "gzip")
                .then().statusCode(403).body("__type", equalTo("InvalidSignatureException"));
        assertAbsent(namespace);
    }

    @Test
    void iamDecidesACompressedPublicationBeforeItIsDecompressed() throws Exception {
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "cw-gzip-denied-")) {
            AwsRequestSigner signer = user.signer("monitoring");
            String denied = namespace();
            request(signer, "PutMetricData", gzip(publication(denied)), "gzip")
                    .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
            request(signer, "PutMetricData", new byte[]{1, 2, 3}, "gzip")
                    .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
            assertAbsent(denied);
        }

        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "cw-gzip-allowed-", "publish-metrics", """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Action": "cloudwatch:PutMetricData",
                      "Resource": "*"
                    }
                  ]
                }
                """)) {
            AwsRequestSigner signer = user.signer("monitoring");
            String allowed = namespace();
            request(signer, "PutMetricData", gzip(publication(allowed)), "gzip").then().statusCode(200);
            list(allowed).then().statusCode(200).body("Metrics[0].MetricName", equalTo("Compressed"));
        }
    }

    @Test
    void otherCloudWatchOperationsAreNotDecompressed() throws Exception {
        request(ROOT, "ListMetrics", gzip("{}".getBytes(StandardCharsets.UTF_8)), "gzip")
                .then().statusCode(400).body("__type", equalTo("SerializationException"));
    }

    @Test
    void aPublicationSentAsPlainJsonIsNotDecompressed() throws Exception {
        byte[] body = {1, 2, 3};
        Map<String, String> signed = signedHeaders("PutMetricData", "gzip");
        given().headers(signed).headers(ROOT.headersFor("POST", endpoint, signed, body))
                .contentType("application/json").body(body).post(endpoint)
                .then().statusCode(404).body("__type", equalTo("UnknownOperationException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AWSStepFunctions.ListStateMachines", "TrentService.ListKeys"})
    void otherJsonServicesAreNotDecompressed(String target) throws Exception {
        boolean kms = target.startsWith("TrentService.");
        byte[] body = gzip("{}".getBytes(StandardCharsets.UTF_8));
        Map<String, String> signed = Map.of("content-encoding", "gzip", "x-amz-target", target);
        given().headers(signed)
                .headers(AwsRequestSigner.signedAs("test", "test", kms ? "kms" : "states")
                        .headersFor("POST", endpoint, signed, body))
                .contentType(kms ? "application/x-amz-json-1.1" : JSON_1_0).body(body).post(endpoint)
                .then().statusCode(400).body("__type", equalTo("SerializationException"));
    }

    private Response list(String namespace) throws Exception {
        return request(ROOT, "ListMetrics",
                ("{\"Namespace\":\"" + namespace + "\"}").getBytes(StandardCharsets.UTF_8), null);
    }

    private void assertAbsent(String namespace) throws Exception {
        list(namespace).then().statusCode(200).body("Metrics", empty());
    }

    private Response request(AwsRequestSigner signer, String operation, byte[] body, String encoding)
            throws Exception {
        Map<String, String> signed = signedHeaders(operation, encoding);
        return given().headers(signed).headers(signer.headersFor("POST", endpoint, signed, body))
                .contentType(JSON_1_0).body(body).post(endpoint);
    }

    private static Map<String, String> signedHeaders(String operation, String encoding) {
        Map<String, String> signed = new HashMap<>();
        signed.put("x-amz-target", TARGET + operation);
        if (encoding != null) {
            signed.put("content-encoding", encoding);
        }
        return signed;
    }

    private static String namespace() {
        return "Floci/Gzip/" + UUID.randomUUID();
    }

    private static byte[] publication(String namespace) {
        return ("{\"Namespace\":\"" + namespace
                + "\",\"MetricData\":[{\"MetricName\":\"Compressed\",\"Value\":7,\"Unit\":\"Count\"}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** The publication followed by spaces up to {@code size} bytes, which JSON reads as whitespace. */
    private static byte[] paddedPublication(String namespace, int size) {
        byte[] plain = publication(namespace);
        byte[] padded = Arrays.copyOf(plain, size);
        Arrays.fill(padded, plain.length, padded.length, (byte) ' ');
        return padded;
    }

    private static byte[] gzip(byte[] plain) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (GZIPOutputStream compressed = new GZIPOutputStream(result)) {
            compressed.write(plain);
        }
        return result.toByteArray();
    }
}
