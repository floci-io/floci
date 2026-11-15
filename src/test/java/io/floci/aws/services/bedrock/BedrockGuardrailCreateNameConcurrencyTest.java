package io.floci.aws.services.bedrock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.bedrock.model.Guardrail;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.testing.ParkingStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BedrockGuardrailCreateNameConcurrencyTest {

    @Test
    void overlappingCreatesWithTheSameNameStoreOneGuardrail() throws Exception {
        overlappingCreates(true);
    }

    @Test
    void overlappingCreatesWithDifferentNamesStoreBothGuardrails() throws Exception {
        overlappingCreates(false);
    }

    private static void overlappingCreates(boolean sameName) throws Exception {
        ParkingStore<Guardrail> storage = new ParkingStore<>();
        ObjectMapper mapper = new ObjectMapper();
        BedrockService service = new BedrockService(storage, new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"), mapper, mock(KmsService.class));
        AtomicReference<Guardrail> firstResult = new AtomicReference<>();
        AtomicReference<Guardrail> secondResult = new AtomicReference<>();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                firstResult.set(service.createGuardrail(request(mapper, "first"), "us-east-1"));
            } catch (Throwable error) {
                firstFailure.set(error);
            }
        });
        Thread second = new Thread(() -> {
            try {
                secondResult.set(service.createGuardrail(request(mapper, sameName ? "first" : "second"), "us-east-1"));
            } catch (Throwable error) {
                secondFailure.set(error);
            }
        });
        storage.parkNextWrite();
        first.start();
        try {
            assertTrue(storage.awaitParked());
            second.start();
            await().atMost(Duration.ofSeconds(5)).until(() ->
                    second.getState() == Thread.State.BLOCKED || second.getState() == Thread.State.TERMINATED);
        } finally {
            storage.release();
            first.join(Duration.ofSeconds(5));
            second.join(Duration.ofSeconds(5));
        }
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertNull(firstFailure.get());
        assertNotNull(firstResult.get());
        assertEquals(sameName ? 1 : 2, storage.scan(key -> true).size());
        if (sameName) {
            assertNull(secondResult.get());
            AwsException conflict = assertInstanceOf(AwsException.class, secondFailure.get());
            assertEquals("ConflictException", conflict.getErrorCode());
        } else {
            assertNull(secondFailure.get());
            assertNotNull(secondResult.get());
        }
    }

    private static ObjectNode request(ObjectMapper mapper, String name) {
        return mapper.createObjectNode().put("name", name)
                .put("blockedInputMessaging", "blocked").put("blockedOutputsMessaging", "blocked");
    }
}
