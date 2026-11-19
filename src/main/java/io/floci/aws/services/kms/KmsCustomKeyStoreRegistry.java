package io.floci.aws.services.kms;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.kms.model.KmsCustomKeyStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Account- and Region-scoped metadata registry for XKS custom key stores.
 *
 * <p>Proxy credentials are stored only as an AES-256-GCM envelope protected by the
 * deployment-managed XKS credential encryption key.
 */
@ApplicationScoped
public class KmsCustomKeyStoreRegistry {

    private static final String ARN_SERVICE = "kms";
    private final AccountAwareStorageBackend<KmsCustomKeyStore> storage;
    private final RegionResolver regionResolver;
    private final XksCredentialCipher credentialCipher;

    @Inject
    public KmsCustomKeyStoreRegistry(StorageFactory storageFactory, RegionResolver regionResolver,
                                     XksCredentialCipher credentialCipher) {
        this(storageFactory.create("kms", "kms-custom-key-stores.json", new TypeReference<Map<String, KmsCustomKeyStore>>() {}),
                regionResolver, credentialCipher);
    }

    KmsCustomKeyStoreRegistry(AccountAwareStorageBackend<KmsCustomKeyStore> storage,
                              RegionResolver regionResolver, XksCredentialCipher credentialCipher) {
        this.storage = storage;
        this.regionResolver = regionResolver;
        this.credentialCipher = credentialCipher;
        recoverInterruptedConnections();
    }

    /** A persisted CONNECTING state cannot have a live request after process startup. */
    private synchronized void recoverInterruptedConnections() {
        for (KmsCustomKeyStore stored : storage.scan(ignored -> true)) {
            if (KmsCustomKeyStore.STATE_CONNECTING.equals(stored.getConnectionState())) {
                KmsCustomKeyStore recovered = stored.copy();
                recovered.setConnectionState(KmsCustomKeyStore.STATE_FAILED);
                recovered.setConnectionErrorCode("INTERNAL_ERROR");
                recovered.setConnectionErrorMessage(
                        "The previous XKS connection attempt was interrupted; disconnect before retrying");
                storage.put(key(recovered.getRegion(), recovered.getCustomKeyStoreId()), recovered);
            }
        }
    }

    /** Assigns a stable ID/ARN and persists metadata after the caller has validated the proxy. */
    public String accountId() {
        return regionResolver.getAccountId();
    }

    public void requireCredentialEncryptionConfigured() {
        credentialCipher.requireConfigured();
    }

    public synchronized KmsCustomKeyStore create(KmsCustomKeyStore candidate, String secretAccessKey, String region) {
        if (candidate == null) {
            throw new AwsException("ValidationException", "Custom key store metadata is required", 400);
        }
        requireRegion(region);
        XksCustomKeyStoreValidation.requiredName(candidate.getCustomKeyStoreName());
        requireProxyMetadata(candidate);
        if (list(region, null, null).size() >= 10) {
            throw new AwsException("LimitExceededException", "The account and Region already contain the maximum of 10 custom key stores", 400);
        }
        ensureUnique(candidate, region, null);
        String id = "cks-" + UUID.randomUUID().toString().replace("-", "");
        candidate.setCustomKeyStoreId(id);
        candidate.setRegion(region);
        candidate.setCustomKeyStoreArn(regionResolver.buildArn(ARN_SERVICE, region, "custom-key-store/" + id));
        candidate.setCustomKeyStoreType(KmsCustomKeyStore.TYPE_EXTERNAL_KEY_STORE);
        candidate.setProxyConnectivity(KmsCustomKeyStore.CONNECTIVITY_PUBLIC_ENDPOINT);
        candidate.setConnectionState(KmsCustomKeyStore.STATE_DISCONNECTED);
        candidate.setConnectionErrorCode(null);
        candidate.setConnectionErrorMessage(null);
        candidate.setEncryptedSecretAccessKey(credentialCipher.encrypt(secretAccessKey, credentialContext(region, id)));
        storage.put(key(region, id), candidate);
        return candidate;
    }

    public Optional<KmsCustomKeyStore> find(String id, String region) {
        return storage.get(key(region, id)).filter(store -> region.equals(store.getRegion()));
    }

    public KmsCustomKeyStore require(String id, String region) {
        return find(id, region).orElseThrow(() -> notFound(id));
    }

    public List<KmsCustomKeyStore> list(String region, List<String> ids, List<String> names) {
        requireRegion(region);
        XksCustomKeyStoreValidation.validateDescribeFilters(ids, names);
        return storage.scan(ignored -> true).stream()
                .filter(store -> region.equals(store.getRegion()))
                .filter(store -> ids == null || ids.isEmpty() || ids.contains(store.getCustomKeyStoreId()))
                .filter(store -> names == null || names.isEmpty() || names.contains(store.getCustomKeyStoreName()))
                .sorted(Comparator.comparing(KmsCustomKeyStore::getCustomKeyStoreId))
                .toList();
    }

    /** Replaces metadata atomically; an omitted secret preserves the existing encrypted credential. */
    public synchronized KmsCustomKeyStore update(KmsCustomKeyStore candidate, String secretAccessKey, String region) {
        if (candidate == null || candidate.getCustomKeyStoreId() == null) {
            throw new AwsException("ValidationException", "CustomKeyStoreId is required", 400);
        }
        KmsCustomKeyStore current = require(candidate.getCustomKeyStoreId(), region);
        boolean currentDisconnected = KmsCustomKeyStore.STATE_DISCONNECTED.equals(current.getConnectionState());
        boolean currentConnected = KmsCustomKeyStore.STATE_CONNECTED.equals(current.getConnectionState());
        if (!currentDisconnected && !currentConnected) {
            throw invalidState("The custom key store must be connected or disconnected before it can be updated");
        }
        boolean endpointChanged = !Objects.equals(candidate.getProxyUriEndpoint(), current.getProxyUriEndpoint());
        if (endpointChanged && !currentDisconnected) {
            throw invalidState("Disconnect the custom key store before changing XksProxyUriEndpoint");
        }
        XksCustomKeyStoreValidation.requiredName(candidate.getCustomKeyStoreName());
        requireProxyMetadata(candidate);
        ensureUnique(candidate, region, current.getCustomKeyStoreId());
        candidate.setRegion(region);
        candidate.setCustomKeyStoreArn(current.getCustomKeyStoreArn());
        candidate.setCustomKeyStoreType(current.getCustomKeyStoreType());
        candidate.setProxyConnectivity(current.getProxyConnectivity());
        candidate.setConnectionState(current.getConnectionState());
        candidate.setConnectionErrorCode(current.getConnectionErrorCode());
        candidate.setConnectionErrorMessage(current.getConnectionErrorMessage());
        candidate.setCreationDate(current.getCreationDate());
        candidate.setEncryptedSecretAccessKey(secretAccessKey == null || secretAccessKey.isBlank()
                ? current.getEncryptedSecretAccessKey()
                : credentialCipher.encrypt(secretAccessKey, credentialContext(region, candidate.getCustomKeyStoreId())));
        storage.put(key(region, candidate.getCustomKeyStoreId()), candidate);
        return candidate;
    }

    /** Decrypts the proxy secret only when it is needed for an outbound SigV4 request. */
    public String secretAccessKey(String id, String region) {
        KmsCustomKeyStore store = require(id, region);
        return credentialCipher.decrypt(store.getEncryptedSecretAccessKey(), credentialContext(region, id));
    }

    /** Starts a health-checked connection attempt; callers must finish it with markConnected/markFailed. */
    public synchronized KmsCustomKeyStore beginConnect(String id, String region) {
        KmsCustomKeyStore store = require(id, region);
        if (!KmsCustomKeyStore.STATE_DISCONNECTED.equals(store.getConnectionState())) {
            throw invalidState("Only a disconnected custom key store can be connected");
        }
        store = store.copy();
        store.setConnectionState(KmsCustomKeyStore.STATE_CONNECTING);
        store.setConnectionErrorCode(null);
        store.setConnectionErrorMessage(null);
        storage.put(key(region, id), store);
        return store;
    }

    public synchronized KmsCustomKeyStore markConnected(String id, String region) {
        KmsCustomKeyStore store = require(id, region);
        requireState(store, KmsCustomKeyStore.STATE_CONNECTING);
        store = store.copy();
        store.setConnectionState(KmsCustomKeyStore.STATE_CONNECTED);
        store.setConnectionErrorCode(null);
        store.setConnectionErrorMessage(null);
        storage.put(key(region, id), store);
        return store;
    }

    public synchronized KmsCustomKeyStore markFailed(String id, String region, String errorCode,
                                                       String connectionErrorCode) {
        KmsCustomKeyStore store = require(id, region);
        requireState(store, KmsCustomKeyStore.STATE_CONNECTING);
        store = store.copy();
        store.setConnectionState(KmsCustomKeyStore.STATE_FAILED);
        store.setConnectionErrorCode(connectionErrorCode == null
                ? XksProxyClient.XksProxyException.connectionErrorCodeFor(errorCode) : connectionErrorCode);
        store.setConnectionErrorMessage(safeErrorMessage(errorCode, store.getConnectionErrorCode()));
        storage.put(key(region, id), store);
        return store;
    }

    /** Disconnect is also the required recovery step after a FAILED connection attempt. */
    public synchronized KmsCustomKeyStore disconnect(String id, String region) {
        KmsCustomKeyStore store = require(id, region);
        if (KmsCustomKeyStore.STATE_DISCONNECTED.equals(store.getConnectionState())
                || KmsCustomKeyStore.STATE_CONNECTING.equals(store.getConnectionState())
                || KmsCustomKeyStore.STATE_DISCONNECTING.equals(store.getConnectionState())) {
            throw invalidState("The custom key store is already disconnected or changing connection state");
        }
        store = store.copy();
        store.setConnectionState(KmsCustomKeyStore.STATE_DISCONNECTED);
        store.setConnectionErrorCode(null);
        store.setConnectionErrorMessage(null);
        storage.put(key(region, id), store);
        return store;
    }

    public synchronized void delete(String id, String region) {
        KmsCustomKeyStore store = require(id, region);
        if (!KmsCustomKeyStore.STATE_DISCONNECTED.equals(store.getConnectionState())) {
            throw invalidState("The custom key store must be disconnected before it can be deleted");
        }
        storage.delete(key(region, id));
    }

    private static void requireState(KmsCustomKeyStore store, String expected) {
        if (!expected.equals(store.getConnectionState())) {
            throw invalidState("The custom key store is not in the expected connection state");
        }
    }

    private static AwsException invalidState(String message) {
        return new AwsException("CustomKeyStoreInvalidStateException", message, 400);
    }

    private static String safeErrorMessage(String errorCode, String connectionErrorCode) {
        if ("XKS_PROXY_TIMED_OUT".equals(connectionErrorCode)) {
            return "The XKS proxy did not respond within the permitted time";
        }
        if ("XKS_PROXY_INVALID_TLS_CONFIGURATION".equals(connectionErrorCode)) {
            return "The XKS proxy TLS connection could not be established";
        }
        // Persist only locally-authored messages. Never copy a proxy response or exception text
        // into durable metadata, where it could contain attacker-controlled or sensitive data.
        return switch (errorCode == null ? "" : errorCode) {
            case "XksProxyIncorrectAuthenticationCredentialException" ->
                    "The XKS proxy denied access to the configured credentials";
            case "XksProxyUriUnreachableException" ->
                    "The XKS proxy health endpoint could not be reached";
            case "XksProxyInvalidResponseException" ->
                    "The XKS proxy returned an invalid health response";
            case "XksProxyInvalidConfigurationException" ->
                    "The XKS proxy configuration is invalid";
            default -> "The XKS proxy connection attempt failed";
        };
    }

    private static void requireProxyMetadata(KmsCustomKeyStore candidate) {
        if (candidate.getProxyUriEndpoint() == null || candidate.getProxyUriEndpoint().isBlank()
                || candidate.getProxyUriPath() == null || candidate.getProxyUriPath().isBlank()
                || candidate.getAccessKeyId() == null || candidate.getAccessKeyId().isBlank()) {
            throw new AwsException("ValidationException",
                    "XKS proxy endpoint, path, and access key ID are required", 400);
        }
    }

    private void ensureUnique(KmsCustomKeyStore candidate, String region, String excludedId) {
        for (KmsCustomKeyStore existing : list(region, null, null)) {
            if (Objects.equals(existing.getCustomKeyStoreId(), excludedId)) {
                continue;
            }
            if (Objects.equals(existing.getCustomKeyStoreName(), candidate.getCustomKeyStoreName())) {
                throw new AwsException("CustomKeyStoreNameInUseException",
                        "A custom key store with this name already exists", 400);
            }
            if (existing.getProxyUriEndpoint() != null && candidate.getProxyUriEndpoint() != null
                    && existing.getProxyUriEndpoint().equalsIgnoreCase(candidate.getProxyUriEndpoint())
                    && Objects.equals(existing.getProxyUriPath(), candidate.getProxyUriPath())) {
                throw new AwsException("XksProxyUriInUseException",
                        "The XKS proxy URI is already associated with a custom key store", 400);
            }
        }
    }

    private String credentialContext(String region, String id) {
        return regionResolver.getAccountId() + "\n" + region + "\n" + id + "\nXKS-proxy-credential-v1";
    }

    private static void requireRegion(String region) {
        if (region == null || region.isBlank()) {
            throw new AwsException("ValidationException", "Region is required", 400);
        }
    }

    private static String key(String region, String id) {
        requireRegion(region);
        if (id == null || id.isBlank()) {
            throw new AwsException("ValidationException", "CustomKeyStoreId is required", 400);
        }
        return region + "::" + id;
    }

    private static AwsException notFound(String id) {
        return new AwsException("CustomKeyStoreNotFoundException",
                "Custom key store " + id + " was not found", 400);
    }
}
