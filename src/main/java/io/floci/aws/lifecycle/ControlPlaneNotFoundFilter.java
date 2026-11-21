package io.floci.aws.lifecycle;

import io.floci.aws.core.common.RoutedResource;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers a path under the control-plane prefixes ({@code /_floci}, {@code /_localstack}) that no
 * control-plane resource serves with a JSON 404, the catch-all every Floci emulator shares. Without
 * it the request falls through to S3's path-style routes as an object in a bucket named
 * {@code _floci}, which no S3 bucket can be named, and a client probing a wrong path reads an S3 XML
 * error. A control-plane resource is one whose route is declared under the prefix: the lifecycle
 * endpoints, the console, and the ECR, EKS, AppSync and sign-in routes.
 * <p>
 * Runs after route matching, so {@link ResourceInfo} names the route, and before the protocol claim
 * and every signature or IAM check, which would otherwise judge the request as an S3 call.
 */
@Provider
@Priority(Priorities.AUTHENTICATION - 300)
public class ControlPlaneNotFoundFilter implements ContainerRequestFilter {

    static final List<String> PREFIXES = List.of("_floci", "_localstack");
    private static final String PREFIX_TEMPLATE = "{prefix:(_floci|_localstack)}";

    @Context
    ResourceInfo resourceInfo;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        if (!isControlPlanePath(path)) {
            return;
        }
        Class<?> resourceClass = RoutedResource.resourceClass(resourceInfo);
        if (resourceClass != null && isControlPlaneRoute(resourceClass, resourceInfo.getResourceMethod())) {
            return;
        }
        ctx.abortWith(notFound(path));
    }

    /** True for {@code /_floci}, {@code /_localstack} and any path under them, with or without the leading slash. */
    public static boolean isControlPlanePath(String path) {
        String relative = stripLeadingSlash(path);
        for (String prefix : PREFIXES) {
            if (relative.equals(prefix) || relative.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    static boolean isControlPlaneRoute(Class<?> resourceClass, Method method) {
        String route = stripLeadingSlash(template(resourceClass.getAnnotation(Path.class)));
        if (route.isEmpty() && method != null) {
            route = stripLeadingSlash(template(method.getAnnotation(Path.class)));
        }
        return route.startsWith(PREFIX_TEMPLATE) || isControlPlanePath(route);
    }

    static Response notFound(String path) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "NotFound");
        body.put("path", path.startsWith("/") ? path : "/" + path);
        return Response.status(Response.Status.NOT_FOUND).type(MediaType.APPLICATION_JSON).entity(body).build();
    }

    private static String template(Path path) {
        return path == null ? "" : path.value();
    }

    private static String stripLeadingSlash(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }
}
