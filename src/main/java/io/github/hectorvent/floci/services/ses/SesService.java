package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntry;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntryResult;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.EmailTemplate;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.SentEmail;
import io.github.hectorvent.floci.services.ses.model.Tag;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class SesService {

    private static final Logger LOG = Logger.getLogger(SesService.class);

    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{\\{\\s*([\\w-]+)\\s*\\}\\}");

    private static final int MAX_BULK_DESTINATIONS = 50;
    private static final int MAX_RECIPIENTS_PER_DESTINATION = 50;

    private static final SecureRandom BOUNDARY_RANDOM = new SecureRandom();

    private final StorageBackend<String, Identity> identityStore;
    private final StorageBackend<String, SentEmail> emailStore;
    private final StorageBackend<String, Boolean> accountSettingsStore;
    private final StorageBackend<String, EmailTemplate> templateStore;
    private final StorageBackend<String, ConfigurationSet> configSetStore;
    private final SmtpRelay smtpRelay;
    private final ObjectMapper objectMapper;

    @Inject
    public SesService(StorageFactory storageFactory, SmtpRelay smtpRelay, ObjectMapper objectMapper) {
        this.identityStore = storageFactory.create("ses", "ses-identities.json",
                new TypeReference<Map<String, Identity>>() {});
        this.emailStore = storageFactory.create("ses", "ses-emails.json",
                new TypeReference<Map<String, SentEmail>>() {});
        this.accountSettingsStore = storageFactory.create("ses", "ses-account-settings.json",
                new TypeReference<Map<String, Boolean>>() {});
        this.templateStore = storageFactory.create("ses", "ses-templates.json",
                new TypeReference<Map<String, EmailTemplate>>() {});
        this.configSetStore = storageFactory.create("ses", "ses-config-sets.json",
                new TypeReference<Map<String, ConfigurationSet>>() {});
        this.smtpRelay = smtpRelay;
        this.objectMapper = objectMapper;
    }

    SesService(StorageBackend<String, Identity> identityStore,
               StorageBackend<String, SentEmail> emailStore,
               StorageBackend<String, Boolean> accountSettingsStore,
               StorageBackend<String, EmailTemplate> templateStore,
               StorageBackend<String, ConfigurationSet> configSetStore,
               SmtpRelay smtpRelay,
               ObjectMapper objectMapper) {
        this.identityStore = identityStore;
        this.emailStore = emailStore;
        this.accountSettingsStore = accountSettingsStore;
        this.templateStore = templateStore;
        this.configSetStore = configSetStore;
        this.smtpRelay = smtpRelay;
        this.objectMapper = objectMapper;
    }

    public Identity verifyEmailIdentity(String emailAddress, String region) {
        validateIdentityWhitespace(emailAddress, "Email address");
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
        validateIdentityWhitespace(domain, "Domain");
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
        if (identityValue == null || identityValue.isBlank()) {
            return;
        }
        String key = identityKey(region, identityValue);
        identityStore.delete(key);

        String prefix = "identity::" + region + "::";
        List<String> keys = new ArrayList<>(identityStore.keys().stream()
                .filter(k -> k.startsWith(prefix))
                .toList());
        for (String storedKey : keys) {
            Identity storedIdentity = identityStore.get(storedKey).orElse(null);
            if (storedIdentity != null && identityValue.equals(storedIdentity.getIdentity())) {
                identityStore.delete(storedKey);
            }
        }

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

    public String sendEmail(String source, List<String> toAddresses, List<String> ccAddresses,
                            List<String> bccAddresses, List<String> replyToAddresses,
                            String subject, String bodyText, String bodyHtml, String region) {
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

        smtpRelay.relay(source, toAddresses, ccAddresses, bccAddresses,
                replyToAddresses, subject, bodyText, bodyHtml);

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

        smtpRelay.relayRaw(source, destinations, rawMessage);

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
        Identity identity = identityStore.get(key).orElse(null);

        if (identity == null) {
            String domain = identityValue != null && identityValue.contains("@")
                    ? identityValue.substring(identityValue.indexOf('@') + 1)
                    : identityValue;
            if (identityValue != null && identityValue.contains("@")
                    && identityStore.get(identityKey(region, domain)).isPresent()) {
                return;
            }
            throw new AwsException("BadRequestException",
                    "Domain " + domain + " is not verified for DKIM signing.", 400);
        }

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
                .orElseThrow(() -> new AwsException("InvalidParameterValue",
                        "Identity " + identityValue
                                + " is invalid. Must be a verified email address or domain.", 400));
        identity.setFeedbackForwardingEnabled(enabled);
        identityStore.put(key, identity);
        LOG.infov("Updated feedback forwarding for {0}: enabled={1}", identityValue, enabled);
    }

    public void setMailFromDomain(String identityValue, String mailFromDomain,
                                   String behaviorOnMxFailure, String region) {
        String normalizedBehavior = null;
        if (behaviorOnMxFailure != null) {
            if (!"UseDefaultValue".equals(behaviorOnMxFailure)
                    && !"RejectMessage".equals(behaviorOnMxFailure)) {
                throw new AwsException("ValidationError",
                        "1 validation error detected: Value at 'behaviorOnMXFailure' failed to satisfy "
                                + "constraint: Member must satisfy enum value set: [RejectMessage, UseDefaultValue]", 400);
            }
            normalizedBehavior = behaviorOnMxFailure;
        }
        boolean clearing = mailFromDomain == null || mailFromDomain.isEmpty();
        if (!clearing && mailFromDomain.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "MailFromDomain must be a domain or an empty string to clear; whitespace is not accepted.", 400);
        }
        String key = identityKey(region, identityValue);
        Identity identity = identityStore.get(key)
                .orElseThrow(() -> new AwsException("InvalidParameterValue",
                        "Identity <" + identityValue + "> does not exist.", 400));
        identity.setMailFromDomain(clearing ? null : mailFromDomain);
        identity.setMailFromDomainStatus(clearing ? "Pending" : "Success");
        if (normalizedBehavior != null) {
            identity.setBehaviorOnMxFailure(normalizedBehavior);
        }
        identityStore.put(key, identity);
        LOG.infov("Updated MAIL FROM domain for {0}: domain={1}, behavior={2}",
                identityValue, mailFromDomain, normalizedBehavior);
    }

    public Identity getMailFromAttributes(String identityValue, String region) {
        String key = identityKey(region, identityValue);
        return identityStore.get(key).orElse(null);
    }

    private static final java.util.List<String> NOTIFICATION_TYPES =
            java.util.List.of("Bounce", "Complaint", "Delivery");

    public void setHeadersInNotificationsEnabled(String identityValue, String notificationType,
                                                   boolean enabled, String region) {
        if (notificationType == null || notificationType.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "NotificationType is required.", 400);
        }
        if (!NOTIFICATION_TYPES.contains(notificationType)) {
            throw new AwsException("ValidationError",
                    "1 validation error detected: Value at 'notificationType' failed to satisfy "
                            + "constraint: Member must satisfy enum value set: "
                            + NOTIFICATION_TYPES, 400);
        }
        String key = identityKey(region, identityValue);
        Identity identity = identityStore.get(key)
                .orElseThrow(() -> new AwsException("InvalidParameterValue",
                        "Identity " + identityValue
                                + " is invalid. It must be a verified email address or domain.", 400));
        identity.getHeadersInNotificationsEnabled().put(notificationType, enabled);
        identityStore.put(key, identity);
        LOG.infov("Updated headers-in-notifications for {0}: {1}={2}",
                identityValue, notificationType, enabled);
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

    // ──────────────────────────── Templates ────────────────────────────

    public EmailTemplate createTemplate(EmailTemplate template, String region) {
        validateTemplate(template);
        if (template.getTags() != null) {
            for (Tag tag : template.getTags()) {
                validateTag(tag);
            }
        }
        String key = templateKey(region, template.getTemplateName());
        if (templateStore.get(key).isPresent()) {
            throw new AwsException("AlreadyExists",
                    "Template " + template.getTemplateName() + " already exists.", 400);
        }
        Instant now = Instant.now();
        template.setCreatedTimestamp(now);
        template.setLastUpdatedTimestamp(now);
        templateStore.put(key, template);
        LOG.infov("Created SES template: {0} in region {1}", template.getTemplateName(), region);
        return template;
    }

    public EmailTemplate getTemplate(String templateName, String region) {
        return templateStore.get(templateKey(region, templateName))
                .orElseThrow(() -> new AwsException("TemplateDoesNotExist",
                        "Template " + templateName + " does not exist.", 400));
    }

    public EmailTemplate updateTemplate(EmailTemplate template, String region) {
        validateTemplate(template);
        String key = templateKey(region, template.getTemplateName());
        EmailTemplate existing = templateStore.get(key)
                .orElseThrow(() -> new AwsException("TemplateDoesNotExist",
                        "Template " + template.getTemplateName() + " does not exist.", 400));
        template.setCreatedTimestamp(existing.getCreatedTimestamp());
        template.setLastUpdatedTimestamp(Instant.now());
        // Tags are managed exclusively via Tag/UntagResource — preserve them on update.
        template.setTags(existing.getTags());
        templateStore.put(key, template);
        LOG.infov("Updated SES template: {0} in region {1}", template.getTemplateName(), region);
        return template;
    }

    public void deleteTemplate(String templateName, String region) {
        String key = templateKey(region, templateName);
        if (templateStore.get(key).isEmpty()) {
            throw new AwsException("TemplateDoesNotExist",
                    "Template " + templateName + " does not exist.", 400);
        }
        templateStore.delete(key);
        LOG.infov("Deleted SES template: {0} in region {1}", templateName, region);
    }

    public List<EmailTemplate> listTemplates(String region) {
        String prefix = "template::" + region + "::";
        List<EmailTemplate> all = new ArrayList<>(templateStore.scan(k -> k.startsWith(prefix)));
        all.sort(Comparator.comparing(EmailTemplate::getCreatedTimestamp,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(EmailTemplate::getTemplateName,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return all;
    }

    public ConfigurationSet createConfigurationSet(ConfigurationSet configSet, String region) {
        if (configSet == null) {
            throw new AwsException("InvalidParameterValue",
                    "ConfigurationSetName is required.", 400);
        }
        String key = configSetKey(region, configSet.getName());
        if (configSet.getTags() != null) {
            for (Tag tag : configSet.getTags()) {
                validateTag(tag);
            }
        }
        if (configSetStore.get(key).isPresent()) {
            throw new AwsException("ConfigurationSetAlreadyExists",
                    "Configuration set " + configSet.getName() + " already exists.", 400);
        }
        if (configSet.getCreatedTimestamp() == null) {
            configSet.setCreatedTimestamp(Instant.now());
        }
        configSetStore.put(key, configSet);
        LOG.infov("Created SES configuration set: {0} in region {1}", configSet.getName(), region);
        return configSet;
    }

    public ConfigurationSet getConfigurationSet(String name, String region) {
        return configSetStore.get(configSetKey(region, name))
                .orElseThrow(() -> new AwsException("ConfigurationSetDoesNotExist",
                        "Configuration set " + name + " does not exist.", 400));
    }

    public List<ConfigurationSet> listConfigurationSets(String region) {
        String prefix = "configSet::" + region + "::";
        List<ConfigurationSet> all = new ArrayList<>(configSetStore.scan(k -> k.startsWith(prefix)));
        all.sort(Comparator.comparing(ConfigurationSet::getCreatedTimestamp,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ConfigurationSet::getName,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return all;
    }

    public void deleteConfigurationSet(String name, String region) {
        String key = configSetKey(region, name);
        if (configSetStore.get(key).isEmpty()) {
            throw new AwsException("ConfigurationSetDoesNotExist",
                    "Configuration set " + name + " does not exist.", 400);
        }
        configSetStore.delete(key);
        LOG.infov("Deleted SES configuration set: {0} in region {1}", name, region);
    }

    private static final Pattern CONFIG_SET_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private static String configSetKey(String region, String name) {
        validateConfigurationSetName(name);
        return "configSet::" + region + "::" + name;
    }

    static void validateConfigurationSetName(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "ConfigurationSetName is required.", 400);
        }
        if (!CONFIG_SET_NAME.matcher(name).matches()) {
            throw new AwsException("InvalidParameterValue",
                    "ConfigurationSetName must be 1-64 characters and may only contain "
                            + "alphanumeric characters, underscores, and hyphens.", 400);
        }
    }

    public List<Tag> listResourceTags(String arn, String region) {
        ResourceRef ref = parseSesArn(arn);
        return switch (ref.type()) {
            case "configuration-set" -> listConfigurationSetTags(ref.name(), ref.region());
            // AWS ListTagsForResource on template / identity ARNs uses the signing region
            // for lookup (the ARN region is effectively ignored), unlike configuration-set
            // which routes by the ARN's region.
            case "template" -> listEmailTemplateTags(ref.name(), region);
            case "identity" -> listIdentityTags(ref.name(), region);
            default -> throw new AwsException("NotFoundException",
                    "Resource " + arn + " was not found.", 404);
        };
    }

    public void tagResource(String arn, String region, List<Tag> newTags) {
        ResourceRef ref = parseSesArn(arn);
        if (!ref.region().equals(region)) {
            throw new AwsException("BadRequestException", "Failed to tag resource", 400);
        }
        if (newTags == null || newTags.isEmpty()) {
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at 'tags' failed to satisfy constraint: "
                            + "Member must have length greater than or equal to 1", 400);
        }
        for (Tag t : newTags) {
            validateTag(t);
        }
        switch (ref.type()) {
            case "configuration-set" -> tagConfigurationSet(ref.name(), ref.region(), newTags);
            case "template" -> tagEmailTemplate(ref.name(), ref.region(), newTags);
            case "identity" -> tagIdentity(ref.name(), ref.region(), newTags);
            default -> throw new AwsException("NotFoundException",
                    "Resource " + arn + " was not found.", 404);
        }
    }

    public void untagResource(String arn, String region, List<String> tagKeys) {
        ResourceRef ref = parseSesArn(arn);
        if (tagKeys == null || tagKeys.isEmpty()) {
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at 'tagKeys' failed to satisfy constraint: "
                            + "Member must have length greater than or equal to 1", 400);
        }
        switch (ref.type()) {
            case "configuration-set" -> untagConfigurationSet(ref.name(), ref.region(), tagKeys);
            case "template" -> {
                // AWS UntagResource on template / identity ARNs strictly requires the ARN
                // region to match the signing region (rejects mismatch with
                // BadRequestException), unlike configuration-set which routes the lookup
                // to the ARN's region.
                if (!ref.region().equals(region)) {
                    throw new AwsException("BadRequestException", "Failed to untag resource", 400);
                }
                untagEmailTemplate(ref.name(), region, tagKeys);
            }
            case "identity" -> {
                if (!ref.region().equals(region)) {
                    throw new AwsException("BadRequestException", "Failed to untag resource", 400);
                }
                untagIdentity(ref.name(), region, tagKeys);
            }
            default -> throw new AwsException("NotFoundException",
                    "Resource " + arn + " was not found.", 404);
        }
    }

    private List<Tag> listConfigurationSetTags(String name, String region) {
        ConfigurationSet cs = configSetStore.get(configSetKey(region, name))
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "No ConfigurationSet present with name: " + name, 404));
        return new ArrayList<>(cs.getTags());
    }

    private void tagConfigurationSet(String name, String region, List<Tag> newTags) {
        String key = configSetKey(region, name);
        ConfigurationSet cs = configSetStore.get(key)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "No ConfigurationSet present with name: " + name, 404));
        cs.setTags(mergeTags(cs.getTags(), newTags));
        configSetStore.put(key, cs);
        LOG.infov("Tagged SES configuration set: {0} (region {1}, +{2} tags)", name, region, newTags.size());
    }

    private void untagConfigurationSet(String name, String region, List<String> tagKeys) {
        String key = configSetKey(region, name);
        ConfigurationSet cs = configSetStore.get(key)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "No ConfigurationSet present with name: " + name, 404));
        Set<String> toRemove = new HashSet<>(tagKeys);
        cs.getTags().removeIf(t -> toRemove.contains(t.key()));
        configSetStore.put(key, cs);
        LOG.infov("Untagged SES configuration set: {0} (region {1}, -{2} keys)", name, region, tagKeys.size());
    }

    private List<Tag> listEmailTemplateTags(String name, String region) {
        EmailTemplate template = templateStore.get(templateKey(region, name))
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "No Template present with name: " + name, 404));
        return new ArrayList<>(template.getTags());
    }

    private void tagEmailTemplate(String name, String region, List<Tag> newTags) {
        String key = templateKey(region, name);
        EmailTemplate template = templateStore.get(key)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "No Template present with name: " + name, 404));
        template.setTags(mergeTags(template.getTags(), newTags));
        templateStore.put(key, template);
        LOG.infov("Tagged SES template: {0} (region {1}, +{2} tags)", name, region, newTags.size());
    }

    private static List<Tag> mergeTags(List<Tag> existing,
                                                         List<Tag> incoming) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (Tag t : existing) {
            merged.put(t.key(), t.value());
        }
        for (Tag t : incoming) {
            merged.put(t.key(), t.value());
        }
        List<Tag> out = new ArrayList<>();
        merged.forEach((k, v) -> out.add(new Tag(k, v)));
        return out;
    }

    private List<Tag> listIdentityTags(String identityValue, String region) {
        Identity identity = identityStore.get(identityKey(region, identityValue))
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "No EmailIdentity present with name: " + identityValue, 404));
        return new ArrayList<>(identity.getTags());
    }

    private void tagIdentity(String identityValue, String region, List<Tag> newTags) {
        String key = identityKey(region, identityValue);
        Identity identity = identityStore.get(key)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "No EmailIdentity present with name: " + identityValue, 404));
        identity.setTags(mergeTags(identity.getTags(), newTags));
        identityStore.put(key, identity);
        LOG.infov("Tagged SES identity: {0} (region {1}, +{2} tags)", identityValue, region, newTags.size());
    }

    private void untagIdentity(String identityValue, String region, List<String> tagKeys) {
        String key = identityKey(region, identityValue);
        Identity identity = identityStore.get(key)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "No EmailIdentity present with name: " + identityValue, 404));
        Set<String> toRemove = new HashSet<>(tagKeys);
        identity.getTags().removeIf(t -> toRemove.contains(t.key()));
        identityStore.put(key, identity);
        LOG.infov("Untagged SES identity: {0} (region {1}, -{2} keys)", identityValue, region, tagKeys.size());
    }

    public void setIdentityTags(String identityValue, String region, List<Tag> tags) {
        if (tags != null) {
            for (Tag tag : tags) {
                validateTag(tag);
            }
        }
        String key = identityKey(region, identityValue);
        Identity identity = identityStore.get(key)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "No EmailIdentity present with name: " + identityValue, 404));
        identity.setTags(tags);
        identityStore.put(key, identity);
    }

    private void untagEmailTemplate(String name, String region, List<String> tagKeys) {
        String key = templateKey(region, name);
        EmailTemplate template = templateStore.get(key)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "No Template present with name: " + name, 404));
        Set<String> toRemove = new HashSet<>(tagKeys);
        template.getTags().removeIf(t -> toRemove.contains(t.key()));
        templateStore.put(key, template);
        LOG.infov("Untagged SES template: {0} (region {1}, -{2} keys)", name, region, tagKeys.size());
    }

    private record ResourceRef(String region, String type, String name) {}

    private static ResourceRef parseSesArn(String arn) {
        if (arn == null || arn.isBlank()) {
            throw new AwsException("BadRequestException", "ResourceArn is required.", 400);
        }
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("BadRequestException", "Invalid ARN: " + arn, 400);
        }
        if (!"ses".equals(parsed.service())) {
            throw new AwsException("BadRequestException",
                    "ResourceArn must be a SES ARN: " + arn, 400);
        }
        if (parsed.region().isEmpty() || parsed.accountId().isEmpty()) {
            throw new AwsException("BadRequestException",
                    "ResourceArn must include region and account: " + arn, 400);
        }
        String resource = parsed.resource();
        int slash = resource.indexOf('/');
        if (slash <= 0 || slash == resource.length() - 1) {
            throw new AwsException("BadRequestException", "Invalid ARN: " + arn, 400);
        }
        return new ResourceRef(parsed.region(), resource.substring(0, slash), resource.substring(slash + 1));
    }

    static void validateTag(Tag tag) {
        if (tag == null) {
            throw new AwsException("InvalidParameterValue", "Tag must not be null.", 400);
        }
        String key = tag.key();
        if (key == null || key.isEmpty()) {
            throw new AwsException("InvalidParameterValue", "Tag Key is required.", 400);
        }
        if (key.length() > 128) {
            throw new AwsException("InvalidParameterValue",
                    "Tag Key must be 1-128 characters.", 400);
        }
        String value = tag.value();
        if (value != null && value.length() > 256) {
            throw new AwsException("InvalidParameterValue",
                    "Tag Value must be 0-256 characters.", 400);
        }
    }

    public String sendTemplatedEmail(String source, List<String> toAddresses, List<String> ccAddresses,
                                     List<String> bccAddresses, List<String> replyToAddresses,
                                     String templateName, JsonNode templateData, String region) {
        EmailTemplate template = getTemplate(templateName, region);
        return sendInlineTemplatedEmail(source, toAddresses, ccAddresses, bccAddresses,
                replyToAddresses, template.getSubject(), template.getTextPart(),
                template.getHtmlPart(), templateData, region);
    }

    public String renderTestTemplate(String templateName, String templateDataRaw, String region) {
        EmailTemplate template = getTemplate(templateName, region);
        JsonNode templateData = parseRenderingData(objectMapper, templateDataRaw);
        String subject = applyTemplateData(template.getSubject(), templateData);
        String text = applyTemplateData(template.getTextPart(), templateData);
        String html = applyTemplateData(template.getHtmlPart(), templateData);
        return buildTestRenderMime(subject, text, html, ZonedDateTime.now(ZoneOffset.UTC), nextBoundary());
    }

    static JsonNode parseRenderingData(ObjectMapper mapper, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AwsException("InvalidRenderingParameter",
                    "Template rendering data is required.", 400);
        }
        JsonNode node;
        try {
            node = mapper.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("InvalidRenderingParameter",
                    "Template rendering data is invalid: " + e.getOriginalMessage(), 400);
        }
        if (!node.isObject()) {
            throw new AwsException("InvalidRenderingParameter",
                    "Template rendering data must be a JSON object.", 400);
        }
        return node;
    }

    static String buildTestRenderMime(String subject, String text, String html,
                                       ZonedDateTime date, String boundary) {
        String safeSubject = sanitizeSubject(subject);
        String safeText = text == null ? "" : text;
        String safeHtml = html == null ? "" : html;
        String dateHeader = DateTimeFormatter.RFC_1123_DATE_TIME.format(date);
        StringBuilder out = new StringBuilder();
        out.append("Date: ").append(dateHeader).append("\r\n");
        out.append("Subject: ").append(safeSubject).append("\r\n");
        out.append("MIME-Version: 1.0\r\n");
        out.append("Content-Type: multipart/alternative; boundary=\"").append(boundary).append("\"\r\n");
        out.append("\r\n");
        appendMimePart(out, boundary, "text/plain", safeText);
        appendMimePart(out, boundary, "text/html", safeHtml);
        out.append("--").append(boundary).append("--\r\n");
        return out.toString();
    }

    private static void appendMimePart(StringBuilder out, String boundary, String mimeType, String body) {
        out.append("--").append(boundary).append("\r\n");
        out.append("Content-Type: ").append(mimeType).append("; charset=UTF-8\r\n");
        out.append("Content-Transfer-Encoding: ").append(pickTransferEncoding(body)).append("\r\n");
        out.append("\r\n");
        String normalized = normalizeToCrlf(body);
        out.append(normalized);
        if (!normalized.endsWith("\r\n")) {
            out.append("\r\n");
        }
    }

    static String normalizeToCrlf(String body) {
        return body.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n");
    }

    static String pickTransferEncoding(String body) {
        return body.codePoints().allMatch(c -> c < 128) ? "7bit" : "8bit";
    }

    static String sanitizeSubject(String subject) {
        if (subject == null) {
            return "";
        }
        // Strip C0 control characters (U+0000-U+001F) and DEL (U+007F): RFC 5322
        // forbids them in unstructured header field bodies. Replace with spaces so
        // visible content is preserved when template data accidentally injects them.
        StringBuilder out = new StringBuilder(subject.length());
        for (int i = 0; i < subject.length(); i++) {
            char c = subject.charAt(i);
            out.append((c < 0x20 || c == 0x7F) ? ' ' : c);
        }
        return out.toString();
    }

    static String stripXml10InvalidChars(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        // XML 1.0 char production: \t \n \r, U+0020-U+D7FF, U+E000-U+FFFD,
        // U+10000-U+10FFFF. Anything else (C0 controls, U+FFFE/U+FFFF, lone
        // surrogates) makes the response unparseable by SDK XML parsers.
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            if (isXml10Char(cp)) {
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static boolean isXml10Char(int cp) {
        return cp == 0x09 || cp == 0x0A || cp == 0x0D
                || (cp >= 0x20 && cp <= 0xD7FF)
                || (cp >= 0xE000 && cp <= 0xFFFD)
                || (cp >= 0x10000 && cp <= 0x10FFFF);
    }

    private static String nextBoundary() {
        byte[] bytes = new byte[6];
        BOUNDARY_RANDOM.nextBytes(bytes);
        return "===_floci_" + HexFormat.of().formatHex(bytes) + "_===";
    }

    public String sendInlineTemplatedEmail(String source, List<String> toAddresses, List<String> ccAddresses,
                                            List<String> bccAddresses, List<String> replyToAddresses,
                                            String subject, String textPart, String htmlPart,
                                            JsonNode templateData, String region) {
        boolean hasSubject = subject != null && !subject.isBlank();
        boolean hasText = textPart != null && !textPart.isBlank();
        boolean hasHtml = htmlPart != null && !htmlPart.isBlank();
        if (!hasSubject && !hasText && !hasHtml) {
            throw new AwsException("InvalidTemplate",
                    "Template must have at least a subject, text, or html part.", 400);
        }
        return sendEmail(source, toAddresses, ccAddresses, bccAddresses, replyToAddresses,
                applyTemplateData(subject, templateData),
                applyTemplateData(textPart, templateData),
                applyTemplateData(htmlPart, templateData),
                region);
    }

    public List<BulkEmailEntryResult> sendBulkTemplatedEmail(String source,
                                                              List<String> replyToAddresses,
                                                              String subject, String textPart, String htmlPart,
                                                              JsonNode defaultTemplateData,
                                                              List<BulkEmailEntry> entries,
                                                              String region) {
        if (source == null || source.isBlank()) {
            throw new AwsException("InvalidParameterValue", "Source email is required.", 400);
        }
        boolean hasSubject = subject != null && !subject.isBlank();
        boolean hasText = textPart != null && !textPart.isBlank();
        boolean hasHtml = htmlPart != null && !htmlPart.isBlank();
        if (!hasSubject && !hasText && !hasHtml) {
            throw new AwsException("InvalidTemplate",
                    "Template must have at least a subject, text, or html part.", 400);
        }
        if (entries == null || entries.isEmpty()) {
            throw new AwsException("InvalidParameterValue",
                    "At least one destination entry is required.", 400);
        }
        if (entries.size() > MAX_BULK_DESTINATIONS) {
            throw new AwsException("MessageRejected",
                    "Number of destinations (" + entries.size() + ") exceeds the maximum of "
                            + MAX_BULK_DESTINATIONS + ".", 400);
        }
        for (BulkEmailEntry entry : entries) {
            int recipientCount = sizeOf(entry.toAddresses())
                    + sizeOf(entry.ccAddresses())
                    + sizeOf(entry.bccAddresses());
            if (recipientCount > MAX_RECIPIENTS_PER_DESTINATION) {
                throw new AwsException("MessageRejected",
                        "Recipient count (" + recipientCount + ") in a destination exceeds the maximum of "
                                + MAX_RECIPIENTS_PER_DESTINATION + ".", 400);
            }
        }

        List<BulkEmailEntryResult> results = new ArrayList<>(entries.size());
        for (BulkEmailEntry entry : entries) {
            try {
                JsonNode merged = mergeTemplateData(defaultTemplateData, entry.replacementTemplateData());
                String messageId = sendEmail(source,
                        entry.toAddresses(), entry.ccAddresses(), entry.bccAddresses(),
                        replyToAddresses,
                        applyTemplateData(subject, merged),
                        applyTemplateData(textPart, merged),
                        applyTemplateData(htmlPart, merged),
                        region);
                results.add(BulkEmailEntryResult.success(messageId));
            } catch (AwsException e) {
                results.add(BulkEmailEntryResult.failure(
                        mapErrorCodeToBulkStatus(e.getErrorCode()), e.getMessage()));
            } catch (Exception e) {
                results.add(BulkEmailEntryResult.failure(BulkEmailEntryResult.Status.FAILED, e.getMessage()));
            }
        }
        return results;
    }

    private static int sizeOf(List<?> list) {
        return list == null ? 0 : list.size();
    }

    static BulkEmailEntryResult.Status mapErrorCodeToBulkStatus(String errorCode) {
        if ("InvalidParameterValue".equals(errorCode)
                || "MissingRenderingAttribute".equals(errorCode)
                || "InvalidRenderingParameter".equals(errorCode)) {
            return BulkEmailEntryResult.Status.INVALID_PARAMETER;
        }
        return BulkEmailEntryResult.Status.FAILED;
    }

    static JsonNode mergeTemplateData(JsonNode defaults, JsonNode replacement) {
        boolean hasDefault = defaults != null && defaults.isObject();
        boolean hasReplacement = replacement != null && replacement.isObject();
        if (!hasDefault && !hasReplacement) {
            return null;
        }
        if (!hasReplacement) {
            return defaults;
        }
        if (!hasDefault) {
            return replacement;
        }
        if (replacement.isEmpty()) {
            return defaults;
        }
        if (defaults.isEmpty()) {
            return replacement;
        }
        ObjectNode merged = ((ObjectNode) defaults).deepCopy();
        replacement.fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue()));
        return merged;
    }

    static String applyTemplateData(String text, JsonNode data) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = TEMPLATE_VARIABLE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (data == null || !data.hasNonNull(key)) {
                throw new AwsException("MissingRenderingAttribute",
                        "Attribute '" + key + "' is not present in the rendering data.", 400);
            }
            JsonNode value = data.get(key);
            String replacement = value.isValueNode() ? value.asText() : value.toString();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static void validateTemplate(EmailTemplate template) {
        if (template == null) {
            throw new AwsException("InvalidTemplate", "Template is required.", 400);
        }
        validateTemplateName(template.getTemplateName());
        boolean hasSubject = template.getSubject() != null && !template.getSubject().isBlank();
        boolean hasText = template.getTextPart() != null && !template.getTextPart().isBlank();
        boolean hasHtml = template.getHtmlPart() != null && !template.getHtmlPart().isBlank();
        if (!hasSubject && !hasText && !hasHtml) {
            throw new AwsException("InvalidTemplate",
                    "Template must have at least a subject, text, or html part.", 400);
        }
    }

    private static void validateTemplateName(String templateName) {
        if (templateName == null || templateName.isBlank()) {
            throw new AwsException("InvalidTemplate", "TemplateName is required.", 400);
        }
        if (Character.isWhitespace(templateName.charAt(0))
                || Character.isWhitespace(templateName.charAt(templateName.length() - 1))) {
            throw new AwsException("InvalidTemplate",
                    "TemplateName must not contain leading or trailing whitespace.", 400);
        }
    }

    private static String templateKey(String region, String templateName) {
        validateTemplateName(templateName);
        return "template::" + region + "::" + templateName;
    }

    /**
     * Extracts the template name from an SES template ARN of the form
     * {@code arn:aws:ses:<region>:<account>:template/<name>}. Region and
     * account segments are not validated; only the {@code template/<name>}
     * suffix is required.
     */
    public static String templateNameFromArn(String arn) {
        if (arn == null || arn.isBlank()) {
            throw new AwsException("InvalidParameterValue", "TemplateArn is required.", 400);
        }
        int marker = arn.indexOf(":template/");
        if (!arn.startsWith("arn:") || marker < 0) {
            throw new AwsException("InvalidParameterValue",
                    "TemplateArn is not a valid SES template ARN: " + arn, 400);
        }
        String name = arn.substring(marker + ":template/".length());
        if (name.isEmpty()) {
            throw new AwsException("InvalidParameterValue",
                    "TemplateArn is missing a template name: " + arn, 400);
        }
        return name;
    }

    private static String identityKey(String region, String identity) {
        validateIdentityWhitespace(identity, "Identity");
        return "identity::" + region + "::" + identity;
    }

    private static void validateIdentityWhitespace(String identity, String fieldName) {
        if (identity == null || identity.isBlank()) {
            return;
        }
        if (Character.isWhitespace(identity.charAt(0)) || Character.isWhitespace(identity.charAt(identity.length() - 1))) {
            throw new AwsException("InvalidParameterValue", fieldName + " must not contain leading or trailing whitespace.", 400);
        }
    }
}
