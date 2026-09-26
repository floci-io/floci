package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.s3.PreSignedUrlFilter;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.Set;

/**
 * Populates {@link RequestContext} with the account ID and region derived from
 * the incoming AWS Authorization header or, for presigned URL requests, the
 * X-Amz-Credential query parameter. Runs at AUTHENTICATION priority so that
 * downstream filters (e.g. IAM enforcement) can rely on the context being set.
 *
 * <p>Account resolution precedence: a 12-digit access key ID is used directly as
 * the account; otherwise IAM and temporary credentials (e.g. assumed-role {@code ASIA...}
 * keys) are looked up via {@link SessionAccountLookup}; if
 * neither matches, the configured default account applies.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION - 100)
public class AccountContextFilter implements ContainerRequestFilter {

    /**
     * Set by a {@code @PreMatching} Host filter that resolved the request to a resource owned by
     * one account (a Cognito custom domain). Such requests carry no AWS credential, so the
     * owner's account is used instead of the default one.
     */
    public static final String PINNED_ACCOUNT_PROPERTY = AccountContextFilter.class.getName() + ".accountId";

    private static final Logger LOG = Logger.getLogger(AccountContextFilter.class);

    /** The signing names S3 answers to; an S3 client expects S3's XML error document. */
    private static final Set<String> S3_SIGNING_NAMES = Set.of("s3", "s3express");

    private final AccountResolver accountResolver;
    private final RegionResolver regionResolver;
    private final RequestContext requestContext;
    private final SessionAccountLookup sessionAccountLookup;
    // Lazily resolved: JAX-RS providers are instantiated before runtime config mappings exist
    // (the AwsProtocolClaimFilter pattern). jakarta.inject.Provider is qualified inline because
    // this class also carries the JAX-RS @Provider annotation.
    private final jakarta.inject.Provider<EmulatorConfig> configProvider;

    @Inject
    public AccountContextFilter(AccountResolver accountResolver,
                                RegionResolver regionResolver,
                                RequestContext requestContext,
                                SessionAccountLookup sessionAccountLookup,
                                jakarta.inject.Provider<EmulatorConfig> configProvider) {
        this.accountResolver = accountResolver;
        this.regionResolver = regionResolver;
        this.requestContext = requestContext;
        this.sessionAccountLookup = sessionAccountLookup;
        this.configProvider = configProvider;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        Object pinnedAccount = ctx.getProperty(PINNED_ACCOUNT_PROPERTY);
        if (pinnedAccount != null) {
            requestContext.setAccountId(pinnedAccount.toString());
            applyRegion(regionResolver.resolveRegionFromAuth(null));
            return;
        }
        String auth = ctx.getHeaderString("Authorization");
        if (auth != null && !auth.isEmpty()) {
            String akid = accountResolver.extractAccessKeyId(auth);
            requestContext.setAccountId(resolveAccount(akid, accountResolver.resolve(auth)));
            String region = regionResolver.resolveRegionFromAuth(auth);
            applyRegion(region);
            rejectUnknownRegion(ctx, region, SigV4CredentialScope.serviceName(auth));
        } else {
            String credential = ctx.getUriInfo().getQueryParameters().getFirst("X-Amz-Credential");
            if (credential != null && !credential.isEmpty()) {
                String akid = accountResolver.extractPresignedAccessKeyId(credential);
                requestContext.setAccountId(
                        resolveAccount(akid, accountResolver.resolveFromPresignedCredential(credential)));
                String region = regionResolver.resolveRegionFromPresignedCredential(credential);
                applyRegion(region);
                rejectUnknownRegion(ctx, region, SigV4CredentialScope.serviceNameFromCredential(credential));
            } else {
                requestContext.setAccountId(accountResolver.resolve(null));
                applyRegion(regionResolver.resolveRegionFromAuth(null));
            }
        }
    }

    /**
     * The region and its partition travel together: the SigV4 credential scope is the only
     * place a request says which partition it belongs to (a China client signs {@code cn-north-1}
     * even for IAM), so the partition is derived here, once, from the same value.
     */
    private void applyRegion(String region) {
        requestContext.setRegion(region);
        requestContext.setPartition(regionResolver.partitionForRegion(region));
    }

    /**
     * A scope region no partition publishes or admits by its region pattern is refused, as moto
     * and LocalStack refuse it by default: on AWS such a label never resolves a host, and served
     * here it would mint ARNs and a storage namespace for a region that does not exist. The
     * region and partition stay on the request context so the error mappers see them. S3
     * clients get S3's own XML {@code AuthorizationHeaderMalformed} (the wording S3 and minio use
     * for a wrong scope region). Everyone else gets {@code InvalidSignatureException}, as a Query
     * {@code <ErrorResponse>} for a form-encoded request and as JSON otherwise: this filter runs
     * before the protocol claim, and a Query SDK cannot parse a JSON error body.
     */
    private void rejectUnknownRegion(ContainerRequestContext ctx, String region, Optional<String> signingName) {
        if (RegionResolver.isKnownRegion(region) || configProvider.get().partitions().allowUnknownRegions()) {
            return;
        }
        LOG.debugv("Refusing request signed for unknown region {0} (service {1}): {2} {3}",
                region, signingName.orElse("?"), ctx.getMethod(), ctx.getUriInfo().getPath());
        if (signingName.isPresent() && S3_SIGNING_NAMES.contains(signingName.get())) {
            ctx.abortWith(PreSignedUrlFilter.errorResponse(400, "AuthorizationHeaderMalformed",
                    "The authorization header is malformed; the region '" + region
                            + "' is wrong; expecting a region AWS publishes."));
            return;
        }
        String message = "Region '" + region + "' is not a region in any AWS partition. Sign the request "
                + "for a published region, or set floci.partitions.allow-unknown-regions=true.";
        if (isFormEncoded(ctx.getMediaType())) {
            ctx.abortWith(AwsQueryResponse.error("InvalidSignatureException", message, null, 400));
            return;
        }
        ctx.abortWith(AwsProtocolClaimFilter.errorResponse(400, "InvalidSignatureException", message));
    }

    private static boolean isFormEncoded(MediaType mediaType) {
        return mediaType != null
                && "application".equalsIgnoreCase(mediaType.getType())
                && "x-www-form-urlencoded".equalsIgnoreCase(mediaType.getSubtype());
    }

    /**
     * Applies the account-resolution precedence. A 12-digit AKID is already reflected in
     * {@code resolvedDefault} (the account or default returned by {@link AccountResolver});
     * for any other key shape, an IAM or live-session lookup takes precedence before falling back.
     */
    private String resolveAccount(String akid, String resolvedDefault) {
        if (akid != null && !akid.matches("\\d{12}")) {
            Optional<String> credentialAccount = sessionAccountLookup.resolveAccountId(akid);
            if (credentialAccount.isPresent()) {
                return credentialAccount.get();
            }
        }
        return resolvedDefault;
    }
}
