package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.iam.IamActionRegistry;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ResourceArnBuilder;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JAX-RS filter that enforces IAM policies on every incoming request when
 * {@code floci.iam.enforcement-enabled = true}.
 *
 * <p>Bypass rules (request is always allowed through):
 * <ul>
 *   <li>Enforcement is disabled (default)</li>
 *   <li>Access key is {@code "test"} (root/admin stand-in)</li>
 *   <li>Access key is not found in the IAM store (backward-compatible with pre-existing credentials)</li>
 *   <li>The action cannot be resolved (unknown mapping → permissive)</li>
 * </ul>
 *
 * <p>Evaluates the caller's identity policies, optional session policy, and optional
 * permissions boundary. Resource-based policies (S3 bucket policy, Lambda resource
 * policy, etc.) are not yet supplied to this filter.
 */
@Provider
@ApplicationScoped
public class IamEnforcementFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(IamEnforcementFilter.class);

    /** Extracts the credential-scope service name (e.g. "s3", "lambda"). */
    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/");

    private final EmulatorConfig config;
    private final AccountResolver accountResolver;
    private final IamService iamService;
    private final IamPolicyEvaluator evaluator;
    private final IamActionRegistry actionRegistry;
    private final ResourceArnBuilder arnBuilder;
    private final RequestContext requestContext;

    @Inject
    public IamEnforcementFilter(EmulatorConfig config,
                                AccountResolver accountResolver,
                                IamService iamService,
                                IamPolicyEvaluator evaluator,
                                IamActionRegistry actionRegistry,
                                ResourceArnBuilder arnBuilder,
                                RequestContext requestContext) {
        this.config = config;
        this.accountResolver = accountResolver;
        this.iamService = iamService;
        this.evaluator = evaluator;
        this.actionRegistry = actionRegistry;
        this.arnBuilder = arnBuilder;
        this.requestContext = requestContext;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!config.services().iam().enforcementEnabled()) {
            return;
        }

        String auth = ctx.getHeaderString("Authorization");
        if (auth == null) {
            return;
        }

        String akid = accountResolver.extractAccessKeyId(auth);
        if (akid == null || "test".equals(akid)) {
            return; // root bypass
        }

        String credentialScope = extractCredentialScope(auth);
        if (credentialScope == null) {
            return;
        }

        String action = actionRegistry.resolve(credentialScope, ctx);
        if (action == null) {
            return; // unknown action → ALLOW (permissive)
        }

        CallerContext caller = iamService.resolveCallerContext(akid);
        if (caller == null) {
            return; // unknown access key → bypass (backward-compat)
        }

        String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
        String accountId = requestContext.getAccountId() == null
                ? accountResolver.resolve(auth)
                : requestContext.getAccountId();
        String resource = arnBuilder.build(credentialScope, ctx, region, accountId);

        Decision decision = evaluator.evaluate(caller, null, action, resource, null);
        if (decision == Decision.DENY) {
            LOG.infov("IAM enforcement DENY: akid={0} action={1} resource={2}", akid, action, resource);
            ctx.abortWith(accessDeniedResponse(action, credentialScope, ctx.getMediaType()));
        }
    }

    private String extractCredentialScope(String auth) {
        Matcher m = SERVICE_PATTERN.matcher(auth);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Builds a 403 Access Denied response in the wire format the calling SDK
     * expects. AWS SDKs hard-fail when they receive the wrong shape: an XML
     * parser blows up on a leading {@code {}, and a JSON parser blows up on
     * {@code <}. Pick the shape from request signals:
     *
     * <ul>
     *   <li>S3 → S3-flavored XML {@code <Error>...</Error>}</li>
     *   <li>{@code application/x-www-form-urlencoded} body → AWS Query
     *       {@code <ErrorResponse>...</ErrorResponse>} (IAM/STS/EC2/SQS/SNS/...)</li>
     *   <li>everything else (JSON 1.x, REST-JSON) → keep the historical JSON shape</li>
     * </ul>
     */
    // Package-private for unit testing.
    static Response accessDeniedResponse(String action, String credentialScope, MediaType requestMediaType) {
        String message = "User is not authorized to perform: " + action;
        if ("s3".equals(credentialScope)) {
            return s3XmlAccessDenied(message);
        }
        if (isFormEncoded(requestMediaType)) {
            return queryXmlAccessDenied(message);
        }
        return jsonAccessDenied(message);
    }

    private static boolean isFormEncoded(MediaType mt) {
        return mt != null
                && "application".equalsIgnoreCase(mt.getType())
                && "x-www-form-urlencoded".equalsIgnoreCase(mt.getSubtype());
    }

    private static Response queryXmlAccessDenied(String message) {
        String xml = new XmlBuilder()
                .start("ErrorResponse")
                  .start("Error")
                    .elem("Type", "Sender")
                    .elem("Code", "AccessDenied")
                    .elem("Message", message)
                  .end("Error")
                  .elem("RequestId", UUID.randomUUID().toString())
                .end("ErrorResponse")
                .build();
        return Response.status(403).type(MediaType.APPLICATION_XML).entity(xml).build();
    }

    private static Response s3XmlAccessDenied(String message) {
        String xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("Error")
                  .elem("Code", "AccessDenied")
                  .elem("Message", message)
                  .elem("RequestId", UUID.randomUUID().toString())
                .end("Error")
                .build();
        return Response.status(403).type(MediaType.APPLICATION_XML).entity(xml).build();
    }

    private static Response jsonAccessDenied(String message) {
        String body = "{\"__type\":\"AccessDeniedException\",\"message\":\"" + message + "\"}";
        return Response.status(403).type(MediaType.APPLICATION_JSON).entity(body).build();
    }
}
