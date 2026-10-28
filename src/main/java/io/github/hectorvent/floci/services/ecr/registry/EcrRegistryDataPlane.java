package io.github.hectorvent.floci.services.ecr.registry;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.NetworkExposureGuard;
import io.github.hectorvent.floci.services.ecr.EcrService;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.HostAndPort;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Proxies OCI Distribution traffic through Floci so ECR repository policy applies to Docker pushes.
 * Docker clients use the AWS-shaped repository URI while {@code registry:2} retains image storage.
 */
@ApplicationScoped
public class EcrRegistryDataPlane {

    private static final Pattern ECR_HOST = Pattern.compile(
            "^([0-9]{12})\\.dkr\\.ecr\\.([a-z0-9-]+)\\.localhost(?:\\.floci\\.io)?(?::[0-9]+)?$");
    private static final String MANIFESTS_PATH = "/manifests/";
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade");

    private final EcrRegistryManager registryManager;
    private final EcrService ecrService;
    private final EmulatorConfig config;
    private final Vertx vertx;

    @Inject
    public EcrRegistryDataPlane(EcrRegistryManager registryManager,
                                EcrService ecrService,
                                EmulatorConfig config,
                                Vertx vertx) {
        this.registryManager = registryManager;
        this.ecrService = ecrService;
        this.config = config;
        this.vertx = vertx;
    }

    void register(@Observes Router router) {
        router.route("/v2").handler(this::handle);
        router.route("/v2/*").handler(this::handle);
    }

    private void handle(RoutingContext context) {
        Optional<RegistryRequest> request = requestFor(
                context.request().host(), context.request().path(), context.request().query(),
                config.services().ecr().uriStyle());
        if (request.isEmpty() || !config.services().ecr().enabled()) {
            context.next();
            return;
        }

        context.request().pause();

        RegistryRequest registryRequest = request.get();
        if (!registryRequest.repositoryName().isEmpty()) {
            registryRequest = registryRequest.withStorageRepositoryName(
                    ecrService.registryRepositoryName(
                            registryRequest.repositoryName(), registryRequest.accountId(), registryRequest.region()));
        }
        String immutableTag = immutableTag(context, registryRequest);
        if (immutableTag != null && !ecrService.claimImmutableTag(immutableTag)) {
            context.request().resume();
            imageTagAlreadyExists(context, registryRequest);
            return;
        }

        Exchange exchange = new Exchange(immutableTag);
        // A client that goes away, by resetting its HTTP/2 stream or closing the connection, ends
        // the exchange at any point, the registry check included: otherwise the registry keeps
        // waiting for the rest of a body with a Content-Length, and the exchange's connection,
        // client and immutable tag lock stay.
        context.response().closeHandler(closed -> exchange.end());
        context.request().exceptionHandler(failure -> exchange.end());
        checkTagAndProxy(context, registryRequest, exchange);
    }

    private String immutableTag(RoutingContext context, RegistryRequest request) {
        if (request.tag() == null || !"PUT".equals(context.request().method().name())
                || !ecrService.isImageTagImmutable(request.repositoryName(), request.accountId(), request.region())) {
            return null;
        }
        return EcrService.immutableTagKey(request.accountId(), request.region(), request.repositoryName(), request.tag());
    }

    private void checkTagAndProxy(RoutingContext context, RegistryRequest request, Exchange exchange) {
        String immutableTag = exchange.immutableTag;
        vertx.<Boolean>executeBlocking(promise -> {
            try {
                registryManager.ensureStarted();
                if (immutableTag == null) {
                    promise.complete(false);
                    return;
                }
                String existing = registryManager.httpClient().headManifestDigest(
                        request.storageRepositoryName(),
                        request.tag(), null);
                promise.complete(existing != null);
            } catch (Exception e) {
                promise.fail(e);
            }
        }).onComplete(result -> {
            if (exchange.ended()) {
                // The client went away during the check: nothing of this exchange reaches the
                // registry.
                return;
            }
            if (result.failed()) {
                context.request().resume();
                exchange.end();
                registryUnavailable(context);
            } else if (result.result()) {
                context.request().resume();
                exchange.end();
                imageTagAlreadyExists(context, request);
            } else {
                proxy(context, request, exchange);
            }
        });
    }

    private void proxy(RoutingContext context, RegistryRequest request, Exchange exchange) {
        URI backend = URI.create(registryManager.httpClient().baseUrl());
        RequestOptions options = new RequestOptions()
                .setHost(backend.getHost())
                .setPort(backend.getPort() == -1 ? 80 : backend.getPort())
                .setURI(request.backendUri())
                .setMethod(context.request().method());
        // A registry connection of its own for every exchange, never a place in a shared pool's
        // queue. An HTTP/2 client such as crane sends a whole push as streams on one connection,
        // so a pool of any size can be full of its uploads while another of its streams waits,
        // paused. The waiting stream's unread bytes hold that connection's flow-control window,
        // the uploads that do have a registry connection stop receiving data, and none of them
        // ever gives its connection back.
        HttpClient client = exchange.open(vertx.createHttpClient(new HttpClientOptions().setConnectTimeout(5_000)));
        client.request(options).onComplete(upstreamRequest -> {
            if (upstreamRequest.failed()) {
                context.request().resume();
                exchange.end();
                registryUnavailable(context);
                return;
            }
            HttpClientRequest clientReq = upstreamRequest.result();
            copyRequestHeaders(context, clientReq);
            clientReq.response().onComplete(upstreamResponse -> {
                if (upstreamResponse.failed()) {
                    exchange.end();
                    failUnlessGone(context, 502);
                    return;
                }
                copyResponseHeaders(context, upstreamResponse.result(), request);
                upstreamResponse.result().pipeTo(context.response()).onComplete(ignored -> exchange.end());
            });
            clientReq.send(context.request()).onFailure(ignored -> {
                exchange.end();
                failUnlessGone(context, 502);
            });
            context.request().resume();
        });
    }

    private static void registryUnavailable(RoutingContext context) {
        if (!context.response().ended() && !context.response().closed()) {
            String message = "ECR registry is unavailable. Check Docker and registry connectivity. "
                    + "When running Floci in Docker, use a user-defined network shared with the registry "
                    + "(FLOCI_SERVICES_ECR_DOCKER_NETWORK).";
            context.response().putHeader("Docker-Distribution-API-Version", "registry/2.0");
            registryError(context, 503, "UNAVAILABLE", message);
        }
    }

    /** Answers {@code status} unless the response is already written or its client went away. */
    private static void failUnlessGone(RoutingContext context, int status) {
        if (!context.response().ended() && !context.response().closed()) {
            context.response().setStatusCode(status).end();
        }
    }

    private static void copyRequestHeaders(RoutingContext context, HttpClientRequest upstream) {
        context.request().headers().forEach(header -> {
            if (!HOP_BY_HOP_HEADERS.contains(header.getKey().toLowerCase())
                    && !"host".equalsIgnoreCase(header.getKey())) {
                upstream.headers().add(header.getKey(), header.getValue());
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

    private static void copyResponseHeaders(RoutingContext context,
                                            io.vertx.core.http.HttpClientResponse upstream,
                                            RegistryRequest request) {
        context.response().setStatusCode(upstream.statusCode());
        upstream.headers().forEach(header -> {
            if (!HOP_BY_HOP_HEADERS.contains(header.getKey().toLowerCase())) {
                String value = "location".equalsIgnoreCase(header.getKey())
                        ? externalLocation(header.getValue(), request)
                        : header.getValue();
                context.response().putHeader(header.getKey(), value);
            }
        });
    }

    static String externalLocation(String location, RegistryRequest request) {
        String prefix = "/v2/" + request.storageRepositoryName();
        return location.replace(prefix, "/v2/" + request.clientRepositoryName());
    }

    private static void imageTagAlreadyExists(RoutingContext context, RegistryRequest request) {
        String tag = request.tag() != null ? request.tag() : "";
        String repository = request.repositoryName() != null ? request.repositoryName() : "";
        String message = String.format(
                "The image tag %s already exists in the %s repository and cannot be overwritten because the tag is immutable.",
                tag, repository);
        registryError(context, 400, "TAG_INVALID", message);
    }

    private static void registryError(RoutingContext context, int status, String code, String message) {
        context.response().setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("errors", new JsonArray().add(new JsonObject()
                        .put("code", code).put("message", message))).encode());
    }

    private void release(String immutableTag) {
        if (immutableTag != null) {
            ecrService.releaseImmutableTag(immutableTag);
        }
    }

    /**
     * One proxied request: the registry client it opens once it gets that far, and the immutable
     * tag it holds. It ends once, at whichever comes first of the registry's answer, a failure and
     * the client going away; the close handler also fires after an exchange that ended normally,
     * when another exchange may already hold the same tag.
     */
    private final class Exchange {

        private final String immutableTag;
        private final AtomicBoolean ended = new AtomicBoolean();
        private HttpClient client;

        private Exchange(String immutableTag) {
            this.immutableTag = immutableTag;
        }

        private boolean ended() {
            return ended.get();
        }

        private HttpClient open(HttpClient registryClient) {
            client = registryClient;
            return registryClient;
        }

        private void end() {
            if (!ended.compareAndSet(false, true)) {
                return;
            }
            if (client == null) {
                release(immutableTag);
                return;
            }
            // Closing the client closes the exchange's registry connection, which the registry
            // sees and aborts on. The tag is free only once it is closed, so another write of the
            // tag cannot start its check while this one can still reach the registry.
            client.close().onComplete(closed -> release(immutableTag));
        }
    }

    static Optional<RegistryRequest> requestFor(String host, String path, String query, String uriStyle) {
        String registryPath = "/v2".equals(path) ? "/v2/" : path;
        if (!registryPath.startsWith("/v2/")) {
            return Optional.empty();
        }
        Matcher hostMatch = ECR_HOST.matcher(host == null ? "" : host);
        if (!hostMatch.matches()) {
            hostMatch = ECR_HOST.matcher(queryParameter(query, "ns"));
        }
        if (hostMatch.matches()) {
            String repository = "/v2/".equals(registryPath) ? "" : repositoryName(registryPath.substring("/v2/".length()));
            String storageRepositoryName = hostMatch.group(1) + "/" + hostMatch.group(2) + "/" + repository;
            String backendUri = repository.isEmpty()
                    ? registryPath + querySuffix(query, true)
                    : "/v2/" + storageRepositoryName
                            + registryPath.substring("/v2/".length() + repository.length())
                            + querySuffix(query, true);
            return Optional.of(new RegistryRequest(hostMatch.group(1), hostMatch.group(2), repository,
                    storageRepositoryName, repository, backendUri,
                    tag(path)));
        }
        // A loopback host is the address Floci itself hands the Docker daemon for the ECS and
        // Lambda images it pulls, path style in either URI style (see rewriteImageUri).
        if (!"path".equalsIgnoreCase(uriStyle) && !isLoopbackHost(host)) {
            return Optional.empty();
        }

        if ("/v2/".equals(registryPath)) {
            return Optional.of(new RegistryRequest("", "", "", "", "", registryPath + querySuffix(query, true), null));
        }

        String[] segments = registryPath.substring("/v2/".length()).split("/", 3);
        if (segments.length < 3 || !segments[0].matches("[0-9]{12}")
                || !segments[1].matches("[a-z0-9-]+")) {
            return Optional.empty();
        }
        String repository = repositoryName(segments[2]);
        String storageRepositoryName = segments[0] + "/" + segments[1] + "/" + repository;
        return Optional.of(new RegistryRequest(segments[0], segments[1], repository,
                storageRepositoryName, storageRepositoryName,
                registryPath + querySuffix(query, true), tag(registryPath)));
    }

    private static boolean isLoopbackHost(String host) {
        HostAndPort authority = host == null ? null : HostAndPort.parseAuthority(host, -1);
        return authority != null && NetworkExposureGuard.isLoopback(authority.host());
    }

    private static String querySuffix(String query, boolean removeNamespace) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        StringBuilder forwarded = new StringBuilder();
        for (String parameter : query.split("&")) {
            if (removeNamespace && parameter.startsWith("ns=")) {
                continue;
            }
            if (!forwarded.isEmpty()) {
                forwarded.append('&');
            }
            forwarded.append(parameter);
        }
        return forwarded.isEmpty() ? "" : "?" + forwarded;
    }

    private static String queryParameter(String query, String name) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        for (String parameter : query.split("&")) {
            int separator = parameter.indexOf('=');
            if (separator > 0 && name.equals(parameter.substring(0, separator))) {
                return URLDecoder.decode(parameter.substring(separator + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String repositoryName(String path) {
        int manifests = path.indexOf(MANIFESTS_PATH);
        if (manifests >= 0) {
            return path.substring(0, manifests);
        }
        int blobs = path.indexOf("/blobs/");
        if (blobs >= 0) {
            return path.substring(0, blobs);
        }
        int tags = path.indexOf("/tags/");
        return tags >= 0 ? path.substring(0, tags) : path;
    }

    private static String tag(String path) {
        int manifests = path.indexOf(MANIFESTS_PATH);
        if (manifests < 0) {
            return null;
        }
        String reference = path.substring(manifests + MANIFESTS_PATH.length());
        return reference.isEmpty() || reference.contains("/") || reference.contains(":") ? null : reference;
    }

    record RegistryRequest(String accountId,
                           String region,
                           String repositoryName,
                           String storageRepositoryName,
                           String clientRepositoryName,
                           String backendUri,
                           String tag) {
        RegistryRequest withStorageRepositoryName(String storageRepositoryName) {
            return new RegistryRequest(accountId, region, repositoryName, storageRepositoryName,
                    clientRepositoryName,
                    backendUri.replace("/v2/" + this.storageRepositoryName, "/v2/" + storageRepositoryName), tag);
        }
    }
}
