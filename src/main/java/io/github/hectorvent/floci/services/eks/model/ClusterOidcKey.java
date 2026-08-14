package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * RSA signing material backing an EKS cluster's IRSA OIDC issuer.
 *
 * <p>Held in a storage backend separate from {@link Cluster} on purpose: {@code Cluster} is
 * serialized straight onto the {@code DescribeCluster} wire response, so a private key carried
 * on that model would leak to any caller. Keeping the key here lets it persist across restarts
 * (a token minted before a restart must still verify afterwards) without ever being reachable
 * from an AWS API response.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClusterOidcKey {

    @JsonProperty("issuer")
    private String issuer;

    @JsonProperty("keyId")
    private String keyId;

    @JsonProperty("publicKey")
    private String publicKey;

    @JsonProperty("privateKey")
    private String privateKey;

    public ClusterOidcKey() {}

    public ClusterOidcKey(String issuer, String keyId, String publicKey, String privateKey) {
        this.issuer = issuer;
        this.keyId = keyId;
        this.publicKey = publicKey;
        this.privateKey = privateKey;
    }

    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }

    public String getPrivateKey() { return privateKey; }
    public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
}
