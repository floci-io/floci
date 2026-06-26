package io.github.hectorvent.floci.core.common;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Populates {@link RequestContext} with the account ID and region derived from
 * the incoming AWS Authorization header or, for presigned URL requests, the
 * X-Amz-Credential query parameter. Runs at AUTHENTICATION priority so that
 * downstream filters (e.g. IAM enforcement) can rely on the context being set.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION - 100)
public class AccountContextFilter implements ContainerRequestFilter {

    private final AccountResolver accountResolver;
    private final RegionResolver regionResolver;
    private final RequestContext requestContext;

    @Inject
    public AccountContextFilter(AccountResolver accountResolver,
                                RegionResolver regionResolver,
                                RequestContext requestContext) {
        this.accountResolver = accountResolver;
        this.regionResolver = regionResolver;
        this.requestContext = requestContext;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        String auth = ctx.getHeaderString("Authorization");
        if (auth != null && !auth.isEmpty()) {
            requestContext.setAccountId(accountResolver.resolve(auth));
            requestContext.setRegion(regionResolver.resolveRegionFromAuth(auth));
        } else {
            String credential = ctx.getUriInfo().getQueryParameters().getFirst("X-Amz-Credential");
            if (credential != null && !credential.isEmpty()) {
                requestContext.setAccountId(accountResolver.resolveFromPresignedCredential(credential));
                requestContext.setRegion(regionResolver.resolveRegionFromPresignedCredential(credential));
            } else {
                requestContext.setAccountId(accountResolver.resolve(null));
                requestContext.setRegion(regionResolver.resolveRegionFromAuth(null));
            }
        }
    }
}
