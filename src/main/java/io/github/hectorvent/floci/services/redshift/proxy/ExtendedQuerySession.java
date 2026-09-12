package io.github.hectorvent.floci.services.redshift.proxy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class ExtendedQuerySession {

    private final Map<String, CopyStatementParser.S3Statement> statements = new LinkedHashMap<>();
    private final Map<String, String> portals = new LinkedHashMap<>();
    private final List<JournalEntry> journal = new ArrayList<>();
    private long nextMutationId = 1;

    synchronized Mutation stageParse(String statementName, CopyStatementParser.S3Statement statement) {
        Mutation mutation = snapshot();
        if (statement == null) {
            statements.remove(statementName);
        } else {
            statements.put(statementName, statement);
        }
        portals.entrySet().removeIf(entry -> entry.getValue().equals(statementName));
        return mutation;
    }

    synchronized Mutation stageBind(String portalName, String statementName) {
        Mutation mutation = snapshot();
        if (statements.containsKey(statementName)) {
            portals.put(portalName, statementName);
        } else {
            portals.remove(portalName);
        }
        return mutation;
    }

    synchronized Mutation stageClose(char targetType, String name) {
        if (targetType != 'S' && targetType != 'P') {
            throw new IllegalArgumentException("Close target type must be S or P");
        }

        Mutation mutation = snapshot();
        if (targetType == 'S') {
            statements.remove(name);
            portals.entrySet().removeIf(entry -> entry.getValue().equals(name));
        } else {
            portals.remove(name);
        }
        return mutation;
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

        JournalEntry rejected = journal.get(rejectedIndex);
        statements.clear();
        statements.putAll(rejected.statementsBefore());
        portals.clear();
        portals.putAll(rejected.portalsBefore());
        journal.subList(rejectedIndex, journal.size()).clear();
    }

    synchronized Optional<CopyStatementParser.S3Statement> statement(String statementName) {
        return Optional.ofNullable(statements.get(statementName));
    }

    synchronized Optional<CopyStatementParser.S3Statement> portal(String portalName) {
        String statementName = portals.get(portalName);
        return Optional.ofNullable(statementName).map(statements::get);
    }

    synchronized void clearPortals() {
        portals.clear();
    }

    synchronized void transactionEnded() {
        if (journal.isEmpty()) {
            portals.clear();
            return;
        }

        Map<String, String> expiredPortals = journal.get(0).portalsBefore();
        removeUnchangedExpiredPortals(portals, expiredPortals);
        for (int i = 0; i < journal.size(); i++) {
            JournalEntry entry = journal.get(i);
            Map<String, String> portalsBefore = new LinkedHashMap<>(entry.portalsBefore());
            removeUnchangedExpiredPortals(portalsBefore, expiredPortals);
            journal.set(i, new JournalEntry(entry.mutation(), entry.statementsBefore(), portalsBefore));
        }
    }

    synchronized void clear() {
        statements.clear();
        portals.clear();
        journal.clear();
    }

    private Mutation snapshot() {
        Mutation mutation = new Mutation(nextMutationId++);
        journal.add(new JournalEntry(
                mutation,
                new LinkedHashMap<>(statements),
                new LinkedHashMap<>(portals)));
        return mutation;
    }

    private static void removeUnchangedExpiredPortals(Map<String, String> candidates,
            Map<String, String> expiredPortals) {
        candidates.entrySet().removeIf(entry -> expiredPortals.containsKey(entry.getKey())
                && Objects.equals(expiredPortals.get(entry.getKey()), entry.getValue()));
    }

    record Mutation(long id) {
    }

    private record JournalEntry(
            Mutation mutation,
            Map<String, CopyStatementParser.S3Statement> statementsBefore,
            Map<String, String> portalsBefore) {
    }
}
