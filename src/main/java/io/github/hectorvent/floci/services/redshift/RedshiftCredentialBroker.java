package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds the short-lived credentials minted by GetClusterCredentials /
 * GetClusterCredentialsWithIAM. No real PostgreSQL role is created: a live
 * credential is a signal to the auth proxy and the Data API resolver that the
 * connection should run as the cluster master (the DbUser is nominal).
 *
 * <p>AWS keeps every issued credential valid until its own expiry, so a second
 * call for the same DbUser does not invalidate the first: each key holds a list
 * of unexpired credentials.
 */
@ApplicationScoped
public class RedshiftCredentialBroker implements Resettable {

    public enum Match { MASTER_EQUIVALENT, REJECT, PASSTHROUGH }

    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int PASSWORD_LENGTH = 32;

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<TempCredential>> store = new ConcurrentHashMap<>();

    public TempCredential issue(String accountId, String clusterId, String dbUser,
                                List<String> dbGroups, int durationSeconds) {
        String password = randomPassword();
        Instant expiresAt = Instant.now().plusSeconds(durationSeconds);
        List<String> groups = dbGroups == null ? List.of() : List.copyOf(dbGroups);
        TempCredential credential = new TempCredential(dbUser, password, expiresAt, groups);
        store.compute(key(accountId, clusterId, dbUser), (k, existing) -> {
            CopyOnWriteArrayList<TempCredential> list =
                    existing == null ? new CopyOnWriteArrayList<>() : existing;
            list.removeIf(RedshiftCredentialBroker::isExpired);
            list.add(credential);
            return list;
        });
        return credential;
    }

    public Optional<TempCredential> resolve(String accountId, String clusterId, String dbUser) {
        return liveCredentials(key(accountId, clusterId, dbUser)).stream().findFirst();
    }

    public Match classify(String accountId, String clusterId, String username, String password) {
        List<TempCredential> live = liveCredentials(key(accountId, clusterId, username));
        if (live.isEmpty()) {
            return Match.PASSTHROUGH;
        }
        return live.stream().anyMatch(c -> c.password().equals(password))
                ? Match.MASTER_EQUIVALENT
                : Match.REJECT;
    }

    /**
     * Drops every credential minted for a cluster. Called when a cluster is deleted so a
     * later cluster created with the same identifier does not inherit master-equivalent
     * access from a stale credential.
     */
    public void revokeCluster(String accountId, String clusterId) {
        String prefix = accountId + ":" + clusterId + ":";
        store.keySet().removeIf(k -> k.startsWith(prefix));
    }

    @Override
    public void clear() {
        store.clear();
    }

    // Package-private hook for tests: number of unexpired credentials held for a DbUser.
    int liveCredentialCountForTesting(String accountId, String clusterId, String dbUser) {
        return liveCredentials(key(accountId, clusterId, dbUser)).size();
    }

    private List<TempCredential> liveCredentials(String key) {
        CopyOnWriteArrayList<TempCredential> list = store.get(key);
        if (list == null) {
            return List.of();
        }
        return list.stream().filter(c -> !isExpired(c)).toList();
    }

    private static boolean isExpired(TempCredential credential) {
        return !credential.expiresAt().isAfter(Instant.now());
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
