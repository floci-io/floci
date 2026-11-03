package io.github.hectorvent.floci.services.paymentcryptography;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.StoredResourceArns;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.paymentcryptography.model.PaymentCryptographyAlias;
import io.github.hectorvent.floci.services.paymentcryptography.model.PaymentCryptographyKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.KeyPair;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /** The only class this service makes material for. */
    private static final String SUPPORTED_CLASS = "SYMMETRIC_KEY";

    /** The class of a key pair, which has a public half and a private one. */
    private static final String ASYMMETRIC_CLASS = "ASYMMETRIC_KEY_PAIR";

    /** The class of a key that is only a public half. */
    private static final String PUBLIC_KEY_CLASS = "PUBLIC_KEY";

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

    /**
     * The service over two stores, with a request context of its own.
     *
     * <p>The context belongs to a request in the running service, so a caller that is not
     * one, such as a test, is given one that resolves its aliases itself.
     */
    PaymentCryptographyService(StorageBackend<String, PaymentCryptographyKey> keyStore,
                                StorageBackend<String, PaymentCryptographyAlias> aliasStore,
                                RegionResolver regionResolver) {
        this(keyStore, aliasStore, regionResolver, new SecureRandom(),
                new PaymentCryptographyRequestContext());
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
        return makeKey(region, attributes, modesOfUse, exportable, enabled, tags, null);
    }

    /**
     * Makes a key, with the material it is given rather than with fresh material.
     *
     * <p>An imported key holds the material that came in with it, so the material is a
     * parameter of the key that is made.
     */
    private PaymentCryptographyKey makeKey(String region, Map<String, Object> attributes,
                                           Map<String, Boolean> modesOfUse, boolean exportable,
                                           boolean enabled, Map<String, String> tags,
                                           String materialHex) {
        return makeKey(region, attributes, modesOfUse, exportable, enabled, tags, materialHex,
                null, null, null);
    }

    /**
     * Makes a key, with the material it is given rather than with fresh material.
     *
     * <p>An imported key holds the material that came in with it, so the material is a
     * parameter of the key that is made. An asymmetric key holds a key pair rather than
     * material, so its halves are parameters too.
     *
     * @param publicKeyBase64 the public half of an asymmetric key, or null
     * @param privateKeyBase64 the private half of an asymmetric key, or null
     * @param certificatePem the certificate an imported public key arrived with, or null
     */
    private PaymentCryptographyKey makeKey(String region, Map<String, Object> attributes,
                                           Map<String, Boolean> modesOfUse, boolean exportable,
                                           boolean enabled, Map<String, String> tags,
                                           String materialHex, String publicKeyBase64,
                                           String privateKeyBase64, String certificatePem) {
        String algorithmName = string(attributes, "KeyAlgorithm", "AES_256");
        String keyClass = string(attributes, "KeyClass", "SYMMETRIC_KEY");
        // The material has to be the algorithm's, so a key that says otherwise would be a key
        // whose attributes do not describe it.
        PaymentCryptographyKeyAlgorithm algorithm = PaymentCryptographyKeyAlgorithm.from(algorithmName);
        if (algorithm == null) {
            throw new AwsException("ValidationException",
                    "KeyAlgorithm '" + algorithmName + "' is not supported, this service makes "
                            + PaymentCryptographyKeyAlgorithm.supportedAll() + " keys", 400);
        }
        if (!SUPPORTED_CLASS.equals(keyClass) && !ASYMMETRIC_CLASS.equals(keyClass)
                && !PUBLIC_KEY_CLASS.equals(keyClass)) {
            throw new AwsException("ValidationException",
                    "KeyClass '" + keyClass + "' is not supported, this service makes "
                            + SUPPORTED_CLASS + ", " + ASYMMETRIC_CLASS + " and " + PUBLIC_KEY_CLASS
                            + " keys", 400);
        }
        // The class says what shape the key has, and the algorithm says what material it
        // has, so the two must agree: a symmetric algorithm cannot make a key pair, and an
        // asymmetric one cannot make a symmetric key.
        boolean symmetric = algorithm.symmetric();
        if (symmetric && !SUPPORTED_CLASS.equals(keyClass)) {
            throw new AwsException("ValidationException",
                    "KeyAlgorithm '" + algorithmName + "' is a symmetric algorithm, so the key is a "
                            + SUPPORTED_CLASS + " and not a " + keyClass, 400);
        }
        if (!symmetric && SUPPORTED_CLASS.equals(keyClass)) {
            throw new AwsException("ValidationException",
                    "KeyAlgorithm '" + algorithmName + "' is an asymmetric algorithm, so the key is a "
                            + ASYMMETRIC_CLASS + " or a " + PUBLIC_KEY_CLASS + " and not a "
                            + SUPPORTED_CLASS, 400);
        }
        // The usage says what the key is for, so a key without one is not a key: the service
        // refuses it rather than guessing a purpose for it.
        String usage = usage(attributes);
        // The usage binds the key to its purpose, and a key whose algorithm does not serve
        // that purpose is not one the service makes.
        String badAlgorithm = PaymentCryptographyKeyAlgorithm.refusal(usage, algorithm);
        if (badAlgorithm != null) {
            throw new AwsException("ValidationException", badAlgorithm, 400);
        }
        PaymentCryptographyKey key = new PaymentCryptographyKey();
        key.setKeyId(UUID.randomUUID().toString().replace("-", ""));
        key.setRegion(region);
        key.setArn(regionResolver.buildArn(SERVICE, region, "key/" + key.getKeyId()));
        key.setKeyAlgorithm(algorithm.name());
        key.setKeyClass(keyClass);
        key.setKeyUsage(usage);
        key.setDeriveKeyUsage(string(attributes, "DeriveKeyUsage", ""));
        key.setKeyModesOfUse(modesOfUse);
        key.setExportable(exportable);
        key.setEnabled(enabled);
        key.setTags(new LinkedHashMap<>(tags));
        key.setKeyState("CREATE_COMPLETE");
        key.setKeyMaterialHex(materialHex);
        key.setKeyPublicKeyBase64(publicKeyBase64);
        key.setKeyPrivateKeyBase64(privateKeyBase64);
        key.setKeyCertificatePem(certificatePem);
        if (algorithm.symmetric()) {
            key.setKeyMaterialHex(materialHex != null
                    ? materialHex
                    : PaymentCryptographyCrypto.randomMaterialHex(algorithm, secureRandom));
            key.setKeyCheckValue(
                    PaymentCryptographyCrypto.checkValue(key.getKeyMaterialHex(), algorithm));
            key.setKeyCheckValueAlgorithm(algorithm.checkValueAlgorithm());
        } else {
            // An asymmetric key has no material a check value could be computed from, so it
            // reports the fixed placeholder a public key does.
            key.setKeyCheckValue("000000");
            key.setKeyCheckValueAlgorithm(algorithm.checkValueAlgorithm());
            if (key.getKeyPublicKeyBase64() == null) {
                KeyPair pair = algorithm.rsa
                        ? PaymentCryptographyKeyPairs.rsa(algorithm.bytes * 8, secureRandom)
                        : PaymentCryptographyKeyPairs.ec(algorithm, secureRandom);
                key.setKeyPublicKeyBase64(Base64.getEncoder()
                        .encodeToString(pair.getPublic().getEncoded()));
                if (!PUBLIC_KEY_CLASS.equals(keyClass)) {
                    key.setKeyPrivateKeyBase64(Base64.getEncoder()
                            .encodeToString(pair.getPrivate().getEncoded()));
                }
            }
        }
        long now = Instant.now().getEpochSecond();
        key.setCreateTimestamp(now);
        key.setUsageStartTimestamp(now);
        keyStore.put(storageKey(region, key.getKeyId()), key);
        return key;
    }

    /** The key an identifier names, or null when there is none. */
    public PaymentCryptographyKey findKey(String region, String identifier) {        if (identifier == null || identifier.isEmpty()) {
            return null;
        }
        if (identifier.startsWith("alias/")) {
            return keyOfAlias(region, identifier);
        }
        if (AwsArnUtils.looksLikeArn(identifier)) {
            Optional<String> resource = AwsArnUtils.resourceIfArnFor(identifier, SERVICE);
            if (resource.isPresent() && resource.get().startsWith("alias/")) {
                // The ARN names the alias in the Region, the account and the partition it was
                // minted in, so an alias of another one is not this Region's alias.
                String name = "alias/" + resource.get().substring("alias/".length());
                if (!identifier.equals(aliasArn(region, name))) {
                    return null;
                }
                return keyOfAlias(region, name);
            }
            for (PaymentCryptographyKey key : keyStore.scan(k -> true)) {
                if (identifier.equals(key.getArn()) && region.equals(key.getRegion())) {
                    return key;
                }
            }
            return null;
        }
        PaymentCryptographyKey byId = findKeyRecord(keyStore, region, identifier);
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
     * the pending timestamp of that deletion.
     *
     * <p>The service deletes a key the moment it is asked to, so the waiting
     * period the service offers is not one: the key is gone when the answer
     * goes out. The answer carries only the pending timestamp of that
     * deletion; the delete timestamp stays unset, because the key is already
     * gone when the answer goes out.
     *
     * <p>The lock is the same one the tagging operations take, so a key that
     * is being tagged cannot be written back after it was deleted.
     */
    public synchronized PaymentCryptographyKey deleteKey(PaymentCryptographyKey key) {
        long now = Instant.now().getEpochSecond();
        key.setKeyState("DELETE_PENDING");
        key.setEnabled(false);
        key.setDeletePendingTimestamp(now);
        keyStore.delete(storageKey(key.getRegion(), key.getKeyId()));
        // A key saved before the region became part of its name is under that name too,
        // and it goes with the key: a record left behind would be a key that was deleted
        // and still there.
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
        return findAliasRecord(region, aliasName);
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
        // A key saved before the region became part of its name is still found under that
        // name, so an alias written then keeps working.
        return keyId.isEmpty() ? null : findKeyRecord(keyStore, region, keyId);
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
        storeKey(current);
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
        PaymentCryptographyKey current =
                findKeyRecord(keyStore, key.getRegion(), key.getKeyId());
        if (current == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Key " + key.getArn() + " is not found", 400);
        }
        return current;
    }

    /**
     * Writes a key back, under the name the store holds it under now.
     *
     * <p>A record that was found under the older name is moved to the current one, so the
     * store holds one record of the key and not two.
     *
     * @param key the key to write
     */
    private void storeKey(PaymentCryptographyKey key) {
        String current = storageKey(key.getRegion(), key.getKeyId());
        keyStore.put(current, key);
        if (!key.getKeyId().equals(current)) {
            keyStore.delete(key.getKeyId());
        }
    }

    /** Removes tags from a key, in this region only. */
    public synchronized void untag(PaymentCryptographyKey key, List<String> tagKeys) {
        PaymentCryptographyKey current = liveKey(key);
        for (String tagKey : tagKeys) {
            current.getTags().remove(tagKey);
        }
        storeKey(current);
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

    // ---- the replication ------------------------------------------------------

    // ---- the key exchange ----------------------------------------------------

    /**
     * The usages a key block may carry, and the modes of use each of them has.
     *
     * <p>A key block carries the usage of the key it carries, so every usage the service
     * makes has to have a code, or a key of that usage cannot leave the service and the
     * counterparty cannot tell what it has.
     */
    private static final Map<String, String> USAGE_NAMES = Map.ofEntries(
            Map.entry("B0", "TR31_B0_BASE_DERIVATION_KEY"),
            Map.entry("C0", "TR31_C0_CARD_VERIFICATION_KEY"),
            Map.entry("D0", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY"),
            Map.entry("E0", "TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS"),
            Map.entry("E1", "TR31_E1_EMV_MKEY_CONFIDENTIALITY"),
            Map.entry("E2", "TR31_E2_EMV_MKEY_INTEGRITY"),
            Map.entry("E4", "TR31_E4_EMV_MKEY_DYNAMIC_NUMBERS"),
            Map.entry("E5", "TR31_E5_EMV_MKEY_CARD_PERSONALIZATION"),
            Map.entry("E6", "TR31_E6_EMV_MKEY_OTHER"),
            Map.entry("K0", "TR31_K0_KEY_ENCRYPTION_KEY"),
            Map.entry("K1", "TR31_K1_KEY_BLOCK_PROTECTION_KEY"),
            Map.entry("M0", "TR31_M0_ISO_16609_MAC_KEY"),
            Map.entry("M1", "TR31_M1_ISO_9797_1_MAC_KEY"),
            Map.entry("M3", "TR31_M3_ISO_9797_3_MAC_KEY"),
            Map.entry("M6", "TR31_M6_ISO_9797_5_CMAC_KEY"),
            Map.entry("M7", "TR31_M7_HMAC_KEY"),
            Map.entry("P0", "TR31_P0_PIN_ENCRYPTION_KEY"),
            Map.entry("P1", "TR31_P1_PIN_GENERATION_KEY"),
            Map.entry("V1", "TR31_V1_IBM3624_PIN_VERIFICATION_KEY"),
            Map.entry("V2", "TR31_V2_VISA_PIN_VERIFICATION_KEY"),
            Map.entry("S0", "TR31_S0_ASYMMETRIC_KEY_FOR_DIGITAL_SIGNATURE"));

    /** The TR-31 usage code of a usage name, such as {@code D0}. */
    static String usageCode(String usageName) {
        for (Map.Entry<String, String> entry : USAGE_NAMES.entrySet()) {
            if (entry.getValue().equals(usageName)) {
                return entry.getKey();
            }
        }
        throw new AwsException("ValidationException",
                "the usage " + usageName + " cannot leave the service: a key block carries one of "
                        + USAGE_NAMES.keySet(), 400);
    }

    /**
     * Whether a key is an AES key rather than a TDES one.
     *
     * <p>The key record knows its algorithm, so the family is read from there and not guessed
     * from the length of the material, because a 16-byte key is a two-key TDES one and an
     * AES-128 one, and a 24-byte key is a three-key TDES one and an AES-192 one.
     */
    static boolean isAes(PaymentCryptographyKey key) {
        PaymentCryptographyKeyAlgorithm algorithm =
                PaymentCryptographyKeyAlgorithm.from(key.getKeyAlgorithm());
        return algorithm != null && algorithm.aes;
    }

    /**
     * The algorithm byte of a key: {@code A} for an AES key and {@code T} for a TDES one.
     *
     * <p>The key record knows its algorithm, so the byte is read from there and not guessed from
     * the length of the material, because a TDES key and an AES key of the same length are
     * different keys.
     */
    static String algorithmByte(PaymentCryptographyKey key) {
        return "AES_256".equals(key.getKeyAlgorithm()) || "AES_192".equals(key.getKeyAlgorithm())
                || "AES_128".equals(key.getKeyAlgorithm()) ? "A" : "T";
    }

    /** The usage name of a TR-31 usage code, such as {@code TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY}. */
    static String usageName(String usageCode) {
        String usage = USAGE_NAMES.get(usageCode);
        if (usage == null) {
            throw new AwsException("ValidationException",
                    "the usage " + usageCode + " of the key block is not one this service serves: "
                            + USAGE_NAMES.keySet(), 400);
        }
        return usage;
    }

    /**
     * The modes of use a mode of use code of a key block stands for.
     *
     * <p>A key encryption key wraps and unwraps rather than encrypts and decrypts, so the
     * same character means the other modes for one.
     *
     * @param mode the mode of use character of the block
     * @param isKek true when the block carries a key encryption key
     */
    static Map<String, Boolean> modesFor(String mode, boolean isKek) {
        return switch (mode) {
            case "B" -> isKek
                    ? Map.of("Wrap", Boolean.TRUE, "Unwrap", Boolean.TRUE)
                    : Map.of("Encrypt", Boolean.TRUE, "Decrypt", Boolean.TRUE);
            case "E" -> isKek
                    ? Map.of("Wrap", Boolean.TRUE)
                    : Map.of("Encrypt", Boolean.TRUE);
            case "D" -> isKek
                    ? Map.of("Unwrap", Boolean.TRUE)
                    : Map.of("Decrypt", Boolean.TRUE);
            case "G" -> Map.of("Generate", Boolean.TRUE);
            case "V" -> Map.of("Verify", Boolean.TRUE);
            case "C" -> Map.of("Generate", Boolean.TRUE, "Verify", Boolean.TRUE);
            case "X" -> Map.of("DeriveKey", Boolean.TRUE);
            case "N" -> Map.of("NoRestrictions", Boolean.TRUE);
            default -> throw new AwsException("ValidationException",
                    "the mode of use " + mode + " of the key block is not one this service serves", 400);
        };
    }

    /**
     * The mode of use character a key block carries.
     *
     * <p>The character is read off the key's own modes, because that is what it says: a key
     * that encrypts and decrypts is a {@code B}, one that only encrypts is an {@code E}. A key
     * encryption key wraps and unwraps rather than encrypts and decrypts, so its modes are read
     * that way.
     *
     * @param key the key the block carries
     * @return the character
     */
    static String modeCode(PaymentCryptographyKey key) {
        Map<String, Boolean> modes = key.getKeyModesOfUse();
        boolean isKek = isKeyEncryptionKey(key.getKeyUsage());
        boolean encrypt = isKek
                ? enabled(modes, "Wrap") || enabled(modes, "Encrypt")
                : enabled(modes, "Encrypt");
        boolean decrypt = isKek
                ? enabled(modes, "Unwrap") || enabled(modes, "Decrypt")
                : enabled(modes, "Decrypt");
        boolean generate = enabled(modes, "Generate");
        boolean verify = enabled(modes, "Verify");
        boolean derive = enabled(modes, "DeriveKey");
        if (encrypt && decrypt) {
            return "B";
        }
        if (encrypt) {
            return "E";
        }
        if (decrypt) {
            return "D";
        }
        if (generate && verify) {
            return "C";
        }
        if (generate) {
            return "G";
        }
        if (verify) {
            return "V";
        }
        if (derive) {
            return "X";
        }
        return "N";
    }

    /** Whether a mode of use is on. A key with no modes at all has every one of them. */
    private static boolean enabled(Map<String, Boolean> modes, String mode) {
        return modes == null || Boolean.TRUE.equals(modes.get(mode));
    }

    /** Whether a usage is a key encryption key's, whose modes wrap and unwrap. */
    static boolean isKeyEncryptionKey(String usageName) {
        return "TR31_K0_KEY_ENCRYPTION_KEY".equals(usageName)
                || "TR31_K1_KEY_BLOCK_PROTECTION_KEY".equals(usageName);
    }

    /** Whether a key may wrap another key. */
    static boolean mayWrap(PaymentCryptographyKey key) {
        Map<String, Boolean> modes = key.getKeyModesOfUse();
        return modes == null || Boolean.TRUE.equals(modes.get("Wrap"))
                || Boolean.TRUE.equals(modes.get("Encrypt"));
    }

    /** Whether a key may unwrap another key. */
    static boolean mayUnwrap(PaymentCryptographyKey key) {
        Map<String, Boolean> modes = key.getKeyModesOfUse();
        return modes == null || Boolean.TRUE.equals(modes.get("Unwrap"))
                || Boolean.TRUE.equals(modes.get("Decrypt"));
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
    public Optional<String> find(String type, List<String> names, String region) {
        if (names.size() != 1 || names.getFirst() == null || names.getFirst().isBlank()) {
            return Optional.empty();
        }
        String name = names.getFirst();
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
     * The storage key of a key.
     *
     * <p>The region is part of it, because a multi-region key has one identifier in several
     * regions, and each of them is a key of its own region.
     */
    private static String storageKey(String region, String keyId) {
        return region + "\u0000" + keyId;
    }

    /**
     * The key a store holds under the name it was saved under.
     *
     * <p>A store written before the region became part of the name holds its keys under
     * their identifier alone, and its aliases under their name alone. A record saved then is
     * still found here, so a store that was not emptied keeps working.
     *
     * @param store the store to look in
     * @param region the region the key is in
     * @param keyId the key's identifier
     * @param <T> what the store holds
     * @return the key, or null when the store holds none
     */
    private static <T> T findKeyRecord(StorageBackend<String, T> store, String region,
                                       String keyId) {
        Optional<T> current = store.get(storageKey(region, keyId));
        if (current.isPresent()) {
            return current.get();
        }
        return store.get(keyId).orElse(null);
    }

    /**
     * The alias a store holds under the name it was saved under.
     *
     * <p>An alias saved before the region became part of the name is still found here.
     *
     * @param region the region the alias is in
     * @param aliasName the alias's name
     * @return the alias, or null when the store holds none
     */
    private PaymentCryptographyAlias findAliasRecord(String region, String aliasName) {
        PaymentCryptographyAlias current = aliasStore.get(aliasKey(aliasName, region)).orElse(null);
        return current != null ? current : aliasStore.get(aliasName).orElse(null);
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

    /** The key store, for the tests. */
    Optional<PaymentCryptographyKey> storedKey(String region, String keyId) {
        return keyStore.get(storageKey(region, keyId));
    }
}
