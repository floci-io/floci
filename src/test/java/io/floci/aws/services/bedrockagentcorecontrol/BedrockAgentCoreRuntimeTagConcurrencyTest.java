package io.floci.aws.services.bedrockagentcorecontrol;

import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static io.floci.aws.services.bedrockagentcorecontrol.BedrockAgentCoreConcurrencyFixture.REGION;
import static io.floci.aws.testing.ParkingStoreAssertions.assertOperationWaitsForParkedStore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BedrockAgentCoreRuntimeTagConcurrencyTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionWaitsForTagPublication(boolean remove) throws Exception {
        BedrockAgentCoreConcurrencyFixture fixture = taggedRuntime();
        fixture.storage.parkNextWrite();
        assertOperationWaitsForParkedStore(fixture.storage,
                () -> changeTags(fixture, remove), fixture::delete);
        assertAbsent(fixture);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tagWriteRechecksExistenceAfterDeletion(boolean remove) throws Exception {
        BedrockAgentCoreConcurrencyFixture fixture = taggedRuntime();
        fixture.storage.parkNextRead();
        assertOperationWaitsForParkedStore(fixture.storage, fixture::delete,
                () -> {
                    AwsException failure = assertThrows(AwsException.class, () -> changeTags(fixture, remove));
                    assertEquals("ResourceNotFoundException", failure.getErrorCode());
                });
        assertAbsent(fixture);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tagChangesPreserveOtherTags(boolean remove) {
        BedrockAgentCoreConcurrencyFixture fixture = taggedRuntime();
        changeTags(fixture, remove);
        assertEquals(remove ? Map.of("other", "retained") : Map.of("key", "new", "other", "retained"),
                fixture.service.getTagsByArn(REGION, fixture.arn));
        fixture.delete();
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> changeTags(fixture, remove)).getErrorCode());
        assertAbsent(fixture);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tagReadWaitsForTagPublication(boolean remove) throws Exception {
        BedrockAgentCoreConcurrencyFixture fixture = taggedRuntime();
        Map<String, String> expected = remove ? Map.of("other", "retained") : Map.of("key", "new", "other", "retained");
        fixture.storage.parkNextWrite();
        assertOperationWaitsForParkedStore(fixture.storage,
                () -> changeTags(fixture, remove),
                () -> assertEquals(expected, fixture.service.getTagsByArn(REGION, fixture.arn)));
    }

    @Test
    void tagReadRechecksExistenceAfterDeletion() throws Exception {
        BedrockAgentCoreConcurrencyFixture fixture = taggedRuntime();
        fixture.storage.parkNextRead();
        assertOperationWaitsForParkedStore(fixture.storage, fixture::delete,
                () -> {
                    AwsException failure = assertThrows(AwsException.class,
                            () -> fixture.service.getTagsByArn(REGION, fixture.arn));
                    assertEquals("ResourceNotFoundException", failure.getErrorCode());
                });
        assertAbsent(fixture);
    }

    @Test
    void tagReadReturnsDetachedSnapshot() {
        BedrockAgentCoreConcurrencyFixture fixture = taggedRuntime();
        Map<String, String> tags = fixture.service.getTagsByArn(REGION, fixture.arn);
        changeTags(fixture, false);
        assertEquals(Map.of("key", "old", "other", "retained"), tags);
        tags.clear();
        assertEquals(Map.of("key", "new", "other", "retained"),
                fixture.service.getTagsByArn(REGION, fixture.arn));
    }

    private static BedrockAgentCoreConcurrencyFixture taggedRuntime() {
        BedrockAgentCoreConcurrencyFixture fixture = new BedrockAgentCoreConcurrencyFixture();
        fixture.service.tagByArn(REGION, fixture.arn, Map.of("key", "old", "other", "retained"));
        return fixture;
    }

    private static void changeTags(BedrockAgentCoreConcurrencyFixture fixture, boolean remove) {
        if (remove) {
            fixture.service.untagByArn(REGION, fixture.arn, List.of("key"));
        } else {
            fixture.service.tagByArn(REGION, fixture.arn, Map.of("key", "new"));
        }
    }

    private static void assertAbsent(BedrockAgentCoreConcurrencyFixture fixture) {
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> fixture.service.getAgentRuntime(fixture.runtimeId, REGION)).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> fixture.service.getTagsByArn(REGION, fixture.arn)).getErrorCode());
    }
}
