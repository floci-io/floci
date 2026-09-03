package io.github.hectorvent.floci.services.ecr.registry;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ecr.EcrService;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.RequestOptions;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Proxies OCI Distribution traffic through Floci so ECR repository policy applies to Docker pushes.
 * Docker clients use the AWS-shaped repository URI while {@code registry:2} retains image storage.
 */
@ApplicationScoped
public class EcrRegistryDataPlane {

    private static final Pattern ECR_HOST = Pattern.compile(
            "^([0-9]{12})\\.dkr\\.ecr\\.([a-z0-9-]+)\\.localhost(?::[0-9]+)?$");
    private static final String MANIFESTS_PATH = "/manifests/";
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade");

    private final EcrRegistryManager registryManager;
    private final EcrService ecrService;
    private final EmulatorConfig config;
    private final Vertx vertx;
    private final HttpClient proxyClient;
    private final Set<String> inFlightImmutableTags = ConcurrentHashMap.newKeySet();

    @Inject
    public EcrRegistryDataPlane(EcrRegistryManager registryManager,
                                EcrService ecrService,
                                EmulatorConfig config,
                                Vertx vertx) {
        this.registryManager = registryManager;
        this.ecrService = ecrService;
        this.config = config;
        this.vertx = vertx;
        this.proxyClient = vertx.createHttpClient(new HttpClientOptions()
                .setConnectTimeout(5_000)
                .setKeepAlive(true));
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

        RegistryRequest registryRequest = request.get();
        if (!registryRequest.repositoryName().isEmpty()) {
            registryRequest = registryRequest.withStorageRepositoryName(
                    ecrService.registryRepositoryName(
                            registryRequest.repositoryName(), registryRequest.accountId(), registryRequest.region()));
        }
        String immutableTag = immutableTag(context, registryRequest);
        if (immutableTag != null && !inFlightImmutableTags.add(immutableTag)) {
            imageTagAlreadyExists(context);
            return;
        }

        checkTagAndProxy(context, registryRequest, immutableTag);
    }

    private String immutableTag(RoutingContext context, RegistryRequest request) {
        if (request.tag() == null || !"PUT".equals(context.request().method().name())
                || !ecrService.isImageTagImmutable(request.repositoryName(), request.accountId(), request.region())) {
            return null;
        }
        return request.accountId() + "/" + request.region() + "/" + request.repositoryName() + ":" + request.tag();
    }

    private void checkTagAndProxy(RoutingContext context, RegistryRequest request, String immutableTag) {
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
            if (result.failed()) {
                release(immutableTag);
                context.response().setStatusCode(503).end();
            } else if (result.result()) {
                release(immutableTag);
                imageTagAlreadyExists(context);
            } else {
                proxy(context, request, immutableTag);
            }
        });
    }

    private void proxy(RoutingContext context, RegistryRequest request, String immutableTag) {
        URI backend = URI.create(registryManager.httpClient().baseUrl());
        RequestOptions options = new RequestOptions()
                .setHost(backend.getHost())
                .setPort(backend.getPort() == -1 ? 80 : backend.getPort())
                .setURI(request.backendUri())
                .setMethod(context.request().method());
        proxyClient.request(options).onComplete(upstreamRequest -> {
            if (upstreamRequest.failed()) {
                release(immutableTag);
                context.response().setStatusCode(503).end();
                return;
            }
            copyRequestHeaders(context, upstreamRequest.result());
            upstreamRequest.result().response().onComplete(upstreamResponse -> {
                if (upstreamResponse.failed()) {
                    release(immutableTag);
                    context.response().setStatusCode(502).end();
                    return;
                }
                copyResponseHeaders(context, upstreamResponse.result(), request);
                upstreamResponse.result().pipeTo(context.response()).onComplete(ignored -> release(immutableTag));
            });
            context.request().pipeTo(upstreamRequest.result()).onFailure(ignored -> {
                release(immutableTag);
                if (!context.response().ended()) {
                    context.response().setStatusCode(502).end();
                }
            });
        });
    }

    private static void copyRequestHeaders(RoutingContext context, io.vertx.core.http.HttpClientRequest upstream) {
        context.request().headers().forEach(header -> {
            if (!HOP_BY_HOP_HEADERS.contains(header.getKey().toLowerCase())
                    && !"host".equalsIgnoreCase(header.getKey())) {
                upstream.putHeader(header.getKey(), header.getValue());
            }
        });
        if (context.request().getHeader("Content-Length") == null) {
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

    private static void imageTagAlreadyExists(RoutingContext context) {
        context.response().setStatusCode(400)
                .putHeader("Content-Type", "application/json")
                .end("{\"errors\":[{\"code\":\"DENIED\",\"message\":\"ImageTagAlreadyExistsException\"}]}");
    }

    private void release(String immutableTag) {
        if (immutableTag != null) {
            inFlightImmutableTags.remove(immutableTag);
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
        if (!"path".equalsIgnoreCase(uriStyle)) {
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
