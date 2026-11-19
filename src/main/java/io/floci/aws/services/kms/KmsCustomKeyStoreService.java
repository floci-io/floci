package io.floci.aws.services.kms;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.SignedMarkers;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.kms.model.KmsCustomKeyStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.jboss.logging.Logger;

/** Management-plane operations for XKS custom key stores using PUBLIC_ENDPOINT connectivity. */
@ApplicationScoped
public class KmsCustomKeyStoreService {
    private static final Logger LOG = Logger.getLogger(KmsCustomKeyStoreService.class);
    private static final int MAX_PAGE_SIZE = 1000;
    private final KmsCustomKeyStoreRegistry registry;
    private final XksProxyClient proxyClient;
    private final RequestContext requestContext;
    private final SignedMarkers markers = new SignedMarkers();

    @Inject
    public KmsCustomKeyStoreService(KmsCustomKeyStoreRegistry registry, XksProxyClient proxyClient, RequestContext requestContext) {
        this.registry = registry;
        this.proxyClient = proxyClient;
        this.requestContext = requestContext;
    }

    KmsCustomKeyStoreService(KmsCustomKeyStoreRegistry registry, XksProxyClient proxyClient) {
        this(registry, proxyClient, null);
    }

    private String requestId() {
        return requestContext == null || requestContext.getRequestId() == null
                ? UUID.randomUUID().toString() : requestContext.getRequestId();
    }

    public KmsCustomKeyStore create(String name, String endpoint, String path, String accessKeyId,
                                    String secretAccessKey, String region) {
        XksCustomKeyStoreValidation.requiredName(name);
        registry.requireCredentialEncryptionConfigured();
        XksCustomKeyStoreValidation.ProxyConfiguration proxy =
                XksCustomKeyStoreValidation.proxyConfiguration(endpoint, path, accessKeyId, secretAccessKey);
        proxyClient.requireHealthy(proxy.endpoint(), proxy.path(), proxy.accessKeyId(), proxy.secretAccessKey(),
                region, "CreateCustomKeyStore", requestId());
        KmsCustomKeyStore candidate = new KmsCustomKeyStore();
        candidate.setCustomKeyStoreName(name);
        candidate.setAccessKeyId(accessKeyId);
        candidate.setProxyUriEndpoint(endpoint);
        candidate.setProxyUriPath(path);
        return registry.create(candidate, secretAccessKey, region);
    }

    public Page describe(String id, String name, String marker, Integer limit, String region) {
        if (marker != null && (marker.length() > 1024 || marker.chars().anyMatch(ch -> ch < 0x20 || ch > 0xFF))) {
            throw new AwsException("InvalidMarkerException", "The pagination marker is invalid", 400);
        }
        List<String> ids = id == null ? null : List.of(id);
        List<String> names = name == null ? null : List.of(name);
        XksCustomKeyStoreValidation.validateDescribeFilters(ids, names);
        List<KmsCustomKeyStore> all = registry.list(region, ids, names);
        if ((id != null || name != null) && all.isEmpty()) {
            throw new AwsException("CustomKeyStoreNotFoundException", "The specified custom key store was not found", 400);
        }
        int pageSize = limit == null ? Math.max(1, all.size()) : Math.min(MAX_PAGE_SIZE, XksCustomKeyStoreValidation.pageSize(limit));
        String scope = "DescribeCustomKeyStores\n" + registry.accountId() + "\n" + region + "\n"
                + (id == null ? "" : id) + "\n" + (name == null ? "" : name) + "\n";
        int start = 0;
        if (marker != null) {
            String lastId = markers.cursorOf(scope, marker);
            if (lastId == null) {
                throw new AwsException("InvalidMarkerException", "The pagination marker is invalid", 400);
            }
            while (start < all.size() && all.get(start).getCustomKeyStoreId().compareTo(lastId) <= 0) start++;
        }
        int end = Math.min(start + pageSize, all.size());
        List<KmsCustomKeyStore> page = all.subList(start, end);
        String next = end < all.size() ? markers.issue(scope, page.getLast().getCustomKeyStoreId()) : null;
        return new Page(page, next);
    }

    public KmsCustomKeyStore update(String id, String name, String endpoint, String path,
                                    String accessKeyId, String secretAccessKey, String region) {
        KmsCustomKeyStore current = registry.require(id, region);
        String effectiveName = name == null ? current.getCustomKeyStoreName() : name;
        String effectiveEndpoint = endpoint == null ? current.getProxyUriEndpoint() : endpoint;
        String effectivePath = path == null ? current.getProxyUriPath() : path;
        String effectiveAccessKeyId = accessKeyId == null ? current.getAccessKeyId() : accessKeyId;
        boolean credentialsChanged = accessKeyId != null || secretAccessKey != null;
        if (credentialsChanged && (accessKeyId == null || secretAccessKey == null)) {
            throw new AwsException("ValidationException",
                    "AccessKeyId and RawSecretAccessKey must be supplied together", 400);
        }
        boolean endpointChanged = !effectiveEndpoint.equals(current.getProxyUriEndpoint());
        boolean pathChanged = !effectivePath.equals(current.getProxyUriPath());
        boolean disconnected = KmsCustomKeyStore.STATE_DISCONNECTED.equals(current.getConnectionState());
        boolean connected = KmsCustomKeyStore.STATE_CONNECTED.equals(current.getConnectionState());
        if (!disconnected && !connected) {
            throw new AwsException("CustomKeyStoreInvalidStateException",
                    "The custom key store must be connected or disconnected before it can be updated", 400);
        }
        if (endpointChanged && !disconnected) {
            throw new AwsException("CustomKeyStoreInvalidStateException",
                    "Disconnect the custom key store before changing XksProxyUriEndpoint", 400);
        }
        if (credentialsChanged || pathChanged || endpointChanged) {
            XksCustomKeyStoreValidation.ProxyConfiguration config =
                    XksCustomKeyStoreValidation.proxyConfiguration(effectiveEndpoint, effectivePath,
                            effectiveAccessKeyId, secretAccessKey == null
                                    ? registry.secretAccessKey(id, region) : secretAccessKey);
            proxyClient.requireHealthy(config.endpoint(), config.path(), config.accessKeyId(), config.secretAccessKey(),
                    region, "UpdateCustomKeyStore", requestId());
        }
        KmsCustomKeyStore candidate = new KmsCustomKeyStore();
        candidate.setCustomKeyStoreId(id);
        candidate.setCustomKeyStoreName(effectiveName);
        candidate.setAccessKeyId(effectiveAccessKeyId);
        candidate.setProxyUriEndpoint(effectiveEndpoint);
        candidate.setProxyUriPath(effectivePath);
        return registry.update(candidate, secretAccessKey, region);
    }

    public void delete(String id, String region) {
        registry.delete(id, region);
    }

    public void connect(String id, String region) {
        KmsCustomKeyStore store = registry.beginConnect(id, region);
        try {
            proxyClient.requireHealthy(URI.create(store.getProxyUriEndpoint()), store.getProxyUriPath(),
                    store.getAccessKeyId(), registry.secretAccessKey(id, region), region, "ConnectCustomKeyStore", requestId());
            registry.markConnected(id, region);
        } catch (AwsException e) {
            String connectionCode = e instanceof XksProxyClient.XksProxyException proxyError
                    ? proxyError.connectionErrorCode() : null;
            registry.markFailed(id, region, e.getErrorCode(), connectionCode);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Unexpected failure while connecting XKS custom key store %s", id);
            registry.markFailed(id, region, "InternalFailure", "INTERNAL_ERROR");
        }
    }

    public void disconnect(String id, String region) {
        registry.disconnect(id, region);
    }

    public record Page(List<KmsCustomKeyStore> items, String nextMarker) {
        public boolean truncated() { return nextMarker != null; }
    }
}
