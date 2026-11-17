package io.floci.aws.core.common;

import io.floci.aws.testing.ValidateSignaturesProfile;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(ValidateSignaturesProfile.class)
class SigV4HeaderAmbiguityIntegrationTest {
    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);
    private static final String TARGET = "AmazonSQS.ListQueues";

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void aSingleSignedHeaderRemainsAuthenticated() throws Exception {
        Map<String, String> signed = signed();
        assertEquals(200, send(signed, List.of(signed.get("Authorization"))).statusCode());
    }

    @Test
    void identicalPhysicalAuthorizationHeadersRemainAuthenticated() throws Exception {
        Map<String, String> signed = signed();
        String authorization = signed.get("Authorization");
        assertEquals(200, send(signed, List.of(authorization, authorization)).statusCode());
    }

    @Test
    void identicalCombinedAuthorizationHeadersRemainAuthenticated() throws Exception {
        Map<String, String> signed = signed();
        String authorization = signed.get("Authorization");
        assertEquals(200, send(signed, List.of(authorization + ", " + authorization)).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Credential", "SignedHeaders", "Signature"})
    void duplicateParametersAreRejectedEvenWhenTheLastValueWouldVerify(String parameter) throws Exception {
        Map<String, String> signed = signed();
        String authorization = signed.get("Authorization");
        int start = authorization.indexOf(parameter + "=") + parameter.length() + 1;
        int end = authorization.indexOf(',', start);
        String value = authorization.substring(start, end < 0 ? authorization.length() : end).trim();
        assertIncomplete(send(signed, List.of(authorization + ", " + parameter + "=" + value)));
    }

    @Test
    void aValidLaterHeaderCannotHideAnEarlierInvalidSignature() throws Exception {
        Map<String, String> signed = signed();
        String authorization = signed.get("Authorization");
        String first = authorization.substring(0, authorization.indexOf("Signature="))
                + "Signature=" + "0".repeat(64);
        assertIncomplete(send(signed, List.of(first, authorization)));
    }

    @Test
    void aCompleteHeaderCannotFillTheMissingFieldsOfAnotherHeader() throws Exception {
        Map<String, String> signed = signed();
        String authorization = signed.get("Authorization");
        String incomplete = authorization.substring(0, authorization.indexOf(','));
        assertIncomplete(send(signed, List.of(incomplete, authorization)));
    }

    private Map<String, String> signed() throws Exception {
        return AwsRequestSigner.signedAs("test", "test", "sqs")
                .headersFor("POST", endpoint, Map.of("x-amz-target", TARGET), BODY);
    }

    private HttpResponse<String> send(Map<String, String> signed, List<String> authorizations) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(15)).version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/x-amz-json-1.0")
                .header("X-Amz-Target", TARGET).header("X-Amz-Date", signed.get("X-Amz-Date"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(BODY));
        authorizations.forEach(value -> request.header("Authorization", value));
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static void assertIncomplete(HttpResponse<String> response) {
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("IncompleteSignatureException"), response.body());
    }
}
