package io.floci.aws.services.dynamodb;

import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The busy marks a write transaction leaves on its items, and the emulation's switch. */
class DynamoDbTransactionConflictsTest {

    private static final DynamoDbTransactionConflicts.Item HOT = new DynamoDbTransactionConflicts.Item("000000000000/us-east-1::t", "hot");

    private final AtomicLong clock = new AtomicLong(5_000_000_000L);
    private final AtomicInteger waits = new AtomicInteger();
    private final DynamoDbTransactionConflicts conflicts = new DynamoDbTransactionConflicts(true, Duration.ofMillis(10),
            clock::get, (deadline, nanoTime) -> waits.incrementAndGet());

    // Catches: the default doing anything at all, so turning the emulation on is the only way to change behaviour.
    @Test
    void offIsTheDefaultAndHoldsNothing() {
        DynamoDbTransactionConflicts off = DynamoDbTransactionConflicts.of(false);

        assertSame(DynamoDbTransactionConflicts.OFF, off);
        assertFalse(off.enabled());
        assertEquals(0, off.hold(List.of(HOT)));
        assertFalse(off.busy(HOT.table(), HOT.itemKey()));
        off.rejectSingleItemWrite(HOT.table(), HOT.itemKey());
    }

    // Catches: a mark outliving its hold, a slow commit using up the window before the transaction answers, or a
    // single write going through on a held item.
    @Test
    void aMarkHoldsTheItemWhileItCommitsAndForTheHoldAfter() {
        long mark = conflicts.hold(List.of(HOT));
        clock.addAndGet(Duration.ofSeconds(1).toNanos());

        assertTrue(conflicts.busy(HOT.table(), HOT.itemKey()), "a commit that takes long still holds its items");
        conflicts.settle(List.of(HOT), mark);
        assertTrue(conflicts.busy(HOT.table(), HOT.itemKey()));
        AwsException rejected = assertThrows(AwsException.class,
                () -> conflicts.rejectSingleItemWrite(HOT.table(), HOT.itemKey()));
        assertEquals("TransactionConflictException", rejected.getErrorCode());
        assertEquals("Transaction is ongoing for the item", rejected.getMessage());
        assertEquals(400, rejected.getHttpStatus());
        assertFalse(conflicts.busy(HOT.table(), "cold"));

        clock.addAndGet(Duration.ofMillis(10).toNanos());
        assertFalse(conflicts.busy(HOT.table(), HOT.itemKey()));
    }

    // Catches: a finished transaction freeing an item a later transaction holds, or a failed one waiting out a hold.
    @Test
    void releasingRemovesOnlyTheTransactionsOwnMarks() {
        long first = conflicts.settle(List.of(HOT), conflicts.hold(List.of(HOT)));
        conflicts.release(List.of(HOT), first, true);
        assertEquals(1, waits.get());
        assertFalse(conflicts.busy(HOT.table(), HOT.itemKey()));

        long earlier = conflicts.hold(List.of(HOT));
        clock.addAndGet(1);
        conflicts.hold(List.of(HOT));
        conflicts.release(List.of(HOT), earlier, false);
        assertEquals(1, waits.get());
        assertTrue(conflicts.busy(HOT.table(), HOT.itemKey()));

        conflicts.clear();
        assertFalse(conflicts.busy(HOT.table(), HOT.itemKey()));
    }

    // Catches: an interrupt ending a committed transaction's hold early, so overlapping requests get through while
    // it is still meant to be running, or the interrupt being swallowed.
    @Test
    void anInterruptDoesNotCutAHoldShort() throws Exception {
        DynamoDbTransactionConflicts parking = new DynamoDbTransactionConflicts(true, Duration.ofMillis(10),
                clock::get, DynamoDbTransactionConflicts::park);
        long deadline = parking.settle(List.of(HOT), parking.hold(List.of(HOT)));
        AtomicBoolean interruptKept = new AtomicBoolean();
        CountDownLatch answered = new CountDownLatch(1);
        Thread committer = Thread.ofPlatform().start(() -> {
            Thread.currentThread().interrupt();
            parking.release(List.of(HOT), deadline, true);
            interruptKept.set(Thread.currentThread().isInterrupted());
            answered.countDown();
        });

        await("the committer parks").atMost(Duration.ofSeconds(5))
                .until(() -> committer.getState() == Thread.State.TIMED_WAITING);
        assertTrue(parking.busy(HOT.table(), HOT.itemKey()), "an interrupted transaction still holds its items");
        assertEquals(1, answered.getCount());

        clock.addAndGet(Duration.ofMillis(10).toNanos());
        assertTrue(answered.await(5, TimeUnit.SECONDS), "the hold ends at its deadline");
        assertTrue(interruptKept.get(), "the interrupt status is restored");
        assertFalse(parking.busy(HOT.table(), HOT.itemKey()));
    }
}
