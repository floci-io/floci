package io.github.hectorvent.floci.services.appsync.graphql.resolver;

import jakarta.enterprise.context.ApplicationScoped;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The in-flight {@code /v1/execute} calls the GraphQL sidecar may call back into.
 *
 * <p>A session exists only for the duration of one GraphQL operation. Its token is what the
 * sidecar presents on the resolver callback, and it is the only thing tying a callback to the
 * operation that authorised it: the callback endpoint is reachable from any container Floci
 * launched, so a request carrying no live token resolves nothing.
 */
@ApplicationScoped
public class ResolverCallbackSessions {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * One operation's callback state: who it runs as, and the errors its resolvers appended.
     *
     * <p>Closing it is what makes the token stop working, so callers close in a finally: a session
     * left behind would keep answering callbacks for an operation that already returned.
     */
    public static final class Session implements AutoCloseable {
        private final Map<String, Session> owner;
        private final String token;
        private final String apiId;
        private final Object identity;
        private final String authType;
        private final List<AppSyncResolverError> appendedErrors = new CopyOnWriteArrayList<>();

        private Session(Map<String, Session> owner, String token, String apiId, Object identity, String authType) {
            this.owner = owner;
            this.token = token;
            this.apiId = apiId;
            this.identity = identity;
            this.authType = authType;
        }

        public String token() {
            return token;
        }

        public String apiId() {
            return apiId;
        }

        public Object identity() {
            return identity;
        }

        public String authType() {
            return authType;
        }

        /** Errors from {@code util.appendError}, which are reported beside the data rather than instead of it. */
        public void appendErrors(List<AppSyncResolverError> errors) {
            if (errors != null && !errors.isEmpty()) {
                appendedErrors.addAll(errors);
            }
        }

        public List<AppSyncResolverError> appendedErrors() {
            return List.copyOf(appendedErrors);
        }

        @Override
        public void close() {
            owner.remove(token);
        }
    }

    public Session open(String apiId, Object identity, String authType) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Session session = new Session(sessions, token, apiId, identity, authType);
        sessions.put(token, session);
        return session;
    }

    public Optional<Session> find(String token) {
        return token == null || token.isBlank() ? Optional.empty() : Optional.ofNullable(sessions.get(token));
    }
}
