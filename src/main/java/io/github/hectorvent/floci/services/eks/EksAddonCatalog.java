package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.eks.model.AddonInfo;
import io.github.hectorvent.floci.services.eks.model.AddonVersionInfo;
import io.github.hectorvent.floci.services.eks.model.Compatibility;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@ApplicationScoped
public class EksAddonCatalog {

    private static final Logger LOG = Logger.getLogger(EksAddonCatalog.class);
    private static final String CATALOG_RESOURCE_NAME = "eks/addon-versions.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private volatile List<AddonInfo> addons;

    public EksAddonCatalog() {
        // Loaded lazily on first access.
    }

    public EksAddonCatalog(List<AddonInfo> addons) {
        this.addons = addons != null ? List.copyOf(addons) : List.of();
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CatalogWrapper(List<AddonInfo> addons) {}

    private List<AddonInfo> getAddons() {
        if (addons == null) {
            synchronized (this) {
                if (addons == null) {
                    addons = loadCatalog();
                }
            }
        }
        return addons;
    }

    private List<AddonInfo> loadCatalog() {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(CATALOG_RESOURCE_NAME)) {
            if (is == null) {
                LOG.warnv("EKS addon versions catalog resource not found: {0}", CATALOG_RESOURCE_NAME);
                return List.of();
            }
            CatalogWrapper wrapper = MAPPER.readValue(is, CatalogWrapper.class);
            return wrapper.addons() != null ? List.copyOf(wrapper.addons()) : List.of();
        } catch (IOException e) {
            LOG.errorv(e, "Failed to load EKS addon versions catalog from {0}", CATALOG_RESOURCE_NAME);
            return List.of();
        }
    }

    public List<AddonInfo> allAddons() {
        return getAddons();
    }

    public Optional<AddonInfo> findAddon(String addonName) {
        if (addonName == null || addonName.isBlank()) {
            return Optional.empty();
        }
        String normalized = addonName.trim();
        return getAddons().stream()
                .filter(a -> a.addonName().equalsIgnoreCase(normalized))
                .findFirst();
    }

    public boolean isKnownAddon(String addonName) {
        return findAddon(addonName).isPresent();
    }

    public boolean isVersionSupported(String addonName, String addonVersion) {
        if (addonVersion == null || addonVersion.isBlank()) {
            return false;
        }
        return findAddon(addonName)
                .map(AddonInfo::addonVersions)
                .orElse(List.of())
                .stream()
                .anyMatch(v -> v.addonVersion().equalsIgnoreCase(addonVersion.trim()));
    }

    public Optional<String> resolveDefaultVersion(String addonName, String kubernetesVersion) {
        List<AddonVersionInfo> versions = findAddon(addonName)
                .map(AddonInfo::addonVersions)
                .orElse(List.of());
        if (versions.isEmpty()) {
            return Optional.empty();
        }

        String k8s = kubernetesVersion != null && !kubernetesVersion.isBlank()
                ? normalizeK8sVersion(kubernetesVersion) : null;
        if (k8s != null) {
            Optional<String> def = versions.stream()
                    .filter(v -> isCompatible(v, k8s, true))
                    .map(AddonVersionInfo::addonVersion)
                    .findFirst();
            if (def.isPresent()) {
                return def;
            }
            Optional<String> any = versions.stream()
                    .filter(v -> isCompatible(v, k8s, false))
                    .map(AddonVersionInfo::addonVersion)
                    .findFirst();
            if (any.isPresent()) {
                return any;
            }
        }

        return versions.stream()
                .filter(v -> isCompatible(v, null, true))
                .map(AddonVersionInfo::addonVersion)
                .findFirst()
                .or(() -> Optional.of(versions.getFirst().addonVersion()));
    }

    public List<AddonInfo> describeVersions(String addonName, String kubernetesVersion,
                                            List<String> publishers, List<String> types, List<String> owners) {
        String k8s = kubernetesVersion != null && !kubernetesVersion.isBlank()
                ? normalizeK8sVersion(kubernetesVersion) : null;
        return getAddons().stream()
                .filter(a -> addonName == null || addonName.isBlank() || a.addonName().equalsIgnoreCase(addonName.trim()))
                .filter(a -> publishers == null || publishers.isEmpty() || publishers.contains(a.publisher()))
                .filter(a -> types == null || types.isEmpty() || types.contains(a.type()))
                .filter(a -> owners == null || owners.isEmpty() || owners.contains(a.owner()))
                .map(a -> k8s == null ? a : filterByK8s(a, k8s))
                .filter(Objects::nonNull)
                .toList();
    }

    private static AddonInfo filterByK8s(AddonInfo addon, String k8s) {
        List<AddonVersionInfo> filtered = addon.addonVersions().stream()
                .filter(v -> isCompatible(v, k8s, false))
                .toList();
        return filtered.isEmpty() ? null
                : new AddonInfo(addon.addonName(), addon.type(), filtered, addon.publisher(), addon.owner());
    }

    private static boolean isCompatible(AddonVersionInfo versionInfo, String k8s, boolean defaultOnly) {
        if (versionInfo.compatibilities() == null) {
            return false;
        }
        return versionInfo.compatibilities().stream().anyMatch(c ->
                (!defaultOnly || Boolean.TRUE.equals(c.defaultVersion()))
                && (k8s == null || k8s.equals(c.clusterVersion())));
    }

    private static String normalizeK8sVersion(String version) {
        if (version == null) {
            return "";
        }
        String v = version.trim();
        if (v.startsWith("v")) {
            v = v.substring(1);
        }
        String[] parts = v.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : v;
    }
}
