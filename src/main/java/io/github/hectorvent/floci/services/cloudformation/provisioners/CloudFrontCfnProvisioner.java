package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudfront.CloudFrontService;
import io.github.hectorvent.floci.services.cloudfront.ResponseHeadersPolicyConfigCodec;
import io.github.hectorvent.floci.services.cloudfront.model.CachePolicy;
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
        return Set.of(RESPONSE_HEADERS_POLICY, CACHE_POLICY, ORIGIN_REQUEST_POLICY, ORIGIN_ACCESS_CONTROL);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case RESPONSE_HEADERS_POLICY -> provisionResponseHeadersPolicy(r, props, ctx);
            case CACHE_POLICY -> provisionCachePolicy(r, props, ctx);
            case ORIGIN_REQUEST_POLICY -> provisionOriginRequestPolicy(r, props, ctx);
            case ORIGIN_ACCESS_CONTROL -> provisionOriginAccessControl(r, props, ctx);
            default -> throw new IllegalArgumentException("Unsupported resource type: " + r.getResourceType());
        }
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
}
