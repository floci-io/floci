package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.services.cognito.model.CognitoAuthorizationCode;
import io.github.hectorvent.floci.services.cognito.model.CognitoAuthorizationTransaction;
import io.github.hectorvent.floci.services.cognito.model.CognitoManagedLoginSession;
import jakarta.enterprise.context.ApplicationScoped;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

@ApplicationScoped
public class CognitoFederationStateStore {

    private static final int OPAQUE_KEY_BYTES = 32;

    private final ConcurrentHashMap<String, CognitoAuthorizationTransaction> transactions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CognitoAuthorizationCode> authorizationCodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CognitoManagedLoginSession> sessions = new ConcurrentHashMap<>();
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public CognitoFederationStateStore(Clock clock) {
        this.clock = clock;
    }

    public String putTransaction(CognitoAuthorizationTransaction transaction) {
        return put(transactions, transaction);
    }

    public Optional<CognitoAuthorizationTransaction> consumeTransaction(String state) {
        return consume(transactions, state, CognitoAuthorizationTransaction::expiresAt);
    }

    public String putAuthorizationCode(CognitoAuthorizationCode authorizationCode) {
        return put(authorizationCodes, authorizationCode);
    }

    public Optional<CognitoAuthorizationCode> consumeAuthorizationCode(String code) {
        return consume(authorizationCodes, code, CognitoAuthorizationCode::expiresAt);
    }

    public Optional<CognitoAuthorizationCode> findAuthorizationCode(String code) {
        return find(authorizationCodes, code, CognitoAuthorizationCode::expiresAt);
    }

    /** Stores a managed login session and returns its id, the value of the browser's session cookie. */
    public String putSession(CognitoManagedLoginSession session) {
        return put(sessions, session);
    }

    /** A session id comes from a cookie, so a missing one is simply no session. */
    public Optional<CognitoManagedLoginSession> findSession(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return find(sessions, sessionId, CognitoManagedLoginSession::expiresAt);
    }

    public void deleteSession(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    /** A random value in the form of this store's keys, for tokens that are not stored, such as the CSRF token. */
    String generateOpaqueKey() {
        byte[] bytes = new byte[OPAQUE_KEY_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private <T> String put(ConcurrentHashMap<String, T> store, T value) {
        String key;
        do {
            key = generateOpaqueKey();
        } while (store.putIfAbsent(key, value) != null);
        return key;
    }

    private <T> Optional<T> find(ConcurrentHashMap<String, T> store, String key, Function<T, Instant> expiresAt) {
        T value = store.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (isExpired(expiresAt.apply(value))) {
            store.remove(key, value);
            return Optional.empty();
        }
        return Optional.of(value);
    }

    private <T> Optional<T> consume(ConcurrentHashMap<String, T> store, String key,
                                    Function<T, Instant> expiresAt) {
        AtomicReference<T> consumed = new AtomicReference<>();
        store.computeIfPresent(key, (ignored, value) -> {
            if (!isExpired(expiresAt.apply(value))) {
                consumed.set(value);
            }
            return null;
        });
        return Optional.ofNullable(consumed.get());
    }

    private boolean isExpired(Instant expiresAt) {
        return !expiresAt.isAfter(clock.instant());
    }
}
