package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Identity sending-authorization policies (the {@code policyStore}), extracted from
 * {@link SesService} as the fourth step of the store-based domain split.
 *
 * <p>New facet vs the earlier steps: a two-way relationship with the (not-yet-extracted) Identity
 * domain. The facade keeps the identity-existence check ({@code requireIdentityExists} reads the
 * identity store) and runs it before delegating the v2 mutators here. In the other direction, the
 * facade's {@code deleteIdentity} calls {@link #deletePoliciesForIdentity} so an identity's policies
 * are purged with it — the monolith depending on the extracted service, which is how the pieces will
 * fit once Identity is itself extracted.
 */
@ApplicationScoped
public class SesPolicyService {

    private static final Logger LOG = Logger.getLogger(SesPolicyService.class);

    static final int MAX_POLICIES_PER_IDENTITY = 20;
    private static final Pattern POLICY_NAME_CHARS = Pattern.compile("[A-Za-z0-9_-]+");

    private final StorageBackend<String, String> policyStore;
    // Serializes the per-identity policy count check-then-put so concurrent creates can't both pass.
    private final Object policyMutationLock = new Object();
    private final ObjectMapper objectMapper;

    @Inject
    public SesPolicyService(StorageFactory storageFactory, ObjectMapper objectMapper) {
        this.policyStore = storageFactory.create("ses", "ses-identity-policies.json",
                new TypeReference<Map<String, String>>() {});
        this.objectMapper = objectMapper;
    }

    SesPolicyService(StorageBackend<String, String> policyStore, ObjectMapper objectMapper) {
        this.policyStore = policyStore;
        this.objectMapper = objectMapper;
    }

    // v1 PutIdentityPolicy: upsert (create or overwrite); v1 does not require the identity to exist.
    public void putIdentityPolicy(String identity, String policyName, String policy, String region) {
        validatePolicyName(policyName);
        String normalized = normalizePolicy(policy);
        String key = policyKey(region, identity, policyName);
        synchronized (policyMutationLock) {
            if (policyStore.get(key).isEmpty()) {
                enforcePolicyLimit(region, identity, false);
            }
            policyStore.put(key, normalized);
        }
        LOG.infov("SES PutIdentityPolicy: {0} on {1} (region {2})", policyName, identity, region);
    }

    // v2 CreateEmailIdentityPolicy: fails if the name already exists. The identity-existence check is
    // done by the SesService facade before this is called.
    public void createEmailIdentityPolicy(String identity, String policyName, String policy, String region) {
        validatePolicyName(policyName);
        String normalized = normalizePolicy(policy);
        String key = policyKey(region, identity, policyName);
        synchronized (policyMutationLock) {
            if (policyStore.get(key).isPresent()) {
                throw new AwsException("AlreadyExistsException",
                        "Policy <" + policyName + "> already exists", 400);
            }
            enforcePolicyLimit(region, identity, true);
            policyStore.put(key, normalized);
        }
        LOG.infov("SES v2 CreateEmailIdentityPolicy: {0} on {1} (region {2})", policyName, identity, region);
    }

    // v2 UpdateEmailIdentityPolicy: fails if the name is missing. Identity check done by the facade.
    public void updateEmailIdentityPolicy(String identity, String policyName, String policy, String region) {
        validatePolicyName(policyName);
        String normalized = normalizePolicy(policy);
        String key = policyKey(region, identity, policyName);
        synchronized (policyMutationLock) {
            if (policyStore.get(key).isEmpty()) {
                throw policyNotFound(policyName);
            }
            policyStore.put(key, normalized);
        }
        LOG.infov("SES v2 UpdateEmailIdentityPolicy: {0} on {1} (region {2})", policyName, identity, region);
    }

    // v1 GetIdentityPolicies: requested names only, missing silently omitted; no identity check.
    public Map<String, String> getIdentityPolicies(String identity, List<String> policyNames, String region) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : policyNames) {
            policyStore.get(policyKey(region, identity, name)).ifPresent(doc -> out.put(name, doc));
        }
        return out;
    }

    // All policies for the identity, keyed by policy name.
    public Map<String, String> listAllPolicies(String identity, String region) {
        String prefix = policyPrefix(region, identity);
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : policyStore.keys()) {
            if (key.startsWith(prefix)) {
                policyStore.get(key).ifPresent(doc -> out.put(key.substring(prefix.length()), doc));
            }
        }
        return out;
    }

    // v1 ListIdentityPolicies: policy names (sorted); no identity check.
    public List<String> listIdentityPolicyNames(String identity, String region) {
        return listAllPolicies(identity, region).keySet().stream().sorted().toList();
    }

    // v2 DeleteEmailIdentityPolicy: NotFound if the policy is missing. Identity check done by the facade.
    public void deleteEmailIdentityPolicy(String identity, String policyName, String region) {
        String key = policyKey(region, identity, policyName);
        synchronized (policyMutationLock) {
            if (policyStore.get(key).isEmpty()) {
                throw policyNotFound(policyName);
            }
            policyStore.delete(key);
        }
        LOG.infov("SES v2 DeleteEmailIdentityPolicy: {0} on {1} (region {2})", policyName, identity, region);
    }

    // v1 DeleteIdentityPolicy: idempotent; no identity check, no error on a missing policy.
    public void deleteIdentityPolicy(String identity, String policyName, String region) {
        policyStore.delete(policyKey(region, identity, policyName));
        LOG.infov("SES DeleteIdentityPolicy: {0} on {1} (region {2})", policyName, identity, region);
    }

    /**
     * Purges every policy attached to an identity. Called by the facade's {@code deleteIdentity} so
     * policies can't resurrect into a same-named identity recreated later (and the per-identity count
     * stays correct).
     */
    public void deletePoliciesForIdentity(String identity, String region) {
        synchronized (policyMutationLock) {
            String prefix = policyPrefix(region, identity);
            for (String key : List.copyOf(policyStore.keys())) {
                if (key.startsWith(prefix)) {
                    policyStore.delete(key);
                }
            }
        }
    }

    private void enforcePolicyLimit(String region, String identity, boolean v2) {
        long count = policyStore.keys().stream()
                .filter(k -> k.startsWith(policyPrefix(region, identity))).count();
        if (count >= MAX_POLICIES_PER_IDENTITY) {
            String msg = "Number of policies for <" + identity
                    + "> exceeds max allowed number of policies per resource";
            throw new AwsException(v2 ? "LimitExceededException" : "InvalidParameterValue", msg, 400);
        }
    }

    // Error codes are the v1 (Query) codes verified against AWS; the v2 controller remaps them to
    // BadRequestException. The messages are identical across v1 and v2.
    private static void validatePolicyName(String policyName) {
        if (policyName == null || policyName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "PolicyName is required.", 400);
        }
        if (policyName.length() > 64) {
            throw new AwsException("ValidationError",
                    "1 validation error detected: Value at 'policyName' failed to satisfy constraint: "
                            + "Member must have length less than or equal to 64", 400);
        }
        if (!POLICY_NAME_CHARS.matcher(policyName).matches()) {
            throw new AwsException("InvalidParameterValue",
                    "PolicyName is invalid. Policy names must only include alpha-numeric characters, "
                            + "dashes, and underscores.", 400);
        }
    }

    private String normalizePolicy(String policy) {
        if (policy == null || policy.isBlank()) {
            throw new AwsException("InvalidParameterValue", "Policy is required.", 400);
        }
        try {
            // AWS returns the policy with insignificant whitespace stripped; compact it to match.
            return objectMapper.readTree(policy).toString();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Floci does not validate policy semantics; keep an unparseable document verbatim.
            LOG.debugv("Identity policy is not valid JSON, storing as-is: {0}", e.getMessage());
            return policy;
        }
    }

    private static AwsException policyNotFound(String policyName) {
        return new AwsException("NotFoundException", "Policy <" + policyName + "> does not exist", 404);
    }

    private static String policyKey(String region, String identity, String policyName) {
        return policyPrefix(region, identity) + policyName;
    }

    private static String policyPrefix(String region, String identity) {
        return "policy::" + region + "::" + identity + "::";
    }
}
