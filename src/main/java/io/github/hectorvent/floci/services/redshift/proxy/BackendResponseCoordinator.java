package io.github.hectorvent.floci.services.redshift.proxy;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

final class BackendResponseCoordinator {

    private final ExtendedQuerySession session;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition changed = lock.newCondition();
    private final ArrayDeque<PendingOperation> pending = new ArrayDeque<>();
    private final Map<Long, GateResult> resolvedGates = new HashMap<>();
    private long nextSequence = 1;
    private boolean discardingUntilSync;
    private boolean closed;
    private char lastReadyStatus = 'I';

    BackendResponseCoordinator(ExtendedQuerySession session) {
        this.session = session;
    }

    Ticket register(Operation operation, ExtendedQuerySession.Mutation mutation) {
        lock.lock();
        try {
            Ticket ticket = new Ticket(nextSequence++, operation);
            if (discardingUntilSync && operation != Operation.SYNC) {
                if (mutation != null) {
                    session.rejectFrom(mutation);
                }
                if (operation == Operation.EXECUTE) {
                    resolvedGates.put(ticket.sequence(), GateResult.SKIPPED_AFTER_ERROR);
                }
            } else {
                pending.addLast(new PendingOperation(ticket, mutation));
            }
            changed.signalAll();
            return ticket;
        } finally {
            lock.unlock();
        }
    }

    GateResult awaitTurn(Ticket ticket) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                if (closed) {
                    return GateResult.CLOSED;
                }
                GateResult resolved = resolvedGates.remove(ticket.sequence());
                if (resolved != null) {
                    return resolved;
                }
                PendingOperation head = pending.peekFirst();
                if (!discardingUntilSync && head != null && head.ticket().equals(ticket)) {
                    return GateResult.READY;
                }
                changed.await();
            }
        } finally {
            lock.unlock();
        }
    }

    GateResult awaitExtendedExecuteTurn(Ticket ticket) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                if (closed) {
                    return GateResult.CLOSED;
                }
                GateResult resolved = resolvedGates.remove(ticket.sequence());
                if (resolved != null) {
                    return resolved;
                }
                if (!discardingUntilSync && canOwnExtendedExecute(ticket)) {
                    return GateResult.READY;
                }
                changed.await();
            }
        } finally {
            lock.unlock();
        }
    }

    boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
        long remaining = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while ((!pending.isEmpty() || discardingUntilSync) && !closed) {
                if (remaining <= 0) {
                    return false;
                }
                remaining = changed.awaitNanos(remaining);
            }
            return !closed && pending.isEmpty() && !discardingUntilSync;
        } finally {
            lock.unlock();
        }
    }

    void onBackendFrame(char type, byte[] body) {
        lock.lock();
        try {
            if (type == 'Z') {
                updateReadyStatus(body);
            }
            if (isAsynchronous(type)) {
                return;
            }

            PendingOperation head = pending.peekFirst();
            if (head == null) {
                changed.signalAll();
                return;
            }

            Operation operation = head.ticket().operation();
            if (type == 'E') {
                if (operation != Operation.SIMPLE_QUERY) {
                    pending.removeFirst();
                    reject(head);
                    resolveSkippedGate(head);
                    discardingUntilSync = true;
                    discardQueuedOperationsBeforeSync();
                }
                changed.signalAll();
                return;
            }

            if (completes(operation, type)) {
                pending.removeFirst();
                confirm(head);
                if (operation == Operation.SYNC) {
                    discardingUntilSync = false;
                }
                changed.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    void completeOwnedExecute(Ticket ticket, boolean failed) {
        lock.lock();
        try {
            PendingOperation owned = pending.stream()
                    .filter(operation -> operation.ticket().equals(ticket))
                    .findFirst()
                    .orElse(null);
            if (owned == null) {
                return;
            }
            if (ticket.operation() != Operation.EXECUTE) {
                throw new IllegalStateException("Only an Execute ticket can be completed as backend-owned");
            }
            pending.remove(owned);
            confirm(owned);
            if (failed) {
                discardingUntilSync = true;
                discardQueuedOperationsBeforeSync();
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    boolean isDiscardingUntilSync() {
        lock.lock();
        try {
            return discardingUntilSync;
        } finally {
            lock.unlock();
        }
    }

    char lastReadyStatus() {
        lock.lock();
        try {
            return lastReadyStatus;
        } finally {
            lock.unlock();
        }
    }

    void close() {
        lock.lock();
        try {
            closed = true;
            pending.clear();
            resolvedGates.clear();
            session.clear();
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void discardQueuedOperationsBeforeSync() {
        while (!pending.isEmpty() && pending.peekFirst().ticket().operation() != Operation.SYNC) {
            PendingOperation skipped = pending.removeFirst();
            reject(skipped);
            resolveSkippedGate(skipped);
        }
    }

    private void resolveSkippedGate(PendingOperation operation) {
        if (operation.ticket().operation() == Operation.EXECUTE) {
            resolvedGates.put(operation.ticket().sequence(), GateResult.SKIPPED_AFTER_ERROR);
        }
    }

    private void updateReadyStatus(byte[] body) {
        if (body != null && body.length == 1) {
            lastReadyStatus = (char) body[0];
            if (lastReadyStatus == 'I') {
                session.transactionEnded();
            }
        }
    }

    private void confirm(PendingOperation operation) {
        if (operation.mutation() != null) {
            session.confirm(operation.mutation());
        }
    }

    private void reject(PendingOperation operation) {
        if (operation.mutation() != null) {
            session.rejectFrom(operation.mutation());
        }
    }

    private boolean canOwnExtendedExecute(Ticket ticket) {
        for (PendingOperation operation : pending) {
            if (operation.ticket().equals(ticket)) {
                return true;
            }
            if (!isExtendedExecutePreamble(operation.ticket().operation())) {
                return false;
            }
        }
        return false;
    }

    private static boolean isExtendedExecutePreamble(Operation operation) {
        return operation == Operation.PARSE
                || operation == Operation.BIND
                || operation == Operation.DESCRIBE_STATEMENT
                || operation == Operation.DESCRIBE_PORTAL;
    }

    private static boolean isAsynchronous(char type) {
        return type == 'N' || type == 'A' || type == 'S';
    }

    private static boolean completes(Operation operation, char responseType) {
        return switch (operation) {
            case SIMPLE_QUERY, SYNC -> responseType == 'Z';
            case PARSE -> responseType == '1';
            case BIND -> responseType == '2';
            case DESCRIBE_STATEMENT, DESCRIBE_PORTAL -> responseType == 'T' || responseType == 'n';
            case CLOSE -> responseType == '3';
            case EXECUTE -> responseType == 'C' || responseType == 'I' || responseType == 's';
        };
    }

    enum Operation {
        SIMPLE_QUERY,
        PARSE,
        BIND,
        DESCRIBE_STATEMENT,
        DESCRIBE_PORTAL,
        CLOSE,
        EXECUTE,
        SYNC
    }

    enum GateResult {
        READY,
        SKIPPED_AFTER_ERROR,
        CLOSED
    }

    record Ticket(long sequence, Operation operation) {
    }

    private record PendingOperation(Ticket ticket, ExtendedQuerySession.Mutation mutation) {
    }
}
