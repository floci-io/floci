package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug condition exploration tests for leading-slash key collision.
 *
 * These tests verify that S3 object keys with leading slashes (e.g., /file.txt)
 * are treated as distinct from keys without leading slashes (e.g., file.txt).
 *
 * The bug is that JAX-RS normalizes // to / in the URL path, so
 * PUT /bucket//file.txt stores under "file.txt" instead of "/file.txt".
 *
 * We use java.net.http.HttpClient for requests with leading-slash keys
 * because RestAssured normalizes double slashes in URL paths.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3LeadingSlashKeyIntegrationTest {

    private static final String BUCKET = "test-slash-bucket";

    @TestHTTPResource("/")
    URI baseUri;

    // --- Setup ---

    @Test
    @Order(0)
    void createBucket() {
        given()
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);
    }

    // --- Test 1: Distinct Objects ---
    // PUT "content-A" to /test-slash-bucket/file.txt and "content-B" to /test-slash-bucket//file.txt
    // then GET each and assert they return different content.
    // Bug: both map to "file.txt", so content-B overwrites content-A.

    @Test
    @Order(1)
    void distinctObjects_leadingSlashKeyIsSeparateFromNormalKey() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String base = baseUri.toString().replaceAll("/$", "");

        // PUT content-A to normal key: /test-slash-bucket/file.txt
        HttpRequest putNormal = HttpRequest.newBuilder()
                .uri(URI.create(base + "/" + BUCKET + "/file.txt"))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("content-A"))
                .build();
        HttpResponse<String> putNormalResp = client.send(putNormal, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, putNormalResp.statusCode(), "PUT normal key should succeed");

        // PUT content-B to leading-slash key: /test-slash-bucket//file.txt (key = /file.txt)
        HttpRequest putSlash = HttpRequest.newBuilder()
                .uri(URI.create(base + "/" + BUCKET + "//file.txt"))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("content-B"))
                .build();
        HttpResponse<String> putSlashResp = client.send(putSlash, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, putSlashResp.statusCode(), "PUT leading-slash key should succeed");

        // GET normal key
        HttpRequest getNormal = HttpRequest.newBuilder()
                .uri(URI.create(base + "/" + BUCKET + "/file.txt"))
                .GET().build();
        HttpResponse<String> getNormalResp = client.send(getNormal, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, getNormalResp.statusCode());
        String normalContent = getNormalResp.body();

        // GET leading-slash key
        HttpRequest getSlash = HttpRequest.newBuilder()
                .uri(URI.create(base + "/" + BUCKET + "//file.txt"))
                .GET().build();
        HttpResponse<String> getSlashResp = client.send(getSlash, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, getSlashResp.statusCode());
        String slashContent = getSlashResp.body();

        // They must be different — /file.txt and file.txt are distinct S3 keys
        assertNotEquals(normalContent, slashContent,
                "Keys 'file.txt' and '/file.txt' should store distinct objects, " +
                "but both returned: " + normalContent);
    }

    // --- Test 2: PUT/GET Round-Trip ---
    // PUT content to /test-slash-bucket//leading.txt, GET it back, assert correct content.
    // Bug: PUT stores under "leading.txt", GET retrieves "leading.txt" — round-trip works
    // but the key is wrong (leading slash stripped).

    @Test
    @Order(2)
    void putGetRoundTrip_leadingSlashKeyPreservesContent() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String base = baseUri.toString().replaceAll("/$", "");
        String content = "leading-slash-content-12345";

        // PUT to leading-slash key: /test-slash-bucket//leading.txt
        HttpRequest put = HttpRequest.newBuilder()
                .uri(URI.create(base + "/" + BUCKET + "//leading.txt"))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString(content))
                .build();
        HttpResponse<String> putResp = client.send(put, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, putResp.statusCode(), "PUT leading-slash key should succeed");

        // GET the leading-slash key
        HttpRequest get = HttpRequest.newBuilder()
                .uri(URI.create(base + "/" + BUCKET + "//leading.txt"))
                .GET().build();
        HttpResponse<String> getResp = client.send(get, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, getResp.statusCode(), "GET leading-slash key should return 200");
        assertEquals(content, getResp.body(),
                "GET should return the exact content PUT to the leading-slash key");

        // Also verify the normal key "leading.txt" does NOT have this content
        // (it shouldn't exist unless the bug causes collision)
        HttpRequest getNormal = HttpRequest.newBuilder()
                .uri(URI.create(base + "/" + BUCKET + "/leading.txt"))
                .GET().build();
        HttpResponse<String> getNormalResp = client.send(getNormal, HttpResponse.BodyHandlers.ofString());

        // If the bug exists, this will be 200 with the same content (collision).
        // If fixed, this should be 404 (no object at "leading.txt").
        assertNotEquals(200, getNormalResp.statusCode(),
                "Normal key 'leading.txt' should NOT exist — only '/leading.txt' was uploaded. " +
                "Bug: leading slash was stripped, storing under 'leading.txt' instead of '/leading.txt'");
    }

    // --- Test 3: HEAD Leading Slash ---
    // PUT to /test-slash-bucket//meta.txt, HEAD /test-slash-bucket//meta.txt,
    // assert correct Content-Length and Content-Type.

    @Test
    @Order(3)
    void headLeadingSlashKey_returnsCorrectMetadata() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String base = baseUri.toString().replaceAll("/$", "");
        String content = "metadata-test-content";

        // PUT to leading-slash key
        HttpRequest put = HttpRequest.newBuilder()
                .uri(URI.create(base + "/" + BUCKET + "//meta.txt"))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString(content))
                .build();
        HttpResponse<String> putResp = client.send(put, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, putResp.statusCode(), "PUT leading-slash key should succeed");

        // HEAD the leading-slash key
        HttpRequest head = HttpRequest.newBuilder()
                .uri(URI.create(base + "/" + BUCKET + "//meta.txt"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> headResp = client.send(head, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, headResp.statusCode(), "HEAD leading-slash key should return 200");

        String contentLength = headResp.headers().firstValue("content-length").orElse(null);
        assertNotNull(contentLength, "HEAD should return Content-Length");
        assertEquals(String.valueOf(content.length()), contentLength,
                "Content-Length should match the PUT body size");

        String contentType = headResp.headers().firstValue("content-type").orElse(null);
        assertNotNull(contentType, "HEAD should return Content-Type");
        assertTrue(contentType.contains("text/plain"),
                "Content-Type should be text/plain but was: " + contentType);
    }

    // --- Test 4: DELETE Isolation ---
    // PUT to both /test-slash-bucket/data.txt and /test-slash-bucket//data.txt,
    // DELETE /test-slash-bucket//data.txt, GET /test-slash-bucket/data.txt should still succeed.
    // Bug: DELETE //data.txt actually deletes "data.txt" (the normal key).

    @Test
    @Order(4)
    void deleteIsolation_deletingLeadingSlashKeyDoesNotAffectNormalKey() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String base = baseUri.toString().replaceAll("/$", "");

        // PUT to normal key: data.txt
        HttpRequest putNormal = HttpRequest.newBuilder()
                .uri(URI.create(base + "/" + BUCKET + "/data.txt"))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("normal-data"))
                .build();
        client.send(putNormal, HttpResponse.BodyHandlers.ofString());

        // PUT to leading-slash key: /data.txt
        HttpRequest putSlash = HttpRequest.newBuilder()
                .uri(URI.create(base + "/" + BUCKET + "//data.txt"))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("slash-data"))
                .build();
        client.send(putSlash, HttpResponse.BodyHandlers.ofString());

        // DELETE the leading-slash key: /data.txt
        HttpRequest deleteSlash = HttpRequest.newBuilder()
                .uri(URI.create(base + "/" + BUCKET + "//data.txt"))
                .DELETE().build();
        HttpResponse<String> deleteResp = client.send(deleteSlash, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, deleteResp.statusCode(), "DELETE leading-slash key should return 204");

        // GET the normal key — it should still exist
        HttpRequest getNormal = HttpRequest.newBuilder()
                .uri(URI.create(base + "/" + BUCKET + "/data.txt"))
                .GET().build();
        HttpResponse<String> getNormalResp = client.send(getNormal, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, getNormalResp.statusCode(),
                "Normal key 'data.txt' should still exist after deleting '/data.txt'. " +
                "Bug: DELETE //data.txt deleted 'data.txt' instead of '/data.txt'");
        assertEquals("normal-data", getNormalResp.body(),
                "Normal key content should be unchanged");
    }

    // --- Test 5: List Shows Both ---
    // PUT to both file.txt and /file.txt in same bucket, list objects,
    // assert both keys appear as separate entries.
    // Bug: both map to "file.txt", so list shows only one entry.

    @Test
    @Order(5)
    void listShowsBoth_leadingSlashAndNormalKeyAppearSeparately() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String base = baseUri.toString().replaceAll("/$", "");

        // Use a dedicated bucket to avoid interference from other tests
        String listBucket = "test-slash-list-bucket";
        given().when().put("/" + listBucket).then().statusCode(200);

        // PUT to normal key: file.txt
        HttpRequest putNormal = HttpRequest.newBuilder()
                .uri(URI.create(base + "/" + listBucket + "/file.txt"))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("normal"))
                .build();
        client.send(putNormal, HttpResponse.BodyHandlers.ofString());

        // PUT to leading-slash key: /file.txt
        HttpRequest putSlash = HttpRequest.newBuilder()
                .uri(URI.create(base + "/" + listBucket + "//file.txt"))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("slash"))
                .build();
        client.send(putSlash, HttpResponse.BodyHandlers.ofString());

        // List objects in the bucket
        HttpRequest list = HttpRequest.newBuilder()
                .uri(URI.create(base + "/" + listBucket))
                .GET().build();
        HttpResponse<String> listResp = client.send(list, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, listResp.statusCode());

        String body = listResp.body();

        // Count how many <Key> entries appear
        int keyCount = 0;
        int idx = 0;
        while ((idx = body.indexOf("<Key>", idx)) != -1) {
            keyCount++;
            idx += 5;
        }

        // Should have at least 2 entries: "file.txt" and "/file.txt"
        assertTrue(keyCount >= 2,
                "List should show at least 2 objects (file.txt and /file.txt) but found " +
                keyCount + " <Key> entries. Bug: both keys collapsed into one. Response: " + body);
    }
}
