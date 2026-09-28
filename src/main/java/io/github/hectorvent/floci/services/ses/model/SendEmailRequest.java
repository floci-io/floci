package io.github.hectorvent.floci.services.ses.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The service-level input of a single send, shaped like the v2 {@code SendEmailRequest}: an
 * envelope plus an {@link EmailContent}. It is built by the v1 Query handler, the v2 REST
 * controller and Cognito, and rebuilt inside SesService whenever the content is rendered or
 * sanitized, so the request always carries the content being sent. It is not a wire shape: each
 * caller translates its own request into these fields (v2 {@code FromEmailAddress} into
 * {@code source}, {@code FeedbackForwardingEmailAddress} into {@code returnPath}, v1 {@code Tags}
 * into {@code emailTags}, v1 {@code SendRawEmail} {@code Destinations} into {@code toAddresses}),
 * and a field a caller's API lacks keeps the builder default.
 */
public record SendEmailRequest(
        String source,
        List<String> toAddresses,
        List<String> ccAddresses,
        List<String> bccAddresses,
        List<String> replyToAddresses,
        String returnPath,
        String configurationSetName,
        List<MessageTag> emailTags,
        ListManagementOptions listManagement,
        String tenantName,
        String region,
        EmailContent content) {

    public SendEmailRequest {
        Objects.requireNonNull(content, "content");
    }

    public List<String> recipients() {
        return recipients(toAddresses, ccAddresses, bccAddresses);
    }

    public static List<String> recipients(List<String> to, List<String> cc, List<String> bcc) {
        List<String> all = new ArrayList<>();
        if (to != null) {
            all.addAll(to);
        }
        if (cc != null) {
            all.addAll(cc);
        }
        if (bcc != null) {
            all.addAll(bcc);
        }
        return all;
    }

    public boolean hasRecipients() {
        return !recipients().isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .source(source)
                .toAddresses(toAddresses)
                .ccAddresses(ccAddresses)
                .bccAddresses(bccAddresses)
                .replyToAddresses(replyToAddresses)
                .returnPath(returnPath)
                .configurationSetName(configurationSetName)
                .emailTags(emailTags)
                .listManagement(listManagement)
                .tenantName(tenantName)
                .region(region)
                .content(content);
    }

    public static final class Builder {
        private String source;
        private List<String> toAddresses = List.of();
        private List<String> ccAddresses = List.of();
        private List<String> bccAddresses = List.of();
        private List<String> replyToAddresses = List.of();
        private String returnPath;
        private String configurationSetName;
        private List<MessageTag> emailTags = List.of();
        private ListManagementOptions listManagement;
        private String tenantName;
        private String region;
        private EmailContent content;

        private Builder() {
        }

        public Builder source(String source) { this.source = source; return this; }
        public Builder toAddresses(List<String> toAddresses) { this.toAddresses = toAddresses; return this; }
        public Builder ccAddresses(List<String> ccAddresses) { this.ccAddresses = ccAddresses; return this; }
        public Builder bccAddresses(List<String> bccAddresses) { this.bccAddresses = bccAddresses; return this; }
        public Builder replyToAddresses(List<String> replyToAddresses) { this.replyToAddresses = replyToAddresses; return this; }
        public Builder returnPath(String returnPath) { this.returnPath = returnPath; return this; }
        public Builder configurationSetName(String configurationSetName) { this.configurationSetName = configurationSetName; return this; }
        public Builder emailTags(List<MessageTag> emailTags) { this.emailTags = emailTags; return this; }
        public Builder listManagement(ListManagementOptions listManagement) { this.listManagement = listManagement; return this; }
        public Builder tenantName(String tenantName) { this.tenantName = tenantName; return this; }
        public Builder region(String region) { this.region = region; return this; }
        public Builder content(EmailContent content) { this.content = content; return this; }

        public SendEmailRequest build() {
            return new SendEmailRequest(source, toAddresses, ccAddresses, bccAddresses, replyToAddresses,
                    returnPath, configurationSetName, emailTags, listManagement, tenantName, region, content);
        }
    }
}
