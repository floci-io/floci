package io.floci.aws.services.kms.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class KmsAlias {
    private String aliasName;
    private String aliasArn;
    private String targetKeyId;
    private long creationDate;

    public KmsAlias() {
        this.creationDate = Instant.now().getEpochSecond();
    }

    public KmsAlias(String aliasName, String aliasArn, String targetKeyId) {
        this();
        this.aliasName = aliasName;
        this.aliasArn = aliasArn;
        this.targetKeyId = targetKeyId;
    }

    public String getAliasName() { return aliasName; }
    public void setAliasName(String aliasName) { this.aliasName = aliasName; }

    public String getAliasArn() { return aliasArn; }
    public void setAliasArn(String aliasArn) { this.aliasArn = aliasArn; }

    public String getTargetKeyId() { return targetKeyId; }
    public void setTargetKeyId(String targetKeyId) { this.targetKeyId = targetKeyId; }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }
}
