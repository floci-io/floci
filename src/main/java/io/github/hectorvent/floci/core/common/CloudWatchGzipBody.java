package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.NameBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds {@link CloudWatchGzipReaderInterceptor} to the resources annotated with it; every other
 * resource reads its body with no reader interceptor. For each intercepted read Quarkus REST
 * builds an interceptor context and looks up the resource method's parameter annotations
 * reflectively, a lookup that fails in Floci's native image for the tag endpoints resources
 * inherit from {@link AbstractTagsController}.
 */
@NameBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface CloudWatchGzipBody {
}
