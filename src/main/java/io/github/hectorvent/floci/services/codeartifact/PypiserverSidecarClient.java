package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The HTTP wire protocol for a pypiserver sidecar: simple-index lookups and flat package-file
 * fetches. Container lifecycle (start, readiness, stop) is {@link PypiserverSidecarManager}'s job;
 * this class asks it for a ready base URL and speaks pypiserver's own protocol against it.
 */
@ApplicationScoped
public class PypiserverSidecarClient implements RepositorySidecarManager {

    private static final Pattern SIMPLE_INDEX_HREF = Pattern.compile("href=\"([^\"]*)\"");

    private final PypiserverSidecarManager manager;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    @Inject
    public PypiserverSidecarClient(PypiserverSidecarManager manager, ObjectMapper mapper) {
        this(manager, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    PypiserverSidecarClient(PypiserverSidecarManager manager, ObjectMapper mapper, HttpClient httpClient) {
        this.manager = manager;
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    @Override
    public String format() {
        return "pypi";
    }

    @Override
    public String ensureReady(String repositoryContainerId, String publicUrl) {
        return manager.ensureReady(repositoryContainerId, publicUrl);
    }

    @Override
    public void release(String repositoryContainerId) {
        manager.release(repositoryContainerId);
        SidecarConcurrency.release(repositoryContainerId);
    }

    /**
     * PyPI packages have no namespace (AWS: "Python ... package versions do not have a
     * corresponding component"). pypiserver serves every file it holds flatly at
     * {@code /packages/<filename>} regardless of which package or version it belongs to,
     * confirmed against a live container, so both are verified before serving anything: that
     * {@code packageName}'s own simple index genuinely lists {@code assetName}, and that
     * {@code assetName} itself embeds {@code version} the way every real PyPI filename does
     * (PEP 427/517 naming) rather than trusting the caller's {@code version} argument on its own.
     *
     * <p>{@code assetName} is percent-encoded via {@link SidecarUriUtils#encodeSegment(String)}
     * before joining it to the fixed {@code /packages/} prefix: without that, a {@code /} inside
     * it would be indistinguishable from a real path separator and could splice in extra path
     * segments.
     */
    @Override
    public Optional<byte[]> fetchPackageVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName) {
        if (!filenameEmbedsVersion(assetName, packageName, version)) {
            return Optional.empty();
        }
        String baseUrl = ensureReady(repositoryContainerId, null);
        if (!packageIndexListsAsset(baseUrl, packageName, assetName)) {
            return Optional.empty();
        }
        URI uri = SidecarUriUtils.combine(URI.create(baseUrl), "/packages/" + SidecarUriUtils.encodeSegment(assetName));
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the pypiserver sidecar to fetch " + assetName, e);
        }
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not fetch " + assetName + " from the pypiserver sidecar: "
                    + "upstream returned " + response.statusCode());
        }
        return Optional.of(response.body());
    }

    /**
     * PEP 503 normalization: lowercase, runs of {@code -_.} collapsed to one {@code -}, same as
     * {@code CodeArtifactPypiController}'s own pre-upload index check. {@code -} and {@code _}
     * collapsing to the same character here is what lets this match a filename's own escaped
     * name portion (wheels, and modern sdists, replace a name's internal {@code -}/{@code .}
     * with {@code _}) against the caller-supplied, unescaped {@code packageName}.
     */
    private static String normalizePackageName(String packageName) {
        return packageName.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }

    @Override
    public boolean packageExists(String repositoryContainerId, String domain, String repository, String namespace,
            String packageName) {
        String baseUrl = ensureReady(repositoryContainerId, null);
        return simpleIndex(baseUrl, packageName).isPresent();
    }

    /**
     * Reuses the same per-project JSON listing {@link #deletePackage} already reads (grouped by
     * release the same way the real PyPI JSON API is), rather than the simple index: the simple
     * index has no per-file version field of its own to group by, only filenames that would need
     * the same version-boundary parsing {@link #filenameEmbedsVersion} does for a known asset name,
     * which isn't available here.
     */
    @Override
    public boolean packageVersionExists(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version) {
        String baseUrl = ensureReady(repositoryContainerId, null);
        return packageFiles(baseUrl, packageName).stream().anyMatch(file -> file.version().equals(version));
    }

    /**
     * pypiserver's own per-project JSON listing ({@link #packageFiles}'s source, read directly
     * here by the {@code releases} map's own keys instead) has no timestamp field of its own
     * (confirmed against a live container), so this follows each version's first listed file's
     * download URL with a plain {@code HEAD} and reads the real {@code Last-Modified} response
     * header it returns. Only one file per version needs checking: every file of the same release
     * lands on disk within the same upload, close enough that picking "the first one" over "the
     * most recently modified one" makes no practical difference, and the JSON listing gives no
     * way to know which upload order they actually arrived in regardless.
     *
     * <p>The per-version {@code HEAD} requests run through {@link SidecarConcurrency#mapBounded},
     * not a plain sequential loop: a package with many versions used to make every one of those
     * requests one at a time before returning even a single-result page. Keyed by {@code
     * repositoryContainerId} so the concurrency cap holds across simultaneous calls against the
     * same pypiserver container, not just within one of them.
     *
     * <p>The whole method runs between {@link SidecarConcurrency#beginUse} and {@link
     * SidecarConcurrency#endUse}, not only the {@code mapBounded} call: a repository delete racing
     * this call's own HTTP fetch of the version listing, before {@code mapBounded} is even reached,
     * must not be able to run {@link SidecarConcurrency#release} to completion in between, or {@code
     * mapBounded} would cache a fresh permit for a container that is already gone.
     */
    @Override
    public List<PackageVersionTimestamp> listPackageVersions(String repositoryContainerId, String domain,
            String repository, String namespace, String packageName) {
        SidecarConcurrency.beginUse(repositoryContainerId);
        try {
            String baseUrl = ensureReady(repositoryContainerId, null);
            String normalized = normalizePackageName(packageName);
            URI uri = SidecarUriUtils.combine(URI.create(baseUrl), "/" + SidecarUriUtils.encodeSegment(normalized)
                    + "/json");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, BodyHandlers.ofString());
            } catch (Exception e) {
                throw new IllegalStateException("Could not reach the pypiserver sidecar to list versions of "
                        + packageName, e);
            }
            if (response.statusCode() == 404) {
                return List.of();
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Could not list versions of " + packageName + " on the pypiserver "
                        + "sidecar: upstream returned " + response.statusCode());
            }
            JsonNode releases;
            try {
                releases = mapper.readTree(response.body()).path("releases");
            } catch (Exception e) {
                throw new IllegalStateException("Could not parse pypiserver's version listing for " + packageName,
                        e);
            }
            List<String> versions = new ArrayList<>();
            releases.fieldNames().forEachRemaining(versions::add);
            return SidecarConcurrency.mapBounded(repositoryContainerId, versions, version -> {
                JsonNode files = releases.path(version);
                String fileUrl = files.isEmpty() ? "" : files.get(0).path("url").asText("");
                return new PackageVersionTimestamp(version, fileUrl.isEmpty() ? null : lastModified(fileUrl));
            });
        } finally {
            SidecarConcurrency.endUse(repositoryContainerId);
        }
    }

    /**
     * Delegates to {@link SidecarConcurrency#beginUse}: {@code CodeArtifactService} calls this
     * right after it resolves {@code repositoryContainerId}, closing the window between that
     * resolution and this client's own {@link #listPackageVersions} call, which brackets the
     * same key again internally. Nesting is safe: {@link SidecarConcurrency}'s bookkeeping is a
     * reference count, not a flag.
     */
    @Override
    public void beginUse(String repositoryContainerId) {
        SidecarConcurrency.beginUse(repositoryContainerId);
    }

    /** Delegates to {@link SidecarConcurrency#endUse}, pairing {@link #beginUse} above. */
    @Override
    public void endUse(String repositoryContainerId) {
        SidecarConcurrency.endUse(repositoryContainerId);
    }

    private Instant lastModified(String fileUrl) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(fileUrl)).timeout(Duration.ofSeconds(10))
                .method("HEAD", BodyPublishers.noBody()).build();
        HttpResponse<Void> response;
        try {
            response = httpClient.send(request, BodyHandlers.discarding());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the pypiserver sidecar to check " + fileUrl, e);
        }
        if (response.statusCode() != 200) {
            return null;
        }
        return response.headers().firstValue("Last-Modified").flatMap(value -> {
            try {
                return Optional.of(Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value)));
            } catch (DateTimeParseException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    /**
     * Removes a whole package, every version at once, confirmed against a live container: the pinned
     * image's management endpoint ({@code POST /} with {@code :action=remove_pkg}) deletes one exact
     * {@code name}/{@code version} pair per call rather than a whole package or project at once, so
     * every file is first listed from the same PyPI JSON API endpoint this image also serves
     * ({@code GET /<project>/json}, grouped by the same normalized project the simple index uses),
     * then each one removed in turn.
     *
     * <p>{@code name} for each call is recovered from the filename that endpoint listed, not from the
     * caller's {@code packageName}: {@code remove_pkg} matches against the literal, unnormalized name
     * the server itself derived when that file was uploaded, and an sdist and a wheel of the very same
     * release can legitimately differ there (a hyphen in one, an underscore in the other), so deleting
     * by one filename's recovered name alone can silently leave the other file behind.
     */
    @Override
    public void deletePackage(String repositoryContainerId, String domain, String repository, String namespace,
            String packageName) {
        String baseUrl = ensureReady(repositoryContainerId, null);
        for (PkgNameVersion file : packageFiles(baseUrl, packageName)) {
            removeVersion(baseUrl, file.name(), file.version());
        }
    }

    /**
     * Every filename pypiserver's own per-project JSON listing records for exactly {@code
     * version}, for bridging {@code CopyPackageVersions} when there is no generic-format record
     * to enumerate assets from. Reuses the same {@code GET /<project>/json} endpoint {@link
     * #packageFiles} already does, this time keeping the filename itself rather than discarding
     * it for the recovered raw name {@link #packageFiles} needs.
     */
    @Override
    public List<String> listPackageVersionAssetNames(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version) {
        String baseUrl = ensureReady(repositoryContainerId, null);
        String normalized = normalizePackageName(packageName);
        URI uri = SidecarUriUtils.combine(URI.create(baseUrl), "/" + SidecarUriUtils.encodeSegment(normalized)
                + "/json");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the pypiserver sidecar to list versions of "
                    + packageName, e);
        }
        if (response.statusCode() == 404) {
            return List.of();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not list versions of " + packageName + " on the pypiserver "
                    + "sidecar: upstream returned " + response.statusCode());
        }
        JsonNode release;
        try {
            release = mapper.readTree(response.body()).path("releases").path(version);
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse pypiserver's version listing for " + packageName, e);
        }
        List<String> filenames = new ArrayList<>();
        for (JsonNode file : release) {
            String url = file.path("url").asText("");
            String filename = url.substring(url.lastIndexOf('/') + 1);
            if (!filename.isEmpty()) {
                filenames.add(filename);
            }
        }
        return filenames;
    }

    /**
     * Uploads {@code content} as {@code assetName} the same real {@code twine upload} wire shape
     * {@code CodeArtifactPypiController#upload} already forwards unmodified, confirmed against a
     * live pypiserver container: a {@code multipart/form-data} {@code POST} to the repository
     * root with a {@code :action=file_upload} field (pypiserver rejects the request outright
     * without it, "Missing ':action' field!", even though neither twine's own real request nor
     * this sidecar's existing upload-forwarding path ever has to name it explicitly), {@code
     * name}/{@code version} fields, and the file itself as the {@code content} part. pypiserver
     * indexes a package by parsing {@code assetName} itself (PEP 427/517 naming), not the
     * {@code name} field, so a mismatched filename silently publishes under whatever name the
     * filename itself implies instead of {@code packageName} — the caller is expected to pass a
     * {@code version}-embedding {@code assetName} the same way {@link #fetchPackageVersionAsset}
     * already requires one to read it back.
     *
     * <p>Rejected outright with a real {@code 409} ("already exists") if {@code assetName}
     * already exists with different content, confirmed live: this container is started without
     * pypiserver's own {@code --overwrite} flag, the same as production. The caller ({@code
     * CodeArtifactService#copyContainerBackedPackageVersions}) is expected to have already
     * deleted a conflicting existing asset first for an overwrite, the same reason {@code
     * ReposiliteSidecarClient#storePackageVersionAsset} documents for Maven.
     */
    @Override
    public void storePackageVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName, byte[] content) {
        String baseUrl = ensureReady(repositoryContainerId, null);
        String boundary = "flociPypiCopyBoundary" + System.nanoTime();
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\":action\"\r\n\r\nfile_upload\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"name\"\r\n\r\n" + packageName + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"version\"\r\n\r\n" + version + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"content\"; filename=\"" + assetName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + content.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(content, 0, body, head.length, content.length);
        System.arraycopy(tail, 0, body, head.length + content.length, tail.length);

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the pypiserver sidecar to publish " + assetName, e);
        }
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IllegalStateException("Could not publish " + assetName + " to the pypiserver sidecar: "
                    + "upstream returned " + response.statusCode());
        }
    }

    /**
     * Removes one asset via pypiserver's own {@code remove_pkg} management call, confirmed live
     * to delete by raw name/version rather than by filename: {@link #rawPkgname} recovers the
     * exact unnormalized name {@code remove_pkg} needs from {@code assetName} itself, the same way
     * {@link #deletePackage}'s own loop already does per file. A sibling file that happens to
     * share the exact same raw name and version as {@code assetName} is removed in the same call
     * (confirmed live for {@link #deletePackage}'s own multi-file case); pypiserver has no finer
     * grain than that to ask for.
     */
    @Override
    public void deletePackageVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName) {
        String baseUrl = ensureReady(repositoryContainerId, null);
        removeVersion(baseUrl, rawPkgname(assetName, version), version);
    }

    private record PkgNameVersion(String name, String version) {}

    private Set<PkgNameVersion> packageFiles(String baseUrl, String packageName) {
        String normalized = normalizePackageName(packageName);
        URI uri = SidecarUriUtils.combine(URI.create(baseUrl), "/" + SidecarUriUtils.encodeSegment(normalized)
                + "/json");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the pypiserver sidecar to list versions of "
                    + packageName, e);
        }
        if (response.statusCode() == 404) {
            return Set.of();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not list versions of " + packageName + " on the pypiserver "
                    + "sidecar: upstream returned " + response.statusCode());
        }
        JsonNode releases;
        try {
            releases = mapper.readTree(response.body()).path("releases");
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse pypiserver's version listing for " + packageName, e);
        }
        Set<PkgNameVersion> files = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> release : releases.properties()) {
            String version = release.getKey();
            for (JsonNode file : release.getValue()) {
                String url = file.path("url").asText("");
                String filename = url.substring(url.lastIndexOf('/') + 1);
                files.add(new PkgNameVersion(rawPkgname(filename, version), version));
            }
        }
        return files;
    }

    /**
     * The exact, unnormalized name pypiserver's own {@code remove_pkg} needs for {@code filename}, at
     * known version {@code version}: the text of {@code filename} up to (but not including) the
     * {@code -<version>} marker that {@link #versionBoundaryEndsAt} confirms really ends the version
     * field there, the same boundary {@link #filenameEmbedsVersion} already relies on. Falls back to
     * the whole filename in the (not expected in practice) case nothing satisfies that boundary: a
     * harmless no-op for {@code remove_pkg}, since no file was ever uploaded under that literal name.
     */
    private static String rawPkgname(String filename, String version) {
        String marker = "-" + version;
        for (int idx = filename.indexOf(marker); idx >= 0; idx = filename.indexOf(marker, idx + 1)) {
            if (versionBoundaryEndsAt(filename, idx + marker.length())) {
                return filename.substring(0, idx);
            }
        }
        return filename;
    }

    private void removeVersion(String baseUrl, String pkgname, String version) {
        String body = "%3Aaction=remove_pkg"
                + "&name=" + URLEncoder.encode(pkgname, StandardCharsets.UTF_8)
                + "&version=" + URLEncoder.encode(version, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the pypiserver sidecar to delete " + pkgname + " "
                    + version, e);
        }
        // 404 means remove_pkg found nothing for this exact name/version: already removed by an
        // earlier call in this same loop for a sibling file that happened to share it, not a real
        // failure.
        if (response.statusCode() != 200 && response.statusCode() != 404) {
            throw new IllegalStateException("Could not delete " + pkgname + " " + version + " from the "
                    + "pypiserver sidecar: upstream returned " + response.statusCode());
        }
    }

    /**
     * pypiserver's own root simple index (confirmed live: {@code GET /simple/} with no project
     * name lists every registered project, each as an {@code <a href="project-name/">} entry,
     * the same href shape {@link #SIMPLE_INDEX_HREF} already parses for one project's own files).
     * Each href is already the PEP 503-normalized name real pypiserver registers it under
     * (confirmed: it never shows the originally-published casing/separators), matching what
     * {@code CodeArtifactService}'s own {@code accountingPackageName} already normalizes pypi
     * names to, so no further normalization is needed here. PyPI packages have no namespace.
     *
     * <p>Raised by hectorvent's review: every CodeArtifact repository gets a pypi sidecar id at
     * creation regardless of whether it is ever actually used through pypi, so without checking
     * {@link PypiserverSidecarManager#isRunning} first, a {@code ListPackages} call with no format
     * filter would start this repository's own pypiserver container just to find it empty, every
     * time, for a repository that has never published anything through pypi.
     */
    @Override
    public List<PackageIdentifier> listPackages(String repositoryContainerId, String domain, String repository) {
        if (!manager.isRunning(repositoryContainerId)) {
            return List.of();
        }
        String baseUrl = ensureReady(repositoryContainerId, null);
        URI uri = SidecarUriUtils.combine(URI.create(baseUrl), "/simple/");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the pypiserver sidecar to list packages", e);
        }
        if (response.statusCode() == 404) {
            return List.of();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not list packages on the pypiserver sidecar: upstream returned "
                    + response.statusCode());
        }
        List<PackageIdentifier> result = new ArrayList<>();
        SIMPLE_INDEX_HREF.matcher(response.body()).results().forEach(match -> {
            String href = match.group(1);
            String name = href.endsWith("/") ? href.substring(0, href.length() - 1) : href;
            result.add(new PackageIdentifier(null, name));
        });
        return result;
    }

    private Optional<String> simpleIndex(String baseUrl, String packageName) {
        String normalized = normalizePackageName(packageName);
        URI uri = SidecarUriUtils.combine(URI.create(baseUrl), "/simple/" + SidecarUriUtils.encodeSegment(normalized)
                + "/");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the pypiserver sidecar to look up " + packageName, e);
        }
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not look up " + packageName + " on the pypiserver sidecar: "
                    + "upstream returned " + response.statusCode());
        }
        return Optional.of(response.body());
    }

    private boolean packageIndexListsAsset(String baseUrl, String packageName, String assetName) {
        Optional<String> index = simpleIndex(baseUrl, packageName);
        if (index.isEmpty()) {
            return false;
        }
        // An exact entry match, not a substring search: a shorter real filename that happens to
        // be a substring of a different, longer real filename must not pass this check.
        return SIMPLE_INDEX_HREF.matcher(index.get()).results()
                .map(match -> {
                    String href = match.group(1);
                    String filename = href.substring(href.lastIndexOf('/') + 1);
                    int fragment = filename.indexOf('#');
                    return fragment < 0 ? filename : filename.substring(0, fragment);
                })
                .anyMatch(assetName::equals);
    }

    /**
     * PEP 440 multi-letter keywords that can introduce a continuation after a {@code -} or
     * {@code .}, per the {@code packaging} library's own {@code VERSION_PATTERN}: {@code
     * alpha}/{@code beta}/{@code preview}/{@code pre}/{@code rc} are all pre-release aliases,
     * {@code post}/{@code rev} are post-release aliases, {@code dev} is the dev-release keyword.
     * The matching single-letter aliases ({@code a}, {@code b}, {@code c}, {@code r}) are handled
     * separately in {@link #versionBoundaryEndsAt}, since a bare letter needs a following digit
     * to be treated as a continuation rather than an unrelated filename extension starting there.
     */
    private static final List<String> PEP440_CONTINUATION_PREFIXES =
            List.of("alpha", "beta", "preview", "pre", "post", "rev", "dev", "rc");

    /**
     * Real PyPI filenames always embed their own version right after a {@code -} (PEP 427/517
     * naming: {@code <name>-<version>.<ext>} for an sdist, {@code <name>-<version>-<tags>.whl}
     * for a wheel), so this is checked directly against the filename rather than trusted from the
     * caller's {@code version} argument alone, which a real file from a different version of the
     * same package would otherwise sail straight through.
     *
     * <p>Checks every {@code -<version>} occurrence and requires both that what follows it
     * actually ends the version field, handled by {@link #versionBoundaryEndsAt} (requesting
     * version {@code 1.0} against a real {@code pkg-1.0.1-py3-none-any.whl} contains the literal
     * text {@code -1.0.}, even though the file's actual version is {@code 1.0.1}, not {@code
     * 1.0}), and that what precedes it is exactly {@code packageName}, normalized the same way
     * the simple-index lookup normalizes it. Without that second check, a package name that
     * itself contains a run of digits could produce a false match inside the name portion of the
     * filename instead of the real version field: a package literally named {@code foo-1.0-bar}
     * at version {@code 2.0} produces {@code foo-1.0-bar-2.0.tar.gz}, and the text {@code -1.0-}
     * inside that name would otherwise look like a complete, valid version {@code 1.0} to a
     * caller who asked for the wrong one.
     *
     * <p>Not {@code private} for exactly one other reason (Greptile's review of this PR):
     * {@code CodeArtifactPypiController#upload} calls this on the way in, the opposite direction
     * from every other caller here. pypiserver itself files an upload under the identity this
     * same boundary logic derives from its filename, never from the multipart form's own
     * {@code name}/{@code version} fields; those fields are only ever used, until this check
     * existed, to pick which coordinate the Archived/Disposed guard and the override-clearing
     * call afterward run against. A form whose fields name an unrelated (unused, never
     * Archived/Disposed) coordinate while the real filename matches a disposed one would let the
     * guard see the harmless coordinate, the real upload land under the disposed one regardless,
     * and the override-clearing call afterward operate on the wrong version too.
     */
    static boolean filenameEmbedsVersion(String assetName, String packageName, String version) {
        String normalizedPackageName = normalizePackageName(packageName);
        String marker = "-" + version;
        for (int idx = assetName.indexOf(marker); idx >= 0; idx = assetName.indexOf(marker, idx + 1)) {
            String prefix = assetName.substring(0, idx);
            if (normalizePackageName(prefix).equals(normalizedPackageName)
                    && versionBoundaryEndsAt(assetName, idx + marker.length())) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code after} is where a candidate version match ends; true when the version field genuinely
     * ends there rather than continuing as a longer PEP 440 version. A {@code +} never does: PEP
     * 440 gives it exactly one meaning, introducing a non-empty local-version segment, so whatever
     * immediately follows it is always part of a longer, different version (e.g. {@code 1.0+linux}
     * is not {@code 1.0}), never an unrelated filename extension starting there.
     *
     * <p>A {@code -} or a {@code .} might not end it either, and both need the same continuation
     * check: PEP 440 allows either character to separate a release segment from a following
     * {@code <release segment>} ({@code 1.0.1}), a pre/post/dev-release suffix (every keyword in
     * {@link #PEP440_CONTINUATION_PREFIXES}, plus the single-letter aliases checked below), or,
     * only after a {@code -} specifically, an implicit post-release with no keyword at all
     * ({@code 1.0-1} means {@code 1.0.post1}) <em>unless</em> {@link #isWheelBuildTag} says the
     * digits are actually a PEP 427 wheel build tag instead, which looks identical but does end
     * the version right there. All of those start right after the matched text the same way, so
     * both separators are checked for and rejected as real continuations.
     */
    private static boolean versionBoundaryEndsAt(String assetName, int after) {
        if (after == assetName.length()) {
            return true;
        }
        char next = assetName.charAt(after);
        if (next == '+' || (next != '-' && next != '.')) {
            return false;
        }
        String remainder = assetName.substring(after + 1);
        if (remainder.isEmpty()) {
            return true;
        }
        if (Character.isDigit(remainder.charAt(0))) {
            return next == '-' && isWheelBuildTag(assetName, remainder);
        }
        for (String continuation : PEP440_CONTINUATION_PREFIXES) {
            if (remainder.startsWith(continuation)) {
                return false;
            }
        }
        // Single-letter PEP 440 aliases (a/alpha, b/beta, c/rc, r/rev/post) need a following
        // digit to count as a continuation; without one, a bare letter is far more likely to be
        // the start of an unrelated filename extension than a pre/post-release marker.
        char first = remainder.charAt(0);
        boolean singleLetterAlias = first == 'a' || first == 'b' || first == 'c' || first == 'r';
        return !(singleLetterAlias && remainder.length() > 1 && Character.isDigit(remainder.charAt(1)));
    }

    /**
     * {@code remainder} is known to start with a digit, right after a {@code -}. PEP 440's
     * implicit, keyword-less post-release numeral ({@code 1.0-1} meaning {@code 1.0.post1}) and
     * PEP 427's wheel build tag ({@code digit+ letter*}, placed between the version and the
     * python/abi/platform tags, e.g. {@code 1.0-1-py3-none-any.whl}) look identical at this
     * point: both are a bare run of digits (optionally followed by letters) right after the
     * version. They're told apart by what comes after that run: a build tag is always followed
     * by another {@code -} introducing the next wheel tag, since wheels always have those three
     * trailing tags, whereas a post-release numeral is followed directly by the file extension.
     * Only a {@code .whl} filename can have a build tag at all; an sdist's {@code -<digits>} has
     * no other valid meaning than the post-release numeral.
     */
    private static boolean isWheelBuildTag(String assetName, String remainder) {
        if (!assetName.endsWith(".whl")) {
            return false;
        }
        int i = 0;
        while (i < remainder.length() && Character.isDigit(remainder.charAt(i))) {
            i++;
        }
        while (i < remainder.length() && Character.isLetter(remainder.charAt(i))) {
            i++;
        }
        return i < remainder.length() && remainder.charAt(i) == '-';
    }
}
