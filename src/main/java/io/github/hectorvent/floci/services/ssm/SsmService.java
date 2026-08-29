package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import io.github.hectorvent.floci.services.ssm.model.ParameterHistory;
import io.github.hectorvent.floci.services.ssm.model.PatchBaselineIdentity;
import io.github.hectorvent.floci.services.ssm.model.ServiceSetting;
import io.github.hectorvent.floci.services.ssm.model.SsmDocument;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import java.util.ArrayList;
import java.util.Set;

@ApplicationScoped
public class SsmService implements ResourceProvider {

    private static final Logger LOG = Logger.getLogger(SsmService.class);

    /**
     * Account-default values for the service settings floci models. AWS rejects
     * unknown setting ids with ServiceSettingNotFound; so do we.
     */
    private static final Map<String, String> SERVICE_SETTING_DEFAULTS = Map.of(
            "/ssm/documents/console/public-sharing-permission", "Enable",
            "/ssm/parameter-store/default-parameter-tier", "Standard",
            "/ssm/parameter-store/high-throughput-enabled", "false",
            "/ssm/managed-instance/activation-tier", "standard"
    );

    private final StorageBackend<String, Parameter> parameterStore;
    private final StorageBackend<String, List<ParameterHistory>> historyStore;
    private final StorageBackend<String, List<String>> documentPermissionStore;
    private final StorageBackend<String, SsmDocument> documentStore;
    private final StorageBackend<String, ServiceSetting> serviceSettingStore;
    private final int maxParameterHistory;
    private final RegionResolver regionResolver;

    @Inject
    public SsmService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver) {
        this(
                storageFactory.create("ssm", "ssm-parameters.json",
                        new TypeReference<>() {
                        }),
                storageFactory.create("ssm", "ssm-history.json",
                        new TypeReference<>() {
                        }),
                storageFactory.create("ssm", "ssm-document-permissions.json",
                        new TypeReference<>() {
                        }),
                storageFactory.create("ssm", "ssm-documents.json",
                        new TypeReference<>() {
                        }),
                storageFactory.create("ssm", "ssm-service-settings.json",
                        new TypeReference<>() {
                        }),
                config.services().ssm().maxParameterHistory(),
                regionResolver
        );
    }

    /**
     * Package-private constructor for testing without CDI.
     */
    SsmService(StorageBackend<String, Parameter> parameterStore,
               StorageBackend<String, List<ParameterHistory>> historyStore,
               StorageBackend<String, List<String>> documentPermissionStore,
               int maxParameterHistory) {
        this(parameterStore, historyStore, documentPermissionStore, new InMemoryStorage<>(),
                new InMemoryStorage<>(), maxParameterHistory,
                new RegionResolver("us-east-1", "000000000000"));
    }

    /**
     * Package-private constructor for testing without CDI.
     */
    SsmService(StorageBackend<String, Parameter> parameterStore,
               StorageBackend<String, List<ParameterHistory>> historyStore,
               int maxParameterHistory) {
        this(parameterStore, historyStore, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), maxParameterHistory,
                new RegionResolver("us-east-1", "000000000000"));
    }

    SsmService(StorageBackend<String, Parameter> parameterStore,
               StorageBackend<String, List<ParameterHistory>> historyStore,
               StorageBackend<String, List<String>> documentPermissionStore,
               StorageBackend<String, SsmDocument> documentStore,
               StorageBackend<String, ServiceSetting> serviceSettingStore,
               int maxParameterHistory, RegionResolver regionResolver) {
        this.parameterStore = parameterStore;
        this.historyStore = historyStore;
        this.documentPermissionStore = documentPermissionStore;
        this.documentStore = documentStore;
        this.serviceSettingStore = serviceSettingStore;
        this.maxParameterHistory = maxParameterHistory;
        this.regionResolver = regionResolver;
    }

    /**
     * Create or update a parameter.
     * Returns the version number.
     */
    public long putParameter(String name, String value, String type, String description, boolean overwrite, String region) {
        String storageKey = regionKey(region, name);
        Parameter existing = parameterStore.get(storageKey).orElse(null);

        if (existing != null && !overwrite) {
            throw new AwsException("ParameterAlreadyExists",
                    "The parameter already exists. To overwrite this value, set the overwrite option in the request to true.",
                    400);
        }

        long version = (existing != null) ? existing.getVersion() + 1 : 1;

        Parameter parameter = new Parameter(name, value, type != null ? type : "String");
        parameter.setVersion(version);
        parameter.setDescription(description);
        parameter.setArn(regionResolver.buildArn("ssm", region, "parameter" + name));
        parameter.setLastModifiedDate(Instant.now());

        parameterStore.put(storageKey, parameter);
        addHistory(storageKey, parameter);

        LOG.infov("Put parameter: {0} in region {1} (version {2})", name, region, version);
        return version;
    }

    public Parameter getParameter(String name, String region) {
        String storageKey = regionKey(region, name);
        return parameterStore.get(storageKey)
                .orElseThrow(() -> new AwsException("ParameterNotFound",
                        "Parameter " + name + " not found.", 400));
    }

    public List<Parameter> getParameters(List<String> names, String region) {
        List<Parameter> result = new ArrayList<>();
        for (String name : names) {
            parameterStore.get(regionKey(region, name)).ifPresent(result::add);
        }
        return result;
    }

    public List<Parameter> getParametersByPath(String path, boolean recursive, String region) {
        String normalizedPath = path.endsWith("/") ? path : path + "/";
        String prefix = region + "::";

        return parameterStore.scan(key -> {
            if (!key.startsWith(prefix)) {
                return false;
            }
            String paramName = key.substring(prefix.length());
            if (!paramName.startsWith(normalizedPath)) {
                return false;
            }
            if (recursive) {
                return true;
            }
            String remainder = paramName.substring(normalizedPath.length());
            return !remainder.contains("/");
        });
    }

    public void deleteParameter(String name, String region) {
        String storageKey = regionKey(region, name);
        if (parameterStore.get(storageKey).isEmpty()) {
            throw new AwsException("ParameterNotFound",
                    "Parameter " + name + " not found.", 400);
        }
        parameterStore.delete(storageKey);
        historyStore.delete(storageKey);
        LOG.infov("Deleted parameter: {0}", name);
    }

    public List<String> deleteParameters(List<String> names, String region) {
        List<String> deleted = new ArrayList<>();
        for (String name : names) {
            String storageKey = regionKey(region, name);
            if (parameterStore.get(storageKey).isPresent()) {
                parameterStore.delete(storageKey);
                historyStore.delete(storageKey);
                deleted.add(name);
            }
        }
        return deleted;
    }

    public List<ParameterHistory> getParameterHistory(String name, String region) {
        String storageKey = regionKey(region, name);
        if (parameterStore.get(storageKey).isEmpty()) {
            throw new AwsException("ParameterNotFound",
                    "Parameter " + name + " not found.", 400);
        }
        return historyStore.get(storageKey).orElse(Collections.emptyList());
    }

    public List<Parameter> describeParameters(String region) {
        return describeParameters(List.of(), region);
    }

    public List<Parameter> describeParameters(List<String> nameFilters, String region) {
        String prefix = region + "::";
        return parameterStore.scan(key -> {
            if (!key.startsWith(prefix)) return false;
            if (nameFilters.isEmpty()) return true;
            String name = key.substring(prefix.length());
            return nameFilters.contains(name);
        });
    }

    public void labelParameterVersion(String name, long parameterVersion, List<String> labels, String region) {
        String storageKey = regionKey(region, name);
        if (parameterStore.get(storageKey).isEmpty()) {
            throw new AwsException("ParameterNotFound",
                    "Parameter " + name + " not found.", 400);
        }

        List<ParameterHistory> history = historyStore.get(storageKey)
                .orElse(List.of());

        history = new ArrayList<>(history);

        boolean found = false;
        for (ParameterHistory h : history) {
            if (h.getVersion() == parameterVersion) {
                List<String> existing = h.getLabels() != null ? new ArrayList<>(h.getLabels()) : new ArrayList<>();
                for (String label : labels) {
                    if (!existing.contains(label)) {
                        existing.add(label);
                    }
                }
                h.setLabels(existing);
                found = true;
                break;
            }
        }

        if (!found) {
            throw new AwsException("ParameterVersionNotFound", "Parameter version " + parameterVersion + " not found.", 400);
        }

        historyStore.put(storageKey, history);
        LOG.infov("Labeled parameter {0} version {1} with labels {2}", name, parameterVersion, labels);
    }

    public void addTagsToResource(String resourceId, Map<String, String> tags, String region) {
        String storageKey = regionKey(region, resourceId);
        Parameter param = parameterStore.get(storageKey)
                .orElseThrow(() -> new AwsException("InvalidResourceId",
                        "Resource " + resourceId + " not found.", 400));

        if (param.getTags() == null) {
            param.setTags(new HashMap<>());
        }
        param.getTags().putAll(tags);
        parameterStore.put(storageKey, param);
        LOG.debugv("Added tags to parameter: {0}", resourceId);
    }

    public Map<String, String> listTagsForResource(String resourceId, String region) {
        String storageKey = regionKey(region, resourceId);
        Parameter param = parameterStore.get(storageKey)
                .orElseThrow(() -> new AwsException("InvalidResourceId",
                        "Resource " + resourceId + " not found.", 400));
        return param.getTags() != null ? param.getTags() : Map.of();
    }

    public void removeTagsFromResource(String resourceId, List<String> tagKeys, String region) {
        String storageKey = regionKey(region, resourceId);
        Parameter param = parameterStore.get(storageKey)
                .orElseThrow(() -> new AwsException("InvalidResourceId",
                        "Resource " + resourceId + " not found.", 400));

        if (param.getTags() != null) {
            for (String key : tagKeys) {
                param.getTags().remove(key);
            }
            parameterStore.put(storageKey, param);
        }
        LOG.debugv("Removed tags from parameter: {0}", resourceId);
    }

    // ──────────────────────── Documents and Share Permissions ────────────────
    // Documents live in documentStore; share state is kept alongside it in
    // documentPermissionStore so callers like LZA's Custom::SSMShareDocument handler
    // can round-trip ModifyDocumentPermission -> DescribeDocumentPermission.
    // Both permission operations resolve the document first, so an unknown document
    // raises InvalidDocument instead of silently minting or reporting share state for
    // a document that does not exist. (ListDocuments still returns an empty list; it
    // is not wired to documentStore.)

    public SsmDocument getDocument(String name, String region) {
        return documentStore.get(regionKey(region, name))
                .orElseThrow(() -> new AwsException("InvalidDocument",
                        "Document " + name + " does not exist.", 400));
    }

    public synchronized SsmDocument createDocument(String name, String content, String documentType, String region) {
        String storageKey = regionKey(region, name);
        if (documentStore.get(storageKey).isPresent()) {
            throw new AwsException("DocumentAlreadyExists",
                    "Document " + name + " already exists.", 400);
        }
        SsmDocument document = new SsmDocument(name, content, documentType);
        documentStore.put(storageKey, document);
        return document;
    }

    public SsmDocument updateDocument(String name, String content, String region) {
        String storageKey = regionKey(region, name);
        SsmDocument document = documentStore.get(storageKey)
                .orElseThrow(() -> new AwsException("InvalidDocument",
                        "Document " + name + " does not exist.", 400));
        if (Objects.equals(document.getContent(), content)) {
            throw new AwsException("DuplicateDocumentContent",
                    "The content of the association document matches another document. "
                            + "Change the content of the document and try again.", 400);
        }
        document.setContent(content);
        document.setDocumentVersion(document.getDocumentVersion() + 1);
        documentStore.put(storageKey, document);
        return document;
    }

    /**
     * Lists the accounts a document is shared with. AWS raises InvalidDocument for a
     * document that does not exist rather than returning an empty list, so the document
     * is resolved first.
     */
    public List<String> describeDocumentPermission(String name, String region) {
        getDocument(name, region);
        return documentPermissionStore.get(regionKey(region, name))
                .map(List::copyOf)
                .orElse(List.of());
    }

    /**
     * Shares (or un-shares) a document with other accounts.
     *
     * <p>Only the document's owner may share it. Ownership here <em>is</em> the storage
     * partition: {@code documentStore} is an {@code AccountAwareStorageBackend}, whose key
     * prefix is the caller's account from {@code RequestContext} — the same resolution
     * {@code RegionResolver.getAccountId()} performs — so {@link #getDocument} can only
     * resolve a document in the caller's own partition. A caller that does not own the
     * document therefore gets InvalidDocument, which is AWS's answer for a document the
     * caller cannot see. Deriving the check from the partition rather than a second stored
     * owner field keeps the guard and the storage scope from ever disagreeing.
     */
    public synchronized void modifyDocumentPermission(String name, List<String> accountIdsToAdd,
                                         List<String> accountIdsToRemove, String region) {
        getDocument(name, region);
        String storageKey = regionKey(region, name);
        List<String> accountIds = new ArrayList<>(
                documentPermissionStore.get(storageKey).orElse(List.of()));
        for (String accountId : accountIdsToAdd) {
            if (!accountIds.contains(accountId)) {
                accountIds.add(accountId);
            }
        }
        accountIds.removeAll(accountIdsToRemove);
        documentPermissionStore.put(storageKey, accountIds);
        LOG.debugv("Modified document permission for {0}: {1} account(s) shared", name, accountIds.size());
    }

    // ──────────────────────────── Patch Baselines ────────────────────────────
    // AWS provides a fixed set of AWS-owned predefined patch baselines (one default per operating
    // system). These are static reference data, not customer state, so they live in-memory only.

    private static final List<PatchBaselineIdentity> PREDEFINED_BASELINES = buildPredefinedBaselines();

    private static List<PatchBaselineIdentity> buildPredefinedBaselines() {
        String[][] defs = {
                {"WINDOWS", "AWS-DefaultPatchBaseline", "Windows"},
                {"AMAZON_LINUX", "AWS-AmazonLinuxDefaultPatchBaseline", "Amazon Linux"},
                {"AMAZON_LINUX_2", "AWS-AmazonLinux2DefaultPatchBaseline", "Amazon Linux 2"},
                {"AMAZON_LINUX_2022", "AWS-AmazonLinux2022DefaultPatchBaseline", "Amazon Linux 2022"},
                {"AMAZON_LINUX_2023", "AWS-AmazonLinux2023DefaultPatchBaseline", "Amazon Linux 2023"},
                {"UBUNTU", "AWS-UbuntuDefaultPatchBaseline", "Ubuntu"},
                {"REDHAT_ENTERPRISE_LINUX", "AWS-RedHatDefaultPatchBaseline", "Red Hat Enterprise Linux"},
                {"SUSE", "AWS-SuseDefaultPatchBaseline", "SUSE Linux Enterprise Server"},
                {"CENTOS", "AWS-CentOSDefaultPatchBaseline", "CentOS"},
                {"ORACLE_LINUX", "AWS-OracleLinuxDefaultPatchBaseline", "Oracle Linux"},
                {"DEBIAN", "AWS-DebianDefaultPatchBaseline", "Debian Server"},
                {"MACOS", "AWS-MacOSDefaultPatchBaseline", "macOS"},
                {"RASPBIAN", "AWS-RaspbianDefaultPatchBaseline", "Raspbian"},
                {"ROCKY_LINUX", "AWS-RockyLinuxDefaultPatchBaseline", "Rocky Linux"},
                {"ALMA_LINUX", "AWS-AlmaLinuxDefaultPatchBaseline", "AlmaLinux"},
        };
        List<PatchBaselineIdentity> baselines = new ArrayList<>();
        for (String[] def : defs) {
            String os = def[0];
            String name = def[1];
            String description = "Default Patch Baseline for " + def[2] + " Provided by AWS.";
            baselines.add(new PatchBaselineIdentity(stableBaselineId(name), name, os, description, true));
        }
        return List.copyOf(baselines);
    }

    /** Deterministic AWS-style baseline id (pb-<17 hex>) derived from the baseline name. */
    private static String stableBaselineId(String name) {
        long h = 1125899906842597L;
        for (int i = 0; i < name.length(); i++) {
            h = 31 * h + name.charAt(i);
        }
        String hex = String.format("%016x", h & 0x0FFFFFFFFFFFFFFFL);
        return "pb-0" + hex;
    }

    /**
     * Return AWS-owned predefined patch baselines matching the given DescribePatchBaselines filters
     * (supported keys: OWNER, OPERATING_SYSTEM, NAME_PREFIX). There are no customer-owned baselines.
     */
    public List<PatchBaselineIdentity> describePatchBaselines(Map<String, List<String>> filters) {
        List<String> owners = filters.getOrDefault("OWNER", List.of());
        // OWNER=Self matches only customer-owned baselines, of which there are none.
        if (!owners.isEmpty() && !owners.contains("AWS") && !owners.contains("All")) {
            return List.of();
        }

        List<String> operatingSystems = filters.getOrDefault("OPERATING_SYSTEM", List.of());
        List<String> namePrefixes = filters.getOrDefault("NAME_PREFIX", List.of());

        return PREDEFINED_BASELINES.stream()
                .filter(b -> operatingSystems.isEmpty() || operatingSystems.contains(b.operatingSystem()))
                .filter(b -> namePrefixes.isEmpty()
                        || namePrefixes.stream().anyMatch(prefix -> b.baselineName().startsWith(prefix)))
                .toList();
    }

    /** Return the default patch baseline id for an operating system (defaults to WINDOWS). */
    public String getDefaultPatchBaseline(String operatingSystem) {
        String os = (operatingSystem == null || operatingSystem.isBlank()) ? "WINDOWS" : operatingSystem;
        return PREDEFINED_BASELINES.stream()
                .filter(b -> b.operatingSystem().equals(os))
                .findFirst()
                .map(PatchBaselineIdentity::baselineId)
                .orElseThrow(() -> new AwsException("DoesNotExistException",
                        "No default patch baseline exists for operating system " + os, 400));
    }

    /**
     * Read a service setting for the calling account. Never-customized settings
     * report their account default with status "Default".
     */
    public ServiceSetting getServiceSetting(String settingId, String region) {
        String defaultValue = requireKnownSetting(settingId);
        return serviceSettingStore.get(settingKey(region, settingId))
                .orElseGet(() -> defaultSetting(settingId, defaultValue, region));
    }

    public void updateServiceSetting(String settingId, String settingValue, String region) {
        requireKnownSetting(settingId);
        ServiceSetting setting = new ServiceSetting(settingId, settingValue,
                settingArn(settingId, region), "Customized",
                "arn:aws:iam::" + regionResolver.getAccountId() + ":root");
        serviceSettingStore.put(settingKey(region, settingId), setting);
    }

    public ServiceSetting resetServiceSetting(String settingId, String region) {
        String defaultValue = requireKnownSetting(settingId);
        serviceSettingStore.delete(settingKey(region, settingId));
        return defaultSetting(settingId, defaultValue, region);
    }

    private String requireKnownSetting(String settingId) {
        String defaultValue = SERVICE_SETTING_DEFAULTS.get(settingId);
        if (defaultValue == null) {
            throw new AwsException("ServiceSettingNotFound",
                    "The specified service setting was not found: " + settingId, 400);
        }
        return defaultValue;
    }

    private ServiceSetting defaultSetting(String settingId, String defaultValue, String region) {
        return new ServiceSetting(settingId, defaultValue, settingArn(settingId, region),
                "Default", "System");
    }

    /**
     * Service settings are per-account per-region: LZA assumes a role into each
     * member account before updating, so the caller's resolved account scopes the key.
     */
    private String settingKey(String region, String settingId) {
        return regionResolver.getAccountId() + "::" + regionKey(region, settingId);
    }

    private String settingArn(String settingId, String region) {
        // Setting ids begin with "/", so concatenation yields .../servicesetting/ssm/...
        return "arn:aws:ssm:" + region + ":" + regionResolver.getAccountId()
                + ":servicesetting" + settingId;
    }

    private static String regionKey(String region, String name) {
        return region + "::" + name;
    }

    private void addHistory(String storageKey, Parameter parameter) {
        List<ParameterHistory> history = historyStore.get(storageKey)
                .orElse(new ArrayList<>());

        history = new ArrayList<>(history);
        history.add(new ParameterHistory(parameter));

        while (history.size() > maxParameterHistory) {
            history.removeFirst();
        }

        historyStore.put(storageKey, history);
    }

    public synchronized void deleteDocument(String name, String region) {
        String storageKey = regionKey(region, name);
        if (!documentStore.get(storageKey).isPresent()) {
            throw new AwsException("InvalidDocument",
                    "Document " + name + " does not exist.", 400);
        }
        documentStore.delete(storageKey);
        documentPermissionStore.delete(storageKey);
    }

    // ─── Resource Explorer 2 ───────────────────────────────────────────────────

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (Parameter parameter : parameterStore.scan(k -> true)) {
            String arn = parameter.getArn();
            if (arn == null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            resources.add(new ExplorerResource(
                    arn, "ssm:parameter", "ssm",
                    parsed.region(), parsed.accountId(),
                    parameter.getLastModifiedDate() != null ? parameter.getLastModifiedDate() : Instant.now(),
                    parameter.getTags() != null ? parameter.getTags() : Map.of()));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("ssm:parameter", "ssm", true));
    }
}
