package io.floci.aws.services.s3;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsEndpoints;
import io.floci.aws.core.common.AwsPartitions;
import io.floci.aws.core.common.MemoizedSupplier;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ResteasyRequests;
import io.floci.aws.core.common.SigV4CredentialScope;
import io.floci.aws.core.common.XmlBuilder;
import io.floci.aws.core.common.auth.SigV4aSignature;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.Optional;

/**
 * Answers a request signed for a Region other than its bucket's with S3's
 * {@code 301 PermanentRedirect}, carrying the bucket's Region in {@code x-amz-bucket-region}, as
 * S3 does. The SDKs follow it: botocore's {@code S3RegionRedirectorv2} re-signs for the header's
 * Region, and the Java SDK v2 does the same when {@code crossRegionAccessEnabled} is set
 * ({@code CrossRegionUtils}). A HEAD request gets the status and the header without a body, the
 * case botocore handles as {@code is_special_head_bucket} / {@code is_special_head_object}.
 *
 * <p>Served from any Region, as on AWS: ListBuckets (no bucket), GetBucketLocation (the S3 model:
 * "In other Regions, the bucket policy doesn't apply") and CreateBucket, whose Region rules are
 * {@link S3Controller#bucketRegionForCreate}'s. Only a SigV4 {@code Authorization} header is
 * checked: an unsigned request, a presigned URL and a SigV4A request (signed for a Region set)
 * are served as before, since no source shows how S3 redirects them. A bucket in another
 * partition is not redirected either, because S3's Regions redirect only within one.
 *
 * <p>Off with {@code floci.services.s3.region-redirect: false}, which serves every bucket from
 * every Region.
 */
@Provider
@Priority(Priorities.AUTHENTICATION - 50)
public class S3RegionRedirectFilter implements ContainerRequestFilter {

    private static final String MESSAGE = "The bucket you are attempting to access must be addressed using the "
            + "specified endpoint. Please send all future requests to this endpoint.";

    private final S3Service s3Service;
    // Resolved on first use: JAX-RS providers are built before the runtime config mappings exist.
    private final MemoizedSupplier<EmulatorConfig> config;
    private final RegionResolver regionResolver;
    private final ResourceInfo resourceInfo;

    @Inject
    public S3RegionRedirectFilter(S3Service s3Service,
                                  Instance<EmulatorConfig> config,
                                  RegionResolver regionResolver,
                                  @Context ResourceInfo resourceInfo) {
        this.s3Service = s3Service;
        this.config = new MemoizedSupplier<>(config::get);
        this.regionResolver = regionResolver;
        this.resourceInfo = resourceInfo;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!config.get().services().s3().regionRedirect() || !S3SignatureFilterScope.routedToS3(resourceInfo)) {
            return;
        }
        String authorization = ctx.getHeaderString("Authorization");
        if (authorization == null || authorization.trim().startsWith(SigV4aSignature.ALGORITHM + " ")
                || !"s3".equals(SigV4CredentialScope.serviceName(authorization).orElse(null))) {
            return;
        }
        String signingRegion = regionResolver.resolveRegionFromAuthOrNull(authorization);
        MultivaluedMap<String, String> pathParameters = ctx.getUriInfo().getPathParameters();
        String bucket = pathParameters.getFirst("bucket");
        if (signingRegion == null || bucket == null || bucket.isEmpty()) {
            return;
        }
        boolean bucketRequest = pathParameters.getFirst("key") == null;
        MultivaluedMap<String, String> query = ResteasyRequests.queryParameters(ctx);
        if (bucketRequest && servedFromAnyRegion(ctx.getMethod(), query)) {
            return;
        }
        Optional<String> bucketRegion = s3Service.findBucketRegion(bucket);
        if (bucketRegion.isEmpty() || bucketRegion.get().equals(signingRegion)
                || !AwsPartitions.forRegionOrCommercial(bucketRegion.get()).id()
                        .equals(AwsPartitions.forRegionOrCommercial(signingRegion).id())) {
            return;
        }
        ctx.abortWith(redirect(ctx.getMethod(), bucket, bucketRegion.get()));
    }

    private static boolean servedFromAnyRegion(String method, MultivaluedMap<String, String> query) {
        if ("GET".equals(method) && query.containsKey("location")) {
            return true;
        }
        return "PUT".equals(method) && query.keySet().stream().allMatch("x-id"::equals);
    }

    static Response redirect(String method, String bucket, String bucketRegion) {
        Response.ResponseBuilder response = Response.status(301).header("x-amz-bucket-region", bucketRegion);
        if ("HEAD".equals(method)) {
            return response.build();
        }
        String xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("Error")
                  .elem("Code", "PermanentRedirect")
                  .elem("Message", MESSAGE)
                  .elem("Bucket", bucket)
                  .elem("Endpoint", AwsEndpoints.s3RegionalHost(bucket, bucketRegion))
                .end("Error")
                .build();
        return response.entity(xml).type(MediaType.APPLICATION_XML).build();
    }
}
