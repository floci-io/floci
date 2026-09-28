package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code ConnectionPasswordEncryption} block of the Data Catalog encryption settings. With
 * {@code ReturnConnectionPasswordEncrypted} true, connection passwords are encrypted with
 * {@code AwsKmsKeyId} when a connection is created or updated and stay encrypted in every read.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConnectionPasswordEncryption {
    @JsonProperty("ReturnConnectionPasswordEncrypted")
    private Boolean returnConnectionPasswordEncrypted;

    @JsonProperty("AwsKmsKeyId")
    private String awsKmsKeyId;

    public Boolean getReturnConnectionPasswordEncrypted() { return returnConnectionPasswordEncrypted; }
    public void setReturnConnectionPasswordEncrypted(Boolean returnConnectionPasswordEncrypted) {
        this.returnConnectionPasswordEncrypted = returnConnectionPasswordEncrypted;
    }

    public String getAwsKmsKeyId() { return awsKmsKeyId; }
    public void setAwsKmsKeyId(String awsKmsKeyId) { this.awsKmsKeyId = awsKmsKeyId; }
}
