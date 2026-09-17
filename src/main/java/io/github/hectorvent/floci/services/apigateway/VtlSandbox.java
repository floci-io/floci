package io.github.hectorvent.floci.services.apigateway;

import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.util.introspection.SecureUberspector;

/**
 * Applies Floci's VTL reflection sandbox to a {@link VelocityEngine}, shared by both the
 * API Gateway and AppSync VTL engines.
 *
 * <p>Velocity normally reads {@code introspector.uberspect.class} and
 * {@code introspector.restrict.*} from the classpath resource
 * {@code org/apache/velocity/runtime/defaults/velocity.properties}, which this project shadows
 * (see {@code src/main/resources/org/apache/velocity/runtime/defaults/velocity.properties}) to
 * select {@link SecureUberspector} instead of Velocity's insecure default {@code UberspectImpl}.
 * That shadowed file is kept for any embedding that resolves the resource normally, but it is
 * <strong>not</strong> the actual enforcement point in this codebase: under Quarkus's custom
 * test/runtime classloader, {@code ClassLoader#getResourceAsStream} for that exact path can
 * resolve to the copy bundled inside the {@code velocity-engine-core} jar instead of this
 * project's shadowed copy, silently leaving the insecure {@code UberspectImpl} active. This was
 * confirmed empirically: with only the properties file changed, reflection-escape tests
 * (e.g. {@code $obj.getClass().forName(...)}) still passed against the vulnerable engine.
 *
 * <p>Setting these properties directly on the {@link VelocityEngine} via its Java API, before
 * {@link VelocityEngine#init()}, is unaffected by that classloader ambiguity, so it is the real,
 * verified enforcement mechanism. Both {@code VtlTemplateEngine} and {@code AppSyncVtlEngine}
 * call {@link #restrictIntrospection(VelocityEngine)} before {@code init()} and
 * {@link #installSandboxedForeach(VelocityEngine)} after {@code init()}.
 */
public final class VtlSandbox {

    /**
     * Packages blocked outright: any method call on an object from these packages is rejected,
     * regardless of which method. {@code java.lang.reflect} and {@code java.lang.invoke} close
     * off reflective/handle-based sandbox escapes; {@code javax.script} closes off the scripting
     * engine escape; {@code java.nio.file} closes off filesystem access alongside
     * {@code java.io.File} below.
     */
    private static final String[] RESTRICTED_PACKAGES = {
            "java.lang.reflect",
            "java.lang.invoke",
            "javax.script",
            "java.nio.file",
    };

    /**
     * Individual classes blocked outright. Mirrors Velocity's own recommended secure-introspector
     * defaults ({@code Class}, {@code ClassLoader}, {@code Runtime}, {@code ProcessBuilder},
     * {@code System}, {@code Thread}, etc.), plus {@code java.io.File} so templates cannot read or
     * write arbitrary files on the host.
     */
    private static final String[] RESTRICTED_CLASSES = {
            "java.lang.Class",
            "java.lang.ClassLoader",
            "java.lang.Compiler",
            "java.lang.InheritableThreadLocal",
            "java.lang.Package",
            "java.lang.Process",
            "java.lang.ProcessBuilder",
            "java.lang.Runtime",
            "java.lang.RuntimePermission",
            "java.lang.SecurityManager",
            "java.lang.System",
            "java.lang.Thread",
            "java.lang.ThreadGroup",
            "java.lang.ThreadLocal",
            "java.net.Socket",
            "javax.management.MBeanServer",
            "javax.script.ScriptEngine",
            "java.io.File",
    };

    private VtlSandbox() {
    }

    /**
     * Configures {@code engine} to use {@link SecureUberspector} with the restricted
     * packages/classes above. Must be called before {@link VelocityEngine#init()}.
     */
    public static void restrictIntrospection(VelocityEngine engine) {
        engine.setProperty(RuntimeConstants.UBERSPECT_CLASSNAME, SecureUberspector.class.getName());
        for (String pkg : RESTRICTED_PACKAGES) {
            engine.addProperty(RuntimeConstants.INTROSPECTOR_RESTRICT_PACKAGES, pkg);
        }
        for (String cls : RESTRICTED_CLASSES) {
            engine.addProperty(RuntimeConstants.INTROSPECTOR_RESTRICT_CLASSES, cls);
        }
    }

    /**
     * Replaces the built-in {@code #foreach} directive with {@link SandboxedForeach}, so every
     * loop iteration is subject to {@link VtlExecutionGuard}'s execution deadline check. Must be
     * called after {@link VelocityEngine#init()} (the built-in directive is only registered during
     * {@code init()}, so calling this beforehand would have nothing to remove/replace).
     */
    public static void installSandboxedForeach(VelocityEngine engine) {
        engine.removeDirective("foreach");
        engine.loadDirective(SandboxedForeach.class.getName());
    }
}
