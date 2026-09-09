package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.Memory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockAgentCoreMemoryServiceTest {

    private static final String REGION = "us-east-1";
    private static final String KEY_ARN = "arn:aws:kms:us-east-1:000000000000:key/mem-key";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/mem-role";

    private BedrockAgentCoreMemoryService service;

    @BeforeEach
    void setUp() {
        service = new BedrockAgentCoreMemoryService(
                new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"));
    }

    private Memory create(String name) {
        return service.create(name, 30, "desc", KEY_ARN, ROLE_ARN,
                Map.of("team", "core"), null, REGION);
    }

    @Test
    void createPersistsAllFields() {
        Memory memory = create("myMemory");
        assertTrue(memory.getMemoryId().matches("myMemory-[a-zA-Z0-9]{10}"), memory.getMemoryId());
        assertEquals(KEY_ARN, memory.getEncryptionKeyArn());
        assertEquals(ROLE_ARN, memory.getMemoryExecutionRoleArn());
        assertEquals("core", memory.getTags().get("team"));

        Memory fetched = service.get(memory.getMemoryId(), REGION);
        assertEquals(KEY_ARN, fetched.getEncryptionKeyArn());
        assertEquals(ROLE_ARN, fetched.getMemoryExecutionRoleArn());
        assertEquals("core", fetched.getTags().get("team"));
    }

    @Test
    void clientTokenReplayReturnsOriginalAndIgnoresNewFields() {
        Memory first = service.create("tokMem", 30, null, null, null, null, "tok-1", REGION);
        Memory replay = service.create("otherName", 60, null, KEY_ARN, ROLE_ARN,
                Map.of("late", "tag"), "tok-1", REGION);
        assertEquals(first.getMemoryId(), replay.getMemoryId());
        assertEquals("tokMem", replay.getName());
        assertNull(replay.getEncryptionKeyArn());
        assertTrue(replay.getTags().isEmpty());
    }

    @Test
    void updateAppliesFieldsAndLeavesNullsUntouched() {
        Memory memory = create("updMemory");
        service.update(memory.getMemoryId(), null, 90, null, REGION);

        Memory after = service.get(memory.getMemoryId(), REGION);
        assertEquals(90, after.getEventExpiryDuration());
        assertEquals("desc", after.getDescription());
        assertEquals(ROLE_ARN, after.getMemoryExecutionRoleArn());

        service.update(memory.getMemoryId(), "new desc", null,
                "arn:aws:iam::000000000000:role/mem-role-2", REGION);
        after = service.get(memory.getMemoryId(), REGION);
        assertEquals("new desc", after.getDescription());
        assertEquals(90, after.getEventExpiryDuration());
        assertEquals("arn:aws:iam::000000000000:role/mem-role-2", after.getMemoryExecutionRoleArn());
    }

    @Test
    void updateRejectsOutOfRangeExpiry() {
        Memory memory = create("badUpd");
        for (int v : new int[]{2, 366}) {
            AwsException e = assertThrows(AwsException.class,
                    () -> service.update(memory.getMemoryId(), null, v, null, REGION));
            assertEquals(400, e.getHttpStatus());
        }
        assertEquals(30, service.get(memory.getMemoryId(), REGION).getEventExpiryDuration());
    }

    @Test
    void rejectedUpdateAppliesNothing() {
        Memory memory = create("atomicUpd");
        AwsException e = assertThrows(AwsException.class, () -> service.update(
                memory.getMemoryId(), "should-not-stick", 999,
                "arn:aws:iam::000000000000:role/should-not-stick", REGION));
        assertEquals(400, e.getHttpStatus());

        Memory after = service.get(memory.getMemoryId(), REGION);
        assertEquals("desc", after.getDescription());
        assertEquals(30, after.getEventExpiryDuration());
        assertEquals(ROLE_ARN, after.getMemoryExecutionRoleArn());
    }

    @Test
    void tagOperationsRoundTripThroughTheArn() {
        Memory memory = create("tagMemory");
        String arn = service.arn(memory, REGION);

        assertEquals(Map.of("team", "core"), service.getTagsByArn(REGION, arn));

        service.tagByArn(REGION, arn, Map.of("env", "prod"));
        assertEquals("prod", service.getTagsByArn(REGION, arn).get("env"));
        assertEquals("core", service.getTagsByArn(REGION, arn).get("team"));

        service.untagByArn(REGION, arn, List.of("env"));
        assertNull(service.getTagsByArn(REGION, arn).get("env"));
        assertEquals("core", service.getTagsByArn(REGION, arn).get("team"));
    }

    @Test
    void findByArnRejectsNonMemoryArnAndUnknownId() {
        AwsException wrongType = assertThrows(AwsException.class, () -> service.getTagsByArn(REGION,
                "arn:aws:bedrock-agentcore:us-east-1:000000000000:gateway/gw-abc1234567"));
        assertEquals(400, wrongType.getHttpStatus());

        AwsException unknown = assertThrows(AwsException.class, () -> service.getTagsByArn(REGION,
                "arn:aws:bedrock-agentcore:us-east-1:000000000000:memory/nosuchmem-abc1234567"));
        assertEquals(404, unknown.getHttpStatus());
    }
}
