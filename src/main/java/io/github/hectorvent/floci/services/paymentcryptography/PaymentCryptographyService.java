package io.github.hectorvent.floci.services.paymentcryptography;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.StoredResourceArns;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.paymentcryptography.model.PaymentCryptographyAlias;
import io.github.hectorvent.floci.services.paymentcryptography.model.PaymentCryptographyKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Payment Cryptography key store.
 *
 * <p>Keys, aliases and tags all live in the region they were made in. The
 * service is regional, and an alias name is unique in an account and a region
 * rather than in an account, so the same name may name a key in two regions
 * and the two are two aliases.
 */
@ApplicationScoped
public class PaymentCryptographyService implements Resettable, StoredResourceArns {

    /** The storage key, which is the one the catalog's descriptor names. */
    private static final String STORAGE_KEY = "paymentcryptography";

    /** The service in the ARN, which is the service's own name. */
    private static final String SERVICE = "payment-cryptography";

    /** The number of results a listing returns when the caller asks for none. */
    private static final int DEFAULT_PAGE_SIZE = 50;

    /** The most results a listing returns when the caller asks for a number. */
    private static final int MAX_RESULTS = 100;

    /** The error a bad page size or a bad token is refused with. */
    private static final String PAGINATION_ERROR = "ValidationException";

    /** The only algorithm this service makes material for. */
    private static final String SUPPORTED_ALGORITHM = "AES_256";

    /** The only class this service makes material for. */
    private static final String SUPPORTED_CLASS = "SYMMETRIC_KEY";

    private final StorageBackend<String, PaymentCryptographyKey> keyStore;
    private final StorageBackend<String, PaymentCryptographyAlias> aliasStore;
    private final RegionResolver regionResolver;
    private final SecureRandom secureRandom;
    private final PaymentCryptographyRequestContext requestContext;

    @Inject
    public PaymentCryptographyService(StorageFactory storageFactory, RegionResolver regionResolver,
                                      PaymentCryptographyRequestContext requestContext) {
        this(storageFactory.create(STORAGE_KEY, "payment-cryptography-keys.json",
                        new TypeReference<>() {}),
                storageFactory.create(STORAGE_KEY, "payment-cryptography-aliases.json",
                        new TypeReference<>() {}),
                regionResolver,
                new SecureRandom(),
                requestContext);
    }

    PaymentCryptographyService(StorageBackend<String, PaymentCryptographyKey> keyStore,
                                StorageBackend<String, PaymentCryptographyAlias> aliasStore,
                                RegionResolver regionResolver,
                                PaymentCryptographyRequestContext requestContext) {
        this(keyStore, aliasStore, regionResolver, new SecureRandom(), requestContext);
    }

    PaymentCryptographyService(StorageBackend<String, PaymentCryptographyKey> keyStore,
                                StorageBackend<String, PaymentCryptographyAlias> aliasStore,
                                RegionResolver regionResolver,
                                SecureRandom secureRandom,
                                PaymentCryptographyRequestContext requestContext) {
        this.keyStore = keyStore;
        this.aliasStore = aliasStore;
        this.regionResolver = regionResolver;
        this.secureRandom = secureRandom;
        this.requestContext = requestContext;
    }

    // ---- keys ---------------------------------------------------------------

    /** Creates a key with fresh AES-256 material. */
    public PaymentCryptographyKey createKey(String region, Map<String, Object> attributes,
                                            Map<String, Boolean> modesOfUse, boolean exportable,
                                            boolean enabled, Map<String, String> tags) {
        String algorithm = string(attributes, "KeyAlgorithm", "AES_256");
        String keyClass = string(attributes, "KeyClass", "SYMMETRIC_KEY");
        // The material is AES-256 and symmetric, so a key that says otherwise would be a key
        // whose attributes do not describe it.
        if (!SUPPORTED_ALGORITHM.equals(algorithm)) {
            throw new AwsException("ValidationException",
                    "KeyAlgorithm '" + algorithm + "' is not supported, this service makes "
                            + SUPPORTED_ALGORITHM + " keys", 400);
        }
        if (!SUPPORTED_CLASS.equals(keyClass)) {
            throw new AwsException("ValidationException",
                    "KeyClass '" + keyClass + "' is not supported, this service makes "
                            + SUPPORTED_CLASS + " keys", 400);
        }
        PaymentCryptographyKey key = new PaymentCryptographyKey();
        key.setKeyId(UUID.randomUUID().toString().replace("-", ""));
        key.setRegion(region);
        key.setArn(regionResolver.buildArn(SERVICE, region, "key/" + key.getKeyId()));
        key.setKeyAlgorithm(algorithm);
        key.setKeyClass(keyClass);
        key.setKeyUsage(usage(attributes));
        key.setKeyModesOfUse(modesOfUse);
        key.setExportable(exportable);
        key.setEnabled(enabled);
        key.setTags(new LinkedHashMap<>(tags));
        key.setKeyState("CREATE_COMPLETE");
        key.setKeyMaterialHex(PaymentCryptographyCrypto.randomAes256Hex(secureRandom));
        key.setKeyCheckValue(PaymentCryptographyCrypto.checkValue(key.getKeyMaterialHex()));
        key.setKeyCheckValueAlgorithm("CMAC");
        long now = Instant.now().getEpochSecond();
        key.setCreateTimestamp(now);
        key.setUsageStartTimestamp(now);
        keyStore.put(key.getKeyId(), key);
        return key;
    }

    /** The key an identifier names, or null when there is none. */
    public PaymentCryptographyKey findKey(String region, String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return null;
        }
        if (identifier.startsWith("alias/")) {
            return keyOfAlias(region, identifier);
        }
        if (AwsArnUtils.looksLikeArn(identifier)) {
            for (PaymentCryptographyKey key : keyStore.scan(k -> true)) {
                if (identifier.equals(key.getArn()) && region.equals(key.getRegion())) {
                    return key;
                }
            }
            return null;
        }
        PaymentCryptographyKey byId = keyStore.get(identifier).orElse(null);
        return byId != null && region.equals(byId.getRegion()) ? byId : null;
    }

    /**
     * The keys of a region, a page at a time.
     *
     * <p>The service answers with as many keys as the caller asks for, and a
     * token that asks for the page after that one. The key's own ARN is the
     * cursor, so a page can be resumed after the key it stopped at.
     *
     * <p>A request naming a state is answered with the keys in that state
     * only, and with every key when it names none.
     */
    public PaginatedResult<PaymentCryptographyKey> listKeys(String region, String keyState,
                                                              Integer maxResults, String nextToken) {
        return Pagination.paginate(listKeys(region, keyState), PaymentCryptographyKey::getArn,
                maxResults, nextToken, DEFAULT_PAGE_SIZE, MAX_RESULTS, PAGINATION_ERROR);
    }

    /** The keys of a region, in the state the request names, or in any when it names none. */
    public List<PaymentCryptographyKey> listKeys(String region, String keyState) {
        List<PaymentCryptographyKey> out = new ArrayList<>();
        for (PaymentCryptographyKey key : keyStore.scan(k -> true)) {
            if (region.equals(key.getRegion()) && (keyState == null || keyState.equals(key.getKeyState()))) {
                out.add(key);
            }
        }
        return out;
    }

    /**
     * Removes a key and answers the key as the service does when a deletion is
     * scheduled: the key's own metadata, its state {@code DELETE_PENDING} and
     * the moment it is deleted.
     *
     * <p>The service deletes a key the moment it is asked to, so the waiting
     * period the service offers is not one: the key is gone when the answer
     * goes out, and the answer says when it is deleted in both of its
     * timestamps.
     *
     * <p>The lock is the same one the tagging operations take, so a key that
     * is being tagged cannot be written back after it was deleted.
     */
    public synchronized PaymentCryptographyKey deleteKey(PaymentCryptographyKey key) {
        long now = Instant.now().getEpochSecond();
        key.setKeyState("DELETE_PENDING");
        key.setEnabled(false);
        key.setDeletePendingTimestamp(now);
        key.setDeleteTimestamp(now);
        keyStore.delete(key.getKeyId());
        for (PaymentCryptographyAlias alias : aliasStore.scan(a -> true)) {
            if (key.getKeyId().equals(alias.getKeyId())) {
                aliasStore.delete(aliasKey(alias.getAliasName(), alias.getRegion()));
            }
        }
        return key;
    }

    // ---- aliases ------------------------------------------------------------

    /**
     * The alias of a name in a region.
     *
     * <p>The alias belongs to the region of the key it names, so the lookup is
     * by name and region together.
     */
    public PaymentCryptographyAlias findAlias(String region, String aliasName) {
        return aliasStore.get(aliasKey(aliasName, region)).orElse(null);
    }

    /**
     * The key an alias names, as this request resolved it.
     *
     * <p>The alias is resolved once for the request, and the resolution is kept: the
     * permission check resolves the alias to the key it names, and the request must act on
     * that key. Another request may move the alias in between, and a key resolved again
     * afterwards could be one no policy was checked against.
     */
    private PaymentCryptographyKey keyOfAlias(String region, String aliasName) {
        String keyId = requestContext.aliasKeyId(region, aliasName);
        if (keyId == null) {
            PaymentCryptographyAlias alias = findAlias(region, aliasName);
            keyId = alias == null || alias.getKeyId() == null ? "" : alias.getKeyId();
            requestContext.pinAliasKeyId(region, aliasName, keyId);
        }
        return keyId.isEmpty() ? null : keyStore.get(keyId).orElse(null);
    }

    /** Points a name at a key. The name must be free in the key's region. */
    public synchronized PaymentCryptographyAlias createAlias(String aliasName, PaymentCryptographyKey key) {
        liveKey(key);
        if (findAlias(key.getRegion(), aliasName) != null) {
            throw new AwsException("ConflictException",
                    "Alias " + aliasName + " already exists", 400);
        }
        PaymentCryptographyAlias alias = new PaymentCryptographyAlias(
                aliasName, key.getRegion(), key.getArn(), key.getKeyId());
        aliasStore.put(aliasKey(aliasName, alias.getRegion()), alias);
        return alias;
    }

    /** Points a name at another key, in the same region. */
    public synchronized PaymentCryptographyAlias updateAlias(String aliasName, PaymentCryptographyKey key) {
        liveKey(key);
        PaymentCryptographyAlias existing = findAlias(key.getRegion(), aliasName);
        if (existing == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Alias " + aliasName + " is not found", 400);
        }
        existing.setKeyArn(key.getArn());
        existing.setKeyId(key.getKeyId());
        aliasStore.put(aliasKey(aliasName, existing.getRegion()), existing);
        return existing;
    }

    /**
     * Removes the key an alias names, leaving the alias itself.
     *
     * <p>The service answers an update that names no key this way: the alias
     * names no key afterwards, and it answers with the alias alone.
     */
    public synchronized PaymentCryptographyAlias detachAlias(String region, String aliasName) {
        PaymentCryptographyAlias existing = findAlias(region, aliasName);
        if (existing == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Alias " + aliasName + " is not found", 400);
        }
        existing.setKeyArn(null);
        existing.setKeyId(null);
        aliasStore.put(aliasKey(aliasName, existing.getRegion()), existing);
        return existing;
    }

    /** Removes a name from a region. */
    public synchronized void deleteAlias(String region, String aliasName) {
        if (findAlias(region, aliasName) == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Alias " + aliasName + " is not found", 400);
        }
        aliasStore.delete(aliasKey(aliasName, region));
    }

    /**
     * The aliases of a region, or of one key, a page at a time.
     *
     * <p>The service answers with as many aliases as the caller asks for, and
     * a token that asks for the page after that one.
     */
    public PaginatedResult<PaymentCryptographyAlias> listAliases(String region, String keyArn,
                                                                 Integer maxResults, String nextToken) {
        return Pagination.paginate(listAliases(region, keyArn), PaymentCryptographyAlias::getAliasName,
                maxResults, nextToken, DEFAULT_PAGE_SIZE, MAX_RESULTS, PAGINATION_ERROR);
    }

    /** The aliases of a region, or of one key. */
    public List<PaymentCryptographyAlias> listAliases(String region, String keyArn) {
        List<PaymentCryptographyAlias> out = new ArrayList<>();
        for (PaymentCryptographyAlias alias : aliasStore.scan(a -> true)) {
            if (!region.equals(alias.getRegion())) {
                continue;
            }
            if (keyArn != null && !keyArn.equals(alias.getKeyArn())) {
                continue;
            }
            out.add(alias);
        }
        return out;
    }

    // ---- tags ---------------------------------------------------------------

    /**
     * Adds tags to a key.
     *
     * <p>The tags are the region's: they are not part of the replication
     * process, so a key in another region carries its own tag set.
     */
    public synchronized void tag(PaymentCryptographyKey key, Map<String, String> tags) {
        PaymentCryptographyKey current = liveKey(key);
        current.getTags().putAll(tags);
        keyStore.put(current.getKeyId(), current);
    }

    /**
     * The key as it is in the store now.
     *
     * <p>The caller looked the key up before it asked for the change, and the
     * key may be gone by the time the change is made. Writing the looked-up
     * object back would put a deleted key into the store again, so the key is
     * read again under the lock and the request fails if it is not there.
     */
    private PaymentCryptographyKey liveKey(PaymentCryptographyKey key) {
        PaymentCryptographyKey current = keyStore.get(key.getKeyId()).orElse(null);
        if (current == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Key " + key.getArn() + " is not found", 400);
        }
        return current;
    }

    /** Removes tags from a key, in this region only. */
    public synchronized void untag(PaymentCryptographyKey key, List<String> tagKeys) {
        PaymentCryptographyKey current = liveKey(key);
        for (String tagKey : tagKeys) {
            current.getTags().remove(tagKey);
        }
        keyStore.put(current.getKeyId(), current);
    }

    /**
     * The tags of a key.
     *
     * <p>The copy takes the lock the tagging operations take, so it cannot read the map while
     * another request is writing to it.
     */
    public synchronized Map<String, String> listTags(PaymentCryptographyKey key) {
        return new LinkedHashMap<>(key.getTags());
    }

    /**
     * The tags of a key, a page at a time.
     *
     * <p>The service answers with as many tags as the caller asks for, and a
     * token that asks for the page after that one.
     */
    public PaginatedResult<Map.Entry<String, String>> listTags(PaymentCryptographyKey key,
                                                                Integer maxResults, String nextToken) {
        return Pagination.paginate(new ArrayList<>(listTags(key).entrySet()), Map.Entry::getKey,
                maxResults, nextToken, DEFAULT_PAGE_SIZE, MAX_RESULTS, PAGINATION_ERROR);
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * The namespace of the resource types this service finds, as the request-resources table names it.
     */
    @Override
    public String namespace() {
        return "payment-cryptography";
    }

    /**
     * The ARN the caller's key or alias of the name in the Region is stored under.
     *
     * <p>A key is named by its id or by an alias, and an alias names the key it points at, so a
     * request naming a key by an alias is checked on the key the alias names, as the handler
     * resolves it: an {@code Allow} scoped to that key passes and a {@code Deny} scoped to it
     * holds, whichever of the two names the request used.
     *
     * <p>An alias's ARN is the name it was given, which the request carries with the
     * {@code alias/} its own name starts with, so the ARN is that name without the prefix the
     * alias's own resource part carries.
     */
    @Override
    public Optional<String> find(String type, String name, String region) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return switch (type) {
            case "key" -> Optional.ofNullable(findKey(region, name)).map(PaymentCryptographyKey::getArn);
            case "alias" -> Optional.of(aliasArn(region, name));
            default -> Optional.empty();
        };
    }

    /**
     * The ARN of the alias of the name in the Region, which the name gives whether or not the
     * alias exists yet, as a create names the alias it makes.
     */
    private String aliasArn(String region, String name) {
        String alias = name.startsWith("alias/") ? name.substring("alias/".length()) : name;
        return regionResolver.buildArn(SERVICE, region, "alias/" + alias);
    }

    /**
     * The storage key of an alias.
     *
     * <p>The name and the region together, because the name is unique in the
     * region and not in the account.
     */
    private static String aliasKey(String aliasName, String region) {
        return region + "\u0000" + aliasName;
    }

    private static String string(Map<String, Object> attributes, String name, String fallback) {
        Object value = attributes.get(name);
        return value == null ? fallback : String.valueOf(value);
    }

    /**
     * The usage of the key the attributes name.
     *
     * <p>The service requires it, as the reference does: a key's usage says
     * what the key is for, and a key without one is not a key.
     */
    private static String usage(Map<String, Object> attributes) {
        Object value = attributes.get("KeyUsage");
        if (value == null || String.valueOf(value).isBlank()) {
            throw new AwsException("ValidationException", "KeyUsage is required", 400);
        }
        return String.valueOf(value);
    }

    @Override
    public void clear() {
        keyStore.clear();
        aliasStore.clear();
    }
}
