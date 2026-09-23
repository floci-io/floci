package io.github.hectorvent.floci.services.ecs.exec;

import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The open {@code ExecuteCommand} sessions, keyed by session id.
 *
 * <p>A session is created by the ECS API and claimed once, by the client that opens the data
 * channel with the matching token. Sessions are in memory and short lived, the way AWS's are: the
 * token is single use and expires if nobody connects.
 */
@ApplicationScoped
public class EcsExecSessionRegistry implements Resettable {

    private static final Logger LOG = Logger.getLogger(EcsExecSessionRegistry.class);

    /** How long a session waits for its client before it is dropped. */
    static final Duration SESSION_TTL = Duration.ofMinutes(5);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, ExecSession> sessions = new ConcurrentHashMap<>();

    private final Clock clock;

    @Inject
    public EcsExecSessionRegistry(Clock clock) {
        this.clock = clock;
    }

    /** With the system clock, for callers that assemble the registry without CDI. */
    public EcsExecSessionRegistry() {
        this(Clock.systemUTC());
    }

    /** Mints a session for a container, returning it with the token the client must present. */
    public ExecSession create(String taskArn, String clusterArn, String containerName,
                              String containerArn, String runtimeId, List<String> command,
                              boolean interactive) {
        expireStaleSessions();
        String sessionId = "ecs-execute-command-" + UUID.randomUUID().toString().replace("-", "");
        byte[] token = new byte[32];
        RANDOM.nextBytes(token);
        ExecSession session = new ExecSession(sessionId,
                Base64.getUrlEncoder().withoutPadding().encodeToString(token),
                taskArn, clusterArn, containerName, containerArn, runtimeId, command, interactive,
                clock.instant());
        sessions.put(sessionId, session);
        return session;
    }

    /**
     * Claims a session for a connecting client. The session is removed, so a replayed token cannot
     * open a second channel into the container. The token is the only gate in front of an
     * interactive shell, so it is compared in constant time, and only for a session that is still
     * within its TTL.
     */
    public Optional<ExecSession> claim(String sessionId, String tokenValue) {
        ExecSession session = liveSession(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        if (tokenValue == null || !MessageDigest.isEqual(
                session.tokenValue().getBytes(StandardCharsets.UTF_8),
                tokenValue.getBytes(StandardCharsets.UTF_8))) {
            LOG.warnv("Rejected an ECS Exec channel for session {0}: the token does not match", sessionId);
            return Optional.empty();
        }
        return sessions.remove(sessionId, session) ? Optional.of(session) : Optional.empty();
    }

    /** The session behind a data channel, or empty once it has expired or been claimed. */
    public Optional<ExecSession> find(String sessionId) {
        return Optional.ofNullable(liveSession(sessionId));
    }

    /**
     * The session under {@code sessionId}, or null if there is none or it has outlived
     * {@link #SESSION_TTL}. An expired session is dropped here rather than waiting for the next
     * {@code create()}: nothing else sweeps the map, so a session nobody connects to would
     * otherwise stay claimable, token and all, for as long as the emulator runs.
     */
    private ExecSession liveSession(String sessionId) {
        ExecSession session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        if (session.createdAt().isBefore(cutoff())) {
            sessions.remove(sessionId, session);
            LOG.debugv("Dropped the expired ECS Exec session {0}", sessionId);
            return null;
        }
        return session;
    }

    private void expireStaleSessions() {
        Instant cutoff = cutoff();
        sessions.values().removeIf(session -> session.createdAt().isBefore(cutoff));
    }

    private Instant cutoff() {
        return clock.instant().minus(SESSION_TTL);
    }

    @Override
    public void clear() {
        sessions.clear();
    }
}
