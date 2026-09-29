package io.github.hectorvent.floci.services.ses.model;

import java.util.List;
import java.util.Objects;

/**
 * The service-level input of a bulk send, shaped like the v2 {@code SendBulkEmailRequest}: an
 * envelope, the default template every entry renders, and the per-destination entries. The
 * controllers resolve a stored template before building it, so the default content is always
 * {@link EmailContent.InlineTemplate}; its data and headers are the defaults each entry's
 * replacements merge over.
 */
public record SendBulkEmailRequest(
        String source,
        List<String> replyToAddresses,
        String returnPath,
        String configurationSetName,
        List<MessageTag> defaultEmailTags,
        String tenantName,
        String region,
        EmailContent.InlineTemplate defaultContent,
        List<BulkEmailEntry> entries) {

    public SendBulkEmailRequest {
        Objects.requireNonNull(defaultContent, "defaultContent");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String source;
        private List<String> replyToAddresses = List.of();
        private String returnPath;
        private String configurationSetName;
        private List<MessageTag> defaultEmailTags = List.of();
        private String tenantName;
        private String region;
        private EmailContent.InlineTemplate defaultContent;
        private List<BulkEmailEntry> entries = List.of();

        private Builder() {
        }

        public Builder source(String source) { this.source = source; return this; }
        public Builder replyToAddresses(List<String> replyToAddresses) { this.replyToAddresses = replyToAddresses; return this; }
        public Builder returnPath(String returnPath) { this.returnPath = returnPath; return this; }
        public Builder configurationSetName(String configurationSetName) { this.configurationSetName = configurationSetName; return this; }
        public Builder defaultEmailTags(List<MessageTag> defaultEmailTags) { this.defaultEmailTags = defaultEmailTags; return this; }
        public Builder tenantName(String tenantName) { this.tenantName = tenantName; return this; }
        public Builder region(String region) { this.region = region; return this; }
        public Builder defaultContent(EmailContent.InlineTemplate defaultContent) { this.defaultContent = defaultContent; return this; }
        public Builder entries(List<BulkEmailEntry> entries) { this.entries = entries; return this; }

        public SendBulkEmailRequest build() {
            return new SendBulkEmailRequest(source, replyToAddresses, returnPath, configurationSetName,
                    defaultEmailTags, tenantName, region, defaultContent, entries);
        }
    }
}
