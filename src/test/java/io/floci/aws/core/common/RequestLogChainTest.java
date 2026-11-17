package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The request log's filters in the order JAX-RS runs them for one request: the pre-matching capture, the
 * post-matching body read, then the response, written to a real file. Floci's own filter chain is not booted for this,
 * so the order it relies on is checked on the annotations.
 */
class RequestLogChainTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * The body has to be read post-matching, on the worker thread: reading it pre-matching fails on the Vert.x IO
     * thread and drops every request body from the log. It runs ahead of every other post-matching filter, and the
     * capture runs ahead of every pre-matching one, so a request another filter aborts is still logged.
     */
    @Test
    void theCaptureRunsFirstPreMatchingAndTheBodyIsReadPostMatching() {
        assertTrue(RequestLogFilter.class.isAnnotationPresent(PreMatching.class));
        assertThat(RequestLogFilter.class.getAnnotation(Priority.class).value(), equalTo(1000));
        assertFalse(RequestLogBodyFilter.class.isAnnotationPresent(PreMatching.class));
        assertThat(RequestLogBodyFilter.class.getAnnotation(Priority.class).value(), equalTo(1));
    }

    @Test
    void aSignedQueryPostIsLoggedWithItsBodyAndResponseAndItsSecretsRedacted(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("requests.jsonl");
        RequestLogFilter capture = new RequestLogFilter(mapper, () -> config(log));
        byte[] body = "Action=ListQueues&Version=2012-11-05".getBytes(StandardCharsets.UTF_8);
        ContainerRequestContext request = request(body);
        ContainerResponseContext response = response();

        capture.filter(request);
        new RequestLogBodyFilter(() -> true).filter(request);
        capture.filter(request, response);

        JsonNode entry = await("the entry in " + log).atMost(Duration.ofSeconds(10))
                .until(() -> Files.exists(log) ? Files.readAllLines(log) : List.<String>of(), lines -> lines.size() == 1)
                .stream().map(this::parse).findFirst().orElseThrow();
        JsonNode logged = entry.path("request");
        assertThat(logged.path("method").asText(), equalTo("POST"));
        assertThat(logged.path("postData").path("text").asText(), equalTo("Action=ListQueues&Version=2012-11-05"));
        assertThat(logged.path("postData").path("mimeType").asText(),
                containsString("application/x-www-form-urlencoded"));
        assertThat(logged.path("bodySize").asInt(), equalTo(body.length));
        assertThat(entry.path("callerAccessKeyId").asText(), equalTo("AKIAEXAMPLE"));
        assertThat(header(logged, "Authorization"), equalTo(RequestLogFilter.REDACTED));
        assertThat(header(logged, "X-Amz-Security-Token"), equalTo(RequestLogFilter.REDACTED));
        assertThat(header(logged, "Cookie"), equalTo(RequestLogFilter.REDACTED));
        assertThat(header(logged, "Content-Type"), containsString("application/x-www-form-urlencoded"));
        assertThat(logged.path("url").asText(), not(containsString("deadbeef")));
        JsonNode answered = entry.path("response");
        assertThat(answered.path("status").asInt(), equalTo(200));
        assertThat(answered.path("content").path("text").asText(), containsString("ListQueuesResponse"));
        assertThat(header(answered, "Set-Cookie"), equalTo(RequestLogFilter.REDACTED));
    }

    private static EmulatorConfig config(Path log) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.requestLog().enabled()).thenReturn(true);
        when(config.requestLog().file()).thenReturn(Optional.of(log.toString()));
        return config;
    }

    private static ContainerRequestContext request(byte[] body) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        Map<String, Object> properties = new HashMap<>();
        doAnswer(invocation -> properties.put(invocation.getArgument(0), invocation.getArgument(1)))
                .when(ctx).setProperty(anyString(), any());
        when(ctx.getProperty(anyString())).thenAnswer(invocation -> properties.get(invocation.<String>getArgument(0)));
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.add("Authorization", "AWS4-HMAC-SHA256 Credential=AKIAEXAMPLE/20260928/us-east-1/sqs/aws4_request, "
                + "SignedHeaders=host;x-amz-date, Signature=deadbeef");
        headers.add("X-Amz-Security-Token", "FwoGZXIvYXdzEXAMPLETOKEN");
        headers.add("Cookie", "session=secret");
        headers.add("Content-Type", "application/x-www-form-urlencoded");
        when(ctx.getHeaders()).thenReturn(headers);
        when(ctx.getHeaderString(anyString())).thenAnswer(invocation -> headers.getFirst(invocation.getArgument(0)));
        UriInfo uri = mock(UriInfo.class);
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost:4566/?X-Amz-Signature=deadbeef"));
        MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        query.add("X-Amz-Signature", "deadbeef");
        when(uri.getQueryParameters()).thenReturn(query);
        when(uri.getPath()).thenReturn("/");
        when(ctx.getUriInfo()).thenReturn(uri);
        when(ctx.getMethod()).thenReturn("POST");
        when(ctx.getMediaType()).thenReturn(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
        when(ctx.hasEntity()).thenReturn(true);
        when(ctx.getLength()).thenReturn(body.length);
        InputStream[] stream = {new ByteArrayInputStream(body)};
        when(ctx.getEntityStream()).thenAnswer(invocation -> stream[0]);
        doAnswer(invocation -> stream[0] = invocation.getArgument(0)).when(ctx).setEntityStream(any());
        return ctx;
    }

    private static ContainerResponseContext response() {
        ContainerResponseContext ctx = mock(ContainerResponseContext.class);
        when(ctx.getEntity()).thenReturn("<ListQueuesResponse><ListQueuesResult/></ListQueuesResponse>");
        when(ctx.getStatus()).thenReturn(200);
        when(ctx.getStatusInfo()).thenReturn(Response.Status.OK);
        when(ctx.getMediaType()).thenReturn(MediaType.TEXT_XML_TYPE);
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.add("Set-Cookie", "session=renewed; Path=/");
        headers.add("Content-Type", "text/xml");
        when(ctx.getStringHeaders()).thenReturn(headers);
        return ctx;
    }

    private JsonNode parse(String line) {
        try {
            return mapper.readTree(line);
        } catch (Exception e) {
            throw new AssertionError("not a JSON line: " + line, e);
        }
    }

    private static String header(JsonNode side, String name) {
        for (JsonNode header : side.path("headers")) {
            if (name.equalsIgnoreCase(header.path("name").asText())) {
                return header.path("value").asText();
            }
        }
        return null;
    }
}
