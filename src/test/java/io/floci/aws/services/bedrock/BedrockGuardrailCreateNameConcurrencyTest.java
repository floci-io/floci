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

import java.util.concurrent.atomic.AtomicReference;

import static io.floci.aws.testing.ParkingStoreAssertions.assertOperationWaitsForParkedStore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        storage.parkNextWrite();
        assertOperationWaitsForParkedStore(storage,
                () -> firstResult.set(service.createGuardrail(request(mapper, "first"), "us-east-1")),
                () -> {
                    if (sameName) {
                        AwsException conflict = assertThrows(AwsException.class,
                                () -> service.createGuardrail(request(mapper, "first"), "us-east-1"));
                        assertEquals("ConflictException", conflict.getErrorCode());
                    } else {
                        secondResult.set(service.createGuardrail(request(mapper, "second"), "us-east-1"));
                    }
                });
        assertNotNull(firstResult.get());
        assertEquals(sameName ? 1 : 2, storage.scan(key -> true).size());
        if (!sameName) {
            assertNotNull(secondResult.get());
        }
    }

    private static ObjectNode request(ObjectMapper mapper, String name) {
        return mapper.createObjectNode().put("name", name)
                .put("blockedInputMessaging", "blocked").put("blockedOutputsMessaging", "blocked");
    }
}
