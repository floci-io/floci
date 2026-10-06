package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsQueryServiceResolver;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.services.iam.model.ServerCertificate;
import io.github.hectorvent.floci.services.lambda.LambdaArnUtils;
import io.github.hectorvent.floci.services.lambda.durable.DurableTokens;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Constructs the target resource ARN for a request so the policy evaluator
 * can match it against Resource patterns in policy documents.
 *
 * Returns {@code *} when the resource cannot be determined, which matches
 * permissive wildcard policies.
 */
@ApplicationScoped
public class ResourceArnBuilder {

    /**
     * The IAM actions whose resource this builder can name. Deliberately only the
     * server-certificate operations; see {@link #buildIamArn}.
     */
    private static final Set<String> SERVER_CERTIFICATE_ACTIONS = Set.of(
            "UploadServerCertificate", "GetServerCertificate", "UpdateServerCertificate",
            "DeleteServerCertificate", "TagServerCertificate", "UntagServerCertificate",
            "ListServerCertificateTags");

    private final ObjectMapper objectMapper;
    /**
     * Looked up lazily through a provider: this builder is constructed by the enforcement filter,
     * which IamService itself does not depend on, and a direct injection would close that loop.
     */
    private final Instance<IamService> iamService;

    @Inject
    public ResourceArnBuilder(ObjectMapper objectMapper, Instance<IamService> iamService) {
        this.objectMapper = objectMapper;
        this.iamService = iamService;
    }

    public ResourceArnBuilder() {
        this(new ObjectMapper(), null);
    }

    public ResourceArnBuilder(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    public String build(String credentialScope, ContainerRequestContext ctx,
                        String region, String accountId) {
        List<String> list = buildResources(credentialScope, ctx, region, accountId);
        return list.isEmpty() ? "*" : list.getFirst();
    }

    public List<String> buildResources(String credentialScope, ContainerRequestContext ctx,
                                       String region, String accountId) {
        if (credentialScope == null) {
            return List.of("*");
        }
        String path = ctx.getUriInfo().getPath();
        return switch (credentialScope) {
            case "s3"             -> List.of(buildS3Arn(path, region));
            case "lambda"         -> List.of(buildLambdaArn(path, region, accountId));
            case "sqs"            -> List.of(buildSqsArn(ctx, region, accountId));
            case "sns"            -> List.of(buildSnsArn(ctx, region, accountId));
            case "dynamodb"       -> buildDynamoDbArns(ctx, region, accountId);
            case "kinesis"        -> List.of(buildKinesisArn(ctx, region, accountId));
            case "secretsmanager" -> List.of(buildSecretsManagerArn(ctx, region, accountId));
            case "ssm"            -> List.of(buildSsmArn(ctx, region, accountId));
            case "kms"            -> List.of(buildKmsArn(path, region, accountId));
            case "iam"            -> buildIamArns(ctx, region, accountId);
            default               -> List.of("*");
        };
    }

    // ── IAM ─────────────────────────────────────────────────────────────────────

    /**
     * IAM resource ARNs are only built for the server-certificate operations. Every other IAM
     * action still resolves to {@code *}, so a policy naming a specific user, role, policy or MFA
     * device does not constrain it. That is how IAM behaved before server certificates existed,
     * and closing it for the rest means mapping the resource of every dispatched IAM action, which
     * is its own change rather than a side effect of this one: tracked in issue 4979, and
     * described in docs/services/iam.md.
     *
     * <p>The operation is resolved through {@link AwsQueryServiceResolver#action(String, String)},
     * so the legacy {@code Operation} parameter names the resource exactly as {@code Action} does.
     * Reading {@code Action} alone would let a caller spell the operation the other way and have
     * the request evaluated against {@code *}, skipping a deny that names the certificate.
     *
     * <p>The path is taken from the stored certificate when one exists: a policy names the full
     * ARN, path included, and only the store knows the path.
     *
     * <p>A rename names two resources rather than one; see {@link #renameTargetArn}.
     */
    private List<String> buildIamArns(ContainerRequestContext ctx, String region,
                                      String accountId) {
        String action = AwsQueryServiceResolver.action(
                RequestBodyReader.formField(ctx, "Action"),
                RequestBodyReader.formField(ctx, "Operation"));
        if (action == null || !SERVER_CERTIFICATE_ACTIONS.contains(action)) {
            return List.of("*");
        }
        String name = RequestBodyReader.formField(ctx, "ServerCertificateName");
        if (name == null || name.isBlank()) {
            return List.of("*");
        }
        if ("UploadServerCertificate".equals(action)) {
            // An upload names a certificate that does not exist yet, so its ARN is minted from
            // the request: the path it asks for, in the partition it is being created in. Reading
            // a stored path here would authorize an upload to /team/ against the root path.
            // IAM is global, so the ARN carries no region. Minted in the request's partition
            // rather than through a blank-region Arn.of, which would silently mean the
            // commercial one.
            String path = normalizeArnPath(RequestBodyReader.formField(ctx, "Path"));
            return List.of(AwsArnUtils.Arn.global(AwsRegions.partitionFor(region), "iam",
                    accountId, "server-certificate" + path + name).toString());
        }
        // Every other operation acts on a certificate that already exists, so the check uses that
        // certificate's own stored ARN instead of deriving one again. The stored ARN carries the
        // path and, more to the point, the partition the certificate was created in: a resource
        // stays in its partition, so re-minting from the caller's signing region would name an
        // ARN that no resource has and leave a deny on the real one unmatched.
        Optional<ServerCertificate> stored = storedServerCertificate(name);
        if (stored.isEmpty()) {
            return List.of("*");
        }
        String storedArn = stored.get().getArn();
        if (!"UpdateServerCertificate".equals(action)) {
            return List.of(storedArn);
        }
        String target = renameTargetArn(ctx, stored.get(), name, region, accountId);
        return storedArn.equals(target) ? List.of(storedArn) : List.of(storedArn, target);
    }

    /**
     * The ARN a rename or a move would produce. AWS requires the principal to be allowed on both
     * the old and the new name, so that a rename into a name they cannot write fails: naming both
     * resources is what enforces it, because the filter authorizes a request once per resource.
     *
     * <p>Built beside the stored ARN so it keeps the certificate's partition and account. Only
     * the path and the name change, and taking the partition from the caller's signing region
     * instead would name a resource in a partition the certificate does not live in. Returns the
     * stored ARN unchanged when the request renames and moves nothing.
     */
    private String renameTargetArn(ContainerRequestContext ctx, ServerCertificate stored,
                                   String name, String region, String accountId) {
        String newName = RequestBodyReader.formField(ctx, "NewServerCertificateName");
        String newPath = RequestBodyReader.formField(ctx, "NewPath");
        String targetName = newName == null || newName.isBlank() ? name : newName;
        String targetPath = newPath == null || newPath.isBlank()
                ? normalizeArnPath(stored.getPath())
                : normalizeArnPath(newPath);
        String partition = AwsArnUtils.partitionOrDefault(stored.getArn(),
                AwsRegions.partitionFor(region));
        String account = AwsArnUtils.accountOrDefault(stored.getArn(), accountId);
        return AwsArnUtils.Arn.global(partition, "iam", account,
                "server-certificate" + targetPath + targetName).toString();
    }

    /** A request's Path as it appears in an ARN: slash-delimited, defaulting to a bare slash. */
    private static String normalizeArnPath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    /**
     * The stored certificate of that name in this account, carrying its own ARN. Empty when the
     * account has none, or when the stored record has no ARN to read: the operation fails as
     * NoSuchEntity either way, and minting an ARN for a resource that is not there would only
     * offer a policy something to match.
     */
    private Optional<ServerCertificate> storedServerCertificate(String name) {
        if (iamService == null) {
            return Optional.empty();
        }
        return iamService.get().findServerCertificate(name)
                .filter(certificate -> certificate.getArn() != null
                        && !certificate.getArn().isBlank());
    }

    // ── S3 ──────────────────────────────────────────────────────────────────────
    // Minted in the request's partition; IamEnforcementFilter re-mints an existing bucket's ARN
    // in the bucket's own partition through S3ResourcePolicyProvider before any policy sees it.
    private String buildS3Arn(String path, String region) {
        // path: /bucket or /bucket/key
        String partition = AwsRegions.partitionFor(region);
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        if (stripped.isEmpty()) {
            return AwsArnUtils.Arn.global(partition, "s3", "", "*").toString();
        }
        // S3VirtualHostFilter rewrites a bucket-level virtual-hosted request (GET /) to
        // /bucket/, and the empty key after that separator is not part of the resource.
        // A policy names the bucket as arn:<partition>:s3:::bucket, so the trailing slash has to
        // go or a bucket-level ARN never matches. A key that itself ends in a slash
        // (a folder marker such as folder/) keeps it, because there the slash is key data.
        int firstSlash = stripped.indexOf('/');
        if (firstSlash == stripped.length() - 1) {
            stripped = stripped.substring(0, firstSlash);
        }
        return AwsArnUtils.Arn.global(partition, "s3", "", stripped).toString();
    }

    // ── Lambda ──────────────────────────────────────────────────────────────────
    private String buildLambdaArn(String path, String region, String accountId) {
        String executionArn = durableExecutionArn(path);
        if (executionArn == null) {
            executionArn = durableCallbackExecutionArn(path);
        }
        if (executionArn != null) {
            return executionArn;
        }
        // path: /2015-03-31/functions/name or similar
        String name = extractSegmentAfter(path, "functions");
        if (name == null) return "*";
        // strip qualifier if present
        int colon = name.indexOf(':');
        if (colon > 0) name = name.substring(0, colon);
        return AwsArnUtils.Arn.of("lambda", region, accountId, "function:" + name).toString();
    }

    /** Durable execution actions are authorized against the execution ARN, which carries the version. */
    private static String durableExecutionArn(String path) {
        String prefix = "/durable-executions/";
        int executions = path.indexOf(prefix);
        if (executions < 0) {
            return null;
        }
        Matcher matcher = LambdaArnUtils.DURABLE_EXECUTION_ARN.matcher(path.substring(executions + prefix.length()));
        return matcher.lookingAt() ? matcher.group() : null;
    }

    /** A callback id carries its execution ARN, so the callback actions are authorized against that execution. */
    private static String durableCallbackExecutionArn(String path) {
        String prefix = "/durable-execution-callbacks/";
        int callbacks = path.indexOf(prefix);
        int action = path.lastIndexOf('/');
        if (callbacks < 0 || action <= callbacks + prefix.length()) {
            return null;
        }
        return DurableTokens.callbackExecutionArn(path.substring(callbacks + prefix.length(), action))
                .filter(arn -> LambdaArnUtils.DURABLE_EXECUTION_ARN.matcher(arn).matches())
                .orElse(null);
    }

    // ── SQS ─────────────────────────────────────────────────────────────────────
    private String buildSqsArn(ContainerRequestContext ctx, String region, String accountId) {
        String queueUrl = ctx.getUriInfo().getQueryParameters().getFirst("QueueUrl");
        if (queueUrl == null) {
            // Try form param for Query-protocol
            queueUrl = firstFormParam(ctx, "QueueUrl");
        }
        if (queueUrl == null) {
            JsonNode json = readJsonBody(ctx);
            if (json != null && json.isObject()) {
                if (json.hasNonNull("QueueUrl")) {
                    queueUrl = json.get("QueueUrl").asText().trim();
                } else if (json.hasNonNull("QueueName")) {
                    String queueName = json.get("QueueName").asText().trim();
                    if (!queueName.isEmpty()) {
                        return AwsArnUtils.Arn.of("sqs", region, accountId, queueName).toString();
                    }
                }
            }
        }
        if (queueUrl != null && !queueUrl.isEmpty()) {
            String queueName = queueUrl.substring(queueUrl.lastIndexOf('/') + 1);
            return AwsArnUtils.Arn.of("sqs", region, accountId, queueName).toString();
        }
        return AwsArnUtils.Arn.of("sqs", region, accountId, "*").toString();
    }

    // ── SNS ─────────────────────────────────────────────────────────────────────
    private String buildSnsArn(ContainerRequestContext ctx, String region, String accountId) {
        String topicArn = firstFormParam(ctx, "TopicArn");
        if (topicArn == null) {
            JsonNode json = readJsonBody(ctx);
            if (json != null && json.isObject() && json.hasNonNull("TopicArn")) {
                topicArn = json.get("TopicArn").asText().trim();
            }
        }
        return (topicArn != null && !topicArn.isEmpty())
                ? topicArn
                : AwsArnUtils.Arn.of("sns", region, accountId, "*").toString();
    }

    // ── DynamoDB ─────────────────────────────────────────────────────────────────
    private String buildDynamoDbArn(ContainerRequestContext ctx, String region, String accountId) {
        List<String> arns = buildDynamoDbArns(ctx, region, accountId);
        return arns.isEmpty() ? "*" : arns.getFirst();
    }

    private List<String> buildDynamoDbArns(ContainerRequestContext ctx, String region, String accountId) {
        JsonNode json = readJsonBody(ctx);
        if (json != null && json.isObject()) {
            if (json.hasNonNull("TableName")) {
                String tableName = json.get("TableName").asText().trim();
                if (!tableName.isEmpty()) {
                    return List.of(toDynamoDbTableArn(tableName, region, accountId));
                }
            }
            if (json.path("TableCreationParameters").hasNonNull("TableName")) {
                String tableName = json.path("TableCreationParameters").get("TableName").asText().trim();
                if (!tableName.isEmpty()) {
                    return List.of(toDynamoDbTableArn(tableName, region, accountId));
                }
            }
            if (json.hasNonNull("ResourceArn")) {
                String resourceArn = json.get("ResourceArn").asText().trim();
                if (!resourceArn.isEmpty()) {
                    return List.of(resourceArn);
                }
            }
            if (json.hasNonNull("TableArn")) {
                String tableArn = json.get("TableArn").asText().trim();
                if (!tableArn.isEmpty()) {
                    return List.of(tableArn);
                }
            }
            if (json.hasNonNull("StreamArn")) {
                String streamArn = json.get("StreamArn").asText().trim();
                if (!streamArn.isEmpty()) {
                    return List.of(streamArn);
                }
            }
            if (json.hasNonNull("ExportArn")) {
                String exportArn = json.get("ExportArn").asText().trim();
                if (!exportArn.isEmpty()) {
                    return List.of(exportArn);
                }
            }
            if (json.hasNonNull("ImportArn")) {
                String importArn = json.get("ImportArn").asText().trim();
                if (!importArn.isEmpty()) {
                    return List.of(importArn);
                }
            }
            if (json.hasNonNull("RequestItems") && json.get("RequestItems").isObject()) {
                Set<String> arns = new LinkedHashSet<>();
                Iterator<String> fieldNames = json.get("RequestItems").fieldNames();
                while (fieldNames.hasNext()) {
                    String table = fieldNames.next().trim();
                    if (!table.isEmpty()) {
                        arns.add(toDynamoDbTableArn(table, region, accountId));
                    }
                }
                if (!arns.isEmpty()) {
                    return new ArrayList<>(arns);
                }
            }
            if (json.hasNonNull("TransactItems") && json.get("TransactItems").isArray()) {
                Set<String> arns = new LinkedHashSet<>();
                JsonNode items = json.get("TransactItems");
                for (JsonNode item : items) {
                    String t = null;
                    if (item.hasNonNull("Put") && item.get("Put").hasNonNull("TableName")) {
                        t = item.get("Put").get("TableName").asText();
                    } else if (item.hasNonNull("Delete") && item.get("Delete").hasNonNull("TableName")) {
                        t = item.get("Delete").get("TableName").asText();
                    } else if (item.hasNonNull("Update") && item.get("Update").hasNonNull("TableName")) {
                        t = item.get("Update").get("TableName").asText();
                    } else if (item.hasNonNull("ConditionCheck") && item.get("ConditionCheck").hasNonNull("TableName")) {
                        t = item.get("ConditionCheck").get("TableName").asText();
                    } else if (item.hasNonNull("Get") && item.get("Get").hasNonNull("TableName")) {
                        t = item.get("Get").get("TableName").asText();
                    }
                    if (t != null) {
                        t = t.trim();
                        if (!t.isEmpty()) {
                            arns.add(toDynamoDbTableArn(t, region, accountId));
                        }
                    }
                }
                if (!arns.isEmpty()) {
                    return new ArrayList<>(arns);
                }
            }
            if (json.hasNonNull("Statement")) {
                String stmt = json.get("Statement").asText().trim();
                String table = extractDynamoDbTableFromPartiQL(stmt);
                if (table != null && !table.isEmpty()) {
                    return List.of(toDynamoDbTableArn(table, region, accountId));
                }
            }
            if (json.hasNonNull("Statements") && json.get("Statements").isArray()) {
                Set<String> arns = new LinkedHashSet<>();
                for (JsonNode s : json.get("Statements")) {
                    if (s.hasNonNull("Statement")) {
                        String table = extractDynamoDbTableFromPartiQL(s.get("Statement").asText());
                        if (table != null && !table.isEmpty()) {
                            arns.add(toDynamoDbTableArn(table, region, accountId));
                        }
                    }
                }
                if (!arns.isEmpty()) {
                    return new ArrayList<>(arns);
                }
            }
            if (json.hasNonNull("TransactStatements") && json.get("TransactStatements").isArray()) {
                Set<String> arns = new LinkedHashSet<>();
                for (JsonNode s : json.get("TransactStatements")) {
                    if (s.hasNonNull("Statement")) {
                        String table = extractDynamoDbTableFromPartiQL(s.get("Statement").asText());
                        if (table != null && !table.isEmpty()) {
                            arns.add(toDynamoDbTableArn(table, region, accountId));
                        }
                    }
                }
                if (!arns.isEmpty()) {
                    return new ArrayList<>(arns);
                }
            }
        }
        return List.of("*");
    }

    private static String extractDynamoDbTableFromPartiQL(String statement) {
        return io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.extractTable(statement);
    }

    private String toDynamoDbTableArn(String tableName, String region, String accountId) {
        if (AwsArnUtils.isArnFor(tableName, "dynamodb")) {
            return tableName;
        }
        return AwsArnUtils.Arn.of("dynamodb", region, accountId, "table/" + tableName).toString();
    }

    // ── Kinesis ──────────────────────────────────────────────────────────────────
    private String buildKinesisArn(ContainerRequestContext ctx, String region, String accountId) {
        JsonNode json = readJsonBody(ctx);
        if (json != null && json.isObject()) {
            if (json.hasNonNull("StreamARN")) {
                String streamArn = json.get("StreamARN").asText().trim();
                if (!streamArn.isEmpty()) {
                    return streamArn;
                }
            }
            if (json.hasNonNull("StreamName")) {
                String streamName = json.get("StreamName").asText().trim();
                if (!streamName.isEmpty()) {
                    if (AwsArnUtils.isArnFor(streamName, "kinesis")) {
                        return streamName;
                    }
                    return AwsArnUtils.Arn.of("kinesis", region, accountId, "stream/" + streamName).toString();
                }
            }
            if (json.hasNonNull("ResourceARN")) {
                String resourceArn = json.get("ResourceARN").asText().trim();
                if (!resourceArn.isEmpty()) {
                    return resourceArn;
                }
            }
        }
        return AwsArnUtils.Arn.of("kinesis", region, accountId, "stream/*").toString();
    }

    // ── Secrets Manager ──────────────────────────────────────────────────────────
    private String buildSecretsManagerArn(ContainerRequestContext ctx, String region, String accountId) {
        JsonNode json = readJsonBody(ctx);
        if (json != null && json.isObject()) {
            if (json.hasNonNull("SecretId")) {
                String secretId = json.get("SecretId").asText().trim();
                if (!secretId.isEmpty()) {
                    if (AwsArnUtils.isArnFor(secretId, "secretsmanager")) {
                        return secretId;
                    }
                    return AwsArnUtils.Arn.of("secretsmanager", region, accountId, "secret:" + secretId).toString();
                }
            }
        }
        return AwsArnUtils.Arn.of("secretsmanager", region, accountId, "secret:*").toString();
    }

    // ── SSM ──────────────────────────────────────────────────────────────────────
    private String buildSsmArn(ContainerRequestContext ctx, String region, String accountId) {
        JsonNode json = readJsonBody(ctx);
        if (json != null && json.isObject()) {
            if (json.hasNonNull("Name")) {
                String name = json.get("Name").asText().trim();
                if (!name.isEmpty()) {
                    if (AwsArnUtils.isArnFor(name, "ssm")) {
                        return name;
                    }
                    String paramResource = name.startsWith("/") ? "parameter" + name : "parameter/" + name;
                    return AwsArnUtils.Arn.of("ssm", region, accountId, paramResource).toString();
                }
            }
        }
        return AwsArnUtils.Arn.of("ssm", region, accountId, "parameter/*").toString();
    }

    // ── KMS ──────────────────────────────────────────────────────────────────────
    private String buildKmsArn(String path, String region, String accountId) {
        String keyId = extractSegmentAfter(path, "keys");
        if (keyId == null) return AwsArnUtils.Arn.of("kms", region, accountId, "key/*").toString();
        return AwsArnUtils.Arn.of("kms", region, accountId, "key/" + keyId).toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private JsonNode readJsonBody(ContainerRequestContext ctx) {
        Object cached = ctx.getProperty("floci.bufferedJsonBody");
        if (cached instanceof JsonNode node) {
            return node;
        }
        InputStream in = ctx.getEntityStream();
        if (in == null) {
            return null;
        }
        byte[] body;
        try {
            body = in.readAllBytes();
        } catch (IOException e) {
            ctx.setEntityStream(new ByteArrayInputStream(new byte[0]));
            return null;
        }
        ctx.setEntityStream(new ByteArrayInputStream(body));
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            ctx.setProperty("floci.bufferedJsonBody", node);
            return node;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractSegmentAfter(String path, String segment) {
        String marker = "/" + segment + "/";
        int idx = path.indexOf(marker);
        if (idx < 0) return null;
        String after = path.substring(idx + marker.length());
        // take only the first segment (stop at next /)
        int slash = after.indexOf('/');
        return slash > 0 ? after.substring(0, slash) : after;
    }

    private String firstFormParam(ContainerRequestContext ctx, String name) {
        // Form params are typically available as query params in REST-Assured / JAX-RS
        String v = ctx.getUriInfo().getQueryParameters().getFirst(name);
        return v;
    }
}
