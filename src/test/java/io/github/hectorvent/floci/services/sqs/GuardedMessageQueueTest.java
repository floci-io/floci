package io.github.hectorvent.floci.services.sqs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.HybridStorage;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.WalStorage;
import io.github.hectorvent.floci.services.sqs.GuardedMessageQueue.ClaimResult;
import io.github.hectorvent.floci.services.sqs.GuardedMessageQueue.HandleResult;
import io.github.hectorvent.floci.services.sqs.GuardedMessageQueue.MessageCounts;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuardedMessageQueueTest {

    private static final String KEY = "us-east-1::/000000000000/failure";

    @TempDir
    Path directory;

    private GuardedMessageQueue queue;

    @BeforeEach
    void setUp() {
        queue = new GuardedMessageQueue(null, null);
    }

    // --- Basic operations ---

    @Test
    void addAndClaimSingleMessage() {
        queue.addMessage(new Message("hello"));

        ClaimResult result = queue.claimVisibleMessages(1, 30, false, -1, null);
        assertEquals(1, result.claimed().size());
        assertEquals("hello", result.claimed().get(0).getBody());
        assertNotNull(result.claimed().get(0).getReceiptHandle());
        assertEquals(1, result.claimed().get(0).getReceiveCount());
        assertTrue(result.dlqCandidates().isEmpty());
    }

    @Test
    void claimEmptyQueueReturnsEmpty() {
        ClaimResult result = queue.claimVisibleMessages(1, 30, false, -1, null);
        assertTrue(result.claimed().isEmpty());
        assertTrue(result.dlqCandidates().isEmpty());
    }

    @Test
    void claimedMessageBecomesInvisible() {
        queue.addMessage(new Message("msg1"));

        ClaimResult first = queue.claimVisibleMessages(1, 30, false, -1, null);
        assertEquals(1, first.claimed().size());

        ClaimResult second = queue.claimVisibleMessages(1, 30, false, -1, null);
        assertTrue(second.claimed().isEmpty());
    }

    @Test
    void claimMultipleMessages() {
        queue.addMessage(new Message("msg1"));
        queue.addMessage(new Message("msg2"));
        queue.addMessage(new Message("msg3"));

        ClaimResult result = queue.claimVisibleMessages(3, 30, false, -1, null);
        assertEquals(3, result.claimed().size());
    }

    @Test
    void claimRespectsMaxMessages() {
        queue.addMessage(new Message("msg1"));
        queue.addMessage(new Message("msg2"));
        queue.addMessage(new Message("msg3"));

        ClaimResult result = queue.claimVisibleMessages(2, 30, false, -1, null);
        assertEquals(2, result.claimed().size());
    }

    @Test
    void removeByReceiptHandle() {
        queue.addMessage(new Message("to-delete"));

        ClaimResult claimed = queue.claimVisibleMessages(1, 30, false, -1, null);
        String handle = claimed.claimed().get(0).getReceiptHandle();

        assertEquals(HandleResult.APPLIED, queue.removeByReceiptHandle(ReceiptHandle.parse(handle), false).result());

        // Message should be gone even with visibility timeout 0
        ClaimResult result = queue.claimVisibleMessages(1, 0, false, -1, null);
        assertTrue(result.claimed().isEmpty());
    }

    @Test
    void removeByReceiptHandleInvalidReturnsEmpty() {
        assertEquals(HandleResult.MESSAGE_GONE, queue.removeByReceiptHandle(ReceiptHandle.issue(null, "nonexistent", ReceiptHandle.DEFAULT_SECRET), false).result());
    }

    @Test
    void changeVisibility() {
        queue.addMessage(new Message("msg"));

        ClaimResult claimed = queue.claimVisibleMessages(1, 30, false, -1, null);
        String handle = claimed.claimed().get(0).getReceiptHandle();

        // Set visibility to 0 — message becomes visible immediately
        assertEquals(HandleResult.APPLIED, queue.changeVisibility(ReceiptHandle.parse(handle), 0, false));

        ClaimResult reClaimed = queue.claimVisibleMessages(1, 30, false, -1, null);
        assertEquals(1, reClaimed.claimed().size());
    }

    @Test
    void changeVisibilityInvalidReturnsFalse() {
        assertEquals(HandleResult.MESSAGE_GONE, queue.changeVisibility(ReceiptHandle.issue(null, "nonexistent", ReceiptHandle.DEFAULT_SECRET), 0, false));
    }

    @Test
    void purge() {
        queue.addMessage(new Message("msg1"));
        queue.addMessage(new Message("msg2"));

        queue.purge();

        ClaimResult result = queue.claimVisibleMessages(10, 30, false, -1, null);
        assertTrue(result.claimed().isEmpty());
    }

    @Test
    void drainAllAndAddAll() {
        queue.addMessage(new Message("msg1"));
        queue.addMessage(new Message("msg2"));

        List<Message> drained = queue.drainAll();
        assertEquals(2, drained.size());
        assertTrue(queue.isEmpty());

        GuardedMessageQueue target = new GuardedMessageQueue(null, null);
        target.addAll(drained);

        ClaimResult result = target.claimVisibleMessages(10, 30, false, -1, null);
        assertEquals(2, result.claimed().size());
    }

    @Test
    void addAllRollsBackTheInMemoryAddWhenPersistFails() {
        FailingStore store = new FailingStore();
        GuardedMessageQueue target = new GuardedMessageQueue(store, KEY);
        target.addMessage(new Message("already-there"));
        store.fail = true;

        assertThrows(IllegalStateException.class, () -> target.addAll(List.of(new Message("incoming"))));

        assertEquals(List.of("already-there"), target.peekAll().stream().map(Message::getBody).toList());
        assertEquals(1, target.messageCounts().visible());
        assertEquals(List.of("already-there"), store.get(KEY).orElseThrow().stream().map(Message::getBody).toList());
    }

    @Test
    void addMessageRollsBackTheInMemoryAddWhenPersistFails() {
        FailingStore store = new FailingStore();
        GuardedMessageQueue target = new GuardedMessageQueue(store, KEY);
        target.addMessage(new Message("already-there"));
        store.fail = true;

        assertThrows(IllegalStateException.class, () -> target.addMessage(new Message("incoming")));

        assertEquals(List.of("already-there"), target.peekAll().stream().map(Message::getBody).toList());
        assertEquals(1, target.messageCounts().visible());
        assertEquals(List.of("already-there"), store.get(KEY).orElseThrow().stream().map(Message::getBody).toList());
    }

    @Test
    void messageCountsReturnsVisibleInFlightAndDelayed() {
        queue.addMessage(new Message("msg1"));
        queue.addMessage(new Message("msg2"));
        queue.addMessage(new Message("msg3"));
        Message delayedMessage = new Message("delayed");
        delayedMessage.setVisibleAt(Instant.now().plusSeconds(60));
        queue.addMessage(delayedMessage);

        MessageCounts counts = queue.messageCounts();
        assertEquals(3, counts.visible());
        assertEquals(0, counts.inFlight());
        assertEquals(1, counts.delayed(),
                "A never-claimed message with a future visibleAt is delayed, not in flight");

        queue.claimVisibleMessages(2, 30, false, -1, null);

        MessageCounts afterClaim = queue.messageCounts();
        assertEquals(1, afterClaim.visible());
        assertEquals(2, afterClaim.inFlight(),
                "Claimed messages are in flight, not delayed");
        assertEquals(1, afterClaim.delayed());
    }

    @Test
    void messageCountsDelayedMessageBecomesVisibleAfterDelay() {
        Message delayedMessage = new Message("delayed");
        delayedMessage.setVisibleAt(Instant.now().minusSeconds(1));
        queue.addMessage(delayedMessage);

        MessageCounts counts = queue.messageCounts();
        assertEquals(1, counts.visible(),
                "A message whose delay has elapsed counts as visible");
        assertEquals(0, counts.delayed());
    }

    // --- FIFO ---

    @Test
    void fifoClaimReturnsMultipleMessagesPerGroupWithinSingleCall() {
        // AWS FIFO: a single ReceiveMessage may return multiple messages from
        // the same MessageGroupId, preserving insertion order. The cross-call
        // group lock applies only against future calls.
        Message g1m1 = new Message("g1-msg1");
        g1m1.setMessageGroupId("group1");
        Message g1m2 = new Message("g1-msg2");
        g1m2.setMessageGroupId("group1");
        Message g2m1 = new Message("g2-msg1");
        g2m1.setMessageGroupId("group2");

        queue.addMessage(g1m1);
        queue.addMessage(g1m2);
        queue.addMessage(g2m1);

        ClaimResult first = queue.claimVisibleMessages(10, 30, true, -1, null);
        assertEquals(3, first.claimed().size(),
                "Single FIFO ReceiveMessage should return all visible messages up to MaxNumberOfMessages");
        List<String> bodies = first.claimed().stream().map(Message::getBody).toList();
        // Inter-group ordering is not guaranteed by FIFO; only within-group order is.
        assertTrue(bodies.contains("g2-msg1"), "batch must contain group2 message");
        int g1m1Idx = bodies.indexOf("g1-msg1");
        int g1m2Idx = bodies.indexOf("g1-msg2");
        assertTrue(g1m1Idx >= 0 && g1m2Idx >= 0, "batch must contain both group1 messages");
        assertTrue(g1m1Idx < g1m2Idx, "group1 messages must be in insertion order");

        // All groups now have in-flight messages — second call returns empty
        ClaimResult second = queue.claimVisibleMessages(10, 30, true, -1, null);
        assertTrue(second.claimed().isEmpty());
    }

    @Test
    void fifoDelayedMessageDoesNotLockItsMessageGroup() {
        // Only in-flight messages (claimed, within their visibility timeout)
        // lock a FIFO message group. A message still waiting out its
        // DelaySeconds was never claimed — no receipt handle — and must not
        // make an already-visible message in the same group unreceivable.
        Message visible = new Message("visible");
        visible.setMessageGroupId("group1");
        Message delayed = new Message("delayed");
        delayed.setMessageGroupId("group1");
        delayed.setVisibleAt(Instant.now().plusSeconds(60));

        queue.addMessage(visible);
        queue.addMessage(delayed);

        ClaimResult result = queue.claimVisibleMessages(10, 30, true, -1, null);
        assertEquals(1, result.claimed().size(),
                "A delayed message must not block its message group");
        assertEquals("visible", result.claimed().get(0).getBody());
    }

    // --- DLQ ---

    @Test
    void dlqCandidatesReturnedButNotRemovedFromSource() {
        queue.addMessage(new Message("msg"));

        // Claim and release (visibility=0) to bump receiveCount
        ClaimResult r1 = queue.claimVisibleMessages(1, 0, false, -1, null);
        assertEquals(1, r1.claimed().get(0).getReceiveCount());

        // Claim again — now receiveCount = 2, maxReceiveCount = 1 → DLQ candidate
        ClaimResult r2 = queue.claimVisibleMessages(1, 0, false, 1, "arn:aws:sqs:us-east-1:000000000000:dlq");
        assertTrue(r2.claimed().isEmpty());
        assertEquals(1, r2.dlqCandidates().size());

        // Message stays in source until explicitly removed
        assertFalse(queue.isEmpty());

        queue.removeMessages(r2.dlqCandidates());
        assertTrue(queue.isEmpty());
    }

    // --- Concurrency: the core bug fix ---

    @Test
    void concurrentReceiveNeverProducesDuplicateDeliveries() throws Exception {
        int messageCount = 100;
        int threadCount = 8;

        for (int i = 0; i < messageCount; i++) {
            queue.addMessage(new Message("msg-" + i));
        }

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            ConcurrentLinkedDeque<Message> allClaimed = new ConcurrentLinkedDeque<>();

            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    ClaimResult result = queue.claimVisibleMessages(messageCount, 30, false, -1, null);
                    allClaimed.addAll(result.claimed());
                }));
            }

            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }

            // Every message should be claimed exactly once
            assertEquals(messageCount, allClaimed.size());

            Set<String> handles = new HashSet<>();
            for (Message m : allClaimed) {
                assertTrue(handles.add(m.getReceiptHandle()),
                        "Duplicate receipt handle: " + m.getReceiptHandle());
            }

            Set<String> ids = new HashSet<>();
            for (Message m : allClaimed) {
                assertTrue(ids.add(m.getMessageId()),
                        "Duplicate message ID (delivered twice): " + m.getMessageId());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentReceiveAndDeleteDoesNotCorruptState() throws Exception {
        int messageCount = 50;
        int threadCount = 4;

        for (int i = 0; i < messageCount; i++) {
            queue.addMessage(new Message("msg-" + i));
        }

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            AtomicInteger claimedCount = new AtomicInteger();

            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                final int threadIdx = t;
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    if (threadIdx % 2 == 0) {
                        ClaimResult result = queue.claimVisibleMessages(messageCount, 30, false, -1, null);
                        claimedCount.addAndGet(result.claimed().size());
                        for (Message m : result.claimed()) {
                            queue.removeByReceiptHandle(ReceiptHandle.parse(m.getReceiptHandle()), false);
                        }
                    } else {
                        ClaimResult result = queue.claimVisibleMessages(messageCount, 30, false, -1, null);
                        claimedCount.addAndGet(result.claimed().size());
                    }
                }));
            }

            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }

            assertEquals(messageCount, claimedCount.get());
        } finally {
            executor.shutdownNow();
        }
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedReceiveKeepsTheMessageVisibleWithoutCountingADelivery(boolean fifo) {
        FailingStore store = new FailingStore();
        GuardedMessageQueue queue = queue(store);
        Message original = queue.peekAll().getFirst();
        store.fail = true;

        assertThrows(IllegalStateException.class, () -> queue.claimVisibleMessages(1, 900, fifo, -1, null));

        assertSame(original, queue.peekAll().getFirst());
        assertTrue(original.isVisible());
        assertEquals(0, original.getReceiveCount());
        assertNull(original.getFirstReceiveTimestamp());
        assertNull(original.getReceiptHandle());
        assertEquals(0, store.get(KEY).orElseThrow().getFirst().getReceiveCount());
        assertNull(store.get(KEY).orElseThrow().getFirst().getReceiptHandle());
    }

    @Test
    void failedReceiveDoesNotAdvanceADeadLetterCandidate() {
        FailingStore store = new FailingStore();
        GuardedMessageQueue queue = queue(store);
        Message first = queue.claimVisibleMessages(1, 0, false, -1, null).claimed().getFirst();
        Instant timestamp = first.getFirstReceiveTimestamp();
        String receipt = first.getReceiptHandle();
        store.fail = true;

        assertThrows(IllegalStateException.class,
                () -> queue.claimVisibleMessages(1, 900, false, 1, "synthetic-destination"));

        assertEquals(1, first.getReceiveCount());
        assertEquals(timestamp, first.getFirstReceiveTimestamp());
        assertEquals(receipt, first.getReceiptHandle());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedMixedReceiveRestoresEveryClaimAndDeadLetterCandidate(boolean fifo) {
        FailingStore store = new FailingStore();
        GuardedMessageQueue queue = new GuardedMessageQueue(store, KEY);
        Instant earlier = Instant.now().minusSeconds(60);
        Message deadLetter = new Message("past-delivery");
        deadLetter.setMessageGroupId("old-group");
        deadLetter.setReceiveCount(1);
        deadLetter.setFirstReceiveTimestamp(earlier.minusSeconds(30));
        deadLetter.setVisibleAt(earlier);
        String previousReceipt = ReceiptHandle.issue(KEY, deadLetter.getMessageId(),
                ReceiptHandle.DEFAULT_SECRET).encode();
        deadLetter.setReceiptHandle(previousReceipt);
        Message first = new Message("first-fresh");
        first.setMessageGroupId("first-group");
        Message second = new Message("second-fresh");
        second.setMessageGroupId("second-group");
        Message hidden = new Message("still-hidden");
        hidden.setMessageGroupId("hidden-group");
        Instant future = Instant.now().plusSeconds(900);
        hidden.setVisibleAt(future);
        queue.addAll(List.of(deadLetter, first, second, hidden));
        store.fail = true;

        assertThrows(IllegalStateException.class,
                () -> queue.claimVisibleMessages(2, 900, fifo, 1, "synthetic-destination"));

        assertEquals(List.of(deadLetter, first, second, hidden), queue.peekAll());
        assertEquals(1, deadLetter.getReceiveCount());
        assertEquals(earlier.minusSeconds(30), deadLetter.getFirstReceiveTimestamp());
        assertEquals(previousReceipt, deadLetter.getReceiptHandle());
        assertEquals(earlier, deadLetter.getVisibleAt());
        for (Message fresh : List.of(first, second)) {
            assertEquals(0, fresh.getReceiveCount());
            assertNull(fresh.getFirstReceiveTimestamp());
            assertNull(fresh.getReceiptHandle());
            assertNull(fresh.getVisibleAt());
            assertTrue(fresh.isVisible());
        }
        assertEquals(0, hidden.getReceiveCount());
        assertNull(hidden.getFirstReceiveTimestamp());
        assertNull(hidden.getReceiptHandle());
        assertEquals(future, hidden.getVisibleAt());
        assertEquals(List.of(1, 0, 0, 0), store.get(KEY).orElseThrow().stream()
                .map(Message::getReceiveCount).toList());
        assertEquals(previousReceipt, store.get(KEY).orElseThrow().getFirst().getReceiptHandle());
        assertEquals(earlier, store.get(KEY).orElseThrow().getFirst().getVisibleAt());
    }

    @Test
    void failedReceiveRetainsTheQueueOwnersStateDespiteADifferentRequestAccount() {
        String owner = "111111111111";
        String caller = "222222222222";
        String fallback = "333333333333";
        String key = "us-east-1::/" + owner + "/owner-queue";
        RequestContext request = new RequestContext();
        request.setAccountId(caller);
        @SuppressWarnings("unchecked")
        Instance<RequestContext> requestContext = mock(Instance.class);
        when(requestContext.get()).thenReturn(request);
        FailingStore raw = new FailingStore();
        Message otherAccount = new Message("other-account");
        raw.put(caller + "/" + key, List.of(otherAccount));
        AccountAwareStorageBackend<List<Message>> store =
                new AccountAwareStorageBackend<>(raw, requestContext, fallback);
        assertEquals(caller, store.accountId());
        GuardedMessageQueue queue = new GuardedMessageQueue(store, key);
        queue.addMessage(new Message("owner-message"));
        Message live = queue.peekAll().getFirst();
        Message acknowledged = raw.get(owner + "/" + key).orElseThrow().getFirst();
        raw.fail = true;

        assertThrows(IllegalStateException.class,
                () -> queue.claimVisibleMessages(1, 900, true, -1, null));

        assertEquals(owner + "/" + key, raw.lastKey);
        assertSame(live, queue.peekAll().getFirst());
        assertEquals(0, live.getReceiveCount());
        assertNull(live.getFirstReceiveTimestamp());
        assertNull(live.getReceiptHandle());
        assertNull(live.getVisibleAt());
        assertSame(acknowledged, raw.get(owner + "/" + key).orElseThrow().getFirst());
        assertEquals(0, acknowledged.getReceiveCount());
        assertNull(acknowledged.getReceiptHandle());
        assertSame(otherAccount, raw.get(caller + "/" + key).orElseThrow().getFirst());
        assertTrue(raw.get(fallback + "/" + key).isEmpty());
        assertEquals(2, raw.keys().size());
    }

    @Test
    void failedVisibilityChangeKeepsTheAcknowledgedDeadline() {
        FailingStore store = new FailingStore();
        GuardedMessageQueue queue = queue(store);
        Message message = queue.claimVisibleMessages(1, 900, true, -1, null).claimed().getFirst();
        Instant deadline = message.getVisibleAt();
        ReceiptHandle receipt = ReceiptHandle.parse(message.getReceiptHandle());
        store.fail = true;

        assertThrows(IllegalStateException.class, () -> queue.changeVisibility(receipt, 0, true));

        assertEquals(deadline, message.getVisibleAt());
        assertEquals(deadline, store.get(KEY).orElseThrow().getFirst().getVisibleAt());
    }

    @Test
    void failedDeleteKeepsTheLiveAndStoredMessage() {
        FailingStore store = new FailingStore();
        GuardedMessageQueue queue = queue(store);
        Message message = queue.claimVisibleMessages(1, 900, true, -1, null).claimed().getFirst();
        ReceiptHandle receipt = ReceiptHandle.parse(message.getReceiptHandle());
        store.fail = true;

        assertThrows(IllegalStateException.class, () -> queue.removeByReceiptHandle(receipt, true));

        assertSame(message, queue.peekAll().getFirst());
        assertEquals(1, store.get(KEY).orElseThrow().size());
    }

    enum ListMutation { REMOVE, EXPIRE, PURGE, DRAIN, DRAIN_FIRST, RESTORE_FIRST }

    @ParameterizedTest
    @EnumSource(ListMutation.class)
    void aFailedListMutationKeepsTheAcknowledgedQueue(ListMutation mutation) {
        FailingStore store = new FailingStore();
        GuardedMessageQueue queue = queue(store);
        Message original = queue.peekAll().getFirst();
        store.fail = true;

        assertThrows(IllegalStateException.class, () -> {
            switch (mutation) {
                case REMOVE -> queue.removeMessages(List.of(original));
                case EXPIRE -> queue.removeExpired(Instant.now().plusSeconds(60));
                case PURGE -> queue.purge();
                case DRAIN -> queue.drainAll();
                case DRAIN_FIRST -> queue.drainFirstIf(message -> true);
                case RESTORE_FIRST -> queue.restoreFirst(new Message("incoming"));
            }
        });

        assertEquals(List.of(original), queue.peekAll());
        assertEquals(List.of("synthetic"), store.get(KEY).orElseThrow().stream().map(Message::getBody).toList());
    }

    @Test
    void aSnapshotStoredBeforeReceiveIsNotChangedBeforeTheNextCommit() {
        FailingStore store = new FailingStore();
        GuardedMessageQueue queue = queue(store);
        Message stored = store.get(KEY).orElseThrow().getFirst();
        Message live = queue.peekAll().getFirst();
        assertNotSame(live, stored);
        store.beforeFailure = () -> {
            assertEquals(0, stored.getReceiveCount());
            assertNull(stored.getFirstReceiveTimestamp());
            assertNull(stored.getReceiptHandle());
            assertTrue(stored.isVisible());
        };
        store.fail = true;

        assertThrows(IllegalStateException.class, () -> queue.claimVisibleMessages(1, 900, true, -1, null));
    }

    @Test
    void aReloadedQueueDoesNotMutateTheStoredSnapshotBeforeCommit() {
        FailingStore store = new FailingStore();
        GuardedMessageQueue original = queue(store);
        Message stored = store.get(KEY).orElseThrow().getFirst();
        GuardedMessageQueue restored = new GuardedMessageQueue(store.get(KEY).orElseThrow(), store, KEY);
        store.beforeFailure = () -> assertEquals(0, stored.getReceiveCount());
        store.fail = true;

        assertThrows(IllegalStateException.class, () -> restored.claimVisibleMessages(1, 900, true, -1, null));

        assertEquals(0, original.peekAll().getFirst().getReceiveCount());
        assertEquals(0, stored.getReceiveCount());
    }

    @Test
    void compactionDuringAFailedReceiveWritesOnlyTheAcknowledgedState() throws Exception {
        Path snapshot = directory.resolve("messages.json");
        class CompactingStore extends WalStorage<String, List<Message>> {
            private boolean fail;

            CompactingStore() {
                super(snapshot, directory.resolve("messages.wal"), new TypeReference<Map<String, List<Message>>>() {},
                        Long.MAX_VALUE);
            }

            @Override
            public void put(String key, List<Message> value) {
                if (fail) {
                    flush();
                    throw new IllegalStateException("synthetic write failure after compaction");
                }
                super.put(key, value);
            }
        }
        CompactingStore store = new CompactingStore();
        store.load();
        try {
            GuardedMessageQueue queue = new GuardedMessageQueue(store, KEY);
            queue.addMessage(new Message("synthetic"));
            store.fail = true;

            assertThrows(IllegalStateException.class, () -> queue.claimVisibleMessages(1, 900, true, -1, null));

            assertEquals(0, new ObjectMapper().readTree(snapshot.toFile()).path(KEY).get(0).path("receiveCount").asInt());
            assertEquals(0, queue.peekAll().getFirst().getReceiveCount());
        } finally {
            store.shutdown();
        }
    }

    @Test
    void persistedSnapshotsDetachAttributesAndBinaryValues() {
        FailingStore store = new FailingStore();
        GuardedMessageQueue queue = new GuardedMessageQueue(store, KEY);
        Message message = new Message("synthetic");
        byte[] binary = {1, 2, 3};
        MessageAttributeValue attribute = new MessageAttributeValue(binary, "Binary");
        message.getMessageAttributes().put("payload", attribute);
        queue.addMessage(message);

        attribute.setDataType("changed");
        binary[0] = 9;
        message.getMessageAttributes().clear();
        Message stored = store.get(KEY).orElseThrow().getFirst();

        assertEquals("Binary", stored.getMessageAttributes().get("payload").getDataType());
        assertArrayEquals(new byte[] {1, 2, 3}, stored.getMessageAttributes().get("payload").getBinaryValue());
    }

    @Test
    void aDetachedMessageCopyPreservesItsIdentityContentAndDeliveryMetadata() {
        Message message = new Message("synthetic-body");
        Instant sent = Instant.parse("2026-01-01T00:00:00Z");
        message.setMessageId("synthetic-message-id");
        message.setSentTimestamp(sent);
        message.setFirstReceiveTimestamp(sent.plusSeconds(30));
        message.setReceiveCount(7);
        message.setMd5OfBody("00000000000000000000000000000000");
        message.setMd5OfMessageAttributes("11111111111111111111111111111111");
        message.setMessageGroupId("synthetic-group");
        message.setMessageDeduplicationId("synthetic-deduplication");
        message.setSequenceNumber(42);
        message.setOriginalSourceQueueUrl("http://localhost:4566/111111111111/source");
        message.setAwsTraceHeader("Root=1-00000000-000000000000000000000000");
        message.setSenderId("synthetic-sender");
        message.setRetentionStartTimestamp(sent.plusSeconds(10));
        message.setReceiptHandle("synthetic-receipt");
        message.setVisibleAt(sent.plusSeconds(60));
        message.getMessageAttributes().put("text", new MessageAttributeValue("synthetic-text", "String"));
        message.getMessageAttributes().put("binary", new MessageAttributeValue(new byte[] {1, 2, 3}, "Binary"));

        Message copy = message.copy();

        assertNotSame(message, copy);
        assertEquals(message.getMessageId(), copy.getMessageId());
        assertEquals(message.getBody(), copy.getBody());
        assertEquals(message.getSentTimestamp(), copy.getSentTimestamp());
        assertEquals(message.getFirstReceiveTimestamp(), copy.getFirstReceiveTimestamp());
        assertEquals(message.getReceiveCount(), copy.getReceiveCount());
        assertEquals(message.getMd5OfBody(), copy.getMd5OfBody());
        assertEquals(message.getMd5OfMessageAttributes(), copy.getMd5OfMessageAttributes());
        assertEquals(message.getMessageGroupId(), copy.getMessageGroupId());
        assertEquals(message.getMessageDeduplicationId(), copy.getMessageDeduplicationId());
        assertEquals(message.getSequenceNumber(), copy.getSequenceNumber());
        assertEquals(message.getOriginalSourceQueueUrl(), copy.getOriginalSourceQueueUrl());
        assertEquals(message.getAwsTraceHeader(), copy.getAwsTraceHeader());
        assertEquals(message.getSenderId(), copy.getSenderId());
        assertEquals(message.getRetentionStartTimestamp(), copy.getRetentionStartTimestamp());
        assertEquals(message.getReceiptHandle(), copy.getReceiptHandle());
        assertEquals(message.getVisibleAt(), copy.getVisibleAt());
        assertNotSame(message.getMessageAttributes(), copy.getMessageAttributes());
        MessageAttributeValue text = copy.getMessageAttributes().get("text");
        assertNotSame(message.getMessageAttributes().get("text"), text);
        assertEquals("synthetic-text", text.getStringValue());
        assertEquals("String", text.getDataType());
        assertNull(text.getBinaryValue());
        MessageAttributeValue binary = copy.getMessageAttributes().get("binary");
        assertNotSame(message.getMessageAttributes().get("binary"), binary);
        assertNotSame(message.getMessageAttributes().get("binary").getBinaryValue(), binary.getBinaryValue());
        assertArrayEquals(new byte[] {1, 2, 3}, binary.getBinaryValue());
        assertEquals("Binary", binary.getDataType());
        assertNull(binary.getStringValue());
    }

    @Test
    void redriveCopyRetainsContentAndDetachesAttributesWhileResettingDelivery() {
        Message source = new Message("redrive-body");
        source.setMessageGroupId("group");
        source.setMessageDeduplicationId("dedup");
        source.setSequenceNumber(42);
        source.setAwsTraceHeader("synthetic-trace");
        source.setSenderId("synthetic-sender");
        source.setOriginalSourceQueueUrl("http://localhost:4566/111111111111/source");
        source.setRetentionStartTimestamp(Instant.EPOCH);
        source.setFirstReceiveTimestamp(Instant.EPOCH.plusSeconds(1));
        source.setReceiveCount(7);
        source.setReceiptHandle("old-receipt");
        source.setVisibleAt(Instant.EPOCH.plusSeconds(60));
        byte[] binary = {1, 2, 3};
        source.getMessageAttributes().put("payload", new MessageAttributeValue(binary, "Binary"));

        Message copy = source.copyForRedrive();
        binary[0] = 9;
        source.getMessageAttributes().get("payload").setDataType("changed");

        assertFalse(source.getMessageId().equals(copy.getMessageId()));
        assertEquals(source.getBody(), copy.getBody());
        assertEquals(source.getSentTimestamp(), copy.getSentTimestamp());
        assertEquals(source.getMd5OfBody(), copy.getMd5OfBody());
        assertEquals(source.getMessageGroupId(), copy.getMessageGroupId());
        assertEquals(source.getMessageDeduplicationId(), copy.getMessageDeduplicationId());
        assertEquals(source.getSequenceNumber(), copy.getSequenceNumber());
        assertEquals(source.getOriginalSourceQueueUrl(), copy.getOriginalSourceQueueUrl());
        assertEquals(source.getAwsTraceHeader(), copy.getAwsTraceHeader());
        assertEquals(source.getSenderId(), copy.getSenderId());
        assertEquals(0, copy.getReceiveCount());
        assertNull(copy.getFirstReceiveTimestamp());
        assertNull(copy.getReceiptHandle());
        assertNull(copy.getVisibleAt());
        assertTrue(copy.getRetentionStartTimestamp().isAfter(Instant.EPOCH));
        assertEquals("Binary", copy.getMessageAttributes().get("payload").getDataType());
        assertArrayEquals(new byte[] {1, 2, 3}, copy.getMessageAttributes().get("payload").getBinaryValue());
        assertEquals(7, source.getReceiveCount());
        assertEquals("old-receipt", source.getReceiptHandle());
        assertEquals(Instant.EPOCH.plusSeconds(60), source.getVisibleAt());
    }

    @Test
    void messageCopiesPreserveMissingAttributesAndRedriveStillInitializesAnEmptyMap() {
        Message source = new Message("no-attributes");
        source.setMessageAttributes(null);

        assertNull(source.copy().getMessageAttributes());
        assertTrue(source.copyForRedrive().getMessageAttributes().isEmpty());
        assertNull(source.getMessageAttributes());
    }

    private GuardedMessageQueue queue(FailingStore store) {
        GuardedMessageQueue queue = new GuardedMessageQueue(store, KEY);
        queue.addMessage(new Message("synthetic"));
        return queue;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void memoryBackendsKeepMessageReferencesWithoutCopyingThem(boolean accountAware) {
        InMemoryStorage<String, List<Message>> raw = new InMemoryStorage<>();
        StorageBackend<String, List<Message>> store = accountAware
                ? new AccountAwareStorageBackend<>(raw, null, "000000000000") : raw;
        String rawKey = accountAware ? "000000000000/" + KEY : KEY;
        CountingMessage initial = new CountingMessage("initial");
        List<Message> loaded = List.of(initial);
        raw.put(rawKey, loaded);
        GuardedMessageQueue queue = new GuardedMessageQueue(loaded, store, KEY);
        assertSame(initial, queue.peekAll().getFirst());

        CountingMessage added = new CountingMessage("added");
        queue.addMessage(added);
        List<Message> acknowledged = raw.get(rawKey).orElseThrow();
        assertEquals(1, loaded.size());
        assertNotSame(loaded, acknowledged);
        assertSame(initial, acknowledged.getFirst());
        assertSame(added, acknowledged.getLast());
        Message claimed = queue.claimVisibleMessages(1, 900, false, -1, null).claimed().getFirst();
        queue.removeByReceiptHandle(ReceiptHandle.parse(claimed.getReceiptHandle()), false);
        assertEquals(2, acknowledged.size());
        assertSame(added, raw.get(rawKey).orElseThrow().getFirst());
        queue.purge();
        assertTrue(raw.get(rawKey).orElseThrow().isEmpty());
        assertEquals(0, initial.copies);
        assertEquals(0, added.copies);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void memoryBackendFailuresStillRestoreDeliveryAndListState(boolean accountAware) {
        MemoryFailingStore raw = new MemoryFailingStore();
        StorageBackend<String, List<Message>> store = accountAware
                ? new AccountAwareStorageBackend<>(raw, null, "000000000000") : raw;
        String rawKey = accountAware ? "000000000000/" + KEY : KEY;
        GuardedMessageQueue queue = new GuardedMessageQueue(store, KEY);
        Message message = new Message("acknowledged");
        queue.addMessage(message);
        assertSame(message, raw.get(rawKey).orElseThrow().getFirst());
        raw.fail = true;
        for (boolean fifo : List.of(false, true)) {
            assertThrows(IllegalStateException.class,
                    () -> queue.claimVisibleMessages(1, 900, fifo, -1, null));
            assertEquals(0, message.getReceiveCount());
            assertNull(message.getFirstReceiveTimestamp());
            assertNull(message.getReceiptHandle());
            assertNull(message.getVisibleAt());
        }
        raw.fail = false;
        queue.claimVisibleMessages(1, 900, true, -1, null);
        ReceiptHandle receipt = ReceiptHandle.parse(message.getReceiptHandle());
        Instant deadline = message.getVisibleAt();
        raw.fail = true;

        assertThrows(IllegalStateException.class, () -> queue.changeVisibility(receipt, 0, true));
        assertEquals(deadline, message.getVisibleAt());
        assertThrows(IllegalStateException.class, () -> queue.removeByReceiptHandle(receipt, true));
        assertThrows(IllegalStateException.class, queue::purge);
        assertThrows(IllegalStateException.class, queue::drainAll);
        assertThrows(IllegalStateException.class, () -> queue.addMessage(new Message("failed")));
        assertEquals(List.of(message), queue.peekAll());
        assertEquals(List.of(message), raw.get(rawKey).orElseThrow());
        assertEquals(1, message.getReceiveCount());
        assertEquals(deadline, message.getVisibleAt());
    }

    enum SnapshotBackend { PERSISTENT, HYBRID, WAL }

    @ParameterizedTest
    @EnumSource(SnapshotBackend.class)
    void serializingBackendsKeepLoadedAndSavedMessagesDetached(SnapshotBackend mode) throws Exception {
        Path snapshot = directory.resolve("messages.json");
        TypeReference<Map<String, List<Message>>> type = new TypeReference<>() {};
        StorageBackend<String, List<Message>> raw = switch (mode) {
            case PERSISTENT -> new PersistentStorage<>(snapshot, type);
            case HYBRID -> new HybridStorage<>(snapshot, type, Long.MAX_VALUE);
            case WAL -> new WalStorage<>(snapshot, directory.resolve("messages.wal"), type, Long.MAX_VALUE);
        };
        raw.load();
        try {
            for (boolean accountAware : List.of(false, true)) {
                StorageBackend<String, List<Message>> store = accountAware
                        ? new AccountAwareStorageBackend<>(raw, null, "000000000000") : raw;
                String key = KEY + (accountAware ? "-wrapped" : "-raw");
                String rawKey = accountAware ? "000000000000/" + key : key;
                GuardedMessageQueue queue = new GuardedMessageQueue(store, key);
                Message message = new Message("acknowledged");
                byte[] binary = {1, 2, 3};
                message.getMessageAttributes().put("binary", new MessageAttributeValue(binary, "Binary"));
                queue.addMessage(message);
                Message stored = raw.get(rawKey).orElseThrow().getFirst();
                assertNotSame(message, stored);
                assertNotSame(message.getMessageAttributes(), stored.getMessageAttributes());
                assertNotSame(binary, stored.getMessageAttributes().get("binary").getBinaryValue());
                binary[0] = 9;
                message.setBody("unsaved");
                GuardedMessageQueue restored = new GuardedMessageQueue(raw.get(rawKey).orElseThrow(), store, key);
                Message loaded = restored.peekAll().getFirst();
                assertNotSame(stored, loaded);
                loaded.setReceiveCount(7);
                loaded.getMessageAttributes().clear();
                store.flush();

                assertEquals(0, stored.getReceiveCount());
                assertArrayEquals(new byte[] {1, 2, 3}, stored.getMessageAttributes().get("binary").getBinaryValue());
                assertEquals("acknowledged", new ObjectMapper().readTree(snapshot.toFile())
                        .path(rawKey).get(0).path("body").asText());
                assertEquals("Binary", new ObjectMapper().readTree(snapshot.toFile())
                        .path(rawKey).get(0).path("messageAttributes").path("binary").path("dataType").asText());
            }
        } finally {
            if (raw instanceof HybridStorage<?, ?> hybrid) {
                hybrid.shutdown();
            } else if (raw instanceof WalStorage<?, ?> wal) {
                wal.shutdown();
            }
        }
    }

    private static class CountingMessage extends Message {
        private int copies;

        CountingMessage(String body) {
            super(body);
        }

        @Override
        public Message copy() {
            copies++;
            return super.copy();
        }
    }

    private static class MemoryFailingStore extends InMemoryStorage<String, List<Message>> {
        private boolean fail;

        @Override
        public void put(String key, List<Message> value) {
            if (fail) {
                throw new IllegalStateException("synthetic memory write failure");
            }
            super.put(key, value);
        }
    }

    /** Retains values like a backend that may serialize them during a later flush. */
    private static class FailingStore implements StorageBackend<String, List<Message>> {
        private final InMemoryStorage<String, List<Message>> delegate = new InMemoryStorage<>();
        private boolean fail;
        private String lastKey;
        private Runnable beforeFailure = () -> { };

        @Override
        public void put(String key, List<Message> value) {
            lastKey = key;
            if (fail) {
                beforeFailure.run();
                throw new IllegalStateException("synthetic write failure");
            }
            delegate.put(key, value);
        }

        @Override
        public Optional<List<Message>> get(String key) {
            return delegate.get(key);
        }

        @Override
        public void delete(String key) {
            delegate.delete(key);
        }

        @Override
        public List<List<Message>> scan(Predicate<String> keyFilter) {
            return delegate.scan(keyFilter);
        }

        @Override
        public Set<String> keys() {
            return delegate.keys();
        }

        @Override
        public void flush() {
            delegate.flush();
        }

        @Override
        public void load() {
            delegate.load();
        }

        @Override
        public void clear() {
            delegate.clear();
        }
    }
}
