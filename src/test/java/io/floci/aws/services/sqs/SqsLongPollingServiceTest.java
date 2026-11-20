package io.floci.aws.services.sqs;

import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.sqs.model.Message;
import io.floci.aws.services.sqs.model.Queue;
import io.floci.aws.testing.MutableClock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqsLongPollingServiceTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletingFifoHeadWakesParkedReceive(boolean inBatch) throws Exception {
        String region = "eu-west-1";
        SqsService service = new SqsService(new InMemoryStorage<>(), 30, 1048576,
                "http://localhost:4566", new MutableClock());
        Queue queue = service.createQueue("delete-wakeup.fifo", Map.of("FifoQueue", "true"), region);
        String queueUrl = queue.getQueueUrl();
        service.sendMessage(queueUrl, "head", 0, "group", "head", region);
        Message next = service.sendMessage(queueUrl, "next", 0, "group", "next", region);
        Message head = service.receiveMessage(queueUrl, 1, 30, 0, region).getFirst();
        assertEquals("head", head.getBody());
        assertTrue(service.receiveMessage(queueUrl, 1, 30, 0, region).isEmpty(),
                "The next message must remain blocked while the head is in flight");

        FutureTask<List<Message>> receive = new FutureTask<>(
                () -> service.receiveMessage(queueUrl, 1, 30, 20, region));
        Thread poller = new Thread(receive, "sqs-fifo-delete-wakeup");
        poller.start();
        try {
            // Observe the actual wait rather than sleeping and assuming the receive has parked.
            await().pollInterval(Duration.ofMillis(1)).atMost(Duration.ofSeconds(5))
                    .until(() -> poller.getState() == Thread.State.TIMED_WAITING);
            if (inBatch) {
                service.deleteMessageInBatch(queueUrl, head.getReceiptHandle(), region);
            } else {
                service.deleteMessage(queueUrl, head.getReceiptHandle(), region);
            }

            // Less than the one-second polling slice: a timeout retry cannot satisfy this check.
            List<Message> received = receive.get(750, TimeUnit.MILLISECONDS);
            assertEquals(1, received.size());
            assertEquals(next.getMessageId(), received.getFirst().getMessageId());
            assertEquals(1, received.getFirst().getReceiveCount());
        } finally {
            poller.interrupt();
            poller.join(5000);
        }
        assertFalse(poller.isAlive());
    }
}
