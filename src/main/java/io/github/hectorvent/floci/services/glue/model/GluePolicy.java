package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * The Data Catalog resource policy as GetResourcePolicy returns it, and as each entry of
 * GetResourcePolicies (the {@code GluePolicy} structure of the API reference).
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GluePolicy {
    @JsonProperty("PolicyInJson")
    private String policyInJson;

    @JsonProperty("PolicyHash")
    private String policyHash;

    @JsonProperty("CreateTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createTime;

    @JsonProperty("UpdateTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant updateTime;

    public String getPolicyInJson() { return policyInJson; }
    public void setPolicyInJson(String policyInJson) { this.policyInJson = policyInJson; }

    public String getPolicyHash() { return policyHash; }
    public void setPolicyHash(String policyHash) { this.policyHash = policyHash; }

    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }

    public Instant getUpdateTime() { return updateTime; }
    public void setUpdateTime(Instant updateTime) { this.updateTime = updateTime; }
}
