package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.services.sqs.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardedMessageQueueTest {

    private GuardedMessageQueue queue;

    @BeforeEach
    void setUp() {
        queue = new GuardedMessageQueue(null, null);
    }

    // --- Basic operations ---

    @Test
    void addAndClaimSingleMessage() {
        queue.addMessage(new Message("hello"));

        var result = queue.claimVisibleMessages(1, 30, false, -1, null);
        assertEquals(1, result.claimed().size());
        assertEquals("hello", result.claimed().get(0).getBody());
        assertNotNull(result.claimed().get(0).getReceiptHandle());
        assertEquals(1, result.claimed().get(0).getReceiveCount());
        assertTrue(result.dlqCandidates().isEmpty());
    }

    @Test
    void claimEmptyQueueReturnsEmpty() {
        var result = queue.claimVisibleMessages(1, 30, false, -1, null);
        assertTrue(result.claimed().isEmpty());
        assertTrue(result.dlqCandidates().isEmpty());
    }

    @Test
    void claimedMessageBecomesInvisible() {
        queue.addMessage(new Message("msg1"));

        var first = queue.claimVisibleMessages(1, 30, false, -1, null);
        assertEquals(1, first.claimed().size());

        var second = queue.claimVisibleMessages(1, 30, false, -1, null);
        assertTrue(second.claimed().isEmpty());
    }

    @Test
    void claimMultipleMessages() {
        queue.addMessage(new Message("msg1"));
        queue.addMessage(new Message("msg2"));
        queue.addMessage(new Message("msg3"));

        var result = queue.claimVisibleMessages(3, 30, false, -1, null);
        assertEquals(3, result.claimed().size());
    }

    @Test
    void claimRespectsMaxMessages() {
        queue.addMessage(new Message("msg1"));
        queue.addMessage(new Message("msg2"));
        queue.addMessage(new Message("msg3"));

        var result = queue.claimVisibleMessages(2, 30, false, -1, null);
        assertEquals(2, result.claimed().size());
    }

    @Test
    void removeByReceiptHandle() {
        queue.addMessage(new Message("to-delete"));

        var claimed = queue.claimVisibleMessages(1, 30, false, -1, null);
        String handle = claimed.claimed().get(0).getReceiptHandle();

        assertTrue(queue.removeByReceiptHandle(handle).isPresent());

        // Message should be gone even with visibility timeout 0
        var result = queue.claimVisibleMessages(1, 0, false, -1, null);
        assertTrue(result.claimed().isEmpty());
    }

    @Test
    void removeByReceiptHandleInvalidReturnsEmpty() {
        assertFalse(queue.removeByReceiptHandle("nonexistent").isPresent());
    }

    @Test
    void changeVisibility() {
        queue.addMessage(new Message("msg"));

        var claimed = queue.claimVisibleMessages(1, 30, false, -1, null);
        String handle = claimed.claimed().get(0).getReceiptHandle();

        // Set visibility to 0 — message becomes visible immediately
        assertTrue(queue.changeVisibility(handle, 0));

        var reClaimed = queue.claimVisibleMessages(1, 30, false, -1, null);
        assertEquals(1, reClaimed.claimed().size());
    }

    @Test
    void changeVisibilityInvalidReturnsFalse() {
        assertFalse(queue.changeVisibility("nonexistent", 0));
    }

    @Test
    void purge() {
        queue.addMessage(new Message("msg1"));
        queue.addMessage(new Message("msg2"));

        queue.purge();

        var result = queue.claimVisibleMessages(10, 30, false, -1, null);
        assertTrue(result.claimed().isEmpty());
    }

    @Test
    void drainAllAndAddAll() {
        queue.addMessage(new Message("msg1"));
        queue.addMessage(new Message("msg2"));

        List<Message> drained = queue.drainAll();
        assertEquals(2, drained.size());
        assertTrue(queue.isEmpty());

        var target = new GuardedMessageQueue(null, null);
        target.addAll(drained);

        var result = target.claimVisibleMessages(10, 30, false, -1, null);
        assertEquals(2, result.claimed().size());
    }

    @Test
    void messageCountsReturnsVisibleAndInFlight() {
        queue.addMessage(new Message("msg1"));
        queue.addMessage(new Message("msg2"));
        queue.addMessage(new Message("msg3"));

        var counts = queue.messageCounts();
        assertEquals(3, counts.visible());
        assertEquals(0, counts.inFlight());

        queue.claimVisibleMessages(2, 30, false, -1, null);

        var afterClaim = queue.messageCounts();
        assertEquals(1, afterClaim.visible());
        assertEquals(2, afterClaim.inFlight());
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

        var first = queue.claimVisibleMessages(10, 30, true, -1, null);
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
        var second = queue.claimVisibleMessages(10, 30, true, -1, null);
        assertTrue(second.claimed().isEmpty());
    }

    // --- DLQ ---

    @Test
    void dlqCandidatesReturnedButNotRemovedFromSource() {
        queue.addMessage(new Message("msg"));

        // Claim and release (visibility=0) to bump receiveCount
        var r1 = queue.claimVisibleMessages(1, 0, false, -1, null);
        assertEquals(1, r1.claimed().get(0).getReceiveCount());

        // Claim again — now receiveCount = 2, maxReceiveCount = 1 → DLQ candidate
        var r2 = queue.claimVisibleMessages(1, 0, false, 1, "arn:aws:sqs:us-east-1:000000000000:dlq");
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
                    var result = queue.claimVisibleMessages(messageCount, 30, false, -1, null);
                    allClaimed.addAll(result.claimed());
                }));
            }

            for (var f : futures) {
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
                        var result = queue.claimVisibleMessages(messageCount, 30, false, -1, null);
                        claimedCount.addAndGet(result.claimed().size());
                        for (Message m : result.claimed()) {
                            queue.removeByReceiptHandle(m.getReceiptHandle());
                        }
                    } else {
                        var result = queue.claimVisibleMessages(messageCount, 30, false, -1, null);
                        claimedCount.addAndGet(result.claimed().size());
                    }
                }));
            }

            for (var f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }

            assertEquals(messageCount, claimedCount.get());
        } finally {
            executor.shutdownNow();
        }
    }
}
