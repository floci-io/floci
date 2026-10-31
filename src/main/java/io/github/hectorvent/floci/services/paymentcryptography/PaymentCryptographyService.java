package io.github.hectorvent.floci.services.paymentcryptography;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
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
import java.util.Locale;
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
public class PaymentCryptographyService implements Resettable {

    /** The storage key, which is the one the catalog's descriptor names. */
    private static final String STORAGE_KEY = "paymentcryptography";

    /** The service in the ARN, which is the service's own name. */
    private static final String SERVICE = "payment-cryptography";

    private final StorageBackend<String, PaymentCryptographyKey> keyStore;
    private final StorageBackend<String, PaymentCryptographyAlias> aliasStore;
    private final RegionResolver regionResolver;
    private final SecureRandom secureRandom;

    @Inject
    public PaymentCryptographyService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create(STORAGE_KEY, "payment-cryptography-keys.json",
                        new TypeReference<>() {}),
                storageFactory.create(STORAGE_KEY, "payment-cryptography-aliases.json",
                        new TypeReference<>() {}),
                regionResolver,
                new SecureRandom());
    }

    PaymentCryptographyService(StorageBackend<String, PaymentCryptographyKey> keyStore,
                                StorageBackend<String, PaymentCryptographyAlias> aliasStore,
                                RegionResolver regionResolver) {
        this(keyStore, aliasStore, regionResolver, new SecureRandom());
    }

    PaymentCryptographyService(StorageBackend<String, PaymentCryptographyKey> keyStore,
                                StorageBackend<String, PaymentCryptographyAlias> aliasStore,
                                RegionResolver regionResolver,
                                SecureRandom secureRandom) {
        this.keyStore = keyStore;
        this.aliasStore = aliasStore;
        this.regionResolver = regionResolver;
        this.secureRandom = secureRandom;
    }

    // ---- keys ---------------------------------------------------------------

    /** Creates a key with fresh AES-256 material. */
    public PaymentCryptographyKey createKey(String region, Map<String, Object> attributes,
                                            Map<String, Boolean> modesOfUse, boolean exportable) {
        PaymentCryptographyKey key = new PaymentCryptographyKey();
        key.setKeyId(UUID.randomUUID().toString().replace("-", ""));
        key.setRegion(region);
        key.setArn(regionResolver.buildArn(SERVICE, region, "key/" + key.getKeyId()));
        key.setKeyAlgorithm(string(attributes, "KeyAlgorithm", "AES_256"));
        key.setKeyClass(string(attributes, "KeyClass", "SYMMETRIC_KEY"));
        key.setKeyUsage(string(attributes, "KeyUsage", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY"));
        key.setKeyModesOfUse(modesOfUse);
        key.setExportable(exportable);
        key.setEnabled(true);
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
            PaymentCryptographyAlias alias = findAlias(region, identifier);
            return alias == null ? null : keyStore.get(alias.getKeyId()).orElse(null);
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

    /** The keys of a region. */
    public List<PaymentCryptographyKey> listKeys(String region) {
        List<PaymentCryptographyKey> out = new ArrayList<>();
        for (PaymentCryptographyKey key : keyStore.scan(k -> true)) {
            if (region.equals(key.getRegion())) {
                out.add(key);
            }
        }
        return out;
    }

    /** Removes a key. The service deletes a key the moment it is asked to. */
    public void deleteKey(PaymentCryptographyKey key) {
        keyStore.delete(key.getKeyId());
        for (PaymentCryptographyAlias alias : aliasStore.scan(a -> true)) {
            if (key.getKeyId().equals(alias.getKeyId())) {
                aliasStore.delete(aliasKey(alias.getAliasName(), alias.getRegion()));
            }
        }
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

    /** Points a name at a key. The name must be free in the key's region. */
    public synchronized PaymentCryptographyAlias createAlias(String aliasName, PaymentCryptographyKey key) {
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

    /** Removes a name from a region. */
    public synchronized void deleteAlias(String region, String aliasName) {
        if (findAlias(region, aliasName) == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Alias " + aliasName + " is not found", 400);
        }
        aliasStore.delete(aliasKey(aliasName, region));
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
    public void tag(PaymentCryptographyKey key, Map<String, String> tags) {
        key.getTags().putAll(tags);
        keyStore.put(key.getKeyId(), key);
    }

    /** Removes tags from a key, in this region only. */
    public void untag(PaymentCryptographyKey key, List<String> tagKeys) {
        for (String tagKey : tagKeys) {
            key.getTags().remove(tagKey);
        }
        keyStore.put(key.getKeyId(), key);
    }

    /** The tags of a key. */
    public Map<String, String> listTags(PaymentCryptographyKey key) {
        return new LinkedHashMap<>(key.getTags());
    }

    // ---- helpers ------------------------------------------------------------

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

    /** The account that owns the key, from the region's own ARN. */
    public String accountId() {
        return regionResolver.getAccountId();
    }

    /** The region a key belongs to, lower-cased the way the service reports it. */
    public static String normalizeRegion(String region) {
        return region == null ? null : region.toLowerCase(Locale.ROOT);
    }

    @Override
    public void clear() {
        keyStore.clear();
        aliasStore.clear();
    }

    /** The key store, for the tests. */
    Optional<PaymentCryptographyKey> storedKey(String keyId) {
        return keyStore.get(keyId);
    }
}
