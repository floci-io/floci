package io.github.hectorvent.floci.services.wafv2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.wafv2.model.IpSet;
import io.github.hectorvent.floci.services.wafv2.model.RegexPatternSet;
import io.github.hectorvent.floci.services.wafv2.model.RuleGroup;
import io.github.hectorvent.floci.services.wafv2.model.WebAcl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WAF v2 management-plane business logic. Resources are identified by the
 * (Scope, Id) pair (storage key {@code scope:id}); Name + Scope find the Id on the
 * duplicate-name check. Updates/deletes enforce the LockToken optimistic-concurrency
 * contract. Recursive rule structures are stored opaquely as raw JSON.
 */
@ApplicationScoped
public class WafV2Service {

    private final StorageBackend<String, WebAcl> webAclStore;
    private final StorageBackend<String, IpSet> ipSetStore;
    private final StorageBackend<String, RegexPatternSet> regexStore;
    private final StorageBackend<String, RuleGroup> ruleGroupStore;
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

    // ──────────────────────────── Web ACL ────────────────────────────

    public WebAcl createWebAcl(WebAcl acl, String scope, String name, String region) {
        validateScope(scope);
        requireName(name);
        if (findByName(webAclStore, scope, name) != null) {
            throw new AwsException("WAFDuplicateItemException",
                    "AWS WAF couldn't perform the operation because some resource "
                            + "in your request is a duplicate of an existing one.", 400);
        }
        acl.setId(UUID.randomUUID().toString());
        acl.setName(name);
        acl.setScope(scope);
        acl.setRegion(region);
        acl.setLockToken(UUID.randomUUID().toString());
        acl.setCreationTime(Instant.now());
        acl.setCapacity(computeCapacity(acl.getRules()));
        acl.setLabelNamespace("awswaf:" + regionResolver.getAccountId() + ":webacl:" + name + ":");
        acl.setArn(buildArn(scope, "webacl", name, acl.getId(), region));
        webAclStore.put(key(scope, acl.getId()), acl);
        return acl;
    }

    public WebAcl getWebAcl(String scope, String id) {
        return require(webAclStore, scope, id);
    }

    public String updateWebAcl(WebAcl changes, String scope, String id, String lockToken) {
        WebAcl existing = require(webAclStore, scope, id);
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
        return rotate(existing, webAclStore, scope);
    }

    public void deleteWebAcl(String scope, String id, String lockToken) {
        WebAcl existing = require(webAclStore, scope, id);
        checkLock(existing.getLockToken(), lockToken);
        boolean associated = associationStore.scan(k -> true).stream()
                .anyMatch(arn -> arn.equals(existing.getArn()));
        if (associated) {
            throw new AwsException("WAFAssociatedItemException",
                    "AWS WAF couldn't perform the operation because your resource is "
                            + "associated with one or more web requests sources.", 400);
        }
        webAclStore.delete(key(scope, id));
    }

    public List<WebAcl> listWebAcls(String scope) {
        validateScope(scope);
        return listByScope(webAclStore, scope);
    }

    // ──────────────────────────── IP set ────────────────────────────

    public IpSet createIpSet(IpSet ipSet, String scope, String name, String region) {
        validateScope(scope);
        requireName(name);
        if (findByName(ipSetStore, scope, name) != null) {
            throw new AwsException("WAFDuplicateItemException", "Duplicate IPSet name: " + name, 400);
        }
        ipSet.setId(UUID.randomUUID().toString());
        ipSet.setName(name);
        ipSet.setScope(scope);
        ipSet.setRegion(region);
        ipSet.setLockToken(UUID.randomUUID().toString());
        ipSet.setArn(buildArn(scope, "ipset", name, ipSet.getId(), region));
        ipSetStore.put(key(scope, ipSet.getId()), ipSet);
        return ipSet;
    }

    public IpSet getIpSet(String scope, String id) {
        return require(ipSetStore, scope, id);
    }

    public String updateIpSet(String scope, String id, String description,
                              List<String> addresses, String lockToken) {
        IpSet existing = require(ipSetStore, scope, id);
        checkLock(existing.getLockToken(), lockToken);
        existing.setDescription(description);
        existing.setAddresses(addresses);
        return rotate(existing, ipSetStore, scope);
    }

    public void deleteIpSet(String scope, String id, String lockToken) {
        IpSet existing = require(ipSetStore, scope, id);
        checkLock(existing.getLockToken(), lockToken);
        ipSetStore.delete(key(scope, id));
    }

    public List<IpSet> listIpSets(String scope) {
        validateScope(scope);
        return listByScope(ipSetStore, scope);
    }

    // ──────────────────────────── Regex pattern set ────────────────────────────

    public RegexPatternSet createRegexPatternSet(RegexPatternSet set, String scope, String name, String region) {
        validateScope(scope);
        requireName(name);
        if (findByName(regexStore, scope, name) != null) {
            throw new AwsException("WAFDuplicateItemException", "Duplicate RegexPatternSet name: " + name, 400);
        }
        set.setId(UUID.randomUUID().toString());
        set.setName(name);
        set.setScope(scope);
        set.setRegion(region);
        set.setLockToken(UUID.randomUUID().toString());
        set.setArn(buildArn(scope, "regexpatternset", name, set.getId(), region));
        regexStore.put(key(scope, set.getId()), set);
        return set;
    }

    public RegexPatternSet getRegexPatternSet(String scope, String id) {
        return require(regexStore, scope, id);
    }

    public String updateRegexPatternSet(String scope, String id, String description,
                                        List<String> regexList, String lockToken) {
        RegexPatternSet existing = require(regexStore, scope, id);
        checkLock(existing.getLockToken(), lockToken);
        existing.setDescription(description);
        existing.setRegularExpressionList(regexList);
        return rotate(existing, regexStore, scope);
    }

    public void deleteRegexPatternSet(String scope, String id, String lockToken) {
        RegexPatternSet existing = require(regexStore, scope, id);
        checkLock(existing.getLockToken(), lockToken);
        regexStore.delete(key(scope, id));
    }

    public List<RegexPatternSet> listRegexPatternSets(String scope) {
        validateScope(scope);
        return listByScope(regexStore, scope);
    }

    // ──────────────────────────── Rule group ────────────────────────────

    public RuleGroup createRuleGroup(RuleGroup group, String scope, String name, String region) {
        validateScope(scope);
        requireName(name);
        if (findByName(ruleGroupStore, scope, name) != null) {
            throw new AwsException("WAFDuplicateItemException", "Duplicate RuleGroup name: " + name, 400);
        }
        group.setId(UUID.randomUUID().toString());
        group.setName(name);
        group.setScope(scope);
        group.setRegion(region);
        group.setLockToken(UUID.randomUUID().toString());
        group.setLabelNamespace("awswaf:" + regionResolver.getAccountId() + ":rulegroup:" + name + ":");
        group.setArn(buildArn(scope, "rulegroup", name, group.getId(), region));
        ruleGroupStore.put(key(scope, group.getId()), group);
        return group;
    }

    public RuleGroup getRuleGroup(String scope, String id) {
        return require(ruleGroupStore, scope, id);
    }

    public String updateRuleGroup(RuleGroup changes, String scope, String id, String lockToken) {
        RuleGroup existing = require(ruleGroupStore, scope, id);
        checkLock(existing.getLockToken(), lockToken);
        existing.setDescription(changes.getDescription());
        existing.setRules(changes.getRules());
        existing.setVisibilityConfig(changes.getVisibilityConfig());
        existing.setCustomResponseBodies(changes.getCustomResponseBodies());
        return rotate(existing, ruleGroupStore, scope);
    }

    public void deleteRuleGroup(String scope, String id, String lockToken) {
        RuleGroup existing = require(ruleGroupStore, scope, id);
        checkLock(existing.getLockToken(), lockToken);
        ruleGroupStore.delete(key(scope, id));
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
        boolean known = webAclStore.scan(k -> true).stream()
                .anyMatch(a -> a.getArn().equals(webAclArn));
        if (!known) {
            throw new AwsException("WAFNonexistentItemException",
                    "AWS WAF couldn't perform the operation because your resource doesn't exist.", 404);
        }
        associationStore.put(resourceArn, webAclArn);
    }

    public void disassociateWebAcl(String resourceArn) {
        associationStore.delete(resourceArn);
    }

    public WebAcl getWebAclForResource(String resourceArn) {
        String webAclArn = associationStore.get(resourceArn).orElse(null);
        if (webAclArn == null) {
            return null;
        }
        return webAclStore.scan(k -> true).stream()
                .filter(a -> a.getArn().equals(webAclArn))
                .findFirst().orElse(null);
    }

    public List<String> listResourcesForWebAcl(String webAclArn) {
        List<String> resources = new ArrayList<>();
        for (String resourceArn : associationStore.keys()) {
            associationStore.get(resourceArn)
                    .filter(webAclArn::equals)
                    .ifPresent(a -> resources.add(resourceArn));
        }
        return resources;
    }

    // ──────────────────────────── Logging ────────────────────────────

    public String putLoggingConfiguration(String resourceArn, String loggingConfigJson) {
        loggingStore.put(resourceArn, loggingConfigJson);
        return loggingConfigJson;
    }

    public String getLoggingConfiguration(String resourceArn) {
        return loggingStore.get(resourceArn).orElseThrow(() -> new AwsException(
                "WAFNonexistentItemException", "No logging configuration for: " + resourceArn, 404));
    }

    public void deleteLoggingConfiguration(String resourceArn) {
        loggingStore.delete(resourceArn);
    }

    public List<String> listLoggingConfigurations() {
        return new ArrayList<>(loggingStore.scan(k -> true));
    }

    // ──────────────────────────── Permission policy ────────────────────────────

    public void putPermissionPolicy(String resourceArn, String policy) {
        policyStore.put(resourceArn, policy);
    }

    public String getPermissionPolicy(String resourceArn) {
        return policyStore.get(resourceArn).orElseThrow(() -> new AwsException(
                "WAFNonexistentItemException", "No policy for: " + resourceArn, 404));
    }

    public void deletePermissionPolicy(String resourceArn) {
        policyStore.delete(resourceArn);
    }

    // ──────────────────────────── Tags ────────────────────────────

    public Map<String, String> listTagsForResource(String resourceArn) {
        return taggable(resourceArn).getTags();
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        Object resource = taggable(resourceArn);
        applyTagged(resource, r -> r.getTags().putAll(tags));
    }

    public void untagResource(String resourceArn, List<String> keys) {
        Object resource = taggable(resourceArn);
        applyTagged(resource, r -> keys.forEach(r.getTags()::remove));
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private interface Tagged { Map<String, String> getTags(); }

    private Tagged taggable(String resourceArn) {
        if (resourceArn == null) {
            throw new AwsException("WAFInvalidParameterException", "ResourceARN is required.", 400);
        }
        WebAcl acl = scanArn(webAclStore, resourceArn);
        if (acl != null) {
            return acl::getTags;
        }
        IpSet ip = scanArn(ipSetStore, resourceArn);
        if (ip != null) {
            return ip::getTags;
        }
        RegexPatternSet rx = scanArn(regexStore, resourceArn);
        if (rx != null) {
            return rx::getTags;
        }
        RuleGroup rg = scanArn(ruleGroupStore, resourceArn);
        if (rg != null) {
            return rg::getTags;
        }
        throw new AwsException("WAFNonexistentItemException", "Resource not found: " + resourceArn, 404);
    }

    private void applyTagged(Object resource, java.util.function.Consumer<Tagged> mutation) {
        if (resource instanceof WebAcl a) {
            mutation.accept(a::getTags);
            webAclStore.put(key(a.getScope(), a.getId()), a);
        } else if (resource instanceof IpSet i) {
            mutation.accept(i::getTags);
            ipSetStore.put(key(i.getScope(), i.getId()), i);
        } else if (resource instanceof RegexPatternSet r) {
            mutation.accept(r::getTags);
            regexStore.put(key(r.getScope(), r.getId()), r);
        } else if (resource instanceof RuleGroup g) {
            mutation.accept(g::getTags);
            ruleGroupStore.put(key(g.getScope(), g.getId()), g);
        }
    }

    private <V> V scanArn(StorageBackend<String, V> store, String arn) {
        return store.scan(k -> true).stream()
                .filter(v -> arn.equals(arnOf(v)))
                .findFirst().orElse(null);
    }

    private String arnOf(Object v) {
        if (v instanceof WebAcl a) return a.getArn();
        if (v instanceof IpSet i) return i.getArn();
        if (v instanceof RegexPatternSet r) return r.getArn();
        if (v instanceof RuleGroup g) return g.getArn();
        return null;
    }

    private <V> V require(StorageBackend<String, V> store, String scope, String id) {
        validateScope(scope);
        if (id == null) {
            throw new AwsException("WAFInvalidParameterException", "Id is required.", 400);
        }
        return store.get(key(scope, id)).orElseThrow(() -> new AwsException(
                "WAFNonexistentItemException",
                "AWS WAF couldn't perform the operation because your resource doesn't exist.", 404));
    }

    private <V> V findByName(StorageBackend<String, V> store, String scope, String name) {
        return store.scan(k -> true).stream()
                .filter(v -> scope.equals(scopeOf(v)) && name.equals(nameOf(v)))
                .findFirst().orElse(null);
    }

    private <V> List<V> listByScope(StorageBackend<String, V> store, String scope) {
        List<V> result = new ArrayList<>();
        for (V v : store.scan(k -> true)) {
            if (scope.equals(scopeOf(v))) {
                result.add(v);
            }
        }
        return result;
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

    @SuppressWarnings("unchecked")
    private <V> String rotate(V resource, StorageBackend<String, V> store, String scope) {
        String next = UUID.randomUUID().toString();
        String id;
        if (resource instanceof WebAcl a) { a.setLockToken(next); id = a.getId(); }
        else if (resource instanceof IpSet i) { i.setLockToken(next); id = i.getId(); }
        else if (resource instanceof RegexPatternSet r) { r.setLockToken(next); id = r.getId(); }
        else { RuleGroup g = (RuleGroup) resource; g.setLockToken(next); id = g.getId(); }
        store.put(key(scope, id), resource);
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

    private String key(String scope, String id) {
        return scope + ":" + id;
    }

    private String buildArn(String scope, String type, String name, String id, String region) {
        String prefix = "CLOUDFRONT".equals(scope) ? "global" : "regional";
        String arnRegion = "CLOUDFRONT".equals(scope) ? "us-east-1" : region;
        return regionResolver.buildArn("wafv2", arnRegion, prefix + "/" + type + "/" + name + "/" + id);
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
