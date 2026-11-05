package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.auth.SigV4aSignature;
import io.github.hectorvent.floci.services.s3.PreSignedUrlFilter;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
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

    @Context
    ResourceInfo resourceInfo;

    private final AccountResolver accountResolver;
    private final RegionResolver regionResolver;
    private final RequestContext requestContext;
    private final SessionAccountLookup sessionAccountLookup;
    // Lazily resolved: JAX-RS providers are instantiated before runtime config mappings exist
    // (the AwsProtocolClaimFilter pattern). jakarta.inject.Provider is qualified inline because
    // this class also carries the JAX-RS @Provider annotation.
    private final jakarta.inject.Provider<EmulatorConfig> configProvider;
    private final jakarta.inject.Provider<ResolvedServiceCatalog> catalogProvider;
    private volatile EmulatorConfig config;
    private volatile ResolvedServiceCatalog catalog;

    @Inject
    public AccountContextFilter(AccountResolver accountResolver,
                                RegionResolver regionResolver,
                                RequestContext requestContext,
                                SessionAccountLookup sessionAccountLookup,
                                jakarta.inject.Provider<EmulatorConfig> configProvider,
                                jakarta.inject.Provider<ResolvedServiceCatalog> catalogProvider) {
        this.accountResolver = accountResolver;
        this.regionResolver = regionResolver;
        this.requestContext = requestContext;
        this.sessionAccountLookup = sessionAccountLookup;
        this.configProvider = configProvider;
        this.catalogProvider = catalogProvider;
    }

    private EmulatorConfig config() {
        EmulatorConfig resolved = config;
        if (resolved == null) {
            resolved = configProvider.get();
            config = resolved;
        }
        return resolved;
    }

    private ResolvedServiceCatalog catalog() {
        ResolvedServiceCatalog resolved = catalog;
        if (resolved == null) {
            resolved = catalogProvider.get();
            catalog = resolved;
        }
        return resolved;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        Object pinnedAccount = ctx.getProperty(PINNED_ACCOUNT_PROPERTY);
        if (pinnedAccount != null) {
            requestContext.setAccountId(pinnedAccount.toString());
            requestContext.setAccessKeyId(null);
            requestContext.setSessionToken(null);
            applyRegion(regionResolver.resolveRegionFromAuth(null));
            return;
        }
        MultivaluedMap<String, String> query = ctx.getUriInfo().getQueryParameters();
        String sessionToken = extractSessionToken(ctx, query);
        String auth = ctx.getHeaderString("Authorization");
        if (rejectAmbiguousRegion(ctx, auth, query)) {
            return;
        }
        String akid = resourceInfo != null && catalog().byResourceClass(resourceInfo.getResourceClass())
                .map(ServiceDescriptor::acceptsLegacyAuthorization).orElse(false)
                && !config().services().iam().enforcementEnabled()
                ? accountResolver.extractS3AccessKeyId(auth) : accountResolver.extractAccessKeyId(auth);
        if (akid != null) {
            requestContext.setAccountId(resolveAccount(akid, accountResolver.resolve(auth)));
            requestContext.setAccessKeyId(akid);
            requestContext.setSessionToken(sessionToken);
            // A SigV4A scope has no region segment; the regions come from the signed region set.
            boolean sigV4a = auth.trim().startsWith(SigV4aSignature.ALGORITHM + " ");
            String region = sigV4a
                    ? regionResolver.resolveRegionFromRegionSet(ctx.getHeaderString("X-Amz-Region-Set"))
                    : regionResolver.resolveRegionFromAuth(auth);
            applyRegion(region);
            if (rejectUnknownRegion(ctx, region, SigV4CredentialScope.serviceName(auth))) {
                return;
            }
            rejectPartitionAbsentService(ctx, SigV4CredentialScope.serviceName(auth).orElse(null));
        } else {
            // A present Authorization header wins, as in IamEnforcementFilter: an unrecognised one
            // must not let the query string pick an account that IAM then never checks.
            boolean hasAuthHeader = auth != null && !auth.isBlank();
            String credential = hasAuthHeader
                    ? null : query.getFirst("X-Amz-Credential");
            if (credential != null && !credential.isEmpty()
                    && query.containsKey("X-Amz-Algorithm")) {
                String presignedAkid = accountResolver.extractPresignedAccessKeyId(credential);
                requestContext.setAccountId(
                        resolveAccount(presignedAkid, accountResolver.resolveFromPresignedCredential(credential)));
                requestContext.setAccessKeyId(presignedAkid);
                requestContext.setSessionToken(sessionToken);
                boolean sigV4a = SigV4aSignature.ALGORITHM.equals(
                        query.getFirst("X-Amz-Algorithm"));
                String region = sigV4a
                        ? regionResolver.resolveRegionFromRegionSet(
                                query.getFirst("X-Amz-Region-Set"))
                        : regionResolver.resolveRegionFromPresignedCredential(credential);
                applyRegion(region);
                if (rejectUnknownRegion(ctx, region, SigV4CredentialScope.serviceNameFromCredential(credential))) {
                    return;
                }
                rejectPartitionAbsentService(ctx,
                        SigV4CredentialScope.serviceNameFromCredential(credential).orElse(null));
            } else {
                requestContext.setAccountId(accountResolver.resolve(null));
                requestContext.setAccessKeyId(null);
                requestContext.setSessionToken(null);
                applyRegion(regionResolver.resolveRegionFromAuth(null));
            }
        }
    }

    private String extractSessionToken(ContainerRequestContext ctx, MultivaluedMap<String, String> query) {
        String token = ctx.getHeaderString("X-Amz-Security-Token");
        if (token != null && !token.isBlank()) {
            return token.trim();
        }
        token = query.getFirst("X-Amz-Security-Token");
        if (token != null && !token.isBlank()) {
            return token.trim();
        }
        return null;
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

    /** Refuses delimiters in region interpretations used by request context or downstream dispatch. */
    private boolean rejectAmbiguousRegion(ContainerRequestContext ctx, String authorization,
                                          MultivaluedMap<String, String> query) {
        String queryCredential = authorization == null || authorization.isBlank()
                ? query.getFirst("X-Amz-Credential") : null;
        String parsedAuthorization = queryCredential == null ? authorization
                : IamEnforcementFilter.requestAuthorization(null, query);
        // Some Query routes synthesize this header even without X-Amz-Algorithm. Inspect the
        // same region they use, in addition to the split credential used by request context.
        String region = regionResolver.resolveRegionFromAuthOrNull(parsedAuthorization);
        if (!RegionResolver.containsArnDelimiter(region) && queryCredential != null) {
            region = regionResolver.resolveRegionFromPresignedCredential(queryCredential);
        }
        if (!RegionResolver.containsArnDelimiter(region)) {
            return false;
        }
        applyRegion(region);
        return rejectUnknownRegion(ctx, region, SigV4CredentialScope.serviceName(parsedAuthorization));
    }

    /**
     * A scope region no partition publishes or admits by its region pattern is refused, as moto
     * and LocalStack refuse it by default: on AWS such a label never resolves a host, and served
     * here it would mint ARNs and a storage namespace for a region that does not exist. The
     * region and partition stay on the request context so the error mappers see them. S3
     * clients get S3's own XML {@code AuthorizationHeaderMalformed} (the wording S3 and minio use
     * for a wrong scope region). Everyone else gets {@code InvalidSignatureException}, as a Query
     * {@code <ErrorResponse>} for a claimed Query or form-encoded request, CBOR for a claimed
     * CBOR request, and JSON otherwise. The pre-matching protocol claim is already available.
     */
    private boolean rejectUnknownRegion(ContainerRequestContext ctx, String region, Optional<String> signingName) {
        boolean ambiguous = RegionResolver.containsArnDelimiter(region);
        if (!ambiguous && (RegionResolver.isKnownRegion(region)
                || config().partitions().allowUnknownRegions())) {
            return false;
        }
        LOG.debugv("Refusing request signed for unknown region {0} (service {1}): {2} {3}",
                region, signingName.orElse("?"), ctx.getMethod(), ctx.getUriInfo().getPath());
        if (signingName.isPresent() && S3_SIGNING_NAMES.contains(signingName.get())) {
            ctx.abortWith(PreSignedUrlFilter.errorResponse(400, "AuthorizationHeaderMalformed",
                    ambiguous ? "The authorization header is malformed; the region '" + region
                            + "' must not contain ':'."
                            : "The authorization header is malformed; the region '" + region
                            + "' is wrong; expecting a region AWS publishes."));
            return true;
        }
        String message = ambiguous ? "Region '" + region + "' must not contain ':' because it separates ARN fields."
                : "Region '" + region + "' is not a region in any AWS partition. Sign the request "
                + "for a published region, or set floci.partitions.allow-unknown-regions=true.";
        WireProtocol protocol = ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY) instanceof ProtocolClaim claim
                ? claim.protocol() : WireProtocol.REST;
        if (protocol == WireProtocol.RPCV2_CBOR || protocol == WireProtocol.AWS_CBOR_TARGET) {
            String contentType = ctx.getHeaderString(AwsCborContentTypeFilter.ORIGINAL_CONTENT_TYPE_HEADER);
            if (contentType == null) {
                contentType = ctx.getHeaderString("Content-Type");
            }
            ctx.abortWith(CborErrorResponses.of(new AwsException("InvalidSignatureException", message, 400),
                    CborErrorResponses.mediaTypeFor(contentType)));
            return true;
        }
        if (protocol == WireProtocol.AWS_QUERY || isFormEncoded(ctx.getMediaType())) {
            ctx.abortWith(AwsQueryResponse.error("InvalidSignatureException", message, null, 400));
            return true;
        }
        ctx.abortWith(AwsProtocolClaimFilter.errorResponse(400, "InvalidSignatureException", message));
        return true;
    }

    private static boolean isFormEncoded(MediaType mediaType) {
        return mediaType != null
                && "application".equalsIgnoreCase(mediaType.getType())
                && "x-www-form-urlencoded".equalsIgnoreCase(mediaType.getSubtype());
    }

    /**
     * Strict partition mode. AWS publishes no endpoint for a service outside the partitions it
     * exists in (CloudFront in GovCloud, Lightsail in China); a client there never reaches
     * an API because the host does not resolve, which the SDKs surface as an
     * {@code UnknownHostException}. Floci cannot fail DNS, so it answers the request with the
     * same 404 shape the unknown-service-scope guard uses. Only a service the published data
     * lists in some other partition is refused: {@code endpoints.json} omits the newer services
     * entirely, and silence there is not absence. Off by default: Floci serves every enabled
     * service in every partition.
     */
    private void rejectPartitionAbsentService(ContainerRequestContext ctx, String signingName) {
        if (signingName == null || !config().partitions().strict()) {
            return;
        }
        Optional<AwsPartition> partition = AwsPartitions.find(requestContext.getPartition());
        if (partition.isEmpty() || !AwsPartitions.publishesSomewhere(signingName)
                || partition.get().offersSigningName(signingName)) {
            return;
        }
        String partitionId = partition.get().id();
        LOG.infov("Rejecting request signed for {0} in partition {1}, which publishes no endpoint for it: {2} {3}",
                signingName, partitionId, ctx.getMethod(), ctx.getUriInfo().getPath());
        String message = "Service " + signingName + " has no endpoint in partition " + partitionId
                + ": on AWS the host does not resolve there (the SDKs report an UnknownHostException). "
                + "Floci is running with floci.partitions.strict=true.";
        if (isFormEncoded(ctx.getMediaType())) {
            ctx.abortWith(AwsQueryResponse.error("UnknownOperationException", message, null, 404));
            return;
        }
        ctx.abortWith(AwsProtocolClaimFilter.unknownOperationResponse(404, message));
    }

    /**
     * Applies account resolution to header and presigned credentials, including legacy S3 keys.
     */
    private String resolveAccount(String akid, String resolvedDefault) {
        if (akid != null) {
            if (akid.matches("\\d{12}")) {
                return akid;
            }
            Optional<String> credentialAccount = sessionAccountLookup.resolveAccountId(akid);
            if (credentialAccount.isPresent()) {
                return credentialAccount.get();
            }
        }
        return resolvedDefault;
    }
}
