package io.github.hectorvent.floci.testing;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Deletes what a test created in a non-commercial scope, in reverse order, after each test.
 * The {@code @QuarkusTest} emulator is shared by every test class, and a resource left behind
 * under {@code cn-north-1} shows up in every later cross-region listing (ResourceExplorer scans
 * all resources), so a test that signs a foreign partition registers each create here.
 *
 * <pre>{@code
 * @RegisterExtension
 * final PartitionCleanup cleanup = new PartitionCleanup();
 * ...
 * createTable(region, name);
 * cleanup.register(() -> deleteTable(region, name));
 * }</pre>
 *
 * A failing cleanup never fails the test: it is reported on stderr so the leak is visible and
 * the assertion that actually failed stays the headline.
 */
public final class PartitionCleanup implements AfterEachCallback {

    private final Deque<Runnable> actions = new ArrayDeque<>();

    public void register(Runnable action) {
        actions.push(action);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        while (!actions.isEmpty()) {
            Runnable action = actions.pop();
            try {
                action.run();
            } catch (RuntimeException e) {
                System.err.println("PartitionCleanup: cleanup step failed after "
                        + context.getDisplayName() + ": " + e);
            }
        }
    }
}
