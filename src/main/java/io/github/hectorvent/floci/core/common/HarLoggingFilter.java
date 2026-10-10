package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
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
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Records every request/response pair as a line of JSON (JSONL) when
 * {@code floci.chaos.har.enabled = true} ({@code FLOCI_CHAOS_HAR_ENABLED=true} in the
 * environment). One HAR-shaped entry is appended per line to {@code floci.chaos.har.file},
 * defaulting to {@code ./floci-chaos.jsonl}. Each line is a self-contained JSON object with the
 * same request/response/timings shape a HAR 1.2 {@code entry} carries, so the file streams and
 * tails cleanly and a partial write only ever affects the last line.
 *
 * <p>Request capture runs pre-matching at priority 1000, ahead of every other Floci filter:
 * {@link AwsProtocolClaimFilter} (pre-matching, 6000), {@link IamEnforcementFilter} (default
 * priority) and {@link ChaosInterceptorFilter} (6500). A request that one of those aborts is
 * therefore already captured. Response filters run in reverse priority order, so this one runs
 * last on the way out and sees the final status and headers, including those added by
 * {@link AwsRequestIdFilter}. Together that means a faulted or delayed response is recorded
 * exactly like a served one.
 *
 * <p>The log is opt-in debug tooling, so it favours never disturbing the response it
 * observes: any failure on either leg is logged at warn and swallowed.
 *
 * <p>Pre-matching runs on the Vert.x IO thread, where reading the JAX-RS entity stream is a
 * blocking operation RESTEasy Reactive rejects outright with
 * {@code BlockingNotAllowedException}. The request body is therefore buffered by
 * {@link HarRequestBodyFilter}, a post-matching filter that runs on the worker thread, and
 * handed over on the request context. A request no post-matching filter ever sees - one that
 * matches no resource, or that a pre-matching filter aborts - is still recorded, with no
 * {@code postData}.
 */
@Provider
@PreMatching
@Priority(1000)
public class HarLoggingFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger LOG = Logger.getLogger(HarLoggingFilter.class);

    static final String CAPTURE_PROPERTY = "floci.chaos.har.capture";
    static final String BODY_PROPERTY = "floci.chaos.har.body";

    /** Stands in for a body {@link HarRequestBodyFilter} declined to buffer because of its size. */
    static final String TRUNCATED = "<floci: body not captured, too large>";

    /** Stands in for a body unreadable because the request was handled on the Vert.x IO thread. */
    static final String NOT_BUFFERED_IO_THREAD =
            "<floci: body not captured, request handled on the IO thread>";

    /** Header/query values carrying replayable secrets are stored as this instead of the value. */
    static final String REDACTED = "<floci: redacted>";

    /** Bodies larger than this are not buffered, so a multi-gigabyte upload is never mirrored. */
    static final int MAX_BODY_BYTES = 256 * 1024;

    private static final String DEFAULT_FILE = "./floci-chaos.jsonl";
    private static final String HAR_VERSION = "1.2";
    private static final String HTTP_VERSION = "HTTP/1.1";

    private final ObjectMapper objectMapper;
    // jakarta.inject.Provider qualified inline: this file imports jakarta.ws.rs.ext.Provider,
    // the JAX-RS annotation, and the two types collide. Resolved lazily for the same reason as
    // AwsProtocolClaimFilter: JAX-RS providers are instantiated before runtime config mappings
    // exist.
    private final jakarta.inject.Provider<EmulatorConfig> configProvider;

    private final Object writeLock = new Object();

    @Inject
    public HarLoggingFilter(ObjectMapper objectMapper, jakarta.inject.Provider<EmulatorConfig> configProvider) {
        this.objectMapper = objectMapper;
        this.configProvider = configProvider;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!harEnabled()) {
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
                    queryStringList(ctx),
                    mimeType(ctx.getMediaType()),
                    callerAccessKeyId(ctx));
            ctx.setProperty(CAPTURE_PROPERTY, capture);
        } catch (Exception e) {
            LOG.warnv(e, "HAR logging: could not capture request {0} {1}", ctx.getMethod(),
                    ctx.getUriInfo().getPath());
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        if (!harEnabled()) {
            return;
        }
        if (!(requestContext.getProperty(CAPTURE_PROPERTY) instanceof RequestCapture capture)) {
            return;
        }
        try {
            appendEntry(buildEntry(capture, requestBody(requestContext), responseContext));
        } catch (Exception e) {
            LOG.warnv(e, "HAR logging: could not record response for {0} {1}",
                    capture.method(), capture.url());
        }
    }

    private boolean harEnabled() {
        try {
            return configProvider.get().chaos().har().enabled();
        } catch (RuntimeException e) {
            LOG.tracev(e, "HAR logging: configuration not resolvable yet");
            return false;
        }
    }

    /**
     * The body {@link HarRequestBodyFilter} buffered, or {@code null} when the request carried
     * none and when no post-matching filter ran for it at all.
     */
    private static String requestBody(ContainerRequestContext ctx) {
        return ctx.getProperty(BODY_PROPERTY) instanceof String body ? body : null;
    }

    private Map<String, Object> buildEntry(RequestCapture capture, String requestBody,
                                           ContainerResponseContext response) {
        long elapsedMs = Duration.ofNanos(System.nanoTime() - capture.startedNanos()).toMillis();
        ResponseBody responseBody = responseBody(response);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", capture.method());
        request.put("url", capture.url());
        request.put("httpVersion", HTTP_VERSION);
        request.put("cookies", List.of());
        request.put("headers", capture.headers());
        request.put("queryString", capture.queryString());
        request.put("headersSize", -1);
        request.put("bodySize", byteLength(requestBody));
        if (requestBody != null) {
            Map<String, Object> postData = new LinkedHashMap<>();
            postData.put("mimeType", capture.mimeType());
            postData.put("params", List.of());
            postData.put("text", requestBody);
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

    /**
     * Appends the entry as one JSON line. Appending under a lock keeps concurrent requests from
     * interleaving partial lines, and unlike the former whole-document rewrite it is O(1) per
     * request and leaves every prior line untouched. Each line is a complete JSON object, so a
     * crash mid-write can only corrupt the final line.
     */
    private void appendEntry(Map<String, Object> entry) {
        synchronized (writeLock) {
            Path target = harFile();
            try {
                Path parent = target.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(target, objectMapper.writeValueAsString(entry) + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND);
            } catch (IOException | RuntimeException e) {
                LOG.warnv(e, "HAR logging: could not write {0}", target);
            }
        }
    }

    private Path harFile() {
        String configured = configProvider.get().chaos().har().file()
                .map(String::strip)
                .filter(path -> !path.isEmpty())
                .orElse(DEFAULT_FILE);
        return Path.of(configured);
    }

    private ResponseBody responseBody(ContainerResponseContext response) {
        Object entity = response.getEntity();
        return switch (entity) {
            case null -> ResponseBody.EMPTY;
            case String text -> ResponseBody.text(truncate(text));
            case byte[] bytes -> encodeBytes(bytes);
            case CharSequence text -> ResponseBody.text(truncate(text.toString()));
            default -> ResponseBody.text(serialize(entity));
        };
    }

    /**
     * A {@code byte[]} response (e.g. CBOR, or any binary content) is stored base64 with
     * {@code encoding=base64} and its ORIGINAL byte count, rather than being decoded as UTF-8:
     * decoding mangles non-text bytes and makes the logged size disagree with what the client
     * received. Bytes that happen to be valid UTF-8 could be stored as text, but detecting that
     * reliably is not worth the ambiguity, so all {@code byte[]} bodies are base64.
     */
    static ResponseBody encodeBytes(byte[] bytes) {
        int originalSize = bytes.length;
        byte[] capped = bytes.length <= MAX_BODY_BYTES
                ? bytes : Arrays.copyOf(bytes, MAX_BODY_BYTES);
        String base64 = Base64.getEncoder().encodeToString(capped);
        return new ResponseBody(base64, "base64", originalSize);
    }

    private String serialize(Object entity) {
        try {
            return truncate(objectMapper.writeValueAsString(entity));
        } catch (JsonProcessingException e) {
            LOG.tracev(e, "HAR logging: entity of type {0} is not serializable to JSON",
                    entity.getClass().getName());
            return truncate(String.valueOf(entity));
        }
    }

    private static String truncate(String body) {
        return body.length() <= MAX_BODY_BYTES ? body : body.substring(0, MAX_BODY_BYTES);
    }

    private static int byteLength(String body) {
        return body == null ? 0 : body.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String statusText(ContainerResponseContext response) {
        Response.StatusType statusInfo = response.getStatusInfo();
        return statusInfo == null || statusInfo.getReasonPhrase() == null ? "" : statusInfo.getReasonPhrase();
    }

    private static String mimeType(MediaType mediaType) {
        return mediaType == null ? "" : mediaType.toString();
    }

    /**
     * Header names whose values are replayable secrets: the SigV4 signature and any session
     * token. Redacted before anything is written, so a reader of the log cannot replay a
     * still-valid signed request. Matched case-insensitively.
     */
    private static final Set<String> SECRET_HEADERS = Set.of(
            "authorization", "x-amz-security-token", "x-amz-content-sha256");

    /**
     * Query parameter names carrying replayable secrets on a presigned URL. The credential's
     * access-key id is captured separately as {@code callerAccessKeyId} (not a secret), but the
     * signature, the security token and the full credential string are redacted here.
     */
    private static final Set<String> SECRET_QUERY_PARAMS = Set.of(
            "X-Amz-Signature", "X-Amz-Security-Token", "X-Amz-Credential");

    private static List<Map<String, String>> headerList(MultivaluedMap<String, String> headers) {
        List<Map<String, String>> result = new ArrayList<>();
        if (headers == null) {
            return result;
        }
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            boolean secret = SECRET_HEADERS.contains(header.getKey().toLowerCase(Locale.ROOT));
            for (String value : header.getValue()) {
                String stored = secret ? REDACTED : (value == null ? "" : value);
                result.add(Map.of("name", header.getKey(), "value", stored));
            }
        }
        return result;
    }

    private static List<Map<String, String>> queryStringList(ContainerRequestContext ctx) {
        MultivaluedMap<String, String> params = ctx.getUriInfo().getQueryParameters();
        List<Map<String, String>> result = new ArrayList<>();
        if (params == null) {
            return result;
        }
        for (Map.Entry<String, List<String>> param : params.entrySet()) {
            boolean secret = SECRET_QUERY_PARAMS.contains(param.getKey());
            for (String value : param.getValue()) {
                String stored = secret ? REDACTED : (value == null ? "" : value);
                result.add(Map.of("name", param.getKey(), "value", stored));
            }
        }
        return result;
    }

    /**
     * The request URL with any presigned-secret query parameters redacted, so the stored URL
     * cannot be replayed as a still-valid signed request. Non-secret parameters are preserved.
     */
    static String redactUrl(String url) {
        if (url == null) {
            return null;
        }
        String redacted = url;
        for (String param : SECRET_QUERY_PARAMS) {
            redacted = redacted.replaceAll("([?&]" + Pattern.quote(param) + "=)[^&]*", "$1" + REDACTED);
        }
        return redacted;
    }

    /** Same resolution the health and info endpoints use, so the HAR creator matches them. */
    private static String resolveVersion() {
        String env = System.getenv("FLOCI_VERSION");
        return env == null || env.isBlank() ? "dev" : env;
    }

    private static final Pattern CREDENTIAL_AKID = Pattern.compile("Credential=([^/]+)/");

    /**
     * The SigV4 access key id the request signed with, used to attribute a request to the caller
     * (in the benchmark, a per-trial dummy key set on the agent container). Read from the
     * {@code Authorization} header, or from the {@code X-Amz-Credential} query parameter for a
     * presigned request. Returns {@code null} for an unsigned request. Terraform, CloudFormation
     * and direct SDK/CLI calls all sign with this key, so it attributes every method uniformly.
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
     * A captured response body. {@code text} is the stored representation (UTF-8 text, or base64
     * for binary), {@code encoding} is {@code "base64"} for binary content and {@code null} for
     * text, and {@code originalSize} is the true byte count of the body the client received,
     * before any truncation to {@link #MAX_BODY_BYTES}.
     */
    record ResponseBody(String text, String encoding, int originalSize) {
        static final ResponseBody EMPTY = new ResponseBody("", null, 0);

        static ResponseBody text(String value) {
            String stored = value == null ? "" : value;
            return new ResponseBody(stored, null, stored.getBytes(StandardCharsets.UTF_8).length);
        }
    }

    /**
     * The request leg of one HAR entry, stashed on the request context so the response filter can
     * complete it. The body is not part of it: it is buffered later, by
     * {@link HarRequestBodyFilter}, and travels under {@link #BODY_PROPERTY}.
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
