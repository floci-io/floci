package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The HTTP wire protocol for a BaGetter sidecar: the NuGet V3 Package Content resource (flat
 * container) and the V2-shaped publish resource BaGetter's push/delete endpoints still use.
 * Container lifecycle (start, readiness, stop) is {@link BagetterSidecarManager}'s job; this class
 * asks it for a ready base URL and speaks BaGetter's own protocol against it.
 *
 * <p>Deliberately does not implement the NuGet V3 Registration or Search resources:
 * {@code nuget install}/{@code dotnet add package} both resolve a package's versions and
 * dependencies from the Package Content resource alone (confirmed directly: NuGet's own V3
 * client falls back to the flat container's {@code index.json} plus each version's {@code
 * .nuspec} when a source advertises no {@code RegistrationsBaseUrl} resource), and AWS's own
 * CodeArtifact User Guide documents {@code nuget list} itself as unsupported against CodeArtifact
 * regardless (only V3 of the protocol works at all), which is exactly what Search would back.
 * {@code CodeArtifactNuGetController} authors its own service index rather than proxying BaGetter's,
 * so this never needs to rewrite a self-referential URL out of a BaGetter response the way
 * {@code PypiserverSidecarClient} rewrites pypiserver's simple-index HTML.
 */
@ApplicationScoped
public class BagetterSidecarClient implements RepositorySidecarManager {

    private final BagetterSidecarManager manager;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    // Shared with CodeArtifactNuGetController's own push handler via lockFor(), keyed by
    // repository container id: without one lock both paths take, a native push that lists a
    // not-yet-deleted version and a DeletePackage's own version-list snapshot can interleave,
    // letting a version pushed in that window survive a DeletePackage that already "succeeded".
    private final ConcurrentHashMap<String, Object> repositoryLocks = new ConcurrentHashMap<>();

    @Inject
    public BagetterSidecarClient(BagetterSidecarManager manager, ObjectMapper mapper) {
        this(manager, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    BagetterSidecarClient(BagetterSidecarManager manager, ObjectMapper mapper, HttpClient httpClient) {
        this.manager = manager;
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    @Override
    public String format() {
        return "nuget";
    }

    @Override
    public String ensureReady(String repositoryContainerId, String publicUrl) {
        return manager.ensureReady(repositoryContainerId, publicUrl);
    }

    @Override
    public void release(String repositoryContainerId) {
        manager.release(repositoryContainerId);
    }

    /**
     * NuGet packages have no namespace (AWS: "Python, NuGet, Ruby, and Cargo package versions do
     * not contain a corresponding component") and exactly one real asset per version, the
     * {@code .nupkg} file itself; AWS's own NuGet asset-name-normalization doc says this name is
     * constructed from the normalized package name and version, so {@code assetName} is checked
     * against that exact, deterministic value rather than fetched unconditionally: a caller asking
     * for any other name is asking for an asset that was never, and could never be, published.
     */
    @Override
    public Optional<byte[]> fetchPackageVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName) {
        String lowerId = packageName.toLowerCase(Locale.ROOT);
        Optional<String> normalizedVersion = matchVersion(repositoryContainerId, lowerId, version);
        if (normalizedVersion.isEmpty()) {
            return Optional.empty();
        }
        String expectedAssetName = lowerId + "." + normalizedVersion.get() + ".nupkg";
        if (!expectedAssetName.equals(assetName)) {
            return Optional.empty();
        }
        return getBytes(repositoryContainerId, "/v3/package/" + SidecarUriUtils.encodeSegment(lowerId) + "/"
                + SidecarUriUtils.encodeSegment(normalizedVersion.get()) + "/"
                + SidecarUriUtils.encodeSegment(expectedAssetName));
    }

    @Override
    public boolean packageExists(String repositoryContainerId, String domain, String repository, String namespace,
            String packageName) {
        return !listVersions(repositoryContainerId, packageName.toLowerCase(Locale.ROOT)).isEmpty();
    }

    /**
     * Bridges {@code DescribePackageVersion}: reuses {@link #matchVersion}, the same normalized
     * lookup {@link #fetchPackageVersionAsset} and {@link #fetchPackageVersionReadme} already use
     * against BaGetter's own flat container listing, so a caller-supplied version in any form
     * BaGetter itself would accept (leading zeroes, a trailing zero fourth segment, build
     * metadata) resolves the same way here as it does for those two.
     */
    @Override
    public boolean packageVersionExists(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version) {
        return matchVersion(repositoryContainerId, packageName.toLowerCase(Locale.ROOT), version).isPresent();
    }

    /**
     * Deletes every version of the named package, confirmed against a live container.
     *
     * <p>Runs inside {@link #lockFor}: without it, a native push that lists the (not yet
     * updated) version set after this method's own {@link #listVersions} snapshot, but finishes
     * before this method's delete loop does, would add a version this call never knew to delete,
     * leaving it behind even though {@code DeletePackage} already reported success.
     */
    @Override
    public void deletePackage(String repositoryContainerId, String domain, String repository, String namespace,
            String packageName) {
        synchronized (lockFor(repositoryContainerId)) {
            String lowerId = packageName.toLowerCase(Locale.ROOT);
            String baseUrl = ensureReady(repositoryContainerId, null);
            for (String version : listVersions(repositoryContainerId, lowerId)) {
                URI uri = SidecarUriUtils.combine(URI.create(baseUrl), "/api/v2/package/"
                        + SidecarUriUtils.encodeSegment(lowerId) + "/" + SidecarUriUtils.encodeSegment(version));
                HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).DELETE().build();
                HttpResponse<Void> response;
                try {
                    response = httpClient.send(request, BodyHandlers.discarding());
                } catch (Exception e) {
                    throw new IllegalStateException("Could not reach the BaGetter sidecar to delete " + packageName
                            + " " + version, e);
                }
                // 404 means this exact version is already gone: a sibling file deleted in an
                // earlier iteration can't cause this (NuGet has one asset per version, unlike
                // pypi), and a concurrent delete of the same package can't either now that both
                // paths share this lock.
                if (response.statusCode() != 204 && response.statusCode() != 404) {
                    throw new IllegalStateException("Could not delete " + packageName + " " + version + " from the "
                            + "BaGetter sidecar: upstream returned " + response.statusCode());
                }
            }
        }
    }

    /**
     * The lock {@link #deletePackage} and {@code CodeArtifactNuGetController}'s own push handler
     * both take for one repository, so a push and a delete for the same repository can never
     * interleave; different repositories never block each other. Package-private so the
     * controller can synchronize its own push-or-conflict check and forwarded write on the exact
     * same lock this method does, the only way either one closes the race against the other.
     */
    Object lockFor(String repositoryContainerId) {
        return repositoryLocks.computeIfAbsent(repositoryContainerId, k -> new Object());
    }

    /**
     * {@code empty()} only when {@code version} genuinely was never published; a published version
     * with no readme file embedded answers {@code Optional.of("")} rather than {@code empty()}, the
     * same empty-string parity already established for npm and pypi (a version republished without
     * a readme records nothing to look up otherwise, which would 404 a version that genuinely
     * exists). Unlike npm and pypi, this is answered live against the sidecar rather than from a
     * record captured at publish time: BaGetter, unlike Verdaccio and pypiserver, keeps every
     * version's own readme (if the nuspec declared one via {@code <readme>}) addressable by exact
     * version for as long as that version exists, confirmed directly against BaGetter's own package
     * content controller, so there is nothing to lose by not capturing it eagerly.
     *
     * <p>Falls back to the nuspec {@code <description>} element, a mandatory field in every valid
     * nuspec, when the version exists but embeds no separate readme file: AWS documents this
     * action as returning "the readme file or descriptive text for a package version", exactly
     * this two-tier fallback.
     */
    @Override
    public Optional<String> fetchPackageVersionReadme(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version) {
        String lowerId = packageName.toLowerCase(Locale.ROOT);
        Optional<String> normalizedVersion = matchVersion(repositoryContainerId, lowerId, version);
        if (normalizedVersion.isEmpty()) {
            return Optional.empty();
        }
        Optional<byte[]> readme = getBytes(repositoryContainerId, "/v3/package/"
                + SidecarUriUtils.encodeSegment(lowerId) + "/" + SidecarUriUtils.encodeSegment(normalizedVersion.get())
                + "/readme");
        if (readme.isPresent()) {
            return Optional.of(new String(readme.get(), StandardCharsets.UTF_8));
        }
        Optional<byte[]> nuspec = getBytes(repositoryContainerId, "/v3/package/"
                + SidecarUriUtils.encodeSegment(lowerId) + "/" + SidecarUriUtils.encodeSegment(normalizedVersion.get())
                + "/" + SidecarUriUtils.encodeSegment(lowerId) + ".nuspec");
        if (nuspec.isEmpty()) {
            // The version was just confirmed to exist via matchVersion above; a nuspec that
            // vanishes in the narrow window between that check and this fetch is a genuine
            // mid-flight race (a concurrent DeletePackage), not a version that never existed.
            return Optional.of("");
        }
        return Optional.of(NuspecXml.parse(nuspec.get()).description().orElse(""));
    }

    /**
     * {@code empty()} when {@code version} doesn't match any version BaGetter actually holds for this
     * package, its own normalized form otherwise. BaGetter lists each version it holds already
     * normalized and lowercased (confirmed directly against its own {@code PackageContentController
     * .GetPackageVersionsAsync}), so {@code version} is matched against that list under the same
     * normalization applied to both sides rather than trusted as already being in that exact form:
     * a caller is free to ask for {@code 1.00.0} and get the version BaGetter itself calls {@code
     * 1.0.0}, the same leading-zero and trailing-zero normalization AWS documents for NuGet.
     */
    private Optional<String> matchVersion(String repositoryContainerId, String lowerId, String version) {
        String wanted = NuGetVersions.normalize(version);
        for (String candidate : listVersions(repositoryContainerId, lowerId)) {
            if (candidate.equals(wanted)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private List<String> listVersions(String repositoryContainerId, String lowerId) {
        String baseUrl = ensureReady(repositoryContainerId, null);
        URI uri = SidecarUriUtils.combine(URI.create(baseUrl), "/v3/package/" + SidecarUriUtils.encodeSegment(lowerId)
                + "/index.json");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the BaGetter sidecar to list versions of " + lowerId, e);
        }
        if (response.statusCode() == 404) {
            return List.of();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not list versions of " + lowerId + " on the BaGetter sidecar: "
                    + "upstream returned " + response.statusCode());
        }
        JsonNode versions;
        try {
            versions = mapper.readTree(response.body()).path("versions");
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse BaGetter's version listing for " + lowerId, e);
        }
        return versions.isArray()
                ? versions.valueStream().map(JsonNode::asText).toList()
                : List.of();
    }

    private Optional<byte[]> getBytes(String repositoryContainerId, String encodedPath) {
        String baseUrl = ensureReady(repositoryContainerId, null);
        HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl), encodedPath))
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the BaGetter sidecar at " + encodedPath, e);
        }
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not fetch " + encodedPath + " from the BaGetter sidecar: upstream "
                    + "returned " + response.statusCode());
        }
        return Optional.of(response.body());
    }
}
