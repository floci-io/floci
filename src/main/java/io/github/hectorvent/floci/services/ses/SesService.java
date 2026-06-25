package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ses.model.AccountSuppressionAttributes;
import io.github.hectorvent.floci.services.ses.model.ArchivingOptions;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntry;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntryResult;
import io.github.hectorvent.floci.services.ses.model.CloudWatchDimensionConfiguration;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.DedicatedIpPool;
import io.github.hectorvent.floci.services.ses.model.DeliveryOptions;
import io.github.hectorvent.floci.services.ses.model.EmailTemplate;
import io.github.hectorvent.floci.services.ses.model.EventDestination;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.MessageHeader;
import io.github.hectorvent.floci.services.ses.model.MessageTag;
import io.github.hectorvent.floci.services.ses.model.TrackingOptions;
import io.github.hectorvent.floci.services.ses.model.VdmOptions;
import io.github.hectorvent.floci.services.ses.model.SentEmail;
import io.github.hectorvent.floci.services.ses.model.SuppressedDestination;
import io.github.hectorvent.floci.services.ses.model.SuppressionOptions;
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
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
    private final StorageBackend<String, SuppressedDestination> suppressionStore;
    private final StorageBackend<String, AccountSuppressionAttributes> accountSuppressionStore;
    private final StorageBackend<String, DedicatedIpPool> dedicatedIpPoolStore;
    private final SmtpRelay smtpRelay;
    private final ObjectMapper objectMapper;
    private final SesEventPublisher eventPublisher;
    private final String defaultAccountId;

    @Inject
    public SesService(StorageFactory storageFactory, SmtpRelay smtpRelay, ObjectMapper objectMapper,
                       SesEventPublisher eventPublisher, EmulatorConfig config) {
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
        this.suppressionStore = storageFactory.create("ses", "ses-suppression.json",
                new TypeReference<Map<String, SuppressedDestination>>() {});
        this.accountSuppressionStore = storageFactory.create("ses", "ses-account-suppression.json",
                new TypeReference<Map<String, AccountSuppressionAttributes>>() {});
        this.dedicatedIpPoolStore = storageFactory.create("ses", "ses-dedicated-ip-pools.json",
                new TypeReference<Map<String, DedicatedIpPool>>() {});
        this.smtpRelay = smtpRelay;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.defaultAccountId = config.defaultAccountId();
    }

    SesService(StorageBackend<String, Identity> identityStore,
               StorageBackend<String, SentEmail> emailStore,
               StorageBackend<String, Boolean> accountSettingsStore,
               StorageBackend<String, EmailTemplate> templateStore,
               StorageBackend<String, ConfigurationSet> configSetStore,
               StorageBackend<String, SuppressedDestination> suppressionStore,
               StorageBackend<String, AccountSuppressionAttributes> accountSuppressionStore,
               StorageBackend<String, DedicatedIpPool> dedicatedIpPoolStore,
               SmtpRelay smtpRelay,
               ObjectMapper objectMapper) {
        this.identityStore = identityStore;
        this.emailStore = emailStore;
        this.accountSettingsStore = accountSettingsStore;
        this.templateStore = templateStore;
        this.configSetStore = configSetStore;
        this.suppressionStore = suppressionStore;
        this.accountSuppressionStore = accountSuppressionStore;
        this.dedicatedIpPoolStore = dedicatedIpPoolStore;
        this.smtpRelay = smtpRelay;
        this.objectMapper = objectMapper;
        this.eventPublisher = null;
        this.defaultAccountId = "000000000000";
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
                            String subject, String bodyText, String bodyHtml,
                            String configurationSetName, List<MessageTag> emailTags,
                            List<MessageHeader> additionalHeaders, String region) {
        if (source == null || source.isBlank()) {
            throw new AwsException("InvalidParameterValue", "Source email is required.", 400);
        }
        boolean hasRecipient = (toAddresses != null && !toAddresses.isEmpty())
                || (ccAddresses != null && !ccAddresses.isEmpty())
                || (bccAddresses != null && !bccAddresses.isEmpty());
        if (!hasRecipient) {
            throw new AwsException("InvalidParameterValue", "At least one destination address is required.", 400);
        }
        validateConfigurationSet(configurationSetName, region);

        String messageId = UUID.randomUUID().toString();
        SentEmail email = new SentEmail(messageId, region, source, toAddresses, ccAddresses,
                bccAddresses, replyToAddresses, subject, bodyText, bodyHtml);
        emailStore.put("email::" + region + "::" + messageId, email);

        List<String> envelope = allRecipients(toAddresses, ccAddresses, bccAddresses);
        Map<String, String> suppressedReasons = collectSuppressedReasons(envelope, configurationSetName, region);

        List<String> relayedTo = filterUnsuppressed(toAddresses, suppressedReasons);
        List<String> relayedCc = filterUnsuppressed(ccAddresses, suppressedReasons);
        List<String> relayedBcc = filterUnsuppressed(bccAddresses, suppressedReasons);
        if (sizeOf(relayedTo) + sizeOf(relayedCc) + sizeOf(relayedBcc) > 0) {
            smtpRelay.relay(source, relayedTo, relayedCc, relayedBcc,
                    replyToAddresses, subject, bodyText, bodyHtml);
        } else {
            LOG.infov("SES email accepted but not relayed (all recipients suppressed): messageId={0}",
                    messageId);
        }

        LOG.infov("SES email sent: from={0}, to={1}, subject={2}, messageId={3}",
                source, toAddresses, subject, messageId);
        publishSendEvents(configurationSetName, messageId, source, subject,
                toAddresses, ccAddresses, bccAddresses, envelope,
                suppressedReasons, emailTags, additionalHeaders, region);
        return messageId;
    }

    public String sendRawEmail(String source, List<String> destinations, String rawMessage,
                               String configurationSetName, List<MessageTag> emailTags,
                               String region) {
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new AwsException("InvalidParameterValue", "RawMessage.Data is required.", 400);
        }
        validateConfigurationSet(configurationSetName, region);
        boolean hasExplicitDestinations = destinations != null && !destinations.isEmpty();
        boolean sourceOmitted = source == null || source.isBlank();
        boolean willPublishEvents = (configurationSetName != null && !configurationSetName.isBlank())
                || !resolveIdentityNotificationTargets(source, region).isEmpty();
        SmtpRelay.RawMessageHeaders headers = (hasExplicitDestinations && !willPublishEvents && !sourceOmitted)
                ? null
                : SmtpRelay.parseRawHeaders(rawMessage);
        String effectiveSource = sourceOmitted && headers != null && !headers.from().isBlank()
                ? headers.from()
                : source;
        List<String> effectiveDestinations = hasExplicitDestinations
                ? destinations
                : allRecipients(headers.to(), headers.cc(), headers.bcc());
        if (effectiveDestinations.isEmpty()) {
            throw new AwsException("InvalidParameterValue",
                    "At least one destination address is required.", 400);
        }
        String messageId = UUID.randomUUID().toString();
        SentEmail email = new SentEmail(messageId, region, effectiveSource, effectiveDestinations, rawMessage);
        emailStore.put("email::" + region + "::" + messageId, email);

        Map<String, String> suppressedReasons = collectSuppressedReasons(effectiveDestinations, configurationSetName, region);

        List<String> relayedDestinations = filterUnsuppressed(effectiveDestinations, suppressedReasons);
        if (!relayedDestinations.isEmpty()) {
            smtpRelay.relayRaw(effectiveSource, relayedDestinations, rawMessage);
        } else {
            LOG.infov("SES raw email accepted but not relayed (all recipients suppressed): messageId={0}",
                    messageId);
        }

        LOG.infov("SES raw email sent: from={0}, messageId={1}", effectiveSource, messageId);
        publishSendEvents(configurationSetName, messageId, effectiveSource,
                headers != null ? headers.subject() : "",
                headers != null ? headers.to() : List.of(),
                headers != null ? headers.cc() : List.of(),
                headers != null ? headers.bcc() : List.of(),
                effectiveDestinations,
                suppressedReasons, emailTags, List.of(), region);
        return messageId;
    }

    private static List<String> allRecipients(List<String> to, List<String> cc, List<String> bcc) {
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

    /**
     * Validate that a non-blank {@code ConfigurationSetName} is usable for a send. Performs
     * two gates:
     *   1. Existence — raises {@code ConfigurationSetDoesNotExist} (400) when the set is
     *      missing in the given region. The V2 REST controller's {@code remapV1Exception}
     *      translates that into {@code NotFoundException 404}; V1 Query keeps the original.
     *   2. Sending-enabled — raises {@code ConfigurationSetSendingPausedException} (400)
     *      when the set's {@code SendingEnabled} flag has been turned off via
     *      {@code UpdateConfigurationSetSendingEnabled} (v1) /
     *      {@code PutConfigurationSetSendingOptions} (v2). The V2 controller narrows that
     *      code to {@code SendingPausedException}; V1 keeps the longer form, matching the
     *      exact wire shape AWS returns on each surface.
     * Mirrors AWS SES behaviour: invalid or paused set fails fast instead of silently
     * storing/relaying the message and skipping event publishing later.
     */
    private void validateConfigurationSet(String configurationSetName, String region) {
        if (configurationSetName == null || configurationSetName.isBlank()) {
            return;
        }
        ConfigurationSet cs = configSetStore.get(configSetKey(region, configurationSetName))
                .orElseThrow(() -> new AwsException("ConfigurationSetDoesNotExist",
                        "Configuration set <" + configurationSetName + "> does not exist.", 400));
        if (cs.getSendingEnabled() != null && !cs.getSendingEnabled()) {
            throw new AwsException("ConfigurationSetSendingPausedException",
                    "Sending is paused for configuration set " + configurationSetName, 400);
        }
    }

    private void publishSendEvents(String configurationSetName, String messageId, String source,
                                   String subject, List<String> toAddresses,
                                   List<String> ccAddresses, List<String> bccAddresses,
                                   List<String> envelopeDestinations,
                                   Map<String, String> suppressedReasons,
                                   List<MessageTag> emailTags,
                                   List<MessageHeader> additionalHeaders, String region) {
        if (eventPublisher == null || messageId == null) {
            return;
        }
        ConfigurationSet cs = null;
        if (configurationSetName != null && !configurationSetName.isBlank()) {
            cs = configSetStore.get(configSetKey(region, configurationSetName)).orElse(null);
            if (cs == null) {
                LOG.warnv("SES send references unknown ConfigurationSet <{0}>; configuration-set "
                        + "events not published (identity notifications, if any, still apply).",
                        configurationSetName);
            }
        }
        boolean configSetActive = cs != null && !cs.getEventDestinations().isEmpty();
        Map<String, IdentityNotificationTarget> identityTargets =
                resolveIdentityNotificationTargets(source, region);
        if (!configSetActive && identityTargets.isEmpty()) {
            return;
        }

        List<String> envelope = envelopeDestinations != null
                ? envelopeDestinations : Collections.emptyList();
        Instant timestamp = Instant.now();
        String sendingAccountId = defaultAccountId;
        String sourceArn = (source == null || source.isBlank())
                ? null
                : AwsArnUtils.Arn.of("ses", region, sendingAccountId,
                        "identity/" + extractEmailAddress(source)).toString();

        List<String> suppressionBounceRecipients = new ArrayList<>();
        List<String> suppressionComplaintRecipients = new ArrayList<>();
        for (Map.Entry<String, String> e : suppressedReasons.entrySet()) {
            if ("BOUNCE".equals(e.getValue())) {
                suppressionBounceRecipients.add(e.getKey());
            } else if ("COMPLAINT".equals(e.getValue())) {
                suppressionComplaintRecipients.add(e.getKey());
            }
        }

        for (String eventType : determineSendEventTypes(envelope,
                suppressionBounceRecipients, suppressionComplaintRecipients)) {
            if (configSetActive) {
                eventPublisher.publish(cs, eventType, messageId, source, sourceArn, sendingAccountId,
                        subject, toAddresses, ccAddresses, bccAddresses, envelope,
                        suppressionBounceRecipients, suppressionComplaintRecipients,
                        emailTags, additionalHeaders, timestamp, region);
            }
            IdentityNotificationTarget target = identityTargets.get(eventType);
            if (target != null) {
                eventPublisher.publishIdentityNotification(target.topicArn(), target.includeHeaders(),
                        eventType, messageId, source, sourceArn, sendingAccountId, subject,
                        toAddresses, ccAddresses, bccAddresses, envelope, suppressionBounceRecipients,
                        suppressionComplaintRecipients, additionalHeaders, timestamp, region);
            }
        }
    }

    /**
     * Resolves the SNS feedback notification target configured via {@code SetIdentityNotificationTopic}
     * for the sending identity. The email-address identity's topic takes precedence over its parent
     * domain identity's topic, per notification type; the headers-in-notifications flag is read from
     * whichever identity supplied the topic. Returns a map keyed by the {@code SEND}-style event name
     * ({@code BOUNCE}/{@code COMPLAINT}/{@code DELIVERY}) so it can be looked up directly against
     * {@link #determineSendEventTypes}.
     */
    private Map<String, IdentityNotificationTarget> resolveIdentityNotificationTargets(String source,
                                                                                       String region) {
        Map<String, IdentityNotificationTarget> targets = new LinkedHashMap<>();
        if (source == null || source.isBlank()) {
            return targets;
        }
        String email = extractEmailAddress(source);
        if (email.isBlank()) {
            return targets;
        }
        Identity emailIdentity = identityStore.get(identityKey(region, email)).orElse(null);
        Identity domainIdentity = null;
        int at = email.indexOf('@');
        if (at >= 0 && at < email.length() - 1) {
            domainIdentity = identityStore.get(identityKey(region, email.substring(at + 1))).orElse(null);
        }
        for (String type : NOTIFICATION_TYPES) {
            String topic = notificationTopicFor(emailIdentity, type);
            Identity owner = emailIdentity;
            if (topic == null) {
                topic = notificationTopicFor(domainIdentity, type);
                owner = domainIdentity;
            }
            if (topic == null) {
                continue;
            }
            boolean includeHeaders = Boolean.TRUE.equals(
                    owner.getHeadersInNotificationsEnabled().get(type));
            targets.put(type.toUpperCase(Locale.ROOT),
                    new IdentityNotificationTarget(topic, includeHeaders));
        }
        return targets;
    }

    private static String notificationTopicFor(Identity identity, String type) {
        if (identity == null) {
            return null;
        }
        String topic = identity.getNotificationAttributes().get(type + "Topic");
        return topic != null && !topic.isBlank() ? topic : null;
    }

    private record IdentityNotificationTarget(String topicArn, boolean includeHeaders) {}

    private static String extractEmailAddress(String source) {
        int open = source.indexOf('<');
        int close = source.indexOf('>', open + 1);
        if (open >= 0 && close > open) {
            return source.substring(open + 1, close).trim();
        }
        return source.trim();
    }

    private static List<String> determineSendEventTypes(List<String> destinations,
                                                        List<String> suppressionBounceRecipients,
                                                        List<String> suppressionComplaintRecipients) {
        List<String> events = new ArrayList<>();
        events.add("SEND");
        for (String d : destinations) {
            if (SimulatorAddresses.isSuccess(d) && !events.contains("DELIVERY")) {
                events.add("DELIVERY");
            }
            if (SimulatorAddresses.isBounce(d) && !events.contains("BOUNCE")) {
                events.add("BOUNCE");
            }
            if (SimulatorAddresses.isComplaint(d) && !events.contains("COMPLAINT")) {
                events.add("COMPLAINT");
            }
            if (SimulatorAddresses.isSuppressionList(d) && !events.contains("REJECT")) {
                events.add("REJECT");
            }
        }
        if (!suppressionBounceRecipients.isEmpty() && !events.contains("BOUNCE")) {
            events.add("BOUNCE");
        }
        if (!suppressionComplaintRecipients.isEmpty() && !events.contains("COMPLAINT")) {
            events.add("COMPLAINT");
        }
        return events;
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
        if (clearing) {
            identity.setBehaviorOnMxFailure("UseDefaultValue");
        } else if (normalizedBehavior != null) {
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

    public void setConfigurationSetSendingEnabled(String configSetName, boolean enabled, String region) {
        ConfigurationSet cs = getConfigurationSet(configSetName, region);
        cs.setSendingEnabled(enabled);
        configSetStore.put(configSetKey(region, configSetName), cs);
        LOG.infov("Updated SendingEnabled on configuration set {0} in region {1}: {2}",
                configSetName, region, enabled);
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
        if (configSet.getSuppressionOptions() != null
                && configSet.getSuppressionOptions().getSuppressedReasons() != null) {
            for (String reason : configSet.getSuppressionOptions().getSuppressedReasons()) {
                if (reason == null) {
                    throw new AwsException("BadRequestException",
                            invalidSuppressionReasonMessage(null), 400);
                }
                if (!isValidConfigSetSuppressionReason(reason)) {
                    throw new AwsException("BadRequestException",
                            "1 validation error detected: Value at "
                                    + "'suppressionOptions.suppressedReasons' failed to satisfy "
                                    + "constraint: Member must satisfy constraint: "
                                    + "[Member must satisfy enum value set: [BOUNCE, COMPLAINT]]",
                            400);
                }
            }
        }
        validateTrackingOptions(configSet.getTrackingOptions(), region);
        validateDeliveryOptions(configSet.getDeliveryOptions(), region);
        validateVdmOptions(configSet.getVdmOptions());
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

    public void setConfigurationSetTrackingOptions(String configSetName, TrackingOptions options, String region) {
        ConfigurationSet cs = getConfigurationSet(configSetName, region);
        validateTrackingOptions(options, region);
        cs.setTrackingOptions(options);
        configSetStore.put(configSetKey(region, configSetName), cs);
        LOG.infov("Updated TrackingOptions on configuration set {0} in region {1}", configSetName, region);
    }

    public void setConfigurationSetDeliveryOptions(String configSetName, DeliveryOptions options, String region) {
        ConfigurationSet cs = getConfigurationSet(configSetName, region);
        validateDeliveryOptions(options, region);
        cs.setDeliveryOptions(options);
        configSetStore.put(configSetKey(region, configSetName), cs);
        LOG.infov("Updated DeliveryOptions on configuration set {0} in region {1}", configSetName, region);
    }

    public void setConfigurationSetReputationOptions(String configSetName, boolean metricsEnabled, String region) {
        ConfigurationSet cs = getConfigurationSet(configSetName, region);
        cs.setReputationMetricsEnabled(metricsEnabled);
        configSetStore.put(configSetKey(region, configSetName), cs);
        LOG.infov("Updated ReputationMetricsEnabled on configuration set {0} in region {1}: {2}",
                configSetName, region, metricsEnabled);
    }

    private boolean isVerifiedDomainIdentity(String domain, String region) {
        Identity identity = getIdentityVerificationAttributes(domain, region);
        return identity != null && "Success".equals(identity.getVerificationStatus())
                && "Domain".equals(identity.getIdentityType());
    }

    private void requireVerifiedRedirectDomain(String domain, String region) {
        if (domain == null) {
            throw new AwsException("ValidationError",
                    "1 validation error detected: Value at 'trackingOptions' failed to satisfy constraint: "
                            + "Member must not be null", 400);
        }
        if (domain.isBlank()) {
            throw new AwsException("InvalidTrackingOptions",
                    "At least one field of TrackingOptions must contain a value.", 400);
        }
        if (!isVerifiedDomainIdentity(domain, region)) {
            throw new AwsException("InvalidTrackingOptions",
                    "Domain <" + domain + "> is not verified under this account.", 400);
        }
    }

    public void createConfigurationSetTrackingOptions(String configSetName, String customRedirectDomain,
                                                      String region) {
        requireVerifiedRedirectDomain(customRedirectDomain, region);
        ConfigurationSet cs = getConfigurationSet(configSetName, region);
        if (cs.getTrackingOptions() != null && cs.getTrackingOptions().getCustomRedirectDomain() != null) {
            throw new AwsException("TrackingOptionsAlreadyExistsException",
                    "Configuration set <" + configSetName + "> already has tracking options.", 400);
        }
        TrackingOptions options = new TrackingOptions();
        options.setCustomRedirectDomain(customRedirectDomain);
        cs.setTrackingOptions(options);
        configSetStore.put(configSetKey(region, configSetName), cs);
        LOG.infov("Created TrackingOptions on configuration set {0} in region {1}", configSetName, region);
    }

    public void updateConfigurationSetTrackingOptions(String configSetName, String customRedirectDomain,
                                                      String region) {
        requireVerifiedRedirectDomain(customRedirectDomain, region);
        ConfigurationSet cs = getConfigurationSet(configSetName, region);
        if (cs.getTrackingOptions() == null || cs.getTrackingOptions().getCustomRedirectDomain() == null) {
            throw new AwsException("TrackingOptionsDoesNotExistException",
                    "There are no tracking options for configuration set <" + configSetName + ">", 400);
        }
        cs.getTrackingOptions().setCustomRedirectDomain(customRedirectDomain);
        configSetStore.put(configSetKey(region, configSetName), cs);
        LOG.infov("Updated TrackingOptions on configuration set {0} in region {1}", configSetName, region);
    }

    public void deleteConfigurationSetTrackingOptions(String configSetName, String region) {
        ConfigurationSet cs = getConfigurationSet(configSetName, region);
        if (cs.getTrackingOptions() == null || cs.getTrackingOptions().getCustomRedirectDomain() == null) {
            throw new AwsException("TrackingOptionsDoesNotExistException",
                    "There are no tracking options for configuration set <" + configSetName + ">", 400);
        }
        cs.setTrackingOptions(null);
        configSetStore.put(configSetKey(region, configSetName), cs);
        LOG.infov("Deleted TrackingOptions on configuration set {0} in region {1}", configSetName, region);
    }

    public void setConfigurationSetArchivingOptions(String configSetName, ArchivingOptions options, String region) {
        ConfigurationSet cs = getConfigurationSet(configSetName, region);
        cs.setArchivingOptions(options);
        configSetStore.put(configSetKey(region, configSetName), cs);
        LOG.infov("Updated ArchivingOptions on configuration set {0} in region {1}", configSetName, region);
    }

    private static final java.util.Set<String> VDM_FEATURE_STATES = java.util.Set.of("ENABLED", "DISABLED");

    public void setConfigurationSetVdmOptions(String configSetName, VdmOptions options, String region) {
        ConfigurationSet cs = getConfigurationSet(configSetName, region);
        validateVdmOptions(options);
        cs.setVdmOptions(options);
        configSetStore.put(configSetKey(region, configSetName), cs);
        LOG.infov("Updated VdmOptions on configuration set {0} in region {1}", configSetName, region);
    }

    private void validateVdmOptions(VdmOptions options) {
        if (options == null) {
            return;
        }
        // Enum values verified against real AWS 2026-06-19; messages use the
        // nested member path and the [ENABLED, DISABLED] value set.
        if (options.getDashboardOptions() != null
                && options.getDashboardOptions().getEngagementMetrics() != null
                && !VDM_FEATURE_STATES.contains(options.getDashboardOptions().getEngagementMetrics())) {
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at 'vdmOptions.dashboardOptions.engagementMetrics' "
                            + "failed to satisfy constraint: Member must satisfy enum value set: [ENABLED, DISABLED]", 400);
        }
        if (options.getGuardianOptions() != null
                && options.getGuardianOptions().getOptimizedSharedDelivery() != null
                && !VDM_FEATURE_STATES.contains(options.getGuardianOptions().getOptimizedSharedDelivery())) {
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at 'vdmOptions.guardianOptions.optimizedSharedDelivery' "
                            + "failed to satisfy constraint: Member must satisfy enum value set: [ENABLED, DISABLED]", 400);
        }
    }

    private static final java.util.Set<String> HTTPS_POLICIES =
            java.util.Set.of("REQUIRE", "REQUIRE_OPEN_ONLY", "OPTIONAL");
    private static final java.util.Set<String> TLS_POLICIES = java.util.Set.of("REQUIRE", "OPTIONAL");

    private void validateTrackingOptions(TrackingOptions options, String region) {
        if (options == null) {
            return;
        }
        String domain = options.getCustomRedirectDomain();
        String httpsPolicy = options.getHttpsPolicy();
        // AWS validation order (verified against real AWS 2026-06-17): a present
        // CustomRedirectDomain must be non-blank, and it is required whenever
        // HttpsPolicy is set; then the domain must be a verified domain identity
        // (checked even without HttpsPolicy); then HttpsPolicy must be a valid enum.
        if ((domain != null && domain.isBlank()) || (httpsPolicy != null && domain == null)) {
            throw new AwsException("BadRequestException",
                    "CustomRedirectDomain must be specified.", 400);
        }
        if (domain != null && !isVerifiedDomainIdentity(domain, region)) {
            throw new AwsException("BadRequestException",
                    "Domain <" + domain + "> is not verified under this account.", 400);
        }
        if (httpsPolicy != null && !HTTPS_POLICIES.contains(httpsPolicy)) {
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at 'httpsPolicy' failed to satisfy constraint: "
                            + "Member must satisfy enum value set: [OPTIONAL, REQUIRE, REQUIRE_OPEN_ONLY]", 400);
        }
    }

    private void validateDeliveryOptions(DeliveryOptions options, String region) {
        if (options == null) {
            return;
        }
        if (options.getTlsPolicy() != null && !TLS_POLICIES.contains(options.getTlsPolicy())) {
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at 'tlsPolicy' failed to satisfy constraint: "
                            + "Member must satisfy enum value set: [OPTIONAL, REQUIRE]", 400);
        }
        // AWS rejects a blank SendingPoolName outright, and a non-existent
        // dedicated IP pool (both verified against real AWS 2026-06-17). The
        // pool must have been created via CreateDedicatedIpPool.
        if (options.getSendingPoolName() != null) {
            if (options.getSendingPoolName().isBlank()) {
                throw new AwsException("BadRequestException",
                        "sendingPoolName can't be blank.", 400);
            }
            if (!dedicatedIpPoolExists(options.getSendingPoolName(), region)) {
                throw new AwsException("BadRequestException",
                        "SendingPool <" + options.getSendingPoolName() + "> doesn't exist", 400);
            }
        }
        // AWS constrains MaxDeliverySeconds to [300, 50400] (max verified against
        // real AWS 2026-06-17; min follows the same smithy range-constraint shape).
        if (options.getMaxDeliverySeconds() != null) {
            long maxDeliverySeconds = options.getMaxDeliverySeconds();
            if (maxDeliverySeconds < 300) {
                throw new AwsException("BadRequestException",
                        "1 validation error detected: Value at 'maxDeliverySeconds' failed to satisfy constraint: "
                                + "Member must have value greater than or equal to 300", 400);
            }
            if (maxDeliverySeconds > 50400) {
                throw new AwsException("BadRequestException",
                        "1 validation error detected: Value at 'maxDeliverySeconds' failed to satisfy constraint: "
                                + "Member must have value less than or equal to 50400", 400);
            }
        }
    }

    public ConfigurationSet getConfigurationSet(String name, String region) {
        return configSetStore.get(configSetKey(region, name))
                .orElseThrow(() -> new AwsException("ConfigurationSetDoesNotExist",
                        "Configuration set <" + name + "> does not exist.", 400));
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
                    "Configuration set <" + name + "> does not exist.", 400);
        }
        configSetStore.delete(key);
        LOG.infov("Deleted SES configuration set: {0} in region {1}", name, region);
    }

    private static final Pattern CONFIG_SET_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private static String configSetKey(String region, String name) {
        validateConfigurationSetName(name);
        return "configSet::" + region + "::" + name;
    }

    // ──────────────────────── Dedicated IP Pools ────────────────────────

    private static final java.util.Set<String> SCALING_MODES = java.util.Set.of("STANDARD", "MANAGED");

    public DedicatedIpPool createDedicatedIpPool(String poolName, String scalingMode, String region) {
        if (poolName == null || poolName.isBlank()) {
            throw new AwsException("BadRequestException", "PoolName is required.", 400);
        }
        String effectiveScaling = (scalingMode == null || scalingMode.isBlank()) ? "STANDARD" : scalingMode;
        if (!SCALING_MODES.contains(effectiveScaling)) {
            throw new AwsException("BadRequestException", "The ScalingMode parameter is invalid.", 400);
        }
        String key = dedicatedIpPoolKey(region, poolName);
        if (dedicatedIpPoolStore.get(key).isPresent()) {
            throw new AwsException("AlreadyExistsException",
                    "The pool <" + poolName + "> already exists.", 400);
        }
        DedicatedIpPool pool = new DedicatedIpPool(poolName, effectiveScaling);
        dedicatedIpPoolStore.put(key, pool);
        LOG.infov("Created SES dedicated IP pool: {0} in region {1}", poolName, region);
        return pool;
    }

    public DedicatedIpPool getDedicatedIpPool(String poolName, String region) {
        return dedicatedIpPoolStore.get(dedicatedIpPoolKey(region, poolName))
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "The requested pool <" + poolName + "> does not exist.", 404));
    }

    public boolean dedicatedIpPoolExists(String poolName, String region) {
        return dedicatedIpPoolStore.get(dedicatedIpPoolKey(region, poolName)).isPresent();
    }

    public List<String> listDedicatedIpPools(String region) {
        String prefix = "dedicatedIpPool::" + region + "::";
        return dedicatedIpPoolStore.scan(k -> k.startsWith(prefix)).stream()
                .map(DedicatedIpPool::getPoolName)
                .sorted()
                .toList();
    }

    public void deleteDedicatedIpPool(String poolName, String region) {
        String key = dedicatedIpPoolKey(region, poolName);
        if (dedicatedIpPoolStore.get(key).isEmpty()) {
            throw new AwsException("NotFoundException",
                    "The requested pool <" + poolName + "> does not exist.", 404);
        }
        dedicatedIpPoolStore.delete(key);
        LOG.infov("Deleted SES dedicated IP pool: {0} in region {1}", poolName, region);
    }

    private static String dedicatedIpPoolKey(String region, String name) {
        return "dedicatedIpPool::" + region + "::" + name;
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

    private static final Pattern EVENT_DESTINATION_NAME_CHARS = Pattern.compile("^[A-Za-z0-9_-]+$");

    private static final int MAX_EVENT_DESTINATION_NAME_LENGTH = 64;

    private static final List<String> VALID_EVENT_TYPES = List.of(
            "SEND", "REJECT", "BOUNCE", "COMPLAINT", "DELIVERY", "OPEN", "CLICK",
            "RENDERING_FAILURE", "DELIVERY_DELAY", "SUBSCRIPTION");

    public void createConfigurationSetEventDestination(String configSetName, String eventDestinationName,
                                                       EventDestination dest, String region) {
        validateEventDestinationName(eventDestinationName);
        validateEventDestination(dest);
        ConfigurationSet cs = getConfigurationSet(configSetName, region);
        if (indexOfEventDestination(cs.getEventDestinations(), eventDestinationName) >= 0) {
            throw new AwsException("AlreadyExists",
                    "An event destination with name <" + eventDestinationName
                            + "> already exists for configuration set <" + configSetName + ">.", 400);
        }
        dest.setName(eventDestinationName);
        cs.getEventDestinations().add(dest);
        configSetStore.put(configSetKey(region, configSetName), cs);
        LOG.infov("Created SES event destination {0} on configuration set {1} in region {2}",
                eventDestinationName, configSetName, region);
    }

    public List<EventDestination> getConfigurationSetEventDestinations(String configSetName, String region) {
        return List.copyOf(getConfigurationSet(configSetName, region).getEventDestinations());
    }

    public void updateConfigurationSetEventDestination(String configSetName, String eventDestinationName,
                                                       EventDestination dest, String region) {
        validateEventDestinationName(eventDestinationName);
        validateEventDestination(dest);
        ConfigurationSet cs = getConfigurationSet(configSetName, region);
        int index = indexOfEventDestination(cs.getEventDestinations(), eventDestinationName);
        if (index < 0) {
            throw new AwsException("NotFoundException",
                    "An event destination with name <" + eventDestinationName
                            + "> does not exist for configuration set <" + configSetName + ">.", 404);
        }
        dest.setName(eventDestinationName);
        cs.getEventDestinations().set(index, dest);
        configSetStore.put(configSetKey(region, configSetName), cs);
        LOG.infov("Updated SES event destination {0} on configuration set {1} in region {2}",
                eventDestinationName, configSetName, region);
    }

    public void deleteConfigurationSetEventDestination(String configSetName, String eventDestinationName,
                                                       String region) {
        validateEventDestinationName(eventDestinationName);
        ConfigurationSet cs = getConfigurationSet(configSetName, region);
        boolean removed = cs.getEventDestinations().removeIf(ed -> eventDestinationName.equals(ed.getName()));
        if (!removed) {
            throw new AwsException("NotFoundException",
                    "An event destination with name <" + eventDestinationName
                            + "> does not exist for configuration set <" + configSetName + ">.", 404);
        }
        configSetStore.put(configSetKey(region, configSetName), cs);
        LOG.infov("Deleted SES event destination {0} on configuration set {1} in region {2}",
                eventDestinationName, configSetName, region);
    }

    /**
     * Stores per-configuration-set suppression overrides. Mirrors the AWS V2
     * {@code PutConfigurationSetSuppressionOptions} contract: {@code reasons} may
     * be {@code null} or empty (explicit "no filtering" for this set) or a subset
     * of {@code [BOUNCE, COMPLAINT]}. Once set, the value is returned through
     * {@link #getConfigurationSet}; downstream callers can resolve the effective
     * reasons for a given send via {@link #getEffectiveSuppressedReasons}.
     */
    public void putConfigurationSetSuppressionOptions(String configSetName,
                                                      List<String> reasons, String region) {
        List<String> sanitized = new ArrayList<>();
        if (reasons != null) {
            for (String r : reasons) {
                validateConfigSetSuppressionReason(r);
                sanitized.add(r);
            }
        }
        ConfigurationSet cs = getConfigurationSet(configSetName, region);
        SuppressionOptions options = new SuppressionOptions();
        options.setSuppressedReasons(sanitized);
        cs.setSuppressionOptions(options);
        configSetStore.put(configSetKey(region, configSetName), cs);
        LOG.infov("Updated SuppressionOptions on configuration set {0} in region {1}: {2}",
                configSetName, region, sanitized);
    }

    /**
     * Returns the effective suppression reasons for a send that is using
     * {@code configurationSetName}. Per the AWS V2 contract, a configuration
     * set's {@code SuppressionOptions} (when present) overrides the
     * account-level reasons — including an empty list, which explicitly
     * disables suppression filtering for that set. Falls back to the
     * account-level reasons when the configuration set has no override, or
     * when {@code configurationSetName} is null/blank (i.e. the caller didn't
     * specify a configuration set).
     */
    public List<String> getEffectiveSuppressedReasons(String configurationSetName, String region) {
        if (configurationSetName != null && !configurationSetName.isBlank()) {
            ConfigurationSet cs = getConfigurationSet(configurationSetName, region);
            SuppressionOptions options = cs.getSuppressionOptions();
            if (options != null) {
                return List.copyOf(options.getSuppressedReasons());
            }
        }
        return List.copyOf(getAccountSuppressionAttributes(region).getSuppressedReasons());
    }

    private static int indexOfEventDestination(List<EventDestination> destinations, String name) {
        for (int i = 0; i < destinations.size(); i++) {
            if (name != null && name.equals(destinations.get(i).getName())) {
                return i;
            }
        }
        return -1;
    }

    static void validateEventDestinationName(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue", "EventDestinationName is required.", 400);
        }
        if (!EVENT_DESTINATION_NAME_CHARS.matcher(name).matches()) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid event destination name <" + name + ">: only alphanumeric ASCII characters, "
                            + "'_', and '-' are allowed.", 400);
        }
        if (name.length() > MAX_EVENT_DESTINATION_NAME_LENGTH) {
            throw new AwsException("InvalidParameterValue",
                    "Event destination name cannot exceed 64 characters.", 400);
        }
    }

    static void validateEventDestination(EventDestination dest) {
        if (dest == null) {
            throw new AwsException("InvalidParameterValue", "EventDestination is required.", 400);
        }
        List<String> types = dest.getMatchingEventTypes();
        if (types == null || types.isEmpty()) {
            throw new AwsException("InvalidParameterValue", "At least one event type must be specified.", 400);
        }
        for (String t : types) {
            if (t == null || !VALID_EVENT_TYPES.contains(t)) {
                throw new AwsException("InvalidParameterValue",
                        "Invalid event type: " + t + ". Valid values are " + VALID_EVENT_TYPES + ".", 400);
            }
        }
        int destinationCount = countDestinations(dest);
        if (destinationCount == 0) {
            throw new AwsException("InvalidParameterValue", "Event destination is not provided.", 400);
        }
        if (destinationCount > 1) {
            throw new AwsException("InvalidParameterValue",
                    "Please provide only one destination with each request. Either a Firehose Destination "
                            + "or a Cloudwatch Destination or an SNS Destination or an EventBridge Destination.", 400);
        }
        if (dest.getSnsDestination() != null
                && (dest.getSnsDestination().getTopicArn() == null
                || dest.getSnsDestination().getTopicArn().isBlank())) {
            throw new AwsException("InvalidParameterValue",
                    "SnsDestination requires a non-blank TopicArn.", 400);
        }
        if (dest.getKinesisFirehoseDestination() != null
                && (dest.getKinesisFirehoseDestination().getIamRoleArn() == null
                || dest.getKinesisFirehoseDestination().getIamRoleArn().isBlank()
                || dest.getKinesisFirehoseDestination().getDeliveryStreamArn() == null
                || dest.getKinesisFirehoseDestination().getDeliveryStreamArn().isBlank())) {
            throw new AwsException("InvalidParameterValue",
                    "KinesisFirehoseDestination requires both IamRoleArn and DeliveryStreamArn.",
                    400);
        }
        if (dest.getCloudWatchDestination() != null) {
            List<CloudWatchDimensionConfiguration> dims =
                    dest.getCloudWatchDestination().getDimensionConfigurations();
            if (dims == null || dims.isEmpty()) {
                throw new AwsException("InvalidParameterValue",
                        "CloudWatch metrics dimension configuration list cannot be empty.", 400);
            }
            for (int i = 0; i < dims.size(); i++) {
                CloudWatchDimensionConfiguration dim = dims.get(i);
                if (dim == null
                        || dim.getDimensionName() == null || dim.getDimensionName().isBlank()
                        || dim.getDimensionValueSource() == null
                        || dim.getDimensionValueSource().isBlank()
                        || dim.getDefaultDimensionValue() == null
                        || dim.getDefaultDimensionValue().isBlank()) {
                    throw new AwsException("InvalidParameterValue",
                            "CloudWatchDestination dimension configurations require "
                                    + "DimensionName, DimensionValueSource, and DefaultDimensionValue "
                                    + "(missing on member " + (i + 1) + ").", 400);
                }
            }
        }
        if (dest.getPinpointDestination() != null
                && (dest.getPinpointDestination().getApplicationArn() == null
                || dest.getPinpointDestination().getApplicationArn().isBlank())) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid Pinpoint application ARN provided: "
                            + dest.getPinpointDestination().getApplicationArn() + ".", 400);
        }
    }

    private static int countDestinations(EventDestination dest) {
        int count = 0;
        if (dest.getSnsDestination() != null) {
            count++;
        }
        if (dest.getCloudWatchDestination() != null) {
            count++;
        }
        if (dest.getKinesisFirehoseDestination() != null) {
            count++;
        }
        if (dest.getEventBridgeDestination() != null) {
            count++;
        }
        if (dest.getPinpointDestination() != null) {
            count++;
        }
        return count;
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

    // ──────────────────── Account-level suppression attributes ────────────────────

    public AccountSuppressionAttributes getAccountSuppressionAttributes(String region) {
        return accountSuppressionStore.get(accountSuppressionKey(region))
                .orElseGet(SesService::defaultAccountSuppressionAttributes);
    }

    private static AccountSuppressionAttributes defaultAccountSuppressionAttributes() {
        // Fresh SES accounts default to auto-suppression on both BOUNCE and COMPLAINT;
        // an explicit PUT (including an empty list) overrides this.
        AccountSuppressionAttributes attrs = new AccountSuppressionAttributes();
        attrs.setSuppressedReasons(new ArrayList<>(List.of("BOUNCE", "COMPLAINT")));
        return attrs;
    }

    public void putAccountSuppressionAttributes(String region, List<String> suppressedReasons) {
        List<String> sanitized = new ArrayList<>();
        if (suppressedReasons != null) {
            for (String r : suppressedReasons) {
                validateSuppressionReason(r, "suppressedReasons", true);
                sanitized.add(r);
            }
        }
        AccountSuppressionAttributes attrs = new AccountSuppressionAttributes();
        attrs.setSuppressedReasons(sanitized);
        accountSuppressionStore.put(accountSuppressionKey(region), attrs);
        LOG.infov("Updated account suppression attributes for region {0}: {1}", region, sanitized);
    }

    private static String accountSuppressionKey(String region) {
        return "account-suppression::" + region;
    }

    // ──────────────────────────── Suppression list ────────────────────────────

    public void putSuppressedDestination(String region, String emailAddress, String reason) {
        String normalized = normalizeSuppressionEmail(emailAddress);
        validateSuppressionReason(reason, "reason", false);
        String key = suppressionKey(region, normalized);
        SuppressionMatch match = existingSuppressionMatch(region, emailAddress, normalized).orElse(null);
        SuppressedDestination entry = match != null ? match.entry() : new SuppressedDestination(normalized, reason);
        entry.setEmailAddress(normalized);
        entry.setReason(reason);
        entry.setLastUpdateTime(Instant.now());
        // Write the canonical key first, then drop a legacy key it migrated from,
        // so a failed write can't lose the entry. The legacy form was persisted by
        // a pre-canonicalization Floci (trim-only key); migrating it avoids leaving
        // a stuck duplicate after a re-PUT.
        suppressionStore.put(key, entry);
        if (match != null && !match.key().equals(key)) {
            suppressionStore.delete(match.key());
        }
        LOG.infov("Suppressed destination {0} in region {1} (reason={2})", normalized, region, reason);
    }

    public SuppressedDestination getSuppressedDestination(String region, String emailAddress) {
        String normalized = normalizeSuppressionEmail(emailAddress);
        return existingSuppressionMatch(region, emailAddress, normalized)
                .map(SuppressionMatch::entry)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Email address " + normalized + " does not exist on your suppression list.",
                        404));
    }

    public void deleteSuppressedDestination(String region, String emailAddress) {
        String normalized = normalizeSuppressionEmail(emailAddress);
        SuppressionMatch match = existingSuppressionMatch(region, emailAddress, normalized)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Email address " + normalized + " does not exist on your suppression list.",
                        404));
        suppressionStore.delete(match.key());
        LOG.infov("Removed suppression entry for {0} in region {1}", normalized, region);
    }

    /** A suppression entry together with the storage key it currently lives under. */
    private record SuppressionMatch(String key, SuppressedDestination entry) {
    }

    /**
     * Resolve a suppression entry by its canonical (domain-lower-cased) key, falling
     * back to the legacy raw-trimmed key used by a pre-canonicalization Floci. Returns
     * the entry and the key it was found under in a single read per candidate, so
     * callers don't re-fetch the store.
     */
    private Optional<SuppressionMatch> existingSuppressionMatch(String region, String rawEmail, String normalized) {
        String canonical = suppressionKey(region, normalized);
        Optional<SuppressedDestination> hit = suppressionStore.get(canonical);
        if (hit.isPresent()) {
            return Optional.of(new SuppressionMatch(canonical, hit.get()));
        }
        String legacy = suppressionKey(region, rawEmail.trim());
        if (!legacy.equals(canonical)) {
            Optional<SuppressedDestination> legacyHit = suppressionStore.get(legacy);
            if (legacyHit.isPresent()) {
                return Optional.of(new SuppressionMatch(legacy, legacyHit.get()));
            }
        }
        return Optional.empty();
    }

    public List<SuppressedDestination> listSuppressedDestinations(String region, List<String> reasonFilters) {
        Set<String> filters = new HashSet<>();
        if (reasonFilters != null) {
            for (String r : reasonFilters) {
                if (r != null && !r.isBlank()) {
                    validateSuppressionReason(r, "reasons", true);
                    filters.add(r);
                }
            }
        }
        String prefix = "suppression::" + region + "::";
        List<SuppressedDestination> all = new ArrayList<>(suppressionStore.scan(k -> k.startsWith(prefix)));
        all.sort(Comparator.comparing(SuppressedDestination::getLastUpdateTime,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SuppressedDestination::getEmailAddress,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        if (filters.isEmpty()) {
            return all;
        }
        return all.stream()
                .filter(s -> filters.contains(s.getReason()))
                .toList();
    }

    private static String suppressionKey(String region, String emailAddress) {
        return "suppression::" + region + "::" + emailAddress;
    }

    /**
     * Resolve the effective suppression reason for each address in a single pass over the
     * store and the effective settings. The returned map only contains entries for
     * addresses that ARE suppressed (i.e., on the list AND whose reason matches the
     * effective {@code suppressedReasons} — the configuration set's
     * {@code SuppressionOptions} override if present, else the account-level reasons).
     * Callers reuse this map for both the SMTP relay filter and the event-publishing
     * partitioning so the store is read once per send regardless of the number of
     * consumers.
     */
    Map<String, String> collectSuppressedReasons(Collection<String> addresses,
                                                  String configurationSetName, String region) {
        if (addresses == null || addresses.isEmpty()) {
            return Map.of();
        }
        List<String> effectiveReasons = getEffectiveSuppressedReasons(configurationSetName, region);
        if (effectiveReasons == null || effectiveReasons.isEmpty()) {
            return Map.of();
        }
        Set<String> reasonFilter = Set.copyOf(effectiveReasons);
        Map<String, String> result = new LinkedHashMap<>();
        for (String address : addresses) {
            if (address == null || address.isBlank() || result.containsKey(address)) {
                continue;
            }
            String normalized = normalizeSuppressionEmail(address);
            SuppressedDestination entry = existingSuppressionMatch(region, address, normalized)
                    .map(SuppressionMatch::entry)
                    .orElse(null);
            if (entry != null && entry.getReason() != null
                    && reasonFilter.contains(entry.getReason())) {
                result.put(address, entry.getReason());
            }
        }
        return result;
    }

    /**
     * Filter out recipients whose effective suppression reason is non-null. Returns a new
     * list containing only the addresses that should reach the SMTP relay, mirroring AWS
     * SES's "accept the message, but doesn't send it" behaviour for suppressed addresses.
     * Returns the original reference when {@code addresses} is {@code null} or empty.
     */
    static List<String> filterUnsuppressed(List<String> addresses, Map<String, String> suppressedReasons) {
        if (addresses == null || addresses.isEmpty()) {
            return addresses;
        }
        if (suppressedReasons.isEmpty()) {
            return addresses;
        }
        List<String> kept = new ArrayList<>(addresses.size());
        for (String a : addresses) {
            if (!suppressedReasons.containsKey(a)) {
                kept.add(a);
            }
        }
        return kept;
    }

    /**
     * Resolve the suppression reason that applies to a given recipient in the given region
     * for sends using {@code configurationSetName}, or {@code null} if the recipient is not
     * suppressed. The recipient is suppressed only when it appears in the address-level
     * suppression list AND its stored reason intersects the effective {@code suppressedReasons}
     * — the configuration set's {@code SuppressionOptions} override if present, else the
     * account-level reasons. {@code configurationSetName} may be {@code null} or blank to
     * scope the check to account-level reasons only.
     *
     * <p>The returned value is one of {@code "BOUNCE"} / {@code "COMPLAINT"}, allowing
     * callers (publishSendEvents) to map the recipient to a synthetic Bounce / Complaint
     * event without consulting the store again. Both the per-address suppression entries
     * and the account-level / per-CS {@code suppressedReasons} go through
     * {@link #validateSuppressionReason} / {@link #validateConfigSetSuppressionReason},
     * which enforce exact case-sensitive equality with the two canonical values, so
     * {@code entry.getReason()} is guaranteed to be canonical and downstream
     * {@code .equals("BOUNCE")} / {@code .equals("COMPLAINT")} checks are safe.
     */
    String resolveSuppressionReason(String emailAddress, String configurationSetName, String region) {
        if (emailAddress == null || emailAddress.isBlank()) {
            return null;
        }
        // Share the same normalization used when entries are stored
        // (`normalizeSuppressionEmail`) so lookups can't drift apart from inserts,
        // with the same legacy-key fallback as GET/DELETE.
        String normalized = normalizeSuppressionEmail(emailAddress);
        SuppressedDestination entry = existingSuppressionMatch(region, emailAddress, normalized)
                .map(SuppressionMatch::entry)
                .orElse(null);
        if (entry == null || entry.getReason() == null) {
            return null;
        }
        List<String> effective = getEffectiveSuppressedReasons(configurationSetName, region);
        if (effective == null || effective.isEmpty()) {
            return null;
        }
        return effective.contains(entry.getReason()) ? entry.getReason() : null;
    }

    private static String normalizeSuppressionEmail(String emailAddress) {
        if (emailAddress == null || emailAddress.isBlank()) {
            throw new AwsException("BadRequestException", "EmailAddress is required.", 400);
        }
        // AWS trims the EmailAddress and canonicalizes only the domain to lower
        // case; the local-part keeps its case. Verified against real AWS SES V2
        // (2026-06-15): `Foo@Example.COM` and `Foo@example.com` collapse to one
        // suppression entry (`Foo@example.com`), but `Foo@x` and `foo@x` are two
        // distinct entries. Lower-casing the whole address would wrongly merge
        // local-part variants and alter the stored value on read-back.
        // Locale.ROOT avoids the JVM-locale Turkish-i pitfall.
        String trimmed = emailAddress.trim();
        int at = trimmed.lastIndexOf('@');
        if (at < 0) {
            return trimmed;
        }
        return trimmed.substring(0, at) + "@" + trimmed.substring(at + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Validation message used by PutAccountSuppressionAttributes,
     * PutSuppressedDestination, and ListSuppressedDestinations — all three
     * return the AWS "1 validation error detected: Value at '<fieldName>'
     * failed to satisfy constraint: ..." V1-style nested message verbatim.
     * (Verified against real AWS V2 SES on 2026-06-03.) The {@code nested}
     * flag controls whether the inner enum constraint is wrapped in
     * {@code Member must satisfy constraint: [...]} — PutSuppressedDestination
     * (single Reason field) returns the unwrapped form; the two list-bearing
     * APIs return the wrapped form.
     */
    private static void validateSuppressionReason(String reason, String fieldName, boolean nested) {
        if (reason == null || (!"BOUNCE".equals(reason) && !"COMPLAINT".equals(reason))) {
            String constraint = nested
                    ? "Member must satisfy constraint: [Member must satisfy enum value set: [BOUNCE, COMPLAINT]]"
                    : "Member must satisfy enum value set: [BOUNCE, COMPLAINT]";
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at '" + fieldName + "' failed to satisfy constraint: "
                            + constraint, 400);
        }
    }

    /**
     * Validation message used by PutConfigurationSetSuppressionOptions. AWS
     * V2 SES uses a different, simpler natural-language sentence on this
     * endpoint than on the three older suppression APIs above:
     *   "Reason <X> is invalid, must be one of [BOUNCE, COMPLAINT]."
     * (Verified against real AWS V2 SES on 2026-06-03.) CreateConfigurationSet
     * reports the constraint-style validation message for invalid non-null
     * values but falls back to this sentence for null elements, matching AWS
     * (verified 2026-06-13); see {@link #createConfigurationSet}.
     */
    private static void validateConfigSetSuppressionReason(String reason) {
        if (!isValidConfigSetSuppressionReason(reason)) {
            throw new AwsException("BadRequestException",
                    invalidSuppressionReasonMessage(reason), 400);
        }
    }

    private static boolean isValidConfigSetSuppressionReason(String reason) {
        return "BOUNCE".equals(reason) || "COMPLAINT".equals(reason);
    }

    private static String invalidSuppressionReasonMessage(String reason) {
        return "Reason " + reason + " is invalid, must be one of [BOUNCE, COMPLAINT].";
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
                                     String templateName, JsonNode templateData,
                                     String configurationSetName, List<MessageTag> emailTags,
                                     List<MessageHeader> additionalHeaders, String region) {
        EmailTemplate template = getTemplate(templateName, region);
        return sendInlineTemplatedEmail(source, toAddresses, ccAddresses, bccAddresses,
                replyToAddresses, template.getSubject(), template.getTextPart(),
                template.getHtmlPart(), templateData,
                configurationSetName, emailTags, additionalHeaders, region);
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
                                            JsonNode templateData,
                                            String configurationSetName, List<MessageTag> emailTags,
                                            List<MessageHeader> additionalHeaders,
                                            String region) {
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
                configurationSetName, emailTags, additionalHeaders, region);
    }

    public List<BulkEmailEntryResult> sendBulkTemplatedEmail(String source,
                                                              List<String> replyToAddresses,
                                                              String subject, String textPart, String htmlPart,
                                                              JsonNode defaultTemplateData,
                                                              List<BulkEmailEntry> entries,
                                                              String configurationSetName,
                                                              List<MessageTag> defaultEmailTags,
                                                              List<MessageHeader> defaultHeaders,
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
        validateConfigurationSet(configurationSetName, region);
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
                List<MessageTag> mergedTags = mergeEmailTags(defaultEmailTags, entry.replacementEmailTags());
                List<MessageHeader> mergedHeaders = mergeHeaders(defaultHeaders, entry.replacementHeaders());
                String messageId = sendEmail(source,
                        entry.toAddresses(), entry.ccAddresses(), entry.bccAddresses(),
                        replyToAddresses,
                        applyTemplateData(subject, merged),
                        applyTemplateData(textPart, merged),
                        applyTemplateData(htmlPart, merged),
                        configurationSetName, mergedTags, mergedHeaders, region);
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

    static List<MessageHeader> mergeHeaders(List<MessageHeader> defaults, List<MessageHeader> replacement) {
        boolean hasDefault = defaults != null && !defaults.isEmpty();
        boolean hasReplacement = replacement != null && !replacement.isEmpty();
        if (!hasDefault && !hasReplacement) {
            return List.of();
        }
        // RFC 5322 header field names are case-insensitive, so the merge key is the
        // lowercased name. The header itself is stored verbatim, so the replacement's
        // original casing wins when it overrides a default.
        LinkedHashMap<String, MessageHeader> byLowerName = new LinkedHashMap<>();
        if (hasDefault) {
            for (MessageHeader h : defaults) {
                if (h != null && h.name() != null && !h.name().isBlank()) {
                    byLowerName.put(h.name().toLowerCase(Locale.ROOT), h);
                }
            }
        }
        if (hasReplacement) {
            for (MessageHeader h : replacement) {
                if (h != null && h.name() != null && !h.name().isBlank()) {
                    byLowerName.put(h.name().toLowerCase(Locale.ROOT), h);
                }
            }
        }
        return new ArrayList<>(byLowerName.values());
    }

    static List<MessageTag> mergeEmailTags(List<MessageTag> defaults, List<MessageTag> replacement) {
        boolean hasDefault = defaults != null && !defaults.isEmpty();
        boolean hasReplacement = replacement != null && !replacement.isEmpty();
        if (!hasDefault && !hasReplacement) {
            return List.of();
        }
        LinkedHashMap<String, MessageTag> byName = new LinkedHashMap<>();
        if (hasDefault) {
            for (MessageTag t : defaults) {
                if (t != null && t.name() != null && !t.name().isBlank()) {
                    byName.put(t.name(), t);
                }
            }
        }
        if (hasReplacement) {
            for (MessageTag t : replacement) {
                if (t != null && t.name() != null && !t.name().isBlank()) {
                    byName.put(t.name(), t);
                }
            }
        }
        return new ArrayList<>(byName.values());
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
