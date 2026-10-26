package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqsFifoSendFailureTest {

    private static final String REGION = "us-east-1";
    private static final String BASE_URL = "http://localhost:4566";

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void retryAfterAFailedSendEnqueuesTheMessage(boolean failDedupWrite, boolean restart) {
        Fixture fixture = new Fixture();
        SqsService service = fixture.service();
        Queue queue = service.createQueue("retry.fifo", null, REGION);
        fixture.messages.failPut = !failDedupWrite;
        fixture.dedup.failPut = failDedupWrite;

        assertThrows(IllegalStateException.class,
                () -> service.sendMessage(queue.getQueueUrl(), "original", 0, "group", "id", REGION));
        assertTrue(service.peekMessages(queue.getQueueUrl(), REGION).isEmpty());
        SqsService retryService = restart ? fixture.service() : service;
        Message accepted = retryService.sendMessage(queue.getQueueUrl(), "retry", 0, "group", "id", REGION);
        Message duplicate = retryService.sendMessage(queue.getQueueUrl(), "again", 0, "group", "id", REGION);

        assertEquals(accepted.getMessageId(), duplicate.getMessageId());
        assertEquals(List.of("retry"), retryService.peekMessages(queue.getQueueUrl(), REGION)
                .stream().map(Message::getBody).toList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void compensatingAFailedGroupKeepsAnotherGroupsAcceptedIdentity(boolean restart) {
        Fixture fixture = new Fixture();
        SqsService service = fixture.service();
        Queue queue = service.createQueue("groups.fifo", Map.of("DeduplicationScope", "messageGroup"), REGION);
        Message accepted = service.sendMessage(queue.getQueueUrl(), "accepted", 0, "group-a", "id", REGION);
        Message received = service.receiveMessage(queue.getQueueUrl(), 1, 30, 0, REGION).getFirst();
        service.deleteMessage(queue.getQueueUrl(), received.getReceiptHandle(), REGION);
        fixture.messages.failPut = true;

        assertThrows(IllegalStateException.class,
                () -> service.sendMessage(queue.getQueueUrl(), "failed", 0, "group-b", "id", REGION));
        SqsService retryService = restart ? fixture.service() : service;
        Message duplicate = retryService.sendMessage(queue.getQueueUrl(), "duplicate", 0, "group-a", "id", REGION);
        retryService.sendMessage(queue.getQueueUrl(), "retry", 0, "group-b", "id", REGION);

        assertEquals(accepted.getMessageId(), duplicate.getMessageId());
        assertEquals(List.of("retry"), retryService.peekMessages(queue.getQueueUrl(), REGION)
                .stream().map(Message::getBody).toList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anIdentityWriteFailureAfterEnqueueKeepsTheDeduplicationReservation(boolean restart) {
        Fixture fixture = new Fixture();
        SqsService service = fixture.service();
        Queue queue = service.createQueue("identity.fifo", null, REGION);
        fixture.identities.failPut = true;

        assertThrows(IllegalStateException.class,
                () -> service.sendMessage(queue.getQueueUrl(), "accepted", 0, "group", "id", REGION));
        Message accepted = service.peekMessages(queue.getQueueUrl(), REGION).getFirst();
        SqsService retryService = restart ? fixture.service() : service;
        Message duplicate = retryService.sendMessage(queue.getQueueUrl(), "duplicate", 0, "group", "id", REGION);

        assertEquals(accepted.getMessageId(), duplicate.getMessageId());
        assertEquals(List.of("accepted"), retryService.peekMessages(queue.getQueueUrl(), REGION)
                .stream().map(Message::getBody).toList());
    }

    @Test
    void failedCompensationPreservesTheSendErrorAndReportsItsCleanupFailure() {
        Fixture fixture = new Fixture();
        SqsService service = fixture.service();
        Queue queue = service.createQueue("cleanup.fifo", null, REGION);
        fixture.messages.failPut = true;
        fixture.dedup.failDelete = true;

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.sendMessage(queue.getQueueUrl(), "failed", 0, "group", "id", REGION));

        assertEquals("synthetic put failure", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("synthetic delete failure", failure.getSuppressed()[0].getMessage());
        service.sendMessage(queue.getQueueUrl(), "retry", 0, "group", "id", REGION);
        assertEquals(1, service.peekMessages(queue.getQueueUrl(), REGION).size());
    }

    @Test
    void repeatedBackendExceptionDoesNotReplaceTheOriginalFailure() {
        IllegalStateException backendFailure = new IllegalStateException("synthetic persistent failure");
        InMemoryStorage<String, Map<String, Long>> dedup = new InMemoryStorage<>() {
            @Override
            public void put(String key, Map<String, Long> value) {
                throw backendFailure;
            }

            @Override
            public void delete(String key) {
                throw backendFailure;
            }
        };
        SqsService service = new SqsService(new InMemoryStorage<>(), new InMemoryStorage<>(), dedup,
                new InMemoryStorage<>(), 30, 1048576, BASE_URL, new RegionResolver(REGION, "000000000000"),
                false, null, new MutableClock());
        Queue queue = service.createQueue("same-error.fifo", null, REGION);

        assertSame(backendFailure, assertThrows(IllegalStateException.class,
                () -> service.sendMessage(queue.getQueueUrl(), "failed", 0, "group", "id", REGION)));
        assertTrue(service.peekMessages(queue.getQueueUrl(), REGION).isEmpty());
    }

    private static class Fixture {
        private final InMemoryStorage<String, Queue> queues = new InMemoryStorage<>();
        private final FailingStore<List<Message>> messages = new FailingStore<>();
        private final FailingStore<Map<String, Long>> dedup = new FailingStore<>();
        private final FailingStore<Map<String, Map<String, String>>> identities = new FailingStore<>();
        private final MutableClock clock = new MutableClock();

        SqsService service() {
            return new SqsService(queues, messages, dedup, identities, 30, 1048576, BASE_URL,
                    new RegionResolver(REGION, "000000000000"), false, null, clock);
        }
    }

    private static class FailingStore<V> extends InMemoryStorage<String, V> {
        private boolean failPut;
        private boolean failDelete;

        @Override
        public void put(String key, V value) {
            if (failPut) {
                failPut = false;
                throw new IllegalStateException("synthetic put failure");
            }
            super.put(key, value);
        }

        @Override
        public void delete(String key) {
            if (failDelete) {
                failDelete = false;
                throw new IllegalStateException("synthetic delete failure");
            }
            super.delete(key);
        }
    }
}
