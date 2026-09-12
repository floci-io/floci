package io.github.hectorvent.floci.services.apigateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers {@code binaryMediaTypes} on the RestApi together with {@code contentHandling} on the
 * integration and its integration responses — AWS's mechanism for moving binary payloads through a
 * REST API.
 *
 * <p>{@code CONVERT_TO_TEXT} base64-encodes a binary payload so it can be handled as a string;
 * {@code CONVERT_TO_BINARY} decodes a base64 text payload back into bytes. Both are applied on the
 * way out of the stage they belong to — request-side for the integration, response-side for the
 * integration response.
 */
@QuarkusTest
class ApiGatewayBinaryContentHandlingTest {

    /** Deliberately not valid UTF-8, so any string round-trip corrupts it detectably. */
    private static final byte[] BINARY_PAYLOAD = {
            (byte) 0x89, 0x50, 0x4E, 0x47, (byte) 0xFF, (byte) 0xD8, 0x00, 0x01, (byte) 0xFE, 0x7F
    };

    private static HttpServer backend;
    private static int backendPort;

    private static final AtomicReference<byte[]> receivedBody = new AtomicReference<>();

    private final List<String> createdApis = new ArrayList<>();

    @BeforeAll
    static void startBackend() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", ApiGatewayBinaryContentHandlingTest::handle);
        backend.start();
        backendPort = backend.getAddress().getPort();
    }

    @AfterAll
    static void stopBackend() {
        if (backend != null) backend.stop(0);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        receivedBody.set(exchange.getRequestBody().readAllBytes());

        // ?echo=binary returns the raw binary payload; ?echo=base64 returns it base64-encoded.
        String query = exchange.getRequestURI().getQuery();
        byte[] response;
        String contentType;
        if (query != null && query.contains("echo=binary")) {
            response = BINARY_PAYLOAD;
            contentType = "image/jpeg";
        } else if (query != null && query.contains("echo=base64")) {
            response = Base64.getEncoder().encodeToString(BINARY_PAYLOAD)
                    .getBytes(StandardCharsets.UTF_8);
            contentType = "text/plain";
        } else {
            response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            contentType = "application/json";
        }
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    /**
     * Builds a deployed REST API with {@code image/jpeg} as a binary media type and a POST
     * /widget method backed by an HTTP (non-proxy) integration.
     */
    private String createApi(String name, String integrationExtras, String integrationResponseBody) {
        return createApi(name, "\"image/jpeg\"", integrationExtras, integrationResponseBody);
    }

    /** As above, but with an explicit {@code binaryMediaTypes} array body (JSON, without brackets). */
    private String createApi(String name, String binaryMediaTypes, String integrationExtras,
                             String integrationResponseBody) {
        String apiId = given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\",\"binaryMediaTypes\":[" + binaryMediaTypes + "]}")
                .when().post("/restapis")
                .then().statusCode(201).body("id", notNullValue())
                .extract().path("id");
        createdApis.add(apiId);

        String rootId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        String resourceId = given().contentType(ContentType.JSON)
                .body("{\"pathPart\":\"widget\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON).body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST")
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"type\":\"HTTP\",\"httpMethod\":\"POST\","
                        + "\"uri\":\"http://127.0.0.1:" + backendPort + "/widget\"" + integrationExtras + "}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST/integration")
                .then().statusCode(201);

        if (integrationResponseBody != null) {
            given().contentType(ContentType.JSON).body(integrationResponseBody)
                    .when().put("/restapis/" + apiId + "/resources/" + resourceId
                            + "/methods/POST/integration/responses/200")
                    .then().statusCode(201);
        }

        String deploymentId = given().contentType(ContentType.JSON).body("{}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201).extract().path("id");
        given().contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);

        return apiId;
    }

    @AfterEach
    void cleanup() {
        for (String apiId : createdApis) {
            given().when().delete("/restapis/" + apiId).then().statusCode(202);
        }
        createdApis.clear();
    }

    @Test
    void convertToTextBase64EncodesABinaryRequestForMappingTemplates() {
        receivedBody.set(null);
        // $input.body is the base64 text, so the backend receives it verbatim inside the template.
        String apiId = createApi("binary-convert-to-text",
                ",\"contentHandling\":\"CONVERT_TO_TEXT\","
                        + "\"requestTemplates\":{\"image/jpeg\":\"$input.body\"}", null);

        given().contentType("image/jpeg").body(BINARY_PAYLOAD)
                .when().post("/execute-api/" + apiId + "/test/widget")
                .then().statusCode(200);

        assertEquals(Base64.getEncoder().encodeToString(BINARY_PAYLOAD),
                new String(receivedBody.get(), StandardCharsets.UTF_8));
    }

    @Test
    void convertToBinaryDecodesABase64RequestBeforeSendingIt() {
        receivedBody.set(null);
        String apiId = createApi("binary-convert-to-binary",
                ",\"contentHandling\":\"CONVERT_TO_BINARY\","
                        + "\"requestTemplates\":{\"text/plain\":\"$input.body\"}", null);

        given().contentType("text/plain")
                .body(Base64.getEncoder().encodeToString(BINARY_PAYLOAD))
                .when().post("/execute-api/" + apiId + "/test/widget")
                .then().statusCode(200);

        assertArrayEquals(BINARY_PAYLOAD, receivedBody.get());
    }

    @Test
    void withoutContentHandlingABinaryRequestIsNotBase64Encoded() {
        receivedBody.set(null);
        String apiId = createApi("binary-passthrough",
                ",\"requestTemplates\":{\"image/jpeg\":\"$input.body\"}", null);

        given().contentType("image/jpeg").body(BINARY_PAYLOAD)
                .when().post("/execute-api/" + apiId + "/test/widget")
                .then().statusCode(200);

        // No conversion requested, so the payload is handled as text — decidedly not base64.
        assertEquals(false,
                Base64.getEncoder().encodeToString(BINARY_PAYLOAD)
                        .equals(new String(receivedBody.get(), StandardCharsets.UTF_8)));
    }

    /**
     * AWS documents the wildcard character for {@code binaryMediaTypes} generally — "You can use the
     * wildcard character (*) to cover multiple media types" — not only as the full catch-all entry.
     * So an {@code image/*} entry has to cover {@code image/png}, which is never named explicitly.
     */
    @Test
    void aSubtypeWildcardBinaryMediaTypeCoversAnySubtype() {
        receivedBody.set(null);
        String apiId = createApi("binary-subtype-wildcard", "\"image/*\"",
                ",\"contentHandling\":\"CONVERT_TO_TEXT\","
                        + "\"requestTemplates\":{\"image/png\":\"$input.body\"}", null);

        given().contentType("image/png").body(BINARY_PAYLOAD)
                .when().post("/execute-api/" + apiId + "/test/widget")
                .then().statusCode(200);

        // Treated as binary purely via the wildcard, so CONVERT_TO_TEXT base64-encoded it.
        assertEquals(Base64.getEncoder().encodeToString(BINARY_PAYLOAD),
                new String(receivedBody.get(), StandardCharsets.UTF_8));
    }

    /** The charset parameter must not defeat the match: {@code image/jpeg; charset=x} is still binary. */
    @Test
    void aCharsetParameterDoesNotDefeatTheBinaryMediaTypeMatch() {
        receivedBody.set(null);
        String apiId = createApi("binary-charset-param",
                ",\"contentHandling\":\"CONVERT_TO_TEXT\","
                        + "\"requestTemplates\":{\"image/jpeg\":\"$input.body\"}", null);

        given().contentType("image/jpeg; charset=utf-8").body(BINARY_PAYLOAD)
                .when().post("/execute-api/" + apiId + "/test/widget")
                .then().statusCode(200);

        assertEquals(Base64.getEncoder().encodeToString(BINARY_PAYLOAD),
                new String(receivedBody.get(), StandardCharsets.UTF_8));
    }

    /** A non-proxy HTTP integration forwards only mapped query params, so ?echo must be mapped. */
    private static final String ECHO_QUERY_MAPPING =
            ",\"requestParameters\":{\"integration.request.querystring.echo\":"
                    + "\"method.request.querystring.echo\"}";

    @Test
    void integrationResponseConvertToTextBase64EncodesABinaryBackendBody() {
        String apiId = createApi("binary-resp-to-text", ECHO_QUERY_MAPPING,
                "{\"selectionPattern\":\"\",\"contentHandling\":\"CONVERT_TO_TEXT\"}");

        byte[] received = given().contentType(ContentType.JSON).body("{}")
                .when().post("/execute-api/" + apiId + "/test/widget?echo=binary")
                .then().statusCode(200)
                .extract().asByteArray();

        assertEquals(Base64.getEncoder().encodeToString(BINARY_PAYLOAD),
                new String(received, StandardCharsets.UTF_8));
    }

    @Test
    void integrationResponseConvertToBinaryDecodesABase64BackendBody() {
        String apiId = createApi("binary-resp-to-binary", ECHO_QUERY_MAPPING,
                "{\"selectionPattern\":\"\",\"contentHandling\":\"CONVERT_TO_BINARY\"}");

        byte[] received = given().contentType(ContentType.JSON).body("{}")
                .when().post("/execute-api/" + apiId + "/test/widget?echo=base64")
                .then().statusCode(200)
                .extract().asByteArray();

        assertArrayEquals(BINARY_PAYLOAD, received);
    }
}
