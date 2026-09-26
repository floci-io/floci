package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbBackendLifecycle;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DynamoDbRuntimeTest {

    private final DynamoDbBackendLifecycle backend = mock(DynamoDbBackendLifecycle.class);
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final DynamoDbRuntime runtime = new DynamoDbRuntime(backend, config);

    @Test
    void drivesTheSelectedBackendLifecycle() {
        when(config.services().dynamodb().enabled()).thenReturn(true);

        runtime.start();
        runtime.checkReset();
        runtime.beforeReset();
        runtime.clear();
        runtime.afterReset();
        runtime.stop();

        InOrder order = inOrder(backend);
        order.verify(backend).start();
        order.verify(backend).checkReset();
        order.verify(backend).beforeReset();
        order.verify(backend).reset();
        order.verify(backend).afterReset();
        order.verify(backend).stop();
        verifyNoMoreInteractions(backend);
    }

    @Test
    void leavesTheBackendAloneWhenDynamoDbIsDisabled() {
        when(config.services().dynamodb().enabled()).thenReturn(false);

        runtime.start();
        runtime.checkReset();
        runtime.beforeReset();
        runtime.clear();
        runtime.afterReset();
        runtime.stop();

        verifyNoInteractions(backend);
    }
}
