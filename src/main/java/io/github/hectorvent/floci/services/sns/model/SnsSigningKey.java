package io.github.hectorvent.floci.services.sns.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The RSA key pair and self-signed certificate SNS signs deliveries with, stored so a restart
 * under persistent storage keeps signing with the certificate consumers have already fetched.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SnsSigningKey {

    @JsonProperty("CertificatePem")
    private String certificatePem;

    @JsonProperty("PrivateKeyPem")
    private String privateKeyPem;

    public SnsSigningKey() {}

    public SnsSigningKey(String certificatePem, String privateKeyPem) {
        this.certificatePem = certificatePem;
        this.privateKeyPem = privateKeyPem;
    }

    public String getCertificatePem() { return certificatePem; }
    public void setCertificatePem(String certificatePem) { this.certificatePem = certificatePem; }

    public String getPrivateKeyPem() { return privateKeyPem; }
    public void setPrivateKeyPem(String privateKeyPem) { this.privateKeyPem = privateKeyPem; }
}
