package io.github.hectorvent.floci.services.dsql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Base64Url;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dsql.model.DsqlCluster;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.github.hectorvent.floci.services.iam.IamService.CallerArns;
import io.github.hectorvent.floci.services.iam.RequestPrincipal;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aurora DSQL's control plane: clusters, their tags and resource policies. Each cluster's
 * database is run by {@link DsqlDataPlane}, which reports when it is ready; until then a cluster
 * is {@code CREATING}, as on AWS. A multi-Region cluster waits in {@code PENDING_SETUP} until it
 * and each of its peers name one another. Errors carry the messages measured on AWS (eu-west-1,
 * 2026-10-16 and 2026-10-20).
 */
@ApplicationScoped
public class DsqlService implements Resettable {

    private static final Logger LOG = Logger.getLogger(DsqlService.class);

    static final String CREATING = "CREATING";
    static final String ACTIVE = "ACTIVE";
    static final String UPDATING = "UPDATING";
    static final String DELETING = "DELETING";
    static final String FAILED = "FAILED";
    static final String PENDING_SETUP = "PENDING_SETUP";
    static final String PENDING_DELETE = "PENDING_DELETE";

    private static final int MAX_RESULTS = 100;
    private static final String PUT_POLICY = "dsql:PutClusterPolicy";
    private static final String DELETE_POLICY = "dsql:DeleteClusterPolicy";
    private static final String INVALID_PAGE_TOKEN = "Invalid pagination token. Request a new token and try again.";
    static final String LOCKOUT = "The new policy would result in the calling principal being unable to further"
            + " update and delete this cluster's policy. Set the 'bypassPolicyLockoutSafetyCheck' parameter to"
            + " 'true' to confirm this intent.";

    private final AccountAwareStorageBackend<DsqlCluster> clusters;
    private final RegionResolver regionResolver;
    private final String endpointSuffix;
    private final DsqlDataPlane dataPlane;
    private final DsqlPrincipals principals;
    private final IamPolicyEvaluator policyEvaluator;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    @Inject
    public DsqlService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver,
                       DsqlDataPlane dataPlane, DsqlPrincipals principals, IamPolicyEvaluator policyEvaluator,
                       ObjectMapper objectMapper) {
        this(storageFactory.create("dsql", "dsql-clusters.json", new TypeReference<>() {}), regionResolver,
                config.services().dsql().endpointSuffix(), dataPlane, principals, policyEvaluator, objectMapper,
                config.services().dsql().enabled());
    }

    DsqlService(AccountAwareStorageBackend<DsqlCluster> clusters, RegionResolver regionResolver,
                String endpointSuffix, DsqlDataPlane dataPlane, DsqlPrincipals principals,
                IamPolicyEvaluator policyEvaluator, ObjectMapper objectMapper, boolean enabled) {
        this.clusters = clusters;
        this.regionResolver = regionResolver;
        this.endpointSuffix = endpointSuffix;
        this.dataPlane = dataPlane;
        this.principals = principals;
        this.policyEvaluator = policyEvaluator;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    // ── Clusters ──────────────────────────────────────────────────────────────

    public DsqlCluster createCluster(String region, Boolean deletionProtectionEnabled, String kmsEncryptionKey,
                                     Map<String, String> tags, String clientToken, String witnessRegion,
                                     List<String> linkedClusterArns, String policy,
                                     boolean bypassPolicyLockoutSafetyCheck) {
        String accountId = regionResolver.getAccountId();
        synchronized (lockFor(accountId + "::" + region)) {
            String request = clientRequest(deletionProtectionEnabled, kmsEncryptionKey, tags, witnessRegion,
                    linkedClusterArns, policy);
            if (clientToken != null) {
                Optional<DsqlCluster> earlier = clusters.scan(key -> key.startsWith(region + "::")).stream()
                        .filter(cluster -> clientToken.equals(cluster.getClientToken()))
                        .findFirst();
                if (earlier.isPresent()) {
                    // Measured: the same token with other parameters is a conflict, not the earlier cluster.
                    if (!request.equals(earlier.get().getClientRequest())) {
                        throw new AwsException("ConflictException",
                                "Requests using a client token should supply identical request parameters", 409);
                    }
                    return earlier.get();
                }
            }
            String identifier = DsqlIdentifiers.newId();
            String arn = clusterArn(region, identifier);
            String keptPolicy = policy == null ? null
                    : checkedPolicy(policy, arn, region, bypassPolicyLockoutSafetyCheck);
            DsqlCluster cluster = new DsqlCluster();
            cluster.setIdentifier(identifier);
            cluster.setArn(arn);
            cluster.setAccountId(accountId);
            cluster.setRegion(region);
            cluster.setCreationTime(Instant.now());
            // Measured: a cluster created without the member has deletion protection.
            cluster.setDeletionProtectionEnabled(deletionProtectionEnabled == null || deletionProtectionEnabled);
            cluster.setKmsEncryptionKey(customerKey(kmsEncryptionKey));
            cluster.setTags(tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags));
            cluster.setEndpoint(endpointFor(identifier, region));
            cluster.setClientToken(clientToken);
            cluster.setClientRequest(clientToken == null ? null : request);
            cluster.setWitnessRegion(blankToNull(witnessRegion));
            cluster.setLinkedClusterArns(linkedClusters(arn, cluster.getWitnessRegion(), linkedClusterArns));
            // Measured: a multi-Region cluster waits for its peers, a single-Region one is being created.
            cluster.setStatus(cluster.getWitnessRegion() != null ? PENDING_SETUP : CREATING);
            if (keptPolicy != null) {
                cluster.setPolicy(keptPolicy);
                // Measured: a policy's version is when it was set, in epoch milliseconds.
                cluster.setPolicyVersion(String.valueOf(cluster.getCreationTime().toEpochMilli()));
            }
            clusters.put(key(region, identifier), cluster);
            LOG.infov("Created DSQL cluster {0} in {1}", identifier, region);
            dataPlane.provision(cluster, ready -> markReady(accountId, region, identifier, ready));
            return cluster;
        }
    }

    public DsqlCluster getCluster(String region, String identifier) {
        requireIdentifier(identifier);
        return clusters.get(key(region, identifier))
                .orElseThrow(() -> notFound(clusterArn(region, identifier), identifier));
    }

    public Page listClusters(String region, Integer maxResults, String nextToken) {
        if (maxResults != null && (maxResults < 1 || maxResults > MAX_RESULTS)) {
            // Measured: unlike other model checks, this one does not quote the value.
            throw new AwsException("ValidationException", "1 validation error detected: Value at 'maxResults'"
                    + " failed to satisfy constraint: Member must have value "
                    + (maxResults < 1 ? "greater than or equal to 1" : "less than or equal to 100"), 400);
        }
        List<DsqlCluster> all = new ArrayList<>(clusters.scan(key -> key.startsWith(region + "::")));
        all.sort(Comparator.comparing(DsqlCluster::getCreationTime).thenComparing(DsqlCluster::getIdentifier));
        int start = 0;
        if (nextToken != null && !nextToken.isBlank()) {
            // The token holds the sort position of the next page's first cluster, so a page carries
            // on from there even when that cluster is gone, as on AWS (measured).
            String[] position = tokenPosition(nextToken);
            start = all.size();
            for (int i = 0; i < all.size(); i++) {
                DsqlCluster cluster = all.get(i);
                int order = cluster.getCreationTime().compareTo(Instant.parse(position[0]));
                if (order > 0 || (order == 0 && cluster.getIdentifier().compareTo(position[1]) >= 0)) {
                    start = i;
                    break;
                }
            }
        }
        int size = maxResults == null ? MAX_RESULTS : maxResults;
        int end = Math.min(all.size(), start + size);
        String next = end < all.size() ? pageToken(all.get(end)) : null;
        return new Page(all.subList(start, end), next);
    }

    /** A member left null keeps its value. */
    public DsqlCluster updateCluster(String region, String identifier, Boolean deletionProtectionEnabled,
                                     String kmsEncryptionKey, String witnessRegion, List<String> linkedClusterArns) {
        DsqlCluster cluster = getCluster(region, identifier);
        synchronized (lockFor(cluster.getArn())) {
            if (deletionProtectionEnabled != null) {
                cluster.setDeletionProtectionEnabled(deletionProtectionEnabled);
            }
            if (kmsEncryptionKey != null) {
                cluster.setKmsEncryptionKey(customerKey(kmsEncryptionKey));
            }
            if (witnessRegion != null) {
                cluster.setWitnessRegion(blankToNull(witnessRegion));
            }
            if (linkedClusterArns != null) {
                cluster.setLinkedClusterArns(linkedClusters(cluster.getArn(), cluster.getWitnessRegion(),
                        linkedClusterArns));
            }
            clusters.put(key(region, identifier), cluster);
        }
        completePeering(cluster);
        return cluster;
    }

    public DsqlCluster deleteCluster(String region, String identifier) {
        DsqlCluster cluster = getCluster(region, identifier);
        String accountId = regionResolver.getAccountId();
        synchronized (lockFor(cluster.getArn())) {
            if (cluster.isDeletionProtectionEnabled()) {
                throw new AwsException("ValidationException", "Can't delete cluster: Deletion protection is enabled."
                        + " To proceed, disable deletion protection and try again.", 400,
                        Map.of("reason", "deletionProtectionEnabled"));
            }
            if (DELETING.equals(cluster.getStatus())) {
                return cluster;
            }
            cluster.setStatus(DELETING);
            clusters.put(key(region, identifier), cluster);
        }
        dataPlane.deprovision(cluster, () -> {
            clusters.deleteForAccount(accountId, key(region, identifier));
            locks.remove(cluster.getArn());
            LOG.infov("Deleted DSQL cluster {0} in {1}", identifier, region);
        });
        return cluster;
    }

    // ── VPC endpoint service ──────────────────────────────────────────────────

    public String vpcEndpointServiceName(String region, String identifier) {
        getCluster(region, identifier);
        return "com.amazonaws." + region + ".dsql-" + regionalServiceSuffix(region);
    }

    public String clusterVpcEndpoint(String region, String identifier) {
        return identifier + ".dsql-" + regionalServiceSuffix(region) + "." + region + "." + endpointSuffix;
    }

    // ── Resource policy ───────────────────────────────────────────────────────

    public String putClusterPolicy(String region, String identifier, String policy, String expectedPolicyVersion,
                                   boolean bypassPolicyLockoutSafetyCheck) {
        requireIdentifier(identifier);
        String arn = clusterArn(region, identifier);
        String keptPolicy = checkedPolicy(policy, arn, region, bypassPolicyLockoutSafetyCheck);
        DsqlCluster cluster = getCluster(region, identifier);
        synchronized (lockFor(cluster.getArn())) {
            // Measured: with no policy any expected version is not found; with one, another version conflicts.
            if (expectedPolicyVersion != null && cluster.getPolicy() == null) {
                throw policyVersionNotFound(identifier, expectedPolicyVersion);
            }
            if (expectedPolicyVersion != null && !expectedPolicyVersion.equals(cluster.getPolicyVersion())) {
                throw new AwsException("ConflictException", policyVersionMessage(identifier, expectedPolicyVersion),
                        409, Map.of("resourceId", identifier, "resourceType", "cluster"));
            }
            cluster.setPolicy(keptPolicy);
            cluster.setPolicyVersion(nextPolicyVersion(cluster));
            clusters.put(key(region, identifier), cluster);
            return cluster.getPolicyVersion();
        }
    }

    public DsqlCluster getClusterPolicy(String region, String identifier) {
        DsqlCluster cluster = getCluster(region, identifier);
        if (cluster.getPolicy() == null) {
            throw policyNotFound(identifier);
        }
        return cluster;
    }

    /** Answers the version of the policy deleted, as DSQL does (measured). */
    public String deleteClusterPolicy(String region, String identifier, String expectedPolicyVersion) {
        DsqlCluster cluster = getCluster(region, identifier);
        synchronized (lockFor(cluster.getArn())) {
            if (cluster.getPolicy() == null) {
                throw policyNotFound(identifier);
            }
            if (expectedPolicyVersion != null && !expectedPolicyVersion.equals(cluster.getPolicyVersion())) {
                throw policyVersionNotFound(identifier, expectedPolicyVersion);
            }
            String version = cluster.getPolicyVersion();
            cluster.setPolicy(null);
            cluster.setPolicyVersion(null);
            clusters.put(key(region, identifier), cluster);
            return version;
        }
    }

    /**
     * Gives the cluster {@code policy}, or none when it is null, as CloudFormation's update does
     * (measured): a policy the cluster already keeps is left as it is, version included.
     */
    public void replaceClusterPolicy(String region, String identifier, String policy) {
        DsqlCluster cluster = getCluster(region, identifier);
        if (policy == null) {
            if (cluster.getPolicy() != null) {
                deleteClusterPolicy(region, identifier, null);
            }
            return;
        }
        if (!checkedPolicy(policy, cluster.getArn(), region, false).equals(cluster.getPolicy())) {
            putClusterPolicy(region, identifier, policy, null, false);
        }
    }

    /** The policy as DSQL keeps it in its own form, once it has passed every check DSQL makes. */
    String checkedPolicy(String policy, String clusterArn, String region, boolean bypassPolicyLockoutSafetyCheck) {
        String kept = DsqlClusterPolicies.canonical(objectMapper, policy, regionResolver.getPartition(),
                principals::exists);
        if (DsqlClusterPolicies.grantsPublicAccess(objectMapper, kept)) {
            throw new AwsException("ValidationException", DsqlClusterPolicies.PUBLIC, 400);
        }
        if (!bypassPolicyLockoutSafetyCheck && deniesCaller(kept, clusterArn, region, PUT_POLICY)
                && deniesCaller(kept, clusterArn, region, DELETE_POLICY)) {
            // Measured: refused only when the caller could neither replace nor delete the policy.
            throw new AwsException("ValidationException", LOCKOUT, 400);
        }
        return kept;
    }

    private boolean deniesCaller(String policy, String clusterArn, String region, String action) {
        CallerArns caller = principals.caller();
        return policyEvaluator.evaluateResourcePolicyFor(List.of(policy), RequestPrincipal.caller(caller), action,
                clusterArn, principals.policyContext(caller, clusterArn, region, Map.of()))
                == ResourcePolicyDecision.EXPLICIT_DENY;
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    public Map<String, String> listTags(String region, String arn) {
        DsqlCluster cluster = clusterForArn(region, arn, "ListTagsForResource");
        synchronized (lockFor(cluster.getArn())) {
            return new LinkedHashMap<>(cluster.getTags());
        }
    }

    public void tagResource(String region, String arn, Map<String, String> tags) {
        DsqlCluster cluster = clusterForArn(region, arn, "TagResource");
        synchronized (lockFor(cluster.getArn())) {
            cluster.getTags().putAll(tags == null ? Map.of() : tags);
            clusters.put(key(cluster.getRegion(), cluster.getIdentifier()), cluster);
        }
    }

    public void untagResource(String region, String arn, List<String> tagKeys) {
        DsqlCluster cluster = clusterForArn(region, arn, "UntagResource");
        synchronized (lockFor(cluster.getArn())) {
            (tagKeys == null ? List.<String>of() : tagKeys).forEach(cluster.getTags()::remove);
            clusters.put(key(cluster.getRegion(), cluster.getIdentifier()), cluster);
        }
    }

    /**
     * Brings every cluster's database back when Floci starts with clusters in its storage, and
     * finishes a deletion that a stop interrupted.
     */
    void onStart(@Observes StartupEvent ignored) {
        if (!enabled) {
            return;
        }
        for (DsqlCluster cluster : clusters.scanAllAccounts()) {
            String accountId = cluster.getAccountId();
            String region = cluster.getRegion();
            String identifier = cluster.getIdentifier();
            if (DELETING.equals(cluster.getStatus())) {
                dataPlane.deprovision(cluster, () -> {
                    clusters.deleteForAccount(accountId, key(region, identifier));
                    locks.remove(cluster.getArn());
                });
            } else if (!FAILED.equals(cluster.getStatus())) {
                cluster.setDatabaseReady(false);
                clusters.putForAccount(accountId, key(region, identifier), cluster);
                dataPlane.provision(cluster, ready -> markReady(accountId, region, identifier, ready));
            }
        }
    }

    @Override
    public void clear() {
        for (DsqlCluster cluster : clusters.scanAllAccounts()) {
            dataPlane.deprovision(cluster, () -> { });
        }
        clusters.clear();
        locks.clear();
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Records whether the cluster's database came up. A cluster being created becomes ACTIVE or
     * FAILED; one still waiting for its peers stays PENDING_SETUP until peering, unless its database
     * failed.
     */
    private void markReady(String accountId, String region, String identifier, boolean ready) {
        clusters.getForAccount(accountId, key(region, identifier)).ifPresent(found -> {
            synchronized (lockFor(found.getArn())) {
                DsqlCluster cluster = clusters.getForAccount(accountId, key(region, identifier)).orElse(null);
                if (cluster == null) {
                    return;
                }
                cluster.setDatabaseReady(ready);
                if (CREATING.equals(cluster.getStatus()) || (!ready && PENDING_SETUP.equals(cluster.getStatus()))) {
                    cluster.setStatus(ready ? ACTIVE : FAILED);
                }
                clusters.putForAccount(accountId, key(region, identifier), cluster);
            }
        });
    }

    /**
     * A multi-Region cluster's clusters as DSQL lists them (measured): its peers as given, then
     * itself. A single-Region cluster has none.
     */
    private static List<String> linkedClusters(String arn, String witnessRegion, List<String> peers) {
        List<String> linked = new ArrayList<>();
        if (peers != null) {
            peers.stream().filter(peer -> !peer.equals(arn) && !linked.contains(peer)).forEach(linked::add);
        }
        if (witnessRegion == null && linked.isEmpty()) {
            return null;
        }
        linked.add(arn);
        return List.copyOf(linked);
    }

    /**
     * Brings out of {@code PENDING_SETUP} every cluster of {@code cluster}'s group once each names
     * every other, as DSQL does when the last peer is updated (measured: CREATING, then both ACTIVE
     * after about 100 s; here ACTIVE at once if the cluster's database answers, else CREATING until
     * it does).
     */
    private void completePeering(DsqlCluster cluster) {
        if (cluster.getLinkedClusterArns() == null) {
            return;
        }
        List<DsqlCluster> group = new ArrayList<>();
        for (String arn : cluster.getLinkedClusterArns()) {
            Optional<DsqlCluster> member = clusterOfArn(arn);
            if (member.isEmpty()) {
                return;
            }
            group.add(member.get());
        }
        Set<String> names = new HashSet<>(cluster.getLinkedClusterArns());
        if (group.size() < 2 || group.stream().anyMatch(member -> member.getWitnessRegion() == null
                || !names.equals(new HashSet<>(member.getLinkedClusterArns())))) {
            return;
        }
        for (DsqlCluster member : group) {
            synchronized (lockFor(member.getArn())) {
                if (PENDING_SETUP.equals(member.getStatus())) {
                    member.setStatus(member.isDatabaseReady() ? ACTIVE : CREATING);
                    clusters.put(key(member.getRegion(), member.getIdentifier()), member);
                }
            }
        }
    }

    /** The cluster {@code arn} names in the caller's account, if there is one. */
    public Optional<DsqlCluster> clusterOfArn(String arn) {
        AwsArnUtils.Arn parsed = clusterArnOrNull(arn);
        if (parsed == null || !regionResolver.getAccountId().equals(parsed.accountId())) {
            return Optional.empty();
        }
        return clusters.get(key(parsed.region(), parsed.resource().substring("cluster/".length())));
    }

    /**
     * The cluster a tag call names, refused as DSQL refuses it (measured): an ARN that is not a
     * well-formed cluster ARN as access denied, another account's as not authorized, another
     * Region's as a bad request, and one of no cluster as not found.
     */
    private DsqlCluster clusterForArn(String region, String arn, String action) {
        AwsArnUtils.Arn parsed = clusterArnOrNull(arn);
        String caller = principals.caller().callerArn();
        if (parsed == null) {
            throw new AwsException("AccessDeniedException", "Invalid input for 'resourceArn'. The cluster ARN that you"
                    + " specified isn't valid. Provide the cluster ARN in the format"
                    + " 'arn:partition:dsql:region:account-id:cluster/cluster-id' and try again."
                    + DsqlPrincipals.notAuthorized(caller, "dsql:" + action, "*",
                            DsqlPrincipals.EXPLICIT_RESOURCE_DENY), 403);
        }
        if (!regionResolver.getAccountId().equals(parsed.accountId())) {
            // Measured: another account's cluster is refused, not looked up in the caller's.
            throw new AwsException("AccessDeniedException", DsqlPrincipals.notAuthorized(caller, "dsql:" + action, arn,
                    "because no resource-based policy allows the dsql:" + action + " action"), 403);
        }
        if (!region.equals(parsed.region())) {
            throw new AwsException("BadRequestException", "Invalid input resource arn: expected region " + region, 400);
        }
        return clusters.get(key(region, parsed.resource().substring("cluster/".length())))
                .orElseThrow(() -> new AwsException("NotFoundException", "Resource was not found", 404));
    }

    /** {@code arn} parsed when it is a cluster ARN with a cluster id DSQL accepts, else null. */
    private static AwsArnUtils.Arn clusterArnOrNull(String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String resource = parsed.resource();
        if (!"dsql".equals(parsed.service()) || !resource.startsWith("cluster/")) {
            return null;
        }
        String identifier = resource.substring("cluster/".length());
        return DsqlIdentifiers.CLUSTER_ID.matcher(identifier).matches()
                && DsqlIdentifiers.decode(identifier).isPresent() ? parsed : null;
    }

    private String endpointFor(String identifier, String region) {
        return identifier + ".dsql." + region + "." + endpointSuffix;
    }

    private String clusterArn(String region, String identifier) {
        return regionResolver.buildArn("dsql", region, "cluster/" + identifier);
    }

    /**
     * The four characters after {@code dsql-} in a Region's VPC endpoint service name, the same
     * for every cluster of the Region (eu-west-1 is {@code 2syq} on AWS). Floci derives them from
     * the Region's name, so they are stable.
     */
    private static String regionalServiceSuffix(String region) {
        byte[] digest = Digests.sha256(region.getBytes(StandardCharsets.UTF_8));
        String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
        StringBuilder suffix = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            suffix.append(alphabet.charAt(digest[i] & 31));
        }
        return suffix.toString();
    }

    /**
     * A cluster id must match DSQL's pattern, and then name a UUID: both refusals carry the texts
     * measured on AWS.
     */
    static void requireIdentifier(String identifier) {
        if (identifier == null || !DsqlIdentifiers.CLUSTER_ID.matcher(identifier).matches()) {
            throw new AwsException("ValidationException", "1 validation error detected: Value at 'identifier'"
                    + " failed to satisfy constraint: Member must satisfy regular expression pattern: [a-z0-9]{26}", 400);
        }
        if (DsqlIdentifiers.decode(identifier).isEmpty()) {
            throw new AwsException("ValidationException", "Invalid input 'identifier: " + identifier
                    + "'. The cluster identifier format is invalid. Provide a valid cluster identifier and try again.",
                    400);
        }
    }

    private static AwsException notFound(String arn, String identifier) {
        return new AwsException("ResourceNotFoundException", "The resource with ARN " + arn
                + " doesn't exist. Verify the ARN and try again.", 404,
                Map.of("resourceId", identifier, "resourceType", "cluster"));
    }

    private static AwsException policyNotFound(String identifier) {
        return new AwsException("ResourceNotFoundException", "Aurora DSQL can't find the policy for the resource "
                + identifier + ". Verify that the policy exists.", 404,
                Map.of("resourceId", identifier, "resourceType", "policy"));
    }

    private static AwsException policyVersionNotFound(String identifier, String version) {
        return new AwsException("ResourceNotFoundException", policyVersionMessage(identifier, version), 404,
                Map.of("resourceId", identifier, "resourceType", "policy"));
    }

    private static String policyVersionMessage(String identifier, String version) {
        return "Aurora DSQL can't find policy version " + version + " for resource " + identifier
                + ". Verify that the policy exists and that the version is correct.";
    }

    /** When the policy is set, in epoch milliseconds, after the version it replaces. */
    private static String nextPolicyVersion(DsqlCluster cluster) {
        long now = Instant.now().toEpochMilli();
        try {
            return String.valueOf(cluster.getPolicyVersion() == null ? now
                    : Math.max(now, Long.parseLong(cluster.getPolicyVersion()) + 1));
        } catch (NumberFormatException e) {
            return String.valueOf(now);
        }
    }

    private Object lockFor(String key) {
        return locks.computeIfAbsent(key, k -> new Object());
    }

    private static String key(String region, String identifier) {
        return region + "::" + identifier;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** A customer-managed key, or null for AWS's own: blank, or the reserved AWS_OWNED_KMS_KEY (measured). */
    private static String customerKey(String kmsEncryptionKey) {
        return "AWS_OWNED_KMS_KEY".equals(kmsEncryptionKey) ? null : blankToNull(kmsEncryptionKey);
    }

    /** What a create asked for, which a later create with the same client token must repeat. */
    private static String clientRequest(Boolean deletionProtectionEnabled, String kmsEncryptionKey,
                                        Map<String, String> tags, String witnessRegion,
                                        List<String> linkedClusterArns, String policy) {
        return String.join("\n", String.valueOf(deletionProtectionEnabled), String.valueOf(kmsEncryptionKey),
                String.valueOf(tags == null ? null : new TreeMap<>(tags)), String.valueOf(witnessRegion),
                String.valueOf(linkedClusterArns), String.valueOf(policy));
    }

    private static String pageToken(DsqlCluster cluster) {
        String position = cluster.getCreationTime() + " " + cluster.getIdentifier();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(position.getBytes(StandardCharsets.UTF_8));
    }

    private static String[] tokenPosition(String nextToken) {
        try {
            String[] position = Base64Url.decode(nextToken)
                    .split(" ", 2);
            if (position.length == 2) {
                Instant.parse(position[0]);
                return position;
            }
        } catch (IllegalArgumentException | DateTimeParseException invalid) {
            // Refused below.
        }
        // Measured: the one refusal here that says which member and why.
        throw new AwsException("ValidationException", INVALID_PAGE_TOKEN, 400, Map.of(
                "reason", "fieldValidationFailed",
                "fieldList", List.of(Map.of("name", "next-token", "message", INVALID_PAGE_TOKEN))));
    }

    /** One page of ListClusters. */
    public record Page(List<DsqlCluster> clusters, String nextToken) {
    }
}
