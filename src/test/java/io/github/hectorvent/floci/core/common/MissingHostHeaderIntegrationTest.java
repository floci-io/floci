package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

/**
 * A Host-less request cannot be expressed with RestAssured or any SDK client, so these go over a
 * raw socket. HTTP/1.0 omitting Host is legal and used to answer 500; HTTP/1.1 omitting it is a
 * protocol violation that Vert.x rejects before the router, and still should. An absolute-form
 * target states its own authority and must keep routing by it, Host header or not.
 */
@QuarkusTest
class MissingHostHeaderIntegrationTest {

    @Test
    void http10WithoutHostHeaderServesHealth() throws Exception {
        String response = rawRequest("GET /_floci/health HTTP/1.0\r\n\r\n");

        assertThat(response, startsWith("HTTP/1.0 200 OK"));
        assertThat(response, containsString("\"edition\":\"community\""));
    }

    @Test
    void http10WithoutHostHeaderServesPathStyleS3() throws Exception {
        String response = rawRequest("GET / HTTP/1.0\r\n\r\n");

        assertThat(response, startsWith("HTTP/1.0 200 OK"));
        assertThat(response, containsString("ListAllMyBucketsResult"));
    }

    @Test
    void http10AbsoluteFormWithoutHostHeaderServesHealth() throws Exception {
        String response = rawRequest(
                "GET http://127.0.0.1:" + RestAssured.port + "/_floci/health HTTP/1.0\r\n\r\n");

        assertThat(response, startsWith("HTTP/1.0 200 OK"));
        assertThat(response, containsString("\"edition\":\"community\""));
    }

    @Test
    void http10AbsoluteFormWithoutHostHeaderRoutesByTheTargetAuthority() throws Exception {
        String bucket = "absolute-form-bucket";
        given().put("/" + bucket).then().statusCode(200);
        try {
            String response = rawRequest("GET http://" + bucket + ".s3.localhost:"
                    + RestAssured.port + "/ HTTP/1.0\r\n\r\n");

            assertThat(response, startsWith("HTTP/1.0 200 OK"));
            assertThat(response, containsString("<Name>" + bucket + "</Name>"));
        } finally {
            given().delete("/" + bucket);
        }
    }

    @Test
    void http11WithoutHostHeaderIsRejected() throws Exception {
        String response = rawRequest("GET /_floci/health HTTP/1.1\r\nConnection: close\r\n\r\n");

        assertThat(response, startsWith("HTTP/1.1 400 Bad Request"));
    }

    @Test
    void http11WithHostHeaderServesHealth() throws Exception {
        String response = rawRequest(
                "GET /_floci/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("\"edition\":\"community\""));
    }

    private String rawRequest(String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", RestAssured.port)) {
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = socket.getInputStream();
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                received.write(chunk, 0, read);
            }
            return received.toString(StandardCharsets.UTF_8);
        }
    }
}
