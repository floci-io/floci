package io.floci.aws.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Uploads sent with {@code Expect: 100-continue} over a raw connection, since an HTTP client library
 * hides whether the connection was kept or closed.
 */
@QuarkusTest
class S3ExpectContinueIntegrationTest {

    @Test
    void putObject_rejectedBeforeItsBodyIsSent_closesTheConnection() throws IOException {
        String bucket = createBucket("expect-too-large");

        try (Socket socket = connect()) {
            send(socket, "PUT /" + bucket + "/big.bin HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Content-Encoding: aws-chunked\r\n"
                    + "x-amz-content-sha256: STREAMING-UNSIGNED-PAYLOAD-TRAILER\r\n"
                    + "x-amz-decoded-content-length: 5368709121\r\n"
                    + "Content-Length: 100\r\n"
                    + "Expect: 100-continue\r\n"
                    + "\r\n");

            ResponseHead head = readHead(socket.getInputStream());
            assertEquals(400, head.status());
            assertEquals("close", head.header("connection"));
            // Reading to the end returns only once Floci closes the connection.
            assertThat(readToEnd(socket), containsString("<Code>EntityTooLarge</Code>"));
        }
    }

    @Test
    void putObject_toAMissingBucketBeforeItsBodyIsSent_closesTheConnection() throws IOException {
        try (Socket socket = connect()) {
            send(socket, "PUT /missing-" + UUID.randomUUID() + "/object.txt HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Content-Length: 5\r\n"
                    + "Expect: 100-continue\r\n"
                    + "\r\n");

            ResponseHead head = readHead(socket.getInputStream());
            assertEquals(404, head.status());
            assertEquals("close", head.header("connection"));
            assertThat(readToEnd(socket), containsString("<Code>NoSuchBucket</Code>"));
        }
    }

    @Test
    void putObject_whoseBodyIsReadAfterContinue_keepsTheConnection() throws IOException {
        String bucket = createBucket("expect-kept");

        try (Socket socket = connect()) {
            InputStream in = socket.getInputStream();
            send(socket, "PUT /" + bucket + "/object.txt HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Content-Length: 5\r\n"
                    + "Expect: 100-continue\r\n"
                    + "\r\n");
            assertEquals(100, readHead(in).status());
            send(socket, "hello");
            ResponseHead stored = readHead(in);
            assertEquals(200, stored.status());
            assertNull(stored.header("connection"));
            readBody(in, stored);

            send(socket, "GET /" + bucket + "/object.txt HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "\r\n");
            ResponseHead read = readHead(in);
            assertEquals(200, read.status());
            assertEquals("hello", readBody(in, read));
        }
    }

    private static Socket connect() throws IOException {
        Socket socket = new Socket("localhost", RestAssured.port);
        // A connection Floci leaves open fails the read instead of leaving it waiting.
        socket.setSoTimeout(10_000);
        return socket;
    }

    private static void send(Socket socket, String text) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static ResponseHead readHead(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int next = in.read();
            if (next < 0) {
                throw new IOException("connection closed before the response head ended");
            }
            bytes.write(next);
            matched = next == "\r\n\r\n".charAt(matched) ? matched + 1 : (next == '\r' ? 1 : 0);
        }
        String[] lines = bytes.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        return new ResponseHead(Integer.parseInt(lines[0].split(" ")[1]), headers);
    }

    private static String readBody(InputStream in, ResponseHead head) throws IOException {
        int length = Integer.parseInt(head.header("content-length"));
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static String readToEnd(Socket socket) throws IOException {
        return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String createBucket(String prefix) {
        String bucket = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        given().when().put("/" + bucket).then().statusCode(200);
        return bucket;
    }

    private record ResponseHead(int status, Map<String, String> headers) {

        String header(String name) {
            return headers.get(name);
        }
    }
}
