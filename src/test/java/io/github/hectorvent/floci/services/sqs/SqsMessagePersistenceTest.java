package io.github.hectorvent.floci.services.sqs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.core.storage.HybridStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.WalStorage;
import io.github.hectorvent.floci.services.sqs.GuardedMessageQueue.HandleResult;
import io.github.hectorvent.floci.services.sqs.GuardedMessageQueue.MessageCounts;
import io.github.hectorvent.floci.services.sqs.model.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqsMessagePersistenceTest {

    private static final String QUEUE = "us-east-1::/000000000000/persistence";
    private static final TypeReference<Map<String, List<Message>>> MESSAGES = new TypeReference<>() {};

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void delayedMessageKeepsItsAbsoluteDeadlineAfterReload(String mode) throws IOException {
        try (Store source = open(mode, tempDir.resolve("source"))) {
            Message message = new Message("delayed");
            Instant deadline = Instant.now().plusSeconds(900);
            message.setVisibleAt(deadline);
            source.queue().addMessage(message);

            try (Store restored = restore(mode, source)) {
                Message reloaded = restored.queue().peekAll().getFirst();
                assertEquals(deadline, reloaded.getVisibleAt());
                assertNull(reloaded.getReceiptHandle());
                assertEquals(new MessageCounts(0, 0, 1), restored.queue().messageCounts());
                assertTrue(restored.queue().claimVisibleMessages(1, 30, false, -1, null).claimed().isEmpty());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void restoredFifoDeliveryKeepsItsReceiptAndBlocksTheGroup(String mode) throws IOException {
        try (Store source = open(mode, tempDir.resolve("source"))) {
            GuardedMessageQueue queue = source.queue();
            Message first = new Message("first");
            first.setMessageGroupId("group");
            Message second = new Message("second");
            second.setMessageGroupId("group");
            queue.addMessage(first);
            queue.addMessage(second);
            Message claimed = queue.claimVisibleMessages(1, 900, true, -1, null).claimed().getFirst();
            String receipt = claimed.getReceiptHandle();
            Instant deadline = claimed.getVisibleAt();

            try (Store restored = restore(mode, source)) {
                GuardedMessageQueue reloaded = restored.queue();
                assertEquals(deadline, reloaded.peekAll().getFirst().getVisibleAt());
                assertEquals(receipt, reloaded.peekAll().getFirst().getReceiptHandle());
                assertEquals(new MessageCounts(1, 1, 0), reloaded.messageCounts());
                assertTrue(reloaded.claimVisibleMessages(10, 30, true, -1, null).claimed().isEmpty());
                assertEquals(HandleResult.APPLIED, reloaded.removeByReceiptHandle(
                        ReceiptHandle.parse(receipt), true).result());
                assertEquals("second", reloaded.claimVisibleMessages(1, 30, true, -1, null)
                        .claimed().getFirst().getBody());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void visibilityRenewalSurvivesReloadWithoutRestartingTheTimeout(String mode) throws IOException {
        try (Store source = open(mode, tempDir.resolve("source"))) {
            GuardedMessageQueue queue = source.queue();
            queue.addMessage(new Message("renewed"));
            Message claimed = queue.claimVisibleMessages(1, 60, false, -1, null).claimed().getFirst();
            String receipt = claimed.getReceiptHandle();
            assertEquals(HandleResult.APPLIED, queue.changeVisibility(ReceiptHandle.parse(receipt), 900, false));
            Instant renewedDeadline = claimed.getVisibleAt();

            try (Store restored = restore(mode, source)) {
                GuardedMessageQueue reloaded = restored.queue();
                assertEquals(renewedDeadline, reloaded.peekAll().getFirst().getVisibleAt());
                assertEquals(receipt, reloaded.peekAll().getFirst().getReceiptHandle());
                assertTrue(reloaded.claimVisibleMessages(1, 30, false, -1, null).claimed().isEmpty());
                assertEquals(HandleResult.APPLIED,
                        reloaded.changeVisibility(ReceiptHandle.parse(receipt), 0, false));
                assertEquals("renewed", reloaded.claimVisibleMessages(1, 30, false, -1, null)
                        .claimed().getFirst().getBody());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void expiredDeliveryIsNotHiddenAgainOnReload(String mode) throws IOException {
        try (Store source = open(mode, tempDir.resolve("source"))) {
            Message message = new Message("expired");
            message.setVisibleAt(Instant.now().minusSeconds(60));
            message.setReceiptHandle(ReceiptHandle.issue(QUEUE, message.getMessageId(), ReceiptHandle.DEFAULT_SECRET).encode());
            source.queue().addMessage(message);

            try (Store restored = restore(mode, source)) {
                Message reloaded = restored.queue().peekAll().getFirst();
                assertEquals(message.getVisibleAt(), reloaded.getVisibleAt());
                assertTrue(reloaded.isVisible());
                assertEquals("expired", restored.queue().claimVisibleMessages(1, 30, true, -1, null)
                        .claimed().getFirst().getBody());
            }
        }
    }

    @Test
    void legacyMessageWithoutDeliveryStateRemainsReadable() throws IOException {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Message message = mapper.readValue("{\"messageId\":\"legacy\",\"body\":\"old body\",\"receiveCount\":0}", Message.class);
        assertEquals("old body", message.getBody());
        assertNull(message.getReceiptHandle());
        assertNull(message.getVisibleAt());
        assertTrue(message.isVisible());
        assertFalse(mapper.valueToTree(message).has("visible"));
    }

    private Store open(String mode, Path directory) throws IOException {
        Files.createDirectories(directory);
        StorageBackend<String, List<Message>> backend;
        if ("wal".equals(mode)) {
            backend = new WalStorage<>(directory.resolve("messages.json"), directory.resolve("messages.wal"),
                    MESSAGES, Long.MAX_VALUE);
        } else if ("hybrid".equals(mode)) {
            backend = new HybridStorage<>(directory.resolve("messages.json"), MESSAGES, Long.MAX_VALUE);
        } else {
            backend = new PersistentStorage<>(directory.resolve("messages.json"), MESSAGES);
        }
        backend.load();
        return new Store(directory, backend);
    }

    private Store restore(String mode, Store source) throws IOException {
        if (source.backend() instanceof HybridStorage<String, List<Message>> hybrid) {
            hybrid.flush();
        }
        Path directory = Files.createTempDirectory(tempDir, "restore-");
        // No writer runs during the copy. WAL compaction is disabled, so this also reads
        // acknowledged log entries without relying on graceful shutdown or a snapshot.
        try (Stream<Path> files = Files.list(source.directory())) {
            for (Path file : files.toList()) {
                Files.copy(file, directory.resolve(file.getFileName()));
            }
        }
        return open(mode, directory);
    }

    private record Store(Path directory, StorageBackend<String, List<Message>> backend) implements AutoCloseable {
        GuardedMessageQueue queue() {
            return new GuardedMessageQueue(backend.get(QUEUE).orElseGet(List::of), backend, QUEUE);
        }

        @Override
        public void close() {
            if (backend instanceof WalStorage<String, List<Message>> wal) {
                wal.shutdown();
            } else if (backend instanceof HybridStorage<String, List<Message>> hybrid) {
                hybrid.shutdown();
            }
        }
    }
}
