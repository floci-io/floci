package io.floci.aws.services.ssm.session;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.AccountAwareStorageBackend.AccountEntry;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.ssm.session.SessionDocumentResolver.ResolvedDocument;
import io.floci.aws.services.ssmmessages.DataChannelRouter;
import io.floci.aws.services.ssmmessages.SsmMessageGateway;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.Node;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.SessionRequest;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.SessionState;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.UriBuilder;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

@ApplicationScoped
public class SsmSessionService implements Resettable {
    private static final int MAX_RESULTS = 200;
    private static final Duration HISTORY_RETENTION = Duration.ofDays(30);

    private final AccountAwareStorageBackend<SsmSession> sessions;
    private final SsmMessageGateway gateway;
    private final SessionDocumentResolver documents;
    private final RegionResolver regionResolver;
    private final RequestContext requestContext;
    private final IamService iam;
    private final Clock clock;
    private final URI baseUri;
    private boolean resetting;

    @Inject
    public SsmSessionService(StorageFactory storage, SsmMessageGateway gateway, SessionDocumentResolver documents,
                             RegionResolver regionResolver, RequestContext requestContext, IamService iam,
                             Clock clock, EmulatorConfig config) {
        this(storage.create("ssm", "ssm-sessions.json", new TypeReference<>() {}), gateway, documents, regionResolver,
                requestContext, iam, clock, URI.create(config.effectiveBaseUrl()));
    }

    SsmSessionService(AccountAwareStorageBackend<SsmSession> sessions, SsmMessageGateway gateway,
                      SessionDocumentResolver documents, RegionResolver regionResolver, RequestContext requestContext,
                      IamService iam, Clock clock, URI baseUri) {
        this.sessions = sessions;
        this.gateway = gateway;
        this.documents = documents;
        this.regionResolver = regionResolver;
        this.requestContext = requestContext;
        this.iam = iam;
        this.clock = clock;
        this.baseUri = baseUri;
    }

    @PostConstruct
    void closeRecoveredSessions() {
        // Agent sockets and channel tokens live in memory. A recovered active row has no live session.
        Instant now = clock.instant();
        for (AccountEntry<SsmSession> entry : sessions.scanAllAccountEntries(key -> true)) {
            if (entry.value().active()) {
                sessions.putForAccount(entry.accountId(), entry.key(), entry.value().transition(SessionState.FAILED, now));
            }
        }
    }

    public synchronized SessionConnection start(String region, String target, String documentName, String reason,
                                                Map<String, List<String>> parameters, URI requestBaseUri) {
        Node node = node(region, target);
        if (resetting || !gateway.isConnected(node)) {
            throw new AwsException("TargetNotConnected", target + " is not connected.", 400);
        }
        String owner = ownerArn(node.accountId());
        ResolvedDocument document = documents.resolve(region, documentName, parameters);
        String sessionId = sessionId(owner);
        String arn = AwsArnUtils.Arn.of("ssm", region, node.accountId(), "session/" + sessionId).toString();
        String url = streamUrl(sessionId, requestBaseUri);
        String key = sessionKey(region, sessionId);
        SessionRequest request = new SessionRequest(sessionId, arn, owner, node, document.name(), document.content(),
                document.parameters());
        sessions.put(key, new SsmSession(sessionId, target, owner, document.name(), reason,
                SessionState.CONNECTED, clock.instant(), null));
        String token;
        try {
            token = gateway.startSession(request, state -> RequestScopes.runAs(node.accountId(), region,
                    () -> transition(key, state)));
        } catch (RuntimeException e) {
            sessions.delete(key);
            throw e;
        }
        return new SessionConnection(sessionId, url, token);
    }

    public synchronized SessionConnection resume(String region, String sessionId, URI requestBaseUri) {
        SsmSession session = requireActive(region, sessionId);
        String token = gateway.resumeSession(node(region, session.target()), sessionId)
                .orElseThrow(SsmSessionService::invalidSessionState);
        return new SessionConnection(sessionId, streamUrl(sessionId, requestBaseUri), token);
    }

    public synchronized void terminate(String region, String sessionId) {
        SsmSession session = requireActive(region, sessionId);
        gateway.terminateSession(node(region, session.target()), sessionId);
        transition(sessionKey(region, sessionId), SessionState.TERMINATED);
    }

    public synchronized boolean isConnected(String region, String target) {
        return !resetting && gateway.isConnected(node(region, target));
    }

    public synchronized PaginatedResult<SsmSession> describe(String region, String state, List<SessionFilter> filters,
                                                            Integer maxResults, String nextToken) {
        boolean active = switch (state) {
            case "Active" -> true;
            case "History" -> false;
            default -> throw new AwsException("ValidationException", "State must be Active or History.", 400);
        };
        if (maxResults != null && (maxResults < 1 || maxResults > MAX_RESULTS)) {
            throw new AwsException("ValidationException", "MaxResults must be between 1 and 200.", 400);
        }
        List<Predicate<SsmSession>> predicates = filters.stream().map(SsmSessionService::filter).toList();
        Instant cutoff = clock.instant().minus(HISTORY_RETENTION);
        String prefix = sessionKey(region, "");
        List<SsmSession> matching = sessions.scan(key -> key.startsWith(prefix)).stream()
                .filter(session -> active == session.active())
                .filter(session -> active || !session.endDate().isBefore(cutoff))
                .filter(session -> predicates.stream().allMatch(predicate -> predicate.test(session)))
                .toList();
        return Pagination.paginate(matching, SsmSessionService::cursor,
                maxResults == null ? MAX_RESULTS : maxResults, nextToken,
                regionResolver.getAccountId() + "/" + region + "/" + state,
                ignored -> new AwsException("InvalidNextToken", "Invalid next token.", 400));
    }

    @Override
    public synchronized void beforeReset() {
        resetting = true;
    }

    @Override
    public void clear() {
        // StorageFactory clears the metadata and the message gateway closes live channels.
    }

    @Override
    public synchronized void afterReset() {
        resetting = false;
    }

    private synchronized void transition(String key, SessionState state) {
        if (resetting) {
            return;
        }
        sessions.get(key).filter(SsmSession::active).filter(session -> session.state() != state)
                .ifPresent(session -> sessions.put(key, session.transition(state, clock.instant())));
    }

    private SsmSession requireActive(String region, String sessionId) {
        if (resetting) {
            throw invalidSessionState();
        }
        return sessions.get(sessionKey(region, sessionId)).filter(SsmSession::active)
                .orElseThrow(SsmSessionService::invalidSessionState);
    }

    private static AwsException invalidSessionState() {
        return new AwsException("ValidationException", "Session is not in a valid state", 400);
    }

    private Node node(String region, String target) {
        return new Node(regionResolver.getAccountId(), region, target);
    }

    private String ownerArn(String accountId) {
        String accessKey = requestContext.getAccessKeyId();
        return iam.resolveCallerArn(accessKey)
                .map(arn -> {
                    if (!accountId.equals(AwsArnUtils.parse(arn).accountId())) {
                        throw new AwsException("AccessDeniedException", "Could not identify the session caller.", 403);
                    }
                    return arn;
                })
                .orElseGet(() -> regionResolver.buildGlobalArn("iam", accountId, "root"));
    }

    private static String sessionId(String ownerArn) {
        String resource = AwsArnUtils.parse(ownerArn).resource();
        String prefix = resource.substring(resource.lastIndexOf('/') + 1);
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 26);
    }

    private String streamUrl(String sessionId, URI requestBaseUri) {
        URI endpoint = requestBaseUri != null ? requestBaseUri : baseUri;
        return UriBuilder.fromUri(endpoint)
                .scheme("https".equalsIgnoreCase(endpoint.getScheme()) ? "wss" : "ws")
                .path(DataChannelRouter.CHANNEL_PATH_PREFIX)
                .segment(sessionId)
                .queryParam("role", "publish_subscribe")
                .build().toString();
    }

    private static String sessionKey(String region, String sessionId) {
        return region + "|" + sessionId;
    }

    private static String cursor(SsmSession session) {
        long reverseStart = Long.MAX_VALUE - session.startDate().toEpochMilli();
        return String.format(Locale.ROOT, "%019d/%s", reverseStart, session.sessionId());
    }

    private static Predicate<SsmSession> filter(SessionFilter filter) {
        String value = filter.value();
        return switch (filter.key()) {
            case "SessionId" -> session -> session.sessionId().equals(value);
            case "Target" -> session -> session.target().equals(value);
            case "Owner" -> session -> session.owner().equals(value);
            case "Status" -> session -> session.status().equals(value);
            case "AccessType" -> session -> "Standard".equals(value);
            case "InvokedBefore" -> {
                Instant before = filterTime(filter);
                yield session -> session.startDate().isBefore(before);
            }
            case "InvokedAfter" -> {
                Instant after = filterTime(filter);
                yield session -> !session.startDate().isBefore(after);
            }
            default -> throw new AwsException("InvalidFilterKey", "Invalid filter key " + filter.key() + ".", 400);
        };
    }

    private static Instant filterTime(SessionFilter filter) {
        try {
            return Instant.parse(filter.value());
        } catch (DateTimeParseException e) {
            throw new AwsException("BadRequest", "Invalid filter value " + filter.value()
                    + " for filter key " + filter.key(), 400);
        }
    }

    public record SessionConnection(String sessionId, String streamUrl, String tokenValue) {}
    public record SessionFilter(String key, String value) {}
}
