package io.github.hectorvent.floci.lifecycle;

import io.github.hectorvent.floci.services.ecs.EcsService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link EcsService#clear()} and {@code reconcile()} share a lock, because the reconciler keeps
 * running across a reset by design — a reset is not a shutdown. A shared lock introduces a
 * deadlock risk that nothing else would catch, so that is what this covers.
 *
 * <p>Deliberately narrow. It does <em>not</em> prove the fences added for CloudControl and
 * CloudFormation: those drop writes from work that started before a reset, and asserting it needs
 * a seam to hold provisioning open across the reset, which does not exist yet. Earlier drafts of
 * this test hammered {@code clear()} hundreds of times across three services, which mutated global
 * state on the shared Quarkus instance for every test that ran afterwards — not worth it for
 * assertions that only checked "does not throw".
 */
@QuarkusTest
class StateResetFencingTest {

    @Inject
    EcsService ecsService;

    @Test
    void ecsReconcileAndClearDoNotDeadlock() throws Exception {
        Method reconcile = EcsService.class.getDeclaredMethod("reconcile");
        reconcile.setAccessible(true);

        Thread reconciling = new Thread(() -> {
            for (int i = 0; i < 20; i++) {
                try {
                    reconcile.invoke(ecsService);
                } catch (Exception e) {
                    throw new AssertionError("reconcile threw while clear() ran concurrently", e);
                }
            }
        }, "reset-fencing-reconcile");
        reconciling.start();
        for (int i = 0; i < 20; i++) {
            ecsService.clear();
        }
        reconciling.join(30_000);
        assertFalse(reconciling.isAlive(), "reconcile/clear deadlocked");

        // The reconciler must still be usable: clear() is a reset, not a shutdown.
        assertDoesNotThrow(() -> reconcile.invoke(ecsService));
    }
}
