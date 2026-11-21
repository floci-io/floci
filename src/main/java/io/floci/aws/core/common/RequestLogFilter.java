package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.InputStream;
import java.io.Writer;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Records every request and response as one line of JSON (JSONL) when {@code floci.request-log.enabled} is true
 * ({@code FLOCI_REQUEST_LOG_ENABLED=true}). Each line is appended to {@code floci.request-log.file}, by default
 * {@code ./floci-requests.jsonl}, and is a self-contained JSON object shaped like a HAR 1.2 {@code entry}
 * (request, response and timings) with the caller's access key id added. The file streams and tails cleanly, and a
 * partial write can only affect its last line. {@link RequestLogWriter} writes the lines from a thread of its own.
 *
 * <p>Request capture runs pre-matching at priority 1000, ahead of every other Floci filter, such as
 * {@link AwsProtocolClaimFilter} (pre-matching, 6000) and {@link IamEnforcementFilter} (default priority), so a
 * request one of them aborts is still captured. Response filters run in reverse priority order, so this one runs last
 * on the way out and sees the final status and headers, including those {@link AwsRequestIdFilter} adds.
 *
 * <p>The log is opt-in debug tooling, so it never disturbs the response it observes: a failure to capture or record
 * an entry is logged at warn and swallowed.
 *
 * <p>Pre-matching runs on the Vert.x IO thread, where reading the JAX-RS entity stream is a blocking operation that
 * RESTEasy Reactive refuses with {@code BlockingNotAllowedException}. The request body is therefore buffered by
 * {@link RequestLogBodyFilter}, a post-matching filter that runs on the worker thread, and handed over on the request
 * context. A request no post-matching filter sees, one that matches no resource or that a pre-matching filter aborts,
 * is still recorded, without {@code postData}.
 */
@Provider
@PreMatching
@Priority(1000)
public class RequestLogFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger LOG = Logger.getLogger(RequestLogFilter.class);

    static final String CAPTURE_PROPERTY = "floci.request-log.capture";
    static final String BODY_PROPERTY = "floci.request-log.body";

    /** Stands in for a body {@link RequestLogBodyFilter} declined to buffer because of its size. */
    static final String TRUNCATED = "<floci: body not captured, too large>";

    /** Stands in for a body sent without a declared length, which could only be read by waiting on the client. */
    static final String UNKNOWN_LENGTH = "<floci: body not captured, no declared length>";

    /** Stands in for a body unreadable because the request was handled on the Vert.x IO thread. */
    static final String NOT_BUFFERED_IO_THREAD =
            "<floci: body not captured, request handled on the IO thread>";

    /** Stands in for a response body streamed to the client, which reading here would consume. */
    static final String STREAMED = "<floci: streamed body not captured>";

    /** Header and query values carrying replayable secrets are stored as this instead of the value. */
    static final String REDACTED = "<floci: redacted>";

    /** Bodies larger than this are not buffered, so a multi-gigabyte upload is never mirrored. */
    static final int MAX_BODY_BYTES = 256 * 1024;

    static final String DEFAULT_FILE = "./floci-requests.jsonl";
    private static final String HTTP_VERSION = "HTTP/1.1";

    /**
     * Header names whose values are replayable secrets: the SigV4 signature, any session token, and session cookies.
     * Redacted before anything is written, so a reader of the log cannot replay a still-valid request. Matched
     * case-insensitively.
     */
    private static final Set<String> SECRET_HEADERS = Set.of(
            "authorization", "x-amz-security-token", "x-amz-content-sha256", "cookie", "set-cookie");

    /**
     * Query parameter names carrying replayable secrets on a presigned URL, lower-cased and matched against the
     * decoded name. The credential's access key id is captured separately as {@code callerAccessKeyId}, as it is no
     * secret, but the signature, the security token and the full credential string are redacted here.
     */
    private static final Set<String> SECRET_QUERY_PARAMS = Set.of(
            "x-amz-signature", "x-amz-security-token", "x-amz-credential");

    private final ObjectMapper objectMapper;
    private final Supplier<EmulatorConfig> config;
    private final Supplier<RequestLogWriter> writer;

    // jakarta.inject.Provider qualified inline: this file imports jakarta.ws.rs.ext.Provider, the JAX-RS annotation,
    // and the two types collide. Read lazily, and once, as AwsProtocolClaimFilter does: JAX-RS providers are
    // instantiated before runtime config mappings exist, and an invalid mapping already fails startup.
    @Inject
    public RequestLogFilter(ObjectMapper objectMapper, jakarta.inject.Provider<EmulatorConfig> configProvider) {
        this.objectMapper = objectMapper;
        this.config = new MemoizedSupplier<>(configProvider::get);
        this.writer = new MemoizedSupplier<>(() -> new RequestLogWriter(logFile(config.get())));
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!enabled()) {
            return;
        }
        try {
            String url = redactUrl(ctx.getUriInfo().getRequestUri().toString());
            RequestCapture capture = new RequestCapture(
                    System.nanoTime(),
                    Instant.now(),
                    ctx.getMethod(),
                    url,
                    headerList(ctx.getHeaders()),
                    queryStringList(ctx.getUriInfo().getQueryParameters()),
                    mimeType(ctx.getMediaType()),
                    callerAccessKeyId(ctx));
            ctx.setProperty(CAPTURE_PROPERTY, capture);
        } catch (Exception e) {
            LOG.warnv(e, "Request log: could not capture request {0} {1}", ctx.getMethod(),
                    ctx.getUriInfo().getPath());
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        if (!enabled()) {
            return;
        }
        if (!(requestContext.getProperty(CAPTURE_PROPERTY) instanceof RequestCapture capture)) {
            return;
        }
        try {
            writer.get().append(objectMapper.writeValueAsString(
                    buildEntry(capture, requestBody(requestContext), responseContext)));
        } catch (Exception e) {
            LOG.warnv(e, "Request log: could not record response for {0} {1}",
                    capture.method(), capture.url());
        }
    }

    private boolean enabled() {
        return config.get().requestLog().enabled();
    }

    /**
     * The body {@link RequestLogBodyFilter} buffered, or {@code null} when the request carried none and when no
     * post-matching filter ran for it at all.
     */
    private static Body requestBody(ContainerRequestContext ctx) {
        return ctx.getProperty(BODY_PROPERTY) instanceof Body body ? body : null;
    }

    private Map<String, Object> buildEntry(RequestCapture capture, Body requestBody,
                                           ContainerResponseContext response) {
        long elapsedMs = Duration.ofNanos(System.nanoTime() - capture.startedNanos()).toMillis();
        Body responseBody = responseBody(response.getEntity());

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", capture.method());
        request.put("url", capture.url());
        request.put("httpVersion", HTTP_VERSION);
        request.put("cookies", List.of());
        request.put("headers", capture.headers());
        request.put("queryString", capture.queryString());
        request.put("headersSize", -1);
        request.put("bodySize", requestBody == null ? 0 : requestBody.originalSize());
        if (requestBody != null) {
            Map<String, Object> postData = new LinkedHashMap<>();
            postData.put("mimeType", capture.mimeType());
            postData.put("params", List.of());
            postData.put("text", requestBody.text());
            if (requestBody.encoding() != null) {
                postData.put("encoding", requestBody.encoding());
            }
            request.put("postData", postData);
        }

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("size", responseBody.originalSize());
        content.put("mimeType", mimeType(response.getMediaType()));
        content.put("text", responseBody.text());
        if (responseBody.encoding() != null) {
            content.put("encoding", responseBody.encoding());
        }

        Map<String, Object> responseNode = new LinkedHashMap<>();
        responseNode.put("status", response.getStatus());
        responseNode.put("statusText", statusText(response));
        responseNode.put("httpVersion", HTTP_VERSION);
        responseNode.put("cookies", List.of());
        responseNode.put("headers", headerList(response.getStringHeaders()));
        responseNode.put("content", content);
        responseNode.put("redirectURL", "");
        responseNode.put("headersSize", -1);
        responseNode.put("bodySize", responseBody.originalSize());

        Map<String, Object> timings = new LinkedHashMap<>();
        timings.put("send", 0);
        timings.put("wait", elapsedMs);
        timings.put("receive", 0);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("startedDateTime", DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .format(capture.startedAt().atOffset(ZoneOffset.UTC)));
        entry.put("time", elapsedMs);
        if (capture.callerAccessKeyId() != null) {
            entry.put("callerAccessKeyId", capture.callerAccessKeyId());
        }
        entry.put("request", request);
        entry.put("response", responseNode);
        entry.put("cache", Map.of());
        entry.put("timings", timings);
        return entry;
    }

    private static Path logFile(EmulatorConfig config) {
        String configured = config.requestLog().file()
                .map(String::strip)
                .filter(path -> !path.isEmpty())
                .orElse(DEFAULT_FILE);
        return Path.of(configured);
    }

    /** The stored form of a response entity, which is never read when it streams to the client. */
    Body responseBody(Object entity) {
        return switch (entity) {
            case null -> Body.EMPTY;
            case String text -> Body.text(text);
            case byte[] bytes -> Body.bytes(bytes);
            case CharSequence text -> Body.text(text.toString());
            // Reading these would consume or reopen what the client is about to receive; their size is unknown here.
            case InputStream ignored -> Body.STREAMED_BODY;
            case StreamingOutput ignored -> Body.STREAMED_BODY;
            case File ignored -> Body.STREAMED_BODY;
            case Path ignored -> Body.STREAMED_BODY;
            default -> serialize(entity);
        };
    }

    /**
     * Serializes an entity no further than the cap, so a large response is never held whole a second time. When the
     * cap is reached the body's full size is not known, and is recorded as -1.
     */
    private Body serialize(Object entity) {
        CappedWriter out = new CappedWriter(MAX_BODY_BYTES);
        try {
            objectMapper.writeValue(out, entity);
        } catch (Exception e) {
            if (!out.capped()) {
                LOG.tracev(e, "Request log: entity of type {0} is not serializable to JSON",
                        entity.getClass().getName());
                return Body.text(String.valueOf(entity));
            }
        }
        return out.capped() ? new Body(out.text(), null, -1) : Body.text(out.text());
    }

    private static String statusText(ContainerResponseContext response) {
        Response.StatusType statusInfo = response.getStatusInfo();
        return statusInfo == null || statusInfo.getReasonPhrase() == null ? "" : statusInfo.getReasonPhrase();
    }

    private static String mimeType(MediaType mediaType) {
        return mediaType == null ? "" : mediaType.toString();
    }

    static List<Map<String, String>> headerList(MultivaluedMap<String, String> headers) {
        return nameValueList(headers, name -> SECRET_HEADERS.contains(name.toLowerCase(Locale.ROOT)));
    }

    static List<Map<String, String>> queryStringList(MultivaluedMap<String, String> params) {
        return nameValueList(params, name -> SECRET_QUERY_PARAMS.contains(name.toLowerCase(Locale.ROOT)));
    }

    /** Each value as a {@code name}/{@code value} pair, with the value of a secret name redacted. */
    private static List<Map<String, String>> nameValueList(MultivaluedMap<String, String> values,
                                                           Predicate<String> secret) {
        List<Map<String, String>> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (Map.Entry<String, List<String>> entry : values.entrySet()) {
            boolean redacted = secret.test(entry.getKey());
            for (String value : entry.getValue()) {
                String stored = redacted ? REDACTED : (value == null ? "" : value);
                result.add(Map.of("name", entry.getKey(), "value", stored));
            }
        }
        return result;
    }

    /**
     * The request URL with the value of every presigned-secret query parameter redacted, so the stored URL cannot be
     * replayed as a still-valid signed request. A parameter is matched by its decoded name, so a percent-encoded name
     * is caught too. Other parameters are kept as they were sent.
     */
    static String redactUrl(String url) {
        if (url == null) {
            return null;
        }
        int query = url.indexOf('?');
        if (query < 0) {
            return url;
        }
        int fragment = url.indexOf('#', query);
        String parameters = fragment < 0 ? url.substring(query + 1) : url.substring(query + 1, fragment);
        StringBuilder redacted = new StringBuilder(url.length()).append(url, 0, query + 1);
        String[] pairs = parameters.split("&", -1);
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                redacted.append('&');
            }
            String pair = pairs[i];
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            if (SECRET_QUERY_PARAMS.contains(decode(name).toLowerCase(Locale.ROOT))) {
                redacted.append(name).append('=').append(REDACTED);
            } else {
                redacted.append(pair);
            }
        }
        if (fragment >= 0) {
            redacted.append(url, fragment, url.length());
        }
        return redacted.toString();
    }

    private static String decode(String name) {
        try {
            return URLDecoder.decode(name, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return name;
        }
    }

    private static final Pattern CREDENTIAL_AKID = Pattern.compile("Credential=([^/]+)/");

    /**
     * The SigV4 access key id the request was signed with, which ties each line to the caller that made it. Read from
     * the {@code Authorization} header, or from the {@code X-Amz-Credential} query parameter of a presigned request;
     * {@code null} for an unsigned request. Terraform, CloudFormation and direct SDK or CLI calls all sign with it.
     */
    private static String callerAccessKeyId(ContainerRequestContext ctx) {
        String auth = ctx.getHeaderString("Authorization");
        if (auth != null) {
            Matcher matcher = CREDENTIAL_AKID.matcher(auth);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        String presigned = ctx.getUriInfo().getQueryParameters().getFirst("X-Amz-Credential");
        if (presigned != null && !presigned.isEmpty()) {
            return presigned.split("/", 2)[0];
        }
        return null;
    }

    /**
     * A captured body. {@code text} is the stored form (UTF-8 text, a placeholder, or base64 for binary content),
     * {@code encoding} is {@code "base64"} for binary content and {@code null} otherwise, and {@code originalSize} is
     * the byte count of the body the client sent or received, before the cap, or -1 when it is not known.
     */
    record Body(String text, String encoding, int originalSize) {
        static final Body EMPTY = new Body("", null, 0);
        static final Body STREAMED_BODY = new Body(STREAMED, null, -1);

        /** Text stored up to the cap, with the size of all of it. */
        static Body text(String value) {
            String full = value == null ? "" : value;
            String stored = full.length() <= MAX_BODY_BYTES ? full : full.substring(0, MAX_BODY_BYTES);
            return new Body(stored, null, full.getBytes(StandardCharsets.UTF_8).length);
        }

        /**
         * Bytes stored up to the cap: as text when they are valid UTF-8, else base64 with {@code encoding=base64},
         * since decoding non-text bytes as UTF-8 mangles them.
         */
        static Body bytes(byte[] bytes) {
            byte[] capped = bytes.length <= MAX_BODY_BYTES ? bytes : Arrays.copyOf(bytes, MAX_BODY_BYTES);
            String text = Utf8.decodeStrictly(capped);
            return text != null
                    ? new Body(text, null, bytes.length)
                    : new Body(Base64.getEncoder().encodeToString(capped), "base64", bytes.length);
        }

        /** A placeholder standing in for a body that was not captured. */
        static Body placeholder(String marker, int originalSize) {
            return new Body(marker, null, originalSize);
        }
    }

    /** Strict UTF-8 decoding, which refuses rather than replaces bytes that are not valid UTF-8. */
    static final class Utf8 {
        private Utf8() {
        }

        static String decodeStrictly(byte[] bytes) {
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException e) {
                return null;
            }
        }
    }

    /** A writer that keeps the first {@code limit} characters and stops the serializer once they are filled. */
    static final class CappedWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private final int limit;
        private boolean capped;

        CappedWriter(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(char[] chars, int offset, int length) {
            int room = limit - text.length();
            if (length > room) {
                text.append(chars, offset, Math.max(room, 0));
                capped = true;
                throw new IllegalStateException("The request log keeps no more of this body");
            }
            text.append(chars, offset, length);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        boolean capped() {
            return capped;
        }

        String text() {
            return text.toString();
        }
    }

    /**
     * The request leg of one entry, stashed on the request context so the response filter can complete it. The body
     * is not part of it: {@link RequestLogBodyFilter} buffers it later and hands it over under {@link #BODY_PROPERTY}.
     */
    record RequestCapture(long startedNanos,
                          Instant startedAt,
                          String method,
                          String url,
                          List<Map<String, String>> headers,
                          List<Map<String, String>> queryString,
                          String mimeType,
                          String callerAccessKeyId) {
    }
}
