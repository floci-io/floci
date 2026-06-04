package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public record BulkEmailEntry(
        List<String> toAddresses,
        List<String> ccAddresses,
        List<String> bccAddresses,
        JsonNode replacementTemplateData,
        List<MessageTag> replacementEmailTags,
        List<MessageHeader> replacementHeaders
) {}
