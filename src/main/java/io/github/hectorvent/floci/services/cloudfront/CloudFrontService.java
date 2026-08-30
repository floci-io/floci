package io.github.hectorvent.floci.services.cloudfront;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudfront.model.CacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.CachePolicy;
import io.github.hectorvent.floci.services.cloudfront.model.CloudFrontFunction;
import io.github.hectorvent.floci.services.cloudfront.model.CloudFrontOriginAccessIdentity;
import io.github.hectorvent.floci.services.cloudfront.model.ContinuousDeploymentPolicy;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.FieldLevelEncryptionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.FieldLevelEncryptionProfile;
import io.github.hectorvent.floci.services.cloudfront.model.Invalidation;
import io.github.hectorvent.floci.services.cloudfront.model.KeyGroup;
import io.github.hectorvent.floci.services.cloudfront.model.MonitoringSubscription;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.cloudfront.model.OriginAccessControl;
import io.github.hectorvent.floci.services.cloudfront.model.OriginRequestPolicy;
import io.github.hectorvent.floci.services.cloudfront.model.PublicKey;
import io.github.hectorvent.floci.services.cloudfront.model.RealtimeLogConfig;
import io.github.hectorvent.floci.services.cloudfront.model.ResponseHeadersPolicy;
import io.github.hectorvent.floci.services.cloudfront.model.StreamingDistribution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

@ApplicationScoped
public class CloudFrontService {

    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int MAX_CUSTOM_RESPONSE_HEADERS_POLICIES = 20;
    private static final int MAX_DISTRIBUTIONS_PER_RESPONSE_HEADERS_POLICY = 100;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> OAC_ORIGIN_TYPES =
            Set.of("s3", "mediastore", "mediapackagev2", "lambda");
    private static final Set<String> OAC_SIGNING_BEHAVIORS =
            Set.of("always", "never", "no-override");
    private static final int MAX_ORIGIN_CUSTOM_HEADERS = 30;
    private static final int MAX_CUSTOM_HEADER_NAME_LENGTH = 256;
    private static final int MAX_CUSTOM_HEADER_VALUE_LENGTH = 1_783;
    private static final int MAX_CUSTOM_HEADERS_LENGTH = 10_240;
    private static final Pattern HTTP_HEADER_NAME =
            Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> PROHIBITED_ORIGIN_CUSTOM_HEADERS = Set.of(
            "cache-control", "connection", "content-length", "cookie", "host", "if-match",
            "if-modified-since", "if-none-match", "if-range", "if-unmodified-since",
            "max-forwards", "pragma", "proxy-authenticate", "proxy-authorization",
            "proxy-connection", "range", "request-range", "te", "trailer", "transfer-encoding",
            "upgrade", "via", "x-real-ip");
    static final String MANAGED_CORS_AND_SECURITY_POLICY_ID =
            "e61eb60c-9c35-4d20-a928-2b84e02af89c";
    static final String MANAGED_CORS_PREFLIGHT_POLICY_ID =
            "5cc3b908-e619-4b99-88e5-2cf7f45965bd";
    static final String MANAGED_CORS_PREFLIGHT_AND_SECURITY_POLICY_ID =
            "eaab4381-ed33-4a86-88ca-d9558dc6cd63";
    static final String MANAGED_SECURITY_POLICY_ID =
            "67f7725c-6f97-4210-82d7-5512b31e9d03";
    static final String MANAGED_SIMPLE_CORS_POLICY_ID =
            "60669652-455b-4ae9-85a4-c4c02393f86c";
    private static final Map<String, ResponseHeadersPolicy> MANAGED_RESPONSE_HEADERS_POLICIES =
            managedResponseHeadersPolicies();

    private final StorageBackend<String, Distribution> distStore;
    private final StorageBackend<String, List<Invalidation>> invalidationStore;
    private final StorageBackend<String, CachePolicy> cachePolicyStore;
    private final StorageBackend<String, OriginRequestPolicy> orpStore;
    private final StorageBackend<String, ResponseHeadersPolicy> rhpStore;
    private final StorageBackend<String, OriginAccessControl> oacStore;
    private final StorageBackend<String, CloudFrontOriginAccessIdentity> oaiStore;
    private final StorageBackend<String, CloudFrontFunction> functionStore;
    private final StorageBackend<String, Map<String, String>> tagStore;
    private final StorageBackend<String, ContinuousDeploymentPolicy> cdpStore;
    private final StorageBackend<String, PublicKey> publicKeyStore;
    private final StorageBackend<String, KeyGroup> keyGroupStore;
    private final StorageBackend<String, RealtimeLogConfig> realtimeLogConfigStore;
    private final StorageBackend<String, StreamingDistribution> streamingDistStore;
    private final StorageBackend<String, FieldLevelEncryptionConfig> fleConfigStore;
    private final StorageBackend<String, FieldLevelEncryptionProfile> fleProfileStore;
    private final StorageBackend<String, MonitoringSubscription> monitoringStore;
    private final String accountId;
    private final String domainSuffix;

    @Inject
    public CloudFrontService(StorageFactory factory, EmulatorConfig config) {
        this.distStore = factory.create("cloudfront", "cloudfront-distributions.json",
                new TypeReference<Map<String, Distribution>>() {});
        this.invalidationStore = factory.create("cloudfront", "cloudfront-invalidations.json",
                new TypeReference<Map<String, List<Invalidation>>>() {});
        this.cachePolicyStore = factory.create("cloudfront", "cloudfront-cache-policies.json",
                new TypeReference<Map<String, CachePolicy>>() {});
        this.orpStore = factory.create("cloudfront", "cloudfront-origin-request-policies.json",
                new TypeReference<Map<String, OriginRequestPolicy>>() {});
        this.rhpStore = factory.create("cloudfront", "cloudfront-response-headers-policies.json",
                new TypeReference<Map<String, ResponseHeadersPolicy>>() {});
        this.oacStore = factory.create("cloudfront", "cloudfront-oac.json",
                new TypeReference<Map<String, OriginAccessControl>>() {});
        this.oaiStore = factory.create("cloudfront", "cloudfront-oai.json",
                new TypeReference<Map<String, CloudFrontOriginAccessIdentity>>() {});
        this.functionStore = factory.create("cloudfront", "cloudfront-functions.json",
                new TypeReference<Map<String, CloudFrontFunction>>() {});
        this.tagStore = factory.create("cloudfront", "cloudfront-tags.json",
                new TypeReference<Map<String, Map<String, String>>>() {});
        this.cdpStore = factory.create("cloudfront", "cloudfront-continuous-deployment-policies.json",
                new TypeReference<Map<String, ContinuousDeploymentPolicy>>() {});
        this.publicKeyStore = factory.create("cloudfront", "cloudfront-public-keys.json",
                new TypeReference<Map<String, PublicKey>>() {});
        this.keyGroupStore = factory.create("cloudfront", "cloudfront-key-groups.json",
                new TypeReference<Map<String, KeyGroup>>() {});
        this.realtimeLogConfigStore = factory.create("cloudfront", "cloudfront-realtime-log-configs.json",
                new TypeReference<Map<String, RealtimeLogConfig>>() {});
        this.streamingDistStore = factory.create("cloudfront", "cloudfront-streaming-distributions.json",
                new TypeReference<Map<String, StreamingDistribution>>() {});
        this.fleConfigStore = factory.create("cloudfront", "cloudfront-fle-configs.json",
                new TypeReference<Map<String, FieldLevelEncryptionConfig>>() {});
        this.fleProfileStore = factory.create("cloudfront", "cloudfront-fle-profiles.json",
                new TypeReference<Map<String, FieldLevelEncryptionProfile>>() {});
        this.monitoringStore = factory.create("cloudfront", "cloudfront-monitoring-subscriptions.json",
                new TypeReference<Map<String, MonitoringSubscription>>() {});
        this.accountId = config.defaultAccountId();
        this.domainSuffix = config.services().cloudfront().domainSuffix();
    }

    // ── Distributions ─────────────────────────────────────────────────────────

    public synchronized Distribution createDistribution(Distribution dist, Map<String, String> tags) {
        ensureAliasesAvailable(dist.getConfig(), null);
        validateOriginCustomHeaders(dist.getConfig());
        validateResponseHeadersPolicyReferences(dist.getConfig(), null);
        validateTrustedKeyGroups(dist.getConfig());
        String id = generateDistributionId();
        dist.setId(id);
        dist.setArn(AwsArnUtils.Arn.of("cloudfront", "", accountId, "distribution/" + id).toString());
        dist.setDomainName(id + "." + domainSuffix);
        dist.setStatus("Deployed");
        dist.setLastModifiedTime(Instant.now());
        dist.setEtag(UUID.randomUUID().toString());
        if (tags != null && !tags.isEmpty()) {
            dist.setTags(tags);
            tagStore.put("distribution/" + id, tags);
        }
        distStore.put(id, dist);
        return dist;
    }

    public Distribution getDistribution(String id) {
        return distStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchDistribution", "The specified distribution does not exist.", 404));
    }

    public synchronized Distribution updateDistribution(String id, String ifMatch, Distribution updated) {
        Distribution existing = getDistribution(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        ensureAliasesAvailable(updated.getConfig(), id);
        validateOriginCustomHeaders(updated.getConfig());
        validateResponseHeadersPolicyReferences(updated.getConfig(), id);
        validateTrustedKeyGroups(updated.getConfig());
        updated.setId(id);
        updated.setArn(existing.getArn());
        updated.setDomainName(existing.getDomainName());
        updated.setStatus("Deployed");
        updated.setLastModifiedTime(Instant.now());
        updated.setEtag(UUID.randomUUID().toString());
        updated.setTags(existing.getTags());
        distStore.put(id, updated);
        return updated;
    }

    private static void validateOriginCustomHeaders(DistributionConfig config) {
        if (config == null || config.getOrigins() == null) {
            return;
        }
        for (Origin origin : config.getOrigins()) {
            List<Map<String, String>> headers = origin.getCustomHeaders();
            if (headers == null) {
                continue;
            }
            if (headers.size() > MAX_ORIGIN_CUSTOM_HEADERS) {
                throw new AwsException(
                        "TooManyOriginCustomHeaders",
                        "Your request contains too many origin custom headers.",
                        400);
            }
            int combinedLength = 0;
            Set<String> names = new HashSet<>();
            for (Map<String, String> header : headers) {
                String name = header == null ? null : header.get("HeaderName");
                String value = header == null ? null : header.get("HeaderValue");
                if (name == null || name.isBlank() || value == null
                        || name.length() > MAX_CUSTOM_HEADER_NAME_LENGTH
                        || value.length() > MAX_CUSTOM_HEADER_VALUE_LENGTH
                        || !HTTP_HEADER_NAME.matcher(name).matches()
                        || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                    throw invalidOriginCustomHeader("Invalid origin custom header name or value");
                }
                String normalized = name.toLowerCase(Locale.ROOT);
                if (PROHIBITED_ORIGIN_CUSTOM_HEADERS.contains(normalized)
                        || normalized.startsWith("x-amz-") || normalized.startsWith("x-edge-")) {
                    throw invalidOriginCustomHeader("Prohibited origin custom header: " + name);
                }
                if (!names.add(normalized)) {
                    throw invalidOriginCustomHeader("Duplicate origin custom header: " + name);
                }
                combinedLength += name.length() + value.length();
                if (combinedLength > MAX_CUSTOM_HEADERS_LENGTH) {
                    throw invalidOriginCustomHeader("Origin custom headers exceed the size quota");
                }
            }
        }
    }

    private static AwsException invalidOriginCustomHeader(String message) {
        return new AwsException("InvalidArgument", message, 400);
    }

    public synchronized void deleteDistribution(String id, String ifMatch) {
        Distribution existing = getDistribution(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        if (existing.getConfig() != null && existing.getConfig().isEnabled()) {
            throw new AwsException("DistributionNotDisabled",
                    "The distribution you are trying to delete has not been disabled.", 409);
        }
        distStore.delete(id);
        invalidationStore.delete(id);
        tagStore.delete("distribution/" + id);
    }

    /**
     * Removes a distribution and its associated invalidations/tags without the disable/If-Match guards
     * enforced by {@link #deleteDistribution(String, String)}. Used by CloudFormation stack deletion,
     * which owns the resource lifecycle at the stack level.
     */
    public synchronized void removeDistribution(String id) {
        distStore.delete(id);
        invalidationStore.delete(id);
        tagStore.delete("distribution/" + id);
    }

    public List<Distribution> listDistributions(String marker, int maxItems) {
        List<Distribution> all = new ArrayList<>(distStore.scan(k -> true));
        all.sort((a, b) -> a.getId().compareTo(b.getId()));
        if (marker != null && !marker.isEmpty()) {
            int idx = 0;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).getId().equals(marker)) {
                    idx = i + 1;
                    break;
                }
            }
            all = all.subList(idx, all.size());
        }
        if (maxItems > 0 && all.size() > maxItems) {
            return all.subList(0, maxItems);
        }
        return all;
    }

    public synchronized void associateAlias(String targetDistributionId, String alias) {
        if (alias == null || alias.isBlank()) {
            throw new AwsException("InvalidArgument", "The alias must not be empty.", 400);
        }
        Distribution dist = getDistribution(targetDistributionId);
        if (dist.getConfig() == null) {
            throw new AwsException("InvalidArgument", "The target distribution has no configuration.", 400);
        }
        for (Distribution candidate : distStore.scan(k -> true)) {
            if (targetDistributionId.equals(candidate.getId()) || candidate.getConfig() == null
                    || candidate.getConfig().getAliases() == null) {
                continue;
            }
            List<String> previousAliases = candidate.getConfig().getAliases();
            List<String> remaining = new ArrayList<>(previousAliases);
            remaining.removeIf(existing -> alias.equalsIgnoreCase(existing));
            if (remaining.size() != previousAliases.size()) {
                candidate.getConfig().setAliases(remaining);
                candidate.setEtag(UUID.randomUUID().toString());
                candidate.setLastModifiedTime(Instant.now());
                distStore.put(candidate.getId(), candidate);
            }
        }

        List<String> aliases = dist.getConfig().getAliases();
        if (aliases == null) {
            aliases = new ArrayList<>();
        } else {
            aliases = new ArrayList<>(aliases);
        }
        aliases.removeIf(existing -> alias.equalsIgnoreCase(existing));
        aliases.add(alias);
        dist.getConfig().setAliases(aliases);
        dist.setEtag(UUID.randomUUID().toString());
        dist.setLastModifiedTime(Instant.now());
        distStore.put(targetDistributionId, dist);
    }

    /**
     * Finds the distribution whose data-plane requests should be served for the given {@code Host}
     * header. A distribution matches when the host equals its assigned CloudFront domain name
     * ({@code <id>.cloudfront.net}) or one of its alternate domain names (CNAME aliases). Any port
     * suffix is ignored and matching is case-insensitive. Returns {@code null} when nothing matches.
     */
    public Distribution findByHost(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String hostname = stripPort(host);
        List<Distribution> distributions = new ArrayList<>(distStore.scan(k -> true));
        for (Distribution dist : distributions) {
            if (hostname.equalsIgnoreCase(dist.getDomainName())) {
                return dist;
            }
        }
        for (Distribution dist : distributions) {
            DistributionConfig cfg = dist.getConfig();
            if (cfg != null && cfg.getAliases() != null) {
                for (String alias : cfg.getAliases()) {
                    if (hostname.equalsIgnoreCase(alias)) {
                        return dist;
                    }
                }
            }
        }
        Distribution best = null;
        int bestSpecificity = -1;
        for (Distribution dist : distributions) {
            DistributionConfig cfg = dist.getConfig();
            if (cfg == null || cfg.getAliases() == null) {
                continue;
            }
            for (String alias : cfg.getAliases()) {
                if (wildcardAliasMatches(alias, hostname) && alias.length() > bestSpecificity) {
                    best = dist;
                    bestSpecificity = alias.length();
                }
            }
        }
        return best;
    }

    private void ensureAliasesAvailable(DistributionConfig config, String currentDistributionId) {
        if (config == null || config.getAliases() == null) {
            return;
        }
        for (String requested : config.getAliases()) {
            if (requested == null || requested.isBlank()) {
                continue;
            }
            for (Distribution existing : distStore.scan(k -> true)) {
                if (existing.getId().equals(currentDistributionId) || existing.getConfig() == null
                        || existing.getConfig().getAliases() == null) {
                    continue;
                }
                for (String assigned : existing.getConfig().getAliases()) {
                    if (requested.equalsIgnoreCase(assigned)) {
                        throw new AwsException("CNAMEAlreadyExists",
                                "The CNAME you provided is already associated with a different resource.", 409);
                    }
                }
            }
        }
    }

    private static boolean wildcardAliasMatches(String alias, String hostname) {
        if (alias == null || !alias.startsWith("*.") || hostname == null) {
            return false;
        }
        String suffix = alias.substring(1);          // ".example.com"
        if (hostname.length() <= suffix.length()
                || !hostname.regionMatches(true, hostname.length() - suffix.length(),
                        suffix, 0, suffix.length())) {
            return false;
        }
        // A CloudFront wildcard replaces exactly one label, so the part standing in for the "*"
        // must not itself contain a dot: "*.example.com" covers marketing.example.com but not
        // marketing.product.example.com. AWS is explicit that names at a level higher or lower
        // than the wildcard are not covered.
        return hostname.lastIndexOf('.', hostname.length() - suffix.length() - 1) < 0;
    }

    private static String stripPort(String host) {
        int colon = host.lastIndexOf(':');
        if (colon > 0) {
            String maybePort = host.substring(colon + 1);
            if (!maybePort.isEmpty() && maybePort.chars().allMatch(Character::isDigit)) {
                return host.substring(0, colon);
            }
        }
        return host;
    }

    // ── Invalidations ─────────────────────────────────────────────────────────

    public synchronized Invalidation createInvalidation(String distributionId, Invalidation inv) {
        getDistribution(distributionId);
        inv.setId(generateInvalidationId());
        inv.setStatus("Completed");
        inv.setCreateTime(Instant.now());
        List<Invalidation> list = new ArrayList<>(
                invalidationStore.get(distributionId).orElse(new ArrayList<>()));
        list.add(inv);
        invalidationStore.put(distributionId, list);
        return inv;
    }

    public Invalidation getInvalidation(String distributionId, String invId) {
        getDistribution(distributionId);
        List<Invalidation> list = invalidationStore.get(distributionId).orElse(List.of());
        return list.stream()
                .filter(i -> i.getId().equals(invId))
                .findFirst()
                .orElseThrow(() -> new AwsException("NoSuchInvalidation",
                        "The specified invalidation does not exist.", 404));
    }

    public List<Invalidation> listInvalidations(String distributionId, String marker, int maxItems) {
        getDistribution(distributionId);
        List<Invalidation> all = new ArrayList<>(
                invalidationStore.get(distributionId).orElse(List.of()));
        if (marker != null && !marker.isEmpty()) {
            int idx = 0;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).getId().equals(marker)) {
                    idx = i + 1;
                    break;
                }
            }
            all = all.subList(idx, all.size());
        }
        if (maxItems > 0 && all.size() > maxItems) {
            return all.subList(0, maxItems);
        }
        return all;
    }

    // ── Cache Policies ────────────────────────────────────────────────────────

    public synchronized CachePolicy createCachePolicy(CachePolicy policy) {
        policy.setId(UUID.randomUUID().toString());
        policy.setEtag(UUID.randomUUID().toString());
        policy.setLastModifiedTime(Instant.now());
        cachePolicyStore.put(policy.getId(), policy);
        return policy;
    }

    public CachePolicy getCachePolicy(String id) {
        return cachePolicyStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchCachePolicy", "The specified cache policy does not exist.", 404));
    }

    public synchronized CachePolicy updateCachePolicy(String id, String ifMatch, CachePolicy updated) {
        CachePolicy existing = getCachePolicy(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        updated.setId(id);
        updated.setEtag(UUID.randomUUID().toString());
        updated.setLastModifiedTime(Instant.now());
        cachePolicyStore.put(id, updated);
        return updated;
    }

    public synchronized void deleteCachePolicy(String id, String ifMatch) {
        CachePolicy existing = getCachePolicy(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        cachePolicyStore.delete(id);
    }

    public List<CachePolicy> listCachePolicies(String marker, int maxItems) {
        List<CachePolicy> all = new ArrayList<>(cachePolicyStore.scan(k -> true));
        all.sort((a, b) -> a.getName() != null && b.getName() != null
                ? a.getName().compareTo(b.getName()) : a.getId().compareTo(b.getId()));
        if (marker != null && !marker.isEmpty()) {
            int idx = 0;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).getId().equals(marker)) {
                    idx = i + 1;
                    break;
                }
            }
            all = all.subList(idx, all.size());
        }
        if (maxItems > 0 && all.size() > maxItems) {
            return all.subList(0, maxItems);
        }
        return all;
    }

    // ── Origin Request Policies ───────────────────────────────────────────────

    public synchronized OriginRequestPolicy createOriginRequestPolicy(OriginRequestPolicy policy) {
        policy.setId(UUID.randomUUID().toString());
        policy.setEtag(UUID.randomUUID().toString());
        policy.setLastModifiedTime(Instant.now());
        orpStore.put(policy.getId(), policy);
        return policy;
    }

    public OriginRequestPolicy getOriginRequestPolicy(String id) {
        return orpStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchOriginRequestPolicy",
                        "The specified origin request policy does not exist.", 404));
    }

    public synchronized OriginRequestPolicy updateOriginRequestPolicy(String id, String ifMatch,
                                                                       OriginRequestPolicy updated) {
        OriginRequestPolicy existing = getOriginRequestPolicy(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        updated.setId(id);
        updated.setEtag(UUID.randomUUID().toString());
        updated.setLastModifiedTime(Instant.now());
        orpStore.put(id, updated);
        return updated;
    }

    public synchronized void deleteOriginRequestPolicy(String id, String ifMatch) {
        OriginRequestPolicy existing = getOriginRequestPolicy(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        orpStore.delete(id);
    }

    public List<OriginRequestPolicy> listOriginRequestPolicies(String marker, int maxItems) {
        List<OriginRequestPolicy> all = new ArrayList<>(orpStore.scan(k -> true));
        all.sort((a, b) -> a.getName() != null && b.getName() != null
                ? a.getName().compareTo(b.getName()) : a.getId().compareTo(b.getId()));
        if (marker != null && !marker.isEmpty()) {
            int idx = 0;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).getId().equals(marker)) {
                    idx = i + 1;
                    break;
                }
            }
            all = all.subList(idx, all.size());
        }
        if (maxItems > 0 && all.size() > maxItems) {
            return all.subList(0, maxItems);
        }
        return all;
    }

    // ── Response Headers Policies ─────────────────────────────────────────────

    public synchronized ResponseHeadersPolicy createResponseHeadersPolicy(ResponseHeadersPolicy policy) {
        ResponseHeadersPolicyValidator.validate(policy);
        ensureResponseHeadersPolicyNameAvailable(policy.getName(), null);
        if (rhpStore.scan(k -> true).size() >= MAX_CUSTOM_RESPONSE_HEADERS_POLICIES) {
            throw new AwsException("TooManyResponseHeadersPolicies",
                    "The maximum number of response headers policies has been reached.", 400);
        }
        policy.setId(UUID.randomUUID().toString());
        policy.setEtag(UUID.randomUUID().toString());
        policy.setLastModifiedTime(Instant.now());
        rhpStore.put(policy.getId(), policy);
        return policy;
    }

    public ResponseHeadersPolicy getResponseHeadersPolicy(String id) {
        return rhpStore.get(id)
                .or(() -> java.util.Optional.ofNullable(MANAGED_RESPONSE_HEADERS_POLICIES.get(id)))
                .orElseThrow(() -> new AwsException("NoSuchResponseHeadersPolicy",
                        "The specified response headers policy does not exist.", 404));
    }

    public synchronized ResponseHeadersPolicy updateResponseHeadersPolicy(String id, String ifMatch,
                                                                           ResponseHeadersPolicy updated) {
        ResponseHeadersPolicy existing = getResponseHeadersPolicy(id);
        rejectManagedResponseHeadersPolicyUpdate(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("PreconditionFailed",
                    "The precondition in one or more of the request-header fields evaluated to false.", 412);
        }
        ResponseHeadersPolicyValidator.validate(updated);
        ensureResponseHeadersPolicyNameAvailable(updated.getName(), id);
        updated.setId(id);
        updated.setEtag(UUID.randomUUID().toString());
        updated.setLastModifiedTime(Instant.now());
        rhpStore.put(id, updated);
        return updated;
    }

    public synchronized void deleteResponseHeadersPolicy(String id, String ifMatch) {
        ResponseHeadersPolicy existing = getResponseHeadersPolicy(id);
        rejectManagedResponseHeadersPolicyDelete(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("PreconditionFailed",
                    "The precondition in one or more of the request-header fields evaluated to false.", 412);
        }
        if (isResponseHeadersPolicyInUse(id)) {
            throw new AwsException("ResponseHeadersPolicyInUse",
                    "The response headers policy is attached to one or more distributions.", 409);
        }
        rhpStore.delete(id);
    }

    public List<ResponseHeadersPolicy> listResponseHeadersPolicies(String marker, int maxItems) {
        return listResponseHeadersPolicies(marker, maxItems, null);
    }

    public List<ResponseHeadersPolicy> listResponseHeadersPolicies(
            String marker, int maxItems, String type) {
        String normalizedType = type;
        if (normalizedType != null
                && !"custom".equals(normalizedType)
                && !"managed".equals(normalizedType)) {
            throw new AwsException("InvalidArgument",
                    "Response headers policy Type must be managed or custom.", 400);
        }
        List<ResponseHeadersPolicy> all = new ArrayList<>();
        if (!"managed".equals(normalizedType)) {
            all.addAll(rhpStore.scan(k -> true));
        }
        if (!"custom".equals(normalizedType)) {
            all.addAll(MANAGED_RESPONSE_HEADERS_POLICIES.values());
        }
        all.sort((a, b) -> a.getName() != null && b.getName() != null
                ? a.getName().compareTo(b.getName()) : a.getId().compareTo(b.getId()));
        if (marker != null && !marker.isEmpty()) {
            int idx = -1;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).getId().equals(marker)) {
                    idx = i + 1;
                    break;
                }
            }
            if (idx < 0) {
                throw new AwsException("InvalidArgument",
                        "The specified marker is invalid.", 400);
            }
            all = all.subList(idx, all.size());
        }
        if (maxItems > 0 && all.size() > maxItems) {
            return all.subList(0, maxItems);
        }
        return all;
    }

    static boolean isManagedResponseHeadersPolicy(String id) {
        return MANAGED_RESPONSE_HEADERS_POLICIES.containsKey(id);
    }

    private void ensureResponseHeadersPolicyNameAvailable(String name, String excludedId) {
        boolean duplicateCustom = rhpStore.scan(k -> true).stream()
                .anyMatch(existing -> !existing.getId().equals(excludedId)
                        && name.equals(existing.getName()));
        boolean duplicateManaged = MANAGED_RESPONSE_HEADERS_POLICIES.values().stream()
                .anyMatch(existing -> name.equals(existing.getName()));
        if (duplicateCustom || duplicateManaged) {
            throw new AwsException("ResponseHeadersPolicyAlreadyExists",
                    "A response headers policy with this name already exists.", 409);
        }
    }

    private static void rejectManagedResponseHeadersPolicyUpdate(String id) {
        if (isManagedResponseHeadersPolicy(id)) {
            throw new AwsException("IllegalUpdate",
                    "AWS managed response headers policies cannot be updated.", 400);
        }
    }

    private static void rejectManagedResponseHeadersPolicyDelete(String id) {
        if (isManagedResponseHeadersPolicy(id)) {
            throw new AwsException("IllegalDelete",
                    "AWS managed response headers policies cannot be deleted.", 400);
        }
    }

    private void validateResponseHeadersPolicyReferences(
            DistributionConfig config, String excludedDistributionId) {
        if (config == null) {
            return;
        }
        Set<String> policyIds = new java.util.LinkedHashSet<>();
        if (config.getDefaultCacheBehavior() != null) {
            addResponseHeadersPolicyId(
                    policyIds,
                    config.getDefaultCacheBehavior().getResponseHeadersPolicyId());
        }
        if (config.getCacheBehaviors() != null) {
            config.getCacheBehaviors().forEach(behavior ->
                    addResponseHeadersPolicyId(
                            policyIds, behavior.getResponseHeadersPolicyId()));
        }
        for (String policyId : policyIds) {
            requireResponseHeadersPolicy(policyId);
            long associatedDistributions = distStore.scan(k -> true).stream()
                    .filter(distribution -> excludedDistributionId == null
                            || !excludedDistributionId.equals(distribution.getId()))
                    .filter(distribution -> usesResponseHeadersPolicy(
                            distribution.getConfig(), policyId))
                    .count();
            if (associatedDistributions
                    >= MAX_DISTRIBUTIONS_PER_RESPONSE_HEADERS_POLICY) {
                throw new AwsException(
                        "TooManyDistributionsAssociatedToResponseHeadersPolicy",
                        "The maximum number of distributions have been associated with the "
                                + "specified response headers policy.",
                        400);
            }
        }
    }

    private static void addResponseHeadersPolicyId(
            Set<String> policyIds, String policyId) {
        if (policyId != null && !policyId.isBlank()) {
            policyIds.add(policyId);
        }
    }

    private void requireResponseHeadersPolicy(String policyId) {
        if (policyId != null && !policyId.isBlank()) {
            getResponseHeadersPolicy(policyId);
        }
    }

    private boolean isResponseHeadersPolicyInUse(String id) {
        return distStore.scan(k -> true).stream()
                .map(Distribution::getConfig)
                .anyMatch(config -> usesResponseHeadersPolicy(config, id));
    }

    private static boolean usesResponseHeadersPolicy(
            DistributionConfig config, String id) {
        if (config == null) {
            return false;
        }
        boolean defaultUsesPolicy = config.getDefaultCacheBehavior() != null
                && id.equals(config.getDefaultCacheBehavior().getResponseHeadersPolicyId());
        boolean orderedUsesPolicy = config.getCacheBehaviors() != null
                && config.getCacheBehaviors().stream().anyMatch(behavior ->
                        id.equals(behavior.getResponseHeadersPolicyId()));
        return defaultUsesPolicy || orderedUsesPolicy;
    }

    private static Map<String, ResponseHeadersPolicy> managedResponseHeadersPolicies() {
        Map<String, Object> simpleCors = corsConfig(
                List.of("*"), List.of(), List.of(), List.of(), null);
        Map<String, Object> preflightCors = corsConfig(
                List.of("*"),
                List.of("GET", "HEAD", "PUT", "POST", "PATCH", "DELETE", "OPTIONS"),
                List.of(), List.of("*"), null);
        Map<String, Object> security = securityHeadersConfig();

        Map<String, ResponseHeadersPolicy> policies = new LinkedHashMap<>();
        policies.put(MANAGED_CORS_AND_SECURITY_POLICY_ID, managedResponseHeadersPolicy(
                MANAGED_CORS_AND_SECURITY_POLICY_ID, "Managed-CORS-and-SecurityHeadersPolicy",
                "Allows all origins for simple CORS requests, and adds security headers",
                Map.of("CorsConfig", simpleCors, "SecurityHeadersConfig", security)));
        policies.put(MANAGED_CORS_PREFLIGHT_POLICY_ID, managedResponseHeadersPolicy(
                MANAGED_CORS_PREFLIGHT_POLICY_ID, "Managed-CORS-With-Preflight",
                "Allows all origins for CORS requests, including preflight requests",
                Map.of("CorsConfig", preflightCors)));
        policies.put(MANAGED_CORS_PREFLIGHT_AND_SECURITY_POLICY_ID, managedResponseHeadersPolicy(
                MANAGED_CORS_PREFLIGHT_AND_SECURITY_POLICY_ID,
                "Managed-CORS-with-preflight-and-SecurityHeadersPolicy",
                "Allows all origins for CORS requests, including preflight requests, and adds security headers",
                Map.of("CorsConfig", preflightCors, "SecurityHeadersConfig", security)));
        policies.put(MANAGED_SECURITY_POLICY_ID, managedResponseHeadersPolicy(
                MANAGED_SECURITY_POLICY_ID, "Managed-SecurityHeadersPolicy",
                "Adds a set of security headers to every response",
                Map.of("SecurityHeadersConfig", security)));
        policies.put(MANAGED_SIMPLE_CORS_POLICY_ID, managedResponseHeadersPolicy(
                MANAGED_SIMPLE_CORS_POLICY_ID, "Managed-SimpleCORS",
                "Allows all origins for simple CORS requests",
                Map.of("CorsConfig", simpleCors)));
        return Map.copyOf(policies);
    }

    private static ResponseHeadersPolicy managedResponseHeadersPolicy(
            String id, String name, String comment, Map<String, Object> config) {
        ResponseHeadersPolicy policy = new ResponseHeadersPolicy();
        policy.setId(id);
        policy.setName(name);
        policy.setComment(comment);
        policy.setEtag("E23ZP02F085DFQ");
        policy.setLastModifiedTime(Instant.EPOCH);
        policy.setConfig(config);
        return policy;
    }

    private static Map<String, Object> corsConfig(List<String> origins, List<String> methods,
                                                   List<String> headers, List<String> exposeHeaders,
                                                   Long maxAgeSeconds) {
        Map<String, Object> cors = new LinkedHashMap<>();
        cors.put("AccessControlAllowCredentials", "false");
        cors.put("AccessControlAllowHeaders", headers);
        cors.put("AccessControlAllowMethods", methods);
        cors.put("AccessControlAllowOrigins", origins);
        cors.put("AccessControlExposeHeaders", exposeHeaders);
        if (maxAgeSeconds != null) {
            cors.put("AccessControlMaxAgeSec", Long.toString(maxAgeSeconds));
        }
        cors.put("OriginOverride", "false");
        return Map.copyOf(cors);
    }

    private static Map<String, Object> securityHeadersConfig() {
        Map<String, Object> security = new LinkedHashMap<>();
        security.put("ContentTypeOptions", Map.of("Override", "true"));
        security.put("FrameOptions", Map.of("FrameOption", "SAMEORIGIN", "Override", "false"));
        security.put("ReferrerPolicy", Map.of(
                "ReferrerPolicy", "strict-origin-when-cross-origin", "Override", "false"));
        security.put("StrictTransportSecurity", Map.of(
                "AccessControlMaxAgeSec", "31536000", "Override", "false"));
        security.put("XSSProtection", Map.of(
                "Protection", "true", "ModeBlock", "true", "Override", "false"));
        return Map.copyOf(security);
    }

    // ── Origin Access Control ─────────────────────────────────────────────────

    public synchronized OriginAccessControl createOriginAccessControl(OriginAccessControl oac) {
        validateOriginAccessControl(oac);
        oac.setId(UUID.randomUUID().toString());
        oac.setEtag(UUID.randomUUID().toString());
        oac.setLastModifiedTime(Instant.now());
        oacStore.put(oac.getId(), oac);
        return oac;
    }

    public OriginAccessControl getOriginAccessControl(String id) {
        return oacStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchOriginAccessControl",
                        "The specified origin access control does not exist.", 404));
    }

    public synchronized OriginAccessControl updateOriginAccessControl(String id, String ifMatch,
                                                                       OriginAccessControl updated) {
        OriginAccessControl existing = getOriginAccessControl(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        validateOriginAccessControl(updated);
        updated.setId(id);
        updated.setEtag(UUID.randomUUID().toString());
        updated.setLastModifiedTime(Instant.now());
        oacStore.put(id, updated);
        return updated;
    }

    public synchronized void deleteOriginAccessControl(String id, String ifMatch) {
        OriginAccessControl existing = getOriginAccessControl(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        if (originAccessControlInUse(id)) {
            throw new AwsException("OriginAccessControlInUse",
                    "Cannot delete the origin access control because it's in use by one or more distributions.",
                    409);
        }
        oacStore.delete(id);
    }

    public List<OriginAccessControl> listOriginAccessControls(String marker, int maxItems) {
        List<OriginAccessControl> all = new ArrayList<>(oacStore.scan(k -> true));
        all.sort((a, b) -> a.getName() != null && b.getName() != null
                ? a.getName().compareTo(b.getName()) : a.getId().compareTo(b.getId()));
        if (marker != null && !marker.isEmpty()) {
            int idx = 0;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).getId().equals(marker)) {
                    idx = i + 1;
                    break;
                }
            }
            all = all.subList(idx, all.size());
        }
        if (maxItems > 0 && all.size() > maxItems) {
            return all.subList(0, maxItems);
        }
        return all;
    }

    // ── Origin Access Identity (OAI) ──────────────────────────────────────────

    public synchronized CloudFrontOriginAccessIdentity createCloudFrontOriginAccessIdentity(
            CloudFrontOriginAccessIdentity oai) {
        for (CloudFrontOriginAccessIdentity existing : oaiStore.scan(k -> true)) {
            if (oai.getCallerReference() != null
                    && oai.getCallerReference().equals(existing.getCallerReference())) {
                throw new AwsException("CloudFrontOriginAccessIdentityAlreadyExists",
                        "An origin access identity with the caller reference already exists.", 409);
            }
        }
        oai.setId(generateDistributionId());
        oai.setS3CanonicalUserId(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        oai.setEtag(UUID.randomUUID().toString());
        oaiStore.put(oai.getId(), oai);
        return oai;
    }

    public CloudFrontOriginAccessIdentity getCloudFrontOriginAccessIdentity(String id) {
        return oaiStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchCloudFrontOriginAccessIdentity",
                        "The specified origin access identity does not exist.", 404));
    }

    public synchronized CloudFrontOriginAccessIdentity updateCloudFrontOriginAccessIdentity(
            String id, String ifMatch, CloudFrontOriginAccessIdentity updated) {
        CloudFrontOriginAccessIdentity existing = getCloudFrontOriginAccessIdentity(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        updated.setId(id);
        updated.setS3CanonicalUserId(existing.getS3CanonicalUserId());
        updated.setEtag(UUID.randomUUID().toString());
        oaiStore.put(id, updated);
        return updated;
    }

    public synchronized void deleteCloudFrontOriginAccessIdentity(String id, String ifMatch) {
        CloudFrontOriginAccessIdentity existing = getCloudFrontOriginAccessIdentity(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        if (originAccessIdentityInUse(id)) {
            throw new AwsException("CloudFrontOriginAccessIdentityInUse",
                    "The Origin Access Identity specified is already in use.", 409);
        }
        oaiStore.delete(id);
    }

    public List<CloudFrontOriginAccessIdentity> listCloudFrontOriginAccessIdentities(
            String marker, int maxItems) {
        List<CloudFrontOriginAccessIdentity> all = new ArrayList<>(oaiStore.scan(k -> true));
        if (marker != null && !marker.isEmpty()) {
            int idx = 0;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).getId().equals(marker)) {
                    idx = i + 1;
                    break;
                }
            }
            all = all.subList(idx, all.size());
        }
        if (maxItems > 0 && all.size() > maxItems) {
            return all.subList(0, maxItems);
        }
        return all;
    }

    private static void validateOriginAccessControl(OriginAccessControl oac) {
        if (oac == null) {
            throw new AwsException("InvalidArgument",
                    "The origin access control configuration is required.", 400);
        }
        if (oac.getName() == null || oac.getName().isBlank()) {
            throw new AwsException("InvalidArgument", "The parameter Name is required.", 400);
        }
        if (oac.getName().length() > 64) {
            throw new AwsException("InvalidArgument",
                    "The parameter Name must be 64 characters or fewer.", 400);
        }
        if (!"sigv4".equals(oac.getSigningProtocol())) {
            throw new AwsException("InvalidArgument",
                    "The parameter SigningProtocol must be sigv4.", 400);
        }
        if (oac.getSigningBehavior() == null
                || !OAC_SIGNING_BEHAVIORS.contains(oac.getSigningBehavior())) {
            throw new AwsException("InvalidArgument",
                    "The parameter SigningBehavior must be one of always, never, or no-override.",
                    400);
        }
        if (oac.getOriginAccessControlOriginType() == null
                || !OAC_ORIGIN_TYPES.contains(oac.getOriginAccessControlOriginType())) {
            throw new AwsException("InvalidArgument",
                    "The parameter OriginAccessControlOriginType must be one of s3, mediastore, "
                            + "mediapackagev2, or lambda.",
                    400);
        }
    }

    private boolean originAccessControlInUse(String id) {
        for (Distribution distribution : distStore.scan(k -> true)) {
            DistributionConfig config = distribution.getConfig();
            if (config == null || config.getOrigins() == null) {
                continue;
            }
            for (Origin origin : config.getOrigins()) {
                if (origin != null && id.equals(origin.getOriginAccessControlId())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean originAccessIdentityInUse(String id) {
        for (Distribution distribution : distStore.scan(k -> true)) {
            DistributionConfig config = distribution.getConfig();
            if (config == null || config.getOrigins() == null) {
                continue;
            }
            for (Origin origin : config.getOrigins()) {
                if (origin != null && originAccessIdentityMatches(
                        origin.getS3OriginConfig() != null
                                ? origin.getS3OriginConfig().get("OriginAccessIdentity")
                                : null,
                        id)) {
                    return true;
                }
            }
        }
        for (StreamingDistribution distribution : streamingDistStore.scan(k -> true)) {
            if (originAccessIdentityMatches(distribution.getS3OriginAccessIdentity(), id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean originAccessIdentityMatches(String reference, String id) {
        if (reference == null || reference.isBlank()) {
            return false;
        }
        String normalized = reference.startsWith("/") ? reference.substring(1) : reference;
        return normalized.equals("origin-access-identity/cloudfront/" + id);
    }

    // ── CloudFront Functions ──────────────────────────────────────────────────

    public synchronized CloudFrontFunction createFunction(CloudFrontFunction fn) {
        fn.setStage("DEVELOPMENT");
        fn.setStatus("UNPUBLISHED");
        fn.setEtag(UUID.randomUUID().toString());
        fn.setCreatedTime(Instant.now());
        fn.setLastModifiedTime(Instant.now());
        functionStore.put(fn.getName(), fn);
        return fn;
    }

    public CloudFrontFunction describeFunction(String name, String stage) {
        String effectiveStage = normalizeFunctionStage(stage);
        Optional<CloudFrontFunction> function = functionStore.get(functionKey(name, effectiveStage));
        if (function.isEmpty() && "LIVE".equals(effectiveStage)) {
            // Releases before stage-aware keys stored a published LIVE function under
            // the bare name. Keep that state readable without exposing it as DEVELOPMENT.
            function = functionStore.get(name).filter(fn -> "LIVE".equals(fn.getStage()));
        }
        return function.filter(fn -> effectiveStage.equals(fn.getStage())).orElseThrow(() ->
                new AwsException("NoSuchFunctionExists",
                        "The specified function does not exist.", 404));
    }

    public synchronized CloudFrontFunction updateFunction(String name, String ifMatch,
                                                          CloudFrontFunction updated) {
        CloudFrontFunction existing = describeFunction(name, null);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        updated.setName(name);
        updated.setStage(existing.getStage());
        updated.setStatus(existing.getStatus());
        updated.setEtag(UUID.randomUUID().toString());
        updated.setCreatedTime(existing.getCreatedTime());
        updated.setLastModifiedTime(Instant.now());
        functionStore.put(name, updated);
        return updated;
    }

    public synchronized CloudFrontFunction publishFunction(String name, String ifMatch) {
        CloudFrontFunction development = describeFunction(name, "DEVELOPMENT");
        if (!development.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        CloudFrontFunction live = copyFunction(development);
        live.setStage("LIVE");
        live.setStatus("DEPLOYED");
        live.setEtag(UUID.randomUUID().toString());
        live.setLastModifiedTime(Instant.now());
        functionStore.put(functionKey(name, "LIVE"), live);
        return live;
    }

    public synchronized void deleteFunction(String name, String ifMatch) {
        CloudFrontFunction existing = functionStore.get(name)
                .or(() -> functionStore.get(functionKey(name, "LIVE")))
                .orElseThrow(() -> new AwsException("NoSuchFunctionExists",
                        "The specified function does not exist.", 404));
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        functionStore.delete(name);
        functionStore.delete(functionKey(name, "LIVE"));
    }

    public List<CloudFrontFunction> listFunctions(String stage, String marker, int maxItems) {
        List<CloudFrontFunction> all = new ArrayList<>(functionStore.scan(k -> true));
        if (stage != null && !stage.isEmpty()) {
            String effectiveStage = normalizeFunctionStage(stage);
            all = new ArrayList<>(all.stream()
                    .filter(f -> effectiveStage.equals(f.getStage()))
                    .toList());
        }
        all.sort(Comparator.comparing(CloudFrontFunction::getName)
                .thenComparing(CloudFrontFunction::getStage));
        return paginate(all, marker, maxItems, CloudFrontFunction::getName);
    }

    private static String functionKey(String name, String stage) {
        return switch (stage) {
            case "DEVELOPMENT" -> name;
            case "LIVE" -> name + "::LIVE";
            default -> throw invalidFunctionStage(stage);
        };
    }

    private static String normalizeFunctionStage(String stage) {
        String effectiveStage = stage == null || stage.isEmpty() ? "DEVELOPMENT" : stage;
        if (!"DEVELOPMENT".equals(effectiveStage) && !"LIVE".equals(effectiveStage)) {
            throw invalidFunctionStage(effectiveStage);
        }
        return effectiveStage;
    }

    private static AwsException invalidFunctionStage(String stage) {
        return new AwsException("InvalidArgument",
                "The parameter Stage must be DEVELOPMENT or LIVE: " + stage, 400);
    }

    private static CloudFrontFunction copyFunction(CloudFrontFunction source) {
        CloudFrontFunction copy = new CloudFrontFunction();
        copy.setName(source.getName());
        copy.setStage(source.getStage());
        copy.setStatus(source.getStatus());
        copy.setFunctionCode(source.getFunctionCode());
        copy.setRuntime(source.getRuntime());
        copy.setComment(source.getComment());
        copy.setEtag(source.getEtag());
        copy.setCreatedTime(source.getCreatedTime());
        copy.setLastModifiedTime(source.getLastModifiedTime());
        return copy;
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    public Map<String, String> listTagsForResource(String arn) {
        return tagStore.get(arn).orElse(new LinkedHashMap<>());
    }

    public synchronized void tagResource(String arn, Map<String, String> tags) {
        Map<String, String> existing = new LinkedHashMap<>(tagStore.get(arn).orElse(new LinkedHashMap<>()));
        existing.putAll(tags);
        tagStore.put(arn, existing);
    }

    public synchronized void untagResource(String arn, List<String> tagKeys) {
        Map<String, String> existing = new LinkedHashMap<>(tagStore.get(arn).orElse(new LinkedHashMap<>()));
        tagKeys.forEach(existing::remove);
        tagStore.put(arn, existing);
    }

    // ── Continuous Deployment Policies ───────────────────────────────────────

    public synchronized ContinuousDeploymentPolicy createContinuousDeploymentPolicy(
            ContinuousDeploymentPolicy policy) {
        policy.setId(UUID.randomUUID().toString());
        policy.setLastModifiedTime(Instant.now());
        policy.setEtag(UUID.randomUUID().toString());
        cdpStore.put(policy.getId(), policy);
        return policy;
    }

    public ContinuousDeploymentPolicy getContinuousDeploymentPolicy(String id) {
        return cdpStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchContinuousDeploymentPolicy",
                        "The specified continuous deployment policy does not exist.", 404));
    }

    public synchronized ContinuousDeploymentPolicy updateContinuousDeploymentPolicy(
            String id, String ifMatch, ContinuousDeploymentPolicy updated) {
        ContinuousDeploymentPolicy existing = getContinuousDeploymentPolicy(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        updated.setId(id);
        updated.setLastModifiedTime(Instant.now());
        updated.setEtag(UUID.randomUUID().toString());
        cdpStore.put(id, updated);
        return updated;
    }

    public synchronized void deleteContinuousDeploymentPolicy(String id, String ifMatch) {
        ContinuousDeploymentPolicy existing = getContinuousDeploymentPolicy(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        cdpStore.delete(id);
    }

    public List<ContinuousDeploymentPolicy> listContinuousDeploymentPolicies(String marker, int maxItems) {
        List<ContinuousDeploymentPolicy> all = new ArrayList<>(cdpStore.scan(k -> true));
        all.sort((a, b) -> a.getId().compareTo(b.getId()));
        return paginate(all, marker, maxItems, ContinuousDeploymentPolicy::getId);
    }

    // ── CopyDistribution ──────────────────────────────────────────────────────

    public synchronized Distribution copyDistribution(String primaryDistributionId, String callerReference,
                                                       Map<String, String> tags) {
        Distribution primary = getDistribution(primaryDistributionId);
        Distribution copy = new Distribution();
        copy.setConfig(primary.getConfig());
        if (copy.getConfig() != null) {
            copy.getConfig().setCallerReference(callerReference);
            copy.getConfig().setStaging(true);
        }
        return createDistribution(copy, tags);
    }

    // ── Public Keys ───────────────────────────────────────────────────────────

    public synchronized PublicKey createPublicKey(PublicKey key) {
        validatePublicKey(key);
        boolean duplicateCallerReference =
                publicKeyStore.scan(existing -> true).stream()
                        .anyMatch(existing -> Objects.equals(
                                existing.getCallerReference(),
                                key.getCallerReference()));
        if (duplicateCallerReference) {
            throw new AwsException(
                    "PublicKeyAlreadyExists",
                    "A public key with this caller reference already exists.",
                    409);
        }
        key.setId(UUID.randomUUID().toString());
        key.setCreatedTime(Instant.now());
        key.setEtag(UUID.randomUUID().toString());
        publicKeyStore.put(key.getId(), key);
        return key;
    }

    public PublicKey getPublicKey(String id) {
        return publicKeyStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchPublicKey", "The specified public key does not exist.", 404));
    }

    public synchronized PublicKey updatePublicKey(String id, String ifMatch, PublicKey updated) {
        PublicKey existing = getPublicKey(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException(
                    "PreconditionFailed",
                    "The precondition in one or more request-header fields evaluated to false.",
                    412);
        }
        validatePublicKey(updated);
        if (!Objects.equals(
                    existing.getCallerReference(),
                    updated.getCallerReference())
                || !Objects.equals(existing.getName(), updated.getName())
                || !Objects.equals(
                    existing.getEncodedKey(), updated.getEncodedKey())) {
            throw new AwsException(
                    "CannotChangeImmutablePublicKeyFields",
                    "The caller reference, name, and encoded public key cannot be changed.",
                    400);
        }
        updated.setId(id);
        updated.setCreatedTime(existing.getCreatedTime());
        updated.setEtag(UUID.randomUUID().toString());
        publicKeyStore.put(id, updated);
        return updated;
    }

    public synchronized void deletePublicKey(String id, String ifMatch) {
        PublicKey existing = getPublicKey(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException(
                    "PreconditionFailed",
                    "The precondition in one or more request-header fields evaluated to false.",
                    412);
        }
        if (publicKeyInUse(id)) {
            throw new AwsException(
                    "PublicKeyInUse", "The specified public key is in use.", 409);
        }
        publicKeyStore.delete(id);
    }

    public List<PublicKey> listPublicKeys(String marker, int maxItems) {
        List<PublicKey> all = new ArrayList<>(publicKeyStore.scan(k -> true));
        all.sort((a, b) -> a.getId().compareTo(b.getId()));
        return paginate(all, marker, maxItems, PublicKey::getId);
    }

    /**
     * Resolves the PEM public key for a {@code Key-Pair-Id} used to sign a request, but only when that
     * public key is a member of one of the supplied key groups. Returns {@code null} when the key is
     * unknown or is not a member of any of those groups — i.e. it is not a trusted signer.
     */
    public String trustedPublicKeyPem(String keyPairId, List<String> keyGroupIds) {
        if (keyPairId == null || keyGroupIds == null || keyGroupIds.isEmpty()) {
            return null;
        }
        boolean trusted = false;
        for (String keyGroupId : keyGroupIds) {
            KeyGroup group = keyGroupStore.get(keyGroupId).orElse(null);
            if (group != null && group.getItems() != null && group.getItems().contains(keyPairId)) {
                trusted = true;
                break;
            }
        }
        if (!trusted) {
            return null;
        }
        return publicKeyStore.get(keyPairId).map(PublicKey::getEncodedKey).orElse(null);
    }

    public List<KeyGroup> activeTrustedKeyGroups(DistributionConfig config) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (config != null) {
            DefaultCacheBehavior defaultBehavior = config.getDefaultCacheBehavior();
            if (defaultBehavior != null
                    && defaultBehavior.isTrustedKeyGroupsEnabled()
                    && defaultBehavior.getTrustedKeyGroups() != null) {
                ids.addAll(defaultBehavior.getTrustedKeyGroups());
            }
            if (config.getCacheBehaviors() != null) {
                for (CacheBehavior behavior : config.getCacheBehaviors()) {
                    if (behavior != null
                            && behavior.isTrustedKeyGroupsEnabled()
                            && behavior.getTrustedKeyGroups() != null) {
                        ids.addAll(behavior.getTrustedKeyGroups());
                    }
                }
            }
        }
        return ids.stream().map(this::getKeyGroup).toList();
    }

    // ── Key Groups ────────────────────────────────────────────────────────────

    public synchronized KeyGroup createKeyGroup(KeyGroup group) {
        validateKeyGroup(group);
        validateUniqueKeyGroupName(group.getName(), null);
        group.setId(UUID.randomUUID().toString());
        group.setLastModifiedTime(Instant.now());
        group.setEtag(UUID.randomUUID().toString());
        keyGroupStore.put(group.getId(), group);
        return group;
    }

    public KeyGroup getKeyGroup(String id) {
        return keyGroupStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchResource", "The specified key group does not exist.", 404));
    }

    public synchronized KeyGroup updateKeyGroup(String id, String ifMatch, KeyGroup updated) {
        KeyGroup existing = getKeyGroup(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException(
                    "PreconditionFailed",
                    "The precondition in one or more request-header fields evaluated to false.",
                    412);
        }
        validateKeyGroup(updated);
        validateUniqueKeyGroupName(updated.getName(), id);
        updated.setId(id);
        updated.setLastModifiedTime(Instant.now());
        updated.setEtag(UUID.randomUUID().toString());
        keyGroupStore.put(id, updated);
        return updated;
    }

    public synchronized void deleteKeyGroup(String id, String ifMatch) {
        KeyGroup existing = getKeyGroup(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException(
                    "PreconditionFailed",
                    "The precondition in one or more request-header fields evaluated to false.",
                    412);
        }
        if (keyGroupInUse(id)) {
            throw new AwsException(
                    "ResourceInUse",
                    "Cannot delete this resource because it is in use.",
                    409);
        }
        keyGroupStore.delete(id);
    }

    private void validateUniqueKeyGroupName(String name, String excludedId) {
        boolean duplicate = keyGroupStore.scan(group -> true).stream()
                .anyMatch(group -> !Objects.equals(excludedId, group.getId())
                        && Objects.equals(name, group.getName()));
        if (duplicate) {
            throw new AwsException(
                    "KeyGroupAlreadyExists",
                    "A key group with this name already exists.",
                    409);
        }
    }

    public List<KeyGroup> listKeyGroups(String marker, int maxItems) {
        List<KeyGroup> all = new ArrayList<>(keyGroupStore.scan(k -> true));
        all.sort((a, b) -> a.getId().compareTo(b.getId()));
        return paginate(all, marker, maxItems, KeyGroup::getId);
    }

    private static void validatePublicKey(PublicKey key) {
        if (key == null
                || key.getCallerReference() == null
                || key.getCallerReference().isBlank()
                || key.getName() == null
                || key.getName().isBlank()
                || key.getEncodedKey() == null
                || key.getEncodedKey().isBlank()) {
            throw new AwsException(
                    "InvalidArgument",
                    "CallerReference, Name, and EncodedKey are required.",
                    400);
        }
        if (key.getComment() != null && key.getComment().length() > 128) {
            throw new AwsException(
                    "InvalidArgument", "The comment must be 128 characters or fewer.", 400);
        }
        try {
            CloudFrontSignatureVerifier.parseSupportedPublicKey(
                    key.getEncodedKey());
        } catch (Exception e) {
            throw new AwsException(
                    "InvalidArgument",
                    "The encoded public key must be RSA-2048 or ECDSA P-256 in X.509 PEM format.",
                    400);
        }
    }

    private void validateKeyGroup(KeyGroup group) {
        if (group == null || group.getName() == null || group.getName().isBlank()) {
            throw new AwsException(
                    "InvalidArgument", "The parameter Name is required.", 400);
        }
        if (group.getComment() != null && group.getComment().length() > 128) {
            throw new AwsException(
                    "InvalidArgument", "The comment must be 128 characters or fewer.", 400);
        }
        List<String> items = group.getItems();
        if (items == null || items.isEmpty()) {
            throw new AwsException(
                    "InvalidArgument",
                    "A key group must contain at least one public key.",
                    400);
        }
        if (items.size() > 5) {
            throw new AwsException(
                    "TooManyPublicKeysInKeyGroup",
                    "A key group can contain at most five public keys.",
                    400);
        }
        if (new LinkedHashSet<>(items).size() != items.size()) {
            throw new AwsException(
                    "InvalidArgument",
                    "A public key cannot appear more than once in a key group.",
                    400);
        }
        for (String publicKeyId : items) {
            if (publicKeyId == null
                    || publicKeyId.isBlank()
                    || publicKeyStore.get(publicKeyId).isEmpty()) {
                throw new AwsException(
                        "InvalidArgument",
                        "The specified public key does not exist.",
                        400);
            }
        }
    }

    private void validateTrustedKeyGroups(DistributionConfig config) {
        if (config == null) {
            return;
        }
        DefaultCacheBehavior defaultBehavior = config.getDefaultCacheBehavior();
        if (defaultBehavior != null) {
            validateTrustedKeyGroups(
                    defaultBehavior.isTrustedKeyGroupsEnabled(),
                    defaultBehavior.getTrustedKeyGroups());
        }
        if (config.getCacheBehaviors() != null) {
            for (CacheBehavior behavior : config.getCacheBehaviors()) {
                if (behavior != null) {
                    validateTrustedKeyGroups(
                            behavior.isTrustedKeyGroupsEnabled(),
                            behavior.getTrustedKeyGroups());
                }
            }
        }
    }

    private void validateTrustedKeyGroups(
            boolean enabled, List<String> keyGroupIds) {
        List<String> ids = keyGroupIds != null ? keyGroupIds : List.of();
        if (enabled && ids.isEmpty()) {
            throw new AwsException(
                    "InvalidArgument",
                    "TrustedKeyGroups cannot be enabled without a key group.",
                    400);
        }
        for (String keyGroupId : ids) {
            if (keyGroupId == null
                    || keyGroupId.isBlank()
                    || keyGroupStore.get(keyGroupId).isEmpty()) {
                throw new AwsException(
                        "TrustedKeyGroupDoesNotExist",
                        "The specified key group does not exist.",
                        400);
            }
        }
    }

    private boolean publicKeyInUse(String id) {
        for (KeyGroup group : keyGroupStore.scan(k -> true)) {
            if (group.getItems() != null && group.getItems().contains(id)) {
                return true;
            }
        }
        return false;
    }

    private boolean keyGroupInUse(String id) {
        for (Distribution distribution : distStore.scan(k -> true)) {
            DistributionConfig config = distribution.getConfig();
            if (config == null) {
                continue;
            }
            DefaultCacheBehavior defaultBehavior =
                    config.getDefaultCacheBehavior();
            if (defaultBehavior != null
                    && defaultBehavior.getTrustedKeyGroups() != null
                    && defaultBehavior.getTrustedKeyGroups().contains(id)) {
                return true;
            }
            if (config.getCacheBehaviors() != null) {
                for (CacheBehavior behavior : config.getCacheBehaviors()) {
                    if (behavior != null
                            && behavior.getTrustedKeyGroups() != null
                            && behavior.getTrustedKeyGroups().contains(id)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ── Realtime Log Configs ──────────────────────────────────────────────────

    public synchronized RealtimeLogConfig createRealtimeLogConfig(RealtimeLogConfig cfg) {
        String arn = AwsArnUtils.Arn.of("cloudfront", "", accountId, "realtime-log-config/" + cfg.getName()).toString();
        cfg.setArn(arn);
        realtimeLogConfigStore.put(cfg.getName(), cfg);
        return cfg;
    }

    public RealtimeLogConfig getRealtimeLogConfig(String nameOrArn) {
        if (nameOrArn != null && nameOrArn.startsWith("arn:")) {
            String name = nameOrArn.substring(nameOrArn.lastIndexOf('/') + 1);
            return realtimeLogConfigStore.get(name).orElseThrow(() ->
                    new AwsException("NoSuchRealtimeLogConfig",
                            "The specified realtime log configuration does not exist.", 404));
        }
        return realtimeLogConfigStore.get(nameOrArn).orElseThrow(() ->
                new AwsException("NoSuchRealtimeLogConfig",
                        "The specified realtime log configuration does not exist.", 404));
    }

    public synchronized RealtimeLogConfig updateRealtimeLogConfig(RealtimeLogConfig updated) {
        getRealtimeLogConfig(updated.getName());
        String arn = AwsArnUtils.Arn.of("cloudfront", "", accountId, "realtime-log-config/" + updated.getName()).toString();
        updated.setArn(arn);
        realtimeLogConfigStore.put(updated.getName(), updated);
        return updated;
    }

    public synchronized void deleteRealtimeLogConfig(String nameOrArn) {
        RealtimeLogConfig existing = getRealtimeLogConfig(nameOrArn);
        String name = existing.getName();
        realtimeLogConfigStore.delete(name);
    }

    public List<RealtimeLogConfig> listRealtimeLogConfigs(String marker, int maxItems) {
        List<RealtimeLogConfig> all = new ArrayList<>(realtimeLogConfigStore.scan(k -> true));
        all.sort((a, b) -> a.getName().compareTo(b.getName()));
        return paginate(all, marker, maxItems, RealtimeLogConfig::getName);
    }

    // ── Streaming Distributions ───────────────────────────────────────────────

    public synchronized StreamingDistribution createStreamingDistribution(StreamingDistribution sd) {
        String id = generateDistributionId();
        sd.setId(id);
        sd.setArn(AwsArnUtils.Arn.of("cloudfront", "", accountId, "streaming-distribution/" + id).toString());
        sd.setDomainName(id + "." + domainSuffix);
        sd.setStatus("Deployed");
        sd.setLastModifiedTime(Instant.now());
        sd.setEtag(UUID.randomUUID().toString());
        streamingDistStore.put(id, sd);
        return sd;
    }

    public StreamingDistribution getStreamingDistribution(String id) {
        return streamingDistStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchStreamingDistribution",
                        "The specified streaming distribution does not exist.", 404));
    }

    public synchronized StreamingDistribution updateStreamingDistribution(String id, String ifMatch,
                                                                           StreamingDistribution updated) {
        StreamingDistribution existing = getStreamingDistribution(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        updated.setId(id);
        updated.setArn(existing.getArn());
        updated.setDomainName(existing.getDomainName());
        updated.setStatus("Deployed");
        updated.setLastModifiedTime(Instant.now());
        updated.setEtag(UUID.randomUUID().toString());
        streamingDistStore.put(id, updated);
        return updated;
    }

    public synchronized void deleteStreamingDistribution(String id, String ifMatch) {
        StreamingDistribution existing = getStreamingDistribution(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        if (existing.isEnabled()) {
            throw new AwsException("StreamingDistributionNotDisabled",
                    "The streaming distribution you are trying to delete has not been disabled.", 409);
        }
        streamingDistStore.delete(id);
    }

    // ── Field-Level Encryption Configs ────────────────────────────────────────

    public synchronized FieldLevelEncryptionConfig createFieldLevelEncryptionConfig(
            FieldLevelEncryptionConfig cfg) {
        cfg.setId(UUID.randomUUID().toString());
        cfg.setLastModifiedTime(Instant.now());
        cfg.setEtag(UUID.randomUUID().toString());
        fleConfigStore.put(cfg.getId(), cfg);
        return cfg;
    }

    public FieldLevelEncryptionConfig getFieldLevelEncryptionConfig(String id) {
        return fleConfigStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchFieldLevelEncryptionConfig",
                        "The specified field-level encryption configuration does not exist.", 404));
    }

    public synchronized FieldLevelEncryptionConfig updateFieldLevelEncryptionConfig(
            String id, String ifMatch, FieldLevelEncryptionConfig updated) {
        FieldLevelEncryptionConfig existing = getFieldLevelEncryptionConfig(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        updated.setId(id);
        updated.setLastModifiedTime(Instant.now());
        updated.setEtag(UUID.randomUUID().toString());
        fleConfigStore.put(id, updated);
        return updated;
    }

    public synchronized void deleteFieldLevelEncryptionConfig(String id, String ifMatch) {
        FieldLevelEncryptionConfig existing = getFieldLevelEncryptionConfig(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        fleConfigStore.delete(id);
    }

    public List<FieldLevelEncryptionConfig> listFieldLevelEncryptionConfigs(String marker, int maxItems) {
        List<FieldLevelEncryptionConfig> all = new ArrayList<>(fleConfigStore.scan(k -> true));
        all.sort((a, b) -> a.getId().compareTo(b.getId()));
        return paginate(all, marker, maxItems, FieldLevelEncryptionConfig::getId);
    }

    // ── Field-Level Encryption Profiles ──────────────────────────────────────

    public synchronized FieldLevelEncryptionProfile createFieldLevelEncryptionProfile(
            FieldLevelEncryptionProfile profile) {
        profile.setId(UUID.randomUUID().toString());
        profile.setLastModifiedTime(Instant.now());
        profile.setEtag(UUID.randomUUID().toString());
        fleProfileStore.put(profile.getId(), profile);
        return profile;
    }

    public FieldLevelEncryptionProfile getFieldLevelEncryptionProfile(String id) {
        return fleProfileStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchFieldLevelEncryptionProfile",
                        "The specified field-level encryption profile does not exist.", 404));
    }

    public synchronized FieldLevelEncryptionProfile updateFieldLevelEncryptionProfile(
            String id, String ifMatch, FieldLevelEncryptionProfile updated) {
        FieldLevelEncryptionProfile existing = getFieldLevelEncryptionProfile(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        updated.setId(id);
        updated.setLastModifiedTime(Instant.now());
        updated.setEtag(UUID.randomUUID().toString());
        fleProfileStore.put(id, updated);
        return updated;
    }

    public synchronized void deleteFieldLevelEncryptionProfile(String id, String ifMatch) {
        FieldLevelEncryptionProfile existing = getFieldLevelEncryptionProfile(id);
        if (!existing.getEtag().equals(ifMatch)) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        fleProfileStore.delete(id);
    }

    public List<FieldLevelEncryptionProfile> listFieldLevelEncryptionProfiles(String marker, int maxItems) {
        List<FieldLevelEncryptionProfile> all = new ArrayList<>(fleProfileStore.scan(k -> true));
        all.sort((a, b) -> a.getId().compareTo(b.getId()));
        return paginate(all, marker, maxItems, FieldLevelEncryptionProfile::getId);
    }

    // ── Monitoring Subscriptions ──────────────────────────────────────────────

    public synchronized MonitoringSubscription createMonitoringSubscription(
            String distributionId, MonitoringSubscription subscription) {
        getDistribution(distributionId);
        subscription.setDistributionId(distributionId);
        monitoringStore.put(distributionId, subscription);
        return subscription;
    }

    public MonitoringSubscription getMonitoringSubscription(String distributionId) {
        return monitoringStore.get(distributionId).orElseThrow(() ->
                new AwsException("NoSuchMonitoringSubscription",
                        "A monitoring subscription does not exist for the specified distribution.", 404));
    }

    public synchronized void deleteMonitoringSubscription(String distributionId) {
        getMonitoringSubscription(distributionId);
        monitoringStore.delete(distributionId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public String getAccountId() {
        return accountId;
    }

    private static String generateDistributionId() {
        StringBuilder sb = new StringBuilder("E");
        for (int i = 0; i < 13; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private static String generateInvalidationId() {
        StringBuilder sb = new StringBuilder("I");
        for (int i = 0; i < 13; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private <T> List<T> paginate(List<T> all, String marker, int maxItems,
                                  Function<T, String> keyFn) {
        if (marker != null && !marker.isEmpty()) {
            int idx = 0;
            for (int i = 0; i < all.size(); i++) {
                if (marker.equals(keyFn.apply(all.get(i)))) {
                    idx = i + 1;
                    break;
                }
            }
            all = all.subList(idx, all.size());
        }
        if (maxItems > 0 && all.size() > maxItems) {
            return all.subList(0, maxItems);
        }
        return all;
    }
}
