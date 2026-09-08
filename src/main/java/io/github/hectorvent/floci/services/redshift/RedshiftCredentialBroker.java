package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the short-lived credentials minted by GetClusterCredentials /
 * GetClusterCredentialsWithIAM. No real PostgreSQL role is created: a live
 * credential is a signal to the auth proxy and the Data API resolver that the
 * connection should run as the cluster master (the DbUser is nominal).
 */
@ApplicationScoped
public class RedshiftCredentialBroker implements Resettable {

    public enum Match { MASTER_EQUIVALENT, REJECT, PASSTHROUGH }

    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int PASSWORD_LENGTH = 32;

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, TempCredential> store = new ConcurrentHashMap<>();

    public TempCredential issue(String accountId, String clusterId, String dbUser,
                                List<String> dbGroups, int durationSeconds) {
        String password = randomPassword();
        Instant expiresAt = Instant.now().plusSeconds(durationSeconds);
        List<String> groups = dbGroups == null ? List.of() : List.copyOf(dbGroups);
        TempCredential credential = new TempCredential(dbUser, password, expiresAt, groups);
        store.put(key(accountId, clusterId, dbUser), credential);
        return credential;
    }

    public Optional<TempCredential> resolve(String accountId, String clusterId, String dbUser) {
        String key = key(accountId, clusterId, dbUser);
        TempCredential credential = store.get(key);
        if (credential == null) {
            return Optional.empty();
        }
        if (!credential.expiresAt().isAfter(Instant.now())) {
            store.remove(key, credential);
            return Optional.empty();
        }
        return Optional.of(credential);
    }

    public Match classify(String accountId, String clusterId, String username, String password) {
        Optional<TempCredential> credential = resolve(accountId, clusterId, username);
        if (credential.isEmpty()) {
            return Match.PASSTHROUGH;
        }
        return credential.get().password().equals(password) ? Match.MASTER_EQUIVALENT : Match.REJECT;
    }

    @Override
    public void clear() {
        store.clear();
    }

    private String randomPassword() {
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String key(String accountId, String clusterId, String dbUser) {
        return accountId + ":" + clusterId + ":" + dbUser;
    }
}
