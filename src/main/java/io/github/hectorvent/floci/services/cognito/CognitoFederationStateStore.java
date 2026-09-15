package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.services.cognito.model.CognitoAuthorizationCode;
import io.github.hectorvent.floci.services.cognito.model.CognitoAuthorizationTransaction;
import jakarta.enterprise.context.ApplicationScoped;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class CognitoFederationStateStore {

    private static final int OPAQUE_KEY_BYTES = 32;

    private final ConcurrentHashMap<String, CognitoAuthorizationTransaction> transactions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CognitoAuthorizationCode> authorizationCodes = new ConcurrentHashMap<>();
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public CognitoFederationStateStore(Clock clock) {
        this.clock = clock;
    }

    public String putTransaction(CognitoAuthorizationTransaction transaction) {
        return put(transactions, transaction);
    }

    public Optional<CognitoAuthorizationTransaction> consumeTransaction(String state) {
        CognitoAuthorizationTransaction transaction = transactions.get(state);
        if (transaction == null || isExpired(transaction.expiresAt())) {
            if (transaction != null) {
                transactions.remove(state, transaction);
            }
            return Optional.empty();
        }
        return transactions.remove(state, transaction) ? Optional.of(transaction) : Optional.empty();
    }

    public String putAuthorizationCode(CognitoAuthorizationCode authorizationCode) {
        return put(authorizationCodes, authorizationCode);
    }

    public Optional<CognitoAuthorizationCode> consumeAuthorizationCode(String code) {
        CognitoAuthorizationCode authorizationCode = authorizationCodes.get(code);
        if (authorizationCode == null || isExpired(authorizationCode.expiresAt())) {
            if (authorizationCode != null) {
                authorizationCodes.remove(code, authorizationCode);
            }
            return Optional.empty();
        }
        return authorizationCodes.remove(code, authorizationCode) ? Optional.of(authorizationCode) : Optional.empty();
    }

    private <T> String put(ConcurrentHashMap<String, T> store, T value) {
        String key;
        do {
            key = generateOpaqueKey();
        } while (store.putIfAbsent(key, value) != null);
        return key;
    }

    private boolean isExpired(Instant expiresAt) {
        return !expiresAt.isAfter(clock.instant());
    }

    private String generateOpaqueKey() {
        byte[] bytes = new byte[OPAQUE_KEY_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
