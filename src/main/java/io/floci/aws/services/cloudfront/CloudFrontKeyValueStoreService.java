package io.floci.aws.services.cloudfront;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudfront.model.KeyValueStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CloudFront key value stores. The stores are created and described through the CloudFront
 * control plane, and their keys are read and written through the separate
 * {@code cloudfront-keyvaluestore} data plane. Both planes share this service but keep an independent
 * ETag per store: a control plane update rotates only the control plane ETag, and a key write only the
 * data plane ETag.
 */
@ApplicationScoped
public class CloudFrontKeyValueStoreService implements StoredResourceArns {

    static final int MAX_KEY_BYTES = 512;
    static final int MAX_VALUE_BYTES = 1024;
    static final long MAX_STORE_BYTES = 5L * 1024 * 1024;
    static final int DEFAULT_LIST_KEYS = 10;
    static final int MAX_LIST_KEYS = 50;
    static final int MAX_KEY_UPDATES = 50;
    static final int MAX_UPDATE_PAYLOAD_BYTES = 3 * 1024 * 1024;
    static final int MAX_STORES = 200;

    private static final Pattern STORE_NAME = Pattern.compile("[a-zA-Z0-9_-]{1,64}");
    private static final int MAX_COMMENT_LENGTH = 128;
    private static final String STATUS_READY = "READY";

    private final StorageBackend<String, KeyValueStore> store;
    private final RegionResolver regionResolver;

    @Inject
    public CloudFrontKeyValueStoreService(StorageFactory factory, RegionResolver regionResolver) {
        this.store = factory.create("cloudfront", "cloudfront-key-value-stores.json",
                new TypeReference<Map<String, KeyValueStore>>() {});
        this.regionResolver = regionResolver;
    }

    // ── Control plane ─────────────────────────────────────────────────────────

    public synchronized KeyValueStore create(String name, String comment) {
        validateName(name);
        String effectiveComment = comment == null ? "" : comment;
        validateComment(effectiveComment);
        if (store.get(name).isPresent()) {
            throw new AwsException("EntityAlreadyExists", "The Key Value Store already exists.", 409);
        }
        if (store.scan(key -> true).size() >= MAX_STORES) {
            throw new AwsException("EntityLimitExceeded",
                    "The maximum number of Key Value Stores for this account has been reached.", 400);
        }
        Instant now = Instant.now();
        KeyValueStore created = new KeyValueStore();
        created.setName(name);
        created.setId(UUID.randomUUID().toString());
        created.setComment(effectiveComment);
        created.setArn(regionResolver.buildGlobalArn("cloudfront", "key-value-store/" + created.getId()));
        created.setStatus(STATUS_READY);
        created.setEtag(newEtag());
        created.setDataEtag(newEtag());
        created.setCreated(now);
        created.setLastModifiedTime(now);
        store.put(name, created);
        return created;
    }

    public KeyValueStore describe(String name) {
        return store.get(name).orElseThrow(() ->
                new AwsException("EntityNotFound", "The specified KeyValueStore does not exist.", 404));
    }

    public List<KeyValueStore> list(String marker, int maxItems, String status) {
        List<KeyValueStore> all = new ArrayList<>(store.scan(key -> true));
        if (status != null && !status.isEmpty()) {
            all.removeIf(kvs -> !status.equals(kvs.getStatus()));
        }
        all.sort(Comparator.comparing(KeyValueStore::getName));
        if (marker != null && !marker.isEmpty()) {
            all = new ArrayList<>(all.stream().filter(kvs -> kvs.getName().compareTo(marker) > 0).toList());
        }
        if (maxItems > 0 && all.size() > maxItems) {
            return new ArrayList<>(all.subList(0, maxItems));
        }
        return all;
    }

    public synchronized KeyValueStore update(String name, String ifMatch, String comment) {
        KeyValueStore existing = describe(name);
        requireControlPlaneEtag(existing, ifMatch);
        if (comment == null) {
            throw new AwsException("InvalidArgument", "The Comment is required.", 400);
        }
        String effectiveComment = comment;
        validateComment(effectiveComment);
        KeyValueStore updated = copyOf(existing);
        updated.setComment(effectiveComment);
        updated.setEtag(newEtag());
        updated.setLastModifiedTime(Instant.now());
        store.put(name, updated);
        return updated;
    }

    /**
     * Deletes the store. The caller has already checked that no function is associated with it,
     * since functions are owned by {@link CloudFrontService}.
     */
    public synchronized void delete(String name, String ifMatch) {
        KeyValueStore existing = describe(name);
        requireControlPlaneEtag(existing, ifMatch);
        store.delete(name);
    }

    @Override
    public String namespace() {
        return "cloudfront";
    }

    /** The ARN a store named by the request is stored under, which ends in the id CloudFront minted for it. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        if (!"key-value-store".equals(type) || names.size() != 1) {
            return Optional.empty();
        }
        return store.get(names.getFirst()).map(KeyValueStore::getArn);
    }
    public Optional<KeyValueStore> findByArn(String arn) {
        if (arn == null) {
            return Optional.empty();
        }
        return store.scan(key -> true).stream().filter(kvs -> arn.equals(kvs.getArn())).findFirst();
    }

    // ── Data plane ────────────────────────────────────────────────────────────

    public KeyValueStore describeByArn(String arn) {
        return findByArn(arn).orElseThrow(() ->
                new AwsException("ResourceNotFoundException", "The Key Value Store was not found.", 404));
    }

    /** One read of the store, so the value and the counts that go with it come from the same state. */
    public KeyLookup getKey(String arn, String key) {
        KeyValueStore kvs = describeByArn(arn);
        String value = kvs.getItems().get(key);
        if (value == null) {
            throw new AwsException("ResourceNotFoundException", "The Key was not found.", 404);
        }
        return new KeyLookup(key, value, kvs);
    }

    public record KeyLookup(String key, String value, KeyValueStore store) {}

    public synchronized KeyValueStore putKey(String arn, String ifMatch, String key, String value) {
        Map<String, String> puts = new LinkedHashMap<>();
        puts.put(key, value == null ? "" : value);
        return apply(arn, ifMatch, puts, List.of(), false);
    }

    public synchronized KeyValueStore deleteKey(String arn, String ifMatch, String key) {
        return apply(arn, ifMatch, Map.of(), List.of(key), true);
    }

    /** Batch update. A delete of a key that is not in the store is skipped. */
    public synchronized KeyValueStore updateKeys(String arn, String ifMatch, Map<String, String> puts,
                                                 List<String> deletes) {
        return apply(arn, ifMatch, puts, deletes, false);
    }

    /**
     * Applies puts and then deletes in one step and rotates the ETag once. Nothing is written when
     * any entry fails validation or the result would exceed the store size quota.
     */
    private KeyValueStore apply(String arn, String ifMatch, Map<String, String> puts, List<String> deletes,
                                boolean failOnMissingDelete) {
        KeyValueStore kvs = describeByArn(arn);
        requireDataPlaneEtag(kvs, ifMatch);
        for (Map.Entry<String, String> put : puts.entrySet()) {
            validateKey(put.getKey());
            validateValue(put.getValue());
        }
        for (String key : deletes) {
            validateKey(key);
        }

        Map<String, String> updated = new LinkedHashMap<>(kvs.getItems());
        updated.putAll(puts);
        for (String key : deletes) {
            if (updated.remove(key) == null && failOnMissingDelete) {
                throw new AwsException("ResourceNotFoundException", "The Key was not found.", 404);
            }
        }
        if (totalSizeInBytes(updated) > MAX_STORE_BYTES) {
            throw new AwsException("ServiceQuotaExceededException", "Limit exceeded.", 402);
        }

        KeyValueStore result = copyOf(kvs);
        result.setItems(updated);
        result.setDataEtag(newEtag());
        result.setLastModifiedTime(Instant.now());
        store.put(result.getName(), result);
        return result;
    }

    /** A page of keys in key order, and the token for the next page when there is one. */
    public KeyPage listKeys(String arn, String nextToken, Integer maxResults) {
        KeyValueStore kvs = describeByArn(arn);
        PaginatedResult<Map.Entry<String, String>> page = Pagination.paginate(
                new ArrayList<>(kvs.getItems().entrySet()), Map.Entry::getKey, maxResults, nextToken,
                DEFAULT_LIST_KEYS, MAX_LIST_KEYS, "ValidationException");
        return new KeyPage(page.items(), page.nextToken());
    }
    public record KeyPage(List<Map.Entry<String, String>> items, String nextToken) {}

    public static int itemCount(KeyValueStore kvs) {
        return kvs.getItems().size();
    }

    public static long totalSizeInBytes(KeyValueStore kvs) {
        return totalSizeInBytes(kvs.getItems());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static long totalSizeInBytes(Map<String, String> items) {
        long total = 0;
        for (Map.Entry<String, String> entry : items.entrySet()) {
            total += utf8Length(entry.getKey()) + utf8Length(entry.getValue());
        }
        return total;
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String newEtag() {
        return UUID.randomUUID().toString();
    }

    /** A stored store is never changed in place: the in-memory backend would show the change to earlier readers. */
    private static KeyValueStore copyOf(KeyValueStore source) {
        KeyValueStore copy = new KeyValueStore();
        copy.setName(source.getName());
        copy.setId(source.getId());
        copy.setComment(source.getComment());
        copy.setArn(source.getArn());
        copy.setStatus(source.getStatus());
        copy.setEtag(source.getEtag());
        copy.setDataEtag(source.getDataEtag());
        copy.setCreated(source.getCreated());
        copy.setLastModifiedTime(source.getLastModifiedTime());
        copy.setItems(new LinkedHashMap<>(source.getItems()));
        return copy;
    }

    private static void requireControlPlaneEtag(KeyValueStore kvs, String ifMatch) {
        if (ifMatch == null || !kvs.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
    }

    private static void requireDataPlaneEtag(KeyValueStore kvs, String ifMatch) {
        if (ifMatch == null || !kvs.getDataEtag().equals(ifMatch)) {
            throw new AwsException("ValidationException",
                    "Pre-Condition failed during update of Key-Value-Store", 400);
        }
    }

    private static void validateName(String name) {
        if (name == null || !STORE_NAME.matcher(name).matches()) {
            throw new AwsException("InvalidArgument",
                    "The Key Value Store name must be 1 to 64 characters of letters, digits, hyphens and underscores.",
                    400);
        }
    }

    private static void validateComment(String comment) {
        if (comment.length() > MAX_COMMENT_LENGTH) {
            throw new AwsException("InvalidArgument",
                    "The Key Value Store comment must be at most " + MAX_COMMENT_LENGTH + " characters.", 400);
        }
    }

    private static void validateKey(String key) {
        if (key == null || key.isEmpty() || utf8Length(key) > MAX_KEY_BYTES) {
            throw new AwsException("ValidationException",
                    "A key must be 1 to " + MAX_KEY_BYTES + " bytes.", 400);
        }
    }

    private static void validateValue(String value) {
        if (value != null && utf8Length(value) > MAX_VALUE_BYTES) {
            throw new AwsException("ValidationException",
                    "A value must be at most " + MAX_VALUE_BYTES + " bytes.", 400);
        }
    }
}
