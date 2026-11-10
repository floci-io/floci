package io.floci.aws.services.bedrock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.bedrock.model.Guardrail;
import io.floci.aws.services.kms.KmsService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BedrockGuardrailLifecycleConcurrencyTest {

    private static final String REGION = "us-east-1";

    @Test
    void deletionAfterAnOverlappingTagWriteLeavesTheGuardrailAbsent() throws Exception {
        overlappingWrite(Write.TAG);
    }

    @Test
    void deletionAfterAnOverlappingUpdateLeavesTheGuardrailAbsent() throws Exception {
        overlappingWrite(Write.UPDATE);
    }

    @Test
    void deletionAfterOverlappingVersionCreationLeavesTheNumberedVersionAbsent() throws Exception {
        overlappingWrite(Write.VERSION);
    }

    private enum Write {
        TAG, UPDATE, VERSION
    }

    private static void overlappingWrite(Write operation) throws Exception {
        PausedWriteStorage storage = new PausedWriteStorage();
        ObjectMapper mapper = new ObjectMapper();
        BedrockService service = new BedrockService(storage, new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"), mapper, mock(KmsService.class));
        ObjectNode request = mapper.createObjectNode().put("name", "lifecycle")
                .put("blockedInputMessaging", "blocked").put("blockedOutputsMessaging", "blocked");
        Guardrail guardrail = service.createGuardrail(request, REGION);
        storage.pause = true;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                if (operation == Write.UPDATE) {
                    service.updateGuardrail(guardrail.getGuardrailId(), request.deepCopy().put("name", "changed"), REGION);
                } else if (operation == Write.VERSION) {
                    service.createGuardrailVersion(guardrail.getGuardrailId(), "snapshot", REGION);
                } else {
                    service.tagResource(guardrail.getGuardrailArn(), Map.of("owner", "test"), REGION);
                }
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        Thread deletion = new Thread(() -> {
            try {
                service.deleteGuardrail(guardrail.getGuardrailId(), null, REGION);
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        writer.start();
        try {
            assertTrue(storage.writeEntered.await(5, TimeUnit.SECONDS));
            deletion.start();
            await().atMost(Duration.ofSeconds(5)).until(() ->
                    deletion.getState() == Thread.State.BLOCKED || deletion.getState() == Thread.State.TERMINATED);
        } finally {
            storage.releaseWrite.countDown();
            writer.join(Duration.ofSeconds(5));
            deletion.join(Duration.ofSeconds(5));
        }
        assertFalse(writer.isAlive());
        assertFalse(deletion.isAlive());
        assertNull(failure.get());
        AwsException missing = assertThrows(AwsException.class,
                () -> service.getGuardrail(guardrail.getGuardrailId(), null, REGION));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
        if (operation == Write.VERSION) {
            AwsException missingVersion = assertThrows(AwsException.class,
                    () -> service.getGuardrail(guardrail.getGuardrailId(), "1", REGION));
            assertEquals("ResourceNotFoundException", missingVersion.getErrorCode());
        }
    }

    private static final class PausedWriteStorage extends InMemoryStorage<String, Guardrail> {
        private final CountDownLatch writeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseWrite = new CountDownLatch(1);
        private volatile boolean pause;

        @Override
        public void put(String key, Guardrail value) {
            if (pause) {
                writeEntered.countDown();
                try {
                    if (!releaseWrite.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Guardrail write was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Guardrail write interrupted", e);
                }
            }
            super.put(key, value);
        }
    }
}
