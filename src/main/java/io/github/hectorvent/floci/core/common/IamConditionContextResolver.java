package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbConditionKeys;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the IAM request context: the condition keys a policy's Condition block can match,
 * for the request currently being enforced.
 *
 * <p>Two keys are generic across services and populated the same way wherever they apply:
 * <ul>
 *   <li>{@code aws:RequestTag/<key>}, a tag the caller asks to attach, read from the request
 *       before the operation is applied.</li>
 *   <li>{@code aws:ResourceTag/<key>}, a tag already on the target resource, read from current
 *       state. {@link #resolve} carries the first target's tags and
 *       {@link #resolveRemainingTargets} one context per further target, so the filter can
 *       require the policy to hold for every resource the request names, as AWS does.</li>
 * </ul>
 */
@ApplicationScoped
public class IamConditionContextResolver {

    private static final Logger LOG = Logger.getLogger(IamConditionContextResolver.class);

    /** Set by {@code ResourceArnBuilder.readJsonBody}, which runs earlier in the same filter pass. */
    private static final String BUFFERED_JSON_BODY = "floci.bufferedJsonBody";
    private static final String BUFFERED_FORM_BODY = "floci.bufferedFormBody";
    private static final String REQUEST_TAG_PREFIX = "aws:RequestTag/";
    private static final String RESOURCE_TAG_PREFIX = "aws:ResourceTag/";

    private final Instance<DynamoDbService> dynamoDbService;
    private final Instance<Ec2Service> ec2Service;
    private final Instance<S3Service> s3Service;
    private final RequestContext requestContext;
    private final EmulatorConfig config;

    @Inject
    public IamConditionContextResolver(Instance<DynamoDbService> dynamoDbService,
                                       Instance<Ec2Service> ec2Service,
                                       Instance<S3Service> s3Service,
                                       RequestContext requestContext,
                                       EmulatorConfig config) {
        this.dynamoDbService = dynamoDbService;
        this.ec2Service = ec2Service;
        this.s3Service = s3Service;
        this.requestContext = requestContext;
        this.config = config;
    }

    public Map<String, List<String>> resolve(String credentialScope, String action,
                                             ContainerRequestContext ctx) {
        return switch (credentialScope) {
            case "s3" -> s3ConditionContext(action, ctx);
            case "ec2" -> ec2ConditionContext(action, ctx);
            case "dynamodb" -> dynamoDbConditionContext(action, ctx);
            default -> null;
        };
    }

    // ── S3 ──────────────────────────────────────────────────────────────────────

    private Map<String, List<String>> s3ConditionContext(String action, ContainerRequestContext ctx) {
        return switch (action) {
            case "s3:ListBucket" -> s3BucketListConditionContext(ctx.getUriInfo().getQueryParameters());
            case "s3:PutBucketTagging" -> s3PutBucketTaggingConditionContext(ctx);
            case "s3:GetBucketTagging", "s3:DeleteBucketTagging", "s3:DeleteBucket" ->
                    s3BucketResourceTagConditionContext(ctx);
            default -> null;
        };
    }

    Map<String, List<String>> s3BucketListConditionContext(MultivaluedMap<String, String> queryParameters) {
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        addQueryCondition(conditions, "s3:prefix", queryParameters, "prefix");
        addQueryCondition(conditions, "s3:delimiter", queryParameters, "delimiter");
        addQueryCondition(conditions, "s3:max-keys", queryParameters, "max-keys");
        return conditions.isEmpty() ? null : conditions;
    }

    private static void addQueryCondition(Map<String, List<String>> conditions, String conditionKey,
                                          MultivaluedMap<String, String> queryParameters, String queryParameter) {
        String value = queryParameters.getFirst(queryParameter);
        if (value != null) {
            conditions.put(conditionKey, List.of(value));
        }
    }

    /** {@code aws:RequestTag/<key>} from the {@code <Tagging><TagSet>} body of PutBucketTagging. */
    private Map<String, List<String>> s3PutBucketTaggingConditionContext(ContainerRequestContext ctx) {
        byte[] body = bufferEntity(ctx);
        if (body == null || body.length == 0) {
            return null;
        }
        Map<String, String> requested = XmlParser.extractPairs(
                new String(body, StandardCharsets.UTF_8), "Tag", "Key", "Value");
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        requested.forEach((key, value) -> conditions.put(REQUEST_TAG_PREFIX + key, List.of(value)));
        return conditions.isEmpty() ? null : conditions;
    }

    /** {@code aws:ResourceTag/<key>} from the target bucket's current tags. */
    private Map<String, List<String>> s3BucketResourceTagConditionContext(ContainerRequestContext ctx) {
        String bucket = s3BucketName(ctx.getUriInfo().getPath());
        if (bucket == null || !s3Service.isResolvable()) {
            return null;
        }
        Map<String, String> existing;
        try {
            existing = s3Service.get().getBucketTagging(bucket);
        } catch (RuntimeException e) {
            // A bucket that does not exist has no tags to offer; the request fails on its own later.
            LOG.debugv(e, "Could not read bucket tags for the IAM condition context: {0}", bucket);
            return null;
        }
        if (existing == null || existing.isEmpty()) {
            return null;
        }
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        existing.forEach((key, value) -> conditions.put(RESOURCE_TAG_PREFIX + key, List.of(value)));
        return conditions;
    }

    /** Path-style bucket, the same reading {@code ResourceArnBuilder} applies to S3 paths. */
    private static String s3BucketName(String path) {
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        if (stripped.isEmpty()) {
            return null;
        }
        int slash = stripped.indexOf('/');
        return slash < 0 ? stripped : stripped.substring(0, slash);
    }

    // ── EC2 ─────────────────────────────────────────────────────────────────────

    private Map<String, List<String>> ec2ConditionContext(String action, ContainerRequestContext ctx) {
        return switch (action) {
            case "ec2:RunInstances" -> ec2RunInstancesConditionContext(ctx);
            case "ec2:CreateTags" -> ec2CreateTagsConditionContext(ctx);
            case "ec2:DeleteTags" -> ec2ResourceTagConditionContext(ctx, "ResourceId.1");
            case "ec2:TerminateInstances", "ec2:DescribeInstances" ->
                    ec2ResourceTagConditionContext(ctx, "InstanceId.1");
            default -> null;
        };
    }

    /**
     * {@code aws:RequestTag/<key>} from every {@code TagSpecification.N.Tag.M} pair. The instance
     * does not exist yet, so there is no resource tag to offer.
     */
    private Map<String, List<String>> ec2RunInstancesConditionContext(ContainerRequestContext ctx) {
        Map<String, String> form = formParameters(ctx);
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        for (int spec = 1; form.containsKey("TagSpecification." + spec + ".ResourceType"); spec++) {
            addRequestTags(conditions, form, "TagSpecification." + spec + ".Tag.");
        }
        return conditions.isEmpty() ? null : conditions;
    }

    /**
     * CreateTags both requests tags and targets existing resources, so both keys apply: the
     * {@code Tag.N} pairs being requested, and each {@code ResourceId.N}'s tags as they are
     * before this call. This context carries the first target; the rest come from
     * {@link #resolveRemainingTargets}.
     */
    private Map<String, List<String>> ec2CreateTagsConditionContext(ContainerRequestContext ctx) {
        Map<String, String> form = formParameters(ctx);
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        addRequestTags(conditions, form, "Tag.");
        addResourceTags(conditions, form.get("ResourceId.1"));
        return conditions.isEmpty() ? null : conditions;
    }

    private Map<String, List<String>> ec2ResourceTagConditionContext(ContainerRequestContext ctx,
                                                                     String idParameter) {
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        addResourceTags(conditions, formParameters(ctx).get(idParameter));
        return conditions.isEmpty() ? null : conditions;
    }

    /**
     * One condition context per target after the first, for the EC2 actions that accept several
     * resource ids in one request. An untagged target yields an empty context rather than none,
     * so a policy scoped through {@code aws:ResourceTag} fails to match it. Requests naming a
     * single target, and every other service, get an empty list.
     */
    public List<Map<String, List<String>>> resolveRemainingTargets(String credentialScope, String action,
                                                                   ContainerRequestContext ctx) {
        if (!"ec2".equals(credentialScope)) {
            return List.of();
        }
        String idParameter = switch (action) {
            case "ec2:CreateTags", "ec2:DeleteTags" -> "ResourceId.";
            case "ec2:TerminateInstances", "ec2:DescribeInstances" -> "InstanceId.";
            default -> null;
        };
        if (idParameter == null) {
            return List.of();
        }
        Map<String, String> form = formParameters(ctx);
        List<Map<String, List<String>>> targets = new ArrayList<>();
        if (!form.containsKey(idParameter + 1)) {
            // The handlers read numbered members from 1 up to the first gap, and so does this.
            return targets;
        }
        for (int i = 2; form.containsKey(idParameter + i); i++) {
            Map<String, List<String>> conditions = new LinkedHashMap<>();
            if ("ec2:CreateTags".equals(action)) {
                addRequestTags(conditions, form, "Tag.");
            }
            addResourceTags(conditions, form.get(idParameter + i));
            targets.add(conditions);
        }
        return targets;
    }

    private static void addRequestTags(Map<String, List<String>> conditions, Map<String, String> form,
                                       String prefix) {
        for (int i = 1; form.containsKey(prefix + i + ".Key"); i++) {
            String value = form.get(prefix + i + ".Value");
            conditions.put(REQUEST_TAG_PREFIX + form.get(prefix + i + ".Key"),
                    List.of(value == null ? "" : value));
        }
    }

    private void addResourceTags(Map<String, List<String>> conditions, String resourceId) {
        if (resourceId == null || resourceId.isBlank() || !ec2Service.isResolvable()) {
            return;
        }
        for (Tag tag : ec2Service.get().resourceTags(resourceId)) {
            conditions.put(RESOURCE_TAG_PREFIX + tag.getKey(),
                    List.of(tag.getValue() == null ? "" : tag.getValue()));
        }
    }

    // ── Request body access ─────────────────────────────────────────────────────

    /**
     * The decoded form parameters of a Query-protocol request, buffered once per request and
     * restored for the controller that reads the form next. A repeated key keeps its first
     * value, which is the value the Query handlers read through {@code getFirst}, so the
     * resource this context describes is the resource the handler acts on.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> formParameters(ContainerRequestContext ctx) {
        Object cached = ctx.getProperty(BUFFERED_FORM_BODY);
        if (cached instanceof Map<?, ?> map) {
            return (Map<String, String>) map;
        }
        MediaType mediaType = ctx.getMediaType();
        if (mediaType == null
                || !"application".equalsIgnoreCase(mediaType.getType())
                || !"x-www-form-urlencoded".equalsIgnoreCase(mediaType.getSubtype())) {
            return Map.of();
        }
        byte[] body = bufferEntity(ctx);
        if (body == null || body.length == 0) {
            return Map.of();
        }
        Charset charset = charsetOf(mediaType);
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String pair : new String(body, charset).split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            parameters.putIfAbsent(URLDecoder.decode(key, charset), URLDecoder.decode(value, charset));
        }
        ctx.setProperty(BUFFERED_FORM_BODY, parameters);
        return parameters;
    }

    /** Reads the entity fully and puts an equivalent stream back for downstream readers. */
    private static byte[] bufferEntity(ContainerRequestContext ctx) {
        InputStream in = ctx.getEntityStream();
        if (in == null) {
            return null;
        }
        byte[] body;
        try {
            body = in.readAllBytes();
        } catch (IOException e) {
            LOG.debugv(e, "Failed to buffer the request body for the IAM condition context");
            ctx.setEntityStream(new ByteArrayInputStream(new byte[0]));
            return null;
        }
        ctx.setEntityStream(new ByteArrayInputStream(body));
        return body;
    }

    private static Charset charsetOf(MediaType mediaType) {
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

    // ── DynamoDB ────────────────────────────────────────────────────────────────

    /**
     * Populates {@code dynamodb:LeadingKeys}, {@code dynamodb:Attributes} and
     * {@code dynamodb:Select} from the buffered request body.
     *
     * <p>A key that cannot be resolved (unknown table, a Key that omits the partition
     * attribute, an unparseable KeyConditionExpression) is omitted rather than guessed. A
     * policy scoping access through the missing key then does not match, so the request is
     * denied: a request that cannot be proven in scope is treated as out of scope.
     */
    Map<String, List<String>> dynamoDbConditionContext(String action, ContainerRequestContext ctx) {
        JsonNode body = bufferedJsonBody(ctx);
        if (body == null || !body.isObject()) {
            return null;
        }
        TableDefinition table = describeTargetTable(body);
        DynamoDbConditionKeys.Result keys = DynamoDbConditionKeys.extract(action, body, table);

        Map<String, List<String>> conditions = new LinkedHashMap<>();
        if (!keys.leadingKeys().isEmpty()) {
            conditions.put("dynamodb:LeadingKeys", keys.leadingKeys());
        }
        if (!keys.attributes().isEmpty()) {
            conditions.put("dynamodb:Attributes", keys.attributes());
        }
        if (keys.select() != null) {
            conditions.put("dynamodb:Select", List.of(keys.select()));
        }
        return conditions.isEmpty() ? null : conditions;
    }

    private JsonNode bufferedJsonBody(ContainerRequestContext ctx) {
        Object cached = ctx.getProperty(BUFFERED_JSON_BODY);
        return cached instanceof JsonNode node ? node : null;
    }

    /**
     * Looks up the table whose key schema names the partition key. Resolved lazily through
     * Instance so core.common keeps no hard dependency on the DynamoDB service, and via
     * {@code findTable} rather than {@code describeTable} so the O(items) item-count refresh
     * never runs on the enforcement hot path.
     */
    private TableDefinition describeTargetTable(JsonNode body) {
        String tableName = targetTableName(body);
        if (tableName == null || !dynamoDbService.isResolvable()) {
            return null;
        }
        String region = requestContext.getRegion() == null
                ? config.defaultRegion() : requestContext.getRegion();
        return dynamoDbService.get().findTable(tableName, region).orElse(null);
    }

    /**
     * The single table this request targets: the TableName field, or the sole RequestItems
     * entry for the batch operations. A multi-table batch has no single key schema, so it
     * gets no leading keys and fails closed.
     */
    private String targetTableName(JsonNode body) {
        if (body.hasNonNull("TableName")) {
            String tableName = body.get("TableName").asText().trim();
            if (!tableName.isEmpty()) {
                return tableName;
            }
        }
        JsonNode requestItems = body.get("RequestItems");
        if (requestItems != null && requestItems.isObject() && requestItems.size() == 1) {
            return requestItems.fieldNames().next();
        }
        return null;
    }
}
