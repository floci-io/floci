package io.floci.aws.services.kms.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * Floci's account- and Region-scoped metadata for an AWS KMS external custom key store.
 *
 * <p>The proxy secret is persisted only as an authenticated-encryption envelope. Plaintext
 * credentials are never part of this model.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class KmsCustomKeyStore {

    public static final String TYPE_EXTERNAL_KEY_STORE = "EXTERNAL_KEY_STORE";
    public static final String CONNECTIVITY_PUBLIC_ENDPOINT = "PUBLIC_ENDPOINT";
    public static final String STATE_DISCONNECTED = "DISCONNECTED";
    public static final String STATE_CONNECTING = "CONNECTING";
    public static final String STATE_CONNECTED = "CONNECTED";
    public static final String STATE_DISCONNECTING = "DISCONNECTING";
    public static final String STATE_FAILED = "FAILED";

    private String customKeyStoreId;
    private String customKeyStoreArn;
    private String customKeyStoreName;
    private String customKeyStoreType = TYPE_EXTERNAL_KEY_STORE;
    private String connectionState = STATE_DISCONNECTED;
    private String connectionErrorCode;
    private String connectionErrorMessage;
    private String accessKeyId;
    private String encryptedSecretAccessKey;
    private String proxyUriEndpoint;
    private String proxyUriPath;
    private String proxyConnectivity = CONNECTIVITY_PUBLIC_ENDPOINT;
    private String region;
    private long creationDate = Instant.now().getEpochSecond();

    public String getCustomKeyStoreId() { return customKeyStoreId; }
    public void setCustomKeyStoreId(String customKeyStoreId) { this.customKeyStoreId = customKeyStoreId; }

    public String getCustomKeyStoreArn() { return customKeyStoreArn; }
    public void setCustomKeyStoreArn(String customKeyStoreArn) { this.customKeyStoreArn = customKeyStoreArn; }

    public String getCustomKeyStoreName() { return customKeyStoreName; }
    public void setCustomKeyStoreName(String customKeyStoreName) { this.customKeyStoreName = customKeyStoreName; }

    public String getCustomKeyStoreType() { return customKeyStoreType; }
    public void setCustomKeyStoreType(String customKeyStoreType) { this.customKeyStoreType = customKeyStoreType; }

    public String getConnectionState() { return connectionState; }
    public void setConnectionState(String connectionState) { this.connectionState = connectionState; }

    public String getConnectionErrorCode() { return connectionErrorCode; }
    public void setConnectionErrorCode(String connectionErrorCode) { this.connectionErrorCode = connectionErrorCode; }

    public String getConnectionErrorMessage() { return connectionErrorMessage; }
    public void setConnectionErrorMessage(String connectionErrorMessage) { this.connectionErrorMessage = connectionErrorMessage; }

    public String getAccessKeyId() { return accessKeyId; }
    public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }

    public String getEncryptedSecretAccessKey() { return encryptedSecretAccessKey; }
    public void setEncryptedSecretAccessKey(String encryptedSecretAccessKey) { this.encryptedSecretAccessKey = encryptedSecretAccessKey; }

    public String getProxyUriEndpoint() { return proxyUriEndpoint; }
    public void setProxyUriEndpoint(String proxyUriEndpoint) { this.proxyUriEndpoint = proxyUriEndpoint; }

    public String getProxyUriPath() { return proxyUriPath; }
    public void setProxyUriPath(String proxyUriPath) { this.proxyUriPath = proxyUriPath; }

    public String getProxyConnectivity() { return proxyConnectivity; }
    public void setProxyConnectivity(String proxyConnectivity) { this.proxyConnectivity = proxyConnectivity; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }
}
