package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.cloudtrail.CloudTrailService;
import io.github.hectorvent.floci.services.iam.IamActionRegistry;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ResourceArnBuilder;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.Map;
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
 *   <li>The action is {@code sts:GetCallerIdentity}, which AWS allows without permissions</li>
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
    private final IamConditionContextResolver conditionContextResolver;
    private final CloudTrailService cloudTrailService;
    private final CurrentVertxRequest currentVertxRequest;

    @Inject
    public IamEnforcementFilter(EmulatorConfig config,
                                AccountResolver accountResolver,
                                IamService iamService,
                                IamPolicyEvaluator evaluator,
                                IamActionRegistry actionRegistry,
                                ResourceArnBuilder arnBuilder,
                                RequestContext requestContext,
                                IamConditionContextResolver conditionContextResolver,
                                CloudTrailService cloudTrailService,
                                CurrentVertxRequest currentVertxRequest) {
        this.config = config;
        this.accountResolver = accountResolver;
        this.iamService = iamService;
        this.evaluator = evaluator;
        this.actionRegistry = actionRegistry;
        this.arnBuilder = arnBuilder;
        this.requestContext = requestContext;
        this.conditionContextResolver = conditionContextResolver;
        this.cloudTrailService = cloudTrailService;
        this.currentVertxRequest = currentVertxRequest;
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
        if ("sts:GetCallerIdentity".equals(action)) {
            return; // AWS returns caller identity even when an identity policy explicitly denies it
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

        Map<String, String> conditionContext = conditionContextResolver.resolve(credentialScope, action, ctx);
        Decision decision = evaluator.evaluate(caller, null, action, resource, conditionContext);
        if (decision == Decision.DENY) {
            LOG.infov("IAM enforcement DENY: akid={0} action={1} resource={2}", akid, action, resource);
            String denyMessage = "User: arn:aws:iam::" + accountId
                    + ":user/" + akid + " is not authorized to perform: " + action
                    + " on resource: \"" + resource + "\""
                    + " because no identity-based policy allows the " + action + " action";
            emitS3DenialIfApplicable(akid, action, resource, ctx, region, denyMessage);
            ctx.abortWith(accessDeniedResponse(action, credentialScope, ctx.getMediaType()));
        }
    }

    /**
     * Best-effort CloudTrail emission for S3 access denials. Without this hook,
     * denied requests get aborted before {@code S3Controller}'s try/catch sees
     * them, so denials would never appear in CloudTrail logs — leaving a major
     * gap vs. real AWS for downstream audit ingestion. Failures here never
     * propagate (the deny response is the load-bearing behavior).
     */
    private void emitS3DenialIfApplicable(String akid, String action, String resource,
                                          ContainerRequestContext ctx, String region,
                                          String denyMessage) {
        try {
            if (action == null || !action.startsWith("s3:")) {
                return;
            }
            String eventName = mapS3ActionToEventName(action, ctx.getMethod());
            if (eventName == null) {
                return;
            }
            String[] bk = parseS3Resource(resource);
            String bucket = bk[0];
            String key = bk[1];

            String userAgent = null;
            String sourceIp = null;
            try {
                var rc = currentVertxRequest.getCurrent();
                if (rc != null) {
                    var req = rc.request();
                    if (req != null) {
                        userAgent = req.getHeader("User-Agent");
                        String fwd = req.getHeader("X-Forwarded-For");
                        if (fwd != null && !fwd.isBlank()) {
                            int comma = fwd.indexOf(',');
                            sourceIp = (comma > 0 ? fwd.substring(0, comma) : fwd).trim();
                        } else if (req.remoteAddress() != null) {
                            sourceIp = req.remoteAddress().host();
                        }
                    }
                }
            } catch (Exception e) {
                LOG.tracev(e, "CloudTrail: could not extract request context for IAM denial {0} on {1}", action, resource);
            }

            cloudTrailService.emitS3DataEvent(CloudTrailService.S3EventInput.builder()
                    .region(region)
                    .eventName(eventName)
                    .bucketName(bucket)
                    .key(key)
                    .accessKeyId(akid)
                    .sourceIp(sourceIp)
                    .userAgent(userAgent)
                    .errorCode("AccessDenied")
                    .errorMessage(denyMessage)
                    .eventTimeMillis(System.currentTimeMillis())
                    .build());
        } catch (RuntimeException e) {
            LOG.tracev(e, "CloudTrail denial emission failed for {0} on {1}", action, resource);
        }
    }

    // Package-private for unit testing.
    static String mapS3ActionToEventName(String action, String httpMethod) {
        if (action == null) return null;
        // Action set sourced from IamActionRegistry — see that file for any
        // additions. HEAD on an object is bucketed under s3:GetObject by the
        // registry, so we distinguish via httpMethod.
        return switch (action) {
            case "s3:GetObject" -> "HEAD".equalsIgnoreCase(httpMethod) ? "HeadObject" : "GetObject";
            case "s3:PutObject" -> "PutObject";
            case "s3:DeleteObject" -> "DeleteObject";
            case "s3:ListBucket" -> "ListObjects";
            case "s3:ListAllMyBuckets" -> "ListBuckets";
            case "s3:GetObjectAcl" -> "GetObjectAcl";
            case "s3:PutObjectAcl" -> "PutObjectAcl";
            case "s3:GetObjectTagging" -> "GetObjectTagging";
            case "s3:PutObjectTagging" -> "PutObjectTagging";
            case "s3:DeleteObjectTagging" -> "DeleteObjectTagging";
            default -> null;
        };
    }

    /** Returns [bucket, key] (key may be null if the resource is a bucket-level ARN). */
    // Package-private for unit testing.
    static String[] parseS3Resource(String resource) {
        if (resource == null || !resource.startsWith("arn:aws:s3:::")) {
            return new String[] { null, null };
        }
        String tail = resource.substring("arn:aws:s3:::".length());
        if (tail.isEmpty() || "*".equals(tail)) {
            return new String[] { null, null };
        }
        int slash = tail.indexOf('/');
        if (slash < 0) {
            return new String[] { tail, null };
        }
        String bucket = tail.substring(0, slash);
        String key = tail.substring(slash + 1);
        return new String[] { bucket, key.isEmpty() ? null : key };
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
