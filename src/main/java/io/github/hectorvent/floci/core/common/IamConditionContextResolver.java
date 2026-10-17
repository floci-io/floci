package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.cloudwatch.logs.LogGroupTagTarget;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbConditionKeys;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbFacade;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbTableNames;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.iam.ResourceArnBuilder;
import io.github.hectorvent.floci.services.s3.S3ObjectActions;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /**
     * The services that set {@code aws:SourceOwner} when they publish to an SNS topic, a deprecated key
     * SNS keeps for the integrations that had it (SNS Developer Guide, "aws:SourceAccount versus
     * aws:SourceOwner"). The guide lists more; these are the ones that call through
     * {@link ServiceCallAuthorizer}.
     */
    private static final Set<String> SNS_SOURCE_OWNER_SERVICES = Set.of("ses");
    public static final String EXISTING_OBJECT_TAG_PREFIX = "s3:ExistingObjectTag/";
    public static final String REQUEST_OBJECT_TAG_PREFIX = "s3:RequestObjectTag/";
    public static final String REQUEST_OBJECT_TAG_KEYS = "s3:RequestObjectTagKeys";
    public static final String VERSION_ID = "s3:versionid";

    private final Instance<DynamoDbFacade> dynamoDbFacade;
    private final Instance<Ec2Service> ec2Service;
    private final Instance<S3Service> s3Service;
    private final Instance<CloudWatchLogsService> logsService;
    private final RequestContext requestContext;
    private final EmulatorConfig config;

    @Inject
    public IamConditionContextResolver(Instance<DynamoDbFacade> dynamoDbFacade,
                                       Instance<Ec2Service> ec2Service,
                                       Instance<S3Service> s3Service,
                                       Instance<CloudWatchLogsService> logsService,
                                       RequestContext requestContext,
                                       EmulatorConfig config) {
        this.dynamoDbFacade = dynamoDbFacade;
        this.ec2Service = ec2Service;
        this.s3Service = s3Service;
        this.logsService = logsService;
        this.requestContext = requestContext;
        this.config = config;
    }

    public Map<String, List<String>> resolve(String credentialScope, String action,
                                             ContainerRequestContext ctx) {
        return switch (credentialScope) {
            case "s3" -> s3ConditionContext(action, ctx);
            case "ec2" -> ec2ConditionContext(action, ctx);
            case "dynamodb" -> dynamoDbConditionContext(action, ctx);
            case "iam" -> iamConditionContext(action, ctx);
            case "logs" -> logsConditionContext(action, ctx);
            default -> null;
        };
    }

    /** Group mutations and stream access use the handler's group and request scope. */
    private Map<String, List<String>> logsConditionContext(String action, ContainerRequestContext ctx) {
        if (!("logs:CreateLogStream".equals(action) || "logs:DeleteLogStream".equals(action)
                || LogGroupTagTarget.supports(action) || "logs:CreateLogGroup".equals(action) || "logs:DeleteLogGroup".equals(action)
                || "logs:PutRetentionPolicy".equals(action) || "logs:DeleteRetentionPolicy".equals(action))) {
            return null;
        }
        JsonNode body = bufferedJsonBody(ctx);
        if (body == null) {
            return null;
        }
        if ("logs:CreateLogGroup".equals(action)) {
            JsonNode tags = body.path("tags");
            if (!tags.isObject() || tags.isEmpty()) {
                return null;
            }
            Map<String, List<String>> conditions = new LinkedHashMap<>();
            List<String> keys = new ArrayList<>();
            tags.properties().forEach(entry -> {
                keys.add(entry.getKey());
                conditions.put("aws:RequestTag/" + entry.getKey(), List.of(entry.getValue().asText()));
            });
            conditions.put("aws:TagKeys", keys);
            return conditions;
        }
        if (!logsService.isResolvable()) {
            return null;
        }
        String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        boolean direct = LogGroupTagTarget.supports(action);
        if ("logs:TagResource".equals(action) && body.path("tags").isObject()) {
            List<String> keys = new ArrayList<>();
            body.path("tags").fields().forEachRemaining(entry -> {
                keys.add(entry.getKey());
                conditions.put(REQUEST_TAG_PREFIX + entry.getKey(), List.of(entry.getValue().asText()));
            });
            if (!keys.isEmpty()) {
                conditions.put("aws:TagKeys", keys);
            }
        } else if ("logs:UntagResource".equals(action)) {
            List<String> keys = new ArrayList<>();
            body.path("tagKeys").forEach(key -> keys.add(key.asText()));
            if (!keys.isEmpty()) {
                conditions.put("aws:TagKeys", keys);
            }
        }
        String group = direct ? logsService.get().resolveTagTarget(body.path("resourceArn").asText(), region).name()
                : body.path("logGroupName").asText();
        Map<String, String> tags;
        try {
            tags = logsService.get().listTagsLogGroup(group, region);
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            // Leave the handler to report a missing group; unexpected lookup errors must not hide a deny.
            return conditions.isEmpty() ? null : conditions;
        }
        if (tags == null || tags.isEmpty()) {
            return conditions.isEmpty() ? null : conditions;
        }
        tags.forEach((key, value) -> conditions.put(RESOURCE_TAG_PREFIX + key,
                List.of(value == null ? "" : value)));
        return conditions;
    }

    private static Map<String, List<String>> iamConditionContext(String action, ContainerRequestContext ctx) {
        return switch (action) {
            case "iam:AttachRolePolicy", "iam:DetachRolePolicy", "iam:AttachUserPolicy",
                    "iam:DetachUserPolicy", "iam:AttachGroupPolicy", "iam:DetachGroupPolicy" -> {
                String policyArn = formParameters(ctx).get("PolicyArn");
                yield policyArn == null ? null : Map.of("iam:PolicyARN", List.of(policyArn));
            }
            default -> null;
        };
    }

    public static Map<String, List<String>> withGlobalContext(Map<String, List<String>> serviceContext,
                                                        String resourceArn, String region,
                                                        String accountId, String resourceOwnerAccountId) {
        Map<String, List<String>> conditions = serviceContext == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(serviceContext);
        // aws:ResourceAccount is the account that OWNS the resource, which is not always the
        // caller's. Some ARNs carry it; S3 bucket and object ARNs deliberately do not, and there
        // the owner has to come from service state. When neither source knows it, the key is left
        // out rather than guessed: an absent key fails a Condition that tests it, whereas a wrong
        // one would silently authorize cross-account access the policy meant to refuse.
        String resourceAccount = accountFromArn(resourceArn);
        if (resourceAccount == null || resourceAccount.isBlank()) {
            resourceAccount = resourceOwnerAccountId;
        }
        putIfPresent(conditions, "aws:ResourceAccount", resourceAccount);
        putIfPresent(conditions, "aws:PrincipalAccount", accountId);
        putIfPresent(conditions, "aws:RequestedRegion", region);
        // Enforcement evaluates signed IAM credentials, not direct AWS service principals.
        conditions.put("aws:PrincipalIsAWSService", List.of("false"));
        return conditions;
    }

    /**
     * The request context of a call an AWS service makes on its own behalf, such as SES publishing to
     * a topic, from the IAM User Guide's global condition keys: {@code aws:PrincipalIsAWSService} is
     * {@code true}; {@code aws:PrincipalServiceName} and {@code aws:PrincipalServiceNamesList} name the
     * service; {@code aws:SourceArn} and {@code aws:SourceAccount} name the resource whose
     * configuration triggered the call, when it has them; and {@code aws:ResourceAccount} and
     * {@code aws:RequestedRegion} describe the target. The keys that describe an IAM principal,
     * {@code aws:PrincipalArn} and {@code aws:PrincipalAccount} among them, are left out: no IAM
     * principal makes the call. A call to an SNS topic from a service SNS lists as setting it also
     * carries {@code aws:SourceOwner}, the source account, which is what the default topic policy
     * checks.
     */
    public static Map<String, List<String>> serviceCallContext(String servicePrincipal, String resourceArn,
                                                               String resourceOwnerAccountId,
                                                               String sourceArn, String sourceAccount) {
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        conditions.put("aws:PrincipalIsAWSService", List.of("true"));
        conditions.put("aws:PrincipalServiceName", List.of(servicePrincipal));
        conditions.put("aws:PrincipalServiceNamesList", List.of(servicePrincipal));
        putIfPresent(conditions, "aws:SourceArn", sourceArn);
        putIfPresent(conditions, "aws:SourceAccount", sourceAccount);
        if (SNS_SOURCE_OWNER_SERVICES.contains(ServicePrincipals.serviceName(servicePrincipal))
                && AwsArnUtils.isArnFor(resourceArn, "sns")) {
            putIfPresent(conditions, "aws:SourceOwner", sourceAccount);
        }
        String resourceAccount = accountFromArn(resourceArn);
        putIfPresent(conditions, "aws:ResourceAccount",
                resourceAccount == null || resourceAccount.isBlank() ? resourceOwnerAccountId : resourceAccount);
        putIfPresent(conditions, "aws:RequestedRegion", AwsArnUtils.regionOrDefault(resourceArn, null));
        return conditions;
    }

    private static String accountFromArn(String arn) {
        if (arn == null || !arn.startsWith("arn:")) {
            return null;
        }
        String[] segments = arn.split(":", 6);
        return segments.length > 4 ? segments[4] : null;
    }

    private static void putIfPresent(Map<String, List<String>> conditions, String key, String value) {
        if (value != null && !value.isBlank()) {
            conditions.put(key, List.of(value));
        }
    }

    // ── S3 ──────────────────────────────────────────────────────────────────────

    private Map<String, List<String>> s3ConditionContext(String action, ContainerRequestContext ctx) {
        Map<String, List<String>> conditions = s3ActionConditionContext(action, ctx);
        // A version action is checked only for a request naming a version, and carries its id.
        if (S3ObjectActions.isVersionAction(action) && ctx.getUriInfo() != null) {
            String versionId = ctx.getUriInfo().getQueryParameters().getFirst("versionId");
            if (versionId != null) {
                conditions = merge(conditions, Map.of(VERSION_ID, List.of(versionId)));
            }
        }
        return conditions;
    }

    private Map<String, List<String>> s3ActionConditionContext(String action, ContainerRequestContext ctx) {
        return switch (action) {
            case "s3:ListBucket" -> s3BucketListConditionContext(ctx.getUriInfo().getQueryParameters());
            // DeleteBucketTagging is checked as s3:PutBucketTagging, and sends no tags of its own:
            // what it has is the bucket's current tags, as GetBucketTagging does.
            case "s3:PutBucketTagging" -> "DELETE".equalsIgnoreCase(ctx.getMethod())
                    ? s3BucketResourceTagConditionContext(ctx)
                    : s3PutBucketTaggingConditionContext(ctx);
            case "s3:GetBucketTagging", "s3:DeleteBucket" -> s3BucketResourceTagConditionContext(ctx);
            // Object tags. Which action is given which key is what real AWS was measured to do,
            // not what reads naturally: s3:DeleteObject is given neither key, and s3:PutObject is
            // given the tags of the REQUEST only, never those of an object it is about to
            // overwrite. See S3ObjectTagConditionEnforcementIntegrationTest.
            //
            // The version actions IamActionRegistry checks for a request naming a versionId carry
            // the key as their base actions do, per the Service Reference, as do the annotation
            // actions, and the versionId picks whose tags are read.
            case "s3:GetObject", "s3:GetObjectVersion", "s3:GetObjectTagging",
                 "s3:GetObjectVersionTagging", "s3:GetObjectAcl", "s3:GetObjectVersionAcl",
                 "s3:PutObjectAcl", "s3:PutObjectVersionAcl", "s3:DeleteObjectTagging",
                 "s3:DeleteObjectVersionTagging", "s3:GetObjectAnnotation", "s3:GetObjectVersionAnnotation",
                 "s3:ListObjectAnnotations", "s3:ListObjectVersionAnnotations", "s3:PutObjectAnnotation",
                 "s3:PutObjectVersionAnnotation", "s3:DeleteObjectAnnotation",
                 "s3:DeleteObjectVersionAnnotation" -> s3ExistingObjectTagConditionContext(ctx);
            case "s3:PutObject" -> s3RequestObjectTagConditionContext(ctx);
            case "s3:PutObjectTagging", "s3:PutObjectVersionTagging" -> merge(
                    s3ExistingObjectTagConditionContext(ctx), s3PutObjectTaggingBodyConditionContext(ctx));
            default -> null;
        };
    }

    /**
     * {@code s3:ExistingObjectTag/<key>} from the tags of the version this request targets. An
     * object that does not exist, or has no tags, offers no keys, so a {@code StringEquals} on one
     * does not match and an allow conditioned on it does not apply.
     *
     * <p>The {@code versionId} query parameter decides which version is read. Falling back to the
     * current version would authorize a read of an older version against tags it does not carry,
     * which lets a request through that the policy refuses.
     */
    private Map<String, List<String>> s3ExistingObjectTagConditionContext(ContainerRequestContext ctx) {
        if (ctx.getUriInfo() == null || !s3Service.isResolvable()) {
            return null;
        }
        String path = ctx.getUriInfo().getPath();
        String bucket = s3BucketName(path);
        String key = s3ObjectKey(path);
        if (bucket == null || key == null) {
            return null;
        }
        String versionId = ctx.getUriInfo().getQueryParameters().getFirst("versionId");
        Map<String, String> tags;
        try {
            tags = s3Service.get().getObjectTagging(bucket, key, versionId);
        } catch (RuntimeException e) {
            LOG.debugv(e, "Could not read object tags for the IAM condition context: {0}/{1} version {2}",
                    bucket, key, versionId);
            return null;
        }
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        tags.forEach((k, v) -> conditions.put(EXISTING_OBJECT_TAG_PREFIX + k, List.of(v)));
        return conditions;
    }

    /**
     * {@code s3:RequestObjectTag/<key>} from a PutObject's {@code x-amz-tagging} header, or the
     * query parameter where a presigner may place it. The header takes precedence, including
     * an empty header, matching the source the S3 controller applies to the object.
     */
    private Map<String, List<String>> s3RequestObjectTagConditionContext(ContainerRequestContext ctx) {
        String header = ctx.getHeaderString("x-amz-tagging");
        if (header == null && ctx.getUriInfo() != null) {
            header = ctx.getUriInfo().getQueryParameters().getFirst("x-amz-tagging");
        }
        if (header == null || header.isBlank()) {
            return null;
        }
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        Set<String> requestedKeys = new LinkedHashSet<>();
        for (String pair : header.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = urlDecode(pair.substring(0, eq));
            String value = urlDecode(pair.substring(eq + 1));
            if (name == null || value == null) {
                continue;
            }
            conditions.put(REQUEST_OBJECT_TAG_PREFIX + name, List.of(value));
            requestedKeys.add(name);
        }
        if (!requestedKeys.isEmpty()) {
            conditions.put(REQUEST_OBJECT_TAG_KEYS, new ArrayList<>(requestedKeys));
        }
        return conditions.isEmpty() ? null : conditions;
    }

    /** {@code s3:RequestObjectTag/<key>} from the {@code <Tagging>} XML body of PutObjectTagging. */
    private Map<String, List<String>> s3PutObjectTaggingBodyConditionContext(ContainerRequestContext ctx) {
        byte[] body = bufferEntity(ctx);
        if (body == null || body.length == 0) {
            return null;
        }
        Map<String, String> tags = XmlParser.extractPairs(
                new String(body, StandardCharsets.UTF_8), "Tag", "Key", "Value");
        if (tags.isEmpty()) {
            return null;
        }
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        tags.forEach((k, v) -> conditions.put(REQUEST_OBJECT_TAG_PREFIX + k, List.of(v)));
        conditions.put(REQUEST_OBJECT_TAG_KEYS, new ArrayList<>(tags.keySet()));
        return conditions;
    }

    private static Map<String, List<String>> merge(Map<String, List<String>> a, Map<String, List<String>> b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        Map<String, List<String>> merged = new LinkedHashMap<>(a);
        merged.putAll(b);
        return merged;
    }

    /**
     * Null for a value {@code URLDecoder} refuses, such as the {@code %zz} of a malformed
     * {@code x-amz-tagging} header. The caller drops the pair rather than letting the
     * {@code IllegalArgumentException} out of the filter, where it becomes a 500 in place of the
     * 400 {@code InvalidTag} the handler returns for the same header.
     */
    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            LOG.debugv(e, "Undecodable tag component in the IAM condition context: {0}", value);
            return null;
        }
    }

    /** The object key of a path-style (or filter-rewritten) S3 path, null for a bucket-level one. */
    private static String s3ObjectKey(String path) {
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        int slash = stripped.indexOf('/');
        if (slash < 0 || slash == stripped.length() - 1) {
            return null;
        }
        return stripped.substring(slash + 1);
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
        if (!requested.isEmpty()) {
            conditions.put("aws:TagKeys", List.copyOf(requested.keySet()));
        }
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
     * single target get an empty list. Single-item DynamoDB writes also use separate contexts
     * for distinct return modes, so every requested output must be authorized on the same resource.
     */
    public List<Map<String, List<String>>> resolveRemainingTargets(String credentialScope, String action,
                                                                   ContainerRequestContext ctx) {
        if ("dynamodb".equals(credentialScope)) {
            List<String> modes = singleItemReturnValues(action, bufferedJsonBody(ctx));
            if (modes.size() < 2) {
                return List.of();
            }
            Map<String, List<String>> primary = dynamoDbConditionContext(action, ctx);
            List<Map<String, List<String>>> contexts = new ArrayList<>();
            for (int i = 1; i < modes.size(); i++) {
                Map<String, List<String>> context = new LinkedHashMap<>(primary);
                context.put("dynamodb:ReturnValues", List.of(modes.get(i)));
                contexts.add(context);
            }
            return contexts;
        }
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
            String key = form.get(prefix + i + ".Key");
            List<String> keys = conditions.computeIfAbsent("aws:TagKeys", ignored -> new ArrayList<>());
            if (!keys.contains(key)) {
                keys.add(key);
            }
            conditions.put(REQUEST_TAG_PREFIX + key,
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
     * {@code dynamodb:Select} and single-item write {@code dynamodb:ReturnValues} from the buffered request body.
     *
     * <p>A key that cannot be resolved (unknown table, a Key that omits the partition
     * attribute, an unparseable KeyConditionExpression) is omitted rather than guessed.
     * A {@code ForAllValues} condition matches an absent key; policies must pair it with
     * {@code Null:false} to require a resolved key.
     */
    Map<String, List<String>> dynamoDbConditionContext(String action, ContainerRequestContext ctx) {
        JsonNode body = bufferedJsonBody(ctx);
        if (body == null || !body.isObject()) {
            return null;
        }
        if (isDynamoDbBatch(action)) {
            return dynamoDbBatchConditionContext(action, ctx, null);
        }
        TableDefinition table = describeTargetTable(targetTableName(body));
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
        List<String> returnValues = singleItemReturnValues(action, body);
        if (!returnValues.isEmpty()) {
            conditions.put("dynamodb:ReturnValues", List.of(returnValues.getFirst()));
        }
        return conditions.isEmpty() ? null : conditions;
    }

    private static List<String> singleItemReturnValues(String action, JsonNode body) {
        if (!("dynamodb:PutItem".equals(action) || "dynamodb:UpdateItem".equals(action)
                || "dynamodb:DeleteItem".equals(action)) || body == null || !body.isObject()) {
            return List.of();
        }
        Set<String> modes = new LinkedHashSet<>();
        for (String field : List.of("ReturnValues", "ReturnValuesOnConditionCheckFailure")) {
            String mode = body.path(field).asText("NONE");
            if (!"NONE".equals(mode)) {
                modes.add(mode);
            }
        }
        return modes.isEmpty() ? List.of("NONE") : List.copyOf(modes);
    }

    /** One transaction item keeps its action, table and condition keys together. */
    record DynamoDbTransactionItem(String action, String resource, Map<String, List<String>> context) {
    }

    /**
     * Resolves every recognized item before the handler can mutate anything. Unknown envelope fields
     * cannot turn item checks back into a transaction-verb permission check.
     */
    List<DynamoDbTransactionItem> dynamoDbTransactionItems(String action, ContainerRequestContext ctx,
                                                         String region, String accountId) {
        boolean read = "dynamodb:TransactGetItems".equals(action);
        if (!read && !"dynamodb:TransactWriteItems".equals(action)) {
            return List.of();
        }
        JsonNode body = bufferedJsonBody(ctx);
        JsonNode items = body == null ? null : body.get("TransactItems");
        if (items == null || !items.isArray()) {
            return List.of();
        }
        List<DynamoDbTransactionItem> checks = new ArrayList<>();
        List<String> operations = read ? List.of("Get") : List.of("Put", "Delete", "Update", "ConditionCheck");
        for (JsonNode item : items) {
            for (String operation : operations) {
                JsonNode request = item.get(operation);
                if (request == null || !request.isObject()) {
                    continue;
                }
                String tableName = targetTableName(request);
                if (tableName == null) {
                    continue;
                }
                // Execution resolves ARN inputs within the request account; authorize that same table.
                String executionTable = DynamoDbTableNames.resolveWithRegion(tableName, region).name();
                String itemAction = "dynamodb:" + operation + "Item";
                DynamoDbConditionKeys.Result keys = DynamoDbConditionKeys.extract(
                        itemAction, request, describeTargetTable(executionTable));
                Map<String, List<String>> context = new LinkedHashMap<>();
                context.put("dynamodb:EnclosingOperation", List.of(read ? "TransactGetItems" : "TransactWriteItems"));
                if (!keys.leadingKeys().isEmpty()) {
                    context.put("dynamodb:LeadingKeys", keys.leadingKeys());
                }
                if (!keys.attributes().isEmpty()) {
                    context.put("dynamodb:Attributes", keys.attributes());
                }
                if (!read && request.hasNonNull("ReturnValuesOnConditionCheckFailure")) {
                    context.put("dynamodb:ReturnValues",
                            List.of(request.path("ReturnValuesOnConditionCheckFailure").asText()));
                }
                checks.add(new DynamoDbTransactionItem(itemAction,
                        ResourceArnBuilder.toDynamoDbTableArn(executionTable, region, accountId), context));
            }
        }
        return checks;
    }

    /** Resolves each batch entry with its own schema, optionally restricted to one resource. */
    Map<String, List<String>> dynamoDbBatchConditionContext(String action, ContainerRequestContext ctx,
                                                            String resourceArn) {
        if (!isDynamoDbBatch(action)) {
            return null;
        }
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        JsonNode body = bufferedJsonBody(ctx);
        JsonNode items = body == null ? null : body.get("RequestItems");
        if (items == null || !items.isObject()) {
            return conditions;
        }
        List<String> leadingKeys = new ArrayList<>();
        Set<String> attributes = new LinkedHashSet<>();
        items.properties().forEach(entry -> {
            String reference = entry.getKey().trim();
            if (resourceArn != null && !matchesTableResource(reference, resourceArn)) {
                return;
            }
            ObjectNode single = ((ObjectNode) body).objectNode();
            single.setAll((ObjectNode) body);
            single.putObject("RequestItems").set(entry.getKey(), entry.getValue());
            DynamoDbConditionKeys.Result keys = DynamoDbConditionKeys.extract(
                    action, single, describeTargetTable(reference));
            leadingKeys.addAll(keys.leadingKeys());
            attributes.addAll(keys.attributes());
        });
        if (!leadingKeys.isEmpty()) {
            conditions.put("dynamodb:LeadingKeys", List.copyOf(leadingKeys));
        }
        if (!attributes.isEmpty()) {
            conditions.put("dynamodb:Attributes", List.copyOf(attributes));
        }
        return conditions;
    }

    private static boolean isDynamoDbBatch(String action) {
        return "dynamodb:BatchGetItem".equals(action) || "dynamodb:BatchWriteItem".equals(action);
    }

    private static boolean matchesTableResource(String reference, String resourceArn) {
        if (AwsArnUtils.isArn(reference)) {
            return reference.equals(resourceArn);
        }
        return AwsArnUtils.parse(resourceArn).resource().equals("table/" + reference);
    }

    private JsonNode bufferedJsonBody(ContainerRequestContext ctx) {
        Object cached = ctx.getProperty(BUFFERED_JSON_BODY);
        return cached instanceof JsonNode node ? node : null;
    }

    /**
     * Looks up the table whose key schema names the partition key. Resolved lazily through
     * Instance so core.common keeps no hard dependency on the DynamoDB facade, and via
     * {@code findTable} rather than {@code describeTable} so the O(items) item-count refresh
     * never runs on the enforcement hot path.
     */
    private TableDefinition describeTargetTable(String tableName) {
        if (tableName == null || !dynamoDbFacade.isResolvable()) {
            return null;
        }
        String region = requestContext.getRegion() == null
                ? config.defaultRegion() : requestContext.getRegion();
        DynamoDbFacade dynamoDb = dynamoDbFacade.get();
        return dynamoDb.tables().findTable(dynamoDb.scope(region), tableName).orElse(null);
    }

    /** The table targeted by a non-batch request. */
    private String targetTableName(JsonNode body) {
        if (body.hasNonNull("TableName")) {
            String tableName = body.get("TableName").asText().trim();
            if (!tableName.isEmpty()) {
                return tableName;
            }
        }
        return null;
    }
}
