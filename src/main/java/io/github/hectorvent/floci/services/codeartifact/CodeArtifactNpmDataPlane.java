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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

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
    private final VerdaccioSidecarClient verdaccioClient;
    private final ServiceConfigAccess serviceConfigAccess;
    private final Vertx vertx;
    private final HttpClient proxyClient;
    private final ObjectMapper mapper;
    /** Bounds concurrent spooled publishes, which each hold a temp file and a scan in progress. */
    private final PreflightGate preflights = new PreflightGate(MAX_CONCURRENT_PREFLIGHTS, MAX_QUEUED_PREFLIGHTS);

    @Inject
    public CodeArtifactNpmDataPlane(CodeArtifactService service, VerdaccioSidecarClient verdaccioClient,
                                     ServiceConfigAccess serviceConfigAccess, Vertx vertx, ObjectMapper mapper) {
        this.service = service;
        this.verdaccioClient = verdaccioClient;
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
                    handlePublish(context, npmRequest, npmRepositoryId, scope.get())));
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
        AuthorizationTokenScope tokenScope = scope.get();
        Optional<PackageIdentity> unpublishTarget = nativeUnpublishTarget(context.request().method(),
                npmRequest.rest());
        vertx.<String>executeBlocking(promise -> promise.complete(ensureBackendReady(repoId, npmRequest)))
                .onComplete(result -> {
                    if (result.failed()) {
                        context.request().resume();
                        context.response().setStatusCode(503).end();
                        return;
                    }
                    proxy(context, result.result(), npmRequest.rest() + querySuffix(context), status ->
                            unpublishTarget.ifPresent(identity -> {
                                if (status == 200 || status == 201) {
                                    service.forgetContainerBackedPackage(tokenScope.region(), npmRequest.domain(),
                                            tokenScope.owner(), npmRequest.repository(), "npm", identity.namespace(),
                                            identity.packageName());
                                }
                            }));
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
     * A real {@code npm unpublish <pkg> --force} (whole package, not one version) is a
     * {@code DELETE} straight to {@code <packagePath>/-rev/<revision>}, the exact mechanism
     * {@code VerdaccioSidecarClient.deletePackage} itself uses internally. The pinned Verdaccio
     * image is configured with {@code unpublish: $all}, so it honors this natively; this proxy
     * only ever forwards it (see {@link #handle}'s own fallback to {@link #proxy}), with no path
     * through {@link CodeArtifactService#deletePackage} at all. Detected here purely so that same
     * fallback can still clean up this service's own accounting afterward, via
     * {@link CodeArtifactService#forgetContainerBackedPackage}, once Verdaccio's response
     * confirms the delete actually happened.
     *
     * <p>Unpublishing a single version of a multi-version package is a different shape, not
     * handled here or anywhere else in this proxy: confirmed directly against npm/cli's own
     * {@code libnpmpublish/lib/unpublish.js} source (not assumed, and not what an earlier version
     * of this comment claimed) that it is a {@code PUT} to this exact same {@code /-rev/<revision>}
     * path this method already matches, carrying the packument with that one version's entry, its
     * now-dangling dist-tags, and {@code _attachments}/{@code _revisions} removed — a metadata-only
     * rewrite of an existing package, not a publish, and a different operation from the real
     * {@code npm deprecate}, which PUTs to the bare package path instead (confirmed the same way).
     * Left unhandled deliberately for this method's whole-package cleanup — {@link
     * CodeArtifactService#forgetContainerBackedPackage} would incorrectly wipe every surviving
     * version's own accounting and readme for a PUT that only ever removes one of several — and
     * for {@code GetPackageVersionReadme}: a real fix needs its own narrower, per-version cleanup
     * fed by this PUT's own request body (the resulting version set), not this DELETE-shaped
     * signal, and is scoped out of this change rather than hand-built without that plumbing. This
     * service's own byte accounting is per-package, not per-version, so there is no narrower count
     * a single version's removal could subtract from there either way.
     */
    private static Optional<PackageIdentity> nativeUnpublishTarget(HttpMethod method, String rest) {
        if (method != HttpMethod.DELETE) {
            return Optional.empty();
        }
        int revIndex = rest.indexOf("/-rev/");
        if (revIndex < 1) {
            return Optional.empty();
        }
        return Optional.of(packageIdentityFromPath(rest.substring(0, revIndex)));
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
    private void handlePublish(RoutingContext context, NpmRequest npmRequest, String npmRepositoryId,
            AuthorizationTokenScope scope) {
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
                                vertx.<RepublishCheck>executeBlocking(promise ->
                                                promise.complete(checkRepublish(backendBaseUrl, npmRequest, path)),
                                                false)
                                        .onComplete(checkResult -> {
                                            if (context.response().closed()) {
                                                finish.run();
                                                return;
                                            }
                                            RepublishCheck check = checkResult.succeeded() ? checkResult.result()
                                                    : new RepublishCheck(false, 0L,
                                                            packageIdentityFromPath(npmRequest.rest()), Map.of());
                                            if (check.confirmed()) {
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
                                            // Snapshotted before the real write (the forwarded
                                            // request below hasn't even been issued yet), not
                                            // after: see CodeArtifactService#
                                            // currentContainerBackedPackageFence's own javadoc for
                                            // why the order matters, and why npm needs it most of
                                            // all three formats, with this call's own response
                                            // arriving on a delayed callback rather than inline.
                                            long fenceSnapshot = service.currentContainerBackedPackageFence(
                                                    scope.region(), npmRequest.domain(), scope.owner(),
                                                    npmRequest.repository(), "npm", check.identity().namespace(),
                                                    check.identity().packageName());
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
                                                                reopened.result(), scope, npmRequest.domain(),
                                                                npmRequest.repository(), check.identity(),
                                                                check.totalAttachmentBytes(), fenceSnapshot,
                                                                check.readmeByVersion());
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

    /** Same guard as the status-only overload, for a response whose status/headers are already set. */
    private static void respondUnlessGone(RoutingContext context, Buffer body) {
        if (!context.response().ended() && !context.response().closed()) {
            context.response().end(body);
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
        return verdaccioClient.ensureReady(npmRepositoryId,
                verdaccioClient.publicUrl(npmRequest.domain(), npmRequest.repository()));
    }

    /**
     * {@code confirmed} is {@code true} only when every version in the publish envelope already
     * exists on Verdaccio with the same integrity, every dist-tag the envelope sends already
     * points where it says, and every attachment in the envelope is one of those versions'
     * tarballs. Client bytes are checked against the integrity the client itself claims, so a
     * mismatch between the two falls through too. Anything unreadable or inconsistent is not
     * confirmable and forwards unchanged. {@code totalAttachmentBytes} is the real decoded byte
     * count of every attachment the envelope carries, regardless of {@code confirmed}: the caller
     * only ever uses it when forwarding an unconfirmed publish actually succeeds, since a
     * confirmed republish writes no new bytes at all. {@code readmeByVersion} is populated the
     * same way, regardless of {@code confirmed} (a real envelope's own per-version {@code readme}
     * field, see {@link NpmPublishEnvelopeScanner.Version} for why this, not Verdaccio's own
     * packument response, is the only place it is ever available), and the caller only ever
     * records it on that same successful-forward path: a confirmed republish writes nothing new,
     * so there is nothing to capture a readme change for either. Every version in the envelope
     * gets an entry here, even one whose {@code readme} field came back {@code null} (missing, or
     * of a shape {@link NpmPublishEnvelopeScanner} ignores): {@link
     * CodeArtifactService#recordPublishedPackageVersionReadme} already turns a {@code null} readme
     * into {@code ""}, the same empty-string answer pypi's own upload path already records when
     * twine sends no {@code description} field, so a published version without a readme is never
     * mistaken for one that was never published at all.
     */
    private record RepublishCheck(boolean confirmed, long totalAttachmentBytes, PackageIdentity identity,
            Map<String, String> readmeByVersion) {}

    private RepublishCheck checkRepublish(String backendBaseUrl, NpmRequest npmRequest, String spoolPath) {
        PackageIdentity identity = packageIdentityFromPath(npmRequest.rest());
        NpmPublishEnvelopeScanner.Envelope envelope;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(Path.of(spoolPath)))) {
            envelope = NpmPublishEnvelopeScanner.scan(in);
        } catch (IOException e) {
            LOG.debugv(e, "Publish for {0}/{1} is not a confirmable republish: {2}",
                    npmRequest.domain(), npmRequest.repository(), e.getMessage());
            return new RepublishCheck(false, 0L, identity, Map.of());
        }
        long totalAttachmentBytes = envelope.attachments().values().stream()
                .mapToLong(NpmPublishEnvelopeScanner.Attachment::size)
                .sum();
        Map<String, String> readmeByVersion = new HashMap<>();
        envelope.versions().forEach((version, v) -> readmeByVersion.put(version, v.readme()));
        Optional<JsonNode> storedDocument;
        try {
            storedDocument = verdaccioClient.fetchPackageDocument(backendBaseUrl, identity.namespace(),
                    identity.packageName());
        } catch (IllegalStateException e) {
            LOG.warnv(e, "Could not check {0}/{1} for an existing match before publishing: {2}",
                    npmRequest.domain(), npmRequest.repository(), e.getMessage());
            return new RepublishCheck(false, totalAttachmentBytes, identity, readmeByVersion);
        }
        if (storedDocument.isEmpty() || !isConfirmedRepublish(envelope, storedDocument.get(), identity)) {
            return new RepublishCheck(false, totalAttachmentBytes, identity, readmeByVersion);
        }
        for (NpmPublishEnvelopeScanner.Version version : envelope.versions().values()) {
            try {
                Optional<String> stored = verdaccioClient.storedTarballIntegrity(backendBaseUrl,
                        identity.namespace(), identity.packageName(), tarballFilename(version.tarball()));
                if (stored.isEmpty() || !stored.get().equals(version.integrity())) {
                    return new RepublishCheck(false, totalAttachmentBytes, identity, readmeByVersion);
                }
            } catch (IllegalStateException e) {
                LOG.warnv(e, "Could not verify the stored tarball of {0}/{1} before publishing: {2}",
                        npmRequest.domain(), npmRequest.repository(), e.getMessage());
                return new RepublishCheck(false, totalAttachmentBytes, identity, readmeByVersion);
            }
        }
        return new RepublishCheck(true, totalAttachmentBytes, identity, readmeByVersion);
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
            NpmPublishEnvelopeScanner.Attachment attachment = envelope.attachments().get(filename);
            if (attachment == null || !sent.integrity().equals(attachment.integrity())) {
                return false;
            }
            tarballs.add(filename);
        }
        for (String attachment : envelope.attachments().keySet()) {
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

    /**
     * {@code identity}/{@code totalAttachmentBytes}/{@code readmeByVersion} are only ever recorded
     * once Verdaccio's own response confirms the publish actually succeeded (200 or 201): a
     * forward that Verdaccio itself rejects (409 on a genuine conflict, or anything else) writes
     * nothing, and must not be counted or recorded as though it had. {@code totalAttachmentBytes}
     * is already 0, and {@code readmeByVersion} already empty,
     * when the envelope couldn't be parsed (see {@link #checkRepublish}), so both become no-ops
     * in that case regardless of what Verdaccio answers. {@code fenceSnapshot} is whatever
     * {@link CodeArtifactService#currentContainerBackedPackageFence} returned at the caller's own
     * earlier snapshot point, before this request was ever forwarded.
     *
     * <p>All of this bookkeeping runs in one {@code executeBlocking} call, and the response is
     * only piped to the client once that call completes, not before: a client that requests this
     * version's readme immediately after seeing its publish succeed must not be able to observe a
     * window where the publish already finished but the readme has not been recorded yet.
     */
    private void forwardSpooled(RoutingContext context, String backendBaseUrl, String backendPath,
            ReadStream<Buffer> body, AuthorizationTokenScope scope, String domain, String repository,
            PackageIdentity identity, long totalAttachmentBytes, long fenceSnapshot,
            Map<String, String> readmeByVersion) {
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
                HttpClientResponse response = upstreamResponse.result();
                copyResponseHeaders(context, response);
                // response.body() attaches its handler on this same event-loop tick, same as
                // pipeTo used to: a publish response is always a small JSON acknowledgement, never
                // tarball-sized, so buffering it rather than streaming it costs nothing. Unlike
                // pipeTo, buffering lets the client-visible response wait on the bookkeeping below
                // without risking the response stream auto-ending for having no consumer attached
                // in time (confirmed the hard way: deferring pipeTo itself past this tick threw
                // "Response already ended").
                response.body().onComplete(bodyResult -> {
                    if (bodyResult.failed()) {
                        respondUnlessGone(context, 502);
                        return;
                    }
                    Buffer responseBody = bodyResult.result();
                    boolean forwardSucceeded = response.statusCode() == 200 || response.statusCode() == 201;
                    boolean needsBytes = totalAttachmentBytes > 0 && forwardSucceeded;
                    boolean needsReadmeCapture = !readmeByVersion.isEmpty() && forwardSucceeded;
                    if (!needsBytes && !needsReadmeCapture) {
                        respondUnlessGone(context, responseBody);
                        return;
                    }
                    // Each still runs off the event loop, in executeBlocking, on principle:
                    // CodeArtifactService is a plain CDI bean Vert.x knows nothing about, so
                    // nothing here can guarantee it stays that way forever, and the cost of
                    // getting this wrong (stalling every request on this event loop, not just this
                    // one) is high enough that "it happens to be cheap today" is not a reason to
                    // call a synchronized service method straight from a response callback.
                    vertx.<Void>executeBlocking(promise -> {
                        if (needsBytes) {
                            service.recordContainerBackedAssetBytes(scope.region(), domain, scope.owner(),
                                    repository, "npm", identity.namespace(), identity.packageName(),
                                    totalAttachmentBytes, fenceSnapshot);
                        }
                        if (needsReadmeCapture) {
                            readmeByVersion.forEach((version, readme) ->
                                    service.recordPublishedPackageVersionReadme(scope.region(), domain,
                                            scope.owner(), repository, "npm", identity.namespace(),
                                            identity.packageName(), version, readme, fenceSnapshot));
                        }
                        promise.complete();
                    }, false).onComplete(result -> {
                        if (result.failed()) {
                            LOG.warnv(result.cause(), "Could not record npm publish bookkeeping for {0}/{1} "
                                    + "package ''{2}'': {3}", domain, repository, identity.packageName(),
                                    result.cause().getMessage());
                        }
                        respondUnlessGone(context, responseBody);
                    });
                });
            });
            clientReq.send(body).onFailure(ignored -> respondUnlessGone(context, 502));
        });
    }

    private void proxy(RoutingContext context, String backendBaseUrl, String backendPath) {
        proxy(context, backendBaseUrl, backendPath, status -> { });
    }

    /**
     * {@code onSuccessStatus} runs only once Verdaccio's own response is fully known, for a
     * caller that needs to react to what it actually was (a native {@code npm unpublish}
     * cleaning up this service's own accounting only once Verdaccio really did remove the
     * package, not before); every other caller passes a no-op.
     */
    private void proxy(RoutingContext context, String backendBaseUrl, String backendPath,
            IntConsumer onSuccessStatus) {
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
                HttpClientResponse response = upstreamResponse.result();
                copyResponseHeaders(context, response);
                response.pipeTo(context.response());
                onSuccessStatus.accept(response.statusCode());
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
