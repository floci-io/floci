package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.core.common.RequestHost;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebEnvironmentManager.Environment;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebEnvironmentManager.StartFailure;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebEnvironmentManager.Throttled;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebService.WebFunctionTarget;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionRevision;
import io.quarkus.vertx.http.runtime.RouteConstants;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Serves the endpoint domains of Web Functions. A request whose Host is a {@code lambda-web} domain
 * goes over HTTP/1.1 to the revision's environment with the headers AWS adds and drops; any other
 * request falls through to the next route.
 */
@ApplicationScoped
public class LambdaWebDataPlane {

    static final Set<String> SUPPORTED_METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "OPTIONS", "DELETE");
    /** Dropped before forwarding. {@code te} passes through, as recorded. */
    static final Set<String> DROPPED_REQUEST_HEADERS = Set.of("host", "connection", "keep-alive", "upgrade", "expect",
            "trailer", "transfer-encoding", "proxy-authenticate", "proxy-authorization", "proxy-connection",
            "x-real-ip", "x-http-method-override", "x-http-method", "x-method-override");
    static final Set<String> DROPPED_RESPONSE_HEADERS = Set.of("connection", "keep-alive", "transfer-encoding",
            "upgrade", "te", "trailer", "trailers", "proxy-authenticate", "proxy-authorization");
    static final String REQUEST_ID_HEADER = "lambda-web-request-id";
    private static final String IAM_AUTH = "IamAuth";
    private static final Pattern ENDPOINT_DOMAIN = Pattern.compile(
            "[a-z0-9]{16}\\.(?:[0-9]{3}\\.lambda-web\\.[a-z0-9-]+|lambda-web\\.global)\\..+", Pattern.CASE_INSENSITIVE);
    private static final Logger LOG = Logger.getLogger(LambdaWebDataPlane.class);
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int REQUEST_ID_LENGTH = 26;
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);
    /** Floci sees an exit some tens of milliseconds after it. A process that keeps running is not waited for longer. */
    private static final Duration EXIT_WAIT = Duration.ofSeconds(1);
    /** The edge counts the request line, the header lines and the blank line, as the client sent them. */
    private static final int MAX_HEAD_LENGTH = 128 * 1024;
    /** The layer behind the edge refuses a URI this long. */
    private static final int URI_TOO_LONG = 65_535;
    /** The Quarkus defaults, which every other host keeps. */
    private static final int MAX_INITIAL_LINE_LENGTH = 4096;
    private static final int MAX_HEADER_SIZE = 20 * 1024;

    private final LambdaWebService service;
    private final LambdaWebEnvironmentManager environments;
    private final LambdaWebIamAuth iamAuth;
    private final LambdaWebMetrics metrics;
    private final ServiceConfigAccess serviceConfigAccess;
    private final Vertx vertx;
    private final HttpClient client;
    private final LambdaWebRateLimiter rateLimiter = new LambdaWebRateLimiter();

    @Inject
    public LambdaWebDataPlane(LambdaWebService service, LambdaWebEnvironmentManager environments,
                              LambdaWebIamAuth iamAuth, LambdaWebMetrics metrics,
                              ServiceConfigAccess serviceConfigAccess, Vertx vertx) {
        this.service = service;
        this.environments = environments;
        this.iamAuth = iamAuth;
        this.metrics = metrics;
        this.serviceConfigAccess = serviceConfigAccess;
        this.vertx = vertx;
        this.client = vertx.createHttpClient(new HttpClientOptions()
                .setMaxPoolSize(256)
                .setConnectTimeout(5_000)
                .setKeepAlive(false));
    }

    void register(@Observes Router router) {
        // A head is refused before its body is, as on AWS.
        router.route().order(RouteConstants.ROUTE_ORDER_UPLOAD_LIMIT - 1).handler(this::limitHead);
        router.route().handler(this::handle);
    }

    /**
     * The server reads heads past every limit, so the limits are kept here. Lambda Web hosts get the
     * AWS ones and every other host the Quarkus defaults. A head within the defaults is within both,
     * so only a longer one has its host looked up.
     */
    void limitHead(RoutingContext context) {
        HttpServerRequest request = context.request();
        if (request.version() == HttpVersion.HTTP_2) {
            context.next();
            return;
        }
        int initialLine = request.method().name().length() + 1 + request.uri().length() + 1 + "HTTP/1.1".length();
        int headerBytes = 0;
        int headerLines = 0;
        for (Map.Entry<String, String> header : request.headers()) {
            headerBytes += header.getKey().length() + ": ".length() + header.getValue().length();
            headerLines++;
        }
        if (initialLine <= MAX_INITIAL_LINE_LENGTH && headerBytes <= MAX_HEADER_SIZE) {
            context.next();
            return;
        }
        String host = RequestHost.of(request);
        if (host != null && isLambdaWebHost(host) && serviceConfigAccess.isEnabled("lambda")) {
            int head = initialLine + headerBytes + 2 * (1 + headerLines + 1);
            if (head > MAX_HEAD_LENGTH) {
                edge(request, 431, "Request Header Fields Too Large", LambdaWebErrorPages.HEADER_TOO_LARGE_TYPE,
                        LambdaWebErrorPages.HEADER_TOO_LARGE_HTML);
            } else if (request.uri().length() >= URI_TOO_LONG) {
                uriTooLong(request);
            } else {
                context.next();
            }
            return;
        }
        HttpServerResponse response = request.response();
        response.setStatusCode(initialLine > MAX_INITIAL_LINE_LENGTH ? 414 : 431).end();
        response.close();
    }

    void handle(RoutingContext context) {
        HttpServerRequest request = context.request();
        String host = RequestHost.of(request);
        if (host == null || !isLambdaWebHost(host) || !serviceConfigAccess.isEnabled("lambda")) {
            context.next();
            return;
        }
        String requestId = newRequestId();
        if (request.version() == HttpVersion.HTTP_1_0) {
            page(request, LambdaWebErrorPages.UNSUPPORTED_HTTP_VERSION, requestId, true);
            return;
        }
        if (!SUPPORTED_METHODS.contains(request.method().name())) {
            malformed(request);
            return;
        }
        Optional<WebFunctionTarget> located = service.locate(host);
        if (located.isEmpty()) {
            page(request, LambdaWebErrorPages.INVALID_ENDPOINT, requestId, false);
            return;
        }
        WebFunctionTarget target = located.get();
        if (target.endpoint().failed) {
            metrics.answered(target, LambdaWebErrorPages.ENDPOINT_NOT_ACTIVE.status());
            page(request, LambdaWebErrorPages.ENDPOINT_NOT_ACTIVE, requestId, false);
            return;
        }
        Integer rateLimit = target.endpoint().throttleConfig == null ? null : target.endpoint().throttleConfig.rateLimit;
        if (Integer.valueOf(0).equals(rateLimit)) {
            metrics.answered(target, LambdaWebErrorPages.ENDPOINT_DISABLED.status());
            page(request, LambdaWebErrorPages.ENDPOINT_DISABLED, requestId, false);
            return;
        }
        if (!IAM_AUTH.equals(target.endpoint().authType)) {
            admit(request, host, target, requestId);
            return;
        }
        // The signature is checked before the rate limit, so refused requests take no tokens.
        request.pause();
        LambdaWebIamAuth.Inbound signed = LambdaWebIamAuth.Inbound.of(request);
        vertx.<Boolean>executeBlocking(() -> iamAuth.allows(signed, target), false).onComplete(allowed -> {
            if (allowed.succeeded() && allowed.result()) {
                admit(request, host, target, requestId);
                return;
            }
            request.resume();
            if (allowed.failed()) {
                LOG.errorv(allowed.cause(), "Could not check the caller of {0}", host);
                metrics.answered(target, 502);
                internalError(request, requestId);
                return;
            }
            metrics.answered(target, LambdaWebErrorPages.FORBIDDEN.status());
            page(request, LambdaWebErrorPages.FORBIDDEN, requestId, false);
        });
    }

    private void admit(HttpServerRequest request, String host, WebFunctionTarget target, String requestId) {
        Integer rateLimit = target.endpoint().throttleConfig == null ? null : target.endpoint().throttleConfig.rateLimit;
        if (rateLimit != null && !rateLimiter.tryAcquire(endpointKey(target), rateLimit)) {
            request.resume();
            metrics.throttled(target, LambdaWebMetrics.REQUEST_THROTTLES);
            page(request, LambdaWebErrorPages.ENDPOINT_REQUEST_THROTTLE, requestId, false);
            return;
        }
        WebFunctionRevision revision = LambdaWebService.pickRevision(target);
        if (revision == null) {
            request.resume();
            metrics.answered(target, LambdaWebErrorPages.ORIGIN_REQUEST_ERROR.status());
            page(request, LambdaWebErrorPages.ORIGIN_REQUEST_ERROR, requestId, false);
            return;
        }
        if ("100-continue".equalsIgnoreCase(request.getHeader("Expect"))) {
            request.response().writeContinue();
        }
        request.pause();
        acquire(request, host, target, revision, requestId, null);
    }

    /**
     * The domain is minted for each creation, so a recreated endpoint shares no state with the deleted one.
     * Each region of an endpoint has its own environments and rate limit.
     */
    private static String endpointKey(WebFunctionTarget target) {
        return String.join("|", target.accountId(), target.servingRegion(), target.endpoint().domainName);
    }

    /** Starts or reuses the revision's environment off the event loop, then forwards. */
    private void acquire(HttpServerRequest request, String host, WebFunctionTarget target,
                         WebFunctionRevision revision, String requestId, Environment dead) {
        vertx.<Environment>executeBlocking(() -> {
            if (dead != null) {
                environments.discard(dead);
            }
            if (!service.isRevisionActive(target, revision.revisionId)) {
                throw new StartFailure(LambdaWebErrorPages.REVISION_NOT_ACTIVE);
            }
            Integer maxEnvironments = target.endpoint().scalingConfig == null
                    ? null : target.endpoint().scalingConfig.maxEnvironments;
            return environments.acquire(target.accountId(), target.servingRegion(), target.function(),
                    endpointKey(target), maxEnvironments, revision);
        }, false)
                .onSuccess(environment -> forward(request, host, target, revision, environment, requestId, dead == null))
                .onFailure(error -> {
                    request.resume();
                    if (error instanceof StartFailure failure) {
                        metrics.answered(target, failure.page().status());
                        page(request, failure.page(), requestId, false);
                        return;
                    }
                    if (error instanceof Throttled) {
                        metrics.throttled(target, LambdaWebMetrics.ACTIVE_ENVIRONMENT_THROTTLES);
                        page(request, LambdaWebErrorPages.ENDPOINT_MAX_ENV_THROTTLE, requestId, false);
                        return;
                    }
                    LOG.errorv(error, "Could not start an environment for {0}", host);
                    metrics.answered(target, 502);
                    internalError(request, requestId);
                });
    }

    /**
     * One forwarded request. {@code timeoutSeconds} bounds the whole exchange: past it an answer
     * that has not started is the 504 page and one that has is cut. The edge takes the whole
     * request body before it answers, so a slow upload gets its 504 once the body is in.
     */
    private void forward(HttpServerRequest request, String host, WebFunctionTarget target,
                         WebFunctionRevision revision, Environment environment, String requestId, boolean firstTry) {
        long started = System.nanoTime();
        Exchange exchange = new Exchange(request, environment, requestId, target, started);
        boolean hasBody = hasRequestBody(request);
        AtomicBoolean bodyIn = new AtomicBoolean(!hasBody);
        AtomicBoolean expired = new AtomicBoolean();
        RequestOptions options = new RequestOptions()
                .setHost(environment.host())
                .setPort(environment.port())
                .setURI(upstreamUri(request))
                .setMethod(request.method());
        client.request(options).onSuccess(upstream -> {
            copyRequestHeaders(request, upstream, host, target.endpoint().endpointName, requestId);
            if (hasBody && request.getHeader("Content-Length") == null) {
                upstream.setChunked(true);
            }
            Runnable timeOut = () -> {
                if (exchange.finish()) {
                    upstream.reset();
                    if (request.response().headWritten()) {
                        exchange.relayed(LambdaWebErrorPages.REQUEST_TIMEOUT.errorType());
                        request.response().close();
                    } else {
                        exchange.replaced(LambdaWebErrorPages.REQUEST_TIMEOUT);
                        page(request, LambdaWebErrorPages.REQUEST_TIMEOUT, requestId, false);
                    }
                }
            };
            exchange.deadline = vertx.setTimer(TimeUnit.SECONDS.toMillis(revision.serviceConfig.timeoutSeconds), id -> {
                expired.set(true);
                if (bodyIn.get()) {
                    timeOut.run();
                }
            });
            Future<HttpClientResponse> sent = hasBody
                    ? upstream.send(new RequestBody(request, () -> {
                        bodyIn.set(true);
                        if (expired.get()) {
                            timeOut.run();
                        }
                    }))
                    : upstream.send();
            sent.onSuccess(response -> relay(request, response, exchange, requestId))
                    .onFailure(error -> vertx.runOnContext(ignored -> {
                        LOG.debugv("The environment {0} did not answer request {1}: {2}",
                                environment.containerId(), requestId, error.toString());
                        if (request.response().headWritten()) {
                            if (exchange.finish()) {
                                exchange.relayed(null);
                                request.response().close();
                            }
                            return;
                        }
                        failed(request, host, target, revision, exchange, requestId, started, firstTry && !hasBody);
                    }));
            if (hasBody) {
                request.resume();
            }
        }).onFailure(error -> {
            LOG.debugv("The environment {0} refused the connection: {1}", environment.containerId(), error.toString());
            failed(request, host, target, revision, exchange, requestId, started, firstTry);
        });
    }

    /**
     * A process that exited before the request arrived is invisible to the client, which gets a
     * fresh environment, as on AWS. One that died under the request, or that cannot be retried
     * because the body is spent, fails the request. The answer waits for Floci to see the exit, so
     * the next request does not meet the dead environment.
     */
    private void failed(HttpServerRequest request, String host, WebFunctionTarget target,
                        WebFunctionRevision revision, Exchange exchange, String requestId, long started,
                        boolean retry) {
        if (!exchange.finish()) {
            return;
        }
        Environment environment = exchange.environment;
        Future.fromCompletionStage(environment.awaitExit(EXIT_WAIT), vertx.getOrCreateContext())
                .onComplete(ignored -> {
                    if (retry && environment.exitedBefore(started)) {
                        environment.exchangeClosed();
                        acquire(request, host, target, revision, requestId, environment);
                        return;
                    }
                    request.resume();
                    exchange.replaced(LambdaWebErrorPages.ORIGIN_REQUEST_ERROR);
                    page(request, LambdaWebErrorPages.ORIGIN_REQUEST_ERROR, requestId, false);
                });
    }

    private void relay(HttpServerRequest request, HttpClientResponse response, Exchange exchange, String requestId) {
        if (exchange.isFinished()) {
            return;
        }
        HttpServerResponse out = request.response();
        out.setStatusCode(response.statusCode());
        copyResponseHeaders(response.headers(), out);
        out.headers().set("lambda-web-logstream", exchange.environment.logStream());
        out.headers().set(REQUEST_ID_HEADER, requestId);
        if (response.getHeader("Content-Length") == null && hasBody(request.method(), response.statusCode())) {
            out.setChunked(true);
        }
        response.pipe().endOnSuccess(false).to(out).onComplete(piped -> {
            if (!exchange.finish()) {
                return;
            }
            if (piped.succeeded()) {
                exchange.relayed(null);
                out.end();
                return;
            }
            if (out.headWritten()) {
                exchange.relayed(null);
                out.close();
            } else {
                exchange.replaced(LambdaWebErrorPages.ORIGIN_REQUEST_ERROR);
                page(request, LambdaWebErrorPages.ORIGIN_REQUEST_ERROR, requestId, false);
            }
        });
    }

    /**
     * One request against one environment. The first finisher releases the reservation and the deadline, and reports
     * the request in {@code platform.requestComplete} and the request metrics. Every answer is reported before its
     * last bytes go out, so a client that has the answer finds its metrics queued.
     */
    private final class Exchange {
        private final HttpServerRequest request;
        private final Environment environment;
        private final String requestId;
        private final WebFunctionTarget target;
        private final String endpointName;
        private final long started;
        private final AtomicBoolean finished = new AtomicBoolean();
        private long deadline = -1;

        Exchange(HttpServerRequest request, Environment environment, String requestId, WebFunctionTarget target,
                 long started) {
            this.request = request;
            this.environment = environment;
            this.requestId = requestId;
            this.target = target;
            this.endpointName = target.endpoint().endpointName;
            this.started = started;
            environment.exchangeOpened();
        }

        /** The answer of the app, cut when {@code errorType} is set. A cut answer counts under its first status. */
        void relayed(String errorType) {
            HttpServerResponse response = request.response();
            environment.requestComplete(requestId, endpointName, response.getStatusCode(), errorType,
                    System.nanoTime() - started, response.bytesWritten());
            metrics.executed(target, response.getStatusCode(), environments.environmentCount(endpointKey(target)));
        }

        /** A page in place of the app's answer. AWS counts none of its bytes. */
        void replaced(LambdaWebErrorPages.Page page) {
            environment.requestComplete(requestId, endpointName, page.status(), page.errorType(),
                    System.nanoTime() - started, 0);
            metrics.executed(target, page.status(), environments.environmentCount(endpointKey(target)));
        }

        boolean finish() {
            if (!finished.compareAndSet(false, true)) {
                return false;
            }
            if (deadline >= 0) {
                vertx.cancelTimer(deadline);
            }
            environment.release();
            return true;
        }

        boolean isFinished() {
            return finished.get();
        }
    }

    /** The request body as the upstream reads it, telling {@code onEnd} when the last byte went by. */
    private static final class RequestBody implements ReadStream<Buffer> {
        private final HttpServerRequest request;
        private final Runnable onEnd;

        RequestBody(HttpServerRequest request, Runnable onEnd) {
            this.request = request;
            this.onEnd = onEnd;
        }

        @Override
        public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            request.exceptionHandler(handler);
            return this;
        }

        @Override
        public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            request.handler(handler);
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            request.pause();
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            request.resume();
            return this;
        }

        @Override
        public ReadStream<Buffer> fetch(long amount) {
            request.fetch(amount);
            return this;
        }

        @Override
        public ReadStream<Buffer> endHandler(Handler<Void> handler) {
            request.endHandler(end -> {
                onEnd.run();
                if (handler != null) {
                    handler.handle(end);
                }
            });
            return this;
        }
    }

    static boolean hasRequestBody(HttpServerRequest request) {
        String length = request.getHeader("Content-Length");
        return (length != null && !"0".equals(length.trim())) || request.headers().contains("Transfer-Encoding");
    }

    /** The shapes Floci mints, in any case and with any port, so a near miss gets the AWS 404 and not another service. */
    static boolean isLambdaWebHost(String host) {
        return ENDPOINT_DOMAIN.matcher(host).matches();
    }

    /** The app gets the path without dot segments, as on AWS. Repeated slashes and the query stay as sent. */
    static String upstreamUri(HttpServerRequest request) {
        String path = request.path();
        if (path == null) {
            return request.uri();
        }
        String query = request.query();
        return withoutDotSegments(path) + (query == null ? "" : "?" + query);
    }

    /** RFC 3986 section 5.2.4. */
    static String withoutDotSegments(String path) {
        String in = path;
        StringBuilder out = new StringBuilder();
        while (!in.isEmpty()) {
            if (in.startsWith("../")) {
                in = in.substring(3);
            } else if (in.startsWith("./") || in.startsWith("/./")) {
                in = in.substring(2);
            } else if (in.equals("/.")) {
                in = "/";
            } else if (in.startsWith("/../")) {
                in = in.substring(3);
                removeLastSegment(out);
            } else if (in.equals("/..")) {
                in = "/";
                removeLastSegment(out);
            } else if (in.equals(".") || in.equals("..")) {
                in = "";
            } else {
                int next = in.indexOf('/', in.startsWith("/") ? 1 : 0);
                int end = next < 0 ? in.length() : next;
                out.append(in, 0, end);
                in = in.substring(end);
            }
        }
        return out.toString();
    }

    private static void removeLastSegment(StringBuilder out) {
        out.setLength(Math.max(out.lastIndexOf("/"), 0));
    }

    static boolean hasBody(HttpMethod method, int status) {
        return method != HttpMethod.HEAD && status >= 200 && status != 204 && status != 304;
    }

    static void copyRequestHeaders(HttpServerRequest request, HttpClientRequest upstream, String host,
                                   String endpointName, String requestId) {
        Set<String> dropped = new HashSet<>(DROPPED_REQUEST_HEADERS);
        for (String connection : request.headers().getAll("Connection")) {
            for (String token : connection.split(",")) {
                dropped.add(token.trim().toLowerCase(Locale.ROOT));
            }
        }
        // An AWS signature and its session token never reach the app, whatever the auth type.
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("AWS4-")) {
            dropped.add("authorization");
            dropped.add("x-amz-security-token");
        }
        MultiMap headers = upstream.headers();
        for (Map.Entry<String, String> header : request.headers()) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (!dropped.contains(name) && !name.startsWith("lambda-web-")) {
                headers.add(header.getKey(), header.getValue());
            }
        }
        headers.set("Host", host);
        headers.add("X-Forwarded-For", request.remoteAddress() == null ? "" : request.remoteAddress().host());
        headers.set("x-forwarded-proto", request.scheme());
        headers.set("x-forwarded-port", String.valueOf(port(host, request.scheme())));
        headers.set(REQUEST_ID_HEADER, requestId);
        headers.set("lambda-web-endpoint-name", endpointName);
        headers.set("Connection", "close");
    }

    static void copyResponseHeaders(MultiMap headers, HttpServerResponse out) {
        Set<String> dropped = new HashSet<>(DROPPED_RESPONSE_HEADERS);
        for (String connection : headers.getAll("Connection")) {
            for (String token : connection.split(",")) {
                dropped.add(token.trim().toLowerCase(Locale.ROOT));
            }
        }
        for (Map.Entry<String, String> header : headers) {
            if (!dropped.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                out.headers().add(header.getKey(), header.getValue());
            }
        }
    }

    static int port(String host, String scheme) {
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(']') < colon) {
            try {
                return Integer.parseInt(host.substring(colon + 1));
            } catch (NumberFormatException e) {
                return "https".equals(scheme) ? 443 : 80;
            }
        }
        return "https".equals(scheme) ? 443 : 80;
    }

    static String newRequestId() {
        StringBuilder id = new StringBuilder(REQUEST_ID_LENGTH);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < REQUEST_ID_LENGTH; i++) {
            id.append(BASE32.charAt(random.nextInt(BASE32.length())));
        }
        return id.toString();
    }

    static void page(HttpServerRequest request, LambdaWebErrorPages.Page page, String requestId, boolean close) {
        HttpServerResponse response = request.response();
        response.setStatusCode(page.status()).setStatusMessage(page.reason());
        response.headers().set("content-type", "text/html");
        response.headers().set("connection", close ? "close" : "keep-alive");
        response.headers().set(REQUEST_ID_HEADER, requestId);
        if (page.errorType() != null) {
            response.headers().set("lambda-web-error-type", page.errorType());
        }
        response.headers().set("date", HTTP_DATE.format(Instant.now()));
        String html = LambdaWebErrorPages.html(page, requestId);
        // A HEAD request gets the length of the page it does not get, as on AWS.
        response.headers().set("content-length", String.valueOf(html.getBytes(StandardCharsets.UTF_8).length));
        response.end(html).onComplete(ignored -> {
            if (close) {
                response.close();
            }
        });
    }

    /** The edge refuses a method before the platform sees the request, with its own header casing. */
    static void malformed(HttpServerRequest request) {
        edge(request, 400, "Bad Request", LambdaWebErrorPages.MALFORMED_REQUEST_TYPE,
                LambdaWebErrorPages.MALFORMED_REQUEST_HTML);
    }

    private static void edge(HttpServerRequest request, int status, String reason, String errorType, String html) {
        HttpServerResponse response = request.response();
        response.setStatusCode(status).setStatusMessage(reason);
        response.headers().set("Date", LambdaWebErrorPages.edgeDate(Instant.now()));
        response.headers().set("Connection", "close");
        response.headers().set("x-amz-request-id", Long.toHexString(ThreadLocalRandom.current().nextLong())
                .toUpperCase(Locale.ROOT));
        response.headers().set("Content-Type", "text/html; charset=utf-8");
        response.headers().set("Lambda-Web-Error-Type", errorType);
        response.headers().set("Content-Length", String.valueOf(html.getBytes(StandardCharsets.UTF_8).length));
        response.end(html).onComplete(ignored -> response.close());
    }

    private static void uriTooLong(HttpServerRequest request) {
        HttpServerResponse response = request.response();
        response.setStatusCode(414).setStatusMessage("URI Too Long");
        response.headers().set("connection", "close");
        response.headers().set("content-length", "0");
        response.headers().set("date", HTTP_DATE.format(Instant.now()));
        response.end().onComplete(ignored -> response.close());
    }

    /** AWS answers its own failures with 502 and no error type, so nothing is revealed. */
    private static void internalError(HttpServerRequest request, String requestId) {
        HttpServerResponse response = request.response();
        response.setStatusCode(502);
        response.headers().set(REQUEST_ID_HEADER, requestId);
        response.end();
    }
}
