package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RequestLogFilter.Body;
import io.floci.aws.testing.ParallelLocks;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link RequestLogFilter}'s helpers: how bodies are stored, redaction of headers, query parameters and URLs, and
 * {@link RequestLogWriter}'s handling of the log file.
 */
@ResourceLock(ParallelLocks.PORTS)
class RequestLogFilterTest {

    private final RequestLogFilter filter = new RequestLogFilter(new ObjectMapper(), () -> null);

    // ---- bodies ----

    @Test
    void binaryBodyIsBase64WithOriginalSize() {
        byte[] cbor = new byte[] {(byte) 0xA1, 0x63, 0x66, 0x6F, 0x6F, (byte) 0xF6, 0x00, (byte) 0xFF};
        Body body = Body.bytes(cbor);

        assertThat(body.encoding(), equalTo("base64"));
        assertThat(body.originalSize(), equalTo(cbor.length));
        // The stored text decodes back to the original bytes, lossless unlike a UTF-8 decode.
        assertThat(Base64.getDecoder().decode(body.text()), equalTo(cbor));
    }

    @Test
    void largeBinaryBodyReportsOriginalSizeButCapsStoredBytes() {
        byte[] big = new byte[RequestLogFilter.MAX_BODY_BYTES + 4096];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i % 256);
        }
        Body body = Body.bytes(big);

        assertThat(body.encoding(), equalTo("base64"));
        assertThat(body.originalSize(), equalTo(big.length));
        assertThat(Base64.getDecoder().decode(body.text()).length, is(RequestLogFilter.MAX_BODY_BYTES));
    }

    @Test
    void bytesThatAreValidUtf8AreStoredAsText() {
        Body body = Body.bytes("Action=ListQueues&Version=2012-11-05".getBytes(StandardCharsets.UTF_8));

        assertThat(body.encoding(), is(nullValue()));
        assertThat(body.text(), equalTo("Action=ListQueues&Version=2012-11-05"));
    }

    @Test
    void textBodyHasNoEncodingAndUtf8Size() {
        Body body = Body.text("<Response>ok</Response>");
        assertThat(body.encoding(), is(nullValue()));
        assertThat(body.originalSize(),
                equalTo("<Response>ok</Response>".getBytes(StandardCharsets.UTF_8).length));
    }

    @Test
    void aTextBodyOverTheCapKeepsItsFullSize() {
        String large = "x".repeat(RequestLogFilter.MAX_BODY_BYTES + 100);
        Body body = filter.responseBody(large);

        assertThat(body.text().length(), equalTo(RequestLogFilter.MAX_BODY_BYTES));
        assertThat(body.originalSize(), equalTo(large.length()));
    }

    @Test
    void aSerializedBodyStopsAtTheCapWithAnUnknownSize() {
        Map<String, String> large = Map.of("value", "x".repeat(RequestLogFilter.MAX_BODY_BYTES * 2));
        Body body = filter.responseBody(large);

        assertThat(body.text().length(), lessThanOrEqualTo(RequestLogFilter.MAX_BODY_BYTES));
        assertThat(body.originalSize(), equalTo(-1));
    }

    @Test
    void aSmallSerializedBodyKeepsItsSize() {
        Body body = filter.responseBody(Map.of("QueueUrls", List.of()));

        assertThat(body.text(), equalTo("{\"QueueUrls\":[]}"));
        assertThat(body.originalSize(), equalTo(body.text().length()));
    }

    @Test
    void aStreamedBodyIsNeverReadAndItsSizeIsUnknown() {
        ByteArrayInputStream stream = new ByteArrayInputStream("object bytes".getBytes(StandardCharsets.UTF_8));
        Body body = filter.responseBody(stream);

        assertThat(body.text(), equalTo(RequestLogFilter.STREAMED));
        assertThat(body.originalSize(), equalTo(-1));
        assertThat(stream.available(), equalTo(12));
    }

    // ---- redaction ----

    @Test
    void sessionCookiesAndSignaturesAreRedactedFromHeaders() {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.add("Cookie", "floci-quicksight=session-secret");
        headers.add("Set-Cookie", "floci-quicksight=new-secret; Path=/");
        headers.add("authorization", "AWS4-HMAC-SHA256 Credential=AKID/x, Signature=abc");
        headers.add("Content-Type", "application/json");

        List<Map<String, String>> logged = RequestLogFilter.headerList(headers);

        assertThat(value(logged, "Cookie"), equalTo(RequestLogFilter.REDACTED));
        assertThat(value(logged, "Set-Cookie"), equalTo(RequestLogFilter.REDACTED));
        assertThat(value(logged, "authorization"), equalTo(RequestLogFilter.REDACTED));
        assertThat(value(logged, "Content-Type"), equalTo("application/json"));
    }

    @Test
    void presignedSecretsAreRedactedFromTheQueryStringWhateverTheirCase() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("x-amz-signature", "deadbeef");
        params.add("X-Amz-Expires", "300");

        List<Map<String, String>> logged = RequestLogFilter.queryStringList(params);

        assertThat(value(logged, "x-amz-signature"), equalTo(RequestLogFilter.REDACTED));
        assertThat(value(logged, "X-Amz-Expires"), equalTo("300"));
    }

    @Test
    void presignedSecretsAreRedactedInUrl() {
        String url = "http://localhost:4566/bucket/key"
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=AKID%2F20260928%2Fus-east-1%2Fs3%2Faws4_request"
                + "&X-Amz-Signature=deadbeefcafe"
                + "&X-Amz-Security-Token=SESSIONTOKEN"
                + "&partNumber=2";
        String redacted = RequestLogFilter.redactUrl(url);

        assertThat(redacted, not(containsString("deadbeefcafe")));
        assertThat(redacted, not(containsString("SESSIONTOKEN")));
        assertThat(redacted, not(containsString("AKID")));
        assertThat(redacted, containsString("X-Amz-Algorithm=AWS4-HMAC-SHA256"));
        assertThat(redacted, containsString("partNumber=2"));
    }

    @Test
    void aPercentEncodedSecretNameIsRedactedInUrlToo() {
        String url = "http://localhost:4566/bucket/key?%58-Amz-Signature=deadbeefcafe&x-amz-signature=cafe#frag";
        String redacted = RequestLogFilter.redactUrl(url);

        assertThat(redacted, equalTo("http://localhost:4566/bucket/key?%58-Amz-Signature=" + RequestLogFilter.REDACTED
                + "&x-amz-signature=" + RequestLogFilter.REDACTED + "#frag"));
    }

    @Test
    void urlWithoutSecretsIsUnchanged() {
        String url = "http://localhost:4566/?Action=ListQueues&Version=2012-11-05";
        assertThat(RequestLogFilter.redactUrl(url), equalTo(url));
        assertThat(RequestLogFilter.redactUrl("http://localhost:4566/health"), equalTo("http://localhost:4566/health"));
    }

    @Test
    void nullUrlIsHandled() {
        assertThat(RequestLogFilter.redactUrl(null), is(nullValue()));
    }

    // ---- the log file ----

    @Test
    void theLogIsCreatedReadableByItsOwnerOnly(@TempDir Path dir) throws IOException {
        Assumptions.assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path log = dir.resolve("nested").resolve("requests.jsonl");

        RequestLogWriter.createOwnerOnly(log);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(log)), equalTo("rw-------"));
    }

    @Test
    void anExistingLogKeepsItsPermissions(@TempDir Path dir) throws IOException {
        Assumptions.assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path log = Files.createFile(dir.resolve("requests.jsonl"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r-----")));

        RequestLogWriter.createOwnerOnly(log);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(log)), equalTo("rw-r-----"));
    }

    @Test
    void aSymbolicLinkAtTheLogPathIsRefused(@TempDir Path dir) throws IOException {
        Path elsewhere = Files.writeString(dir.resolve("someone-elses-file"), "theirs\n");
        Path log = Files.createSymbolicLink(dir.resolve("requests.jsonl"), elsewhere);

        assertThrows(IOException.class, () -> RequestLogWriter.open(log));

        assertThat(Files.readString(elsewhere), equalTo("theirs\n"));
    }

    @Test
    void aDirectoryAtTheLogPathIsRefused(@TempDir Path dir) throws IOException {
        Path log = Files.createDirectory(dir.resolve("requests.jsonl"));

        assertThrows(IOException.class, () -> RequestLogWriter.open(log));
    }

    @Test
    void theWriterAppendsLinesFromItsOwnThread(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("requests.jsonl");
        RequestLogWriter writer = new RequestLogWriter(log);

        writer.append("{\"first\":1}");
        writer.append("{\"second\":2}");

        await().atMost(Duration.ofSeconds(10))
                .until(() -> Files.exists(log) && Files.readAllLines(log).size() == 2);
        assertThat(Files.readAllLines(log), equalTo(List.of("{\"first\":1}", "{\"second\":2}")));
        try (FileChannel ignored = RequestLogWriter.open(log)) {
            assertThat(Files.isRegularFile(log), is(true));
        }
    }

    private static String value(List<Map<String, String>> pairs, String name) {
        return pairs.stream().filter(pair -> pair.get("name").equals(name)).findFirst()
                .map(pair -> pair.get("value")).orElse(null);
    }
}
