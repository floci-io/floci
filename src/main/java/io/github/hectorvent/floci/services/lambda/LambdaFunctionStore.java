package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Wraps the storage backend for Lambda functions with region-aware key logic.
 */
@ApplicationScoped
public class LambdaFunctionStore implements Resettable {

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
        List<LambdaFunction> all = backend instanceof AccountAwareStorageBackend<LambdaFunction> aware
                ? aware.scanAllAccounts()
                : backend.scan(key -> true);
        all.forEach(this::indexFunction);
    }

    public void clear() {
        urlIdIndex.clear();
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

    /**
     * Saves into {@code accountId}'s partition rather than the ambient caller's. Async
     * paths (CDI observers, pollers) run with no {@code RequestContext} and would
     * otherwise write every account's function into the default account's partition.
     */
    public void saveForAccount(String accountId, String region, LambdaFunction fn) {
        getForAccount(accountId, region, fn.getFunctionName(), fn.getVersion()).ifPresent(this::deindexFunction);

        if (backend instanceof AccountAwareStorageBackend<LambdaFunction> aware) {
            aware.putForAccount(accountId, regionKey(region, fn.getFunctionName(), fn.getVersion()), fn);
        } else {
            backend.put(regionKey(region, fn.getFunctionName(), fn.getVersion()), fn);
        }
        indexFunction(fn);
    }

    public Optional<LambdaFunction> get(String region, String functionName) {
        return get(region, functionName, "$LATEST");
    }

    public Optional<LambdaFunction> get(String region, String functionName, String version) {
        return backend.get(regionKey(region, functionName, version));
    }

    public Optional<LambdaFunction> getForAccount(String accountId, String region, String functionName) {
        return getForAccount(accountId, region, functionName, "$LATEST");
    }

    public Optional<LambdaFunction> getForAccount(
            String accountId, String region, String functionName, String version) {
        if (backend instanceof AccountAwareStorageBackend<LambdaFunction> aware) {
            return aware.getForAccountMigratingLegacy(
                    accountId,
                    regionKey(region, functionName, version),
                    function -> belongsToAccount(function, accountId));
        }
        return backend.get(regionKey(region, functionName, version));
    }

    private static boolean belongsToAccount(LambdaFunction function, String accountId) {
        if (function.getAccountId() != null && !function.getAccountId().isBlank()) {
            return accountId.equals(function.getAccountId());
        }
        return accountId.equals(AwsArnUtils.accountOrDefault(function.getFunctionArn(), ""));
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

    /**
     * Like {@link #list(String)}, but across every account's partition. For async callers
     * that have no {@code RequestContext} and so would otherwise see only the default
     * account's functions.
     */
    public List<LambdaFunction> listAllAccounts(String region) {
        String prefix = "lambda::" + region + "::";
        Predicate<String> filter = key -> key.startsWith(prefix) && key.endsWith("::$LATEST");
        if (backend instanceof AccountAwareStorageBackend<LambdaFunction> aware) {
            return aware.scanAllAccountEntries(filter).stream()
                    .map(AccountAwareStorageBackend.AccountEntry::value)
                    .toList();
        }
        return backend.scan(filter);
    }

    public List<LambdaFunction> listAll() {
        return backend instanceof AccountAwareStorageBackend<LambdaFunction> aware
                ? aware.scanAllAccounts()
                : backend.scan(key -> true);
    }

    public void delete(String region, String functionName) {
        // Delete all versions
        listVersions(region, functionName).forEach(fn -> {
            deindexFunction(fn);
            backend.delete(regionKey(region, functionName, fn.getVersion()));
        });
    }

    /** Deletes one published version, leaving {@code $LATEST} and the other versions in place. */
    public void deleteVersion(String region, String functionName, String version) {
        get(region, functionName, version).ifPresent(fn -> {
            deindexFunction(fn);
            backend.delete(regionKey(region, functionName, version));
        });
    }

    private static String regionKey(String region, String functionName, String version) {
        return "lambda::" + region + "::" + functionName + "::" + (version != null ? version : "$LATEST");
    }
}
