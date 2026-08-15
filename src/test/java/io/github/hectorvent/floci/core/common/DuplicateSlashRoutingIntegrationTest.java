package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Router-level regression lock: request paths with duplicate slashes must match
 * the same routes as their single-slash form.
 * <p>
 * AWS SDKs resolving an operation path against an endpoint that carries a
 * trailing slash produce doubled-slash request paths ({@code POST //guardrails},
 * {@code POST //v2/domainnames}, {@code POST //authorizer/...}); real AWS
 * accepts them. Floci gets this from the JAX-RS layer itself: RESTEasy Reactive
 * collapses consecutive slashes during route matching, so no floci-side
 * normalization filter exists or is needed. These tests pin that platform
 * behavior so a Quarkus upgrade that drops it fails loudly instead of 404ing
 * every trailing-slash-endpoint SDK call.
 * <p>
 * S3 is the deliberate exception: object keys legitimately contain consecutive
 * slashes, and S3Controller re-derives keys from the raw request URI rather
 * than the matched path parameters, so matching-time normalization never
 * corrupts them (see also {@code S3LeadingSlashKeyIntegrationTest}).
 * <p>
 * Uses java.net.http.HttpClient because RestAssured normalizes double slashes
 * in URL paths before sending.
 */
@QuarkusTest
class DuplicateSlashRoutingIntegrationTest {

    @io.quarkus.test.common.http.TestHTTPResource("/")
    URI baseUri;

    private final HttpClient client = HttpClient.newHttpClient();

    private String base() {
        return baseUri.toString().replaceAll("/$", "");
    }

    private static String authorization(String service) {
        return "AWS4-HMAC-SHA256 Credential=test/20260815/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host;x-amz-date, Signature=deadbeef";
    }

    private HttpResponse<String> send(String method, String path, String service, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .method(method, body != null
                        ? HttpRequest.BodyPublishers.ofString(body)
                        : HttpRequest.BodyPublishers.noBody());
        if (service != null) {
            builder.header("Authorization", authorization(service));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void doubledLeadingSlashMatchesRestJsonRoute() throws Exception {
        // IoT CreateThingType lives at POST /thing-types/{name}; the doubled-slash
        // form must reach the same route instead of falling through to S3 wildcards.
        HttpResponse<String> response =
                send("POST", "//thing-types/slash-norm-type", "iot", "{}");

        assertEquals(200, response.statusCode(), () -> "body: " + response.body());
        assertTrue(response.body().contains("slash-norm-type"),
                () -> "expected thing type name in body: " + response.body());
    }

    @Test
    void tripleSlashCollapsesToo() throws Exception {
        HttpResponse<String> response =
                send("GET", "///thing-types", "iot", null);

        assertEquals(200, response.statusCode(), () -> "body: " + response.body());
        assertTrue(response.body().contains("thingTypes"),
                () -> "expected thingTypes listing: " + response.body());
    }

    @Test
    void internalDoubledSlashMatchesNestedRoute() throws Exception {
        // Backup ListBackupPlans lives at GET /backup/plans/; double the internal
        // separator to prove tolerance is not limited to the leading segment.
        HttpResponse<String> response =
                send("GET", "/backup//plans/", "backup", null);

        assertEquals(200, response.statusCode(), () -> "body: " + response.body());
        assertTrue(response.body().contains("BackupPlansList"),
                () -> "expected BackupPlansList in body: " + response.body());
    }

    @Test
    void doubledSlashApiGatewayV2RouteMatches() throws Exception {
        // API Gateway v2 CreateApi is the exact shape from the failing cohort
        // (POST //v2/... with an apigateway credential scope).
        HttpResponse<String> response = send("POST", "//v2/apis", "apigateway",
                "{\"name\": \"slash-norm-api\", \"protocolType\": \"HTTP\"}");

        assertEquals(201, response.statusCode(), () -> "body: " + response.body());
        assertTrue(response.body().contains("slash-norm-api"),
                () -> "expected api name in body: " + response.body());
    }

    @Test
    void s3DoubledSlashKeysStayDistinct() throws Exception {
        // Signed-for-s3 requests keep raw key semantics: /bucket//k.txt names the
        // key "/k.txt", distinct from "k.txt". Matching-time slash collapsing must
        // never leak into S3 key extraction.
        String bucket = "slash-norm-exempt-bucket";
        assertEquals(200, send("PUT", "/" + bucket, "s3", "").statusCode());

        assertEquals(200, send("PUT", "/" + bucket + "/k.txt", "s3", "plain").statusCode());
        assertEquals(200, send("PUT", "/" + bucket + "//k.txt", "s3", "leading-slash").statusCode());

        HttpResponse<String> normal = send("GET", "/" + bucket + "/k.txt", "s3", null);
        HttpResponse<String> slashed = send("GET", "/" + bucket + "//k.txt", "s3", null);
        assertEquals("plain", normal.body());
        assertEquals("leading-slash", slashed.body());
    }
}
