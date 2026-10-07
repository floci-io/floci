package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.AuthorizationTokenScope;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Proxies the real npm registry protocol through Floci to the per-repository Verdaccio container
 * backing {@code GetRepositoryEndpoint}'s {@code npm} format
 * ({@code /codeartifact/npm/<domain>/<repository>/}). A raw streaming proxy, not a JAX-RS
 * controller, since npm's wire protocol has no fixed path shape (package metadata, scoped
 * packages with an encoded slash, tarball downloads, publish) the way Maven's GAV layout does;
 * Verdaccio already speaks that protocol correctly, so this only needs to authenticate the
 * request and forward it.
 *
 * <p>Real CodeArtifact npm auth is {@code Authorization: Bearer <token>} (npm's own
 * {@code _authToken} config always sends Bearer, unlike Maven's HTTP wagon), so unlike
 * {@code CodeArtifactMavenController} there is no Basic-auth form to also accept here.
 */
@ApplicationScoped
public class CodeArtifactNpmDataPlane {

    private static final Logger LOG = Logger.getLogger(CodeArtifactNpmDataPlane.class);
    private static final String PREFIX = "/codeartifact/npm/";
    private static final String SERVICE_KEY = "codeartifact";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final int MAX_CONCURRENT_PREFLIGHTS = 4;
    private static final int MAX_QUEUED_PREFLIGHTS = 64;
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade", "authorization", "host");

    private final CodeArtifactService service;
    private final VerdaccioSidecarManager verdaccioManager;
    private final ServiceConfigAccess serviceConfigAccess;
    private final Vertx vertx;
    private final HttpClient proxyClient;
    private final ObjectMapper mapper;
    /** Bounds concurrent spooled publishes, which each hold a temp file and a scan in progress. */
    private final PreflightGate preflights = new PreflightGate(MAX_CONCURRENT_PREFLIGHTS, MAX_QUEUED_PREFLIGHTS);

    @Inject
    public CodeArtifactNpmDataPlane(CodeArtifactService service, VerdaccioSidecarManager verdaccioManager,
                                     ServiceConfigAccess serviceConfigAccess, Vertx vertx, ObjectMapper mapper) {
        this.service = service;
        this.verdaccioManager = verdaccioManager;
        this.serviceConfigAccess = serviceConfigAccess;
        this.vertx = vertx;
        this.proxyClient = vertx.createHttpClient(new HttpClientOptions()
                .setConnectTimeout(5_000)
                .setKeepAlive(true));
        this.mapper = mapper;
    }

    void register(@Observes Router router) {
        router.route(PREFIX + "*").handler(this::handle);
    }

    /**
     * A raw Vert.x route, unlike {@code CodeArtifactMavenController}, never passes through
     * {@code ServiceEnabledFilter} (a JAX-RS {@code ContainerRequestFilter}), so a disabled
     * {@code codeartifact} service has to be checked here explicitly or this route would keep
     * serving npm reads and publishes regardless.
     */
    private void handle(RoutingContext context) {
        if (!serviceConfigAccess.isEnabled(SERVICE_KEY)) {
            context.next();
            return;
        }
        Optional<NpmRequest> request = requestFor(context.request().path());
        if (request.isEmpty()) {
            context.next();
            return;
        }
        NpmRequest npmRequest = request.get();

        String token = extractToken(context);
        Optional<AuthorizationTokenScope> scope =
                token == null ? Optional.empty() : service.resolveAuthorizationToken(token, npmRequest.domain());
        if (scope.isEmpty()) {
            context.response().putHeader("WWW-Authenticate", "Bearer").setStatusCode(401).end();
            return;
        }

        String npmRepositoryId;
        try {
            npmRepositoryId = service.ensureFormatContainerId("npm", scope.get().region(), npmRequest.domain(),
                    scope.get().owner(), npmRequest.repository());
        } catch (AwsException e) {
            context.response().setStatusCode(404).end();
            return;
        }

        if (isPublish(context.request().method(), npmRequest.rest())) {
            context.request().pause();
            Context eventLoop = vertx.getOrCreateContext();
            AtomicReference<PreflightGate.Waiter> queued = new AtomicReference<>();
            context.response().closeHandler(ignored -> preflights.abandon(queued.get()));
            PreflightGate.Waiter waiter = preflights.acquire(() -> eventLoop.runOnContext(ignored ->
                    handlePublish(context, npmRequest, npmRepositoryId)));
            if (waiter == null) {
                context.request().resume();
                context.response().putHeader("Retry-After", "1").setStatusCode(429).end();
                return;
            }
            queued.set(waiter);
            return;
        }

        context.request().pause();
        String repoId = npmRepositoryId;
        vertx.<String>executeBlocking(promise -> promise.complete(ensureBackendReady(repoId, npmRequest)))
                .onComplete(result -> {
                    if (result.failed()) {
                        context.request().resume();
                        context.response().setStatusCode(503).end();
                        return;
                    }
                    proxy(context, result.result(), npmRequest.rest() + querySuffix(context));
                });
    }

    /**
     * A real {@code npm publish} is a {@code PUT} to the bare package path (no further path
     * segments): {@code /<name>} unscoped, or {@code /@scope%2Fname} scoped, still one segment
     * since the slash between scope and name arrives URL-encoded. {@code npm deprecate} is a
     * {@code PUT} at this same bare-package shape (a dist-tag update is not: that always goes to
     * {@code /-/package/<name>/dist-tags/<tag>}, a different, multi-segment path {@link #isPublish}
     * already excludes), but {@link #confirmedRepublish} only ever short-circuits when it can
     * positively confirm every version in the body already exists with byte-identical content, so
     * a deprecate call (which carries no new {@code _attachments}) falls through to the normal
     * proxy path unaffected.
     */
    private static boolean isPublish(HttpMethod method, String rest) {
        return method == HttpMethod.PUT && rest.length() > 1 && rest.indexOf('/', 1) < 0;
    }

    /**
     * Real npm registries and the pinned Verdaccio image both reject a republish of an existing
     * version with 409, even for byte-identical content. The AWS CodeArtifact User Guide states the
     * opposite for package assets in general: republishing an existing asset with identical content
     * succeeds because the operation is idempotent. This applies that rule to npm, which has one
     * tarball asset per version. The envelope is spooled to disk in bounded chunks and scanned as a
     * stream, so its size never costs heap. Only when every version and dist-tag matches does this
     * answer 200 without reaching Verdaccio; anything else forwards the spooled body unchanged.
     */
    private void handlePublish(RoutingContext context, NpmRequest npmRequest, String npmRepositoryId) {
        AtomicBoolean finished = new AtomicBoolean();
        AtomicReference<String> spool = new AtomicReference<>();
        AtomicReference<AsyncFile> reading = new AtomicReference<>();
        Runnable finish = () -> {
            if (finished.compareAndSet(false, true)) {
                if (reading.get() != null) {
                    reading.get().close();
                }
                if (spool.get() != null) {
                    vertx.fileSystem().delete(spool.get());
                }
                preflights.release();
            }
        };
        if (context.response().ended() || context.response().closed()) {
            finish.run();
            return;
        }
        vertx.fileSystem().createTempFile("npm-publish-", ".json").onComplete(created -> {
            if (created.failed()) {
                finish.run();
                respondUnlessGone(context, 500);
                return;
            }
            String path = created.result();
            spool.set(path);
            if (finished.get()) {
                vertx.fileSystem().delete(path);
                return;
            }
            vertx.fileSystem().open(path, new OpenOptions().setWrite(true)).onComplete(opened -> {
                if (opened.failed()) {
                    finish.run();
                    respondUnlessGone(context, 500);
                    return;
                }
                context.request().pipeTo(opened.result()).onComplete(piped -> {
                    if (piped.failed()) {
                        finish.run();
                        respondUnlessGone(context, 400);
                        return;
                    }
                    vertx.<String>executeBlocking(promise ->
                                    promise.complete(ensureBackendReady(npmRepositoryId, npmRequest)), false)
                            .onComplete(readyResult -> {
                                if (readyResult.failed()) {
                                    finish.run();
                                    respondUnlessGone(context, 503);
                                    return;
                                }
                                String backendBaseUrl = readyResult.result();
                                vertx.<Boolean>executeBlocking(promise ->
                                                promise.complete(confirmedRepublish(backendBaseUrl, npmRequest, path)),
                                                false)
                                        .onComplete(matchResult -> {
                                            if (context.response().closed()) {
                                                finish.run();
                                                return;
                                            }
                                            if (matchResult.succeeded() && matchResult.result()) {
                                                // "ok" is a message string in a real Verdaccio response
                                                // (e.g. "created new package"), not a boolean; matching
                                                // that shape in case any client inspects it for display
                                                // rather than just checking truthiness.
                                                context.response().putHeader("Content-Type", "application/json")
                                                        .setStatusCode(200)
                                                        .end("{\"ok\":\"accepted, content already published\","
                                                                + "\"success\":true}");
                                                finish.run();
                                                return;
                                            }
                                            vertx.fileSystem().open(path, new OpenOptions().setRead(true))
                                                    .onComplete(reopened -> {
                                                        if (reopened.failed()) {
                                                            finish.run();
                                                            respondUnlessGone(context, 500);
                                                            return;
                                                        }
                                                        reading.set(reopened.result());
                                                        context.addEndHandler(ended -> finish.run());
                                                        context.response().closeHandler(ignored -> finish.run());
                                                        forwardSpooled(context, backendBaseUrl,
                                                                npmRequest.rest() + querySuffix(context),
                                                                reopened.result());
                                                    });
                                        });
                            });
                });
            });
        });
    }

    private static void respondUnlessGone(RoutingContext context, int status) {
        if (!context.response().ended() && !context.response().closed()) {
            context.response().setStatusCode(status).end();
        }
    }

    /**
     * Admits at most {@code permits} publish preflights at once. Waiters queue here rather than
     * blocking a worker thread, and each one resumes on the event loop it arrived on.
     */
    private static final class PreflightGate {
        private final int maxQueued;
        private final Deque<Waiter> waiting = new ArrayDeque<>();
        private int available;

        PreflightGate(int permits, int maxQueued) {
            this.available = permits;
            this.maxQueued = maxQueued;
        }

        /** The waiter to abandon if the client leaves; {@code null} when the queue is already full. */
        Waiter acquire(Runnable onAcquired) {
            Waiter waiter = new Waiter(onAcquired);
            synchronized (this) {
                if (available > 0) {
                    available--;
                } else if (waiting.size() < maxQueued) {
                    waiting.add(waiter);
                    return waiter;
                } else {
                    return null;
                }
            }
            onAcquired.run();
            return waiter;
        }

        void abandon(Waiter waiter) {
            if (waiter == null) {
                return;
            }
            synchronized (this) {
                waiting.remove(waiter);
            }
        }

        void release() {
            Waiter next;
            synchronized (this) {
                next = waiting.poll();
                if (next == null) {
                    available++;
                    return;
                }
            }
            next.onAcquired.run();
        }

        private static final class Waiter {
            private final Runnable onAcquired;

            Waiter(Runnable onAcquired) {
                this.onAcquired = onAcquired;
            }
        }
    }

    private String ensureBackendReady(String npmRepositoryId, NpmRequest npmRequest) {
        return verdaccioManager.ensureReady(npmRepositoryId,
                verdaccioManager.publicUrl(npmRequest.domain(), npmRequest.repository()));
    }

    /**
     * {@code true} only when every version in the publish envelope already exists on Verdaccio with
     * the same integrity, every dist-tag the envelope sends already points where it says, and every
     * attachment in the envelope is one of those versions' tarballs. Client bytes are checked against
     * the integrity the client itself claims, so a mismatch between the two falls through too.
     * Anything unreadable or inconsistent is not confirmable and forwards unchanged.
     */
    private boolean confirmedRepublish(String backendBaseUrl, NpmRequest npmRequest, String spoolPath) {
        PackageIdentity identity = packageIdentityFromPath(npmRequest.rest());
        NpmPublishEnvelopeScanner.Envelope envelope;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(Path.of(spoolPath)))) {
            envelope = NpmPublishEnvelopeScanner.scan(in);
        } catch (IOException e) {
            LOG.debugv(e, "Publish for {0}/{1} is not a confirmable republish: {2}",
                    npmRequest.domain(), npmRequest.repository(), e.getMessage());
            return false;
        }
        Optional<JsonNode> storedDocument;
        try {
            storedDocument = verdaccioManager.fetchPackageDocument(backendBaseUrl, identity.namespace(),
                    identity.packageName());
        } catch (IllegalStateException e) {
            LOG.warnv(e, "Could not check {0}/{1} for an existing match before publishing: {2}",
                    npmRequest.domain(), npmRequest.repository(), e.getMessage());
            return false;
        }
        if (storedDocument.isEmpty() || !isConfirmedRepublish(envelope, storedDocument.get(), identity)) {
            return false;
        }
        for (NpmPublishEnvelopeScanner.Version version : envelope.versions().values()) {
            try {
                Optional<String> stored = verdaccioManager.storedTarballIntegrity(backendBaseUrl,
                        identity.namespace(), identity.packageName(), tarballFilename(version.tarball()));
                if (stored.isEmpty() || !stored.get().equals(version.integrity())) {
                    return false;
                }
            } catch (IllegalStateException e) {
                LOG.warnv(e, "Could not verify the stored tarball of {0}/{1} before publishing: {2}",
                        npmRequest.domain(), npmRequest.repository(), e.getMessage());
                return false;
            }
        }
        return true;
    }

    private static boolean isConfirmedRepublish(NpmPublishEnvelopeScanner.Envelope envelope, JsonNode stored,
            PackageIdentity identity) {
        if (envelope.name() != null && !envelope.name().equals(identity.fullName())) {
            return false;
        }
        if (envelope.versions().isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> tag : envelope.distTags().entrySet()) {
            JsonNode storedTag = stored.path("dist-tags").get(tag.getKey());
            if (storedTag == null || !storedTag.asText("").equals(tag.getValue())) {
                return false;
            }
        }
        Set<String> tarballs = new HashSet<>();
        for (Map.Entry<String, NpmPublishEnvelopeScanner.Version> entry : envelope.versions().entrySet()) {
            NpmPublishEnvelopeScanner.Version sent = entry.getValue();
            if (sent.tarball() == null || sent.integrity() == null) {
                return false;
            }
            String filename = tarballFilename(sent.tarball());
            JsonNode storedVersion = stored.path("versions").get(entry.getKey());
            if (storedVersion == null
                    || !filename.equals(tarballFilename(storedVersion.path("dist").path("tarball").asText("")))
                    || !sent.integrity().equals(storedVersion.path("dist").path("integrity").asText(""))) {
                return false;
            }
            if (!sent.integrity().equals(envelope.attachmentIntegrity().get(filename))) {
                return false;
            }
            tarballs.add(filename);
        }
        for (String attachment : envelope.attachmentIntegrity().keySet()) {
            if (!tarballs.contains(attachment)) {
                return false;
            }
        }
        return true;
    }

    private static String tarballFilename(String tarballUrl) {
        return tarballUrl.substring(tarballUrl.lastIndexOf('/') + 1);
    }

    /**
     * {@code namespace} is the npm scope without its leading {@code @}, or {@code null} for an
     * unscoped package, the same shape {@link RepositorySidecarManager#fetchPackageVersionAsset}
     * takes everywhere else. {@code rest} is a bare-package publish path, exactly one segment
     * ({@link #isPublish} already confirmed that): {@code /<name>} unscoped, or {@code
     * /@scope%2Fname} scoped, with the scope separator arriving percent-encoded since Netty
     * leaves a {@code %2F} undecoded in {@link HttpServerRequest#path()}
     * specifically so it is never confused with a real path separator. The one, targeted decode
     * here undoes exactly that, and only that: every other npm package-name character is already
     * unreserved and needs no decoding.
     */
    private static PackageIdentity packageIdentityFromPath(String rest) {
        String decoded = rest.substring(1).replace("%2F", "/").replace("%2f", "/");
        if (decoded.startsWith("@")) {
            int slash = decoded.indexOf('/');
            if (slash > 0) {
                return new PackageIdentity(decoded.substring(1, slash), decoded.substring(slash + 1));
            }
        }
        return new PackageIdentity(null, decoded);
    }

    private record PackageIdentity(String namespace, String packageName) {
        String fullName() {
            return namespace == null ? packageName : "@" + namespace + "/" + packageName;
        }
    }

    private void forwardSpooled(RoutingContext context, String backendBaseUrl, String backendPath,
            ReadStream<Buffer> body) {
        URI backend = URI.create(backendBaseUrl);
        RequestOptions options = new RequestOptions()
                .setHost(backend.getHost())
                .setPort(backend.getPort())
                .setURI(backendPath)
                .setMethod(context.request().method());
        proxyClient.request(options).onComplete(upstreamRequest -> {
            if (upstreamRequest.failed()) {
                respondUnlessGone(context, 503);
                return;
            }
            HttpClientRequest clientReq = upstreamRequest.result();
            copyRequestHeaders(context, clientReq);
            clientReq.response().onComplete(upstreamResponse -> {
                if (upstreamResponse.failed()) {
                    respondUnlessGone(context, 502);
                    return;
                }
                copyResponseHeaders(context, upstreamResponse.result());
                upstreamResponse.result().pipeTo(context.response());
            });
            clientReq.send(body).onFailure(ignored -> respondUnlessGone(context, 502));
        });
    }

    private void proxy(RoutingContext context, String backendBaseUrl, String backendPath) {
        URI backend = URI.create(backendBaseUrl);
        RequestOptions options = new RequestOptions()
                .setHost(backend.getHost())
                .setPort(backend.getPort())
                .setURI(backendPath)
                .setMethod(context.request().method());
        proxyClient.request(options).onComplete(upstreamRequest -> {
            if (upstreamRequest.failed()) {
                context.request().resume();
                context.response().setStatusCode(503).end();
                return;
            }
            HttpClientRequest clientReq = upstreamRequest.result();
            copyRequestHeaders(context, clientReq);
            clientReq.response().onComplete(upstreamResponse -> {
                if (upstreamResponse.failed()) {
                    if (!context.response().ended()) {
                        context.response().setStatusCode(502).end();
                    }
                    return;
                }
                copyResponseHeaders(context, upstreamResponse.result());
                upstreamResponse.result().pipeTo(context.response());
            });
            clientReq.send(context.request()).onFailure(ignored -> {
                if (!context.response().ended()) {
                    context.response().setStatusCode(502).end();
                }
            });
            context.request().resume();
        });
    }

    private static void copyRequestHeaders(RoutingContext context, HttpClientRequest upstream) {
        context.request().headers().forEach(header -> {
            if (!HOP_BY_HOP_HEADERS.contains(header.getKey().toLowerCase())) {
                upstream.putHeader(header.getKey(), header.getValue());
            }
        });
        if (context.request().getHeader("Content-Length") == null
                && ("chunked".equalsIgnoreCase(context.request().getHeader("Transfer-Encoding"))
                || context.request().method() == HttpMethod.POST
                || context.request().method() == HttpMethod.PUT
                || context.request().method() == HttpMethod.PATCH)) {
            upstream.setChunked(true);
        }
    }

    /**
     * Skips {@code Content-Length} and always streams chunked, rather than copying the upstream
     * length through: Quarkus's own response filters can commit this response to chunked framing
     * before this handler ever runs, and a Content-Length header set after that point makes Vert.x
     * throw on the first {@code pipeTo} write instead of silently doing the right thing.
     */
    private static void copyResponseHeaders(RoutingContext context, HttpClientResponse upstream) {
        context.response().setStatusCode(upstream.statusCode());
        upstream.headers().forEach(header -> {
            if (!HOP_BY_HOP_HEADERS.contains(header.getKey().toLowerCase())
                    && !"content-length".equalsIgnoreCase(header.getKey())) {
                context.response().putHeader(header.getKey(), header.getValue());
            }
        });
        context.response().setChunked(true);
    }

    private static String extractToken(RoutingContext context) {
        String authorization = context.request().getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return authorization.substring(BEARER_PREFIX.length());
    }

    private static String querySuffix(RoutingContext context) {
        String query = context.request().query();
        return query == null || query.isEmpty() ? "" : "?" + query;
    }

    static Optional<NpmRequest> requestFor(String path) {
        if (path == null || !path.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String remainder = path.substring(PREFIX.length());
        String[] segments = remainder.split("/", 3);
        if (segments.length < 2 || segments[0].isEmpty() || segments[1].isEmpty()) {
            return Optional.empty();
        }
        String rest = segments.length == 3 ? "/" + segments[2] : "/";
        return Optional.of(new NpmRequest(segments[0], segments[1], rest));
    }

    record NpmRequest(String domain, String repository, String rest) {}
}
