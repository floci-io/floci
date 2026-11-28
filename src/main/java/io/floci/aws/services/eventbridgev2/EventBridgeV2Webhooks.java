package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.eventbridgev2.EventBridgeV2EventSources.LocatedSource;
import io.floci.aws.services.eventbridgev2.model.EventSourceV2;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.secretsmanager.model.SecretVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a webhook event source does with a request to its URL, in the order AWS checks it: the method, the URL, the
 * size of the body, the credential and then the body. A request that passes becomes an event on the source's bus.
 * It blocks on storage, so it is never called on the event loop.
 */
@ApplicationScoped
public class EventBridgeV2Webhooks {

    /** {@code headers} are keyed by lower-case name. {@code body} is empty when {@code oversize} is set. */
    record Request(String method, String hostId, String path, Map<String, String> headers, byte[] body,
                   boolean oversize) {
    }

    /** The reply: a status and its plain text body. A 400 also closes the connection. */
    record Answer(int status, String body) {
        boolean closesConnection() {
            return status == 400;
        }
    }

    static final Answer OK = new Answer(200, "OK");
    static final Answer FORBIDDEN = new Answer(403, "Forbidden");
    static final Answer BAD_REQUEST = new Answer(400, "Bad Request");
    static final Answer NOT_FOUND = new Answer(404, "Not Found");

    private static final Logger LOG = Logger.getLogger(EventBridgeV2Webhooks.class);
    private static final Duration PREVIOUS_CREDENTIAL_GRACE = Duration.ofMinutes(15);
    private static final Pattern PATH = Pattern.compile("/(k1[A-Za-z0-9_-]{64})/");

    private final EventBridgeV2Service service;
    private final EventBridgeV2EventSources sources;
    private final SecretsManagerService secrets;
    private final Clock clock;

    @Inject
    public EventBridgeV2Webhooks(EventBridgeV2Service service, EventBridgeV2EventSources sources,
                                 SecretsManagerService secrets) {
        this(service, sources, secrets, Clock.systemUTC());
    }

    EventBridgeV2Webhooks(EventBridgeV2Service service, EventBridgeV2EventSources sources,
                          SecretsManagerService secrets, Clock clock) {
        this.service = service;
        this.sources = sources;
        this.secrets = secrets;
        this.clock = clock;
    }

    Answer receive(Request request) {
        if (!"POST".equals(request.method())) {
            return BAD_REQUEST;
        }
        Matcher path = PATH.matcher(request.path());
        if (!path.matches() || !WebhookTokens.isValid(request.hostId(), path.group(1))) {
            return NOT_FOUND;
        }
        if (request.oversize()) {
            return BAD_REQUEST;
        }
        Optional<LocatedSource> located = sources.locate(request.hostId());
        if (located.isEmpty()) {
            return FORBIDDEN;
        }
        EventSourceV2 source = located.get().source();
        if (!MessageDigest.isEqual(path.group(1).getBytes(StandardCharsets.US_ASCII),
                source.getWebhookToken().getBytes(StandardCharsets.US_ASCII))) {
            return NOT_FOUND;
        }
        String accountId = located.get().accountId();
        String region = AwsArnUtils.parse(source.getArn()).region();
        JsonNode webhook = source.getConfiguration().path(WebhookEventsConfigurations.WEBHOOK);
        if (source.isRevoked() || !credentialAccepted(accountId, region, source, webhook, request)) {
            return FORBIDDEN;
        }
        Optional<JsonNode> data = WebhookEvents.parseBody(request.body());
        if (data.isEmpty()) {
            return BAD_REQUEST;
        }

        Map<String, String> metadata = WebhookEvents.metadata(webhook.path("MetadataHttpHeaders"), request.headers());
        boolean contentBased = WebhookEvents.CONTENT_BASED.equals(
                webhook.path("DeduplicationConfiguration").path("DeduplicationType").asText());
        String groupId = contentBased ? WebhookEvents.contentHash(request.body(), metadata)
                : UUID.randomUUID().toString();
        if (contentBased && !sources.firstSeen(source, groupId)) {
            return OK;
        }
        try {
            RequestScopes.runAs(accountId, region, () -> service.publishWebhookEvent(source.getEventBusArn(), region,
                    sequenceNumber -> WebhookEvents.event(data.get(), request.body(), metadata, groupId,
                            clock.instant(), UUID.randomUUID().toString(), sequenceNumber)));
        } catch (RuntimeException e) {
            if (contentBased) {
                // The sender retries a request that failed, and the retry is not a repeat.
                sources.forgetSeen(source, groupId);
            }
            throw e;
        }
        return OK;
    }

    /** The current credential, and the one the last rotation replaced until that rotation is 15 minutes old. */
    private boolean credentialAccepted(String accountId, String region, EventSourceV2 source, JsonNode webhook,
                                       Request request) {
        Optional<SecretVersion> current = secretVersion(accountId, region, source, null);
        if (current.isEmpty()) {
            return false;
        }
        if (matches(webhook, current.get(), request)) {
            return true;
        }
        Instant rotated = current.get().getCreatedDate();
        if (rotated == null || !clock.instant().isBefore(rotated.plus(PREVIOUS_CREDENTIAL_GRACE))) {
            return false;
        }
        return secretVersion(accountId, region, source, "AWSPREVIOUS")
                .filter(previous -> matches(webhook, previous, request)).isPresent();
    }

    private boolean matches(JsonNode webhook, SecretVersion version, Request request) {
        return version.getSecretString() != null
                && WebhookAuthenticator.allows(webhook, version.getSecretString(), request.headers(), request.body(),
                        clock.instant());
    }

    private Optional<SecretVersion> secretVersion(String accountId, String region, EventSourceV2 source,
                                                  String stage) {
        try {
            return Optional.ofNullable(RequestScopes.callAs(accountId, region,
                    () -> secrets.getSecretValue(source.getSecretArn(), null, stage, region)));
        } catch (AwsException e) {
            if (stage == null) {
                LOG.warnv("The secret of event source {0} could not be read: {1}", source.getArn(), e.getMessage());
            }
            return Optional.empty();
        }
    }
}
