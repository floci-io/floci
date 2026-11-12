package io.floci.aws.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Routes a REST service's paths that another controller also claims, by the service the request
 * is signed for. A pre-matching filter calls {@link #apply} before JAX-RS route matching.
 *
 * <p>The service's controller registers the contested routes under an internal prefix. A request
 * for one of the public paths that is signed for the service is rewritten onto that prefix; any
 * other request keeps its path and reaches whichever controller claims it, usually S3's
 * path-style {@code /{bucket}} routes. The signing service comes from the {@code Authorization}
 * header's credential scope, or from the {@code X-Amz-Credential} query parameter on a
 * query-signed request.
 *
 * <p>The internal prefix is itself a path S3 could claim, as bucket {@code _name}. A request sent
 * straight to it that is not signed for the service is refused the way S3 refuses that bucket
 * name, since an S3 bucket name may not begin with an underscore, rather than reaching the
 * service.
 *
 * <p>A request routed under the prefix, rewritten onto it or sent to it, records the prefix
 * ({@link #internalPrefix}), so IAM matches it on the path the service's model gives the
 * operation: the path it was routed under, without the prefix.
 */
public final class SignedServiceRoute {

    /** The request property that holds the internal prefix a request was routed under. */
    public static final String INTERNAL_PREFIX_PROPERTY = SignedServiceRoute.class.getName() + ".internalPrefix";

    private final ResolvedServiceCatalog catalog;
    private final Set<String> externalKeys;
    private final String internalPrefix;
    private final Predicate<String> internalPaths;
    private final Predicate<String> publicPaths;

    /**
     * @param externalKey    the service's external key, as {@link ServiceDescriptor#externalKey()}
     * @param internalPrefix one path segment beginning with an underscore, such as {@code /_dlm}
     * @param publicPaths    which normalized request paths are the service's contested routes
     */
    public SignedServiceRoute(ResolvedServiceCatalog catalog, String externalKey, String internalPrefix,
                              Predicate<String> publicPaths) {
        this(catalog, Set.of(externalKey), internalPrefix, publicPaths);
    }

    /**
     * A service with more than one plane has one external key per plane, and a request signed for
     * either of them belongs to the service.
     *
     * @param externalKeys   the service's external keys, one per plane
     * @param internalPrefix one path segment beginning with an underscore, such as {@code /_dlm}
     * @param publicPaths    which normalized request paths are the service's contested routes
     */
    public SignedServiceRoute(ResolvedServiceCatalog catalog, Set<String> externalKeys, String internalPrefix,
                              Predicate<String> publicPaths) {
        if (!internalPrefix.startsWith("/_") || internalPrefix.indexOf('/', 1) >= 0) {
            throw new IllegalArgumentException("Internal prefix must be one segment starting with '/_': "
                    + internalPrefix);
        }
        this.catalog = catalog;
        this.externalKeys = Set.copyOf(externalKeys);
        this.internalPrefix = internalPrefix;
        this.internalPaths = under(internalPrefix);
        this.publicPaths = publicPaths;
    }

    /** Matches {@code root} itself and every path beneath it. */
    public static Predicate<String> under(String root) {
        // Every request of every service runs these tests, so the prefix is built once.
        String beneath = root + "/";
        return path -> path.equals(root) || path.startsWith(beneath);
    }

    /**
     * The service a request is signed for, lower-cased, from the {@code Authorization} header or,
     * failing that, the {@code X-Amz-Credential} query parameter.
     */
    public static Optional<String> signingService(ContainerRequestContext ctx) {
        return SigV4CredentialScope.serviceName(ctx.getHeaderString("Authorization"))
                .or(() -> SigV4CredentialScope.serviceNameFromCredential(
                        ResteasyRequests.queryParameters(ctx).getFirst("X-Amz-Credential")));
    }

    /**
     * The internal prefix a request was routed under, as {@link #apply} recorded it: empty for a
     * request no route moved onto one or sent to one.
     */
    public static Optional<String> internalPrefix(ContainerRequestContext ctx) {
        return ctx.getProperty(INTERNAL_PREFIX_PROPERTY) instanceof String prefix ? Optional.of(prefix)
                : Optional.empty();
    }

    public void apply(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        String normalizedPath = path.startsWith("/") ? path : "/" + path;

        if (internalPaths.test(normalizedPath)) {
            if (!signedForService(ctx)) {
                ctx.abortWith(invalidBucketName());
                return;
            }
            ctx.setProperty(INTERNAL_PREFIX_PROPERTY, internalPrefix);
            return;
        }
        if (!publicPaths.test(normalizedPath) || !signedForService(ctx)) {
            return;
        }
        ctx.setRequestUri(ctx.getUriInfo().getRequestUriBuilder()
                .replacePath(internalPrefix + normalizedPath)
                .build());
        ctx.setProperty(INTERNAL_PREFIX_PROPERTY, internalPrefix);
    }

    private boolean signedForService(ContainerRequestContext ctx) {
        return signingService(ctx)
                .flatMap(catalog::byCredentialScope)
                .map(ServiceDescriptor::externalKey)
                .filter(externalKeys::contains)
                .isPresent();
    }

    private Response invalidBucketName() {
        String bucket = internalPrefix.substring(1);
        return Response.status(400)
                .type(MediaType.APPLICATION_XML)
                .entity("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Error><Code>InvalidBucketName</Code>"
                        + "<Message>The specified bucket is not valid.</Message>"
                        + "<BucketName>" + bucket + "</BucketName></Error>")
                .build();
    }
}
