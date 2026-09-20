package io.github.hectorvent.floci.services.iam;

import java.util.List;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedMap;

/**
 * Maps (credentialScope, httpMethod, requestPath) → IAM action string.
 *
 * For Query-protocol services (SQS, SNS, IAM, STS, ...) the Action form
 * parameter is mapped directly to {@code <service>:<Action>}.
 *
 * For REST-JSON services the first matching rule wins (specific before wildcard).
 */
@ApplicationScoped
public class IamActionRegistry {

    private static final Logger LOG = Logger.getLogger(IamActionRegistry.class);

    private record ActionRule(String service, String method, Pattern pathPattern, String action) {}

    private static final List<ActionRule> RULES = List.of(
        // ── S3 ─────────────────────────────────────────────────────────────────
        rule("s3", "GET",    "^/?$",                              "s3:ListAllMyBuckets"),
        rule("s3", "PUT",    "^/[^/]+/?$",                       "s3:CreateBucket"),
        rule("s3", "DELETE", "^/[^/]+/?$",                       "s3:DeleteBucket"),
        rule("s3", "HEAD",   "^/[^/]+/?$",                       "s3:ListBucket"),
        rule("s3", "GET",    "^/[^/]+/?$",                       "s3:ListBucket"),
        rule("s3", "GET",    "^/[^/]+/.+",                       "s3:GetObject"),
        rule("s3", "PUT",    "^/[^/]+/.+",                       "s3:PutObject"),
        rule("s3", "DELETE", "^/[^/]+/.+",                       "s3:DeleteObject"),
        rule("s3", "HEAD",   "^/[^/]+/.+",                       "s3:GetObject"),

        // ── Lambda ──────────────────────────────────────────────────────────────
        rule("lambda", "GET",    ".*/functions$",                          "lambda:ListFunctions"),
        rule("lambda", "POST",   ".*/functions$",                          "lambda:CreateFunction"),
        rule("lambda", "GET",    ".*/functions/[^/]+$",                    "lambda:GetFunction"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/code$",               "lambda:UpdateFunctionCode"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/configuration$",      "lambda:UpdateFunctionConfiguration"),
        rule("lambda", "DELETE", ".*/functions/[^/]+$",                    "lambda:DeleteFunction"),
        rule("lambda", "POST",   ".*/functions/[^/]+/invocations$",        "lambda:InvokeFunction"),
        rule("lambda", "GET",    ".*/functions/[^/]+/aliases$",            "lambda:ListAliases"),
        rule("lambda", "POST",   ".*/functions/[^/]+/aliases$",            "lambda:CreateAlias"),
        rule("lambda", "GET",    ".*/functions/[^/]+/aliases/[^/]+$",      "lambda:GetAlias"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/aliases/[^/]+$",      "lambda:UpdateAlias"),
        rule("lambda", "DELETE", ".*/functions/[^/]+/aliases/[^/]+$",      "lambda:DeleteAlias"),
        rule("lambda", "GET",    ".*/functions/[^/]+/policy$",             "lambda:GetPolicy"),
        rule("lambda", "POST",   ".*/functions/[^/]+/policy$",             "lambda:AddPermission"),
        rule("lambda", "DELETE", ".*/functions/[^/]+/policy/.+",           "lambda:RemovePermission"),
        rule("lambda", "GET",    ".*/event-source-mappings$",              "lambda:ListEventSourceMappings"),
        rule("lambda", "POST",   ".*/event-source-mappings$",              "lambda:CreateEventSourceMapping"),
        rule("lambda", "DELETE", ".*/event-source-mappings/[^/]+$",        "lambda:DeleteEventSourceMapping"),
        rule("lambda", "GET",    ".*/functions/[^/]+/url$",                "lambda:GetFunctionUrlConfig"),
        rule("lambda", "POST",   ".*/functions/[^/]+/url$",                "lambda:CreateFunctionUrlConfig"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/url$",                "lambda:UpdateFunctionUrlConfig"),
        rule("lambda", "DELETE", ".*/functions/[^/]+/url$",                "lambda:DeleteFunctionUrlConfig"),

        // ── DynamoDB (JSON 1.1, action from X-Amz-Target handled separately) ──
        // Handled via Query-style action extraction in the filter

        // ── RDS Data API ───────────────────────────────────────────────────────
        rule("rds-data", "POST", "^/Execute/?$",              "rds-data:ExecuteStatement"),
        rule("rds-data", "POST", "^/ExecuteSql/?$",           "rds-data:ExecuteSql"),
        rule("rds-data", "POST", "^/BatchExecute/?$",         "rds-data:BatchExecuteStatement"),
        rule("rds-data", "POST", "^/BeginTransaction/?$",     "rds-data:BeginTransaction"),
        rule("rds-data", "POST", "^/CommitTransaction/?$",    "rds-data:CommitTransaction"),
        rule("rds-data", "POST", "^/RollbackTransaction/?$",  "rds-data:RollbackTransaction"),

        // ── API Gateway ────────────────────────────────────────────────────────
        rule("apigateway", "GET",    ".*/account$",                       "apigateway:GET"),
        rule("apigateway", "PATCH",  ".*/account$",                       "apigateway:PATCH"),
        rule("apigateway", "GET",    ".*/restapis$",                        "apigateway:GET"),
        rule("apigateway", "POST",   ".*/restapis$",                        "apigateway:POST"),
        rule("apigateway", "GET",    ".*/restapis/.+",                      "apigateway:GET"),
        rule("apigateway", "PUT",    ".*/restapis/.+",                      "apigateway:PUT"),
        rule("apigateway", "PATCH",  ".*/restapis/.+",                      "apigateway:PATCH"),
        rule("apigateway", "DELETE", ".*/restapis/.+",                      "apigateway:DELETE"),
        rule("apigateway", "POST",   ".*/restapis/.+",                      "apigateway:POST"),

        // ── Kinesis ────────────────────────────────────────────────────────────
        rule("kinesis", "POST", ".*", "kinesis:*")
    );

    private static ActionRule rule(String service, String method, String path, String action) {
        return new ActionRule(service, method, Pattern.compile(path, Pattern.CASE_INSENSITIVE), action);
    }

    /**
     * Resolves the IAM action for an incoming request.
     *
     * For Query-protocol services the action comes directly from the {@code Action}
     * form param (e.g. {@code sqs:SendMessage}).
     *
     * For JSON 1.1 protocol the action comes from {@code X-Amz-Target}
     * (e.g. {@code DynamoDB_20120810.PutItem} → {@code dynamodb:PutItem}).
     *
     * For REST-JSON services the action is derived from the path rule table.
     *
     * Returns {@code null} when the action is unknown (caller treats this as ALLOW).
     */
    public String resolve(String credentialScope, ContainerRequestContext ctx) {
        // Query-protocol: Action param → service:Action.
        // AWS SDKs send Query-protocol calls (IAM, STS, EC2, SQS, SNS, ...) as
        // POST with Action=... in the application/x-www-form-urlencoded body,
        // not the URL query string — so we look in both places.
        String queryAction = ctx.getUriInfo().getQueryParameters().getFirst("Action");
        if (queryAction == null || queryAction.isBlank()) {
            queryAction = readFormAction(ctx);
        }
        if (queryAction != null && !queryAction.isBlank()) {
            return credentialScope + ":" + queryAction;
        }

        // JSON 1.1: X-Amz-Target → service:OperationName
        String target = ctx.getHeaderString("X-Amz-Target");
        if (target != null && target.contains(".")) {
            String operationName = target.substring(target.lastIndexOf('.') + 1);
            return credentialScope + ":" + operationName;
        }

        // REST-JSON: match against rule table
        String method = ctx.getMethod().toUpperCase();
        String path   = ctx.getUriInfo().getPath();
        if (!path.startsWith("/")) path = "/" + path;

        // S3 sub-resource override: the URL path alone doesn't distinguish
        // s3:GetObjectAcl / s3:PutObjectAcl from s3:GetObject / s3:PutObject
        // — only the {@code ?acl} query parameter does. Without this hook,
        // an actor with s3:GetObject permission could read ACLs that their
        // policy intends to forbid.
        if ("s3".equals(credentialScope)) {
            String s3SubAction = resolveS3SubResourceAction(method, ctx);
            if (s3SubAction != null) {
                return s3SubAction;
            }
        }

        for (ActionRule rule : RULES) {
            if (rule.service().equals(credentialScope)
                    && rule.method().equals(method)
                    && rule.pathPattern().matcher(path).find()) {
                return rule.action();
            }
        }

        LOG.debugv("No action mapping for {0} {1} {2} — defaulting to ALLOW", credentialScope, method, path);
        return null;
    }

    /** One bucket sub-resource operation: the query parameter that selects it, and the IAM action. */
    private record SubResourceAction(String queryParameter, String action) {}

    private static SubResourceAction sub(String queryParameter, String action) {
        return new SubResourceAction(queryParameter, action);
    }

    private static final List<SubResourceAction> GET_BUCKET_SUBRESOURCES = List.of(
            sub("uploads",           "s3:ListBucketMultipartUploads"),
            sub("notification",      "s3:GetBucketNotification"),
            sub("versioning",        "s3:GetBucketVersioning"),
            sub("versions",          "s3:ListBucketVersions"),
            sub("location",          "s3:GetBucketLocation"),
            sub("tagging",           "s3:GetBucketTagging"),
            sub("object-lock",       "s3:GetBucketObjectLockConfiguration"),
            sub("website",           "s3:GetBucketWebsite"),
            sub("logging",           "s3:GetBucketLogging"),
            sub("policy",            "s3:GetBucketPolicy"),
            sub("cors",              "s3:GetBucketCORS"),
            sub("lifecycle",         "s3:GetLifecycleConfiguration"),
            sub("acl",               "s3:GetBucketAcl"),
            sub("encryption",        "s3:GetEncryptionConfiguration"),
            sub("publicAccessBlock", "s3:GetBucketPublicAccessBlock"),
            sub("ownershipControls", "s3:GetBucketOwnershipControls"),
            sub("requestPayment",    "s3:GetBucketRequestPayment"),
            sub("accelerate",        "s3:GetAccelerateConfiguration"),
            sub("replication",       "s3:GetReplicationConfiguration"),
            sub("metrics",           "s3:GetMetricsConfiguration"));

    private static final List<SubResourceAction> PUT_BUCKET_SUBRESOURCES = List.of(
            sub("notification",      "s3:PutBucketNotification"),
            sub("versioning",        "s3:PutBucketVersioning"),
            sub("tagging",           "s3:PutBucketTagging"),
            sub("object-lock",       "s3:PutBucketObjectLockConfiguration"),
            sub("website",           "s3:PutBucketWebsite"),
            sub("logging",           "s3:PutBucketLogging"),
            sub("policy",            "s3:PutBucketPolicy"),
            sub("cors",              "s3:PutBucketCORS"),
            sub("lifecycle",         "s3:PutLifecycleConfiguration"),
            sub("acl",               "s3:PutBucketAcl"),
            sub("encryption",        "s3:PutEncryptionConfiguration"),
            sub("publicAccessBlock", "s3:PutBucketPublicAccessBlock"),
            sub("ownershipControls", "s3:PutBucketOwnershipControls"),
            sub("requestPayment",    "s3:PutBucketRequestPayment"),
            sub("accelerate",        "s3:PutAccelerateConfiguration"),
            sub("replication",       "s3:PutReplicationConfiguration"),
            sub("metrics",           "s3:PutMetricsConfiguration"));

    // AWS gives only DeleteBucketPolicy and DeleteBucketWebsite their own action; removing any other
    // sub-resource is authorised by the same Put* action that sets it. ?accelerate is absent because
    // S3Controller rejects DELETE on it with 405, so no mapping should claim the request.
    private static final List<SubResourceAction> DELETE_BUCKET_SUBRESOURCES = List.of(
            sub("tagging",           "s3:DeleteBucketTagging"),
            sub("website",           "s3:DeleteBucketWebsite"),
            sub("policy",            "s3:DeleteBucketPolicy"),
            sub("cors",              "s3:PutBucketCORS"),
            sub("lifecycle",         "s3:PutLifecycleConfiguration"),
            sub("encryption",        "s3:PutEncryptionConfiguration"),
            sub("publicAccessBlock", "s3:PutBucketPublicAccessBlock"),
            sub("ownershipControls", "s3:PutBucketOwnershipControls"),
            sub("replication",       "s3:PutReplicationConfiguration"),
            sub("metrics",           "s3:PutMetricsConfiguration"));

    private static String resolveS3SubResourceAction(String method, ContainerRequestContext ctx) {
        MultivaluedMap<String, String> params = ctx.getUriInfo().getQueryParameters();
        // /{bucket}?acl -> bucket-level; /{bucket}/{key}?acl -> object-level.
        // A trailing slash is a valid key character, so /bucket/folder/?acl is an object request -
        // we cannot use endsWith("/") to infer bucket-level.
        String path = ctx.getUriInfo().getPath();
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        int firstSlash = stripped.indexOf('/');
        boolean isBucketLevel = firstSlash < 0 || firstSlash == stripped.length() - 1;
        if (!isBucketLevel) {
            return objectSubResourceAction(method, params);
        }
        List<SubResourceAction> chain = switch (method) {
            case "GET" -> GET_BUCKET_SUBRESOURCES;
            case "PUT" -> PUT_BUCKET_SUBRESOURCES;
            case "DELETE" -> DELETE_BUCKET_SUBRESOURCES;
            default -> List.of();
        };
        for (SubResourceAction entry : chain) {
            if (params.containsKey(entry.queryParameter())) {
                return entry.action();
            }
        }
        return null;
    }

    private static String objectSubResourceAction(String method, MultivaluedMap<String, String> params) {
        if (params.containsKey("acl")) {
            return switch (method) {
                case "GET" -> "s3:GetObjectAcl";
                case "PUT" -> "s3:PutObjectAcl";
                default -> null;
            };
        }
        if (params.containsKey("tagging")) {
            return switch (method) {
                case "GET" -> "s3:GetObjectTagging";
                case "PUT" -> "s3:PutObjectTagging";
                case "DELETE" -> "s3:DeleteObjectTagging";
                default -> null;
            };
        }
        return null;
    }

    /**
     * Reads {@code Action} from a {@code application/x-www-form-urlencoded}
     * request body and restores the entity stream so downstream consumers
     * (e.g. {@code AwsQueryController}'s {@code MultivaluedMap} injection)
     * can still parse the form themselves. Returns {@code null} if the
     * request is not form-encoded or the body has no {@code Action} field.
     */
    private static String readFormAction(ContainerRequestContext ctx) {
        // Delegates to RequestBodyReader so this and ResourceArnBuilder's per-service resource
        // lookups share one buffered copy of the body per request instead of each independently
        // reading (and needing to reset) the live entity stream.
        return RequestBodyReader.formField(ctx, "Action");
    }
}
