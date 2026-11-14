package io.floci.aws.services.redshift;

import io.floci.aws.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reference-counts temporary {@code DbGroups} memberships granted for active sessions so
 * concurrent sessions sharing a role and group do not revoke each other's access prematurely,
 * and pre-existing permanent memberships are left untouched.
 */
@ApplicationScoped
public class TempGroupGrantTracker implements Resettable {

    private static final Logger LOG = Logger.getLogger(TempGroupGrantTracker.class);

    @FunctionalInterface
    public interface GroupAction {
        void execute(String group) throws SQLException;
    }

    @FunctionalInterface
    public interface MembershipCheck {
        boolean isMember(String group) throws SQLException;
    }

    public static final class Lease {
        private final TempGroupGrantTracker tracker;
        private final String backendKey;
        private final String roleName;
        private final List<String> groups;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Lease(TempGroupGrantTracker tracker, String backendKey, String roleName, List<String> groups) {
            this.tracker = tracker;
            this.backendKey = backendKey;
            this.roleName = roleName;
            this.groups = List.copyOf(groups);
        }

        public void release(GroupAction revokeAction) throws SQLException {
            if (!released.compareAndSet(false, true) || groups.isEmpty()) {
                return;
            }
            tracker.releaseGroups(backendKey, roleName, groups, revokeAction);
        }
    }

    private record Key(String backendKey, String roleName, String groupName) { }

    private final ConcurrentHashMap<Key, Integer> activeCounts = new ConcurrentHashMap<>();
    private final Set<Key> pendingRevokes = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Key, Object> keyLocks = new ConcurrentHashMap<>();

    public Lease acquire(String backendKey, String roleName, List<String> groupsToGrant,
                         GroupAction grantAction) throws SQLException {
        return acquire(backendKey, roleName, groupsToGrant, grantAction, null, null);
    }

    public Lease acquire(String backendKey, String roleName, List<String> groupsToGrant,
                         GroupAction grantAction, GroupAction rollbackRevokeAction) throws SQLException {
        return acquire(backendKey, roleName, groupsToGrant, grantAction, rollbackRevokeAction, null);
    }

    public Lease acquire(String backendKey, String roleName, List<String> groupsToGrant,
                         GroupAction grantAction, GroupAction rollbackRevokeAction,
                         MembershipCheck alreadyMemberCheck) throws SQLException {
        if (groupsToGrant == null || groupsToGrant.isEmpty()) {
            return new Lease(this, backendKey, roleName, List.of());
        }
        Set<String> distinct = new LinkedHashSet<>(groupsToGrant);
        List<String> acquired = new ArrayList<>();
        try {
            for (String group : distinct) {
                Key key = new Key(backendKey, roleName, group);
                synchronized (lockFor(key)) {
                    int current = activeCounts.getOrDefault(key, 0);
                    boolean hasPendingRevoke = pendingRevokes.contains(key);
                    if (current == 0 && !hasPendingRevoke) {
                        if (alreadyMemberCheck != null && alreadyMemberCheck.isMember(group)) {
                            continue;
                        }
                        grantAction.execute(group);
                    } else if (hasPendingRevoke) {
                        pendingRevokes.remove(key);
                    }
                    activeCounts.put(key, current + 1);
                    acquired.add(group);
                }
            }
        } catch (SQLException | RuntimeException failure) {
            rollbackAcquired(backendKey, roleName, acquired, rollbackRevokeAction);
            throw failure;
        }
        return new Lease(this, backendKey, roleName, acquired);
    }

    private void rollbackAcquired(String backendKey, String roleName, List<String> acquired,
                                  GroupAction rollbackRevokeAction) {
        for (int i = acquired.size() - 1; i >= 0; i--) {
            String group = acquired.get(i);
            Key key = new Key(backendKey, roleName, group);
            synchronized (lockFor(key)) {
                int current = activeCounts.getOrDefault(key, 0);
                if (current <= 1) {
                    activeCounts.remove(key);
                    if (rollbackRevokeAction != null) {
                        try {
                            rollbackRevokeAction.execute(group);
                            pendingRevokes.remove(key);
                        } catch (SQLException | RuntimeException failure) {
                            pendingRevokes.add(key);
                            LOG.warnv(failure,
                                    "Failed to revoke temporary DbGroup {0} for role {1} during rollback",
                                    group, roleName);
                        }
                    }
                } else {
                    activeCounts.put(key, current - 1);
                }
            }
        }
    }

    private void releaseGroups(String backendKey, String roleName, List<String> groups,
                               GroupAction revokeAction) throws SQLException {
        SQLException firstFailure = null;
        for (String group : groups) {
            Key key = new Key(backendKey, roleName, group);
            synchronized (lockFor(key)) {
                int current = activeCounts.getOrDefault(key, 0);
                if (current <= 1) {
                    if (current == 1 && revokeAction != null) {
                        try {
                            revokeAction.execute(group);
                            activeCounts.remove(key);
                            pendingRevokes.remove(key);
                        } catch (SQLException failure) {
                            activeCounts.remove(key);
                            pendingRevokes.add(key);
                            if (firstFailure == null) {
                                firstFailure = failure;
                            } else {
                                firstFailure.addSuppressed(failure);
                            }
                        } catch (RuntimeException failure) {
                            activeCounts.remove(key);
                            pendingRevokes.add(key);
                            throw failure;
                        }
                    } else {
                        activeCounts.remove(key);
                        pendingRevokes.remove(key);
                    }
                } else {
                    activeCounts.put(key, current - 1);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private Object lockFor(Key key) {
        return keyLocks.computeIfAbsent(key, ignored -> new Object());
    }

    public void revokeBackend(String backendKey) {
        activeCounts.keySet().removeIf(k -> k.backendKey().equals(backendKey));
        pendingRevokes.removeIf(k -> k.backendKey().equals(backendKey));
        keyLocks.keySet().removeIf(k -> k.backendKey().equals(backendKey));
    }

    @Override
    public void clear() {
        activeCounts.clear();
        pendingRevokes.clear();
        keyLocks.clear();
    }
}
