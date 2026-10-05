package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * The AWS SES V2 {@code SigningScheme} union: exactly one of {@code DefaultScheme} (an empty
 * structure) or {@code SmimeScheme} is set.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SigningScheme {

    @JsonProperty("DefaultScheme")
    private Map<String, String> defaultScheme;

    @JsonProperty("SmimeScheme")
    private SmimeSigningScheme smimeScheme;

    public SigningScheme() {}

    public static SigningScheme defaultScheme() {
        SigningScheme scheme = new SigningScheme();
        scheme.defaultScheme = Map.of();
        return scheme;
    }

    public static SigningScheme smime(String signatureFormat) {
        SigningScheme scheme = new SigningScheme();
        scheme.smimeScheme = new SmimeSigningScheme(signatureFormat);
        return scheme;
    }

    public Map<String, String> getDefaultScheme() { return defaultScheme; }
    public void setDefaultScheme(Map<String, String> defaultScheme) { this.defaultScheme = defaultScheme; }

    public SmimeSigningScheme getSmimeScheme() { return smimeScheme; }
    public void setSmimeScheme(SmimeSigningScheme smimeScheme) { this.smimeScheme = smimeScheme; }
}
