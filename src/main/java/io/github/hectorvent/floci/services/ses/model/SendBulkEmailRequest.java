package io.github.hectorvent.floci.services.ses.model;

import java.util.List;
import java.util.Objects;

/**
 * The service-level input of a bulk send, shaped like the v2 {@code SendBulkEmailRequest}: an
 * envelope, the default template every entry renders, and the per-destination entries. The
 * default content is a stored {@link EmailContent.Template}, which the send resolves only after its
 * sender checks, or an {@link EmailContent.InlineTemplate}; its data and headers are the defaults
 * each entry's replacements merge over. {@code sourceArn} and {@code returnPathArn} are the
 * sending-authorization identity ARNs of the sender and the return path. With
 * {@code recipientLimitPerEntry} an entry over the recipient limit fails on its own, as on v1
 * (probe-confirmed); without it the whole call is rejected.
 */
public record SendBulkEmailRequest(
        String source,
        String sourceArn,
        List<String> replyToAddresses,
        String returnPath,
        String returnPathArn,
        String configurationSetName,
        List<MessageTag> defaultEmailTags,
        String tenantName,
        String region,
        boolean recipientLimitPerEntry,
        EmailContent.TemplateContent defaultContent,
        List<BulkEmailEntry> entries) {

    public SendBulkEmailRequest {
        Objects.requireNonNull(defaultContent, "defaultContent");
    }

    /**
     * The name of the stored template the default content names, or {@code null} for inline content.
     */
    public String templateName() {
        return defaultContent instanceof EmailContent.Template stored ? stored.templateName() : null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String source;
        private String sourceArn;
        private List<String> replyToAddresses = List.of();
        private String returnPath;
        private String returnPathArn;
        private String configurationSetName;
        private List<MessageTag> defaultEmailTags = List.of();
        private String tenantName;
        private String region;
        private boolean recipientLimitPerEntry;
        private EmailContent.TemplateContent defaultContent;
        private List<BulkEmailEntry> entries = List.of();

        private Builder() {
        }

        public Builder source(String source) { this.source = source; return this; }
        public Builder sourceArn(String sourceArn) { this.sourceArn = sourceArn; return this; }
        public Builder replyToAddresses(List<String> replyToAddresses) { this.replyToAddresses = replyToAddresses; return this; }
        public Builder returnPath(String returnPath) { this.returnPath = returnPath; return this; }
        public Builder returnPathArn(String returnPathArn) { this.returnPathArn = returnPathArn; return this; }
        public Builder configurationSetName(String configurationSetName) { this.configurationSetName = configurationSetName; return this; }
        public Builder defaultEmailTags(List<MessageTag> defaultEmailTags) { this.defaultEmailTags = defaultEmailTags; return this; }
        public Builder tenantName(String tenantName) { this.tenantName = tenantName; return this; }
        public Builder region(String region) { this.region = region; return this; }
        public Builder recipientLimitPerEntry(boolean recipientLimitPerEntry) { this.recipientLimitPerEntry = recipientLimitPerEntry; return this; }
        public Builder defaultContent(EmailContent.TemplateContent defaultContent) { this.defaultContent = defaultContent; return this; }
        public Builder entries(List<BulkEmailEntry> entries) { this.entries = entries; return this; }

        public SendBulkEmailRequest build() {
            return new SendBulkEmailRequest(source, sourceArn, replyToAddresses, returnPath, returnPathArn,
                    configurationSetName, defaultEmailTags, tenantName, region, recipientLimitPerEntry, defaultContent,
                    entries);
        }
    }
}
