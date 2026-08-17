package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.route53.Route53Service;
import io.github.hectorvent.floci.services.route53.model.HostedZone;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import io.github.hectorvent.floci.services.ses.model.AccountSuppressionAttributes;
import io.github.hectorvent.floci.services.ses.model.AccountVdmAttributes;
import io.github.hectorvent.floci.services.ses.model.ArchivingOptions;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntry;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntryResult;
import io.github.hectorvent.floci.services.ses.model.CloudWatchDimensionConfiguration;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.Contact;
import io.github.hectorvent.floci.services.ses.model.ContactList;
import io.github.hectorvent.floci.services.ses.model.CustomVerificationEmailTemplate;
import io.github.hectorvent.floci.services.ses.model.DedicatedIpPool;
import io.github.hectorvent.floci.services.ses.model.DeliveryOptions;
import io.github.hectorvent.floci.services.ses.model.EmailTemplate;
import io.github.hectorvent.floci.services.ses.model.EventDestination;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.ListManagementOptions;
import io.github.hectorvent.floci.services.ses.model.MessageHeader;
import io.github.hectorvent.floci.services.ses.model.MessageTag;
import io.github.hectorvent.floci.services.ses.model.ReceiptRuleSet;
import io.github.hectorvent.floci.services.ses.model.Topic;
import io.github.hectorvent.floci.services.ses.model.TopicPreference;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class SesService {

    private static final Logger LOG = Logger.getLogger(SesService.class);

    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{\\{\\s*([\\w-]+)\\s*\\}\\}");

    private static final int MAX_BULK_DESTINATIONS = 50;
    private static final int MAX_RECIPIENTS_PER_DESTINATION = 50;
    private static final Duration DKIM_LOOKUP_CACHE_TTL = Duration.ofSeconds(5);

    private static final SecureRandom BOUNDARY_RANDOM = new SecureRandom();

    private final StorageBackend<String, Identity> identityStore;
    // Sent-email records extracted to SesSentEmailService. The send path records finished emails via
    // it; send-statistics and inspection read back through it.
    private final SesSentEmailService sentEmailService;
    // Account-level settings, extracted to its own service. The facade delegates.
    private final SesAccountService accountService;
    // Email templates extracted to SesTemplateService. The facade delegates; the templated-send path
    // reads via it, and ARN-dispatched tagging reads/writes via its find/save.
    private final SesTemplateService templateService;
    private final StorageBackend<String, ConfigurationSet> configSetStore;
    // Account suppression attributes + the per-address suppression list (two stores) extracted to
    // SesSuppressionService. The facade delegates; its send filters read via it.
    private final SesSuppressionService suppressionService;
    // Dedicated IP pools extracted to SesDedicatedIpService. The facade delegates.
    private final SesDedicatedIpService dedicatedIpService;
    // Contact lists and contacts (two stores) extracted to SesContactService. The
    // facade delegates, and its send-path list-management orchestration calls into the service.
    private final SesContactService contactService;
    // Identity (sending authorization) policy storage, extracted to SesPolicyService.
    // The facade keeps the identity-existence check and delegates the rest.
    private final SesPolicyService policyService;
    // Receipt-rule-set domain, extracted to its own service. The facade delegates.
    private final SesReceiptRuleService receiptRuleService;
    // Custom verification email templates: storage extracted to SesCvetService. The
    // facade keeps the identity-dependent validation and the send path; the service owns the store.
    private final SesCvetService cvetService;
    private final SmtpRelay smtpRelay;
    private final ObjectMapper objectMapper;
    private final SesEventPublisher eventPublisher;
    private final String defaultAccountId;
    // Base URL used to build functional list-management unsubscribe links (the {{amazonSESUnsubscribeUrl}}
    // placeholder and the List-Unsubscribe header) that resolve to Floci's own unsubscribe endpoint.
    private final String baseUrl;
    private final Route53Service route53Service;
    private final Clock clock;
    private final ConcurrentHashMap<String, DkimLookupCacheEntry> dkimLookupCache = new ConcurrentHashMap<>();

    @Inject
    public SesService(StorageFactory storageFactory, SesReceiptRuleService receiptRuleService,
                       SesAccountService accountService, SesCvetService cvetService,
                       SesPolicyService policyService, SesContactService contactService,
                       SesSuppressionService suppressionService, SesDedicatedIpService dedicatedIpService,
                       SesTemplateService templateService, SesSentEmailService sentEmailService,
                       SmtpRelay smtpRelay, ObjectMapper objectMapper,
                       SesEventPublisher eventPublisher, EmulatorConfig config, Route53Service route53Service,
                       Clock clock) {
        this.identityStore = storageFactory.create("ses", "ses-identities.json",
                new TypeReference<Map<String, Identity>>() {});
        this.sentEmailService = sentEmailService;
        this.accountService = accountService;
        this.templateService = templateService;
        this.configSetStore = storageFactory.create("ses", "ses-config-sets.json",
                new TypeReference<Map<String, ConfigurationSet>>() {});
        this.suppressionService = suppressionService;
        this.dedicatedIpService = dedicatedIpService;
        this.contactService = contactService;
        this.policyService = policyService;
        this.receiptRuleService = receiptRuleService;
        this.cvetService = cvetService;
        this.smtpRelay = smtpRelay;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.defaultAccountId = config.defaultAccountId();
        this.baseUrl = config.effectiveBaseUrl();
        this.route53Service = route53Service;
        this.clock = clock;
    }

    SesService(StorageBackend<String, Identity> identityStore,
               SesSentEmailService sentEmailService,
               SesAccountService accountService,
               SesTemplateService templateService,
               StorageBackend<String, ConfigurationSet> configSetStore,
               SesSuppressionService suppressionService,
               SesDedicatedIpService dedicatedIpService,
               SesContactService contactService,
               SesPolicyService policyService,
               SesReceiptRuleService receiptRuleService,
               SesCvetService cvetService,
               SmtpRelay smtpRelay,
               ObjectMapper objectMapper,
               Clock clock) {
        this(identityStore, sentEmailService, accountService, templateService, configSetStore, suppressionService,
                dedicatedIpService, contactService, policyService,
                receiptRuleService, cvetService, smtpRelay, objectMapper, null, clock);
    }

    SesService(StorageBackend<String, Identity> identityStore,
               SesSentEmailService sentEmailService,
               SesAccountService accountService,
               SesTemplateService templateService,
               StorageBackend<String, ConfigurationSet> configSetStore,
               SesSuppressionService suppressionService,
               SesDedicatedIpService dedicatedIpService,
               SesContactService contactService,
               SesPolicyService policyService,
               SesReceiptRuleService receiptRuleService,
               SesCvetService cvetService,
               SmtpRelay smtpRelay,
               ObjectMapper objectMapper,
               Route53Service route53Service,
               Clock clock) {
        this.identityStore = identityStore;
        this.sentEmailService = sentEmailService;
        this.accountService = accountService;
        this.templateService = templateService;
        this.configSetStore = configSetStore;
        this.suppressionService = suppressionService;
        this.dedicatedIpService = dedicatedIpService;
        this.contactService = contactService;
        this.policyService = policyService;
        this.receiptRuleService = receiptRuleService;
        this.cvetService = cvetService;
        this.smtpRelay = smtpRelay;
        this.objectMapper = objectMapper;
        this.eventPublisher = null;
        this.defaultAccountId = "000000000000";
        this.baseUrl = "http://localhost:4566";
        this.route53Service = route53Service;
        this.clock = clock;
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
        regenerateDkimTokens(identity);
        identity.setVerificationStatus("Pending");
        identity.setDkimEnabled(true);
        // The create response reports DKIM verification as NotStarted (SES hasn't begun tracking the
        // CNAMEs yet); the first Get/List refresh transitions it to Pending. Matches AWS, where
        // CreateEmailIdentity returns NOT_STARTED but a subsequent GetEmailIdentity returns PENDING.
        identity.setDkimVerificationStatus("NotStarted");
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
        invalidateDkimLookupCache(region, identityValue);

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

        // Policies are sub-resources of the identity; drop them too so they can't resurrect into a
        // same-named identity recreated later (and so the per-identity count stays correct).
        policyService.deletePoliciesForIdentity(identityValue, region);

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
        Identity identity = identityStore.get(key).orElse(null);
        return refreshIdentityState(identity, region);
    }

    public String sendEmail(String source, List<String> toAddresses, List<String> ccAddresses,
                            List<String> bccAddresses, List<String> replyToAddresses,
                            String subject, String bodyText, String bodyHtml,
                            String configurationSetName, List<MessageTag> emailTags,
                            List<MessageHeader> additionalHeaders, ListManagementOptions listManagement,
                            String region) {
        if (source == null || source.isBlank()) {
            throw new AwsException("InvalidParameterValue", "Source email is required.", 400);
        }
        boolean hasRecipient = (toAddresses != null && !toAddresses.isEmpty())
                || (ccAddresses != null && !ccAddresses.isEmpty())
                || (bccAddresses != null && !bccAddresses.isEmpty());
        if (!hasRecipient) {
            throw new AwsException("InvalidParameterValue", "At least one destination address is required.", 400);
        }
        String effectiveConfigSet = resolveDefaultConfigurationSet(configurationSetName, source, region);
        validateConfigurationSet(effectiveConfigSet, region);

        // Resolve suppression before recording the message so a bad ListManagementOptions (e.g. an
        // unknown contact list) fails the whole send without leaving an orphaned SentEmail record.
        List<String> envelope = allRecipients(toAddresses, ccAddresses, bccAddresses);
        Map<String, String> suppressedReasons = new LinkedHashMap<>(
                collectSuppressedReasons(envelope, effectiveConfigSet, region));
        // putIfAbsent so a suppression-list reason already set for an address (e.g. COMPLAINT) wins
        // over the list-management BOUNCE and keeps its synthetic event type.
        collectListManagementOptOuts(envelope, listManagement, region).forEach(suppressedReasons::putIfAbsent);

        // A single-recipient list-managed send gets a functional unsubscribe link: the
        // {{amazonSESUnsubscribeUrl}} body placeholder is replaced and the List-Unsubscribe headers
        // are added, matching AWS (which only injects these for a single recipient). The link
        // resolves to Floci's own /_aws/ses/unsubscribe endpoint.
        if (hasListManagement(listManagement) && envelope.size() == 1) {
            String url = buildUnsubscribeUrl(region, listManagement, extractEmailAddress(envelope.get(0)));
            bodyText = replaceUnsubscribePlaceholder(bodyText, url);
            bodyHtml = replaceUnsubscribePlaceholder(bodyHtml, url);
            additionalHeaders = withUnsubscribeHeaders(additionalHeaders, url);
        }

        // Drop unsafe user-supplied headers (blank name or CR/LF in name/value) once here, so the
        // stored SentEmail, the SMTP relay, and the published events all reflect the same sanitized
        // set and no injection payload is retained on any surface.
        if (additionalHeaders != null) {
            additionalHeaders = additionalHeaders.stream().filter(MessageHeader::isSafe).toList();
        }

        String messageId = UUID.randomUUID().toString();
        SentEmail email = new SentEmail(messageId, region, source, toAddresses, ccAddresses,
                bccAddresses, replyToAddresses, subject, bodyText, bodyHtml);
        if (additionalHeaders != null && !additionalHeaders.isEmpty()) {
            email.setHeaders(additionalHeaders);
        }
        sentEmailService.record(region, messageId, email);

        List<String> relayedTo = filterUnsuppressed(toAddresses, suppressedReasons);
        List<String> relayedCc = filterUnsuppressed(ccAddresses, suppressedReasons);
        List<String> relayedBcc = filterUnsuppressed(bccAddresses, suppressedReasons);
        if (sizeOf(relayedTo) + sizeOf(relayedCc) + sizeOf(relayedBcc) > 0) {
            smtpRelay.relay(source, relayedTo, relayedCc, relayedBcc,
                    replyToAddresses, subject, bodyText, bodyHtml, additionalHeaders);
        } else {
            LOG.infov("SES email accepted but not relayed (all recipients suppressed): messageId={0}",
                    messageId);
        }

        LOG.infov("SES email sent: from={0}, to={1}, subject={2}, messageId={3}",
                source, toAddresses, subject, messageId);
        publishSendEvents(effectiveConfigSet, messageId, source, subject,
                toAddresses, ccAddresses, bccAddresses, envelope,
                suppressedReasons, emailTags, additionalHeaders, region);
        return messageId;
    }

    public String sendRawEmail(String source, List<String> destinations, String rawMessage,
                               String configurationSetName, List<MessageTag> emailTags,
                               ListManagementOptions listManagement, String region) {
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new AwsException("InvalidParameterValue", "RawMessage.Data is required.", 400);
        }
        String effectiveConfigSet = resolveDefaultConfigurationSet(configurationSetName, source, region);
        validateConfigurationSet(effectiveConfigSet, region);
        boolean hasExplicitDestinations = destinations != null && !destinations.isEmpty();
        boolean sourceOmitted = source == null || source.isBlank();
        boolean willPublishEvents = (effectiveConfigSet != null && !effectiveConfigSet.isBlank())
                || !resolveIdentityNotificationTargets(source, region).isEmpty();
        SmtpRelay.RawMessageHeaders headers = (hasExplicitDestinations && !willPublishEvents && !sourceOmitted)
                ? null
                : SmtpRelay.parseRawHeaders(rawMessage);
        String effectiveSource = sourceOmitted && headers != null && !headers.from().isBlank()
                ? headers.from()
                : source;
        if (effectiveSource == null || effectiveSource.isBlank()) {
            // Shared by the v1 Query and v2 REST surfaces. Throw the v1-native code; the v2
            // controller's remapV1Exception translates InvalidParameterValue -> BadRequestException.
            // Verified against real AWS: v1 returns InvalidParameterValue and v2 BadRequestException,
            // both with this message.
            throw new AwsException("InvalidParameterValue", "Missing required header 'From'.", 400);
        }
        // FromEmailAddress was omitted, so the configuration set couldn't be resolved from the
        // sender until the MIME "From" was parsed. Re-resolve from the effective sender now so an
        // email identity's default configuration set still applies to a Raw send without an
        // explicit FromEmailAddress.
        if (sourceOmitted && (configurationSetName == null || configurationSetName.isBlank())) {
            effectiveConfigSet = resolveDefaultConfigurationSet(configurationSetName, effectiveSource, region);
            validateConfigurationSet(effectiveConfigSet, region);
        }
        List<String> effectiveDestinations = hasExplicitDestinations
                ? destinations
                : allRecipients(headers.to(), headers.cc(), headers.bcc());
        if (effectiveDestinations.isEmpty()) {
            throw new AwsException("InvalidParameterValue",
                    "At least one destination address is required.", 400);
        }
        // Resolve suppression before recording the message so a bad ListManagementOptions (e.g. an
        // unknown contact list) fails the whole send without leaving an orphaned SentEmail record.
        Map<String, String> suppressedReasons = new LinkedHashMap<>(
                collectSuppressedReasons(effectiveDestinations, effectiveConfigSet, region));
        // putIfAbsent so a suppression-list reason already set for an address (e.g. COMPLAINT) wins
        // over the list-management BOUNCE and keeps its synthetic event type.
        collectListManagementOptOuts(effectiveDestinations, listManagement, region)
                .forEach(suppressedReasons::putIfAbsent);

        String messageId = UUID.randomUUID().toString();
        SentEmail email = new SentEmail(messageId, region, effectiveSource, effectiveDestinations, rawMessage);
        sentEmailService.record(region, messageId, email);

        List<String> relayedDestinations = filterUnsuppressed(effectiveDestinations, suppressedReasons);
        if (!relayedDestinations.isEmpty()) {
            smtpRelay.relayRaw(effectiveSource, relayedDestinations, rawMessage);
        } else {
            LOG.infov("SES raw email accepted but not relayed (all recipients suppressed): messageId={0}",
                    messageId);
        }

        LOG.infov("SES raw email sent: from={0}, messageId={1}", effectiveSource, messageId);
        publishSendEvents(effectiveConfigSet, messageId, effectiveSource,
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
        return sentEmailService.countInRegion(region);
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
        if (identityValue == null || identityValue.isBlank()) {
            throw new AwsException("InvalidParameterValue", "Identity is required.", 400);
        }
        // DKIM is a domain concept and an email reports its parent domain's DKIM (via effectiveDkimSource),
        // so toggling DKIM on an email whose parent domain is a registered identity is a no-op that leaves
        // the domain untouched — matching real AWS, regardless of whether the email identity itself exists.
        if (identityValue.contains("@")) {
            String domain = identityValue.substring(identityValue.indexOf('@') + 1);
            if (identityStore.get(identityKey(region, domain)).isPresent()) {
                return;
            }
        }
        String key = identityKey(region, identityValue);
        Identity identity = identityStore.get(key).orElse(null);
        if (identity == null) {
            String domain = identityValue.contains("@")
                    ? identityValue.substring(identityValue.indexOf('@') + 1)
                    : identityValue;
            // v1-native code; the v2 controller remaps InvalidParameterValue -> BadRequestException.
            throw new AwsException("InvalidParameterValue",
                    "Domain " + domain + " is not verified for DKIM signing.", 400);
        }

        // Only toggle the signing flag. DkimVerificationStatus tracks DNS record detection (via the
        // Route53 lookup in refreshIdentityState), not the enabled flag — matching real AWS, where
        // SetIdentityDkimEnabled / PutEmailIdentityDkimAttributes leave the verification status alone.
        identity.setDkimEnabled(signingEnabled);
        identityStore.put(key, identity);
        LOG.infov("Updated DKIM attributes for {0}: signingEnabled={1}", identityValue, signingEnabled);
    }

    private List<String> generateDkimTokens() {
        List<String> tokens = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            tokens.add(UUID.randomUUID().toString().replace("-", ""));
        }
        return tokens;
    }

    /**
     * Generates a fresh Easy DKIM token set and records the key length / generation timestamp. New
     * tokens mean the previously published CNAMEs no longer match, so the verification status resets
     * to Pending (re-detected via the Route53 lookup) and the origin returns to AWS_SES. Only meaningful
     * for domain identities; refreshIdentityState re-upgrades to Success once the new records exist.
     */
    private void regenerateDkimTokens(Identity identity) {
        identity.setDkimTokens(generateDkimTokens());
        identity.setDkimCurrentSigningKeyLength(identity.getDkimNextSigningKeyLength());
        identity.setDkimLastKeyGenerationTimestamp(Instant.now(clock));
        identity.setDkimSigningAttributesOrigin("AWS_SES");
        // The new tokens' CNAMEs aren't detected yet, so DKIM verification resets to Pending. The
        // identity's own verification is NOT revoked by a key rotation (matching AWS) — keep Success
        // when already verified; a not-yet-verified identity stays Pending.
        identity.setDkimVerificationStatus("Pending");
        if (!"Success".equals(identity.getVerificationStatus())) {
            identity.setVerificationStatus("Pending");
        }
    }

    /**
     * v1 VerifyDomainDkim: returns the domain identity's DKIM tokens (3), generating them if needed.
     * Tokens are stable across calls (AWS does not regenerate them). The domain is registered as a
     * pending identity if it does not exist yet, matching AWS's lenient behavior (VerifyDomainDkim
     * starts DKIM setup for any domain).
     */
    public List<String> verifyDomainDkim(String domain, String region) {
        validateIdentityWhitespace(domain, "Domain");
        if (domain == null || domain.isBlank()) {
            throw new AwsException("InvalidParameterValue", "Domain is required.", 400);
        }
        if (domain.contains("@")) {
            // Domain-only action: an email-shaped value must not create an email-valued "Domain".
            throw new AwsException("InvalidParameterValue", "Domain " + domain + " is invalid.", 400);
        }
        String key = identityKey(region, domain);
        Identity identity = identityStore.get(key).orElse(null);
        if (identity == null) {
            identity = new Identity(domain, "Domain");
            identity.setVerificationStatus("Pending");
            identity.setDkimEnabled(true);
            identity.setDkimVerificationStatus("Pending");
        }
        if (!hasDkimTokens(identity)) {
            regenerateDkimTokens(identity);
        }
        identityStore.put(key, identity);
        LOG.infov("VerifyDomainDkim: {0} (region {1})", domain, region);
        return identity.getDkimTokens();
    }

    /**
     * v2 PutEmailIdentityDkimSigningAttributes. AWS_SES (Easy DKIM): sets the next signing key length
     * and regenerates tokens when the length changes. EXTERNAL (BYODKIM): switches the origin and
     * clears the Easy DKIM tokens (the caller publishes its own selector, which Floci does not use for
     * signing). Returns the resulting DKIM status and tokens.
     */
    public DkimSigningResult putDkimSigningAttributes(String identityValue, String origin,
                                                      String signingSelector, String nextKeyLength,
                                                      String region) {
        // DKIM signing attributes are domain-level; AWS rejects a missing/blank value or an
        // email-address identity here (verified: all return the same 400 "must be a valid domain")
        // rather than mutating state that the email would just inherit back from its parent domain.
        if (identityValue == null || identityValue.isBlank() || identityValue.contains("@")) {
            throw new AwsException("BadRequestException",
                    "The EmailIdentity value must be a valid domain.", 400);
        }
        String key = identityKey(region, identityValue);
        Identity identity = identityStore.get(key)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Email identity " + identityValue + " does not exist.", 404));
        if ("EXTERNAL".equals(origin)) {
            identity.setDkimSigningAttributesOrigin("EXTERNAL");
            // Clear the Easy DKIM tokens and reset the status: Floci can't verify a BYODKIM selector,
            // so leaving a prior Success/Pending with no tokens (and no Route53 detection path) would
            // be inconsistent.
            identity.setDkimTokens(new ArrayList<>());
            identity.setDkimVerificationStatus("Pending");
            LOG.infov("PutEmailIdentityDkimSigningAttributes(EXTERNAL): {0} selector={1}",
                    identityValue, signingSelector);
        } else {
            identity.setDkimSigningAttributesOrigin("AWS_SES");
            // DKIM tokens are a domain concept; only (re)generate them for a domain identity.
            if ("Domain".equals(identity.getIdentityType())) {
                if (nextKeyLength != null && !nextKeyLength.equals(identity.getDkimCurrentSigningKeyLength())) {
                    identity.setDkimNextSigningKeyLength(nextKeyLength);
                    regenerateDkimTokens(identity);
                } else if (!hasDkimTokens(identity)) {
                    regenerateDkimTokens(identity);
                }
            }
            LOG.infov("PutEmailIdentityDkimSigningAttributes(AWS_SES): {0} keyLength={1}",
                    identityValue, nextKeyLength);
        }
        identityStore.put(key, refreshIdentityState(identity, region));
        Identity src = effectiveDkimSource(identity, region);
        return new DkimSigningResult(src.getDkimVerificationStatus(),
                src.getDkimTokens() == null ? List.of() : src.getDkimTokens());
    }

    /** Carrier for the PutEmailIdentityDkimSigningAttributes response ({@code dkimStatus} is v1-native). */
    public record DkimSigningResult(String dkimStatus, List<String> dkimTokens) {}

    /**
     * Resolves which identity's DKIM state should be reported for {@code identity}. A domain reports
     * its own DKIM; an email address reports its parent domain's DKIM (SigningEnabled / Status /
     * Tokens all inherit from the domain), matching AWS. Falls back to the identity itself when the
     * parent domain is not a registered identity.
     */
    public Identity effectiveDkimSource(Identity identity, String region) {
        if (identity == null || !"EmailAddress".equals(identity.getIdentityType())) {
            return identity;
        }
        String addr = identity.getIdentity();
        int at = addr == null ? -1 : addr.indexOf('@');
        if (at < 0 || at == addr.length() - 1) {
            return identity;
        }
        Identity domainIdentity = identityStore.get(identityKey(region, addr.substring(at + 1))).orElse(null);
        return domainIdentity == null ? identity : refreshIdentityState(domainIdentity, region);
    }

    private Identity refreshIdentityState(Identity identity, String region) {
        if (identity == null) {
            return null;
        }

        boolean changed = false;
        if ("Domain".equals(identity.getIdentityType()) && identity.getDkimTokens() == null) {
            regenerateDkimTokens(identity);
            changed = true;
        }

        if ("Domain".equals(identity.getIdentityType()) && hasDkimTokens(identity)) {
            changed |= normalizePendingDomainState(identity);
            // Upgrade identity- and DKIM-verification independently so that, e.g., after a key rotation
            // (which resets only DkimVerificationStatus while the identity stays verified), the DKIM
            // status can still return to Success once the new records are detected.
            // Only look up DNS when a status can still be upgraded — skip the (cached) Route53 check
            // once both identity- and DKIM-verification are already Success. DKIM verification tracks
            // DNS detection independently of the signing-enabled flag, so it can reach Success even
            // when DKIM signing is disabled, and can re-pend/re-upgrade after a key rotation.
            boolean needsUpgrade = !"Success".equals(identity.getVerificationStatus())
                    || !"Success".equals(identity.getDkimVerificationStatus());
            if (needsUpgrade && hasAllExpectedDkimRecords(identity, region)) {
                if (!"Success".equals(identity.getVerificationStatus())) {
                    identity.setVerificationStatus("Success");
                    changed = true;
                }
                if (!"Success".equals(identity.getDkimVerificationStatus())) {
                    identity.setDkimVerificationStatus("Success");
                    changed = true;
                }
            }
        }

        if ("Success".equals(identity.getVerificationStatus())) {
            invalidateDkimLookupCache(region, identity.getIdentity());
        }

        if (changed) {
            identityStore.put(identityKey(region, identity.getIdentity()), identity);
        }
        return identity;
    }

    private boolean hasDkimTokens(Identity identity) {
        return identity.getDkimTokens() != null && !identity.getDkimTokens().isEmpty();
    }

    private boolean normalizePendingDomainState(Identity identity) {
        boolean changed = false;
        if (!"Success".equals(identity.getVerificationStatus())
                && !"Pending".equals(identity.getVerificationStatus())) {
            identity.setVerificationStatus("Pending");
            changed = true;
        }
        // DKIM verification tracks DNS detection, not the signing-enabled flag, so a domain that has
        // begun tracking (NotStarted -> Pending) reports Pending on Get even while signing is disabled.
        if (!"Success".equals(identity.getDkimVerificationStatus())
                && !"Pending".equals(identity.getDkimVerificationStatus())) {
            identity.setDkimVerificationStatus("Pending");
            changed = true;
        }
        return changed;
    }

    private boolean hasAllExpectedDkimRecords(Identity identity, String region) {
        if (route53Service == null) {
            return false;
        }
        Instant now = Instant.now(clock);
        String cacheKey = dkimLookupCacheKey(region, identity);
        DkimLookupCacheEntry cached = dkimLookupCache.get(cacheKey);
        if (cached != null) {
            if (now.isBefore(cached.expiresAt())) {
                return cached.present();
            }
            dkimLookupCache.remove(cacheKey, cached);
        }

        boolean present = true;
        for (String token : identity.getDkimTokens()) {
            if (!hasExpectedDkimRecord(identity.getIdentity(), token)) {
                present = false;
                break;
            }
        }
        dkimLookupCache.put(cacheKey, new DkimLookupCacheEntry(present, now.plus(DKIM_LOOKUP_CACHE_TTL)));
        return present;
    }

    private boolean hasExpectedDkimRecord(String domain, String token) {
        String expectedName = normalizeDnsName(token + "._domainkey." + domain);
        String expectedValue = normalizeDnsName(token + ".dkim.amazonses.com");
        for (HostedZone zone : route53Service.listHostedZones(null, Integer.MAX_VALUE)) {
            for (ResourceRecordSet recordSet : route53Service.listResourceRecordSets(zone.getId(), null, null,
                    Integer.MAX_VALUE)) {
                if (!"CNAME".equalsIgnoreCase(recordSet.getType())) {
                    continue;
                }
                if (!expectedName.equals(normalizeDnsName(recordSet.getName()))) {
                    continue;
                }
                List<ResourceRecord> records = recordSet.getRecords();
                if (records == null) {
                    continue;
                }
                for (ResourceRecord record : records) {
                    if (record != null && expectedValue.equals(normalizeDnsName(record.getValue()))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String normalizeDnsName(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void invalidateDkimLookupCache(String region, String identityValue) {
        if (identityValue == null || identityValue.isBlank()) {
            return;
        }
        String cachePrefix = region + "::" + normalizeDnsName(identityValue) + "::";
        dkimLookupCache.keySet().removeIf(key -> key.startsWith(cachePrefix));
    }

    private String dkimLookupCacheKey(String region, Identity identity) {
        List<String> normalizedTokens = identity.getDkimTokens().stream()
                .map(this::normalizeDnsName)
                .sorted()
                .toList();
        return region + "::" + normalizeDnsName(identity.getIdentity()) + "::" + String.join(",", normalizedTokens);
    }

    private record DkimLookupCacheEntry(boolean present, Instant expiresAt) {}

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

    public void setEmailIdentityConfigurationSet(String identityValue, String configurationSetName,
                                                 String region) {
        String key = identityKey(region, identityValue);
        Identity identity = identityStore.get(key)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Identity <" + identityValue + "> does not exist.", 404));
        boolean clearing = configurationSetName == null || configurationSetName.isEmpty();
        if (!clearing) {
            getConfigurationSet(configurationSetName, region);
        }
        identity.setConfigurationSetName(clearing ? null : configurationSetName);
        identityStore.put(key, identity);
        LOG.infov("Updated default ConfigurationSet for {0}: {1}",
                identityValue, clearing ? "<cleared>" : configurationSetName);
    }

    /**
     * Resolves the configuration set a send should use: a non-blank configuration set explicitly
     * supplied by the caller takes precedence (a blank value is treated as absent); otherwise the
     * default configuration set associated with the sending identity (set via
     * {@code PutEmailIdentityConfigurationSetAttributes}) is used, with the email-address identity
     * taking precedence over its parent domain. If that default association is stale (its
     * configuration set was deleted), the send fails with a bad-request error, matching AWS.
     */
    private String resolveDefaultConfigurationSet(String configurationSetName, String source, String region) {
        if (configurationSetName != null && !configurationSetName.isBlank()) {
            return configurationSetName;
        }
        if (source == null || source.isBlank()) {
            return configurationSetName;
        }
        String email = extractEmailAddress(source);
        if (email.isBlank()) {
            return configurationSetName;
        }
        String fromEmail = existingDefaultConfigSet(identityStore.get(identityKey(region, email)).orElse(null), region);
        if (fromEmail != null) {
            return fromEmail;
        }
        int at = email.indexOf('@');
        if (at >= 0 && at < email.length() - 1) {
            String fromDomain = existingDefaultConfigSet(
                    identityStore.get(identityKey(region, email.substring(at + 1))).orElse(null), region);
            if (fromDomain != null) {
                return fromDomain;
            }
        }
        return configurationSetName;
    }

    private String existingDefaultConfigSet(Identity identity, String region) {
        if (identity == null) {
            return null;
        }
        String cs = identity.getConfigurationSetName();
        if (cs == null || cs.isEmpty()) {
            return null;
        }
        // AWS: deleting the configuration set that is an identity's default, then sending through
        // that identity, fails with a bad-request error rather than silently sending without it.
        if (configSetStore.get(configSetKey(region, cs)).isEmpty()) {
            throw new AwsException("BadRequestException",
                    "Configuration set <" + cs + "> does not exist.", 400);
        }
        return cs;
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
        return sentEmailService.listAll();
    }

    public void clearEmails() {
        sentEmailService.clear();
    }

    public boolean isAccountSendingEnabled(String region) {
        return accountService.isAccountSendingEnabled(region);
    }

    public void setAccountSendingEnabled(String region, boolean enabled) {
        accountService.setAccountSendingEnabled(region, enabled);
    }

    public Optional<AccountVdmAttributes> findAccountVdmAttributes(String region) {
        return accountService.findAccountVdmAttributes(region);
    }

    public void putAccountVdmAttributes(String region, AccountVdmAttributes vdm) {
        accountService.putAccountVdmAttributes(region, vdm);
    }

    public void setConfigurationSetSendingEnabled(String configSetName, boolean enabled, String region) {
        ConfigurationSet cs = getConfigurationSet(configSetName, region);
        cs.setSendingEnabled(enabled);
        configSetStore.put(configSetKey(region, configSetName), cs);
        LOG.infov("Updated SendingEnabled on configuration set {0} in region {1}: {2}",
                configSetName, region, enabled);
    }

    // ──────────────────────────── Templates ────────────────────────────

    // Email templates live in SesTemplateService; the facade forwards. The templated-send path below
    // reads them back through getTemplate, and ARN-dispatched tagging through find/save.

    public EmailTemplate createTemplate(EmailTemplate template, String region) {
        return templateService.createTemplate(template, region);
    }

    public EmailTemplate getTemplate(String templateName, String region) {
        return templateService.getTemplate(templateName, region);
    }

    public EmailTemplate updateTemplate(EmailTemplate template, String region) {
        return templateService.updateTemplate(template, region);
    }

    public void deleteTemplate(String templateName, String region) {
        templateService.deleteTemplate(templateName, region);
    }

    public List<EmailTemplate> listTemplates(String region) {
        return templateService.listTemplates(region);
    }

    // ──────────── Custom verification email templates (v1 + v2 shared store) ────────────
    // Verified against real AWS: the From address must be a verified identity, redirection URLs
    // must be valid, and the template body is not content-validated. Floci enforces the
    // From-verified check against its own identity store (it does track verified identities).

    public void createCustomVerificationEmailTemplate(CustomVerificationEmailTemplate template, String region) {
        // The From-verified check inside validation reaches the Identity domain, so the facade
        // validates here before the storage service performs the create.
        validateCustomVerificationTemplate(template, region);
        cvetService.createCustomVerificationEmailTemplate(template, region);
    }

    public CustomVerificationEmailTemplate getCustomVerificationEmailTemplate(String templateName, String region) {
        return cvetService.getCustomVerificationEmailTemplate(templateName, region);
    }

    public List<CustomVerificationEmailTemplate> listCustomVerificationEmailTemplates(String region) {
        return cvetService.listCustomVerificationEmailTemplates(region);
    }

    public void updateCustomVerificationEmailTemplate(CustomVerificationEmailTemplate template, String region) {
        // Validate (including the From-verified identity check and the required-field checks) before
        // delegating the storage update, matching createCustomVerificationEmailTemplate.
        validateCustomVerificationTemplate(template, region);
        cvetService.updateCustomVerificationEmailTemplate(template, region);
    }

    public void deleteCustomVerificationEmailTemplate(String templateName, String region) {
        cvetService.deleteCustomVerificationEmailTemplate(templateName, region);
    }

    // AWS appends this exact disclaimer to the end of every custom verification email and it cannot
    // be removed (SES docs Q10).
    private static final String CUSTOM_VERIFICATION_DISCLAIMER =
            "If you did not request to verify this email address, please disregard this message.";

    public String sendCustomVerificationEmail(String emailAddress, String templateName,
                                              String configurationSetName, String region) {
        // AWS validates the recipient before the template exists check (probe-confirmed): a blank
        // address is "Email address not specified."; anything that isn't a single valid address —
        // no local-part/domain separator, more than one separator, whitespace, or longer than 320
        // chars — is "Invalid email address<addr>." (both InvalidParameterValue / 400, remapped to
        // BadRequestException on v2). The separator count ignores '@' inside a quoted local part,
        // since AWS accepts an RFC-5321 quoted local part that contains '@' (e.g. "a@b"@example.com).
        if (emailAddress == null || emailAddress.isBlank()) {
            throw new AwsException("InvalidParameterValue", "Email address not specified.", 400);
        }
        // A leading/trailing '@' means an empty local part (@example.com) or empty domain (local@),
        // both of which AWS rejects (probe-confirmed). AWS does not require a dot in the domain
        // (a@b is accepted), so no stricter domain shape is enforced.
        boolean emptyBoundary = emailAddress.charAt(0) == '@'
                || emailAddress.charAt(emailAddress.length() - 1) == '@';
        if (emailAddress.length() > 320 || unquotedAtCount(emailAddress) != 1 || emptyBoundary
                || emailAddress.chars().anyMatch(Character::isWhitespace)) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid email address<" + emailAddress + ">.", 400);
        }
        if (templateName == null || templateName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "TemplateName is required.", 400);
        }
        CustomVerificationEmailTemplate template = cvetService.find(templateName, region)
                .orElseThrow(() -> new AwsException("CustomVerificationEmailTemplateDoesNotExist",
                        "Template <" + templateName + "> does not exist", 400));
        if (!isVerifiedSender(template.getFromEmailAddress(), region)) {
            throw new AwsException("FromEmailAddressNotVerified",
                    "Email address is not verified. The following identities failed the check in region "
                            + region.toUpperCase(Locale.ROOT) + ": " + template.getFromEmailAddress(), 400);
        }
        if (configurationSetName != null && !configurationSetName.isBlank()) {
            getConfigurationSet(configurationSetName, region);
        }

        // AWS registers the recipient as a pending-verification identity as part of sending the
        // verification email, so ListIdentities / GetIdentityVerificationAttributes surface it.
        markPendingEmailIdentity(emailAddress, region);

        // AWS sends the template content verbatim and appends a fixed, non-removable disclaimer (SES
        // docs Q10). AWS also appends a unique verification link, which Floci does not reproduce
        // because it has no verification-click flow. The template body carries no placeholder that
        // AWS substitutes, so the content itself is passed through unchanged.
        String body = template.getTemplateContent() == null ? "" : template.getTemplateContent();
        String renderedHtml = body + "<p>" + CUSTOM_VERIFICATION_DISCLAIMER + "</p>";

        String messageId = UUID.randomUUID().toString();
        SentEmail email = new SentEmail(messageId, region, template.getFromEmailAddress(),
                List.of(emailAddress), List.of(), List.of(), List.of(),
                template.getTemplateSubject(), null, renderedHtml);
        sentEmailService.record(region, messageId, email);
        smtpRelay.relay(template.getFromEmailAddress(), List.of(emailAddress), List.of(), List.of(),
                List.of(), template.getTemplateSubject(), null, renderedHtml, List.of());
        LOG.infov("SES custom verification email sent: to={0}, template={1}, messageId={2}",
                emailAddress, templateName, messageId);
        return messageId;
    }

    // Counts '@' characters outside a quoted local part. A valid address has exactly one — the
    // local-part/domain separator; '@' inside a quoted local part (honoring backslash escapes) is
    // part of the local part and doesn't count, so AWS-accepted forms like "a@b"@example.com pass
    // while a@@b.com and "a"@@example.com are rejected.
    private static int unquotedAtCount(String address) {
        int count = 0;
        boolean inQuotes = false;
        boolean escaped = false;
        for (int i = 0; i < address.length(); i++) {
            char c = address.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == '@' && !inQuotes) {
                count++;
            }
        }
        return count;
    }

    private void markPendingEmailIdentity(String emailAddress, String region) {
        String key = identityKey(region, emailAddress);
        if (identityStore.get(key).isEmpty()) {
            Identity identity = new Identity(emailAddress, "EmailAddress");
            identity.setVerificationStatus("Pending");
            identityStore.put(key, identity);
            LOG.infov("SES custom verification email registered pending identity {0} in region {1}",
                    emailAddress, region);
        }
    }

    private void validateCustomVerificationTemplate(CustomVerificationEmailTemplate t, String region) {
        requireCvetField(t.getTemplateName(), "TemplateName");
        requireCvetField(t.getFromEmailAddress(), "FromEmailAddress");
        requireCvetField(t.getTemplateSubject(), "TemplateSubject");
        requireCvetField(t.getTemplateContent(), "TemplateContent");
        requireCvetField(t.getSuccessRedirectionURL(), "SuccessRedirectionURL");
        requireCvetField(t.getFailureRedirectionURL(), "FailureRedirectionURL");
        if (!isVerifiedSender(t.getFromEmailAddress(), region)) {
            // v1-native code (verified: FromEmailAddressNotVerified / 400); remapV1Exception
            // translates it to NotFoundException / 404 for the v2 boundary.
            throw new AwsException("FromEmailAddressNotVerified",
                    "The from email address <" + t.getFromEmailAddress() + "> is not verified", 400);
        }
        if (!isValidRedirectUrl(t.getSuccessRedirectionURL())) {
            throw new AwsException("InvalidParameterValue", "The success redirection URL is invalid", 400);
        }
        if (!isValidRedirectUrl(t.getFailureRedirectionURL())) {
            throw new AwsException("InvalidParameterValue", "The failure redirection URL is invalid", 400);
        }
    }

    private boolean isVerifiedSender(String fromEmail, String region) {
        if (fromEmail == null) {
            return false;
        }
        if (isIdentityVerified(fromEmail, region)) {
            return true;
        }
        int at = fromEmail.indexOf('@');
        return at >= 0 && at < fromEmail.length() - 1
                && isIdentityVerified(fromEmail.substring(at + 1), region);
    }

    private boolean isIdentityVerified(String identity, String region) {
        Identity id = getIdentityVerificationAttributes(identity, region);
        return id != null && "Success".equals(id.getVerificationStatus());
    }

    private static boolean isValidRedirectUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            String scheme = uri.getScheme();
            return uri.getHost() != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void requireCvetField(String value, String name) {
        if (value == null || value.isBlank()) {
            // v1-native code; the v2 controller remaps it to BadRequestException via remapV1Exception,
            // so the Query API stays consistent with requireParam and v2 behavior is unchanged.
            throw new AwsException("InvalidParameterValue", name + " is required.", 400);
        }
    }

    public ConfigurationSet createConfigurationSet(ConfigurationSet configSet, String region) {
        if (configSet == null) {
            throw new AwsException("InvalidParameterValue",
                    "ConfigurationSetName is required.", 400);
        }
        String key = configSetKey(region, configSet.getName());
        if (configSet.getTags() != null) {
            for (Tag tag : configSet.getTags()) {
                SesTags.validate(tag);
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

    // ──────────────────────── Receipt rule sets (inbound) ────────────────────────
    //
    // Receipt rule sets live in SesReceiptRuleService; this facade
    // just forwards, keeping the v1 SesQueryHandler call sites unchanged.

    public ReceiptRuleSet createReceiptRuleSet(String name, String region) {
        return receiptRuleService.createReceiptRuleSet(name, region);
    }

    public ReceiptRuleSet describeReceiptRuleSet(String name, String region) {
        return receiptRuleService.describeReceiptRuleSet(name, region);
    }

    public List<ReceiptRuleSet> listReceiptRuleSets(String region) {
        return receiptRuleService.listReceiptRuleSets(region);
    }

    public void deleteReceiptRuleSet(String name, String region) {
        receiptRuleService.deleteReceiptRuleSet(name, region);
    }

    public void setActiveReceiptRuleSet(String name, String region) {
        receiptRuleService.setActiveReceiptRuleSet(name, region);
    }

    public ReceiptRuleSet describeActiveReceiptRuleSet(String region) {
        return receiptRuleService.describeActiveReceiptRuleSet(region);
    }

    // ──────────────────────── Dedicated IP Pools ────────────────────────

    // Storage lives in SesDedicatedIpService; the facade forwards.

    public DedicatedIpPool createDedicatedIpPool(String poolName, String scalingMode, String region) {
        return dedicatedIpService.createDedicatedIpPool(poolName, scalingMode, region);
    }

    public DedicatedIpPool getDedicatedIpPool(String poolName, String region) {
        return dedicatedIpService.getDedicatedIpPool(poolName, region);
    }

    public boolean dedicatedIpPoolExists(String poolName, String region) {
        return dedicatedIpService.dedicatedIpPoolExists(poolName, region);
    }

    public List<String> listDedicatedIpPools(String region) {
        return dedicatedIpService.listDedicatedIpPools(region);
    }

    public void deleteDedicatedIpPool(String poolName, String region) {
        dedicatedIpService.deleteDedicatedIpPool(poolName, region);
    }


    // Contact lists and contacts live in SesContactService; the facade forwards.
    // Its send-path list-management (collectListManagementOptOuts) also calls into that service.

    public ContactList createContactList(String name, String description, List<Topic> topics,
                                         List<Tag> tags, String region) {
        return contactService.createContactList(name, description, topics, tags, region);
    }

    public ContactList getContactList(String name, String region) {
        return contactService.getContactList(name, region);
    }

    public List<ContactList> listContactLists(String region) {
        return contactService.listContactLists(region);
    }

    public ContactList updateContactList(String name, String description, boolean descriptionPresent,
                                         List<Topic> topics, String region) {
        return contactService.updateContactList(name, description, descriptionPresent, topics, region);
    }

    public void deleteContactList(String name, String region) {
        contactService.deleteContactList(name, region);
    }

    public Contact createContact(String listName, String emailAddress, List<TopicPreference> topicPreferences,
                                 Boolean unsubscribeAll, String attributesData, String region) {
        return contactService.createContact(listName, emailAddress, topicPreferences, unsubscribeAll,
                attributesData, region);
    }

    public SesContactService.ContactWithList getContact(String listName, String emailAddress, String region) {
        return contactService.getContact(listName, emailAddress, region);
    }

    public SesContactService.ContactsWithList listContacts(String listName, String region) {
        return contactService.listContacts(listName, region);
    }

    public Contact updateContact(String listName, String emailAddress, List<TopicPreference> topicPreferences,
                                 boolean topicPreferencesPresent, Boolean unsubscribeAll, String attributesData,
                                 String region) {
        return contactService.updateContact(listName, emailAddress, topicPreferences, topicPreferencesPresent,
                unsubscribeAll, attributesData, region);
    }

    public void deleteContact(String listName, String emailAddress, String region) {
        contactService.deleteContact(listName, emailAddress, region);
    }

    public List<TopicPreference> deriveTopicDefaultPreferences(Contact contact, ContactList list) {
        return contactService.deriveTopicDefaultPreferences(contact, list);
    }

    public void unsubscribeContact(String listName, String emailAddress, String topicName, String region) {
        contactService.unsubscribeContact(listName, emailAddress, topicName, region);
    }

    // ──────────────── Identity (sending authorization) policies ────────────────
    // One shared store behind the v1 (PutIdentityPolicy/GetIdentityPolicies/ListIdentityPolicies/
    // DeleteIdentityPolicy) and v2 (Create/Get/Update/DeleteEmailIdentityPolicy) APIs. Verified
    // against real AWS. Floci stores and returns policies but does not enforce the authorization
    // (Principal-account existence, Resource-ARN match, or send-time checks) — it has no account
    // registry and does not gate sending, so these are treated as metadata.
    // Policy storage lives in SesPolicyService; this facade forwards, and for the v2 mutators it runs
    // the identity-existence check (an Identity-domain read) first, before delegating.

    public void putIdentityPolicy(String identity, String policyName, String policy, String region) {
        policyService.putIdentityPolicy(identity, policyName, policy, region);
    }

    public void createEmailIdentityPolicy(String identity, String policyName, String policy, String region) {
        requireIdentityExists(identity, region);
        policyService.createEmailIdentityPolicy(identity, policyName, policy, region);
    }

    public void updateEmailIdentityPolicy(String identity, String policyName, String policy, String region) {
        requireIdentityExists(identity, region);
        policyService.updateEmailIdentityPolicy(identity, policyName, policy, region);
    }

    public Map<String, String> getEmailIdentityPolicies(String identity, String region) {
        requireIdentityExists(identity, region);
        return policyService.listAllPolicies(identity, region);
    }

    public Map<String, String> getIdentityPolicies(String identity, List<String> policyNames, String region) {
        return policyService.getIdentityPolicies(identity, policyNames, region);
    }

    public List<String> listIdentityPolicyNames(String identity, String region) {
        return policyService.listIdentityPolicyNames(identity, region);
    }

    public void deleteEmailIdentityPolicy(String identity, String policyName, String region) {
        requireIdentityExists(identity, region);
        policyService.deleteEmailIdentityPolicy(identity, policyName, region);
    }

    public void deleteIdentityPolicy(String identity, String policyName, String region) {
        policyService.deleteIdentityPolicy(identity, policyName, region);
    }

    private void requireIdentityExists(String identity, String region) {
        if (identityStore.get(identityKey(region, identity)).isEmpty()) {
            throw new AwsException("NotFoundException",
                    "Email identity <" + identity + "> does not exist.", 404);
        }
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
            SesTags.validate(t);
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
        EmailTemplate template = templateService.find(name, region)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "No Template present with name: " + name, 404));
        return new ArrayList<>(template.getTags());
    }

    private void tagEmailTemplate(String name, String region, List<Tag> newTags) {
        EmailTemplate template = templateService.find(name, region)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "No Template present with name: " + name, 404));
        template.setTags(mergeTags(template.getTags(), newTags));
        templateService.save(template, region);
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
                SesTags.validate(tag);
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
        EmailTemplate template = templateService.find(name, region)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "No Template present with name: " + name, 404));
        Set<String> toRemove = new HashSet<>(tagKeys);
        template.getTags().removeIf(t -> toRemove.contains(t.key()));
        templateService.save(template, region);
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

    // ──────────────────── Suppression (account attributes + list) ────────────────────
    // Storage lives in SesSuppressionService; the facade forwards, and its send
    // filters (collectSuppressedReasons / resolveSuppressionReason) read entries back through it.

    public AccountSuppressionAttributes getAccountSuppressionAttributes(String region) {
        return suppressionService.getAccountSuppressionAttributes(region);
    }

    public void putAccountSuppressionAttributes(String region, List<String> suppressedReasons) {
        suppressionService.putAccountSuppressionAttributes(region, suppressedReasons);
    }

    public void putSuppressedDestination(String region, String emailAddress, String reason) {
        suppressionService.putSuppressedDestination(region, emailAddress, reason);
    }

    public SuppressedDestination getSuppressedDestination(String region, String emailAddress) {
        return suppressionService.getSuppressedDestination(region, emailAddress);
    }

    public void deleteSuppressedDestination(String region, String emailAddress) {
        suppressionService.deleteSuppressedDestination(region, emailAddress);
    }

    public List<SuppressedDestination> listSuppressedDestinations(String region, List<String> reasonFilters) {
        return suppressionService.listSuppressedDestinations(region, reasonFilters);
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
            SuppressedDestination entry = suppressionService.findSuppressedDestination(region, address)
                    .orElse(null);
            if (entry != null && entry.getReason() != null
                    && reasonFilter.contains(entry.getReason())) {
                result.put(address, entry.getReason());
            }
        }
        return result;
    }

    /**
     * Resolves the recipients suppressed by SES V2 {@code SendEmail} {@code ListManagementOptions}:
     * for each envelope recipient that is opted out of the named contact list (or the given topic),
     * returns a {@code BOUNCE} suppression reason so the shared send path drops the recipient from
     * the relay and publishes a Bounce event — matching AWS ("SES will issue a bounce event for a
     * message that is sent to an unsubscribed contact"). Returns an empty map when no
     * {@code ListManagementOptions} was supplied. Throws when the contact list does not exist, so a
     * bad reference fails the whole send. A recipient that is not yet a contact is created
     * automatically (matching AWS), then evaluated like any other contact.
     */
    private Map<String, String> collectListManagementOptOuts(Collection<String> addresses,
                                                             ListManagementOptions listManagement, String region) {
        if (listManagement == null || listManagement.contactListName() == null
                || listManagement.contactListName().isBlank() || addresses == null || addresses.isEmpty()) {
            return Map.of();
        }
        ContactList list = contactService.getContactList(listManagement.contactListName(), region);
        String topicName = listManagement.topicName();
        String effectiveTopic = (topicName == null || topicName.isBlank()) ? null : topicName;
        // Fail fast on a topic that isn't defined on the list rather than silently skipping
        // suppression (a typo would otherwise send to everyone). AWS does not document this, so the
        // exact error is best-effort.
        if (effectiveTopic != null && contactService.defaultTopicStatus(list, effectiveTopic) == null) {
            throw new AwsException("BadRequestException",
                    "Topic " + effectiveTopic + " does not exist in contact list "
                            + list.getContactListName() + ".", 400);
        }
        Map<String, String> optOuts = new LinkedHashMap<>();
        for (String address : addresses) {
            if (address == null || address.isBlank() || optOuts.containsKey(address)) {
                continue;
            }
            String email = extractEmailAddress(address);
            if (email == null || email.isBlank()) {
                continue;
            }
            Contact contact = contactService.getOrAutoCreateContact(list, email, region);
            if (contactService.isListManagementOptedOut(contact, list, effectiveTopic)) {
                optOuts.put(address, "BOUNCE");
            }
        }
        return optOuts;
    }

    private static final String UNSUBSCRIBE_PLACEHOLDER = "{{amazonSESUnsubscribeUrl}}";

    private static boolean hasListManagement(ListManagementOptions listManagement) {
        return listManagement != null && listManagement.contactListName() != null
                && !listManagement.contactListName().isBlank();
    }

    /**
     * Builds the functional one-click unsubscribe URL served by Floci's {@code /_aws/ses/unsubscribe}
     * endpoint. Unlike AWS's opaque hosted URL, the list, topic, and address are carried as readable
     * query parameters so the link is directly usable and testable against the local emulator.
     */
    private String buildUnsubscribeUrl(String region, ListManagementOptions listManagement, String address) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        StringBuilder sb = new StringBuilder(base)
                .append("/_aws/ses/unsubscribe?region=").append(urlEncode(region))
                .append("&contactList=").append(urlEncode(listManagement.contactListName()))
                .append("&address=").append(urlEncode(address));
        if (listManagement.topicName() != null && !listManagement.topicName().isBlank()) {
            sb.append("&topic=").append(urlEncode(listManagement.topicName()));
        }
        return sb.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Replaces up to the first two {@code {{amazonSESUnsubscribeUrl}}} occurrences, as AWS does. */
    private static String replaceUnsubscribePlaceholder(String body, String url) {
        if (body == null || !body.contains(UNSUBSCRIBE_PLACEHOLDER)) {
            return body;
        }
        StringBuilder out = new StringBuilder();
        int from = 0;
        int replaced = 0;
        while (replaced < 2) {
            int at = body.indexOf(UNSUBSCRIBE_PLACEHOLDER, from);
            if (at < 0) {
                break;
            }
            out.append(body, from, at).append(url);
            from = at + UNSUBSCRIBE_PLACEHOLDER.length();
            replaced++;
        }
        out.append(body.substring(from));
        return out.toString();
    }

    private static List<MessageHeader> withUnsubscribeHeaders(List<MessageHeader> headers, String url) {
        List<MessageHeader> out = new ArrayList<>();
        // Override any caller-supplied unsubscribe headers rather than appending a duplicate, matching
        // AWS ("SES will override these headers if they are present in the email").
        if (headers != null) {
            for (MessageHeader h : headers) {
                if (!"List-Unsubscribe".equalsIgnoreCase(h.name())
                        && !"List-Unsubscribe-Post".equalsIgnoreCase(h.name())) {
                    out.add(h);
                }
            }
        }
        out.add(new MessageHeader("List-Unsubscribe", "<" + url + ">"));
        out.add(new MessageHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click"));
        return out;
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
     * {@link SesSuppressionService}'s reason validation / {@link #validateConfigSetSuppressionReason},
     * which enforce exact case-sensitive equality with the two canonical values, so
     * {@code entry.getReason()} is guaranteed to be canonical and downstream
     * {@code .equals("BOUNCE")} / {@code .equals("COMPLAINT")} checks are safe.
     */
    String resolveSuppressionReason(String emailAddress, String configurationSetName, String region) {
        if (emailAddress == null || emailAddress.isBlank()) {
            return null;
        }
        // Read through the suppression service so this shares its normalization and legacy-key
        // fallback with GET/DELETE (lookups can't drift apart from inserts).
        SuppressedDestination entry = suppressionService.findSuppressedDestination(region, emailAddress)
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

    public String sendTemplatedEmail(String source, List<String> toAddresses, List<String> ccAddresses,
                                     List<String> bccAddresses, List<String> replyToAddresses,
                                     String templateName, JsonNode templateData,
                                     String configurationSetName, List<MessageTag> emailTags,
                                     List<MessageHeader> additionalHeaders,
                                     ListManagementOptions listManagement, String region) {
        EmailTemplate template = getTemplate(templateName, region);
        return sendInlineTemplatedEmail(source, toAddresses, ccAddresses, bccAddresses,
                replyToAddresses, template.getSubject(), template.getTextPart(),
                template.getHtmlPart(), templateData,
                configurationSetName, emailTags, additionalHeaders, listManagement, region);
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
                                            ListManagementOptions listManagement, String region) {
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
                configurationSetName, emailTags, additionalHeaders, listManagement, region);
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
                // SendBulkEmail has no ListManagementOptions field, so list-managed suppression
                // does not apply to bulk sends.
                String messageId = sendEmail(source,
                        entry.toAddresses(), entry.ccAddresses(), entry.bccAddresses(),
                        replyToAddresses,
                        applyTemplateData(subject, merged),
                        applyTemplateData(textPart, merged),
                        applyTemplateData(htmlPart, merged),
                        configurationSetName, mergedTags, mergedHeaders, null, region);
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
            if ("amazonSESUnsubscribeUrl".equals(key)) {
                // Reserved list-management placeholder: leave it intact for post-render replacement
                // in the send path, so a templated body can carry {{amazonSESUnsubscribeUrl}} without
                // failing as a missing rendering attribute.
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
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
