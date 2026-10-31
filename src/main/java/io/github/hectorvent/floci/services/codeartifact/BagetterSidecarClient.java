package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
     * {@link #listVersions} already gives every version BaGetter holds for this package; the only
     * new work is a publish timestamp for each. BaGetter's flat container has none of its own
     * (confirmed: {@code index.json} carries only the version strings), but its NuGet V3
     * Registration resource's {@code catalogEntry.published} does, confirmed live against a real
     * container — read here purely as an internal data source for this one field, not by
     * standing up a registration endpoint of Floci's own the way {@code CodeArtifactNuGetController}
     * would (this class's own class-level documentation explains why that's deliberately never
     * built for real NuGet clients to use).
     */
    @Override
    public List<PackageVersionTimestamp> listPackageVersions(String repositoryContainerId, String domain,
            String repository, String namespace, String packageName) {
        String lowerId = packageName.toLowerCase(Locale.ROOT);
        List<String> versions = listVersions(repositoryContainerId, lowerId);
        if (versions.isEmpty()) {
            return List.of();
        }
        Map<String, Instant> published = registrationPublishedTimes(repositoryContainerId, lowerId);
        List<PackageVersionTimestamp> result = new ArrayList<>();
        for (String version : versions) {
            result.add(new PackageVersionTimestamp(version, published.get(version)));
        }
        return result;
    }

    /**
     * Every version's {@code catalogEntry.published}, keyed by version string. The real NuGet V3
     * Registration resource splits a package with many versions across separate pages (each
     * {@code items} entry carrying its own {@code @id} to fetch instead of an inline {@code
     * items} array once a package has enough versions), so a page missing an inline {@code items}
     * array is followed to its own {@code @id} rather than assumed to mean "no more versions" —
     * even though BaGetter, a single small self-hosted feed, was never observed paginating this
     * way for the handful of versions any test here publishes.
     *
     * <p>Keyed by {@link NuGetVersions#normalize}, not the raw {@code catalogEntry.version} text:
     * {@link #listPackageVersions}'s caller looks entries up by the version {@link #listVersions}
     * returns, which is itself normalized (lowercased, no leading zeros) from BaGetter's flat
     * container resource. A raw-cased or non-normalized key here (e.g. {@code 1.0.0-Beta} instead
     * of {@code 1.0.0-beta}) would simply never be found, losing that version's timestamp.
     */
    private Map<String, Instant> registrationPublishedTimes(String repositoryContainerId, String lowerId) {
        Optional<JsonNode> index = getJson(repositoryContainerId, "/v3/registration/"
                + SidecarUriUtils.encodeSegment(lowerId) + "/index.json");
        if (index.isEmpty()) {
            return Map.of();
        }
        Map<String, Instant> result = new LinkedHashMap<>();
        for (JsonNode page : index.get().path("items")) {
            JsonNode items = page.path("items");
            if (!items.isArray()) {
                String pageId = page.path("@id").asText("");
                items = pageId.isEmpty() ? null : getJsonAbsolute(pageId).map(p -> p.path("items")).orElse(null);
                if (items == null) {
                    continue;
                }
            }
            for (JsonNode entry : items) {
                JsonNode catalogEntry = entry.path("catalogEntry");
                String version = catalogEntry.path("version").asText("");
                String publishedText = catalogEntry.path("published").asText("");
                if (version.isEmpty() || publishedText.isEmpty()) {
                    continue;
                }
                try {
                    result.put(NuGetVersions.normalize(version), OffsetDateTime.parse(publishedText).toInstant());
                } catch (DateTimeParseException e) {
                    // Leave unmapped: CodeArtifactService falls back to a deterministic tie-break
                    // rather than guessing a time from text that didn't parse.
                }
            }
        }
        return result;
    }

    /**
     * BaGetter's NuGet V3 Search resource ({@code /v3/search}), pressed into service purely as an
     * admin-side enumeration for this management API, a different purpose from the native NuGet
     * client protocol this class's own class-level documentation explains it deliberately never
     * stands up Search to serve. An empty {@code q} matches every package (confirmed live); {@code
     * data} empties out once every page has been read, the loop's own termination signal rather
     * than {@code totalHits}, which was confirmed live to report results *remaining from the
     * current skip*, not a stable grand total, so trusting it as a loop bound would stop one page
     * early. {@code id} is returned real-cased by BaGetter (confirmed live), lowercased here to
     * match what {@code CodeArtifactService}'s own {@code accountingPackageName} already
     * normalizes every NuGet package name to (AWS's own documented NuGet normalization). NuGet
     * packages have no namespace.
     *
     * <p>Raised by Greptile's review: the real NuGet V3 Search resource excludes prerelease
     * versions whenever {@code prerelease} is omitted (the official API's own documented default),
     * so a package whose only published version is a prerelease (e.g. {@code 1.0.0-beta}) would
     * never appear here without it. {@code prerelease=true} is passed explicitly since this call
     * enumerates every package a repository holds, regardless of what any one version's own
     * stability looks like, not a client-facing search that should default to stable results.
     *
     * <p>Raised by hectorvent's review: every CodeArtifact repository gets a nuget sidecar id at
     * creation regardless of whether it is ever actually used through nuget, so without checking
     * {@link BagetterSidecarManager#isRunning} first, a {@code ListPackages} call with no format
     * filter would start this repository's own BaGetter container just to find it empty, every
     * time, for a repository that has never published anything through nuget.
     */
    @Override
    public List<PackageIdentifier> listPackages(String repositoryContainerId, String domain, String repository) {
        if (!manager.isRunning(repositoryContainerId)) {
            return List.of();
        }
        List<PackageIdentifier> result = new ArrayList<>();
        int skip = 0;
        int take = 100;
        while (true) {
            JsonNode page = getJson(repositoryContainerId, "/v3/search?q=&prerelease=true&skip=" + skip + "&take="
                    + take).orElseThrow(() -> new IllegalStateException("Could not list packages on the BaGetter "
                            + "sidecar"));
            JsonNode data = page.path("data");
            if (!data.isArray() || data.isEmpty()) {
                return result;
            }
            for (JsonNode entry : data) {
                result.add(new PackageIdentifier(null, entry.path("id").asText("").toLowerCase(Locale.ROOT)));
            }
            skip += data.size();
        }
    }

    private Optional<JsonNode> getJson(String repositoryContainerId, String encodedPath) {
        String baseUrl = ensureReady(repositoryContainerId, null);
        return getJsonAbsolute(baseUrl + encodedPath);
    }

    private Optional<JsonNode> getJsonAbsolute(String absoluteUrl) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(absoluteUrl)).timeout(Duration.ofSeconds(10))
                .GET().build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the BaGetter sidecar at " + absoluteUrl, e);
        }
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not reach the BaGetter sidecar at " + absoluteUrl
                    + ": upstream returned " + response.statusCode());
        }
        try {
            return Optional.of(mapper.readTree(response.body()));
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse the BaGetter sidecar's response from " + absoluteUrl,
                    e);
        }
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
     * NuGet's one asset per version is deterministically named (AWS's own normalization rule,
     * already relied on by {@link #fetchPackageVersionAsset}), so enumerating it for {@code
     * CopyPackageVersions} needs only confirm the version exists and name it the same way; empty
     * when it doesn't.
     */
    @Override
    public List<String> listPackageVersionAssetNames(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version) {
        String lowerId = packageName.toLowerCase(Locale.ROOT);
        Optional<String> normalizedVersion = matchVersion(repositoryContainerId, lowerId, version);
        return normalizedVersion.map(v -> List.of(lowerId + "." + v + ".nupkg")).orElse(List.of());
    }

    /**
     * Pushes {@code content} (the whole {@code .nupkg}, NuGet's one asset per version) through
     * the real push wire shape {@code CodeArtifactNuGetController#push} already forwards
     * unmodified, confirmed against a live BaGetter container: a {@code multipart/form-data}
     * {@code PUT} to {@code /api/v2/package} whose first (only, here) part is the raw package
     * bytes — no separate {@code name}/{@code version} form field, since BaGetter reads both from
     * the {@code .nuspec} embedded in the package itself, not from {@code assetName} or this
     * method's own {@code packageName}/{@code version} parameters.
     *
     * <p>Rejected outright with a real {@code 409} ("a package with this id and version already
     * exists") if this exact id/version already exists, confirmed live, even for byte-identical
     * content: unlike Maven's Reposilite, BaGetter has no idempotent-retry short-circuit of its
     * own ({@code CodeArtifactNuGetController#push} does its own pre-check precisely because of
     * this). The caller ({@code CodeArtifactService#copyContainerBackedPackageVersions}) already
     * short-circuits an identical-content version as a no-op before ever reaching here, and is
     * expected to have deleted a conflicting, differing existing version first for a genuine
     * overwrite, the same reason {@code ReposiliteSidecarClient#storePackageVersionAsset}
     * documents for Maven.
     *
     * <p>Runs inside {@link #lockFor}, the same lock {@link #deletePackage} and {@code
     * CodeArtifactNuGetController#push} both already take for this repository: without it, a
     * native push racing this exact id/version could interleave with this call's own write.
     */
    @Override
    public void storePackageVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName, byte[] content) {
        synchronized (lockFor(repositoryContainerId)) {
            String baseUrl = ensureReady(repositoryContainerId, null);
            String boundary = "flociNuGetCopyBoundary" + System.nanoTime();
            byte[] head = ("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"package\"; filename=\"" + assetName + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] body = new byte[head.length + content.length + tail.length];
            System.arraycopy(head, 0, body, 0, head.length);
            System.arraycopy(content, 0, body, head.length, content.length);
            System.arraycopy(tail, 0, body, head.length + content.length, tail.length);

            HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl),
                            "/api/v2/package"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .PUT(BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, BodyHandlers.ofString());
            } catch (Exception e) {
                throw new IllegalStateException("Could not reach the BaGetter sidecar to push " + assetName, e);
            }
            if (response.statusCode() != 200 && response.statusCode() != 201 && response.statusCode() != 202) {
                throw new IllegalStateException("Could not push " + assetName + " to the BaGetter sidecar: "
                        + "upstream returned " + response.statusCode());
            }
        }
    }

    /**
     * Deletes the one version this asset belongs to, confirmed against a live container: NuGet
     * has exactly one asset per version, so there is no finer grain to delete at than {@link
     * #deletePackage}'s own per-version {@code DELETE /api/v2/package/<id>/<version>} already
     * uses. Runs inside the same {@link #lockFor} lock {@link #storePackageVersionAsset} and
     * {@link #deletePackage} both take, for the same reason.
     */
    @Override
    public void deletePackageVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName) {
        synchronized (lockFor(repositoryContainerId)) {
            String lowerId = packageName.toLowerCase(Locale.ROOT);
            String normalizedVersion = NuGetVersions.normalize(version);
            String baseUrl = ensureReady(repositoryContainerId, null);
            URI uri = SidecarUriUtils.combine(URI.create(baseUrl), "/api/v2/package/"
                    + SidecarUriUtils.encodeSegment(lowerId) + "/" + SidecarUriUtils.encodeSegment(normalizedVersion));
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).DELETE().build();
            HttpResponse<Void> response;
            try {
                response = httpClient.send(request, BodyHandlers.discarding());
            } catch (Exception e) {
                throw new IllegalStateException("Could not reach the BaGetter sidecar to delete " + packageName
                        + " " + version + "'s asset " + assetName, e);
            }
            if (response.statusCode() != 204 && response.statusCode() != 404) {
                throw new IllegalStateException("Could not delete " + packageName + " " + version + "'s asset "
                        + assetName + " from the BaGetter sidecar: upstream returned " + response.statusCode());
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
