package io.github.hectorvent.floci.services.apigatewayv2.websocket;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * WebSocket $connect over the AWS-native execute-api subdomain host
 * ({@code {apiId}.execute-api.{region}.host/{stage}}).
 *
 * <p>Unlike the {@code /ws/{apiId}/{stage}} path form, the subdomain form carries the region in the
 * host, so a $connect upgrade — which has no SigV4 Authorization header — can still resolve the API's
 * region. This lets an API created in a non-default region complete the handshake.
 */
@QuarkusTest
class WebSocketSubdomainConnectTest {

    @TestHTTPResource("/")
    URL baseUrl;

    // ──────────────────────────── Host parser (unit) ────────────────────────────

    @Test
    void parsesApiIdAndRegionFromSubdomainHost() {
        WebSocketHandler.ExecuteApiHost h =
                WebSocketHandler.parseExecuteApiHost("abc123.execute-api.ap-northeast-2.localhost:4566");
        assertNotNull(h);
        assertEquals("abc123", h.apiId());
        assertEquals("ap-northeast-2", h.region());
    }

    @Test
    void regionIsNullWhenHostOmitsIt() {
        WebSocketHandler.ExecuteApiHost h =
                WebSocketHandler.parseExecuteApiHost("abc123.execute-api.localhost:4566");
        assertNotNull(h);
        assertEquals("abc123", h.apiId());
        assertNull(h.region());
    }

    @Test
    void returnsNullForNonExecuteApiHosts() {
        assertNull(WebSocketHandler.parseExecuteApiHost("my-bucket.localhost:4566"));
        assertNull(WebSocketHandler.parseExecuteApiHost("localhost:4566"));
        assertNull(WebSocketHandler.parseExecuteApiHost(null));
    }

    // ──────────────────────── Subdomain $connect (integration) ────────────────────────

    @Test
    void subdomainUpgradeSucceedsForNonDefaultRegionApi() throws Exception {
        // Default region is us-east-1; create the API in a different region.
        String region = "ap-northeast-2";
        String apiId = createWebSocketApiWithMockConnect(region);

        int status = upgradeStatusViaSubdomain(apiId, region, "prod");

        assertEquals(101, status,
                "Subdomain WebSocket upgrade should complete (101) for an API in a non-default region");
    }

    // ──────────────────────────── helpers ────────────────────────────

    /** Creates a WEBSOCKET API with a MOCK $connect route in the given region and returns its id. */
    private String createWebSocketApiWithMockConnect(String region) {
        // The region is derived from the SigV4 credential scope in the Authorization header.
        String auth = "AWS4-HMAC-SHA256 Credential=test/20260714/" + region
                + "/apigateway/aws4_request, SignedHeaders=host, Signature=deadbeef";

        String apiId = given().header("Authorization", auth).contentType(ContentType.JSON)
                .body("{\"name\":\"ws-subdomain-test\",\"protocolType\":\"WEBSOCKET\","
                        + "\"routeSelectionExpression\":\"$request.body.action\"}")
                .when().post("/v2/apis")
                .then().statusCode(201).body("apiId", notNullValue())
                .extract().path("apiId");

        String integrationId = given().header("Authorization", auth).contentType(ContentType.JSON)
                .body("{\"integrationType\":\"MOCK\"}")
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then().statusCode(201)
                .extract().path("integrationId");

        given().header("Authorization", auth).contentType(ContentType.JSON)
                .body("{\"routeKey\":\"$connect\",\"target\":\"integrations/" + integrationId + "\"}")
                .when().post("/v2/apis/" + apiId + "/routes")
                .then().statusCode(201);

        given().header("Authorization", auth).contentType(ContentType.JSON)
                .body("{\"stageName\":\"prod\",\"autoDeploy\":true}")
                .when().post("/v2/apis/" + apiId + "/stages")
                .then().statusCode(201);

        return apiId;
    }

    /**
     * Sends a raw WebSocket upgrade with an execute-api subdomain Host header and returns the HTTP
     * status code. A raw socket is used because {@code java.net.http} forbids setting the Host header.
     */
    private int upgradeStatusViaSubdomain(String apiId, String region, String stage) throws Exception {
        String host = apiId + ".execute-api." + region + ".localhost:" + baseUrl.getPort();
        String request = "GET /" + stage + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Connection: Upgrade\r\n"
                + "Upgrade: websocket\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "\r\n";

        try (Socket socket = new Socket(baseUrl.getHost(), baseUrl.getPort())) {
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String statusLine = in.readLine(); // e.g. "HTTP/1.1 101 Switching Protocols"
            assertNotNull(statusLine, "Expected an HTTP status line from the upgrade response");
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }
}
