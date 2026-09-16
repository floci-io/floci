package io.github.hectorvent.floci.services.apigateway;

import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Isolated, non-CDI test proving that {@link VtlSandbox#installSandboxedForeach(VelocityEngine)}
 * actually wires {@link SandboxedForeach}'s per-iteration {@link VtlExecutionGuard#checkDeadline()}
 * call into a real {@code #foreach} render.
 *
 * <p>This is deliberately distinct from the loop-count cap test in {@code VtlTemplateEngineTest}
 * (which only proves Velocity's own native {@code directive.foreach.max_loops}) and from
 * {@link BoundedWriterTest} (which only proves the output-size cap): the template below never
 * writes its growing {@code $s} to the output writer at all, so a failure here can only come from
 * the execution-time deadline tripping inside the loop body, which is exactly the "runaway
 * {@code #set} string doubling that never reaches the output writer" scenario the deadline check
 * exists to catch.
 */
class SandboxedForeachTest {

    private VelocityEngine sandboxedEngine() {
        VelocityEngine engine = new VelocityEngine();
        engine.setProperty(RuntimeConstants.RESOURCE_LOADERS, "string");
        engine.setProperty("resource.loader.string.class",
                "org.apache.velocity.runtime.resource.loader.StringResourceLoader");
        VtlSandbox.restrictIntrospection(engine);
        engine.init();
        VtlSandbox.installSandboxedForeach(engine);
        return engine;
    }

    @Test
    void runawayStringDoublingNeverWrittenToOutput_isStoppedByDeadline_notByOutputCap() {
        VelocityEngine engine = sandboxedEngine();
        // Doubling a 2-character seed 40 times would reach roughly 2 billion characters, well
        // past what any sane loop-count cap (10,000) would prevent, and $s is never written to
        // the output writer, so BoundedWriter cannot be what stops this either.
        String template = "#set($s = \"xy\")#foreach($i in [1..40])#set($s = \"$s$s\")#end";
        StringWriter writer = new StringWriter();

        VtlExecutionGuard.begin(Duration.ofMillis(20));
        try {
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> engine.evaluate(new VelocityContext(), writer, "runaway-doubling", template));
            assertTrue(causedByDeadline(ex),
                    "expected the failure to be caused by VtlLimitExceededException (the deadline check), but was: "
                            + ex);
        } finally {
            VtlExecutionGuard.end();
        }

        assertEquals("", writer.toString(),
                "nothing should have reached the output writer before the deadline tripped inside the loop");
    }

    @Test
    void shortLoopWithinDeadline_completesNormally() {
        VelocityEngine engine = sandboxedEngine();
        String template = "#foreach($i in [1..5])x#end";
        StringWriter writer = new StringWriter();

        VtlExecutionGuard.begin(Duration.ofSeconds(30));
        try {
            engine.evaluate(new VelocityContext(), writer, "short-loop", template);
        } finally {
            VtlExecutionGuard.end();
        }

        assertEquals("xxxxx", writer.toString());
    }

    private static boolean causedByDeadline(Throwable t) {
        while (t != null) {
            if (t instanceof VtlLimitExceededException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
