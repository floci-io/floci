package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshift.TempCredential;
import io.github.hectorvent.floci.services.redshiftserverless.model.ConfigParameter;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import io.github.hectorvent.floci.services.redshiftserverless.model.PricePerformanceTarget;
import io.github.hectorvent.floci.services.redshiftserverless.model.Workgroup;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@ApplicationScoped
public class RedshiftServerlessService implements Resettable {
    private static final Logger LOG = Logger.getLogger(RedshiftServerlessService.class);

    public static final String DEFAULT_DB_NAME = "dev";
    public static final String AWS_OWNED_KMS_KEY = "AWS_OWNED_KMS_KEY";

    private static final Pattern NAMESPACE_NAME = Pattern.compile("[a-z0-9-]+");
    private static final Pattern DB_NAME = Pattern.compile("[a-zA-Z][a-zA-Z_0-9+.@-]*");
    private static final Set<String> LOG_EXPORTS = Set.of("useractivitylog", "userlog", "connectionlog");

    /**
     * Amazon Redshift's SQL reserved words, which CreateNamespace rejects as namespace names even
     * though they satisfy the length and character rules. Held lowercase because a namespace name
     * is already constrained to lowercase letters, digits and hyphens, so a direct lookup suffices.
     * Transcribed from https://docs.aws.amazon.com/redshift/latest/dg/r_pg_keywords.html
     */
    private static final Set<String> RESERVED_WORDS = Set.of(
            "aes128", "aes256", "all", "allowoverwrite", "analyse", "analyze",
            "and", "any", "array", "as", "asc", "authorization",
            "az64", "backup", "between", "binary", "blanksasnull", "both",
            "bytedict", "bzip2", "case", "cast", "check", "collate",
            "column", "constraint", "create", "credentials", "cross", "current_date",
            "current_time", "current_timestamp", "current_user", "current_user_id", "default", "deferrable",
            "deflate", "defrag", "delta", "delta32k", "desc", "disable",
            "distinct", "do", "else", "emptyasnull", "enable", "encode",
            "encrypt", "encryption", "end", "except", "explicit", "false",
            "for", "foreign", "freeze", "from", "full", "globaldict256",
            "globaldict64k", "grant", "group", "gzip", "having", "identity",
            "ignore", "ilike", "in", "initially", "inner", "intersect",
            "interval", "into", "is", "isnull", "join", "leading",
            "left", "like", "limit", "localtime", "localtimestamp", "lun",
            "luns", "lzo", "lzop", "minus", "mostly16", "mostly32",
            "mostly8", "natural", "new", "not", "notnull", "null",
            "nulls", "off", "offline", "offset", "oid", "old",
            "on", "only", "open", "or", "order", "outer",
            "overlaps", "parallel", "partition", "percent", "permissions", "pivot",
            "placing", "primary", "raw", "readratio", "recover", "references",
            "rejectlog", "resort", "respect", "restore", "right", "select",
            "session_user", "similar", "snapshot", "some", "sysdate", "system",
            "table", "tag", "tdes", "text255", "text32k", "then",
            "timestamp", "to", "top", "trailing", "true", "truncatecolumns",
            "union", "unique", "unnest", "unpivot", "user", "using",
            "verbose", "wallet", "when", "where", "with", "without");

    public static final int DEFAULT_BASE_CAPACITY = 128;
    public static final int DEFAULT_PORT = 5439;
    public static final String DEFAULT_TRACK_NAME = "current";
    public static final String DEFAULT_IP_ADDRESS_TYPE = "ipv4";
    public static final String DEFAULT_ADMIN_USERNAME = "admin";
    public static final int DEFAULT_CREDENTIAL_DURATION_SECONDS = 900;
    public static final int MIN_CREDENTIAL_DURATION_SECONDS = 900;
    public static final int MAX_CREDENTIAL_DURATION_SECONDS = 3600;

    private static final Pattern WORKGROUP_NAME = Pattern.compile("[a-z0-9-]+");
    private static final Pattern TRACK_NAME = Pattern.compile("[a-zA-Z0-9_]+");
    private static final Set<String> IP_ADDRESS_TYPES = Set.of("ipv4", "dualstack");
    private static final Set<String> PERFORMANCE_TARGET_STATUSES = Set.of("ENABLED", "DISABLED");

    private final AccountAwareStorageBackend<Namespace> namespaces;
    private final AccountAwareStorageBackend<Workgroup> workgroups;
    private final RegionResolver regionResolver;
    private final RedshiftServerlessEndpoints endpoints;
    private final RedshiftServerlessRuntime runtime;

    @Inject
    public RedshiftServerlessService(StorageFactory storageFactory, RegionResolver regionResolver,
                                     RedshiftServerlessEndpoints endpoints, RedshiftServerlessRuntime runtime) {
        this.namespaces = storageFactory.create("redshiftserverless", "redshiftserverless-namespaces.json",
                new TypeReference<Map<String, Namespace>>() {});
        this.workgroups = storageFactory.create("redshiftserverless", "redshiftserverless-workgroups.json",
                new TypeReference<Map<String, Workgroup>>() {});
        this.regionResolver = regionResolver;
        this.endpoints = endpoints;
        this.runtime = runtime;
    }

    /**
     * A persisted workgroup keeps its endpoint across restarts. The port is claimed again in the
     * shared pool; if a provisioned cluster already took it, the workgroup gets a new endpoint and the
     * change is stored. The priority places this after the default-priority observers, which includes
     * {@code RedshiftService}, so every persisted cluster has reserved its own port by then and the
     * two services cannot be handed the same one.
     */
    void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION + 600) StartupEvent event) {
        for (AccountAwareStorageBackend.AccountEntry<Workgroup> entry : workgroups.scanAllAccountEntries(key -> true)) {
            Workgroup workgroup = new Workgroup(entry.value());
            workgroup.setEndpoint(endpoints.restore(workgroup.getEndpoint()));
            recoverRuntime(entry.accountId(), regionOf(entry.key()), workgroup);
            workgroups.putForAccount(entry.accountId(), entry.key(), workgroup);
        }
    }

    /**
     * Re-attaches to the workgroup's container, or starts a fresh one when it did not survive, and
     * restarts the proxy on the persisted port. A failure is logged and the runtime coordinates are
     * cleared rather than the status changed: the status enum has no failed member, and a Data API
     * call against a workgroup with no runtime reports that clearly.
     */
    private void recoverRuntime(String accountId, String region, Workgroup workgroup) {
        Optional<Namespace> namespace = namespaces.getForAccount(accountId,
                storageKey(region, workgroup.getNamespaceName()));
        if (namespace.isEmpty() || namespace.get().getAdminUserPassword() == null) {
            clearRuntime(workgroup);
            LOG.warnv("Workgroup {0} has no namespace admin credentials to restore its runtime with", workgroup.getWorkgroupName());
            return;
        }
        try {
            startRuntime(accountId, region, workgroup, namespace.get(), true);
        } catch (RuntimeException e) {
            clearRuntime(workgroup);
            LOG.warnv(e, "Failed to recover the runtime of workgroup {0}", workgroup.getWorkgroupName());
        }
    }

    private static void clearRuntime(Workgroup workgroup) {
        workgroup.setRuntimeHost(null);
        workgroup.setRuntimePort(0);
    }

    /**
     * Starts the workgroup's container and proxy with the namespace admin pair and records where the
     * container listens. The master role is the namespace admin user, or a role it was originally
     * created with when the namespace was renamed since.
     */
    private void startRuntime(String accountId, String region, Workgroup workgroup, Namespace namespace,
                              boolean adopt) {
        String masterUsername = workgroup.getMasterUsername() != null
                ? workgroup.getMasterUsername() : adminUserOf(namespace);
        RedshiftServerlessRuntime.Backend backend = runtime.start(accountId, region, workgroup.getWorkgroupName(),
                masterUsername, namespace.getAdminUserPassword(), namespace.getDbName(), workgroup.getEndpoint(),
                adopt, adminSupplier(accountId, region, namespace.getNamespaceName()));
        workgroup.setMasterUsername(masterUsername);
        workgroup.setRuntimeHost(backend.host());
        workgroup.setRuntimePort(backend.port());
    }

    private Supplier<Optional<RedshiftServerlessRuntime.AdminCredentials>> adminSupplier(
            String accountId, String region, String namespaceName) {
        return () -> namespaces.getForAccount(accountId, storageKey(region, namespaceName))
                .filter(namespace -> namespace.getAdminUserPassword() != null)
                .map(namespace -> new RedshiftServerlessRuntime.AdminCredentials(
                        adminUserOf(namespace), namespace.getAdminUserPassword()));
    }

    private static String adminUserOf(Namespace namespace) {
        String adminUsername = namespace.getAdminUsername();
        return adminUsername == null || adminUsername.isBlank() ? DEFAULT_ADMIN_USERNAME : adminUsername;
    }

    private static String regionOf(String storageKey) {
        return storageKey.substring(0, storageKey.indexOf("::"));
    }

    public synchronized Namespace createNamespace(String namespaceName, String adminUsername, String dbName,
                                                  String kmsKeyId, String defaultIamRoleArn, List<String> iamRoles,
                                                  List<String> logExports, Map<String, String> tags, String region) {
        return createNamespace(namespaceName, adminUsername, null, dbName, kmsKeyId, defaultIamRoleArn, iamRoles,
                logExports, tags, region);
    }

    public synchronized Namespace createNamespace(String namespaceName, String adminUsername,
                                                  String adminUserPassword, String dbName, String kmsKeyId,
                                                  String defaultIamRoleArn, List<String> iamRoles,
                                                  List<String> logExports, Map<String, String> tags, String region) {
        validateNamespaceName(namespaceName);
        String key = storageKey(region, namespaceName);
        if (namespaces.get(key).isPresent()) {
            throw new AwsException("ConflictException",
                    "The namespace " + namespaceName + " already exists.", 409);
        }

        Namespace namespace = new Namespace();
        namespace.setNamespaceName(namespaceName);
        namespace.setNamespaceId(UUID.randomUUID().toString());
        namespace.setNamespaceArn(regionResolver.buildArn("redshift-serverless", region,
                "namespace/" + namespace.getNamespaceId()));
        namespace.setAdminUsername(adminUsername);
        namespace.setAdminUserPassword(blankToNull(adminUserPassword));
        namespace.setDbName(validateDbName(dbName));
        namespace.setKmsKeyId(kmsKeyId == null || kmsKeyId.isBlank() ? AWS_OWNED_KMS_KEY : kmsKeyId);
        namespace.setDefaultIamRoleArn(defaultIamRoleArn);
        namespace.setIamRoles(copyOf(iamRoles));
        namespace.setLogExports(validateLogExports(logExports));
        namespace.setStatus("AVAILABLE");
        namespace.setCreationDate(Instant.now());
        namespace.setTags(tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags));
        namespaces.put(key, namespace);
        return namespace;
    }

    public Namespace getNamespace(String namespaceName, String region) {
        validateNamespaceName(namespaceName);
        return namespaces.get(storageKey(region, namespaceName))
                .orElseThrow(() -> notFound(namespaceName));
    }

    public PaginatedResult<Namespace> listNamespaces(String region, Integer maxResults, String nextToken) {
        List<Namespace> all = namespaces.scan(key -> key.startsWith(region + "::"));
        return Pagination.paginate(all, Namespace::getNamespaceName, maxResults, nextToken,
                100, 100, "ValidationException");
    }

    public synchronized Namespace updateNamespace(String namespaceName, String adminUsername, String kmsKeyId,
                                                  String defaultIamRoleArn, List<String> iamRoles,
                                                  List<String> logExports, String region) {
        return updateNamespace(namespaceName, adminUsername, null, kmsKeyId, defaultIamRoleArn, iamRoles,
                logExports, region);
    }

    /**
     * A new {@code adminUserPassword} is applied to the running workgroup backend last, once every
     * other field has validated, and rolled back if the namespace cannot be stored. The backend
     * therefore never holds a password the stored namespace does not.
     */
    public synchronized Namespace updateNamespace(String namespaceName, String adminUsername,
                                                  String adminUserPassword, String kmsKeyId,
                                                  String defaultIamRoleArn, List<String> iamRoles,
                                                  List<String> logExports, String region) {
        return applyNamespaceUpdate(namespaceName, adminUsername, adminUserPassword, kmsKeyId,
                defaultIamRoleArn, iamRoles, logExports, region, false);
    }

    /**
     * As {@link #updateNamespace} for CloudFormation, which drives a namespace to the template: an
     * omitted {@code kmsKeyId} returns to the AWS-owned key and an omitted {@code defaultIamRoleArn}
     * is cleared, where the API call would keep the stored value.
     */
    public synchronized Namespace reconcileNamespace(String namespaceName, String adminUsername,
                                                     String adminUserPassword, String kmsKeyId,
                                                     String defaultIamRoleArn, List<String> iamRoles,
                                                     List<String> logExports, String region) {
        return applyNamespaceUpdate(namespaceName, adminUsername, adminUserPassword, kmsKeyId,
                defaultIamRoleArn, iamRoles, logExports, region, true);
    }

    /**
     * Builds the new state on a copy and stores that, rather than mutating the stored instance.
     * Reads do not take the monitor the callers hold, so an in-place mutation lets a concurrent
     * GetNamespace or ListNamespaces observe a torn object: some fields updated, some not.
     */
    private Namespace applyNamespaceUpdate(String namespaceName, String adminUsername,
                                           String adminUserPassword, String kmsKeyId,
                                           String defaultIamRoleArn, List<String> iamRoles,
                                           List<String> logExports, String region, boolean resetOmitted) {
        String key = storageKey(region, namespaceName);
        Namespace current = getNamespace(namespaceName, region);
        Namespace updated = new Namespace(current);
        if (adminUsername != null) {
            updated.setAdminUsername(adminUsername);
        }
        if (kmsKeyId != null && !kmsKeyId.isBlank()) {
            updated.setKmsKeyId(kmsKeyId);
        } else if (resetOmitted) {
            updated.setKmsKeyId(AWS_OWNED_KMS_KEY);
        }
        if (defaultIamRoleArn != null || resetOmitted) {
            updated.setDefaultIamRoleArn(defaultIamRoleArn);
        }
        if (iamRoles != null) {
            updated.setIamRoles(copyOf(iamRoles));
        }
        if (logExports != null) {
            updated.setLogExports(validateLogExports(logExports));
        }
        boolean rotated = adminUserPassword != null && !adminUserPassword.isBlank();
        if (rotated) {
            applyNewAdminPassword(current, adminUserPassword, region);
            updated.setAdminUserPassword(adminUserPassword);
        }
        try {
            namespaces.put(key, updated);
        } catch (RuntimeException e) {
            if (rotated) {
                try {
                    restoreAdminPassword(current, region);
                } catch (RuntimeException restoreFailure) {
                    LOG.errorv(restoreFailure, "Could not restore the admin password of namespace {0}; "
                            + "the backend may reject the stored password", namespaceName);
                    e.addSuppressed(restoreFailure);
                }
            }
            throw e;
        }
        return updated;
    }

    public synchronized Namespace deleteNamespace(String namespaceName, String region) {
        Namespace deleted = new Namespace(getNamespace(namespaceName, region));
        // A workgroup cannot outlive its namespace; the model lists ConflictException for this call
        // but not its trigger, so this guard is the single place to relax if AWS turns out to differ.
        if (workgroupOf(namespaceName, region).isPresent()) {
            throw new AwsException("ConflictException",
                    "The namespace " + namespaceName + " still has a workgroup. Delete the workgroup first.", 409);
        }
        namespaces.delete(storageKey(region, namespaceName));
        deleted.setStatus("DELETING");
        return deleted;
    }

    public Map<String, String> listTagsForResource(String resourceArn, String region) {
        requireResourceArn(resourceArn);
        Optional<Namespace> namespace = namespaceByArn(resourceArn, region);
        if (namespace.isPresent()) {
            return new LinkedHashMap<>(namespace.get().getTags());
        }
        return new LinkedHashMap<>(workgroupByArn(resourceArn, region).orElseThrow(
                () -> resourceNotFound(resourceArn)).getTags());
    }

    public synchronized Map<String, String> tagResource(String resourceArn, Map<String, String> tags, String region) {
        return retag(resourceArn, region, tagMap -> {
            if (tags != null) {
                tagMap.putAll(tags);
            }
        });
    }

    public synchronized Map<String, String> untagResource(String resourceArn, List<String> tagKeys, String region) {
        requireResourceArn(resourceArn);
        if (namespaceByArn(resourceArn, region).isEmpty() && workgroupByArn(resourceArn, region).isEmpty()) {
            throw resourceNotFound(resourceArn);
        }
        if (tagKeys == null) {
            throw validation("tagKeys is required.");
        }
        return retag(resourceArn, region, tagMap -> tagKeys.forEach(tagMap::remove));
    }

    /**
     * Redshift Serverless tags whatever the ARN names, so the lookup is by ARN rather than by name.
     * Namespaces and workgroups are the taggable resources in Floci, so any other Redshift Serverless
     * ARN resolves to nothing and is reported as absent rather than as an unsupported resource type.
     * The change is applied to a copy and stored, never to the instance readers may hold.
     */
    private Map<String, String> retag(String resourceArn, String region, Consumer<Map<String, String>> change) {
        requireResourceArn(resourceArn);
        Optional<Namespace> namespace = namespaceByArn(resourceArn, region);
        if (namespace.isPresent()) {
            Namespace updated = new Namespace(namespace.get());
            change.accept(updated.getTags());
            namespaces.put(storageKey(region, updated.getNamespaceName()), updated);
            return new LinkedHashMap<>(updated.getTags());
        }
        Workgroup workgroup = workgroupByArn(resourceArn, region).orElseThrow(() -> resourceNotFound(resourceArn));
        Workgroup updated = new Workgroup(workgroup);
        change.accept(updated.getTags());
        workgroups.put(storageKey(region, updated.getWorkgroupName()), updated);
        return new LinkedHashMap<>(updated.getTags());
    }

    private Optional<Namespace> namespaceByArn(String resourceArn, String region) {
        return namespaces.scan(key -> key.startsWith(region + "::")).stream()
                .filter(namespace -> resourceArn.equals(namespace.getNamespaceArn()))
                .findFirst();
    }

    private Optional<Workgroup> workgroupByArn(String resourceArn, String region) {
        return workgroups.scan(key -> key.startsWith(region + "::")).stream()
                .filter(workgroup -> resourceArn.equals(workgroup.getWorkgroupArn()))
                .findFirst();
    }

    private static void requireResourceArn(String resourceArn) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw validation("resourceArn is required.");
        }
    }

    private static AwsException resourceNotFound(String resourceArn) {
        return new AwsException("ResourceNotFoundException",
                "The resource " + resourceArn + " was not found.", 404);
    }

    public synchronized Workgroup createWorkgroup(String workgroupName, String namespaceName,
                                                  WorkgroupSettings settings, Map<String, String> tags,
                                                  String region) {
        validateWorkgroupName(workgroupName);
        validateSettings(settings);
        Namespace namespace = getNamespace(namespaceName, region);
        String key = storageKey(region, workgroupName);
        if (workgroups.get(key).isPresent()) {
            throw new AwsException("ConflictException",
                    "The workgroup " + workgroupName + " already exists.", 409);
        }
        if (workgroupOf(namespaceName, region).isPresent()) {
            throw new AwsException("ConflictException",
                    "The namespace " + namespaceName + " already has a workgroup.", 409);
        }

        Workgroup workgroup = new Workgroup();
        workgroup.setWorkgroupName(workgroupName);
        workgroup.setWorkgroupId(UUID.randomUUID().toString());
        workgroup.setWorkgroupArn(regionResolver.buildArn("redshift-serverless", region,
                "workgroup/" + workgroup.getWorkgroupId()));
        workgroup.setNamespaceName(namespaceName);
        workgroup.setBaseCapacity(orDefault(settings.baseCapacity(), DEFAULT_BASE_CAPACITY));
        workgroup.setMaxCapacity(settings.maxCapacity());
        workgroup.setEnhancedVpcRouting(Boolean.TRUE.equals(settings.enhancedVpcRouting()));
        workgroup.setPubliclyAccessible(Boolean.TRUE.equals(settings.publiclyAccessible()));
        workgroup.setExtraComputeForAutomaticOptimization(
                Boolean.TRUE.equals(settings.extraComputeForAutomaticOptimization()));
        workgroup.setConfigParameters(copyOf(settings.configParameters()));
        workgroup.setSecurityGroupIds(copyOf(settings.securityGroupIds()));
        workgroup.setSubnetIds(copyOf(settings.subnetIds()));
        workgroup.setPort(orDefault(settings.port(), DEFAULT_PORT));
        workgroup.setIpAddressType(orDefault(settings.ipAddressType(), DEFAULT_IP_ADDRESS_TYPE));
        workgroup.setTrackName(orDefault(settings.trackName(), DEFAULT_TRACK_NAME));
        workgroup.setPricePerformanceTarget(settings.pricePerformanceTarget() != null
                ? settings.pricePerformanceTarget() : new PricePerformanceTarget("DISABLED", null));
        workgroup.setStatus("AVAILABLE");
        workgroup.setCreationDate(Instant.now());
        workgroup.setTags(tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags));
        workgroup.setEndpoint(endpoints.allocate());
        try {
            startRuntime(workgroups.accountId(), region, workgroup, ensureAdminPassword(namespace, region), false);
            workgroups.put(key, workgroup);
        } catch (RuntimeException e) {
            // The runtime may have started before the failure (a failed put), so tear it down; a
            // runtime that failed to start has already rolled itself back and this is then a no-op.
            try {
                runtime.stop(workgroups.accountId(), region, workgroupName);
            } catch (RuntimeException stopFailure) {
                LOG.warnv(stopFailure, "Could not stop the runtime of workgroup {0} after a failed create",
                        workgroupName);
                e.addSuppressed(stopFailure);
            }
            endpoints.release(workgroup.getEndpoint());
            throw e;
        }
        return workgroup;
    }

    public Workgroup getWorkgroup(String workgroupName, String region) {
        validateWorkgroupName(workgroupName);
        return workgroups.get(storageKey(region, workgroupName))
                .orElseThrow(() -> workgroupNotFound(workgroupName));
    }

    public PaginatedResult<Workgroup> listWorkgroups(String region, Integer maxResults, String nextToken) {
        List<Workgroup> all = workgroups.scan(key -> key.startsWith(region + "::"));
        return Pagination.paginate(all, Workgroup::getWorkgroupName, maxResults, nextToken,
                100, 100, "ValidationException");
    }

    /**
     * Applies the supplied settings to a copy and stores that. A {@code trackName} that differs from
     * the current one is recorded as {@code pendingTrackName} and the current track is left alone,
     * as AWS switches tracks only on the next release. {@code port} updates the configured value
     * that is echoed; the endpoint keeps the port the emulator allocated.
     */
    public synchronized Workgroup updateWorkgroup(String workgroupName, WorkgroupSettings settings, String region) {
        validateSettings(settings);
        Workgroup updated = new Workgroup(getWorkgroup(workgroupName, region));
        if (settings.baseCapacity() != null) {
            updated.setBaseCapacity(settings.baseCapacity());
        }
        if (settings.maxCapacity() != null) {
            updated.setMaxCapacity(settings.maxCapacity());
        }
        if (settings.enhancedVpcRouting() != null) {
            updated.setEnhancedVpcRouting(settings.enhancedVpcRouting());
        }
        if (settings.publiclyAccessible() != null) {
            updated.setPubliclyAccessible(settings.publiclyAccessible());
        }
        if (settings.extraComputeForAutomaticOptimization() != null) {
            updated.setExtraComputeForAutomaticOptimization(settings.extraComputeForAutomaticOptimization());
        }
        if (settings.configParameters() != null) {
            updated.setConfigParameters(copyOf(settings.configParameters()));
        }
        if (settings.securityGroupIds() != null) {
            updated.setSecurityGroupIds(copyOf(settings.securityGroupIds()));
        }
        if (settings.subnetIds() != null) {
            updated.setSubnetIds(copyOf(settings.subnetIds()));
        }
        if (settings.port() != null) {
            updated.setPort(settings.port());
        }
        if (settings.ipAddressType() != null) {
            updated.setIpAddressType(settings.ipAddressType());
        }
        if (settings.pricePerformanceTarget() != null) {
            updated.setPricePerformanceTarget(settings.pricePerformanceTarget());
        }
        if (settings.trackName() != null) {
            if (settings.trackName().equals(updated.getTrackName())) {
                updated.setPendingTrackName(null);
            } else {
                updated.setPendingTrackName(settings.trackName());
            }
        }
        workgroups.put(storageKey(region, workgroupName), updated);
        return updated;
    }

    public synchronized Workgroup deleteWorkgroup(String workgroupName, String region) {
        Workgroup deleted = new Workgroup(getWorkgroup(workgroupName, region));
        // The proxy goes first and its failure aborts the delete: a listener that could not be closed
        // keeps its port, so the workgroup must stay on record and the call can be retried.
        try {
            runtime.stop(workgroups.accountId(), region, workgroupName);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Failed to stop the runtime of workgroup {0}", workgroupName);
            throw new AwsException("InternalServerException",
                    "Failed to stop workgroup " + workgroupName + "; it was not deleted", 500);
        }
        workgroups.delete(storageKey(region, workgroupName));
        endpoints.release(deleted.getEndpoint());
        deleted.setStatus("DELETING");
        return deleted;
    }

    /**
     * Resolves a workgroup name or workgroup ARN, as the Data API accepts either, to the live
     * connection details of its backend.
     */
    public WorkgroupTarget getWorkgroupTarget(String nameOrArn, String region) {
        Workgroup workgroup = resolveWorkgroup(nameOrArn, region);
        Namespace namespace = getNamespace(workgroup.getNamespaceName(), region);
        if (workgroup.getRuntimeHost() == null || workgroup.getRuntimePort() <= 0
                || namespace.getAdminUserPassword() == null) {
            throw new AwsException("ValidationException",
                    "Workgroup runtime is not available for Data API execution.", 400);
        }
        return new WorkgroupTarget(workgroup.getWorkgroupArn(), workgroup.getWorkgroupName(),
                workgroup.getRuntimeHost(), workgroup.getRuntimePort(), namespace.getDbName(),
                workgroup.getMasterUsername(), namespace.getAdminUserPassword(), adminUserOf(namespace));
    }

    /** Connection details of a workgroup's backend; {@code adminUsername} is the name clients log in with. */
    public record WorkgroupTarget(String arn, String workgroupName, String host, int port, String database,
                                  String masterUsername, String masterPassword, String adminUsername) {
    }

    private Workgroup resolveWorkgroup(String nameOrArn, String region) {
        if (nameOrArn == null || nameOrArn.isBlank()) {
            throw validation("workgroupName is required.");
        }
        if (!nameOrArn.startsWith("arn:")) {
            return getWorkgroup(nameOrArn, region);
        }
        return workgroupByArn(nameOrArn, region).orElseThrow(() -> workgroupNotFound(nameOrArn));
    }

    /**
     * Mints a temporary credential for the caller's database user. It is master-equivalent on the
     * backend leg, exactly like GetClusterCredentials, so the user name is nominal.
     */
    public TempCredential getCredentials(String workgroupName, String dbName, Integer durationSeconds,
                                         String dbUser, String region) {
        Workgroup workgroup = resolveWorkgroup(workgroupName, region);
        Namespace namespace = getNamespace(workgroup.getNamespaceName(), region);
        if (dbName != null && !dbName.equals(namespace.getDbName())) {
            throw validation("dbName must be " + namespace.getDbName() + ", the only database in this workgroup.");
        }
        int duration = durationSeconds == null ? DEFAULT_CREDENTIAL_DURATION_SECONDS : durationSeconds;
        if (duration < MIN_CREDENTIAL_DURATION_SECONDS || duration > MAX_CREDENTIAL_DURATION_SECONDS) {
            throw validation("durationSeconds must be between " + MIN_CREDENTIAL_DURATION_SECONDS + " and "
                    + MAX_CREDENTIAL_DURATION_SECONDS + ".");
        }
        if (workgroup.getRuntimeHost() == null) {
            throw validation("Workgroup runtime is not available.");
        }
        return runtime.issueCredential(workgroups.accountId(), region, workgroup.getWorkgroupName(), dbUser,
                duration);
    }

    private Namespace ensureAdminPassword(Namespace namespace, String region) {
        if (namespace.getAdminUserPassword() != null) {
            return namespace;
        }
        Namespace withPassword = new Namespace(namespace);
        withPassword.setAdminUserPassword(runtime.generatePassword());
        namespaces.put(storageKey(region, namespace.getNamespaceName()), withPassword);
        return withPassword;
    }

    private void applyNewAdminPassword(Namespace namespace, String newPassword, String region) {
        Optional<Workgroup> attached = workgroupOf(namespace.getNamespaceName(), region);
        if (attached.isEmpty() || attached.get().getRuntimeHost() == null) {
            return;
        }
        try {
            runtime.changeMasterPassword(workgroups.accountId(), region, attached.get().getWorkgroupName(),
                    attached.get().getMasterUsername(), namespace.getDbName(), namespace.getAdminUserPassword(),
                    newPassword);
        } catch (AwsException e) {
            if ("InvalidParameterValue".equals(e.getErrorCode())) {
                throw new AwsException("ValidationException", e.getMessage(), 400);
            }
            throw e;
        }
    }

    private void restoreAdminPassword(Namespace previous, String region) {
        Optional<Workgroup> attached = workgroupOf(previous.getNamespaceName(), region);
        if (attached.isEmpty() || attached.get().getRuntimeHost() == null
                || previous.getAdminUserPassword() == null) {
            return;
        }
        runtime.restoreMasterPassword(workgroups.accountId(), region, attached.get().getWorkgroupName(),
                attached.get().getMasterUsername(), previous.getDbName(), previous.getAdminUserPassword());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Optional<Workgroup> workgroupOf(String namespaceName, String region) {
        return workgroups.scan(key -> key.startsWith(region + "::")).stream()
                .filter(workgroup -> namespaceName.equals(workgroup.getNamespaceName()))
                .findFirst();
    }

    @Override
    public void clear() {
        for (AccountAwareStorageBackend.AccountEntry<Workgroup> entry : workgroups.scanAllAccountEntries(key -> true)) {
            try {
                runtime.stop(entry.accountId(), regionOf(entry.key()), entry.value().getWorkgroupName());
                endpoints.release(entry.value().getEndpoint());
            } catch (RuntimeException e) {
                // The listener may still be bound, so the endpoint stays reserved rather than being
                // handed to a later cluster or workgroup whose proxy could not bind it.
                LOG.warnv(e, "Could not stop the runtime of workgroup {0} while clearing state; "
                        + "its endpoint stays reserved", entry.value().getWorkgroupName());
            }
        }
        namespaces.clear();
        workgroups.clear();
    }

    private static void validateNamespaceName(String namespaceName) {
        if (namespaceName == null || namespaceName.length() < 3 || namespaceName.length() > 64
                || !NAMESPACE_NAME.matcher(namespaceName).matches()) {
            throw validation("namespaceName must be 3-64 characters of lowercase letters, numbers, and hyphens.");
        }
        if (RESERVED_WORDS.contains(namespaceName)) {
            throw validation("namespaceName must not be an Amazon Redshift reserved word.");
        }
    }

    private static String validateDbName(String dbName) {
        if (dbName == null || dbName.isBlank()) {
            return DEFAULT_DB_NAME;
        }
        if (dbName.length() > 127 || !DB_NAME.matcher(dbName).matches()) {
            throw validation("dbName must start with a letter and be at most 127 characters.");
        }
        return dbName;
    }

    private static List<String> validateLogExports(List<String> logExports) {
        List<String> validated = copyOf(logExports);
        for (String logExport : validated) {
            if (!LOG_EXPORTS.contains(logExport)) {
                throw validation("logExports must contain only useractivitylog, userlog, and connectionlog.");
            }
        }
        return validated;
    }

    private static <T> List<T> copyOf(List<T> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    private static <T> T orDefault(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private static void validateWorkgroupName(String workgroupName) {
        if (workgroupName == null || workgroupName.length() < 3 || workgroupName.length() > 64
                || !WORKGROUP_NAME.matcher(workgroupName).matches()) {
            throw validation("workgroupName must be 3-64 characters of lowercase letters, numbers, and hyphens.");
        }
    }

    private static void validateSettings(WorkgroupSettings settings) {
        requirePositive(settings.baseCapacity(), "baseCapacity");
        requirePositive(settings.maxCapacity(), "maxCapacity");
        if (settings.port() != null && !isAllowedPort(settings.port())) {
            throw validation("port must be in the range 5431-5455 or 8191-8215.");
        }
        if (settings.ipAddressType() != null && !IP_ADDRESS_TYPES.contains(settings.ipAddressType())) {
            throw validation("ipAddressType must be ipv4 or dualstack.");
        }
        if (settings.trackName() != null
                && (settings.trackName().isEmpty() || settings.trackName().length() > 256
                || !TRACK_NAME.matcher(settings.trackName()).matches())) {
            throw validation("trackName must be 1-256 characters of letters, numbers, and underscores.");
        }
        PricePerformanceTarget target = settings.pricePerformanceTarget();
        if (target != null) {
            if (target.getStatus() != null && !PERFORMANCE_TARGET_STATUSES.contains(target.getStatus())) {
                throw validation("pricePerformanceTarget.status must be ENABLED or DISABLED.");
            }
            if (target.getLevel() != null && (target.getLevel() < 1 || target.getLevel() > 100)) {
                throw validation("pricePerformanceTarget.level must be between 1 and 100.");
            }
        }
        if (settings.configParameters() != null) {
            for (ConfigParameter parameter : settings.configParameters()) {
                if (parameter.getParameterKey() == null || parameter.getParameterKey().isBlank()) {
                    throw validation("configParameters entries require a parameterKey.");
                }
            }
        }
    }

    private static void requirePositive(Integer value, String field) {
        if (value != null && value < 1) {
            throw validation(field + " must be a positive integer.");
        }
    }

    private static boolean isAllowedPort(int port) {
        return (port >= 5431 && port <= 5455) || (port >= 8191 && port <= 8215);
    }

    private static AwsException workgroupNotFound(String workgroupName) {
        return new AwsException("ResourceNotFoundException",
                "The workgroup " + workgroupName + " was not found.", 404);
    }

    private static String storageKey(String region, String namespaceName) {
        return region + "::" + namespaceName;
    }

    private static AwsException notFound(String namespaceName) {
        return new AwsException("ResourceNotFoundException",
                "The namespace " + namespaceName + " was not found.", 404);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
