package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The signing material behind one of the account's web identity token algorithms: the key id the
 * token's {@code kid} header names, and the keypair itself, the public half X.509 encoded and the
 * private half PKCS#8, both base64.
 *
 * <p>One of these exists per account and per signing algorithm, minted the first time a token asks
 * for that algorithm. It is stored rather than held in memory because a relying party verifies a
 * token against the published key, so a restart that minted a new one would invalidate every token
 * already handed out.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebIdentityTokenKey {

    private String keyId;
    private String publicKey;
    private String privateKey;

    public WebIdentityTokenKey() {}

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
    public String getPrivateKey() { return privateKey; }
    public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
}
