package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.SentEmail;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class SesService {

    private static final Logger LOG = Logger.getLogger(SesService.class);

    private final StorageBackend<String, Identity> identityStore;
    private final StorageBackend<String, SentEmail> emailStore;
    private final StorageBackend<String, Boolean> accountSettingsStore;

    @Inject
    public SesService(StorageFactory storageFactory) {
        this.identityStore = storageFactory.create("ses", "ses-identities.json",
                new TypeReference<Map<String, Identity>>() {});
        this.emailStore = storageFactory.create("ses", "ses-emails.json",
                new TypeReference<Map<String, SentEmail>>() {});
        this.accountSettingsStore = storageFactory.create("ses", "ses-account-settings.json",
                new TypeReference<Map<String, Boolean>>() {});
    }

    SesService(StorageBackend<String, Identity> identityStore,
               StorageBackend<String, SentEmail> emailStore,
               StorageBackend<String, Boolean> accountSettingsStore) {
        this.identityStore = identityStore;
        this.emailStore = emailStore;
        this.accountSettingsStore = accountSettingsStore;
    }

    public Identity verifyEmailIdentity(String emailAddress, String region) {
        if (emailAddress == null || emailAddress.isBlank()) {
            throw new AwsException("InvalidParameterValue", "Email address is required.", 400);
        }
        String key = identityKey(region, emailAddress);
        Identity existing = identityStore.get(key).orElse(null);
        if (existing != null) return existing;

        Identity identity = new Identity(emailAddress, "EmailAddress");
        identityStore.put(key, identity);
        LOG.infov("Verified email identity: {0} in region {1}", emailAddress, region);
        return identity;
    }

    public Identity verifyDomainIdentity(String domain, String region) {
        if (domain == null || domain.isBlank()) {
            throw new AwsException("InvalidParameterValue", "Domain is required.", 400);
        }
        String key = identityKey(region, domain);
        Identity existing = identityStore.get(key).orElse(null);
        if (existing != null) return existing;

        Identity identity = new Identity(domain, "Domain");
        identityStore.put(key, identity);
        LOG.infov("Verified domain identity: {0} in region {1}", domain, region);
        return identity;
    }

    public void deleteIdentity(String identityValue, String region) {
        String key = identityKey(region, identityValue);
        identityStore.delete(key);
        LOG.infov("Deleted identity: {0}", identityValue);
    }

    public List<Identity> listIdentities(String identityType, String region) {
        String prefix = "identity::" + region + "::";
        List<Identity> all = identityStore.scan(k -> k.startsWith(prefix));
        if (identityType == null || identityType.isBlank()) {
            return all;
        }
        return all.stream()
                .filter(i -> identityType.equals(i.getIdentityType()))
                .toList();
    }

    public Identity getIdentityVerificationAttributes(String identityValue, String region) {
        String key = identityKey(region, identityValue);
        return identityStore.get(key).orElse(null);
    }

    public String sendEmail(String region, String source,
                            List<String> toAddresses, List<String> ccAddresses,
                            List<String> bccAddresses, List<String> replyToAddresses,
                            String subject, String bodyText, String bodyHtml) {
        if (source == null || source.isBlank()) {
            throw new AwsException("InvalidParameterValue", "Source email is required.", 400);
        }
        boolean hasRecipient = (toAddresses != null && !toAddresses.isEmpty())
                || (ccAddresses != null && !ccAddresses.isEmpty())
                || (bccAddresses != null && !bccAddresses.isEmpty());
        if (!hasRecipient) {
            throw new AwsException("InvalidParameterValue", "At least one destination address is required.", 400);
        }

        String messageId = UUID.randomUUID().toString();
        SentEmail email = new SentEmail(messageId, region, source, toAddresses, ccAddresses,
                bccAddresses, replyToAddresses, subject, bodyText, bodyHtml);
        emailStore.put("email::" + region + "::" + messageId, email);

        LOG.infov("SES email sent: from={0}, to={1}, subject={2}, messageId={3}",
                source, toAddresses, subject, messageId);
        return messageId;
    }

    public String sendRawEmail(String source, List<String> destinations, String rawMessage, String region) {
        if (source == null || source.isBlank()) {
            throw new AwsException("InvalidParameterValue", "Source email is required.", 400);
        }
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new AwsException("InvalidParameterValue", "RawMessage.Data is required.", 400);
        }
        String messageId = UUID.randomUUID().toString();
        SentEmail email = new SentEmail(messageId, region, source,
                destinations != null ? destinations : Collections.emptyList(),
                rawMessage);
        emailStore.put("email::" + region + "::" + messageId, email);

        LOG.infov("SES raw email sent: from={0}, messageId={1}", source, messageId);
        return messageId;
    }

    public long getSentEmailCount(String region) {
        String prefix = "email::" + region + "::";
        return emailStore.scan(k -> k.startsWith(prefix)).size();
    }

    public void setIdentityNotificationTopic(String identityValue, String notificationType,
                                              String snsTopic, String region) {
        String key = identityKey(region, identityValue);
        Identity identity = identityStore.get(key)
                .orElseThrow(() -> new AwsException("InvalidParameterValue",
                        "Identity does not exist: " + identityValue, 400));
        if (snsTopic != null && !snsTopic.isBlank()) {
            identity.getNotificationAttributes().put(notificationType + "Topic", snsTopic);
        } else {
            identity.getNotificationAttributes().remove(notificationType + "Topic");
        }
        identityStore.put(key, identity);
    }

    public Identity getIdentityNotificationAttributes(String identityValue, String region) {
        String key = identityKey(region, identityValue);
        return identityStore.get(key).orElse(null);
    }

    public void setDkimAttributes(String identityValue, boolean signingEnabled, String region) {
        String key = identityKey(region, identityValue);
        Identity identity = identityStore.get(key)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Identity does not exist: " + identityValue, 404));
        identity.setDkimEnabled(signingEnabled);
        if (signingEnabled) {
            identity.setDkimVerificationStatus("Success");
        } else {
            identity.setDkimVerificationStatus("NotStarted");
        }
        identityStore.put(key, identity);
        LOG.infov("Updated DKIM attributes for {0}: signingEnabled={1}", identityValue, signingEnabled);
    }

    public void setFeedbackForwardingEnabled(String identityValue, boolean enabled, String region) {
        String key = identityKey(region, identityValue);
        Identity identity = identityStore.get(key)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Identity does not exist: " + identityValue, 404));
        identity.setFeedbackForwardingEnabled(enabled);
        identityStore.put(key, identity);
        LOG.infov("Updated feedback forwarding for {0}: enabled={1}", identityValue, enabled);
    }

    public List<String> getVerifiedEmailAddresses(String region) {
        String prefix = "identity::" + region + "::";
        List<Identity> all = identityStore.scan(k -> k.startsWith(prefix));
        List<String> emails = new ArrayList<>();
        for (Identity identity : all) {
            if ("EmailAddress".equals(identity.getIdentityType())
                    && "Success".equals(identity.getVerificationStatus())) {
                emails.add(identity.getIdentity());
            }
        }
        return emails;
    }

    public List<SentEmail> getEmails() {
        return emailStore.scan(k -> k.startsWith("email::"));
    }

    public void clearEmails() {
        emailStore.clear();
        LOG.info("Cleared all SES emails");
    }

    public boolean isAccountSendingEnabled(String region) {
        return accountSettingsStore.get("sending::" + region).orElse(true);
    }

    public void setAccountSendingEnabled(String region, boolean enabled) {
        accountSettingsStore.put("sending::" + region, enabled);
        LOG.infov("Updated account sending enabled for region {0}: {1}", region, enabled);
    }

    private static String identityKey(String region, String identity) {
        return "identity::" + region + "::" + identity;
    }
}
