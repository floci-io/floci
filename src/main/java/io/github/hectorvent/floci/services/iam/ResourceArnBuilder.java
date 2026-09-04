package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Constructs the target resource ARN for a request so the policy evaluator
 * can match it against Resource patterns in policy documents.
 *
 * Returns {@code *} when the resource cannot be determined, which matches
 * permissive wildcard policies.
 */
@ApplicationScoped
public class ResourceArnBuilder {

    private final ObjectMapper objectMapper;

    @Inject
    public ResourceArnBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ResourceArnBuilder() {
        this(new ObjectMapper());
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
            case "s3"             -> List.of(buildS3Arn(path));
            case "lambda"         -> List.of(buildLambdaArn(path, region, accountId));
            case "sqs"            -> List.of(buildSqsArn(ctx, region, accountId));
            case "sns"            -> List.of(buildSnsArn(ctx, region, accountId));
            case "dynamodb"       -> buildDynamoDbArns(ctx, region, accountId);
            case "kinesis"        -> List.of(buildKinesisArn(ctx, region, accountId));
            case "secretsmanager" -> List.of(buildSecretsManagerArn(ctx, region, accountId));
            case "ssm"            -> List.of(buildSsmArn(ctx, region, accountId));
            case "kms"            -> List.of(buildKmsArn(path, region, accountId));
            case "sts"            -> List.of(buildStsArn(ctx));
            case "ecs"            -> buildEcsArns(ctx, region, accountId);
            default               -> List.of("*");
        };
    }

    // ── S3 ──────────────────────────────────────────────────────────────────────
    private String buildS3Arn(String path) {
        // path: /bucket or /bucket/key
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        if (stripped.isEmpty()) {
            return AwsArnUtils.Arn.of("s3", "", "", "*").toString();
        }
        int slash = stripped.indexOf('/');
        if (slash < 0) {
            return AwsArnUtils.Arn.of("s3", "", "", stripped).toString();
        }
        return AwsArnUtils.Arn.of("s3", "", "", stripped).toString();
    }

    // ── Lambda ──────────────────────────────────────────────────────────────────
    private String buildLambdaArn(String path, String region, String accountId) {
        // path: /2015-03-31/functions/name or similar
        String name = extractSegmentAfter(path, "functions");
        if (name == null) return "*";
        // strip qualifier if present
        int colon = name.indexOf(':');
        if (colon > 0) name = name.substring(0, colon);
        return AwsArnUtils.Arn.of("lambda", region, accountId, "function:" + name).toString();
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
            if (json.hasNonNull("RequestItems") && json.get("RequestItems").isObject()) {
                Set<String> arns = new LinkedHashSet<>();
                var fieldNames = json.get("RequestItems").fieldNames();
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
        if (tableName.startsWith("arn:aws:dynamodb:")) {
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
                    if (streamName.startsWith("arn:aws:kinesis:")) {
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
                    if (secretId.startsWith("arn:aws:secretsmanager:")) {
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
                    if (name.startsWith("arn:aws:ssm:")) {
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

    // ── STS ───────────────────────────────────────────────────────────────────
    private String buildStsArn(ContainerRequestContext ctx) {
        String roleArn = firstFormParam(ctx, "RoleArn");
        if (roleArn == null || roleArn.isBlank()) {
            return "*";
        }
        roleArn = roleArn.trim();
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(roleArn);
            if (!"iam".equals(parsed.service())
                    || !parsed.region().isEmpty()
                    || !parsed.accountId().matches("\\d{12}")
                    || !parsed.resource().startsWith("role/")
                    || parsed.resource().length() == "role/".length()) {
                return "*";
            }
            return roleArn;
        } catch (IllegalArgumentException e) {
            return "*";
        }
    }

    // ── ECS ─────────────────────────────────────────────────────────────────────

    /**
     * Builds service resources only for ECS operations whose request body identifies services.
     * DescribeServices accepts a list and UpdateService accepts one service; every member must
     * resolve or the request is rejected so a malformed mixed request cannot become an
     * accidentally narrower authorization check.
     */
    private List<String> buildEcsArns(ContainerRequestContext ctx, String region, String accountId) {
        String operation = ecsOperation(ctx);
        if ("DescribeServices".equals(operation)) {
            return buildEcsDescribeServiceArns(ctx, region, accountId);
        }
        if ("UpdateService".equals(operation)) {
            return buildEcsUpdateServiceArns(ctx, region, accountId);
        }
        return List.of("*");
    }

    private List<String> buildEcsDescribeServiceArns(ContainerRequestContext ctx,
                                                     String region, String accountId) {
        JsonNode json = readJsonBody(ctx);
        if (json == null || !json.isObject()) {
            return invalidEcsRequest("DescribeServices request body must be a JSON object");
        }
        EcsClusterReference cluster = parseEcsCluster(json.get("cluster"), region, accountId);
        JsonNode services = json.get("services");
        if (cluster == null || services == null || !services.isArray() || services.isEmpty()) {
            return invalidEcsRequest("DescribeServices requires a non-empty services array");
        }

        List<String> resources = new ArrayList<>();
        for (JsonNode serviceNode : services) {
            if (serviceNode == null || !serviceNode.isTextual()) {
                return invalidEcsRequest("DescribeServices service identifiers must be strings");
            }
            EcsServiceReference service = parseEcsService(serviceNode.asText());
            if (service == null || !matchesEcsCluster(service, cluster)) {
                return invalidEcsRequest("DescribeServices contains an invalid or mismatched service identifier");
            }
            resources.add(service.fullArn() != null
                    ? service.fullArn()
                    : buildEcsServiceArn(cluster, service.name()));
        }
        return resources.isEmpty()
                ? invalidEcsRequest("DescribeServices requires at least one service identifier")
                : resources;
    }

    private List<String> buildEcsUpdateServiceArns(ContainerRequestContext ctx,
                                                   String region, String accountId) {
        JsonNode json = readJsonBody(ctx);
        if (json == null || !json.isObject()) {
            return invalidEcsRequest("UpdateService request body must be a JSON object");
        }
        EcsClusterReference cluster = parseEcsCluster(json.get("cluster"), region, accountId);
        JsonNode serviceNode = json.get("service");
        if (cluster == null || serviceNode == null || !serviceNode.isTextual()) {
            return invalidEcsRequest("UpdateService requires a service identifier");
        }
        EcsServiceReference service = parseEcsService(serviceNode.asText());
        if (service == null || !matchesEcsCluster(service, cluster)) {
            return invalidEcsRequest("UpdateService contains an invalid or mismatched service identifier");
        }
        return List.of(service.fullArn() != null
                ? service.fullArn()
                : buildEcsServiceArn(cluster, service.name()));
    }

    private static List<String> invalidEcsRequest(String message) {
        throw new AwsException("InvalidParameterException", message, 400);
    }

    private String ecsOperation(ContainerRequestContext ctx) {
        String target = ctx.getHeaderString("X-Amz-Target");
        if (target == null) {
            return null;
        }
        target = target.trim();
        int dot = target.lastIndexOf('.');
        if (dot < 0 || dot == target.length() - 1) {
            return null;
        }
        String operation = target.substring(dot + 1).trim();
        return operation.isEmpty() ? null : operation;
    }

    private EcsClusterReference parseEcsCluster(JsonNode clusterNode,
                                                 String region, String accountId) {
        if (clusterNode == null || clusterNode.isNull()) {
            return validEcsIdentity(region, accountId)
                    ? new EcsClusterReference("aws", region, accountId, "default", false, false)
                    : null;
        }
        if (!clusterNode.isTextual()) {
            return null;
        }
        String raw = clusterNode.asText();
        String value = raw.trim();
        if (value.isEmpty() || !value.equals(raw)) {
            return null;
        }
        if (!value.startsWith("arn:")) {
            return validEcsName(value)
                    && validEcsIdentity(region, accountId)
                    ? new EcsClusterReference("aws", region, accountId, value, true, false)
                    : null;
        }
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(value);
            String resource = parsed.resource();
            String prefix = "cluster/";
            if (!"ecs".equals(parsed.service()) || !validEcsIdentity(parsed.region(), parsed.accountId())
                    || !resource.startsWith(prefix) || !validEcsName(resource.substring(prefix.length()))) {
                return null;
            }
            return new EcsClusterReference(parsed.partition(), parsed.region(), parsed.accountId(),
                    resource.substring(prefix.length()), true, true);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private EcsServiceReference parseEcsService(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || !value.equals(raw)) {
            return null;
        }
        if (!value.startsWith("arn:")) {
            return validEcsName(value) ? new EcsServiceReference(value, null, null, null, null, null) : null;
        }
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(value);
            String prefix = "service/";
            String resource = parsed.resource();
            if (!"ecs".equals(parsed.service()) || !validEcsIdentity(parsed.region(), parsed.accountId())
                    || !resource.startsWith(prefix)) {
                return null;
            }
            String[] parts = resource.substring(prefix.length()).split("/", -1);
            if (parts.length != 2 || !validEcsName(parts[0]) || !validEcsName(parts[1])) {
                return null;
            }
            return new EcsServiceReference(parts[1], value, parsed.partition(), parsed.region(),
                    parsed.accountId(), parts[0]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean matchesEcsCluster(EcsServiceReference service, EcsClusterReference cluster) {
        if (service.fullArn() == null) {
            return true;
        }
        // An omitted cluster is AWS's default cluster. A full service ARN is still accepted when
        // it names that default cluster, but a non-default ARN without an explicit cluster must
        // fail closed because AWS requires the cluster parameter for that case.
        if (!cluster.explicit()) {
            return "default".equals(service.clusterName());
        }
        return cluster.name().equals(service.clusterName())
                && (!cluster.fullArn() || (cluster.partition().equals(service.partition())
                && cluster.region().equals(service.region())
                && cluster.accountId().equals(service.accountId())));
    }

    private String buildEcsServiceArn(EcsClusterReference cluster, String serviceName) {
        return new AwsArnUtils.Arn(cluster.partition(), "ecs", cluster.region(), cluster.accountId(),
                "service/" + cluster.name() + "/" + serviceName).toString();
    }

    private static boolean validEcsIdentity(String region, String accountId) {
        return region != null && !region.isBlank()
                && accountId != null && accountId.matches("\\d{12}");
    }

    private static boolean validEcsName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,255}");
    }

    private record EcsClusterReference(String partition, String region, String accountId,
                                       String name, boolean explicit, boolean fullArn) {
    }

    private record EcsServiceReference(String name, String fullArn, String partition, String region,
                                       String accountId, String clusterName) {
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
        String v = ctx.getUriInfo().getQueryParameters().getFirst(name);
        if (v != null) {
            return v;
        }
        MediaType mediaType = ctx.getMediaType();
        if (mediaType == null
                || !"application".equalsIgnoreCase(mediaType.getType())
                || !"x-www-form-urlencoded".equalsIgnoreCase(mediaType.getSubtype())) {
            return null;
        }
        InputStream in = ctx.getEntityStream();
        if (in == null) {
            return null;
        }
        byte[] body;
        try {
            body = in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
        ctx.setEntityStream(new ByteArrayInputStream(body));
        Charset charset = resolveCharset(mediaType);
        String form = new String(body, charset);
        try {
            for (String pair : form.split("&")) {
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                if (name.equals(URLDecoder.decode(key, charset))) {
                    return eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), charset);
                }
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return null;
    }

    private Charset resolveCharset(MediaType mediaType) {
        String name = mediaType.getParameters().get("charset");
        if (name == null || name.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(name);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }
}
