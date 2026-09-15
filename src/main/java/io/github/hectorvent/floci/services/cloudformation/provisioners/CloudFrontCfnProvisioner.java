package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudfront.CloudFrontService;
import io.github.hectorvent.floci.services.cloudfront.ResponseHeadersPolicyConfigCodec;
import io.github.hectorvent.floci.services.cloudfront.model.CacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.CachePolicy;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.cloudfront.model.OriginAccessControl;
import io.github.hectorvent.floci.services.cloudfront.model.OriginRequestPolicy;
import io.github.hectorvent.floci.services.cloudfront.model.ResponseHeadersPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Provisions the CloudFront configuration types a distribution references by id: response headers
 * policies, cache policies, origin request policies and origin access controls.
 *
 * <p>Each type has a single required {@code <Type>Config} property and no create-only property, so
 * the physical id is the id the service assigns and every update is applied in place through the
 * service's etag-guarded update, reading the current etag first as a client would. {@code Fn::GetAtt}
 * exposes {@code Id} and {@code LastModifiedTime} (origin access controls only {@code Id}), the
 * registry schema's read-only properties. Before this provisioner these types fell through to the
 * dispatcher's stub arm, whose synthetic id a distribution then refused (issue #2441).
 *
 * <p>A policy configuration is handed to the service as nested maps and lists with every scalar as
 * text, since the service's validator and codec read the blocks as string maps; the response headers
 * config is additionally reshaped by {@link ResponseHeadersPolicyConfigCodec#fromItemsTree} into the
 * flattened form that codec stores. {@code Name} and {@code Comment} are model fields, not part of
 * the map.
 *
 * <p>A committed in-place update is not reverted when a later resource fails the update; the stack
 * keeps the new policy configuration, the same limit the Cognito user pool has.
 */
@ApplicationScoped
public class CloudFrontCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(CloudFrontCfnProvisioner.class);

    static final String DISTRIBUTION = "AWS::CloudFront::Distribution";
    static final String RESPONSE_HEADERS_POLICY = "AWS::CloudFront::ResponseHeadersPolicy";
    static final String CACHE_POLICY = "AWS::CloudFront::CachePolicy";
    static final String ORIGIN_REQUEST_POLICY = "AWS::CloudFront::OriginRequestPolicy";
    static final String ORIGIN_ACCESS_CONTROL = "AWS::CloudFront::OriginAccessControl";

    static final String NO_SUCH_RESPONSE_HEADERS_POLICY = "NoSuchResponseHeadersPolicy";
    static final String NO_SUCH_CACHE_POLICY = "NoSuchCachePolicy";
    static final String NO_SUCH_ORIGIN_REQUEST_POLICY = "NoSuchOriginRequestPolicy";
    static final String NO_SUCH_ORIGIN_ACCESS_CONTROL = "NoSuchOriginAccessControl";

    private static final Set<String> MODEL_FIELDS = Set.of("Name", "Comment");

    private final CloudFrontService cloudFrontService;

    public CloudFrontCfnProvisioner(CloudFrontService cloudFrontService) {
        this.cloudFrontService = cloudFrontService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(DISTRIBUTION, RESPONSE_HEADERS_POLICY, CACHE_POLICY, ORIGIN_REQUEST_POLICY,
                ORIGIN_ACCESS_CONTROL);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        switch (r.getResourceType()) {
            case DISTRIBUTION -> provisionDistribution(r, props, ctx);
            case RESPONSE_HEADERS_POLICY -> provisionResponseHeadersPolicy(r, props, ctx);
            case CACHE_POLICY -> provisionCachePolicy(r, props, ctx);
            case ORIGIN_REQUEST_POLICY -> provisionOriginRequestPolicy(r, props, ctx);
            case ORIGIN_ACCESS_CONTROL -> provisionOriginAccessControl(r, props, ctx);
            default -> throw new IllegalArgumentException("Unsupported resource type: " + r.getResourceType());
        }
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /**
     * The physical id only changes here when the prior object was already gone: {@code prior}
     * returns the entity whenever the service still knows it, so an update either mutates that
     * entity in place under the same id or recreates a missing one under a new id. The entity
     * {@link ReplacementCleanup} records as displaced is therefore always one that no longer
     * exists, and the delete these hooks run for it is a no-op tolerated by its not-found code.
     * They are still needed: without them a recorded entity would never leave the cleanup list.
     */
    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }

    /**
     * Undoes a recreation when a later resource fails the stack update: the resource names the
     * prior id again with the attributes it carried, and the object this update created is
     * deleted. Without this the engine reached its "Rollback is not implemented" arm, which never
     * deletes, so the replacement stayed behind.
     *
     * <p>An in-place update leaves the physical id unchanged, so no rollback fields are recorded
     * and this answers false. Putting a committed in-place change back needs a configuration
     * snapshot this provisioner does not keep, so that case still reports as not rolled back, as
     * it does for {@code AWS::Cognito::UserPool}.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return ReplacementCleanup.rollback(resource, this::delete);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        // Only the type's own not-found code is tolerated. A policy still attached to a distribution
        // is refused with <Type>InUse, which must fail the stack delete as it does on AWS; in a stack
        // the distribution depends on the policy, so it is deleted first.
        switch (resourceType) {
            // removeDistribution is the stack-level delete: no disable or If-Match guard, and it
            // tolerates an id that is already gone, so no not-found code needs listing here.
            case DISTRIBUTION -> cloudFrontService.removeDistribution(physicalId);
            case RESPONSE_HEADERS_POLICY -> deleteWithEtag("response headers policy", physicalId,
                    id -> cloudFrontService.getResponseHeadersPolicy(id).getEtag(),
                    cloudFrontService::deleteResponseHeadersPolicy, NO_SUCH_RESPONSE_HEADERS_POLICY);
            case CACHE_POLICY -> deleteWithEtag("cache policy", physicalId,
                    id -> cloudFrontService.getCachePolicy(id).getEtag(),
                    cloudFrontService::deleteCachePolicy, NO_SUCH_CACHE_POLICY);
            case ORIGIN_REQUEST_POLICY -> deleteWithEtag("origin request policy", physicalId,
                    id -> cloudFrontService.getOriginRequestPolicy(id).getEtag(),
                    cloudFrontService::deleteOriginRequestPolicy, NO_SUCH_ORIGIN_REQUEST_POLICY);
            case ORIGIN_ACCESS_CONTROL -> deleteWithEtag("origin access control", physicalId,
                    id -> cloudFrontService.getOriginAccessControl(id).getEtag(),
                    cloudFrontService::deleteOriginAccessControl, NO_SUCH_ORIGIN_ACCESS_CONTROL);
            default -> { }
        }
    }

    private void provisionResponseHeadersPolicy(StackResource r, JsonNode props, ProvisionContext ctx) {
        JsonNode config = requireConfig(RESPONSE_HEADERS_POLICY, "ResponseHeadersPolicyConfig", props, ctx);
        ResponseHeadersPolicy policy = new ResponseHeadersPolicy();
        policy.setName(text(config, "Name"));
        policy.setComment(text(config, "Comment"));
        policy.setConfig(ResponseHeadersPolicyConfigCodec.fromItemsTree(toConfigMap(config)));
        ResponseHeadersPolicy prior = prior(ctx, "response headers policy",
                cloudFrontService::getResponseHeadersPolicy, NO_SUCH_RESPONSE_HEADERS_POLICY);
        ResponseHeadersPolicy provisioned = prior == null
                ? cloudFrontService.createResponseHeadersPolicy(policy)
                : cloudFrontService.updateResponseHeadersPolicy(prior.getId(), prior.getEtag(), policy);
        expose(r, provisioned.getId(), provisioned.getLastModifiedTime());
    }

    private void provisionCachePolicy(StackResource r, JsonNode props, ProvisionContext ctx) {
        JsonNode config = requireConfig(CACHE_POLICY, "CachePolicyConfig", props, ctx);
        CachePolicy policy = new CachePolicy();
        policy.setName(text(config, "Name"));
        policy.setComment(text(config, "Comment"));
        policy.setConfig(toConfigMap(config));
        CachePolicy prior = prior(ctx, "cache policy", cloudFrontService::getCachePolicy, NO_SUCH_CACHE_POLICY);
        CachePolicy provisioned = prior == null
                ? cloudFrontService.createCachePolicy(policy)
                : cloudFrontService.updateCachePolicy(prior.getId(), prior.getEtag(), policy);
        expose(r, provisioned.getId(), provisioned.getLastModifiedTime());
    }

    private void provisionOriginRequestPolicy(StackResource r, JsonNode props, ProvisionContext ctx) {
        JsonNode config = requireConfig(ORIGIN_REQUEST_POLICY, "OriginRequestPolicyConfig", props, ctx);
        OriginRequestPolicy policy = new OriginRequestPolicy();
        policy.setName(text(config, "Name"));
        policy.setComment(text(config, "Comment"));
        policy.setConfig(toConfigMap(config));
        OriginRequestPolicy prior = prior(ctx, "origin request policy",
                cloudFrontService::getOriginRequestPolicy, NO_SUCH_ORIGIN_REQUEST_POLICY);
        OriginRequestPolicy provisioned = prior == null
                ? cloudFrontService.createOriginRequestPolicy(policy)
                : cloudFrontService.updateOriginRequestPolicy(prior.getId(), prior.getEtag(), policy);
        expose(r, provisioned.getId(), provisioned.getLastModifiedTime());
    }

    private void provisionOriginAccessControl(StackResource r, JsonNode props, ProvisionContext ctx) {
        JsonNode config = requireConfig(ORIGIN_ACCESS_CONTROL, "OriginAccessControlConfig", props, ctx);
        OriginAccessControl oac = new OriginAccessControl();
        oac.setName(text(config, "Name"));
        oac.setDescription(text(config, "Description"));
        oac.setSigningBehavior(text(config, "SigningBehavior"));
        oac.setSigningProtocol(text(config, "SigningProtocol"));
        oac.setOriginAccessControlOriginType(text(config, "OriginAccessControlOriginType"));
        OriginAccessControl prior = prior(ctx, "origin access control",
                cloudFrontService::getOriginAccessControl, NO_SUCH_ORIGIN_ACCESS_CONTROL);
        OriginAccessControl provisioned = prior == null
                ? cloudFrontService.createOriginAccessControl(oac)
                : cloudFrontService.updateOriginAccessControl(prior.getId(), prior.getEtag(), oac);
        r.setPhysicalId(provisioned.getId());
        r.getAttributes().put("Id", provisioned.getId());
    }

    private static void expose(StackResource r, String id, Instant lastModifiedTime) {
        r.setPhysicalId(id);
        r.getAttributes().put("Id", id);
        r.getAttributes().put("LastModifiedTime", lastModifiedTime == null ? "" : lastModifiedTime.toString());
    }

    /**
     * The prior entity on an update, or null on a create or when the record is stale: a prior id the
     * service no longer knows (the emulator restarted without persistence) is recreated rather than
     * failing the update on a policy nobody can see.
     */
    private <T> T prior(ProvisionContext ctx, String description, Function<String, T> get, String notFound) {
        if (!ctx.isUpdate() || ctx.priorPhysicalId() == null || ctx.priorPhysicalId().isBlank()) {
            return null;
        }
        try {
            return get.apply(ctx.priorPhysicalId());
        } catch (AwsException e) {
            if (!notFound.equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Prior {0} {1} is gone; creating a new one", description, ctx.priorPhysicalId());
            return null;
        }
    }

    private static void deleteWithEtag(String description, String physicalId, Function<String, String> etagOf,
                                       BiConsumer<String, String> delete, String notFound) {
        CfnDeletes.safeDelete(description, physicalId,
                () -> delete.accept(physicalId, etagOf.apply(physicalId)), notFound);
    }

    private static JsonNode requireConfig(String type, String name, JsonNode props, ProvisionContext ctx) {
        JsonNode raw = props == null ? null : props.get(name);
        JsonNode resolved = raw == null || raw.isNull() ? null : ctx.engine().resolveNode(raw);
        if (resolved == null || !resolved.isObject()) {
            throw new AwsException("ValidationError", type + " requires " + name, 400);
        }
        return resolved;
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * The configuration blocks as the service's codec shapes them: objects to maps, arrays to lists,
     * every scalar as its text (a JSON {@code true} arrives as {@code "true"}), nulls dropped.
     */
    static Map<String, Object> toConfigMap(JsonNode config) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = config.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            if (MODEL_FIELDS.contains(field.getKey())) {
                continue;
            }
            Object value = toConfigValue(field.getValue());
            if (value != null) {
                map.put(field.getKey(), value);
            }
        }
        return map;
    }

    private static Object toConfigValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                Object value = toConfigValue(field.getValue());
                if (value != null) {
                    map.put(field.getKey(), value);
                }
            }
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode element : node) {
                Object value = toConfigValue(element);
                if (value != null) {
                    list.add(value);
                }
            }
            return list;
        }
        return node.asText();
    }

    // The distribution itself, moved out of CloudFormationResourceProvisioner. It reads the
    // policy ids the types above hand it, which is why the two live in one provisioner.


    /**
     * Provisions an {@code AWS::CloudFront::Distribution} by translating its {@code DistributionConfig}
     * property tree into a {@link DistributionConfig} and creating or updating the distribution.
     * {@code Ref} returns the distribution id; {@code Fn::GetAtt} exposes {@code Id} and
     * {@code DomainName} (closes #1147, where {@code Fn::GetAtt DomainName} previously returned an
     * unresolved token).
     */
    private void provisionDistribution(StackResource r, JsonNode props, ProvisionContext ctx) {
        CloudFormationTemplateEngine engine = ctx.engine();
        JsonNode dc = props != null ? props.path("DistributionConfig") : null;
        DistributionConfig config = new DistributionConfig();
        if (dc != null && !dc.isMissingNode() && !dc.isNull()) {
            config.setEnabled(cfnBool(dc, "Enabled", engine, true));
            config.setComment(cfnText(dc, "Comment", engine));
            config.setDefaultRootObject(cfnText(dc, "DefaultRootObject", engine));
            config.setHttpVersion(cfnTextOrDefault(dc, "HttpVersion", engine, "http2"));
            config.setPriceClass(cfnTextOrDefault(dc, "PriceClass", engine, "PriceClass_All"));
            config.setAliases(cfnStringList(dc.path("Aliases"), engine));
            config.setOrigins(cfnOrigins(dc, engine));
            config.setDefaultCacheBehavior(cfnDefaultCacheBehavior(dc.path("DefaultCacheBehavior"), engine));
            config.setCacheBehaviors(cfnCacheBehaviors(dc, engine));
            config.setCustomErrorResponses(cfnCustomErrorResponses(dc, engine));
        }

        Distribution dist = new Distribution();
        dist.setConfig(config);
        if (!ctx.isUpdate()) {
            dist = cloudFrontService.createDistribution(dist, Map.of());
        } else {
            Distribution existing = cloudFrontService.getDistribution(ctx.priorPhysicalId());
            dist = cloudFrontService.updateDistribution(
                    existing.getId(), existing.getEtag(), dist);
        }

        r.setPhysicalId(dist.getId());
        r.getAttributes().put("Id", dist.getId());
        r.getAttributes().put("DomainName", dist.getDomainName());
        r.getAttributes().put("Arn", dist.getArn());
    }
    private List<Origin> cfnOrigins(JsonNode dc, CloudFormationTemplateEngine engine) {
        List<Origin> origins = new ArrayList<>();
        JsonNode items = dc.path("Origins");
        if (items.isArray()) {
            for (JsonNode node : items) {
                Origin origin = new Origin();
                origin.setId(cfnText(node, "Id", engine));
                origin.setDomainName(cfnText(node, "DomainName", engine));
                String originPath = cfnText(node, "OriginPath", engine);
                if (!originPath.isEmpty()) {
                    origin.setOriginPath(originPath);
                }
                String originAccessControlId =
                        cfnText(node, "OriginAccessControlId", engine);
                if (!originAccessControlId.isEmpty()) {
                    origin.setOriginAccessControlId(originAccessControlId);
                }
                JsonNode originCustomHeaders = node.path("OriginCustomHeaders");
                if (originCustomHeaders.isArray()) {
                    List<Map<String, String>> customHeaders = new ArrayList<>();
                    for (JsonNode customHeader : originCustomHeaders) {
                        Map<String, String> mapped = new LinkedHashMap<>();
                        mapped.put("HeaderName", cfnText(customHeader, "HeaderName", engine));
                        mapped.put("HeaderValue", cfnText(customHeader, "HeaderValue", engine));
                        customHeaders.add(mapped);
                    }
                    origin.setCustomHeaders(customHeaders);
                }
                JsonNode s3 = node.path("S3OriginConfig");
                JsonNode custom = node.path("CustomOriginConfig");
                if (!custom.isMissingNode() && !custom.isNull()) {
                    Map<String, Object> coc = new LinkedHashMap<>();
                    coc.put("HTTPPort", cfnTextOrDefault(custom, "HTTPPort", engine, "80"));
                    coc.put("HTTPSPort", cfnTextOrDefault(custom, "HTTPSPort", engine, "443"));
                    coc.put("OriginProtocolPolicy",
                            cfnTextOrDefault(custom, "OriginProtocolPolicy", engine, "https-only"));
                    origin.setCustomOriginConfig(coc);
                } else {
                    // No CustomOriginConfig => S3 origin (S3OriginConfig may be present or defaulted).
                    Map<String, String> s3c = new LinkedHashMap<>();
                    s3c.put("OriginAccessIdentity",
                            s3.isMissingNode() || s3.isNull() ? "" : cfnText(s3, "OriginAccessIdentity", engine));
                    origin.setS3OriginConfig(s3c);
                }
                origins.add(origin);
            }
        }
        return origins;
    }
    private DefaultCacheBehavior cfnDefaultCacheBehavior(JsonNode node, CloudFormationTemplateEngine engine) {
        DefaultCacheBehavior dcb = new DefaultCacheBehavior();
        if (node != null && !node.isMissingNode() && !node.isNull()) {
            dcb.setTargetOriginId(cfnText(node, "TargetOriginId", engine));
            dcb.setViewerProtocolPolicy(cfnTextOrDefault(node, "ViewerProtocolPolicy", engine, "allow-all"));
            dcb.setResponseHeadersPolicyId(cfnText(node, "ResponseHeadersPolicyId", engine));
            List<String> trustedKeyGroups = cfnStringList(node.path("TrustedKeyGroups"), engine);
            if (!trustedKeyGroups.isEmpty()) {
                dcb.setTrustedKeyGroups(trustedKeyGroups);
            }
        }
        return dcb;
    }
    private List<CacheBehavior> cfnCacheBehaviors(JsonNode dc, CloudFormationTemplateEngine engine) {
        List<CacheBehavior> behaviors = new ArrayList<>();
        JsonNode items = dc.path("CacheBehaviors");
        if (items.isArray()) {
            for (JsonNode node : items) {
                CacheBehavior cb = new CacheBehavior();
                cb.setPathPattern(cfnText(node, "PathPattern", engine));
                cb.setTargetOriginId(cfnText(node, "TargetOriginId", engine));
                cb.setViewerProtocolPolicy(cfnTextOrDefault(node, "ViewerProtocolPolicy", engine, "allow-all"));
                cb.setResponseHeadersPolicyId(cfnText(node, "ResponseHeadersPolicyId", engine));
                List<String> trustedKeyGroups = cfnStringList(node.path("TrustedKeyGroups"), engine);
                if (!trustedKeyGroups.isEmpty()) {
                    cb.setTrustedKeyGroups(trustedKeyGroups);
                }
                behaviors.add(cb);
            }
        }
        return behaviors;
    }
    private List<Map<String, Object>> cfnCustomErrorResponses(JsonNode dc, CloudFormationTemplateEngine engine) {
        List<Map<String, Object>> result = new ArrayList<>();
        JsonNode items = dc.path("CustomErrorResponses");
        if (items.isArray()) {
            for (JsonNode node : items) {
                Map<String, Object> cer = new LinkedHashMap<>();
                cer.put("ErrorCode", cfnText(node, "ErrorCode", engine));
                putIfPresent(cer, "ResponseCode", cfnText(node, "ResponseCode", engine));
                putIfPresent(cer, "ResponsePagePath", cfnText(node, "ResponsePagePath", engine));
                putIfPresent(cer, "ErrorCachingMinTTL", cfnText(node, "ErrorCachingMinTTL", engine));
                result.add(cer);
            }
        }
        return result;
    }
    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isEmpty()) {
            map.put(key, value);
        }
    }
    private List<String> cfnStringList(JsonNode arrayNode, CloudFormationTemplateEngine engine) {
        List<String> result = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode item : arrayNode) {
                String value = engine.resolve(item);
                if (value != null && !value.isEmpty()) {
                    result.add(value);
                }
            }
        }
        return result;
    }
    private String cfnText(JsonNode parent, String field, CloudFormationTemplateEngine engine) {
        return parent == null ? "" : engine.resolve(parent.path(field));
    }
    private String cfnTextOrDefault(JsonNode parent, String field, CloudFormationTemplateEngine engine,
                                    String dflt) {
        String value = cfnText(parent, field, engine);
        return value.isEmpty() ? dflt : value;
    }
    private boolean cfnBool(JsonNode parent, String field, CloudFormationTemplateEngine engine, boolean dflt) {
        String value = cfnText(parent, field, engine);
        return value.isEmpty() ? dflt : "true".equalsIgnoreCase(value);
    }
}
