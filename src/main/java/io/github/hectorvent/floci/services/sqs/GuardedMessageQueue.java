package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.sqs.model.Message;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Thread-safe wrapper around a per-queue message list. All operations acquire a
 * {@link ReentrantLock} so that compound read-modify-write sequences (e.g., claim
 * visible messages) are atomic with respect to each other.
 */
class GuardedMessageQueue {

    private final ReentrantLock lock = new ReentrantLock();
    private final List<Message> messages;
    private final StorageBackend<String, List<Message>> messageStore;
    private final String storageKey;

    @FunctionalInterface
    private interface Guard extends AutoCloseable {
        @Override
        void close(); // no checked exception
    }

    private Guard hold() {
        lock.lock();
        return lock::unlock;
    }

    GuardedMessageQueue(StorageBackend<String, List<Message>> messageStore, String storageKey) {
        this(new ArrayList<>(), messageStore, storageKey);
    }

    GuardedMessageQueue(List<Message> initial, StorageBackend<String, List<Message>> messageStore, String storageKey) {
        this.messages = new ArrayList<>(initial);
        this.messageStore = messageStore;
        this.storageKey = storageKey;
    }

    record ClaimResult(List<Message> claimed, List<Message> dlqCandidates) {
    }

    void addMessage(Message message) {
        try (var _ = hold()) {
            messages.add(message);
            persist();
        }
    }

    void addAll(List<Message> toAdd) {
        try (var _ = hold()) {
            messages.addAll(toAdd);
            persist();
        }
    }

    ClaimResult claimVisibleMessages(int maxMessages, int effectiveTimeout,
                                     boolean fifo, int maxReceiveCount,
                                     String deadLetterTargetArn) {
        try (var _ = hold()) {
            List<Message> claimed = new ArrayList<>();
            List<Message> dlqCandidates = new ArrayList<>();

            if (fifo) {
                claimFifo(maxMessages, effectiveTimeout, maxReceiveCount, deadLetterTargetArn,
                        claimed, dlqCandidates);
            } else {
                claimStandard(maxMessages, effectiveTimeout, maxReceiveCount, deadLetterTargetArn,
                        claimed, dlqCandidates);
            }

            if (!claimed.isEmpty() || !dlqCandidates.isEmpty()) {
                persist();
            }

            return new ClaimResult(claimed, dlqCandidates);
        }
    }

    private boolean tryClaim(Message msg, int effectiveTimeout, int maxReceiveCount,
                             String deadLetterTargetArn, List<Message> claimed,
                             List<Message> dlqCandidates) {
        msg.setReceiveCount(msg.getReceiveCount() + 1);

        if (maxReceiveCount > 0 && deadLetterTargetArn != null
                && msg.getReceiveCount() > maxReceiveCount) {
            dlqCandidates.add(msg);
            return false;
        }

        msg.setReceiptHandle(UUID.randomUUID().toString());
        msg.setVisibleAt(Instant.now().plusSeconds(effectiveTimeout));
        claimed.add(msg);
        return true;
    }

    private void claimStandard(int maxMessages, int effectiveTimeout,
                               int maxReceiveCount, String deadLetterTargetArn,
                               List<Message> claimed, List<Message> dlqCandidates) {
        for (Message msg : messages) {
            if (claimed.size() >= maxMessages) break;
            if (!msg.isVisible()) continue;
            tryClaim(msg, effectiveTimeout, maxReceiveCount, deadLetterTargetArn, claimed, dlqCandidates);
        }
    }

    private void claimFifo(int maxMessages, int effectiveTimeout,
                           int maxReceiveCount, String deadLetterTargetArn,
                           List<Message> claimed, List<Message> dlqCandidates) {
        Set<String> groupsWithInFlight;
        Set<String> groupsDelivered = new HashSet<>();

        groupsWithInFlight =
                messages.stream().filter(msg -> !msg.isVisible() && msg.getMessageGroupId() != null)
                        .map(Message::getMessageGroupId).collect(Collectors.toSet());

        for (Message msg : messages) {
            if (claimed.size() >= maxMessages) break;
            if (!msg.isVisible()) continue;

            String groupId = msg.getMessageGroupId();
            if (groupId != null && groupsWithInFlight.contains(groupId)) continue;
            if (groupId != null && groupsDelivered.contains(groupId)) continue;

            if (tryClaim(msg, effectiveTimeout, maxReceiveCount, deadLetterTargetArn, claimed, dlqCandidates)
                    && groupId != null) {
                groupsDelivered.add(groupId);
            }
        }
    }

    boolean removeByReceiptHandle(String receiptHandle) {
        try (var _ = hold()) {
            boolean removed = messages.removeIf(m -> receiptHandle.equals(m.getReceiptHandle()));
            if (removed) {
                persist();
            }
            return removed;
        }
    }

    boolean changeVisibility(String receiptHandle, int visibilityTimeout) {
        try (var _ = hold()) {
            for (Message msg : messages) {
                if (receiptHandle.equals(msg.getReceiptHandle())) {
                    msg.setVisibleAt(Instant.now().plusSeconds(visibilityTimeout));
                    persist();
                    return true;
                }
            }
            return false;
        }
    }

    void removeMessages(List<Message> toRemove) {
        try (var _ = hold()) {
            messages.removeAll(toRemove);
            persist();
        }
    }

    void purge() {
        try (var _ = hold()) {
            messages.clear();
            persist();
        }
    }

    List<Message> drainAll() {
        try (var _ = hold()) {
            List<Message> drained = new ArrayList<>(messages);
            messages.clear();
            persist();
            return drained;
        }
    }

    record MessageCounts(long visible, long inFlight) {
    }

    MessageCounts messageCounts() {
        try (var _ = hold()) {
            long visible = 0;
            long inFlight = 0;
            for (Message m : messages) {
                if (m.isVisible()) visible++;
                else inFlight++;
            }
            return new MessageCounts(visible, inFlight);
        }
    }

    boolean isEmpty() {
        try (var _ = hold()) {
            return messages.isEmpty();
        }
    }

    Message findByDeduplicationId(String dedupId) {
        try (var _ = hold()) {
            return messages.stream().filter(msg -> dedupId.equals(msg.getMessageDeduplicationId()))
                    .findFirst().orElse(null);
        }
    }

    private void persist() {
        if (messageStore == null || storageKey == null) {
            return;
        }
        messageStore.put(storageKey, new ArrayList<>(messages));
    }
}
