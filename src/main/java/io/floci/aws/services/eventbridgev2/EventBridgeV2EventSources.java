package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.TlsCertificateManager;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.eventbridge.model.Target;
import io.floci.aws.services.eventbridgev2.model.EventBusV2;
import io.floci.aws.services.eventbridgev2.model.EventSourceV2;
import io.floci.aws.services.eventbridgev2.model.RotationRequest;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.secretsmanager.model.Secret;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * The webhook event sources of EventBridge V2: their storage, the secret each one keeps its credential in and the
 * URL it is reached at. The event bus a source belongs to is the bus service's, so each call that needs one is
 * given the lookup.
 */
@ApplicationScoped
public class EventBridgeV2EventSources implements Resettable {
    private static final String OWNING_SERVICE = "events-webhook";
    private static final String EVENTS_PRINCIPAL = ServicePrincipals.of("events");
    private static final String CREATE_FAILED = "CREATE_FAILED";
    private static final String UPDATE_FAILED = "UPDATE_FAILED";
    private static final String WEBHOOK_HOST_LABELS = "c01.events-webhook.";

    private static final Logger LOG = Logger.getLogger(EventBridgeV2EventSources.class);
    private static final int GENERATED_SECRET_BYTES = 32;
    private static final String TOKEN_NAMESPACE = "event-sourcev2";
    private static final Duration ROTATION_TOKEN_MEMORY = Duration.ofHours(8);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> ROTATION_MEMBERS = Map.of(
            WebhookEventsConfigurations.STATIC_TOKEN, "StaticTokenSecretValue",
            WebhookEventsConfigurations.BASIC, "BasicHttpPassword",
            WebhookEventsConfigurations.HMAC, "HmacSecretValue");

    private final AccountAwareStorageBackend<EventSourceV2> sources;
    private final SecretsManagerService secrets;
    private final RegionResolver regionResolver;
    private final Clock clock;
    private final URI baseUrl;
    private final SecureRandom random = new SecureRandom();
    private final WebhookDeduplication deduplication;
    private final TlsCertificateManager certificateManager;
    private final EventBridgeService eventBridge;

    @Inject
    public EventBridgeV2EventSources(StorageFactory storageFactory, SecretsManagerService secrets,
                                     RegionResolver regionResolver, EmulatorConfig config,
                                     TlsCertificateManager certificateManager, EventBridgeService eventBridge) {
        this(storageFactory.create("eventbridgev2", "eventbridgev2-event-sources.json",
                        new TypeReference<Map<String, EventSourceV2>>() {}),
                secrets, regionResolver, Clock.systemUTC(), config.effectiveBaseUrl(), certificateManager,
                eventBridge);
    }

    EventBridgeV2EventSources(AccountAwareStorageBackend<EventSourceV2> sources, SecretsManagerService secrets,
                              RegionResolver regionResolver, Clock clock, String baseUrl,
                              TlsCertificateManager certificateManager, EventBridgeService eventBridge) {
        this.sources = sources;
        this.secrets = secrets;
        this.regionResolver = regionResolver;
        this.clock = clock;
        this.baseUrl = URI.create(baseUrl);
        this.certificateManager = certificateManager;
        this.eventBridge = eventBridge;
        this.deduplication = new WebhookDeduplication(clock);
    }

    /**
     * Creates an event source on a bus of the caller's. A webhook source is {@code ACTIVE} at once, with the secret
     * that holds its credential. A retry with the {@code ClientToken} of an earlier create returns that source when
     * it repeats the request, and fails when it changes it.
     */
    EventSourceV2 create(JsonNode request, String region, Function<String, Optional<EventBusV2>> findBusByName) {
        EventBridgeV2RequestShapes.check("CreateEventSource", request);
        String name = request.get("Name").textValue();
        String eventBusArn = request.get("EventBusArn").textValue();
        JsonNode configuration = request.get("Configuration");
        String roleArn = configuration.path(WebhookEventsConfigurations.WEBHOOK).path("RoleArn").asText(null);

        requireEndpointRegion(eventBusArn, region, "EventBusArn");
        requireOwnAccount(eventBusArn);
        if (roleArn != null) {
            requireOwnAccount(roleArn);
        }
        String clientToken = request.path("ClientToken").textValue();
        if (clientToken == null) {
            throw EventBridgeV2Service.invalidInput("ClientToken is required");
        }
        Map.Entry<String, JsonNode> variant = WebhookEventsConfigurations.onlyVariant(configuration);
        if (ServiceEventsConfigurations.PARTNER.equals(variant.getKey())) {
            checkPartnerSource(variant.getValue(), region);
            requireBus(eventBusArn, findBusByName);
            throw ServiceEventsConfigurations.partnerSourceMissing(variant.getValue());
        }
        if (ServiceEventsConfigurations.AWS_SERVICE.equals(variant.getKey())) {
            return createServiceSource(request, region, clientToken, variant.getValue(), findBusByName);
        }
        JsonNode webhook = WebhookEventsConfigurations.validateForCreate(configuration);

        // The request carries credentials, so only its hash is kept.
        String requestHash = Digests.sha256Hex(EventBridgeV2Service.withoutClientToken(request));
        Optional<EventSourceV2> retried = retriedSource(region, clientToken, requestHash);
        if (retried.isPresent()) {
            return retried.get();
        }
        requireBus(eventBusArn, findBusByName);
        String key = EventBridgeV2Service.storageKey(region, name);
        if (sources.get(key).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException", "Resource already exists", 409);
        }

        String hostId = WebhookTokens.newHostId(random);
        Instant now = clock.instant();
        EventSourceV2 source = new EventSourceV2();
        source.setName(name);
        source.setArn(sourceArn(region, "webhook", name));
        source.setEventBusArn(eventBusArn);
        source.setDescription(request.path("Description").textValue());
        source.setConfiguration(WebhookEventsConfigurations.echo(webhook));
        source.setState(EventBridgeV2Service.ACTIVE);
        source.setCreationTime(now);
        source.setLastModifiedTime(now);
        source.setTags(tags(request.get("Tags")));
        source.setClientToken(clientToken);
        source.setCreateRequestHash(requestHash);
        source.setWebhookHostId(hostId);
        source.setWebhookToken(WebhookTokens.newToken(hostId, random));
        source.setSecretArn(createSecret(webhook, region));
        try {
            sources.put(key, source);
        } catch (RuntimeException e) {
            deleteOrphanedSecret(source.getSecretArn(), region);
            throw e;
        }
        return source;
    }

    private Optional<EventSourceV2> retriedSource(String region, String clientToken, String requestHash) {
        return EventBridgeV2Service.retried(() -> inRegion(region),
                clientToken, requestHash, EventSourceV2::getClientToken, EventSourceV2::getCreateRequestHash,
                () -> new AwsException("IdempotentParameterMismatchException",
                        "ClientToken was used by a previous request with different parameters", 409));
    }

    private String sourceArn(String region, String kind, String name) {
        return regionResolver.buildArn("events", region,
                "event-sourcev2/" + kind + "/" + name + "/" + EventBridgeV2Service.newId(random));
    }

    private void checkPartnerSource(JsonNode partner, String region) {
        ServiceEventsConfigurations.checkPattern(partner, false);
        ServiceEventsConfigurations.checkOnFailure(partner, region, regionResolver.getAccountId());
        ServiceEventsConfigurations.checkPartnerRegion(partner, region);
    }

    private void checkServiceSource(JsonNode service, String region) {
        ServiceEventsConfigurations.checkPattern(service, true);
        ServiceEventsConfigurations.checkOnFailure(service, region, regionResolver.getAccountId());
    }

    /**
     * Creates a source of AWS service events. A pattern the forwarding rule cannot use still leaves the source
     * behind, as {@code CREATE_FAILED}, with no rule.
     */
    private EventSourceV2 createServiceSource(JsonNode request, String region, String clientToken, JsonNode service,
                                              Function<String, Optional<EventBusV2>> findBusByName) {
        String eventBusArn = request.get("EventBusArn").textValue();
        checkServiceSource(service, region);
        String requestHash = Digests.sha256Hex(EventBridgeV2Service.withoutClientToken(request));
        Optional<EventSourceV2> retried = retriedSource(region, clientToken, requestHash);
        if (retried.isPresent()) {
            if (CREATE_FAILED.equals(retried.get().getState())) {
                throw ServiceEventsConfigurations.provisioningFailed();
            }
            return retried.get();
        }
        requireBus(eventBusArn, findBusByName);
        String name = request.get("Name").textValue();
        String key = EventBridgeV2Service.storageKey(region, name);
        if (sources.get(key).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException", "Resource already exists", 409);
        }

        Instant now = clock.instant();
        EventSourceV2 source = new EventSourceV2();
        source.setName(name);
        source.setArn(sourceArn(region, "aws.service", name));
        source.setEventBusArn(eventBusArn);
        source.setDescription(request.path("Description").textValue());
        source.setConfiguration(ServiceEventsConfigurations.echo(service));
        source.setCreationTime(now);
        source.setLastModifiedTime(now);
        source.setTags(tags(request.get("Tags")));
        source.setClientToken(clientToken);
        source.setCreateRequestHash(requestHash);
        boolean provisioned = provisionRule(source, service, region);
        source.setState(provisioned ? EventBridgeV2Service.ACTIVE : CREATE_FAILED);
        try {
            sources.put(key, source);
        } catch (RuntimeException e) {
            if (provisioned) {
                eventBridge.deleteManagedRule(id(source), region);
            }
            throw e;
        }
        if (!provisioned) {
            throw ServiceEventsConfigurations.provisioningFailed();
        }
        return source;
    }

    /**
     * Puts the rule on the default bus that forwards the service's events to the source's bus. False when the
     * source's own pattern is not one an event rule can use, and then no rule is touched.
     */
    private boolean provisionRule(EventSourceV2 source, JsonNode service, String region) {
        if (ServiceEventsConfigurations.givesPattern(service)) {
            JsonNode own = ServiceEventsConfigurations.ownPattern(service);
            if (!ServiceEventsConfigurations.isUsablePattern(own) || !acceptedByEventBridge(own)) {
                return false;
            }
        }
        String ruleName = id(source);
        Target target = new Target(ruleName, source.getEventBusArn(), null, null);
        String deadLetterQueue = service.path("OnFailureConfiguration").path("Arn").textValue();
        if (deadLetterQueue != null) {
            target.setDeadLetterConfig(new Target.DeadLetterConfig(deadLetterQueue));
        }
        eventBridge.putManagedRule(ruleName, EVENTS_PRINCIPAL,
                ServiceEventsConfigurations.forwardingPattern(service, regionResolver.getAccountId(), region),
                target, region);
        return true;
    }

    private boolean acceptedByEventBridge(JsonNode pattern) {
        try {
            eventBridge.validateEventPattern(pattern.toString());
            return true;
        } catch (AwsException e) {
            if (!"InvalidEventPatternException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("The pattern of an event source was refused: {0}", e.getMessage());
            return false;
        }
    }

    /**
     * Replaces the description the request gives and the whole webhook configuration. The credentials, the URL and
     * the secret stay as they are. A refused update changes nothing.
     */
    EventSourceV2 update(JsonNode request, String region, Function<String, Optional<EventBusV2>> findBusByName) {
        EventBridgeV2RequestShapes.check("UpdateEventSource", request);
        String arn = request.get("EventSourceArn").textValue();
        checkArn(arn, region);
        JsonNode configuration = request.hasNonNull("Configuration") ? request.get("Configuration") : null;
        String roleArn = configuration == null ? null
                : configuration.path(WebhookEventsConfigurations.WEBHOOK).path("RoleArn").asText(null);
        if (roleArn != null) {
            requireOwnAccount(roleArn);
        }
        EventSourceV2 source = require(arn, region);
        if (ServiceEventsConfigurations.isAwsService(source)) {
            return updateServiceSource(request, region, source, configuration, findBusByName);
        }
        JsonNode webhook = configuration == null ? null : WebhookEventsConfigurations.validateForUpdate(
                configuration, WebhookEventsConfigurations.authType(
                        source.getConfiguration().get(WebhookEventsConfigurations.WEBHOOK)));

        requireNotRevoked(source, "EventSource is revoked and cannot be updated");

        EventSourceV2 changed = copyOf(source);
        changed.setDescription(request.hasNonNull("Description") ? request.get("Description").textValue()
                : source.getDescription());
        changed.setConfiguration(webhook == null ? source.getConfiguration()
                : WebhookEventsConfigurations.echo(webhook));
        changed.setLastModifiedTime(clock.instant());
        sources.put(EventBridgeV2Service.storageKey(region, source.getName()), changed);
        return changed;
    }

    /**
     * Replaces the description and the whole configuration of a source of AWS service events, and the forwarding
     * rule with it. A pattern the rule cannot use still stores the new configuration, as {@code UPDATE_FAILED},
     * and leaves the rule as it was.
     */
    private EventSourceV2 updateServiceSource(JsonNode request, String region, EventSourceV2 source,
                                              JsonNode configuration,
                                              Function<String, Optional<EventBusV2>> findBusByName) {
        JsonNode service = null;
        if (configuration != null) {
            Map.Entry<String, JsonNode> variant = WebhookEventsConfigurations.onlyVariant(configuration);
            if (!ServiceEventsConfigurations.AWS_SERVICE.equals(variant.getKey())) {
                throw WebhookEventsConfigurations.variantImmutable(ServiceEventsConfigurations.AWS_SERVICE_TYPE);
            }
            service = variant.getValue();
            checkServiceSource(service, region);
        }
        requireNotRevoked(source, "EventSource is revoked and cannot be updated");
        requireBus(source.getEventBusArn(), findBusByName);

        EventSourceV2 changed = copyOf(source);
        changed.setDescription(request.hasNonNull("Description") ? request.get("Description").textValue()
                : source.getDescription());
        changed.setLastModifiedTime(clock.instant());
        boolean provisioned = true;
        if (service != null) {
            changed.setConfiguration(ServiceEventsConfigurations.echo(service));
            provisioned = provisionRule(changed, service, region);
            changed.setState(provisioned ? EventBridgeV2Service.ACTIVE : UPDATE_FAILED);
        }
        sources.put(EventBridgeV2Service.storageKey(region, source.getName()), changed);
        if (!provisioned) {
            throw ServiceEventsConfigurations.provisioningFailed();
        }
        return changed;
    }

    /**
     * Replaces the credential in the source's secret, with the one the request gives or a generated one, and
     * returns the secret's ARN. The URL and the secret stay, and so does the time the source was modified. A
     * retry within 8 hours with a {@code ClientToken} of the source returns the ARN and changes nothing when it
     * repeats the request, and fails when it changes it.
     */
    String rotateCredentials(JsonNode request, String region) {
        EventBridgeV2RequestShapes.check("RotateWebhookCredentials", request);
        String arn = request.get("EventSourceArn").textValue();
        checkArn(arn, region);
        String clientToken = request.path("ClientToken").textValue();
        if (clientToken == null) {
            throw EventBridgeV2Service.invalidInput("ClientToken is required");
        }
        JsonNode credential = request.hasNonNull("Credential") ? request.get("Credential") : null;
        String given = credential == null ? null : credentialMember(credential);
        EventSourceV2 source = require(arn, region);
        JsonNode webhook = source.getConfiguration().path(WebhookEventsConfigurations.WEBHOOK);
        String authType = WebhookEventsConfigurations.authType(webhook);
        String member = ROTATION_MEMBERS.get(authType);
        if (given != null && !given.equals(member)) {
            throw EventBridgeV2Service.invalidInput("Credential must set " + member
                    + ", because the authentication type of this EventSource is " + authType);
        }
        String value = given == null ? null : credential.get(given).textValue();
        if (WebhookEventsConfigurations.HMAC.equals(authType) && value != null) {
            value = WebhookEventsConfigurations.rotatedHmacSecret(value,
                    webhook.path("AuthConfiguration").path(WebhookEventsConfigurations.HMAC));
        }

        String requestHash = Digests.sha256Hex(EventBridgeV2Service.withoutClientToken(request));
        Instant now = clock.instant();
        RotationRequest seen = source.getRotationRequests().get(clientToken);
        if (seen != null && remembered(seen, now)) {
            if (!seen.requestHash().equals(requestHash)) {
                throw new AwsException("IdempotentParameterMismatchException",
                        "ClientToken was used by a previous request with different parameters", 409);
            }
            return source.getSecretArn();
        }
        requireNotRevoked(source, "EventSource is revoked and its credentials cannot be rotated");
        requireSecretUsable(source, region);

        String current = secrets.getSecretValue(source.getSecretArn(), null, null, region).getSecretString();
        String replacement = WebhookEventsConfigurations.BASIC.equals(authType)
                ? basicSecret(usernameOf(current), value != null ? value : generated())
                : value != null ? value : generated();
        if (!replacement.equals(current)) {
            secrets.rotateManagedSecretAfter(source.getSecretArn(), OWNING_SERVICE, region, () -> replacement,
                    () -> { });
        }
        EventSourceV2 changed = copyOf(source);
        changed.getRotationRequests().values().removeIf(entry -> !remembered(entry, now));
        changed.getRotationRequests().put(clientToken, new RotationRequest(requestHash, now));
        sources.put(EventBridgeV2Service.storageKey(region, source.getName()), changed);
        return changed.getSecretArn();
    }

    private static boolean remembered(RotationRequest request, Instant now) {
        return now.isBefore(request.rotatedAt().plus(ROTATION_TOKEN_MEMORY));
    }

    private void requireSecretUsable(EventSourceV2 source, String region) {
        boolean unusable;
        try {
            unusable = secrets.describeSecret(source.getSecretArn(), region).getDeletedDate() != null;
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            unusable = true;
        }
        if (unusable) {
            throw new AwsException("InvalidStateException",
                    "The secret of the EventSource is scheduled for deletion or has been deleted", 409);
        }
    }

    /** The one member of {@code Credential} that is set, or an error when the request sets none or several. */
    private static String credentialMember(JsonNode credential) {
        List<String> set = ROTATION_MEMBERS.values().stream().filter(credential::hasNonNull).toList();
        if (set.size() != 1) {
            throw EventBridgeV2Service.invalidInput("Credential must set exactly one of StaticTokenSecretValue, "
                    + "BasicHttpPassword or HmacSecretValue");
        }
        return set.getFirst();
    }

    private static String usernameOf(String basicSecret) {
        try {
            return JSON.readTree(basicSecret).path("username").textValue();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The secret of a Basic webhook event source is not JSON", e);
        }
    }

    private static EventSourceV2 copyOf(EventSourceV2 source) {
        EventSourceV2 copy = new EventSourceV2();
        copy.setName(source.getName());
        copy.setArn(source.getArn());
        copy.setEventBusArn(source.getEventBusArn());
        copy.setDescription(source.getDescription());
        copy.setConfiguration(source.getConfiguration());
        copy.setState(source.getState());
        copy.setCreationTime(source.getCreationTime());
        copy.setLastModifiedTime(source.getLastModifiedTime());
        copy.setTags(source.getTags());
        copy.setClientToken(source.getClientToken());
        copy.setCreateRequestHash(source.getCreateRequestHash());
        copy.setWebhookHostId(source.getWebhookHostId());
        copy.setWebhookToken(source.getWebhookToken());
        copy.setSecretArn(source.getSecretArn());
        copy.setRevoked(source.isRevoked());
        copy.setRotationRequests(new LinkedHashMap<>(source.getRotationRequests()));
        return copy;
    }

    private static String basicSecret(String username, String password) {
        ObjectNode credential = JsonNodeFactory.instance.objectNode();
        credential.put("username", username);
        credential.put("password", password);
        return credential.toString();
    }

    EventSourceV2 describe(JsonNode request, String region) {
        return find("DescribeEventSource", request, region);
    }

    private EventSourceV2 find(String operation, JsonNode request, String region) {
        EventBridgeV2RequestShapes.check(operation, request);
        String arn = request.get("EventSourceArn").textValue();
        checkArn(arn, region);
        return require(arn, region);
    }

    /** Deletes the source and schedules its secret for deletion, unless the secret is already scheduled. */
    void delete(JsonNode request, String region) {
        EventSourceV2 source = find("DeleteEventSource", request, region);
        if (ServiceEventsConfigurations.isAwsService(source)) {
            eventBridge.deleteManagedRule(id(source), region);
        } else {
            deleteSecret(source, region);
        }
        sources.delete(EventBridgeV2Service.storageKey(region, source.getName()));
        deduplication.forget(source.getArn());
    }

    private void deleteSecret(EventSourceV2 source, String region) {
        try {
            if (secrets.describeSecret(source.getSecretArn(), region).getDeletedDate() == null) {
                secrets.deleteSecret(source.getSecretArn(), null, false, region);
            }
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.warnv("The secret {0} of event source {1} is already gone", source.getSecretArn(), source.getArn());
        }
    }

    /**
     * One page of the Region's sources, or those of one bus, whose name starts with {@code NamePrefix}, in order of
     * their ids. A token that is not one of ours is refused before the bus is looked up.
     */
    PaginatedResult<EventSourceV2> list(JsonNode request, String region,
                                        Function<String, Optional<EventBusV2>> findBusByName) {
        EventBridgeV2RequestShapes.check("ListEventSources", request);
        String eventBusArn = request.path("EventBusArn").textValue();
        String namePrefix = request.path("NamePrefix").textValue();
        String nextToken = request.path("NextToken").textValue();
        if (eventBusArn != null) {
            requireEndpointRegion(eventBusArn, region, "EventBusArn");
        }
        String namespace = tokenNamespace(region, eventBusArn, namePrefix);
        if (nextToken != null && !Pagination.tokenStartsWith(nextToken, namespace + ":")) {
            throw invalidNextToken();
        }
        if (eventBusArn != null) {
            if (AwsArnUtils.isForeignAccount(AwsArnUtils.parse(eventBusArn), regionResolver.getAccountId())) {
                throw busMissing();
            }
            requireBus(eventBusArn, findBusByName);
        }
        List<EventSourceV2> listed = inRegion(region).stream()
                .filter(source -> eventBusArn == null || eventBusArn.equals(source.getEventBusArn()))
                .filter(source -> namePrefix == null || source.getName().startsWith(namePrefix))
                .toList();
        int maxResults = request.hasNonNull("MaxResults") ? request.get("MaxResults").intValue()
                : EventBridgeV2Service.MAX_LIST_RESULTS;
        return Pagination.paginate(listed, EventBridgeV2EventSources::id, maxResults, nextToken, namespace,
                token -> invalidNextToken());
    }

    private static String tokenNamespace(String region, String eventBusArn, String namePrefix) {
        String filters = region + "\n" + (eventBusArn == null ? "-" : "+" + eventBusArn) + "\n"
                + (namePrefix == null ? "-" : "+" + namePrefix);
        return TOKEN_NAMESPACE + "-" + Digests.sha256Hex(filters);
    }

    boolean hasSourcesOn(String eventBusArn, String region) {
        return inRegion(region).stream()
                .anyMatch(source -> eventBusArn.equals(source.getEventBusArn()) && !source.isRevoked()
                        && !CREATE_FAILED.equals(source.getState()));
    }

    /**
     * Whether {@code ruleArn} is the managed rule of a source of AWS service events that is on the bus and takes
     * events: the rule of that id on the default bus of the bus's own account and Region.
     */
    boolean takesServiceEvents(String eventBusArn, String region, String ruleArn) {
        String sourceId = ruleArn.substring(ruleArn.lastIndexOf('/') + 1);
        String managedRuleArn = regionResolver.buildArn("events", region,
                AwsArnUtils.parse(eventBusArn).accountId(), "rule/" + sourceId);
        if (!managedRuleArn.equals(ruleArn) || !eventBridge.isManagedRule(sourceId, region)) {
            return false;
        }
        return inRegion(region).stream()
                .anyMatch(source -> eventBusArn.equals(source.getEventBusArn())
                        && ServiceEventsConfigurations.isAwsService(source) && !source.isRevoked()
                        && sourceId.equals(id(source)));
    }

    /** The URL a source receives its events at, in the scheme of Floci's own base URL. */
    String webhookUrl(EventSourceV2 source) {
        return baseUrl.getScheme() + "://" + source.getWebhookHostId() + "."
                + webhookDomain(AwsArnUtils.parse(source.getArn()).region()) + baseUrl.getAuthority() + "/"
                + source.getWebhookToken() + "/";
    }

    private static String webhookDomain(String region) {
        return WEBHOOK_HOST_LABELS + region + ".";
    }

    /**
     * Has the server certificate name the webhook hosts of the Region. Blocks until the HTTPS listener has
     * switched certificates, so call it with no lock held.
     */
    void coverWebhookHosts(String region) {
        certificateManager.ensureHost("*." + webhookDomain(region) + baseUrl.getHost());
    }

    record LocatedSource(String accountId, EventSourceV2 source) {
    }

    /** The source whose URL has this host id, in whichever account holds it. */
    Optional<LocatedSource> locate(String hostId) {
        return sources.scanAllAccountEntries(key -> true).stream()
                .filter(entry -> hostId.equals(entry.value().getWebhookHostId()))
                .map(entry -> new LocatedSource(entry.accountId(), entry.value()))
                .findFirst();
    }

    /** Records a request hash the source received and returns true, or false when it saw the hash lately. */
    boolean firstSeen(EventSourceV2 source, String hash) {
        return deduplication.firstSeen(source.getArn(), hash);
    }

    void forgetSeen(EventSourceV2 source, String hash) {
        deduplication.forget(source.getArn(), hash);
    }

    @Override
    public void clear() {
        sources.clear();
        deduplication.clear();
    }

    private String createSecret(JsonNode webhook, String region) {
        JsonNode auth = webhook.get("AuthConfiguration");
        String authType = WebhookEventsConfigurations.authType(webhook);
        String secretString;
        if (WebhookEventsConfigurations.BASIC.equals(authType)) {
            JsonNode basic = auth.get(WebhookEventsConfigurations.BASIC);
            secretString = basicSecret(basic.hasNonNull("Username") ? basic.get("Username").textValue() : generated(),
                    basic.hasNonNull("Password") ? basic.get("Password").textValue() : generated());
        } else if (WebhookEventsConfigurations.HMAC.equals(authType)) {
            String given = WebhookEventsConfigurations.hmacSecretValue(auth.get(WebhookEventsConfigurations.HMAC));
            secretString = given != null ? given : generated();
        } else {
            JsonNode staticToken = auth.get(WebhookEventsConfigurations.STATIC_TOKEN);
            secretString = staticToken.hasNonNull("SecretValue") ? staticToken.get("SecretValue").textValue()
                    : generated();
        }
        return secrets.createSecret("events-webhook!" + UUID.randomUUID(), secretString, null, null, null,
                List.of(new Secret.Tag("aws:secretsmanager:owningService", OWNING_SERVICE)), OWNING_SERVICE,
                region).getArn();
    }

    private void deleteOrphanedSecret(String secretArn, String region) {
        try {
            secrets.deleteSecret(secretArn, null, true, region);
        } catch (RuntimeException e) {
            LOG.warnv(e, "The secret {0} of an event source that was not stored could not be deleted", secretArn);
        }
    }

    private String generated() {
        byte[] bytes = new byte[GENERATED_SECRET_BYTES];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static void requireBus(String eventBusArn, Function<String, Optional<EventBusV2>> findBusByName) {
        String busName = EventBridgeV2Service.busName(eventBusArn);
        EventBusV2 bus = findBusByName.apply(busName).orElseThrow(EventBridgeV2EventSources::busMissing);
        if (!eventBusArn.equals(bus.getArn())) {
            throw new AwsException("ResourceNotFoundException", "Event bus not found: " + busName, 404);
        }
    }

    static AwsException busMissing() {
        return new AwsException("ResourceNotFoundException", "Resource does not exist", 404);
    }

    private static AwsException invalidNextToken() {
        return EventBridgeV2Service.invalidInput("Invalid nextToken");
    }

    /** Refuses an ARN of another Region, partition or account. {@code member} names the request member in the error. */
    void checkArn(String arn, String region, String member) {
        requireEndpointRegion(arn, region, member);
        requireOwnAccount(arn);
    }

    private void checkArn(String eventSourceArn, String region) {
        checkArn(eventSourceArn, region, "EventSourceArn");
    }

    private Optional<EventSourceV2> lookup(String eventSourceArn, String region) {
        return lookup(eventSourceArn, region, sources.accountId());
    }

    Optional<EventSourceV2> lookup(String eventSourceArn, String region, String accountId) {
        String[] resource = AwsArnUtils.parse(eventSourceArn).resource().split("/");
        return resource.length == 4 && "event-sourcev2".equals(resource[0])
                ? sources.getForAccount(accountId, EventBridgeV2Service.storageKey(region, resource[2]))
                        .filter(source -> eventSourceArn.equals(source.getArn()))
                : Optional.empty();
    }

    private EventSourceV2 require(String eventSourceArn, String region) {
        return lookup(eventSourceArn, region).orElseThrow(() -> notFound(eventSourceArn));
    }

    private static AwsException notFound(String eventSourceArn) {
        return new AwsException("ResourceNotFoundException", "EventSource not found: " + eventSourceArn, 404);
    }

    private static void requireNotRevoked(EventSourceV2 source, String message) {
        if (source.isRevoked()) {
            throw new AwsException("InvalidStateException", message, 409);
        }
    }

    /**
     * Revokes the source {@code eventSourceArn} names. A webhook source that does not exist is answered as access
     * denied, and any other source type as not found.
     */
    void revoke(String eventSourceArn, String region) {
        boolean webhook = "webhook".equals(AwsArnUtils.parse(eventSourceArn).resource().split("/")[1]);
        EventSourceV2 source = lookup(eventSourceArn, region)
                .orElseThrow(() -> webhook ? accessDenied() : notFound(eventSourceArn));
        if (source.isRevoked()) {
            return;
        }
        EventSourceV2 revoked = copyOf(source);
        revoked.setRevoked(true);
        revoked.setLastModifiedTime(clock.instant());
        sources.put(EventBridgeV2Service.storageKey(region, source.getName()), revoked);
    }

    private void requireEndpointRegion(String arn, String region, String member) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        if (!parsed.region().equals(region) || !parsed.partition().equals(regionResolver.partitionForRegion(region))) {
            throw EventBridgeV2Service.invalidInput(
                    "The region or partition in " + member + " does not match this endpoint");
        }
    }

    private void requireOwnAccount(String arn) {
        if (AwsArnUtils.isForeignAccount(AwsArnUtils.parse(arn), regionResolver.getAccountId())) {
            throw accessDenied();
        }
    }

    static AwsException accessDenied() {
        return new AwsException("AccessDeniedException", "Access denied.", 403);
    }

    private List<EventSourceV2> inRegion(String region) {
        String prefix = region + "::";
        return sources.scan(key -> key.startsWith(prefix));
    }

    private static Map<String, String> tags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            for (Map.Entry<String, JsonNode> tag : node.properties()) {
                tags.put(tag.getKey(), tag.getValue().textValue());
            }
        }
        return tags;
    }

    static String id(EventSourceV2 source) {
        return source.getArn().substring(source.getArn().length() - EventBridgeV2Service.ID_LENGTH);
    }
}
