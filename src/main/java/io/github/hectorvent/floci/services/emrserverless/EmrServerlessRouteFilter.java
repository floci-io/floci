package io.github.hectorvent.floci.services.emrserverless;

import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;

/**
 * Pre-matching filter that disambiguates REST JSON routes for EMR Serverless.
 * EMR Serverless and AppConfig both use `/applications` in their REST API, which
 * causes a JAX-RS routing collision. This filter rewrites EMR Serverless requests
 * to an internal path.
 */
@Provider
@PreMatching
@Priority(5000)
public class EmrServerlessRouteFilter implements ContainerRequestFilter {

    static final String INTERNAL_PATH = "/_emrserverless/applications";

    @Override
    public void filter(ContainerRequestContext ctx) {
        UriInfo uriInfo = ctx.getUriInfo();
        String path = uriInfo.getPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        if (!path.startsWith("applications")) {
            return;
        }

        String host = ctx.getHeaderString("Host");
        boolean isEmrServerlessHost = host != null && host.startsWith("emr-serverless.");

        String auth = ctx.getHeaderString("Authorization");
        boolean isEmrServerlessAuth = auth != null && auth.contains("/emr-serverless/aws4_request");

        if (isEmrServerlessHost || isEmrServerlessAuth) {
            URI rewritten = uriInfo.getRequestUriBuilder()
                    .replacePath(INTERNAL_PATH + (path.length() > 12 ? path.substring(12) : ""))
                    .build();
            ctx.setRequestUri(rewritten);
        }
    }
}
