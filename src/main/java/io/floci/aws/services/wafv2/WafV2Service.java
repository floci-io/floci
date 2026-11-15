package io.floci.aws.services.wafv2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsPartition;
import io.floci.aws.core.common.AwsPartitions;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.wafv2.model.IpSet;
import io.floci.aws.services.wafv2.model.RegexPatternSet;
import io.floci.aws.services.wafv2.model.RuleGroup;
import io.floci.aws.services.wafv2.model.WebAcl;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * WAF v2 management-plane business logic. Resources are stored by Region, Scope and Id: WAF v2 is Regional, a
 * {@code CLOUDFRONT}-scoped resource living in the partition's CloudFront Region, which its ARN names. Get, update,
 * and delete operations also validate Name.
 * Updates/deletes enforce the LockToken optimistic-concurrency contract.
 * Recursive rule structures are stored opaquely as raw JSON.
 */
@ApplicationScoped
public class WafV2Service {

    private static final int MAX_TAGS = 50;
    private static final int MAX_TAG_KEY_LENGTH = 128;
    private static final int MAX_TAG_VALUE_LENGTH = 256;
    private static final Pattern TAG_PATTERN = Pattern.compile("^[\\p{L}\\p{Z}\\p{N}_.:/=+\\-@]*$");

    /** Separates a key's Region from the {@code Scope:Id} part. */
    private static final String KEY_SEPARATOR = "::";

    private final AccountAwareStorageBackend<WebAcl> webAclStore;
    private final AccountAwareStorageBackend<IpSet> ipSetStore;
    private final AccountAwareStorageBackend<RegexPatternSet> regexStore;
    private final AccountAwareStorageBackend<RuleGroup> ruleGroupStore;
    private final StorageBackend<String, String> loggingStore;
    private final StorageBackend<String, String> associationStore;
    private final StorageBackend<String, String> policyStore;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public WafV2Service(StorageFactory storageFactory, RegionResolver regionResolver,
                        ObjectMapper objectMapper) {
        this.webAclStore = storageFactory.create("wafv2", "wafv2-webacls.json",
                new TypeReference<Map<String, WebAcl>>() {});
        this.ipSetStore = storageFactory.create("wafv2", "wafv2-ipsets.json",
                new TypeReference<Map<String, IpSet>>() {});
        this.regexStore = storageFactory.create("wafv2", "wafv2-regex-sets.json",
                new TypeReference<Map<String, RegexPatternSet>>() {});
        this.ruleGroupStore = storageFactory.create("wafv2", "wafv2-rule-groups.json",
                new TypeReference<Map<String, RuleGroup>>() {});
        this.loggingStore = storageFactory.create("wafv2", "wafv2-logging.json",
                new TypeReference<Map<String, String>>() {});
        this.associationStore = storageFactory.create("wafv2", "wafv2-associations.json",
                new TypeReference<Map<String, String>>() {});
        this.policyStore = storageFactory.create("wafv2", "wafv2-policies.json",
                new TypeReference<Map<String, String>>() {});
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * Moves the web ACLs, IP sets, regex pattern sets and rule groups stored before they were keyed by Region, in
     * every account, from {@code Scope:Id} onto the key of the Region they live in: the one their ARN names for a
     * {@code REGIONAL} resource, that Region's partition's CloudFront Region for a {@code CLOUDFRONT} one, and the
     * default Region for a resource without an ARN, so each is listed and found again.
     */
    @PostConstruct
    void migrateLegacyKeys() {
        webAclStore.migrateLegacyEntriesInAllAccounts(k -> !k.contains(KEY_SEPARATOR), this::keyOf);
        ipSetStore.migrateLegacyEntriesInAllAccounts(k -> !k.contains(KEY_SEPARATOR), this::keyOf);
        regexStore.migrateLegacyEntriesInAllAccounts(k -> !k.contains(KEY_SEPARATOR), this::keyOf);
        ruleGroupStore.migrateLegacyEntriesInAllAccounts(k -> !k.contains(KEY_SEPARATOR), this::keyOf);
    }


    // ──────────────────────────── Web ACL ────────────────────────────

    public WebAcl createWebAcl(WebAcl acl, String scope, String name, String region) {
        validateScope(scope);
        requireName(name);
        if (findByName(webAclStore, scope, name, region) != null) {
            throw new AwsException("WAFDuplicateItemException",
                    "AWS WAF couldn't perform the operation because some resource "
                            + "in your request is a duplicate of an existing one.", 400);
        }
        validateTags(acl.getTags());
        acl.setId(UUID.randomUUID().toString());
        acl.setName(name);
        acl.setScope(scope);
        acl.setRegion(region);
        acl.setLockToken(UUID.randomUUID().toString());
        acl.setCreationTime(Instant.now());
        acl.setCapacity(computeCapacity(acl.getRules()));
        acl.setLabelNamespace("awswaf:" + regionResolver.getAccountId() + ":webacl:" + name + ":");
        acl.setArn(buildArn(scope, "webacl", name, acl.getId(), region));
        webAclStore.put(key(scope, acl.getId(), region), acl);
        return acl;
    }

    public WebAcl getWebAcl(String scope, String id, String name) {
        return require(webAclStore, scope, id, name);
    }

    public String updateWebAcl(WebAcl changes, String scope, String id, String name, String lockToken) {
        WebAcl existing = require(webAclStore, scope, id, name);
        checkLock(existing.getLockToken(), lockToken);
        existing.setDescription(changes.getDescription());
        existing.setDefaultAction(changes.getDefaultAction());
        existing.setRules(changes.getRules());
        existing.setVisibilityConfig(changes.getVisibilityConfig());
        existing.setCustomResponseBodies(changes.getCustomResponseBodies());
        existing.setCaptchaConfig(changes.getCaptchaConfig());
        existing.setChallengeConfig(changes.getChallengeConfig());
        existing.setTokenDomains(changes.getTokenDomains());
        existing.setAssociationConfig(changes.getAssociationConfig());
        existing.setDataProtectionConfig(changes.getDataProtectionConfig());
        existing.setCapacity(computeCapacity(changes.getRules()));
        return rotate(existing, webAclStore);
    }

    public void deleteWebAcl(String scope, String id, String name, String lockToken) {
        WebAcl existing = require(webAclStore, scope, id, name);
        checkLock(existing.getLockToken(), lockToken);
        boolean associated = associationStore.scan(k -> true).stream()
                .anyMatch(arn -> arn.equals(existing.getArn()));
        if (associated) {
            throw new AwsException("WAFAssociatedItemException",
                    "AWS WAF couldn't perform the operation because your resource is "
                            + "associated with one or more web requests sources.", 400);
        }
        webAclStore.delete(keyOf(existing));
    }

    public List<WebAcl> listWebAcls(String scope) {
        validateScope(scope);
        return listByScope(webAclStore, scope);
    }

    // ──────────────────────────── IP set ────────────────────────────

    public IpSet createIpSet(IpSet ipSet, String scope, String name, String region) {
        validateScope(scope);
        requireName(name);
        if (findByName(ipSetStore, scope, name, region) != null) {
            throw new AwsException("WAFDuplicateItemException", "Duplicate IPSet name: " + name, 400);
        }
        validateTags(ipSet.getTags());
        validateAddresses(ipSet.getAddresses(), ipSet.getIpAddressVersion());
        ipSet.setId(UUID.randomUUID().toString());
        ipSet.setName(name);
        ipSet.setScope(scope);
        ipSet.setRegion(region);
        ipSet.setLockToken(UUID.randomUUID().toString());
        ipSet.setArn(buildArn(scope, "ipset", name, ipSet.getId(), region));
        ipSetStore.put(key(scope, ipSet.getId(), region), ipSet);
        return ipSet;
    }

    public IpSet getIpSet(String scope, String id, String name) {
        return require(ipSetStore, scope, id, name);
    }

    public String updateIpSet(String scope, String id, String description,
                              List<String> addresses, String name, String lockToken) {
        IpSet existing = require(ipSetStore, scope, id, name);
        checkLock(existing.getLockToken(), lockToken);
        validateAddresses(addresses, existing.getIpAddressVersion());
        existing.setDescription(description);
        existing.setAddresses(addresses);
        return rotate(existing, ipSetStore);
    }

    public void deleteIpSet(String scope, String id, String name, String lockToken) {
        IpSet existing = require(ipSetStore, scope, id, name);
        checkLock(existing.getLockToken(), lockToken);
        ipSetStore.delete(keyOf(existing));
    }

    public List<IpSet> listIpSets(String scope) {
        validateScope(scope);
        return listByScope(ipSetStore, scope);
    }

    // ──────────────────────────── Regex pattern set ────────────────────────────

    public RegexPatternSet createRegexPatternSet(RegexPatternSet set, String scope, String name, String region) {
        validateScope(scope);
        requireName(name);
        if (findByName(regexStore, scope, name, region) != null) {
            throw new AwsException("WAFDuplicateItemException", "Duplicate RegexPatternSet name: " + name, 400);
        }
        validateTags(set.getTags());
        set.setId(UUID.randomUUID().toString());
        set.setName(name);
        set.setScope(scope);
        set.setRegion(region);
        set.setLockToken(UUID.randomUUID().toString());
        set.setArn(buildArn(scope, "regexpatternset", name, set.getId(), region));
        regexStore.put(key(scope, set.getId(), region), set);
        return set;
    }

    public RegexPatternSet getRegexPatternSet(String scope, String id, String name) {
        return require(regexStore, scope, id, name);
    }

    public String updateRegexPatternSet(String scope, String id, String description,
                                        List<String> regexList, String name, String lockToken) {
        RegexPatternSet existing = require(regexStore, scope, id, name);
        checkLock(existing.getLockToken(), lockToken);
        existing.setDescription(description);
        existing.setRegularExpressionList(regexList);
        return rotate(existing, regexStore);
    }

    public void deleteRegexPatternSet(String scope, String id, String name, String lockToken) {
        RegexPatternSet existing = require(regexStore, scope, id, name);
        checkLock(existing.getLockToken(), lockToken);
        regexStore.delete(keyOf(existing));
    }

    public List<RegexPatternSet> listRegexPatternSets(String scope) {
        validateScope(scope);
        return listByScope(regexStore, scope);
    }

    // ──────────────────────────── Rule group ────────────────────────────

    public RuleGroup createRuleGroup(RuleGroup group, String scope, String name, String region) {
        validateScope(scope);
        requireName(name);
        if (findByName(ruleGroupStore, scope, name, region) != null) {
            throw new AwsException("WAFDuplicateItemException", "Duplicate RuleGroup name: " + name, 400);
        }
        validateTags(group.getTags());
        group.setId(UUID.randomUUID().toString());
        group.setName(name);
        group.setScope(scope);
        group.setRegion(region);
        group.setLockToken(UUID.randomUUID().toString());
        group.setLabelNamespace("awswaf:" + regionResolver.getAccountId() + ":rulegroup:" + name + ":");
        group.setArn(buildArn(scope, "rulegroup", name, group.getId(), region));
        ruleGroupStore.put(key(scope, group.getId(), region), group);
        return group;
    }

    public RuleGroup getRuleGroup(String scope, String id, String name) {
        return require(ruleGroupStore, scope, id, name);
    }

    public String updateRuleGroup(RuleGroup changes, String scope, String id, String name, String lockToken) {
        RuleGroup existing = require(ruleGroupStore, scope, id, name);
        checkLock(existing.getLockToken(), lockToken);
        existing.setDescription(changes.getDescription());
        existing.setRules(changes.getRules());
        existing.setVisibilityConfig(changes.getVisibilityConfig());
        existing.setCustomResponseBodies(changes.getCustomResponseBodies());
        return rotate(existing, ruleGroupStore);
    }

    public void deleteRuleGroup(String scope, String id, String name, String lockToken) {
        RuleGroup existing = require(ruleGroupStore, scope, id, name);
        checkLock(existing.getLockToken(), lockToken);
        ruleGroupStore.delete(keyOf(existing));
    }

    public List<RuleGroup> listRuleGroups(String scope) {
        validateScope(scope);
        return listByScope(ruleGroupStore, scope);
    }

    public long checkCapacity(String rulesJson) {
        return computeCapacity(rulesJson);
    }

    // ──────────────────────────── Association ────────────────────────────

    public void associateWebAcl(String webAclArn, String resourceArn) {
        if (webAclArn == null || scanArn(webAclStore, webAclArn) == null) {
            throw nonexistentItem();
        }
        associationStore.put(resourceArn, webAclArn);
    }

    /** Removes the association of {@code resourceArn}, if its web ACL is one the request's Region reaches. */
    public void disassociateWebAcl(String resourceArn) {
        if (resourceArn != null && associationStore.get(resourceArn).filter(this::reachable).isPresent()) {
            associationStore.delete(resourceArn);
        }
    }

    public WebAcl getWebAclForResource(String resourceArn) {
        String webAclArn = associationStore.get(resourceArn).orElse(null);
        if (webAclArn == null) {
            return null;
        }
        return scanArn(webAclStore, webAclArn);
    }

    public List<String> listResourcesForWebAcl(String webAclArn) {
        List<String> resources = new ArrayList<>();
        if (!reachable(webAclArn)) {
            return resources;
        }
        for (String resourceArn : associationStore.keys()) {
            associationStore.get(resourceArn)
                    .filter(webAclArn::equals)
                    .ifPresent(a -> resources.add(resourceArn));
        }
        return resources;
    }

    // ──────────────────────────── Logging ────────────────────────────

    public String putLoggingConfiguration(String resourceArn, String loggingConfigJson) {
        if (resourceArn == null || scanArn(webAclStore, resourceArn) == null) {
            throw nonexistentItem();
        }
        loggingStore.put(resourceArn, loggingConfigJson);
        return loggingConfigJson;
    }

    public String getLoggingConfiguration(String resourceArn) {
        return loggingStore.get(resourceArn).filter(stored -> reachable(resourceArn)).orElseThrow(() -> new AwsException(
                "WAFNonexistentItemException", "No logging configuration for: " + resourceArn, 400));
    }

    public void deleteLoggingConfiguration(String resourceArn) {
        if (reachable(resourceArn)) {
            loggingStore.delete(resourceArn);
        }
    }

    /** The logging configurations of the web ACLs of {@code scope} in the request's Region. */
    public List<String> listLoggingConfigurations(String scope) {
        validateScope(scope);
        String prefix = "CLOUDFRONT".equals(scope) ? "global/" : "regional/";
        return new ArrayList<>(loggingStore.scan(arn -> reachableArn(arn)
                .filter(parsed -> parsed.resource().startsWith(prefix)).isPresent()));
    }

    // ──────────────────────────── Permission policy ────────────────────────────

    /** Puts the policy of a rule group the request's Region reaches: AWS shares rule groups this way. */
    public void putPermissionPolicy(String resourceArn, String policy) {
        if (resourceArn == null || scanArn(ruleGroupStore, resourceArn) == null) {
            throw nonexistentItem();
        }
        policyStore.put(resourceArn, policy);
    }

    public String getPermissionPolicy(String resourceArn) {
        return policyStore.get(resourceArn).filter(stored -> reachable(resourceArn)).orElseThrow(() -> new AwsException(
                "WAFNonexistentItemException", "No policy for: " + resourceArn, 400));
    }

    public void deletePermissionPolicy(String resourceArn) {
        if (reachable(resourceArn)) {
            policyStore.delete(resourceArn);
        }
    }

    // ──────────────────────────── Tags ────────────────────────────

    public Map<String, String> listTagsForResource(String resourceArn) {
        return tagsOf(taggable(resourceArn));
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        validateTags(tags);
        Object resource = taggable(resourceArn);
        Map<String, String> current = tagsOf(resource);
        Map<String, String> merged = new LinkedHashMap<>(current);
        merged.putAll(tags);
        requireTagCount(merged.size());
        current.putAll(tags);
        persistTagged(resource);
    }

    public void untagResource(String resourceArn, List<String> keys) {
        keys.forEach(key -> requireValidTagKey(key, "TAG_KEYS"));
        Object resource = taggable(resourceArn);
        keys.forEach(tagsOf(resource)::remove);
        persistTagged(resource);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private Object taggable(String resourceArn) {
        if (resourceArn == null) {
            throw new AwsException("WAFInvalidParameterException", "ResourceARN is required.", 400);
        }
        WebAcl acl = scanArn(webAclStore, resourceArn);
        if (acl != null) {
            return acl;
        }
        IpSet ip = scanArn(ipSetStore, resourceArn);
        if (ip != null) {
            return ip;
        }
        RegexPatternSet rx = scanArn(regexStore, resourceArn);
        if (rx != null) {
            return rx;
        }
        RuleGroup rg = scanArn(ruleGroupStore, resourceArn);
        if (rg != null) {
            return rg;
        }
        throw new AwsException("WAFNonexistentItemException", "Resource not found: " + resourceArn, 400);
    }

    private Map<String, String> tagsOf(Object resource) {
        if (resource instanceof WebAcl a) {
            return a.getTags();
        } else if (resource instanceof IpSet i) {
            return i.getTags();
        } else if (resource instanceof RegexPatternSet r) {
            return r.getTags();
        } else if (resource instanceof RuleGroup g) {
            return g.getTags();
        }
        throw new AwsException("WAFNonexistentItemException", "Resource not found.", 400);
    }

    private void persistTagged(Object resource) {
        if (resource instanceof WebAcl a) {
            webAclStore.put(keyOf(a), a);
        } else if (resource instanceof IpSet i) {
            ipSetStore.put(keyOf(i), i);
        } else if (resource instanceof RegexPatternSet r) {
            regexStore.put(keyOf(r), r);
        } else if (resource instanceof RuleGroup g) {
            ruleGroupStore.put(keyOf(g), g);
        }
    }

    /** The resource {@code arn} names, if the request's Region reaches it ({@link #reachableArn}). */
    private <V> V scanArn(StorageBackend<String, V> store, String arn) {
        return reachableArn(arn).map(parsed -> {
            String prefix = parsed.region() + KEY_SEPARATOR;
            return store.scan(k -> k.startsWith(prefix)).stream()
                    .filter(v -> arn.equals(arnOf(v)))
                    .findFirst().orElse(null);
        }).orElse(null);
    }

    private boolean reachable(String arn) {
        return reachableArn(arn).isPresent();
    }

    /**
     * {@code arn} parsed, when a request in the request's Region reaches the resource it names: a regional resource
     * of that Region, or a global ({@code CLOUDFRONT}) one of its partition's CloudFront Region. Another Region's
     * resource does not exist there, as on AWS.
     */
    private Optional<AwsArnUtils.Arn> reachableArn(String arn) {
        if (!AwsArnUtils.isArnFor(arn, "wafv2")) {
            return Optional.empty();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String scope = parsed.resource().startsWith("global/") ? "CLOUDFRONT" : "REGIONAL";
        try {
            String home = homeRegion(scope, regionResolver.getRegion());
            return parsed.region().equals(home) && parsed.partition().equals(AwsRegions.partitionFor(home))
                    ? Optional.of(parsed) : Optional.empty();
        } catch (AwsException e) {
            return Optional.empty();
        }
    }

    private String arnOf(Object v) {
        if (v instanceof WebAcl a) return a.getArn();
        if (v instanceof IpSet i) return i.getArn();
        if (v instanceof RegexPatternSet r) return r.getArn();
        if (v instanceof RuleGroup g) return g.getArn();
        return null;
    }

    private <V> V require(StorageBackend<String, V> store, String scope, String id, String name) {
        validateScope(scope);
        if (id == null) {
            throw new AwsException("WAFInvalidParameterException", "Id is required.", 400);
        }
        if (name == null || name.isBlank()) {
            throw new AwsException("WAFInvalidParameterException", "Name is required.", 400);
        }
        V resource = store.get(key(scope, id, regionResolver.getRegion())).orElseThrow(WafV2Service::nonexistentItem);
        if (!name.equals(nameOf(resource))) {
            throw nonexistentItem();
        }
        return resource;
    }

    private static AwsException nonexistentItem() {
        return new AwsException("WAFNonexistentItemException",
                "AWS WAF couldn't perform the operation because your resource doesn't exist.", 400);
    }

    /** The resource of {@code scope} named {@code name} in {@code region}: a name is unique within one Region. */
    private <V> V findByName(StorageBackend<String, V> store, String scope, String name, String region) {
        String prefix = homeRegion(scope, region) + KEY_SEPARATOR + scope + ":";
        return store.scan(k -> k.startsWith(prefix)).stream()
                .filter(v -> name.equals(nameOf(v)))
                .findFirst().orElse(null);
    }

    /** The resources of {@code scope} in the request's Region. */
    private <V> List<V> listByScope(StorageBackend<String, V> store, String scope) {
        String prefix = homeRegion(scope, regionResolver.getRegion()) + KEY_SEPARATOR + scope + ":";
        return new ArrayList<>(store.scan(k -> k.startsWith(prefix)));
    }

    private String scopeOf(Object v) {
        if (v instanceof WebAcl a) return a.getScope();
        if (v instanceof IpSet i) return i.getScope();
        if (v instanceof RegexPatternSet r) return r.getScope();
        if (v instanceof RuleGroup g) return g.getScope();
        return null;
    }

    private String nameOf(Object v) {
        if (v instanceof WebAcl a) return a.getName();
        if (v instanceof IpSet i) return i.getName();
        if (v instanceof RegexPatternSet r) return r.getName();
        if (v instanceof RuleGroup g) return g.getName();
        return null;
    }

    private <V> String rotate(V resource, StorageBackend<String, V> store) {
        String next = UUID.randomUUID().toString();
        if (resource instanceof WebAcl a) { a.setLockToken(next); }
        else if (resource instanceof IpSet i) { i.setLockToken(next); }
        else if (resource instanceof RegexPatternSet r) { r.setLockToken(next); }
        else { RuleGroup g = (RuleGroup) resource; g.setLockToken(next); }
        store.put(keyOf(resource), resource);
        return next;
    }

    private void checkLock(String current, String supplied) {
        if (supplied == null || !supplied.equals(current)) {
            throw new AwsException("WAFOptimisticLockException",
                    "AWS WAF couldn't save your changes because someone changed the resource "
                            + "after you started to edit it. Reapply your changes.", 400);
        }
    }

    private void validateScope(String scope) {
        if (!"CLOUDFRONT".equals(scope) && !"REGIONAL".equals(scope)) {
            throw new AwsException("WAFInvalidParameterException",
                    "Scope must be CLOUDFRONT or REGIONAL.", 400);
        }
    }

    private void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("WAFInvalidParameterException", "Name is required.", 400);
        }
    }

    /**
     * Enforces the WAFv2 tag contract: at most {@value #MAX_TAGS} tags per resource, keys of
     * 1-{@value #MAX_TAG_KEY_LENGTH} characters, values of up to {@value #MAX_TAG_VALUE_LENGTH}
     * characters, both restricted to letters, numbers, spaces and {@code _ . : / = + - @}.
     */
    private void validateTags(Map<String, String> tags) {
        if (tags == null) {
            return;
        }
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            requireValidTagKey(tag.getKey(), "TAGS");
            String value = tag.getValue();
            if (value != null
                    && (value.length() > MAX_TAG_VALUE_LENGTH || !TAG_PATTERN.matcher(value).matches())) {
                throw invalidParameter("TAGS", tag.getKey(), "ILLEGAL_ARGUMENT");
            }
        }
        requireTagCount(tags.size());
    }

    private void requireValidTagKey(String key, String field) {
        if (key == null || key.isEmpty() || key.length() > MAX_TAG_KEY_LENGTH
                || !TAG_PATTERN.matcher(key).matches()) {
            throw invalidParameter(field, key == null ? "" : key, "INVALID_TAG_KEY");
        }
    }

    private void requireTagCount(int count) {
        if (count > MAX_TAGS) {
            throw new AwsException("WAFLimitsExceededException",
                    "AWS WAF couldn't perform the operation because you exceeded your resource limit. "
                            + "A resource can have at most " + MAX_TAGS + " tags.", 400);
        }
    }

    /**
     * The single IPV4/IPV6 rule for a set's {@code IPAddressVersion}, also called by
     * {@code WafV2CfnProvisioner}: an in-place update never sends the member to
     * {@code UpdateIPSet}, so the provisioner would otherwise accept a value WAFv2 never sees.
     */
    public static void requireIpAddressVersion(String ipAddressVersion) {
        if (!"IPV4".equals(ipAddressVersion) && !"IPV6".equals(ipAddressVersion)) {
            throw invalidParameter("IP_ADDRESS_VERSION", ipAddressVersion, "must be IPV4 or IPV6.");
        }
    }

    /**
     * Validates that every entry in an IPSet's {@code Addresses} is a CIDR block matching
     * the declared {@code IPAddressVersion}, per the {@code CreateIPSet}/{@code UpdateIPSet}
     * contract: a bare IP address with no prefix is rejected, as is a prefix outside
     * 1-32 for IPv4 or 1-128 for IPv6 (AWS WAF supports all CIDR ranges except {@code /0}).
     */
    private void validateAddresses(List<String> addresses, String ipAddressVersion) {
        requireIpAddressVersion(ipAddressVersion);
        boolean ipv6 = "IPV6".equals(ipAddressVersion);
        if (addresses == null || addresses.isEmpty()) {
            return;
        }
        for (String address : addresses) {
            validateCidrAddress(address, ipv6);
        }
    }

    private void validateCidrAddress(String address, boolean ipv6) {
        int maxPrefix = ipv6 ? 128 : 32;
        int slash = address == null ? -1 : address.lastIndexOf('/');
        if (slash < 1 || slash == address.length() - 1) {
            throw invalidAddress(address,
                    "must be specified in CIDR notation, e.g. \"203.0.113.10/32\" (a bare IP address is not valid).");
        }
        String addressPart = address.substring(0, slash);
        int prefixLength;
        try {
            prefixLength = Integer.parseInt(address.substring(slash + 1));
        } catch (NumberFormatException e) {
            throw invalidAddress(address, "the CIDR prefix length must be an integer.");
        }
        InetAddress parsed;
        try {
            parsed = InetAddress.ofLiteral(addressPart);
        } catch (IllegalArgumentException e) {
            throw invalidAddress(address, "the address portion is not a valid IP literal.");
        }
        boolean isIpv4Address = parsed instanceof Inet4Address;
        if (ipv6 == isIpv4Address) {
            throw invalidAddress(address,
                    "the address does not match the IPSet's IPAddressVersion (" + (ipv6 ? "IPV6" : "IPV4") + ").");
        }
        if (prefixLength < 1 || prefixLength > maxPrefix) {
            throw invalidAddress(address,
                    "the CIDR prefix length must be between 1 and " + maxPrefix + ".");
        }
    }

    private AwsException invalidAddress(String address, String reason) {
        return invalidParameter("IP_ADDRESS", address, reason);
    }

    /**
     * Builds a {@code WAFInvalidParameterException} with the Field/Parameter/Reason triple
     * carried as structured extendedData, matching the real WAFV2 {@code ParameterExceptionField}
     * enum (e.g. {@code IP_ADDRESS}, {@code IP_ADDRESS_VERSION}) rather than a free-text message.
     */
    private static AwsException invalidParameter(String field, String parameter, String reason) {
        Map<String, Object> extendedData = new LinkedHashMap<>();
        extendedData.put("Field", field);
        extendedData.put("Parameter", parameter);
        extendedData.put("Reason", reason);
        return new AwsException("WAFInvalidParameterException",
                "Field: " + field + ", Parameter: " + parameter + ", Reason: " + reason, 400, extendedData);
    }

    /**
     * The Region a resource of {@code scope} lives in for a request in {@code region}: the request's for
     * {@code REGIONAL}, the partition's CloudFront Region for {@code CLOUDFRONT}, the Region its ARN names.
     */
    private String homeRegion(String scope, String region) {
        return "CLOUDFRONT".equals(scope) ? cloudFrontScopeRegion(region) : region;
    }

    private String key(String scope, String id, String region) {
        return homeRegion(scope, region) + KEY_SEPARATOR + scope + ":" + id;
    }

    /**
     * The key a stored resource belongs under: {@link #key} for the Region its ARN names, or, when it has none, the
     * Region it records it was created in, or else the default Region. Null for a {@code CLOUDFRONT} resource of a
     * partition without CloudFront, which no request reaches.
     */
    private String keyOf(Object resource) {
        String arn = arnOf(resource);
        String region = AwsArnUtils.isArnFor(arn, "wafv2") ? AwsArnUtils.parse(arn).region()
                : Optional.ofNullable(regionOf(resource)).orElseGet(regionResolver::getDefaultRegion);
        try {
            return key(scopeOf(resource), idOf(resource), region);
        } catch (AwsException e) {
            return null;
        }
    }

    private String regionOf(Object v) {
        if (v instanceof WebAcl a) return a.getRegion();
        if (v instanceof IpSet i) return i.getRegion();
        if (v instanceof RegexPatternSet r) return r.getRegion();
        if (v instanceof RuleGroup g) return g.getRegion();
        return null;
    }

    private String idOf(Object v) {
        if (v instanceof WebAcl a) return a.getId();
        if (v instanceof IpSet i) return i.getId();
        if (v instanceof RegexPatternSet r) return r.getId();
        if (v instanceof RuleGroup g) return g.getId();
        return null;
    }

    private String buildArn(String scope, String type, String name, String id, String region) {
        String prefix = "CLOUDFRONT".equals(scope) ? "global" : "regional";
        String arnRegion = "CLOUDFRONT".equals(scope) ? cloudFrontScopeRegion(region) : region;
        return regionResolver.buildArn("wafv2", arnRegion, prefix + "/" + type + "/" + name + "/" + id);
    }

    /** A CLOUDFRONT-scoped resource lives in the partition's implicit global region (us-east-1, cn-northwest-1). */
    private String cloudFrontScopeRegion(String region) {
        return cloudFrontPartition(region).implicitGlobalRegion();
    }

    /**
     * Refuses the CLOUDFRONT scope for a request sent to a partition without CloudFront, for every
     * operation, so a List or Get answers the same error a Create does.
     */
    public void requireCloudFrontScope(String region) {
        cloudFrontPartition(region);
    }

    /**
     * The partition of {@code region}, provided it has CloudFront: GovCloud and the ISO partitions
     * do not, so the CLOUDFRONT scope is invalid there.
     */
    private AwsPartition cloudFrontPartition(String region) {
        AwsPartition partition = AwsPartitions.forRegionOrCommercial(region);
        if (!partition.offers("cloudfront")) {
            throw invalidParameter("SCOPE_VALUE", "CLOUDFRONT",
                    "The CLOUDFRONT scope is not available in partition " + partition.id() + ".");
        }
        return partition;
    }

    /**
     * Deterministic WCU approximation: 1 base unit per rule plus a small premium for
     * recursive logical statements. Real AWS computes exact WCUs per statement type;
     * Phase 1 only needs a stable, positive number.
     */
    private long computeCapacity(String rulesJson) {
        if (rulesJson == null || rulesJson.isBlank()) {
            return 0L;
        }
        try {
            JsonNode rules = objectMapper.readTree(rulesJson);
            if (!rules.isArray()) {
                return 1L;
            }
            long total = 0L;
            for (JsonNode rule : rules) {
                total += 1 + countStatements(rule.path("Statement"));
            }
            return Math.max(1L, total);
        } catch (Exception e) {
            return 1L;
        }
    }

    private long countStatements(JsonNode statement) {
        if (statement == null || !statement.isObject()) {
            return 0L;
        }
        long count = 0L;
        for (String logical : List.of("AndStatement", "OrStatement", "NotStatement")) {
            JsonNode nested = statement.path(logical).path("Statements");
            if (nested.isArray()) {
                for (JsonNode s : nested) {
                    count += 1 + countStatements(s);
                }
            }
            JsonNode single = statement.path(logical).path("Statement");
            if (single.isObject()) {
                count += 1 + countStatements(single);
            }
        }
        return count;
    }
}
