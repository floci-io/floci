package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eventbridgev2.model.EventBusV2;
import io.floci.aws.services.eventbridgev2.model.EventSourceV2;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.secretsmanager.model.Secret;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
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
    static final String OWNING_SERVICE = "events-webhook";

    private static final Logger LOG = Logger.getLogger(EventBridgeV2EventSources.class);
    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int ID_LENGTH = 25;
    private static final int GENERATED_SECRET_BYTES = 32;

    private final AccountAwareStorageBackend<EventSourceV2> sources;
    private final SecretsManagerService secrets;
    private final RegionResolver regionResolver;
    private final Clock clock;
    private final URI baseUrl;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public EventBridgeV2EventSources(StorageFactory storageFactory, SecretsManagerService secrets,
                                     RegionResolver regionResolver, EmulatorConfig config) {
        this(storageFactory.create("eventbridgev2", "eventbridgev2-event-sources.json",
                        new TypeReference<Map<String, EventSourceV2>>() {}),
                secrets, regionResolver, Clock.systemUTC(), config.effectiveBaseUrl());
    }

    EventBridgeV2EventSources(AccountAwareStorageBackend<EventSourceV2> sources, SecretsManagerService secrets,
                              RegionResolver regionResolver, Clock clock, String baseUrl) {
        this.sources = sources;
        this.secrets = secrets;
        this.regionResolver = regionResolver;
        this.clock = clock;
        this.baseUrl = URI.create(baseUrl);
    }

    /**
     * Creates a webhook event source on a bus of the caller's, {@code ACTIVE} at once, with the secret that holds
     * its credential. A retry with the {@code ClientToken} of an earlier create returns that source when it repeats
     * the request, and fails when it changes it.
     */
    EventSourceV2 create(JsonNode request, String region, Function<String, Optional<EventBusV2>> findBus) {
        throwIfInvalid(WebhookEventsConfigurations.createViolations(request));
        String name = request.get("Name").textValue();
        String eventBusArn = request.get("EventBusArn").textValue();
        JsonNode configuration = request.get("Configuration");
        String roleArn = configuration.path(WebhookEventsConfigurations.WEBHOOK).path("RoleArn").asText(null);

        requireEndpointRegion(eventBusArn, region, "EventBusArn");
        requireOwnAccount(eventBusArn);
        if (roleArn != null) {
            requireOwnAccount(roleArn);
        }
        JsonNode webhook = WebhookEventsConfigurations.validateForCreate(configuration);

        String clientToken = request.hasNonNull("ClientToken") ? request.get("ClientToken").textValue() : null;
        String requestHash = sha256(EventBridgeV2Service.withoutClientToken(request));
        if (clientToken != null) {
            Optional<EventSourceV2> retried = inRegion(region).stream()
                    .filter(source -> clientToken.equals(source.getClientToken()))
                    .findFirst();
            if (retried.isPresent()) {
                if (!requestHash.equals(retried.get().getCreateRequestHash())) {
                    throw new AwsException("IdempotentParameterMismatchException",
                            "ClientToken was used by a previous request with different parameters", 409);
                }
                return retried.get();
            }
        }
        findBus.apply(eventBusArn).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "Event bus not found: " + AwsArnUtils.parse(eventBusArn).resource().split("/")[1], 404));
        String key = storageKey(region, name);
        if (sources.get(key).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException", "Resource already exists", 409);
        }

        String hostId = WebhookTokens.newHostId(random);
        Instant now = clock.instant();
        EventSourceV2 source = new EventSourceV2();
        source.setName(name);
        source.setArn(regionResolver.buildArn("events", region, "event-sourcev2/webhook/" + name + "/" + newId()));
        source.setEventBusArn(eventBusArn);
        source.setDescription(request.hasNonNull("Description") ? request.get("Description").textValue() : null);
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
        sources.put(key, source);
        return source;
    }

    /**
     * Replaces the description the request gives and the whole webhook configuration. The credentials, the URL and
     * the secret stay as they are. A refused update changes nothing.
     */
    EventSourceV2 update(JsonNode request, String region) {
        throwIfInvalid(WebhookEventsConfigurations.updateViolations(request));
        String arn = request.get("EventSourceArn").textValue();
        checkArn(arn, region);
        JsonNode configuration = request.hasNonNull("Configuration") ? request.get("Configuration") : null;
        String roleArn = configuration == null ? null
                : configuration.path(WebhookEventsConfigurations.WEBHOOK).path("RoleArn").asText(null);
        if (roleArn != null) {
            requireOwnAccount(roleArn);
        }
        EventSourceV2 source = require(arn, region);
        JsonNode webhook = configuration == null ? null : WebhookEventsConfigurations.validateForUpdate(
                configuration, WebhookEventsConfigurations.authType(
                        source.getConfiguration().get(WebhookEventsConfigurations.WEBHOOK)));

        if (webhook != null) {
            source.setConfiguration(WebhookEventsConfigurations.echo(webhook));
        }
        if (request.hasNonNull("Description")) {
            source.setDescription(request.get("Description").textValue());
        }
        source.setLastModifiedTime(clock.instant());
        sources.put(storageKey(region, source.getName()), source);
        return source;
    }

    EventSourceV2 describe(JsonNode request, String region) {
        throwIfInvalid(WebhookEventsConfigurations.eventSourceArnViolations(request));
        String arn = request.get("EventSourceArn").textValue();
        checkArn(arn, region);
        return require(arn, region);
    }

    /** Deletes the source and schedules its secret for deletion. */
    void delete(JsonNode request, String region) {
        EventSourceV2 source = describe(request, region);
        try {
            secrets.deleteSecret(source.getSecretArn(), null, false, region);
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.warnv("The secret {0} of event source {1} is already gone", source.getSecretArn(), source.getArn());
        }
        sources.delete(storageKey(region, source.getName()));
    }

    /** The Region's sources, or those of one bus, whose name starts with {@code namePrefix}, in order of their ids. */
    List<EventSourceV2> list(String eventBusArn, String namePrefix, String region) {
        return inRegion(region).stream()
                .filter(source -> eventBusArn == null || eventBusArn.equals(source.getEventBusArn()))
                .filter(source -> namePrefix == null || source.getName().startsWith(namePrefix))
                .sorted(Comparator.comparing(EventBridgeV2EventSources::id))
                .toList();
    }

    boolean hasSourcesOn(String eventBusArn, String region) {
        return inRegion(region).stream().anyMatch(source -> eventBusArn.equals(source.getEventBusArn()));
    }

    /** The URL a source receives its events at, in the scheme of Floci's own base URL. */
    String webhookUrl(EventSourceV2 source) {
        return baseUrl.getScheme() + "://" + source.getWebhookHostId() + ".c01.events-webhook."
                + AwsArnUtils.parse(source.getArn()).region() + "." + baseUrl.getAuthority() + "/"
                + source.getWebhookToken() + "/";
    }

    @Override
    public void clear() {
        sources.clear();
    }

    private String createSecret(JsonNode webhook, String region) {
        JsonNode auth = webhook.get("AuthConfiguration");
        String authType = WebhookEventsConfigurations.authType(webhook);
        String secretString;
        if (WebhookEventsConfigurations.BASIC.equals(authType)) {
            JsonNode basic = auth.get(WebhookEventsConfigurations.BASIC);
            ObjectNode credential = JsonNodeFactory.instance.objectNode();
            credential.put("username", basic.hasNonNull("Username") ? basic.get("Username").textValue() : generated());
            credential.put("password", basic.hasNonNull("Password") ? basic.get("Password").textValue() : generated());
            secretString = credential.toString();
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

    private String generated() {
        byte[] bytes = new byte[GENERATED_SECRET_BYTES];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private void checkArn(String eventSourceArn, String region) {
        requireEndpointRegion(eventSourceArn, region, "EventSourceArn");
        requireOwnAccount(eventSourceArn);
    }

    private EventSourceV2 require(String eventSourceArn, String region) {
        String[] resource = AwsArnUtils.parse(eventSourceArn).resource().split("/");
        Optional<EventSourceV2> found = resource.length == 4 && "event-sourcev2".equals(resource[0])
                ? sources.get(storageKey(region, resource[2])).filter(source -> eventSourceArn.equals(source.getArn()))
                : Optional.empty();
        return found.orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "EventSource not found: " + eventSourceArn, 404));
    }

    private void requireEndpointRegion(String arn, String region, String member) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        if (!parsed.region().equals(region) || !parsed.partition().equals(regionResolver.partitionForRegion(region))) {
            throw new AwsException("InvalidInputException",
                    "The region or partition in " + member + " does not match this endpoint", 400);
        }
    }

    private void requireOwnAccount(String arn) {
        if (AwsArnUtils.isForeignAccount(AwsArnUtils.parse(arn), regionResolver.getAccountId())) {
            throw new AwsException("AccessDeniedException", "Access denied.", 403);
        }
    }

    private List<EventSourceV2> inRegion(String region) {
        return sources.scan(key -> key.startsWith(region + "::"));
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

    private static void throwIfInvalid(List<String> violations) {
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.exception("InvalidInputException", violations);
        }
    }

    static String id(EventSourceV2 source) {
        return source.getArn().substring(source.getArn().length() - ID_LENGTH);
    }

    private String newId() {
        StringBuilder id = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < ID_LENGTH; i++) {
            id.append(ID_ALPHABET.charAt(random.nextInt(ID_ALPHABET.length())));
        }
        return id.toString();
    }

    private static String storageKey(String region, String name) {
        return region + "::" + name;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
