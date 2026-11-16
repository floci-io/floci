package io.floci.aws.core.common;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.CBORParser;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.NotFoundException;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.KeyState;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.translate.TranslateClient;
import software.amazon.awssdk.services.translate.model.TranslateDocumentResponse;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(AwsJsonCborServiceRoutingIntegrationTest.RoutingProfile.class)
class AwsJsonCborTypedShapesIntegrationTest {

    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());
    private static final byte[] PAYLOAD = {0, (byte) 255, 1, 127, 0};

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void secretsManagerReturnsBinaryBytesAndTaggedCreatedDate() throws Exception {
        try (SecretsManagerClient secrets = AwsTestClients.create(SecretsManagerClient.builder(),
                "AKID", "secret", Region.US_EAST_1, endpoint)) {
            String secretId = secrets.createSecret(request -> request.name("cbor-shapes-" + UUID.randomUUID())
                    .secretBinary(SdkBytes.fromByteArray(PAYLOAD))).arn();
            try {
                GetSecretValueResponse expected = secrets.getSecretValue(request -> request.secretId(secretId));
                byte[] body = cborCall("secretsmanager", "GetSecretValue", Map.of("SecretId", secretId))
                        .asByteArray();
                assertArrayEquals(PAYLOAD, byteString(body, "SecretBinary"));
                assertEquals(1, tagOf(body, "CreatedDate"), "CreatedDate must carry CBOR tag 1");
                JsonNode decoded = CBOR.readTree(body);
                assertEquals(expected.createdDate().toEpochMilli() / 1000.0,
                        decoded.path("CreatedDate").asDouble(), 0.001, "timestamps use epoch seconds");
            } finally {
                secrets.deleteSecret(request -> request.secretId(secretId).forceDeleteWithoutRecovery(true));
            }
        }
    }

    @Test
    void kmsEncryptAcceptsByteStringsAndReturnsDecryptableByteStrings() throws Exception {
        try (KmsClient kms = kmsClient()) {
            String keyId = kms.createKey().keyMetadata().keyId();
            try {
                byte[] request = CBOR.writeValueAsBytes(Map.of("KeyId", keyId, "Plaintext", PAYLOAD));
                assertArrayEquals(PAYLOAD, byteString(request, "Plaintext"));
                byte[] encrypted = cborCall("TrentService", "Encrypt", request).asByteArray();
                byte[] ciphertext = byteString(encrypted, "CiphertextBlob");
                assertTrue(ciphertext.length > 0);
                assertArrayEquals(PAYLOAD, kms.decrypt(builder -> builder.keyId(keyId)
                        .ciphertextBlob(SdkBytes.fromByteArray(ciphertext))).plaintext().asByteArray());
            } finally {
                kms.scheduleKeyDeletion(request -> request.keyId(keyId).pendingWindowInDays(7));
            }
        }
    }

    @Test
    void kmsDisableKeyHasNoResponseBodyOrContentTypeAndChangesKeyState() throws Exception {
        try (KmsClient kms = kmsClient()) {
            String keyId = kms.createKey().keyMetadata().keyId();
            try {
                // Botocore models DisableKey without an output shape, unlike an empty output structure.
                Response response = cborCall("TrentService", "DisableKey", Map.of("KeyId", keyId));
                assertEquals(KeyState.DISABLED, kms.describeKey(request -> request.keyId(keyId)).keyMetadata().keyState());
                assertEquals(0, response.asByteArray().length, "Unit output must have an empty HTTP body");
                assertNull(response.header("Content-Type"), "Unit output must omit Content-Type");
            } finally {
                kms.scheduleKeyDeletion(request -> request.keyId(keyId).pendingWindowInDays(7));
            }
        }
    }

    @Test
    void apiGatewayV2FacadePreservesUppercaseMembersAndTagsNumericCreatedDate() throws Exception {
        try (ApiGatewayV2Client gateway = AwsTestClients.create(ApiGatewayV2Client.builder(),
                "AKID", "secret", Region.US_EAST_1, endpoint)) {
            String apiId = gateway.createApi(request -> request.name("cbor-api-" + UUID.randomUUID())
                    .protocolType("HTTP")).apiId();
            try {
                Instant createdDate = gateway.getApi(request -> request.apiId(apiId)).createdDate();
                byte[] body = cborCall("AmazonApiGatewayV2", "GetApi", Map.of("ApiId", apiId)).asByteArray();
                JsonNode decoded = CBOR.readTree(body);
                assertEquals(apiId, decoded.path("ApiId").asText());
                assertFalse(decoded.has("apiId"), "JSON facade members retain their uppercase spelling");
                assertTrue(decoded.path("CreatedDate").isNumber(), "facade timestamps use numeric epoch seconds");
                assertEquals(1, tagOf(body, "CreatedDate"));
                assertEquals(createdDate.toEpochMilli() / 1000.0, decoded.path("CreatedDate").asDouble(), 0.001);
            } finally {
                gateway.deleteApi(request -> request.apiId(apiId));
            }
        }
    }

    @Test
    void rpcV2CreateApiReturns200AndCreatesAnApi() throws Exception {
        try (ApiGatewayV2Client gateway = AwsTestClients.create(ApiGatewayV2Client.builder(),
                "AKID", "secret", Region.US_EAST_1, endpoint)) {
            byte[] request = CBOR.writeValueAsBytes(Map.of("Name", "cbor-create-" + UUID.randomUUID(),
                    "ProtocolType", "HTTP"));
            Response response = rawCborCall("AmazonApiGatewayV2", "CreateApi", request);
            String apiId = CBOR.readTree(response.asByteArray()).path("ApiId").asText();
            try {
                assertFalse(apiId.isBlank(), "CreateApi must return the created API identifier");
                assertEquals(apiId, gateway.getApi(builder -> builder.apiId(apiId)).apiId());
                assertEquals(200, response.statusCode(), "RPC v2 creation succeeds with HTTP 200");
            } finally {
                if (!apiId.isBlank()) {
                    gateway.deleteApi(builder -> builder.apiId(apiId));
                }
            }
        }
    }

    @Test
    void rpcV2DeleteApiReturns200AndDeletesTheApi() throws Exception {
        try (ApiGatewayV2Client gateway = AwsTestClients.create(ApiGatewayV2Client.builder(),
                "AKID", "secret", Region.US_EAST_1, endpoint)) {
            String apiId = gateway.createApi(request -> request.name("cbor-delete-" + UUID.randomUUID())
                    .protocolType("HTTP")).apiId();
            try {
                Response response = rawCborCall("AmazonApiGatewayV2", "DeleteApi",
                        CBOR.writeValueAsBytes(Map.of("ApiId", apiId)));
                assertThrows(NotFoundException.class, () -> gateway.getApi(request -> request.apiId(apiId)));
                assertEquals(200, response.statusCode(), "RPC v2 deletion succeeds with HTTP 200");
            } finally {
                try {
                    gateway.deleteApi(request -> request.apiId(apiId));
                } catch (NotFoundException expected) {
                    // DeleteApi under test already removed this fixture.
                }
            }
        }
    }

    @Test
    void translateSdkUsesRpcV2CborAndRoundTripsDocumentBytes() {
        AtomicReference<SdkHttpRequest> transmitted = new AtomicReference<>();
        ExecutionInterceptor capture = new ExecutionInterceptor() {
            @Override
            public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
                transmitted.set(context.httpRequest());
            }
        };
        try (TranslateClient translate = AwsTestClients.create(TranslateClient.builder()
                .overrideConfiguration(config -> config.addExecutionInterceptor(capture)),
                "AKID", "secret", Region.US_EAST_1, endpoint)) {
            TranslateDocumentResponse response = translate.translateDocument(request -> request
                    .document(document -> document.content(SdkBytes.fromByteArray(PAYLOAD)).contentType("text/plain"))
                    .sourceLanguageCode("en").targetLanguageCode("es"));

            assertArrayEquals(PAYLOAD, response.translatedDocument().content().asByteArray());
            assertEquals("en", response.sourceLanguageCode());
            assertEquals("es", response.targetLanguageCode());
            SdkHttpRequest request = transmitted.get();
            assertNotNull(request, "The real SDK must transmit its marshalled request");
            assertEquals("/service/AWSShineFrontendService_20170701/operation/TranslateDocument",
                    request.getUri().getPath());
            assertEquals("rpc-v2-cbor", request.firstMatchingHeader("smithy-protocol").orElse(null));
            assertEquals("application/cbor", request.firstMatchingHeader("Content-Type").orElse(null));
        }
    }

    private KmsClient kmsClient() {
        return AwsTestClients.create(KmsClient.builder(), "AKID", "secret", Region.US_EAST_1, endpoint);
    }

    private Response cborCall(String service, String operation, Map<String, ?> request) throws Exception {
        return cborCall(service, operation, CBOR.writeValueAsBytes(request));
    }

    private Response cborCall(String service, String operation, byte[] request) {
        Response response = rawCborCall(service, operation, request);
        assertEquals(200, response.statusCode(), service + "." + operation + " must reach its service handler");
        return response;
    }

    private Response rawCborCall(String service, String operation, byte[] request) {
        return given().baseUri(endpoint.toString()).port(endpoint.getPort())
                .contentType("application/cbor").accept("application/cbor")
                .header("smithy-protocol", "rpc-v2-cbor").body(request)
                .post("/service/" + service + "/operation/" + operation);
    }

    private static byte[] byteString(byte[] body, String member) throws Exception {
        try (CBORParser parser = (CBORParser) CBOR.getFactory().createParser(body)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() == JsonToken.FIELD_NAME && member.equals(parser.currentName())) {
                    assertEquals(JsonToken.VALUE_EMBEDDED_OBJECT, parser.nextToken(), member + " must be binary");
                    int offset = (int) parser.currentTokenLocation().getByteOffset();
                    assertEquals(2, (body[offset] & 0xff) >>> 5, member + " must use CBOR major type 2");
                    return parser.getBinaryValue();
                }
            }
        }
        throw new AssertionError("Missing CBOR member: " + member);
    }

    private static int tagOf(byte[] body, String member) throws Exception {
        try (CBORParser parser = (CBORParser) CBOR.getFactory().createParser(body)) {
            while (parser.nextToken() != null) {
                if (member.equals(parser.currentName()) && parser.currentToken().isScalarValue()) {
                    return parser.getCurrentTag();
                }
            }
        }
        throw new AssertionError("Missing CBOR member: " + member);
    }
}
