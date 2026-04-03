package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wraps the storage backend for Lambda functions with region-aware key logic.
 */
@ApplicationScoped
public class LambdaFunctionStore {

    private final StorageBackend<String, LambdaFunction> backend;
    private final ConcurrentHashMap<String, LambdaFunction> urlIdIndex = new ConcurrentHashMap<>();

    @Inject
    public LambdaFunctionStore(StorageFactory storageFactory) {
        this.backend = storageFactory.create("lambda", "lambda-functions.json",
                new TypeReference<>() {
                });
        loadIndex();
    }

    LambdaFunctionStore(StorageBackend<String, LambdaFunction> backend) {
        this.backend = backend;
        loadIndex();
    }

    private void loadIndex() {
        backend.scan(key -> true).forEach(this::indexFunction);
    }

    private void indexFunction(LambdaFunction fn) {
        if (fn.getUrlConfig() != null && fn.getUrlConfig().getFunctionUrl() != null) {
            String urlId = extractUrlId(fn.getUrlConfig().getFunctionUrl());
            if (urlId != null) {
                urlIdIndex.put(urlId, fn);
            }
        }
    }

    private void deindexFunction(LambdaFunction fn) {
        if (fn.getUrlConfig() != null && fn.getUrlConfig().getFunctionUrl() != null) {
            String urlId = extractUrlId(fn.getUrlConfig().getFunctionUrl());
            if (urlId != null) {
                urlIdIndex.remove(urlId);
            }
        }
    }

    private String extractUrlId(String url) {
        // http://urlId.lambda-url.region.baseHost/
        int start = url.indexOf("://");
        if (start < 0) return null;
        int end = url.indexOf(".", start + 3);
        if (end < 0) return null;
        return url.substring(start + 3, end);
    }

    public void save(String region, LambdaFunction fn) {
        // Remove old index entry if URL changed or was removed
        get(region, fn.getFunctionName(), fn.getVersion()).ifPresent(this::deindexFunction);
        
        backend.put(regionKey(region, fn.getFunctionName(), fn.getVersion()), fn);
        indexFunction(fn);
    }

    public Optional<LambdaFunction> get(String region, String functionName) {
        return get(region, functionName, "$LATEST");
    }

    public Optional<LambdaFunction> get(String region, String functionName, String version) {
        return backend.get(regionKey(region, functionName, version));
    }

    public Optional<LambdaFunction> getByUrlId(String urlId) {
        return Optional.ofNullable(urlIdIndex.get(urlId));
    }

    public List<LambdaFunction> list(String region) {
        String prefix = "lambda::" + region + "::";
        return backend.scan(key -> key.startsWith(prefix) && key.endsWith("::$LATEST"));
    }

    public List<LambdaFunction> listVersions(String region, String functionName) {
        String prefix = "lambda::" + region + "::" + functionName + "::";
        return backend.scan(key -> key.startsWith(prefix));
    }

    public List<LambdaFunction> listAll() {
        return backend.scan(key -> true);
    }

    public void delete(String region, String functionName) {
        // Delete all versions
        listVersions(region, functionName).forEach(fn -> {
            deindexFunction(fn);
            backend.delete(regionKey(region, functionName, fn.getVersion()));
        });
    }

    private static String regionKey(String region, String functionName, String version) {
        return "lambda::" + region + "::" + functionName + "::" + (version != null ? version : "$LATEST");
    }
}
