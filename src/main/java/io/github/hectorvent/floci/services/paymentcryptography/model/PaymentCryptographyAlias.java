package io.github.hectorvent.floci.services.paymentcryptography.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A Payment Cryptography alias.
 *
 * <p>An alias name is unique in an account and a region, not in an account:
 * the same name may name a key in another region, and a program that creates
 * keys in two regions uses the same name in both. The alias therefore
 * carries the region it belongs to.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class PaymentCryptographyAlias {
    private String aliasName;
    private String region;
    private String keyArn;
    private String keyId;

    public PaymentCryptographyAlias() {
    }

    public PaymentCryptographyAlias(String aliasName, String region, String keyArn, String keyId) {
        this.aliasName = aliasName;
        this.region = region;
        this.keyArn = keyArn;
        this.keyId = keyId;
    }

    public String getAliasName() { return aliasName; }
    public void setAliasName(String aliasName) { this.aliasName = aliasName; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getKeyArn() { return keyArn; }
    public void setKeyArn(String keyArn) { this.keyArn = keyArn; }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
}
