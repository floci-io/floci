package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Tls {

    @JsonProperty("certificateAuthorityArnList")
    private List<String> certificateAuthorityArnList;

    @JsonProperty("enabled")
    private Boolean enabled;

    public Tls() {}

    public List<String> getCertificateAuthorityArnList() { return certificateAuthorityArnList; }
    public void setCertificateAuthorityArnList(List<String> certificateAuthorityArnList) { this.certificateAuthorityArnList = certificateAuthorityArnList; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}