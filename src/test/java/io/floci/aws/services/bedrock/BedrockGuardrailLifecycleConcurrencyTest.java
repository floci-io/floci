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

import java.util.Map;

import static io.floci.aws.testing.ParkingStoreAssertions.assertOperationWaitsForParkedStore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        ParkingStore<Guardrail> storage = new ParkingStore<>();
        ObjectMapper mapper = new ObjectMapper();
        BedrockService service = new BedrockService(storage, new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"), mapper, mock(KmsService.class));
        ObjectNode request = mapper.createObjectNode().put("name", "lifecycle")
                .put("blockedInputMessaging", "blocked").put("blockedOutputsMessaging", "blocked");
        Guardrail guardrail = service.createGuardrail(request, REGION);
        storage.parkNextWrite();
        assertOperationWaitsForParkedStore(storage, () -> {
            if (operation == Write.UPDATE) {
                service.updateGuardrail(guardrail.getGuardrailId(), request.deepCopy().put("name", "changed"), REGION);
            } else if (operation == Write.VERSION) {
                service.createGuardrailVersion(guardrail.getGuardrailId(), "snapshot", REGION);
            } else {
                service.tagResource(guardrail.getGuardrailArn(), Map.of("owner", "test"), REGION);
            }
        }, () -> service.deleteGuardrail(guardrail.getGuardrailId(), null, REGION));
        AwsException missing = assertThrows(AwsException.class,
                () -> service.getGuardrail(guardrail.getGuardrailId(), null, REGION));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
        if (operation == Write.VERSION) {
            AwsException missingVersion = assertThrows(AwsException.class,
                    () -> service.getGuardrail(guardrail.getGuardrailId(), "1", REGION));
            assertEquals("ResourceNotFoundException", missingVersion.getErrorCode());
        }
    }
}
