package io.floci.aws.services.dynamodb;

import io.floci.aws.core.common.AwsException;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/**
 * Opt-in emulation of the transaction conflicts DynamoDB reports under contention, switched on by
 * {@code floci.services.dynamodb.transaction-conflicts-enabled}.
 *
 * <p>On AWS a TransactWriteItems keeps each of its items busy from the moment the item accepts the
 * transaction until the transaction commits. The developer guide rejects a PutItem, UpdateItem or
 * DeleteItem on a busy item with {@code TransactionConflictException}, and cancels another
 * TransactWriteItems, or a TransactGetItems, that reaches one with the reason {@code TransactionConflict};
 * a GetItem is not affected. Floci applies a transaction in microseconds, so with the switch on a write
 * transaction marks its items busy from the moment they accept it until {@link #HOLD} after it commits, and
 * answers only when that time is up: a caller that waits for one transaction before it sends the next never
 * sees a conflict, callers that overlap do. The mark is a deadline kept beside the item, so no item lock is
 * held while a transaction waits.
 *
 * <p>Off, the default, every method is a no-op and overlapping transactions keep serializing on their
 * item locks.
 */
final class DynamoDbTransactionConflicts {

    /**
     * How long a write transaction keeps its items busy. DynamoDB answers in single-digit milliseconds, and
     * a write transaction takes about four times as long as a PutItem ("Distributed Transactions at Scale in
     * Amazon DynamoDB", USENIX ATC 2023).
     */
    static final Duration HOLD = Duration.ofMillis(10);

    static final String REASON = "TransactionConflict";

    /** The message AWS gives the {@code TransactionConflict} reason and {@code TransactionConflictException}. */
    static final String MESSAGE = "Transaction is ongoing for the item";

    /** How far ahead a mark is dated while its transaction still commits, so it cannot lapse before {@link #settle}. */
    private static final long WHILE_COMMITTING_NANOS = Long.MAX_VALUE / 4;

    static final DynamoDbTransactionConflicts OFF = new DynamoDbTransactionConflicts(false, HOLD, System::nanoTime,
            DynamoDbTransactionConflicts::park);

    /** One item of one table: the table's account-scoped items key and the item key. */
    record Item(String table, String itemKey) {}

    /** Waits until {@code deadlineNanos} on the clock {@code nanoTime} reads. */
    @FunctionalInterface
    interface Waiter {
        void awaitDeadline(long deadlineNanos, LongSupplier nanoTime);
    }

    private final boolean enabled;
    private final long holdNanos;
    private final LongSupplier nanoTime;
    private final Waiter waiter;
    private final ConcurrentHashMap<Item, Long> busyUntil = new ConcurrentHashMap<>();

    DynamoDbTransactionConflicts(boolean enabled, Duration hold, LongSupplier nanoTime, Waiter waiter) {
        this.enabled = enabled;
        this.holdNanos = hold.toNanos();
        this.nanoTime = nanoTime;
        this.waiter = waiter;
    }

    /** The emulation the configuration asks for. */
    static DynamoDbTransactionConflicts of(boolean enabled) {
        return enabled ? new DynamoDbTransactionConflicts(true, HOLD, System::nanoTime,
                DynamoDbTransactionConflicts::park) : OFF;
    }

    boolean enabled() {
        return enabled;
    }

    /** Whether a write transaction in flight holds the item now. */
    boolean busy(String table, String itemKey) {
        if (!enabled) {
            return false;
        }
        Long until = busyUntil.get(new Item(table, itemKey));
        return until != null && until - nanoTime.getAsLong() > 0;
    }

    /** Rejects a single-item write, a PutItem, UpdateItem, DeleteItem or PartiQL statement, on a held item. */
    void rejectSingleItemWrite(String table, String itemKey) {
        if (busy(table, itemKey)) {
            throw new AwsException("TransactionConflictException", MESSAGE, 400);
        }
    }

    /**
     * Marks a write transaction's items busy, called while it holds their locks and after its conditions
     * held. The marks last until {@link #settle} dates them, however long the commit takes. Returns the
     * value the marks carry, or 0 when the emulation is off.
     */
    long hold(Collection<Item> items) {
        if (!enabled || items.isEmpty()) {
            return 0;
        }
        long mark = nanoTime.getAsLong() + WHILE_COMMITTING_NANOS;
        for (Item item : items) {
            busyUntil.put(item, mark);
        }
        return mark;
    }

    /**
     * Starts the {@link #HOLD} of a transaction that has committed, called while it still holds its item locks,
     * so the window counts from the moment it would answer. Returns the deadline its marks now carry, for
     * {@link #release}, or 0 when nothing was held.
     */
    long settle(Collection<Item> items, long mark) {
        if (mark == 0) {
            return 0;
        }
        long deadline = nanoTime.getAsLong() + holdNanos;
        for (Item item : items) {
            busyUntil.replace(item, mark, deadline);
        }
        return deadline;
    }

    /**
     * Ends a transaction's hold, called once it has released its item locks: a committed transaction first
     * waits out the hold, as it would still be running on AWS, and one that failed does not. Only marks
     * this transaction set are removed.
     */
    void release(Collection<Item> items, long deadline, boolean committed) {
        if (deadline == 0) {
            return;
        }
        try {
            if (committed) {
                waiter.awaitDeadline(deadline, nanoTime);
            }
        } finally {
            for (Item item : items) {
                busyUntil.remove(item, deadline);
            }
        }
    }

    /** Emulator reset: forgets every mark. */
    void clear() {
        busyUntil.clear();
    }

    /**
     * Waits until the deadline. An interrupt does not cut the hold short, since overlapping requests would then
     * get through while the transaction is still meant to be running: the wait goes on, and the interrupt
     * status is restored once it ends.
     */
    static void park(long deadlineNanos, LongSupplier nanoTime) {
        boolean interrupted = false;
        long remaining;
        while ((remaining = deadlineNanos - nanoTime.getAsLong()) > 0) {
            LockSupport.parkNanos(remaining);
            interrupted |= Thread.interrupted();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
