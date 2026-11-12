package io.floci.aws.services.iam;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.iam.IamService.CallerArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Who is calling, for a service that checks a resource policy against the caller itself rather than leaving it to
 * IAM enforcement. A credential that names no IAM identity is its account's root, as for STS.
 */
@ApplicationScoped
public class RequestCallerResolver {

    private final IamService iamService;
    private final RegionResolver regionResolver;
    private final Instance<RequestContext> requestContext;

    @Inject
    public RequestCallerResolver(IamService iamService, RegionResolver regionResolver,
                                 Instance<RequestContext> requestContext) {
        this.iamService = iamService;
        this.regionResolver = regionResolver;
        this.requestContext = requestContext;
    }

    /** The caller of the current request. */
    public CallerArns caller() {
        return callerOf(accessKeyId());
    }

    /** Who signs with {@code accessKeyId}, as {@link #caller()} names the caller of a request. */
    public CallerArns callerOf(String accessKeyId) {
        String accountId = regionResolver.getAccountId();
        return iamService.resolveCallerArns(accessKeyId)
                .orElseGet(() -> CallerArns.accountRoot(regionResolver.buildGlobalArn("iam", accountId, "root"),
                        accountId));
    }

    private String accessKeyId() {
        try {
            return requestContext.isResolvable() ? requestContext.get().getAccessKeyId() : null;
        } catch (ContextNotActiveException e) {
            // A call outside a request, such as CloudFormation's, is the account's own.
            return null;
        }
    }
}
