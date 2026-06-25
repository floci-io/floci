package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import io.github.hectorvent.floci.services.ssm.model.ParameterHistory;
import io.github.hectorvent.floci.services.ssm.model.PatchBaselineIdentity;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class SsmService {

    private static final Logger LOG = Logger.getLogger(SsmService.class);

    private final StorageBackend<String, Parameter> parameterStore;
    private final StorageBackend<String, List<ParameterHistory>> historyStore;
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
                config.services().ssm().maxParameterHistory(),
                regionResolver
        );
    }

    /**
     * Package-private constructor for testing without CDI.
     */
    SsmService(StorageBackend<String, Parameter> parameterStore,
               StorageBackend<String, List<ParameterHistory>> historyStore,
               int maxParameterHistory) {
        this(parameterStore, historyStore, maxParameterHistory,
                new RegionResolver("us-east-1", "000000000000"));
    }

    SsmService(StorageBackend<String, Parameter> parameterStore,
               StorageBackend<String, List<ParameterHistory>> historyStore,
               int maxParameterHistory, RegionResolver regionResolver) {
        this.parameterStore = parameterStore;
        this.historyStore = historyStore;
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
}
