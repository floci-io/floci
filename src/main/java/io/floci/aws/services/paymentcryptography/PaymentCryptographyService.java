package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.acm.CertificateGenerator;
import io.floci.aws.services.paymentcryptography.crypto.DukptCrypto;
import io.floci.aws.services.paymentcryptography.model.PaymentCryptographyAlias;
import io.floci.aws.services.paymentcryptography.model.PaymentCryptographyKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.KeyPair;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
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

    /** The origin of a key the service made itself. */
    private static final String ORIGIN_SERVICE = "AWS_PAYMENT_CRYPTOGRAPHY";

    /** The origin of a key whose material came in from outside the service. */
    private static final String ORIGIN_EXTERNAL = "EXTERNAL";

    /**
     * The storage key the account's default replication regions are kept under.
     *
     * <p>The store is account-aware, so what is under this key belongs to the account that
     * put it there and to no other.
     */
    private static final String DEFAULT_REGIONS_KEY = "default-replication-regions";

    private final StorageBackend<String, PaymentCryptographyKey> keyStore;
    private final StorageBackend<String, PaymentCryptographyAlias> aliasStore;

    /** The key exchange: the forms a key may leave in, and the certificates that carry them. */
    private final PaymentCryptographyKeyExchange exchange;
    private final StorageBackend<String, List<String>> settingsStore;
    private final RegionResolver regionResolver;
    private final SecureRandom secureRandom;
    private final PaymentCryptographyRequestContext requestContext;

    @Inject
    public PaymentCryptographyService(StorageFactory storageFactory, RegionResolver regionResolver,
                                      PaymentCryptographyRequestContext requestContext,
                                      CertificateGenerator certificates) {
        this(storageFactory.create(STORAGE_KEY, "payment-cryptography-keys.json",
                        new TypeReference<>() {}),
                storageFactory.create(STORAGE_KEY, "payment-cryptography-aliases.json",
                        new TypeReference<>() {}),
                storageFactory.create(STORAGE_KEY, "payment-cryptography-settings.json",
                        new TypeReference<Map<String, List<String>>>() {}),
                regionResolver,
                new SecureRandom(),
                requestContext,
                certificates,
                Clock.systemUTC());
    }

    PaymentCryptographyService(StorageBackend<String, PaymentCryptographyKey> keyStore,
                                StorageBackend<String, PaymentCryptographyAlias> aliasStore,
                                RegionResolver regionResolver,
                                PaymentCryptographyRequestContext requestContext) {
        this(keyStore, aliasStore, new InMemoryStorage<>(), regionResolver, new SecureRandom(),
                requestContext, new CertificateGenerator(), Clock.systemUTC());
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
        this(keyStore, aliasStore, new InMemoryStorage<>(), regionResolver, new SecureRandom(),
                new PaymentCryptographyRequestContext(), new CertificateGenerator(), Clock.systemUTC());
    }

    PaymentCryptographyService(StorageBackend<String, PaymentCryptographyKey> keyStore,
                                StorageBackend<String, PaymentCryptographyAlias> aliasStore,
                                RegionResolver regionResolver,
                                SecureRandom secureRandom,
                                PaymentCryptographyRequestContext requestContext) {
        this(keyStore, aliasStore, new InMemoryStorage<>(), regionResolver, secureRandom,
                requestContext, new CertificateGenerator(), Clock.systemUTC());
    }

    PaymentCryptographyService(StorageBackend<String, PaymentCryptographyKey> keyStore,
                                StorageBackend<String, PaymentCryptographyAlias> aliasStore,
                                RegionResolver regionResolver,
                                SecureRandom secureRandom,
                                PaymentCryptographyRequestContext requestContext,
                                CertificateGenerator certificates,
                                Clock clock) {
        this(keyStore, aliasStore, new InMemoryStorage<>(), regionResolver, secureRandom,
                requestContext, certificates, clock);
    }

    PaymentCryptographyService(StorageBackend<String, PaymentCryptographyKey> keyStore,
                                StorageBackend<String, PaymentCryptographyAlias> aliasStore,
                                StorageBackend<String, List<String>> settingsStore,
                                RegionResolver regionResolver,
                                SecureRandom secureRandom,
                                PaymentCryptographyRequestContext requestContext,
                                CertificateGenerator certificates,
                                Clock clock) {
        this.keyStore = keyStore;
        this.aliasStore = aliasStore;
        this.settingsStore = settingsStore;
        this.regionResolver = regionResolver;
        this.exchange = new PaymentCryptographyKeyExchange(this, certificates, clock);
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
                null, null, null, ORIGIN_SERVICE);
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
     * @param origin where the key material came from
     */
    private PaymentCryptographyKey makeKey(String region, Map<String, Object> attributes,
                                           Map<String, Boolean> modesOfUse, boolean exportable,
                                           boolean enabled, Map<String, String> tags,
                                           String materialHex, String publicKeyBase64,
                                           String privateKeyBase64, String certificatePem,
                                           String origin) {
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
        key.setKeyOrigin(origin);
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
        // A replica is read-only: only the primary may be deleted.
        requireNotReplica(key, "only the primary may be deleted");
        long now = Instant.now().getEpochSecond();
        key.setKeyState("DELETE_PENDING");
        key.setEnabled(false);
        key.setDeletePendingTimestamp(now);
        // The replicas of a key go when the key goes. A replica left behind would be a
        // record of a key that is gone, and it could not be deleted on its own.
        for (String target : key.getReplicationRegions()) {
            keyStore.delete(storageKey(target, key.getKeyId()));
        }
        keyStore.delete(storageKey(key.getRegion(), key.getKeyId()));
        // A key saved before the region became part of its name is under that name too,
        // and it goes with the key: a record left behind would be a key that was deleted
        // and still there.
        keyStore.delete(key.getKeyId());
        // The aliases that name the key go with it, in every region: a replica shares the
        // identifier of its primary, so these are the aliases of the replicas too.
        deleteAliasesOf(null, key.getKeyId());
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
     * <p>A replica is a copy of the primary's metadata, so it carries the primary's tags,
     * and a change made on the primary reaches the replicas. A replica is read-only, so the
     * change is made on the primary.
     */
    public synchronized void tag(PaymentCryptographyKey key, Map<String, String> tags) {
        PaymentCryptographyKey current = liveKey(key);
        requireNotReplica(current, "a replica is read-only");
        Map<String, String> updated = new LinkedHashMap<>(current.getTags());
        updated.putAll(tags);
        current.setTags(updated);
        storeKey(current);
        refreshReplicas(current, current.getReplicationRegions());
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

    /**
     * Removes tags from a key.
     *
     * <p>A replica is read-only, so the change is made on the primary and reaches the
     * replicas.
     */
    public synchronized void untag(PaymentCryptographyKey key, List<String> tagKeys) {
        PaymentCryptographyKey current = liveKey(key);
        requireNotReplica(current, "a replica is read-only");
        Map<String, String> updated = new LinkedHashMap<>(current.getTags());
        tagKeys.forEach(updated::remove);
        current.setTags(updated);
        storeKey(current);
        refreshReplicas(current, current.getReplicationRegions());
    }

    /**
     * The replicas of a primary brought up to date with it.
     *
     * <p>A replica is a copy of the primary's material and metadata, and a change to the
     * primary's metadata is replicated to the replicas, as the reference says. The copy is
     * in one place, so every change to a primary reaches its replicas the same way.
     *
     * @param primary the key the change was made on
     * @param regions the regions the replicas are in
     */
    private void refreshReplicas(PaymentCryptographyKey primary, Collection<String> regions) {
        for (String target : regions) {
            PaymentCryptographyKey replica = findKeyRecord(keyStore, target, primary.getKeyId());
            if (replica == null) {
                continue;
            }
            replica.setTags(new LinkedHashMap<>(primary.getTags()));
            replica.setKeyState(primary.getKeyState());
            replica.setEnabled(primary.isEnabled());
            replica.setUsingDefaultReplicationRegions(primary.isUsingDefaultReplicationRegions());
            replica.setPrimaryRegion(primary.getPrimaryRegion());
            replica.setReplicationRegions(new ArrayList<>(primary.getReplicationRegions()));
            keyStore.put(storageKey(target, replica.getKeyId()), replica);
        }
    }

    /**
     * The refusal of a request that names a replica.
     *
     * <p>A replica is read-only: only the primary may be deleted, replicated further or
     * changed, so a change made on the replica itself is refused. The check is in one place,
     * so every operation that names a replica refuses it in the same words.
     *
     * @param key the key the request names
     * @param what what the request wanted to do with the key, for the message
     */
    private static void requireNotReplica(PaymentCryptographyKey key, String what) {
        if ("REPLICA".equals(key.getMultiRegionKeyType())) {
            throw new AwsException("ValidationException",
                    "the key " + key.getArn() + " is a replica of a key in "
                            + key.getPrimaryRegion() + ", and " + what, 400);
        }
    }

    /**
     * The tags of a key.
     *
     * <p>The tagging operations put a new map in place of the held one, so the copy is of one
     * whole set; it still takes their lock, so it reads the set the last of them left.
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

    // ---- the key exchange ----------------------------------------------------

    /**
     * The key as it leaves the service, in the form the request names.
     *
     * <p>A key that is not exportable does not leave. The form decides how it is protected: a
     * TR-31 block under a key the service holds, an RSA-OAEP cryptogram under the receiver's
     * certificate, a TR-34 block the service signs, a block under a key the two parties
     * agreed, or an AS2805 cryptogram.
     *
     * @param region the region the key is in
     * @param keyIdentifier the key that leaves
     * @param form the form the request names
     * @param material the members of the form
     * @return the material, the format it is in, and the key that protects it
     */
    public Map<String, Object> exportKey(String region, String keyIdentifier, String form,
                                          Map<String, Object> material, String keySerialNumber,
                                          String checkValueAlgorithm) {
        PaymentCryptographyKey key = findKey(region, keyIdentifier);
        if (key == null) {
            throw new AwsException("ResourceNotFoundException", "Key not found: " + keyIdentifier, 400);
        }
        PaymentCryptographyKeyRules.requireEnabled(key);
        // An initial key leaves instead of the key itself: the service derives it and does
        // not keep it.
        PaymentCryptographyKey leaving = keySerialNumber.isEmpty() ? key : initialKey(key, keySerialNumber);
        if (!checkValueAlgorithm.isEmpty()
                && !checkValueAlgorithm.equals(leaving.getKeyCheckValueAlgorithm())) {
            throw new AwsException("ValidationException",
                    "the check value algorithm " + checkValueAlgorithm + " is not the one the key "
                            + leaving.getArn() + " has, which is "
                            + leaving.getKeyCheckValueAlgorithm(), 400);
        }
        if (!leaving.isExportable() && keySerialNumber.isEmpty()) {
            throw new AwsException("ValidationException",
                    "the key " + key.getArn() + " is not exportable", 400);
        }
        PaymentCryptographyKeyExchange.Exported exported = exchange.export(region, leaving, form, material);
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("KeyMaterial", exported.material());
        wrapped.put("WrappedKeyMaterialFormat", exported.format());
        // The check value belongs to a key block: a cryptogram carries no block, so it has no
        // check value either, which is what the reference answers.
        if (!"KEY_CRYPTOGRAM".equals(exported.format())) {
            wrapped.put("KeyCheckValue", leaving.getKeyCheckValue());
            wrapped.put("KeyCheckValueAlgorithm", leaving.getKeyCheckValueAlgorithm());
        }
        if (exported.wrappingKeyArn() != null) {
            wrapped.put("WrappingKeyArn", exported.wrappingKeyArn());
        }
        return Map.of("WrappedKey", wrapped);
    }

    /**
     * The key that comes into the service, in the form the request names.
     *
     * @param region the region the key comes into
     * @param form the form the request names
     * @param material the members of the form
     * @return the key
     */
    public PaymentCryptographyKey importKey(String region, String form, Map<String, Object> material,
                                         String checkValueAlgorithm, boolean enabled,
                                         Map<String, String> tags) {
        return importKey(region, form, material, checkValueAlgorithm, enabled, tags, List.of());
    }

    /**
     * Imports a key and replicates it to the regions the request names.
     *
     * <p>The model gives an import the same replication regions a creation does, so a key
     * that comes in can be replicated as soon as it lands.
     */
    public PaymentCryptographyKey importKey(String region, String form, Map<String, Object> material,
                                         String checkValueAlgorithm, boolean enabled,
                                         Map<String, String> tags, List<String> replicationRegions) {
        PaymentCryptographyKeyExchange.Imported imported = exchange.importKey(region, form, material);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", imported.algorithm());
        attributes.put("KeyClass", imported.keyClass());
        attributes.put("KeyUsage", imported.usage());
        if (!checkValueAlgorithm.isEmpty()) {
            PaymentCryptographyKeyAlgorithm algorithm =
                    PaymentCryptographyKeyAlgorithm.from(imported.algorithm());
            String own = algorithm == null ? "" : algorithm.checkValueAlgorithm();
            if (!checkValueAlgorithm.equals(own)) {
                throw new AwsException("ValidationException",
                        "the check value algorithm " + checkValueAlgorithm + " is not the one a "
                                + imported.algorithm() + " key has, which is " + own, 400);
            }
        }
        Replication replication = replicationOf(region, replicationRegions);
        // The key is checked before it is made, so a refused request leaves no key behind.
        requireExportable(imported.exportable(), replication.targets());
        PaymentCryptographyKey key = makeKey(region, attributes, imported.modes(),
                imported.exportable(), enabled, tags,
                imported.material() == null ? null
                        : PaymentCryptographyCrypto.toHex(imported.material()),
                imported.publicKeyBase64(), imported.privateKeyBase64(), imported.certificatePem(),
                ORIGIN_EXTERNAL);
        return replicate(key, replication);
    }

    /**
     * The initial key of the DUKPT derivation, derived from a base derivation key.
     *
     * <p>The derived key is a PIN encryption key of sixteen bytes, and it does not leave the
     * service again: what leaves is the derived key, not the base one.
     *
     * @param base the base derivation key
     * @param keySerialNumber the serial number of the key the derivation starts from
     * @return the derived key, as the key that leaves
     */
    private static PaymentCryptographyKey initialKey(PaymentCryptographyKey base,
                                                      String keySerialNumber) {
        if (!"TR31_B0_BASE_DERIVATION_KEY".equals(base.getKeyUsage())) {
            throw new AwsException("ValidationException",
                    "an initial key under export is derived from a base derivation key, but "
                            + base.getArn() + " is " + base.getKeyUsage(), 400);
        }
        byte[] initial;
        try {
            initial = DukptCrypto.deriveIpek(PaymentCryptographyKeyRules.materialHex(base),
                    keySerialNumber);
        } catch (RuntimeException e) {
            throw new AwsException("ValidationException",
                    "the initial key could not be derived: " + e.getMessage(), 400);
        }
        PaymentCryptographyKey derived = new PaymentCryptographyKey();
        derived.setKeyId(base.getKeyId());
        derived.setArn(base.getArn());
        derived.setRegion(base.getRegion());
        derived.setKeyAlgorithm("TDES_2KEY");
        derived.setKeyClass("SYMMETRIC_KEY");
        // The standard gives the key derived from a base derivation key the usage of an
        // initial derivation key, and the mode of a derivable one.
        derived.setKeyUsage("TR31_B1_INITIAL_DERIVATION_KEY");
        derived.setKeyModesOfUse(new LinkedHashMap<>(Map.of("DeriveKey", Boolean.TRUE)));
        derived.setKeyMaterialHex(PaymentCryptographyCrypto.toHex(initial));
        derived.setKeyCheckValue(PaymentCryptographyCrypto.checkValue(derived.getKeyMaterialHex(),
                PaymentCryptographyKeyAlgorithm.TDES_2KEY));
        derived.setKeyCheckValueAlgorithm("ANSI_X9_24");
        // The derived key is for the counterparty's use and does not leave again.
        derived.setExportable(false);
        return derived;
    }

    /**
     * The number of exchange tokens the service holds.
     *
     * <p>For the test that watches the expired ones swept.
     *
     * @return how many tokens are held
     */
    int issuedTokenCount() {
        return exchange.issuedTokenCount();
    }



    /**
     * The algorithm a key block carries.
     *
     * <p>The block names the algorithm of the key it carries, and the length of the material
     * says which of that family's keys it is. The algorithm is not guessed from the length
     * alone, because a TDES key and an AES key of the same length are different keys.
     *
     * <p>The header's algorithm byte fixes how long the key may be, so a key of a length the
     * algorithm does not allow is refused rather than read as a key of another length.
     */
    static String algorithmOf(String block, int materialLength) {
        String family = PaymentCryptographyTr31.algorithmInHeader(block);
        int length = materialLength;
        return switch (family) {
            case "A" -> switch (length) {
                case 16 -> "AES_128";
                case 24 -> "AES_192";
                case 32 -> "AES_256";
                default -> throw new AwsException("ValidationException",
                        "the key block carries a " + (length * 8) + "-bit key; AES needs 128/192/256", 400);
            };
            case "T" -> switch (length) {
                case 16 -> "TDES_2KEY";
                case 24 -> "TDES_3KEY";
                default -> throw new AwsException("ValidationException",
                        "the key block carries a " + (length * 8) + "-bit key; TDES needs 128/192", 400);
            };
            default -> throw new AwsException("ValidationException",
                    "the key block's algorithm is '" + family + "', which is not one this service has a key "
                            + "algorithm for (T for TDES, A for AES)", 400);
        };
    }


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

    /**
     * The usage name of the key an initial derivation key has.
     *
     * <p>The standard gives an initial derivation key the usage code B1, and the key the
     * service derives carries that code in the block it is exported in. The name is the
     * service's own marker for such a key: the API has no usage name for it, because a key
     * with that usage never lives in the service.
     */
    static final String INITIAL_DERIVATION_KEY_USAGE = "TR31_B1_INITIAL_DERIVATION_KEY";

    /** The TR-31 usage code of an initial derivation key. */
    static final String INITIAL_DERIVATION_KEY_CODE = "B1";

    /**
     * Whether a key is the one derived from a base derivation key.
     *
     * <p>It is an initial derivation key, and the standard gives it the usage code B1.
     */
    static boolean isInitialDerivationKey(PaymentCryptographyKey key) {
        return INITIAL_DERIVATION_KEY_USAGE.equals(key.getKeyUsage());
    }

    /** The TR-31 usage code of a key, which the initial derivation key has of its own. */
    static String usageCode(PaymentCryptographyKey key) {
        if (isInitialDerivationKey(key)) {
            return INITIAL_DERIVATION_KEY_CODE;
        }
        return usageCode(key.getKeyUsage());
    }

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
     * Whether a key is a Triple DES key rather than an AES one.
     *
     * <p>The key record knows its algorithm, so the family is read from there and not guessed
     * from the length of the material, because a 16-byte key is a two-key TDES one and an
     * AES-128 one, and a 24-byte key is a three-key TDES one and an AES-192 one.
     */
    static boolean isTdes(PaymentCryptographyKey key) {
        PaymentCryptographyKeyAlgorithm algorithm =
                PaymentCryptographyKeyAlgorithm.from(key.getKeyAlgorithm());
        return algorithm != null && algorithm.isTdes();
    }

    /**
     * The algorithm byte of a key: {@code A} for an AES key and {@code T} for a TDES one.
     *
     * <p>The key record knows its algorithm, so the byte is read from there and not guessed from
     * the length of the material, because a TDES key and an AES key of the same length are
     * different keys.
     */
    static String algorithmByte(PaymentCryptographyKey key) {
        return isAes(key) ? "A" : "T";
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
     * <p>The character says what the key may be used for, and both spellings of a direction
     * stand for it: a key that may encrypt may also wrap, and a key that may decrypt may also
     * unwrap. A block that says {@code B} therefore grants all four of them, whichever use the
     * key has, which is what the reference does.
     *
     * @param mode the mode of use character of the block
     * @return the modes of use the character stands for
     */
    static Map<String, Boolean> modesFor(String mode) {
        return switch (mode) {
            case "B" -> Map.of("Encrypt", Boolean.TRUE, "Decrypt", Boolean.TRUE,
                    "Wrap", Boolean.TRUE, "Unwrap", Boolean.TRUE);
            case "E" -> Map.of("Encrypt", Boolean.TRUE, "Wrap", Boolean.TRUE);
            case "D" -> Map.of("Decrypt", Boolean.TRUE, "Unwrap", Boolean.TRUE);
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
     * The mode of use character a set of modes gives.
     *
     * <p>The character is read off the modes, because that is what it says: a key that
     * encrypts and decrypts is a {@code B}, one that only encrypts is an {@code E}. A key
     * encryption key wraps and unwraps rather than encrypts and decrypts, so its modes are
     * read that way.
     *
     * @param modes the modes of the key
     * @param isKek whether the key is a key encryption key, whose modes are wrap and unwrap
     * @return the character
     */
    static String modeCode(Map<String, Boolean> modes, boolean isKek) {
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

    /**
     * The mode of use character a key block carries.
     *
     * <p>The character is read off the key's own modes, because that is what it says.
     *
     * @param key the key the block carries
     * @return the character
     */
    static String modeCode(PaymentCryptographyKey key) {
        return modeCode(key.getKeyModesOfUse(), isKeyEncryptionKey(key.getKeyUsage()));
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
     * The tags held on the key stored under {@code arn}, in the account and Region it names, read in place without the
     * service's lock: the tagging operations put a new map in place of the held one, so the read is of one whole set.
     * A key saved before the Region became part of its storage name is found as {@link #findKeyRecord} finds it, and
     * either is matched on its whole stored ARN. Empty for an ARN naming no key.
     */
    @Override
    public Map<String, String> storedTags(String arn) {
        if (!AwsArnUtils.isArnFor(arn, SERVICE)) {
            return Map.of();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String account = parsed.accountId();
        if (account.isEmpty() || !parsed.resource().startsWith("key/")) {
            return Map.of();
        }
        String keyId = parsed.resource().substring("key/".length());
        Map<String, String> held = new LinkedHashMap<>();
        AccountAwareStorageBackend.readInAccount(keyStore, account, storageKey(parsed.region(), keyId))
                .or(() -> AccountAwareStorageBackend.readInAccount(keyStore, account, keyId))
                .filter(key -> arn.equals(key.getArn()))
                .map(PaymentCryptographyKey::getTags)
                .ifPresent(tags -> tags.forEach((tagKey, value) -> {
                    if (tagKey != null && value != null) {
                        held.put(tagKey, value);
                    }
                }));
        return held;
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
        settingsStore.clear();
        aliasStore.clear();
    }

    /** The key store, for the tests. */
    Optional<PaymentCryptographyKey> storedKey(String region, String keyId) {
        return keyStore.get(storageKey(region, keyId));
    }

    /** The certificate of a public key. */
    public Map<String, Object> publicKeyCertificate(PaymentCryptographyKey key) {
        return exchange.publicKeyCertificate(key);
    }

    /** The parameters a counterparty needs to import a key into this service. */
    public Map<String, Object> parametersForImport(String materialType, String algorithm,
                                                    boolean reuse) {
        return exchange.parametersForImport(materialType, algorithm, reuse);
    }

    /** The parameters a counterparty needs to import a key this service exported. */
    public Map<String, Object> parametersForExport(String materialType, String algorithm,
                                                    boolean reuse) {
        return exchange.parametersForExport(materialType, algorithm, reuse);
    }

    // ---- the replication ------------------------------------------------------

    /**
     * The regions the account replicates a new key to when the request names none.
     *
     * <p>They are kept in the account-aware store, so they belong to the account that set
     * them and to no other account. Each Region has its own settings, so a Region whose
     * keys are replicated is not the same as another Region whose keys are not.
     *
     * @param region the Region the settings are read in
     */
    public List<String> defaultReplicationRegions(String region) {
        List<String> regions = settingsStore.get(defaultRegionsKey(region)).orElseGet(ArrayList::new);
        return new ArrayList<>(regions);
    }

    /**
     * The name the default regions of a Region are kept under.
     *
     * @param region the Region the settings are of
     */
    private static String defaultRegionsKey(String region) {
        return DEFAULT_REGIONS_KEY + ":" + region;
    }

    /**
     * Turns the default replication of the account on for the regions the request names.
     *
     * <p>The regions the account already had are left on, so the operation adds rather than
     * replaces. The settings are the ones of the Region the request is in, so each Region
     * has its own.
     *
     * @param region the Region the request is in
     * @param regions the regions the request names
     */
    public synchronized List<String> enableDefaultReplicationRegions(String region,
                                                                       List<String> regions) {
        requireReplicationRegions(region, regions);
        // The region of the request is where the keys made there are primary, so it is not
        // a region they are replicated to.
        requireOtherRegion(region, regions, "enable");
        List<String> current = defaultReplicationRegions(region);
        for (String target : regions) {
            if (!current.contains(target)) {
                current.add(target);
            }
        }
        settingsStore.put(defaultRegionsKey(region), current);
        return defaultReplicationRegions(region);
    }

    /**
     * Turns the default replication of the account off for the regions the request names.
     *
     * <p>Only the regions the request names are turned off, so the others stay on. The
     * settings are the ones of the Region the request is in, so each Region has its own.
     *
     * @param region the Region the request is in
     * @param regions the regions the request names
     */
    public synchronized List<String> disableDefaultReplicationRegions(String region,
                                                                        List<String> regions) {
        requireKnownRegions(regions);
        List<String> current = defaultReplicationRegions(region);
        current.removeAll(regions);
        settingsStore.put(defaultRegionsKey(region), current);
        return defaultReplicationRegions(region);
    }

    /**
     * The regions the service serves, or the refusal of the ones it does not.
     *
     * <p>The check is in one place, so every operation that names a region refuses the same
     * regions in the same words.
     *
     * @param regions the regions the request names
     */
    private static void requireKnownRegions(List<String> regions) {
        for (String region : regions) {
            if (!RegionResolver.isKnownRegion(region)) {
                throw new AwsException("ValidationException",
                        "the region " + region + " is not a region this service serves", 400);
            }
        }
    }

    /**
     * The regions a key may be replicated to, or the refusal of the ones it may not.
     *
     * <p>A key is replicated within the partition it is in and the account it belongs to, as
     * the reference says, so a region of another partition is refused. The account is the
     * one the request is made in, so a request cannot name a region of another account.
     * The partition of a region is the one its ARN is minted with, so a region the catalog
     * cannot place is in the partition of the deployment and not in the commercial one.
     * The check is in one place, so every operation that names a replication region
     * refuses the same regions in the same words.
     *
     * @param region the region the request is in
     * @param regions the regions the request names
     */
    private void requireReplicationRegions(String region, List<String> regions) {
        requireKnownRegions(regions);
        for (String target : regions) {
            if (!regionResolver.partitionForRegion(region)
                    .equals(regionResolver.partitionForRegion(target))) {
                throw new AwsException("ValidationException",
                        "the region " + target + " is in another partition than the region "
                                + region + ", and a key is replicated within its own partition", 400);
            }
        }
    }

    /**
     * The refusal of a request that names the region of the request as a region to replicate to.
     *
     * <p>A key is in the region of the request already, so that region is not a region the key
     * is replicated to. The check is in one place, so every operation that names a region
     * refuses it in the same words.
     *
     * @param region the region the request is in
     * @param regions the regions the request names
     * @param what the operation, for the message
     */
    private static void requireOtherRegion(String region, List<String> regions, String what) {
        if (regions.contains(region)) {
            throw new AwsException("ValidationException",
                    "the region " + region + " is the region of the request, and it is not a region to "
                            + what, 400);
        }
    }

    /**
     * The key with the regions the request names added to its replication.
     *
     * <p>Only the primary of a multi-region key may be replicated further, and a key that is
     * replicated has to be exportable.
     */
    public synchronized PaymentCryptographyKey addKeyReplicationRegions(String region, String keyIdentifier,
                                                                        List<String> regions) {
        PaymentCryptographyKey key = primaryOf(region, keyIdentifier);
        // The key has to be in an active state to be replicated, as the reference says.
        PaymentCryptographyKeyRules.requireEnabled(key);
        if (!key.isExportable()) {
            throw new AwsException("ValidationException",
                    "a key that is replicated has to be exportable, and " + key.getArn() + " is not", 400);
        }
        requireReplicationRegions(region, regions);
        // The key is in its own region already, so naming that region again is a request
        // that cannot be carried out.
        requireOtherRegion(region, regions, "add");
        Set<String> added = new LinkedHashSet<>(key.getReplicationRegions());
        for (String target : regions) {
            if (added.contains(target)) {
                // The key already replicates there, so adding it again is a request that
                // cannot be carried out rather than one that changes nothing.
                throw new AwsException("ValidationException",
                        "the key " + key.getArn() + " already replicates into " + target, 400);
            }
            added.add(target);
        }
        if (added.isEmpty()) {
            // Nothing is left to replicate to, so the key would be a multi-region key with no
            // replica, which is not a thing: a request that names no region is refused.
            throw new AwsException("ValidationException",
                    "the request names no region to add to the replication of " + key.getArn(), 400);
        }
        // A key with a replica is a primary, and says which region it is the primary of.
        key.setMultiRegionKeyType("PRIMARY");
        key.setPrimaryRegion(key.getPrimaryRegion() == null ? region : key.getPrimaryRegion());
        key.setReplicationRegions(new ArrayList<>(added));
        // The key has a configuration of its own now, so it no longer takes the account's
        // default regions.
        key.setUsingDefaultReplicationRegions(false);
        storeKey(key);
        // A region the key did not replicate into gets a replica of the key, and a replica
        // that is already there keeps its own region and its own ARN and takes the primary's
        // metadata, so it does not report regions the primary has left behind.
        for (String target : added) {
            if (findKeyRecord(keyStore, target, key.getKeyId()) == null) {
                keyStore.put(storageKey(target, key.getKeyId()), replicaOf(key, target));
            }
        }
        refreshReplicas(key, added);
        return key;
    }

    /**
     * The key with the regions the request names taken away from its replication.
     *
     * <p>The key goes out of the region it was replicated to, and the record of it there is
     * gone.
     */
    public synchronized PaymentCryptographyKey removeKeyReplicationRegions(String region,
                                                                          String keyIdentifier,
                                                                          List<String> regions) {
        PaymentCryptographyKey key = primaryOf(region, keyIdentifier);
        // The region the primary is in is not a replica, and taking it away would
        // delete the key without going through the deletion operation.
        requireOtherRegion(region, regions, "remove");
        Set<String> left = new LinkedHashSet<>(key.getReplicationRegions());
        for (String target : regions) {
            if (!left.contains(target)) {
                // The key does not replicate there, so there is nothing to take away.
                throw new AwsException("ValidationException",
                        "the key " + key.getArn() + " does not replicate into " + target, 400);
            }
        }
        left.removeAll(regions);
        key.setReplicationRegions(new ArrayList<>(left));
        if (left.isEmpty()) {
            // A key that replicates nowhere is not part of a replication relationship, and
            // says so by carrying no type at all. Taking the last replica away is how the
            // replication is turned off for a key, so the key is a plain one afterwards.
            key.setMultiRegionKeyType(null);
            key.setPrimaryRegion(null);
            key.setUsingDefaultReplicationRegions(false);
        }
        storeKey(key);
        for (String target : regions) {
            keyStore.delete(storageKey(target, key.getKeyId()));
            // The aliases that named the replica in that region go with it: an alias that
            // names a key that is not there cannot be resolved, and its name would be taken
            // by a key that is not the one it named.
            deleteAliasesOf(target, key.getKeyId());
        }
        // The replicas that are left carry the regions the primary has now, so none of them
        // reports a region the primary has left behind. They carry the primary's tags too: a
        // replica is a copy of the primary's material and metadata.
        refreshReplicas(key, left);
        return key;
    }

    /**
     * The aliases that name a key, gone with the key.
     *
     * <p>An alias that names a key that is not there cannot be resolved, so it goes when the
     * key does, as it does when the key itself is deleted. A replica shares the identifier
     * of its primary, so the aliases that name the replica are the ones that name the key.
     *
     * @param region the region the aliases are in, or null for the aliases of every region
     * @param keyId the key the aliases name
     */
    private void deleteAliasesOf(String region, String keyId) {
        for (PaymentCryptographyAlias alias : aliasStore.scan(a -> true)) {
            if (region != null && !region.equals(alias.getRegion())) {
                continue;
            }
            if (keyId.equals(alias.getKeyId())) {
                aliasStore.delete(aliasKey(alias.getAliasName(), alias.getRegion()));
            }
        }
    }

    /**
     * The primary of a key, which is the only one that may be replicated further.
     *
     * <p>A replica is read-only: it cannot be deleted on its own and it cannot be replicated
     * further, so the operations that change the replication of a key start at its primary.
     */
    private PaymentCryptographyKey primaryOf(String region, String keyIdentifier) {
        PaymentCryptographyKey key = findKey(region, keyIdentifier);
        if (key == null) {
            throw new AwsException("ResourceNotFoundException", "Key not found: " + keyIdentifier, 400);
        }
        requireNotReplica(key, "only the primary may be replicated");
        return key;
    }

    /**
     * The record of a key in another region: the same key, with the ARN of that region.
     *
     * <p>The alias names are the region's own, but the tags are the primary's: a replica is
     * a copy of the primary's material and metadata.
     */
    private PaymentCryptographyKey replicaOf(PaymentCryptographyKey primary, String region) {
        PaymentCryptographyKey replica = new PaymentCryptographyKey();
        replica.setKeyId(primary.getKeyId());
        replica.setArn(regionResolver.buildArn(SERVICE, region, "key/" + primary.getKeyId()));
        replica.setRegion(region);
        replica.setKeyAlgorithm(primary.getKeyAlgorithm());
        replica.setKeyClass(primary.getKeyClass());
        replica.setKeyUsage(primary.getKeyUsage());
        replica.setKeyModesOfUse(new LinkedHashMap<>(primary.getKeyModesOfUse()));
        replica.setKeyOrigin(primary.getKeyOrigin());
        replica.setKeyCheckValue(primary.getKeyCheckValue());
        replica.setKeyCheckValueAlgorithm(primary.getKeyCheckValueAlgorithm());
        replica.setKeyState(primary.getKeyState());
        replica.setExportable(primary.isExportable());
        replica.setEnabled(primary.isEnabled());
        replica.setKeyMaterialHex(primary.getKeyMaterialHex());
        // The halves of an asymmetric key travel with it, or the replica is a record of a
        // key that has none.
        replica.setKeyPublicKeyBase64(primary.getKeyPublicKeyBase64());
        replica.setKeyPrivateKeyBase64(primary.getKeyPrivateKeyBase64());
        replica.setKeyCertificatePem(primary.getKeyCertificatePem());
        replica.setDeriveKeyUsage(primary.getDeriveKeyUsage());
        // A replica is a copy of the primary's material and metadata, so it carries the
        // primary's tags too.
        replica.setTags(new LinkedHashMap<>(primary.getTags()));
        replica.setCreateTimestamp(primary.getCreateTimestamp());
        replica.setUsageStartTimestamp(primary.getUsageStartTimestamp());
        replica.setPrimaryRegion(primary.getPrimaryRegion());
        replica.setReplicationRegions(new ArrayList<>(primary.getReplicationRegions()));
        replica.setMultiRegionKeyType("REPLICA");
        replica.setUsingDefaultReplicationRegions(primary.isUsingDefaultReplicationRegions());
        return replica;
    }

    /**
     * Creates a key, in the region of the request and in the regions it is replicated to.
     *
     * <p>A key that is replicated is one key with one identifier in several regions, and each
     * region has its own ARN of it. The regions the request names are the ones the key is
     * replicated to; when it names none, the account's default regions are the ones, and the
     * key says that it took them.
     */
    public PaymentCryptographyKey createKey(String region, Map<String, Object> attributes,
                                            Map<String, Boolean> modesOfUse, boolean exportable,
                                            boolean enabled, Map<String, String> tags,
                                            List<String> replicationRegions) {
        Replication replication = replicationOf(region, replicationRegions);
        // The key is checked before it is made, so a refused request leaves no key behind.
        requireExportable(exportable, replication.targets());
        PaymentCryptographyKey primary = makeKey(region, attributes, modesOfUse, exportable,
                enabled, tags, null);
        return replicate(primary, replication);
    }

    /**
     * The regions a key is replicated to, and whether they are the account's defaults.
     *
     * <p>The regions the request names are the ones the key is replicated to; when it names
     * none, the account's default regions are the ones. The region's own is never one of
     * them, since the key is in that region already.
     *
     * @param region the region the request is in
     * @param replicationRegions the regions the request names, or none for the account's
     */
    private Replication replicationOf(String region, List<String> replicationRegions) {
        Set<String> targets = new LinkedHashSet<>();
        boolean named = replicationRegions != null && !replicationRegions.isEmpty();
        if (named) {
            // A key is in the region of the request already, so naming that region is a
            // request that cannot be carried out.
            requireOtherRegion(region, replicationRegions, "replicate to");
            targets.addAll(replicationRegions);
        } else {
            targets.addAll(defaultReplicationRegions(region));
            // The region of the request is never a replica of the key made there.
            targets.remove(region);
        }
        requireReplicationRegions(region, new ArrayList<>(targets));
        return new Replication(targets, !named);
    }

    /**
     * A key that is replicated has to be exportable, since a replica is a copy of its
     * material.
     *
     * @param exportable whether the key may leave the service
     * @param targets the regions the key would be replicated to
     */
    private static void requireExportable(boolean exportable, Set<String> targets) {
        if (!targets.isEmpty() && !exportable) {
            throw new AwsException("ValidationException",
                    "a key that is replicated has to be exportable, and this one is not", 400);
        }
    }

    /**
     * The regions a key is replicated to, and whether they are the account's defaults.
     *
     * @param targets the regions the key is replicated to
     * @param usingDefaults whether those regions are the account's default ones
     */
    private record Replication(Set<String> targets, boolean usingDefaults) { }

    /**
     * Replicates a key to the regions the request names.
     *
     * <p>A key that is replicated is one key with one identifier in several regions, and each
     * region has its own ARN of it. The regions the request names are the ones the key is
     * replicated to; when it names none, the account's default regions are the ones, and the
     * key says that it took them.
     *
     * @param primary the key that was made
     * @param replication the regions it is replicated to
     * @return the key, which is the primary of a multi-region key when it was replicated
     */
    private PaymentCryptographyKey replicate(PaymentCryptographyKey primary, Replication replication) {
        if (replication.targets().isEmpty()) {
            return primary;
        }
        primary.setPrimaryRegion(primary.getRegion());
        primary.setMultiRegionKeyType("PRIMARY");
        primary.setReplicationRegions(new ArrayList<>(replication.targets()));
        primary.setUsingDefaultReplicationRegions(replication.usingDefaults());
        storeKey(primary);
        for (String target : replication.targets()) {
            keyStore.put(storageKey(target, primary.getKeyId()), replicaOf(primary, target));
        }
        return primary;
    }
}
