package io.floci.aws.services.redshift;

import io.floci.aws.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reference-counts the temporary password an IAM-derived session logs in with, so PostgreSQL
 * authenticates the session as the role itself. A session that authenticated as the role cannot
 * switch to another user, whatever SQL it runs, because PostgreSQL authorizes
 * {@code SET SESSION AUTHORIZATION} against the authenticated user.
 *
 * <p>The first login of a role stores the role's current password hash and installs a random
 * password; logins in flight at the same time share it; the last one to finish restores the stored
 * hash. Callers release the lease as soon as their backend login completes, so the role's own
 * password works again while the session is open. A failed
 * restore keeps the stored hash so the next session does not mistake the temporary password for
 * the role's own.
 */
@ApplicationScoped
public class TempRolePasswordTracker implements Resettable {

    private static final Logger LOG = Logger.getLogger(TempRolePasswordTracker.class);

    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int PASSWORD_LENGTH = 32;

    @FunctionalInterface
    public interface PasswordReader {
        /** Returns the role's stored password hash, or {@code null} when it has none. */
        String read() throws SQLException;
    }

    @FunctionalInterface
    public interface PasswordWriter {
        /** Stores {@code password} (a pre-hashed value or plaintext); {@code null} removes it. */
        void write(String password) throws SQLException;
    }

    public static final class Lease {
        private final TempRolePasswordTracker tracker;
        private final Key key;
        private final String password;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Lease(TempRolePasswordTracker tracker, Key key, String password) {
            this.tracker = tracker;
            this.key = key;
            this.password = password;
        }

        public String password() {
            return password;
        }

        public void release(PasswordWriter restore) {
            if (released.compareAndSet(false, true)) {
                tracker.release(key, restore);
            }
        }
    }

    private record Key(String backendKey, String roleName) { }

    private static final class Entry {
        private int active;
        private String temporaryPassword;
        private String storedHash;
    }

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    // One lock per key that outlives its entry: acquire and release always take the same lock, so a
    // login waiting on it cannot carry on with an entry the last release has already removed.
    private final ConcurrentHashMap<Key, Object> keyLocks = new ConcurrentHashMap<>();

    public Lease acquire(String backendKey, String roleName, PasswordReader reader,
                         PasswordWriter writer) throws SQLException {
        Key key = new Key(backendKey, roleName);
        synchronized (lockFor(key)) {
            Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
            if (entry.active == 0) {
                if (entry.temporaryPassword == null) {
                    entry.storedHash = reader.read();
                }
                String temporary = randomPassword();
                writer.write(temporary);
                entry.temporaryPassword = temporary;
            }
            entry.active++;
            return new Lease(this, key, entry.temporaryPassword);
        }
    }

    private void release(Key key, PasswordWriter restore) {
        synchronized (lockFor(key)) {
            Entry entry = entries.get(key);
            if (entry == null) {
                return;
            }
            entry.active--;
            if (entry.active > 0) {
                return;
            }
            try {
                restore.write(entry.storedHash);
                entry.temporaryPassword = null;
                entry.storedHash = null;
                entries.remove(key);
            } catch (SQLException | RuntimeException failure) {
                // Keep the stored hash and temporary marker: the next acquire must restore the
                // original, not store the temporary password as if it were the role's own.
                LOG.warnv(failure, "Failed to restore the password of Redshift role {0}", key.roleName());
            }
        }
    }

    private String randomPassword() {
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    private Object lockFor(Key key) {
        return keyLocks.computeIfAbsent(key, ignored -> new Object());
    }

    public void revokeBackend(String backendKey) {
        entries.keySet().removeIf(k -> k.backendKey().equals(backendKey));
        keyLocks.keySet().removeIf(k -> k.backendKey().equals(backendKey));
    }

    @Override
    public void clear() {
        entries.clear();
        keyLocks.clear();
    }
}
