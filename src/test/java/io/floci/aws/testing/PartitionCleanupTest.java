package io.floci.aws.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PartitionCleanupTest {

    @Test
    void assertionFailureDoesNotStopEarlierCleanup() {
        PartitionCleanup cleanup = new PartitionCleanup();
        AtomicBoolean olderActionRan = new AtomicBoolean();
        cleanup.register(() -> olderActionRan.set(true));
        cleanup.register(() -> {
            throw new AssertionError("failed removal");
        });

        assertDoesNotThrow(() -> cleanup.afterEach(mock(ExtensionContext.class)));
        assertTrue(olderActionRan.get());
    }
}
