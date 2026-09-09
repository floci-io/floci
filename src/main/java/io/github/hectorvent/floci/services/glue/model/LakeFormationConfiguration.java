package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class LakeFormationConfiguration {
    @JsonProperty("AccountId")
    private String accountId;

    @JsonProperty("UseLakeFormationCredentials")
    private Boolean useLakeFormationCredentials;

    public LakeFormationConfiguration() {}

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public Boolean getUseLakeFormationCredentials() { return useLakeFormationCredentials; }
    public void setUseLakeFormationCredentials(Boolean useLakeFormationCredentials) { this.useLakeFormationCredentials = useLakeFormationCredentials; }
}
