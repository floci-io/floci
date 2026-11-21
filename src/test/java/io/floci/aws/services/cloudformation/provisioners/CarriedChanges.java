package io.floci.aws.services.cloudformation.provisioners;

import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import org.mockito.MockedStatic;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.withSettings;

/**
 * Test support for provisioners that rebuild their {@link ProvisionContext} mid-provision: the
 * engine's {@link PropertyChanges} must still be on the context they hand to
 * {@link ReplacementCleanup#record}.
 */
final class CarriedChanges {

    /** A distinct instance, so a test can tell the engine's statement from one derived afterwards. */
    static final PropertyChanges ENGINE_STATEMENT = new PropertyChanges(Map.of());

    private CarriedChanges() {
    }

    /** {@code base} with the engine's statement attached. */
    static ProvisionContext withEngineStatement(ProvisionContext base) {
        return new ProvisionContext(base.engine(), base.region(), base.accountId(), base.stackName(),
                base.priorPhysicalId(), base.progress(), base.priorProperties(), ENGINE_STATEMENT);
    }

    /** Runs {@code provision} and returns the context its last {@code ReplacementCleanup.record} call received. */
    static ProvisionContext contextRecorded(Runnable provision) {
        try (MockedStatic<ReplacementCleanup> cleanup = mockStatic(ReplacementCleanup.class,
                withSettings().mockMaker(MockMakers.INLINE).defaultAnswer(CALLS_REAL_METHODS))) {
            provision.run();
            ArgumentCaptor<ProvisionContext> recorded = ArgumentCaptor.forClass(ProvisionContext.class);
            cleanup.verify(() -> ReplacementCleanup.record(any(), recorded.capture(), any()), atLeastOnce());
            return recorded.getAllValues().getLast();
        }
    }
}
