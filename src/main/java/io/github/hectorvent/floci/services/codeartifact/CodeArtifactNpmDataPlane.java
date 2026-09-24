package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.AuthorizationTokenScope;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.net.URI;
import java.util.Optional;
import java.util.Set;

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

    private static final String PREFIX = "/codeartifact/npm/";
    private static final String SERVICE_KEY = "codeartifact";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade", "authorization", "host");

    private final CodeArtifactService service;
    private final VerdaccioSidecarManager verdaccioManager;
    private final ServiceConfigAccess serviceConfigAccess;
    private final EmulatorConfig config;
    private final Vertx vertx;
    private final HttpClient proxyClient;

    @Inject
    public CodeArtifactNpmDataPlane(CodeArtifactService service, VerdaccioSidecarManager verdaccioManager,
                                     ServiceConfigAccess serviceConfigAccess, EmulatorConfig config, Vertx vertx) {
        this.service = service;
        this.verdaccioManager = verdaccioManager;
        this.serviceConfigAccess = serviceConfigAccess;
        this.config = config;
        this.vertx = vertx;
        this.proxyClient = vertx.createHttpClient(new HttpClientOptions()
                .setConnectTimeout(5_000)
                .setKeepAlive(true));
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

        context.request().pause();
        String publicUrl = config.effectiveBaseUrl() + PREFIX + npmRequest.domain() + "/" + npmRequest.repository()
                + "/";
        String repoId = npmRepositoryId;
        vertx.<String>executeBlocking(promise -> promise.complete(verdaccioManager.ensureReady(repoId, publicUrl)))
                .onComplete(result -> {
                    if (result.failed()) {
                        context.request().resume();
                        context.response().setStatusCode(503).end();
                        return;
                    }
                    proxy(context, result.result(), npmRequest.rest() + querySuffix(context));
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
