package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Per-configuration-set archiving override. Mirrors the AWS SES V2
 * {@code ArchivingOptions} shape: the ARN of the Mail Manager archive that
 * sends through the configuration set are written to.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ArchivingOptions {

    @JsonProperty("ArchiveArn")
    private String archiveArn;

    public ArchivingOptions() {}

    public String getArchiveArn() { return archiveArn; }
    public void setArchiveArn(String archiveArn) { this.archiveArn = archiveArn; }
}
