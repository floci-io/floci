package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * One destination of a bulk send. A non-null {@code rejection} is the {@code InvalidParameterValue}
 * message the caller already found for this entry; the send answers the entry with it alone.
 */
@RegisterForReflection
public record BulkEmailEntry(
        List<String> toAddresses,
        List<String> ccAddresses,
        List<String> bccAddresses,
        JsonNode replacementTemplateData,
        List<MessageTag> replacementEmailTags,
        List<MessageHeader> replacementHeaders,
        String rejection
) {

    public BulkEmailEntry(List<String> toAddresses, List<String> ccAddresses, List<String> bccAddresses,
                          JsonNode replacementTemplateData, List<MessageTag> replacementEmailTags,
                          List<MessageHeader> replacementHeaders) {
        this(toAddresses, ccAddresses, bccAddresses, replacementTemplateData, replacementEmailTags,
                replacementHeaders, null);
    }
}
