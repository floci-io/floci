package io.github.hectorvent.floci.services.appsync.graphql;

import io.github.hectorvent.floci.services.appsync.graphql.util.AppSyncUtil;
import io.github.hectorvent.floci.services.appsync.graphql.util.VtlErrorSignal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class AppSyncVtlEngine {

    private final VelocityEngine engine;

    @Inject
    public AppSyncVtlEngine() {
        this.engine = new VelocityEngine();
        engine.setProperty(RuntimeConstants.INPUT_ENCODING, "UTF-8");
        engine.setProperty(RuntimeConstants.RUNTIME_LOG_NAME,
                "io.github.hectorvent.floci.appsync.vtl");
        engine.setProperty(RuntimeConstants.RESOURCE_LOADERS, "string");
        engine.setProperty("resource.loader.string.class",
                "org.apache.velocity.runtime.resource.loader.StringResourceLoader");
        engine.setProperty(RuntimeConstants.RUNTIME_REFERENCES_STRICT, false);
        engine.setProperty("userdirective",
                "io.github.hectorvent.floci.services.appsync.graphql.ReturnDirective");
        engine.init();
    }

    public AppSyncVtlResult evaluate(String template, AppSyncVtlContext ctx) {
        if (template == null || template.isEmpty()) {
            return new AppSyncVtlResult("", null, List.of());
        }

        VelocityContext vc = new VelocityContext();
        Map<String, Object> contextMap = ctx.getContextMap();

        vc.put("context", contextMap);
        vc.put("ctx", contextMap);
        vc.put("args", contextMap.get("arguments"));
        vc.put("source", contextMap.get("source"));
        vc.put("stash", contextMap.get("stash"));
        vc.put("prev", contextMap.get("prev"));

        AppSyncUtil util = ctx.getUtil();
        vc.put("util", util);
        vc.put("utils", util);

        StringWriter writer = new StringWriter();
        try {
            engine.evaluate(vc, writer, "appsync-template", template);
        } catch (ReturnSignal signal) {
            return new AppSyncVtlResult(signal.getValue(), null, ctx.getAppendedErrors());
        } catch (Exception e) {
            Throwable cause = e;
            while (cause != null) {
                if (cause instanceof VtlErrorSignal signal) {
                    return new AppSyncVtlResult("", signal, ctx.getAppendedErrors());
                }
                cause = cause.getCause();
            }
            throw new RuntimeException("VTL evaluation failed", e);
        }

        return new AppSyncVtlResult(writer.toString(), null, ctx.getAppendedErrors());
    }

}
