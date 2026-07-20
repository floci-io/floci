package io.github.hectorvent.floci.services.appsync.graphql;

import org.apache.velocity.context.InternalContextAdapter;
import org.apache.velocity.runtime.directive.Directive;
import org.apache.velocity.runtime.parser.node.Node;

import java.io.Writer;
import java.io.IOException;

public class ReturnDirective extends Directive {

    @Override
    public String getName() {
        return "return";
    }

    @Override
    public int getType() {
        return LINE;
    }

    @Override
    public boolean render(InternalContextAdapter context, Writer writer, Node node)
            throws IOException {
        Object value = null;
        if (node.jjtGetNumChildren() > 0) {
            value = node.jjtGetChild(0).value(context);
        }
        throw new ReturnSignal(value);
    }
}
