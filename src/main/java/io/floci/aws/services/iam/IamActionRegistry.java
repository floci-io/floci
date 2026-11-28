package io.floci.aws.services.iam;

import io.floci.aws.core.common.AbstractTagsController;
import io.floci.aws.core.common.AwsProtocolClaimFilter;
import io.floci.aws.core.common.AwsQueryServiceResolver;
import io.floci.aws.core.common.IamOperation;
import io.floci.aws.core.common.ProtocolClaim;
import io.floci.aws.core.common.RequestResources;
import io.floci.aws.core.common.ResteasyRequests;
import io.floci.aws.core.common.SignedServiceRoute;
import io.floci.aws.core.common.WireProtocol;
import io.floci.aws.services.s3.S3BucketSubresources;
import io.floci.aws.services.s3.S3ObjectActions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps (credentialScope, httpMethod, requestPath) → IAM action string.
 *
 * For Query-protocol services (SQS, SNS, IAM, STS, ...) the Action form
 * parameter, or the controller's Operation fallback, is mapped directly to
 * {@code <service>:<Action>}.
 *
 * For REST-JSON services a request is the operation whose route in botocore's model it matches
 * ({@link IamRestRoutes}), when Floci can check that operation as AWS does.
 */
@ApplicationScoped
public class IamActionRegistry {

    private static final Logger LOG = Logger.getLogger(IamActionRegistry.class);

    private final IamRestRoutes restRoutes;

    public IamActionRegistry() {
        this(IamRestRoutes.vendored());
    }

    IamActionRegistry(IamRestRoutes restRoutes) {
        this.restRoutes = restRoutes;
    }

    /**
     * Whether the request is an S3 Control one: the route it matches is the {@code s3control} model's. A denial then
     * answers in S3 Control's own REST-XML envelope, the only one the s3control SDK deserializers read (issue #557),
     * so the route decides the shape and not the action. A request whose route cannot be read, which only a synthetic
     * context is, is not a control one.
     */
    public static boolean isS3ControlRequest(String credentialScope, ContainerRequestContext ctx) {
        if (!"s3".equals(credentialScope) || ctx.getMethod() == null || ctx.getUriInfo() == null
                || ctx.getUriInfo().getRequestUri() == null) {
            return false;
        }
        return matchedRoute(IamRestRoutes.vendored(), credentialScope, ctx)
                .map(match -> "s3control".equals(match.model())).orElse(false);
    }

    /**
     * Resolves the IAM action for an incoming request.
     *
     * For Query-protocol requests the action comes from {@code Action} or
     * {@code Operation} (e.g. {@code sqs:SendMessage}). REST requests never
     * read these caller-controlled fields as an IAM action.
     *
     * For JSON 1.1 protocol the action comes from {@code X-Amz-Target}
     * (e.g. {@code DynamoDB_20120810.PutItem} → {@code dynamodb:PutItem}).
     *
     * For REST-JSON services the action is that of the operation whose botocore route the request
     * matches.
     *
     * Returns {@code null} when the action is unknown (caller treats this as ALLOW).
     */
    public String resolve(String credentialScope, ContainerRequestContext ctx) {
        // REST requests with Action or Operation fields are still checked by their method and path.
        if (isQueryRequest(ctx)) {
            String queryAction = queryAction(ctx);
            if (queryAction != null && !queryAction.isBlank()) {
                return IamAuthorizedActions.actionFor(credentialScope, queryAction);
            }
        }

        // JSON 1.1: X-Amz-Target → the action AWS checks for the operation it names
        String operationName = targetOperation(ctx);
        if (operationName != null) {
            return resolveOperation(credentialScope, operationName, ctx);
        }

        return resolveRoute(credentialScope, ctx);
    }

    /** The operation a JSON request's {@code X-Amz-Target} names, the part after its last dot; null without one. */
    public static String targetOperation(ContainerRequestContext ctx) {
        String target = ctx.getHeaderString("X-Amz-Target");
        return target != null && target.contains(".") ? target.substring(target.lastIndexOf('.') + 1) : null;
    }

    /**
     * The action for a dispatched operation. One AWS authorizes by what its request's parts each do (a DynamoDB
     * PartiQL statement by its verb) is checked as the actions the request-resources table gives those parts.
     */
    public String resolveOperation(String credentialScope, String operationName, ContainerRequestContext ctx) {
        return IamAuthorizedActions.actionFor(credentialScope, operationName);
    }

    /** The serving method identifies these actions even when path encoding or matrix parameters vary. */
    public String resolveMatchedRoute(String credentialScope, ResourceInfo resourceInfo) {
        if (resourceInfo == null || resourceInfo.getResourceClass() == null) {
            return null;
        }
        IamOperation iamOperation = resourceInfo.getResourceClass().getAnnotation(IamOperation.class);
        if (iamOperation == null || !credentialScope.equals(iamOperation.namespace())) {
            return null;
        }
        return IamAuthorizedActions.actionFor(iamOperation.namespace(), iamOperation.operation());
    }

    /**
     * The scope a request Floci serves under {@code credentialScope} is checked under: the signing name AWS signs its
     * model's requests with, where the scope is a vendored model's own name the routes file does not route
     * ({@link IamRestRoutes#signedScope}), and the scope itself otherwise.
     */
    public String routedScope(String credentialScope) {
        return restRoutes.signedScope(credentialScope);
    }

    /**
     * The action of a REST request, from its method and path alone. A REST request is the operation
     * whose route it reached, so neither an {@code X-Amz-Target} header nor a Query {@code Action}
     * field may name another one: a caller could attach either to have the call checked as an
     * action they hold. The path is matched as sent, still percent-encoded, which is the form the
     * router matched: decoded, an encoded {@code /} inside a parameter splits it into two segments,
     * so its route would not match and the call would pass as an unknown action. It is read
     * from the request URI because RESTEasy Reactive does not serve {@code UriInfo.getPath(false)}.
     *
     * <p>The request is the operation whose route, in botocore's model of its service, it
     * matches, and is checked as that operation's action for the request's method
     * ({@link IamAuthorizedActions#actionFor(String, String, String)}): the one a request naming the operation
     * is checked as, or, for an operation AWS authorizes by method as it does API Gateway's management API,
     * {@code apigateway:PUT} for a PUT. That is when Floci can check it as AWS does ({@code checkedAsAws}). One
     * it cannot is left unmapped rather than checked on {@code *}, which would refuse a grant scoped to the
     * resource AWS checks it on. A request a route filter moved under an internal prefix is matched on its
     * path without the prefix ({@link #withoutInternalPrefix}), the path the model gives it. Returns {@code null}
     * when nothing maps the request (caller treats this as ALLOW).
     */
    public String resolveRoute(String credentialScope, ContainerRequestContext ctx) {
        String method = ctx.getMethod().toUpperCase();
        // Matched on the path the router dispatched on, as every scope is: S3 too, whose key of slashes alone
        // S3SlashOnlyKeyFilter rewrites escaped (PUT /bucket// writes the key "/"), and whose own reading of a key,
        // which keeps the slashes it was sent with, names the object (RequestPathLabels).
        String path = routedPath(ctx);
        MultivaluedMap<String, String> query = ctx.getUriInfo() == null ? null : ctx.getUriInfo().getQueryParameters();
        Optional<IamRestRoutes.Match> match = restRoutes.match(credentialScope, method, withoutInternalPrefix(ctx, path),
                query, movedUnderPrefix(ctx), ctx.getHeaders(), routedLabels(ctx));
        if (match.isPresent()) {
            String action = IamAuthorizedActions.actionFor(credentialScope, match.get().operation(), method,
                    match.get().model(), query);
            if (checkedAsAws(credentialScope, match.get().operation(), action)) {
                return action;
            }
        }

        LOG.debugv("No action mapping for {0} {1} {2} — defaulting to ALLOW", credentialScope, method, path);
        return null;
    }

    /** A REST model's explicit denial status, retaining the protocol default when none is modelled. */
    public static int accessDeniedStatus(String credentialScope, int fallback) {
        return IamRestRoutes.vendored().accessDeniedStatus(credentialScope, fallback);
    }

    /**
     * The value a REST request's path gives each label of the vendored route it matches, decoded, by the label's
     * name; empty when it matches none. {@link RequestResources} reads a member the table of request resources
     * locates in the path from them, as {@link #resolveRoute} matches the route.
     */
    public static Map<String, String> pathLabels(String credentialScope, ContainerRequestContext ctx) {
        IamRestRoutes routes = IamRestRoutes.vendored();
        if (credentialScope == null || !routes.routes(credentialScope)) {
            return Map.of();
        }
        return matchedRoute(routes, credentialScope, ctx).map(IamRestRoutes.Match::labels).orElse(Map.of());
    }

    /**
     * Whether a request of the credential scope is taken by a route of a model AWS documents on a page of its own
     * rather than in botocore, as Glue's Iceberg REST endpoint is: such a request is another API's, Apache Iceberg's
     * REST catalog, so it carries none of its operation's members, and one its body gives was added by the caller.
     */
    public static boolean takenByDocumentedRoute(String credentialScope, ContainerRequestContext ctx) {
        IamRestRoutes routes = IamRestRoutes.vendored();
        return credentialScope != null && routes.documents(credentialScope)
                && matchedRoute(routes, credentialScope, ctx).map(IamRestRoutes.Match::documented).orElse(false);
    }

    /**
     * The vendored route of the credential scope a request's method, path and query match, as {@link #resolveRoute}
     * matches it.
     */
    private static Optional<IamRestRoutes.Match> matchedRoute(IamRestRoutes routes, String credentialScope,
                                                              ContainerRequestContext ctx) {
        return routes.match(credentialScope, ctx.getMethod().toUpperCase(), withoutInternalPrefix(ctx, routedPath(ctx)),
                ctx.getUriInfo() == null ? null : ctx.getUriInfo().getQueryParameters(), movedUnderPrefix(ctx),
                ctx.getHeaders(), routedLabels(ctx));
    }

    /**
     * Whether the path of a request sent to the URI {@code uriInfo} gives is one of the credential scope's vendored
     * model, whatever method it is sent with: matched still percent-encoded, so an encoded {@code /} stays inside
     * its label and an unencoded one ends it. True for a scope whose routes are not vendored, which the model
     * cannot answer for.
     */
    public static boolean modelRoutes(String credentialScope, UriInfo uriInfo) {
        IamRestRoutes routes = IamRestRoutes.vendored();
        return !routes.routes(credentialScope)
                || routes.takesPath(credentialScope, sentPath(uriInfo), uriInfo.getQueryParameters());
    }

    /** Keep the modeled operation when AWS authorizes its route under a different action name. */
    public String operationForRoute(String credentialScope, String action, ContainerRequestContext ctx) {
        if (credentialScope == null || action == null) {
            return action;
        }
        String method = ctx.getMethod().toUpperCase();
        MultivaluedMap<String, String> query = ctx.getUriInfo() == null ? null : ctx.getUriInfo().getQueryParameters();
        return restRoutes.match(credentialScope, method, withoutInternalPrefix(ctx, routedPath(ctx)), query,
                        movedUnderPrefix(ctx), ctx.getHeaders(), routedLabels(ctx))
                .filter(match -> action.equals(IamAuthorizedActions.actionFor(credentialScope, match.operation(),
                        method, match.model(), query)))
                .map(match -> credentialScope + ":" + match.operation())
                .orElse(action);
    }

    /**
     * The vendored route a request of an operation matched: the member locations of a generated alternative route
     * ({@code located}; absent entries keep the operation's model locations) and the botocore model whose route it is
     * ({@code model}), which tells apart the clients of an operation several serve, with that model's API version
     * ({@code apiVersion}), which the rows of the generated table read for its API alone name.
     */
    public record RouteMembers(Map<String, String> located, String model, String apiVersion) {
        static final RouteMembers NONE = new RouteMembers(Map.of(), null, null);
    }

    /**
     * Whether a REST request of the credential scope is taken alike by the routes of two operations, none outranking
     * the other, as one naming two of S3's sub-resources is ({@code PUT /bucket?cors&policy}): no botocore client
     * sends one, and it runs as whichever its handler picks, so no one action covers it.
     */
    public static boolean ambiguousRoute(String credentialScope, ContainerRequestContext ctx) {
        if (credentialScope == null || ctx.getMethod() == null || ctx.getUriInfo() == null
                || ctx.getUriInfo().getRequestUri() == null) {
            return false;
        }
        String path = routedPath(ctx);
        return IamRestRoutes.vendored().ambiguous(credentialScope, ctx.getMethod().toUpperCase(),
                withoutInternalPrefix(ctx, path), ctx.getUriInfo().getQueryParameters(), movedUnderPrefix(ctx),
                ctx.getHeaders(), routedLabels(ctx));
    }

    /** The route a request of the operation matches, {@link RouteMembers#NONE} for one it matches none of. */
    public static RouteMembers routeMembers(String operation, ContainerRequestContext ctx) {
        int separator = operation.indexOf(':');
        if (separator < 0 || ctx.getMethod() == null || ctx.getUriInfo() == null
                || ctx.getUriInfo().getRequestUri() == null) {
            return RouteMembers.NONE;
        }
        return IamRestRoutes.vendored().match(operation.substring(0, separator), ctx.getMethod().toUpperCase(),
                        withoutInternalPrefix(ctx, routedPath(ctx)), ctx.getUriInfo().getQueryParameters(),
                        movedUnderPrefix(ctx), ctx.getHeaders(), routedLabels(ctx))
                .filter(match -> match.operation().equals(operation.substring(separator + 1)))
                .map(match -> new RouteMembers(match.located(), match.model(),
                        IamRestRoutes.vendored().apiVersion(match.model())))
                .orElse(RouteMembers.NONE);
    }

    /**
     * Whether a request of the operation is checked on the resources AWS checks its action on: those its request
     * names, which the vendored request resources read for the operation ({@link RequestResources#reads}), or
     * {@code *} when AWS authorizes the action on no resource type ({@link IamAuthorizedActions#actsOnNoResourceType}),
     * so none of the resources in the request's path is needed.
     */
    private static boolean checkedAsAws(String credentialScope, String operation, String action) {
        return RequestResources.reads(credentialScope + ":" + operation)
                || IamAuthorizedActions.actsOnNoResourceType(action);
    }

    /**
     * The path the router matched, relative to the application root. A request no filter rewrote is matched
     * on its normalized path, still percent-encoded but with encoded unreserved characters such as {@code %2E}
     * decoded and dot segments removed, so {@code /functions/x/%2E%2E/fn} is {@code /functions/fn}; a request
     * a filter rewrote is matched on its new path, decoded. {@link #sentPath} for a synthetic context.
     */
    static String rawPath(ContainerRequestContext ctx) {
        ResteasyReactiveRequestContext request = ResteasyRequests.of(ctx);
        return request == null ? sentPath(ctx.getUriInfo()) : rooted(request.getPathWithoutPrefix());
    }

    /**
     * The request path relative to the application root, as {@link UriInfo#getPath()} gives it, but
     * still percent-encoded.
     */
    static String sentPath(UriInfo uriInfo) {
        String path = uriInfo.getRequestUri().getRawPath();
        String base = uriInfo.getBaseUri().getRawPath();
        if (base != null && base.length() > 1 && path.startsWith(base)) {
            path = path.substring(base.length() - 1);
        }
        return rooted(path);
    }

    /**
     * {@link #rawPath} decoded: the path {@link UriInfo#getPath()} would give for the route the router
     * matched. {@link UriInfo#getPath()} itself for a synthetic context.
     */
    public static String decodedPath(ContainerRequestContext ctx) {
        ResteasyReactiveRequestContext request = ResteasyRequests.of(ctx);
        if (request == null) {
            return ctx.getUriInfo().getPath();
        }
        String path = request.getPathWithoutPrefix();
        boolean rewritten = !request.getPath().equals(request.serverRequest().getRequestNormalisedPath());
        return rooted(rewritten ? path : URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8));
    }

    private static String rooted(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    /**
     * {@link #rawPath} without a trailing slash: the router matches {@code /clusters/} as {@code /clusters},
     * so a route ending at its last segment has to match it too, or the request would pass as an
     * unknown action.
     */
    static String routedPath(ContainerRequestContext ctx) {
        return withoutTrailingSlash(rawPath(ctx));
    }

    /**
     * The path without the internal prefix a route filter moved the request under ({@link SignedServiceRoute}), which
     * is the path the service's model gives the operation and the vendored routes hold; the path itself when it was
     * moved under none.
     */
    static String withoutInternalPrefix(ContainerRequestContext ctx, String path) {
        return SignedServiceRoute.internalPrefix(ctx)
                .filter(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"))
                .map(prefix -> path.length() == prefix.length() ? "/" : path.substring(prefix.length()))
                .orElse(path);
    }

    /** Whether a route filter moved the request under an internal prefix, so the router matched its path decoded. */
    private static boolean movedUnderPrefix(ContainerRequestContext ctx) {
        return SignedServiceRoute.internalPrefix(ctx).isPresent();
    }

    /**
     * The values the router gave the path parameters of the method it dispatched the request to, which tell apart the
     * vendored routes the request matches ({@link IamRestRoutes#match}); none for a request it has not dispatched. Nor
     * for one a shared tagging controller serves: it serves a service only on the routes the service's model gives
     * ({@code TagDispatcher#resolveHandler}), so the values its own path takes name no route of the service's.
     */
    private static List<String> routedLabels(ContainerRequestContext ctx) {
        ResteasyReactiveRequestContext request = ResteasyRequests.of(ctx);
        if (request != null && request.getTarget() != null
                && AbstractTagsController.class.isAssignableFrom(request.getTarget().getResourceClass())) {
            return List.of();
        }
        UriInfo uriInfo = ctx.getUriInfo();
        MultivaluedMap<String, String> parameters = uriInfo == null ? null : uriInfo.getPathParameters();
        List<String> values = new ArrayList<>();
        if (parameters != null) {
            parameters.values().forEach(values::addAll);
        }
        return values;
    }

    /** The path without its trailing slashes, the root's own kept. */
    static String withoutTrailingSlash(String path) {
        String trimmed = path;
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * Returns the Query action from GET query parameters or the POST form body, preserving the
     * entity stream. Both use the controller's Action-first, Operation-second rule; a POST URL
     * Action must not override the form operation that will execute.
     */
    public String queryAction(ContainerRequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.getMethod())) {
            return AwsQueryServiceResolver.action(ctx.getUriInfo().getQueryParameters());
        }
        return AwsQueryServiceResolver.action(
                RequestBodyReader.formField(ctx, "Action"),
                RequestBodyReader.formField(ctx, "Operation"));
    }

    /** Returns the API version from the same Query input that the controller dispatches. */
    public String queryVersion(ContainerRequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.getMethod())) {
            return ctx.getUriInfo().getQueryParameters().getFirst("Version");
        }
        return RequestBodyReader.formField(ctx, "Version");
    }

    /** The root form POST or CloudWatch GET that the Query controller dispatches. */
    public static boolean isQueryRequest(ContainerRequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.getMethod())) {
            return ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY) instanceof ProtocolClaim claim
                    && claim.protocol() == WireProtocol.AWS_QUERY;
        }
        if (ctx.getUriInfo() == null) {
            return false;
        }
        String path = ctx.getUriInfo().getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            return false;
        }
        MediaType mediaType = ctx.getMediaType();
        return "POST".equalsIgnoreCase(ctx.getMethod())
                && mediaType != null
                && "application".equalsIgnoreCase(mediaType.getType())
                && "x-www-form-urlencoded".equalsIgnoreCase(mediaType.getSubtype());
    }
}
