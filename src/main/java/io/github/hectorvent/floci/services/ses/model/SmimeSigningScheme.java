package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** The AWS SES V2 {@code SmimeSigningScheme} shape, the S/MIME member of the {@code SigningScheme} union. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SmimeSigningScheme {

    @JsonProperty("SignatureFormat")
    private String signatureFormat;

    public SmimeSigningScheme() {}

    public SmimeSigningScheme(String signatureFormat) {
        this.signatureFormat = signatureFormat;
    }

    public String getSignatureFormat() { return signatureFormat; }
    public void setSignatureFormat(String signatureFormat) { this.signatureFormat = signatureFormat; }
}
