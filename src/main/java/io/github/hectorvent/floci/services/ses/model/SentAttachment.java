package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * What the sent-email store keeps about one attachment of a Simple send: its metadata as it was
 * written into the MIME message and its decoded size. The bytes themselves live in the stored MIME.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SentAttachment(
        @JsonProperty("FileName") String fileName,
        @JsonProperty("ContentType") String contentType,
        @JsonProperty("ContentDisposition") String contentDisposition,
        @JsonProperty("ContentId") String contentId,
        @JsonProperty("ContentDescription") String contentDescription,
        @JsonProperty("Size") long size) {
}
