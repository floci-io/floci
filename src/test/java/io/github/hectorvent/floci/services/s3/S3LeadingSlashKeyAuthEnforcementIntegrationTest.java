package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.testutil.S3RequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A key that starts with a slash ({@code /email.txt}) travels as a double slash on the wire
 * ({@code PUT /bucket//email.txt}, or {@code PUT //email.txt} virtual-hosted), and the client
 * signs that wire path verbatim: S3 never normalizes the canonical URI. JAX-RS collapses the
 * consecutive slashes in {@code UriInfo}, so the signature filters have to verify against the
 * path as it arrived, not the normalized one, or every such request is rejected with
 * {@code SignatureDoesNotMatch} under {@code enforce-auth}.
 *
 * <p>RestAssured normalizes double slashes too, so the requests here go through
 * {@link HttpClient} (path-style) or a raw socket (virtual-hosted, where the {@code Host}
 * header must be set by hand).
 */
@QuarkusTest
@TestProfile(S3AuthEnforcementIntegrationTest.S3AuthProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3LeadingSlashKeyAuthEnforcementIntegrationTest {

    private static final String BUCKET = "auth-leading-slash-bucket";
    private static final String KEY = "/email.txt";
    private static final String VHOST_KEY = "/vhost.txt";
    private static final S3RequestSigner LOCAL_SIGNER = S3RequestSigner.signedAs("test", "test");
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    @Test
    @Order(1)
    void createBucket() {
        given().filter(LOCAL_SIGNER).when().put("/" + BUCKET).then().statusCode(200);
    }

    @Test
    @Order(2)
    void headerSignedPathStyleRequestAcceptsLeadingSlashKey() throws Exception {
        URI uri = URI.create(base() + "/" + BUCKET + "/" + KEY);
        byte[] body = "slash body".getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> put = send("PUT", uri, body, LOCAL_SIGNER);
        assertEquals(200, put.statusCode(), put.body());

        HttpResponse<String> get = send("GET", uri, new byte[0], LOCAL_SIGNER);
        assertEquals(200, get.statusCode(), get.body());
        assertEquals("slash body", get.body());

        given()
            .filter(LOCAL_SIGNER)
        .when()
            .get("/" + BUCKET + "?list-type=2")
        .then()
            .statusCode(200)
            .body(containsString("<Key>" + KEY + "</Key>"));
    }

    @Test
    @Order(3)
    void headerSignedRequestSignedForNormalizedPathIsStillRejected() throws Exception {
        // A signature over "/bucket/email.txt" must not verify a request for "/bucket//email.txt":
        // the two are different objects, and S3 signs the wire path verbatim.
        URI signedUri = URI.create(base() + "/" + BUCKET + "/email.txt");
        URI sentUri = URI.create(base() + "/" + BUCKET + "/" + KEY);
        byte[] body = "forged".getBytes(StandardCharsets.UTF_8);

        HttpRequest.Builder request = HttpRequest.newBuilder(sentUri)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        LOCAL_SIGNER.headersFor("PUT", signedUri, body).forEach(request::header);
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request.build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(403, response.statusCode(), response.body());
        assertThat(response.body(), containsString("SignatureDoesNotMatch"));
    }

    @Test
    @Order(4)
    void headerSignedVirtualHostedRequestAcceptsLeadingSlashKey() throws Exception {
        String authority = BUCKET + ".localhost:" + RestAssured.port;
        URI uri = URI.create("http://" + authority + "/" + VHOST_KEY);
        byte[] body = "vhost body".getBytes(StandardCharsets.UTF_8);

        RawResponse put = sendRaw("PUT", uri, body, LOCAL_SIGNER.headersFor("PUT", uri, body));
        assertEquals(200, put.status(), put.body());

        RawResponse get = sendRaw("GET", uri, new byte[0], LOCAL_SIGNER.headersFor("GET", uri, new byte[0]));
        assertEquals(200, get.status(), get.body());
        assertEquals("vhost body", get.body());

        // The key keeps its leading slash: "//vhost.txt" on the wire is "/vhost.txt", not "vhost.txt".
        given()
            .filter(LOCAL_SIGNER)
        .when()
            .get("/" + BUCKET + "?list-type=2")
        .then()
            .statusCode(200)
            .body(containsString("<Key>" + VHOST_KEY + "</Key>"));
    }

    @Test
    @Order(5)
    void presignedPathStyleRequestAcceptsLeadingSlashKey() throws Exception {
        String path = "/" + BUCKET + "/" + KEY;
        String timestamp = AMZ_DATE.format(Instant.now());
        String query = presignedQuery("GET", path, "localhost:" + RestAssured.port, timestamp);
        URI uri = URI.create(base() + path + "?" + query);

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("slash body", response.body());
    }

    @Test
    @Order(6)
    void presignedVirtualHostedRequestAcceptsLeadingSlashKey() throws Exception {
        String authority = BUCKET + ".localhost:" + RestAssured.port;
        String path = "/" + VHOST_KEY;
        String timestamp = AMZ_DATE.format(Instant.now());
        String query = presignedQuery("GET", path, authority, timestamp);
        URI uri = URI.create("http://" + authority + path + "?" + query);

        RawResponse response = sendRaw("GET", uri, new byte[0], Map.of());

        assertEquals(200, response.status(), response.body());
        assertEquals("vhost body", response.body());
    }

    @Test
    @Order(7)
    void cleanup() throws Exception {
        HttpResponse<String> delete = send("DELETE", URI.create(base() + "/" + BUCKET + "/" + KEY),
                new byte[0], LOCAL_SIGNER);
        assertEquals(204, delete.statusCode(), delete.body());
        HttpResponse<String> deleteVhost = send("DELETE", URI.create(base() + "/" + BUCKET + "/" + VHOST_KEY),
                new byte[0], LOCAL_SIGNER);
        assertEquals(204, deleteVhost.statusCode(), deleteVhost.body());
        given().filter(LOCAL_SIGNER).when().delete("/" + BUCKET).then().statusCode(204);
    }

    private static String base() {
        return "http://localhost:" + RestAssured.port;
    }

    private static HttpResponse<String> send(String method, URI uri, byte[] body, S3RequestSigner signer)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        signer.headersFor(method, uri, body).forEach(request::header);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Sends the request over a plain socket so the {@code Host} header can name the bucket
     * (HttpClient refuses to set it) and the double slash reaches the server untouched.
     */
    private static RawResponse sendRaw(String method, URI uri, byte[] body, Map<String, String> headers)
            throws Exception {
        String target = uri.getRawPath() + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
        StringBuilder head = new StringBuilder()
                .append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                .append("Host: ").append(uri.getHost()).append(':').append(uri.getPort()).append("\r\n")
                .append("Connection: close\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n");
        headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
        head.append("\r\n");

        try (Socket socket = new Socket("localhost", RestAssured.port)) {
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            in.transferTo(buffer);
            String raw = buffer.toString(StandardCharsets.UTF_8);
            int status = Integer.parseInt(raw.substring(9, 12));
            int bodyStart = raw.indexOf("\r\n\r\n");
            return new RawResponse(status, bodyStart >= 0 ? raw.substring(bodyStart + 4) : "");
        }
    }

    private record RawResponse(int status, String body) {
    }

    private static String presignedQuery(String method, String path, String authority, String timestamp)
            throws Exception {
        String date = timestamp.substring(0, 8);
        String credentialScope = date + "/us-east-1/s3/aws4_request";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        params.put("X-Amz-Credential", URLEncoder.encode("test/" + credentialScope, StandardCharsets.UTF_8));
        params.put("X-Amz-Date", timestamp);
        params.put("X-Amz-Expires", "3600");
        params.put("X-Amz-SignedHeaders", "host");
        String canonicalQuery = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "&" + b).orElseThrow();

        String canonicalRequest = method + "\n"
                + path + "\n"
                + canonicalQuery + "\n"
                + "host:" + authority + "\n\n"
                + "host\n"
                + "UNSIGNED-PAYLOAD";
        String stringToSign = "AWS4-HMAC-SHA256\n"
                + timestamp + "\n"
                + credentialScope + "\n"
                + hex(MessageDigest.getInstance("SHA-256").digest(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        byte[] kDate = hmac(("AWS4test").getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion = hmac(kDate, "us-east-1");
        byte[] kService = hmac(kRegion, "s3");
        byte[] signingKey = hmac(kService, "aws4_request");
        return canonicalQuery + "&X-Amz-Signature=" + hex(hmac(signingKey, stringToSign));
    }

    private static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
