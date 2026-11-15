package io.floci.aws.services.redshift.proxy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class ExtendedQuerySession {

    private final Map<String, PreparedSql> statements = new LinkedHashMap<>();
    private final Map<String, BoundPortal> portals = new LinkedHashMap<>();
    private final List<JournalEntry> journal = new ArrayList<>();
    private long nextMutationId = 1;

    synchronized Mutation stageParse(String statementName, CopyStatementParser.S3Statement statement) {
        return stageParse(statementName, statement, null);
    }

    synchronized Mutation stageParse(String statementName, CopyStatementParser.S3Statement statement, String sql) {
        Map<String, PreparedSql> statementsBefore = new LinkedHashMap<>(statements);
        Map<String, BoundPortal> portalsBefore = new LinkedHashMap<>(portals);
        if (statement == null && sql == null) {
            statements.remove(statementName);
        } else {
            statements.put(statementName, new PreparedSql(statement, sql));
        }
        portals.entrySet().removeIf(entry -> entry.getValue().statementName().equals(statementName));
        return record(statementsBefore, portalsBefore);
    }

    synchronized Mutation stageBind(String portalName, String statementName) {
        Map<String, PreparedSql> statementsBefore = new LinkedHashMap<>(statements);
        Map<String, BoundPortal> portalsBefore = new LinkedHashMap<>(portals);
        if (statements.containsKey(statementName)) {
            portals.put(portalName, new BoundPortal(statementName, nextMutationId));
        } else {
            portals.remove(portalName);
        }
        return record(statementsBefore, portalsBefore);
    }

    synchronized Mutation stageClose(char targetType, String name) {
        if (targetType != 'S' && targetType != 'P') {
            throw new IllegalArgumentException("Close target type must be S or P");
        }

        Map<String, PreparedSql> statementsBefore = new LinkedHashMap<>(statements);
        Map<String, BoundPortal> portalsBefore = new LinkedHashMap<>(portals);
        if (targetType == 'S') {
            statements.remove(name);
            portals.entrySet().removeIf(entry -> entry.getValue().statementName().equals(name));
        } else {
            portals.remove(name);
        }
        return record(statementsBefore, portalsBefore);
    }

    synchronized void confirm(Mutation mutation) {
        journal.removeIf(entry -> entry.mutation().equals(mutation));
    }

    synchronized void rejectFrom(Mutation mutation) {
        int rejectedIndex = -1;
        for (int i = 0; i < journal.size(); i++) {
            if (journal.get(i).mutation().equals(mutation)) {
                rejectedIndex = i;
                break;
            }
        }
        if (rejectedIndex < 0) {
            return;
        }

        // The caller rejects one pipelined mutation at a time, in journal order (see
        // BackendResponseCoordinator#discardQueuedOperationsBeforeSync), so this only ever needs to
        // undo the rejected mutation's own effect. A later mutation that built on it (e.g. a Bind
        // naming the statement this Parse defined) gets its own rejectFrom call once the coordinator
        // reaches it; blindly restoring the whole map back to this entry's "before" would also wipe
        // out any later, independent mutation that happened to run first (see
        // rejectingAnEarlierMutationRetainsLaterSyncCycleMutation). The entry's own "after" snapshot
        // is used rather than the live map or the next entry's "before": both go stale once an
        // earlier reject's cascading portal cleanup has already touched a key this mutation never
        // itself changed.
        JournalEntry rejected = journal.get(rejectedIndex);
        revertUnchangedSince(statements, rejected.statementsBefore(), rejected.statementsAfter());
        revertUnchangedSince(portals, rejected.portalsBefore(), rejected.portalsAfter());
        // A statement this mutation defined may just have been reverted away; drop any portal now
        // pointing at a statement that no longer exists, the same cleanup stageClose('S', ...) does.
        portals.entrySet().removeIf(entry -> !statements.containsKey(entry.getValue().statementName()));

        // Later rollbacks must not restore a binding this rejection has invalidated.
        for (int i = rejectedIndex + 1; i < journal.size(); i++) {
            JournalEntry entry = journal.get(i);
            Map<String, BoundPortal> portalsBefore = new LinkedHashMap<>(entry.portalsBefore());
            Map<String, BoundPortal> portalsAfter = new LinkedHashMap<>(entry.portalsAfter());
            revertUnchangedSince(portalsBefore, rejected.portalsBefore(), rejected.portalsAfter());
            revertUnchangedSince(portalsAfter, rejected.portalsBefore(), rejected.portalsAfter());
            journal.set(i, new JournalEntry(entry.mutation(), entry.statementsBefore(), portalsBefore,
                    entry.statementsAfter(), portalsAfter));
        }
        journal.remove(rejectedIndex);
    }

    /**
     * Reverts {@code live}'s entries for keys the rejected mutation changed (where {@code before}
     * and {@code after} differ), but only where the live map still holds the value the mutation set
     * ({@code after}): a key a later mutation has since changed again is left alone, since that
     * later mutation now owns it and will be reverted by its own {@link #rejectFrom} call if needed.
     */
    private static <K, V> void revertUnchangedSince(Map<K, V> live, Map<K, V> before, Map<K, V> after) {
        Set<K> touchedKeys = new LinkedHashSet<>(before.keySet());
        touchedKeys.addAll(after.keySet());
        for (K key : touchedKeys) {
            V beforeValue = before.get(key);
            V afterValue = after.get(key);
            if (Objects.equals(beforeValue, afterValue)) {
                continue;
            }
            if (!Objects.equals(live.get(key), afterValue)) {
                continue;
            }
            if (beforeValue == null) {
                live.remove(key);
            } else {
                live.put(key, beforeValue);
            }
        }
    }

    synchronized Optional<CopyStatementParser.S3Statement> statement(String statementName) {
        return Optional.ofNullable(statements.get(statementName)).map(PreparedSql::statement);
    }

    synchronized Optional<CopyStatementParser.S3Statement> portal(String portalName) {
        return Optional.ofNullable(portals.get(portalName)).map(BoundPortal::statementName)
                .map(statements::get).map(PreparedSql::statement);
    }

    synchronized Optional<String> portalSql(String portalName) {
        return Optional.ofNullable(portals.get(portalName)).map(BoundPortal::statementName)
                .map(statements::get).map(PreparedSql::sql);
    }

    synchronized void clearPortals() {
        portals.clear();
    }

    synchronized void transactionEnded() {
        if (journal.isEmpty()) {
            portals.clear();
            return;
        }

        Map<String, BoundPortal> expiredPortals = journal.get(0).portalsBefore();
        removeUnchangedExpiredPortals(portals, expiredPortals);
        for (int i = 0; i < journal.size(); i++) {
            JournalEntry entry = journal.get(i);
            Map<String, BoundPortal> portalsBefore = new LinkedHashMap<>(entry.portalsBefore());
            removeUnchangedExpiredPortals(portalsBefore, expiredPortals);
            journal.set(i, new JournalEntry(entry.mutation(), entry.statementsBefore(), portalsBefore,
                    entry.statementsAfter(), entry.portalsAfter()));
        }
    }

    synchronized void clear() {
        statements.clear();
        portals.clear();
        journal.clear();
    }

    /**
     * Journals a mutation with both its "before" snapshots (passed in, captured prior to the
     * caller's own action) and its "after" snapshots, captured here immediately once that action has
     * been applied. Storing "after" explicitly, rather than reconstructing it later from the next
     * entry's "before" or the live maps, keeps it a fixed historical fact: those two proxies go
     * stale once an earlier {@link #rejectFrom} has already run its own cascading portal cleanup.
     */
    private Mutation record(Map<String, PreparedSql> statementsBefore,
                            Map<String, BoundPortal> portalsBefore) {
        Mutation mutation = new Mutation(nextMutationId++);
        journal.add(new JournalEntry(
                mutation,
                statementsBefore,
                portalsBefore,
                new LinkedHashMap<>(statements),
                new LinkedHashMap<>(portals)));
        return mutation;
    }

    private static void removeUnchangedExpiredPortals(Map<String, BoundPortal> candidates,
            Map<String, BoundPortal> expiredPortals) {
        candidates.entrySet().removeIf(entry -> expiredPortals.containsKey(entry.getKey())
                && Objects.equals(expiredPortals.get(entry.getKey()), entry.getValue()));
    }

    record Mutation(long id) {
    }

    // Rebinding the same name to the same statement still creates a new portal lifetime.
    private record BoundPortal(String statementName, long bindingId) { }

    private record PreparedSql(CopyStatementParser.S3Statement statement, String sql) { }

    private record JournalEntry(
            Mutation mutation,
            Map<String, PreparedSql> statementsBefore,
            Map<String, BoundPortal> portalsBefore,
            Map<String, PreparedSql> statementsAfter,
            Map<String, BoundPortal> portalsAfter) {
    }
}
