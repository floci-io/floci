package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.model.AccountSuppressionAttributes;
import io.github.hectorvent.floci.services.ses.model.AccountDetails;
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
import io.github.hectorvent.floci.services.ses.model.Tenant;
import io.github.hectorvent.floci.services.ses.model.TenantResourceAssociation;
import io.github.hectorvent.floci.services.ses.model.SuppressedDestination;
import io.github.hectorvent.floci.services.ses.model.SuppressionOptions;
import io.github.hectorvent.floci.services.ses.model.Tag;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class SesService {

    private static final Logger LOG = Logger.getLogger(SesService.class);

    private static final int MAX_BULK_DESTINATIONS = 50;
    private static final int MAX_RECIPIENTS_PER_DESTINATION = 50;
    // Identities extracted to SesIdentityService (CRUD, verification, MAIL FROM, notifications,
    // tags, and the DKIM state machine with its Route53 lookup). The facade keeps the cross-domain
    // flows and send-path reads, reaching the store through its find/save.
    private final SesIdentityService identityService;
    // Sent-email records extracted to SesSentEmailService. The send path records finished emails via
    // it; send-statistics and inspection read back through it.
    private final SesSentEmailService sentEmailService;
    // Account-level settings, extracted to its own service. The facade delegates.
    private final SesAccountService accountService;
    // Email templates extracted to SesTemplateService. The facade delegates; the templated-send path
    // reads via it, and ARN-dispatched tagging reads/writes via its find/save.
    private final SesTemplateService templateService;
    // Configuration sets extracted to SesConfigurationSetService. The facade delegates; the
    // cross-domain option validation (tracking's verified domain, delivery's dedicated pool), the
    // send-path reads, and the ARN-dispatched tagging go through its get/find/save.
    private final SesConfigurationSetService configSetService;
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
    // Tenants (multi-tenancy) live in SesTenantService. The facade delegates.
    private final SesTenantService tenantService;
    private final SmtpRelay smtpRelay;
    private final SesEventPublisher eventPublisher;
    private final String defaultAccountId;
    // Base URL used to build functional list-management unsubscribe links (the {{amazonSESUnsubscribeUrl}}
    // placeholder and the List-Unsubscribe header) that resolve to Floci's own unsubscribe endpoint.
    private final String baseUrl;
    // Resolves the caller's account per request so send-event payloads report the sending account, not
    // the fixed default. Null in the package-private test constructor (falls back to defaultAccountId).
    private final RegionResolver regionResolver;

    @Inject
    public SesService(SesIdentityService identityService, SesReceiptRuleService receiptRuleService,
                       SesAccountService accountService, SesCvetService cvetService,
                       SesPolicyService policyService, SesContactService contactService,
                       SesSuppressionService suppressionService, SesDedicatedIpService dedicatedIpService,
                       SesTemplateService templateService, SesSentEmailService sentEmailService,
                       SesTenantService tenantService, SesConfigurationSetService configSetService,
                       SmtpRelay smtpRelay,
                       SesEventPublisher eventPublisher, EmulatorConfig config,
                       RegionResolver regionResolver) {
        this.identityService = identityService;
        this.sentEmailService = sentEmailService;
        this.accountService = accountService;
        this.templateService = templateService;
        this.configSetService = configSetService;
        this.suppressionService = suppressionService;
        this.dedicatedIpService = dedicatedIpService;
        this.contactService = contactService;
        this.policyService = policyService;
        this.receiptRuleService = receiptRuleService;
        this.cvetService = cvetService;
        this.tenantService = tenantService;
        this.smtpRelay = smtpRelay;
        this.eventPublisher = eventPublisher;
        this.defaultAccountId = config.defaultAccountId();
        this.baseUrl = config.effectiveBaseUrl();
        this.regionResolver = regionResolver;
    }

    SesService(SesIdentityService identityService,
               SesSentEmailService sentEmailService,
               SesAccountService accountService,
               SesTemplateService templateService,
               SesConfigurationSetService configSetService,
               SesSuppressionService suppressionService,
               SesDedicatedIpService dedicatedIpService,
               SesContactService contactService,
               SesPolicyService policyService,
               SesReceiptRuleService receiptRuleService,
               SesCvetService cvetService,
               SesTenantService tenantService,
               SmtpRelay smtpRelay) {
        this.identityService = identityService;
        this.sentEmailService = sentEmailService;
        this.accountService = accountService;
        this.templateService = templateService;
        this.configSetService = configSetService;
        this.suppressionService = suppressionService;
        this.dedicatedIpService = dedicatedIpService;
        this.contactService = contactService;
        this.policyService = policyService;
        this.receiptRuleService = receiptRuleService;
        this.cvetService = cvetService;
        this.tenantService = tenantService;
        this.smtpRelay = smtpRelay;
        this.eventPublisher = null;
        this.defaultAccountId = "000000000000";
        this.baseUrl = "http://localhost:4566";
        this.regionResolver = null;
    }

    public Identity verifyEmailIdentity(String emailAddress, String region) {
        return identityService.verifyEmailIdentity(emailAddress, region);
    }

    public Identity verifyDomainIdentity(String domain, String region) {
        return identityService.verifyDomainIdentity(domain, region);
    }

    public void deleteIdentity(String identityValue, String region) {
        if (identityValue == null || identityValue.isBlank()) {
            return;
        }
        tenantService.deleteBackingResource(SesTenantService.RESOURCE_TYPE_IDENTITY, identityValue,
                region, () -> doDeleteIdentity(identityValue, region));
    }

    private void doDeleteIdentity(String identityValue, String region) {
        identityService.delete(identityValue, region);

        // Policies are sub-resources of the identity; drop them too so they can't resurrect into a
        // same-named identity recreated later (and so the per-identity count stays correct).
        policyService.deletePoliciesForIdentity(identityValue, region);

        LOG.infov("Deleted identity: {0}", identityValue);
    }

    public List<Identity> listIdentities(String identityType, String region) {
        return identityService.listIdentities(identityType, region);
    }

    public Identity getIdentityVerificationAttributes(String identityValue, String region) {
        return identityService.getIdentityVerificationAttributes(identityValue, region);
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
        ConfigurationSet cs = configSetService.get(configurationSetName, region);
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
            cs = configSetService.find(configurationSetName, region).orElse(null);
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
        // Report the caller's account (resolved per request) in the event payload and source ARN,
        // falling back to the default account outside a request context (e.g. unit tests).
        String sendingAccountId = regionResolver != null ? regionResolver.getAccountId() : defaultAccountId;
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
        Identity emailIdentity = identityService.find(email, region).orElse(null);
        Identity domainIdentity = null;
        int at = email.indexOf('@');
        if (at >= 0 && at < email.length() - 1) {
            domainIdentity = identityService.find(email.substring(at + 1), region).orElse(null);
        }
        for (String type : SesIdentityService.NOTIFICATION_TYPES) {
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
        identityService.setIdentityNotificationTopic(identityValue, notificationType, snsTopic, region);
    }

    public Identity getIdentityNotificationAttributes(String identityValue, String region) {
        return identityService.getIdentityNotificationAttributes(identityValue, region);
    }

    public void setDkimAttributes(String identityValue, boolean signingEnabled, String region) {
        identityService.setDkimAttributes(identityValue, signingEnabled, region);
    }

    public List<String> verifyDomainDkim(String domain, String region) {
        return identityService.verifyDomainDkim(domain, region);
    }

    public SesIdentityService.DkimSigningResult putDkimSigningAttributes(String identityValue, String origin,
                                                                         String signingSelector, String nextKeyLength,
                                                                         String region) {
        return identityService.putDkimSigningAttributes(identityValue, origin, signingSelector,
                nextKeyLength, region);
    }

    public Identity effectiveDkimSource(Identity identity, String region) {
        return identityService.effectiveDkimSource(identity, region);
    }

    public void setFeedbackForwardingEnabled(String identityValue, boolean enabled, String region) {
        identityService.setFeedbackForwardingEnabled(identityValue, enabled, region);
    }

    public void setEmailIdentityConfigurationSet(String identityValue, String configurationSetName,
                                                 String region) {
        Identity identity = identityService.find(identityValue, region)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Identity <" + identityValue + "> does not exist.", 404));
        boolean clearing = configurationSetName == null || configurationSetName.isEmpty();
        if (!clearing) {
            getConfigurationSet(configurationSetName, region);
        }
        identity.setConfigurationSetName(clearing ? null : configurationSetName);
        identityService.save(identity, region);
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
        String fromEmail = existingDefaultConfigSet(identityService.find(email, region).orElse(null), region);
        if (fromEmail != null) {
            return fromEmail;
        }
        int at = email.indexOf('@');
        if (at >= 0 && at < email.length() - 1) {
            String fromDomain = existingDefaultConfigSet(
                    identityService.find(email.substring(at + 1), region).orElse(null), region);
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
        if (configSetService.find(cs, region).isEmpty()) {
            throw new AwsException("BadRequestException",
                    "Configuration set <" + cs + "> does not exist.", 400);
        }
        return cs;
    }

    public void setMailFromDomain(String identityValue, String mailFromDomain,
                                   String behaviorOnMxFailure, String region) {
        identityService.setMailFromDomain(identityValue, mailFromDomain, behaviorOnMxFailure, region);
    }

    public Identity getMailFromAttributes(String identityValue, String region) {
        return identityService.getMailFromAttributes(identityValue, region);
    }

    public void setHeadersInNotificationsEnabled(String identityValue, String notificationType,
                                                   boolean enabled, String region) {
        identityService.setHeadersInNotificationsEnabled(identityValue, notificationType, enabled, region);
    }

    public List<String> getVerifiedEmailAddresses(String region) {
        return identityService.getVerifiedEmailAddresses(region);
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

    public Optional<AccountDetails> findAccountDetails(String region) {
        return accountService.findAccountDetails(region);
    }

    public AccountDetails putAccountDetails(String region, String mailType, String websiteUrl,
                                            String contactLanguage, String useCaseDescription,
                                            List<String> additionalContacts, boolean productionAccessEnabled) {
        return accountService.putAccountDetails(region, mailType, websiteUrl, contactLanguage,
                useCaseDescription, additionalContacts, productionAccessEnabled);
    }

    public Optional<AccountVdmAttributes> findAccountVdmAttributes(String region) {
        return accountService.findAccountVdmAttributes(region);
    }

    public void putAccountVdmAttributes(String region, AccountVdmAttributes vdm) {
        accountService.putAccountVdmAttributes(region, vdm);
    }

    public void setConfigurationSetSendingEnabled(String configSetName, boolean enabled, String region) {
        configSetService.setSendingEnabled(configSetName, enabled, region);
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
        tenantService.deleteBackingResource(SesTenantService.RESOURCE_TYPE_TEMPLATE, templateName,
                region, () -> templateService.deleteTemplate(templateName, region));
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
        if (!identityService.isVerifiedSender(template.getFromEmailAddress(), region)) {
            throw new AwsException("FromEmailAddressNotVerified",
                    "Email address is not verified. The following identities failed the check in region "
                            + region.toUpperCase(Locale.ROOT) + ": " + template.getFromEmailAddress(), 400);
        }
        if (configurationSetName != null && !configurationSetName.isBlank()) {
            getConfigurationSet(configurationSetName, region);
        }

        // AWS registers the recipient as a pending-verification identity as part of sending the
        // verification email, so ListIdentities / GetIdentityVerificationAttributes surface it.
        identityService.markPendingEmailIdentity(emailAddress, region);

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

    private void validateCustomVerificationTemplate(CustomVerificationEmailTemplate t, String region) {
        requireCvetField(t.getTemplateName(), "TemplateName");
        requireCvetField(t.getFromEmailAddress(), "FromEmailAddress");
        requireCvetField(t.getTemplateSubject(), "TemplateSubject");
        requireCvetField(t.getTemplateContent(), "TemplateContent");
        requireCvetField(t.getSuccessRedirectionURL(), "SuccessRedirectionURL");
        requireCvetField(t.getFailureRedirectionURL(), "FailureRedirectionURL");
        if (!identityService.isVerifiedSender(t.getFromEmailAddress(), region)) {
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
        SesConfigurationSetService.validateConfigurationSetName(configSet.getName());
        SesTags.validate(configSet.getTags());
        if (configSet.getSuppressionOptions() != null
                && configSet.getSuppressionOptions().getSuppressedReasons() != null) {
            for (String reason : configSet.getSuppressionOptions().getSuppressedReasons()) {
                if (reason == null) {
                    throw new AwsException("BadRequestException",
                            SesConfigurationSetService.invalidSuppressionReasonMessage(null), 400);
                }
                if (!SesConfigurationSetService.isValidSuppressionReason(reason)) {
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
        SesConfigurationSetService.validateVdmOptions(configSet.getVdmOptions());
        return configSetService.create(configSet, region);
    }

    // The tracking and delivery setters stay here: their validation reads other domains (a verified
    // domain identity, a dedicated IP pool), so the facade resolves existence through the service,
    // validates, and writes back through save.

    public void setConfigurationSetTrackingOptions(String configSetName, TrackingOptions options, String region) {
        ConfigurationSet cs = configSetService.get(configSetName, region);
        validateTrackingOptions(options, region);
        cs.setTrackingOptions(options);
        configSetService.save(cs, region);
        LOG.infov("Updated TrackingOptions on configuration set {0} in region {1}", configSetName, region);
    }

    public void setConfigurationSetDeliveryOptions(String configSetName, DeliveryOptions options, String region) {
        ConfigurationSet cs = configSetService.get(configSetName, region);
        validateDeliveryOptions(options, region);
        cs.setDeliveryOptions(options);
        configSetService.save(cs, region);
        LOG.infov("Updated DeliveryOptions on configuration set {0} in region {1}", configSetName, region);
    }

    public void setConfigurationSetReputationOptions(String configSetName, boolean metricsEnabled, String region) {
        configSetService.setReputationMetricsEnabled(configSetName, metricsEnabled, region);
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
        ConfigurationSet cs = configSetService.get(configSetName, region);
        if (cs.getTrackingOptions() != null && cs.getTrackingOptions().getCustomRedirectDomain() != null) {
            throw new AwsException("TrackingOptionsAlreadyExistsException",
                    "Configuration set <" + configSetName + "> already has tracking options.", 400);
        }
        TrackingOptions options = new TrackingOptions();
        options.setCustomRedirectDomain(customRedirectDomain);
        cs.setTrackingOptions(options);
        configSetService.save(cs, region);
        LOG.infov("Created TrackingOptions on configuration set {0} in region {1}", configSetName, region);
    }

    public void updateConfigurationSetTrackingOptions(String configSetName, String customRedirectDomain,
                                                      String region) {
        requireVerifiedRedirectDomain(customRedirectDomain, region);
        ConfigurationSet cs = configSetService.get(configSetName, region);
        if (cs.getTrackingOptions() == null || cs.getTrackingOptions().getCustomRedirectDomain() == null) {
            throw new AwsException("TrackingOptionsDoesNotExistException",
                    "There are no tracking options for configuration set <" + configSetName + ">", 400);
        }
        cs.getTrackingOptions().setCustomRedirectDomain(customRedirectDomain);
        configSetService.save(cs, region);
        LOG.infov("Updated TrackingOptions on configuration set {0} in region {1}", configSetName, region);
    }

    public void deleteConfigurationSetTrackingOptions(String configSetName, String region) {
        configSetService.deleteTrackingOptions(configSetName, region);
    }

    public void setConfigurationSetArchivingOptions(String configSetName, ArchivingOptions options, String region) {
        configSetService.setArchivingOptions(configSetName, options, region);
    }

    public void setConfigurationSetVdmOptions(String configSetName, VdmOptions options, String region) {
        configSetService.setVdmOptions(configSetName, options, region);
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
        return configSetService.get(name, region);
    }

    public List<ConfigurationSet> listConfigurationSets(String region) {
        return configSetService.list(region);
    }

    public void deleteConfigurationSet(String name, String region) {
        configSetService.get(name, region);
        tenantService.deleteBackingResource(SesTenantService.RESOURCE_TYPE_CONFIGURATION_SET, name,
                region, () -> configSetService.remove(name, region));
    }

    // ──────────────────────── Tenants (multi-tenancy) ────────────────────────
    // Tenants live in SesTenantService; the facade forwards.

    public Tenant createTenant(String tenantName, List<Tag> tags, List<String> suppressedReasons,
                               String suppressionScope, String accountId, String region) {
        return tenantService.createTenant(tenantName, tags, suppressedReasons, suppressionScope,
                accountId, region);
    }

    public void putTenantSuppressionAttributes(String tenantName, List<String> suppressedReasons,
                                               String suppressionScope, String region) {
        tenantService.putSuppressionAttributes(tenantName, suppressedReasons, suppressionScope, region);
    }

    public Tenant getTenant(String tenantName, String region) {
        return tenantService.getTenant(tenantName, region);
    }

    public List<Tenant> listTenants(String region) {
        return tenantService.listTenants(region);
    }

    public void deleteTenant(String tenantName, String region) {
        // The tenant-scoped suppression entries live in the suppression domain; the callback runs
        // their cascade inside the tenant lock, before the associations and the tenant record.
        tenantService.deleteTenant(tenantName, region,
                tenant -> suppressionService.deleteAllForTenant(region, tenant.tenantId()));
    }

    // The association operations validate resource existence here in the facade — the tenant domain
    // owns the association store, but only this class can reach the identity/configuration-set/template
    // stores without a service→service dependency.

    public void createTenantResourceAssociation(String tenantName, String resourceArn,
                                                String accountId, String region) {
        SesTenantService.AssociationResource ref =
                SesTenantService.parseResourceArn(resourceArn, accountId, region);
        Tenant tenant = tenantService.tenantForAssociation(tenantName, region);
        // The existence check runs inside the association lock so it stays atomic with the
        // backing-resource delete guards.
        tenantService.associate(tenant, ref, region, () -> requireTenantResourceExists(ref, region));
    }

    public void deleteTenantResourceAssociation(String tenantName, String resourceArn,
                                                String accountId, String region) {
        SesTenantService.AssociationResource ref =
                SesTenantService.parseResourceArn(resourceArn, accountId, region);
        Tenant tenant = tenantService.tenantForAssociation(tenantName, region);
        // AWS still 404s on a missing resource even though removing a missing association succeeds.
        requireTenantResourceExists(ref, region);
        tenantService.disassociate(tenant, ref, region);
    }

    public List<TenantResourceAssociation> listTenantResources(String tenantName,
                                                               String resourceTypeFilter,
                                                               Integer pageSize, String nextToken,
                                                               String region) {
        SesTenantService.validateListPaging(pageSize, nextToken);
        SesTenantService.validateResourceTypeFilter(resourceTypeFilter);
        Tenant tenant = tenantService.tenantForAssociation(tenantName, region);
        return tenantService.listTenantResources(tenant, resourceTypeFilter, region);
    }

    public List<TenantResourceAssociation> listResourceTenants(String resourceArn, Integer pageSize,
                                                               String nextToken, String accountId,
                                                               String region) {
        SesTenantService.validateListPaging(pageSize, nextToken);
        SesTenantService.AssociationResource ref =
                SesTenantService.parseResourceArn(resourceArn, accountId, region);
        requireTenantResourceExists(ref, region);
        return tenantService.listResourceTenants(ref, region);
    }

    /**
     * The tenant send gate (Phase 4): resolves the tenant with the send-flavored not-found wording,
     * then requires every resource the send uses to be associated with it. The tenant's
     * SendingStatus is not checked — no API can move it off ENABLED, so the DISABLED gate is not
     * emulated. Placement is probe-confirmed: request-shape validation (a missing Content, an empty
     * inline template, a missing FromEmailAddress on Simple, the bulk template-content checks) runs
     * before the tenant lookup, while the recipient checks, the raw MIME-From derivation, and
     * identity verification all lose to the tenant 404.
     */
    public void checkTenantSendAccess(String tenantName, String fromEmailAddress,
                                      String configurationSetName, String templateName,
                                      String accountId, String region) {
        if (tenantName == null) {
            return;
        }
        Tenant tenant = tenantService.tenantForSending(tenantName, region, accountId);
        String arnPrefix = "arn:aws:ses:" + region + ":" + accountId + ":";
        List<SesTenantService.AssociationResource> used = new ArrayList<>();
        if (fromEmailAddress != null && !fromEmailAddress.isBlank()) {
            String identityName = sendIdentityName(fromEmailAddress, region);
            used.add(new SesTenantService.AssociationResource(
                    SesTenantService.RESOURCE_TYPE_IDENTITY, identityName,
                    arnPrefix + "identity/" + identityName));
        }
        // The gate covers the EFFECTIVE configuration set: an omitted name resolves to the sender
        // identity's default, and that one needs the association just as an explicit one does.
        String effectiveConfigSet =
                resolveDefaultConfigurationSet(configurationSetName, fromEmailAddress, region);
        if (effectiveConfigSet != null && !effectiveConfigSet.isBlank()) {
            used.add(new SesTenantService.AssociationResource(
                    SesTenantService.RESOURCE_TYPE_CONFIGURATION_SET, effectiveConfigSet,
                    arnPrefix + "configuration-set/" + effectiveConfigSet));
        }
        if (templateName != null && !templateName.isBlank()) {
            used.add(new SesTenantService.AssociationResource(
                    SesTenantService.RESOURCE_TYPE_TEMPLATE, templateName,
                    arnPrefix + "template/" + templateName));
        }
        tenantService.requireResourcesAssociated(tenant, used, region);
    }

    /**
     * The raw-content variant of the tenant send gate: when {@code FromEmailAddress} is omitted,
     * the effective sender comes from the MIME {@code From} header — the same derivation
     * {@code sendRawEmail} applies — so the gate must resolve it the same way before checking.
     */
    public void checkTenantRawSendAccess(String tenantName, String fromEmailAddress,
                                         String rawMessage, String configurationSetName,
                                         String accountId, String region) {
        if (tenantName == null) {
            return;
        }
        String effectiveSource = fromEmailAddress;
        if (effectiveSource == null || effectiveSource.isBlank()) {
            SmtpRelay.RawMessageHeaders headers = SmtpRelay.parseRawHeaders(rawMessage);
            effectiveSource = headers != null && !headers.from().isBlank() ? headers.from() : null;
        }
        checkTenantSendAccess(tenantName, effectiveSource, configurationSetName, null,
                accountId, region);
    }

    // The gate names the identity the way AWS did in the probed error: the exact address identity
    // when one exists, otherwise the domain identity the address falls under. The sender may carry
    // display-name syntax ("Name <a@b>"), so the bare address is extracted first.
    private String sendIdentityName(String fromEmailAddress, String region) {
        String email = extractEmailAddress(fromEmailAddress);
        if (email.isBlank()) {
            return fromEmailAddress.trim();
        }
        if (identityService.find(email, region).isPresent()) {
            return email;
        }
        int at = email.lastIndexOf('@');
        if (at >= 0) {
            String domain = email.substring(at + 1);
            if (identityService.find(domain, region).isPresent()) {
                return domain;
            }
        }
        return email;
    }

    // The association APIs 404 with a per-type message when the referenced resource is missing; the
    // trailing colon on the configuration-set variant is AWS's own.
    private void requireTenantResourceExists(SesTenantService.AssociationResource ref, String region) {
        boolean exists = switch (ref.type()) {
            case SesTenantService.RESOURCE_TYPE_IDENTITY ->
                    identityService.find(ref.name(), region).isPresent();
            case SesTenantService.RESOURCE_TYPE_CONFIGURATION_SET ->
                    SesConfigurationSetService.isValidName(ref.name())
                            && configSetService.find(ref.name(), region).isPresent();
            case SesTenantService.RESOURCE_TYPE_TEMPLATE ->
                    templateService.find(ref.name(), region).isPresent();
            default -> false;
        };
        if (exists) {
            return;
        }
        String message = switch (ref.type()) {
            case SesTenantService.RESOURCE_TYPE_IDENTITY ->
                    "Identity <" + ref.name() + "> does not exist";
            case SesTenantService.RESOURCE_TYPE_CONFIGURATION_SET ->
                    "Configuration set <" + ref.name() + "> does not exist:";
            default -> "Email template <" + ref.name() + "> does not exist";
        };
        throw new AwsException("NotFoundException", message, 404);
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

    public DedicatedIpPool createDedicatedIpPool(String poolName, String scalingMode, List<Tag> tags,
                                                 String region) {
        return dedicatedIpService.createDedicatedIpPool(poolName, scalingMode, tags, region);
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
        if (identityService.find(identity, region).isEmpty()) {
            throw new AwsException("NotFoundException",
                    "Email identity <" + identity + "> does not exist.", 404);
        }
    }



    public void createConfigurationSetEventDestination(String configSetName, String eventDestinationName,
                                                       EventDestination dest, String region) {
        configSetService.createEventDestination(configSetName, eventDestinationName, dest, region);
    }

    public List<EventDestination> getConfigurationSetEventDestinations(String configSetName, String region) {
        return configSetService.getEventDestinations(configSetName, region);
    }

    public void updateConfigurationSetEventDestination(String configSetName, String eventDestinationName,
                                                       EventDestination dest, String region) {
        configSetService.updateEventDestination(configSetName, eventDestinationName, dest, region);
    }

    public void deleteConfigurationSetEventDestination(String configSetName, String eventDestinationName,
                                                       String region) {
        configSetService.deleteEventDestination(configSetName, eventDestinationName, region);
    }

    public void putConfigurationSetSuppressionOptions(String configSetName,
                                                      List<String> reasons, String region) {
        configSetService.putSuppressionOptions(configSetName, reasons, region);
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


    public List<Tag> listResourceTags(String arn, String region) {
        ResourceRef ref = parseSesArn(arn);
        requireCallerAccount(ref);
        List<Tag> tags = switch (ref.type()) {
            case "configuration-set" -> listConfigurationSetTags(ref.name(), region);
            case "template" -> listEmailTemplateTags(ref.name(), region);
            case "identity" -> identityService.listTags(ref.name(), region);
            case "contact-list" -> contactService.listTags(ref.name(), region);
            case "custom-verification-email-template" -> cvetService.listTags(ref.name(), region);
            case "dedicated-ip-pool" -> dedicatedIpService.listTags(ref.name(), region);
            case "tenant" -> listTenantTags(ref.name(), region);
            default -> throw new AwsException("NotFoundException",
                    "Resource " + arn + " was not found.", 404);
        };
        // AWS checks existence against the signing region but keys the tag store by the literal
        // ARN: a mismatched ARN region passes the existence check above yet addresses an ARN
        // nothing was ever tagged under, so the result is empty (probe-confirmed across all six
        // resource types).
        if (!ref.region().equals(region)) {
            return List.of();
        }
        return tags;
    }

    public void tagResource(String arn, String region, List<Tag> newTags) {
        ResourceRef ref = parseSesArn(arn);
        requireCallerAccount(ref);
        if (!ref.region().equals(region)) {
            throw new AwsException("BadRequestException", "Failed to tag resource", 400);
        }
        // An empty Tags list is not an error: AWS still runs the account, region, and existence
        // checks and then applies the empty merge as a no-op (probe-confirmed).
        List<Tag> tags = newTags == null ? List.of() : newTags;
        SesTags.validate(tags);
        switch (ref.type()) {
            case "configuration-set" -> tagConfigurationSet(ref.name(), region, tags);
            case "template" -> tagEmailTemplate(ref.name(), region, tags);
            case "identity" -> identityService.tag(ref.name(), region, tags);
            case "contact-list" -> contactService.tag(ref.name(), region, tags);
            case "custom-verification-email-template" -> cvetService.tag(ref.name(), region, tags);
            case "dedicated-ip-pool" -> dedicatedIpService.tag(ref.name(), region, tags);
            case "tenant" -> tagTenant(ref.name(), region, tags);
            default -> throw new AwsException("NotFoundException",
                    "Resource " + arn + " was not found.", 404);
        }
    }

    public void untagResource(String arn, String region, List<String> tagKeys) {
        ResourceRef ref = parseSesArn(arn);
        requireCallerAccount(ref);
        if (tagKeys == null || tagKeys.isEmpty()) {
            // AWS rejects a missing/empty TagKeys member with a message-less ValidationException
            // (probe-confirmed: only the error-type header, empty body), after the account guard
            // and before the region guard. The null message is deliberate — it surfaces through
            // Floci's standard error body as "message":null, which restJson1 SDKs parse the same
            // way as AWS's empty body since they read x-amzn-errortype first.
            throw new AwsException("ValidationException", null, 400);
        }
        if (!ref.region().equals(region)) {
            throw new AwsException("BadRequestException", "Failed to untag resource", 400);
        }
        switch (ref.type()) {
            case "configuration-set" -> untagConfigurationSet(ref.name(), region, tagKeys);
            case "template" -> untagEmailTemplate(ref.name(), region, tagKeys);
            case "identity" -> identityService.untag(ref.name(), region, tagKeys);
            case "contact-list" -> contactService.untag(ref.name(), region, tagKeys);
            case "custom-verification-email-template" -> cvetService.untag(ref.name(), region, tagKeys);
            case "dedicated-ip-pool" -> dedicatedIpService.untag(ref.name(), region, tagKeys);
            case "tenant" -> untagTenant(ref.name(), region, tagKeys);
            default -> throw new AwsException("NotFoundException",
                    "Resource " + arn + " was not found.", 404);
        }
    }

    // A tenant tag ARN carries two path segments (tenant/<name>/<tenantId>), so parseSesArn's
    // first-slash split leaves "<name>/<tenantId>" as the resource name; the tenant domain owns
    // that remainder's decomposition and the id-only resolution (see SesTenantService.TenantTagArn).

    private List<Tag> listTenantTags(String resourceName, String region) {
        Tenant tenant = tenantService.tenantForTagArn(resourceName, region);
        // AWS returns a tenant's tags ordered by key (probe-confirmed).
        return (tenant.tags() == null ? List.<Tag>of() : tenant.tags()).stream()
                .sorted(Comparator.comparing(Tag::key, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private void tagTenant(String resourceName, String region, List<Tag> newTags) {
        tenantService.mutateTags(resourceName, region, tags -> SesTags.merge(tags, newTags));
        LOG.infov("Tagged SES tenant <{0}> (region {1}, +{2} tags)",
                resourceName, region, newTags.size());
    }

    private void untagTenant(String resourceName, String region, List<String> tagKeys) {
        Set<String> toRemove = new HashSet<>(tagKeys);
        tenantService.mutateTags(resourceName, region, tags -> {
            List<Tag> remaining = new ArrayList<>(tags);
            remaining.removeIf(t -> toRemove.contains(t.key()));
            return remaining;
        });
        LOG.infov("Untagged SES tenant <{0}> (region {1}, -{2} keys)",
                resourceName, region, tagKeys.size());
    }

    private List<Tag> listConfigurationSetTags(String name, String region) {
        ConfigurationSet cs = configSetService.find(name, region)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "No ConfigurationSet present with name: " + name, 404));
        return new ArrayList<>(cs.getTags());
    }

    private void tagConfigurationSet(String name, String region, List<Tag> newTags) {
        ConfigurationSet cs = configSetService.find(name, region)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "No ConfigurationSet present with name: " + name, 404));
        cs.setTags(SesTags.merge(cs.getTags(), newTags));
        configSetService.save(cs, region);
        LOG.infov("Tagged SES configuration set: {0} (region {1}, +{2} tags)", name, region, newTags.size());
    }

    private void untagConfigurationSet(String name, String region, List<String> tagKeys) {
        ConfigurationSet cs = configSetService.find(name, region)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "No ConfigurationSet present with name: " + name, 404));
        Set<String> toRemove = new HashSet<>(tagKeys);
        // Copy-on-write: the stored list may be immutable, and unlocked readers iterate it.
        List<Tag> remaining = new ArrayList<>(cs.getTags());
        remaining.removeIf(t -> toRemove.contains(t.key()));
        cs.setTags(remaining);
        configSetService.save(cs, region);
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
        template.setTags(SesTags.merge(template.getTags(), newTags));
        templateService.save(template, region);
        LOG.infov("Tagged SES template: {0} (region {1}, +{2} tags)", name, region, newTags.size());
    }

    public void setIdentityTags(String identityValue, String region, List<Tag> tags) {
        identityService.setTags(identityValue, region, tags);
    }

    private void untagEmailTemplate(String name, String region, List<String> tagKeys) {
        EmailTemplate template = templateService.find(name, region)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "No Template present with name: " + name, 404));
        Set<String> toRemove = new HashSet<>(tagKeys);
        // Copy-on-write: the stored list may be immutable, and unlocked readers iterate it.
        List<Tag> remaining = new ArrayList<>(template.getTags());
        remaining.removeIf(t -> toRemove.contains(t.key()));
        template.setTags(remaining);
        templateService.save(template, region);
        LOG.infov("Untagged SES template: {0} (region {1}, -{2} keys)", name, region, tagKeys.size());
    }

    private record ResourceRef(String account, String region, String type, String name) {}

    /**
     * AWS rejects a tag operation whose ARN carries a different account id before any region or
     * existence check (probe-confirmed): the account error wins even when the region is also
     * mismatched or the resource doesn't exist anywhere.
     */
    private void requireCallerAccount(ResourceRef ref) {
        String callerAccountId = regionResolver != null ? regionResolver.getAccountId() : defaultAccountId;
        if (!ref.account().equals(callerAccountId)) {
            throw new AwsException("BadRequestException",
                    "Operations on a resource created in a different account is not allowed", 400);
        }
    }

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
        return new ResourceRef(parsed.accountId(), parsed.region(),
                resource.substring(0, slash), resource.substring(slash + 1));
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

    // A TenantName routes each suppression-list operation to that tenant's own list (fully separate
    // from the account list on AWS); the reason/address validation still runs first, matching the
    // probed precedence where request validation precedes tenant existence.

    public void putSuppressedDestination(String region, String emailAddress, String reason) {
        putSuppressedDestination(region, emailAddress, reason, null);
    }

    public SuppressedDestination getSuppressedDestination(String region, String emailAddress) {
        return getSuppressedDestination(region, emailAddress, null);
    }

    public void deleteSuppressedDestination(String region, String emailAddress) {
        deleteSuppressedDestination(region, emailAddress, null);
    }

    public List<SuppressedDestination> listSuppressedDestinations(String region,
                                                                  List<String> reasonFilters) {
        return listSuppressedDestinations(region, reasonFilters, null);
    }

    public void putSuppressedDestination(String region, String emailAddress, String reason,
                                         String tenantName) {
        if (tenantName == null) {
            suppressionService.putSuppressedDestination(region, emailAddress, reason);
            return;
        }
        // The address and reason are validated before the tenant is resolved, keeping request
        // validation ahead of tenant existence for every member, as on the attribute operations.
        SesSuppressionService.normalizeSuppressionEmail(emailAddress);
        SesSuppressionService.validateSuppressionReason(reason, "reason", false);
        tenantService.runWithTenant(tenantName, region, tenant -> {
            suppressionService.putTenantSuppressedDestination(region, tenant.tenantId(),
                    tenantName, emailAddress, reason);
            return null;
        });
    }

    public SuppressedDestination getSuppressedDestination(String region, String emailAddress,
                                                          String tenantName) {
        if (tenantName == null) {
            return suppressionService.getSuppressedDestination(region, emailAddress);
        }
        SesSuppressionService.normalizeSuppressionEmail(emailAddress);
        return tenantService.runWithTenant(tenantName, region, tenant ->
                suppressionService.getTenantSuppressedDestination(region, tenant.tenantId(),
                        emailAddress));
    }

    public void deleteSuppressedDestination(String region, String emailAddress, String tenantName) {
        if (tenantName == null) {
            suppressionService.deleteSuppressedDestination(region, emailAddress);
            return;
        }
        SesSuppressionService.normalizeSuppressionEmail(emailAddress);
        tenantService.runWithTenant(tenantName, region, tenant -> {
            suppressionService.deleteTenantSuppressedDestination(region, tenant.tenantId(),
                    emailAddress);
            return null;
        });
    }

    public List<SuppressedDestination> listSuppressedDestinations(String region,
                                                                  List<String> reasonFilters,
                                                                  String tenantName) {
        if (tenantName == null) {
            return suppressionService.listSuppressedDestinations(region, reasonFilters);
        }
        SesSuppressionService.validateReasonFilters(reasonFilters);
        return tenantService.runWithTenant(tenantName, region, tenant ->
                suppressionService.listTenantSuppressedDestinations(region, tenant.tenantId(),
                        reasonFilters));
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
     * and the account-level / per-CS {@code suppressedReasons} go through reason validation
     * (in {@link SesSuppressionService} and {@link SesConfigurationSetService} respectively),
     * which enforces exact case-sensitive equality with the two canonical values, so
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
        return templateService.renderTestTemplate(templateName, templateDataRaw, region);
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

    /**
     * Also called by the controller ahead of the tenant send gate: AWS reports an empty inline
     * template before it looks the tenant up (probe-confirmed), so the check must not stay behind
     * the gate for tenant sends.
     */
    static void requireInlineTemplateContent(String subject, String textPart, String htmlPart) {
        boolean hasSubject = subject != null && !subject.isBlank();
        boolean hasText = textPart != null && !textPart.isBlank();
        boolean hasHtml = htmlPart != null && !htmlPart.isBlank();
        if (!hasSubject && !hasText && !hasHtml) {
            throw new AwsException("InvalidTemplate",
                    "Template must have at least a subject, text, or html part.", 400);
        }
    }

    public String sendInlineTemplatedEmail(String source, List<String> toAddresses, List<String> ccAddresses,
                                            List<String> bccAddresses, List<String> replyToAddresses,
                                            String subject, String textPart, String htmlPart,
                                            JsonNode templateData,
                                            String configurationSetName, List<MessageTag> emailTags,
                                            List<MessageHeader> additionalHeaders,
                                            ListManagementOptions listManagement, String region) {
        requireInlineTemplateContent(subject, textPart, htmlPart);
        return sendEmail(source, toAddresses, ccAddresses, bccAddresses, replyToAddresses,
                SesTemplateService.applyTemplateData(subject, templateData),
                SesTemplateService.applyTemplateData(textPart, templateData),
                SesTemplateService.applyTemplateData(htmlPart, templateData),
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
        requireInlineTemplateContent(subject, textPart, htmlPart);
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
                        SesTemplateService.applyTemplateData(subject, merged),
                        SesTemplateService.applyTemplateData(textPart, merged),
                        SesTemplateService.applyTemplateData(htmlPart, merged),
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

}
