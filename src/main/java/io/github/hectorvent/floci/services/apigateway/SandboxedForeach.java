package io.github.hectorvent.floci.services.apigateway;

import org.apache.velocity.context.InternalContextAdapter;
import org.apache.velocity.runtime.directive.Foreach;
import org.apache.velocity.runtime.parser.node.Node;

import java.io.IOException;
import java.io.Writer;

/**
 * Drop-in replacement for Velocity's built-in {@code #foreach} directive that checks the current
 * thread's {@link VtlExecutionGuard} deadline before rendering each loop iteration.
 *
 * <p>Registered in place of {@code org.apache.velocity.runtime.directive.Foreach} via the
 * shadowed classpath resource {@code org/apache/velocity/runtime/defaults/directive.properties},
 * so it applies identically to every {@code #foreach} loop evaluated by either the API Gateway or
 * the AppSync VTL engine.
 */
public class SandboxedForeach extends Foreach {

    @Override
    protected void renderBlock(InternalContextAdapter context, Writer writer, Node node) throws IOException {
        VtlExecutionGuard.checkDeadline();
        super.renderBlock(context, writer, node);
    }
}
